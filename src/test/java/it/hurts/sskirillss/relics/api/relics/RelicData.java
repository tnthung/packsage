package it.hurts.sskirillss.relics.api.relics;

import it.hurts.sskirillss.relics.api.relics.data.AbilitiesData;
import it.hurts.sskirillss.relics.api.relics.data.LevelingData;
import it.hurts.sskirillss.relics.api.relics.data.RelicStatisticData;
import net.minecraft.world.item.ItemStack;

public final class RelicData {
    private final ItemStack stack;

    public RelicData(ItemStack stack) { this.stack = stack; }
    public LevelingData getLevelingData() { return new LevelingData(stack); }
    public AbilitiesData getAbilitiesData() { return new AbilitiesData(stack); }
    public RelicStatisticData getStatisticData() { return new RelicStatisticData(); }
    public RelicTemplate getTemplate() { return new RelicTemplate(); }
}
