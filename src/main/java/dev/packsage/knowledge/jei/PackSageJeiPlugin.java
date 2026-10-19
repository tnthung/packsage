package dev.packsage.knowledge.jei;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.knowledge.Toolbox;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.vanilla.IJeiIngredientInfoRecipe;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

@JeiPlugin
public final class PackSageJeiPlugin implements IModPlugin {
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final AtomicLong RUNTIME_GENERATION = new AtomicLong();
    private static final AtomicLong CLIENT_TICKS = new AtomicLong();
    private static boolean tickListenerRegistered;
    public static volatile KnowledgeProvider activeProvider;
    private final Supplier<Collection<RecipeHolder<?>>> nativeRecipes;
    private volatile JeiProvider provider;
    private volatile RuntimeState runtimeState;

    public PackSageJeiPlugin() {
        this(() -> {
            var client = Minecraft.getInstance();
            return client.level == null ? List.of() : client.level.getRecipeManager().getRecipes();
        });
    }

    PackSageJeiPlugin(Supplier<Collection<RecipeHolder<?>>> nativeRecipes) {
        this.nativeRecipes = nativeRecipes;
    }

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath("packsage", "jei");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        var oldState = runtimeState;
        if (oldState != null) oldState.unavailable();
        var oldProvider = provider;
        if (oldProvider != null) oldProvider.unavailable();
        var state = new RuntimeState(runtime, GENERATION.incrementAndGet(), nativeRecipes);
        if (!tickListenerRegistered) {
            tickListenerRegistered = true;
            NeoForge.EVENT_BUS.addListener((ClientTickEvent.Pre ignored) -> clientTicked());
        }
        var next = new JeiProvider(state::snapshot, state.clientExecutor(), state::owningThread, CLIENT_TICKS::get,
                CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS, state.clientExecutor()));
        runtimeState = state;
        provider = next;
        activeProvider = next;
        RUNTIME_GENERATION.incrementAndGet();
    }

    @Override
    public void onRuntimeUnavailable() {
        var state = runtimeState;
        if (state != null) state.unavailable();
        var current = provider;
        if (current != null) current.unavailable();
        if (activeProvider == current) activeProvider = null;
        runtimeState = null;
        provider = null;
        RUNTIME_GENERATION.incrementAndGet();
    }

    public static long runtimeGeneration() { return RUNTIME_GENERATION.get(); }

    static void clientTicked() { CLIENT_TICKS.incrementAndGet(); }

    public JeiProvider provider() {
        var current = provider;
        if (current != null) return current;
        var unavailable = new JeiProvider(new JeiProvider.Snapshot(
                new Evidence.Scope("unknown", "unknown", "unknown", "unavailable", 0),
                0, 0, List.of(), ignored -> { }, Evidence.Coverage.UNAVAILABLE, true, null));
        unavailable.unavailable();
        return unavailable;
    }

    private static final class RuntimeState {
        private static final int MAX_CATEGORIES = 256;
        private static final int MAX_SCANNED_RECIPES = 2048;
        private static final int MAX_NAVIGATION_RECIPES = 65_536;
        private static final int MAX_CURSOR_SEEK = 65_536;
        private static final int MAX_PAGE = 64;
        private static final int MAX_NAVIGATION_REGISTRATIONS = 512;
        private static final int CURSOR_RADIX = 1_000_000;

        private final IJeiRuntime runtime;
        private final long generation;
        private final Supplier<Collection<RecipeHolder<?>>> nativeRecipes;
        private final AtomicLong snapshotVersions = new AtomicLong();
        private final LinkedHashMap<String, RegisteredRecipe> navigationRegistrations = new LinkedHashMap<>();
        private volatile boolean active = true;

        private RuntimeState(IJeiRuntime runtime, long generation,
                             Supplier<Collection<RecipeHolder<?>>> nativeRecipes) {
            this.runtime = runtime;
            this.generation = generation;
            this.nativeRecipes = nativeRecipes;
        }

        private Executor clientExecutor() {
            return Minecraft.getInstance()::execute;
        }

        private boolean owningThread() {
            return Minecraft.getInstance().isSameThread();
        }

        private void unavailable() {
            active = false;
            navigationRegistrations.clear();
        }

        private JeiProvider.Snapshot snapshot(Evidence.Scope scope, Evidence.Query query, Toolbox.DocumentBudget budget) {
            if (!active) return new JeiProvider.Snapshot(scope, generation, snapshotVersions.incrementAndGet(), List.of(),
                    ignored -> { }, Evidence.Coverage.UNAVAILABLE, true, null);
            if (!owningThread()) throw new IllegalStateException("JEI snapshot must be captured on the client thread");
            var snapshotVersion = snapshotVersions.incrementAndGet();
            var manager = runtime.getRecipeManager();
            class NativeIdentityIndex {
                private final IdentityHashMap<Object, ResourceLocation> identities = new IdentityHashMap<>();
                private final IdentityHashMap<Object, Boolean> ambiguous = new IdentityHashMap<>();
                private boolean initialized;
                private boolean unavailable;

                private ResourceLocation id(Object recipe) {
                    if (!(recipe instanceof Recipe<?>)) return null;
                    if (!initialized) build();
                    return unavailable || ambiguous.containsKey(recipe) ? null : identities.get(recipe);
                }

                private void build() {
                    initialized = true;
                    var complete = false;
                    try {
                        var holders = nativeRecipes.get();
                        if (holders == null) {
                            return;
                        }
                        for (var holder : holders) {
                            if (!budget.callback("jei-native-identity", MAX_NAVIGATION_RECIPES)) {
                                return;
                            }
                            var recipe = holder.value();
                            var id = holder.id();
                            if (ambiguous.containsKey(recipe)) continue;
                            var previous = identities.putIfAbsent(recipe, id);
                            if (previous != null && !previous.equals(id)) {
                                identities.remove(recipe);
                                ambiguous.put(recipe, true);
                            }
                        }
                        complete = true;
                    } catch (RuntimeException failure) {
                        unavailable = true;
                    } finally {
                        if (!complete) {
                            identities.clear();
                            ambiguous.clear();
                            unavailable = true;
                        }
                    }
                }
            }
            Function<Object, ResourceLocation> nativeRecipeId = new NativeIdentityIndex()::id;
            var outputItem = stringArgument(query.arguments(), "outputItem");
            var id = stringArgument(query.arguments(), "id");
            var retained = query.operation() == Evidence.Operation.GET && id != null
                    ? navigationRegistrations.get(id) : null;
            var resolvedNavigation = false;
            if (query.operation() == Evidence.Operation.GET && retained == null && id != null
                    && (id.startsWith("recipe-v1|") || id.startsWith("category-v1|") || id.startsWith("jei/"))) {
                var identity = navigationIdentity(id, manager, budget);
                retained = identity == null ? null : resolveNavigation(identity, manager, scope, budget,
                        nativeRecipeId);
                if (retained == null) {
                    return new JeiProvider.Snapshot(scope, generation, snapshotVersion, List.of(), ignored -> { },
                            Evidence.Coverage.UNAVAILABLE, true, null);
                }
                var categoryId = categoryId(retained.category(), 0);
                id = retained.recipe() == null ? categoryHandle(snapshotVersion, categoryId)
                        : sourceHandle(generation, snapshotVersion, categoryId,
                        canonical(retained.category(), retained.recipe(), nativeRecipeId).value(), retained.recipe(), 0);
                retained = new RegisteredRecipe(retained.category(), retained.recipe(), scope, null);
                navigationRegistrations.put(id, retained);
                while (navigationRegistrations.size() > MAX_NAVIGATION_REGISTRATIONS) {
                    navigationRegistrations.remove(navigationRegistrations.keySet().iterator().next());
                }
                resolvedNavigation = true;
            }
            if (retained != null) {
                if (!retained.scope().worldSession().equals(scope.worldSession())
                        || !retained.scope().resourcesLocale().equals(scope.resourcesLocale())
                        || !retained.scope().playerTeam().equals(scope.playerTeam())
                        || !retained.scope().providerRuntime().equals(scope.providerRuntime())
                        || outputItem != null && !outputItem.equals(retained.outputItem())) {
                    return new JeiProvider.Snapshot(scope, generation, snapshotVersion, List.of(), ignored -> { },
                            Evidence.Coverage.UNAVAILABLE, true, null);
                }
                outputItem = retained.outputItem();
            }
            var matchingOutputItem = outputItem;
            List<IFocus<?>> focuses = List.of();
            ResourceLocation matchingOutputItemId = null;
            if (outputItem != null) {
                matchingOutputItemId = ResourceLocation.tryParse(outputItem);
                if (matchingOutputItemId == null || !BuiltInRegistries.ITEM.containsKey(matchingOutputItemId)) {
                    throw new IllegalArgumentException("unknown or invalid outputItem " + outputItem);
                }
            }
            if (retained != null) {
                var category = retained.category();
                var type = category.getRecipeType();
                if (manager.getRecipeCategory(type) != category) {
                    return new JeiProvider.Snapshot(scope, generation, snapshotVersion, List.of(), ignored -> { },
                            Evidence.Coverage.UNAVAILABLE, true, null);
                }
                var categoryRegistered = resolvedNavigation;
                if (!resolvedNavigation) {
                    try (var categories = manager.createRecipeCategoryLookup().limitTypes(List.of(type))
                            .limitFocus(focuses).get()) {
                        var iterator = categories.iterator();
                        for (int scanned = 0; ; scanned++) {
                            if (scanned >= MAX_CATEGORIES || !budget.document()) throw new Toolbox.Failure(
                                    Toolbox.Code.EXTRACTION_FAILED, "JEI category membership exceeded the read budget");
                            if (!iterator.hasNext()) break;
                            if (iterator.next() == category) { categoryRegistered = true; break; }
                        }
                    }
                }
                if (!categoryRegistered) {
                    return new JeiProvider.Snapshot(scope, generation, snapshotVersion, List.of(), ignored -> { },
                            Evidence.Coverage.UNAVAILABLE, true, null);
                }
                var categoryId = categoryId(category, 0);
                JeiProvider.Projection projection;
                if (retained.recipe() == null) {
                    projection = new JeiProvider.Projection(categoryId, id, null, title(category, categoryId),
                            List.of(), List.of(), List.of(), true, true, Evidence.Coverage.OPAQUE,
                            Evidence.DecodingStatus.OPAQUE, "JEI category has no detached recipe projection");
                } else {
                    var recipeRegistered = resolvedNavigation;
                    if (!resolvedNavigation) {
                        try (var recipes = manager.createRecipeLookup(type).limitFocus(focuses).get()) {
                            var iterator = recipes.iterator();
                            for (int scanned = 0; ; scanned++) {
                                if (scanned >= MAX_NAVIGATION_RECIPES
                                        || !budget.callback(JeiProvider.ID, MAX_NAVIGATION_RECIPES)) throw new Toolbox.Failure(
                                        Toolbox.Code.EXTRACTION_FAILED, "JEI recipe membership exceeded the read budget");
                                if (!iterator.hasNext()) break;
                                if (iterator.next() == retained.recipe()) { recipeRegistered = true; break; }
                            }
                        }
                    }
                    if (!recipeRegistered) {
                        return new JeiProvider.Snapshot(scope, generation, snapshotVersion, List.of(), ignored -> { },
                                Evidence.Coverage.UNAVAILABLE, true, null);
                    }
                    var canonical = canonical(category, retained.recipe(), nativeRecipeId);
                    var catalystCapture = catalysts(runtime, manager, type, budget);
                    projection = project(runtime, manager, category, retained.recipe(), categoryId, id,
                            canonical.value(), title(category, categoryId), knownCategory(categoryId), canonical.failed(),
                            catalystCapture, null, budget);
                    if (!projection.visible() || !projection.visibilityKnown()) {
                        return new JeiProvider.Snapshot(scope, generation, snapshotVersion, List.of(), ignored -> { },
                                Evidence.Coverage.UNAVAILABLE, true, null);
                    }
                    if (outputItem != null && !projection.slots().stream()
                            .filter(slot -> slot.role() == JeiProvider.RecipeRole.OUTPUT)
                            .flatMap(slot -> slot.alternatives().stream())
                            .anyMatch(ingredient -> ingredient.type().equals("item")
                                    && ingredient.comparisonIdentity().split("\\|componentsHash=", 2)[0]
                                    .equals(matchingOutputItem))) {
                        var coverage = projection.coverage() == Evidence.Coverage.COMPLETE
                                ? Evidence.Coverage.UNAVAILABLE : projection.coverage();
                        return new JeiProvider.Snapshot(scope, generation, snapshotVersion, List.of(), ignored -> { },
                                coverage, true, null);
                    }
                }
                var registeredHandle = id;
                var registeredRecipe = retained;
                return new JeiProvider.Snapshot(scope, generation, snapshotVersion, List.of(projection), handle -> {
                    if (!active) throw new IllegalStateException("JEI runtime unavailable");
                    if (!registeredHandle.equals(handle)) throw new IllegalArgumentException("unknown JEI projection " + handle);
                    showRecipe(runtime, registeredRecipe);
                }, projection.coverage(), true, null);
            }
            var projections = new ArrayList<JeiProvider.Projection>();
            var navigation = new LinkedHashMap<String, RegisteredRecipe>();
            var queryCursorBytes = query.cursor() == null ? 0L : (long) query.cursor().length() * 2;
            if (queryCursorBytes > budget.remainingBytes()
                    || query.cursor() != null && !budget.bytes((int) queryCursorBytes)) {
                return new JeiProvider.Snapshot(scope, generation, snapshotVersion, List.of(
                        JeiProvider.Projection.error("unknown:category", "jei/" + generation + "/cursor", null,
                                "JEI cursor", "JEI cursor exceeded the shared read budget")), ignored -> { },
                        Evidence.Coverage.ERROR, true, null);
            }
            var queryRemainingBytes = budget.remainingBytes();
            var cursorParts = query.cursor() == null ? new String[0] : query.cursor().split("\\|", -1);
            var cursor = 0;
            var retryCursor = false;
            try {
                if (query.cursor() != null && cursorParts.length != 2 && cursorParts.length != 3
                        || query.cursor() != null && cursorParts.length == 3 && !cursorParts[2].equals("retry")
                        || query.cursor() != null && Long.parseLong(cursorParts[0]) != generation) {
                    throw new NumberFormatException();
                }
                if (query.cursor() != null) {
                    cursor = Integer.parseInt(cursorParts[1]);
                    retryCursor = cursorParts.length == 3;
                }
                if (cursor < 0) throw new NumberFormatException();
            } catch (NumberFormatException invalidCursor) {
                return new JeiProvider.Snapshot(scope, generation, snapshotVersion, List.of(
                        JeiProvider.Projection.error("unknown:category", "jei/" + generation + "/cursor", null,
                                "JEI cursor", "JEI cursor no longer identifies this runtime position")),
                        ignored -> { }, Evidence.Coverage.ERROR, true, null);
            }
            var cursorCategory = cursor / CURSOR_RADIX;
            var cursorRecipe = cursor % CURSOR_RADIX;
            if (query.cursor() != null && (cursorCategory >= MAX_CATEGORIES || cursorRecipe >= MAX_CURSOR_SEEK)) {
                return new JeiProvider.Snapshot(scope, generation, snapshotVersion, List.of(
                        JeiProvider.Projection.error("unknown:category", "jei/" + generation + "/cursor", null,
                                "JEI cursor", "JEI cursor cannot be resumed within the current source bounds")),
                        ignored -> { }, Evidence.Coverage.ERROR, true, null);
            }
            var pageLimit = Math.min(query.limit(), MAX_PAGE);
            var text = stringArgument(query.arguments(), "text");
            text = text == null ? "" : text;
            var scanned = 0;
            var categoryCount = 0;
            var categoriesVisited = 0;
            var hasMore = false;
            var nextCategory = 0;
            var nextRecipe = 0;
            var nextRetryCandidate = false;
            var partial = false;
            var opaque = false;
            var error = false;
            var categoryLookupFailed = false;
            var cursorInvalid = false;
            var cursorCategoryFound = false;
            var terminalPartial = false;
            try (var categories = manager.createRecipeCategoryLookup().includeHidden().limitFocus(focuses).get()) {
                var categoryIterator = categories.iterator();
                while (categoryIterator.hasNext()) {
                    var categoryIndex = categoryCount;
                    var seekingCategory = categoryIndex < cursorCategory;
                    var categoryStartRemainingBytes = budget.remainingBytes();
                    if (categoriesVisited >= MAX_CATEGORIES) {
                        partial = true;
                        hasMore = true;
                        nextCategory = categoryIndex;
                        nextRecipe = 0;
                        break;
                    }
                    if (!seekingCategory && (!budget.value() || !budget.bytes(512))) {
                        partial = true;
                        hasMore = true;
                        nextCategory = categoryIndex;
                        nextRecipe = 0;
                        break;
                    }
                    var category = categoryIterator.next();
                    categoryCount++;
                    categoriesVisited++;
                    if (categoryIndex == cursorCategory) cursorCategoryFound = true;
                    if (seekingCategory) continue;
                    var categoryId = categoryId(category, categoryCount);
                    var categoryKnown = knownCategory(categoryId);
                    opaque |= !categoryKnown;
                    if (!budget.value() || !budget.bytes(1024)) {
                        partial = true;
                        hasMore = true;
                        nextCategory = categoryIndex;
                        nextRecipe = 0;
                        break;
                    }
                    var title = title(category, categoryId);
                    var categoryCursor = categoryIndex == cursorCategory ? cursorRecipe : 0;
                    RecipeType<?> type;
                    try {
                        type = category.getRecipeType();
                    } catch (Throwable failure) {
                        var handle = categoryHandle(snapshotVersion, categoryId);
                        var recordOrdinal = 1;
                        if (categoryCursor < recordOrdinal && matches(categoryId, title, null, handle,
                                query.operation(), id, text)) {
                            if (projections.size() >= pageLimit) {
                                hasMore = true;
                                nextCategory = categoryIndex;
                                nextRecipe = categoryCursor;
                            } else {
                                projections.add(JeiProvider.Projection.error(categoryId, handle, null, title,
                                        failure.toString()));
                            }
                        }
                        error = true;
                        if (!hasMore && projections.size() >= pageLimit && categoryIterator.hasNext()) {
                            hasMore = true;
                            nextCategory = categoryIndex + 1;
                            nextRecipe = 0;
                        }
                        if (hasMore) break;
                        continue;
                    }
                    var catalystCapture = categoryKnown ? catalysts(runtime, manager, type, budget)
                            : new CatalystCapture(List.of(), false, false, false);
                    partial |= catalystCapture.partial();
                    if (budget.exhausted()) {
                        hasMore = true;
                        nextCategory = categoryIndex;
                        nextRecipe = categoryCursor;
                        break;
                    }
                    var categoryPreludeBytes = categoryStartRemainingBytes - budget.remainingBytes();
                    try (var recipes = manager.createRecipeLookup(type).includeHidden().limitFocus(focuses).get()) {
                        var recipeIterator = recipes.iterator();
                        var recipeCount = 0;
                        var recipeOrdinal = 0;
                        while (recipeOrdinal < MAX_CURSOR_SEEK && recipeIterator.hasNext()) {
                            var seekingRecipe = recipeOrdinal < categoryCursor;
                            if (projections.size() >= pageLimit || scanned >= MAX_SCANNED_RECIPES) {
                                partial |= scanned >= MAX_SCANNED_RECIPES;
                                hasMore = true;
                                nextCategory = categoryIndex;
                                nextRecipe = recipeOrdinal;
                                break;
                            }
                            if (!seekingRecipe && !budget.value()) {
                                partial = true;
                                hasMore = true;
                                nextCategory = categoryIndex;
                                nextRecipe = recipeOrdinal;
                                break;
                            }
                            var recipe = recipeIterator.next();
                            recipeOrdinal++;
                            recipeCount++;
                            if (seekingRecipe) continue;
                            scanned++;
                            var canonicalCapture = new Canonical(null, true);
                            if (!budget.value()) {
                                partial = true;
                                hasMore = true;
                                nextCategory = categoryIndex;
                                nextRecipe = recipeOrdinal - 1;
                                break;
                            }
                            canonicalCapture = canonical(category, recipe, nativeRecipeId);
                            if (canonicalCapture.value() != null) {
                                var canonicalBytes = canonicalCapture.value().length() * 2L;
                                var retryCursorBytes = (Long.toString(generation).length() + 1L
                                        + Integer.toString(categoryIndex * CURSOR_RADIX
                                        + recipeOrdinal - 1).length()
                                        + (retryCursor && categoryIndex == cursorCategory
                                        && recipeOrdinal - 1 == cursorRecipe ? "|retry".length() : 0)) * 2;
                                var freshCategoryBytes = queryRemainingBytes + queryCursorBytes
                                        - retryCursorBytes - categoryPreludeBytes;
                                if (canonicalBytes > budget.remainingBytes()) {
                                    if (canonicalBytes > freshCategoryBytes) {
                                        partial = true;
                                        terminalPartial = true;
                                        break;
                                    }
                                    partial = true;
                                    hasMore = true;
                                    nextCategory = categoryIndex;
                                    nextRecipe = recipeOrdinal - 1;
                                    break;
                                }
                                if (!budget.bytes((int) canonicalBytes)) {
                                    if (canonicalBytes > freshCategoryBytes) {
                                        partial = true;
                                        terminalPartial = true;
                                    } else {
                                        partial = true;
                                        hasMore = true;
                                        nextCategory = categoryIndex;
                                        nextRecipe = recipeOrdinal - 1;
                                    }
                                    break;
                                }
                            }
                            if (canonicalCapture.failed()) partial = true;
                            var handle = sourceHandle(generation, snapshotVersion, categoryId, canonicalCapture.value(), recipe, recipeOrdinal);
                            if (query.operation() == Evidence.Operation.GET
                                    && !matches(categoryId, title, canonicalCapture.value(), handle,
                                    query.operation(), id, text)) continue;
                            JeiProvider.Projection projection;
                            try {
                                if (outputItem != null && categoryKnown) {
                                    var layout = manager.createRecipeLayoutDrawable(
                                            (IRecipeCategory<Object>) category, recipe, new EmptyFocusGroup())
                                            .orElse(null);
                                    if (layout == null) {
                                        projection = JeiProvider.Projection.error(categoryId, handle,
                                                canonicalCapture.value(), title,
                                                "JEI did not provide a detached recipe layout");
                                    } else {
                                        var observedSlotsView = layout.getRecipeSlotsView();
                                        var outputMatches = false;
                                        var slotViews = observedSlotsView.getSlotViews().iterator();
                                        while (slotViews.hasNext()) {
                                            if (!budget.value()) break;
                                            var slotView = slotViews.next();
                                            if (slotView.getRole() != RecipeIngredientRole.OUTPUT) continue;
                                            var ingredientValues = slotView.getAllIngredientsList().iterator();
                                            while (ingredientValues.hasNext()) {
                                                if (!budget.value()) break;
                                                if (ingredientValues.next().getIngredient() instanceof ItemStack stack
                                                        && BuiltInRegistries.ITEM.getKey(stack.getItem())
                                                        .equals(matchingOutputItemId)) {
                                                    outputMatches = true;
                                                    break;
                                                }
                                            }
                                            if (outputMatches || budget.exhausted()) break;
                                        }
                                        if (!outputMatches && !budget.exhausted()) continue;
                                        projection = project(runtime, manager, category, recipe, categoryId, handle,
                                                canonicalCapture.value(), title, true, canonicalCapture.failed(),
                                                catalystCapture, observedSlotsView, budget);
                                    }
                                } else if (categoryKnown) {
                                    projection = project(runtime, manager, category, recipe, categoryId, handle,
                                            canonicalCapture.value(), title, true, canonicalCapture.failed(),
                                            catalystCapture, null, budget);
                                } else {
                                    projection = new JeiProvider.Projection(categoryId, handle, canonicalCapture.value(), title,
                                            List.of(), List.of(), List.of(), false, false, Evidence.Coverage.OPAQUE,
                                            Evidence.DecodingStatus.OPAQUE, "JEI category semantics are not decoded by PackSage");
                                }
                            } catch (Throwable failure) {
                                projection = JeiProvider.Projection.error(categoryId, handle, canonicalCapture.value(),
                                        title, failure.toString());
                            }
                            error |= projection.coverage() == Evidence.Coverage.ERROR;
                            opaque |= projection.coverage() == Evidence.Coverage.OPAQUE;
                            partial |= projection.coverage() == Evidence.Coverage.PARTIAL;
                            var matchesQuery = (outputItem == null || projection.slots().stream()
                                    .filter(slot -> slot.role() == JeiProvider.RecipeRole.OUTPUT)
                                    .flatMap(slot -> slot.alternatives().stream())
                                    .anyMatch(ingredient -> ingredient.type().equals("item")
                                            && ingredient.comparisonIdentity().split("\\|componentsHash=", 2)[0]
                                            .equals(matchingOutputItem))) && JeiProvider.matches(projection, text);
                            if (budget.exhausted() && !matchesQuery) {
                                partial = true;
                                if (retryCursor && categoryIndex == cursorCategory
                                        && recipeOrdinal - 1 == cursorRecipe) {
                                    terminalPartial = true;
                                    if (projections.size() < pageLimit) projections.add(new JeiProvider.Projection(
                                            projection.categoryId(), projection.viewerHandle(), projection.canonicalRecipeId(),
                                            projection.title(), projection.slots(), projection.catalysts(), projection.information(),
                                            false, false, Evidence.Coverage.PARTIAL, Evidence.DecodingStatus.PARTIAL,
                                            projection.error() == null ? "JEI candidate exceeded the shared read budget; search remains incomplete"
                                                    : projection.error()));
                                } else {
                                    hasMore = true;
                                    nextCategory = categoryIndex;
                                    nextRecipe = recipeOrdinal - 1;
                                    nextRetryCandidate = true;
                                }
                                break;
                            }
                            if (!matchesQuery) continue;
                            projections.add(projection);
                            if (projection.coverage() != Evidence.Coverage.ERROR) {
                                var registration = new RegisteredRecipe(category, recipe, scope, outputItem);
                                navigation.put(handle, registration);
                                navigationRegistrations.put(handle, registration);
                                while (navigationRegistrations.size() > MAX_NAVIGATION_REGISTRATIONS) {
                                    navigationRegistrations.remove(navigationRegistrations.keySet().iterator().next());
                                }
                            }
                            if (budget.exhausted()) {
                                partial = true;
                                hasMore = recipeIterator.hasNext() || categoryIterator.hasNext();
                                nextCategory = recipeIterator.hasNext() ? categoryIndex : categoryIndex + 1;
                                nextRecipe = recipeIterator.hasNext() ? recipeOrdinal : 0;
                                break;
                            }
                        }
                        if (recipeOrdinal >= MAX_CURSOR_SEEK) {
                            partial = true;
                            terminalPartial = true;
                        }
                        if (categoryIndex == cursorCategory && categoryCursor > recipeOrdinal && !hasMore) cursorInvalid = true;
                        if (hasMore) break;
                        if (recipeCount == 0 && !categoryKnown && categoryCursor == 0 && matches(categoryId, title, null,
                                categoryHandle(snapshotVersion, categoryId), query.operation(), id, text)) {
                            var handle = categoryHandle(snapshotVersion, categoryId);
                            if (projections.size() >= pageLimit) {
                                hasMore = true;
                                nextCategory = categoryIndex;
                                nextRecipe = 0;
                            } else {
                                projections.add(new JeiProvider.Projection(categoryId, handle, null, title, List.of(),
                                        List.of(), List.of(), true, true, Evidence.Coverage.OPAQUE,
                                        Evidence.DecodingStatus.OPAQUE, "JEI category has no detached recipe projection"));
                                var registration = new RegisteredRecipe(category, null, scope, outputItem);
                                navigation.put(handle, registration);
                                navigationRegistrations.put(handle, registration);
                                while (navigationRegistrations.size() > MAX_NAVIGATION_REGISTRATIONS) {
                                    navigationRegistrations.remove(navigationRegistrations.keySet().iterator().next());
                                }
                            }
                        }
                    } catch (Throwable failure) {
                        var handle = categoryHandle(snapshotVersion, categoryId);
                        var projection = JeiProvider.Projection.error(categoryId, handle, null, title, failure.toString());
                        if (categoryCursor == 0 && matches(categoryId, title, null, handle, query.operation(), id, text)
                                && projections.size() < pageLimit) {
                            projections.add(projection);
                        } else if (categoryCursor == 0 && matches(categoryId, title, null, handle, query.operation(), id, text)) {
                            hasMore = true;
                            nextCategory = categoryIndex;
                            nextRecipe = 0;
                        }
                        error = true;
                    }
                    if (terminalPartial) break;
                    if (!hasMore && (projections.size() >= pageLimit || scanned >= MAX_SCANNED_RECIPES)
                            && categoryIterator.hasNext()) {
                        partial |= scanned >= MAX_SCANNED_RECIPES;
                        hasMore = true;
                        nextCategory = categoryIndex + 1;
                        nextRecipe = 0;
                    }
                    if (hasMore) break;
                }
                if (query.cursor() != null && !cursorCategoryFound && !hasMore) cursorInvalid = true;
            } catch (Throwable failure) {
                categoryLookupFailed = true;
                var handle = "jei/" + generation + "/" + snapshotVersion + "/category-lookup";
                projections.add(JeiProvider.Projection.error("unknown:category", handle, null,
                        "JEI category lookup", failure.toString()));
                error = true;
            }
            if (hasMore && budget.exhausted()) {
                partial = true;
                if (retryCursor && nextCategory * CURSOR_RADIX + nextRecipe == cursor) {
                    hasMore = false;
                    cursorCategoryFound = true;
                    projections.add(new JeiProvider.Projection("unknown:category",
                            "jei/" + generation + "/" + snapshotVersion + "/budget", null, "JEI budget",
                            List.of(), List.of(), List.of(), false, false, Evidence.Coverage.PARTIAL,
                            Evidence.DecodingStatus.PARTIAL,
                            "JEI shared read budget ended again before this source position could be decoded"));
                } else {
                    nextRetryCandidate = true;
                }
            }
            if (hasMore && (nextCategory >= MAX_CATEGORIES || nextRecipe >= MAX_CURSOR_SEEK)) {
                hasMore = false;
                partial = true;
            }
            if (cursorInvalid) return new JeiProvider.Snapshot(scope, generation, snapshotVersion, List.of(
                    JeiProvider.Projection.error("unknown:category", "jei/" + generation + "/cursor", null,
                            "JEI cursor", "JEI cursor no longer identifies this runtime position")),
                    ignored -> { }, Evidence.Coverage.ERROR, true, null);
            if (categoryLookupFailed || scanned >= MAX_SCANNED_RECIPES) partial = true;
            var coverage = error ? Evidence.Coverage.ERROR : partial ? Evidence.Coverage.PARTIAL
                    : opaque ? Evidence.Coverage.OPAQUE : Evidence.Coverage.COMPLETE;
            var next = hasMore ? generation + "|" + (nextCategory * CURSOR_RADIX + nextRecipe)
                    + (nextRetryCandidate ? "|retry" : "") : null;
            return new JeiProvider.Snapshot(scope, generation, snapshotVersion, projections, handle -> {
                var registered = navigation.get(handle);
                if (!active) throw new IllegalStateException("JEI runtime unavailable");
                if (registered == null) throw new IllegalArgumentException("unknown JEI projection " + handle);
                showRecipe(runtime, registered);
            }, coverage, true, next, hasMore ? "JEI scan pending category " + nextCategory
                    + ", recipe " + nextRecipe + (nextRetryCandidate ? " (retry required)" : "") : null);
        }

        private static JeiProvider.Projection project(IJeiRuntime runtime, IRecipeManager manager,
                                                            IRecipeCategory<?> category, Object recipe,
                                                            String categoryId, String handle, String canonical,
                                                            String title, boolean knownCategory,
                                                            boolean canonicalFailed,
                                                            CatalystCapture catalystCapture,
                                                            IRecipeSlotsView observedSlotsView,
                                                            Toolbox.DocumentBudget budget) {
            if (!budget.value() || !budget.bytes(2048)) return new JeiProvider.Projection(categoryId, handle,
                    canonical, title, List.of(), catalystCapture.ingredients(), List.of(), false, false,
                    Evidence.Coverage.PARTIAL, Evidence.DecodingStatus.PARTIAL,
                    "JEI shared read budget ended before recipe layout extraction");
            var slotsView = observedSlotsView;
            if (slotsView == null) {
                var layout = manager.createRecipeLayoutDrawable((IRecipeCategory<Object>) category, recipe,
                        new EmptyFocusGroup()).orElse(null);
                if (layout == null) return JeiProvider.Projection.error(categoryId, handle, canonical, title,
                        "JEI did not provide a detached recipe layout");
                slotsView = layout.getRecipeSlotsView();
            }
            var capturedSlots = new ArrayList<JeiProvider.Slot>();
            var partial = canonicalFailed || catalystCapture.partial();
            var visible = catalystCapture.visible();
            var visibilityKnown = catalystCapture.visibilityKnown();
            var slotIndex = 0;
            var slots = slotsView.getSlotViews().iterator();
            while (slots.hasNext()) {
                if (!budget.value() || !budget.bytes(256)) return new JeiProvider.Projection(categoryId, handle,
                        canonical, title, capturedSlots, catalystCapture.ingredients(), List.of(), false, false,
                        Evidence.Coverage.PARTIAL, Evidence.DecodingStatus.PARTIAL,
                        "JEI shared read budget ended at a recipe slot");
                var slotView = slots.next();
                var groupId = slotView.getSlotName().orElse("slot-" + slotIndex++);
                if (groupId.length() > budget.remainingBytes()) {
                    budget.bytes(budget.remainingBytes() + 1);
                    return new JeiProvider.Projection(categoryId, handle, canonical, title, capturedSlots,
                            catalystCapture.ingredients(), List.of(), false, false, Evidence.Coverage.PARTIAL,
                            Evidence.DecodingStatus.PARTIAL, "JEI shared read budget ended at an oversized slot name");
                }
                var groupIdBytes = groupId.getBytes(StandardCharsets.UTF_8).length;
                var escapedGroupIdBytes = 0;
                for (var index = 0; index < groupId.length(); index++) {
                    var character = groupId.charAt(index);
                    if (character == '"' || character == '\\' || character == '\b' || character == '\t'
                            || character == '\n' || character == '\f' || character == '\r') escapedGroupIdBytes++;
                    else if (character < 0x20 || character == '<' || character == '>' || character == '&'
                            || character == '=' || character == '\'' || character == '\u2028' || character == '\u2029') {
                        escapedGroupIdBytes += 5;
                    }
                }
                var groupIdEvidenceBytes = 2L * (groupIdBytes + escapedGroupIdBytes);
                if (groupIdEvidenceBytes > budget.remainingBytes()) {
                    budget.bytes(budget.remainingBytes() + 1);
                    return new JeiProvider.Projection(categoryId, handle, canonical, title, capturedSlots,
                            catalystCapture.ingredients(), List.of(), false, false, Evidence.Coverage.PARTIAL,
                            Evidence.DecodingStatus.PARTIAL, "JEI shared read budget ended at an oversized slot name");
                }
                if (!budget.bytes((int) groupIdEvidenceBytes)) {
                    return new JeiProvider.Projection(categoryId, handle, canonical, title, capturedSlots,
                            catalystCapture.ingredients(), List.of(), false, false, Evidence.Coverage.PARTIAL,
                            Evidence.DecodingStatus.PARTIAL, "JEI shared read budget ended at an oversized slot name");
                }
                var ingredients = new ArrayList<JeiProvider.Ingredient>();
                var ingredientValues = slotView.getAllIngredientsList().iterator();
                while (ingredientValues.hasNext()) {
                    if (!budget.value()) return new JeiProvider.Projection(categoryId, handle, canonical, title,
                            capturedSlots, catalystCapture.ingredients(), List.of(), false, false,
                            Evidence.Coverage.PARTIAL, Evidence.DecodingStatus.PARTIAL,
                            "JEI shared read budget ended at a slot ingredient");
                    var captured = ingredient(runtime, ingredientValues.next(), slotView.getRole(), budget);
                    ingredients.add(captured.ingredient());
                    partial |= captured.partial();
                    visible &= captured.visible();
                    visibilityKnown &= captured.visibilityKnown();
                    if (budget.exhausted()) return new JeiProvider.Projection(categoryId, handle, canonical, title,
                            capturedSlots, catalystCapture.ingredients(), List.of(), false, false,
                            Evidence.Coverage.PARTIAL, Evidence.DecodingStatus.PARTIAL,
                            "JEI shared read budget ended at a slot ingredient");
                }
                capturedSlots.add(new JeiProvider.Slot(groupId, ingredients, role(slotView.getRole())));
            }
            if (capturedSlots.isEmpty()) {
                partial = true;
                visible = false;
                visibilityKnown = false;
            }
            var information = new ArrayList<String>();
            try {
                if (recipe instanceof IJeiIngredientInfoRecipe infoRecipe) {
                    var description = infoRecipe.getDescription().iterator();
                    while (description.hasNext()) {
                        if (!budget.value() || !budget.bytes(1024)) { partial = true; break; }
                        var text = description.next().getString();
                        if (text.length() <= 512) information.add(text);
                        else partial = true;
                    }
                }
            } catch (Throwable ignored) {
                partial = true;
            }
            try {
                if (budget.value()) {
                    var tooltip = ((IRecipeCategory<Object>) category).getTooltipStrings(recipe, slotsView, 0, 0).iterator();
                    while (tooltip.hasNext()) {
                        if (!budget.value() || !budget.bytes(1024)) { partial = true; break; }
                        var text = tooltip.next().getString();
                        if (text.length() <= 512) information.add(text);
                        else partial = true;
                    }
                } else partial = true;
            } catch (Throwable ignored) {
                partial = true;
            }
            var coverage = !knownCategory ? Evidence.Coverage.OPAQUE
                    : partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE;
            var decoding = !knownCategory ? Evidence.DecodingStatus.OPAQUE
                    : partial ? Evidence.DecodingStatus.PARTIAL : Evidence.DecodingStatus.VERIFIED;
            return new JeiProvider.Projection(categoryId, handle, canonical, title, capturedSlots,
                    catalystCapture.ingredients(), information, visible, visibilityKnown, coverage, decoding, null);
        }

        private static CatalystCapture catalysts(IJeiRuntime runtime, IRecipeManager manager, RecipeType<?> type,
                                                  Toolbox.DocumentBudget budget) {
            var result = new ArrayList<JeiProvider.Ingredient>();
            var partial = false;
            var visible = true;
            var visibilityKnown = true;
            try (var values = manager.createRecipeCatalystLookup(type).includeHidden().get()) {
                var iterator = values.iterator();
                var count = 0;
                while (iterator.hasNext()) {
                    if (count++ >= 32) {
                        partial = true;
                        break;
                    }
                    if (!budget.value()) { partial = true; break; }
                    var value = iterator.next();
                    var captured = ingredient(runtime, value, RecipeIngredientRole.CATALYST, budget);
                    result.add(captured.ingredient());
                    partial |= captured.partial();
                    visible &= captured.visible();
                    visibilityKnown &= captured.visibilityKnown();
                    if (budget.exhausted()) { partial = true; break; }
                }
                return new CatalystCapture(result, partial, visible, visibilityKnown);
            } catch (Throwable failure) {
                return new CatalystCapture(result, true, false, false);
            }
        }

        private static CapturedIngredient ingredient(IJeiRuntime runtime, ITypedIngredient<?> typed,
                                                     RecipeIngredientRole role, Toolbox.DocumentBudget budget) {
            var unreadIngredient = new CapturedIngredient(
                    new JeiProvider.Ingredient("unknown", "budget:unread", new Evidence.JsonObject(Map.of()),
                            null, Evidence.Consumption.UNKNOWN), true, false, false);
            if (!budget.value()) return unreadIngredient;
            var type = typed.getType();
            var value = typed.getIngredient();
            var partial = false;
            var typeId = value instanceof ItemStack ? "item" : type.getUid();
            var valueClass = value.getClass().getName();
            if (typeId.length() > budget.remainingBytes() || valueClass.length() > budget.remainingBytes()) {
                budget.bytes(budget.remainingBytes() + 1);
                return unreadIngredient;
            }
            var typeIdBytes = typeId.getBytes(StandardCharsets.UTF_8);
            var valueClassBytes = valueClass.getBytes(StandardCharsets.UTF_8);
            String identity;
            int identityBytes;
            var componentBytes = valueClassBytes.length;
            var descriptionId = valueClass;
            var componentHash = 0;
            String potionId = null;
            if (value instanceof ItemStack stack) {
                var itemKey = BuiltInRegistries.ITEM.getKey(stack.getItem());
                var itemNamespace = itemKey.getNamespace();
                var itemPath = itemKey.getPath();
                descriptionId = stack.getDescriptionId();
                componentHash = stack.getComponents().hashCode();
                var componentHashText = Integer.toString(componentHash);
                var potionContents = stack.get(DataComponents.POTION_CONTENTS);
                potionId = potionContents == null ? null : potionContents.potion()
                        .flatMap(holder -> holder.unwrapKey())
                        .map(key -> key.location().toString()).orElse(null);
                if (itemNamespace.length() + 1L + itemPath.length() > budget.remainingBytes()
                        || descriptionId.length() > budget.remainingBytes()
                        || potionId != null && potionId.length() > budget.remainingBytes()) {
                    budget.bytes(budget.remainingBytes() + 1);
                    return unreadIngredient;
                }
                var itemNamespaceBytes = itemNamespace.getBytes(StandardCharsets.UTF_8);
                var itemPathBytes = itemPath.getBytes(StandardCharsets.UTF_8);
                var descriptionIdBytes = descriptionId.getBytes(StandardCharsets.UTF_8);
                identityBytes = itemNamespaceBytes.length + 1 + itemPathBytes.length
                        + "|componentsHash=".length() + componentHashText.length();
                componentBytes = descriptionIdBytes.length
                        + (potionId == null ? 0 : potionId.getBytes(StandardCharsets.UTF_8).length);
                var escapedBytes = 0;
                for (var text : new String[] { typeId, itemNamespace, itemPath, itemNamespace, itemPath,
                        descriptionId, potionId == null ? "" : potionId }) {
                    for (var index = 0; index < text.length(); index++) {
                        var character = text.charAt(index);
                        if (character == '"' || character == '\\' || character == '\b' || character == '\t'
                                || character == '\n' || character == '\f' || character == '\r') escapedBytes++;
                        else if (character < 0x20 || character == '<' || character == '>' || character == '&'
                                || character == '=' || character == '\'' || character == '\u2028' || character == '\u2029') {
                            escapedBytes += 5;
                        }
                    }
                }
                if (!budget.bytes(256 + typeIdBytes.length + identityBytes + componentBytes + escapedBytes)) {
                    return unreadIngredient;
                }
                var itemId = itemKey.toString();
                identity = itemId + "|componentsHash=" + componentHashText;
                partial = !stack.getComponents().isEmpty();
            } else {
                identityBytes = typeIdBytes.length + 1 + valueClassBytes.length;
                var escapedBytes = 0;
                for (var text : new String[] { typeId, typeId, valueClass, valueClass }) {
                    for (var index = 0; index < text.length(); index++) {
                        var character = text.charAt(index);
                        if (character == '"' || character == '\\' || character == '\b' || character == '\t'
                                || character == '\n' || character == '\f' || character == '\r') escapedBytes++;
                        else if (character < 0x20 || character == '<' || character == '>' || character == '&'
                                || character == '=' || character == '\'' || character == '\u2028' || character == '\u2029') {
                            escapedBytes += 5;
                        }
                    }
                }
                if (!budget.bytes(256 + typeIdBytes.length + identityBytes + componentBytes + escapedBytes)) {
                    return unreadIngredient;
                }
                identity = typeId + ":" + valueClass;
                partial = true;
            }
            Integer quantity = null;
            try {
                if (value instanceof ItemStack stack) quantity = stack.getCount();
            } catch (Throwable ignored) {
                partial = true;
            }
            var componentValues = new LinkedHashMap<String, Evidence.JsonValue>();
            if (value instanceof ItemStack stack) {
                componentValues.put("componentCount", new Evidence.JsonNumber(BigDecimal.valueOf(
                        stack.getComponents().size())));
                componentValues.put("componentHash", new Evidence.JsonNumber(BigDecimal.valueOf(componentHash)));
                componentValues.put("descriptionId", new Evidence.JsonString(descriptionId));
                if (potionId != null) componentValues.put("potion", new Evidence.JsonString(potionId));
            } else {
                componentValues.put("valueClass", new Evidence.JsonString(value.getClass().getName()));
                partial = true;
            }
            var components = new Evidence.JsonObject(componentValues);
            var visible = true;
            var visibilityKnown = true;
            try {
                if (!budget.value()) throw new IllegalStateException("JEI shared read budget ended before visibility lookup");
                visible = runtime.getIngredientVisibility().isIngredientVisible(typed);
            } catch (Throwable ignored) {
                partial = true;
                visible = false;
                visibilityKnown = false;
            }
            var consumption = role == RecipeIngredientRole.INPUT ? Evidence.Consumption.CONSUMED
                    : role == RecipeIngredientRole.CATALYST ? Evidence.Consumption.REUSED : Evidence.Consumption.UNKNOWN;
            return new CapturedIngredient(new JeiProvider.Ingredient(typeId, identity, components, quantity, consumption),
                    partial, visible, visibilityKnown);
        }

        private record CapturedIngredient(JeiProvider.Ingredient ingredient, boolean partial, boolean visible,
                                          boolean visibilityKnown) { }

        private record CatalystCapture(List<JeiProvider.Ingredient> ingredients, boolean partial, boolean visible,
                                       boolean visibilityKnown) {
            private CatalystCapture {
                ingredients = List.copyOf(ingredients);
            }
        }

        private record Canonical(String value, boolean failed) { }

        private record RegisteredRecipe(IRecipeCategory<?> category, Object recipe, Evidence.Scope scope,
                                        String outputItem) { }

        private record NavigationIdentity(String categoryId, String canonicalRecipeId,
                                          IRecipeCategory<?> legacyCategory) { }

        private static <T> void showRecipe(IJeiRuntime runtime, RegisteredRecipe registered) {
            if (registered.recipe() == null) {
                runtime.getRecipesGui().showTypes(List.of(registered.category().getRecipeType()));
                return;
            }
            showRecipeTyped(runtime, (IRecipeCategory<T>) registered.category(), (T) registered.recipe());
        }

        private static <T> void showRecipeTyped(IJeiRuntime runtime, IRecipeCategory<T> category, T recipe) {
            runtime.getRecipesGui().showRecipes(category, List.of(recipe), List.of());
        }

        private static JeiProvider.RecipeRole role(RecipeIngredientRole value) {
            return switch (value) {
                case INPUT -> JeiProvider.RecipeRole.INPUT;
                case OUTPUT -> JeiProvider.RecipeRole.OUTPUT;
                case CATALYST -> JeiProvider.RecipeRole.CATALYST;
                case RENDER_ONLY -> JeiProvider.RecipeRole.RENDER_ONLY;
            };
        }

        private static String categoryId(IRecipeCategory<?> category, int index) {
            try {
                return category.getRecipeType().getUid().toString();
            } catch (Throwable ignored) {
                return "unknown:category-" + index;
            }
        }

        private NavigationIdentity navigationIdentity(String id, IRecipeManager manager,
                                                       Toolbox.DocumentBudget budget) {
            if (id.length() > budget.remainingBytes() / 2 || !budget.bytes(id.length() * 2)) return null;
            if (id.startsWith("category-v1|")) {
                var category = id.substring("category-v1|".length());
                var parsed = ResourceLocation.tryParse(category);
                return parsed != null && parsed.toString().equals(category)
                        ? new NavigationIdentity(category, null, null) : null;
            }
            if (id.startsWith("recipe-v1|")) {
                var parts = id.split("\\|", -1);
                if (parts.length != 3) return null;
                var category = ResourceLocation.tryParse(parts[1]);
                var recipe = ResourceLocation.tryParse(parts[2]);
                return category != null && recipe != null && category.toString().equals(parts[1])
                        && recipe.toString().equals(parts[2])
                        ? new NavigationIdentity(parts[1], parts[2], null) : null;
            }
            if (!id.matches("jei/[0-9]+/[0-9]+/.*")) return null;
            var prefixStart = id.indexOf('/', id.indexOf('/', 4) + 1);
            if (prefixStart < 0) return null;
            NavigationIdentity identity = null;
            try (var categories = manager.createRecipeCategoryLookup().get()) {
                var iterator = categories.iterator();
                for (int scanned = 0; iterator.hasNext(); scanned++) {
                    if (scanned >= MAX_CATEGORIES || !budget.document()) return null;
                    var category = iterator.next();
                    var categoryId = categoryId(category, scanned + 1);
                    var parsedCategory = ResourceLocation.tryParse(categoryId);
                    if (parsedCategory == null || !parsedCategory.toString().equals(categoryId)) continue;
                    var prefix = id.substring(0, prefixStart + 1) + categoryId + "/recipe-";
                    if (!id.startsWith(prefix)) continue;
                    var suffix = id.substring(prefix.length());
                    var ordinalSeparator = suffix.lastIndexOf('-');
                    if (ordinalSeparator <= 0) continue;
                    try {
                        if (Integer.parseInt(suffix.substring(ordinalSeparator + 1)) < 0) continue;
                    } catch (NumberFormatException ignored) {
                        continue;
                    }
                    var canonical = suffix.substring(0, ordinalSeparator);
                    var canonicalId = ResourceLocation.tryParse(canonical);
                    if (canonicalId == null || !canonicalId.toString().equals(canonical)) continue;
                    if (identity != null) return null;
                    identity = new NavigationIdentity(categoryId, canonical, category);
                }
            } catch (RuntimeException ignored) {
                return null;
            }
            return identity;
        }

        private RegisteredRecipe resolveNavigation(NavigationIdentity identity, IRecipeManager manager,
                                                    Evidence.Scope scope, Toolbox.DocumentBudget budget,
                                                    Function<Object, ResourceLocation> nativeRecipeId) {
            var selectedCategory = identity.legacyCategory();
            if (selectedCategory == null) {
                try (var categories = manager.createRecipeCategoryLookup().get()) {
                    var iterator = categories.iterator();
                    for (int scanned = 0; iterator.hasNext(); scanned++) {
                        if (scanned >= MAX_CATEGORIES || !budget.document()) return null;
                        var category = iterator.next();
                        if (!identity.categoryId().equals(categoryId(category, scanned + 1))) continue;
                        if (selectedCategory != null) return null;
                        selectedCategory = category;
                    }
                } catch (RuntimeException ignored) {
                    return null;
                }
            }
            if (selectedCategory == null) return null;
            var type = selectedCategory.getRecipeType();
            if (manager.getRecipeCategory(type) != selectedCategory) return null;
            if (identity.canonicalRecipeId() == null) {
                return new RegisteredRecipe(selectedCategory, null, scope, null);
            }
            Object selectedRecipe = null;
            try (var recipes = manager.createRecipeLookup(type).get()) {
                var iterator = recipes.iterator();
                for (int scanned = 0; iterator.hasNext(); scanned++) {
                    if (scanned >= MAX_NAVIGATION_RECIPES
                            || !budget.callback(JeiProvider.ID, MAX_NAVIGATION_RECIPES)) return null;
                    var recipe = iterator.next();
                    var canonical = canonical(selectedCategory, recipe, nativeRecipeId);
                    if (!canonical.failed() && identity.canonicalRecipeId().equals(canonical.value())) {
                        if (selectedRecipe != null) return null;
                        selectedRecipe = recipe;
                    }
                }
            } catch (RuntimeException ignored) {
                return null;
            }
            return selectedRecipe == null ? null : new RegisteredRecipe(selectedCategory, selectedRecipe, scope, null);
        }

        private static String title(IRecipeCategory<?> category, String fallback) {
            try {
                return category.getTitle().getString();
            } catch (Throwable ignored) {
                return fallback;
            }
        }

        private static Canonical canonical(IRecipeCategory<?> category, Object recipe,
                                           Function<Object, ResourceLocation> nativeRecipeId) {
            try {
                var name = ((IRecipeCategory<Object>) category).getRegistryName(recipe);
                if (name != null) return new Canonical(name.toString(), false);
                var nativeName = nativeRecipeId.apply(recipe);
                return new Canonical(nativeName == null ? null : nativeName.toString(), false);
            } catch (Throwable ignored) {
                return new Canonical(null, true);
            }
        }

        private static String sourceHandle(long generation, long snapshotVersion, String categoryId, String canonical,
                                           Object recipe, int ordinal) {
            var identity = canonical == null ? recipe.getClass().getName() + "-"
                    + Integer.toHexString(System.identityHashCode(recipe)) + "-" + ordinal
                    : "recipe-" + canonical + "-" + ordinal;
            return "jei/" + generation + "/" + snapshotVersion + "/" + categoryId + "/" + identity;
        }

        private String categoryHandle(long snapshotVersion, String categoryId) {
            return "jei/" + generation + "/" + snapshotVersion + "/" + categoryId + "/category";
        }

        private static boolean knownCategory(String categoryId) {
            return categoryId.startsWith("minecraft:");
        }

        private static boolean matches(String categoryId, String title, String canonical, String handle,
                                       Evidence.Operation operation, String id, String text) {
            if (operation == Evidence.Operation.GET && id != null && !id.equals(canonical)
                    && !id.equals(handle) && !id.equals(categoryId)) return false;
            if (operation != Evidence.Operation.SEARCH && operation != Evidence.Operation.RELATED) return true;
            if (text.isEmpty()) return true;
            java.util.function.Predicate<String> contains = value -> {
                if (value == null || value.length() < text.length()) return false;
                for (var index = 0; index <= value.length() - text.length(); index++) {
                    if (value.regionMatches(true, index, text, 0, text.length())) return true;
                }
                return false;
            };
            return contains.test(categoryId) || contains.test(title) || contains.test(canonical) || contains.test(handle);
        }

        private static String stringArgument(Evidence.JsonObject arguments, String key) {
            var value = arguments.values().get(key);
            return value instanceof Evidence.JsonString string ? string.value() : null;
        }

        private static int parseCursor(String cursor) {
            if (cursor == null) return 0;
            try {
                var value = Integer.parseInt(cursor);
                return value < 0 ? -1 : value;
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }

        private static final class EmptyFocusGroup implements mezz.jei.api.recipe.IFocusGroup {
            @Override public boolean isEmpty() { return true; }
            @Override public List<mezz.jei.api.recipe.IFocus<?>> getAllFocuses() { return List.of(); }
            @Override public java.util.stream.Stream<mezz.jei.api.recipe.IFocus<?>> getFocuses(RecipeIngredientRole role) {
                return java.util.stream.Stream.empty();
            }
            @Override public <T> java.util.stream.Stream<mezz.jei.api.recipe.IFocus<T>> getFocuses(
                    mezz.jei.api.ingredients.IIngredientType<T> type) { return java.util.stream.Stream.empty(); }
            @Override public <T> java.util.stream.Stream<mezz.jei.api.recipe.IFocus<T>> getFocuses(
                    mezz.jei.api.ingredients.IIngredientType<T> type, RecipeIngredientRole role) {
                return java.util.stream.Stream.empty();
            }
        }
    }
}
