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

    public static int rings(RandomSpreadStructurePlacement placement, int radiusChunks) {
        return Math.ceilDiv(radiusChunks, placement.spacing());
    }

    public static List<ChunkPos> concentric(List<ChunkPos> ringPositions, int centerChunkX, int centerChunkZ, int radiusChunks) {
        return ringPositions.stream()
                .filter(chunk -> Math.hypot((double) chunk.x - centerChunkX, (double) chunk.z - centerChunkZ) <= radiusChunks)
                .sorted(Comparator.comparingDouble(chunk -> Math.hypot((double) chunk.x - centerChunkX, (double) chunk.z - centerChunkZ)))
                .toList();
    }
}
