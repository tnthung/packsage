package dev.packsage.jei;

import dev.packsage.knowledge.Toolbox;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import mezz.jei.api.runtime.IJeiRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JeiRuntimeCacheTest {
    private static IJeiRuntime runtime(String name) {
        return (IJeiRuntime) Proxy.newProxyInstance(IJeiRuntime.class.getClassLoader(), new Class<?>[] { IJeiRuntime.class },
                (proxy, method, args) -> method.getName().equals("toString") ? name : null);
    }

    @AfterEach
    void clear() {
        JeiRuntimeHolder.unavailable();
    }

    @Test
    void indexFromAnEarlierSnapshotIsStaleAfterTheRuntimeIsReplaced() {
        var a = runtime("A");
        JeiRuntimeHolder.available(a);
        var snapshot = JeiRuntimeHolder.snapshot();
        assertSame(a, snapshot.runtime());
        JeiRuntimeHolder.available(runtime("B"));

        var index = new JeiRuntimeIndex(snapshot.runtime(), snapshot.generation(), () -> true, () -> null);
        assertSame(a, index.runtime());
        assertEquals(Toolbox.Code.STALE_SCOPE, assertThrows(Toolbox.Failure.class, index::generation).code());
    }

    @Test
    void cacheBuildsFromOneSnapshotAndNeverKeepsAValueMadeDuringAReplacement() {
        var a = runtime("A");
        var b = runtime("B");
        var built = new ArrayList<JeiRuntimeHolder.Snapshot>();
        var cache = new JeiRuntimeCache<>(snapshot -> {
            built.add(snapshot);
            // The client replaces A with B while the value for A is being built off the client thread.
            if (snapshot.runtime() == a) JeiRuntimeHolder.available(b);
            return snapshot;
        });
        JeiRuntimeHolder.available(a);
        long generationA = JeiRuntimeHolder.generation();

        var first = cache.get().orElseThrow();
        assertSame(a, first.runtime());
        assertEquals(generationA, first.generation());
        var second = cache.get().orElseThrow();
        assertSame(b, second.runtime());
        assertEquals(JeiRuntimeHolder.generation(), second.generation());
        assertSame(second, cache.get().orElseThrow());
        assertEquals(List.of(first, second), built);

        JeiRuntimeHolder.unavailable();
        assertTrue(cache.get().isEmpty());
    }
}
