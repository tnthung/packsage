package dev.packsage.world.minecraft;

import dev.packsage.world.ServerWork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;

import java.util.Iterator;
import java.util.function.Predicate;

/** Samples one spiral column per server step, in the same order as BiomeSource.findClosestBiome3d. */
public final class LocateBiome implements ServerWork.Slice<LocateBiome.Result> {
    public static final int HORIZONTAL_STEP = 32;
    public static final int VERTICAL_STEP = 64;

    public interface Sampler {
        String biomeAt(int x, int y, int z);
    }

    public record Hit(String biome, int x, int y, int z) {
    }

    /** nextIndex is -1 after a hit or exhaustion; columns counts only this page's visited columns. */
    public record Result(Hit hit, int nextIndex, int columns) {
    }

    private final Predicate<String> matches;
    private final Sampler sampler;
    private final int x;
    private final int z;
    private final int[] heights;
    private final Iterator<BlockPos.MutableBlockPos> spiral;
    private int index;
    private int columns;
    private Hit hit;
    private int nextIndex = -1;
    private boolean finished;

    /** Construct on the worker: skipping a large continuation need not consume server time. */
    public LocateBiome(Predicate<String> matches, Sampler sampler, int x, int y, int z, int radiusChunks, int minY, int maxY, int startIndex) {
        this.matches = matches;
        this.sampler = sampler;
        this.x = x;
        this.z = z;
        heights = Mth.outFromOrigin(y, minY + 1, maxY, VERTICAL_STEP).toArray();
        spiral = BlockPos.spiralAround(BlockPos.ZERO, radiusChunks * 16 / HORIZONTAL_STEP, Direction.EAST, Direction.SOUTH).iterator();
        while (index < startIndex && spiral.hasNext()) {
            spiral.next();
            index++;
        }
    }

    @Override
    public boolean step() {
        if (finished) return false;
        if (!spiral.hasNext()) {
            finished = true;
            return false;
        }
        var column = spiral.next();
        var sampleX = x + column.getX() * HORIZONTAL_STEP;
        var sampleZ = z + column.getZ() * HORIZONTAL_STEP;
        index++;
        columns++;
        for (var height : heights) {
            var biome = sampler.biomeAt(sampleX, height, sampleZ);
            if (matches.test(biome)) {
                hit = new Hit(biome, sampleX, height, sampleZ);
                finished = true;
                return false;
            }
        }
        finished = !spiral.hasNext();
        return !finished;
    }

    @Override
    public Result result() {
        return new Result(hit, nextIndex, columns);
    }

    @Override
    public boolean stopEarly() {
        if (!finished) {
            nextIndex = spiral.hasNext() ? index : -1;
            finished = true;
        }
        return true;
    }
}
