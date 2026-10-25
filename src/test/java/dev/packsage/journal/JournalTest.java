package dev.packsage.journal;

import cpw.mods.niofs.union.UnionFileSystem;
import dev.packsage.knowledge.Evidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JournalTest {
    @TempDir
    Path dir;

    @Test
    void recordedEventsAreSearchableAfterAWriterPeriod() throws Exception {
        try (var worker = Executors.newSingleThreadExecutor()) {
            var journal = new Journal(worker);
            journal.open(dir.resolve("journal.db"), () -> 0);
            try {
                journal.record(JournalEvents.broken(new JournalEvents.Stamp(0, 100, "minecraft:overworld"),
                        "player:A", "Alex", "minecraft:sand", 1, 64, 2));
                var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while ((!journal.ready() || journal.stats().written.sum() == 0) && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertTrue(journal.ready());
                var page = journal.search(new JournalFilter(null, null, null, null, Set.of(), List.of(), null, false),
                        null, System.currentTimeMillis()).toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertEquals(1, page.items().size());
                assertEquals("minecraft:sand", ((JournalEntry.EventEntry) page.items().getFirst()).subject());
                journal.stats().failedEvents.increment();
                var partial = journal.search(new JournalFilter(null, null, null, null, Set.of(), List.of(), null, false),
                        null, System.currentTimeMillis()).toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertEquals(Evidence.Coverage.PARTIAL, partial.coverage());
                assertTrue(partial.note().contains("1 events failed"));
                assertTrue(journal.read(store -> Thread.currentThread().getName()).toCompletableFuture()
                        .get(10, TimeUnit.SECONDS).startsWith("pool-"));
                assertEquals("packsage-journal", journal.write(store -> Thread.currentThread().getName())
                        .toCompletableFuture().get(10, TimeUnit.SECONDS));
            } finally {
                journal.close().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void closeReturnsAtOnceAndFlushes() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var journal = new Journal(worker, file -> {
                entered.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new SQLException(e);
                }
                return JournalStore.open(file);
            });
            journal.open(dir.resolve("journal.db"), () -> 0);
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertFalse(journal.ready());
                assertEquals("the journal is still opening", journal.unavailableReason());
                journal.record(JournalEvents.place(new JournalEvents.Stamp(0, 100, "minecraft:overworld"),
                        "player:A", null, "minecraft:stone", 1, 64, 2));
                var closing = journal.close();
                assertFalse(closing.isDone());
                assertFalse(journal.recording());
                assertFalse(journal.ready());
                journal.record(JournalEvents.place(new JournalEvents.Stamp(1, 101, "minecraft:overworld"),
                        "player:A", null, "minecraft:dirt", 1, 64, 2));
                release.countDown();
                closing.get(10, TimeUnit.SECONDS);
                assertEquals(1, journal.stats().written.sum());
            } finally {
                release.countDown();
                journal.close().get(10, TimeUnit.SECONDS);
            }
            try (var store = JournalStore.open(dir.resolve("journal.db"))) {
                assertNotNull(store.sessions().getFirst().endedWall());
            }
        }
    }

    @Test
    void reopeningWhileThePreviousCloseFlushesWaitsForIt() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var opens = new AtomicInteger();
        try (var worker = Executors.newSingleThreadExecutor()) {
            var journal = new Journal(worker, file -> {
                if (opens.incrementAndGet() == 1) {
                    entered.countDown();
                    try {
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        throw new SQLException(e);
                    }
                }
                return JournalStore.open(file);
            });
            var file = dir.resolve("journal.db");
            journal.open(file, () -> 0);
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                journal.record(JournalEvents.broken(new JournalEvents.Stamp(0, 100, "minecraft:overworld"),
                        "player:A", null, "minecraft:sand", 1, 64, 2));
                var firstNotes = journal.notes();
                var firstStats = journal.stats();
                var closing = journal.close();
                journal.open(file, () -> 10);
                assertNotSame(firstNotes, journal.notes());
                assertNotSame(firstStats, journal.stats());
                journal.record(JournalEvents.place(new JournalEvents.Stamp(10, 200, "minecraft:overworld"),
                        "player:A", null, "minecraft:stone", 1, 64, 2));
                assertEquals(1, opens.get());
                assertFalse(closing.isDone());
                release.countDown();
                journal.close().get(10, TimeUnit.SECONDS);
                assertTrue(closing.isDone());
                assertEquals(2, opens.get());
            } finally {
                release.countDown();
                journal.close().get(10, TimeUnit.SECONDS);
            }
            try (var store = JournalStore.open(file)) {
                assertEquals(2, store.sessions().size());
                assertTrue(store.sessions().stream().allMatch(session -> session.endedWall() != null));
                assertEquals(2, new JournalSearch(store).search(new JournalFilter(null, null, null, null,
                        Set.of(), List.of(), null, false), null, System.currentTimeMillis()).items().size());
            }
        }
    }

    @Test
    void unopenableDatabaseLeavesRecordingOffAndToolsUnavailable() throws Exception {
        try (var worker = Executors.newSingleThreadExecutor()) {
            var journal = new Journal(worker);
            journal.open(dir, () -> 0);
            try {
                assertThrows(ExecutionException.class, () -> journal.write(JournalStore::notes).toCompletableFuture().get(10, TimeUnit.SECONDS));
                assertFalse(journal.ready());
                assertFalse(journal.recording());
                assertTrue(journal.unavailableReason().contains(dir.toString()));
                journal.record(JournalEvents.broken(new JournalEvents.Stamp(0, 100, "minecraft:overworld"),
                        "player:A", null, "minecraft:sand", 1, 64, 2));
                assertThrows(ExecutionException.class, () -> journal.read(JournalStore::sessions).toCompletableFuture().get());
            } finally {
                journal.close().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void notesAreLoadedIntoTheNoteBookOnOpen() throws Exception {
        var file = dir.resolve("journal.db");
        var note = new Note("n00000001", "iron farm", "I built an iron farm", List.of("build"), null,
                100, 0, 100, null, null);
        try (var store = JournalStore.open(file)) {
            store.putNote(note);
        }
        try (var worker = Executors.newSingleThreadExecutor()) {
            var journal = new Journal(worker);
            journal.open(file, () -> 0);
            try {
                journal.write(store -> null).toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertTrue(journal.ready());
                assertEquals(List.of(note), journal.notes().all());
            } finally {
                journal.close().get(10, TimeUnit.SECONDS);
            }
            assertTrue(journal.notes().all().isEmpty());
        }
    }

    @Test
    void closeWaitsForAnInFlightReadWithoutBlockingTheCaller() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var journal = new Journal(worker);
            journal.open(dir.resolve("journal.db"), () -> 0);
            journal.write(store -> null).toCompletableFuture().get(10, TimeUnit.SECONDS);
            var reading = journal.read(store -> {
                entered.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new SQLException(e);
                }
                return store.sessions();
            }).toCompletableFuture();
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                var closing = journal.close();
                assertFalse(closing.isDone());
                release.countDown();
                assertEquals(1, reading.get(10, TimeUnit.SECONDS).size());
                closing.get(10, TimeUnit.SECONDS);
            } finally {
                release.countDown();
                journal.close().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void anOldScheduledReadCannotReadANewWorld() throws Exception {
        var task = new AtomicReference<Runnable>();
        var journal = new Journal(task::set);
        journal.open(dir.resolve("first.db"), () -> 0);
        try {
            journal.write(store -> null).toCompletableFuture().get(10, TimeUnit.SECONDS);
            var reading = journal.read(JournalStore::sessions).toCompletableFuture();
            journal.close().get(10, TimeUnit.SECONDS);
            journal.open(dir.resolve("second.db"), () -> 0);
            task.get().run();
            assertThrows(ExecutionException.class, reading::get);
        } finally {
            journal.close().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void aNativeLoadErrorLeavesRecordingOff() throws Exception {
        var journal = new Journal(Runnable::run, file -> {
            throw new UnsatisfiedLinkError("test native load failure");
        });
        journal.open(dir.resolve("journal.db"), () -> 0);
        try {
            assertThrows(ExecutionException.class, () -> journal.write(JournalStore::notes).toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertFalse(journal.ready());
            assertFalse(journal.recording());
            assertTrue(journal.unavailableReason().contains("test native load failure"));
        } finally {
            journal.close().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void systemExitWaitsForThePendingFlushAndBoundsAStuckWriter() throws Exception {
        var classpath = List.of(JournalShutdownProcess.class, Journal.class, org.sqlite.SQLiteConfig.class,
                        org.slf4j.LoggerFactory.class, com.google.gson.JsonObject.class).stream()
                .map(type -> Path.of(java.net.URI.create(type.getProtectionDomain().getCodeSource().getLocation().toString())))
                .map(path -> path.getFileSystem() instanceof UnionFileSystem union ? union.getPrimaryPath().toString() : path.toString())
                .distinct().collect(Collectors.joining(File.pathSeparator));
        var project = Path.of(System.getProperty("user.dir"));
        while (!Files.isDirectory(project.resolve("build/classes/java/test"))) {
            project = project.getParent();
        }
        classpath = project.resolve("build/classes/java/test") + File.pathSeparator
                + project.resolve("build/classes/java/main") + File.pathSeparator + classpath;
        for (var mode : List.of("flush", "stuck")) {
            var file = dir.resolve(mode + ".db");
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                    "-cp", classpath, JournalShutdownProcess.class.getName(), file.toString(), mode)
                    .redirectErrorStream(true).redirectOutput(dir.resolve(mode + ".log").toFile()).start();
            try {
                assertTrue(process.waitFor(20, TimeUnit.SECONDS), classpath + "\n" + Files.readString(dir.resolve(mode + ".log")));
                assertEquals(0, process.exitValue(), Files.readString(dir.resolve(mode + ".log")));
                if (mode.equals("flush")) {
                    try (var store = JournalStore.open(file)) {
                        assertEquals(1, new JournalSearch(store).search(new JournalFilter(null, null, null, null,
                                Set.of(), List.of(), null, false), null, System.currentTimeMillis()).items().size());
                        assertNotNull(store.sessions().getFirst().endedWall());
                    }
                }
            } finally {
                process.destroyForcibly();
            }
        }
    }
}
