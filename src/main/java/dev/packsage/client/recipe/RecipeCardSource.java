package dev.packsage.client.recipe;

import java.util.Optional;

public interface RecipeCardSource {
    Optional<RecipeCard> card(RecipeCardModel model);

    /** A card that fits {@code maxWidth} when the source can adapt its size; by default the card is unchanged. */
    default Optional<RecipeCard> card(RecipeCardModel model, int maxWidth) {
        return card(model);
    }

    /** Changes whenever cards made earlier may be stale or a new card could differ; prepared cards are rebuilt then. */
    default long generation() {
        return 0;
    }
}
