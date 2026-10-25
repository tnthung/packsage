package dev.packsage.journal;

import dev.packsage.PackSageLog;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The background thread that owns the journal store: it opens the store, drains the queue about once a period, merges
 * runs, writes one transaction per batch, runs store commands, and flushes everything when closed.
 */
public final class JournalWriter {
    static final long PERIOD_MILLIS = 1000;
    static final int MAX_BATCH = 65_536;
    private static final long FAILURE_LOG_MILLIS = 60_000;

    /** Work for the writer thread against the open store. */
    public interface StoreCommand<T> {
        T run(JournalStore store) throws SQLException;
    }

    private final Callable<JournalStore> opener;
    private final EventQueue queue;
    private final JournalStats stats;
    private final LongSupplier gameTime;
    private final LongSupplier wallClock;
    private final long periodNanos;
    private final RunMerger merger = new RunMerger();
    private final LinkedBlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
    private final Object taskLock = new Object();
    private final CompletableFuture<JournalStore> ready = new CompletableFuture<>();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private boolean accepting = true;
    private boolean started;
    private volatile boolean closing;
    private volatile JournalStore store;
    private volatile Throwable openFailure;
    private long sessionId;
    private long lastFailureLog;
    private boolean failureLogged;

    public JournalWriter(Callable<JournalStore> opener, EventQueue queue, JournalStats stats, LongSupplier gameTime,
                         LongSupplier wallClock, long periodMillis) {
        this.opener = opener;
        this.queue = queue;
        this.stats = stats;
        this.gameTime = gameTime;
        this.wallClock = wallClock;
        this.periodNanos = TimeUnit.MILLISECONDS.toNanos(periodMillis);
    }

    /** Starts the non-daemon {@code packsage-journal} thread, which opens the store and starts a session first. */
    public void start() {
        synchronized (taskLock) {
            if (started) {
                return;
            }
            started = true;
        }
        var thread = new Thread(this::run, "packsage-journal");
        thread.setDaemon(false);
        thread.start();
    }

    /** Completes with the open store once the session started, or exceptionally when opening failed. */
    public CompletableFuture<JournalStore> ready() {
        return ready;
    }

    /** Runs {@code command} on the writer thread after any due periodic drain; fails once the writer is closing. */
    public <T> CompletableFuture<T> submit(StoreCommand<T> command) {
        var future = new CompletableFuture<T>();
        synchronized (taskLock) {
            if (!accepting) {
                future.completeExceptionally(new IllegalStateException("the journal writer is closed"));
                return future;
            }
            tasks.add(() -> {
                var open = store;
                if (open == null) {
                    future.completeExceptionally(new IllegalStateException("the journal did not open", openFailure));
                    return;
                }
                try {
                    future.complete(command.run(open));
                } catch (Throwable e) {
                    future.completeExceptionally(e);
                }
            });
        }
        return future;
    }

    /**
     * Stops accepting commands and closes the queue on the calling thread, then lets the writer thread flush. Call it on
     * the thread that offers events (the server thread), so every offer either precedes the close and is flushed or
     * follows it and is counted as dropped. Returns at once; the future completes after the store closed. Before
     * {@link #start()} it completes at once, counting the queued events as failed, and the writer never starts.
     */
    public CompletableFuture<Void> close() {
        boolean neverStarted;
        synchronized (taskLock) {
            if (!accepting) {
                return closed;
            }
            accepting = false;
            queue.close();
            closing = true;
            tasks.add(() -> {
            });
            neverStarted = !started;
            started = true;
        }
        if (neverStarted) {
            abandon(new IllegalStateException("the journal writer was closed before it started"));
        }
        return closed;
    }

    /** Opens the store and starts the session on the current thread, which becomes the store's writer thread. */
    JournalStore openNow() throws Exception {
        var opened = opener.call();
        try {
            sessionId = opened.startSession(wallClock.getAsLong(), gameTime.getAsLong());
        } catch (SQLException | RuntimeException e) {
            try {
                opened.close();
            } catch (SQLException | RuntimeException closing) {
                e.addSuppressed(closing);
            }
            throw e;
        }
        store = opened;
        return opened;
    }

