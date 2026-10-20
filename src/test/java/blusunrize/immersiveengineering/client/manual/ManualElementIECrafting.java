package blusunrize.immersiveengineering.client.manual;

import blusunrize.lib.manual.SpecialManualElement;
import blusunrize.lib.manual.utils.ManualRecipeRef;

public class ManualElementIECrafting extends SpecialManualElement {
    protected final ManualRecipeRef[] stacks;

    public ManualElementIECrafting(ManualRecipeRef[] stacks) {
        this.stacks = stacks;
    }
}
