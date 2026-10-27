package dev.packsage.client.recipe;

import java.util.List;
import java.util.Optional;

public final class RecipeCardSources {
    /** A source that asks each source in order and returns the first card produced. */
    public static RecipeCardSource first(List<RecipeCardSource> sources) {
        var ordered = List.copyOf(sources);
        return new RecipeCardSource() {
            @Override
            public Optional<RecipeCard> card(RecipeCardModel model) {
                for (var source : ordered) {
                    var card = source.card(model);
                    if (card.isPresent()) return card;
                }
                return Optional.empty();
            }

            @Override
            public Optional<RecipeCard> card(RecipeCardModel model, int maxWidth) {
                for (var source : ordered) {
                    var card = source.card(model, maxWidth);
                    if (card.isPresent()) return card;
                }
                return Optional.empty();
            }

            @Override
            public long generation() {
                long generation = 0;
                for (var source : ordered) generation = generation * 31 + source.generation();
                return generation;
            }
        };
    }

    private RecipeCardSources() { }
}
