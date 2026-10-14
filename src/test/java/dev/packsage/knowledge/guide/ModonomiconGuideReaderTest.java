package dev.packsage.knowledge.guide;

import com.klikli_dev.modonomicon.book.BookTextHolder;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.knowledge.Toolbox;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void localizedBookTextFragmentsRetainOverlapAndAdvanceAcrossFields() {
        var holder = new BookTextHolder("packsage.fixture.long_text");
        var source = holder.getKey() + "x".repeat(600);
        var first = ModonomiconGuideReader.textFragment(source, 0, 0, 0, 1, 512,
                new Toolbox.DocumentBudget(), false);
        var second = ModonomiconGuideReader.textFragment(source, 0, 0, 512, 1, 512,
                new Toolbox.DocumentBudget(), false);
        var title = ModonomiconGuideReader.textFragment("Title", 0, 0, 0, 1, 256,
                new Toolbox.DocumentBudget(), false);
        var body = ModonomiconGuideReader.textFragment("Body", 0, 1, 0, 1, 256,
                new Toolbox.DocumentBudget(), false);

        assertEquals("0:0:512", first.nextPosition());
        assertEquals(GuideDocumentReader.FRAGMENT_OVERLAP_CHARS, second.overlapChars());
        assertEquals(512, second.text().length());
        assertEquals("0:0:513", second.nextPosition());
        assertTrue(second.text().startsWith(source.substring(1, 512)));
        assertEquals("0:1:0", title.nextPosition());
        assertNull(body.nextPosition());
    }

    @Test
    void textFragmentFailsInsteadOfReturningAnUnadvanceableEmptyPage() {
        var budget = new Toolbox.DocumentBudget();
        assertTrue(budget.bytes(budget.remainingBytes()));

        var failure = assertThrows(Toolbox.Failure.class, () -> ModonomiconGuideReader.textFragment(
                "x", 0, 0, 0, 1, 1, budget, false));

        assertEquals(Toolbox.Code.EXTRACTION_FAILED, failure.code());
    }
}
