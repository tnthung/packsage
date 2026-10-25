package dev.packsage.journal;

import dev.packsage.knowledge.Evidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class JournalLossTest {
    @TempDir
    Path dir;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void markerFailureRetainsEarlierEventBoundsAndRetriesExactlyOnce(boolean overflow) throws Exception {
        var file = dir.resolve("retry.db");
        var wall = new AtomicLong();
        var game = new AtomicLong();
        var stats = new JournalStats();
        var queue = new EventQueue(overflow ? 0 : 10, 10);
        var writer = new JournalWriter(() -> JournalStore.open(file), queue, stats, game::get, wall::get, 60_000);
        try (var store = writer.openNow()) {
            wall.set(1_000);
            game.set(20);
            writer.drainOnce();
            try (var statement = store.writer().createStatement()) {
                statement.execute("CREATE TEMP TRIGGER fail_markers BEFORE INSERT ON recording_losses BEGIN SELECT RAISE(ABORT, 'marker refused'); END");
                if (!overflow) statement.execute("CREATE TEMP TRIGGER fail_events BEFORE INSERT ON events BEGIN SELECT RAISE(ABORT, 'event refused'); END");
            }
            queue.offer(new JournalEvent(EventType.BREAK, 10, 500, "minecraft:overworld", "player:A",
                    "minecraft:sand", null, 1, new long[]{Positions.pack(0, 64, 0)}, null));
            wall.set(2_000);
            game.set(40);
            writer.drainOnce();
            var page = new JournalSearch(store, stats).search(new JournalFilter(500L, 600L, null, null,
                    Set.of(), List.of(), null, false), null, 2_000);
            assertEquals(Evidence.Coverage.PARTIAL, page.coverage());
            assertTrue(page.note().contains("awaiting persistence"), page.note());
            try (var statement = store.writer().createStatement()) {
                try (var rows = statement.executeQuery("SELECT COUNT(*) FROM recording_losses")) {
                    rows.next();
                    assertEquals(0, rows.getLong(1));
                }
                statement.execute("DROP TRIGGER fail_markers");
            }
            writer.drainOnce();
            writer.drainOnce();
            try (var statement = store.writer().createStatement(); var rows = statement.executeQuery(
                    "SELECT COUNT(*), SUM(count), MIN(first_wall), MIN(first_game_time) FROM recording_losses")) {
                rows.next();
                assertEquals(1, rows.getLong(1));
                assertEquals(1, rows.getLong(2));
                assertEquals(500, rows.getLong(3));
                assertEquals(10, rows.getLong(4));
            }
            store.endSession(store.sessions().getFirst().id(), 2_000, 40);
        }
        try (var store = JournalStore.open(file)) {
            store.startSession(2_000, 40);
            var search = new JournalSearch(store, new JournalStats());
            assertEquals(Evidence.Coverage.PARTIAL, search.search(new JournalFilter(500L, 600L, null, null,
                    Set.of(), List.of(), null, false), null, 3_000).coverage());
            assertEquals(Evidence.Coverage.COMPLETE, search.search(new JournalFilter(1L, 499L, null, null,
                    Set.of(), List.of(), null, false), null, 3_000).coverage());
        }
    }

    @Test
    void committedMarkerAdvancesCheckpointEvenWhenAutoCommitRestoreFails() throws Exception {
        var stats = new JournalStats();
        var writer = new JournalWriter(() -> JournalStore.open(dir.resolve("commit.db")), new EventQueue(10, 10),
                stats, () -> 0, () -> 0, 60_000);
        try (var store = writer.openNow()) {
            var connection = store.writer();
            var armed = new java.util.concurrent.atomic.AtomicBoolean(true);
            var field = JournalStore.class.getDeclaredField("writer");
            field.setAccessible(true);
            field.set(store, java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(),
                    new Class<?>[]{java.sql.Connection.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("setAutoCommit") && Boolean.TRUE.equals(arguments[0]) && armed.getAndSet(false)) {
                            throw new java.sql.SQLException("auto-commit restore refused");
                        }
                        try {
                            return method.invoke(connection, arguments);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    }));
            stats.hookFailures.increment();
            writer.drainOnce();
            assertEquals(1, stats.lossCheckpoint.counts()[2], "acknowledge the commit before restoring auto-commit");
            writer.drainOnce();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*), SUM(count) FROM recording_losses")) {
                rows.next();
                assertEquals(1, rows.getLong(1));
                assertEquals(1, rows.getLong(2));
            }
        }
    }

    @Test
    void sustainedSameKindLossesMergeWithinTheSession() throws Exception {
        var stats = new JournalStats();
        var wall = new AtomicLong();
        var writer = new JournalWriter(() -> JournalStore.open(dir.resolve("merged.db")), new EventQueue(10, 10),
                stats, () -> 0, wall::get, 60_000);
        try (var store = writer.openNow()) {
            for (int i = 1; i <= 100; i++) {
                wall.set(i * 1_000L);
                stats.hookFailures.increment();
                writer.drainOnce();
            }
            try (var statement = store.writer().createStatement(); var rows = statement.executeQuery(
                    "SELECT COUNT(*), SUM(count), MIN(first_wall), MAX(last_wall) FROM recording_losses")) {
                rows.next();
                assertEquals(1, rows.getLong(1));
                assertEquals(100, rows.getLong(2));
                assertEquals(0, rows.getLong(3));
                assertEquals(100_000, rows.getLong(4));
            }
        }
    }

    @Test
    void markerFailureLogsPersistenceFailureWithoutClaimingABatchWasLost() throws Exception {
        var messages = new java.util.ArrayList<String>();
        var logger = (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getLogger("PackSage");
        var appender = new org.apache.logging.log4j.core.appender.AbstractAppender("journal-marker-test", null, null, true,
                org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
            @Override
            public void append(org.apache.logging.log4j.core.LogEvent event) {
                messages.add(event.getMessage().getFormattedMessage());
            }
        };
        appender.start();
        logger.addAppender(appender);
        try {
            var stats = new JournalStats();
            var writer = new JournalWriter(() -> JournalStore.open(dir.resolve("logging.db")), new EventQueue(10, 10),
                    stats, () -> 0, () -> 0, 60_000);
            try (var store = writer.openNow(); var statement = store.writer().createStatement()) {
                statement.execute("CREATE TEMP TRIGGER fail_markers BEFORE INSERT ON recording_losses BEGIN SELECT RAISE(ABORT, 'marker refused'); END");
                stats.hookFailures.increment();
                writer.drainOnce();
                assertTrue(messages.stream().anyMatch(message -> message.contains("loss marker persistence failed")), messages.toString());
                assertFalse(messages.stream().anyMatch(message -> message.contains("journal batch failed")), messages.toString());
            }
        } finally {
            logger.removeAppender(appender);
            appender.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
    void recordingLossRemainsPartialAfterCleanRestart(int kind) throws Exception {
        var file = dir.resolve("journal.db");
        var wall = new AtomicLong();
        var game = new AtomicLong();
        var stats = new JournalStats();
        var queue = new EventQueue(kind == 6 ? 0 : 10, 10);
        var writer = new JournalWriter(() -> JournalStore.open(file), queue, stats, game::get, wall::get, 60_000);
        writer.start();
        writer.ready().get(10, TimeUnit.SECONDS);
        var filter = new JournalFilter(400L, 600L, null, null, Set.of(), List.of(), null, false);
        try {
            wall.set(1_000);
            game.set(20);
            if (kind == 0 || kind == 6) {
                queue.offer(new JournalEvent(EventType.BREAK, 10, 500, "minecraft:overworld", "player:A",
                        "minecraft:sand", null, 1, new long[]{Positions.pack(0, 64, 0)}, null));
            }
            writer.submit(store -> {
                if (kind == 0) {
                    try (var statement = store.writer().createStatement()) {
                        statement.execute("CREATE TEMP TRIGGER fail_events BEFORE INSERT ON events BEGIN SELECT RAISE(ABORT, 'disk full'); END");
                    }
                }
                switch (kind) {
                    case 1 -> stats.hookFailures.increment();
                    case 2 -> stats.droppedExplosionPositions.add(3);
                    case 3 -> stats.unreadExplosionPositions.add(4);
                    case 4 -> stats.trackerEvictions.increment();
                    case 5 -> stats.oversizedSnapshots.increment();
                }
                writer.drainOnce();
                assertEquals(Evidence.Coverage.PARTIAL, new JournalSearch(store, stats).search(filter, null, 1_000).coverage());
                return null;
            }).get(10, TimeUnit.SECONDS);
            wall.set(2_000);
            game.set(40);
        } finally {
            writer.close().get(10, TimeUnit.SECONDS);
        }
        try (var store = JournalStore.open(file)) {
            store.startSession(2_000, 40);
            var search = new JournalSearch(store, new JournalStats());
            var page = search.search(filter, null, 3_000);
            assertEquals(Evidence.Coverage.PARTIAL, page.coverage(), "loss kind " + kind + " was forgotten on restart");
            assertTrue(page.note().contains("recording loss"), page.note());
            assertEquals(Evidence.Coverage.COMPLETE, search.search(new JournalFilter(1_001L, 1_999L,
                    null, null, Set.of(), List.of(), null, false), null, 3_000).coverage());
        }
    }

    @Test
    void openingAnExistingV1DatabaseAddsLossStorageWithoutChangingItsDataOrVersion() throws Exception {
        var file = dir.resolve("v1.db");
        try (var store = JournalStore.open(file)) {
            store.startSession(0, 0);
            store.putNote(new Note("n00000001", "old note", null, List.of(), null, 10, 1, 10, null, null));
            try (var statement = store.writer().createStatement()) {
                statement.execute("DROP TABLE IF EXISTS recording_losses");
            }
        }
        try (var store = JournalStore.open(file)) {
            assertEquals(1, store.schemaVersion());
            assertEquals("old note", store.notes().getFirst().text());
            assertEquals(1, store.sessions().size());
            assertEquals(0, store.read(connection -> {
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM recording_losses")) {
                    rows.next();
                    return rows.getInt(1);
                }
            }).intValue());
        }
    }
}
