package blusunrize.lib.manual.utils;

import blusunrize.lib.manual.PositionedItemStack;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public sealed interface ManualRecipeRef permits ManualRecipeRef.RecipeName, ManualRecipeRef.Result, ManualRecipeRef.Layout {
    boolean isRecipeName();
    String getRecipeName();
    boolean isResult();
    ItemStack getResult();
    boolean isLayout();
    List<PositionedItemStack> getLayout();

    static ManualRecipeRef recipeName(String id) {
        return new RecipeName(id);
    }

    static Result result(ItemStack item) {
        return new Result(item);
    }

    static ManualRecipeRef layout(List<PositionedItemStack> value) {
        return new Layout(value);
    }

    record RecipeName(String id) implements ManualRecipeRef {
        public boolean isRecipeName() { return true; }
        public String getRecipeName() { return id; }
        public boolean isResult() { return false; }
        public ItemStack getResult() { return null; }
        public boolean isLayout() { return false; }
        public List<PositionedItemStack> getLayout() { return List.of(); }
    }

    final class Result implements ManualRecipeRef {
        private ItemStack item;
        private Result(ItemStack item) { this.item = item; }
        public boolean isRecipeName() { return false; }
        public String getRecipeName() { return null; }
        public boolean isResult() { return true; }
        public ItemStack getResult() { return item; }
        public boolean isLayout() { return false; }
        public List<PositionedItemStack> getLayout() { return List.of(); }
    }

    record Layout(List<PositionedItemStack> cells) implements ManualRecipeRef {
        public boolean isRecipeName() { return false; }
        public String getRecipeName() { return null; }
        public boolean isResult() { return false; }
        public ItemStack getResult() { return null; }
        public boolean isLayout() { return true; }
        public List<PositionedItemStack> getLayout() { return cells; }
    }
}
