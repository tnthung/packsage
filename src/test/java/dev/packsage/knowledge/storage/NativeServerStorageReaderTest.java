package dev.packsage.knowledge.storage;

import net.minecraft.world.LockCode;
import org.junit.jupiter.api.Test;

import java.util.UUID;

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
}
