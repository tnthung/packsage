package dev.packsage.knowledge.nativegame;

import dev.packsage.knowledge.Evidence;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    private static net.minecraft.locale.Language language(String key, String text) {
        return new net.minecraft.locale.Language() {
            @Override
            public String getOrDefault(String candidate, String fallback) {
                return candidate.equals(key) ? text : fallback;
            }

            @Override
            public boolean has(String candidate) {
                return candidate.equals(key);
            }

            @Override
            public boolean isDefaultRightToLeft() {
                return false;
            }

            @Override
            public net.minecraft.util.FormattedCharSequence getVisualOrder(net.minecraft.network.chat.FormattedText text) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static java.util.Map<String, Evidence.JsonValue> describe(net.minecraft.world.effect.MobEffect effect, String text) {
        var payload = new java.util.LinkedHashMap<String, Evidence.JsonValue>();
        NativeEffectReader.describe(payload, effect, language("effect.minecraft.luck.description", text));
        return payload;
    }

    @Test
    void providedDescriptionIsReadWithSource() {
        var payload = describe(MobEffects.LUCK.value(), "Improves loot.");
        assertEquals(new Evidence.JsonString("Improves loot."), payload.get("description"));
        assertEquals(new Evidence.JsonString("language:effect.minecraft.luck.description"), payload.get("descriptionSource"));
        assertFalse(payload.containsKey("descriptionStatus"));
    }

    @Test
    void missingDescriptionIsNotProvided() {
        var payload = describe(MobEffects.MOVEMENT_SPEED.value(), "Improves loot.");
        assertEquals(new Evidence.JsonString("not_provided"), payload.get("descriptionStatus"));
        assertFalse(payload.containsKey("description"));
    }

    @Test
    void longDescriptionIsBounded() {
        var payload = describe(MobEffects.LUCK.value(), "x".repeat(2000));
        assertEquals(1024, ((Evidence.JsonString) payload.get("description")).value().length());
        assertEquals(new Evidence.JsonString("bounded"), payload.get("descriptionStatus"));
    }

    @Test
    void formattingCodesAreStripped() {
        var payload = describe(MobEffects.LUCK.value(), "§aGood §lluck");
        assertEquals(new Evidence.JsonString("Good luck"), payload.get("description"));
    }

    @Test
    void throwingLanguageIsUnknown() {
        var payload = new java.util.LinkedHashMap<String, Evidence.JsonValue>();
        NativeEffectReader.describe(payload, MobEffects.LUCK.value(), new net.minecraft.locale.Language() {
            @Override
            public String getOrDefault(String key, String fallback) {
                throw new IllegalStateException("boom");
            }

            @Override
            public boolean has(String key) {
                throw new IllegalStateException("boom");
            }

            @Override
            public boolean isDefaultRightToLeft() {
                return false;
            }

            @Override
            public net.minecraft.util.FormattedCharSequence getVisualOrder(net.minecraft.network.chat.FormattedText text) {
                throw new UnsupportedOperationException();
            }
        });
        assertEquals(new Evidence.JsonString("unknown"), payload.get("descriptionStatus"));
    }

    private static net.minecraft.world.effect.MobEffect throwingEffect() {
        return new net.minecraft.world.effect.MobEffect(net.minecraft.world.effect.MobEffectCategory.NEUTRAL, 0) {
            @Override
            public void createModifiers(int amplifier, java.util.function.BiConsumer<net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute>,
                    net.minecraft.world.entity.ai.attributes.AttributeModifier> output) {
                throw new IllegalStateException("template failure");
            }
        };
    }

    @Test
    void throwingModifierCreationIsReportedUnknownAndRecordStaysBuilt() {
        var record = NativeRegistryReader.effect(throwingEffect(), "test:broken", SCOPE);
        assertEquals(new Evidence.JsonObject(java.util.Map.of("status", new Evidence.JsonString("unknown"))),
                record.payload().values().get("attributeModifiers"));
        assertEquals(Evidence.Coverage.PARTIAL, record.source().coverage());
    }

    @Test
    void throwingActiveEffectStillReturnsPartialPage() {
        var active = List.of(new MobEffectInstance(net.minecraft.core.Holder.direct(throwingEffect()), 100, 0),
                new MobEffectInstance(MobEffects.NIGHT_VISION, 100, 0));
        var page = NativeEffectReader.searchActive(active, null, List.of(), List.of(), 0, 16, SCOPE);
        assertEquals(2, page.records().size());
        assertTrue(page.records().stream().anyMatch(record -> record.source().coverage() == Evidence.Coverage.PARTIAL
                && record.payload().values().get("attributeModifiers") instanceof Evidence.JsonObject));
    }

    @Test
    void activeEffectsAreSortedFilteredAndPaged() {
        var active = List.of(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 600, 1),
                new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0));
        var page = NativeEffectReader.searchActive(active, null, List.of(), List.of(), 0, 16, SCOPE);
        assertEquals(List.of("active_effect:minecraft:night_vision", "active_effect:minecraft:speed"),
                page.records().stream().map(record -> record.source().sourceHandle()).toList());
        var speed = page.records().getLast().payload().values();
        assertEquals(0, BigDecimal.ONE.compareTo(((Evidence.JsonNumber) speed.get("amplifier")).value()));
        assertEquals(0, new BigDecimal(600).compareTo(((Evidence.JsonNumber) speed.get("durationTicks")).value()));
        assertEquals(new Evidence.JsonBoolean(false), speed.get("infinite"));
        assertEquals(new Evidence.JsonBoolean(true), page.records().getFirst().payload().values().get("infinite"));
        var first = NativeEffectReader.searchActive(active, null, List.of(), List.of(), 0, 1, SCOPE);
        assertEquals(1, first.nextOffset());
        assertFalse(first.exhausted());
        assertEquals(List.of("active_effect:minecraft:speed"), NativeEffectReader.searchActive(active, null,
                List.of("minecraft:speed"), List.of(), 0, 16, SCOPE).records().stream().map(record -> record.source().sourceHandle()).toList());
    }

    @Test
    void expiredEffectIsNotReplayed() {
        assertNull(NativeEffectReader.readActive(List.of(), "minecraft:speed", SCOPE));
        assertNotNull(NativeEffectReader.readActive(List.of(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 20, 0)), "minecraft:speed", SCOPE));
    }

    private static BigDecimal amount(Evidence.JsonValue entry) {
        var number = (Evidence.JsonNumber) ((Evidence.JsonObject) entry).values().get("amount");
        return number.value().setScale(6, RoundingMode.HALF_UP);
    }
}
