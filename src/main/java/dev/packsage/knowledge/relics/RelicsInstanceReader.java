package dev.packsage.knowledge.relics;

import dev.packsage.knowledge.Evidence;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

public final class RelicsInstanceReader {
    public RelicsInstanceReader(boolean present, boolean compatible, String version) { }

    public Evidence.JsonObject read(LivingEntity player, ItemStack stack) {
        return null;
    }

    Evidence.JsonObject project(LivingEntity player, ItemStack stack, Object relicItem) {
        return null;
    }
}
