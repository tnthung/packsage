package it.hurts.sskirillss.relics.api.relics.data;

import it.hurts.sskirillss.relics.api.relics.data.abilities.AbilityData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public final class AbilitiesData {
    private final ItemStack stack;

    public AbilitiesData(ItemStack stack) { this.stack = stack; }
    public java.util.Set<String> getAbilityIDs() {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompound("abilities").getAllKeys();
    }
    public AbilityData getAbilityData(String id) { return new AbilityData(stack, id); }
}
