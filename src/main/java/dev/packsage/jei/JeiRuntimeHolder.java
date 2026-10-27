package dev.packsage.jei;

import mezz.jei.api.runtime.IJeiRuntime;

import java.util.Optional;

public final class JeiRuntimeHolder {
    private static IJeiRuntime runtime;
    private static long generation;

    private JeiRuntimeHolder() {
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
