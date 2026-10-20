package blusunrize.lib.manual.utils;

import blusunrize.immersiveengineering.api.ManualHelper;
import blusunrize.lib.manual.PositionedItemStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.function.Consumer;

public class ManualRecipeRef {
    private final ItemStack output;
    private final PositionedItemStack[] layout;
    private final ResourceLocation recipeName;

    public ManualRecipeRef(ItemStack output) {
        this.output = output;
        layout = null;
        recipeName = null;
    }

    public ManualRecipeRef(PositionedItemStack[] layout) {
        output = null;
        this.layout = layout;
        recipeName = null;
    }

    public ManualRecipeRef(ResourceLocation recipeName) {
        output = null;
        layout = null;
        this.recipeName = recipeName;
    }

    public boolean isLayout() { return layout != null; }
    public PositionedItemStack[] getLayout() { return layout; }
    public boolean isResult() { return output != null; }
    public ItemStack getResult() { return output; }
    public boolean isRecipeName() { return recipeName != null; }
    public ResourceLocation getRecipeName() { return recipeName; }

    public <C extends RecipeInput, R extends Recipe<C>> void forEachMatchingRecipe(RecipeType<R> type, Consumer<R> action) {
        ManualHelper.getManual().noteRecipeMatchCall();
    }
}
