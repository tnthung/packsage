package dev.packsage.knowledge.storage;

import dev.packsage.knowledge.Evidence;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StorageReaderTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "resources", "team", "runtime", 1);

    @Test
    void requestCopiesFiltersAndAcceptsOnlyBoundedPages() {
        var identities = new ArrayList<>(List.of("minecraft:chest"));
        var request = new StorageReader.Request(StorageReader.Target.OPENED, null, identities, 32, null, SCOPE);
        identities.clear();

        assertEquals(List.of("minecraft:chest"), request.identities());
        assertThrows(IllegalArgumentException.class,
                () -> new StorageReader.Request(StorageReader.Target.HELD, null, List.of(), 0, null, SCOPE));
        assertThrows(IllegalArgumentException.class,
                () -> new StorageReader.Request(StorageReader.Target.TARGETED, null, List.of(), 33, null, SCOPE));
    }
}
