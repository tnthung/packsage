package dev.packsage.client.recipe;

import java.util.List;

public record RecipeCardModel(Category category, int width, int height, List<Slot> slots, String caption, List<Text> texts, int cookingTime) {
    public RecipeCardModel(Category category, int width, int height, List<Slot> slots, String caption, List<Text> texts) {
        this(category, width, height, slots, caption, texts, 0);
    }

    public enum Category { CRAFTING_SHAPED, CRAFTING_SHAPELESS, SMELTING, BLASTING, SMOKING, CAMPFIRE, STONECUTTING, SMITHING, BREWING }

    public record Slot(String role, int x, int y, boolean output, List<Stack> stacks) { }

    public record Stack(Kind kind, String identity, String componentsJson, int count, String description) {
        public enum Kind { ITEM, TAG, CUSTOM, UNKNOWN }
    }

    public record Text(String text, int x, int y, boolean rightAligned) { }
}
