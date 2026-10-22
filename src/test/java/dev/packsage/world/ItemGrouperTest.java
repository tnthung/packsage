package dev.packsage.world;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemGrouperTest {
    private static final UUID PLAYER = UUID.fromString("3f1c0000-0000-0000-0000-000000000001");

    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.bootStrap();
    }

    private static ItemPath path(int slot) {
        return new ItemPath(new PathRoot.Player(PLAYER), java.util.List.of(
                new PathStep(new InventoryId("minecraft", "container"), Integer.toString(slot), "minecraft:chest")));
    }

    @Test
    void groupsByIdSumsCountsCountsVariantsAndCapsLocations() {
        var grouper = new ItemGrouper();
        for (var i = 0; i < 10; i++) {
            grouper.add(path(i), new ItemStack(Items.STONE, 64), false);
        }
        grouper.add(path(10), new ItemStack(Items.IRON_SWORD), false);
        grouper.add(path(11), new ItemStack(Items.IRON_SWORD), false);
        var named = new ItemStack(Items.IRON_SWORD);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Excalibur"));
        grouper.add(path(12), named, true);

        var entries = grouper.entries();

        assertEquals(2, entries.size());
        var stone = entries.get(0);
        assertEquals("minecraft:stone", stone.item());
        assertEquals(640, stone.count());
        assertEquals(1, stone.variants());
        assertEquals(8, stone.locations().size());
        assertEquals(path(0), stone.locations().get(0));
        assertFalse(stone.holdsItems());
        var sword = entries.get(1);
        assertEquals("minecraft:iron_sword", sword.item());
        assertEquals(3, sword.count());
        assertEquals(2, sword.variants());
        assertEquals(3, sword.locations().size());
        assertTrue(sword.holdsItems());
    }
}
