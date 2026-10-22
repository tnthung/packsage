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
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeServerStorageReaderTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.bootStrap();
    }

    @Test
    void lockedAndOutOfReachContainersAreRead() {
        var furnace = new FurnaceBlockEntity(BlockPos.ZERO, Blocks.FURNACE.defaultBlockState());
        furnace.applyComponents(DataComponentMap.builder()
                .set(DataComponents.LOCK, new LockCode("key"))
                .set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.SAND, 64)))).build(),
                DataComponentPatch.EMPTY);

        assertTrue(NativeServerStorageReader.mayReadContainerContents(false));
        assertEquals(64, furnace.getItem(0).getCount());
        assertEquals(List.of(1, 2), NativeServerStorageReader.blockEmptySlots(furnace, "container").stream()
                .map(value -> ((Evidence.JsonNumber) ((Evidence.JsonObject) value).values().get("componentSlot")).value().intValueExact())
                .toList());
    }

    @Test
    void containersWithPendingLootAreStillNotRead() {
        assertTrue(NativeServerStorageReader.mayReadContainerContents(false));
        assertFalse(NativeServerStorageReader.mayReadContainerContents(true));
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
