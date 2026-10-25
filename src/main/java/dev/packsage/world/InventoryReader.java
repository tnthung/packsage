package dev.packsage.world;

import net.minecraft.world.item.ItemStack;

import java.util.List;

public interface InventoryReader {
    String adapterId();

    List<InventoryInfo> inventories(LiveHolder holder);

    /** Returns null when the section is not this reader's own. */
    LiveInventory open(LiveHolder holder, String section);

    /** Returns null when this reader has nothing to add. */
    Section inspectItem(ItemStack stack);
}
