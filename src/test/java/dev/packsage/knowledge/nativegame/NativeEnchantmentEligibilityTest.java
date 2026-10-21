package dev.packsage.knowledge.nativegame;

import com.mojang.serialization.Lifecycle;
import dev.packsage.knowledge.Evidence;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NativeEnchantmentEligibilityTest {
    private static MappedRegistry<Enchantment> registry;
    private static Holder.Reference<Enchantment> swordOnlyHolder;

    @BeforeAll
    static void registerEnchantments() {
        Bootstrap.bootStrap();
        registry = new MappedRegistry<>(Registries.ENCHANTMENT, Lifecycle.stable());
        swordOnlyHolder = register("sword_only", Enchantment.enchantment(Enchantment.definition(
                HolderSet.direct(Items.DIAMOND_SWORD.builtInRegistryHolder()),
                HolderSet.direct(Items.DIAMOND_SWORD.builtInRegistryHolder()),
                10, 5, Enchantment.dynamicCost(1, 10), Enchantment.dynamicCost(21, 10), 1, EquipmentSlotGroup.MAINHAND)));
        register("bow_only", Enchantment.enchantment(Enchantment.definition(
                HolderSet.direct(Items.BOW.builtInRegistryHolder()),
                10, 1, Enchantment.dynamicCost(1, 10), Enchantment.dynamicCost(21, 10), 1, EquipmentSlotGroup.MAINHAND)));
        register("sword_exclusive", Enchantment.enchantment(Enchantment.definition(
                HolderSet.direct(Items.DIAMOND_SWORD.builtInRegistryHolder()),
                10, 1, Enchantment.dynamicCost(1, 10), Enchantment.dynamicCost(21, 10), 1, EquipmentSlotGroup.MAINHAND))
                .exclusiveWith(HolderSet.direct(swordOnlyHolder)));
    }

    private static Holder.Reference<Enchantment> register(String path, Enchantment.Builder builder) {
        var id = ResourceLocation.fromNamespaceAndPath("packsage", path);
        return registry.register(ResourceKey.create(Registries.ENCHANTMENT, id), builder.build(id), RegistrationInfo.BUILT_IN);
    }

    @Test
    void reportsSupportedPrimaryAndInspectedCounts() {
        var result = NativeEnchantmentEligibility.read(new ItemStack(Items.DIAMOND_SWORD), registry);
        assertEquals(new Evidence.JsonBoolean(true), result.values().get("enchantable"));
        assertEquals(List.of("packsage:sword_only", "packsage:sword_exclusive"), ids(result));
        assertEquals(new Evidence.JsonBoolean(true), entry(result, "packsage:sword_only").values().get("primary"));
        assertEquals(0, new BigDecimal(3).compareTo(((Evidence.JsonNumber) result.values().get("inspected")).value()));
        assertEquals(new Evidence.JsonString("complete"), result.values().get("status"));
    }

    @Test
    void existingEnchantmentMakesExclusiveIncompatibleAndReportsCurrentLevel() {
        var stack = new ItemStack(Items.DIAMOND_SWORD);
        stack.enchant(swordOnlyHolder, 2);
        var result = NativeEnchantmentEligibility.read(stack, registry);
        assertEquals(new Evidence.JsonBoolean(false), entry(result, "packsage:sword_exclusive").values().get("compatibleWithCurrent"));
        assertEquals(new Evidence.JsonBoolean(true), entry(result, "packsage:sword_only").values().get("compatibleWithCurrent"));
        assertEquals(0, new BigDecimal(2).compareTo(((Evidence.JsonNumber) entry(result, "packsage:sword_only").values().get("currentLevel")).value()));
        assertEquals(new Evidence.JsonBoolean(false), result.values().get("enchantable"));
    }

    @Test
    void unsupportedItemHasNoEntries() {
        assertEquals(List.of(), ids(NativeEnchantmentEligibility.read(new ItemStack(Items.STICK), registry)));
    }

    @Test
    void exclusiveEntriesListEachOtherAndConflictFreeEntryIsEmpty() {
        var sword = NativeEnchantmentEligibility.read(new ItemStack(Items.DIAMOND_SWORD), registry);
        assertEquals(List.of("packsage:sword_exclusive"), exclusive(entry(sword, "packsage:sword_only")));
        assertEquals(List.of("packsage:sword_only"), exclusive(entry(sword, "packsage:sword_exclusive")));
        var bow = NativeEnchantmentEligibility.read(new ItemStack(Items.BOW), registry);
        assertEquals(List.of(), exclusive(entry(bow, "packsage:bow_only")));
    }

    @Test
    void exclusiveListIsCappedAndMakesStatusBoundedNotComplete() {
        var big = new MappedRegistry<Enchantment>(Registries.ENCHANTMENT, Lifecycle.stable());
        var others = new java.util.ArrayList<Holder<Enchantment>>();
        for (int i = 0; i < 40; i++) {
            var id = ResourceLocation.fromNamespaceAndPath("packsage", "e" + String.format("%02d", i));
            others.add(big.register(ResourceKey.create(Registries.ENCHANTMENT, id), Enchantment.enchantment(Enchantment.definition(
                    HolderSet.direct(Items.DIAMOND_SWORD.builtInRegistryHolder()),
                    10, 1, Enchantment.dynamicCost(1, 10), Enchantment.dynamicCost(21, 10), 1, EquipmentSlotGroup.MAINHAND)).build(id),
                    RegistrationInfo.BUILT_IN));
        }
        var hubId = ResourceLocation.fromNamespaceAndPath("packsage", "hub");
        big.register(ResourceKey.create(Registries.ENCHANTMENT, hubId), Enchantment.enchantment(Enchantment.definition(
                HolderSet.direct(Items.DIAMOND_SWORD.builtInRegistryHolder()),
                10, 1, Enchantment.dynamicCost(1, 10), Enchantment.dynamicCost(21, 10), 1, EquipmentSlotGroup.MAINHAND))
                .exclusiveWith(HolderSet.direct(others)).build(hubId), RegistrationInfo.BUILT_IN);
        var result = NativeEnchantmentEligibility.read(new ItemStack(Items.DIAMOND_SWORD), big);
        var hub = entry(result, "packsage:hub");
        assertEquals(32, exclusive(hub).size());
        assertEquals(new Evidence.JsonString("bounded"), hub.values().get("exclusiveWithStatus"));
        assertEquals(new Evidence.JsonString("bounded"), result.values().get("status"));
        org.junit.jupiter.api.Assertions.assertFalse(NativeEnchantmentEligibility.complete(result));
    }

    private static List<String> exclusive(Evidence.JsonObject entry) {
        return ((Evidence.JsonArray) entry.values().get("exclusiveWith")).values().stream()
                .map(value -> ((Evidence.JsonString) value).value()).toList();
    }

    private static List<String> ids(Evidence.JsonObject result) {
        return ((Evidence.JsonArray) result.values().get("supported")).values().stream()
                .map(value -> ((Evidence.JsonString) ((Evidence.JsonObject) value).values().get("id")).value()).toList();
    }

    private static Evidence.JsonObject entry(Evidence.JsonObject result, String id) {
        return ((Evidence.JsonArray) result.values().get("supported")).values().stream()
                .map(value -> (Evidence.JsonObject) value)
                .filter(value -> new Evidence.JsonString(id).equals(value.values().get("id"))).findFirst().orElseThrow();
    }
}
