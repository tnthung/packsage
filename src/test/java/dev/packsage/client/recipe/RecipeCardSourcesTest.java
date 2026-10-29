package dev.packsage.client.recipe;

import dev.packsage.client.ModernUi;
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

    @Test void machineFallbackCapsChipsAndOverflowNames() {
        var machines = java.util.stream.IntStream.range(0, 15).mapToObj(i -> new RecipeCardSource.Machine(
                net.minecraft.world.item.ItemStack.EMPTY, List.of(net.minecraft.network.chat.Component.literal("Machine " + i)))).toList();
        RecipeCardSource source = new RecipeCardSource() {
            @Override public Optional<RecipeCard> card(RecipeCardModel model) { return Optional.empty(); }
            @Override public List<Machine> machines(RecipeCardModel model) { return machines; }
        };
        var combined = RecipeCardSources.first(List.of(source));
        assertEquals(machines, combined.machines(VIEWER));
        var chips = combined.machineChips(VIEWER);
        assertEquals(4, chips.size());
        assertEquals(machines.subList(0, 3), chips.subList(0, 3));
        assertEquals("+12", chips.getLast().label());
        assertEquals(10, chips.getLast().tooltip().size());
        assertEquals("Machine 3", chips.getLast().tooltip().getFirst().getString());
        assertEquals("Machine 10", chips.getLast().tooltip().get(7).getString());
        assertEquals("\u2026 and 4 more", chips.getLast().tooltip().get(8).getString());
        assertEquals("Click to open in JEI", chips.getLast().tooltip().getLast().getString());
        assertEquals(ModernUi.MUTED & 0xffffff, chips.getLast().tooltip().getLast().getStyle().getColor().getValue());
        var crafting = new RecipeCardModel(RecipeCardModel.Category.CRAFTING_SHAPED, 0, 0, List.of(), "", List.of());
        assertTrue(combined.machineChips(crafting).isEmpty());
        assertTrue(((RecipeCardSource) model -> Optional.empty()).machines(VIEWER).isEmpty());
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

    @Test void tooWideViewerRetainsItsFullCardForTheFallbackHover() {
        RecipeCardSource viewer = new RecipeCardSource() {
            @Override public Optional<RecipeCard> card(RecipeCardModel model) { return Optional.of(new Card(200)); }
            @Override public Optional<RecipeCard> card(RecipeCardModel model, int maxWidth) { return Optional.empty(); }
        };
        RecipeCardSource nativeSource = model -> Optional.of(new Card(80));
        assertEquals(200, RecipeCardSources.first(List.of(viewer, nativeSource)).card(NATIVE, 100).orElseThrow().width());
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
