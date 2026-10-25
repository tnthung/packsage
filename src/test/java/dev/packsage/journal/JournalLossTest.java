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
