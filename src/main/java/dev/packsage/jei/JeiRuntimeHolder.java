package dev.packsage.jei;

import mezz.jei.api.runtime.IJeiRuntime;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;

public final class JeiRuntimeHolder {
    private static IJeiRuntime runtime;
    private static long generation;

    private JeiRuntimeHolder() {
    }

    /**
     * A random identity for this process, independent of the clock. Temporary recipe handles carry it, so a handle saved by another
     * process never matches here even when its generation and ordinal do.
     */
    private static final String SESSION = HexFormat.of().toHexDigits(new SecureRandom().nextLong());

    public static String session() {
        return SESSION;
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
