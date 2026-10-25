package dev.packsage.journal;

import dev.packsage.world.BlockPlace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JournalStoreTest {
    @TempDir
    Path dir;

    @Test
    void notesRoundTripAcrossReopen() throws Exception {
        var file = dir.resolve("journal.db");
        var original = new Note("n00000001", "iron farm", "I built an iron farm here", List.of("build", "farm"),
                new BlockPlace("minecraft:overworld", -40, 70, 8), 10, 200, 10, "chat1", "request1");
        var other = new Note("n00000002", "beat the Wither", null, List.of(), null, 20, 300, 20, null, null);
        try (var store = JournalStore.open(file)) {
            store.startSession(0, 0);
            store.putNote(original);
            store.putNote(other);
        }
        var updated = NoteBook.apply(original, NoteInput.validated("my iron farm moved", new NoteInput.Clear(), List.of()),
                30, "chat2", "request2");
        try (var store = JournalStore.open(file)) {
            store.startSession(0, 0);
            assertEquals(List.of(other, original), store.notes());
            store.putNote(updated);
            store.deleteNote(other.id());
            assertEquals(List.of(updated), store.notes());
            var found = new JournalSearch(store).search(new JournalFilter(null, null, null, null, Set.of("note"),
                    List.of(), "moved", true), null, 100);
            assertEquals(updated, ((JournalEntry.NoteEntry) found.items().getFirst()).note());
        }
        try (var store = JournalStore.open(file)) {
            store.startSession(0, 0);
            assertEquals(List.of(updated), store.notes());
            store.deleteNote(updated.id());
        }
        try (var store = JournalStore.open(file)) {
            assertTrue(store.notes().isEmpty());
        }
    }

    @Test
    void noteWritesRunOnTheWriterThread() throws Exception {
        var writer = new JournalWriter(() -> JournalStore.open(dir.resolve("journal.db")),
                new EventQueue(EventQueue.CAPACITY, EventQueue.OVERFLOW_KEYS), new JournalStats(), () -> 0, () -> 0, 1_000);
        writer.start();
        try {
            var store = writer.ready().get(5, TimeUnit.SECONDS);
            var note = new Note("n00000001", "old base", "old base", List.of(), null, 10, 20, 10, null, null);
            assertThrows(IllegalStateException.class, () -> store.putNote(note));
            assertThrows(IllegalStateException.class, () -> store.deleteNote(note.id()));
            writer.submit(open -> {
                open.putNote(note);
                return null;
            }).get(5, TimeUnit.SECONDS);
            assertEquals(List.of(note), store.notes());
            writer.submit(open -> {
                open.deleteNote(note.id());
                return null;
            }).get(5, TimeUnit.SECONDS);
            assertTrue(store.notes().isEmpty());
        } finally {
            writer.close().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void opensAWalDatabaseAndCreatesSchemaVersionOne() throws Exception {
        var file = dir.resolve("w/packsage/journal.db");
        try (var store = JournalStore.open(file)) {
            assertTrue(Files.isDirectory(file.getParent()));
            try (var statement = store.reader().createStatement();
                 var result = statement.executeQuery("PRAGMA journal_mode")) {
                assertTrue(result.next());
                assertEquals("wal", result.getString(1));
            }
            assertEquals(1, store.schemaVersion());
            var tables = new ArrayList<String>();
            try (var statement = store.reader().createStatement();
                 var result = statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")) {
                while (result.next()) {
                    tables.add(result.getString(1));
                }
            }
            assertTrue(tables.containsAll(List.of("meta", "sessions", "events", "events_box", "structure_visits", "notes")), tables::toString);
        }
    }

    @Test
    void openingExistingVersionOneAddsTheCountOnlyIndexWithoutLosingData() throws Exception {
        var file = dir.resolve("journal.db");
        try (var store = JournalStore.open(file); var statement = store.writer().createStatement()) {
            store.startSession(1_000, 20);
            statement.executeUpdate("DROP INDEX IF EXISTS events_count_only_time");
        }
        for (int i = 0; i < 2; i++) {
            try (var store = JournalStore.open(file); var statement = store.reader().createStatement();
                 var rows = statement.executeQuery("SELECT sql FROM sqlite_master WHERE type = 'index' AND name = 'events_count_only_time'")) {
                assertEquals(1, store.schemaVersion());
                assertEquals(1_000, store.meta().createdWall());
                assertEquals(1, store.sessions().size());
                assertTrue(rows.next());
                assertTrue(rows.getString(1).contains("WHERE count_only = 1"));
            }
        }
    }

    @Test
    void integerRtreeIsAvailable() throws Exception {
        try (var store = JournalStore.open(dir.resolve("journal.db"))) {
            try (var statement = store.writer().createStatement()) {
                statement.executeUpdate("INSERT INTO events_box VALUES (1, -5, 5, 60, 70, -5, 5)");
            }
            var ids = new ArrayList<Long>();
            try (var statement = store.reader().createStatement();
                 var result = statement.executeQuery("SELECT id FROM events_box WHERE min_x <= 0 AND max_x >= 0")) {
                while (result.next()) {
                    ids.add(result.getLong(1));
                }
            }
            assertEquals(List.of(1L), ids);
        }
    }

    @Test
    void reopeningKeepsDataAndSessions() throws Exception {
        var file = dir.resolve("journal.db");
        long createdWall;
        try (var store = JournalStore.open(file)) {
            assertNull(store.meta());
            var session = store.startSession(1_000L, 20L);
            store.endSession(session, 2_000L, 420L);
            createdWall = store.meta().createdWall();
            assertEquals(1_000L, createdWall);
            assertEquals(20L, store.meta().createdGameTime());
        }
        try (var store = JournalStore.open(file)) {
            var sessions = store.sessions();
            assertEquals(1, sessions.size());
            var session = sessions.get(0);
            assertEquals(1_000L, session.startedWall());
            assertEquals(20L, session.startedGameTime());
            assertEquals(2_000L, session.endedWall());
            assertEquals(420L, session.endedGameTime());
            assertEquals(createdWall, store.meta().createdWall());
        }
    }

    @Test
    void aNewerSchemaVersionIsRefused() throws Exception {
        var file = dir.resolve("journal.db");
        try (var store = JournalStore.open(file)) {
            try (var statement = store.writer().createStatement()) {
                statement.executeUpdate("UPDATE meta SET value = '2' WHERE key = 'schema_version'");
            }
        }
        var failure = assertThrows(SQLException.class, () -> JournalStore.open(file));
        assertTrue(failure.getMessage().contains("version 2"), failure.getMessage());
    }

    @Test
    void aMetaValueThatIsNotANumberIsReportedAsSqlException() throws Exception {
        try (var store = JournalStore.open(dir.resolve("journal.db"))) {
            store.startSession(1_000L, 20L);
            try (var statement = store.writer().createStatement()) {
                statement.executeUpdate("UPDATE meta SET value = 'soon' WHERE key = 'created_wall'");
            }
            var failure = assertThrows(SQLException.class, store::meta);
            assertTrue(failure.getCause() instanceof NumberFormatException, failure::toString);
        }
    }

    /** Deleting an open SQLite file fails only on Windows, so elsewhere this passes without proving the release. */
    @Test
    void aRefusedOpenReleasesTheFile() throws Exception {
        var file = dir.resolve("journal.db");
        try (var store = JournalStore.open(file)) {
            try (var statement = store.writer().createStatement()) {
                statement.executeUpdate("UPDATE meta SET value = '2' WHERE key = 'schema_version'");
            }
        }
        assertThrows(SQLException.class, () -> JournalStore.open(file));
        for (var suffix : List.of("", "-wal", "-shm")) {
            Files.deleteIfExists(dir.resolve("journal.db" + suffix));
        }
        try (var store = JournalStore.open(file)) {
            assertEquals(1, store.schemaVersion());
        }
    }
}
