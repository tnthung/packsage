package dev.packsage.world;

import dev.packsage.knowledge.Evidence;

import java.util.List;

public record Page<T>(List<T> items, String nextCursor, Evidence.Coverage coverage, String note) {
    public Page {
        items = List.copyOf(items);
    }
}
