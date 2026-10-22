package dev.packsage.world;

import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

/** Returns the live stacks; callers must not mutate them. */
public interface LiveInventory {
    ItemStack get(String entry);

    List<Map.Entry<String, ItemStack>> entries();
}
