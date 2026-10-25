package dev.packsage.world.minecraft;

import dev.packsage.world.ServerWork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;

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
        boolean biomePasses(Structure structure, BlockPos columnMiddle);
        int surfaceY(int x, int z);
    }

    public record Target(String id, Structure structure, List<StructurePlacement> placements) {
    }

    public record Result(List<Hit> hits, int nextRing, int nextEntry, int nextCandidate, List<String> unsupported,
                         List<String> notes, boolean budget) {
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
    private final List<String> notes = new ArrayList<>();
    private final LinkedHashSet<String> biomeRejected = new LinkedHashSet<>();
    private final HashSet<String> notBiomeRejected = new HashSet<>();
    private final List<Hit> hits = new ArrayList<>();
    private final HashSet<String> seen = new HashSet<>();
    private int ring;
    private int lastRing;
    private int entry;
    private int candidate;
    private int nextRing = -1;
    private boolean budget;
    private boolean finished;

    /** Uses captured placement and ring inputs; World methods are called only by server-thread steps. */
    public LocateStructure(World world, List<Target> targets, long seed,
                           Function<ConcentricRingsStructurePlacement, List<ChunkPos>> rings,
                           int centerChunkX, int centerChunkZ, int radiusChunks, int startRing, int startEntry, int startCandidate) {
        this.world = world;
        this.seed = seed;
        centerX = centerChunkX;
        centerZ = centerChunkZ;
        radius = radiusChunks;
        ring = startRing;
        if (targets.isEmpty()) notes.add("structure tag is empty");
        for (var target : targets) {
            if (target.placements().isEmpty()) notes.add(target.id() + " has no placements in this dimension");
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
        if (startEntry >= 0 && startEntry < entries.size()) {
            entry = startEntry;
            candidate = startCandidate;
        }
    }

    @Override
    public boolean step() {
        if (finished) return false;
        if (entry == entries.size()) {
            ring++;
            entry = 0;
            candidate = 0;
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
            notBiomeRejected.add(current.target().id());
            var start = world.loadedStart(chunk.x, chunk.z, current.target().structure());
            if (start != null && start.isValid()) {
                seen.add(identity);
                hits.add(new Hit(current.target().id(), chunk.x, chunk.z, pos.getX(), start.getBoundingBox().getCenter().getY(), pos.getZ(),
                        Kind.FOUND, "loaded chunk"));
            }
        } else if (world.placementPasses(current.placement(), chunk.x, chunk.z)) {
            if (world.biomePasses(current.target().structure(), chunk.getMiddleBlockPosition(0))) {
                notBiomeRejected.add(current.target().id());
                seen.add(identity);
                hits.add(new Hit(current.target().id(), chunk.x, chunk.z, pos.getX(), world.surfaceY(pos.getX(), pos.getZ()), pos.getZ(),
                        Kind.PREDICTED, "placement and biome checks"));
            } else {
                biomeRejected.add(current.target().id());
            }
        } else {
            notBiomeRejected.add(current.target().id());
        }
        return true;
    }

    @Override
    public Result result() {
        var details = new ArrayList<>(notes);
        var rejected = new LinkedHashSet<>(biomeRejected);
        rejected.removeAll(notBiomeRejected);
        if (!rejected.isEmpty()) details.add("biome checks rejected candidate columns for " + String.join(", ", rejected)
                + "; sampled columns do not prove structure absence");
        return new Result(hits.stream().sorted(Comparator.comparingDouble(hit -> Math.hypot((double) hit.x() - centerX * 16.0,
                (double) hit.z() - centerZ * 16.0))).toList(), nextRing, entry, candidate, List.copyOf(unsupported), List.copyOf(details), budget);
    }

    @Override
    public boolean stopEarly() {
        if (!finished) {
            if (entry == entries.size()) {
                ring++;
                entry = 0;
                candidate = 0;
            }
            nextRing = ring <= lastRing ? ring : -1;
            budget = nextRing != -1;
            finished = true;
        }
        return true;
    }

    /** Vertical biome sampling permits underground starts without requiring terrain generation. */
    public static boolean biomePasses(Structure structure, int minY, int maxY, IntFunction<Holder<Biome>> biomeAtY) {
        for (int y = minY; y <= maxY; y += LocateBiome.VERTICAL_STEP) {
            if (structure.biomes().contains(biomeAtY.apply(y))) return true;
        }
        return false;
    }
}
