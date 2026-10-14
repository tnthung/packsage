package dev.packsage.knowledge.guide;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArtifactsGuideReaderTest {
    @Test
    void exposesOnlyThePinnedArtifactsGuideSource() {
        Executor clientExecutor = Runnable::run;
        var provider = new GuideDocumentProvider(new ArtifactsGuideReader(),
                () -> new Evidence.Scope("world", "en_us", "team", "source", 1),
                clientExecutor);

        assertEquals("artifacts", provider.id());
        assertEquals("13.2.3", provider.status().version());
        assertEquals(java.util.List.of("guide"), provider.metadata().documentTypes());
    }

    @Test
    void rejectsMalformedRegistryPositionInsteadOfRestartingAtTheBeginning() {
        var reader = new ArtifactsGuideReader();

        var failure = assertThrows(Toolbox.Failure.class,
                () -> reader.candidates("not-an-index", 1, new Toolbox.DocumentBudget()));

        assertEquals(Toolbox.Code.INVALID_CURSOR, failure.code());
    }

    @Test
    void rejectsMalformedTooltipContinuationPosition() {
        var reader = new ArtifactsGuideReader();

        var failure = assertThrows(Toolbox.Failure.class,
                () -> reader.readFragment("artifacts:umbrella#artifacts:double_jump", "bad-offset", 512,
                        new Toolbox.DocumentBudget()));

        assertEquals(Toolbox.Code.INVALID_CURSOR, failure.code());
    }

    @Test
    void boundsLongTooltipFragmentsAndDetectsChangedSourcePositions() {
        var source = Component.literal("x".repeat(1400));
        var first = ArtifactsGuideReader.tooltipTextFragment(source, 0, 600, new Toolbox.DocumentBudget());

        assertEquals(600, first.text().length());
        assertEquals(0, first.overlapChars());
        assertEquals(600, first.nextOffset());

        var continued = ArtifactsGuideReader.tooltipTextFragment(source, first.nextOffset(), 600,
                new Toolbox.DocumentBudget());

        assertEquals(511, continued.overlapChars());
        assertEquals(source.getString().substring(89, 689), continued.text());
        assertEquals(689, continued.nextOffset());
        assertTrue(continued.positionValid());

        var changed = ArtifactsGuideReader.tooltipTextFragment(Component.literal("short"), continued.nextOffset(), 600,
                new Toolbox.DocumentBudget());
        assertFalse(changed.positionValid());
        assertNull(changed.nextOffset());
    }
}
