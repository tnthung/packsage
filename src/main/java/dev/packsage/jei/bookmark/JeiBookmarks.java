package dev.packsage.jei.bookmark;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.packsage.jei.JeiIndex;
import dev.packsage.jei.JeiRuntimeIndex;
import dev.packsage.knowledge.Toolbox;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IBookmarkManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** The only place that talks to JEI's bookmark manager (JEI 19.53 and newer). Call on the client thread. */
public interface JeiBookmarks {
    enum Action { ADD, REMOVE, CHECK }

    /** Applies the action and reports whether the item is bookmarked afterwards. */
    boolean item(Action action, String itemId, String componentsJson);

    /** Applies the action and reports whether the recipe is bookmarked afterwards. */
    boolean recipe(Action action, JeiIndex.RecipeRef ref);

    static JeiBookmarks of(IJeiRuntime runtime, JeiRuntimeIndex index) {
        return new JeiBookmarks() {
            @Override public boolean item(Action action, String itemId, String componentsJson) {
                var registries = Minecraft.getInstance().level.registryAccess();
                var id = ResourceLocation.tryParse(itemId);
                var item = id == null ? null : registries.lookupOrThrow(Registries.ITEM).get(ResourceKey.create(Registries.ITEM, id)).orElse(null);
                if (item == null) throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "item is unknown");
                var patch = DataComponentPatch.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE),
                        JsonParser.parseString(componentsJson)).result()
                        .orElseThrow(() -> new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "item components are invalid"));
                var typed = runtime.getIngredientManager().createTypedIngredient(VanillaTypes.ITEM_STACK, new ItemStack(item, 1, patch))
                        .orElseThrow(() -> new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "JEI does not list this item"));
                var manager = runtime.getBookmarkManager();
                switch (action) {
                    case ADD -> manager.add(typed);
                    case REMOVE -> manager.remove(typed);
                    case CHECK -> { }
                }
                return manager.contains(typed);
            }

            @Override public boolean recipe(Action action, JeiIndex.RecipeRef ref) {
                var resolved = index.resolve(ref).orElseThrow(() -> new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE, "JEI recipe unavailable"));
                return apply(action, runtime.getBookmarkManager(), resolved.category(), resolved.recipe());
            }
        };
    }

    /** Adds the stack to the bookmarks, or removes it when already there; false when JEI does not list the stack. */
    static boolean toggle(IJeiRuntime runtime, ItemStack stack) {
        var typed = runtime.getIngredientManager().createTypedIngredient(VanillaTypes.ITEM_STACK, stack.copyWithCount(1));
        if (typed.isEmpty()) return false;
        var manager = runtime.getBookmarkManager();
        if (manager.contains(typed.get())) manager.remove(typed.get());
        else manager.add(typed.get());
        return true;
    }

    private static <R> boolean apply(Action action, IBookmarkManager manager, IRecipeCategory<R> category, Object recipe) {
        var type = category.getRecipeType();
        var typed = type.getRecipeClass().cast(recipe);
        switch (action) {
            case ADD -> manager.add(type, typed);
            case REMOVE -> manager.remove(type, typed);
            case CHECK -> { }
        }
        return manager.contains(type, typed);
    }
}
