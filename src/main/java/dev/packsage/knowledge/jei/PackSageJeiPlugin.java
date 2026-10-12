package dev.packsage.knowledge.jei;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.vanilla.IJeiIngredientInfoRecipe;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@JeiPlugin
public final class PackSageJeiPlugin implements IModPlugin {
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final AtomicLong RUNTIME_GENERATION = new AtomicLong();
    private static final AtomicLong CLIENT_TICKS = new AtomicLong();
    private static boolean tickListenerRegistered;
    public static volatile KnowledgeProvider activeProvider;
    private volatile JeiProvider provider;
    private volatile RuntimeState runtimeState;

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
        var state = new RuntimeState(runtime, GENERATION.incrementAndGet());
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
        private static final int MAX_PAGE = 64;
        private static final int CURSOR_RADIX = 1_000_000;

        private final IJeiRuntime runtime;
        private final long generation;
        private final AtomicLong snapshotVersions = new AtomicLong();
        private volatile boolean active = true;

        private RuntimeState(IJeiRuntime runtime, long generation) {
            this.runtime = runtime;
            this.generation = generation;
        }

        private Executor clientExecutor() {
            return Minecraft.getInstance()::execute;
        }

        private boolean owningThread() {
            return Minecraft.getInstance().isSameThread();
        }

        private void unavailable() {
            active = false;
        }

