package dev.packsage.jei;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IRecipesGui;

public final class JeiViewer {
    private final IRecipesGui gui;
    private final Function<JeiIndex.RecipeRef, Optional<JeiRuntimeIndex.Resolved<?>>> resolve;

    JeiViewer(IRecipesGui gui, Function<JeiIndex.RecipeRef, Optional<JeiRuntimeIndex.Resolved<?>>> resolve) {
        this.gui = gui;
        this.resolve = resolve;
    }

    @SuppressWarnings("unchecked")
    public boolean showRecipe(JeiIndex.RecipeRef ref) {
        var recipe = resolve.apply(ref).orElse(null);
        if (recipe == null) return false;
        gui.showRecipes((IRecipeCategory<Object>) recipe.category(), List.of((Object) recipe.recipe()), List.of());
        return true;
    }
}
