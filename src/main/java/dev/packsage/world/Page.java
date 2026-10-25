package dev.packsage.world;

import dev.packsage.knowledge.Evidence;

import java.util.List;

/** A page of matches; loadNote is the part of note about chunks that were not loaded, without cursor instructions. */
public record Page<T>(List<T> items, String nextCursor, Evidence.Coverage coverage, String note, String loadNote) {
    public Page {
        items = List.copyOf(items);
    }

    public Page(List<T> items, String nextCursor, Evidence.Coverage coverage, String note) {
        this(items, nextCursor, coverage, note, null);
    }
}
