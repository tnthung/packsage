package dev.packsage.knowledge.guide;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModonomiconGuideReaderTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "runtime", 1);

    @Test
    void factoryExposesTheLogicalProviderAndVerifiedRuntimeVersion() {
        KnowledgeProvider provider = ModonomiconGuideReader.forMinecraft(() -> SCOPE, Runnable::run);

        assertEquals("modonomicon", provider.id());
        assertEquals("modonomicon", provider.metadata().id());
        assertEquals(java.util.List.of("guide"), provider.metadata().documentTypes());
        assertEquals(KnowledgeProvider.Availability.AVAILABLE, provider.status().availability());
        assertTrue(provider.status().version().contains("1.120.4"));
    }

    @Test
    void malformedEntryIdentityIsRejectedBeforeReadingTheLoadedBookRegistry() {
        var reader = new ModonomiconGuideReader();

        var failure = assertThrows(dev.packsage.knowledge.Toolbox.Failure.class,
                () -> reader.readFragment("invalid", null, 256, new dev.packsage.knowledge.Toolbox.DocumentBudget()));

        assertEquals(dev.packsage.knowledge.Toolbox.Code.INVALID_REFERENCE, failure.code());
    }

    @Test
    void malformedFragmentPositionIsRejectedBeforeReadingTheLoadedBookRegistry() {
        var reader = new ModonomiconGuideReader();

        var failure = assertThrows(dev.packsage.knowledge.Toolbox.Failure.class,
                () -> reader.readFragment("test:book|test:category|test:entry", "bad", 256,
                        new dev.packsage.knowledge.Toolbox.DocumentBudget()));

        assertEquals(dev.packsage.knowledge.Toolbox.Code.INVALID_CURSOR, failure.code());
    }
}
