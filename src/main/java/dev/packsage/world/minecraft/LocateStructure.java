package dev.packsage.world.minecraft;

import dev.packsage.world.ServerWork;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;

/** One candidate per server step; never loads a chunk or accesses disk. */
public final class LocateStructure implements ServerWork.Slice<LocateStructure.Result> {
    public static final int RESULTS = 5;
    public static final int SCANS = 8;

    public enum Kind { FOUND, PREDICTED }

    public record Hit(String structure, int chunkX, int chunkZ, int x, int y, int z, Kind kind, String basis) {
    }

    public interface World {
        boolean loaded(int chunkX, int chunkZ);
        StructureStart loadedStart(int chunkX, int chunkZ, Structure structure);
        boolean placementPasses(StructurePlacement placement, int chunkX, int chunkZ);
        boolean biomePasses(Structure structure, BlockPos locatePos);
        int surfaceY(int x, int z);
    }

    public record Target(String id, Structure structure, List<StructurePlacement> placements) {
    }

    public record Result(List<Hit> hits, int nextRing, List<String> unsupported) {
    }

    private record Entry(Target target, StructurePlacement placement, List<ChunkPos> positions) {
    }

    private final World world;
    private final long seed;
    private final int centerX;
    private final int centerZ;
    private final int radius;
    private final List<Entry> entries = new ArrayList<>();
    private final List<String> unsupported = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private final HashSet<String> seen = new HashSet<>();
    private int ring;
    private int lastRing;
    private int entry;
    private int candidate;
    private int ringHits;
    private int nextRing = -1;
    private boolean finished;

    /** Construct on the worker; ring positions are captured from generator state before this slice runs. */
    public LocateStructure(World world, List<Target> targets, long seed,
                           Function<ConcentricRingsStructurePlacement, List<ChunkPos>> rings,
                           int centerChunkX, int centerChunkZ, int radiusChunks, int startRing) {
        this.world = world;
        this.seed = seed;
        centerX = centerChunkX;
        centerZ = centerChunkZ;
        radius = radiusChunks;
        ring = startRing;
        for (var target : targets) {
            for (var placement : target.placements()) {
                if (placement instanceof ConcentricRingsStructurePlacement concentric) {
                    entries.add(new Entry(target, placement, StructureCandidates.concentric(rings.apply(concentric), centerX, centerZ, radius)));
                } else if (placement instanceof RandomSpreadStructurePlacement random) {
                    lastRing = Math.max(lastRing, StructureCandidates.lastRing(random, radius));
                    entries.add(new Entry(target, placement, null));
                } else {
                    var description = target.id() + " (" + placement.getClass().getName() + ")";
                    if (!unsupported.contains(description)) unsupported.add(description);
                }
            }
        }
        entries.sort(Comparator.comparing(entry -> entry.positions() == null));
    }

    @Override
    public boolean step() {
        if (finished) return false;
        if (entry == entries.size()) {
            ring++;
            entry = 0;
            candidate = 0;
            ringHits = hits.size();
            if (ring > lastRing || hits.size() >= RESULTS) {
                nextRing = ring <= lastRing ? ring : -1;
                finished = true;
                return false;
            }
            return true;
        }
        var current = entries.get(entry);
        ChunkPos chunk;
        if (current.positions() != null) {
            if (ring != 0 || candidate >= current.positions().size()) {
                entry++;
                candidate = 0;
                return true;
            }
            chunk = current.positions().get(candidate++);
        } else {
            var placement = (RandomSpreadStructurePlacement) current.placement();
            if (ring > StructureCandidates.lastRing(placement, radius) || candidate >= (ring == 0 ? 1 : 8 * ring)) {
                entry++;
                candidate = 0;
                return true;
            }
            // Vanilla's nested-loop perimeter order without walking the square's interior.
            int dx;
            int dz;
            if (candidate <= 2 * ring) {
                dx = -ring;
                dz = -ring + candidate;
            } else if (candidate < 6 * ring - 1) {
                dx = -ring + 1 + (candidate - 2 * ring - 1) / 2;
                dz = (candidate - 2 * ring - 1) % 2 == 0 ? -ring : ring;
            } else {
                dx = ring;
                dz = -ring + candidate - (6 * ring - 1);
            }
            candidate++;
            chunk = placement.getPotentialStructureChunk(seed, centerX + placement.spacing() * dx, centerZ + placement.spacing() * dz);
        }
        if (Math.hypot((double) chunk.x - centerX, (double) chunk.z - centerZ) > radius) return true;
        var identity = current.target().id() + "|" + chunk.x + "," + chunk.z;
        if (seen.contains(identity)) return true;
        var pos = current.placement().getLocatePos(chunk);
        if (world.loaded(chunk.x, chunk.z)) {
            var start = world.loadedStart(chunk.x, chunk.z, current.target().structure());
            if (start != null && start.isValid()) {
                seen.add(identity);
                hits.add(new Hit(current.target().id(), chunk.x, chunk.z, pos.getX(), start.getBoundingBox().getCenter().getY(), pos.getZ(),
                        Kind.FOUND, "loaded chunk"));
            }
        } else if (world.placementPasses(current.placement(), chunk.x, chunk.z) && world.biomePasses(current.target().structure(), pos)) {
            seen.add(identity);
            hits.add(new Hit(current.target().id(), chunk.x, chunk.z, pos.getX(), world.surfaceY(pos.getX(), pos.getZ()), pos.getZ(),
                    Kind.PREDICTED, "placement and biome checks"));
        }
        return true;
    }

    @Override
    public Result result() {
        return new Result(hits.stream().sorted(Comparator.comparingDouble(hit -> Math.hypot((double) hit.x() - centerX * 16.0,
                (double) hit.z() - centerZ * 16.0))).toList(), nextRing, List.copyOf(unsupported));
    }

    @Override
    public boolean stopEarly() {
        if (!finished) {
            // Replay the unfinished ring rather than losing any candidates at a tick-budget boundary.
            hits.subList(ringHits, hits.size()).clear();
            nextRing = ring;
            finished = true;
        }
        return true;
    }
}
