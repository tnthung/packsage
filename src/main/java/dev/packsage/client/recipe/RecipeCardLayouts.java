package dev.packsage.client.recipe;

import java.util.List;

final class RecipeCardLayouts {
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
