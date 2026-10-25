package dev.packsage.world.minecraft;

import dev.packsage.world.KindFilter;
import dev.packsage.world.ServerWork;
import dev.packsage.world.Statistic;
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

import java.util.List;

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
        // Stands in for a mod registering its own stat types during startup.
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

        assertEquals(List.of("mined", "crafted", "used", "broken", "picked_up", "dropped"), rows.stream().map(Statistic::category).toList());
        assertTrue(rows.stream().allMatch(row -> row.raw() == 0), rows.toString());
        assertFalse(Stats.BLOCK_MINED.contains(Blocks.ANCIENT_DEBRIS));
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
        assertTrue(steps > 2, "steps " + steps);
        var expected = BuiltInRegistries.ITEM.stream().map(item -> BuiltInRegistries.ITEM.getKey(item).toString()).toList();
        assertEquals(expected, slice.result().stream().map(Statistic::subject).toList());
    }

    @Test
    void modStatTypeCategoryIsItsFullId() {
        assertEquals("examplemod:smelted", MinecraftStatistics.category(ResourceLocation.fromNamespaceAndPath("examplemod", "smelted")));
        assertEquals("mined", MinecraftStatistics.category(ResourceLocation.withDefaultNamespace("mined")));
    }

    private static <T> T run(ServerWork.Slice<T> slice) {
        while (slice.step()) {
        }
        return slice.result();
    }

    private static Statistic row(List<Statistic> rows, String subject) {
        return rows.stream().filter(row -> row.subject().equals(subject)).findFirst().orElseThrow(() -> new AssertionError(subject + " in " + rows));
    }
}
