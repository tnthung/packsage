package dev.packsage.world;

import dev.packsage.knowledge.Evidence;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Searches holders for matching items, one container per step, inspecting at most {@link #PAGE_CONTAINERS} containers
 * per page. Each holder's containers are walked depth first: the holder itself is container 0, then each item that
 * holds items, in entry order. Cursors are {@code <holder>} to start a fresh holder or {@code <holder>.<container>}
 * to resume at that 0-based container of the holder's walk; the containers before it are walked again without
 * reporting, since their matches belong to the earlier page. A page cut by the container bound or by an early stop
 * at the call's work budget says which in its note.
 */
public final class ItemSearch implements ServerWork.Slice<Page<ItemEntry>> {
    public static final int PAGE_CONTAINERS = 512;

    private record Container(ItemPath path, LiveHolder holder, Page<InventoryInfo> inventories) {
    }

    private final WorldRegistry registry;
    private final List<Map.Entry<PathRoot, LiveHolder>> holders;
    private final KindFilter filter;
    private final boolean includeNested;
    private final ItemGrouper grouper = new ItemGrouper();
    private final Set<String> notes = new LinkedHashSet<>();
    private final Deque<Container> pending = new ArrayDeque<>();
    private int holder;
    private boolean started;
    private int ordinal;
    private int replayUntil;
    private int containers;
    private boolean stopped;

    /** The cursor is null for the first page, or a {@code nextCursor} from an earlier page over the same holders. */
    public ItemSearch(WorldRegistry registry, List<Map.Entry<PathRoot, LiveHolder>> holders, KindFilter filter, boolean includeNested, String cursor) {
        this.registry = registry;
        this.holders = List.copyOf(holders);
        this.filter = filter;
        this.includeNested = includeNested;
        if (cursor != null) {
            var parts = cursor.split("[.]", -1);
            if (parts.length > 2) {
                throw new IllegalArgumentException("Invalid item search cursor: " + cursor);
            }
            this.holder = parseIndex(parts[0], cursor);
            this.replayUntil = parts.length == 2 ? parseIndex(parts[1], cursor) : 0;
        }
    }

    @Override
    public boolean step() {
        if (stopped) {
            return false;
        }
        if (pending.isEmpty()) {
            if (started) {
                holder++;
                started = false;
                ordinal = 0;
                replayUntil = 0;
            }
            if (holder >= holders.size() || containers >= PAGE_CONTAINERS) {
                return false;
            }
            var root = holders.get(holder);
            pending.push(new Container(new ItemPath(root.getKey(), List.of()), root.getValue(), registry.inventories(root.getValue())));
            started = true;
        }
        var replay = ordinal < replayUntil;
        if (!replay && containers >= PAGE_CONTAINERS) {
            return false;
        }
        var container = pending.pop();
        ordinal++;
        if (!replay) {
            containers++;
        }
        search(container, replay);
        return hasMore();
    }

    @Override
    public Page<ItemEntry> result() {
        var cursor = nextCursor();
        var all = new ArrayList<>(notes);
        if (cursor != null) {
            all.add(stopped ? "Stopped at the per-call work limit after searching " + containers + " containers; continue with the cursor"
                    : "Searched " + PAGE_CONTAINERS + " containers, the page limit; continue with the cursor");
        }
        var coverage = cursor == null && all.isEmpty() ? Evidence.Coverage.COMPLETE : Evidence.Coverage.PARTIAL;
        return new Page<>(grouper.entries(), cursor, coverage, all.isEmpty() ? null : String.join("; ", all));
    }

    /** Stops between containers; the cursor continues at the next one. */
    @Override
    public boolean stopEarly() {
        stopped = true;
        return true;
    }

    private boolean hasMore() {
        if (!pending.isEmpty()) {
            return ordinal < replayUntil || containers < PAGE_CONTAINERS;
        }
        return nextHolder() < holders.size() && containers < PAGE_CONTAINERS;
    }

    private String nextCursor() {
        if (!pending.isEmpty()) {
            // A stop while replaying still skips the containers the earlier page reported.
            return holder + "." + Math.max(ordinal, replayUntil);
        }
        return nextHolder() < holders.size() ? String.valueOf(nextHolder()) : null;
    }

    private int nextHolder() {
        return started ? holder + 1 : holder;
    }

    /** Reads one container and queues the items in it that hold items, so they are walked next, in entry order. */
    private void search(Container container, boolean replay) {
        if (container.inventories().note() != null) {
            note(replay, container.inventories().note());
        }
        var nested = new ArrayList<Container>();
        for (var inventory : container.inventories().items()) {
            var reader = registry.inventoryReader(inventory.id().adapter());
            LiveInventory live;
            try {
                live = reader == null ? null : reader.open(container.holder(), inventory.id().section());
            } catch (RuntimeException exception) {
                note(replay, "Reading " + inventory.id().text() + " failed: " + describe(exception));
                continue;
            }
            if (live == null) {
                note(replay, "Inventory " + inventory.id().text() + " could not be opened");
                continue;
            }
            for (var entry : live.entries()) {
                var stack = entry.getValue();
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                var kind = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                var location = container.path().child(new PathStep(inventory.id(), entry.getKey(), kind));
                var item = new LiveHolder.OfItem(stack);
                var inventories = registry.inventories(item);
                if (inventories.note() != null) {
                    note(replay, inventories.note());
                }
                var holdsItems = !inventories.items().isEmpty();
                if (!replay && filter.matches(kind, stack.getTags().map(tag -> tag.location().toString()).toList(), stack.getHoverName().getString())) {
                    grouper.add(location, stack, holdsItems);
                }
                if (!includeNested || !holdsItems) {
                    continue;
                }
                if (location.steps().size() >= ItemPath.MAX_STEPS) {
                    note(replay, "Items nested deeper than " + ItemPath.MAX_STEPS + " steps were not searched");
                } else {
                    nested.add(new Container(location, item, inventories));
                }
            }
        }
        for (var i = nested.size() - 1; i >= 0; i--) {
            pending.push(nested.get(i));
        }
    }

    private void note(boolean replay, String note) {
        if (!replay) {
            notes.add(note);
        }
    }

    private static int parseIndex(String text, String cursor) {
        try {
            var index = Integer.parseInt(text);
            if (index < 0 || !String.valueOf(index).equals(text)) {
                throw new IllegalArgumentException("Invalid item search cursor: " + cursor);
            }
            return index;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid item search cursor: " + cursor, exception);
        }
    }

    private static String describe(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
