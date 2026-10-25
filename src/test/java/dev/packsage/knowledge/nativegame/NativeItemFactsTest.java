package dev.packsage.knowledge.nativegame;

import dev.packsage.knowledge.Evidence;
import net.minecraft.core.NonNullList;
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
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void componentsLeaveOutNestedContents() {
        var items = NonNullList.withSize(27, ItemStack.EMPTY);
        items.set(0, new ItemStack(Items.DIAMOND, 3));
        var shulker = new ItemStack(Items.SHULKER_BOX);
        shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
        shulker.set(DataComponents.CUSTOM_NAME, Component.literal("Loot"));
        var components = NativeItemFacts.components(shulker, registries).values();
        assertFalse(components.containsKey("minecraft:container"));
        assertFalse(components.containsKey("status"));
        assertTrue(components.containsKey("minecraft:custom_name"));
    }

    private static ItemStack potionWithCustomEffects(int count) {
        var stack = new ItemStack(Items.POTION);
        var contents = new PotionContents(java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.stream.IntStream.range(0, count).mapToObj(index -> new MobEffectInstance(MobEffects.JUMP, 20 + index, 0)).toList());
        stack.set(DataComponents.POTION_CONTENTS, contents);
        return stack;
    }

    @Test
    void enrichReportsPartialOnlyWhenEffectsAreBounded() {
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        assertTrue(NativeItemFacts.enrich(payload, potionWithCustomEffects(33), registries));
        var contents = ((Evidence.JsonObject) payload.get("potionContents")).values();
        assertEquals(new Evidence.JsonString("bounded"), contents.get("effectsStatus"));
        assertEquals(32, ((Evidence.JsonArray) contents.get("effects")).values().size());
        assertFalse(NativeItemFacts.enrich(new LinkedHashMap<>(), potionWithCustomEffects(32), registries));
        assertFalse(NativeItemFacts.enrich(new LinkedHashMap<>(), new ItemStack(Items.DIAMOND), registries));
    }

    @Test
    void inventoryRecordWithBoundedPotionEffectsIsPartial() {
        var scope = new Evidence.Scope("world", "en_us", "team", "native", 1);
        var record = NativeSnapshot.inventoryRecord(scope, potionWithCustomEffects(33), BuiltInRegistries.ITEM, registries,
                "main", 0, null, false);
        assertEquals(Evidence.Coverage.PARTIAL, record.source().coverage());
        var plain = NativeSnapshot.inventoryRecord(scope, new ItemStack(Items.DIAMOND), BuiltInRegistries.ITEM, registries,
                "main", 0, null, false);
        assertEquals(Evidence.Coverage.COMPLETE, plain.source().coverage());
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
