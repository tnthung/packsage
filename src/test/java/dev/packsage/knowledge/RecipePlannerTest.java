package dev.packsage.knowledge;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals(1, unknown.records().size());
        assertTrue(unknown.records().getFirst().payload().values().get("batches") instanceof Evidence.JsonNull);
    }

    @Test
    void usesKnownInputsFromPartialRecipeWithoutClaimingCompletePlan() {
        var partial = partialRecipe("mod:partial-output", "mod:known-input");
        var index = new KnowledgeIndex();
        index.replace(new dev.packsage.knowledge.nativegame.NativeSnapshot(SCOPE, List.of(partial, rawRecipe("mod:known-input"))));

        var result = new RecipePlanner(index).plan("mod:partial-output", 2, SCOPE, 4);

        assertEquals(Evidence.Outcome.PARTIAL, result.outcome());
        assertTrue(result.records().stream().anyMatch(record -> record.source().sourceHandle().equals("mod:partial-output")));
        assertTrue(result.records().stream().anyMatch(record -> record.source().sourceHandle().equals("mod:known-input")));
        var root = result.records().stream().filter(record -> record.source().sourceHandle().equals("mod:partial-output"))
                .findFirst().orElseThrow();
        assertEquals(new java.math.BigDecimal("2"), ((Evidence.JsonNumber) root.payload().values().get("batches")).value());
    }

    @Test
    void cycleAndUnknownQuantityProducePartialRouteWithoutZeroCostClaims() {
        var index = new KnowledgeIndex();
        index.replace(new dev.packsage.knowledge.nativegame.NativeSnapshot(SCOPE, List.of(cycleRecipe(), unknownRecipe())));

        var result = new RecipePlanner(index).plan("mod:cycle", 1, SCOPE, 1);

        assertEquals(Evidence.Outcome.PARTIAL, result.outcome());
        assertTrue(result.records().get(0).recipePayload().outputs().stream().allMatch(output -> output.quantity() == null || output.quantity() > 0));
        assertTrue(result.records().stream().anyMatch(record -> record.source().provenance().contains("bounded-depth")));
        assertTrue(result.detail().contains("bounded-depth"));
    }

    @Test
    void partialPlanExplainsUnresolvedRawLeaves() {
        var index = new KnowledgeIndex();
        index.replace(new dev.packsage.knowledge.nativegame.NativeSnapshot(SCOPE, List.of(record("mod:output", new Evidence.RecipePayload("mod:output",
                List.of(new Evidence.RecipeSlot("input", List.of(new Evidence.RecipeAlternative("item", "mod:raw",
                        new Evidence.JsonObject(Map.of()), 1, Evidence.Consumption.CONSUMED)))),
                List.of(new Evidence.RecipeOutput("item", "mod:output", new Evidence.JsonObject(Map.of()), 1)),
                List.of(), List.of(), List.of(), Evidence.DecodingStatus.VERIFIED)))));

        var result = new RecipePlanner(index).plan("mod:output", 2, SCOPE, 2);

        assertEquals(Evidence.Outcome.PARTIAL, result.outcome());
        assertTrue(result.detail().contains("materials with no recipe here, obtained rather than crafted="));
        assertTrue(result.detail().contains("mod:raw"));
    }

    @Test
    void uncheckedMaterialsBeyondSixteenAreCounted() {
        var index = new KnowledgeIndex();
        index.replace(new dev.packsage.knowledge.nativegame.NativeSnapshot(SCOPE, List.of(record("mod:output", new Evidence.RecipePayload("mod:output",
                java.util.stream.IntStream.range(0, 18).mapToObj(i -> new Evidence.RecipeSlot("input-" + i, List.of(new Evidence.RecipeAlternative("item",
                        "mod:raw_" + i, new Evidence.JsonObject(Map.of()), 1, Evidence.Consumption.CONSUMED)))).toList(),
                List.of(new Evidence.RecipeOutput("item", "mod:output", new Evidence.JsonObject(Map.of()), 1)),
                List.of(), List.of(), List.of(), Evidence.DecodingStatus.VERIFIED)))));
        var unchecked = new RecipePlanner.Source() {
            @Override public boolean available(Evidence.Scope scope) { return index.available(scope); }
            @Override public List<Evidence.Record> recipeOutputs(String identity, Evidence.JsonObject components, Evidence.Scope scope) {
                return index.recipeOutputs(identity, components, scope);
            }
            @Override public boolean recipesComplete(String identity, Evidence.JsonObject components) { return false; }
        };

        var result = new RecipePlanner(unchecked).plan("mod:output", 1, SCOPE, 2);

        assertTrue(result.detail().contains("materials with no recipe found, 2 beyond the 16 listed"), result.detail());
    }

    @Test
    void aMaterialReachedThroughSeveralBranchesIsCountedOnce() {
        java.util.function.BiFunction<String, List<String>, Evidence.Record> recipe = (output, inputs) -> record(output, new Evidence.RecipePayload(output,
                inputs.stream().map(input -> new Evidence.RecipeSlot("slot:" + input, List.of(new Evidence.RecipeAlternative("item", input,
                        new Evidence.JsonObject(Map.of()), 1, Evidence.Consumption.CONSUMED)))).toList(),
                List.of(new Evidence.RecipeOutput("item", output, new Evidence.JsonObject(Map.of()), 1)), List.of(), List.of(), List.of(),
                Evidence.DecodingStatus.VERIFIED));
        var roots = new java.util.ArrayList<String>();
        for (int i = 0; i < 16; i++) roots.add("mod:raw_" + i);
        roots.add("mod:left");
        roots.add("mod:right");
        var index = new KnowledgeIndex();
        index.replace(new dev.packsage.knowledge.nativegame.NativeSnapshot(SCOPE, List.of(recipe.apply("mod:output", roots),
                recipe.apply("mod:left", List.of("mod:shared")), recipe.apply("mod:right", List.of("mod:shared")))));

        var result = new RecipePlanner(index).plan("mod:output", 1, SCOPE, 3);

        assertTrue(result.detail().contains("more materials with no recipe, 1 beyond the 16 listed"), result.detail());
    }

    @Test
    void anIncompleteRecipeLookupIsNotCalledAnObtainedMaterial() {
        var index = new KnowledgeIndex();
        index.replace(new dev.packsage.knowledge.nativegame.NativeSnapshot(SCOPE, List.of(record("mod:output", new Evidence.RecipePayload("mod:output",
                List.of(new Evidence.RecipeSlot("input", List.of(new Evidence.RecipeAlternative("item", "mod:hidden",
                        new Evidence.JsonObject(Map.of()), 1, Evidence.Consumption.CONSUMED)))),
                List.of(new Evidence.RecipeOutput("item", "mod:output", new Evidence.JsonObject(Map.of()), 1)),
                List.of(), List.of(), List.of(), Evidence.DecodingStatus.VERIFIED)))));
        var capped = new RecipePlanner.Source() {
            @Override public boolean available(Evidence.Scope scope) { return index.available(scope); }
            @Override public List<Evidence.Record> recipeOutputs(String identity, Evidence.JsonObject components, Evidence.Scope scope) {
                return index.recipeOutputs(identity, components, scope);
            }
            @Override public boolean recipesComplete(String identity, Evidence.JsonObject components) { return !identity.equals("mod:hidden"); }
        };

        var result = new RecipePlanner(capped).plan("mod:output", 1, SCOPE, 2);

        assertTrue(result.detail().contains("could still make them=[mod:hidden]"), result.detail());
        assertFalse(result.detail().contains("obtained rather than crafted"), result.detail());
    }

    @Test
    void brewingRoutesWithholdBatchCounts() {
        var index = new KnowledgeIndex();
        index.replace(new dev.packsage.knowledge.nativegame.NativeSnapshot(SCOPE, List.of(brewingRecord())));

        var result = new RecipePlanner(index).plan("minecraft:potion", 3, SCOPE, 1);

        var payload = result.records().getFirst().payload().values();
        assertEquals(new Evidence.JsonNull(), payload.get("batches"));
        assertEquals(new Evidence.JsonNull(), payload.get("producedQuantity"));
        assertEquals(new Evidence.JsonString("brewing: up to 3 bottles per operation, one reagent per operation; "
                + "operation count depends on bottle grouping and is not computed"), payload.get("operationSemantics"));
        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
    }

    @Test
    void jeiBrewingRoutesWithholdBatchCounts() {
        var components = new Evidence.JsonObject(Map.of("minecraft:potion_contents", new Evidence.JsonString("minecraft:strength")));
        var layout = new dev.packsage.jei.JeiLayout("minecraft:brewing", "Brewing", null, 3, 7, false, true, Evidence.DecodingStatus.PARTIAL, List.of(
                new dev.packsage.jei.JeiLayout.Slot(0, dev.packsage.jei.JeiLayout.Role.INPUT, List.of(new dev.packsage.jei.JeiLayout.Ingredient("item", "minecraft:potion", "{}", 1, null)), List.of()),
                new dev.packsage.jei.JeiLayout.Slot(1, dev.packsage.jei.JeiLayout.Role.OUTPUT, List.of(new dev.packsage.jei.JeiLayout.Ingredient("item", "minecraft:potion", Evidence.serialize(components), 1, null)), List.of())), List.of());
        var jei = dev.packsage.jei.JeiProjection.record(layout, SCOPE, "19.27", java.time.Instant.EPOCH);
        var index = new KnowledgeIndex();
        index.replace(new dev.packsage.knowledge.nativegame.NativeSnapshot(SCOPE, List.of(jei)));

        var result = new RecipePlanner(index).plan("minecraft:potion", 3, SCOPE, 1);

        var payload = result.records().getFirst().payload().values();
        assertEquals(new Evidence.JsonNull(), payload.get("batches"));
        assertTrue(payload.containsKey("operationSemantics"), payload.toString());
        assertTrue(result.detail().contains("operation-count-unknown:"), result.detail());
    }

    @Test
    void oneBucketForEightCrafts() {
        var mud = machine("mod:mix_mud", List.of(slot("slot-0", item("minecraft:water_bucket", 1)), slot("slot-1", item("minecraft:dirt", 1))),
                List.of(output("mod:mud", 1)), List.of("slot-2"), List.of(), List.of("container-returned=minecraft:water_bucket>minecraft:bucket"));
        var source = source(mud, rawRecipe("minecraft:dirt"), craft("minecraft:water_bucket", "minecraft:bucket"),
                craft("minecraft:bucket", "minecraft:iron_ingot"), rawRecipe("minecraft:iron_ingot"));

        var result = new RecipePlanner(source).plan("mod:mud", 8, SCOPE, 4);

        var route = route(result, "mod:mix_mud");
        assertEquals(new Evidence.JsonArray(List.of(need("contents", "minecraft:water_bucket", 8))), route.payload().values().get("unexpandedNeeds"));
        assertTrue(result.detail().contains("equipment=[minecraft:bucket]"), result.detail());
        assertEquals(number(8), route(result, "minecraft:dirt").payload().values().get("requestedQuantity"));
        assertTrue(result.records().stream().noneMatch(record -> List.of("minecraft:water_bucket", "minecraft:bucket", "minecraft:iron_ingot")
                .contains(record.source().sourceHandle())), result.records().toString());
    }

    @Test
    void fluidInputListedNotExpanded() {
        var clay = machine("mod:wet_clay", List.of(slot("slot-0", new Evidence.RecipeAlternative("fluid", "minecraft:water",
                        new Evidence.JsonObject(Map.of()), 1000, Evidence.Consumption.CONSUMED)), slot("slot-1", item("minecraft:dirt", 1))),
                List.of(output("mod:clay", 1)), List.of("slot-2"), List.of(), List.of());

        var result = new RecipePlanner(source(clay, rawRecipe("minecraft:dirt"))).plan("mod:clay", 3, SCOPE, 4);

        var route = route(result, "mod:wet_clay");
        assertEquals(new Evidence.JsonArray(List.of(need("fluid", "minecraft:water", 3000))), route.payload().values().get("unexpandedNeeds"));
        assertFalse(result.detail().contains("unknown-consumption-or-requirement"), result.detail());
        assertEquals(number(3), route(result, "minecraft:dirt").payload().values().get("requestedQuantity"));
    }

    @Test
    void containerAlternativesAreChoicesNotRefillRoutes() {
        var either = slot("slot-0", item("minecraft:water_bucket", 1), item("mod:brine_bucket", 1));
        var mud = machine("mod:mix_mud", List.of(either, either, slot("slot-1", item("minecraft:dirt", 1))), List.of(output("mod:mud", 1)),
                List.of("slot-2"), List.of(), List.of("container-returned=minecraft:water_bucket>minecraft:bucket",
                        "container-returned=mod:brine_bucket>minecraft:bucket"));
        var source = source(mud, rawRecipe("minecraft:dirt"), craft("minecraft:water_bucket", "minecraft:bucket"),
                craft("mod:brine_bucket", "minecraft:bucket"), craft("minecraft:bucket", "minecraft:iron_ingot"), rawRecipe("minecraft:iron_ingot"));

        var result = new RecipePlanner(source).plan("mod:mud", 8, SCOPE, 4);

        assertEquals(new Evidence.JsonArray(List.of(choice("contents", "minecraft:water_bucket", 16, "slot-0"),
                choice("contents", "mod:brine_bucket", 16, "slot-0"))), route(result, "mod:mix_mud").payload().values().get("unexpandedNeeds"));
        assertTrue(result.detail().contains("equipment=[minecraft:bucket]"), result.detail());
        assertFalse(result.detail().contains("unknown-consumption-or-requirement"), result.detail());
        assertEquals(number(8), route(result, "minecraft:dirt").payload().values().get("requestedQuantity"));
        assertTrue(result.records().stream().noneMatch(record -> List.of("minecraft:water_bucket", "mod:brine_bucket", "minecraft:bucket",
                "minecraft:iron_ingot").contains(record.source().sourceHandle())), result.records().toString());
    }

    @Test
    void containersThatDependOnTheChosenAlternativeAreNotPlanEquipment() {
        var either = slot("slot-0", item("minecraft:water_bucket", 1), item("mod:water_jar", 1));
        var mud = machine("mod:mix_mud", List.of(either, slot("slot-1", item("minecraft:dirt", 1))), List.of(output("mod:mud", 1)),
                List.of("slot-2"), List.of(), List.of("container-returned=minecraft:water_bucket>minecraft:bucket",
                        "container-returned=mod:water_jar>mod:jar"));

        var result = new RecipePlanner(source(mud, rawRecipe("minecraft:dirt"))).plan("mod:mud", 8, SCOPE, 4);

        assertEquals(new Evidence.JsonArray(List.of(withContainer(choice("contents", "minecraft:water_bucket", 8, "slot-0"), "minecraft:bucket"),
                withContainer(choice("contents", "mod:water_jar", 8, "slot-0"), "mod:jar"))), route(result, "mod:mix_mud").payload().values().get("unexpandedNeeds"));
        assertFalse(result.detail().contains("equipment="), result.detail());
    }

    private static Evidence.JsonObject withContainer(Evidence.JsonObject need, String container) {
        var values = new java.util.LinkedHashMap<>(need.values());
        values.put("container", new Evidence.JsonString(container));
        return new Evidence.JsonObject(values);
    }

    @Test
    void fluidAlternativesAreListedAsChoices() {
        var clay = machine("mod:wet_clay", List.of(slot("slot-0", new Evidence.RecipeAlternative("fluid", "minecraft:water",
                        new Evidence.JsonObject(Map.of()), 250, Evidence.Consumption.CONSUMED), new Evidence.RecipeAlternative("fluid", "mod:brine",
                        new Evidence.JsonObject(Map.of()), 500, Evidence.Consumption.CONSUMED)), slot("slot-1", item("minecraft:dirt", 1))),
                List.of(output("mod:clay", 1)), List.of("slot-2"), List.of(), List.of());

        var result = new RecipePlanner(source(clay, rawRecipe("minecraft:dirt"))).plan("mod:clay", 3, SCOPE, 4);

        assertEquals(new Evidence.JsonArray(List.of(choice("fluid", "minecraft:water", 750, "slot-0"), choice("fluid", "mod:brine", 1500, "slot-0"))),
                route(result, "mod:wet_clay").payload().values().get("unexpandedNeeds"));
        assertFalse(result.detail().contains("unknown-consumption-or-requirement"), result.detail());
        assertTrue(result.records().stream().noneMatch(record -> List.of("minecraft:water", "mod:brine").contains(record.source().sourceHandle())));
    }

    private static Evidence.JsonObject potion(String effect) {
        return new Evidence.JsonObject(Map.of("minecraft:potion_contents", new Evidence.JsonObject(Map.of("potion", new Evidence.JsonString(effect)))));
    }

    private static Evidence.RecipeAlternative fluid(String identity, Evidence.JsonObject components, int quantity) {
        return new Evidence.RecipeAlternative("fluid", identity, components, quantity, Evidence.Consumption.CONSUMED);
    }

    private static Evidence.JsonObject withComponents(Evidence.JsonObject need, Evidence.JsonObject components) {
        var values = new java.util.LinkedHashMap<>(need.values());
        values.put("components", components);
        return new Evidence.JsonObject(values);
    }

    @Test
    void componentFluidNeedsKeepTheirIdentity() {
        var strength = potion("minecraft:strength");
        var healing = potion("minecraft:healing");
        var brew = machine("mod:blend", List.of(slot("slot-0", fluid("mod:potion", strength, 250)), slot("slot-1", fluid("mod:potion", healing, 500)),
                        slot("slot-2", fluid("mod:potion", potion("minecraft:healing"), 100))),
                List.of(output("mod:tonic", 1)), List.of("slot-3"), List.of(), List.of());

        var result = new RecipePlanner(source(brew)).plan("mod:tonic", 3, SCOPE, 4);

        assertEquals(new Evidence.JsonArray(List.of(withComponents(need("fluid", "mod:potion", 750), strength),
                withComponents(need("fluid", "mod:potion", 1800), healing))), route(result, "mod:blend").payload().values().get("unexpandedNeeds"));
    }

    @Test
    void componentFluidAlternativesStaySeparateChoicesWithTheirComponents() {
        var strength = potion("minecraft:strength");
        var healing = potion("minecraft:healing");
        var brew = machine("mod:blend", List.of(slot("slot-0", fluid("mod:potion", strength, 250), fluid("mod:potion", healing, 250))),
                List.of(output("mod:tonic", 1)), List.of("slot-1"), List.of(), List.of());

        var result = new RecipePlanner(source(brew)).plan("mod:tonic", 2, SCOPE, 4);

        assertEquals(new Evidence.JsonArray(List.of(withComponents(choice("fluid", "mod:potion", 500, "slot-0"), strength),
                withComponents(choice("fluid", "mod:potion", 500, "slot-0"), healing))), route(result, "mod:blend").payload().values().get("unexpandedNeeds"));
    }

    @Test
    void sameFluidAlternativesStaySeparateChoices() {
        var clay = machine("mod:wet_clay", List.of(slot("slot-0", new Evidence.RecipeAlternative("fluid", "minecraft:water",
                        new Evidence.JsonObject(Map.of()), 250, Evidence.Consumption.CONSUMED), new Evidence.RecipeAlternative("fluid", "minecraft:water",
                        new Evidence.JsonObject(Map.of()), 500, Evidence.Consumption.CONSUMED)), slot("slot-1", item("minecraft:dirt", 1))),
                List.of(output("mod:clay", 1)), List.of("slot-2"), List.of(), List.of());

        var result = new RecipePlanner(source(clay, rawRecipe("minecraft:dirt"))).plan("mod:clay", 3, SCOPE, 4);

        assertEquals(new Evidence.JsonArray(List.of(choice("fluid", "minecraft:water", 750, "slot-0"), choice("fluid", "minecraft:water", 1500, "slot-0"))),
                route(result, "mod:wet_clay").payload().values().get("unexpandedNeeds"));
    }

    @Test
    void reusedCatalystCountedOnce() {
        var altar = machine("mod:infuse", List.of(slot("slot-0", item("minecraft:iron_ingot", 1))), List.of(output("mod:infused", 1)),
                List.of("slot-1"), List.of(new Evidence.RecipeAlternative("item", "naturesaura:altar", new Evidence.JsonObject(Map.of()), 1,
                        Evidence.Consumption.REUSED)), List.of());

        var result = new RecipePlanner(source(altar, rawRecipe("minecraft:iron_ingot"))).plan("mod:infused", 5, SCOPE, 4);

        assertTrue(result.detail().contains("equipment=[naturesaura:altar]"), result.detail());
        assertFalse(result.detail().contains("catalysts-present"), result.detail());
        assertEquals(number(5), route(result, "minecraft:iron_ingot").payload().values().get("requestedQuantity"));
    }

    @Test
    void onlyPlannedItemCounts() {
        var grind = grinder(List.of());

        var dust = new RecipePlanner(source(grind, rawRecipe("minecraft:iron_ore"))).plan("mod:iron_dust", 16, SCOPE, 4);

        var route = route(dust, "mod:grind");
        assertEquals(number(8), route.payload().values().get("batches"));
        assertEquals(number(16), route.payload().values().get("producedQuantity"));
        assertEquals(number(8), route(dust, "minecraft:iron_ore").payload().values().get("requestedQuantity"));
        var extras = (Evidence.JsonArray) route.payload().values().get("possibleExtras");
        assertEquals(1, extras.values().size());
        assertEquals(new Evidence.JsonString("mod:gold_dust"), ((Evidence.JsonObject) extras.values().getFirst()).values().get("comparisonIdentity"));

        var alloy = record("mod:alloy", new Evidence.RecipePayload("mod:alloy", List.of(slot("iron", item("mod:iron_dust", 16)),
                slot("gold", item("mod:gold_dust", 4))), List.of(new Evidence.RecipeOutput("item", "mod:alloy", new Evidence.JsonObject(Map.of()), 1)),
                List.of(), List.of(), List.of(), Evidence.DecodingStatus.VERIFIED));
        var both = new RecipePlanner(source(alloy, grind, rawRecipe("minecraft:iron_ore"))).plan("mod:alloy", 1, SCOPE, 4);

        var grindRoutes = both.records().stream().filter(record -> record.source().sourceHandle().equals("mod:grind")).toList();
        assertEquals(List.of(number(16), number(4)), grindRoutes.stream().map(record -> record.payload().values().get("requestedQuantity")).toList());
        assertEquals(List.of(number(8), number(4)), grindRoutes.stream().map(record -> record.payload().values().get("batches")).toList());
    }

    @Test
    void chanceOutputNeverCounts() {
        var grind = grinder(List.of("chance:slot-2=10% Chance"));

        var gold = new RecipePlanner(source(grind, rawRecipe("minecraft:iron_ore"))).plan("mod:gold_dust", 4, SCOPE, 4);

        var route = route(gold, "mod:grind");
        assertEquals(new Evidence.JsonNull(), route.payload().values().get("batches"));
        assertEquals(new Evidence.JsonNull(), route.payload().values().get("producedQuantity"));
        assertTrue(gold.detail().contains("chance-output:mod:grind"), gold.detail());
        assertTrue(gold.records().stream().noneMatch(record -> record.source().sourceHandle().equals("minecraft:iron_ore")));

        var iron = new RecipePlanner(source(grind, rawRecipe("minecraft:iron_ore"))).plan("mod:iron_dust", 16, SCOPE, 4);
        assertEquals(number(8), route(iron, "mod:grind").payload().values().get("batches"));
        assertFalse(iron.detail().contains("chance-output"), iron.detail());
    }

    @Test
    void possibleExtrasListOnlyOutputsOtherThanThePlannedItem() {
        var torch = dev.packsage.jei.JeiProjection.record(new dev.packsage.jei.JeiLayout("minecraft:crafting", "Crafting", "minecraft:torch", 0, 1,
                false, true, Evidence.DecodingStatus.VERIFIED, List.of(
                new dev.packsage.jei.JeiLayout.Slot(0, dev.packsage.jei.JeiLayout.Role.INPUT,
                        List.of(new dev.packsage.jei.JeiLayout.Ingredient("item", "minecraft:stick", "{}", 1, null)), List.of()),
                new dev.packsage.jei.JeiLayout.Slot(1, dev.packsage.jei.JeiLayout.Role.OUTPUT,
                        List.of(new dev.packsage.jei.JeiLayout.Ingredient("item", "minecraft:torch", "{}", 4, null)), List.of())), List.of()),
                SCOPE, "19.27", Instant.EPOCH);
        var torches = new RecipePlanner(source(torch, rawRecipe("minecraft:stick"))).plan("minecraft:torch", 4, SCOPE, 4);
        assertFalse(route(torches, torch.source().sourceHandle()).payload().values().containsKey("possibleExtras"));

        var split = record("mod:split", new Evidence.RecipePayload("mod:split", List.of(slot("slot-0", item("mod:log", 1))),
                List.of(output("mod:plank", 4), output("mod:bark", 1)), List.of(), List.of(), List.of(), Evidence.DecodingStatus.VERIFIED));
        var planks = new RecipePlanner(source(split, rawRecipe("mod:log"))).plan("mod:plank", 8, SCOPE, 4);
        var extras = (Evidence.JsonArray) route(planks, "mod:split").payload().values().get("possibleExtras");
        assertEquals(List.of(new Evidence.JsonString("mod:bark")),
                extras.values().stream().map(value -> ((Evidence.JsonObject) value).values().get("comparisonIdentity")).toList());

        var gold = new RecipePlanner(source(grinder(List.of("chance:slot-2=10% Chance")), rawRecipe("minecraft:iron_ore"))).plan("mod:gold_dust", 4, SCOPE, 4);
        var chanceExtras = (Evidence.JsonArray) route(gold, "mod:grind").payload().values().get("possibleExtras");
        assertEquals(List.of(new Evidence.JsonString("mod:iron_dust")),
                chanceExtras.values().stream().map(value -> ((Evidence.JsonObject) value).values().get("comparisonIdentity")).toList());
    }

    @Test
    void assumedStepsMarked() {
        var result = new RecipePlanner(source(grinder(List.of()), rawRecipe("minecraft:iron_ore"))).plan("mod:iron_dust", 2, SCOPE, 4);

        assertEquals(new Evidence.JsonBoolean(true), route(result, "mod:grind").payload().values().get("assumed"));
        assertFalse(route(result, "minecraft:iron_ore").payload().values().containsKey("assumed"));
        assertTrue(result.detail().contains("assumed-consumption:mod:grind"), result.detail());
        assertEquals(Evidence.Outcome.PARTIAL, result.outcome());
    }

    @Test
    void nativePlansUnchanged() {
        var beacon = record("minecraft:beacon", new Evidence.RecipePayload("minecraft:beacon", List.of(slot("slot-0", item("minecraft:glass", 5)),
                slot("slot-1", item("minecraft:nether_star", 1)), slot("slot-2", item("minecraft:obsidian", 3))),
                List.of(new Evidence.RecipeOutput("item", "minecraft:beacon", new Evidence.JsonObject(Map.of()), 1)), List.of(), List.of(),
                List.of("recipeType=minecraft:crafting", "serializer=minecraft:crafting_shaped", "shape=3x3"), Evidence.DecodingStatus.VERIFIED));
        var glass = record("minecraft:glass", new Evidence.RecipePayload("minecraft:glass", List.of(slot("slot-0",
                item("minecraft:sand", 1), item("minecraft:red_sand", 1))),
                List.of(new Evidence.RecipeOutput("item", "minecraft:glass", new Evidence.JsonObject(Map.of()), 1)), List.of(), List.of(),
                List.of("recipeType=minecraft:smelting"), Evidence.DecodingStatus.VERIFIED));
        var torch = record("minecraft:torch", new Evidence.RecipePayload("minecraft:torch", List.of(slot("slot-0",
                item("minecraft:coal", 1), item("minecraft:charcoal", 1)), slot("slot-1", item("minecraft:stick", 1))),
                List.of(new Evidence.RecipeOutput("item", "minecraft:torch", new Evidence.JsonObject(Map.of()), 4)), List.of(), List.of(),
                List.of("recipeType=minecraft:crafting", "shape=1x2"), Evidence.DecodingStatus.VERIFIED));
        var stick = record("minecraft:stick", new Evidence.RecipePayload("minecraft:stick", List.of(slot("slot-0", item("minecraft:oak_planks", 1)),
                slot("slot-1", item("minecraft:oak_planks", 1))),
                List.of(new Evidence.RecipeOutput("item", "minecraft:stick", new Evidence.JsonObject(Map.of()), 4)),
                List.of(new Evidence.RecipeOutput("item", "minecraft:bowl", new Evidence.JsonObject(Map.of()), 1)),
                List.of(new Evidence.RecipeAlternative("item", "minecraft:crafting_table", new Evidence.JsonObject(Map.of()), null,
                        Evidence.Consumption.REUSED)), List.of("recipeType=minecraft:crafting"), Evidence.DecodingStatus.VERIFIED));
        var source = source(beacon, glass, torch, stick, rawRecipe("minecraft:obsidian"), rawRecipe("minecraft:sand"),
                rawRecipe("minecraft:coal"), rawRecipe("minecraft:oak_planks"));

        var fixtures = List.of(beacon, glass, torch, stick);

        // Captured from the planner before mod machine rules were added.
        assertEquals("""
                minecraft:beacon COMPLETE [fixture] {batches=2, producedQuantity=2, requestedQuantity=2}
                minecraft:glass COMPLETE [fixture] {batches=10, producedQuantity=10, requestedQuantity=10}
                minecraft:sand COMPLETE [fixture] {batches=10, producedQuantity=10, requestedQuantity=10}
                minecraft:obsidian COMPLETE [fixture] {batches=6, producedQuantity=6, requestedQuantity=6}
                PARTIAL PARTIAL null materials with no recipe here, obtained rather than crafted=[minecraft:red_sand, minecraft:nether_star]""",
                canonical(new RecipePlanner(source).plan("minecraft:beacon", 2, SCOPE, 4), fixtures));
        assertEquals("""
                minecraft:torch COMPLETE [fixture] {batches=3, producedQuantity=12, requestedQuantity=9}
                minecraft:coal COMPLETE [fixture] {batches=3, producedQuantity=3, requestedQuantity=3}
                minecraft:stick COMPLETE [fixture] {batches=1, producedQuantity=4, requestedQuantity=3}
                minecraft:oak_planks COMPLETE [fixture] {batches=2, producedQuantity=2, requestedQuantity=2}
                PARTIAL PARTIAL null catalysts-present:minecraft:stick; materials with no recipe here, obtained rather than crafted=[minecraft:charcoal]""",
                canonical(new RecipePlanner(source).plan("minecraft:torch", 9, SCOPE, 4), fixtures));
    }

    @Test
    void everyRouteForThePlannedItemLeadsAManyRoutePlan() {
        // A large pack: each route for the alloy has inputs with 64 routes each, so the inputs' routes alone exceed the node cap.
        var records = new java.util.ArrayList<Evidence.Record>();
        for (int route = 0; route < 10; route++) {
            var inputs = new java.util.ArrayList<Evidence.RecipeSlot>();
            for (int input = 0; input < 3; input++) {
                var material = "mod:material_" + route + "_" + input;
                inputs.add(slot("slot-" + input, item(material, 1)));
                for (int source = 0; source < 64; source++) records.add(craft("mod:make_" + route + "_" + input + "_" + source, material, "mod:raw_" + source));
            }
            records.add(record("mod:alloy_" + route, new Evidence.RecipePayload("mod:alloy_" + route, inputs, List.of(output("mod:alloy", 1)),
                    List.of(), List.of(), List.of(), Evidence.DecodingStatus.VERIFIED)));
        }
        var source = new RecipePlanner.Source() {
            @Override public boolean available(Evidence.Scope scope) { return true; }
            @Override public List<Evidence.Record> recipeOutputs(String identity, Evidence.JsonObject components, Evidence.Scope scope) {
                return records.stream().filter(record -> record.recipePayload().outputs().stream()
                        .anyMatch(output -> output.comparisonIdentity().equals(identity))).toList();
            }
        };

        var result = new RecipePlanner(source).plan("mod:alloy", 8, SCOPE, 1);

        assertEquals(java.util.stream.IntStream.range(0, 10).mapToObj(route -> "mod:alloy_" + route).toList(),
                result.records().stream().limit(10).map(record -> record.source().sourceHandle()).toList());
        for (var route : result.records().subList(0, 10)) assertEquals(number(8), route.payload().values().get("batches"));
        assertTrue(result.detail().contains("node-cap=256"), result.detail());
        assertEquals(Evidence.Outcome.PARTIAL, result.outcome());
    }

    /** Key-sorted rendering, so the comparison does not depend on JsonObject's unordered map. */
    private static String canonical(Evidence.Result result, List<Evidence.Record> fixtures) {
        var text = new StringBuilder();
        for (var record : result.records()) {
            fixtures.stream().filter(fixture -> fixture.source().sourceHandle().equals(record.source().sourceHandle())).findFirst().ifPresent(fixture -> {
                assertEquals(fixture.source(), record.source());
                assertEquals(fixture.title(), record.title());
                assertEquals(fixture.targets(), record.targets());
                assertEquals(fixture.recipePayload(), record.recipePayload());
            });
            text.append(record.source().sourceHandle()).append(' ').append(record.source().coverage()).append(' ')
                    .append(record.source().provenance()).append(' ').append(canonical(record.payload())).append('\n');
        }
        return text.append(result.coverage()).append(' ').append(result.outcome()).append(' ').append(result.nextCursor())
                .append(' ').append(result.detail()).toString();
    }

    private static String canonical(Evidence.JsonValue value) {
        if (value instanceof Evidence.JsonObject object) return new java.util.TreeMap<>(object.values()).entrySet().stream()
                .map(entry -> entry.getKey() + "=" + canonical(entry.getValue())).collect(java.util.stream.Collectors.joining(", ", "{", "}"));
        if (value instanceof Evidence.JsonArray array) return array.values().stream().map(RecipePlannerTest::canonical)
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
        if (value instanceof Evidence.JsonNumber number) return number.value().toPlainString();
        if (value instanceof Evidence.JsonString string) return '"' + string.value() + '"';
        return value.toString();
    }

    private static Evidence.Record grinder(List<String> extraConstraints) {
        return machine("mod:grind", List.of(slot("slot-0", item("minecraft:iron_ore", 1))),
                List.of(output("mod:iron_dust", 2), output("mod:gold_dust", 1)), List.of("slot-1", "slot-2"), List.of(), extraConstraints);
    }

    private static Evidence.Record machine(String id, List<Evidence.RecipeSlot> inputs, List<Evidence.RecipeOutput> outputs, List<String> outputGroups,
                                           List<Evidence.RecipeAlternative> catalysts, List<String> extraConstraints) {
        var constraints = new java.util.ArrayList<>(List.of("recipeType=mod:machine", "consumption=assumed", "output=assumed-guaranteed",
                "not-read=values drawn by the category"));
        constraints.addAll(extraConstraints);
        var remainders = constraints.stream().filter(value -> value.startsWith("container-returned="))
                .map(value -> output(value.substring(value.indexOf('>') + 1), 1)).toList();
        var groups = outputGroups.stream().map(group -> (Evidence.JsonValue) new Evidence.JsonObject(Map.of("groupId", new Evidence.JsonString(group))))
                .toList();
        var source = new Evidence.Source(SCOPE, "jei", "19.27", id, 1, Instant.EPOCH, Evidence.Activity.ACTIVE,
                Evidence.Coverage.PARTIAL, Evidence.Authority.LOADED, List.of("fixture"));
        return new Evidence.Record(source, Evidence.RecordKind.RECIPE, id, new Evidence.JsonObject(Map.of("outputs", new Evidence.JsonArray(groups))),
                List.of(), new Evidence.RecipePayload(id, inputs, outputs, remainders, catalysts, constraints, Evidence.DecodingStatus.PARTIAL));
    }

    private static Evidence.RecipeOutput output(String id, int quantity) {
        return new Evidence.RecipeOutput("item", id, new Evidence.JsonObject(Map.of()), quantity);
    }

    private static Evidence.Record craft(String id, String input) {
        return record(id, new Evidence.RecipePayload(id, List.of(slot("slot-0", item(input, 1))), List.of(output(id, 1)), List.of(), List.of(),
                List.of(), Evidence.DecodingStatus.VERIFIED));
    }

    private static Evidence.Record craft(String id, String product, String input) {
        return record(id, new Evidence.RecipePayload(id, List.of(slot("slot-0", item(input, 1))), List.of(output(product, 1)), List.of(), List.of(),
                List.of(), Evidence.DecodingStatus.VERIFIED));
    }

    private static Evidence.RecipeSlot slot(String groupId, Evidence.RecipeAlternative... alternatives) {
        return new Evidence.RecipeSlot(groupId, List.of(alternatives));
    }

    private static Evidence.RecipeAlternative item(String id, int quantity) {
        return new Evidence.RecipeAlternative("item", id, new Evidence.JsonObject(Map.of()), quantity, Evidence.Consumption.CONSUMED);
    }

    private static Evidence.JsonObject need(String type, String identity, long quantity) {
        var values = new java.util.LinkedHashMap<String, Evidence.JsonValue>();
        values.put("type", new Evidence.JsonString(type));
        values.put("identity", new Evidence.JsonString(identity));
        values.put("quantity", number(quantity));
        return new Evidence.JsonObject(values);
    }

    private static Evidence.JsonObject choice(String type, String identity, long quantity, String oneOf) {
        var values = new java.util.LinkedHashMap<>(need(type, identity, quantity).values());
        values.put("oneOf", new Evidence.JsonString(oneOf));
        return new Evidence.JsonObject(values);
    }

    private static Evidence.JsonNumber number(long value) {
        return new Evidence.JsonNumber(java.math.BigDecimal.valueOf(value));
    }

    private static Evidence.Record route(Evidence.Result result, String handle) {
        return result.records().stream().filter(record -> record.source().sourceHandle().equals(handle)).findFirst()
                .orElseThrow(() -> new AssertionError(handle + " not in " + result.records()));
    }

    private static RecipePlanner.Source source(Evidence.Record... records) {
        return new RecipePlanner.Source() {
            @Override public boolean available(Evidence.Scope scope) { return true; }
            @Override public List<Evidence.Record> recipeOutputs(String identity, Evidence.JsonObject components, Evidence.Scope scope) {
                return java.util.Arrays.stream(records).filter(record -> record.recipePayload().outputs().stream()
                        .anyMatch(output -> output.comparisonIdentity().equals(identity))).toList();
            }
        };
    }

    private static Evidence.Record brewingRecord() {
        var components = new Evidence.JsonObject(Map.of("minecraft:potion_contents", new Evidence.JsonString("minecraft:strength")));
        var record = record("minecraft:potion", new Evidence.RecipePayload("minecraft:potion", List.of(new Evidence.RecipeSlot("input",
                List.of(new Evidence.RecipeAlternative("item", "minecraft:potion", new Evidence.JsonObject(Map.of()), 1,
                        Evidence.Consumption.CONSUMED)))), List.of(new Evidence.RecipeOutput("item", "minecraft:potion", components, 1)),
                List.of(), List.of(), List.of("brewing-batch=up-to-3-bottles; reagent=one-per-operation; fuel=unknown-per-operation; remainders=unknown"),
                Evidence.DecodingStatus.PARTIAL));
        var source = record.source();
        return new Evidence.Record(new Evidence.Source(source.scope(), source.providerId(), source.providerVersion(),
                source.sourceHandle(), source.observedGeneration(), source.observedAt(), source.activity(),
                Evidence.Coverage.PARTIAL, source.authority(), source.provenance()), record.kind(), record.title(),
                record.payload(), record.targets(), record.recipePayload());
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

    private static Evidence.Record partialRecipe(String id, String input) {
        var record = record(id, new Evidence.RecipePayload(id, List.of(new Evidence.RecipeSlot("input",
                List.of(new Evidence.RecipeAlternative("item", input, new Evidence.JsonObject(Map.of()), 1,
                        Evidence.Consumption.CONSUMED)))), List.of(new Evidence.RecipeOutput("item", id,
                new Evidence.JsonObject(Map.of()), 1)), List.of(), List.of(), List.of("custom behavior unknown"),
                Evidence.DecodingStatus.PARTIAL));
        var source = record.source();
        return new Evidence.Record(new Evidence.Source(source.scope(), source.providerId(), source.providerVersion(),
                source.sourceHandle(), source.observedGeneration(), source.observedAt(), source.activity(),
                Evidence.Coverage.PARTIAL, source.authority(), source.provenance()), record.kind(), record.title(),
                record.payload(), record.targets(), record.recipePayload());
    }

    private static Evidence.Record record(String id, Evidence.RecipePayload payload) {
        var source = new Evidence.Source(SCOPE, "native", "1.21.1", id, 1, Instant.EPOCH, Evidence.Activity.ACTIVE,
                Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of("fixture"));
        return new Evidence.Record(source, Evidence.RecordKind.RECIPE, id, new Evidence.JsonObject(Map.of()), List.of(), payload);
    }
}
