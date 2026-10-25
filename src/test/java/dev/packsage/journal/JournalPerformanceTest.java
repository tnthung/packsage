package dev.packsage.journal;

import dev.packsage.knowledge.AdapterRegistry;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.model.PackSageTools;
import net.minecraft.SharedConstants;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JournalPerformanceTest {
    @TempDir
    Path dir;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void recordingNeverWaitsForAStalledStore() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var file = dir.resolve("stalled.db");
        try (var worker = Executors.newSingleThreadExecutor(); var producer = Executors.newSingleThreadExecutor()) {
            var journal = new Journal(worker, path -> observedStore(path, operation -> {
                // Stall inside writeBatch, after it starts the transaction but before it inserts events.
                if (operation.startsWith("prepareStatement INSERT INTO events(") && release.getCount() != 0) {
                    entered.countDown();
                    try {
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
            }));
            journal.open(file, () -> 0);
            EventQueue queue;
            try {
                journal.record(JournalEvents.broken(new JournalEvents.Stamp(0, 100, "minecraft:overworld"),
                        "player:A", null, "minecraft:stone", 0, 64, 0));
                assertTrue(entered.await(10, TimeUnit.SECONDS), "the writer must be stalled during writeBatch");
                var sessionField = Journal.class.getDeclaredField("session");
                sessionField.setAccessible(true);
                var session = sessionField.get(journal);
                var queueField = session.getClass().getDeclaredField("queue");
                queueField.setAccessible(true);
                queue = (EventQueue) queueField.get(session);
                var event = JournalEvents.broken(new JournalEvents.Stamp(0, 101, "minecraft:overworld"),
                        "player:A", null, "minecraft:sand", 1, 64, 0);
                var timing = producer.submit(() -> {
                    long longest = 0;
                    var started = System.nanoTime();
                    for (int i = 0; i < 50_000; i++) {
                        var before = System.nanoTime();
                        journal.record(event);
                        longest = Math.max(longest, System.nanoTime() - before);
                    }
                    return new long[]{System.nanoTime() - started, longest};
                }).get(2, TimeUnit.SECONDS);
                assertTrue(timing[0] < TimeUnit.MILLISECONDS.toNanos(250), "50,000 records took " + timing[0] + " ns");
                assertTrue(timing[1] < TimeUnit.MILLISECONDS.toNanos(5), "slowest record took " + timing[1] + " ns");
                assertTrue(queue.overflowed() > 0);
                assertEquals(1, release.getCount(), "the store stayed stalled for the whole recording loop");
            } finally {
                release.countDown();
                journal.close().get(10, TimeUnit.SECONDS);
            }
            try (var store = JournalStore.open(file)) {
                var counts = store.read(connection -> {
                    try (var statement = connection.createStatement();
                         var result = statement.executeQuery("SELECT count_only, SUM(count) FROM events"
                                 + " WHERE subject = 'minecraft:sand' GROUP BY count_only")) {
                        var totals = new long[2];
                        while (result.next()) totals[result.getInt(1)] = result.getLong(2);
                        return totals;
                    }
                });
                assertTrue(counts[0] > 0);
                assertEquals(queue.overflowed(), counts[1]);
                assertEquals(0, queue.dropped());
                assertEquals(0, journal.stats().failedEvents());
                assertEquals(50_000, counts[0] + counts[1] + queue.dropped());
                assertEquals(50_001, journal.stats().written.sum(), "includes the separate stone event that stalled the writer");
            }
        }
    }

    @Test
    void twoThousandSandBreaksAreOneRowAndOneTransaction() throws Exception {
        var opening = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var journal = new Journal(worker, file -> {
                opening.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SQLException(e);
                }
                return JournalStore.open(file);
            });
            journal.open(dir.resolve("sand.db"), () -> 0);
            try {
                assertTrue(opening.await(10, TimeUnit.SECONDS));
                for (int i = 0; i < 2_000; i++) {
                    journal.record(JournalEvents.broken(new JournalEvents.Stamp(0, 100, "minecraft:overworld"),
                            "player:A", null, "minecraft:sand", i, 64, 0));
                }
                release.countDown();
                var before = journal.write(JournalStore::transactions).toCompletableFuture().get(10, TimeUnit.SECONDS);
                var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (journal.stats().written.sum() < 2_000 && System.nanoTime() < deadline) Thread.sleep(10);
                assertEquals(2_000, journal.stats().written.sum());
                assertEquals(before + 1, journal.write(JournalStore::transactions).toCompletableFuture().get(10, TimeUnit.SECONDS));
                journal.read(store -> store.read(connection -> {
                    try (var statement = connection.createStatement();
                         var result = statement.executeQuery("SELECT count, count_only, positions FROM events")) {
                        assertTrue(result.next());
                        assertEquals(2_000, result.getLong(1));
                        assertEquals(0, result.getInt(2));
                        assertEquals(2_000, Positions.decode(result.getBytes(3)).length);
                        assertFalse(result.next(), "all breaks must be one row");
                    }
                    return null;
                })).toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertEquals(0, journal.stats().overflowed.sum());
                assertEquals(0, journal.stats().failedEvents());
            } finally {
                release.countDown();
                journal.close().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void serverThreadNeverCallsTheStore() throws Exception {
        var threads = ConcurrentHashMap.<String>newKeySet();
        var field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = field.get(null);
        var integrated = (IntegratedServer) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, IntegratedServer.class);
        var scope = new Evidence.Scope("world", "resources", "team", "runtime", 1);
        var file = dir.resolve("threads.db");
        try (var worker = Executors.newSingleThreadExecutor(task -> new Thread(task, "packsage-worker"));
             var server = Executors.newSingleThreadExecutor(task -> new Thread(task, "Server thread"))) {
            var journal = new Journal(worker, path -> {
                threads.add(Thread.currentThread().getName());
                return observedStore(path, operation -> threads.add(Thread.currentThread().getName()));
            });
            server.submit(() -> journal.open(file, () -> 0)).get(10, TimeUnit.SECONDS);
            try {
                journal.write(store -> null).toCompletableFuture().get(10, TimeUnit.SECONDS);
                server.submit(() -> journal.record(JournalEvents.broken(new JournalEvents.Stamp(0, 100, "minecraft:overworld"),
                        "player:A", null, "minecraft:sand", 1, 64, 0))).get(10, TimeUnit.SECONDS);
                server.submit(() -> journal.search(new JournalFilter(null, null, null, null, Set.of(), List.of(), null, false),
                        null, System.currentTimeMillis())).get(10, TimeUnit.SECONDS).toCompletableFuture().get(10, TimeUnit.SECONDS);
                var tools = new JournalTools(journal, () -> scope, worker, () -> integrated,
                        anchor -> CompletableFuture.failedFuture(new AssertionError("a placeless note needs no live read")));
                var context = new Toolbox.Context(() -> scope, Map.of(), new AdapterRegistry(), new Toolbox.DocumentBudget(), null,
                        new Toolbox.Origin("conversation", "request", "I beat the Wither"));
                server.submit(() -> tools.handle(new PackSageTools.ReadRequest("remember",
                        Evidence.deserializeObject("{\"text\":\"I beat the Wither\"}"), scope), context))
                        .get(10, TimeUnit.SECONDS).toCompletableFuture().get(10, TimeUnit.SECONDS);
            } finally {
                server.submit(journal::close).get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS);
            }
            assertFalse(threads.contains("Server thread"), threads.toString());
            assertEquals(Set.of("packsage-journal", "packsage-worker"), threads);
            try (var store = JournalStore.open(file)) {
                assertEquals("I beat the Wither", store.notes().getFirst().text());
                assertEquals(1, store.notes().size());
                assertEquals(1, new JournalSearch(store).search(new JournalFilter(null, null, null, null,
                        Set.of("break"), List.of(), null, false), null, System.currentTimeMillis()).items().size());
                assertTrue(store.sessions().getFirst().endedWall() != null);
            }
        }
    }

    /** Keep the real SQLite connections and schema, observing every JDBC entry without a production test seam. */
    private static JournalStore observedStore(Path file, Consumer<String> operation) throws SQLException {
        var opened = JournalStore.open(file);
        try {
            var connections = new Connection[2];
            var originals = new Connection[]{opened.writer(), opened.reader()};
            for (int i = 0; i < originals.length; i++) {
                var original = originals[i];
                connections[i] = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                        (proxy, method, args) -> {
                            operation.accept(method.getName() + (args != null && args.length > 0 && args[0] instanceof String sql ? " " + sql : ""));
                            try {
                                return method.invoke(original, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
            }
            var constructor = JournalStore.class.getDeclaredConstructor(Connection.class, Connection.class, long.class);
            constructor.setAccessible(true);
            return constructor.newInstance(connections[0], connections[1], opened.schemaVersion());
        } catch (ReflectiveOperationException | RuntimeException e) {
            opened.close();
            throw new SQLException(e);
        }
    }
}
