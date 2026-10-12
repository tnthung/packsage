package dev.packsage.knowledge;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipePlannerTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "native", 1);

    @Test
    void plansAndSlotsOrAlternativesBatchesCatalystsAndRemainders() {
        var index = new KnowledgeIndex();
        index.replace(new dev.packsage.knowledge.nativegame.NativeSnapshot(SCOPE, List.of(recipe())));

        var result = new RecipePlanner(index).plan("mod:lantern", 5, SCOPE, 4);

        assertEquals(Evidence.Outcome.PARTIAL, result.outcome());
        var payload = result.records().get(0).recipePayload();
        assertEquals(2, payload.inputs().size());
        assertEquals(2, payload.inputs().get(0).alternatives().size());
        assertEquals(3, payload.outputs().get(0).quantity());
        assertEquals("minecraft:bucket", payload.catalysts().get(0).comparisonIdentity());
        assertEquals("minecraft:bucket", payload.remainders().get(0).comparisonIdentity());
    }

    @Test
    void requestedQuantityScalesBatchesAndAggregatesMatchingRepeatedInputs() {
        var index = new KnowledgeIndex();
        index.replace(new dev.packsage.knowledge.nativegame.NativeSnapshot(SCOPE, List.of(repeatedInputRecipe(), rawRecipe())));

        var result = new RecipePlanner(index).plan("mod:output", 5, SCOPE, 4);

        assertEquals(Evidence.Outcome.COMPLETE, result.outcome());
        var root = result.records().stream().filter(record -> record.source().sourceHandle().equals("mod:output")).findFirst().orElseThrow();
        assertEquals(new Evidence.JsonNumber(new java.math.BigDecimal("3")), root.payload().values().get("batches"));
        assertEquals(new Evidence.JsonNumber(new java.math.BigDecimal("5")), root.payload().values().get("requestedQuantity"));
        assertEquals(1, result.records().stream().filter(record -> record.source().sourceHandle().equals("mod:raw")).count());
    }

    @Test
    void preservesAlternativeRecipesAndUnknownOutputQuantityAsPartial() {
        var index = new KnowledgeIndex();
        index.replace(new dev.packsage.knowledge.nativegame.NativeSnapshot(SCOPE, List.of(
                alternativeRecipe("mod:alternative-a", "mod:raw-a"), alternativeRecipe("mod:alternative-b", "mod:raw-b"),
                rawRecipe("mod:raw-a"), rawRecipe("mod:raw-b"), unknownRecipe())));

        var alternatives = new RecipePlanner(index).plan("mod:alternative", 1, SCOPE, 4);
        assertEquals(Evidence.Outcome.COMPLETE, alternatives.outcome());
        assertEquals(2, alternatives.records().stream().filter(record -> record.recipePayload() != null
                && record.recipePayload().outputs().stream().anyMatch(output -> output.comparisonIdentity().equals("mod:alternative"))).count());

        var unknown = new RecipePlanner(index).plan("mod:unknown", 1, SCOPE, 4);
        assertEquals(Evidence.Outcome.PARTIAL, unknown.outcome());
    }

    @Test
    void cycleAndUnknownQuantityProducePartialRouteWithoutZeroCostClaims() {
        var index = new KnowledgeIndex();
        index.replace(new dev.packsage.knowledge.nativegame.NativeSnapshot(SCOPE, List.of(cycleRecipe(), unknownRecipe())));

        var result = new RecipePlanner(index).plan("mod:cycle", 1, SCOPE, 1);

        assertEquals(Evidence.Outcome.PARTIAL, result.outcome());
        assertTrue(result.records().get(0).recipePayload().outputs().stream().allMatch(output -> output.quantity() == null || output.quantity() > 0));
        assertTrue(result.records().stream().anyMatch(record -> record.source().provenance().contains("bounded-depth")));
    }

    private static Evidence.Record recipe() {
        return record("mod:lantern", new Evidence.RecipePayload("mod:lantern", List.of(
                new Evidence.RecipeSlot("metal", List.of(new Evidence.RecipeAlternative("item", "minecraft:iron_ingot", new Evidence.JsonObject(Map.of()), 2, Evidence.Consumption.CONSUMED),
                        new Evidence.RecipeAlternative("item", "mod:steel_ingot", new Evidence.JsonObject(Map.of()), 2, Evidence.Consumption.CONSUMED))),
                new Evidence.RecipeSlot("fuel", List.of(new Evidence.RecipeAlternative("tag", "#minecraft:coals", new Evidence.JsonObject(Map.of()), 1, Evidence.Consumption.CONSUMED)))),
                List.of(new Evidence.RecipeOutput("item", "mod:lantern", new Evidence.JsonObject(Map.of()), 3)),
                List.of(new Evidence.RecipeOutput("item", "minecraft:bucket", new Evidence.JsonObject(Map.of()), 1)),
                List.of(new Evidence.RecipeAlternative("item", "minecraft:bucket", new Evidence.JsonObject(Map.of()), null, Evidence.Consumption.REUSED)),
                List.of("machine:verified"), Evidence.DecodingStatus.VERIFIED));
    }

    private static Evidence.Record cycleRecipe() {
        return record("mod:cycle", new Evidence.RecipePayload("mod:cycle", List.of(new Evidence.RecipeSlot("input",
                List.of(new Evidence.RecipeAlternative("item", "mod:cycle", new Evidence.JsonObject(Map.of()), 1, Evidence.Consumption.CONSUMED)))),
                List.of(new Evidence.RecipeOutput("item", "mod:cycle", new Evidence.JsonObject(Map.of()), 1)), List.of(), List.of(), List.of(), Evidence.DecodingStatus.VERIFIED));
    }

    private static Evidence.Record repeatedInputRecipe() {
        return record("mod:output", new Evidence.RecipePayload("mod:output", List.of(
                new Evidence.RecipeSlot("first", List.of(new Evidence.RecipeAlternative("item", "mod:raw",
                        new Evidence.JsonObject(Map.of()), 2, Evidence.Consumption.CONSUMED))),
                new Evidence.RecipeSlot("second", List.of(new Evidence.RecipeAlternative("item", "mod:raw",
                        new Evidence.JsonObject(Map.of()), 1, Evidence.Consumption.CONSUMED)))),
                List.of(new Evidence.RecipeOutput("item", "mod:output", new Evidence.JsonObject(Map.of()), 2)),
                List.of(), List.of(), List.of(), Evidence.DecodingStatus.VERIFIED));
    }

    private static Evidence.Record rawRecipe() {
        return rawRecipe("mod:raw");
    }

    private static Evidence.Record rawRecipe(String id) {
        return record(id, new Evidence.RecipePayload(id, List.of(),
                List.of(new Evidence.RecipeOutput("item", id, new Evidence.JsonObject(Map.of()), 1)),
                List.of(), List.of(), List.of(), Evidence.DecodingStatus.VERIFIED));
    }

    private static Evidence.Record alternativeRecipe(String id, String input) {
        return record(id, new Evidence.RecipePayload(id, List.of(new Evidence.RecipeSlot("input",
                List.of(new Evidence.RecipeAlternative("item", input, new Evidence.JsonObject(Map.of()), 1,
                        Evidence.Consumption.CONSUMED)))), List.of(new Evidence.RecipeOutput("item", "mod:alternative",
                new Evidence.JsonObject(Map.of()), 1)), List.of(), List.of(), List.of(), Evidence.DecodingStatus.VERIFIED));
    }

    private static Evidence.Record unknownRecipe() {
        return record("mod:unknown", new Evidence.RecipePayload("mod:unknown", List.of(), List.of(
                new Evidence.RecipeOutput("item", "mod:unknown", new Evidence.JsonObject(Map.of()), null)), List.of(), List.of(), List.of(), Evidence.DecodingStatus.UNKNOWN));
    }

    private static Evidence.Record record(String id, Evidence.RecipePayload payload) {
        var source = new Evidence.Source(SCOPE, "native", "1.21.1", id, 1, Instant.EPOCH, Evidence.Activity.ACTIVE,
                Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of("fixture"));
        return new Evidence.Record(source, Evidence.RecordKind.RECIPE, id, new Evidence.JsonObject(Map.of()), List.of(), payload);
    }
}
