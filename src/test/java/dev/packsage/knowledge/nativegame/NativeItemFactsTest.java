package dev.packsage.knowledge.nativegame;

import dev.packsage.knowledge.Evidence;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeItemFactsTest {
    private static RegistryAccess registries;

    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.bootStrap();
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    @Test
    void differentPotionContentsGiveDifferentFingerprints() {
        var strength = PotionContents.createItemStack(Items.POTION, Potions.STRENGTH);
        var swiftness = PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS);
        assertNotEquals(NativeItemFacts.componentFingerprint(strength, registries), NativeItemFacts.componentFingerprint(swiftness, registries));
        assertEquals(NativeItemFacts.componentFingerprint(strength, registries),
                NativeItemFacts.componentFingerprint(PotionContents.createItemStack(Items.POTION, Potions.STRENGTH), registries));
    }

    @Test
    void fingerprintIgnoresComponentInsertionOrder() {
        var first = new ItemStack(Items.DIAMOND_SWORD);
        first.set(DataComponents.CUSTOM_NAME, Component.literal("A"));
        first.set(DataComponents.DAMAGE, 3);
        var second = new ItemStack(Items.DIAMOND_SWORD);
        second.set(DataComponents.DAMAGE, 3);
        second.set(DataComponents.CUSTOM_NAME, Component.literal("A"));
        assertEquals(NativeItemFacts.componentFingerprint(first, registries), NativeItemFacts.componentFingerprint(second, registries));
    }

    @Test
    void fingerprintSurvivesComponentTruncation() {
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        NativeItemFacts.enrich(payload, PotionContents.createItemStack(Items.POTION, Potions.STRENGTH), registries);
        payload.put("components", new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unknown_truncated"))));
        assertTrue(payload.get("componentFingerprint") instanceof Evidence.JsonString);
    }

    @Test
    void lingeringPotionDecodesBaseAndCustomEffectsWithScale() {
        var stack = PotionContents.createItemStack(Items.LINGERING_POTION, Potions.STRENGTH);
        stack.set(DataComponents.POTION_CONTENTS, stack.get(DataComponents.POTION_CONTENTS)
                .withEffectAdded(new MobEffectInstance(MobEffects.JUMP, 200, 1)));
        var contents = NativeItemFacts.potionContents(stack).values();
        assertEquals(new Evidence.JsonString("minecraft:strength"), contents.get("potion"));
        assertEquals(0, new BigDecimal("0.25").compareTo(((Evidence.JsonNumber) contents.get("durationScale")).value()));
        var effects = ((Evidence.JsonArray) contents.get("effects")).values();
        assertEquals(List.of("potion", "custom"), effects.stream()
                .map(effect -> ((Evidence.JsonString) ((Evidence.JsonObject) effect).values().get("origin")).value()).toList());
    }

    @Test
    void unknownPotionCarrierReportsUnknownScale() {
        var stack = new ItemStack(Items.STICK);
        stack.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.STRENGTH));
        var contents = NativeItemFacts.potionContents(stack).values();
        assertEquals(new Evidence.JsonNull(), contents.get("durationScale"));
        assertEquals(new Evidence.JsonString("unknown_item"), contents.get("durationScaleStatus"));
    }
}
