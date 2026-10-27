package dev.packsage.jei;

import mezz.jei.api.runtime.IJeiRuntime;

import java.util.Optional;

public final class JeiRuntimeHolder {
    private static IJeiRuntime runtime;
    private static long generation = firstGeneration(System.currentTimeMillis());

    private JeiRuntimeHolder() {
    }

    /**
     * The generation a process started at {@code millis} counts up from. Temporary recipe handles carry the generation, so a later
     * process must never reach a value an earlier one used; each millisecond leaves room for 2^20 runtime changes.
     */
    static long firstGeneration(long millis) {
        return millis << 20;
    }

    /** The runtime, or null, and the generation it was published under, read together. */
    public record Snapshot(IJeiRuntime runtime, long generation) { }

    public static synchronized Snapshot snapshot() {
        return new Snapshot(runtime, generation);
    }

    public static synchronized Optional<IJeiRuntime> current() {
        return Optional.ofNullable(runtime);
    }

    public static synchronized long generation() {
        return generation;
    }

    static synchronized void available(IJeiRuntime next) {
        runtime = next;
        generation++;
    }

    static synchronized void unavailable() {
        runtime = null;
        generation++;
    }
}
