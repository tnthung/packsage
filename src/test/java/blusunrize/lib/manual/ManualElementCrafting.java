package blusunrize.lib.manual;

import blusunrize.lib.manual.utils.ManualRecipeRef;

public class ManualElementCrafting extends SpecialManualElement {
    private final ManualRecipeRef[][] recipeRows;

    public ManualElementCrafting(ManualRecipeRef[][] recipeRows) {
        this.recipeRows = recipeRows;
    }
}
