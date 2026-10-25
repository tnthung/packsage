package dev.packsage.world.minecraft;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.DesertPyramidPiece;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class LocateStructureTest {
    private static Structure structure;
    private final RandomSpreadStructurePlacement placement = new RandomSpreadStructurePlacement(34, 8, RandomSpreadType.LINEAR, 10387312);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        structure = VanillaRegistries.createLookup().lookupOrThrow(Registries.STRUCTURE).getOrThrow(
                ResourceKey.create(Registries.STRUCTURE, ResourceLocation.parse("minecraft:desert_pyramid"))).value();
    }

    private static class World implements LocateStructure.World {
        boolean loaded;
        boolean biome = true;
        StructureStart start;
        int checked;

        public boolean loaded(int x, int z) { checked++; return loaded; }
        public StructureStart loadedStart(int x, int z, Structure structure) { return start; }
        public boolean placementPasses(StructurePlacement placement, int x, int z) { return true; }
        public boolean biomePasses(Structure structure, BlockPos pos) { return biome; }
        public int surfaceY(int x, int z) { return 72; }
    }

    private LocateStructure search(World world, StructurePlacement placement, int x, int z, int radius, int start) {
        return new LocateStructure(world, List.of(new LocateStructure.Target("minecraft:desert_pyramid", structure, List.of(placement))),
                42, ignored -> List.of(new ChunkPos(0, 0)), x, z, radius, start);
    }

    private static LocateStructure.Result finish(LocateStructure slice) {
        while (slice.step()) { }
        return slice.result();
    }

    @Test
    void loadedStartsAreFoundAndLoadedEmptyChunksAreSkipped() {
        var world = new World();
        world.loaded = true;
        assertTrue(finish(search(world, placement, 0, 0, 100, 0)).hits().isEmpty());
        world.start = new StructureStart(structure, new ChunkPos(0, 0), 0,
                new PiecesContainer(List.of(new DesertPyramidPiece(RandomSource.create(1), 0, 0))));
        var hit = finish(search(world, placement, 0, 0, 100, 0)).hits().getFirst();
        assertEquals(LocateStructure.Kind.FOUND, hit.kind());
        assertEquals("loaded chunk", hit.basis());
        assertEquals(world.start.getBoundingBox().getCenter().getY(), hit.y());
    }

    @Test
    void unloadedCandidatesPassingPlacementAndBiomeArePredicted() {
        var world = new World();
        var hit = finish(search(world, placement, 0, 0, 100, 0)).hits().getFirst();
        var pos = placement.getLocatePos(new ChunkPos(hit.chunkX(), hit.chunkZ()));
        assertEquals(pos.getX(), hit.x());
        assertEquals(pos.getZ(), hit.z());
        assertEquals(72, hit.y());
        assertEquals(LocateStructure.Kind.PREDICTED, hit.kind());
        world.biome = false;
        assertTrue(finish(search(world, placement, 0, 0, 100, 0)).hits().isEmpty());
    }

    @Test
    void stopsAfterTheRingThatReachesFiveHits() {
        var world = new World();
        var result = finish(search(world, placement, 0, 0, 100, 0));
        assertEquals(9, result.hits().size());
        assertEquals(9, world.checked);
        assertEquals(2, result.nextRing());
        var distances = result.hits().stream().map(hit -> Math.hypot(hit.x(), hit.z())).toList();
        assertEquals(distances.stream().sorted().toList(), distances);
    }

    @Test
    @SuppressWarnings("deprecation")
    void unsupportedPlacementsAreNamed() {
        var unknown = new StructurePlacement(Vec3i.ZERO, StructurePlacement.FrequencyReductionMethod.DEFAULT, 1, 0, Optional.empty()) {
            protected boolean isPlacementChunk(ChunkGeneratorStructureState state, int x, int z) { return fail("unsupported"); }
            public StructurePlacementType<?> type() { return fail("unsupported"); }
        };
        var unsupported = finish(search(new World(), unknown, 0, 0, 100, 0)).unsupported();
        assertEquals(1, unsupported.size());
        assertTrue(unsupported.getFirst().contains("minecraft:desert_pyramid"));
        assertTrue(unsupported.getFirst().contains(unknown.getClass().getName()));
    }

    @Test
    void stopEarlyLeavesAResumableRing() {
        var world = new World();
        var slice = search(world, placement, 0, 0, 100, 0);
        while (world.checked < 2) assertTrue(slice.step());
        assertTrue(slice.stopEarly());
        assertEquals(1, slice.result().nextRing());
        assertEquals(1, slice.result().hits().size());
        assertEquals(8, finish(search(new World(), placement, 0, 0, 100, 1)).hits().size());
    }

    @Test
    void includesLastRingAndConcentricCandidatesAreFirst() {
        var world = new World();
        world.biome = false;
        finish(search(world, placement, 33, 0, 100, 3));
        assertTrue(world.checked > 0);
        var concentric = new ConcentricRingsStructurePlacement(32, 3, 128, HolderSet.direct(List.of()));
        assertEquals(1, finish(search(new World(), concentric, 0, 0, 100, 0)).hits().size());
    }

    @Test
    void aFailedPlacementDoesNotHideAnotherPlacementAtTheSameChunk() {
        var second = new RandomSpreadStructurePlacement(34, 8, RandomSpreadType.LINEAR, 10387312);
        var world = new World() {
            public boolean placementPasses(StructurePlacement current, int x, int z) { return current == second; }
        };
        assertEquals(9, finish(new LocateStructure(world,
                List.of(new LocateStructure.Target("minecraft:desert_pyramid", structure, List.of(placement, second))),
                42, ignored -> List.of(), 0, 0, 100, 0)).hits().size());
    }
}
