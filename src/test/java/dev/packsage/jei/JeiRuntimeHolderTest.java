package dev.packsage.jei;

import dev.packsage.knowledge.Toolbox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JeiRuntimeHolderTest {
    @Test
    void staleIndexRejectsAccessBeforeTouchingMinecraft() {
        var index = new JeiRuntimeIndex(null);
        JeiRuntimeHolder.unavailable();
        assertEquals(Toolbox.Code.STALE_SCOPE, assertThrows(Toolbox.Failure.class, index::generation).code());
    }

    @Test
    void pluginCallbacksAdvanceGeneration() {
        var plugin = new PackSageJeiPlugin();
        long initial = JeiRuntimeHolder.generation();
        plugin.onRuntimeAvailable(null);
        assertEquals(initial + 1, JeiRuntimeHolder.generation());
        plugin.onRuntimeUnavailable();
        assertEquals(initial + 2, JeiRuntimeHolder.generation());
        assertTrue(JeiRuntimeHolder.current().isEmpty());
        assertEquals("packsage:jei", plugin.getPluginUid().toString());
    }
}
