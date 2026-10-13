package dev.packsage.knowledge.jei;

import dev.packsage.knowledge.Evidence;
import mezz.jei.api.recipe.IRecipeCategoriesLookup;
import mezz.jei.api.recipe.IRecipeCatalystLookup;
import mezz.jei.api.recipe.IRecipeLookup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.api.constants.VanillaTypes;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JeiProjectionTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "jei-1", 1);

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
        var provider = new JeiProvider((scope, query) -> {
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
        assertEquals(Evidence.NavigationStatus.STALE, provider.open(firstTarget, SCOPE).status());
    }

    @Test
    void emptySnapshotsDoNotEvictARecentTargetWithinTheSameRuntime() {
        var target = new JeiProvider.Projection("mod:category", "mod:category#target", "mod:target",
                "Target", List.of(), List.of(), List.of(), true, Evidence.Coverage.COMPLETE,
                Evidence.DecodingStatus.VERIFIED, null);
        var provider = new JeiProvider((scope, query) -> {
            var text = ((Evidence.JsonString) query.arguments().values().get("text")).value();
            if (text.equals("target")) return new JeiProvider.Snapshot(scope, 4, 1, List.of(target), ignored -> { });
            return new JeiProvider.Snapshot(scope, 4, Long.parseLong(text.substring("empty-".length())), List.of(), ignored -> { });
        }, Runnable::run, () -> true);

        var targetResult = provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("target"))), 1, null, null), SCOPE)
                .toCompletableFuture().join();
        var targetHandle = targetResult.records().getFirst().targets().getFirst();
        for (var index = 2; index <= 18; index++) {
            provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                    new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("empty-" + index))), 1, null, null), SCOPE)
                    .toCompletableFuture().join();
        }

        assertEquals(Evidence.NavigationStatus.OPENED, provider.open(targetHandle, SCOPE).status());
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
    void consecutiveScanWindowsOfOneSearchRunOnDifferentClientTicks() {
        var tick = new long[1];
        var clientTasks = new java.util.ArrayDeque<Runnable>();
        var laterTick = new java.util.ArrayList<Runnable>();
        var captureTicks = new java.util.ArrayList<Long>();
        var provider = new JeiProvider((scope, query) -> {
            captureTicks.add(tick[0]);
            int next = query.cursor() == null ? 1 : Integer.parseInt(query.cursor()) + 1;
            return new JeiProvider.Snapshot(scope, 1, next, List.of(), ignored -> { }, Evidence.Coverage.COMPLETE, true,
                    Integer.toString(next));
        }, clientTasks::add, () -> true, () -> tick[0], laterTick::add);
        var service = new dev.packsage.knowledge.KnowledgeService(List.of(provider), () -> SCOPE,
                plan -> java.util.concurrent.CompletableFuture.completedFuture(new Evidence.Result(List.of(),
                        Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null)),
                (identities, scope) -> java.util.concurrent.CompletableFuture.completedFuture(new Evidence.Result(List.of(),
                        Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null)));

        var result = service.execute(new dev.packsage.model.PackSageTools.ReadRequest("search_pack",
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("nothing"))), SCOPE)).toCompletableFuture();
        while (!result.isDone() && tick[0] < 100) {
            while (!clientTasks.isEmpty()) clientTasks.poll().run();
            tick[0]++;
            clientTasks.addAll(laterTick);
            laterTick.clear();
        }

        assertTrue(result.isDone());
        assertEquals(dev.packsage.knowledge.KnowledgeService.MAX_PROVIDER_PAGES_PER_CALL, captureTicks.size());
        assertEquals(captureTicks.size(), new java.util.HashSet<>(captureTicks).size(),
                "JEI scan windows ran in the same client tick: " + captureTicks);
        assertNotNull(result.join().nextCursor());
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
            assertEquals("2048", first.nextCursor());
            PackSageJeiPlugin.clientTicked();
            var second = plugin.provider().query(new Evidence.Query(Evidence.Operation.SEARCH,
                    new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("2049"))), 1, SCOPE,
                    first.nextCursor()), SCOPE).toCompletableFuture().get();
            assertEquals(1, second.records().size());

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
    void focusedScannerPassesOutputFocusToCategoryAndRecipeLookups() throws Exception {
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
            var shownRecipes = new AtomicReference<List<?>>();
            var focusFactoryType = IJeiRuntime.class.getMethod("getJeiHelpers").getReturnType()
                    .getMethod("getFocusFactory").getReturnType();
            var focusFactory = Proxy.newProxyInstance(focusFactoryType.getClassLoader(), new Class<?>[] { focusFactoryType },
                    (proxy, method, arguments) -> {
                        if (!method.getName().equals("createFocus")) return null;
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
                        default -> null;
                    });
            var recipe = new Object();
            var recipeLookup = (IRecipeLookup<Object>) Proxy.newProxyInstance(IRecipeLookup.class.getClassLoader(),
                    new Class<?>[] { IRecipeLookup.class }, (proxy, method, arguments) -> switch (method.getName()) {
                        case "includeHidden" -> proxy;
                        case "limitFocus" -> { appliedFocus.set(arguments[0]); focusApplications.incrementAndGet(); yield proxy; }
                        case "get" -> Stream.of(recipe);
                        default -> null;
                    });
            var categoryLookup = (IRecipeCategoriesLookup) Proxy.newProxyInstance(IRecipeCategoriesLookup.class.getClassLoader(),
                    new Class<?>[] { IRecipeCategoriesLookup.class }, (proxy, method, arguments) -> switch (method.getName()) {
                        case "includeHidden", "limitTypes" -> proxy;
                        case "limitFocus" -> { appliedFocus.set(arguments[0]); focusApplications.incrementAndGet(); yield proxy; }
                        case "get" -> Stream.of(category);
                        default -> null;
                    });
            var catalystLookup = (IRecipeCatalystLookup) Proxy.newProxyInstance(IRecipeCatalystLookup.class.getClassLoader(),
                    new Class<?>[] { IRecipeCatalystLookup.class }, (proxy, method, arguments) -> switch (method.getName()) {
                        case "includeHidden" -> proxy;
                        case "get" -> Stream.empty();
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
                            var slotViewArgument = ((ParameterizedType) slotsType.getMethod("getSlotViews")
                                    .getGenericReturnType()).getActualTypeArguments()[0];
                            var slotViewType = slotViewArgument instanceof Class<?> typeClass ? typeClass
                                    : (Class<?>) ((ParameterizedType) slotViewArgument).getRawType();
                            var slotView = Proxy.newProxyInstance(slotViewType.getClassLoader(), new Class<?>[] { slotViewType },
                                    (slotProxy, slotMethod, slotArgs) -> switch (slotMethod.getName()) {
                                        case "getRole" -> RecipeIngredientRole.OUTPUT;
                                        case "getAllIngredientsList" -> List.of();
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
            assertNotNull(appliedFocus.get());
            assertEquals(RecipeIngredientRole.OUTPUT, focusArguments.get()[0]);
            assertEquals(VanillaTypes.ITEM_STACK, focusArguments.get()[1]);
            assertEquals(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("minecraft", "stone")),
                    ((ItemStack) focusArguments.get()[2]).getItem());
            assertEquals("Crafting", result.records().getFirst().title());
            assertEquals(1, result.records().getFirst().targets().size());
            assertEquals(Evidence.NavigationStatus.OPENED,
                    plugin.provider().open(result.records().getFirst().targets().getFirst(), SCOPE).status());
            assertSame(recipe, shownRecipes.get().getFirst());
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
        JeiProvider.SnapshotSource source = (scope, query) -> {
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
}
