package dev.packsage.world;

import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ItemGrouper {
    public static final int MAX_LOCATIONS = 8;

    private final Map<String, Group> groups = new LinkedHashMap<>();

    public void add(ItemPath location, ItemStack stack, boolean holdsItems) {
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        var group = groups.computeIfAbsent(id, key -> new Group(stack.getHoverName().getString()));
        group.count += stack.getCount();
        group.patches.add(stack.getComponentsPatch());
        if (group.locations.size() < MAX_LOCATIONS) {
            group.locations.add(location);
        }
        group.holdsItems |= holdsItems;
    }

    public List<ItemEntry> entries() {
        return groups.entrySet().stream()
                .map(entry -> new ItemEntry(entry.getKey(), entry.getValue().name, entry.getValue().count,
                        entry.getValue().patches.size(), entry.getValue().locations, entry.getValue().holdsItems))
                .sorted(Comparator.comparingLong(ItemEntry::count).reversed().thenComparing(ItemEntry::item))
                .toList();
    }

    private static final class Group {
        final String name;
        final Set<DataComponentPatch> patches = new HashSet<>();
        final List<ItemPath> locations = new ArrayList<>();
        long count;
        boolean holdsItems;

        Group(String name) {
            this.name = name;
        }
    }
}
