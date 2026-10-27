package dev.packsage.jei;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JeiRuntimeHolderTest {
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
