package dev.packsage.knowledge.nativegame;

import dev.packsage.knowledge.Evidence;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeEffectReaderTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "native", 1);

    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.bootStrap();
    }

    @Test
    void speedModifierScalesWithAmplifier() {
        var modifiers = NativeEffectReader.attributeModifiers(MobEffects.MOVEMENT_SPEED.value(), 0, 3);
        assertEquals(1, modifiers.values().size());
        var modifier = (Evidence.JsonObject) modifiers.values().getFirst();
        assertEquals(new Evidence.JsonString(Attributes.MOVEMENT_SPEED.unwrapKey().orElseThrow().location().toString()),
                modifier.values().get("attribute"));
        assertEquals(new Evidence.JsonString("minecraft:effect.speed"), modifier.values().get("modifierId"));
        assertEquals(new Evidence.JsonString("add_multiplied_total"), modifier.values().get("operation"));
        var amounts = ((Evidence.JsonArray) modifier.values().get("amounts")).values();
        assertEquals(4, amounts.size());
        assertEquals(0, new BigDecimal("0.4").compareTo(amount(amounts.get(1))), "amplifier 1 doubles 0.2");
    }

    @Test
    void effectWithoutModifiersReturnsEmptyArray() {
        assertEquals(List.of(), NativeEffectReader.attributeModifiers(MobEffects.NIGHT_VISION.value(), 0, 3).values());
    }

    @Test
    void registryRecordAddsModifiersAndStaysPartial() {
        var record = NativeRegistryReader.effect(MobEffects.MOVEMENT_SPEED.value(), "minecraft:speed", SCOPE);
        assertTrue(record.payload().values().get("attributeModifiers") instanceof Evidence.JsonArray);
        assertEquals(Evidence.Coverage.PARTIAL, record.source().coverage());
    }

    private static BigDecimal amount(Evidence.JsonValue entry) {
        var number = (Evidence.JsonNumber) ((Evidence.JsonObject) entry).values().get("amount");
        return number.value().setScale(6, RoundingMode.HALF_UP);
    }
}