    /** Drains, merges and writes one batch, then forgets idle runs. Returns the number of events drained. */
    int drainOnce() {
        var events = queue.drain(MAX_BATCH);
        for (var e : events) {
            merger.add(e);
        }
        var changed = merger.takeChanged();
        var overflow = queue.drainOverflow();
        if (!changed.isEmpty() || !overflow.isEmpty()) {
            var overflowed = overflow.stream().mapToLong(OverflowRow::count).sum();
            stats.overflowed.add(overflowed);
            var started = System.nanoTime();
            try {
                store.writeBatch(changed, overflow);
                stats.written.add(events.size() + overflowed);
            } catch (SQLException | RuntimeException e) {
                stats.failedEvents.add(events.size() + overflowed);
                merger.forget(changed);
                logFailure(e);
            }
            stats.batchMillis(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
        merger.closeIdle(gameTime.getAsLong());
        return events.size();
    }

    private void run() {
        try {
            openNow();
        } catch (Throwable e) {
            PackSageLog.LOGGER.warn("PackSage journal could not open; recording is off: {}", PackSageLog.describe(e));
            failOpen(e);
            return;
        }
        ready.complete(store);
        try {
            loop();
        } catch (Throwable e) {
            PackSageLog.LOGGER.error("PackSage journal writer stopped: {}", PackSageLog.describe(e));
        }
        finish();
    }

    private void loop() throws InterruptedException {
        var due = System.nanoTime() + periodNanos;
        while (!closing) {
            var wait = due - System.nanoTime();
            if (wait <= 0) {
                drainOnce();
                due = System.nanoTime() + periodNanos;
                continue;
            }
            var task = tasks.poll(wait, TimeUnit.NANOSECONDS);
            if (task != null) {
                task.run();
            }
        }
    }

    /** Runs the accepted commands, then flushes the closed queue, ends the session and closes the store. */
    private void finish() {
        close();
        try {
            for (Runnable task; (task = tasks.poll()) != null; ) {
                task.run();
            }
            while (drainOnce() > 0) {
                // drainOnce writes every batch, including the open runs it extends
            }
        } catch (RuntimeException e) {
            PackSageLog.LOGGER.error("PackSage journal could not flush: {}", PackSageLog.describe(e));
        }
        try {
            store.endSession(sessionId, wallClock.getAsLong(), gameTime.getAsLong());
        } catch (SQLException | RuntimeException e) {
            PackSageLog.LOGGER.warn("PackSage journal could not end its session: {}", PackSageLog.describe(e));
        }
        try {
            store.close();
        } catch (SQLException | RuntimeException e) {
            PackSageLog.LOGGER.warn("PackSage journal could not close: {}", PackSageLog.describe(e));
        }
        PackSageLog.LOGGER.info("PackSage {}", stats.summary());
        closed.complete(null);
    }

    private void failOpen(Throwable cause) {
        synchronized (taskLock) {
            accepting = false;
            queue.close();
            closing = true;
        }
        abandon(cause);
    }

    /**
     * For a writer that has no store: fails readiness and the pending commands, counts what was queued as failed, logs the
     * stats and completes the close future. The queue is already closed.
     */
    private void abandon(Throwable cause) {
        openFailure = cause;
        ready.completeExceptionally(cause);
        List<Runnable> pending;
        synchronized (taskLock) {
            pending = List.copyOf(tasks);
            tasks.clear();
        }
        for (var task : pending) {
            task.run();
        }
        long lost = 0;
        for (List<JournalEvent> events; !(events = queue.drain(MAX_BATCH)).isEmpty(); ) {
            lost += events.size();
        }
        lost += queue.drainOverflow().stream().mapToLong(OverflowRow::count).sum();
        stats.failedEvents.add(lost);
        PackSageLog.LOGGER.info("PackSage {}", stats.summary());
        closed.complete(null);
    }

    private void logFailure(Exception e) {
        var now = wallClock.getAsLong();
        if (!failureLogged || now - lastFailureLog >= FAILURE_LOG_MILLIS) {
            failureLogged = true;
            lastFailureLog = now;
            PackSageLog.LOGGER.warn("PackSage journal batch failed; {} events lost so far: {}", stats.failedEvents.sum(),
                    PackSageLog.describe(e));
        }
    }
}
