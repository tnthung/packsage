package dev.packsage.world.minecraft;

import dev.packsage.world.KindFilter;
import dev.packsage.world.ServerWork;
import dev.packsage.world.Statistic;
import dev.packsage.world.StatisticListing;
import dev.packsage.world.StatisticUnit;
import net.minecraft.SharedConstants;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatType;
import net.minecraft.stats.Stats;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftStatisticsTest {
    private final MinecraftStatistics statistics = new MinecraftStatistics();

    private static StatType<Item> MOD_SMELTED;
    private static StatType<Item> MOD_SENTENCE;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // Stands in for a mod registering its own stat types during startup. The registry is global to the test JVM, so
        // these types stay registered and are visible to every test class that runs after this one.
        var types = (MappedRegistry<StatType<?>>) BuiltInRegistries.STAT_TYPE;
        types.unfreeze();
        MOD_SMELTED = Registry.register(types, ResourceLocation.fromNamespaceAndPath("examplemod", "smelted"),
                new StatType<>(BuiltInRegistries.ITEM, Component.literal("Times Smelted")));
        MOD_SENTENCE = Registry.register(types, ResourceLocation.fromNamespaceAndPath("examplemod", "sentence"),
                new StatType<>(BuiltInRegistries.ITEM, Component.literal("You smelted %s %s")));
        types.freeze();
    }

    @Test
    void listsMinedCraftedKilledAndCustomCountersWithLabels() {
        var counter = new StatsCounter();
        counter.setValue(null, Stats.BLOCK_MINED.get(Blocks.DIAMOND_ORE), 12);
        counter.setValue(null, Stats.ITEM_CRAFTED.get(Items.TORCH), 64);
        counter.setValue(null, Stats.ENTITY_KILLED.get(EntityType.ZOMBIE), 3);
        counter.setValue(null, Stats.ENTITY_KILLED_BY.get(EntityType.SKELETON), 4);
        counter.setValue(null, Stats.CUSTOM.get(Stats.DEATHS), 2);

        var rows = run(statistics.statistics(counter, List.of(), KindFilter.ANY));

        assertEquals(List.of(
                new Statistic("minecraft", "mined", "minecraft:diamond_ore", "Times Mined: Diamond Ore", 12, StatisticUnit.COUNT, 12),
                new Statistic("minecraft", "crafted", "minecraft:torch", "Times Crafted: Torch", 64, StatisticUnit.COUNT, 64),
                new Statistic("minecraft", "killed", "minecraft:zombie", "You killed 3 Zombie", 3, StatisticUnit.COUNT, 3),
                new Statistic("minecraft", "killed_by", "minecraft:skeleton", "Skeleton killed you 4 time(s)", 4, StatisticUnit.COUNT, 4),
                new Statistic("minecraft", "custom", "minecraft:deaths", "Number of Deaths", 2, StatisticUnit.COUNT, 2)), rows);
    }

    @Test
    void distancesAreBlocksAndTimesSeconds() {
        var counter = new StatsCounter();
        counter.setValue(null, Stats.CUSTOM.get(Stats.WALK_ONE_CM), 123456);
        counter.setValue(null, Stats.CUSTOM.get(Stats.PLAY_TIME), 72000);

        var rows = run(statistics.statistics(counter, List.of("custom"), KindFilter.ANY));

        assertEquals(2, rows.size());
        var walk = row(rows, "minecraft:walk_one_cm");
        assertEquals(1234.56, walk.value(), 1e-9);
        assertEquals(StatisticUnit.DISTANCE, walk.unit());
        assertEquals(123456, walk.raw());
        var played = row(rows, "minecraft:play_time");
        assertEquals(3600.0, played.value(), 1e-9);
        assertEquals(StatisticUnit.TIME, played.unit());
        assertEquals(72000, played.raw());
    }

    @Test
    void damageIsHealthPointsAndUnknownFormattersAreCounts() {
        var counter = new StatsCounter();
        counter.setValue(null, Stats.CUSTOM.get(Stats.DAMAGE_TAKEN), 55);

        var damage = row(run(statistics.statistics(counter, List.of("custom"), KindFilter.ANY)), "minecraft:damage_taken");

        assertEquals(5.5, damage.value(), 1e-9);
        assertEquals(StatisticUnit.HEALTH, damage.unit());
        assertEquals(StatisticUnit.COUNT, MinecraftStatistics.unit(Stats.ITEM_CRAFTED.get(Items.TORCH)));
        // A subclass, so the custom-formatted stat stays out of the stat type's map.
        assertEquals(StatisticUnit.COUNT, MinecraftStatistics.unit(new Stat<>(Stats.ITEM_USED, Items.IRON_INGOT, value -> value + " ingots") {
        }));
    }

    @Test
    void namedNeverIncrementedSubjectIsAZeroRowAndCreatesNoStat() {
        assertFalse(Stats.BLOCK_MINED.contains(Blocks.ANCIENT_DEBRIS));
        var subjects = new KindFilter(List.of("minecraft:ancient_debris"), List.of(), "");

        var rows = run(statistics.statistics(new StatsCounter(), List.of("mined"), subjects));

        assertEquals(List.of(new Statistic("minecraft", "mined", "minecraft:ancient_debris", "Times Mined: Ancient Debris", 0, StatisticUnit.COUNT, 0)), rows);
        assertFalse(Stats.BLOCK_MINED.contains(Blocks.ANCIENT_DEBRIS));
    }

    @Test
    void neverKilledSubjectsUseTheNoneSentences() {
        var subjects = new KindFilter(List.of("minecraft:creeper"), List.of(), "");

        var rows = run(statistics.statistics(new StatsCounter(), List.of("killed", "killed_by"), subjects));

        assertEquals(List.of("You have never killed Creeper", "You have never been killed by Creeper"), rows.stream().map(Statistic::label).toList());
    }

    @Test
    void namedSubjectWithoutCategoriesIsAZeroRowInEveryCategoryHoldingIt() {
        var subjects = new KindFilter(List.of("minecraft:ancient_debris"), List.of(), "");

        var rows = run(statistics.statistics(new StatsCounter(), List.of(), subjects));

        assertEquals(List.of("mined", "crafted", "used", "broken", "picked_up", "dropped", "examplemod:smelted", "examplemod:sentence"),
                rows.stream().map(Statistic::category).toList());
        assertTrue(rows.stream().allMatch(row -> row.raw() == 0), rows.toString());
        assertFalse(Stats.BLOCK_MINED.contains(Blocks.ANCIENT_DEBRIS));
    }

    @Test
    void namedAbsentCustomCountersKeepTheirVanillaUnits() {
        var subjects = new KindFilter(List.of("minecraft:walk_one_cm", "minecraft:play_time", "minecraft:damage_taken", "minecraft:deaths"),
                List.of(), "");

        var rows = run(statistics.statistics(new StatsCounter(), List.of("custom"), subjects));

        assertEquals(StatisticUnit.DISTANCE, row(rows, "minecraft:walk_one_cm").unit());
        assertEquals(StatisticUnit.TIME, row(rows, "minecraft:play_time").unit());
        assertEquals(StatisticUnit.HEALTH, row(rows, "minecraft:damage_taken").unit());
        assertEquals(StatisticUnit.COUNT, row(rows, "minecraft:deaths").unit());
        assertTrue(rows.stream().allMatch(row -> row.raw() == 0 && row.value() == 0), rows.toString());
    }

    @Test
    void customUnitTableMatchesEveryVanillaCounterFormatter() {
        // Vanilla's custom stats already exist in Stats.CUSTOM's map from Stats' own initialisation, so get creates none.
        for (var entry : MinecraftStatistics.CUSTOM_UNITS.entrySet()) {
            assertEquals(entry.getValue(), MinecraftStatistics.unit(Stats.CUSTOM.get(entry.getKey())), entry.getKey().toString());
        }
        for (var key : BuiltInRegistries.CUSTOM_STAT) {
            if (!key.getNamespace().equals(ResourceLocation.DEFAULT_NAMESPACE)) continue;
            assertEquals(MinecraftStatistics.CUSTOM_UNITS.getOrDefault(key, StatisticUnit.COUNT), MinecraftStatistics.unit(Stats.CUSTOM.get(key)), key.toString());
        }
    }

    @Test
    void zeroRowsFollowTheTextFilterToo() {
        var subjects = new KindFilter(List.of("minecraft:ancient_debris"), List.of(), "diamond");

        assertEquals(List.of(), run(statistics.statistics(new StatsCounter(), List.of("mined"), subjects)));
    }

    @Test
    void unreadableCounterFailsTheReadWithoutCreatingStats() {
        var subjects = new KindFilter(List.of("minecraft:ancient_debris"), List.of(), "");
        var slice = statistics.statistics(() -> {
            throw new IllegalStateException("no counter");
        }, List.of("mined"), subjects);

        var failure = assertThrows(IllegalStateException.class, () -> run(slice));

        assertEquals("no counter", failure.getMessage());
        assertFalse(Stats.BLOCK_MINED.contains(Blocks.ANCIENT_DEBRIS));
    }

    @Test
    void modStatTypeRowsUseItsFullIdAndDisplayNameBesideVanillaRows() {
        var counter = new StatsCounter();
        counter.setValue(null, Stats.BLOCK_MINED.get(Blocks.STONE), 5);
        counter.setValue(null, MOD_SMELTED.get(Items.IRON_INGOT, value -> value + " ingots"), 7);
        counter.setValue(null, MOD_SENTENCE.get(Items.GOLD_INGOT), 2);

        var rows = run(statistics.statistics(counter, List.of(), KindFilter.ANY));

        assertEquals(List.of(
                new Statistic("minecraft", "mined", "minecraft:stone", "Times Mined: Stone", 5, StatisticUnit.COUNT, 5),
                new Statistic("minecraft", "examplemod:smelted", "minecraft:iron_ingot", "Times Smelted: Iron Ingot", 7, StatisticUnit.COUNT, 7),
                new Statistic("minecraft", "examplemod:sentence", "minecraft:gold_ingot", "examplemod:sentence: Gold Ingot", 2, StatisticUnit.COUNT, 2)), rows);
        assertTrue(statistics.categories().contains("examplemod:smelted"));
    }

    @Test
    void unknownCategoryIsRejectedWithTheValidList() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> statistics.statistics(new StatsCounter(), List.of("smelted"), KindFilter.ANY));

        assertTrue(failure.getMessage().startsWith("unknown statistic category smelted; use one of "), failure.getMessage());
        for (var category : List.of("mined", "crafted", "used", "broken", "picked_up", "dropped", "killed", "killed_by", "custom")) {
            assertTrue(statistics.categories().contains(category), category);
            assertTrue(failure.getMessage().contains(category), failure.getMessage());
        }
    }

    @Test
    void walkSpansStepsOverLargeCounters() {
        var counter = new StatsCounter();
        for (var item : BuiltInRegistries.ITEM) {
            counter.setValue(null, Stats.ITEM_CRAFTED.get(item), 1);
        }
        counter.setValue(null, Stats.BLOCK_MINED.get(Blocks.STONE), 5);
        var slice = statistics.statistics(counter, List.of("crafted"), KindFilter.ANY);

        var steps = 1;
        while (slice.step()) {
            steps++;
        }

        assertTrue(BuiltInRegistries.ITEM.size() > 1024);
        assertTrue(steps > 1, "steps " + steps);
        var expected = BuiltInRegistries.ITEM.stream().map(item -> BuiltInRegistries.ITEM.getKey(item).toString()).toList();
        var ordered = new ArrayList<Statistic>();
        for (var offset = 0; offset < expected.size(); offset += 64) {
            ordered.addAll(StatisticListing.page(List.of(slice.result()), offset, List.of()).items());
        }
        assertEquals(expected.stream().sorted().toList(), ordered.stream().map(Statistic::subject).toList());
    }

    @Test
    void aNewCounterBetweenStepsRestartsTheWalkWithoutFailing() {
        var map = new Object2IntOpenHashMap<Stat<?>>();
        for (var item : BuiltInRegistries.ITEM) map.put(Stats.ITEM_CRAFTED.get(item), 1);
        var slice = statistics.statistics(() -> map, List.of(), KindFilter.ANY);
        assertTrue(slice.step());
        map.put(Stats.BLOCK_MINED.get(Blocks.STONE), 3);
        while (slice.step()) {
        }
        assertEquals(map.size(), slice.result().size());
        assertEquals(3, slice.result().stream().filter(row -> row.category().equals("mined") && row.subject().equals("minecraft:stone"))
                .findFirst().orElseThrow().raw());
        assertEquals(map.size(), slice.result().stream().map(row -> row.category() + "/" + row.subject()).distinct().count());
    }

    @Test
    void aSameSizeCounterRehashBetweenStepsKeepsEveryCounterOnce() {
        var map = new Object2IntOpenHashMap<Stat<?>>();
        for (var item : BuiltInRegistries.ITEM) map.put(Stats.ITEM_CRAFTED.get(item), 1);
        var slice = statistics.statistics(() -> map, List.of("crafted"), KindFilter.ANY);
        assertTrue(slice.step());
        map.ensureCapacity(100_000);
        while (slice.step()) {
        }
        assertEquals(map.size(), slice.result().size());
        assertEquals(map.size(), slice.result().stream().map(Statistic::subject).distinct().count());
    }

    @Test
    void anEntryBatchLeavesNamedZeroWorkForTheNextStep() {
        var map = new Object2IntOpenHashMap<Stat<?>>();
        BuiltInRegistries.ITEM.stream().limit(MinecraftStatistics.STEP_ENTRIES).forEach(item -> map.put(Stats.ITEM_CRAFTED.get(item), 1));
        var slice = statistics.statistics(() -> map, List.of("mined"),
                new KindFilter(List.of("minecraft:ancient_debris"), List.of(), ""));
        assertTrue(slice.step());
        assertTrue(slice.result().isEmpty(), "the entry batch consumes this step's entire operation budget");
        while (slice.step()) {
        }
        assertEquals(1, slice.result().size());
    }

    @Test
    void repeatedCounterGrowthStopsWithARetryNoteAfterBoundedRestarts() {
        var stats = BuiltInRegistries.ITEM.stream().map(Stats.ITEM_CRAFTED::get).toList();
        var map = new Object2IntOpenHashMap<Stat<?>>();
        for (var i = 0; i < MinecraftStatistics.STEP_ENTRIES + 1; i++) map.put(stats.get(i), 1);
        var slice = statistics.statistics(() -> map, List.of("crafted"), KindFilter.ANY);
        assertTrue(slice.step());
        var changes = 0;
        boolean more;
        do {
            map.put(stats.get(MinecraftStatistics.STEP_ENTRIES + 1 + changes++), 1);
            more = slice.step();
            assertTrue(changes <= 4);
        } while (more);
        assertTrue(slice.note().contains("repeat list_statistics"));
    }

    @Test
    void copyingALargeCounterVisitsAtMostOneBatchPerStep() {
        var visited = new AtomicInteger();
        var map = new Object2IntOpenHashMap<Stat<?>>() {
            @Override
            public it.unimi.dsi.fastutil.objects.ObjectSet<Stat<?>> keySet() {
                var keys = super.keySet();
                return new it.unimi.dsi.fastutil.objects.AbstractObjectSet<>() {
                    @Override public int size() { return keys.size(); }
                    @Override public it.unimi.dsi.fastutil.objects.ObjectIterator<Stat<?>> iterator() {
                        var iterator = keys.iterator();
                        return new it.unimi.dsi.fastutil.objects.ObjectIterator<>() {
                            @Override public boolean hasNext() { return iterator.hasNext(); }
                            @Override public Stat<?> next() { visited.incrementAndGet(); return iterator.next(); }
                        };
                    }
                };
            }

            @Override
            public int getInt(Object key) {
                visited.incrementAndGet();
                return super.getInt(key);
            }
        };
        for (var item : BuiltInRegistries.ITEM) map.put(Stats.ITEM_CRAFTED.get(item), 1);
        var slice = statistics.statistics(() -> map, List.of("crafted"), KindFilter.ANY);
        var steps = 0;
        boolean more;
        do {
            var before = visited.get();
            more = slice.step();
            assertTrue(visited.get() - before <= MinecraftStatistics.STEP_ENTRIES, "entries visited in one step: " + (visited.get() - before));
            steps++;
        } while (more);
        assertEquals(map.size(), visited.get());
        assertTrue(steps > 1);
        assertEquals(map.size(), slice.result().size());
    }

    @Test
    void sparseCountersCountEmptySlotsAgainstTheStepBudget() {
        var map = new Object2IntOpenHashMap<Stat<?>>(100_000);
        map.put(Stats.BLOCK_MINED.get(Blocks.STONE), 3);
        var slice = statistics.statistics(() -> map, List.of("mined"), KindFilter.ANY);
        assertTrue(slice.step(), "a sparse table still needs bounded batches");
        var steps = 1;
        while (slice.step()) steps++;
        assertTrue(steps > 2);
        assertEquals(List.of(new Statistic("minecraft", "mined", "minecraft:stone", "Times Mined: Stone", 3, StatisticUnit.COUNT, 3)), slice.result());
    }

    @Test
    void modStatTypeCategoryIsItsFullId() {
        assertEquals("examplemod:smelted", MinecraftStatistics.category(ResourceLocation.fromNamespaceAndPath("examplemod", "smelted")));
        assertEquals("mined", MinecraftStatistics.category(ResourceLocation.withDefaultNamespace("mined")));
    }

    private static List<Statistic> run(ServerWork.Slice<List<Statistic>> slice) {
        while (slice.step()) {
        }
        // As in the tool, ordering belongs to the consumer after the server has finished copying.
        var categories = new MinecraftStatistics().categories();
        return slice.result().stream().sorted(java.util.Comparator.<Statistic>comparingInt(row -> categories.indexOf(row.category()))
                .thenComparing(Statistic::subject)).toList();
    }

    private static Statistic row(List<Statistic> rows, String subject) {
        return rows.stream().filter(row -> row.subject().equals(subject)).findFirst().orElseThrow(() -> new AssertionError(subject + " in " + rows));
    }
}
