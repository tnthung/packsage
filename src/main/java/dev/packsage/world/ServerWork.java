package dev.packsage.world;

import dev.packsage.knowledge.Toolbox;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Runs world reads on the server thread in slices. Reads queue from any thread; each {@link #tick} (once per server
 * tick, on the server thread) steps the pending reads in turn for at most {@link #BUDGET_NANOS} in total. A read that
 * spends {@link #CALL_BUDGET_NANOS} of server time asks its slice to stop early, so a long scan returns a partial page
 * with a continuation instead of running into the caller's timeout.
 */
public final class ServerWork {
    public static final long BUDGET_NANOS = 5_000_000;
    public static final long CALL_BUDGET_NANOS = 500_000_000;

    public interface Slice<T> {
        /** Does one bounded unit of work; returns false when finished. */
        boolean step();

        T result();

        /**
         * Asked between steps once the call has spent its work budget. A slice that can continue later records where
         * it stopped and a note naming the bound, so {@link #result} is a PARTIAL page with a continuation, and
         * returns true; a slice that cannot stop returns false and keeps stepping.
         */
        default boolean stopEarly() {
            return false;
        }
    }

    private final class Job<T> {
        final Slice<T> slice;
        final BooleanSupplier worldOpen;
        final CompletableFuture<T> future = new CompletableFuture<>();
        long spent;

        Job(Slice<T> slice, BooleanSupplier worldOpen) {
            this.slice = slice;
            this.worldOpen = worldOpen;
        }

        /** Runs one step; returns true while the job wants more. */
        boolean advance() {
            if (future.isDone()) return false;
            try {
                if (!worldOpen.getAsBoolean()) {
                    future.completeExceptionally(closed());
                    return false;
                }
                var before = nanoClock.getAsLong();
                var more = slice.step();
                spent += nanoClock.getAsLong() - before;
                if (!more || spent >= CALL_BUDGET_NANOS && slice.stopEarly()) {
                    future.complete(slice.result());
                    return false;
                }
                return true;
            } catch (VirtualMachineError e) {
                future.completeExceptionally(e);
                jobs.decrementAndGet();
                throw e;
            } catch (Throwable e) {
                // An adapter's Error, such as a LinkageError, fails this read without killing the server thread.
                future.completeExceptionally(e);
                return false;
            }
        }
    }

    private final LongSupplier nanoClock;
    private final Queue<Job<?>> submitted = new ConcurrentLinkedQueue<>();
    private final Deque<Job<?>> active = new ArrayDeque<>();
    private final AtomicInteger jobs = new AtomicInteger();

    public ServerWork(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    /** Queues a read for the next server ticks; it fails at once when the world is already closed. */
    public <T> CompletionStage<T> run(Slice<T> slice, BooleanSupplier worldOpen) {
        var job = new Job<>(slice, worldOpen);
        jobs.incrementAndGet();
        submitted.add(job);
        // Checked after queueing, so a close racing this call either sees the job or is seen here.
        if (!worldOpen.getAsBoolean()) job.future.completeExceptionally(closed());
        return job.future;
    }

    /** Whether any read is queued or running; safe from any thread. */
    public boolean hasWork() {
        return jobs.get() > 0;
    }

    /** Steps the pending reads in turn for at most {@link #BUDGET_NANOS}; server thread only. */
    public void tick() {
        for (Job<?> job; (job = submitted.poll()) != null; ) active.add(job);
        var start = nanoClock.getAsLong();
        while (!active.isEmpty() && nanoClock.getAsLong() - start < BUDGET_NANOS) {
            var job = active.poll();
            if (job.advance()) {
                active.add(job);
            } else {
                jobs.decrementAndGet();
            }
        }
    }

    /** Fails every queued and running read because the server is stopping; server thread only. */
    public void closeAll() {
        for (Job<?> job; (job = submitted.poll()) != null; ) active.add(job);
        for (Job<?> job; (job = active.poll()) != null; ) {
            job.future.completeExceptionally(closed());
            jobs.decrementAndGet();
        }
    }

    private static Toolbox.Failure closed() {
        return new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "world closed while reading");
    }
}
