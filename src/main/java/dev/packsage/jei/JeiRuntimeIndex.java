package dev.packsage.jei;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.knowledge.nativegame.NativeSnapshot;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.neoforge.NeoForgeTypes;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.crafting.RecipeHolder;

public final class JeiRuntimeIndex implements JeiIndex {
    private final IJeiRuntime runtime;
    private final Map<String, CategoryRecipes> categories = new LinkedHashMap<>(16, 0.75f, true);
    private final long createdGeneration;
    private final BooleanSupplier clientThread;
    private long generation;
    private record CategoryRecipes(List<?> recipes, IdentityHashMap<Object, Integer> ordinals) { }
    private boolean truncated;

    public JeiRuntimeIndex(JeiRuntimeHolder.Snapshot snapshot) {
        this(snapshot.runtime(), snapshot.generation(), () -> Minecraft.getInstance().isSameThread());
    }

    JeiRuntimeIndex(IJeiRuntime runtime, long generation, BooleanSupplier clientThread) {
        this.runtime = runtime;
        this.createdGeneration = generation;
        this.generation = generation;
        this.clientThread = clientThread;
    }

    /** The runtime this index was built from; only current while {@link #generation()} accepts it. */
    IJeiRuntime runtime() {
        return runtime;
    }

    @Override public long generation() {
        long current = JeiRuntimeHolder.generation();
        if (current != generation) {
            generation = current;
            categories.clear();
        }
        if (current != createdGeneration) throw new Toolbox.Failure(Toolbox.Code.STALE_SCOPE, "JEI runtime generation changed");
        if (!clientThread.getAsBoolean()) throw new IllegalStateException("JEI index must run on the client thread");
        return generation;
    }

    @Override public List<RecipeRef> byFocus(JeiLayout.Role role, String itemId) {
        generation();
        var id = ResourceLocation.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return List.of();
        var stack = BuiltInRegistries.ITEM.get(id).getDefaultInstance();
        var stacks = hasSubtypes(itemId)
                ? runtime.getIngredientManager().getAllItemStacks().stream().filter(value -> value.getItem() == stack.getItem()).toList()
                : List.of(stack);
        var focuses = stacks.stream().map(value -> runtime.getJeiHelpers().getFocusFactory().createFocus(
                RecipeIngredientRole.valueOf(role.name()), VanillaTypes.ITEM_STACK, value)).toList();
        var refs = new LinkedHashSet<RecipeRef>();
        if (!focuses.isEmpty()) runtime.getRecipeManager().createRecipeCategoryLookup().limitFocus(focuses).includeHidden().get()
                .forEach(category -> refs.addAll(lookup(category, focuses)));
        return List.copyOf(refs);
    }

    @Override public boolean hasSubtypes(String itemId) {
        generation();
        var id = ResourceLocation.tryParse(itemId);
        return id != null && BuiltInRegistries.ITEM.containsKey(id)
                && runtime.getIngredientManager().getIngredientHelper(VanillaTypes.ITEM_STACK)
                .hasSubtypes(BuiltInRegistries.ITEM.get(id).getDefaultInstance());
    }

