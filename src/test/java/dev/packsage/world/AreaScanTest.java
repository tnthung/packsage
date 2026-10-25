package dev.packsage.world;

import dev.packsage.knowledge.Evidence;
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
    void anEarlyStopResumesAtTheChunkItStoppedIn() {
        assertEquals("4:0", AreaScan.resumeCursor(4, 0, false, 9).text());
        assertEquals("4:7", AreaScan.resumeCursor(4, 7, false, 0).text(), "a chunk not entered yet keeps the cursor's skip");
        assertEquals("4:9", AreaScan.resumeCursor(4, 0, true, 9).text(), "matches seen in the chunk so far are skipped");
        assertEquals("4:7", AreaScan.resumeCursor(4, 7, true, 3).text(), "matches an earlier page returned stay skipped");
    }

    @Test
    void anEarlyStopNamesTheBoundAndHowToContinue() {
        var listing = AreaScan.stopNote(5, 40, true);
        assertTrue(listing.contains("work limit") && listing.contains("5 of 40 chunks") && listing.contains("cursor"), listing);
        var summary = AreaScan.stopNote(5, 40, false);
        assertTrue(summary.contains("5 of 40 chunks") && summary.contains("smaller radius") && !summary.contains("cursor"), summary);
    }

    @Test
    void aPageKeepsItsLoadNotesApartFromItsCursorNotes() {
        var page = AreaScan.page(List.of(), "3:0", 1, 2, List.of(AreaScan.PAGE_LIMIT_NOTE));
        assertEquals("1 chunks in range were not loaded and were not fully read; "
                + "2 chunks in range did not have their entities loaded yet, so entities there were not read; " + AreaScan.PAGE_LIMIT_NOTE, page.note());
        assertEquals("1 chunks in range were not loaded and were not fully read; "
                + "2 chunks in range did not have their entities loaded yet, so entities there were not read", page.loadNote());
        assertEquals(Evidence.Coverage.PARTIAL, page.coverage());
        var complete = AreaScan.page(List.of(), null, 0, 0, List.of());
        assertEquals(null, complete.note());
        assertEquals(null, complete.loadNote());
        assertEquals(Evidence.Coverage.COMPLETE, complete.coverage());
    }

    @Test
    void blockKindsAreCachedPerState() {
        var kinds = new AreaScan.BlockKinds();

        assertEquals("minecraft:stone", kinds.of(Blocks.STONE.defaultBlockState()));
        assertSame(kinds.of(Blocks.STONE.defaultBlockState()), kinds.of(Blocks.STONE.defaultBlockState()));
    }
}
