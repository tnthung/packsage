package dev.packsage.client.recipe;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

public interface RecipeCard {
    int width();
    int height();
    void draw(GuiGraphics graphics, int x, int y, int mouseX, int mouseY);
    Optional<Hover> hoverAt(int x, int y, double mouseX, double mouseY);

    sealed interface Hover permits Hover.Item, Hover.Text {
        record Item(ItemStack stack) implements Hover { }
        record Text(List<Component> lines) implements Hover { }
    }
}
