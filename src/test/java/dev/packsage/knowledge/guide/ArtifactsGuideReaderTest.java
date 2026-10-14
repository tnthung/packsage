package dev.packsage.knowledge.guide;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArtifactsGuideReaderTest {
    @Test
    void exposesOnlyThePinnedArtifactsGuideSource() {
        Executor clientExecutor = Runnable::run;
        var provider = ArtifactsGuideReader.forMinecraft(() -> new Evidence.Scope("world", "en_us", "team", "source", 1),
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
}
