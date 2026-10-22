package dev.packsage.world;

import java.util.List;

public record ItemEntry(String item, String name, long count, int variants, List<ItemPath> locations, boolean holdsItems) {
    public ItemEntry {
        locations = List.copyOf(locations);
    }
}
