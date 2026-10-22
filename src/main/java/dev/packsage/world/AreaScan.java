package dev.packsage.world;

import dev.packsage.knowledge.Evidence;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sliced block and entity scans in a sphere around a center. Each loaded chunk is one step; a chunk that is not
 * loaded when its step runs is counted, never loaded, and makes the page PARTIAL. List cursors are
 * {@code <chunk index>:<matches to skip in that chunk>} and stay stable for the same level, center and radius.
 */
public final class AreaScan {
    public static final int SUMMARY_NOTABLE = 4096;
    public static final int LIST_PAGE = 64;

    private final WorldRegistry registry;

    public AreaScan(WorldRegistry registry) {
        this.registry = registry;
    }

    public ServerWork.Slice<Page<BlockSummary>> summary(ServerLevel level, Vec3 center, int radius) {
        return new SummarySlice(level, center, radius, registry.accessors());
    }

    public ServerWork.Slice<Page<BlockHandle>> listBlocks(ServerLevel level, Vec3 center, int radius, KindFilter filter, String cursor) {
        return new BlockListSlice(level, center, radius, filter, Cursor.parse(cursor));
    }

    public ServerWork.Slice<Page<EntityHandle>> listEntities(ServerLevel level, Vec3 center, int radius, KindFilter filter, String cursor) {
        return new EntityListSlice(level, center, radius, filter, Cursor.parse(cursor));
    }

    private record Cursor(int chunk, int skip) {
        static final Cursor START = new Cursor(0, 0);

        static Cursor parse(String text) {
            if (text == null) {
                return START;
            }
            var colon = text.indexOf(':');
            try {
                var cursor = new Cursor(Integer.parseInt(text.substring(0, colon)), Integer.parseInt(text.substring(colon + 1)));
                if (cursor.chunk < 0 || cursor.skip < 0) {
                    throw new IllegalArgumentException("Invalid area cursor: " + text);
                }
                return cursor;
            } catch (NumberFormatException | StringIndexOutOfBoundsException exception) {
                throw new IllegalArgumentException("Invalid area cursor: " + text, exception);
            }
        }

        String text() {
            return chunk + ":" + skip;
        }
    }

    private interface BlockVisitor {
        /** Returns false to stop visiting the chunk. */
        boolean visit(BlockPos.MutableBlockPos pos, BlockState state);
    }

    private abstract static class ChunkSlice<T> implements ServerWork.Slice<Page<T>> {
        final ServerLevel level;
        final Vec3 center;
        final int radius;
        final double radiusSquared;
        final int minY;
        final int maxY;
        final List<ChunkPos> chunks;
        final Set<String> notes = new LinkedHashSet<>();
        int next;
        int unloaded;
        String nextCursor;

        ChunkSlice(ServerLevel level, Vec3 center, int radius, int startChunk) {
            this.level = level;
            this.center = center;
            this.radius = radius;
            this.radiusSquared = (double) radius * radius;
            this.minY = Math.max(level.getMinBuildHeight(), Mth.floor(center.y - radius));
            this.maxY = Math.min(level.getMaxBuildHeight() - 1, Mth.floor(center.y + radius));
            this.chunks = chunksInRange(center, radius);
            this.next = startChunk;
        }

        @Override
        public boolean step() {
            if (nextCursor != null || next >= chunks.size()) {
                return false;
            }
            var pos = chunks.get(next);
            var chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk == null) {
                unloaded++;
            } else {
                scan(chunk, next);
            }
            next++;
            return nextCursor == null && next < chunks.size();
        }

        abstract void scan(LevelChunk chunk, int index);

        <R> Page<R> page(List<R> items) {
            var all = new ArrayList<String>();
            if (unloaded > 0) {
                all.add(unloaded + " chunks in range were not loaded and were not read");
            }
            all.addAll(notes);
            var coverage = nextCursor == null && all.isEmpty() ? Evidence.Coverage.COMPLETE : Evidence.Coverage.PARTIAL;
            return new Page<>(items, nextCursor, coverage, all.isEmpty() ? null : String.join("; ", all));
        }

        String dimension() {
            return level.dimension().location().toString();
        }

