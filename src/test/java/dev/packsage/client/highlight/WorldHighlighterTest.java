package dev.packsage.client.highlight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void distantMarkerScaleMovesTheTargetToThe128BlockClamp() {
        assertEquals(0.64, WorldHighlighter.markerScale(200, 768));
        assertEquals(1, WorldHighlighter.markerScale(0, 768));
    }

    @Test
    void markerBeamRisesFromItsBlockToTheBuildCeiling() {
        assertEquals(256, WorldHighlighter.markerBeamHeight(64, 320));
        assertEquals(0, WorldHighlighter.markerBeamHeight(320, 320));
    }

    @Test
    void highlightsExpireAtTheirDeadline() {
        assertFalse(WorldHighlighter.isExpired(30_000, 29_999));
        assertTrue(WorldHighlighter.isExpired(30_000, 30_000));
    }
}
