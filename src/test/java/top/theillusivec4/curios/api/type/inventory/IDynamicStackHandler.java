package top.theillusivec4.curios.api.type.inventory;

import net.minecraft.world.item.ItemStack;

public interface IDynamicStackHandler {
    int getSlots();

    ItemStack getStackInSlot(int index);
}
