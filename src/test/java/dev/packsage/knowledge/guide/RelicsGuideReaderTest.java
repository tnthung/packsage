package dev.packsage.knowledge.guide;

import dev.packsage.knowledge.Evidence;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelicsGuideReaderTest {
    @Test
    void factoryReturnsTheLogicalProviderWithoutLoadingRelicsClasses() {
        var provider = RelicsGuideReader.forMinecraft(() -> new Evidence.Scope("world", "en_us", "team", "guides", 1), Runnable::run, "0.12.9");

        assertNotNull(provider);
        assertEquals("relics", provider.id());
        assertEquals("0.12.9", provider.status().version());
    }

    @Test
    void configuredReaderUsesItsProviderIdentityAndVersion() {
        var reader = new RelicsGuideReader("artifacts", "13.2.3", "artifacts");

        assertEquals("artifacts", reader.providerId());
        assertEquals("13.2.3", reader.version());
    }

    @Test
    void longLocalizedDescriptionsContinueWithOverlapAndDetectShortenedLiveText() {
        String localized = "A localized ability description ".repeat(80);
        var first = RelicsGuideReader.textFragment("relics:ember_ring#fireball", localized, "description", 0, 0,
                700, 1, new dev.packsage.knowledge.Toolbox.DocumentBudget());

        assertTrue(first.text().length() <= 700);
        assertEquals(0, first.overlapChars());
        assertNotNull(first.nextPosition());

        int nextOffset = Integer.parseInt(first.nextPosition().substring(first.nextPosition().indexOf(':') + 1));
        var continued = RelicsGuideReader.textFragment("relics:ember_ring#fireball", localized, "description", 0,
                nextOffset, 700, 1, new dev.packsage.knowledge.Toolbox.DocumentBudget());

        assertEquals(511, continued.overlapChars());
        assertTrue(continued.text().startsWith(localized.substring(nextOffset - 511, nextOffset)));
        var changed = RelicsGuideReader.textFragment("relics:ember_ring#fireball", "shortened", "description", 0,
                nextOffset, 700, 1, new dev.packsage.knowledge.Toolbox.DocumentBudget());
        assertEquals(Evidence.Coverage.PARTIAL, changed.coverage());
        assertTrue(changed.detail().contains("changed"));
    }
}
