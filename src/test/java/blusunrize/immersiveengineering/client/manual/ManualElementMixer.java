package blusunrize.immersiveengineering.client.manual;

import blusunrize.lib.manual.utils.ManualRecipeRef;
import net.minecraft.world.level.material.Fluid;

public final class ManualElementMixer extends ManualElementIECrafting {
    protected final Fluid[] fluids;

    public ManualElementMixer(Fluid[] fluids) {
        super(new ManualRecipeRef[0]);
        this.fluids = fluids;
    }
}
