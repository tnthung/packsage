package dev.packsage.world;

import java.util.List;

/** One adapter's trees and entries, entries in the adapter's display order; {@code note} is non-null when partial. */
public record ProgressSnapshot(List<ProgressTree> trees, List<ProgressEntry> entries, String note) {
    public ProgressSnapshot {
        trees = List.copyOf(trees);
        entries = List.copyOf(entries);
    }
}
