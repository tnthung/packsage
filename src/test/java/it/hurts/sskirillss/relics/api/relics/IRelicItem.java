package it.hurts.sskirillss.relics.api.relics;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

public interface IRelicItem {
    RelicData getRelicData(LivingEntity player, ItemStack stack);
}
