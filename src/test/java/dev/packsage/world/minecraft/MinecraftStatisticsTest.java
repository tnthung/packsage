package dev.packsage.world.minecraft;

import dev.packsage.world.KindFilter;
import dev.packsage.world.ServerWork;
import dev.packsage.world.Statistic;
import dev.packsage.world.StatisticUnit;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.stats.StatType;
import net.minecraft.stats.Stats;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.entity.EntityType;
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

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void listsMinedCraftedKilledAndCustomCountersWithLabels() {
        var counter = new StatsCounter();
        counter.setValue(null, Stats.BLOCK_MINED.get(Blocks.DIAMOND_ORE), 12);
        counter.setValue(null, Stats.ITEM_CRAFTED.get(Items.TORCH), 64);
        counter.setValue(null, Stats.ENTITY_KILLED.get(EntityType.ZOMBIE), 3);
        counter.setValue(null, Stats.CUSTOM.get(Stats.DEATHS), 2);

        var rows = run(statistics.statistics(counter, List.of(), KindFilter.ANY));

        assertEquals(List.of(
                new Statistic("minecraft", "mined", "minecraft:diamond_ore", "Times Mined: Diamond Ore", 12, StatisticUnit.COUNT, 12),
                new Statistic("minecraft", "crafted", "minecraft:torch", "Times Crafted: Torch", 64, StatisticUnit.COUNT, 64),
                new Statistic("minecraft", "killed", "minecraft:zombie", "Killed: Zombie", 3, StatisticUnit.COUNT, 3),
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
        var modType = new StatType<>(BuiltInRegistries.ITEM, Component.literal("Smelted"));
        assertEquals(StatisticUnit.COUNT, MinecraftStatistics.unit(modType.get(Items.IRON_INGOT, value -> value + " ingots")));
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
    void walkSpansStepsOverLargeRegistries() {
        var counter = new StatsCounter();
        counter.setValue(null, Stats.BLOCK_MINED.get(Blocks.STONE), 5);
        var slice = statistics.statistics(counter, List.of("mined"), KindFilter.ANY);

        var steps = 1;
        while (slice.step()) {
            steps++;
        }

        assertTrue(BuiltInRegistries.BLOCK.size() > 1024);
        assertTrue(steps > 1, "steps " + steps);
        assertEquals(List.of("minecraft:stone"), slice.result().stream().map(Statistic::subject).toList());
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
