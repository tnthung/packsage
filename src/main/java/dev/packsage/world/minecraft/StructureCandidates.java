package dev.packsage.world.minecraft;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Candidate positions only: never asks a level to load or generate a structure. */
public final class StructureCandidates {
    private StructureCandidates() {
    }

    public static boolean supported(StructurePlacement placement) {
        return placement instanceof RandomSpreadStructurePlacement || placement instanceof ConcentricRingsStructurePlacement;
    }

    public static List<ChunkPos> ring(RandomSpreadStructurePlacement placement, long seed, int centerChunkX, int centerChunkZ, int ring) {
        var candidates = new ArrayList<ChunkPos>();
        for (int j = -ring; j <= ring; j++) {
            for (int k = -ring; k <= ring; k++) {
                if (j == -ring || j == ring || k == -ring || k == ring) {
                    candidates.add(placement.getPotentialStructureChunk(seed, centerChunkX + placement.spacing() * j,
                            centerChunkZ + placement.spacing() * k));
                }
            }
        }
        return candidates;
    }

    /** Returns the inclusive last ring index; callers must iterate from zero through this index. */
    public static int lastRing(RandomSpreadStructurePlacement placement, int radiusChunks) {
        return Math.ceilDiv(radiusChunks, placement.spacing());
    }

    /**
     * Filters and sorts by 2D chunk distance from the center chunk. Vanilla instead measures 3D block distance
     * from each candidate's (x * 16 + 8, 32, z * 16 + 8) to the search position.
     */
    public static List<ChunkPos> concentric(List<ChunkPos> ringPositions, int centerChunkX, int centerChunkZ, int radiusChunks) {
        record Candidate(ChunkPos chunk, double distance) {}
        return ringPositions.stream()
                .map(chunk -> new Candidate(chunk, Math.hypot((double) chunk.x - centerChunkX, (double) chunk.z - centerChunkZ)))
                .filter(candidate -> candidate.distance() <= radiusChunks)
                .sorted(Comparator.comparingDouble(Candidate::distance))
                .map(Candidate::chunk)
                .toList();
    }
}