        private JeiProvider.Snapshot snapshot(Evidence.Scope scope, Evidence.Query query) {
            if (!active) return new JeiProvider.Snapshot(scope, generation, snapshotVersions.incrementAndGet(), List.of(),
                    ignored -> { }, Evidence.Coverage.UNAVAILABLE, true, null);
            if (!owningThread()) throw new IllegalStateException("JEI snapshot must be captured on the client thread");
            var snapshotVersion = snapshotVersions.incrementAndGet();
            var manager = runtime.getRecipeManager();
            var projections = new ArrayList<JeiProvider.Projection>();
            var navigation = new LinkedHashMap<String, RegisteredRecipe>();
            var cursor = parseCursor(query.cursor());
            var cursorCategory = cursor / CURSOR_RADIX;
            var cursorRecipe = cursor % CURSOR_RADIX;
            var pageLimit = Math.min(query.limit(), MAX_PAGE);
            var text = stringArgument(query.arguments(), "text");
            text = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
            var id = stringArgument(query.arguments(), "id");
            var scanned = 0;
            var categoryCount = 0;
            var hasMore = false;
            var nextCategory = 0;
            var nextRecipe = 0;
            var partial = false;
            var opaque = false;
            var error = false;
            var categoryLookupFailed = false;
            try (var categories = manager.createRecipeCategoryLookup().includeHidden().get()) {
                var categoryIterator = categories.iterator();
                while (categoryIterator.hasNext()) {
                    var categoryIndex = categoryCount++;
                    if (categoryIndex - cursorCategory >= MAX_CATEGORIES) {
                        partial = true;
                        hasMore = true;
                        nextCategory = categoryIndex;
                        nextRecipe = 0;
                        break;
                    }
                    var category = categoryIterator.next();
                    if (categoryIndex < cursorCategory) continue;
                    var categoryId = categoryId(category, categoryCount);
                    var categoryKnown = knownCategory(categoryId);
                    opaque |= !categoryKnown;
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
                    var catalystCapture = catalysts(runtime, manager, type);
                    partial |= catalystCapture.partial();
                    try (var recipes = manager.createRecipeLookup(type).includeHidden().get()) {
                        var recipeIterator = recipes.iterator();
                        var recipeCount = 0;
                        var recipeOrdinal = 0;
                        while (recipeIterator.hasNext()) {
                            if (recipeOrdinal < categoryCursor) {
                                recipeIterator.next();
                                recipeOrdinal++;
                                continue;
                            }
                            if (projections.size() >= pageLimit || scanned >= MAX_SCANNED_RECIPES) {
                                partial |= scanned >= MAX_SCANNED_RECIPES;
                                hasMore = true;
                                nextCategory = categoryIndex;
                                nextRecipe = recipeOrdinal;
                                break;
                            }
                            var recipe = recipeIterator.next();
                            recipeOrdinal++;
                            recipeCount++;
                            scanned++;
                            var canonicalCapture = canonical(category, recipe);
                            var handle = sourceHandle(generation, snapshotVersion, categoryId, canonicalCapture.value(), recipe, recipeOrdinal);
                            var candidate = matches(categoryId, title, canonicalCapture.value(), handle, query.operation(), id, text);
                            if (!candidate) continue;
                            JeiProvider.Projection projection;
                            try {
                                projection = project(runtime, manager, category, recipe, categoryId, handle,
                                        canonicalCapture.value(), title, categoryKnown, canonicalCapture.failed(), catalystCapture);
                            } catch (Throwable failure) {
                                projection = JeiProvider.Projection.error(categoryId, handle, canonicalCapture.value(),
                                        title, failure.toString());
                            }
                            projections.add(projection);
                            if (projection.coverage() != Evidence.Coverage.ERROR) {
                                navigation.put(handle, new RegisteredRecipe(category, recipe));
                            }
                            partial |= projection.coverage() == Evidence.Coverage.PARTIAL;
                            opaque |= projection.coverage() == Evidence.Coverage.OPAQUE;
                            error |= projection.coverage() == Evidence.Coverage.ERROR;
                        }
                        if (hasMore) break;
                        if (recipeCount == 0 && !categoryKnown && categoryCursor == 0 && matches(categoryId, title, null,
                                categoryHandle(snapshotVersion, categoryId), query.operation(), id, text)) {
                            var handle = categoryHandle(snapshotVersion, categoryId);
                            if (projections.size() >= pageLimit) {
                                hasMore = true;
                                nextCategory = categoryIndex;
                                nextRecipe = 0;
                            } else {
                                projections.add(JeiProvider.Projection.opaque(categoryId, handle, title,
                                        "JEI category has no detached recipe projection"));
                                navigation.put(handle, new RegisteredRecipe(category, null));
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
                    if (!hasMore && (projections.size() >= pageLimit || scanned >= MAX_SCANNED_RECIPES)
                            && categoryIterator.hasNext()) {
                        partial |= scanned >= MAX_SCANNED_RECIPES;
                        hasMore = true;
                        nextCategory = categoryIndex + 1;
                        nextRecipe = 0;
                    }
                    if (hasMore) break;
                }
            } catch (Throwable failure) {
                categoryLookupFailed = true;
                var handle = "jei/" + generation + "/" + snapshotVersion + "/category-lookup";
                projections.add(JeiProvider.Projection.error("unknown:category", handle, null,
                        "JEI category lookup", failure.toString()));
                error = true;
            }
            if (categoryLookupFailed || scanned >= MAX_SCANNED_RECIPES) partial = true;
            var coverage = error ? Evidence.Coverage.ERROR : partial ? Evidence.Coverage.PARTIAL
                    : opaque ? Evidence.Coverage.OPAQUE : Evidence.Coverage.COMPLETE;
            var next = hasMore ? Integer.toString(nextCategory * CURSOR_RADIX + nextRecipe) : null;
            return new JeiProvider.Snapshot(scope, generation, snapshotVersion, projections, handle -> {
                var registered = navigation.get(handle);
                if (!active) throw new IllegalStateException("JEI runtime unavailable");
                if (registered == null) throw new IllegalArgumentException("unknown JEI projection " + handle);
                showRecipe(runtime, registered);
            }, coverage, true, next);
        }

        private static JeiProvider.Projection project(IJeiRuntime runtime, IRecipeManager manager,
                                                            IRecipeCategory<?> category, Object recipe,
                                                            String categoryId, String handle, String canonical,
                                                            String title, boolean knownCategory,
                                                            boolean canonicalFailed,
                                                            CatalystCapture catalystCapture) {
            var layout = manager.createRecipeLayoutDrawable((IRecipeCategory<Object>) category, recipe,
                    new EmptyFocusGroup()).orElse(null);
            if (layout == null) return JeiProvider.Projection.error(categoryId, handle, canonical, title,
                    "JEI did not provide a detached recipe layout");
            var slotsView = layout.getRecipeSlotsView();
            var capturedSlots = new ArrayList<JeiProvider.Slot>();
            var partial = canonicalFailed || catalystCapture.partial();
            var visible = catalystCapture.visible();
            var visibilityKnown = catalystCapture.visibilityKnown();
            var slotIndex = 0;
            for (var slotView : slotsView.getSlotViews()) {
                var ingredients = new ArrayList<JeiProvider.Ingredient>();
                for (var ingredient : slotView.getAllIngredientsList()) {
                    var captured = ingredient(runtime, ingredient, slotView.getRole());
                    ingredients.add(captured.ingredient());
                    partial |= captured.partial();
                    visible &= captured.visible();
                    visibilityKnown &= captured.visibilityKnown();
                }
                var groupId = slotView.getSlotName().orElse("slot-" + slotIndex++);
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
                    information.addAll(infoRecipe.getDescription().stream().map(value -> value.getString()).toList());
                }
            } catch (Throwable ignored) {
                partial = true;
            }
            try {
                information.addAll(((IRecipeCategory<Object>) category).getTooltipStrings(recipe, slotsView, 0, 0).stream()
                        .map(Component::getString).toList());
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

        private static CatalystCapture catalysts(IJeiRuntime runtime, IRecipeManager manager, RecipeType<?> type) {
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
                    var value = iterator.next();
                    var captured = ingredient(runtime, value, RecipeIngredientRole.CATALYST);
                    result.add(captured.ingredient());
                    partial |= captured.partial();
                    visible &= captured.visible();
                    visibilityKnown &= captured.visibilityKnown();
                }
                return new CatalystCapture(result, partial, visible, visibilityKnown);
            } catch (Throwable failure) {
                return new CatalystCapture(result, true, false, false);
            }
        }

        private static CapturedIngredient ingredient(IJeiRuntime runtime, ITypedIngredient<?> typed,
                                                     RecipeIngredientRole role) {
            var type = typed.getType();
            var value = typed.getIngredient();
            var partial = false;
            var typeId = type.getUid();
            var identity = "unknown:" + typeId + ":" + value.getClass().getName();
            if (value instanceof ItemStack stack) {
                typeId = type.getUid();
                identity = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString() + "|components="
                        + stack.getComponents();
            } else {
                try {
                    var helper = ingredientHelper(runtime, value);
                    identity = helper.getUniqueId(value, UidContext.Recipe);
                } catch (Throwable failure) {
                    partial = true;
                    identity = typeId + ":" + value.getClass().getName() + ":" + String.valueOf(value);
                }
                partial = true;
            }
            Integer quantity = null;
            try {
                if (value instanceof ItemStack stack) quantity = stack.getCount();
                else {
                    var helper = ingredientHelper(runtime, value);
                    var amount = helper.getAmount(value);
                    if (amount > 0 && amount <= Integer.MAX_VALUE) quantity = (int) amount;
                    else if (amount > Integer.MAX_VALUE) partial = true;
                }
            } catch (Throwable ignored) {
                partial = true;
            }
            var componentValues = new LinkedHashMap<String, Evidence.JsonValue>();
            if (value instanceof ItemStack stack) {
                componentValues.put("componentIdentity", new Evidence.JsonString(stack.getComponents().toString()));
                componentValues.put("descriptionId", new Evidence.JsonString(stack.getDescriptionId()));
            } else {
                componentValues.put("valueClass", new Evidence.JsonString(value.getClass().getName()));
                try {
                    var helper = ingredientHelper(runtime, value);
                    componentValues.put("displayName", new Evidence.JsonString(helper.getDisplayName(value)));
                } catch (Throwable failure) {
                    partial = true;
                }
            }
            var components = new Evidence.JsonObject(componentValues);
            var visible = true;
            var visibilityKnown = true;
            try {
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

        private static IIngredientHelper<Object> ingredientHelper(IJeiRuntime runtime, Object value) {
            return (IIngredientHelper<Object>) (IIngredientHelper<?>) runtime.getIngredientManager().getIngredientHelper(value);
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

        private record RegisteredRecipe(IRecipeCategory<?> category, Object recipe) { }

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

        private static String title(IRecipeCategory<?> category, String fallback) {
            try {
                return category.getTitle().getString();
            } catch (Throwable ignored) {
                return fallback;
            }
        }

        private static Canonical canonical(IRecipeCategory<?> category, Object recipe) {
            try {
                var name = ((IRecipeCategory<Object>) category).getRegistryName(recipe);
                return new Canonical(name == null ? null : name.toString(), false);
            } catch (Throwable ignored) {
                return new Canonical(null, true);
            }
        }

        private static String sourceHandle(long generation, long snapshotVersion, String categoryId, String canonical,
                                           Object recipe, int ordinal) {
            var identity = canonical == null ? recipe.getClass().getName() + "-" + Integer.toHexString(
                    String.valueOf(recipe).hashCode()) + "-" + ordinal : "recipe-" + canonical + "-" + ordinal;
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
            return text.isEmpty() || categoryId.toLowerCase(java.util.Locale.ROOT).contains(text)
                    || title.toLowerCase(java.util.Locale.ROOT).contains(text)
                    || canonical != null && canonical.toLowerCase(java.util.Locale.ROOT).contains(text)
                    || handle.toLowerCase(java.util.Locale.ROOT).contains(text);
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
