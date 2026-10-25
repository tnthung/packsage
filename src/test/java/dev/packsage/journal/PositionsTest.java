package dev.packsage.journal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PositionsTest {
    @Test
    void packRoundTripsExtremeCoordinates() {
        int[][] coordinates = {{33_554_431, 2_047, -33_554_431}, {0, -64, 0}, {-1, 319, -1}};
        for (var c : coordinates) {
            var packed = Positions.pack(c[0], c[1], c[2]);
            assertEquals(c[0], Positions.x(packed));
            assertEquals(c[1], Positions.y(packed));
            assertEquals(c[2], Positions.z(packed));
        }
    }

    @Test
    void blobRoundTripsInOrderIncludingRepeats() {
        long[] positions = {
                Positions.pack(10, 64, -3),
                Positions.pack(10, 64, -3),
                Positions.pack(-5000, -60, 7000),
                Positions.pack(11, 64, -3)
        };
        assertArrayEquals(positions, Positions.decode(Positions.encode(positions, positions.length)));
    }

    @Test
    void aTunnelOfTwoThousandBlocksPacksSmall() {
        var positions = new long[2000];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = Positions.pack(i, 64, 0);
        }
        var blob = Positions.encode(positions, positions.length);
        assertTrue(blob.length < 6_100, () -> blob.length + " bytes");
        assertArrayEquals(positions, Positions.decode(blob));
    }

    @Test
    void boxGrowContainsAndIntersects() {
        var box = Box.of(0, 64, 0).grow(16);
        assertTrue(box.contains(16, 48, -16));
        assertFalse(box.contains(17, 64, 0));
        assertTrue(box.intersects(Box.of(16, 80, 16)));
        assertFalse(box.intersects(Box.of(17, 64, 0).include(20, 64, 0)));
        assertTrue(box.intersects(Box.of(17, 64, 0).include(-20, 64, 0)));
    }

    @Test
    void packRejectsOutOfRangeCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> Positions.pack(33_554_432, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> Positions.pack(0, 0, -33_554_432));
        assertThrows(IllegalArgumentException.class, () -> Positions.pack(0, 2_048, 0));
        assertThrows(IllegalArgumentException.class, () -> Positions.pack(Integer.MIN_VALUE, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> Positions.pack(0, Integer.MIN_VALUE, 0));
        assertThrows(IllegalArgumentException.class, () -> Positions.pack(0, 0, Integer.MIN_VALUE));
    }

    @Test
    void decodeRejectsANegativeCount() {
        byte[] minusOne = {-1, -1, -1, -1, -1, -1, -1, -1, -1, 1};
        assertThrows(IllegalArgumentException.class, () -> Positions.decode(minusOne));
    }

    @Test
    void eventTypesRoundTripTheirIds() {
        for (var type : EventType.values()) {
            assertEquals(type, EventType.byId(type.id()));
        }
        assertThrows(IllegalArgumentException.class, () -> EventType.byId("note"));
    }

    @Test
    void journalEventsCopyPositionsAndCompareByContent() {
        long[] positions = {Positions.pack(1, 64, 2)};
        var event = event(positions, null);
        positions[0] = Positions.pack(9, 9, 9);
        assertEquals(Positions.pack(1, 64, 2), event.positions()[0]);
        event.positions()[0] = Positions.pack(9, 9, 9);
        assertEquals(Positions.pack(1, 64, 2), event.positions()[0]);
        var same = event(new long[]{Positions.pack(1, 64, 2)}, null);
        assertEquals(event, same);
        assertEquals(event.hashCode(), same.hashCode());
        assertNotEquals(event, event(new long[]{Positions.pack(1, 65, 2)}, null));
        assertTrue(event.toString().contains(Long.toString(Positions.pack(1, 64, 2))), event::toString);
        assertThrows(IllegalArgumentException.class, () -> event(new long[0], null));
        assertThrows(IllegalArgumentException.class, () -> event(null, null));
    }

    @Test
    void journalEventDetailIsCutWithoutSplittingASurrogatePair() {
        long[] positions = {Positions.pack(0, 64, 0)};
        assertNull(event(positions, null).detail());
        assertEquals(2048, event(positions, "a".repeat(3000)).detail().length());
        assertEquals("a".repeat(2048), event(positions, "a".repeat(2048)).detail());
        var split = event(positions, "a".repeat(2047) + "😀b").detail();
        assertEquals("a".repeat(2047), split);
    }

    private static JournalEvent event(long[] positions, String detail) {
        return new JournalEvent(EventType.BREAK, 10L, 1_000L, "minecraft:overworld", "player:A", "minecraft:sand", null,
                1L, positions, detail);
    }
}
