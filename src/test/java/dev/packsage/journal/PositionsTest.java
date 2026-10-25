package dev.packsage.journal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
