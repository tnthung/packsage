package dev.packsage.knowledge.nativegame;

import com.mojang.serialization.Codec;
import dev.packsage.knowledge.Evidence;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.Enchantments;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

    @Test
    void sharpnessDamageIsDecodedAsPerLevelAddition() {
        var entry = component(NativeEnchantmentReader.mechanics(enchantment(Enchantments.SHARPNESS)), "minecraft:damage");
        assertEquals("value", string(entry, "kind"));
        assertEquals("decoded", string(entry, "status"));
        var effect = first(entry, "effects");
        assertEquals("minecraft:add", string(effect, "type"));
        assertEquals(0, BigDecimal.ONE.compareTo(number(at(effect, "values", 0), "add")));
        assertEquals(0, new BigDecimal("3").compareTo(number(at(effect, "values", 4), "add")));
    }

    @Test
    void protectionReportsItsConditionWithoutEvaluatingIt() {
        var entry = component(NativeEnchantmentReader.mechanics(enchantment(Enchantments.PROTECTION)), "minecraft:damage_protection");
        var effect = first(entry, "effects");
        assertEquals(new Evidence.JsonBoolean(true), effect.values().get("conditional"));
        assertEquals(0, new BigDecimal("4").compareTo(number(at(effect, "values", 3), "add")));
    }

    @Test
    void efficiencyAttributeAmountsFollowLevelsSquared() {
        var entry = component(NativeEnchantmentReader.mechanics(enchantment(Enchantments.EFFICIENCY)), "minecraft:attributes");
        assertEquals("attribute", string(entry, "kind"));
        var effect = first(entry, "effects");
        assertEquals(Attributes.MINING_EFFICIENCY.unwrapKey().orElseThrow().location().toString(), string(effect, "attribute"));
        assertEquals(0, new BigDecimal("5").compareTo(number(at(effect, "amounts", 1), "amount")));
    }

    @Test
    void unknownComponentTypeIsOpaqueAndPartial() {
        DataComponentType<String> modded = DataComponentType.<String>builder().persistent(Codec.STRING).build();
        var mechanics = NativeEnchantmentReader.mechanics(customWith(modded, "x"));
        assertTrue(mechanics.partial());
        assertEquals("opaque", string(component(mechanics, "unknown"), "kind"));
        assertEquals("presentation", string(component(mechanics, "minecraft:trident_sound"), "kind"));
    }

    @Test
    void recordReportsDecodedStatus() {
        var sharpness = enchantment(Enchantments.SHARPNESS);
        // Vanilla definitions reference unbound tags in the bootstrap lookup, so rebuild with direct holder sets.
        var definition = Enchantment.definition(HolderSet.direct(), 10, 5, Enchantment.dynamicCost(1, 11),
                Enchantment.dynamicCost(21, 11), 1, EquipmentSlotGroup.MAINHAND);
        var bound = new Enchantment(sharpness.description(), definition, HolderSet.direct(), sharpness.effects());
        var record = NativeRegistryReader.enchantment(bound, "minecraft:sharpness", lookup, SCOPE);
        assertEquals(new Evidence.JsonString("built_in_components_decoded"), record.payload().values().get("effectsStatus"));
        assertInstanceOf(Evidence.JsonArray.class, record.payload().values().get("effectMechanics"));
        assertEquals(Evidence.Coverage.COMPLETE, record.source().coverage());

        DataComponentType<String> modded = DataComponentType.<String>builder().persistent(Codec.STRING).build();
        var opaque = NativeRegistryReader.enchantment(customWith(modded, "x"), "packsage:custom", lookup, SCOPE);
        assertEquals(Evidence.Coverage.PARTIAL, opaque.source().coverage());
    }

    private static <E> Enchantment customWith(DataComponentType<E> type, E value) {
        return Enchantment.enchantment(Enchantment.definition(HolderSet.direct(), 1, 1,
                Enchantment.constantCost(1), Enchantment.constantCost(2), 1, EquipmentSlotGroup.ANY))
                .withSpecialEffect(type, value)
                .withSpecialEffect(EnchantmentEffectComponents.TRIDENT_SOUND, List.of(SoundEvents.TRIDENT_RIPTIDE_1))
                .build(ResourceLocation.parse("packsage:custom"));
    }

    private static Evidence.JsonObject component(NativeEnchantmentReader.Mechanics mechanics, String id) {
        for (var value : mechanics.values().values()) {
            var object = assertInstanceOf(Evidence.JsonObject.class, value);
            if (new Evidence.JsonString(id).equals(object.values().get("component"))) return object;
        }
        throw new AssertionError("missing component " + id);
    }

    private static Evidence.JsonObject first(Evidence.JsonObject entry, String key) {
        return at(entry, key, 0);
    }

    private static Evidence.JsonObject at(Evidence.JsonObject entry, String key, int index) {
        var array = assertInstanceOf(Evidence.JsonArray.class, entry.values().get(key));
        return assertInstanceOf(Evidence.JsonObject.class, array.values().get(index));
    }

    private static String string(Evidence.JsonObject entry, String key) {
        return assertInstanceOf(Evidence.JsonString.class, entry.values().get(key)).value();
    }

    private static BigDecimal number(Evidence.JsonObject entry, String key) {
        return assertInstanceOf(Evidence.JsonNumber.class, entry.values().get(key)).value();
    }
}
