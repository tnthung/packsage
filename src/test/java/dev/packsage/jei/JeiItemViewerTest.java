package dev.packsage.jei;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

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
}
