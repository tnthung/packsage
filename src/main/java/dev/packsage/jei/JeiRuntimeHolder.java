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
