package dev.packsage.world;

import dev.packsage.knowledge.Toolbox;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Queues server slices and hands their plain results and failures back to the worker. */
final class ServerReads {
    private ServerReads() {
    }

    static <T> CompletionStage<T> run(ServerWork work, Executor worker, BooleanSupplier worldOpen, ServerWork.Slice<T> slice) {
        return work.run(slice, worldOpen).toCompletableFuture().orTimeout(60, TimeUnit.SECONDS).handleAsync((value, failure) -> {
            if (failure == null) return value;
            while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
            if (failure instanceof Toolbox.Failure typed) throw typed;
            if (failure instanceof TimeoutException) throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "the world did not answer in time");
            if (failure instanceof IllegalArgumentException) throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, failure.getMessage());
            throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        }, worker);
    }

    static <T> ServerWork.Slice<T> once(Supplier<T> read) {
        return new ServerWork.Slice<>() {
            private T value;

            @Override
            public boolean step() {
                value = read.get();
                return false;
            }

            @Override
            public T result() {
                return value;
            }
        };
    }
}
