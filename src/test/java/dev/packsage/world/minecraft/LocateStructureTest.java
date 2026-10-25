package dev.packsage.world.minecraft;

import dev.packsage.world.ServerWork;
import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

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
        int surfaces;
        final List<ChunkPos> evaluated = new ArrayList<>();
        final List<BlockPos> biomePositions = new ArrayList<>();

        public boolean loaded(int x, int z) { checked++; evaluated.add(new ChunkPos(x, z)); return loaded; }
        public StructureStart loadedStart(int x, int z, Structure structure) { return start; }
        public boolean placementPasses(StructurePlacement placement, int x, int z) { return true; }
        public boolean biomePasses(Structure structure, BlockPos pos) { biomePositions.add(pos); return biome; }
        public int surfaceY(int x, int z) { surfaces++; return 72; }
    }

    private LocateStructure search(World world, StructurePlacement placement, int x, int z, int radius, int start) {
        return new LocateStructure(world, List.of(new LocateStructure.Target("minecraft:desert_pyramid", structure, List.of(placement))),
                42, ignored -> List.of(new ChunkPos(0, 0)), x, z, radius, start, 0, 0);
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
        assertEquals(world.checked, world.surfaces);
        assertEquals(world.evaluated.stream().map(chunk -> chunk.getMiddleBlockPosition(0)).toList(), world.biomePositions);
        world.biome = false;
        var rejected = finish(search(world, placement, 0, 0, 100, 0));
        assertTrue(rejected.hits().isEmpty());
        assertTrue(rejected.notes().stream().anyMatch(note -> note.contains("biome") && note.contains("minecraft:desert_pyramid")));
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
        assertEquals(2, slice.result().hits().size());
        assertEquals(1, slice.result().nextCandidate());
        var resumed = finish(new LocateStructure(new World(), List.of(new LocateStructure.Target("minecraft:desert_pyramid", structure, List.of(placement))),
                42, ignored -> List.of(), 0, 0, 100, slice.result().nextRing(), slice.result().nextEntry(), slice.result().nextCandidate()));
        assertEquals(7, resumed.hits().size());
    }

    @Test
    void includesLastRingAndConcentricCandidatesAreFirst() {
        var world = new World();
        world.biome = false;
        finish(search(world, placement, 33, 0, 100, 3));
        assertEquals(StructureCandidates.ring(placement, 42, 33, 0, 3).stream()
                .filter(chunk -> Math.hypot(chunk.x - 33, chunk.z) <= 100).toList(), world.evaluated);
        var concentric = new ConcentricRingsStructurePlacement(32, 3, 128, HolderSet.direct(List.of()));
        var mixed = new World();
        mixed.biome = false;
        finish(new LocateStructure(mixed, List.of(new LocateStructure.Target("minecraft:test", structure, List.of(placement, concentric))),
                42, ignored -> List.of(new ChunkPos(2, 0), new ChunkPos(0, 0), new ChunkPos(101, 0)), 0, 0, 100, 0, 0, 0));
        var expected = new ArrayList<>(List.of(new ChunkPos(0, 0), new ChunkPos(2, 0)));
        for (int ring = 0; ring <= 3; ring++) expected.addAll(StructureCandidates.ring(placement, 42, 0, 0, ring).stream()
                .filter(chunk -> Math.hypot(chunk.x, chunk.z) <= 100).toList());
        assertEquals(expected, mixed.evaluated);
    }

    @Test
    void aFailedPlacementDoesNotHideAnotherPlacementAtTheSameChunk() {
        var second = new RandomSpreadStructurePlacement(34, 8, RandomSpreadType.LINEAR, 10387312);
        var world = new World() {
            public boolean placementPasses(StructurePlacement current, int x, int z) { return current == second; }
        };
        assertEquals(9, finish(new LocateStructure(world,
                List.of(new LocateStructure.Target("minecraft:desert_pyramid", structure, List.of(placement, second))),
                42, ignored -> List.of(), 0, 0, 100, 0, 0, 0)).hits().size());
    }

    @Test
    void deepBiomeAloneAllowsAnUndergroundPrediction() {
        var lookup = VanillaRegistries.createLookup();
        var ancient = lookup.lookupOrThrow(Registries.STRUCTURE).getOrThrow(
                ResourceKey.create(Registries.STRUCTURE, ResourceLocation.parse("minecraft:ancient_city"))).value();
        var biomes = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        var deep = biomes.register(Biomes.DEEP_DARK, lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.DEEP_DARK).value(), RegistrationInfo.BUILT_IN);
        // Bootstrap lookups do not load the data-pack biome tags used by ancient cities.
        biomes.bindTags(Map.of(BiomeTags.HAS_ANCIENT_CITY, List.of(deep)));
        var plains = biomes.register(Biomes.PLAINS, lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        var sampled = new ArrayList<Integer>();
        var world = new World() {
            public boolean biomePasses(Structure target, BlockPos pos) {
                return LocateStructure.biomePasses(target, -64, 319, y -> {
                    sampled.add(y);
                    return y < 0 ? deep : plains;
                });
            }
        };
        var result = finish(new LocateStructure(world, List.of(new LocateStructure.Target("minecraft:ancient_city", ancient, List.of(placement))),
                42, ignored -> List.of(), 0, 0, 100, 0, 0, 0));
        assertEquals(9, result.hits().size());
        assertTrue(sampled.contains(-64));
        assertEquals(LocateStructure.Kind.PREDICTED, result.hits().getFirst().kind());
        var allHeights = new ArrayList<Integer>();
        assertTrue(!LocateStructure.biomePasses(ancient, -64, 319, y -> { allHeights.add(y); return plains; }));
        assertEquals(List.of(-64, 0, 64, 128, 192, 256), allHeights);
    }

    @Test
    void expensiveRingAdvancesEveryPageAndEventuallyFinishes() {
        var clock = new AtomicLong();
        var world = new World() {
            public boolean loaded(int x, int z) {
                clock.addAndGet(200_000_000);
                return super.loaded(x, z);
            }
        };
        var work = new ServerWork(clock::get);
        int ring = 1;
        int entry = 0;
        int candidate = 0;
        var hits = new ArrayList<LocateStructure.Hit>();
        for (int page = 0; page < 20; page++) {
            var future = work.run(new LocateStructure(world, List.of(new LocateStructure.Target("minecraft:test", structure, List.of(placement))),
                    42, ignored -> List.of(), 0, 0, 34, ring, entry, candidate), () -> true).toCompletableFuture();
            for (int tick = 0; !future.isDone() && tick < 100; tick++) work.tick();
            assertTrue(future.isDone());
            var result = future.join();
            hits.addAll(result.hits());
            if (result.nextRing() == -1) {
                assertEquals(StructureCandidates.ring(placement, 42, 0, 0, 1).stream()
                        .filter(chunk -> Math.hypot(chunk.x, chunk.z) <= 34).toList(), world.evaluated);
                assertEquals(world.evaluated.size(), hits.size());
                assertEquals(hits.size(), hits.stream().map(hit -> new ChunkPos(hit.chunkX(), hit.chunkZ())).distinct().count());
                return;
            }
            assertTrue(result.budget());
            assertTrue(result.nextRing() > ring || result.nextRing() == ring && (result.nextEntry() > entry
                    || result.nextEntry() == entry && result.nextCandidate() > candidate));
            ring = result.nextRing();
            entry = result.nextEntry();
            candidate = result.nextCandidate();
        }
        fail("the expensive ring never finished");
    }

    @Test
    void staleEntryRestartsTheRingAndUnavailableTargetsAreExplicit() {
        var result = finish(new LocateStructure(new World(), List.of(new LocateStructure.Target("minecraft:test", structure, List.of(placement))),
                42, ignored -> List.of(), 0, 0, 100, 1, 2, 7));
        assertEquals(8, result.hits().size());
        var none = finish(new LocateStructure(new World(), List.of(new LocateStructure.Target("minecraft:test", structure, List.of())),
                42, ignored -> List.of(), 0, 0, 100, 0, 0, 0));
        assertTrue(none.notes().stream().anyMatch(note -> note.contains("minecraft:test") && note.contains("no placements in this dimension")));
        var empty = finish(new LocateStructure(new World(), List.of(), 42, ignored -> List.of(), 0, 0, 100, 0, 0, 0));
        assertTrue(empty.notes().stream().anyMatch(note -> note.contains("tag is empty")));
    }

    @Test
    void stoppingAfterEntryBookkeepingDoesNotReplayTheFinishedRing() {
        for (int radius : List.of(34, 100)) {
            var slice = new LocateStructure(new World(), List.of(new LocateStructure.Target("minecraft:test", structure, List.of(placement))),
                    42, ignored -> List.of(), 0, 0, radius, 1, 0, 8);
            assertTrue(slice.step());
            assertTrue(slice.stopEarly());
            assertEquals(radius == 34 ? -1 : 2, slice.result().nextRing());
            assertEquals(0, slice.result().nextEntry());
            assertEquals(0, slice.result().nextCandidate());
        }
    }
}