        void forEachBlock(LevelChunk chunk, BlockVisitor visitor) {
            var minX = chunk.getPos().getMinBlockX();
            var minZ = chunk.getPos().getMinBlockZ();
            var pos = new BlockPos.MutableBlockPos();
            var sections = chunk.getSections();
            for (var i = 0; i < sections.length; i++) {
                var section = sections[i];
                var baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
                if (section.hasOnlyAir() || baseY + 15 < minY || baseY > maxY) {
                    continue;
                }
                for (var y = Math.max(0, minY - baseY); y <= Math.min(15, maxY - baseY); y++) {
                    for (var z = 0; z < 16; z++) {
                        for (var x = 0; x < 16; x++) {
                            var dx = minX + x + 0.5 - center.x;
                            var dy = baseY + y + 0.5 - center.y;
                            var dz = minZ + z + 0.5 - center.z;
                            if (dx * dx + dy * dy + dz * dz > radiusSquared) {
                                continue;
                            }
                            var state = section.getBlockState(x, y, z);
                            if (state.isAir()) {
                                continue;
                            }
                            if (!visitor.visit(pos.set(minX + x, baseY + y, minZ + z), state)) {
                                return;
                            }
                        }
                    }
                }
            }
        }

        private static List<ChunkPos> chunksInRange(Vec3 center, int radius) {
            var chunks = new ArrayList<ChunkPos>();
            var radiusSquared = (double) radius * radius;
            var minChunkX = SectionPos.blockToSectionCoord(Mth.floor(center.x - radius));
            var maxChunkX = SectionPos.blockToSectionCoord(Mth.floor(center.x + radius));
            var minChunkZ = SectionPos.blockToSectionCoord(Mth.floor(center.z - radius));
            var maxChunkZ = SectionPos.blockToSectionCoord(Mth.floor(center.z + radius));
            for (var chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (var chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    var dx = gap(center.x, SectionPos.sectionToBlockCoord(chunkX));
                    var dz = gap(center.z, SectionPos.sectionToBlockCoord(chunkZ));
                    if (dx * dx + dz * dz <= radiusSquared) {
                        chunks.add(new ChunkPos(chunkX, chunkZ));
                    }
                }
            }
            return chunks;
        }

