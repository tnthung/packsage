package dev.packsage.world;

import dev.packsage.knowledge.Evidence;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public interface InventoryReader {
    String adapterId();

    List<InventoryInfo> inventories(LiveHolder holder);

    /** Returns null when the section is not this reader's own. */
    LiveInventory open(LiveHolder holder, String section);

    /** Returns null when this reader has nothing to add. */
    Section inspectItem(ItemStack stack);

    /** Facts about one entry's slot, such as its equipment slot; null when there are none. */
    default Evidence.JsonObject entryFacts(LiveHolder holder, String section, String entry) {
        return null;
    }
}
