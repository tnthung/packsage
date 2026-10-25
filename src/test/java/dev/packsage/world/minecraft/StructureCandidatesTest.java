package dev.packsage.world.minecraft;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Vec3i;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureCandidatesTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void ringOrderMatchesVanilla() {
        var placement = new RandomSpreadStructurePlacement(34, 8, RandomSpreadType.LINEAR, 10387312);
        for (int ring = 0; ring <= 2; ring++) {
            var expected = new ArrayList<ChunkPos>();
            for (int j = -ring; j <= ring; j++) {
                boolean edgeX = j == -ring || j == ring;
                for (int k = -ring; k <= ring; k++) {
                    boolean edgeZ = k == -ring || k == ring;
                    if (edgeX || edgeZ) {
                        expected.add(placement.getPotentialStructureChunk(42L, 3 + placement.spacing() * j, -7 + placement.spacing() * k));
                    }
                }
            }
            assertEquals(expected, StructureCandidates.ring(placement, 42L, 3, -7, ring));
        }
    }

    @Test
    void ringCountCoversTheRadius() {
        var placement = new RandomSpreadStructurePlacement(34, 8, RandomSpreadType.LINEAR, 10387312);
        assertEquals(3, StructureCandidates.rings(placement, 100));
        assertEquals(0, StructureCandidates.rings(placement, 0));
        assertEquals(1, StructureCandidates.rings(placement, 34));
        assertEquals(2, StructureCandidates.rings(placement, 35));
    }

    @Test
    void concentricKeepsPositionsInRangeNearestFirst() {
        var positions = List.of(new ChunkPos(6, -3), new ChunkPos(7, -5), new ChunkPos(3, -7),
                new ChunkPos(4, -7), new ChunkPos(9, -7), new ChunkPos(7, -3), new ChunkPos(65539, -7));
        assertEquals(List.of(new ChunkPos(3, -7), new ChunkPos(4, -7), new ChunkPos(7, -5), new ChunkPos(6, -3)),
                StructureCandidates.concentric(positions, 3, -7, 5));
        assertEquals(List.of(new ChunkPos(3, -7)), StructureCandidates.concentric(positions, 3, -7, 0));
    }

    @Test
    void unknownPlacementTypesAreUnsupported() {
        assertFalse(StructureCandidates.supported(new StructurePlacement(Vec3i.ZERO,
                StructurePlacement.FrequencyReductionMethod.DEFAULT, 1.0F, 0, Optional.empty()) {
            @Override
            protected boolean isPlacementChunk(ChunkGeneratorStructureState state, int x, int z) {
                throw new AssertionError("Unsupported placements must not be evaluated");
            }

            @Override
            public StructurePlacementType<?> type() {
                throw new AssertionError("Unsupported placements must not need a registered type");
            }
        }));
        assertTrue(StructureCandidates.supported(new RandomSpreadStructurePlacement(34, 8, RandomSpreadType.LINEAR, 10387312)));
        assertTrue(StructureCandidates.supported(new ConcentricRingsStructurePlacement(32, 3, 128, HolderSet.direct(List.of()))));
    }
}
