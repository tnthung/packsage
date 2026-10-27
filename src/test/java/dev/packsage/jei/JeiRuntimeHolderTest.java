package dev.packsage.jei;

import dev.packsage.knowledge.Toolbox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JeiRuntimeHolderTest {
    @Test
    void staleIndexRejectsAccessBeforeTouchingMinecraft() {
        var index = new JeiRuntimeIndex(JeiRuntimeHolder.snapshot());
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

    @Test
    void generationsNeverRepeatAcrossProcesses() {
        // A restarted process starts past every generation an earlier one could reach, so a saved temporary handle cannot match.
        long jvmStart = java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
        assertTrue(JeiRuntimeHolder.generation() >= JeiRuntimeHolder.firstGeneration(jvmStart));
        assertTrue(JeiRuntimeHolder.firstGeneration(jvmStart + 1) > JeiRuntimeHolder.firstGeneration(jvmStart) + 1_000_000);
        assertTrue(JeiRuntimeHolder.firstGeneration(jvmStart) > 0);
    }
}
