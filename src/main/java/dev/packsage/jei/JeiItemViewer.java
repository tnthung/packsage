package dev.packsage.jei;

import com.mojang.blaze3d.platform.InputConstants;
import dev.packsage.client.JeiSupport;
import dev.packsage.client.recipe.ItemViewer;
import dev.packsage.jei.bookmark.JeiBookmarks;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

public final class JeiItemViewer implements ItemViewer {
    private final boolean bookmarks;

    public JeiItemViewer() {
        this(new JeiSupport().installedVersion().filter(JeiSupport::bookmarksSupported).isPresent());
    }

    JeiItemViewer(boolean bookmarks) {
        this.bookmarks = bookmarks;
    }

    @Override
    public boolean showRecipes(ItemStack stack) {
        var runtime = JeiRuntimeHolder.current();
        if (runtime.isEmpty()) return false;
        runtime.get().getRecipesGui().show(runtime.get().getJeiHelpers().getFocusFactory()
                .createFocus(RecipeIngredientRole.OUTPUT, VanillaTypes.ITEM_STACK, stack));
        return true;
    }

    @Override
    public boolean showUses(ItemStack stack) {
        var runtime = JeiRuntimeHolder.current();
        if (runtime.isEmpty()) return false;
        runtime.get().getRecipesGui().show(runtime.get().getJeiHelpers().getFocusFactory()
                .createFocus(RecipeIngredientRole.INPUT, VanillaTypes.ITEM_STACK, stack));
        return true;
    }

    @Override
    public boolean toggleBookmark(ItemStack stack) {
        var runtime = JeiRuntimeHolder.current();
        return bookmarks && runtime.isPresent() && JeiBookmarks.toggle(runtime.get(), stack);
    }

    @Override
    public boolean matchesShowRecipes(int keyCode, int scanCode) {
        return JeiRuntimeHolder.current().map(runtime -> runtime.getKeyMappings().getShowRecipe()
                .isActiveAndMatches(InputConstants.getKey(keyCode, scanCode))).orElse(false);
    }

    @Override
    public boolean matchesShowUses(int keyCode, int scanCode) {
        return JeiRuntimeHolder.current().map(runtime -> runtime.getKeyMappings().getShowUses()
                .isActiveAndMatches(InputConstants.getKey(keyCode, scanCode))).orElse(false);
    }

    @Override
    public boolean matchesBookmark(int keyCode, int scanCode) {
        return bookmarks && JeiRuntimeHolder.current().isPresent() && keyCode == GLFW.GLFW_KEY_A;
    }
}
