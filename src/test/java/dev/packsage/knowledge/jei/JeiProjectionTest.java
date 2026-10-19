package dev.packsage.knowledge.jei;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.AdapterRegistry;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.model.PackSageTools;
import mezz.jei.api.recipe.IRecipeCategoriesLookup;
import mezz.jei.api.recipe.IRecipeCatalystLookup;
import mezz.jei.api.recipe.IRecipeLookup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.IIngredientType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.lang.reflect.ParameterizedType;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JeiProjectionTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "jei-1", 1);

    @Test
    void recipeAdapterAcceptsNullOutputAndDeclinesBeforeValidatingAnotherProvider() {
        var reads = new AtomicInteger();
        var provider = new JeiProvider((scope, query, budget) -> {
            reads.incrementAndGet();
            assertFalse(query.arguments().values().containsKey("outputItem"));
            return new JeiProvider.Snapshot(scope, 1, 1, List.of(), ignored -> { },
                    Evidence.Coverage.COMPLETE, true, null);
        }, Runnable::run, () -> true);
        var registry = new AdapterRegistry();
        registry.register(provider);
        var request = new PackSageTools.ReadRequest("search_recipe", new Evidence.JsonObject(Map.of(
                "filter", new Evidence.JsonObject(Map.of("provider", new Evidence.JsonString("jei"),
                        "outputItem", new Evidence.JsonNull())))), SCOPE);

        assertTrue(provider.searchRecipe(request, context(registry, Map.of())).toCompletableFuture().join().records().isEmpty());
        assertEquals(1, reads.get());
        assertEquals(Toolbox.Code.NOT_HANDLED, failureCode(provider.searchRecipe(
                recipeRequest("native", "not an item"), context(registry, Map.of()))));
    }

    @Test
    void maximumLengthRecipeQueryCanContinueThroughPublicValidation() {
        var positions = new java.util.ArrayList<String>();
        var provider = new JeiProvider((scope, query, budget) -> {
            positions.add(query.cursor());
            return new JeiProvider.Snapshot(scope, 1, positions.size(), List.of(), ignored -> { },
                    Evidence.Coverage.PARTIAL, true, query.cursor() == null ? "1" : null);
        }, Runnable::run, () -> true);
        var registry = new AdapterRegistry();
        registry.register(provider);
        var arguments = new java.util.LinkedHashMap<String, Evidence.JsonValue>();
        arguments.put("filter", new Evidence.JsonObject(Map.of("provider", new Evidence.JsonString("jei"),
                "text", new Evidence.JsonString("\u754C".repeat(512)))));
        var first = provider.searchRecipe(new PackSageTools.ReadRequest("search_recipe", new Evidence.JsonObject(arguments), SCOPE),
                context(registry, Map.of())).toCompletableFuture().join();
        assertTrue(first.nextCursor().length() <= 1024);
        arguments.put("cursor", new Evidence.JsonString(first.nextCursor()));
        var response = new PackSageTools(request -> provider.searchRecipe(request, context(registry, Map.of())))
                .execute(new dev.packsage.model.ModelBackend.ToolCall("continued", "search_recipe",
                        Evidence.serialize(new Evidence.JsonObject(arguments))), SCOPE).toCompletableFuture().join();
        assertFalse(response.result().error());
        assertEquals("1", positions.getLast());
        arguments.put("filter", new Evidence.JsonObject(Map.of("provider", new Evidence.JsonString("jei"),
                "text", new Evidence.JsonString("changed"))));
        assertEquals(Toolbox.Code.INVALID_CURSOR, failureCode(provider.searchRecipe(new PackSageTools.ReadRequest(
                "search_recipe", new Evidence.JsonObject(arguments), SCOPE), context(registry, Map.of()))));
    }

    @Test
    void recipeAdapterUsesOutputFocusAndRebuildsCurrentProjection() {
        var readCount = new AtomicInteger();
        var seenOutput = new AtomicReference<String>();
        var provider = new JeiProvider((scope, query, budget) -> {
            seenOutput.set(((Evidence.JsonString) query.arguments().values().get("outputItem")).value());
            var title = readCount.incrementAndGet() == 1 ? "Old recipe" : "Current recipe";
            var output = new JeiProvider.Ingredient("item", "minecraft:stone", new Evidence.JsonObject(Map.of()),
                    1, Evidence.Consumption.CONSUMED);
            var projection = new JeiProvider.Projection("minecraft:crafting", "jei:recipe", "fixture:recipe", title,
                    List.of(new JeiProvider.Slot("output", List.of(output), JeiProvider.RecipeRole.OUTPUT)),
                    List.of(), List.of(), true, true, Evidence.Coverage.COMPLETE,
                    Evidence.DecodingStatus.VERIFIED, null);
            return new JeiProvider.Snapshot(scope, 1, readCount.get(), List.of(projection), ignored -> { },
                    Evidence.Coverage.COMPLETE, true, null);
        }, Runnable::run, () -> true);
        var request = recipeRequest("jei", "minecraft:stone");
        var registry = new AdapterRegistry();
        registry.register(provider);

        var first = provider.searchRecipe(request, context(registry, Map.of())).toCompletableFuture().join();
        var second = provider.searchRecipe(request, context(registry, Map.of())).toCompletableFuture().join();

        assertEquals("minecraft:stone", seenOutput.get());
        assertEquals("Old recipe", first.records().getFirst().title());
        assertEquals("Current recipe", second.records().getFirst().title());
        assertEquals("jei", provider.metadata().id());
        assertTrue(provider.metadata().recipes());
        assertEquals(Toolbox.Code.NOT_HANDLED, failureCode(provider.searchRecipe(
                recipeRequest("native", "minecraft:stone"), context(registry, Map.of()))));
    }

    @Test
    void projectionPreservesGroupedAlternativesQuantitiesComponentsCatalystsAndNullId() throws Exception {
        var iron = new JeiProvider.Ingredient("item", "minecraft:iron_ingot",
                new Evidence.JsonObject(Map.of("component", new Evidence.JsonString("value"))), 2,
                Evidence.Consumption.CONSUMED);
        var copper = new JeiProvider.Ingredient("item", "minecraft:copper_ingot",
                new Evidence.JsonObject(Map.of()), 1, Evidence.Consumption.CONSUMED);
        var catalyst = new JeiProvider.Ingredient("item", "minecraft:crafting_table",
                new Evidence.JsonObject(Map.of()), 1, Evidence.Consumption.REUSED);
        var projection = new JeiProvider.Projection("mod:category", "mod:category#0", null, "Example",
                List.of(new JeiProvider.Slot("metal", List.of(iron, copper), JeiProvider.RecipeRole.INPUT),
                        new JeiProvider.Slot("result", List.of(new JeiProvider.Ingredient("item", "mod:result",
                                new Evidence.JsonObject(Map.of()), 3, Evidence.Consumption.UNKNOWN)), JeiProvider.RecipeRole.OUTPUT)),
                List.of(catalyst), List.of("requires power"), true, Evidence.Coverage.COMPLETE,
                Evidence.DecodingStatus.VERIFIED, null);
        var provider = new JeiProvider(new JeiProvider.Snapshot(SCOPE, 1, List.of(projection), ignored -> { }));
        provider.refresh();

        var result = provider.query(new Evidence.Query(Evidence.Operation.GET,
                new Evidence.JsonObject(Map.of("id", new Evidence.JsonString("mod:category#0"))), 10, null, null), SCOPE)
                .toCompletableFuture().get();

        var recipe = result.records().get(0).recipePayload();
        assertNull(recipe.canonicalRecipeId());
        assertEquals(List.of("minecraft:iron_ingot", "minecraft:copper_ingot"),
                recipe.inputs().get(0).alternatives().stream().map(Evidence.RecipeAlternative::comparisonIdentity).toList());
        assertEquals(2, recipe.inputs().get(0).alternatives().get(0).quantity());
        assertEquals("value", ((Evidence.JsonString) recipe.inputs().get(0).alternatives().get(0).components().values().get("component")).value());
        assertEquals("minecraft:crafting_table", recipe.catalysts().get(0).comparisonIdentity());
        assertEquals(List.of("requires power"), recipe.constraints());
        assertTrue(provider.enabled());
        provider.unavailable();
        assertFalse(provider.enabled());
    }

    @Test
    void recipeNavigationTargetUsesCanonicalIdentityAcrossCategoryNamespaces() {
        var projection = new JeiProvider.Projection("addon:machine", "jei/7/19/addon:machine/recipe-addon:press",
                "addon:press", "Press", List.of(), List.of(), List.of(), true,
                Evidence.Coverage.COMPLETE, Evidence.DecodingStatus.VERIFIED, null);
        var record = JeiProvider.toRecord(projection, new JeiProvider.Snapshot(SCOPE, 7, 19,
                List.of(projection), ignored -> { }));

        assertEquals("recipe-v1|addon:machine|addon:press", record.targets().getFirst().targetId());
        assertEquals(projection.viewerHandle(), record.targets().getFirst().sourceHandle());
    }

    @Test
    void recipeWithoutCanonicalIdKeepsOnlyTemporaryNavigationIdentity() {
        var projection = new JeiProvider.Projection("addon:machine", "jei/7/19/addon:machine/object-123",
                null, "Press", List.of(), List.of(), List.of(), true,
                Evidence.Coverage.COMPLETE, Evidence.DecodingStatus.VERIFIED, null);
        var record = JeiProvider.toRecord(projection, new JeiProvider.Snapshot(SCOPE, 7, 19,
                List.of(projection), ignored -> { }));

        assertEquals(projection.viewerHandle(), record.targets().getFirst().targetId());
    }

    @Test
    void categoryOnlyProjectionUsesStableCategoryIdentity() {
        var projection = JeiProvider.Projection.opaque("addon:machine", "jei/7/19/addon:machine/category",
                "Machine", "category only");
        var record = JeiProvider.toRecord(projection, new JeiProvider.Snapshot(SCOPE, 7, 19,
                List.of(projection), ignored -> { }));

        assertEquals("category-v1|addon:machine", record.targets().getFirst().targetId());
    }

    @Test
    void opaqueCategoryRemainsSearchableWithoutConsumedInputEdges() throws Exception {
        var projection = JeiProvider.Projection.opaque("addon:machine", "addon:machine#0", "Machine", "custom category");
        var provider = new JeiProvider(new JeiProvider.Snapshot(SCOPE, 1, List.of(projection), ignored -> { }));
        provider.refresh();

        var result = provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("machine"))), 10, null, null), SCOPE)
                .toCompletableFuture().get();

        assertEquals(Evidence.Coverage.OPAQUE, result.records().get(0).source().coverage());
        assertEquals(List.of(), result.records().get(0).recipePayload().inputs());
        assertEquals(Evidence.DecodingStatus.OPAQUE, result.records().get(0).recipePayload().decodingStatus());
    }

    @Test
    void navigationRequiresCurrentScopeAndUsesViewerHandle() {
        var opened = new AtomicReference<String>();
        var projection = new JeiProvider.Projection("minecraft:crafting", "minecraft:crafting#0", "mod:recipe",
                "Recipe", List.of(), List.of(), List.of(), true, Evidence.Coverage.COMPLETE,
                Evidence.DecodingStatus.VERIFIED, null);
        var provider = new JeiProvider(new JeiProvider.Snapshot(SCOPE, 1, List.of(projection), opened::set));
        provider.refresh();

        var target = new Evidence.NavigationTarget(SCOPE, "jei", "minecraft:crafting#0",
                "recipe-v1|minecraft:crafting|mod:recipe");
        assertEquals(Evidence.NavigationStatus.OPENED, provider.open(target, SCOPE).status());
        assertEquals("minecraft:crafting#0", opened.get());
        assertEquals(Evidence.NavigationStatus.STALE, provider.open(target,
                new Evidence.Scope("world", "en_us", "team", "jei-2", 2)).status());
    }

    @Test
    void recentSnapshotsRemainOpenableWithinOneRuntimeGeneration() {
        var first = new JeiProvider.Projection("mod:category", "mod:category#a", "mod:first",
                "First", List.of(), List.of(), List.of(), true, Evidence.Coverage.COMPLETE,
                Evidence.DecodingStatus.VERIFIED, null);
        var second = new JeiProvider.Projection("mod:category", "mod:category#b", "mod:second",
                "Second", List.of(), List.of(), List.of(), true, Evidence.Coverage.COMPLETE,
                Evidence.DecodingStatus.VERIFIED, null);
        var replacement = new JeiProvider.Projection("mod:category", "mod:category#c", "mod:third",
                "Third", List.of(), List.of(), List.of(), true, Evidence.Coverage.COMPLETE,
                Evidence.DecodingStatus.VERIFIED, null);
        var replaced = new AtomicBoolean();
        var provider = new JeiProvider((scope, query, budget) -> {
            if (query.operation() == Evidence.Operation.GET) {
                var id = ((Evidence.JsonString) query.arguments().values().get("id")).value();
                return !replaced.get() && id.equals(first.viewerHandle())
                        ? new JeiProvider.Snapshot(scope, 7, 3, List.of(first), ignored -> { })
                        : new JeiProvider.Snapshot(scope, 8, 1, List.of(replacement), ignored -> { });
            }
            var text = ((Evidence.JsonString) query.arguments().values().get("text")).value();
            return text.contains("first") ? new JeiProvider.Snapshot(scope, 7, 1, List.of(first), ignored -> { })
                    : text.contains("second") ? new JeiProvider.Snapshot(scope, 7, 2, List.of(second), ignored -> { })
                    : new JeiProvider.Snapshot(scope, 8, 1, List.of(replacement), ignored -> { });
        }, Runnable::run, () -> true);

        var firstResult = provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("first"))), 1, null, null), SCOPE)
                .toCompletableFuture().join();
        var firstTarget = firstResult.records().getFirst().targets().getFirst();
        provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("second"))), 1, null, null), SCOPE)
                .toCompletableFuture().join();

        assertEquals(Evidence.NavigationStatus.OPENED, provider.open(firstTarget, SCOPE).status());

        provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("replacement"))), 1, null, null), SCOPE)
                .toCompletableFuture().join();
        replaced.set(true);
        assertEquals(Evidence.NavigationStatus.STALE, provider.open(firstTarget, SCOPE).status());
    }

    @Test
    void emptySnapshotsDoNotEvictARecentTargetWithinTheSameRuntime() {
        var target = new JeiProvider.Projection("mod:category", "mod:category#target", "mod:target",
                "Target", List.of(), List.of(), List.of(), true, Evidence.Coverage.COMPLETE,
                Evidence.DecodingStatus.VERIFIED, null);
        var present = new AtomicBoolean(true);
        var provider = new JeiProvider((scope, query, budget) -> {
            if (query.operation() == Evidence.Operation.GET) return new JeiProvider.Snapshot(scope, 4, 2,
                    present.get() ? List.of(target) : List.of(), ignored -> { });
            return new JeiProvider.Snapshot(scope, 4, 1, List.of(target), ignored -> { });
        }, Runnable::run, () -> true);

        var targetResult = provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("target"))), 1, null, null), SCOPE)
                .toCompletableFuture().join();
        var targetHandle = targetResult.records().getFirst().targets().getFirst();
        assertEquals(Evidence.NavigationStatus.OPENED, provider.open(targetHandle, SCOPE).status());
        present.set(false);
        assertEquals(Evidence.NavigationStatus.STALE, provider.open(targetHandle, SCOPE).status());
    }

    @Test
    void invalidOutputItemIsRejectedOnlyByTheMatchingProvider() {
        var registry = new AdapterRegistry();
        registry.register(new JeiProvider(new JeiProvider.Snapshot(SCOPE, 1, List.of(), ignored -> { })));

        assertEquals(Toolbox.Code.INVALID_ARGUMENTS, failureCode(registry.dispatch(recipeRequest("jei",
                "minecraft:this_item_does_not_exist"), context(registry, Map.of()))));
        assertEquals(Toolbox.Code.NOT_HANDLED, failureCode(registry.dispatch(recipeRequest("native",
                "minecraft:this_item_does_not_exist"), context(registry, Map.of()))));
    }

    @Test
    void getWithoutTextAndNegativeCursorReturnResultsInsteadOfThrowing() throws Exception {
        var projection = new JeiProvider.Projection("mod:category", "mod:category#0", "mod:recipe",
                "Recipe", List.of(), List.of(), List.of(), true, Evidence.Coverage.COMPLETE,
                Evidence.DecodingStatus.VERIFIED, null);
        var provider = new JeiProvider(new JeiProvider.Snapshot(SCOPE, 1, List.of(projection), ignored -> { }));
        provider.refresh();

        var get = provider.query(new Evidence.Query(Evidence.Operation.GET,
                new Evidence.JsonObject(Map.of("id", new Evidence.JsonString("mod:recipe"))), 1, null, null), SCOPE)
                .toCompletableFuture().get();
        assertEquals(1, get.records().size());

        var negative = provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("recipe"))), 1, SCOPE, "-1"), SCOPE)
                .toCompletableFuture().get();
        assertEquals(Evidence.Coverage.ERROR, negative.coverage());
    }

    @Test
    void eachFreshRecipeContinuationReadsOnItsClientTick() {
        var tick = new long[1];
        var clientTasks = new java.util.ArrayDeque<Runnable>();
        var laterTick = new java.util.ArrayList<Runnable>();
        var captureTicks = new java.util.ArrayList<Long>();
        var provider = new JeiProvider((scope, query, budget) -> {
            captureTicks.add(tick[0]);
            assertEquals("nothing", ((Evidence.JsonString) query.arguments().values().get("text")).value());
            int next = query.cursor() == null ? 1 : Integer.parseInt(query.cursor()) + 1;
            return new JeiProvider.Snapshot(scope, 1, next, List.of(), ignored -> { }, Evidence.Coverage.COMPLETE, true,
                    Integer.toString(next));
        }, clientTasks::add, () -> true, () -> tick[0], laterTick::add);
        var registry = new AdapterRegistry();
        registry.register(provider);
        var context = context(registry, Map.of());
        var request = recipeSearchRequest(null);
        var firstFuture = provider.searchRecipe(request, context).toCompletableFuture();
        assertFalse(clientTasks.isEmpty());
        clientTasks.remove().run();
        var first = firstFuture.join();
        assertNotNull(first.nextCursor());

        tick[0]++;
        var secondFuture = provider.searchRecipe(recipeSearchRequest(first.nextCursor()), context).toCompletableFuture();
        clientTasks.remove().run();
        var second = secondFuture.join();

        assertEquals(List.of(0L, 1L), captureTicks);
        assertTrue(laterTick.isEmpty());
        assertNotNull(second.nextCursor());
    }

    @Test
    void filteredCursorAdvancesPastSkippedRecords() throws Exception {
        var first = new JeiProvider.Projection("mod:category", "mod:category#0", "mod:first",
                "Other", List.of(), List.of(), List.of(), true, Evidence.Coverage.COMPLETE,
                Evidence.DecodingStatus.VERIFIED, null);
        var second = new JeiProvider.Projection("mod:category", "mod:category#1", "mod:second",
                "Target", List.of(), List.of(), List.of(), true, Evidence.Coverage.COMPLETE,
                Evidence.DecodingStatus.VERIFIED, null);
        var third = new JeiProvider.Projection("mod:category", "mod:category#2", "mod:third",
                "Target again", List.of(), List.of(), List.of(), true, Evidence.Coverage.COMPLETE,
                Evidence.DecodingStatus.VERIFIED, null);
        var provider = new JeiProvider(new JeiProvider.Snapshot(SCOPE, 1, List.of(first, second, third), ignored -> { }));
        provider.refresh();

        var page = provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("target"))), 1, null, null), SCOPE)
                .toCompletableFuture().get();
        assertEquals("mod:category#1", page.records().get(0).source().sourceHandle());
        assertEquals("2", page.nextCursor());

        var next = provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("target"))), 1, SCOPE,
                page.nextCursor()), SCOPE).toCompletableFuture().get();
        assertEquals("mod:category#2", next.records().get(0).source().sourceHandle());
    }

    @Test
    void outputItemSearchKeepsComponentVariantsAdvancesAndCanRefreshSelectedTarget() throws Exception {
        var other = new JeiProvider.Projection("minecraft:brewing", "jei/1/1/minecraft:brewing/0",
                "minecraft:unrelated", "Strength from another item", List.of(), List.of(), List.of(), true,
                Evidence.Coverage.COMPLETE, Evidence.DecodingStatus.VERIFIED, null);
        var awkwardPotion = new JeiProvider.Projection("minecraft:brewing", "jei/1/1/minecraft:brewing/1",
                "minecraft:awkward_to_strength", "Awkward brew",
                List.of(new JeiProvider.Slot("output", List.of(new JeiProvider.Ingredient("item",
                        "minecraft:potion|componentsHash=17291", new Evidence.JsonObject(Map.of(
                        "minecraft:potion_contents", new Evidence.JsonObject(Map.of("potion",
                                new Evidence.JsonString("minecraft:strength"))))), 1,
                        Evidence.Consumption.UNKNOWN)), JeiProvider.RecipeRole.OUTPUT)), List.of(), List.of(), true,
                Evidence.Coverage.COMPLETE, Evidence.DecodingStatus.VERIFIED, null);
        var longPotion = new JeiProvider.Projection("minecraft:brewing", "jei/1/1/minecraft:brewing/2",
                "minecraft:long_strength", "Extended brew",
                List.of(new JeiProvider.Slot("output", List.of(new JeiProvider.Ingredient("item",
                        "minecraft:potion|componentsHash=29813", new Evidence.JsonObject(Map.of(
                        "minecraft:potion_contents", new Evidence.JsonObject(Map.of("potion",
                                new Evidence.JsonString("minecraft:long_strength"))))), 1,
                        Evidence.Consumption.UNKNOWN)), JeiProvider.RecipeRole.OUTPUT)), List.of(), List.of(), true,
                Evidence.Coverage.COMPLETE, Evidence.DecodingStatus.VERIFIED, null);
        var provider = new JeiProvider((scope, query, budget) -> new JeiProvider.Snapshot(scope, 1, 1,
                List.of(other, awkwardPotion, longPotion), ignored -> { }, Evidence.Coverage.COMPLETE, false, null),
                Runnable::run, () -> true);
        var query = new Evidence.Query(Evidence.Operation.SEARCH, new Evidence.JsonObject(Map.of(
                "text", new Evidence.JsonString("strength"),
                "outputItem", new Evidence.JsonString("minecraft:potion"))), 1, null, null);

        var first = provider.query(query, SCOPE).toCompletableFuture().get();

        assertEquals(List.of("jei/1/1/minecraft:brewing/1"), first.records().stream()
                .map(record -> record.source().sourceHandle()).toList());
        assertEquals("2", first.nextCursor());
        var next = provider.query(new Evidence.Query(Evidence.Operation.SEARCH, query.arguments(), 1, SCOPE,
                first.nextCursor()), SCOPE).toCompletableFuture().get();
        assertEquals("jei/1/1/minecraft:brewing/2", next.records().getFirst().source().sourceHandle());
        var refreshed = provider.refreshNavigation(first.records().getFirst().targets().getFirst(), SCOPE)
                .toCompletableFuture().get();
        assertNotNull(refreshed);
        assertEquals(Evidence.NavigationStatus.OPENED, provider.open(refreshed, SCOPE).status());
    }

    @Test
    void renderOnlySlotsArePreservedWithoutInventingRemainders() throws Exception {
        var input = new JeiProvider.Ingredient("item", "mod:input", new Evidence.JsonObject(Map.of()), 1,
                Evidence.Consumption.CONSUMED);
        var catalyst = new JeiProvider.Ingredient("item", "mod:catalyst", new Evidence.JsonObject(Map.of()), 1,
                Evidence.Consumption.REUSED);
        var display = new JeiProvider.Ingredient("item", "mod:display", new Evidence.JsonObject(Map.of()), 1,
                Evidence.Consumption.UNKNOWN);
        var projection = new JeiProvider.Projection("mod:category", "mod:category#0", null, "Recipe",
                List.of(new JeiProvider.Slot("input", List.of(input), JeiProvider.RecipeRole.INPUT),
                        new JeiProvider.Slot("catalyst", List.of(catalyst), JeiProvider.RecipeRole.CATALYST),
                        new JeiProvider.Slot("display", List.of(display), JeiProvider.RecipeRole.RENDER_ONLY)),
                List.of(), List.of(), true, Evidence.Coverage.COMPLETE, Evidence.DecodingStatus.VERIFIED, null);
        var provider = new JeiProvider(new JeiProvider.Snapshot(SCOPE, 1, List.of(projection), ignored -> { }));
        provider.refresh();

        var result = provider.query(new Evidence.Query(Evidence.Operation.GET,
                new Evidence.JsonObject(Map.of("id", new Evidence.JsonString("mod:category#0"))), 1, null, null), SCOPE)
                .toCompletableFuture().get();
        var recipe = result.records().get(0).recipePayload();
        assertEquals(List.of(), recipe.remainders());
        assertEquals(List.of("mod:catalyst"), recipe.catalysts().stream()
                .map(Evidence.RecipeAlternative::comparisonIdentity).toList());
        assertEquals(List.of(new Evidence.JsonString("display")),
                ((Evidence.JsonArray) result.records().get(0).payload().values().get("renderOnlySlots")).values());
    }

    @Test
    void recipeSpecificCatalystsAreSerializedOnce() throws Exception {
        var catalyst = new JeiProvider.Ingredient("item", "mod:catalyst", new Evidence.JsonObject(Map.of()), 1,
                Evidence.Consumption.REUSED);
        var projection = new JeiProvider.Projection("mod:category", "mod:category#0", "mod:recipe", "Recipe",
                List.of(new JeiProvider.Slot("catalyst", List.of(catalyst), JeiProvider.RecipeRole.CATALYST)),
                List.of(catalyst), List.of(), true, Evidence.Coverage.COMPLETE, Evidence.DecodingStatus.VERIFIED, null);
        var provider = new JeiProvider(new JeiProvider.Snapshot(SCOPE, 1, List.of(projection), ignored -> { }));
        provider.refresh();

        var result = provider.query(new Evidence.Query(Evidence.Operation.GET,
                new Evidence.JsonObject(Map.of("id", new Evidence.JsonString("mod:recipe"))), 1, null, null), SCOPE)
                .toCompletableFuture().get();
        assertEquals(List.of("mod:catalyst"), result.records().get(0).recipePayload().catalysts().stream()
                .map(Evidence.RecipeAlternative::comparisonIdentity).toList());
    }

    @Test
    void opaqueProjectionDoesNotClaimVisibility() throws Exception {
        var projection = JeiProvider.Projection.opaque("addon:machine", "addon:machine#0", "Machine", "custom category");
        var provider = new JeiProvider(new JeiProvider.Snapshot(SCOPE, 1, List.of(projection), ignored -> { }));
        provider.refresh();

        var result = provider.query(new Evidence.Query(Evidence.Operation.GET,
                new Evidence.JsonObject(Map.of("id", new Evidence.JsonString("addon:machine#0"))), 1, null, null), SCOPE)
                .toCompletableFuture().get();
        var payload = result.records().get(0).payload().values();
        assertEquals(false, ((Evidence.JsonBoolean) payload.get("visible")).value());
        assertEquals(false, ((Evidence.JsonBoolean) payload.get("visibilityKnown")).value());
    }

    @Test
    void failedProjectionHasNoNavigationTarget() throws Exception {
        var projection = JeiProvider.Projection.error("addon:category", "addon:category#0", null,
                "Custom", "projection failed");
        var provider = new JeiProvider(new JeiProvider.Snapshot(SCOPE, 1, List.of(projection), ignored -> { }));
        provider.refresh();

        var result = provider.query(new Evidence.Query(Evidence.Operation.GET,
                new Evidence.JsonObject(Map.of("id", new Evidence.JsonString("addon:category#0"))), 1, null, null), SCOPE)
                .toCompletableFuture().get();
        assertEquals(List.of(), result.records().get(0).targets());
    }

    @Test
    void pinnedRuntimeScannerContinuesPastPerCallLimitAndRejectsStaleCursors() throws Exception {
        var unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = unsafeField.get(null);
        var allocateInstance = unsafe.getClass().getMethod("allocateInstance", Class.class);
        var fakeMinecraft = (Minecraft) allocateInstance.invoke(unsafe, Minecraft.class);
        var minecraftInstance = Minecraft.class.getDeclaredField("instance");
        minecraftInstance.setAccessible(true);
        var previousMinecraft = minecraftInstance.get(null);
        var gameThread = Minecraft.class.getDeclaredField("gameThread");
        gameThread.setAccessible(true);
        gameThread.set(fakeMinecraft, Thread.currentThread());
        minecraftInstance.set(null, fakeMinecraft);
        try {
            var recipes = new AtomicReference<List<Object>>(java.util.stream.IntStream.range(0, 20000)
                    .mapToObj(value -> (Object) ("recipe-" + value)).toList());
            var recipeVisits = new AtomicInteger();
            var type = new AtomicReference<>(new RecipeType<>(
                    ResourceLocation.fromNamespaceAndPath("minecraft", "crafting"), Object.class));
            var categories = new AtomicReference<List<IRecipeCategory<?>>>(List.of());
            var category = (IRecipeCategory<?>) Proxy.newProxyInstance(IRecipeCategory.class.getClassLoader(),
                    new Class<?>[] { IRecipeCategory.class }, (proxy, method, arguments) -> switch (method.getName()) {
                        case "getRecipeType" -> type.get();
                        case "getTitle" -> Component.literal("Opaque");
                        case "getRegistryName" -> ResourceLocation.fromNamespaceAndPath("fixture", (String) arguments[0]);
                        default -> null;
                    });
            var recipeLookup = (IRecipeLookup<Object>) Proxy.newProxyInstance(IRecipeLookup.class.getClassLoader(),
                    new Class<?>[] { IRecipeLookup.class }, (proxy, method, arguments) -> switch (method.getName()) {
                        case "includeHidden", "limitFocus" -> proxy;
                        case "get" -> recipes.get().stream().peek(ignored -> recipeVisits.incrementAndGet());
                        default -> null;
                    });
            var catalystLookup = (IRecipeCatalystLookup) Proxy.newProxyInstance(
                    IRecipeCatalystLookup.class.getClassLoader(), new Class<?>[] { IRecipeCatalystLookup.class },
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "includeHidden" -> proxy;
                        case "get" -> Stream.empty();
                        default -> null;
                    });
            var categoryLookup = (IRecipeCategoriesLookup) Proxy.newProxyInstance(
                    IRecipeCategoriesLookup.class.getClassLoader(), new Class<?>[] { IRecipeCategoriesLookup.class },
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "includeHidden", "limitTypes", "limitFocus" -> proxy;
                        case "get" -> categories.get().stream();
                        default -> null;
                    });
            var manager = (IRecipeManager) Proxy.newProxyInstance(IRecipeManager.class.getClassLoader(),
                    new Class<?>[] { IRecipeManager.class }, (proxy, method, arguments) -> switch (method.getName()) {
                        case "createRecipeCategoryLookup" -> categoryLookup;
                        case "createRecipeLookup" -> recipeLookup;
                        case "createRecipeCatalystLookup" -> catalystLookup;
                        case "createRecipeLayoutDrawable" -> {
                            var layoutArgument = ((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments()[0];
                            var layoutType = layoutArgument instanceof Class<?> typeClass ? typeClass
                                    : (Class<?>) ((ParameterizedType) layoutArgument).getRawType();
                            var slotsType = layoutType.getMethod("getRecipeSlotsView").getReturnType();
                            var slots = Proxy.newProxyInstance(slotsType.getClassLoader(), new Class<?>[] { slotsType },
                                    (slotsProxy, slotsMethod, slotsArgs) -> slotsMethod.getName().equals("getSlotViews")
                                            ? List.of() : null);
                            var layout = Proxy.newProxyInstance(layoutType.getClassLoader(), new Class<?>[] { layoutType },
                                    (layoutProxy, layoutMethod, layoutArgs) -> layoutMethod.getName().equals("getRecipeSlotsView")
                                            ? slots : null);
                            yield Optional.of(layout);
                        }
                        default -> null;
                    });
            var runtime = (IJeiRuntime) Proxy.newProxyInstance(IJeiRuntime.class.getClassLoader(),
                    new Class<?>[] { IJeiRuntime.class }, (proxy, method, arguments) ->
                    method.getName().equals("getRecipeManager") ? manager : null);
            var plugin = new PackSageJeiPlugin();
            categories.set(List.of(category));
            plugin.onRuntimeAvailable(runtime);

            var first = plugin.provider().query(new Evidence.Query(Evidence.Operation.SEARCH,
                    new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("fixture:recipe-4095"))), 1,
                    null, null), SCOPE)
                    .toCompletableFuture().get();
            assertTrue(first.records().isEmpty(), () -> "unexpected first-page records: " + first.records().stream()
                    .map(record -> record.source().sourceHandle()).toList() + "; coverage=" + first.coverage()
                    + "; detail=" + first.detail());
            assertEquals(Evidence.Coverage.PARTIAL, first.coverage());
            assertNotNull(first.nextCursor());
            var cursor = first.nextCursor();
            var matchPage = first;
            for (var page = 1; page < 16 && matchPage.records().isEmpty(); page++) {
                recipeVisits.set(0);
                PackSageJeiPlugin.clientTicked();
                matchPage = plugin.provider().query(new Evidence.Query(Evidence.Operation.SEARCH,
                        new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("fixture:recipe-4095"))), 1,
                        SCOPE, cursor), SCOPE).toCompletableFuture().get();
                assertNotEquals(Evidence.Coverage.ERROR, matchPage.coverage(),
                        "an emitted cursor for an unchanged search must remain resumable");
                assertTrue(recipeVisits.get() <= 6143,
                        "the live recipe walk must bound cursor seeking and candidate reads");
                if (matchPage.records().isEmpty()) {
                    assertNotNull(matchPage.nextCursor(), "an empty partial page must provide a resumable cursor");
                    cursor = matchPage.nextCursor();
                }
            }
            assertEquals(1, matchPage.records().size(),
                    "continuation must find the exact canonical recipe beyond ordinal 2048");
            assertEquals("fixture:recipe-4095", matchPage.records().getFirst().recipePayload().canonicalRecipeId());
            assertNotNull(matchPage.nextCursor());
            recipeVisits.set(0);
            PackSageJeiPlugin.clientTicked();
            var third = plugin.provider().query(new Evidence.Query(Evidence.Operation.SEARCH,
                    new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("fixture:recipe-4095"))), 1,
                    SCOPE, matchPage.nextCursor()), SCOPE).toCompletableFuture().get();
            assertTrue(third.records().isEmpty(), "continuation must advance past the matching recipe");
            assertNotEquals(Evidence.Coverage.ERROR, third.coverage(),
                    "an emitted cursor beyond ordinal 2048 must remain resumable");
            assertTrue(recipeVisits.get() <= 6143,
                    "a later live cursor must not trigger an unbounded recipe scan");

            recipes.set(java.util.stream.IntStream.range(0, 70000)
                    .mapToObj(value -> (Object) ("recipe-" + value)).toList());
            recipeVisits.set(0);
            PackSageJeiPlugin.clientTicked();
            var terminal = plugin.provider().query(new Evidence.Query(Evidence.Operation.SEARCH,
                    new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("fixture:missing"))), 1, SCOPE,
                    matchPage.nextCursor().substring(0, matchPage.nextCursor().indexOf('|')) + "|65535"), SCOPE)
                    .toCompletableFuture().get();
            assertTrue(terminal.records().isEmpty());
            assertEquals(Evidence.Coverage.PARTIAL, terminal.coverage());
            assertNull(terminal.nextCursor(), "the scan ceiling must end with a terminal partial page");
            assertTrue(recipeVisits.get() <= 65536, "the cursor seek must stop at its explicit ceiling");

            plugin.onRuntimeAvailable(runtime);
            recipeVisits.set(0);
            PackSageJeiPlugin.clientTicked();
            var stale = plugin.provider().query(new Evidence.Query(Evidence.Operation.SEARCH,
                    new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("fixture:recipe-4095"))), 1,
                    SCOPE, matchPage.nextCursor()), SCOPE).toCompletableFuture().get();
            assertEquals(Evidence.Coverage.ERROR, stale.coverage(), "a cursor from the previous runtime must be rejected");
            assertEquals(0, recipeVisits.get(), "stale cursors must be rejected before scanning live recipes");

            recipes.set(List.of("recipe"));
            categories.set(java.util.Collections.nCopies(202, category));
            PackSageJeiPlugin.clientTicked();
            var categorySeed = plugin.provider().query(new Evidence.Query(Evidence.Operation.SEARCH,
                    new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("fixture:recipe"))), 1,
                    null, null), SCOPE).toCompletableFuture().get();
            assertEquals(1, categorySeed.records().size());
            assertNotNull(categorySeed.nextCursor());
            var categoryCursor = categorySeed.nextCursor().substring(0, categorySeed.nextCursor().indexOf('|'))
                    + "|200000000";
            PackSageJeiPlugin.clientTicked();
            var lateCategory = plugin.provider().query(new Evidence.Query(Evidence.Operation.SEARCH,
                    new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("fixture:recipe"))), 1,
                    SCOPE, categoryCursor), SCOPE).toCompletableFuture().get();
            assertNotEquals(Evidence.Coverage.ERROR, lateCategory.coverage(),
                    "a cursor within the category seek bound must remain resumable");
            assertEquals(1, lateCategory.records().size());
            assertEquals("fixture:recipe", lateCategory.records().getFirst().recipePayload().canonicalRecipeId());
            assertNotNull(lateCategory.nextCursor());
            assertTrue(lateCategory.nextCursor().endsWith("|201000000"));
            PackSageJeiPlugin.clientTicked();
            var nextCategory = plugin.provider().query(new Evidence.Query(Evidence.Operation.SEARCH,
                    new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("fixture:recipe"))), 1,
                    SCOPE, lateCategory.nextCursor()), SCOPE).toCompletableFuture().get();
            assertNotEquals(Evidence.Coverage.ERROR, nextCategory.coverage(),
                    "a returned category continuation must remain resumable");
            assertEquals(1, nextCategory.records().size());
            assertNull(nextCategory.nextCursor());

            recipes.set(List.of("a".repeat(50_000)));
            categories.set(java.util.Collections.nCopies(202, category));
            PackSageJeiPlugin.clientTicked();
            var oversizedCanonical = plugin.provider().query(new Evidence.Query(Evidence.Operation.SEARCH,
                    new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("missing"))), 1,
                    null, null), SCOPE).toCompletableFuture().get();
            assertTrue(oversizedCanonical.records().isEmpty());
            assertEquals(Evidence.Coverage.PARTIAL, oversizedCanonical.coverage());
            assertNull(oversizedCanonical.nextCursor(),
                    "a canonical ID too large for a fresh page must not emit an unusable cursor");

            recipes.set(List.of("first", "second"));
            categories.set(List.of(category));
            type.set(new RecipeType<>(ResourceLocation.fromNamespaceAndPath("fixture", "uncoded"), Object.class));
            PackSageJeiPlugin.clientTicked();
            var firstPage = plugin.provider().query(new Evidence.Query(Evidence.Operation.SEARCH,
                    new Evidence.JsonObject(Map.of("text", new Evidence.JsonString(""))), 1, null, null), SCOPE)
                    .toCompletableFuture().get();
            PackSageJeiPlugin.clientTicked();
            var secondPage = plugin.provider().query(new Evidence.Query(Evidence.Operation.SEARCH,
                    new Evidence.JsonObject(Map.of("text", new Evidence.JsonString(""))), 1, SCOPE,
                    firstPage.nextCursor()), SCOPE).toCompletableFuture().get();
            assertEquals(1, firstPage.records().size());
            assertEquals(1, secondPage.records().size());

            recipes.set(List.of("recipe"));
            PackSageJeiPlugin.clientTicked();
            var noMatch = plugin.provider().query(new Evidence.Query(Evidence.Operation.SEARCH,
                    new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("absent"))), 1, null, null), SCOPE)
                    .toCompletableFuture().get();
            assertEquals(Evidence.Coverage.OPAQUE, noMatch.coverage());
        } finally {
            minecraftInstance.set(null, previousMinecraft);
        }
    }

    @Test
    void focusedScannerRefreshesExactHandlesOnlyForCurrentVisibleRegistrations() throws Exception {
        var unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = unsafeField.get(null);
        var fakeMinecraft = (Minecraft) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, Minecraft.class);
        var minecraftInstance = Minecraft.class.getDeclaredField("instance");
        minecraftInstance.setAccessible(true);
        var previousMinecraft = minecraftInstance.get(null);
        var gameThread = Minecraft.class.getDeclaredField("gameThread");
        gameThread.setAccessible(true);
        gameThread.set(fakeMinecraft, Thread.currentThread());
        minecraftInstance.set(null, fakeMinecraft);
        try {
            var focusArguments = new AtomicReference<Object[]>();
            var appliedFocus = new AtomicReference<Object>();
            var focusApplications = new AtomicInteger();
            var focusCreations = new AtomicInteger();
            var shownRecipes = new AtomicReference<List<?>>();
            var shownTypes = new AtomicReference<List<?>>();
            var hiddenRecipe = new AtomicBoolean();
            var hiddenCategory = new AtomicBoolean();
            var hiddenIngredient = new AtomicBoolean();
            var ingredientBudgetScenario = new AtomicBoolean();
            var oversizedIngredientScenario = new AtomicBoolean();
            var oversizedSlotNameScenario = new AtomicBoolean();
            var recipeVisits = new AtomicInteger();
            var categoryVisits = new AtomicInteger();
            var categoryPrefix = new AtomicInteger();
            var limitedRecipeTypes = new AtomicReference<Object>();
            var focusFactoryType = IJeiRuntime.class.getMethod("getJeiHelpers").getReturnType()
                    .getMethod("getFocusFactory").getReturnType();
            var focusFactory = Proxy.newProxyInstance(focusFactoryType.getClassLoader(), new Class<?>[] { focusFactoryType },
                    (proxy, method, arguments) -> {
                        if (!method.getName().equals("createFocus")) return null;
                        focusCreations.incrementAndGet();
                        focusArguments.set(arguments);
                        var focusType = method.getReturnType();
                        return Proxy.newProxyInstance(focusType.getClassLoader(), new Class<?>[] { focusType },
                                (focusProxy, focusMethod, focusArgs) -> null);
                    });
            var helpersType = IJeiRuntime.class.getMethod("getJeiHelpers").getReturnType();
            var helpers = Proxy.newProxyInstance(helpersType.getClassLoader(), new Class<?>[] { helpersType },
                    (proxy, method, arguments) -> method.getName().equals("getFocusFactory") ? focusFactory : null);
            var type = new RecipeType<>(ResourceLocation.fromNamespaceAndPath("minecraft", "crafting"), Object.class);
            var canonicalIdsEnabled = new AtomicBoolean();
            var canonicalIdThrows = new AtomicBoolean();
            var canonicalVisits = new AtomicInteger();
            var navigationFiller = new Object();
            var category = (IRecipeCategory<?>) Proxy.newProxyInstance(IRecipeCategory.class.getClassLoader(),
                    new Class<?>[] { IRecipeCategory.class }, (proxy, method, arguments) -> switch (method.getName()) {
                        case "getRecipeType" -> type;
                        case "getTitle" -> Component.literal("Crafting");
                        case "getTooltipStrings" -> List.of();
                        case "getRegistryName" -> {
                            canonicalVisits.incrementAndGet();
                            if (canonicalIdThrows.get()) throw new IllegalStateException("fixture canonical ID failure");
                            yield canonicalIdsEnabled.get()
                                    ? ResourceLocation.fromNamespaceAndPath("fixture", arguments[0] == navigationFiller
                                    ? "filler" : "press") : null;
                        }
                        default -> null;
                    });
            var replacementCategory = (IRecipeCategory<?>) Proxy.newProxyInstance(IRecipeCategory.class.getClassLoader(),
                    new Class<?>[] { IRecipeCategory.class }, (proxy, method, arguments) -> switch (method.getName()) {
                        case "getRecipeType" -> type;
                        case "getTitle" -> Component.literal("Replacement crafting");
                        default -> null;
                    });
            var recipe = new Object();
            var replacementRecipe = new Object();
            var unrelatedPotionQueryRecipe = new Object();
            var strengthPotionRecipe = new Object();
            var longStrengthPotionRecipe = new Object();
            var recipes = new AtomicReference<List<Object>>(List.of(recipe));
            var currentLayoutRecipe = new AtomicReference<Object>(recipe);
            var recipeOutputs = new java.util.IdentityHashMap<Object, ItemStack>();
            var nativeHolders = new AtomicReference<Collection<RecipeHolder<?>>>(List.of());
            var nativeHolderReads = new AtomicInteger();
            Supplier<Collection<RecipeHolder<?>>> nativeHolderSupplier = () -> {
                nativeHolderReads.incrementAndGet();
                return nativeHolders.get();
            };
            Supplier<Object> nativeRecipeFactory = () -> Proxy.newProxyInstance(Recipe.class.getClassLoader(),
                    new Class<?>[] { Recipe.class }, (proxy, method, arguments) -> switch (method.getName()) {
                        case "equals" -> Recipe.class.isInstance(arguments[0]);
                        case "hashCode" -> 1;
                        case "toString" -> "Native recipe fixture";
                        default -> null;
                    });
            var currentCategory = new AtomicReference<IRecipeCategory<?>>(category);
            var managerCategory = new AtomicReference<IRecipeCategory<?>>(category);
            var item = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("minecraft", "stone"));
            var outputStack = new ItemStack(item);
            recipeOutputs.put(recipe, outputStack);
            recipeOutputs.put(replacementRecipe, outputStack);
            recipeOutputs.put(unrelatedPotionQueryRecipe, outputStack);
            recipeOutputs.put(strengthPotionRecipe, PotionContents.createItemStack(
                    BuiltInRegistries.ITEM.get(ResourceLocation.parse("minecraft:potion")),
                    Potions.STRENGTH));
            recipeOutputs.put(longStrengthPotionRecipe, PotionContents.createItemStack(
                    BuiltInRegistries.ITEM.get(ResourceLocation.parse("minecraft:potion")),
                    Potions.LONG_STRENGTH));
            var componentSensitiveScenario = new AtomicBoolean();
            var oversizedTypeUid = "x".repeat(100_000);
            var oversizedSlotName = "s".repeat(100_000);
            var typedIngredientType = Proxy.newProxyInstance(IIngredientType.class.getClassLoader(),
                    new Class<?>[] { IIngredientType.class },
                    (proxy, method, arguments) -> method.getName().equals("getUid") && oversizedIngredientScenario.get()
                            ? oversizedTypeUid : method.invoke(VanillaTypes.ITEM_STACK, arguments));
            var typedIngredient = Proxy.newProxyInstance(ITypedIngredient.class.getClassLoader(),
                    new Class<?>[] { ITypedIngredient.class }, (proxy, method, arguments) -> switch (method.getName()) {
                        case "getType" -> ingredientBudgetScenario.get() || oversizedIngredientScenario.get()
                                ? typedIngredientType : VanillaTypes.ITEM_STACK;
                        case "getIngredient" -> recipeOutputs.get(currentLayoutRecipe.get());
                        default -> null;
                    });
            var visibilityType = IJeiRuntime.class.getMethod("getIngredientVisibility").getReturnType();
            var visibility = Proxy.newProxyInstance(visibilityType.getClassLoader(), new Class<?>[] { visibilityType },
                    (proxy, method, arguments) -> method.getName().equals("isIngredientVisible")
                            ? !hiddenIngredient.get() : null);
            var catalystLookup = (IRecipeCatalystLookup) Proxy.newProxyInstance(IRecipeCatalystLookup.class.getClassLoader(),
                    new Class<?>[] { IRecipeCatalystLookup.class }, (proxy, method, arguments) -> switch (method.getName()) {
                        case "includeHidden" -> proxy;
                        case "get" -> Stream.empty();
                        default -> null;
                    });
            var manager = (IRecipeManager) Proxy.newProxyInstance(IRecipeManager.class.getClassLoader(),
                    new Class<?>[] { IRecipeManager.class }, (proxy, method, arguments) -> switch (method.getName()) {
                        case "createRecipeCatalystLookup" -> catalystLookup;
                        case "createRecipeLayoutDrawable" -> {
                            currentLayoutRecipe.set(arguments[1]);
                            var layoutArgument = ((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments()[0];
                            var layoutType = layoutArgument instanceof Class<?> typeClass ? typeClass
                                    : (Class<?>) ((ParameterizedType) layoutArgument).getRawType();
                            var slotsType = layoutType.getMethod("getRecipeSlotsView").getReturnType();
                            var slotViewArgument = ((ParameterizedType) slotsType.getMethod("getSlotViews")
                                    .getGenericReturnType()).getActualTypeArguments()[0];
                            var slotViewType = slotViewArgument instanceof Class<?> typeClass ? typeClass
                                    : (Class<?>) ((ParameterizedType) slotViewArgument).getRawType();
                            var slotViews = ingredientBudgetScenario.get() || oversizedIngredientScenario.get()
                                    || oversizedSlotNameScenario.get()
                                    ? java.util.stream.IntStream.range(0, oversizedSlotNameScenario.get() ? 2 : 8)
                                    .mapToObj(index -> Proxy.newProxyInstance(
                                            slotViewType.getClassLoader(), new Class<?>[] { slotViewType },
                                            (slotProxy, slotMethod, slotArgs) -> switch (slotMethod.getName()) {
                                                case "getRole" -> RecipeIngredientRole.INPUT;
                                                case "getAllIngredientsList" -> oversizedSlotNameScenario.get()
                                                        ? List.of(typedIngredient)
                                                        : java.util.Collections.nCopies(21, typedIngredient);
                                                case "getSlotName" -> oversizedSlotNameScenario.get() && index == 1
                                                        ? java.util.Optional.of(oversizedSlotName)
                                                        : java.util.Optional.of("input-" + index);
                                                default -> null;
                                            })).toList()
                                    : List.of(Proxy.newProxyInstance(slotViewType.getClassLoader(), new Class<?>[] { slotViewType },
                                            (slotProxy, slotMethod, slotArgs) -> switch (slotMethod.getName()) {
                                                case "getRole" -> RecipeIngredientRole.OUTPUT;
                                                case "getAllIngredientsList" -> List.of(typedIngredient);
                                                case "getSlotName" -> java.util.Optional.of("result");
                                                default -> null;
                                            }));
                            var slots = Proxy.newProxyInstance(slotsType.getClassLoader(), new Class<?>[] { slotsType },
                                    (slotsProxy, slotsMethod, slotsArgs) -> slotsMethod.getName().equals("getSlotViews")
                                            ? slotViews : null);
                            var layout = Proxy.newProxyInstance(layoutType.getClassLoader(), new Class<?>[] { layoutType },
                                    (layoutProxy, layoutMethod, layoutArgs) -> layoutMethod.getName().equals("getRecipeSlotsView")
                                            ? slots : null);
                            yield java.util.Optional.of(layout);
                        }
                        case "getRecipeCategory" -> managerCategory.get();
                        case "createRecipeLookup" -> {
                            var includeHidden = new AtomicBoolean();
                            yield Proxy.newProxyInstance(IRecipeLookup.class.getClassLoader(),
                                    new Class<?>[] { IRecipeLookup.class }, (lookup, lookupMethod, lookupArguments) -> {
                                        return switch (lookupMethod.getName()) {
                                            case "includeHidden" -> { includeHidden.set(true); yield lookup; }
                                            case "limitFocus" -> {
                                                appliedFocus.set(lookupArguments[0]);
                                                focusApplications.incrementAndGet();
                                                yield lookup;
                                            }
                                            case "get" -> hiddenRecipe.get() && !includeHidden.get()
                                                    || componentSensitiveScenario.get()
                                                    && appliedFocus.get() instanceof Collection<?> focuses && !focuses.isEmpty()
                                                    ? Stream.empty() : recipes.get().stream().peek(value -> recipeVisits.incrementAndGet());
                                            default -> null;
                                        };
                                    });
                        }
                        case "createRecipeCategoryLookup" -> {
                            var includeHidden = new AtomicBoolean();
                            yield Proxy.newProxyInstance(IRecipeCategoriesLookup.class.getClassLoader(),
                                    new Class<?>[] { IRecipeCategoriesLookup.class }, (lookup, lookupMethod, lookupArguments) -> {
                                        return switch (lookupMethod.getName()) {
                                            case "includeHidden" -> { includeHidden.set(true); yield lookup; }
                                            case "limitTypes" -> { limitedRecipeTypes.set(lookupArguments[0]); yield lookup; }
                                            case "limitFocus" -> {
                                                appliedFocus.set(lookupArguments[0]);
                                                focusApplications.incrementAndGet();
                                                yield lookup;
                                            }
                                            case "get" -> hiddenCategory.get() && !includeHidden.get()
                                                    ? Stream.empty() : currentCategory.get() == null
                                                    ? Stream.empty() : Stream.concat(Stream.generate(() -> replacementCategory)
                                                    .limit(categoryPrefix.get()), Stream.of(currentCategory.get()))
                                                    .peek(value -> categoryVisits.incrementAndGet());
                                            default -> null;
                                        };
                                    });
                        }
                        default -> null;
                    });
            var recipesGuiType = IJeiRuntime.class.getMethod("getRecipesGui").getReturnType();
            var recipesGui = Proxy.newProxyInstance(recipesGuiType.getClassLoader(), new Class<?>[] { recipesGuiType },
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("showRecipes")) shownRecipes.set((List<?>) arguments[1]);
                        if (method.getName().equals("showTypes")) shownTypes.set((List<?>) arguments[0]);
                        return null;
                    });
            var runtime = (IJeiRuntime) Proxy.newProxyInstance(IJeiRuntime.class.getClassLoader(),
                    new Class<?>[] { IJeiRuntime.class }, (proxy, method, arguments) -> switch (method.getName()) {
                        case "getRecipeManager" -> manager;
                        case "getJeiHelpers" -> helpers;
                        case "getIngredientVisibility" -> visibility;
                        case "getRecipesGui" -> recipesGui;
                        default -> null;
                    });
            var plugin = new PackSageJeiPlugin(nativeHolderSupplier);
            plugin.onRuntimeAvailable(runtime);

            componentSensitiveScenario.set(true);
            recipes.set(List.of(unrelatedPotionQueryRecipe, strengthPotionRecipe, longStrengthPotionRecipe));
            var potionQuery = new Evidence.Query(Evidence.Operation.SEARCH, new Evidence.JsonObject(Map.of(
                    "text", new Evidence.JsonString(""),
                    "outputItem", new Evidence.JsonString("minecraft:potion"))), 1, null, null);
            var potionFirstPage = plugin.provider().query(potionQuery, SCOPE).toCompletableFuture().get();
            assertEquals(1, potionFirstPage.records().size());
            assertTrue(potionFirstPage.records().getFirst().recipePayload().outputs().getFirst()
                    .comparisonIdentity().startsWith("minecraft:potion|componentsHash="));
            assertTrue(Evidence.serialize(potionFirstPage.records().getFirst().payload()).contains("minecraft:strength"));
            assertNotNull(potionFirstPage.nextCursor());
            PackSageJeiPlugin.clientTicked();
            var potionSecondPage = plugin.provider().query(new Evidence.Query(Evidence.Operation.SEARCH,
                    potionQuery.arguments(), 1, SCOPE, potionFirstPage.nextCursor()), SCOPE).toCompletableFuture().get();
            assertEquals(1, potionSecondPage.records().size());
            assertTrue(potionSecondPage.records().getFirst().recipePayload().outputs().getFirst()
                    .comparisonIdentity().startsWith("minecraft:potion|componentsHash="));
            assertNotEquals(potionFirstPage.records().getFirst().recipePayload().outputs().getFirst()
                    .comparisonIdentity(), potionSecondPage.records().getFirst().recipePayload().outputs().getFirst()
                    .comparisonIdentity());
            assertTrue(Evidence.serialize(potionSecondPage.records().getFirst().payload()).contains("minecraft:long_strength"));
            var refreshedPotionTarget = plugin.provider().refreshNavigation(
                    potionFirstPage.records().getFirst().targets().getFirst(), SCOPE).toCompletableFuture().get();
            assertNotNull(refreshedPotionTarget);
            assertEquals(Evidence.NavigationStatus.OPENED,
                    plugin.provider().open(refreshedPotionTarget, SCOPE).status());
            componentSensitiveScenario.set(false);
            appliedFocus.set(null);
            recipes.set(List.of(recipe));

            var result = plugin.provider().query(new Evidence.Query(Evidence.Operation.SEARCH,
                    new Evidence.JsonObject(Map.of("text", new Evidence.JsonString(""),
                            "outputItem", new Evidence.JsonString("minecraft:stone"))), 4, null, null), SCOPE)
                    .toCompletableFuture().get();

            assertEquals("Crafting", result.records().getFirst().title());
            assertEquals(1, result.records().getFirst().targets().size());
            var initial = result.records().getFirst();
            assertEquals(1, initial.recipePayload().outputs().getFirst().quantity());
            assertTrue(initial.recipePayload().outputs().getFirst().comparisonIdentity()
                    .startsWith("minecraft:stone|componentsHash="));
            var handle = initial.source().sourceHandle();
            var nextScope = new Evidence.Scope("world", "en_us", "team", "jei-1", 2);
            var exactGet = new Evidence.Query(Evidence.Operation.GET,
                    new Evidence.JsonObject(Map.of("id", new Evidence.JsonString(handle))), 4, null, null);

            ingredientBudgetScenario.set(true);
            PackSageJeiPlugin.clientTicked();
            var furnaceIngredients = plugin.provider().query(exactGet, nextScope).toCompletableFuture().get();
            assertEquals(8, furnaceIngredients.records().getFirst().recipePayload().inputs().size());
            assertTrue(furnaceIngredients.records().getFirst().recipePayload().inputs().stream()
                    .allMatch(slot -> slot.alternatives().size() == 21));

            ingredientBudgetScenario.set(false);
            oversizedIngredientScenario.set(true);
            PackSageJeiPlugin.clientTicked();
            var oversizedExactGet = plugin.provider().query(exactGet, nextScope).toCompletableFuture().get();
            assertEquals(Evidence.Coverage.UNAVAILABLE, oversizedExactGet.coverage());
            PackSageJeiPlugin.clientTicked();
            var oversizedIngredient = plugin.provider().query(new Evidence.Query(Evidence.Operation.SEARCH,
                    new Evidence.JsonObject(Map.of("text", new Evidence.JsonString(""),
                            "outputItem", new Evidence.JsonString("minecraft:stone"))), 4, null, null), nextScope)
                    .toCompletableFuture().get();
            assertEquals(Evidence.Coverage.PARTIAL, oversizedIngredient.coverage());
            var oversizedPayload = Evidence.serialize(oversizedIngredient.records().getFirst().payload());
            assertTrue(oversizedPayload.getBytes(StandardCharsets.UTF_8).length <= 96 * 1024);
            assertFalse(oversizedPayload.contains(oversizedTypeUid));
            oversizedIngredientScenario.set(false);
            oversizedSlotNameScenario.set(true);
            PackSageJeiPlugin.clientTicked();
            var oversizedSlotResult = plugin.provider().query(new Evidence.Query(Evidence.Operation.SEARCH,
                    new Evidence.JsonObject(Map.of("text", new Evidence.JsonString(""),
                            "outputItem", new Evidence.JsonString("minecraft:stone"))), 4, null, null), nextScope)
                    .toCompletableFuture().get();
            assertEquals(Evidence.Coverage.PARTIAL, oversizedSlotResult.coverage());
            var oversizedSlotPayload = Evidence.serialize(oversizedSlotResult.records().getFirst().payload());
            assertTrue(oversizedSlotPayload.getBytes(StandardCharsets.UTF_8).length <= 96 * 1024);
            assertFalse(oversizedSlotPayload.contains(oversizedSlotName));
            assertEquals(1, oversizedSlotResult.records().getFirst().recipePayload().inputs().size());
            assertEquals("input-0", oversizedSlotResult.records().getFirst().recipePayload().inputs().getFirst().groupId());
            oversizedSlotNameScenario.set(false);
            outputStack.setCount(2);
            PackSageJeiPlugin.clientTicked();
            var refreshed = plugin.provider().query(exactGet, nextScope).toCompletableFuture().get();
            assertEquals(1, refreshed.records().size());
            assertEquals(refreshed.records().getFirst().source().coverage(), refreshed.coverage());
            assertTrue(refreshed.coverage() == Evidence.Coverage.COMPLETE
                    || refreshed.coverage() == Evidence.Coverage.PARTIAL);
            assertEquals(handle, refreshed.records().getFirst().source().sourceHandle());
            assertNotEquals(initial.source().provenance(), refreshed.records().getFirst().source().provenance());
            assertEquals(nextScope, refreshed.records().getFirst().source().scope());
            assertEquals(2, refreshed.records().getFirst().recipePayload().outputs().getFirst().quantity());
            assertEquals(List.of(type), limitedRecipeTypes.get());
            assertEquals(Evidence.NavigationStatus.OPENED,
                    plugin.provider().open(refreshed.records().getFirst().targets().getFirst(), nextScope).status());
            assertSame(recipe, shownRecipes.get().getFirst());

            PackSageJeiPlugin.clientTicked();
            var unretainedHandle = plugin.provider().query(new Evidence.Query(Evidence.Operation.GET,
                    new Evidence.JsonObject(Map.of("id", new Evidence.JsonString("jei/1/999/minecraft:crafting/old"))),
                    4, null, null), nextScope).toCompletableFuture().get();
            assertEquals(Evidence.Coverage.UNAVAILABLE, unretainedHandle.coverage());
            assertTrue(unretainedHandle.records().isEmpty());

            var distantRecipes = new java.util.ArrayList<Object>(java.util.Collections.nCopies(65_537, replacementRecipe));
            distantRecipes.set(65_536, recipe);
            recipes.set(distantRecipes);
            recipeVisits.set(0);
            PackSageJeiPlugin.clientTicked();
            var boundedRecipeRead = plugin.provider().query(exactGet, nextScope).toCompletableFuture().get();
            assertEquals(Evidence.Coverage.ERROR, boundedRecipeRead.coverage());
            assertTrue(boundedRecipeRead.records().isEmpty());
            assertTrue(recipeVisits.get() <= 65_536);
            recipes.set(List.of(recipe));
            categoryPrefix.set(400);
            categoryVisits.set(0);
            PackSageJeiPlugin.clientTicked();
            var boundedCategoryRead = plugin.provider().query(exactGet, nextScope).toCompletableFuture().get();
            assertEquals(Evidence.Coverage.ERROR, boundedCategoryRead.coverage());
            assertTrue(boundedCategoryRead.records().isEmpty());
            assertTrue(categoryVisits.get() <= 256);
            categoryPrefix.set(0);

            var laterRecipe = new Object();
            var laterRecipes = new java.util.ArrayList<Object>();
            laterRecipes.add(new Object());
            laterRecipes.add(laterRecipe);
            recipes.set(List.copyOf(laterRecipes));
            PackSageJeiPlugin.clientTicked();
            var firstLaterWindow = plugin.provider().query(new Evidence.Query(Evidence.Operation.SEARCH,
                    new Evidence.JsonObject(Map.of("text", new Evidence.JsonString(""),
                            "outputItem", new Evidence.JsonString("minecraft:stone"))), 1, null, null), SCOPE)
                    .toCompletableFuture().get();
            PackSageJeiPlugin.clientTicked();
            var laterPage = plugin.provider().query(new Evidence.Query(Evidence.Operation.SEARCH,
                    new Evidence.JsonObject(Map.of("text", new Evidence.JsonString(""),
                            "outputItem", new Evidence.JsonString("minecraft:stone"))), 1, SCOPE,
                    firstLaterWindow.nextCursor()), SCOPE).toCompletableFuture().get();
            assertEquals(1, laterPage.records().size());
            var laterHandle = laterPage.records().getFirst().source().sourceHandle();
            PackSageJeiPlugin.clientTicked();
            var laterRefresh = plugin.provider().query(new Evidence.Query(Evidence.Operation.GET,
                    new Evidence.JsonObject(Map.of("id", new Evidence.JsonString(laterHandle))), 1, null, null), nextScope)
                    .toCompletableFuture().get();
            assertEquals(1, laterRefresh.records().size());
            assertEquals(laterHandle, laterRefresh.records().getFirst().source().sourceHandle());
            assertEquals(List.of(type), limitedRecipeTypes.get());
            assertEquals(Evidence.NavigationStatus.OPENED,
                    plugin.provider().open(laterRefresh.records().getFirst().targets().getFirst(), nextScope).status());
            assertSame(laterRecipe, shownRecipes.get().getFirst());
            recipes.set(List.of(recipe));

            for (var foreignScope : List.of(
                    new Evidence.Scope("other-world", "en_us", "team", "jei-1", 2),
                    new Evidence.Scope("world", "fr_fr", "team", "jei-1", 2),
                    new Evidence.Scope("world", "en_us", "other-team", "jei-1", 2),
                    new Evidence.Scope("world", "en_us", "team", "jei-2", 2))) {
                PackSageJeiPlugin.clientTicked();
                var foreign = plugin.provider().query(exactGet, foreignScope).toCompletableFuture().get();
                assertEquals(Evidence.Coverage.UNAVAILABLE, foreign.coverage());
                assertTrue(foreign.records().isEmpty());
            }

            hiddenIngredient.set(true);
            PackSageJeiPlugin.clientTicked();
            var hiddenByIngredient = plugin.provider().query(exactGet, nextScope).toCompletableFuture().get();
            assertEquals(Evidence.Coverage.UNAVAILABLE, hiddenByIngredient.coverage());
            hiddenIngredient.set(false);

            hiddenRecipe.set(true);
            PackSageJeiPlugin.clientTicked();
            var hiddenByRecipe = plugin.provider().query(exactGet, nextScope).toCompletableFuture().get();
            assertEquals(Evidence.Coverage.UNAVAILABLE, hiddenByRecipe.coverage());
            hiddenRecipe.set(false);

            hiddenCategory.set(true);
            PackSageJeiPlugin.clientTicked();
            var hiddenByCategory = plugin.provider().query(exactGet, nextScope).toCompletableFuture().get();
            assertEquals(Evidence.Coverage.UNAVAILABLE, hiddenByCategory.coverage());
            hiddenCategory.set(false);

            recipes.set(List.of(replacementRecipe));
            PackSageJeiPlugin.clientTicked();
            var replacedRecipe = plugin.provider().query(exactGet, nextScope).toCompletableFuture().get();
            assertEquals(Evidence.Coverage.UNAVAILABLE, replacedRecipe.coverage());

            recipes.set(List.of());
            PackSageJeiPlugin.clientTicked();
            var removedRecipe = plugin.provider().query(exactGet, nextScope).toCompletableFuture().get();
            assertEquals(Evidence.Coverage.UNAVAILABLE, removedRecipe.coverage());
            recipes.set(List.of(recipe));

            currentCategory.set(replacementCategory);
            managerCategory.set(replacementCategory);
            PackSageJeiPlugin.clientTicked();
            var replacedCategory = plugin.provider().query(exactGet, nextScope).toCompletableFuture().get();
            assertEquals(Evidence.Coverage.UNAVAILABLE, replacedCategory.coverage());

            currentCategory.set(category);
            managerCategory.set(category);
            canonicalIdsEnabled.set(true);
            recipes.set(List.of(recipe));
            PackSageJeiPlugin.clientTicked();
            var durableRecord = plugin.provider().query(new Evidence.Query(Evidence.Operation.SEARCH,
                    new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("fixture:press"))), 4,
                    null, null), nextScope).toCompletableFuture().get().records().getFirst();
            var savedTarget = durableRecord.targets().getFirst();
            assertEquals("recipe-v1|minecraft:crafting|fixture:press", savedTarget.targetId());

            var recreatedRecipe = new Object();
            recipes.set(List.of(recreatedRecipe));
            var recreatedPlugin = new PackSageJeiPlugin(nativeHolderSupplier);
            recreatedPlugin.onRuntimeAvailable(runtime);
            var recreatedScope = new Evidence.Scope("world-reconnected", "fr_fr", "team", "jei-2", 3);
            var refreshedTarget = recreatedPlugin.provider().refreshNavigation(savedTarget, recreatedScope)
                    .toCompletableFuture().get();
            assertNotNull(refreshedTarget, "the saved recipe should resolve after recreating the JEI runtime state");
            assertNotEquals(savedTarget.sourceHandle(), refreshedTarget.sourceHandle());
            assertEquals(savedTarget.targetId(), refreshedTarget.targetId());
            assertEquals(recreatedScope, refreshedTarget.scope());
            assertEquals(Evidence.NavigationStatus.OPENED, recreatedPlugin.provider().open(refreshedTarget, recreatedScope).status());
            assertSame(recreatedRecipe, shownRecipes.get().getFirst());

            var legacyTarget = new Evidence.NavigationTarget(savedTarget.scope(), "jei", savedTarget.sourceHandle(),
                    savedTarget.sourceHandle());
            PackSageJeiPlugin.clientTicked();
            assertNotNull(recreatedPlugin.provider().refreshNavigation(legacyTarget, recreatedScope)
                    .toCompletableFuture().get(), "an unambiguous legacy canonical handle should migrate");

            var largeCategory = new java.util.ArrayList<Object>(java.util.Collections.nCopies(4096, navigationFiller));
            largeCategory.set(4095, recreatedRecipe);
            recipes.set(largeCategory);
            ingredientBudgetScenario.set(true);
            PackSageJeiPlugin.clientTicked();
            var largeCategoryTarget = recreatedPlugin.provider().refreshNavigation(savedTarget, recreatedScope)
                    .toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(largeCategoryTarget, "navigation must not exhaust the semantic extraction budget scanning identities");
            assertEquals(Evidence.NavigationStatus.OPENED,
                    recreatedPlugin.provider().open(largeCategoryTarget, recreatedScope).status());
            assertSame(recreatedRecipe, shownRecipes.get().getFirst());
            ingredientBudgetScenario.set(false);
            largeCategory.set(0, new Object());
            PackSageJeiPlugin.clientTicked();
            assertNull(recreatedPlugin.provider().refreshNavigation(savedTarget, recreatedScope)
                    .toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS),
                    "a duplicate beyond the old 2048 scan window must still reject navigation");
            recipes.set(new java.util.ArrayList<>(java.util.Collections.nCopies(65_536, navigationFiller)));
            recipes.get().set(65_535, recreatedRecipe);
            PackSageJeiPlugin.clientTicked();
            var lastBoundedTarget = recreatedPlugin.provider().refreshNavigation(savedTarget, recreatedScope)
                    .toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(lastBoundedTarget, "a complete scan at the navigation bound must resolve");
            assertEquals(Evidence.NavigationStatus.OPENED,
                    recreatedPlugin.provider().open(lastBoundedTarget, recreatedScope).status());
            recipes.get().add(navigationFiller);
            recipes.get().set(0, recreatedRecipe);
            recipes.get().set(65_535, navigationFiller);
            recipeVisits.set(0);
            canonicalVisits.set(0);
            PackSageJeiPlugin.clientTicked();
            assertNull(recreatedPlugin.provider().refreshNavigation(savedTarget, recreatedScope)
                    .toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS),
                    "an incomplete scan beyond the navigation bound must remain unavailable");
            assertEquals(65_537, recipeVisits.get(), "the stream iterator can prefetch one entry in hasNext");
            assertEquals(65_536, canonicalVisits.get());

            recipes.set(List.of(recreatedRecipe, new Object()));
            PackSageJeiPlugin.clientTicked();
            assertNull(recreatedPlugin.provider().refreshNavigation(savedTarget, recreatedScope).toCompletableFuture().get(),
                    "duplicate canonical IDs must not select an arbitrary recipe");
            recipes.set(List.of(recreatedRecipe));
            hiddenRecipe.set(true);
            PackSageJeiPlugin.clientTicked();
            assertNull(recreatedPlugin.provider().refreshNavigation(savedTarget, recreatedScope).toCompletableFuture().get(),
                    "hidden recipes must not be restored from saved navigation");
            hiddenRecipe.set(false);
            recipes.set(List.of());
            PackSageJeiPlugin.clientTicked();
            assertNull(recreatedPlugin.provider().refreshNavigation(savedTarget, recreatedScope).toCompletableFuture().get(),
                    "removed recipes must not be restored from saved navigation");
            PackSageJeiPlugin.clientTicked();
            assertNull(recreatedPlugin.provider().refreshNavigation(new Evidence.NavigationTarget(savedTarget.scope(),
                    "jei", "bad", "recipe-v1|minecraft:crafting|not a resource id"), recreatedScope)
                    .toCompletableFuture().get(), "malformed stable targets must fail closed");
            PackSageJeiPlugin.clientTicked();
            var categoryTarget = recreatedPlugin.provider().refreshNavigation(new Evidence.NavigationTarget(
                    savedTarget.scope(), "jei", "old-category", "category-v1|minecraft:crafting"), recreatedScope)
                    .toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(categoryTarget, "category destinations must resolve without a recipe object");
            assertEquals(Evidence.NavigationStatus.OPENED, recreatedPlugin.provider().open(categoryTarget, recreatedScope).status());
            assertEquals(List.of(type), shownTypes.get());
            recipes.set(List.of(replacementRecipe));
            canonicalIdThrows.set(true);
            PackSageJeiPlugin.clientTicked();
            var temporaryRecord = plugin.provider().query(new Evidence.Query(Evidence.Operation.SEARCH,
                    new Evidence.JsonObject(Map.of("text", new Evidence.JsonString(""))), 1, null, null), nextScope)
                    .toCompletableFuture().get().records().getFirst();
            assertEquals(temporaryRecord.source().sourceHandle(), temporaryRecord.targets().getFirst().targetId(),
                    "a throwing canonical ID must retain only its temporary locator");
            PackSageJeiPlugin.clientTicked();
            assertNotNull(plugin.provider().refreshNavigation(temporaryRecord.targets().getFirst(), nextScope)
                    .toCompletableFuture().get(), "temporary handles should remain openable in the same runtime");
            PackSageJeiPlugin.clientTicked();
            assertNotNull(plugin.provider().refreshNavigation(temporaryRecord.targets().getFirst(), new Evidence.Scope(
                    nextScope.worldSession(), nextScope.resourcesLocale(), nextScope.playerTeam(), nextScope.providerRuntime(), 4))
                    .toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS),
                    "temporary handles must survive ordinary request progression in the same environment");
            PackSageJeiPlugin.clientTicked();
            assertNull(recreatedPlugin.provider().refreshNavigation(temporaryRecord.targets().getFirst(), nextScope)
                    .toCompletableFuture().get(), "a temporary locator must not acquire semantic fallback identity");

            canonicalIdThrows.set(false);
            var addonType = new RecipeType<>(ResourceLocation.parse("addon:machine"), Object.class);
            var addonCategory = (IRecipeCategory<?>) Proxy.newProxyInstance(IRecipeCategory.class.getClassLoader(),
                    new Class<?>[] { IRecipeCategory.class }, (proxy, method, arguments) ->
                            method.getName().equals("getRecipeType") ? addonType : method.invoke(category, arguments));
            currentCategory.set(addonCategory);
            managerCategory.set(addonCategory);
            PackSageJeiPlugin.clientTicked();
            var opaqueRecord = plugin.provider().query(new Evidence.Query(Evidence.Operation.SEARCH,
                    new Evidence.JsonObject(Map.of("text", new Evidence.JsonString(""))), 1, null, null), nextScope)
                    .toCompletableFuture().get().records().getFirst();
            assertEquals(Evidence.DecodingStatus.OPAQUE, opaqueRecord.recipePayload().decodingStatus());
            assertEquals("recipe-v1|addon:machine|fixture:press", opaqueRecord.targets().getFirst().targetId(),
                    "custom category discovery must preserve canonical identity even when slot semantics are opaque");
            var addonRestart = new PackSageJeiPlugin(nativeHolderSupplier);
            addonRestart.onRuntimeAvailable(runtime);
            var addonTarget = addonRestart.provider().refreshNavigation(opaqueRecord.targets().getFirst(), recreatedScope)
                    .toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(addonTarget);
            assertEquals(Evidence.NavigationStatus.OPENED, addonRestart.provider().open(addonTarget, recreatedScope).status());
            assertSame(replacementRecipe, shownRecipes.get().getFirst());

            var nativeId = ResourceLocation.parse("actuallyadditions:laser/crystalize_diamatine_crystal");
            canonicalIdsEnabled.set(false);
            var nativeRecipe = nativeRecipeFactory.get();
            recipes.set(List.of(nativeRecipe));
            nativeHolders.set(List.of(new RecipeHolder<>(nativeId, (Recipe<?>) nativeRecipe)));
            PackSageJeiPlugin.clientTicked();
            var nativeRecord = plugin.provider().query(new Evidence.Query(Evidence.Operation.SEARCH,
                    new Evidence.JsonObject(Map.of("text", new Evidence.JsonString(""))), 1,
                    null, null), nextScope).toCompletableFuture().get().records().getFirst();
            var nativeTarget = nativeRecord.targets().getFirst();
            assertEquals("recipe-v1|addon:machine|" + nativeId, nativeTarget.targetId(),
                    "a null JEI name should recover the native holder ID by exact recipe object identity");

            var reloadedNativeRecipe = nativeRecipeFactory.get();
            recipes.set(List.of(reloadedNativeRecipe));
            nativeHolders.set(List.of(new RecipeHolder<>(nativeId, (Recipe<?>) reloadedNativeRecipe)));
            var nativeRestart = new PackSageJeiPlugin(nativeHolderSupplier);
            nativeRestart.onRuntimeAvailable(runtime);
            PackSageJeiPlugin.clientTicked();
            var nativeRefresh = nativeRestart.provider().refreshNavigation(nativeTarget, recreatedScope)
                    .toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(nativeRefresh, "a recreated native recipe with the same holder ID should resolve");
            assertEquals(Evidence.NavigationStatus.OPENED, nativeRestart.provider().open(nativeRefresh, recreatedScope).status());
            assertSame(reloadedNativeRecipe, shownRecipes.get().getFirst());

            var fillerNativeRecipe = nativeRecipeFactory.get();
            var fillerHolder = new RecipeHolder<>(ResourceLocation.parse("fixture:filler"),
                    (Recipe<?>) fillerNativeRecipe);
            var boundedNativeHolders = new java.util.ArrayList<RecipeHolder<?>>(java.util.Collections.nCopies(
                    65_535, fillerHolder));
            boundedNativeHolders.add(new RecipeHolder<>(nativeId, (Recipe<?>) reloadedNativeRecipe));
            nativeHolders.set(List.copyOf(boundedNativeHolders));
            PackSageJeiPlugin.clientTicked();
            assertNotNull(nativeRestart.provider().refreshNavigation(nativeTarget, recreatedScope)
                    .toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS),
                    "a complete native holder scan at the cap should retain its ID");
            boundedNativeHolders.add(fillerHolder);
            nativeHolders.set(List.copyOf(boundedNativeHolders));
            PackSageJeiPlugin.clientTicked();
            assertNull(nativeRestart.provider().refreshNavigation(nativeTarget, recreatedScope)
                    .toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS),
                    "an incomplete native holder scan must discard all fallback IDs");

            var sameNativeObject = nativeRecipeFactory.get();
            recipes.set(List.of(sameNativeObject));
            nativeHolders.set(List.of(new RecipeHolder<>(nativeId, (Recipe<?>) sameNativeObject),
                    new RecipeHolder<>(ResourceLocation.parse("fixture:alias"), (Recipe<?>) sameNativeObject),
                    new RecipeHolder<>(nativeId, (Recipe<?>) sameNativeObject)));
            PackSageJeiPlugin.clientTicked();
            var ambiguousNative = plugin.provider().query(new Evidence.Query(Evidence.Operation.SEARCH,
                    new Evidence.JsonObject(Map.of("text", new Evidence.JsonString(""))), 1, null, null), nextScope)
                    .toCompletableFuture().get().records().getFirst();
            assertEquals(ambiguousNative.source().sourceHandle(), ambiguousNative.targets().getFirst().targetId(),
                    "multiple holder IDs for one recipe object must remain temporary");

            var registeredNativeObject = nativeRecipeFactory.get();
            recipes.set(List.of(nativeRecipeFactory.get()));
            nativeHolders.set(List.of(new RecipeHolder<>(nativeId, (Recipe<?>) registeredNativeObject)));
            PackSageJeiPlugin.clientTicked();
            var equalContentNative = plugin.provider().query(new Evidence.Query(Evidence.Operation.SEARCH,
                    new Evidence.JsonObject(Map.of("text", new Evidence.JsonString(""))), 1, null, null), nextScope)
                    .toCompletableFuture().get().records().getFirst();
            assertEquals(equalContentNative.source().sourceHandle(), equalContentNative.targets().getFirst().targetId(),
                    "an equal-content object without holder identity must not borrow another recipe ID");

            recipes.set(List.of(nativeRecipeFactory.get()));
            nativeHolders.set(List.of());
            PackSageJeiPlugin.clientTicked();
            var missingNative = plugin.provider().query(new Evidence.Query(Evidence.Operation.SEARCH,
                    new Evidence.JsonObject(Map.of("text", new Evidence.JsonString(""))), 1, null, null), nextScope)
                    .toCompletableFuture().get().records().getFirst();
            assertEquals(missingNative.source().sourceHandle(), missingNative.targets().getFirst().targetId(),
                    "native recipes absent from the current holder collection must remain temporary");

            canonicalIdsEnabled.set(true);
            recipes.set(List.of(nativeRecipeFactory.get()));
            nativeHolders.set(List.of(new RecipeHolder<>(nativeId, (Recipe<?>) recipes.get().getFirst())));
            var nativeHolderReadsBeforeExplicitId = nativeHolderReads.get();
            PackSageJeiPlugin.clientTicked();
            var explicitJeiId = plugin.provider().query(new Evidence.Query(Evidence.Operation.SEARCH,
                    new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("fixture:press"))), 1,
                    null, null), nextScope).toCompletableFuture().get().records().getFirst();
            assertEquals("recipe-v1|addon:machine|fixture:press", explicitJeiId.targets().getFirst().targetId(),
                    "an explicit JEI registry ID must take precedence over the native holder ID");
            assertEquals(nativeHolderReadsBeforeExplicitId, nativeHolderReads.get(),
                    "explicit JEI names must not initialize the native holder index");

            canonicalIdsEnabled.set(false);
            var firstPartialNative = nativeRecipeFactory.get();
            var secondPartialNative = nativeRecipeFactory.get();
            recipes.set(List.of(firstPartialNative, secondPartialNative));
            var firstPartialHolder = new RecipeHolder<>(ResourceLocation.parse("fixture:first_partial"),
                    (Recipe<?>) firstPartialNative);
            var secondPartialHolder = new RecipeHolder<>(ResourceLocation.parse("fixture:second_partial"),
                    (Recipe<?>) secondPartialNative);
            nativeHolders.set(new java.util.AbstractCollection<>() {
                @Override
                public java.util.Iterator<RecipeHolder<?>> iterator() {
                    return new java.util.Iterator<>() {
                        private int index;

                        @Override
                        public boolean hasNext() {
                            if (index < 2) return true;
                            throw new AssertionError("native holder scan failed after a partial result");
                        }

                        @Override
                        public RecipeHolder<?> next() {
                            return index++ == 0 ? firstPartialHolder : secondPartialHolder;
                        }
                    };
                }

                @Override
                public int size() {
                    return 3;
                }
            });
            PackSageJeiPlugin.clientTicked();
            var partialNativeRecords = plugin.provider().query(new Evidence.Query(Evidence.Operation.SEARCH,
                    new Evidence.JsonObject(Map.of("text", new Evidence.JsonString(""))), 10, null, null), nextScope)
                    .toCompletableFuture().get().records();
            assertEquals(2, partialNativeRecords.size());
            assertTrue(partialNativeRecords.stream().allMatch(record -> record.targets().getFirst().targetId()
                    .equals(record.source().sourceHandle())),
                    "a holder iterator failure must discard every ID from its partial native index");
        } finally {
            minecraftInstance.set(null, previousMinecraft);
        }
    }

    @Test
    void navigationRejectsMismatchedSourceHandle() {
        var projection = new JeiProvider.Projection("mod:category", "stable-handle", "mod:recipe",
                "Recipe", List.of(), List.of(), List.of(), true, Evidence.Coverage.COMPLETE,
                Evidence.DecodingStatus.VERIFIED, null);
        var provider = new JeiProvider(new JeiProvider.Snapshot(SCOPE, 1, 8, List.of(projection), ignored -> { }));
        provider.refresh();

        var target = new Evidence.NavigationTarget(SCOPE, "jei", "old-handle", "stable-handle");
        assertEquals(Evidence.NavigationStatus.STALE, provider.open(target, SCOPE).status());
    }

    @Test
    void snapshotFactoryReceivesSuppliedScopeAndProjectionErrorsStayVisible() throws Exception {
        var supplied = new AtomicReference<Evidence.Scope>();
        JeiProvider.SnapshotSource source = (scope, query, budget) -> {
            supplied.set(scope);
            var projection = JeiProvider.Projection.error("addon:category", "jei/4/9/addon:category/recipe-0",
                    null, "Custom", "projection failed");
            return new JeiProvider.Snapshot(scope, 4, 9, List.of(projection), ignored -> { },
                    Evidence.Coverage.ERROR, true, null);
        };
        var provider = new JeiProvider(source, Runnable::run, () -> true);
        var scope = new Evidence.Scope("actual-world", "zh_tw", "actual-team", "jei-runtime-4", 19);
        var result = provider.query(new Evidence.Query(Evidence.Operation.GET,
                new Evidence.JsonObject(Map.of("id", new Evidence.JsonString("addon:category"))), 1, null, null), scope)
                .toCompletableFuture().get();
        assertEquals(scope, supplied.get());
        assertEquals(Evidence.Coverage.ERROR, result.coverage());
        assertEquals(Evidence.DecodingStatus.UNKNOWN, result.records().get(0).recipePayload().decodingStatus());
    }

    @Test
    void projectionFixtureKeepsNullableCanonicalIdentity() throws Exception {
        try (var input = getClass().getResourceAsStream("/jei/projection-fixture.json")) {
            var fixture = Evidence.deserializeObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            assertEquals(new Evidence.JsonNull(), fixture.values().get("canonicalRecipeId"));
            assertEquals("mod:category#0", ((Evidence.JsonString) fixture.values().get("viewerHandle")).value());
        }
    }

    private static PackSageTools.ReadRequest recipeRequest(String provider, String outputItem) {
        return new PackSageTools.ReadRequest("search_recipe", new Evidence.JsonObject(Map.of(
                "filter", new Evidence.JsonObject(Map.of("provider", new Evidence.JsonString(provider),
                        "outputItem", new Evidence.JsonString(outputItem))))), SCOPE);
    }

    private static PackSageTools.ReadRequest recipeSearchRequest(String cursor) {
        var arguments = new java.util.LinkedHashMap<String, Evidence.JsonValue>();
        arguments.put("filter", new Evidence.JsonObject(Map.of("provider", new Evidence.JsonString("jei"),
                "text", new Evidence.JsonString("nothing"))));
        arguments.put("limit", new Evidence.JsonNumber(java.math.BigDecimal.ONE));
        if (cursor != null) arguments.put("cursor", new Evidence.JsonString(cursor));
        return new PackSageTools.ReadRequest("search_recipe", new Evidence.JsonObject(arguments), SCOPE);
    }

    private static Toolbox.Context context(AdapterRegistry registry, Map<String, Toolbox.Reference> references) {
        return new Toolbox.Context(() -> SCOPE, references, registry, new Toolbox.DocumentBudget(), "test-call");
    }

    private static Toolbox.Code failureCode(java.util.concurrent.CompletionStage<Evidence.Result> stage) {
        try {
            stage.toCompletableFuture().join();
            return null;
        } catch (java.util.concurrent.CompletionException failure) {
            return ((Toolbox.Failure) failure.getCause()).code();
        }
    }
}
