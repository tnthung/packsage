package dev.packsage.journal;

import dev.packsage.PackSageLog;
import dev.packsage.journal.JournalStore.SqlFunction;
import dev.packsage.journal.JournalWriter.StoreCommand;
import dev.packsage.world.Page;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Server-thread lifecycle, a background writer per world session, and worker-only reads. */
public final class Journal {
    private static final class Session {
        final EventQueue queue = new EventQueue(EventQueue.CAPACITY, EventQueue.OVERFLOW_KEYS);
        final JournalStats stats = new JournalStats();
        final NoteBook notes = new NoteBook();
        final JournalWriter writer;
        volatile boolean recording = true;
        volatile Throwable failure;

        Session(Path file, LongSupplier gameTime, CompletableFuture<Void> previous, SqlFunction<Path, JournalStore> opener) {
            writer = new JournalWriter(() -> {
                previous.join();
                var store = opener.apply(file);
                try {
                    notes.load(store.notes());
                    return store;
                } catch (Throwable e) {
                    try {
                        store.close();
                    } catch (Throwable closing) {
                        e.addSuppressed(closing);
                    }
                    throw e;
                }
            }, queue, stats, gameTime, System::currentTimeMillis, JournalWriter.PERIOD_MILLIS);
            writer.ready().whenComplete((store, error) -> {
                if (error != null) {
                    failure = new IllegalStateException("could not open journal " + file + ": " + PackSageLog.describe(error), error);
                    recording = false;
                }
            });
        }
    }

    private final Executor worker;
    private final SqlFunction<Path, JournalStore> opener;
    private final NoteBook emptyNotes = new NoteBook();
    private final JournalStats idleStats = new JournalStats();
    private volatile Session session;
    private volatile CompletableFuture<Void> pendingClose = CompletableFuture.completedFuture(null);

    public Journal(Executor worker) {
        this(worker, JournalStore::open);
    }

    Journal(Executor worker, SqlFunction<Path, JournalStore> opener) {
        this.worker = worker;
        this.opener = opener;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                pendingClose.get(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                PackSageLog.LOGGER.warn("PackSage journal shutdown wait ended: {}", PackSageLog.describe(e));
            }
        }, "packsage-journal-shutdown"));
    }

    /** Called on the server thread. A previous session flush is awaited only by the new writer. */
    public void open(Path databaseFile, LongSupplier gameTime) {
        close();
        var next = new Session(databaseFile, gameTime, pendingClose, opener);
        session = next;
        next.writer.start();
    }

    /** Called after the last server-thread hook; only the writer waits for reads, flushes and closes the store. */
    public CompletableFuture<Void> close() {
        var current = session;
        if (current != null) {
            current.recording = false;
            pendingClose = current.writer.close();
        }
        return pendingClose;
    }

    public boolean recording() {
        var current = session;
        return current != null && current.recording;
    }

    public boolean ready() {
        var current = session;
        return current != null && current.recording && current.writer.ready().isDone()
                && !current.writer.ready().isCompletedExceptionally() && current.notes.loaded();
    }

    public String unavailableReason() {
        var current = session;
        if (current != null && current.failure != null) {
            return PackSageLog.describe(current.failure);
        }
        return recording() ? "the journal is still opening" : "the journal is closed";
    }

    public void record(JournalEvent event) {
        var current = session;
        if (current != null && current.recording) {
            current.stats.offered.increment();
            current.queue.offer(event);
        }
    }

    public NoteBook notes() {
        var current = session;
        return current != null && current.recording ? current.notes : emptyNotes;
    }

    public JournalStats stats() {
        var current = session;
        return current == null ? idleStats : current.stats;
    }

    public <T> CompletionStage<T> read(SqlFunction<JournalStore, T> query) {
        return read(session, query);
    }

    private <T> CompletionStage<T> read(Session current, SqlFunction<JournalStore, T> query) {
        if (!ready() || session != current) {
            return CompletableFuture.failedFuture(new IllegalStateException(unavailableReason()));
        }
        return CompletableFuture.supplyAsync(() -> {
            if (session != current || !current.recording) {
                throw new IllegalStateException("the journal session closed");
            }
            try {
                var store = current.writer.ready().join();
                return store.read(connection -> query.apply(store));
            } catch (java.sql.SQLException e) {
                throw new CompletionException(e);
            }
        }, worker);
    }

    /** Always uses this session's writer statistics and the worker executor. */
    public CompletionStage<Page<JournalEntry>> search(JournalFilter filter, String cursor, long nowWall) {
        var current = session;
        return read(current, store -> new JournalSearch(store, current.stats).search(filter, cursor, nowWall));
    }

    public <T> CompletionStage<T> write(StoreCommand<T> command) {
        var current = session;
        return current == null || !current.recording
                ? CompletableFuture.failedFuture(new IllegalStateException(unavailableReason())) : current.writer.submit(command);
    }
}
