package dev.packsage.knowledge.nativegame;

import com.google.gson.JsonParser;
import dev.packsage.knowledge.Evidence;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeSnapshotInventoryTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "native", 1);

    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.bootStrap();
    }

    @Test
    void inventoryRecordPreservesSlotLabelsAndMergedStackComponents() {
        var stack = new ItemStack(Items.DIAMOND_PICKAXE, 2);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Survey Pick"));
        stack.set(DataComponents.DAMAGE, 7);
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

        var selected = NativeSnapshot.inventoryRecord(SCOPE, stack, BuiltInRegistries.ITEM, registries,
                "hotbar", 4, "mainhand", true);
        var selectedPayload = selected.payload().values();
        var encodedComponents = JsonParser.parseString(Evidence.serialize(selectedPayload.get("components"))).getAsJsonObject();

        assertEquals("inventory:hotbar:4", selected.source().sourceHandle());
        assertEquals("minecraft:diamond_pickaxe", selected.title());
        assertEquals("Survey Pick", ((Evidence.JsonString) selectedPayload.get("name")).value());
        assertEquals("hotbar", ((Evidence.JsonString) selectedPayload.get("section")).value());
        assertEquals(4, ((Evidence.JsonNumber) selectedPayload.get("slot")).value().intValueExact());
        assertEquals("mainhand", ((Evidence.JsonString) selectedPayload.get("equipmentSlot")).value());
        assertTrue(((Evidence.JsonBoolean) selectedPayload.get("selectedHotbar")).value());
        assertEquals(2, ((Evidence.JsonNumber) selectedPayload.get("count")).value().intValueExact());
        assertEquals(7, encodedComponents.get("minecraft:damage").getAsInt());
        assertEquals(1561, encodedComponents.get("minecraft:max_damage").getAsInt());
        assertTrue(encodedComponents.has("minecraft:attribute_modifiers"));

        var armor = NativeSnapshot.inventoryRecord(SCOPE, stack, BuiltInRegistries.ITEM, registries,
                "armor", 3, "head", false);
        var offhand = NativeSnapshot.inventoryRecord(SCOPE, stack, BuiltInRegistries.ITEM, registries,
                "offhand", 0, "offhand", false);
        assertEquals("inventory:armor:3", armor.source().sourceHandle());
        assertEquals("head", ((Evidence.JsonString) armor.payload().values().get("equipmentSlot")).value());
        assertEquals("inventory:offhand:0", offhand.source().sourceHandle());
        assertEquals("offhand", ((Evidence.JsonString) offhand.payload().values().get("equipmentSlot")).value());
        assertFalse(((Evidence.JsonBoolean) armor.payload().values().get("selectedHotbar")).value());
    }

    @Test
    void inventoryMetadataOmitsNestedContainerContentsAndMarksTheOmission() {
        var nested = new ItemStack(Items.SHULKER_BOX);
        nested.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(java.util.List.of(new ItemStack(Items.DIAMOND))));
        var stack = new ItemStack(Items.SHULKER_BOX);
        stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(java.util.List.of(nested)));
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

        var record = NativeSnapshot.inventoryRecord(SCOPE, stack, BuiltInRegistries.ITEM, registries,
                "main", 0, null, false);
        var payload = record.payload().values();
        var encodedComponents = JsonParser.parseString(Evidence.serialize(payload.get("components"))).getAsJsonObject();

        assertFalse(encodedComponents.has("minecraft:container"));
        assertTrue(((Evidence.JsonBoolean) payload.get("nestedStorageContentsOmitted")).value());

        var bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(java.util.List.of(new ItemStack(Items.DIAMOND))));
        var bundleRecord = NativeSnapshot.inventoryRecord(SCOPE, bundle, BuiltInRegistries.ITEM, registries,
                "main", 1, null, false);
        var bundleComponents = JsonParser.parseString(Evidence.serialize(bundleRecord.payload().values().get("components")))
                .getAsJsonObject();
        assertFalse(bundleComponents.has("minecraft:bundle_contents"));
        assertTrue(((Evidence.JsonBoolean) bundleRecord.payload().values().get("nestedStorageContentsOmitted")).value());
    }
}
