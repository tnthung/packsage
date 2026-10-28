package dev.packsage.client.recipe;

import dev.packsage.client.ModernUi;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public interface RecipeCardSource {
    Optional<RecipeCard> card(RecipeCardModel model);

    /** A card that fits {@code maxWidth} when the source can adapt its size; by default the card is unchanged. */
    default Optional<RecipeCard> card(RecipeCardModel model, int maxWidth) {
        return card(model);
    }

    record Machine(ItemStack stack, List<Component> tooltip, String name) {
        public Machine(ItemStack stack, List<Component> tooltip) { this(stack, tooltip, null); }
        public String label() { return name != null ? name : tooltip.isEmpty() ? stack.getHoverName().getString() : tooltip.getFirst().getString(); }
    }

    default List<Machine> machines(RecipeCardModel model) { return List.of(); }

    default boolean open(RecipeCardModel model) { return false; }

    default List<Machine> machineChips(RecipeCardModel model) {
        if (model.category() == RecipeCardModel.Category.CRAFTING_SHAPED || model.category() == RecipeCardModel.Category.CRAFTING_SHAPELESS
                || model.viewerHandle() != null && model.viewerHandle().startsWith("minecraft:crafting|")) return List.of();
        var machines = machines(model);
        if (machines.size() <= 3) return machines;
        var chips = new ArrayList<>(machines.subList(0, 3));
        chips.add(new Machine(null, overflowTooltip(machines.subList(3, machines.size())), "+" + (machines.size() - 3)));
        return List.copyOf(chips);
    }

    static List<Component> overflowTooltip(List<Machine> hidden) {
        var lines = new ArrayList<Component>();
        for (var machine : hidden.subList(0, Math.min(8, hidden.size()))) lines.add(Component.literal(machine.label()));
        if (hidden.size() > 8) lines.add(Component.literal("\u2026 and " + (hidden.size() - 8) + " more"));
        lines.add(Component.literal("Click to open in JEI").withColor(ModernUi.MUTED));
        return List.copyOf(lines);
    }

    /** Changes whenever cards made earlier may be stale or a new card could differ; prepared cards are rebuilt then. */
    default long generation() {
        return 0;
    }
}
