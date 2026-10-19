package it.hurts.sskirillss.relics.api.relics.data.abilities;

import it.hurts.sskirillss.relics.api.relics.abilities.AbilityTemplate;
import it.hurts.sskirillss.relics.api.relics.data.AbilityStatData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public final class AbilityData {
    private final ItemStack stack;
    private final String id;

    public AbilityData(ItemStack stack, String id) { this.stack = stack; this.id = id; }
    public int getLevel() { return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompound("abilities").getCompound(id).getInt("points"); }
    public AbilityTemplate getTemplate() { return new AbilityTemplate(stack, id); }
    public AbilityStatData getStatData(String statId) {
        var customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (customData.getString("failStat").equals(statId)) throw new IllegalStateException("fixture stat failure");
        customData.putBoolean("initialized", true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(customData));
        return new AbilityStatData(stack, id, statId);
    }
}
