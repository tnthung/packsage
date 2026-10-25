package dev.packsage.world.minecraft;

import dev.packsage.world.KindFilter;
import dev.packsage.world.ServerWork;
import dev.packsage.world.Statistic;
import dev.packsage.world.StatisticReader;
import dev.packsage.world.StatisticUnit;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatFormatter;
import net.minecraft.stats.StatType;
import net.minecraft.stats.Stats;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The player's vanilla statistics. Each selected stat type's registry is walked by raw id, never the stat type's own
 * map, which the client thread extends while the stats screen is open; a stat is read only when it already exists, so
 * a read never creates one.
 */
public final class MinecraftStatistics implements StatisticReader {
    static final int STEP_ENTRIES = 1024;
    private static final int SAMPLE = 123456;

    @Override
    public String adapterId() {
        return MinecraftInventories.ADAPTER;
    }

    @Override
    public List<String> categories() {
        var categories = new ArrayList<String>();
        for (var type : statTypes()) {
            categories.add(category(BuiltInRegistries.STAT_TYPE.getKey(type)));
        }
        return categories;
    }

    @Override
    public ServerWork.Slice<List<Statistic>> statistics(ServerPlayer player, List<String> categories, KindFilter subjects) {
        return statistics(player.getStats(), categories, subjects);
    }

    ServerWork.Slice<List<Statistic>> statistics(StatsCounter counter, List<String> categories, KindFilter subjects) {
        var known = categories();
        for (var category : categories) {
            if (!known.contains(category)) {
                throw new IllegalArgumentException("unknown statistic category " + category + "; use one of " + String.join(", ", known));
            }
        }
        var selected = new ArrayList<StatType<?>>();
        for (var type : statTypes()) {
            if (categories.isEmpty() || categories.contains(category(BuiltInRegistries.STAT_TYPE.getKey(type)))) {
                selected.add(type);
            }
        }
        return new Walk(counter, selected, subjects);
    }

    /** The stored value's unit, recognised by formatting a sample value the way each vanilla formatter does. */
    static StatisticUnit unit(Stat<?> stat) {
        var sample = stat.format(SAMPLE);
        if (sample.equals(StatFormatter.DISTANCE.format(SAMPLE))) return StatisticUnit.DISTANCE;
        if (sample.equals(StatFormatter.TIME.format(SAMPLE))) return StatisticUnit.TIME;
        if (sample.equals(StatFormatter.DIVIDE_BY_TEN.format(SAMPLE))) return StatisticUnit.HEALTH;
        return StatisticUnit.COUNT;
    }

    static String category(ResourceLocation statTypeId) {
        return statTypeId.getNamespace().equals(ResourceLocation.DEFAULT_NAMESPACE) ? statTypeId.getPath() : statTypeId.toString();
    }

    private static double value(StatisticUnit unit, int raw) {
        return switch (unit) {
            case DISTANCE -> raw / 100.0;
            case TIME -> raw / 20.0;
            case HEALTH -> raw / 10.0;
            case COUNT -> raw;
        };
    }

    /** By raw id, which is fixed for the loaded content, unlike the key set's hash order. */
    private static List<StatType<?>> statTypes() {
        var registry = BuiltInRegistries.STAT_TYPE;
        var types = new ArrayList<StatType<?>>();
        for (var id = 0; id < registry.size(); id++) {
            var type = registry.byId(id);
            if (type != null) types.add(type);
        }
        return types;
    }

    private static final class Walk implements ServerWork.Slice<List<Statistic>> {
        private final StatsCounter counter;
        private final List<StatType<?>> types;
        private final KindFilter subjects;
        private final List<Statistic> rows = new ArrayList<>();
        private int type;
        private int entry;

        Walk(StatsCounter counter, List<StatType<?>> types, KindFilter subjects) {
            this.counter = counter;
            this.types = types;
            this.subjects = subjects;
        }

        @Override
        public boolean step() {
            if (type >= types.size()) return false;
            var current = types.get(type);
            var end = Math.min(entry + STEP_ENTRIES, current.getRegistry().size());
            for (; entry < end; entry++) {
                read(current, entry);
            }
            if (entry >= current.getRegistry().size()) {
                type++;
                entry = 0;
            }
            return type < types.size();
        }

        @Override
        public List<Statistic> result() {
            return List.copyOf(rows);
        }

        private <T> void read(StatType<T> statType, int rawId) {
            var registry = statType.getRegistry();
            var value = registry.byId(rawId);
            if (value == null) return;
            var key = registry.getKey(value);
            if (key == null) return;
            var id = key.toString();
            var stat = statType.contains(value) ? statType.get(value) : null;
            var raw = stat == null ? 0 : counter.getValue(stat);
            if (raw == 0 && !subjects.ids().contains(id)) return;
            var custom = statType == Stats.CUSTOM;
            var name = name(value, id, custom);
            Collection<String> tags = subjects.tags().isEmpty() ? List.of()
                    : registry.wrapAsHolder(value).tags().map(tag -> tag.location().toString()).toList();
            if (!subjects.matches(id, tags, name)) return;
            var category = category(BuiltInRegistries.STAT_TYPE.getKey(statType));
            var label = custom ? name : prefix(statType, category) + ": " + name;
            var unit = stat == null ? StatisticUnit.COUNT : unit(stat);
            rows.add(new Statistic(MinecraftInventories.ADAPTER, category, id, label, value(unit, raw), unit, raw));
        }

        /** A display name that is a sentence with placeholders, such as killed's "You killed %s %s", gives the category instead. */
        private static String prefix(StatType<?> statType, String category) {
            var displayName = statType.getDisplayName().getString();
            return displayName.contains("%") ? category : displayName;
        }

        private static String name(Object value, String id, boolean custom) {
            if (custom) return Component.translatable("stat." + id.replace(':', '.')).getString();
            if (value instanceof Block block) return block.getName().getString();
            if (value instanceof Item item) return item.getDescription().getString();
            if (value instanceof EntityType<?> entityType) return entityType.getDescription().getString();
            return id;
        }
    }
}
