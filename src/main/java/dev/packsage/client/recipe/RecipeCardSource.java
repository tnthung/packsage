package dev.packsage.client.recipe;

import java.util.Optional;

public interface RecipeCardSource {
    Optional<RecipeCard> card(RecipeCardModel model);
}
