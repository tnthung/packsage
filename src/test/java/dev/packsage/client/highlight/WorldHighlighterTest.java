package dev.packsage.client.highlight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldHighlighterTest {
    @Test
    void distantNetherAndOverworldPlacesUseTheSameVisibleRange() {
        // L1 fortress and L2 village fixtures, with render distances of 12 and 8 chunks respectively.
        assertEquals(128, WorldHighlighter.markerDistance(Math.hypot(414, -228), 768));
        assertEquals(128, WorldHighlighter.markerDistance(Math.hypot(724, -306), 512));
        assertEquals(128, WorldHighlighter.markerDistance(Math.hypot(724, -306), 768));
        assertEquals(0, WorldHighlighter.markerDistance(0, 768));
    }

    @Test
    void placeColumnsStayWithinTheMarkerRangeAndInsideTheFarPlane() {
        // 12 chunks of render distance: the far plane is 12 * 16 * 4 blocks.
        assertEquals(50, WorldHighlighter.markerDistance(50, 768));
        assertEquals(128, WorldHighlighter.markerDistance(128, 768));
        assertEquals(128, WorldHighlighter.markerDistance(5_000, 768));
        // 2 chunks, the smallest render distance: the far plane is 128 blocks, so the column comes in to half of it.
        assertEquals(64, WorldHighlighter.markerDistance(100, 128));
        assertEquals(40, WorldHighlighter.markerDistance(40, 128));
    }
}
