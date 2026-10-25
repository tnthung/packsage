package dev.packsage.world.minecraft;

import dev.packsage.world.ServerWork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocateBiomeTest {
    @Test
    void sampledOrderMatchesTheVanillaSpiral() {
        var sampled = new ArrayList<BlockPos>();
        var search = new LocateBiome("minecraft:desert"::equals, (x, y, z) -> {
            sampled.add(new BlockPos(x, y, z));
            return "minecraft:plains";
        }, -17, 65, 23, 4, -64, 320, 0);
        while (search.step()) { }

        var expected = new ArrayList<BlockPos>();
        for (var column : BlockPos.spiralAround(BlockPos.ZERO, 2, Direction.EAST, Direction.SOUTH)) {
            for (var height : Mth.outFromOrigin(65, -63, 320, 64).toArray()) {
                expected.add(new BlockPos(-17 + column.getX() * 32, height, 23 + column.getZ() * 32));
            }
        }
        assertEquals(expected, sampled);
        assertEquals(25, search.result().columns());
    }

    @Test
    void stopsAtTheFirstMatchingColumn() {
        var sampled = new ArrayList<BlockPos>();
        var search = new LocateBiome("minecraft:desert"::equals, (x, y, z) -> {
            sampled.add(new BlockPos(x, y, z));
            return x == 64 && z == 0 ? "minecraft:desert" : "minecraft:plains";
        }, 0, 70, 0, 4, -64, 320, 0);
        while (search.step()) { }

        assertEquals(new LocateBiome.Hit("minecraft:desert", 64, 70, 0), search.result().hit());
        assertEquals(new BlockPos(64, 70, 0), sampled.getLast());
        assertEquals(-1, search.result().nextIndex());
        assertFalse(search.step());
    }

    @Test
    void earlyStopResumesFromTheNextColumn() {
        var sampled = new ArrayList<BlockPos>();
        LocateBiome.Sampler sampler = (x, y, z) -> {
            sampled.add(new BlockPos(x, y, z));
            return "minecraft:plains";
        };
        var search = new LocateBiome("minecraft:desert"::equals, sampler, 0, 64, 0, 4, 0, 64, 0);
        for (int column = 0; column < 10; column++) assertTrue(search.step());
        assertTrue(search.stopEarly());
        assertEquals(10, search.result().nextIndex());
        assertEquals(10, search.result().columns());
        assertFalse(search.step());

        var resumed = new LocateBiome("minecraft:desert"::equals, sampler, 0, 64, 0, 4, 0, 64, 10);
        assertTrue(resumed.step());
        var spiral = BlockPos.spiralAround(BlockPos.ZERO, 2, Direction.EAST, Direction.SOUTH).iterator();
        for (int column = 0; column < 10; column++) spiral.next();
        var next = spiral.next();
        assertEquals(new BlockPos(next.getX() * 32, 64, next.getZ() * 32), sampled.get(10));
        while (resumed.step()) { }
        assertEquals(25, sampled.size());
        assertEquals(15, resumed.result().columns());
        assertEquals(-1, resumed.result().nextIndex());
    }

    @Test
    void noMatchWithinRadiusFinishesEmpty() {
        var search = new LocateBiome("minecraft:desert"::equals, (x, y, z) -> "minecraft:plains",
                0, 64, 0, 4, -64, 320, 0);
        while (search.step()) { }

        assertNull(search.result().hit());
        assertEquals(-1, search.result().nextIndex());
        assertEquals(25, search.result().columns());
    }

    @Test
    void serverBudgetStopsWithAContinuationAndTicksStaySliced() {
        var clock = new AtomicLong();
        var work = new ServerWork(clock::get);
        var result = work.run(new LocateBiome("minecraft:desert"::equals, (x, y, z) -> {
            clock.addAndGet(1_000_000);
            return "minecraft:plains";
        }, 0, 64, 0, 100, 0, 64, 0), () -> true).toCompletableFuture();
        work.tick();
        assertEquals(5_000_000, clock.get());
        assertFalse(result.isDone());
        while (!result.isDone()) work.tick();
        assertEquals(500, result.join().columns());
        assertEquals(500, result.join().nextIndex());
        assertNull(result.join().hit());
    }
}
