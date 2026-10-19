package it.hurts.sskirillss.relics.api.relics.data;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public final class LevelingData {
    private final ItemStack stack;

    public LevelingData(ItemStack stack) { this.stack = stack; }
    public int getLevel() { return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getInt("level"); }
    public double getExperience() { return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getDouble("experience"); }
    public int getRank() { return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getInt("rank"); }
    public int getPoints() { return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getInt("points"); }
}
