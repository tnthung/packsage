package dev.packsage.knowledge.storage;

import dev.packsage.knowledge.Evidence;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.LockCode;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NativeServerStorageReaderTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.bootStrap();
    }

    @Test
    void lockedAndOutOfReachContainersAreRead() {
        var farAway = new BlockPos(29_999_000, 64, 29_999_000);
        var furnace = new FurnaceBlockEntity(farAway, Blocks.FURNACE.defaultBlockState());
        furnace.applyComponents(DataComponentMap.builder()
                .set(DataComponents.LOCK, new LockCode("key"))
                .set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.SAND, 64)))).build(),
                DataComponentPatch.EMPTY);

        assertEquals(new LockCode("key"), furnace.collectComponents().get(DataComponents.LOCK));
        assertNull(NativeServerStorageReader.unreadableReason(furnace));
        assertEquals(64, furnace.getItem(0).getCount());
    }

    @Test
    void containersWithPendingLootAreStillNotRead() {
        var chest = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        assertNull(NativeServerStorageReader.unreadableReason(chest));

        chest.setLootTable(BuiltInLootTables.SPAWN_BONUS_CHEST);
        assertEquals("pending loot table contents are unavailable", NativeServerStorageReader.unreadableReason(chest));
    }

    @Test
    void entityContainersShareThePendingLootDecision() {
        assertNull(NativeServerStorageReader.pendingLootReason(null));
        assertEquals("pending loot table contents are unavailable",
                NativeServerStorageReader.pendingLootReason(BuiltInLootTables.SPAWN_BONUS_CHEST));
    }

    @Test
    void targetedBlockEmptySlotsUsePhysicalContainerCapacity() {
        var furnace = new FurnaceBlockEntity(BlockPos.ZERO, Blocks.FURNACE.defaultBlockState());
        furnace.applyComponents(DataComponentMap.builder().set(DataComponents.CONTAINER,
                ItemContainerContents.fromItems(List.of(new ItemStack(Items.SAND, 64)))).build(), DataComponentPatch.EMPTY);

        assertEquals(64, furnace.getItem(0).getCount());
        assertEquals(List.of(1, 2), NativeServerStorageReader.blockEmptySlots(furnace, "container").stream()
                .map(value -> ((Evidence.JsonNumber) ((Evidence.JsonObject) value).values().get("componentSlot")).value().intValueExact())
                .toList());
        assertEquals(List.of("container", "container"), NativeServerStorageReader.blockEmptySlots(furnace, "container").stream()
                .map(value -> ((Evidence.JsonString) ((Evidence.JsonObject) value).values().get("section")).value())
                .toList());
    }

    @Test
    void targetedBlockWithNoItemsStillReportsEveryPhysicalSlotAsEmpty() {
        var furnace = new FurnaceBlockEntity(BlockPos.ZERO, Blocks.FURNACE.defaultBlockState());

        assertEquals(List.of(0, 1, 2), NativeServerStorageReader.blockEmptySlots(furnace, "container").stream()
                .map(value -> ((Evidence.JsonNumber) ((Evidence.JsonObject) value).values().get("componentSlot")).value().intValueExact())
                .toList());
    }
}
