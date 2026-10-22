package dev.packsage.knowledge.jei;

import dev.packsage.knowledge.AdapterRegistry;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.model.PackSageTools;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JeiPlanningTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "jei-1", 1);

    @Test
    void planFollowsConsumedInputsThroughOneLookupPerItem() {
        var lookups = new LinkedHashMap<String, Integer>();
        var provider = provider(lookups, item -> switch (item) {
            case "minecraft:torch" -> List.of(crafting("torch", List.of(
                    input("coal", item("minecraft:coal", 1), item("minecraft:charcoal", 1)),
                    input("stick", item("minecraft:stick", 1))), item("minecraft:torch", 4)));
            case "minecraft:stick" -> List.of(crafting("stick", List.of(
                    input("planks-top", item("minecraft:oak_planks", 1)),
                    input("planks-bottom", item("minecraft:oak_planks", 1))), item("minecraft:stick", 4)));
            case "minecraft:oak_planks" -> List.of(crafting("oak_planks", List.of(
                    input("log", item("minecraft:oak_log", 1))), item("minecraft:oak_planks", 4)));
            default -> List.of();
        });

        var result = plan(provider, Map.of("identity", text("minecraft:torch"), "quantity", number(4)));

        assertEquals(List.of("minecraft:torch", "minecraft:coal", "minecraft:charcoal", "minecraft:stick",
                "minecraft:oak_planks", "minecraft:oak_log"), List.copyOf(lookups.keySet()));
        assertTrue(lookups.values().stream().allMatch(count -> count == 1), () -> "repeated lookups: " + lookups);
        assertTrue(result.records().stream().allMatch(record -> record.source().providerId().equals(JeiProvider.ID)));
        var torch = route(result, "torch");
        assertEquals("minecraft:torch", torch.recipePayload().outputs().getFirst().comparisonIdentity());
        assertEquals("minecraft:stick", torch.recipePayload().inputs().get(1).alternatives().getFirst().comparisonIdentity());
        assertEquals(new BigDecimal("1"), ((Evidence.JsonNumber) torch.payload().values().get("batches")).value());
        assertFalse(torch.targets().isEmpty(), "a JEI route keeps its recipe link");
        var stick = route(result, "stick");
        assertEquals(new BigDecimal("1"), ((Evidence.JsonNumber) stick.payload().values().get("batches")).value());
        var planks = route(result, "oak_planks");
        assertEquals(new BigDecimal("1"), ((Evidence.JsonNumber) planks.payload().values().get("batches")).value());
        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
    }

    @Test
    void brewingRoutesWithholdBatchCounts() {
        var provider = provider(new LinkedHashMap<>(), item -> item.equals("minecraft:potion") ? List.of(new JeiProvider.Projection(
                "minecraft:brewing", "jei/1/1/minecraft:brewing/strength", null, "Brewing",
                List.of(input("ingredient", item("minecraft:blaze_powder", 1)), input("bottle", item("minecraft:potion", 1)),
                        new JeiProvider.Slot("output", List.of(item("minecraft:potion", 1)), JeiProvider.RecipeRole.OUTPUT)),
                List.of(), List.of(), true, true, Evidence.Coverage.COMPLETE, Evidence.DecodingStatus.VERIFIED, null)) : List.of());

        var result = plan(provider, Map.of("identity", text("minecraft:potion"), "quantity", number(3)));

        var brewing = result.records().getFirst();
        assertTrue(brewing.payload().values().get("batches") instanceof Evidence.JsonNull);
        assertNotNull(brewing.payload().values().get("operationSemantics"));
    }

    @Test
    void lookupAndPageBoundsMakeThePlanPartial() {
        var lookups = new LinkedHashMap<String, Integer>();
        var wide = provider(lookups, item -> item.equals("minecraft:torch") ? List.of(crafting("torch", List.of(
                new JeiProvider.Slot("wide", net.minecraft.core.registries.BuiltInRegistries.ITEM.keySet().stream()
                        .map(Object::toString).filter(id -> !id.equals("minecraft:torch")).sorted().limit(100)
                        .map(id -> item(id, 1)).toList(), JeiProvider.RecipeRole.INPUT)),
                item("minecraft:torch", 1))) : List.of());
        var bounded = plan(wide, Map.of("identity", text("minecraft:torch"), "quantity", number(1)));
        assertEquals(64, lookups.size());
        assertNotNull(bounded.detail());
        assertEquals(Evidence.Coverage.PARTIAL, bounded.coverage());

        var many = provider(new LinkedHashMap<>(), item -> item.equals("minecraft:torch")
                ? java.util.stream.IntStream.range(0, 17).mapToObj(index -> crafting("torch-" + index, List.of(),
                item("minecraft:torch", 1))).toList() : List.of());
        var paged = plan(many, Map.of("identity", text("minecraft:torch"), "quantity", number(1), "depth", number(0)));
        assertEquals(16, paged.records().size());
        assertEquals(Evidence.Coverage.PARTIAL, paged.coverage());
        assertNotNull(paged.detail());
    }

    @Test
    void providerSelectionDeclinesOtherSourcesAndReportsUnavailableJei() {
        var provider = provider(new LinkedHashMap<>(), item -> List.of());
        var native_ = assertThrows(CompletionException.class, () -> plan(provider, Map.of("identity", text("minecraft:torch"),
                "quantity", number(1), "provider", text("native"))));
        assertEquals(Toolbox.Code.NOT_HANDLED, ((Toolbox.Failure) native_.getCause()).code());

        provider.unavailable();
        var unqualified = assertThrows(CompletionException.class, () -> plan(provider, Map.of("identity", text("minecraft:torch"),
                "quantity", number(1))));
        assertEquals(Toolbox.Code.NOT_HANDLED, ((Toolbox.Failure) unqualified.getCause()).code());
        var selected = assertThrows(CompletionException.class, () -> plan(provider, Map.of("identity", text("minecraft:torch"),
                "quantity", number(1), "provider", text("jei"))));
        assertEquals(Toolbox.Code.UNAVAILABLE, ((Toolbox.Failure) selected.getCause()).code());
    }

    private static JeiProvider provider(Map<String, Integer> lookups, Function<String, List<JeiProvider.Projection>> recipes) {
        return new JeiProvider((scope, query, budget) -> {
            var output = ((Evidence.JsonString) query.arguments().values().get("outputItem")).value();
            lookups.merge(output, 1, Integer::sum);
            return new JeiProvider.Snapshot(scope, 1, 1, recipes.apply(output), ignored -> { },
                    Evidence.Coverage.COMPLETE, true, null);
        }, Runnable::run, () -> true);
    }

    private static Evidence.Result plan(JeiProvider provider, Map<String, Evidence.JsonValue> arguments) {
        var registry = new AdapterRegistry();
        var context = new Toolbox.Context(() -> SCOPE, Map.of(), registry, new Toolbox.DocumentBudget(), null);
        return provider.planRecipe(new PackSageTools.ReadRequest("plan_recipe", new Evidence.JsonObject(arguments), SCOPE), context)
                .toCompletableFuture().join();
    }

    private static Evidence.Record route(Evidence.Result result, String name) {
        return result.records().stream().filter(record -> record.source().sourceHandle().endsWith("/" + name)).findFirst()
                .orElseThrow(() -> new AssertionError("missing route " + name + " in " + result.records().stream()
                        .map(record -> record.source().sourceHandle()).toList()));
    }

    private static JeiProvider.Projection crafting(String name, List<JeiProvider.Slot> inputs, JeiProvider.Ingredient output) {
        var slots = new ArrayList<>(inputs);
        slots.add(new JeiProvider.Slot("output", List.of(output), JeiProvider.RecipeRole.OUTPUT));
        return new JeiProvider.Projection("minecraft:crafting", "jei/1/1/minecraft:crafting/" + name, "minecraft:" + name,
                "Crafting", slots, List.of(), List.of(), true, true, Evidence.Coverage.COMPLETE,
                Evidence.DecodingStatus.VERIFIED, null);
    }

    private static JeiProvider.Slot input(String group, JeiProvider.Ingredient... alternatives) {
        return new JeiProvider.Slot(group, List.of(alternatives), JeiProvider.RecipeRole.INPUT);
    }

    private static JeiProvider.Ingredient item(String id, int count) {
        return new JeiProvider.Ingredient("item", id + "|componentsHash=7", new Evidence.JsonObject(Map.of(
                "componentHash", new Evidence.JsonNumber(BigDecimal.valueOf(7)))), count, Evidence.Consumption.CONSUMED);
    }

    private static Evidence.JsonString text(String value) { return new Evidence.JsonString(value); }
    private static Evidence.JsonNumber number(int value) { return new Evidence.JsonNumber(BigDecimal.valueOf(value)); }
}
