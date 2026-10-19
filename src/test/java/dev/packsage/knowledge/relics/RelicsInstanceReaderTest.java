package dev.packsage.knowledge.relics;

import dev.packsage.knowledge.Evidence;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelicsInstanceReaderTest {
    private static final String ABILITY = "fixture:power";
    private static final String STAT = "fixture:strength";

    @BeforeAll
    static void bootstrapMinecraft() { Bootstrap.bootStrap(); }

    @Test
    void publicReadLeavesOrdinaryRegisteredItemsWithoutRelicProjection() {
        assertNull(new RelicsInstanceReader(false, false, "not-installed").read(null, new ItemStack(Items.APPLE)));
        assertNull(new RelicsInstanceReader(true, true, "0.12.8").read(null, new ItemStack(Items.DIAMOND)));
    }

    @Test
    void projectsFreshIndependentInstanceValuesAndExplicitZeroFromAStackCopy() {
        var reader = new RelicsInstanceReader(true, true, "0.12.8");
        var firstStack = relicStack(7, 123.5, 2, 0, 0, 1.75, 0);
        var secondStack = relicStack(3, 28.0, 1, 4, 2, 0.5, 3);

        var first = reader.project(null, firstStack, new FixtureRelicItem());
        var second = reader.project(null, secondStack, new FixtureRelicItem());

        assertNotNull(first);
        assertEquals("COMPLETE", ((Evidence.JsonString) first.values().get("coverage")).value());
        assertEquals("0.12.8", ((Evidence.JsonString) first.values().get("apiVersion")).value());
        assertEquals(new BigDecimal("7"), ((Evidence.JsonNumber) first.values().get("level")).value());
        assertEquals(new BigDecimal("123.5"), ((Evidence.JsonNumber) first.values().get("experience")).value());
        assertEquals(new BigDecimal("2"), ((Evidence.JsonNumber) first.values().get("rank")).value());
        assertEquals(BigDecimal.ZERO, ((Evidence.JsonNumber) first.values().get("points")).value());
        var firstAbilities = (Evidence.JsonObject) first.values().get("abilities");
        var firstAbility = (Evidence.JsonObject) firstAbilities.values().get(ABILITY);
        assertEquals(BigDecimal.ZERO, ((Evidence.JsonNumber) firstAbility.values().get("points")).value());
        var firstStats = (Evidence.JsonObject) firstAbility.values().get("stats");
        var firstStat = (Evidence.JsonObject) firstStats.values().get(STAT);
        assertEquals(new BigDecimal("1.75"), ((Evidence.JsonNumber) firstStat.values().get("value")).value());
        assertEquals(BigDecimal.ZERO, ((Evidence.JsonNumber) firstStat.values().get("quality")).value());
        assertEquals(new BigDecimal("3"), ((Evidence.JsonNumber) second.values().get("level")).value());
        var secondAbility = (Evidence.JsonObject) ((Evidence.JsonObject) second.values().get("abilities")).values().get(ABILITY);
        var secondStats = (Evidence.JsonObject) secondAbility.values().get("stats");
        var secondStat = (Evidence.JsonObject) secondStats.values().get(STAT);
        assertEquals(new BigDecimal("0.5"), ((Evidence.JsonNumber) secondStat.values().get("value")).value());
        assertFalse(firstStack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getBoolean("initialized"));

        var changed = firstStack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        changed.putInt("level", 9);
        changed.putDouble("experience", 250.0);
        var changedAbility = changed.getCompound("abilities").getCompound(ABILITY);
        changedAbility.getCompound("stats").getCompound(STAT).putDouble("value", 2.25);
        firstStack.set(DataComponents.CUSTOM_DATA, CustomData.of(changed));
        var reread = reader.project(null, firstStack, new FixtureRelicItem());

        assertEquals(new BigDecimal("9"), ((Evidence.JsonNumber) reread.values().get("level")).value());
        assertEquals(new BigDecimal("250.0"), ((Evidence.JsonNumber) reread.values().get("experience")).value());
        var rereadStat = (Evidence.JsonObject) ((Evidence.JsonObject) ((Evidence.JsonObject)
                reread.values().get("abilities")).values().get(ABILITY)).values().get("stats");
        assertEquals(new BigDecimal("2.25"), ((Evidence.JsonNumber) ((Evidence.JsonObject) rereadStat.values().get(STAT))
                .values().get("value")).value());
    }

    @Test
    void failedAbilityStatKeepsReadableProgressionAndReportsPartialCoverage() {
        var stack = relicStack(5, 63, 1, 2, 1, 1.25, 2);
        var customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        customData.putString("failStat", STAT);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(customData));

        var result = new RelicsInstanceReader(true, true, "0.12.8").project(null, stack, new FixtureRelicItem());

        assertNotNull(result);
        assertEquals("PARTIAL", ((Evidence.JsonString) result.values().get("coverage")).value());
        assertEquals(new BigDecimal("5"), ((Evidence.JsonNumber) result.values().get("level")).value());
        assertFalse(((Evidence.JsonObject) ((Evidence.JsonObject) result.values().get("abilities"))
                .values().get(ABILITY)).values().containsKey("stats"));
    }

    @Test
    void unsupportedPresentApiReportsUnknownStatsWithoutInventingFields() {
        var result = new RelicsInstanceReader(true, false, "0.13.0").project(null,
                relicStack(5, 63, 1, 2, 1, 1.25, 2), new FixtureRelicItem());

        assertNotNull(result);
        assertEquals("UNKNOWN", ((Evidence.JsonString) result.values().get("coverage")).value());
        assertEquals("0.13.0", ((Evidence.JsonString) result.values().get("apiVersion")).value());
        assertFalse(result.values().containsKey("level"));
    }

    @Test
    void absentApiAndOrdinaryItemsDoNotAddRelicStats() {
        assertNull(new RelicsInstanceReader(false, false, "missing").read(null,
                relicStack(1, 0, 0, 0, 0, 1.0, 0)));
        assertNull(new RelicsInstanceReader(true, true, "0.12.8").read(null,
                new ItemStack(net.minecraft.world.item.Items.DIAMOND)));
    }

    @Test
    void traversalRespectsAbilityPerAbilityAndAggregateStatBounds() {
        var customData = new CompoundTag();
        customData.putInt("level", 1);
        customData.putInt("rank", 0);
        customData.putInt("points", 0);
        var abilities = new CompoundTag();
        for (int abilityIndex = 0; abilityIndex < 17; abilityIndex++) {
            var ability = new CompoundTag();
            var stats = new CompoundTag();
            for (int statIndex = 0; statIndex < 33; statIndex++) {
                var stat = new CompoundTag();
                stat.putDouble("value", 1.0);
                stat.putInt("quality", 1);
                stats.put("fixture:stat_" + statIndex, stat);
            }
            ability.putInt("points", 1);
            ability.put("stats", stats);
            abilities.put("fixture:ability_" + abilityIndex, ability);
        }
        customData.put("abilities", abilities);
        var stack = new ItemStack(Items.APPLE);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(customData));

        var result = new RelicsInstanceReader(true, true, "0.12.8").project(null, stack, new FixtureRelicItem());

        assertNotNull(result);
        var projectedAbilities = (Evidence.JsonObject) result.values().get("abilities");
        assertTrue(projectedAbilities.values().size() <= 16);
        assertTrue(projectedAbilities.values().values().stream().map(value -> (Evidence.JsonObject) value)
                .allMatch(value -> ((Evidence.JsonObject) value.values().get("stats")).values().size() <= 32));
        int totalStats = projectedAbilities.values().values().stream().map(value -> (Evidence.JsonObject) value)
                .mapToInt(value -> ((Evidence.JsonObject) value.values().get("stats")).values().size()).sum();
        assertEquals(128, totalStats);
        assertEquals("PARTIAL", ((Evidence.JsonString) result.values().get("coverage")).value());
    }

    private static ItemStack relicStack(int level, double experience, int rank, int points,
                                       int abilityPoints, double value, int quality) {
        var customData = new CompoundTag();
        customData.putInt("level", level);
        customData.putDouble("experience", experience);
        customData.putInt("rank", rank);
        customData.putInt("points", points);
        var stat = new CompoundTag();
        stat.putDouble("value", value);
        stat.putInt("quality", quality);
        var stats = new CompoundTag();
        stats.put(STAT, stat);
        var ability = new CompoundTag();
        ability.putInt("points", abilityPoints);
        ability.put("stats", stats);
        var abilities = new CompoundTag();
        abilities.put(ABILITY, ability);
        customData.put("abilities", abilities);
        var stack = new ItemStack(Items.APPLE);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(customData));
        return stack;
    }
}
