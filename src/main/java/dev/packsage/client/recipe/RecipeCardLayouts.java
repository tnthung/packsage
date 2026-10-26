package dev.packsage.client.recipe;

import java.util.List;

final class RecipeCardLayouts {
    // Coordinates are relative to the JEI recipe area.
    static final int SLOT_SIZE = 18;
    static final int OUTPUT_SLOT_SIZE = 26;
    static final int SLOT_OFFSET = -1;
    static final int OUTPUT_SLOT_OFFSET = -5;
    static final int ARROW_WIDTH = 22;
    static final int ARROW_HEIGHT = 16;
    static final int CRAFTING_ARROW_X = 61, CRAFTING_ARROW_Y = 19;
    static final int SHAPELESS_X = 107, SHAPELESS_Y = 0, SHAPELESS_SIZE = 9;
    static final int COOKING_ARROW_X = 26, COOKING_ARROW_Y = 17, CAMPFIRE_ARROW_Y = 7;
    static final int FLAME_X = 1, FLAME_Y = 20, FLAME_SIZE = 14;
    static final int STONECUTTING_ARROW_X = 26, STONECUTTING_ARROW_Y = 9;
    static final int SMITHING_ARROW_X = 61, SMITHING_ARROW_Y = 6;
    static final int BREWING_BACKGROUND_X = 0, BREWING_BACKGROUND_Y = 1, BREWING_BACKGROUND_WIDTH = 64, BREWING_BACKGROUND_HEIGHT = 60;
    static final int BREWING_HEAT_X = 5, BREWING_HEAT_Y = 30, BREWING_HEAT_WIDTH = 18, BREWING_HEAT_HEIGHT = 4;
    static final int BREWING_BUBBLES_X = 9, BREWING_BUBBLES_Y = 1, BREWING_BUBBLES_WIDTH = 11, BREWING_BUBBLES_HEIGHT = 28;
    static final int BREWING_ARROW_X = 43, BREWING_ARROW_Y = 3, BREWING_ARROW_WIDTH = 7, BREWING_ARROW_HEIGHT = 27;
    static final int COOKING_TEXT_X = 82, EXPERIENCE_Y = 0, COOKING_TIME_Y = 46, CAMPFIRE_TIME_Y = 36;
    static final int BREWING_STEPS_X = 70, BREWING_STEPS_Y = 28;

    record Position(String role, int x, int y, boolean output) { }
    record Layout(int width, int height, String caption, List<Position> slots) { }

    static Layout layout(RecipeCardModel.Category category) {
        return switch (category) {
            case CRAFTING_SHAPED, CRAFTING_SHAPELESS -> new Layout(116, 54,
                    category == RecipeCardModel.Category.CRAFTING_SHAPED ? "Crafting table" : "Crafting table · shapeless",
                    List.of(new Position("slot-0", 1, 1, false), new Position("slot-1", 19, 1, false),
                            new Position("slot-2", 37, 1, false), new Position("slot-3", 1, 19, false),
                            new Position("slot-4", 19, 19, false), new Position("slot-5", 37, 19, false),
                            new Position("slot-6", 1, 37, false), new Position("slot-7", 19, 37, false),
                            new Position("slot-8", 37, 37, false), new Position("output", 95, 19, true)));
            case SMELTING, BLASTING, SMOKING -> new Layout(82, 54,
                    switch (category) { case SMELTING -> "Furnace"; case BLASTING -> "Blast furnace"; default -> "Smoker"; },
                    List.of(new Position("slot-0", 1, 1, false), new Position("fuel", 1, 37, false),
                            new Position("output", 61, 19, true)));
            case CAMPFIRE -> new Layout(82, 44, "Campfire", List.of(new Position("slot-0", 1, 1, false), new Position("output", 61, 9, true)));
            case STONECUTTING -> new Layout(82, 34, "Stonecutter", List.of(new Position("slot-0", 1, 9, false), new Position("output", 61, 9, true)));
            case SMITHING -> new Layout(108, 28, "Smithing table", List.of(new Position("slot-0", 1, 6, false),
                    new Position("slot-1", 19, 6, false), new Position("slot-2", 37, 6, false), new Position("output", 91, 6, true)));
            case BREWING -> new Layout(114, 61, "Brewing stand", List.of(new Position("bottle", 1, 37, false),
                    new Position("bottle", 24, 44, false), new Position("bottle", 47, 37, false),
                    new Position("reagent", 24, 3, false), new Position("output", 81, 3, true)));
        };
    }

    private RecipeCardLayouts() { }
}
