package dev.packsage.world.minecraft;

import dev.packsage.world.KindFilter;
import dev.packsage.world.ServerWork;
import dev.packsage.world.Statistic;
import dev.packsage.world.StatisticReader;
import dev.packsage.world.StatisticUnit;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The player's vanilla statistics, enumerated from the counter's own server-owned map. A {@link StatType}'s map is
 * never read or extended, because the client thread extends it while the stats screen is open; a named subject the
 * counter does not hold becomes a zero row by name, so a read never creates a stat.
 */
public final class MinecraftStatistics implements StatisticReader {
    static final int STEP_ENTRIES = 1024;
    static final String UNREADABLE_NOTE = "the statistics counter could not be read";
    private static final int SAMPLE = 123456;
    private static final Field STATS;
    private static final String STATS_FAILURE;

    static {
        Field field = null;
        String failure = null;
        try {
            field = StatsCounter.class.getDeclaredField("stats");
            field.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            field = null;
            failure = e.toString();
        }
        STATS = field;
        STATS_FAILURE = failure;
    }

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
        return statistics(() -> counted(counter), categories, subjects);
    }

    /** {@code counted} is the counter's map, read on the first step; it throws when the map cannot be reached. */
    ServerWork.Slice<List<Statistic>> statistics(Supplier<Object2IntMap<Stat<?>>> counted, List<String> categories, KindFilter subjects) {
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
        return new Walk(counted, selected, subjects);
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

    @SuppressWarnings("unchecked")
    private static Object2IntMap<Stat<?>> counted(StatsCounter counter) {
        if (STATS == null) throw new IllegalStateException(UNREADABLE_NOTE + ": " + STATS_FAILURE);
        try {
            return (Object2IntMap<Stat<?>>) STATS.get(counter);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(UNREADABLE_NOTE + ": " + e, e);
        }
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

    private record Counted(Stat<?> stat, int raw, int typeId, int valueId) {
    }

    private record Row(int typeId, int valueId, Statistic statistic) {
    }

    private static final Comparator<Row> ORDER = Comparator.comparingInt(Row::typeId).thenComparingInt(Row::valueId);

    /** The first step copies the counter under its lock; later steps build rows, then the named zero rows. */
    private static final class Walk implements ServerWork.Slice<List<Statistic>> {
        private final Supplier<Object2IntMap<Stat<?>>> source;
        private final List<StatType<?>> types;
        private final KindFilter subjects;
        private final List<Row> rows = new ArrayList<>();
        private final Set<List<Object>> present = new HashSet<>();
        private List<Counted> counted;
        private int next;

        Walk(Supplier<Object2IntMap<Stat<?>>> source, List<StatType<?>> types, KindFilter subjects) {
            this.source = source;
            this.types = types;
            this.subjects = subjects;
        }

        @Override
        public boolean step() {
            if (counted == null) {
                counted = copy(source.get());
                return true;
            }
            if (next < counted.size()) {
                var end = Math.min(next + STEP_ENTRIES, counted.size());
                for (; next < end; next++) {
                    read(counted.get(next));
                }
                return true;
            }
            for (var type : types) {
                for (var id : subjects.ids()) {
                    zero(type, id);
                }
            }
            return false;
        }

        @Override
        public List<Statistic> result() {
            return rows.stream().sorted(ORDER).map(Row::statistic).toList();
        }

        private List<Counted> copy(Object2IntMap<Stat<?>> map) {
            var selected = Set.copyOf(types);
            var copied = new ArrayList<Counted>();
            // The map is a fastutil synchronized map that locks on itself, so iteration holds the same monitor.
            synchronized (map) {
                for (var entry : map.object2IntEntrySet()) {
                    var stat = entry.getKey();
                    if (selected.contains(stat.getType())) {
                        copied.add(new Counted(stat, entry.getIntValue(), BuiltInRegistries.STAT_TYPE.getId(stat.getType()), valueId(stat)));
                    }
                }
            }
            copied.sort(Comparator.comparingInt(Counted::typeId).thenComparingInt(Counted::valueId));
            return copied;
        }

        private static <T> int valueId(Stat<T> stat) {
            return stat.getType().getRegistry().getId(stat.getValue());
        }

        private void read(Counted entry) {
            read(entry.stat(), entry.raw(), entry.typeId(), entry.valueId());
        }

        private <T> void read(Stat<T> stat, int raw, int typeId, int valueId) {
            var statType = stat.getType();
            var key = statType.getRegistry().getKey(stat.getValue());
            if (key == null) return;
            var id = key.toString();
            present.add(List.of(statType, id));
            if (raw == 0 && !subjects.ids().contains(id)) return;
            var unit = unit(stat);
            add(statType, stat.getValue(), id, raw, unit, typeId, valueId);
        }

        private <T> void zero(StatType<T> statType, String id) {
            if (present.contains(List.of(statType, id))) return;
            var key = ResourceLocation.tryParse(id);
            if (key == null) return;
            var registry = statType.getRegistry();
            var value = registry.getOptional(key);
            if (value.isEmpty()) return;
            present.add(List.of(statType, id));
            add(statType, value.get(), id, 0, StatisticUnit.COUNT, BuiltInRegistries.STAT_TYPE.getId(statType), registry.getId(value.get()));
        }

        private <T> void add(StatType<T> statType, T value, String id, int raw, StatisticUnit unit, int typeId, int valueId) {
            var custom = statType == Stats.CUSTOM;
            var name = name(value, id, custom);
            Collection<String> tags = subjects.tags().isEmpty() ? List.of()
                    : statType.getRegistry().wrapAsHolder(value).tags().map(tag -> tag.location().toString()).toList();
            if (!subjects.matches(id, tags, name)) return;
            var category = category(BuiltInRegistries.STAT_TYPE.getKey(statType));
            rows.add(new Row(typeId, valueId, new Statistic(MinecraftInventories.ADAPTER, category, id, label(statType, category, name, raw),
                    value(unit, raw), unit, raw)));
        }

        /**
         * Kills use the sentences the vanilla stats screen shows; a custom counter is its name; other types prefix their
         * display name, or the category when the display name is a sentence with placeholders.
         */
        private static String label(StatType<?> statType, String category, String name, int raw) {
            if (statType == Stats.CUSTOM) return name;
            if (statType == Stats.ENTITY_KILLED) {
                return (raw == 0 ? Component.translatable("stat_type.minecraft.killed.none", name)
                        : Component.translatable("stat_type.minecraft.killed", raw, name)).getString();
            }
            if (statType == Stats.ENTITY_KILLED_BY) {
                return (raw == 0 ? Component.translatable("stat_type.minecraft.killed_by.none", name)
                        : Component.translatable("stat_type.minecraft.killed_by", name, raw)).getString();
            }
            var displayName = statType.getDisplayName().getString();
            return (displayName.contains("%") ? category : displayName) + ": " + name;
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
