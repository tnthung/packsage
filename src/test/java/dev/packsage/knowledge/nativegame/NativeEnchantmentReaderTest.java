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
        assertEquals("{\"level\":1,\"minCost\":1,\"maxCost\":12}", Evidence.serialize(first));
        assertEquals("{\"level\":4,\"minCost\":34,\"maxCost\":45}", Evidence.serialize(last));
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
        var record = NativeRegistryReader.enchantment(enchantment(Enchantments.PROTECTION), "minecraft:protection", lookup, SCOPE);
        var payload = record.payload().values();
        assertTrue(payload.containsKey("minCost"));
        assertTrue(payload.containsKey("maxCost"));
        assertTrue(payload.get("levels") instanceof Evidence.JsonArray);
        assertTrue(payload.get("effects") instanceof Evidence.JsonObject);
        assertFalse(payload.containsKey("levelsStatus"));
    }
}
