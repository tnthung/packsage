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
        var components = Evidence.deserializeObject("{\"minecraft:potion_contents\":{\"potion\":\"minecraft:strength\"},\"minecraft:custom_data\":{\"label\":\"<brew>\",\"level\":1}}");
        index.variants.put(new JeiIndex.ItemVariant("minecraft:potion",
                "{ \"minecraft:custom_data\": { \"level\": 1, \"label\": \"<brew>\" }, \"minecraft:potion_contents\": { \"potion\": \"minecraft:strength\" } }"),
                List.of(new JeiIndex.RecipeRef("minecraft:brewing", 1)));
        var source = new JeiPlanSource(index, scope, "19.27", Instant.EPOCH);
        var records = source.recipeOutputs("minecraft:potion", components, scope);
        assertEquals(List.of("test:recipe1"), records.stream().map(record -> record.recipePayload().canonicalRecipeId()).toList());
        assertTrue(source.recipesComplete("minecraft:potion", components));
        var other = new Evidence.Scope("world", "en_us", "team", "other-runtime", 2);
        assertFalse(source.available(other));
        assertTrue(source.recipeOutputs("minecraft:potion", components, other).isEmpty());
    }

    @Test
    void unmatchedSubtypeVariantIsNotComplete() {
        var scope = new Evidence.Scope("world", "en_us", "team", "runtime", 1);
        var index = new JeiSearchTest.Fake() {
            @Override public boolean hasSubtypes(String identity) { return identity.equals("minecraft:potion"); }
        };
        var source = new JeiPlanSource(index, scope, "19.27", Instant.EPOCH);
        var components = Evidence.deserializeObject("{\"minecraft:potion_contents\":{\"potion\":\"minecraft:strength\"}}");
        assertTrue(source.recipeOutputs("minecraft:potion", components, scope).isEmpty());
        assertFalse(source.recipesComplete("minecraft:potion", components));
        assertTrue(source.recipesComplete("minecraft:stone", components));
    }

    @Test
    void recipesAreDrawnOnlyWhenReadAndOncePerPlan() {
        var scope = new Evidence.Scope("world", "en_us", "team", "runtime", 1);
        var index = new JeiSearchTest.Fake();
        for (int i = 0; i < 300; i++) index.add(i, "mod:machine", "Machine", true);
        index.focuses.put("OUTPUT:mod:product", List.copyOf(index.layouts.keySet()));
        index.focuses.put("OUTPUT:mod:other", List.copyOf(index.layouts.keySet()).subList(0, 2));
        var source = new JeiPlanSource(index, scope, "19.27", Instant.EPOCH);

        var records = source.recipeOutputs("mod:product", new Evidence.JsonObject(Map.of()), scope);
        assertEquals(300, records.size());
        assertEquals(0, index.layoutCalls);
        assertEquals("test:recipe1", records.get(1).recipePayload().canonicalRecipeId());
        assertEquals("test:recipe1", source.recipeOutputs("mod:product", new Evidence.JsonObject(Map.of()), scope).get(1).recipePayload().canonicalRecipeId());
        source.recipeOutputs("mod:other", new Evidence.JsonObject(Map.of()), scope).forEach(record -> { });
        assertEquals(2, index.layoutCalls);
    }

    @Test
    void aManyRoutePlanDrawsOnlyTheRoutesItReads() {
        var scope = new Evidence.Scope("world", "en_us", "team", "runtime", 1);
        var index = new JeiSearchTest.Fake();
        for (int i = 0; i < 300; i++) index.add(i, "mod:machine", "Machine", true);
        index.focuses.put("OUTPUT:mod:product", List.copyOf(index.layouts.keySet()));

        var result = new dev.packsage.knowledge.RecipePlanner(new JeiPlanSource(index, scope, "19.27", Instant.EPOCH))
                .plan("mod:product", 1, scope, 1);

        assertEquals(64, result.records().size());
        assertEquals(64, index.layoutCalls);
    }

    @Test
    void focusLookupIsReusedAcrossReadAndCompleteness() {
        var scope = new Evidence.Scope("world", "en_us", "team", "runtime", 1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var index = new JeiSearchTest.Fake() {
            @Override public List<RecipeRef> byFocus(JeiLayout.Role role, String identity) {
                calls.incrementAndGet();
                return super.byFocus(role, identity);
            }
        };
        var source = new JeiPlanSource(index, scope, "19.27", Instant.EPOCH);
        assertTrue(source.recipeOutputs("minecraft:stone", new Evidence.JsonObject(Map.of()), scope).isEmpty());
        assertTrue(source.recipesComplete("minecraft:stone", new Evidence.JsonObject(Map.of())));
        assertEquals(1, calls.get());
    }
}
