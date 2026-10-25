package dev.packsage.world;

import dev.packsage.knowledge.Evidence;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.HashSet;
import java.util.UUID;

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
    void removingAnEarlierEntityBetweenPagesDoesNotSkipTheNextMatch() throws Exception {
        var world = DenseWorld.create(130);
        var scan = new AreaScan(new WorldRegistry());
        var first = scan.listEntities(world, new Vec3(8, 64, 8), 4, KindFilter.ANY, null);
        while (first.step()) {
        }
        var ids = new HashSet<UUID>();
        first.result().items().forEach(entity -> ids.add(entity.id()));
        world.entities.removeIf(entity -> entity.getUUID().equals(first.result().items().getFirst().id()));
        var cursor = first.result().nextCursor();
        while (cursor != null) {
            var slice = scan.listEntities(world, new Vec3(8, 64, 8), 4, KindFilter.ANY, cursor);
            while (slice.step()) {
            }
            for (var entity : slice.result().items()) assertTrue(ids.add(entity.id()), "repeated " + entity.id());
            cursor = slice.result().nextCursor();
        }
        assertEquals(130, ids.size());
    }

    @Test
    void entityMutationBetweenStepsReturnsUniqueRevalidatedResultsWithHonestCoverage() throws Exception {
        var world = DenseWorld.create(130);
        var slice = new AreaScan(new WorldRegistry()).listEntities(world, new Vec3(8, 64, 8), 4,
                new KindFilter(List.of("minecraft:zombie"), List.of(), ""), null);
        while (world.visited == 0) assertTrue(slice.step());
        world.entities.remove(world.entities.size() - 1);
        world.entities.add(new ItemEntity(EntityType.ITEM, world));
        while (slice.step()) {
        }
        assertEquals(Evidence.Coverage.PARTIAL, slice.result().coverage());
        assertTrue(slice.result().note().contains("snapshot"));
    }

    @Test
    void aSmallRadiusNeverTraversesTheGlobalEntityLookup() throws Exception {
        var world = DenseWorld.create(129);
        for (var entity : world.entities.subList(1, world.entities.size())) entity.setPos(1000, 64, 1000);
        world.forbidGlobal = true;
        var slice = new AreaScan(new WorldRegistry()).listEntities(world, new Vec3(8, 64, 8), 4, KindFilter.ANY, null);
        while (slice.step()) {
        }
        assertEquals(1, slice.result().items().size());
        assertEquals(1, world.visited, "distant loaded entities are never revalidated");
    }

    @Test
    void aMissingUuidAnchorContinuesWithAnExplicitPartialNote() throws Exception {
        var world = DenseWorld.create(130);
        var scan = new AreaScan(new WorldRegistry());
        var first = scan.listEntities(world, new Vec3(8, 64, 8), 4, KindFilter.ANY, null);
        while (first.step()) {
        }
        world.entities.removeIf(entity -> entity.getUUID().equals(first.result().items().getLast().id()));
        var next = scan.listEntities(world, new Vec3(8, 64, 8), 4, KindFilter.ANY, first.result().nextCursor());
        while (next.step()) {
        }
        assertEquals(64, next.result().items().size());
        assertEquals(Evidence.Coverage.PARTIAL, next.result().coverage());
        assertTrue(next.result().note().contains("anchor is gone"));
        assertTrue(next.result().items().stream().noneMatch(entity -> first.result().items().stream().anyMatch(previous -> previous.id().equals(entity.id()))));
    }

    @Test
    void denseEntityPagesContinueWithoutDroppingOrRepeatingMatches() throws Exception {
        var world = DenseWorld.create(130);
        var ids = new HashSet<UUID>();
        String cursor = null;
        int pages = 0;
        do {
            var slice = new AreaScan(new WorldRegistry()).listEntities(world, new Vec3(8, 64, 8), 4, KindFilter.ANY, cursor);
            while (slice.step()) {
            }
            var page = slice.result();
            for (var entity : page.items()) assertTrue(ids.add(entity.id()), "repeated " + entity.id());
            cursor = page.nextCursor();
            assertTrue(++pages <= 3);
        } while (cursor != null);
        assertEquals(130, ids.size());
        assertEquals(3, pages);
    }

    @Test
    void aDenseChunkIsEnumeratedInBoundedStepsEvenWhenNothingMatches() throws Exception {
        var world = DenseWorld.create(4000);
        var slice = new AreaScan(new WorldRegistry()).listEntities(world, new Vec3(8, 64, 8), 4,
                new KindFilter(List.of("minecraft:zombie"), List.of(), ""), null);
        var steps = 0;
        boolean more;
        do {
            var before = world.visited;
            more = slice.step();
            assertTrue(world.visited - before <= 64, "entity visits in one step: " + (world.visited - before));
            steps++;
        } while (more);
        assertTrue(steps > 60);
        assertEquals(4000, world.visited);
        assertTrue(slice.result().items().isEmpty());
        assertEquals(Evidence.Coverage.PARTIAL, slice.result().coverage());
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
