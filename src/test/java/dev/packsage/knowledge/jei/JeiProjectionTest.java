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
    void invalidOutputItemIsRejectedBeforeProviderDispatch() {
        var registry = new AdapterRegistry();
        registry.register(new JeiProvider(new JeiProvider.Snapshot(SCOPE, 1, List.of(), ignored -> { })));

        assertEquals(Toolbox.Code.INVALID_ARGUMENTS, failureCode(registry.dispatch(recipeRequest("jei",
                "minecraft:this_item_does_not_exist"), context(registry, Map.of()))));
        assertEquals(Toolbox.Code.INVALID_ARGUMENTS, failureCode(registry.dispatch(recipeRequest("native",
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
    void pinnedRuntimeScannerAdvancesPastCapAndKeepsOpaqueNoMatchCoverage() throws Exception {
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
            var recipes = new AtomicReference<List<Object>>(java.util.stream.IntStream.range(0, 2050)
                    .mapToObj(value -> (Object) ("recipe-" + value)).toList());
            var type = new RecipeType<>(ResourceLocation.fromNamespaceAndPath("addon", "category"), Object.class);
            var category = (IRecipeCategory<?>) Proxy.newProxyInstance(IRecipeCategory.class.getClassLoader(),
                    new Class<?>[] { IRecipeCategory.class }, (proxy, method, arguments) -> switch (method.getName()) {
                        case "getRecipeType" -> type;
                        case "getTitle" -> Component.literal("Opaque");
                        default -> null;
                    });
            var recipeLookup = (IRecipeLookup<Object>) Proxy.newProxyInstance(IRecipeLookup.class.getClassLoader(),
                    new Class<?>[] { IRecipeLookup.class }, (proxy, method, arguments) -> switch (method.getName()) {
                        case "includeHidden", "limitFocus" -> proxy;
                        case "get" -> recipes.get().stream();
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
                        case "get" -> Stream.of(category);
                        default -> null;
                    });
            var manager = (IRecipeManager) Proxy.newProxyInstance(IRecipeManager.class.getClassLoader(),
                    new Class<?>[] { IRecipeManager.class }, (proxy, method, arguments) -> switch (method.getName()) {
                        case "createRecipeCategoryLookup" -> categoryLookup;
                        case "createRecipeLookup" -> recipeLookup;
                        case "createRecipeCatalystLookup" -> catalystLookup;
                        case "createRecipeLayoutDrawable" -> Optional.empty();
                        default -> null;
                    });
            var runtime = (IJeiRuntime) Proxy.newProxyInstance(IJeiRuntime.class.getClassLoader(),
                    new Class<?>[] { IJeiRuntime.class }, (proxy, method, arguments) ->
                    method.getName().equals("getRecipeManager") ? manager : null);
            var plugin = new PackSageJeiPlugin();
            plugin.onRuntimeAvailable(runtime);

            var first = plugin.provider().query(new Evidence.Query(Evidence.Operation.SEARCH,
                    new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("2049"))), 1, null, null), SCOPE)
                    .toCompletableFuture().get();
            assertTrue(first.records().isEmpty());
            assertEquals(Evidence.Coverage.PARTIAL, first.coverage());
            assertNotNull(first.nextCursor());
            PackSageJeiPlugin.clientTicked();
            var second = plugin.provider().query(new Evidence.Query(Evidence.Operation.SEARCH,
                    new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("2049"))), 1, SCOPE,
                    first.nextCursor()), SCOPE).toCompletableFuture().get();
            assertEquals(Evidence.Coverage.ERROR, second.coverage(),
                    "a cursor beyond this call's bounded seek window must be rejected, not retried as a fresh scan");

            recipes.set(List.of("first", "second"));
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
            var typedIngredient = Proxy.newProxyInstance(ITypedIngredient.class.getClassLoader(),
                    new Class<?>[] { ITypedIngredient.class }, (proxy, method, arguments) -> switch (method.getName()) {
                        case "getType" -> VanillaTypes.ITEM_STACK;
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
                            var slotView = Proxy.newProxyInstance(slotViewType.getClassLoader(), new Class<?>[] { slotViewType },
                                    (slotProxy, slotMethod, slotArgs) -> switch (slotMethod.getName()) {
                                        case "getRole" -> RecipeIngredientRole.OUTPUT;
                                        case "getAllIngredientsList" -> List.of(typedIngredient);
                                        case "getSlotName" -> java.util.Optional.of("result");
                                        default -> null;
                                    });
                            var slots = Proxy.newProxyInstance(slotsType.getClassLoader(), new Class<?>[] { slotsType },
                                    (slotsProxy, slotsMethod, slotsArgs) -> slotsMethod.getName().equals("getSlotViews")
                                            ? List.of(slotView) : null);
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
                                                    ? Stream.empty() : recipes.get().stream();
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
                                                    ? Stream.empty() : Stream.of(currentCategory.get());
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
