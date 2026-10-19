package dev.packsage.knowledge.guide;

import com.klikli_dev.modonomicon.book.BookTextHolder;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.knowledge.Toolbox;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModonomiconGuideReaderTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "runtime", 1);

    @Test
    void factoryExposesTheLogicalProviderAndVerifiedRuntimeVersion() {
        KnowledgeProvider provider = ModonomiconGuideReader.forMinecraft(() -> SCOPE, Runnable::run, "1.120.7");

        assertEquals("modonomicon", provider.id());
        assertEquals("modonomicon", provider.metadata().id());
        assertEquals(java.util.List.of("guide"), provider.metadata().documentTypes());
        assertEquals(KnowledgeProvider.Availability.AVAILABLE, provider.status().availability());
        assertEquals("1.120.7", provider.status().version());
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

    @Test
    void literalAndTranslatedBookTextHoldersResolveWithoutRenderingWholeComponents() {
        var language = new FixtureLanguage(Map.of("packsage.fixture.translation", "Translated fixture text"));
        var literal = ModonomiconGuideReader.localizedText(new BookTextHolder(Component.literal("Literal fixture text")), language);
        var translatedString = ModonomiconGuideReader.localizedText(new BookTextHolder("packsage.fixture.translation"), language);
        var translatedComponent = ModonomiconGuideReader.localizedText(
                new BookTextHolder(Component.translatable("packsage.fixture.translation")), language);
        var missing = ModonomiconGuideReader.localizedText(new BookTextHolder("packsage.fixture.missing"), language);

        assertEquals("Literal fixture text", literal.text());
        assertEquals("Translated fixture text", translatedString.text());
        assertEquals("Translated fixture text", translatedComponent.text());
        assertEquals("packsage.fixture.missing", missing.text());
        assertTrue(missing.partial());
    }

    private static final class FixtureLanguage extends Language {
        private final Map<String, String> translations;

        private FixtureLanguage(Map<String, String> translations) {
            this.translations = Map.copyOf(translations);
        }

        @Override public String getOrDefault(String key, String fallback) { return translations.getOrDefault(key, fallback); }
        @Override public boolean has(String key) { return translations.containsKey(key); }
        @Override public boolean isDefaultRightToLeft() { return false; }
        @Override public FormattedCharSequence getVisualOrder(FormattedText text) { return FormattedCharSequence.EMPTY; }
    }
}
