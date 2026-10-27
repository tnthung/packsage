package dev.packsage.jei;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.drawable.IScalableDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import mezz.jei.api.ingredients.IIngredientSupplier;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeCatalystLookup;
import mezz.jei.api.recipe.IRecipeCategoriesLookup;
import mezz.jei.api.recipe.IRecipeLookup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.advanced.IRecipeButtonControllerFactory;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * A JEI runtime over plain recipe objects, for exercising {@link JeiRuntimeIndex} without Minecraft. JEI's API loads outside the
 * game layer in unit tests, so anything whose methods name Minecraft types is a compiled class here rather than a proxy.
 */
public final class FakeJeiRuntime {
    private record Entry(IRecipeCategory<Object> category, boolean hidden, List<Object> recipes) { }

    private final List<Entry> entries = new ArrayList<>();
    /** Names a category reports through getRegistryName; other recipes use JEI's default, the holder ID. */
    public final Map<Object, ResourceLocation> registryNames = new IdentityHashMap<>();
    /** Recipes JEI hides inside otherwise visible categories. */
    public final Set<Object> hiddenRecipes = Collections.newSetFromMap(new IdentityHashMap<>());

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (self, method, args) -> switch (method.getName()) {
            case "equals" -> self == args[0];
            case "hashCode" -> System.identityHashCode(self);
            case "toString" -> type.getSimpleName();
            default -> handler.invoke(self, method, args);
        });
    }

    public IRecipeCategory<Object> category(String uid, boolean hidden, Object... recipes) {
        var type = new RecipeType<>(ResourceLocation.parse(uid), Object.class);
        var category = new IRecipeCategory<Object>() {
            @Override public RecipeType<Object> getRecipeType() { return type; }
            @Override public Component getTitle() { return Component.literal(uid); }
            @Override public IDrawable getIcon() { return null; }
            @Override public void setRecipe(IRecipeLayoutBuilder builder, Object recipe, IFocusGroup focuses) { throw new UnsupportedOperationException(); }
            @Override public ResourceLocation getRegistryName(Object recipe) {
                return registryNames.containsKey(recipe) ? registryNames.get(recipe) : recipe instanceof RecipeHolder<?> holder ? holder.id() : null;
            }
        };
        entries.add(new Entry(category, hidden, new ArrayList<>(List.of(recipes))));
        return category;
    }

    private Optional<Entry> entry(RecipeType<?> type) {
        return entries.stream().filter(entry -> entry.category().getRecipeType() == type).findFirst();
    }

    private IRecipeCategoriesLookup categories(boolean hidden) {
        return proxy(IRecipeCategoriesLookup.class, (self, method, args) -> switch (method.getName()) {
            case "includeHidden" -> categories(true);
            case "limitFocus", "limitTypes" -> self;
            case "get" -> entries.stream().filter(entry -> hidden || !entry.hidden()).map(entry -> (IRecipeCategory<?>) entry.category());
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    @SuppressWarnings("unchecked")
    private <R> IRecipeLookup<R> recipes(RecipeType<R> type, boolean hidden) {
        return proxy(IRecipeLookup.class, (self, method, args) -> switch (method.getName()) {
            case "includeHidden" -> recipes(type, true);
            case "limitFocus" -> self;
            case "get" -> entry(type).map(entry -> entry.recipes().stream()
                    .filter(recipe -> hidden || !entry.hidden() && !hiddenRecipes.contains(recipe))).orElse(Stream.empty());
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private final class Manager implements IRecipeManager {
        @Override public <R> IRecipeLookup<R> createRecipeLookup(RecipeType<R> type) { return recipes(type, false); }
        @Override public IRecipeCategoriesLookup createRecipeCategoryLookup() { return categories(false); }
        @Override public <T> IRecipeCategory<T> getRecipeCategory(RecipeType<T> type) { throw new UnsupportedOperationException(); }
        @Override public IRecipeCatalystLookup createRecipeCatalystLookup(RecipeType<?> type) { throw new UnsupportedOperationException(); }
        @Override public <T> void hideRecipes(RecipeType<T> type, Collection<T> recipes) { throw new UnsupportedOperationException(); }
        @Override public <T> void unhideRecipes(RecipeType<T> type, Collection<T> recipes) { throw new UnsupportedOperationException(); }
        @Override public <T> void addRecipes(RecipeType<T> type, List<T> recipes) { throw new UnsupportedOperationException(); }
        @Override public void hideRecipeCategory(RecipeType<?> type) { throw new UnsupportedOperationException(); }
        @Override public void unhideRecipeCategory(RecipeType<?> type) { throw new UnsupportedOperationException(); }
        @Override public <T> IRecipeLayoutDrawable<T> createRecipeLayoutDrawableOrShowError(IRecipeCategory<T> category, T recipe, IFocusGroup focuses) { throw new UnsupportedOperationException(); }
        @Override public <T> Optional<IRecipeLayoutDrawable<T>> createRecipeLayoutDrawable(IRecipeCategory<T> category, T recipe, IFocusGroup focuses) { throw new UnsupportedOperationException(); }
        @Override public <T> Optional<IRecipeLayoutDrawable<T>> createRecipeLayoutDrawable(IRecipeCategory<T> category, T recipe, IFocusGroup focuses, IScalableDrawable background, int borderPadding) { throw new UnsupportedOperationException(); }
        @Override public IRecipeSlotDrawable createRecipeSlotDrawable(RecipeIngredientRole role, List<Optional<ITypedIngredient<?>>> ingredients, Set<Integer> focusedIngredients, int ingredientCycleOffset) { throw new UnsupportedOperationException(); }
        @Override public <T> IIngredientSupplier getRecipeIngredients(IRecipeCategory<T> category, T recipe) { throw new UnsupportedOperationException(); }
        @Override public <T> Optional<RecipeType<T>> getRecipeType(ResourceLocation uid, Class<? extends T> recipeClass) { throw new UnsupportedOperationException(); }
        @Override public Optional<RecipeType<?>> getRecipeType(ResourceLocation uid) { throw new UnsupportedOperationException(); }
        @Override public List<IRecipeButtonControllerFactory> getRecipeButtonControllerFactories() { throw new UnsupportedOperationException(); }
    }

    public IJeiRuntime runtime() {
        var manager = new Manager();
        return proxy(IJeiRuntime.class, (self, method, args) -> switch (method.getName()) {
            case "getRecipeManager" -> manager;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    /** An index current for the holder's present generation. */
    public JeiRuntimeIndex index() {
        return new JeiRuntimeIndex(runtime(), JeiRuntimeHolder.generation(), () -> true);
    }
}
