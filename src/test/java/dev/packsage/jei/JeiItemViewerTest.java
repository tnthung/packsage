package dev.packsage.jei;

import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JeiItemViewerTest {
    @Test
    void noRuntimeLeavesItemsAndKeysInert() {
        JeiRuntimeHolder.unavailable();
        var viewer = new JeiItemViewer();
        var stack = new ItemStack(Items.GLASS);

        assertFalse(viewer.showRecipes(stack));
        assertFalse(viewer.showUses(stack));
        assertFalse(viewer.toggleBookmark(stack));
        assertFalse(viewer.matchesShowRecipes(GLFW.GLFW_KEY_R, 0));
        assertFalse(viewer.matchesShowUses(GLFW.GLFW_KEY_U, 0));
        assertFalse(viewer.matchesBookmark(GLFW.GLFW_KEY_A, 0));
    }
    @Test
    void usesIncludeTheCatalystRoleLikeJeisOwnShowUses() {
        var roles = new java.util.ArrayList<RecipeIngredientRole>();
        var factory = (IFocusFactory) java.lang.reflect.Proxy.newProxyInstance(IFocusFactory.class.getClassLoader(),
                new Class<?>[] { IFocusFactory.class }, (self, method, args) -> {
                    if (!method.getName().equals("createFocus")) throw new UnsupportedOperationException(method.getName());
                    roles.add((RecipeIngredientRole) args[0]);
                    return java.lang.reflect.Proxy.newProxyInstance(IFocus.class.getClassLoader(), new Class<?>[] { IFocus.class },
                            (focus, focusMethod, focusArgs) -> { throw new UnsupportedOperationException(focusMethod.getName()); });
                });

        var focuses = JeiItemViewer.usesFocuses(factory, new ItemStack(Items.FURNACE));

        assertEquals(2, focuses.size());
        assertEquals(java.util.List.of(RecipeIngredientRole.INPUT, RecipeIngredientRole.CATALYST), roles);
    }
}
