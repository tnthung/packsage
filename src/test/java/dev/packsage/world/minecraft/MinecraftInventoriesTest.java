package dev.packsage.world.minecraft;

import com.mojang.serialization.Lifecycle;
import dev.packsage.knowledge.Evidence;
import dev.packsage.world.InventoryInfo;
import dev.packsage.world.LiveHolder;
import dev.packsage.world.Section;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.enchantment.Enchantment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftInventoriesTest {
    private static MappedRegistry<Enchantment> enchantments;
    private static MinecraftInventories inventories;

    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.bootStrap();
        enchantments = new MappedRegistry<>(Registries.ENCHANTMENT, Lifecycle.stable());
        var id = ResourceLocation.fromNamespaceAndPath("packsage", "sharpness");
        enchantments.register(ResourceKey.create(Registries.ENCHANTMENT, id), Enchantment.enchantment(Enchantment.definition(
                HolderSet.direct(Items.IRON_SWORD.builtInRegistryHolder()),
                10, 5, Enchantment.dynamicCost(1, 11), Enchantment.dynamicCost(21, 11), 1, EquipmentSlotGroup.MAINHAND)).build(id),
                RegistrationInfo.BUILT_IN);
        var provider = HolderLookup.Provider.create(Stream.<HolderLookup.RegistryLookup<?>>of(enchantments.asLookup()));
        inventories = new MinecraftInventories(() -> provider);
    }

    @Test
    void shulkerAndBundleItemsExposeTheirSections() {
        var items = NonNullList.withSize(27, ItemStack.EMPTY);
        items.set(3, new ItemStack(Items.STONE, 5));
        var shulker = new ItemStack(Items.SHULKER_BOX);
        shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
        var shulkerHolder = new LiveHolder.OfItem(shulker);
        var container = only(inventories.inventories(shulkerHolder));
        assertEquals("minecraft/container", container.id().text());
        assertEquals(27, container.slots());
        var opened = inventories.open(shulkerHolder, "container");
        assertTrue(opened.get("3").is(Items.STONE));
        assertEquals(5, opened.get("3").getCount());
        assertTrue(opened.get("26").isEmpty());
        assertNull(opened.get("27"));
        assertEquals(List.of("3"), opened.entries().stream().map(java.util.Map.Entry::getKey).toList());
        assertNull(inventories.open(shulkerHolder, "bundle"));

        var bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(new ItemStack(Items.DIRT, 2), new ItemStack(Items.STICK))));
        var bundleHolder = new LiveHolder.OfItem(bundle);
        var bundleInfo = only(inventories.inventories(bundleHolder));
        assertEquals("minecraft/bundle", bundleInfo.id().text());
        assertEquals(-1, bundleInfo.slots());
        var bundleInventory = inventories.open(bundleHolder, "bundle");
        assertTrue(bundleInventory.get("1").is(Items.STICK));
        assertEquals(List.of("0", "1"), bundleInventory.entries().stream().map(java.util.Map.Entry::getKey).toList());
    }

    @Test
    void openingEmptyContainerItemsLeavesComponentsUnchanged() {
        for (var stack : List.of(new ItemStack(Items.SHULKER_BOX), new ItemStack(Items.BUNDLE))) {
            var before = stack.getComponents();
            var beforePatch = stack.getComponentsPatch();
            var holder = new LiveHolder.OfItem(stack);
            for (var info : inventories.inventories(holder)) {
                var opened = inventories.open(holder, info.id().section());
                assertNotNull(opened);
                opened.entries();
                opened.get("0");
            }
            assertNotNull(inventories.inspectItem(stack));
            assertEquals(before, stack.getComponents());
            assertEquals(beforePatch, stack.getComponentsPatch());
        }
    }

    @Test
    void inspectItemReportsEnchantmentsDamageAndEligibility() {
        var sword = new ItemStack(Items.IRON_SWORD);
        sword.enchant(enchantments.getHolderOrThrow(ResourceKey.create(Registries.ENCHANTMENT,
                ResourceLocation.fromNamespaceAndPath("packsage", "sharpness"))), 2);
        sword.setDamageValue(10);
        var facts = inventories.inspectItem(sword).facts().values();
        assertEquals(number(1), facts.get("count"));
        assertEquals(number(1), facts.get("maxStackSize"));
        assertEquals(number(10), facts.get("damage"));
        assertEquals(number(250), facts.get("maxDamage"));
        assertEquals(new Evidence.JsonObject(java.util.Map.of("packsage:sharpness", number(2))), facts.get("enchantments"));
        var eligibility = (Evidence.JsonObject) facts.get("enchantmentEligibility");
        var supported = (Evidence.JsonArray) eligibility.values().get("supported");
        assertEquals(new Evidence.JsonString("packsage:sharpness"),
                ((Evidence.JsonObject) supported.values().getFirst()).values().get("id"));
        assertTrue(facts.containsKey("attributeModifiers"));
        assertNull(facts.get("storedEnchantments"));
        assertNull(facts.get("customName"));
    }

    @Test
    void eligibilityReportsUnavailableWhenRegistriesFail() {
        var failing = new MinecraftInventories(() -> {
            throw new IllegalStateException("no registries");
        });
        var eligibility = (Evidence.JsonObject) failing.inspectItem(new ItemStack(Items.IRON_SWORD)).facts().values().get("enchantmentEligibility");
        assertEquals(new Evidence.JsonString("unavailable"), eligibility.values().get("status"));
        assertEquals(new Evidence.JsonString("no registries"), eligibility.values().get("detail"));
    }

    @Test
    void inspectItemReportsComponentsFingerprintAndFullPotionFacts() {
        var splash = PotionContents.createItemStack(Items.SPLASH_POTION, Potions.STRENGTH);
        splash.set(DataComponents.POTION_CONTENTS, splash.get(DataComponents.POTION_CONTENTS)
                .withEffectAdded(new MobEffectInstance(MobEffects.JUMP, 200, 1)));
        var section = inventories.inspectItem(splash);
        assertEquals(Evidence.Coverage.COMPLETE, section.coverage());
        var facts = section.facts().values();
        assertTrue(facts.get("components") instanceof Evidence.JsonObject components
                && components.values().containsKey("minecraft:potion_contents"));
        assertTrue(facts.get("componentFingerprint") instanceof Evidence.JsonString);
        var potion = ((Evidence.JsonObject) facts.get("potion")).values();
        assertEquals(new Evidence.JsonString("minecraft:strength"), potion.get("potion"));
        assertEquals(0, BigDecimal.ONE.compareTo(((Evidence.JsonNumber) potion.get("durationScale")).value()));
        var effects = ((Evidence.JsonArray) potion.get("effects")).values();
        assertEquals(2, effects.size());
        for (var effect : effects) {
            var values = ((Evidence.JsonObject) effect).values();
            for (var key : List.of("amplifier", "durationTicks", "ambient", "visible", "showIcon", "origin")) {
                assertTrue(values.containsKey(key), key);
            }
        }
        var first = new ItemStack(Items.DIAMOND_SWORD);
        first.set(DataComponents.CUSTOM_NAME, Component.literal("First"));
        var second = new ItemStack(Items.DIAMOND_SWORD);
        second.set(DataComponents.CUSTOM_NAME, Component.literal("Second"));
        assertNotEquals(inventories.inspectItem(first).facts().values().get("componentFingerprint"),
                inventories.inspectItem(second).facts().values().get("componentFingerprint"));
    }

    @Test
    void everyItemReportsEnchantmentEligibilityEvenWhenNothingApplies() {
        var section = inventories.inspectItem(new ItemStack(Items.STONE));
        var eligibility = ((Evidence.JsonObject) section.facts().values().get("enchantmentEligibility")).values();
        assertEquals(new Evidence.JsonArray(List.of()), eligibility.get("supported"));
        assertEquals(new Evidence.JsonString("No enchantment can be applied to this item"), eligibility.get("none"));
        assertEquals(Evidence.Coverage.COMPLETE, section.coverage());
    }

    @Test
    void boundedEligibilityOrComponentsMakeTheSectionPartial() {
        var book = new ItemStack(Items.WRITTEN_BOOK);
        var pages = java.util.stream.IntStream.range(0, 10)
                .mapToObj(index -> Filterable.passThrough((Component) Component.literal("x".repeat(1000)))).toList();
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough("Long"), "Author", 0, pages, true));
        Section section = inventories.inspectItem(book);
        var components = (Evidence.JsonObject) section.facts().values().get("components");
        assertEquals(new Evidence.JsonString("bounded"), components.values().get("status"));
        assertEquals(Evidence.Coverage.PARTIAL, section.coverage());
    }

    private static InventoryInfo only(List<InventoryInfo> infos) {
        assertEquals(1, infos.size());
        return infos.getFirst();
    }

    private static Evidence.JsonNumber number(int value) {
        return new Evidence.JsonNumber(new BigDecimal(value));
    }
}
