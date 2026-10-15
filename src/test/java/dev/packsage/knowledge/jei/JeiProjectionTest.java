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
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.lang.reflect.ParameterizedType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

        var target = new Evidence.NavigationTarget(SCOPE, "jei", "minecraft:crafting#0", "minecraft:crafting#0");
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
            var hiddenRecipe = new AtomicBoolean();
            var hiddenCategory = new AtomicBoolean();
            var hiddenIngredient = new AtomicBoolean();
            var ingredientBudgetScenario = new AtomicBoolean();
            var oversizedIngredientScenario = new AtomicBoolean();
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
            var category = (IRecipeCategory<?>) Proxy.newProxyInstance(IRecipeCategory.class.getClassLoader(),
                    new Class<?>[] { IRecipeCategory.class }, (proxy, method, arguments) -> switch (method.getName()) {
                        case "getRecipeType" -> type;
                        case "getTitle" -> Component.literal("Crafting");
                        case "getTooltipStrings" -> List.of();
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
            var recipes = new AtomicReference<List<Object>>(List.of(recipe));
            var currentCategory = new AtomicReference<IRecipeCategory<?>>(category);
            var managerCategory = new AtomicReference<IRecipeCategory<?>>(category);
            var item = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("minecraft", "stone"));
            var outputStack = new ItemStack(item);
            var typedIngredientType = Proxy.newProxyInstance(IIngredientType.class.getClassLoader(),
                    new Class<?>[] { IIngredientType.class },
                    (proxy, method, arguments) -> method.getName().equals("getUid") && oversizedIngredientScenario.get()
                            ? "x".repeat(100_000) : method.invoke(VanillaTypes.ITEM_STACK, arguments));
            var typedIngredient = Proxy.newProxyInstance(ITypedIngredient.class.getClassLoader(),
                    new Class<?>[] { ITypedIngredient.class }, (proxy, method, arguments) -> switch (method.getName()) {
                        case "getType" -> ingredientBudgetScenario.get() || oversizedIngredientScenario.get()
                                ? typedIngredientType : VanillaTypes.ITEM_STACK;
                        case "getIngredient" -> outputStack;
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
                            var layoutArgument = ((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments()[0];
                            var layoutType = layoutArgument instanceof Class<?> typeClass ? typeClass
                                    : (Class<?>) ((ParameterizedType) layoutArgument).getRawType();
                            var slotsType = layoutType.getMethod("getRecipeSlotsView").getReturnType();
                            var slotViewArgument = ((ParameterizedType) slotsType.getMethod("getSlotViews")
                                    .getGenericReturnType()).getActualTypeArguments()[0];
                            var slotViewType = slotViewArgument instanceof Class<?> typeClass ? typeClass
                                    : (Class<?>) ((ParameterizedType) slotViewArgument).getRawType();
                            var slotViews = ingredientBudgetScenario.get() || oversizedIngredientScenario.get()
                                    ? java.util.stream.IntStream.range(0, 8).mapToObj(index -> Proxy.newProxyInstance(
                                            slotViewType.getClassLoader(), new Class<?>[] { slotViewType },
                                            (slotProxy, slotMethod, slotArgs) -> switch (slotMethod.getName()) {
                                                case "getRole" -> RecipeIngredientRole.INPUT;
                                                case "getAllIngredientsList" -> java.util.Collections.nCopies(21, typedIngredient);
                                                case "getSlotName" -> java.util.Optional.of("input-" + index);
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
            var plugin = new PackSageJeiPlugin();
            plugin.onRuntimeAvailable(runtime);

            var result = plugin.provider().query(new Evidence.Query(Evidence.Operation.SEARCH,
                    new Evidence.JsonObject(Map.of("text", new Evidence.JsonString(""),
                            "outputItem", new Evidence.JsonString("minecraft:stone"))), 4, null, null), SCOPE)
                    .toCompletableFuture().get();

            assertEquals(2, focusApplications.get(), "both JEI category and recipe lookups must receive the output focus");
            assertEquals(1, focusCreations.get());
            assertNotNull(appliedFocus.get());
            assertEquals(RecipeIngredientRole.OUTPUT, focusArguments.get()[0]);
            assertEquals(VanillaTypes.ITEM_STACK, focusArguments.get()[1]);
            assertSame(item, ((ItemStack) focusArguments.get()[2]).getItem());
            assertEquals("Crafting", result.records().getFirst().title());
            assertEquals(1, result.records().getFirst().targets().size());
            var initial = result.records().getFirst();
            assertEquals(1, initial.recipePayload().outputs().getFirst().quantity());
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
            var oversizedIngredient = plugin.provider().query(exactGet, nextScope).toCompletableFuture().get();
            assertEquals(Evidence.Coverage.PARTIAL, oversizedIngredient.coverage());
            assertTrue(Evidence.serialize(oversizedIngredient.records().getFirst().payload())
                    .getBytes(StandardCharsets.UTF_8).length <= 96 * 1024);
            oversizedIngredientScenario.set(false);
            focusApplications.set(0);
            focusCreations.set(0);

            outputStack.setCount(2);
            PackSageJeiPlugin.clientTicked();
            var refreshed = plugin.provider().query(exactGet, nextScope).toCompletableFuture().get();
            assertEquals(1, refreshed.records().size());
            assertEquals(refreshed.records().getFirst().source().coverage(), refreshed.coverage());
            assertTrue(refreshed.coverage() == Evidence.Coverage.COMPLETE
                    || refreshed.coverage() == Evidence.Coverage.PARTIAL);
            assertEquals(4, focusApplications.get());
            assertEquals(2, focusCreations.get(), "exact revalidation must reapply the original output focus");
            assertEquals(handle, refreshed.records().getFirst().source().sourceHandle());
            assertNotEquals(initial.source().provenance(), refreshed.records().getFirst().source().provenance());
            assertEquals(nextScope, refreshed.records().getFirst().source().scope());
            assertEquals(2, refreshed.records().getFirst().recipePayload().outputs().getFirst().quantity());
            assertEquals(List.of(type), limitedRecipeTypes.get());
            assertEquals(Evidence.NavigationStatus.OPENED,
                    plugin.provider().open(refreshed.records().getFirst().targets().getFirst(), nextScope).status());
            assertSame(recipe, shownRecipes.get().getFirst());

            var focusApplicationsBeforeUnknown = focusApplications.get();
            PackSageJeiPlugin.clientTicked();
            var unretainedHandle = plugin.provider().query(new Evidence.Query(Evidence.Operation.GET,
                    new Evidence.JsonObject(Map.of("id", new Evidence.JsonString("jei/1/999/minecraft:crafting/old"))),
                    4, null, null), nextScope).toCompletableFuture().get();
            assertEquals(Evidence.Coverage.UNAVAILABLE, unretainedHandle.coverage());
            assertTrue(unretainedHandle.records().isEmpty());
            assertEquals(focusApplicationsBeforeUnknown, focusApplications.get());

            var distantRecipes = new java.util.ArrayList<Object>(java.util.Collections.nCopies(4096, replacementRecipe));
            distantRecipes.set(4095, recipe);
            recipes.set(distantRecipes);
            recipeVisits.set(0);
            PackSageJeiPlugin.clientTicked();
            var boundedRecipeRead = plugin.provider().query(exactGet, nextScope).toCompletableFuture().get();
            assertEquals(Evidence.Coverage.ERROR, boundedRecipeRead.coverage());
            assertTrue(boundedRecipeRead.records().isEmpty());
            assertTrue(recipeVisits.get() <= 2048);
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