        /** Distance from a coordinate to the 16-block span starting at min. */
        private static double gap(double coordinate, int min) {
            return Math.max(0, Math.max(min - coordinate, coordinate - (min + 16)));
        }
    }

    /** Collects matches into one page, skipping the matches earlier pages returned from the cursor's chunk. */
    private abstract static class ListSlice<T> extends ChunkSlice<T> {
        final List<T> items = new ArrayList<>();
        final Cursor start;

        ListSlice(ServerLevel level, Vec3 center, int radius, Cursor start) {
            super(level, center, radius, start.chunk());
            this.start = start;
        }

        int skip(int index) {
            return index == start.chunk() ? start.skip() : 0;
        }

        /** Returns false once the page is full; the match at seen becomes the next page's first. */
        boolean offer(int index, int seen, T item) {
            if (items.size() >= LIST_PAGE) {
                nextCursor = new Cursor(index, seen).text();
                return false;
            }
            items.add(item);
            return true;
        }

        @Override
        public Page<T> result() {
            return page(items);
        }
    }

    private static final class SummarySlice extends ChunkSlice<BlockSummary> {
        private final List<WorldAccessor> accessors;
        private final Map<String, Integer> counts = new HashMap<>();
        private final List<NotableBlock> notable = new ArrayList<>();
        private final Map<BlockState, String> roles = new IdentityHashMap<>();
        private boolean truncated;

        SummarySlice(ServerLevel level, Vec3 center, int radius, List<WorldAccessor> accessors) {
            super(level, center, radius, 0);
            this.accessors = accessors;
        }

        @Override
        void scan(LevelChunk chunk, int index) {
            forEachBlock(chunk, (pos, state) -> {
                var kind = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                counts.merge(kind, 1, Integer::sum);
                var role = role(chunk, pos, state);
                if (role != null) {
                    if (notable.size() < SUMMARY_NOTABLE) {
                        notable.add(new NotableBlock(new BlockHandle(kind, new BlockPlace(dimension(), pos.getX(), pos.getY(), pos.getZ())), role));
                    } else {
                        truncated = true;
                    }
                }
                return true;
            });
        }

        private String role(LevelChunk chunk, BlockPos pos, BlockState state) {
            if (state.hasBlockEntity()) {
                return classify(state, chunk.getBlockEntity(pos.immutable(), LevelChunk.EntityCreationType.CHECK));
            }
            if (roles.containsKey(state)) {
                return roles.get(state);
            }
            var role = classify(state, null);
            roles.put(state, role);
            return role;
        }

        private String classify(BlockState state, BlockEntity entity) {
            for (var accessor : accessors) {
                try {
                    var role = accessor.notableRole(state, entity);
                    if (role != null) {
                        return role;
                    }
                } catch (RuntimeException exception) {
                    notes.add("Notable roles from " + accessor.adapterId() + " failed: "
                            + (exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()));
                }
            }
            return null;
        }

        @Override
        public Page<BlockSummary> result() {
            if (truncated) {
                notes.add("Only the first " + SUMMARY_NOTABLE + " notable blocks are listed");
            }
            return page(List.of(new BlockSummary(counts, notable, unloaded)));
        }
    }

    private static final class BlockListSlice extends ListSlice<BlockHandle> {
        private final KindFilter filter;
        private final Map<BlockState, Boolean> matches = new IdentityHashMap<>();

        BlockListSlice(ServerLevel level, Vec3 center, int radius, KindFilter filter, Cursor start) {
            super(level, center, radius, start);
            this.filter = filter;
        }

        @Override
        void scan(LevelChunk chunk, int index) {
            var skip = skip(index);
            var seen = new int[1];
            forEachBlock(chunk, (pos, state) -> {
                if (!matches.computeIfAbsent(state, this::matches)) {
                    return true;
                }
                if (seen[0] < skip) {
                    seen[0]++;
                    return true;
                }
                var kind = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                if (!offer(index, seen[0], new BlockHandle(kind, new BlockPlace(dimension(), pos.getX(), pos.getY(), pos.getZ())))) {
                    return false;
                }
                seen[0]++;
                return true;
            });
        }

        private boolean matches(BlockState state) {
            var kind = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            var tags = filter.tags().isEmpty() ? List.<String>of() : state.getTags().map(tag -> tag.location().toString()).toList();
            var name = filter.text().isEmpty() ? null : state.getBlock().getName().getString();
            return filter.matches(kind, tags, name);
        }
    }

    private static final class EntityListSlice extends ListSlice<EntityHandle> {
        private final KindFilter filter;

        EntityListSlice(ServerLevel level, Vec3 center, int radius, KindFilter filter, Cursor start) {
            super(level, center, radius, start);
            this.filter = filter;
        }

        @Override
        void scan(LevelChunk chunk, int index) {
            var chunkPos = chunk.getPos();
            var box = new AABB(chunkPos.getMinBlockX(), center.y - radius, chunkPos.getMinBlockZ(), chunkPos.getMinBlockX() + 16, center.y + radius, chunkPos.getMinBlockZ() + 16);
            var found = new ArrayList<>(level.getEntities((Entity) null, box, entity -> inChunk(entity, chunkPos)
                    && entity.position().distanceToSqr(center) <= radiusSquared && matches(entity)));
            found.sort(Comparator.comparing(Entity::getUUID));
            for (var seen = skip(index); seen < found.size(); seen++) {
                var entity = found.get(seen);
                var kind = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
                if (!offer(index, seen, new EntityHandle(kind, entity.getUUID(), dimension(), entity.getX(), entity.getY(), entity.getZ(), entity.getName().getString()))) {
                    return;
                }
            }
        }

        private static boolean inChunk(Entity entity, ChunkPos chunk) {
            return SectionPos.blockToSectionCoord(entity.getBlockX()) == chunk.x && SectionPos.blockToSectionCoord(entity.getBlockZ()) == chunk.z;
        }

        private boolean matches(Entity entity) {
            var kind = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            var tags = filter.tags().isEmpty() ? List.<String>of()
                    : BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(entity.getType()).tags().map(tag -> tag.location().toString()).toList();
            return filter.matches(kind, tags, entity.getName().getString());
        }
    }
}
