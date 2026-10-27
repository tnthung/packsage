package dev.packsage.client.recipe;

import java.util.Optional;

public interface RecipeCardSource {
    Optional<RecipeCard> card(RecipeCardModel model);

    /** A card that fits {@code maxWidth} when the source can adapt its size; by default the card is unchanged. */
    default Optional<RecipeCard> card(RecipeCardModel model, int maxWidth) {
        return card(model);
    }
}
