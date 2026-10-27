package dev.packsage.client.recipe;

import net.minecraft.world.item.ItemStack;

/** Opens an external item viewer such as JEI for a stack shown in chat. */
public interface ItemViewer {
    boolean showRecipes(ItemStack stack);
    boolean showUses(ItemStack stack);
    /** Returns false when bookmarks are unsupported. */
    boolean toggleBookmark(ItemStack stack);
    boolean matchesShowRecipes(int keyCode, int scanCode);
    boolean matchesShowUses(int keyCode, int scanCode);
    boolean matchesBookmark(int keyCode, int scanCode);
}
