package dev.packsage.world;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.function.Function;

public final class PathResolver {
    public enum Failure { NONE, ROOT_GONE, UNAVAILABLE, STALE }

    /** On failure, holder and stack are the last resolved ones and failedStep is the zero-based step that failed. */
    public record Resolution(LiveHolder holder, ItemStack stack, int failedStep, Failure failure) {
    }

    private final WorldRegistry registry;
    private final Function<PathRoot, LiveHolder> roots;

    public PathResolver(WorldRegistry registry, Function<PathRoot, LiveHolder> roots) {
        this.registry = registry;
        this.roots = roots;
    }

    public Resolution resolve(ItemPath path) {
        var holder = roots.apply(path.root());
        if (holder == null) {
            return new Resolution(null, null, 0, Failure.ROOT_GONE);
        }
        ItemStack stack = null;
        var steps = path.steps();
        for (var i = 0; i < steps.size(); i++) {
            var step = steps.get(i);
            var reader = registry.inventoryReader(step.inventory().adapter());
            var inventory = reader == null ? null : open(reader, holder, step.inventory().section());
            if (inventory == null) {
                return new Resolution(holder, stack, i, Failure.UNAVAILABLE);
            }
            var entry = inventory.get(step.entry());
            if (entry == null || entry.isEmpty() || !step.kind().equals(BuiltInRegistries.ITEM.getKey(entry.getItem()).toString())) {
                return new Resolution(holder, stack, i, Failure.STALE);
            }
            stack = entry;
            holder = new LiveHolder.OfItem(entry);
        }
        return new Resolution(holder, stack, -1, Failure.NONE);
    }

    private static LiveInventory open(InventoryReader reader, LiveHolder holder, String section) {
        try {
            return reader.open(holder, section);
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
