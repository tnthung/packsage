package dev.packsage.journal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JournalWriterTest {
    private static final String OVERWORLD = "minecraft:overworld";

    @TempDir
    Path dir;

    private final AtomicLong gameTime = new AtomicLong();
    private final JournalStats stats = new JournalStats();

    @Test
    void oneDrainWritesOneTransaction() throws Exception {
        var queue = new EventQueue(EventQueue.CAPACITY, EventQueue.OVERFLOW_KEYS);
        var writer = writer(queue);
        var store = writer.openNow();
        for (int i = 0; i < 2_000; i++) {
            queue.offer(sand(i, i));
        }
        var before = store.transactions();
        assertEquals(2_000, writer.drainOnce());
        assertEquals(before + 1, store.transactions());
        assertEquals(1, longValue(store, "SELECT COUNT(*) FROM events"));
        assertEquals(2_000, longValue(store, "SELECT count FROM events"));
        assertEquals(2_000, stats.written.sum());
        store.close();
    }

    @Test
    void anExtendedRunUpdatesItsRowAndBox() throws Exception {
        var queue = new EventQueue(EventQueue.CAPACITY, EventQueue.OVERFLOW_KEYS);
        var writer = writer(queue);
        var store = writer.openNow();
        for (int i = 0; i < 1_000; i++) {
            queue.offer(sand(i, i));
        }
        writer.drainOnce();
        assertEquals(999, longValue(store, "SELECT max_x FROM events"));
        for (int i = 1_000; i < 2_000; i++) {
            queue.offer(sand(i, i));
        }
        writer.drainOnce();
        assertEquals(1, longValue(store, "SELECT COUNT(*) FROM events"));
        assertEquals(2_000, longValue(store, "SELECT count FROM events"));
        assertEquals(1_999, longValue(store, "SELECT max_x FROM events"));
        assertEquals(1, longValue(store, "SELECT COUNT(*) FROM events_box"));
        assertEquals(0, longValue(store, "SELECT min_x FROM events_box"));
        assertEquals(1_999, longValue(store, "SELECT max_x FROM events_box"));
        assertEquals(2_000, Positions.decode(blob(store, "SELECT positions FROM events")).length);
        store.close();
    }

    @Test
    void overflowBecomesCountOnlyRows() throws Exception {
        var queue = new EventQueue(10, EventQueue.OVERFLOW_KEYS);
        var writer = writer(queue);
        var store = writer.openNow();
        for (int i = 0; i < 30; i++) {
            queue.offer(sand(i, i));
        }
        assertEquals(10, writer.drainOnce());
        assertEquals(2, longValue(store, "SELECT COUNT(*) FROM events"));
        assertEquals(10, longValue(store, "SELECT count FROM events WHERE count_only = 0"));
        assertEquals(20, longValue(store, "SELECT count FROM events WHERE count_only = 1"));
        assertEquals(1, longValue(store, "SELECT COUNT(*) FROM events WHERE count_only = 1"
                + " AND at_x IS NULL AND min_x IS NULL AND max_x IS NULL AND positions IS NULL"));
        assertEquals(1, longValue(store, "SELECT COUNT(*) FROM events_box"));
        assertEquals(longValue(store, "SELECT id FROM events WHERE count_only = 0"), longValue(store, "SELECT id FROM events_box"));
        assertEquals(20, stats.overflowed.sum());
        assertEquals(30, stats.written.sum());
        store.close();
    }

    @Test
    void aSecondEntryIntoTheSameStructureStartIsNotWritten() throws Exception {
        var file = dir.resolve("journal.db");
        var queue = new EventQueue(EventQueue.CAPACITY, EventQueue.OVERFLOW_KEYS);
        var writer = writer(queue, file);
        var store = writer.openNow();
        queue.offer(structure(10));
        writer.drainOnce();
        queue.offer(structure(500));
        writer.drainOnce();
        assertEquals(1, longValue(store, "SELECT COUNT(*) FROM events WHERE type = 'structure'"));
        store.close();

        var reopenedQueue = new EventQueue(EventQueue.CAPACITY, EventQueue.OVERFLOW_KEYS);
        var reopened = writer(reopenedQueue, file);
        var again = reopened.openNow();
        reopenedQueue.offer(structure(9_000));
        reopened.drainOnce();
        assertEquals(1, longValue(again, "SELECT COUNT(*) FROM events WHERE type = 'structure'"));
        assertEquals(1, longValue(again, "SELECT COUNT(*) FROM structure_visits"));
        again.close();
    }

    @Test
    void closeFlushesQueuedEventsAndOpenRuns() throws Exception {
        var file = dir.resolve("journal.db");
        var queue = new EventQueue(100, EventQueue.OVERFLOW_KEYS);
        var writer = new JournalWriter(() -> JournalStore.open(file), queue, stats, gameTime::get, () -> 5_000L, 60_000);
        writer.start();
        for (int i = 0; i < 500; i++) {
            queue.offer(event(i % 2 == 0 ? "minecraft:sand" : "minecraft:gravel", i, i));
        }
        writer.close().get(30, TimeUnit.SECONDS);
        assertFalse(queue.offer(sand(600, 0)));
        try (var store = JournalStore.open(file)) {
            assertEquals(500, longValue(store, "SELECT SUM(count) FROM events"));
            var sessions = store.sessions();
            assertEquals(1, sessions.size());
            assertNotNull(sessions.get(0).endedWall());
        }
    }

    @Test
    void storeWritesOffTheWriterThreadAreRefused() throws Exception {
        var queue = new EventQueue(EventQueue.CAPACITY, EventQueue.OVERFLOW_KEYS);
        var writer = writer(queue);
        writer.start();
        var store = writer.ready().get(30, TimeUnit.SECONDS);
        assertThrows(IllegalStateException.class, () -> store.writeBatch(List.of(), List.of()));
        assertThrows(IllegalStateException.class, () -> store.endSession(1, 0, 0));
        assertEquals(1, writer.submit(s -> {
            s.writeBatch(List.of(), List.of());
            return 1;
        }).get(30, TimeUnit.SECONDS));
        writer.close().get(30, TimeUnit.SECONDS);
    }

    @Test
    void aFailingBatchIsCountedAndTheWriterContinues() throws Exception {
        var queue = new EventQueue(EventQueue.CAPACITY, EventQueue.OVERFLOW_KEYS);
        var file = dir.resolve("journal.db");
        var writer = writer(queue, file);
        var store = writer.openNow();
        try (var statement = store.writer().createStatement()) {
            statement.execute("CREATE TEMP TRIGGER fail_once BEFORE INSERT ON events BEGIN SELECT RAISE(ABORT, 'disk is full'); END");
        }
        for (int i = 0; i < 300; i++) {
            queue.offer(sand(i, i));
        }
        assertEquals(300, writer.drainOnce());
        assertEquals(300, stats.failedEvents.sum());
        assertEquals(0, longValue(store, "SELECT COUNT(*) FROM events"));
        try (var statement = store.writer().createStatement()) {
            statement.execute("DROP TRIGGER fail_once");
        }
        for (int i = 300; i < 500; i++) {
            queue.offer(sand(i, i));
        }
        assertEquals(200, writer.drainOnce());
        assertEquals(300, stats.failedEvents.sum());
        assertEquals(200, stats.written.sum());
        assertEquals(200, longValue(store, "SELECT SUM(count) FROM events"));
        assertNull(nullableLong(store, "SELECT id FROM events WHERE count <> 200"));
        store.close();
    }

    private JournalWriter writer(EventQueue queue) {
        return writer(queue, dir.resolve("journal.db"));
    }

    private JournalWriter writer(EventQueue queue, Path file) {
        return new JournalWriter(() -> JournalStore.open(file), queue, stats, gameTime::get, () -> 5_000L, 1_000);
    }

    private static long longValue(JournalStore store, String sql) throws SQLException {
        var value = nullableLong(store, sql);
        assertNotNull(value, sql);
        return value;
    }

    private static Long nullableLong(JournalStore store, String sql) throws SQLException {
        try (var statement = store.reader().createStatement(); var result = statement.executeQuery(sql)) {
            if (!result.next()) {
                return null;
            }
            var value = result.getLong(1);
            return result.wasNull() ? null : value;
        }
    }

    private static byte[] blob(JournalStore store, String sql) throws SQLException {
        try (var statement = store.reader().createStatement(); var result = statement.executeQuery(sql)) {
            result.next();
            return result.getBytes(1);
        }
    }

    private JournalEvent sand(long tick, int x) {
        return event("minecraft:sand", tick, x);
    }

    private JournalEvent event(String subject, long tick, int x) {
        gameTime.set(Math.max(gameTime.get(), tick));
        return new JournalEvent(EventType.BREAK, tick, 1_000L + tick * 50, OVERWORLD, "player:A", subject, null, 1,
                new long[]{Positions.pack(x, 64, 0)}, null);
    }

    private JournalEvent structure(long tick) {
        gameTime.set(Math.max(gameTime.get(), tick));
        return new JournalEvent(EventType.STRUCTURE, tick, 1_000L + tick * 50, OVERWORLD, "player:A",
                "minecraft:village_plains", "3,-7", 1, new long[]{Positions.pack(48, 70, -112)}, null);
    }
}
