package dev.packsage.client.recipe;

import java.util.List;

/**
 * A recipe card to draw. {@code recipeId} is the canonical recipe ID when known. {@code viewerHandle} is set only for
 * {@link Category#VIEWER} models, whose layout is drawn by the recipe viewer that produced the record; their slots
 * only carry the recorded stacks for the narrow fallback.
 */
public record RecipeCardModel(Category category, int width, int height, List<Slot> slots, String caption, List<Text> texts, int cookingTime,
                              String recipeId, String viewerHandle) {
    public RecipeCardModel(Category category, int width, int height, List<Slot> slots, String caption, List<Text> texts) {
        this(category, width, height, slots, caption, texts, 0, null, null);
    }

    public enum Category { CRAFTING_SHAPED, CRAFTING_SHAPELESS, SMELTING, BLASTING, SMOKING, CAMPFIRE, STONECUTTING, SMITHING, BREWING, VIEWER }

    public record Slot(String role, int x, int y, boolean output, List<Stack> stacks) { }

    public record Stack(Kind kind, String identity, String componentsJson, int count, String description) {
        public enum Kind { ITEM, TAG, CUSTOM, UNKNOWN }
    }

    public record Text(String text, int x, int y, boolean rightAligned) { }
}
