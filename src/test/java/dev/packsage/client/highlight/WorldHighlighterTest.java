package dev.packsage.client.highlight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldHighlighterTest {
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
