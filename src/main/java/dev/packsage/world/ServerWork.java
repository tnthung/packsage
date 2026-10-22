package dev.packsage.world;

import dev.packsage.knowledge.Toolbox;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public final class ServerWork {
    public static final long BUDGET_NANOS = 5_000_000;

    public interface Slice<T> {
        /** Does one bounded unit of work; returns false when finished. */
        boolean step();

        T result();
    }

    private final Executor serverThread;
    private final LongSupplier nanoClock;
    private final BooleanSupplier worldOpen;

    public ServerWork(Executor serverThread, LongSupplier nanoClock, BooleanSupplier worldOpen) {
        this.serverThread = serverThread;
        this.nanoClock = nanoClock;
        this.worldOpen = worldOpen;
    }

    public <T> CompletionStage<T> run(Slice<T> job) {
        var future = new CompletableFuture<T>();
        schedule(job, future);
        return future;
    }

    private <T> void schedule(Slice<T> job, CompletableFuture<T> future) {
        try {
            serverThread.execute(() -> tick(job, future));
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
        }
    }

    private <T> void tick(Slice<T> job, CompletableFuture<T> future) {
        try {
            if (future.isDone()) return;
            if (!worldOpen.getAsBoolean()) {
                future.completeExceptionally(new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "world closed while reading"));
                return;
            }
            long start = nanoClock.getAsLong();
            do {
                if (!job.step()) {
                    future.complete(job.result());
                    return;
                }
            } while (nanoClock.getAsLong() - start < BUDGET_NANOS);
            schedule(job, future);
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
        }
    }
}
