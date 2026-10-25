package dev.packsage.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
    void progressIdRejectsInvalidAdaptersAndParsesEdgeCases() {
        assertThrows(IllegalArgumentException.class, () -> new ProgressId("a:b", "x"));
        assertThrows(IllegalArgumentException.class, () -> new ProgressId("a/b", "x"));
        assertThrows(IllegalArgumentException.class, () -> new ProgressId("", "x"));
        assertThrows(IllegalArgumentException.class, () -> ProgressId.parse("/x"));
        assertThrows(IllegalArgumentException.class, () -> ProgressId.parse("x/"));
        assertEquals(new ProgressId(null, "x"), ProgressId.parse("x"));
    }

    @Test
    void aBareLocalIdWithASlashMustBeNamespaced() {
        assertThrows(IllegalArgumentException.class, () -> new ProgressId(null, "story/root"));
        assertDoesNotThrow(() -> new ProgressId(null, "minecraft:story/root"));
        assertDoesNotThrow(() -> new ProgressId("ftbquests", "chapter/quest"));
    }

    @Test
    void aHandleTitleIsNullExactlyWhenHidden() {
        var id = new ProgressId("minecraft", "minecraft:story/root");
        assertThrows(IllegalArgumentException.class, () -> new ProgressHandle(id, id, "Minecraft", ProgressStatus.HIDDEN));
        assertThrows(IllegalArgumentException.class, () -> new ProgressHandle(id, id, null, ProgressStatus.DONE));
        assertThrows(IllegalArgumentException.class, () -> new ProgressHandle(id, id, " ", ProgressStatus.AVAILABLE));
        assertDoesNotThrow(() -> new ProgressHandle(id, id, null, ProgressStatus.HIDDEN));
    }

    @Test
    void statisticRequiresACategory() {
        assertThrows(IllegalArgumentException.class, () -> new Statistic("minecraft", null, "minecraft:stone", "Stone", 1, StatisticUnit.COUNT, 1));
        assertThrows(IllegalArgumentException.class, () -> new Statistic("minecraft", " ", "minecraft:stone", "Stone", 1, StatisticUnit.COUNT, 1));
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
