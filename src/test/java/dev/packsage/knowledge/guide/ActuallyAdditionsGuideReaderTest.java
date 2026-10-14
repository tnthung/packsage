package dev.packsage.knowledge.guide;

import dev.packsage.knowledge.Evidence;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActuallyAdditionsGuideReaderTest {
    @Test
    void registersThePinnedPatchouliBookletAsAnActuallyAdditionsGuide() {
        var scope = new Evidence.Scope("world", "en_us", "solo", "client", 1);
        var provider = ActuallyAdditionsGuideReader.forMinecraft(() -> scope, Runnable::run);

        assertEquals("actuallyadditions", provider.id());
        assertEquals("1.3.26", provider.status().version());
        assertTrue(provider.metadata().documentTypes().contains("guide"));
    }
}