    @Override public List<RecipeRef> byVariant(JeiLayout.Role role, ItemVariant variant) {
        generation();
        var registries = Minecraft.getInstance().level.registryAccess();
        var item = registries.lookupOrThrow(Registries.ITEM).get(ResourceKey.create(Registries.ITEM,
                ResourceLocation.parse(variant.itemId()))).orElseThrow();
        var patch = DataComponentPatch.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE),
                JsonParser.parseString(variant.componentsJson())).result().orElseThrow();
        var focuses = List.of(runtime.getJeiHelpers().getFocusFactory().createFocus(
                RecipeIngredientRole.valueOf(role.name()), VanillaTypes.ITEM_STACK, new ItemStack(item, 1, patch)));
        var refs = new LinkedHashSet<RecipeRef>();
        runtime.getRecipeManager().createRecipeCategoryLookup().limitFocus(focuses).includeHidden().get()
                .forEach(category -> refs.addAll(lookup(category, focuses)));
        return List.copyOf(refs);
    }
    private <T> CategoryRecipes categoryRecipes(IRecipeCategory<T> category) {
        var uid = category.getRecipeType().getUid().toString();
        var cached = categories.get(uid);
        if (cached != null) return cached;
        var recipes = runtime.getRecipeManager().createRecipeLookup(category.getRecipeType()).includeHidden().get().toList();
        cached = new CategoryRecipes(recipes, new IdentityHashMap<>());
        if (categories.size() == 256) categories.remove(categories.keySet().iterator().next());
        categories.put(uid, cached);
        return cached;
    }

    private <T> List<RecipeRef> lookup(IRecipeCategory<T> category, List<? extends IFocus<?>> focuses) {
        var cached = categoryRecipes(category);
        var uid = category.getRecipeType().getUid().toString();
        if (focuses == null) {
            var refs = new ArrayList<RecipeRef>();
            for (int i = 0; i < cached.recipes().size(); i++) refs.add(new RecipeRef(uid, i));
            return refs;
        }
        if (cached.ordinals().isEmpty())
            for (int i = 0; i < cached.recipes().size(); i++) cached.ordinals().putIfAbsent(cached.recipes().get(i), i);
        var refs = new ArrayList<RecipeRef>();
        runtime.getRecipeManager().createRecipeLookup(category.getRecipeType()).limitFocus(focuses).includeHidden().get()
                .forEach(recipe -> {
                    var ordinal = cached.ordinals().get(recipe);
                    if (ordinal != null) refs.add(new RecipeRef(uid, ordinal));
                });
        return refs;
    }

    @Override public List<String> categoriesMatching(String text) {
        generation();
        var query = text.toLowerCase(Locale.ROOT);
        return runtime.getRecipeManager().createRecipeCategoryLookup().includeHidden().get()
                .filter(category -> category.getTitle().getString().toLowerCase(Locale.ROOT).contains(query))
                .map(category -> category.getRecipeType().getUid().toString()).toList();
    }

    @Override public List<ItemVariant> itemsMatching(String text) {
        generation();
        var filter = runtime.getIngredientFilter();
        var previous = filter.getFilterText();
        var variants = new LinkedHashSet<ItemVariant>();
        truncated = false;
        try {
            filter.setFilterText(text);
            var query = text.toLowerCase(Locale.ROOT);
            for (var stack : filter.getFilteredItemStacks()) {
                if (!stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(query)) continue;
                var variant = new ItemVariant(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), components(stack.getComponentsPatch()));
                if (!variants.contains(variant) && variants.size() == 64) { truncated = true; break; }
                variants.add(variant);
            }
        } finally {
            filter.setFilterText(previous);
        }
        return List.copyOf(variants);
    }

    @Override public boolean itemsMatchingTruncated() { return truncated; }

    @Override public List<RecipeRef> inCategory(String categoryUid) {
        generation();
        return runtime.getRecipeManager().createRecipeCategoryLookup().includeHidden().get()
                .filter(category -> category.getRecipeType().getUid().toString().equals(categoryUid))
                .findFirst().map(category -> lookup(category, null)).orElse(List.of());
    }

    @Override public Optional<RecipeRef> byRecipeId(String categoryUid, String recipeId) {
        generation();
        return runtime.getRecipeManager().createRecipeCategoryLookup().get()
                .filter(category -> category.getRecipeType().getUid().toString().equals(categoryUid))
                .findFirst().flatMap(category -> byRecipeId(category, recipeId));
    }

    private <T> Optional<RecipeRef> byRecipeId(IRecipeCategory<T> category, String recipeId) {
        T match = null;
        for (var recipe : runtime.getRecipeManager().createRecipeLookup(category.getRecipeType()).includeHidden().get().toList()) {
            var id = category.getRegistryName(recipe);
            if (id == null && recipe instanceof RecipeHolder<?> holder) id = holder.id();
            if (id != null && recipeId.equals(id.toString())) {
                if (match != null) return Optional.empty();
                match = recipe;
            }
        }
        if (match == null) return Optional.empty();
        var cached = categoryRecipes(category);
        var uid = category.getRecipeType().getUid().toString();
        for (int i = 0; i < cached.recipes().size(); i++)
            if (cached.recipes().get(i) == match) return Optional.of(new RecipeRef(uid, i));
        // Keep existing temporary ordinals when a recipe is registered during this runtime.
        var recipes = new ArrayList<Object>(cached.recipes());
        recipes.add(match);
        categories.put(uid, new CategoryRecipes(List.copyOf(recipes), new IdentityHashMap<>()));
        return Optional.of(new RecipeRef(uid, recipes.size() - 1));
    }

    /** Like resolve, but only for recipes JEI currently shows, so navigation never opens a hidden recipe. */
    Optional<Resolved<?>> resolveVisible(RecipeRef ref) {
        generation();
        var category = runtime.getRecipeManager().createRecipeCategoryLookup().get()
                .filter(value -> value.getRecipeType().getUid().toString().equals(ref.categoryUid())).findFirst().orElse(null);
        if (category == null) return Optional.empty();
        var recipes = categoryRecipes(category).recipes();
        if (ref.ordinal() < 0 || ref.ordinal() >= recipes.size()) return Optional.empty();
        var recipe = recipes.get(ref.ordinal());
        if (runtime.getRecipeManager().createRecipeLookup(category.getRecipeType()).get().noneMatch(value -> value == recipe))
            return Optional.empty();
        return Optional.of(resolve(category, ref));
    }

    public record Resolved<T>(IRecipeCategory<T> category, T recipe) { }

    /** The category and recipe object behind a reference, or empty when the reference is no longer valid. */
    public Optional<Resolved<?>> resolve(RecipeRef ref) {
        generation();
        return runtime.getRecipeManager().createRecipeCategoryLookup().includeHidden().get()
                .filter(value -> value.getRecipeType().getUid().toString().equals(ref.categoryUid())).findFirst()
                .<Resolved<?>>map(category -> resolve(category, ref));
    }

    private <T> Resolved<T> resolve(IRecipeCategory<T> category, RecipeRef ref) {
        var recipes = categoryRecipes(category).recipes();
        if (ref.ordinal() < 0 || ref.ordinal() >= recipes.size()) return null;
        return new Resolved<>(category, category.getRecipeType().getRecipeClass().cast(recipes.get(ref.ordinal())));
    }

    @Override public JeiLayout layout(RecipeRef ref) {
        try {
            generation();
            var category = runtime.getRecipeManager().createRecipeCategoryLookup().includeHidden().get()
                    .filter(value -> value.getRecipeType().getUid().toString().equals(ref.categoryUid())).findFirst().orElseThrow();
            return layout(category, ref);
        } catch (RuntimeException failure) {
            return new JeiLayout(ref.categoryUid(), ref.categoryUid(), null, ref.ordinal(), generation, false, false, null, List.of(), List.of());
        }
    }

    // These tooltip APIs are retained for compatibility with JEI 19.27.
    @SuppressWarnings({"deprecation", "removal"})
    private <T> JeiLayout layout(IRecipeCategory<T> category, RecipeRef ref) {
        var cached = categoryRecipes(category);
        if (ref.ordinal() < 0 || ref.ordinal() >= cached.recipes().size()) throw new IllegalArgumentException("invalid recipe reference");
        var recipe = category.getRecipeType().getRecipeClass().cast(cached.recipes().get(ref.ordinal()));
        var id = category.getRegistryName(recipe);
        if (id == null && recipe instanceof RecipeHolder<?> holder) id = holder.id();
        var drawable = runtime.getRecipeManager().createRecipeLayoutDrawable(category, recipe,
                runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup()).orElseThrow();
        var slots = new ArrayList<JeiLayout.Slot>();
        for (var slot : drawable.getRecipeSlotsView().getSlotViews()) {
            var alternatives = new ArrayList<JeiLayout.Ingredient>();
            for (var ingredient : slot.getAllIngredients().toList()) {
                var item = ingredient.getIngredient(VanillaTypes.ITEM_STACK);
                var fluid = ingredient.getIngredient(NeoForgeTypes.FLUID_STACK);
                if (item.isPresent()) {
                    var stack = item.get();
                    var remainder = stack.getCraftingRemainingItem();
                    alternatives.add(new JeiLayout.Ingredient("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                            components(stack.getComponentsPatch()), stack.getCount(), remainder.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(remainder.getItem()).toString()));
                } else if (fluid.isPresent()) {
                    var stack = fluid.get();
                    alternatives.add(new JeiLayout.Ingredient("fluid", BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString(),
                            components(stack.getComponentsPatch()), stack.getAmount(), null));
                } else alternatives.add(new JeiLayout.Ingredient("other", ingredient.getType().toString(), "{}", 1, null));
            }
            var extra = new ArrayList<String>();
            if (slot instanceof IRecipeSlotDrawable drawableSlot) {
                extra.addAll(drawableSlot.getTooltip().stream().map(value -> value.getString()).toList());
                slot.getDisplayedIngredient().ifPresent(ingredient -> { for (var line : tooltip(ingredient)) extra.remove(line); });
            }
            slots.add(new JeiLayout.Slot(slots.size(), JeiLayout.Role.valueOf(slot.getRole().name()), alternatives, extra));
        }
        boolean hidden = runtime.getRecipeManager().createRecipeCategoryLookup().get()
                .noneMatch(value -> value.getRecipeType().getUid().equals(category.getRecipeType().getUid()))
                || runtime.getRecipeManager().createRecipeLookup(category.getRecipeType()).get().noneMatch(value -> value == recipe);
        Evidence.DecodingStatus decoding = null;
        if (JeiProjection.VANILLA_CATEGORIES.contains(ref.categoryUid()) && recipe instanceof RecipeHolder<?> holder)
            decoding = NativeSnapshot.decoding(holder, Minecraft.getInstance(), new Evidence.Scope("", "", "", "", generation));
        return new JeiLayout(ref.categoryUid(), category.getTitle().getString(), id == null ? null : id.toString(), ref.ordinal(), generation,
                hidden, true, decoding, slots, category.getTooltipStrings(recipe, drawable.getRecipeSlotsView(), 0, 0).stream().map(value -> value.getString()).toList());
    }

    private String components(DataComponentPatch patch) {
        var encoded = DataComponentPatch.CODEC.encodeStart(Minecraft.getInstance().level.registryAccess().createSerializationContext(JsonOps.INSTANCE), patch)
                .result().orElseThrow();
        if (!encoded.isJsonObject()) throw new IllegalStateException("components are not an object");
        return encoded.toString();
    }

    // This tooltip API is retained for compatibility with JEI 19.27.
    @SuppressWarnings({"deprecation", "removal"})
    private <T> List<String> tooltip(ITypedIngredient<T> ingredient) {
        return runtime.getIngredientManager().getIngredientRenderer(ingredient.getType()).getTooltip(ingredient.getIngredient(), TooltipFlag.NORMAL)
                .stream().map(value -> value.getString()).toList();
    }
}
