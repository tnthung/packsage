package dev.packsage.knowledge.nativegame;

import dev.packsage.knowledge.Evidence;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeEnchantmentReaderTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "native", 1);
    private static HolderLookup.Provider lookup;

    @BeforeAll
    static void bootstrap() {
        Bootstrap.bootStrap();
        lookup = VanillaRegistries.createLookup();
    }

    private static Enchantment enchantment(ResourceKey<Enchantment> key) {
        return lookup.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key).value();
    }

    @Test
    void protectionReportsMinAndMaxCostForEveryLevel() {
        var levels = NativeEnchantmentReader.levels(enchantment(Enchantments.PROTECTION));
        assertFalse(levels.bounded());
        assertEquals(4, levels.values().values().size());
        var first = (Evidence.JsonObject) levels.values().values().getFirst();
        var last = (Evidence.JsonObject) levels.values().values().getLast();
        // Evidence.JsonObject copies into an immutable map, so key order is not stable; compare values.
        assertLevel(first, 1, 1, 12);
        assertLevel(last, 4, 34, 45);
    }

    private static void assertLevel(Evidence.JsonObject entry, int level, int minCost, int maxCost) {
        assertEquals(level, ((Evidence.JsonNumber) entry.values().get("level")).value().intValueExact());
        assertEquals(minCost, ((Evidence.JsonNumber) entry.values().get("minCost")).value().intValueExact());
        assertEquals(maxCost, ((Evidence.JsonNumber) entry.values().get("maxCost")).value().intValueExact());
    }

    @Test
    void levelsAboveThirtyTwoAreBounded() {
        var wide = Enchantment.enchantment(Enchantment.definition(HolderSet.direct(), 1, 255,
                Enchantment.constantCost(1), Enchantment.constantCost(2), 1, EquipmentSlotGroup.ANY)).build(
                ResourceLocation.parse("packsage:wide"));
        var levels = NativeEnchantmentReader.levels(wide);
        assertTrue(levels.bounded());
        assertEquals(32, levels.values().values().size());
    }

    @Test
    void effectsEncodeLoadedComponentsByComponentId() {
        var effects = NativeEnchantmentReader.effects(enchantment(Enchantments.SHARPNESS), lookup);
        assertTrue(effects.values().containsKey("minecraft:damage"));
        assertFalse(effects.values().containsKey("status"));
    }

    @Test
    void enchantmentRecordAddsLevelsAndEffectsWithoutReplacingExistingFields() {
        var protection = enchantment(Enchantments.PROTECTION);
        // Vanilla definitions reference tags that are unbound in the bootstrap lookup, so rebuild with direct holder sets.
        var definition = Enchantment.definition(HolderSet.direct(), 10, 4, Enchantment.dynamicCost(1, 11),
                Enchantment.dynamicCost(12, 11), 1, EquipmentSlotGroup.ARMOR);
        var bound = new Enchantment(protection.description(), definition, HolderSet.direct(), protection.effects());
        var record = NativeRegistryReader.enchantment(bound, "minecraft:protection", lookup, SCOPE);
        var payload = record.payload().values();
        assertTrue(payload.containsKey("minCost"));
        assertTrue(payload.containsKey("maxCost"));
        assertTrue(payload.get("levels") instanceof Evidence.JsonArray);
        assertTrue(payload.get("effects") instanceof Evidence.JsonObject);
        assertFalse(payload.containsKey("levelsStatus"));
    }
}
