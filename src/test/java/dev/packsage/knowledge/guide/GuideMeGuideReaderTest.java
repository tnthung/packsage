package dev.packsage.knowledge.guide;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GuideMeGuideReaderTest {
    @Test
    void missingOptionalRuntimeIsReportedAsUnavailable() {
        var reader = new GuideMeGuideReader();

        var page = reader.candidates(null, 4, new Toolbox.DocumentBudget());

        assertEquals("guideme", reader.providerId());
        assertEquals("21.1.19", reader.version());
        assertEquals(Evidence.Coverage.UNAVAILABLE, page.coverage());
        assertFalse(page.more());
    }

    @Test
    void malformedPageIdentityIsRejectedBeforeReadingTheOptionalRuntime() {
        var reader = new GuideMeGuideReader();

        var failure = assertThrows(Toolbox.Failure.class,
                () -> reader.readFragment("bad-page-id", null, 128, new Toolbox.DocumentBudget()));

        assertEquals(Toolbox.Code.INVALID_REFERENCE, failure.code());
    }
}
