package dev.packsage.world;

import dev.packsage.knowledge.Evidence;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Searches holders for matching items, one holder per step, inspecting at most {@link #PAGE_CONTAINERS} containers per page. */
public final class ItemSearch implements ServerWork.Slice<Page<ItemEntry>> {
    public static final int PAGE_CONTAINERS = 512;

    private final WorldRegistry registry;
    private final List<Map.Entry<PathRoot, LiveHolder>> holders;
    private final KindFilter filter;
    private final boolean includeNested;
    private final ItemGrouper grouper = new ItemGrouper();
    private final Set<String> notes = new LinkedHashSet<>();
    private int next;
    private int containers;

    public ItemSearch(WorldRegistry registry, List<Map.Entry<PathRoot, LiveHolder>> holders, KindFilter filter, boolean includeNested, int startHolder) {
        this.registry = registry;
        this.holders = List.copyOf(holders);
        this.filter = filter;
        this.includeNested = includeNested;
        this.next = startHolder;
    }

    @Override
    public boolean step() {
        if (next >= holders.size() || containers >= PAGE_CONTAINERS) {
            return false;
        }
        var holder = holders.get(next++);
        containers++;
        search(new ItemPath(holder.getKey(), List.of()), holder.getValue(), inventories(holder.getValue()));
        return next < holders.size() && containers < PAGE_CONTAINERS;
    }

    @Override
    public Page<ItemEntry> result() {
        var cursor = next < holders.size() ? String.valueOf(next) : null;
        var coverage = cursor == null && notes.isEmpty() ? Evidence.Coverage.COMPLETE : Evidence.Coverage.PARTIAL;
        return new Page<>(grouper.entries(), cursor, coverage, notes.isEmpty() ? null : String.join("; ", notes));
    }

    private void search(ItemPath path, LiveHolder holder, List<InventoryInfo> inventories) {
        for (var inventory : inventories) {
            var reader = registry.inventoryReader(inventory.id().adapter());
            LiveInventory live;
            try {
                live = reader == null ? null : reader.open(holder, inventory.id().section());
            } catch (RuntimeException exception) {
                notes.add("Reading " + inventory.id().text() + " failed: " + describe(exception));
                continue;
            }
            if (live == null) {
                notes.add("Inventory " + inventory.id().text() + " could not be opened");
                continue;
            }
            for (var entry : live.entries()) {
                var stack = entry.getValue();
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                var kind = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                var location = path.child(new PathStep(inventory.id(), entry.getKey(), kind));
                var nested = inventories(new LiveHolder.OfItem(stack));
                var tags = stack.getTags().map(tag -> tag.location().toString()).toList();
                if (filter.matches(kind, tags, stack.getHoverName().getString())) {
                    grouper.add(location, stack, !nested.isEmpty());
                }
                if (includeNested && !nested.isEmpty()) {
                    descend(location, stack, nested);
                }
            }
        }
    }

    private void descend(ItemPath location, ItemStack stack, List<InventoryInfo> nested) {
        if (location.steps().size() >= ItemPath.MAX_STEPS) {
            notes.add("Items nested deeper than " + ItemPath.MAX_STEPS + " steps were not searched");
            return;
        }
        if (containers >= PAGE_CONTAINERS) {
            notes.add("Some nested containers were not searched after " + PAGE_CONTAINERS + " containers");
            return;
        }
        containers++;
        search(location, new LiveHolder.OfItem(stack), nested);
    }

    private List<InventoryInfo> inventories(LiveHolder holder) {
        var page = registry.inventories(holder);
        if (page.note() != null) {
            notes.add(page.note());
        }
        return page.items();
    }

    private static String describe(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
