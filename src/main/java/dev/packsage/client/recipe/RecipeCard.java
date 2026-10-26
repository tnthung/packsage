package dev.packsage.client.recipe;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

public interface RecipeCard {
    int width();
    int height();
    void draw(GuiGraphics graphics, int x, int y, int mouseX, int mouseY);
    Optional<ItemStack> stackAt(int x, int y, double mouseX, double mouseY);
}
