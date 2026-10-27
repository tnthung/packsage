package dev.packsage.jei;

import dev.packsage.knowledge.Evidence;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JeiPlanSourceTest {
    @Test
    void outputsComeFromFocusIndex() {
        var scope = new Evidence.Scope("world", "en_us", "team", "runtime", 1);
        var index = new JeiSearchTest.Fake(); index.add(0, "mod:machine", "Machine", true); index.forbidCategories = true;
        index.focuses.put("OUTPUT:mod:product", List.copyOf(index.layouts.keySet()));
        var source = new JeiPlanSource(index, scope, "19.27", Instant.EPOCH);
        var records = source.recipeOutputs("mod:product", new Evidence.JsonObject(Map.of()), scope);
        assertEquals(1, records.size());
        assertEquals("mod:machine|test:recipe0", records.getFirst().source().sourceHandle());
        assertTrue(source.recipesComplete("mod:product", new Evidence.JsonObject(Map.of())));
    }

    @Test
    void boundedFocusIsNotComplete() {
        var scope = new Evidence.Scope("world", "en_us", "team", "runtime", 1);
        var index = new JeiSearchTest.Fake();
        for (int i = 0; i < 513; i++) index.add(i, "mod:machine", "Machine", true);
        index.focuses.put("OUTPUT:mod:product", List.copyOf(index.layouts.keySet()));
        var source = new JeiPlanSource(index, scope, "19.27", Instant.EPOCH);
        assertFalse(source.recipesComplete("mod:product", new Evidence.JsonObject(Map.of())));
        assertEquals(512, source.recipeOutputs("mod:product", new Evidence.JsonObject(Map.of()), scope).size());
    }

    @Test
    void componentRequirementsUseExactVariantFocus() {
        var scope = new Evidence.Scope("world", "en_us", "team", "runtime", 1);
        var index = new JeiSearchTest.Fake();
        index.add(0, "minecraft:brewing", "Brewing", true); index.add(1, "minecraft:brewing", "Brewing", true);
        index.focuses.put("OUTPUT:minecraft:potion", List.copyOf(index.layouts.keySet()));
        var components = new Evidence.JsonObject(Map.of("minecraft:potion_contents", new Evidence.JsonString("minecraft:strength")));
        index.variants.put(new JeiIndex.ItemVariant("minecraft:potion", Evidence.serialize(components)),
                List.of(new JeiIndex.RecipeRef("minecraft:brewing", 1)));
        var source = new JeiPlanSource(index, scope, "19.27", Instant.EPOCH);
        var records = source.recipeOutputs("minecraft:potion", components, scope);
        assertEquals(List.of("test:recipe1"), records.stream().map(record -> record.recipePayload().canonicalRecipeId()).toList());
        var other = new Evidence.Scope("world", "en_us", "team", "other-runtime", 2);
        assertFalse(source.available(other));
        assertTrue(source.recipeOutputs("minecraft:potion", components, other).isEmpty());
    }
}
