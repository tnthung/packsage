package dev.packsage.client.recipe;

import net.minecraft.client.gui.GuiGraphics;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RecipeCardSourcesTest {
    private static final RecipeCardModel VIEWER = new RecipeCardModel(RecipeCardModel.Category.VIEWER, 0, 0, List.of(), "Mixing",
            List.of(), 0, null, "create:mixing|create:mixing/brass");
    private static final RecipeCardModel NATIVE = new RecipeCardModel(RecipeCardModel.Category.STONECUTTING, 82, 34, List.of(), "Stonecutter",
            List.of(), 0, "minecraft:stone_slab", null);

    private record Card(int width) implements RecipeCard {
        @Override public int height() { return 10; }
        @Override public void draw(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) { }
        @Override public Optional<Hover> hoverAt(int x, int y, double mouseX, double mouseY) { return Optional.empty(); }
    }

    @Test void firstNonEmptyWins() {
        var widths = new ArrayList<Integer>();
        RecipeCardSource empty = model -> Optional.empty();
        RecipeCardSource first = new RecipeCardSource() {
            @Override public Optional<RecipeCard> card(RecipeCardModel model) { return Optional.of(new Card(1)); }
            @Override public Optional<RecipeCard> card(RecipeCardModel model, int maxWidth) {
                widths.add(maxWidth);
                return Optional.of(new Card(maxWidth));
            }
        };
        RecipeCardSource second = model -> Optional.of(new Card(2));
        var source = RecipeCardSources.first(List.of(empty, first, second));
        assertEquals(120, source.card(NATIVE, 120).orElseThrow().width());
        assertEquals(List.of(120), widths);
        assertEquals(1, source.card(NATIVE).orElseThrow().width());
        assertTrue(RecipeCardSources.first(List.of(empty)).card(NATIVE, 120).isEmpty());
    }

    @Test void generationChangesWithAnySource() {
        long[] generation = { 3 };
        RecipeCardSource viewer = new RecipeCardSource() {
            @Override public Optional<RecipeCard> card(RecipeCardModel model) { return Optional.empty(); }
            @Override public long generation() { return generation[0]; }
        };
        RecipeCardSource nativeSource = model -> Optional.empty();
        var source = RecipeCardSources.first(List.of(viewer, nativeSource));
        long before = source.generation();
        assertEquals(before, source.generation());
        generation[0] = 4;
        assertNotEquals(before, source.generation());
        assertEquals(0, nativeSource.generation());
    }

    @Test void nativeFallbackWhenViewerEmpty() {
        RecipeCardSource viewer = model -> model.category() == RecipeCardModel.Category.VIEWER ? Optional.of(new Card(3)) : Optional.empty();
        RecipeCardSource nativeSource = model -> model.category() == RecipeCardModel.Category.VIEWER ? Optional.empty() : Optional.of(new Card(4));
        var source = RecipeCardSources.first(List.of(viewer, nativeSource));
        assertEquals(4, source.card(NATIVE, 200).orElseThrow().width());
        assertEquals(3, source.card(VIEWER, 200).orElseThrow().width());
        assertTrue(RecipeCardSources.first(List.of(nativeSource)).card(VIEWER, 200).isEmpty());
    }
}
