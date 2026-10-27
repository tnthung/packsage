package dev.packsage.jei;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/** One value per JEI runtime, built from a runtime and the generation it was published under, read together. */
public final class JeiRuntimeCache<T> implements Supplier<Optional<T>> {
    private final Function<JeiRuntimeHolder.Snapshot, T> factory;
    private long generation;
    private T cached;

    public JeiRuntimeCache(Function<JeiRuntimeHolder.Snapshot, T> factory) {
        this.factory = factory;
    }

    @Override public synchronized Optional<T> get() {
        var snapshot = JeiRuntimeHolder.snapshot();
        if (snapshot.runtime() == null) { cached = null; return Optional.empty(); }
        if (cached != null && generation == snapshot.generation()) return Optional.of(cached);
        var value = factory.apply(snapshot);
        // A runtime replaced while building leaves the value on the old generation: its own checks reject it, and it is not kept.
        if (JeiRuntimeHolder.generation() == snapshot.generation()) { cached = value; generation = snapshot.generation(); }
        return Optional.of(value);
    }
}
