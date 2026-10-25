package dev.packsage.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProgressTypesTest {
    @Test
    void progressIdRoundTripsAndAcceptsABareLocalId() {
        var id = ProgressId.parse("minecraft/minecraft:story/root");
        assertEquals(new ProgressId("minecraft", "minecraft:story/root"), id);
        assertEquals("minecraft/minecraft:story/root", id.text());
        assertEquals(id, ProgressId.parse(id.text()));
        var bare = ProgressId.parse("minecraft:story/root");
        assertNull(bare.adapter());
        assertEquals("minecraft:story/root", bare.local());
        assertThrows(IllegalArgumentException.class, () -> ProgressId.parse(" "));
    }

    @Test
    void statusWireNamesRoundTrip() {
        assertEquals("in_progress", ProgressStatus.IN_PROGRESS.wire());
        assertEquals(ProgressStatus.HIDDEN, ProgressStatus.fromWire("hidden"));
        for (var status : ProgressStatus.values()) {
            assertEquals(status, ProgressStatus.fromWire(status.wire()));
        }
    }
}
