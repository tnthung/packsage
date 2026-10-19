package it.hurts.sskirillss.relics.api.relics.abilities;

import it.hurts.sskirillss.relics.api.relics.abilities.stats.AbilityStatTemplate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AbilityTemplate {
    private final ItemStack stack;
    private final String id;

    public AbilityTemplate(ItemStack stack, String id) { this.stack = stack; this.id = id; }
    public Map<String, AbilityStatTemplate> getStats() {
        var stats = new LinkedHashMap<String, AbilityStatTemplate>();
        for (String stat : stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
                .getCompound("abilities").getCompound(id).getCompound("stats").getAllKeys()) {
            stats.put(stat, new AbilityStatTemplate(stat));
        }
        return stats;
    }
}
