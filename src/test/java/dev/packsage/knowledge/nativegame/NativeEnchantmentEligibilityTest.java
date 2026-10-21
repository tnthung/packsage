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
