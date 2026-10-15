package dev.packsage.knowledge.storage;

import dev.packsage.knowledge.Evidence;
import net.minecraft.core.BlockPos;
import net.minecraft.world.LockCode;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeServerStorageReaderTest {
    @Test
    void privateOwnerReadRequiresTheSameSingleConnectedPlayerAndAnUnpublishedServer() {
        var owner = UUID.randomUUID();
        var other = UUID.randomUUID();

        assertTrue(NativeServerStorageReader.privateOwnerReadAllowed(false, owner, owner, owner, 1));
        assertFalse(NativeServerStorageReader.privateOwnerReadAllowed(true, owner, owner, owner, 1));
        assertFalse(NativeServerStorageReader.privateOwnerReadAllowed(false, other, owner, owner, 1));
        assertFalse(NativeServerStorageReader.privateOwnerReadAllowed(false, owner, other, owner, 1));
        assertFalse(NativeServerStorageReader.privateOwnerReadAllowed(false, owner, owner, other, 1));
        assertFalse(NativeServerStorageReader.privateOwnerReadAllowed(false, owner, owner, owner, 2));
        assertFalse(NativeServerStorageReader.privateOwnerReadAllowed(false, null, owner, owner, 1));
    }

    @Test
    void lockedOrUnpackedLootContainersAreNeverRead() {
        assertTrue(NativeServerStorageReader.mayReadContainerContents(LockCode.NO_LOCK, false));
        assertFalse(NativeServerStorageReader.mayReadContainerContents(new LockCode("key"), false));
        assertFalse(NativeServerStorageReader.mayReadContainerContents(LockCode.NO_LOCK, true));
        assertFalse(NativeServerStorageReader.mayReadContainerContents(new LockCode("key"), true));
    }

    @Test
    void targetedBlockEmptySlotsUsePhysicalContainerCapacity() {
        var furnace = new FurnaceBlockEntity(BlockPos.ZERO, Blocks.FURNACE.defaultBlockState());
        furnace.setItem(0, new ItemStack(Items.SAND, 64));

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
