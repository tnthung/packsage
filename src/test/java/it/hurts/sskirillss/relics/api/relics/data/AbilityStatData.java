package it.hurts.sskirillss.relics.api.relics.data;

import it.hurts.sskirillss.relics.api.relics.abilities.stats.AbilityStatTemplate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public final class AbilityStatData {
    private final ItemStack stack;
    private final String abilityId;
    private final String id;

    public AbilityStatData(ItemStack stack, String abilityId, String id) { this.stack = stack; this.abilityId = abilityId; this.id = id; }
    public String getId() { return id; }
    public AbilityStatTemplate getTemplate() { return new AbilityStatTemplate(id); }
    public int getQuality() { return data().getInt("quality"); }
    public double getValue() { return data().getDouble("value"); }
    public double getUnscaledValue() { return data().getDouble("value"); }
    private net.minecraft.nbt.CompoundTag data() {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompound("abilities")
                .getCompound(abilityId).getCompound("stats").getCompound(id);
    }
}
