package dev.packsage.world;

import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaScanTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.bootStrap();
    }

    @Test
    void chunksInRangeSkipCornersBeyondTheRadiusInAStableOrder() {
        var chunks = AreaScan.chunksInRange(new Vec3(0.5, 64, 0.5), 20);

        assertEquals(12, chunks.size());
        assertEquals(new ChunkPos(-2, -1), chunks.get(0));
        assertFalse(chunks.contains(new ChunkPos(-2, -2)));
        assertFalse(chunks.contains(new ChunkPos(1, 1)));
        assertTrue(chunks.contains(new ChunkPos(-2, 0)));
        assertEquals(chunks, AreaScan.chunksInRange(new Vec3(0.5, 64, 0.5), 20));
    }

    @Test
    void sectionsOutsideTheVerticalRangeAreSkipped() {
        assertTrue(AreaScan.sectionOverlaps(48, 63, 95));
        assertTrue(AreaScan.sectionOverlaps(80, 63, 95));
        assertFalse(AreaScan.sectionOverlaps(32, 63, 95));
        assertFalse(AreaScan.sectionOverlaps(96, 63, 95));
    }

    @Test
    void cursorsRoundTripAndRejectMalformedText() {
        assertEquals("3:17", AreaScan.Cursor.parse("3:17").text());
        assertEquals(AreaScan.Cursor.START, AreaScan.Cursor.parse(null));
        for (var text : List.of("", "3", "3:", ":1", "a:1", "-1:0", "1:-1")) {
            assertThrows(IllegalArgumentException.class, () -> AreaScan.Cursor.parse(text), text);
        }
    }

    @Test
    void blockKindsAreCachedPerState() {
        var kinds = new AreaScan.BlockKinds();

        assertEquals("minecraft:stone", kinds.of(Blocks.STONE.defaultBlockState()));
        assertSame(kinds.of(Blocks.STONE.defaultBlockState()), kinds.of(Blocks.STONE.defaultBlockState()));
    }
}
