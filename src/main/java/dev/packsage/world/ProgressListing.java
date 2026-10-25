package dev.packsage.world;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Filters, merges and pages progress snapshots off the server thread. */
public final class ProgressListing {
    static final String NO_MATCH_NOTE = "no progress entry matches";

    private ProgressListing() {
    }

    /**
     * A null {@code tree} means all trees, and one with a null adapter matches by local id alone. An empty
     * {@code statuses} means every status. {@code text} is a case-insensitive substring of an entry's title,
     * description or local id, and never matches hidden entries.
     */
    public record Filter(ProgressId tree, Set<ProgressStatus> statuses, String text) {
        public Filter {
            statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
            text = text == null ? "" : text;
        }

        boolean matchesTree(ProgressId id) {
            return tree == null || (tree.adapter() == null ? tree.local().equals(id.local()) : tree.equals(id));
        }

        boolean narrowsEntries() {
            return !statuses.isEmpty() || !text.isEmpty();
        }

        /** {@code needle} is {@code text} in lower case, computed once per page. */
        boolean matches(ProgressEntry entry, String needle) {
            var handle = entry.handle();
            if (!matchesTree(handle.tree()) || !statuses.isEmpty() && !statuses.contains(handle.status())) {
                return false;
            }
            if (text.isEmpty()) {
                return true;
            }
            if (handle.status() == ProgressStatus.HIDDEN) {
                return false;
            }
            return contains(handle.title(), needle) || contains(entry.description(), needle) || contains(handle.id().local(), needle);
        }

        private static boolean contains(String haystack, String needle) {
            return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
        }
    }

    /** One listed record: exactly one of {@code tree} and {@code entry} is non-null. */
    public record Item(ProgressTree tree, ProgressEntry entry) {
        public Item {
            if ((tree == null) == (entry == null)) {
                throw new IllegalArgumentException("A progress listing item is exactly one of a tree and an entry");
            }
        }
    }

    /**
     * Lists every matching tree, then every matching entry, in snapshot (adapter registration) order. When the filter
     * narrows entries by status or text, only trees holding a matching entry are listed. A page holds
     * {@link AreaScan#LIST_PAGE} items from {@code offset}; the cursor is the next offset.
     */
    public static Page<Item> page(List<ProgressSnapshot> snapshots, Filter filter, int offset) {
        return page(snapshots, filter, offset, null);
    }

    public static Page<Item> page(List<ProgressSnapshot> snapshots, Filter filter, int offset,
                                  java.util.function.ToIntFunction<Item> encodedBytes) {
        var needle = filter.text().toLowerCase(Locale.ROOT);
        var entries = new ArrayList<ProgressEntry>();
        var entryTrees = new HashSet<ProgressId>();
        for (var snapshot : snapshots) {
            for (var entry : snapshot.entries()) {
                if (filter.matches(entry, needle)) {
                    entries.add(entry);
                    entryTrees.add(entry.handle().tree());
                }
            }
        }
        var items = new ArrayList<Item>();
        for (var snapshot : snapshots) {
            for (var tree : snapshot.trees()) {
                if (filter.matchesTree(tree.id()) && (!filter.narrowsEntries() || entryTrees.contains(tree.id()))) {
                    items.add(new Item(tree, null));
                }
            }
        }
        for (var entry : entries) {
            items.add(new Item(null, entry));
        }
        var notes = snapshots.stream().map(ProgressSnapshot::note).filter(Objects::nonNull).toList();
        return ListingPages.slice(items, offset, notes, NO_MATCH_NOTE, encodedBytes);
    }
}
