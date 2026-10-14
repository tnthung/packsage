package dev.packsage.knowledge.guide;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import guideme.FixtureGuide;
import guideme.Guides;
import guideme.compiler.ParsedGuidePage;
import guideme.libs.mdast.model.MdAstRoot;
import guideme.libs.mdast.model.MdAstText;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuideMeGuideReaderTest {
    private static final ResourceLocation GUIDE_ID = ResourceLocation.parse("fixture:book");
    private static final ResourceLocation PAGE_ID = ResourceLocation.parse("fixture:entry");

    @AfterEach
    void clearGuides() { Guides.setForTest(List.of()); }

    @Test
    void candidatesExposeCurrentParsedPagesAndContinueAfterTheLastPosition() {
        var first = new ParsedGuidePage(PAGE_ID, "en_us", new MdAstRoot());
        var second = new ParsedGuidePage(ResourceLocation.parse("fixture:second"), "en_us", new MdAstRoot());
        Guides.setForTest(List.of(new FixtureGuide(GUIDE_ID, List.of(first, second))));
        var reader = new GuideMeGuideReader();

        var page = reader.candidates(null, 1, new Toolbox.DocumentBudget());
        var continued = reader.candidates(page.nextPosition(), 1, new Toolbox.DocumentBudget());

        assertEquals("fixture:book#fixture:entry", page.candidates().getFirst().id());
        assertEquals("en_us", page.candidates().getFirst().provenance().getLast().substring("guideme:language:".length()));
        assertTrue(page.more());
        assertEquals("fixture:book#fixture:second", continued.candidates().getFirst().id());
        assertFalse(continued.more());
    }

    @Test
    void longAstTextContinuesWithBoundedOverlapAndFreshPageResolution() {
        String source = "needle-".repeat(200);
        var root = new MdAstRoot();
        root.add(new MdAstText(source));
        var page = new ParsedGuidePage(PAGE_ID, "en_us", root);
        Guides.setForTest(List.of(new FixtureGuide(GUIDE_ID, List.of(page))));
        var reader = new GuideMeGuideReader();

        var first = reader.readFragment("fixture:book#fixture:entry", null, 600, new Toolbox.DocumentBudget());
        var second = reader.readFragment("fixture:book#fixture:entry", first.nextPosition(), 600, new Toolbox.DocumentBudget());

        assertEquals(source.substring(0, 600), first.text());
        assertNotNull(first.nextPosition());
        assertEquals(511, second.overlapChars());
        assertEquals(600, second.text().length());
        assertEquals(source.substring(89, 689), second.text());
        assertEquals(Evidence.Coverage.COMPLETE, second.coverage());
    }

    @Test
    void childPathContinuationReadsBeyondThePerCallTraversalBudget() {
        var root = new MdAstRoot();
        var expected = new StringBuilder();
        for (int index = 0; index < 2200; index++) {
            String value = "page-" + index + ";";
            expected.append(value);
            root.add(new MdAstText(value));
        }
        var page = new ParsedGuidePage(PAGE_ID, "en_us", root);
        Guides.setForTest(List.of(new FixtureGuide(GUIDE_ID, List.of(page))));
        var reader = new GuideMeGuideReader();
        String position = null;
        var actual = new StringBuilder();

        do {
            var fragment = reader.readFragment("fixture:book#fixture:entry", position, 64,
                    new Toolbox.DocumentBudget());
            actual.append(fragment.text());
            position = fragment.nextPosition();
        } while (position != null);

        assertEquals(expected.toString(), actual.toString());
    }

    @Test
    void malformedPageIdentityIsRejectedBeforeOptionalLookup() {
        var failure = org.junit.jupiter.api.Assertions.assertThrows(Toolbox.Failure.class,
                () -> new GuideMeGuideReader().readFragment("bad-page-id", null, 128, new Toolbox.DocumentBudget()));

        assertEquals(Toolbox.Code.INVALID_REFERENCE, failure.code());
    }
}
