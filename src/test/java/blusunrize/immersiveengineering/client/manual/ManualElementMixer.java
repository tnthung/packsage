package blusunrize.immersiveengineering.client.manual;

import blusunrize.lib.manual.SpecialManualElement;
import net.minecraft.world.level.material.Fluid;

public final class ManualElementMixer extends SpecialManualElement {
    protected final Fluid[] fluids;

    public ManualElementMixer(Fluid[] fluids) {
        this.fluids = fluids;
    }
}
