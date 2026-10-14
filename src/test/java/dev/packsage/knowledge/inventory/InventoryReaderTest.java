package dev.packsage.knowledge.inventory;

import dev.packsage.knowledge.Evidence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InventoryReaderTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "native", 1);

    @Test
    void rejectsMalformedOrExcessiveItemIdentities() {
        assertThrows(IllegalArgumentException.class, () -> new InventoryReader.Request(InventoryReader.Operation.READ,
                List.of("minecraft:stone with spaces"), null, List.of(), 32, null, SCOPE));
        assertThrows(IllegalArgumentException.class, () -> new InventoryReader.Request(InventoryReader.Operation.READ,
                java.util.Collections.nCopies(33, "minecraft:stone"), null, List.of(), 32, null, SCOPE));
    }

    @Test
    void searchRequiresLiteralTextAndOnlyKnownFields() {
        assertThrows(IllegalArgumentException.class, () -> new InventoryReader.Request(InventoryReader.Operation.SEARCH,
                List.of(), "  ", List.of(), 8, null, SCOPE));
        assertThrows(IllegalArgumentException.class, () -> new InventoryReader.Request(InventoryReader.Operation.SEARCH,
                List.of(), "diamond", List.of("components"), 8, null, SCOPE));
        assertThrows(IllegalArgumentException.class, () -> new InventoryReader.Request(InventoryReader.Operation.SEARCH,
                List.of(), "diamond", List.of("name", "id", "tag", "tooltip", "name"), 8, null, SCOPE));
        assertThrows(IllegalArgumentException.class, () -> new InventoryReader.Request(InventoryReader.Operation.READ,
                List.of(), null, List.of(), 8, "x".repeat(1025), SCOPE));
        assertEquals(List.of("name", "id"), new InventoryReader.Request(InventoryReader.Operation.SEARCH,
                List.of(), "diamond", List.of(), 8, null, SCOPE).fields());
    }
}
