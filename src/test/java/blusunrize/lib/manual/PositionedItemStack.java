package blusunrize.lib.manual;

import net.minecraft.world.item.ItemStack;

import java.util.List;

public record PositionedItemStack(List<ItemStack> displayList, int x, int y, String amount) {}
