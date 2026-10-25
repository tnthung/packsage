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
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.LevelEntityGetterAdapter;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Sliced block and entity scans in a sphere around a center. Block scans take one non-empty 16x16x16 chunk section
 * per step. Entity scans acquire at most 64 candidates (including resume checks) and revalidate at most 64 snapshot UUIDs
 * per step. Each page re-copies its chunk snapshot, costing about N^2/64 candidate visits over all pages of N entities;
 * acquisition stays bounded per step, with capped rescans if a section changes. A chunk that is not loaded when its step runs, or unloads while its
 * sections are read, is counted, never loaded, and makes the page PARTIAL. List cursors are
 * {@code <chunk index>:<matches to skip in that chunk>} for blocks, or a chunk index and UUID anchor for entities.
 * They stay stable while the relevant loaded content is unchanged. A scan
 * asked to stop early at the call's work budget notes how far it got; a list continues from a cursor at that chunk.
 */
public final class AreaScan {
    public static final int SUMMARY_NOTABLE = 4096;
    public static final int LIST_PAGE = 64;
    static final String PAGE_LIMIT_NOTE = "This page holds " + LIST_PAGE + " matches, the page limit; continue with the cursor";
    private static final int SECTION_SIZE = 16;
    static final int SNAPSHOT_LIMIT = 4096;
    static final String SNAPSHOT_NOTE = "The read spanned multiple server ticks; game-time span: %s..%s";

    private final WorldRegistry registry;

    /** Counts server ticks even while /tick freeze stops game time; test levels without a server use game time. */
    static long serverTick(ServerLevel level) {
        var server = level.getServer();
        return server == null ? level.getGameTime() : server.getTickCount();
    }

    public AreaScan(WorldRegistry registry) {
        this.registry = registry;
    }

    public ServerWork.Slice<Page<BlockSummary>> summary(ServerLevel level, Vec3 center, int radius) {
        return new SummarySlice(level, center, radius, registry.accessors());
    }

    public ServerWork.Slice<Page<BlockHandle>> listBlocks(ServerLevel level, Vec3 center, int radius, KindFilter filter, String cursor) {
        return new BlockListSlice(level, center, radius, filter, Cursor.parse(cursor));
    }

    EntityListSlice listEntities(ServerLevel level, Vec3 center, int radius, KindFilter filter, String cursor) {
        return new EntityListSlice(level, center, radius, filter, cursor);
    }

    /** Visits at most 64 section candidates per step, including nonmatches and resume-anchor checks. */
    static final class EntitySnapshot {
        final NavigableSet<UUID> ids = new TreeSet<>();
        private Long section;
        private int index;
        private EntitySection<Entity> previousSection;
        private int previousSize;
        private UUID previousId;
        private int rescans;

        boolean step(ServerLevel level, ChunkPos pos, Set<String> notes) {
            if (!(level.getEntities() instanceof LevelEntityGetterAdapter<Entity> getter)) {
                notes.add("The entity index is unavailable; entities in this chunk could not be read");
                return false;
            }
            var storage = getter.sectionStorage;
            int visited = 0;
            if (index > 0) {
                var current = storage.getSection(section);
                var changed = current != previousSection || current == null || !current.getStatus().isAccessible()
                        || current.storage.allInstances.size() != previousSize;
                if (!changed) {
                    visited++;
                    changed = !previousId.equals(current.storage.allInstances.get(index - 1).getUUID());
                }
                if (changed) {
                    if (++rescans > 3) {
                        notes.add("An entity section changed repeatedly during acquisition; this chunk snapshot is incomplete");
                        return false;
                    }
                    // ArrayList removals shift unseen entities below the old index; owned UUIDs deduplicate rescans.
                    index = 0;
                }
            }
            for (; visited < LIST_PAGE; visited++) {
                if (section == null) {
                    var sections = storage.getChunkSections(pos.x, pos.z);
                    if (sections.isEmpty()) return false;
                    section = sections.firstLong();
                }
                var current = storage.getSection(section);
                if (current != null && current.getStatus().isAccessible() && index < current.storage.allInstances.size()) {
                    var entity = current.storage.allInstances.get(index++);
                    previousSection = current;
                    previousSize = current.storage.allInstances.size();
                    previousId = entity.getUUID();
                    if (entity.chunkPosition().equals(pos)) ids.add(entity.getUUID());
                } else {
                    if (current != null && !current.getStatus().isAccessible() && !current.isEmpty()) {
                        notes.add("An entity section became unavailable during acquisition; this chunk snapshot is incomplete");
                    }
                    // Choose the next section from a fresh sorted view; no engine iterator survives a step.
                    var remaining = storage.getChunkSections(pos.x, pos.z).tailSet(section + 1);
                    if (remaining.isEmpty()) return false;
                    section = remaining.firstLong();
                    index = 0;
                    rescans = 0;
                }
            }
            return true;
        }
    }

    record Cursor(int chunk, int skip) {
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

    /** Block IDs per block state, so a scan builds each ID string once. */
    static final class BlockKinds {
        private final Map<BlockState, String> kinds = new IdentityHashMap<>();

        String of(BlockState state) {
            return kinds.computeIfAbsent(state, key -> BuiltInRegistries.BLOCK.getKey(key.getBlock()).toString());
        }
    }

    /** Chunks whose column comes within radius of the center horizontally, in x-then-z order. */
    static List<ChunkPos> chunksInRange(Vec3 center, int radius) {
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

    /**
     * Where a list stopped early continues: the chunk it stopped in, skipping the matches seen in that chunk so far
     * when it was inside it, and never fewer than the matches an earlier page returned from it.
     */
    static Cursor resumeCursor(int chunk, int skip, boolean insideChunk, int seen) {
        return new Cursor(chunk, insideChunk ? Math.max(seen, skip) : skip);
    }

    /** The note of a scan stopped at the call's work limit; scans without a cursor point at narrower reads. */
    static String stopNote(int scanned, int total, boolean continues) {
        return "Stopped at the per-call work limit after scanning " + scanned + " of " + total + " chunks; "
                + (continues ? "continue with the cursor" : "use a smaller radius, or list_blocks with a filter");
    }

    /** A scan page: the load notes first, then the scan's own notes; coverage is complete only with no cursor and no notes. */
    static <R> Page<R> page(List<R> items, String nextCursor, int unloaded, int entitiesUnloaded, Collection<String> notes) {
        var load = new ArrayList<String>();
        if (unloaded > 0) {
            load.add(unloaded + " chunks in range were not loaded and were not fully read");
        }
        if (entitiesUnloaded > 0) {
            load.add(entitiesUnloaded + " chunks in range did not have their entities loaded yet, so entities there were not read");
        }
        var all = new ArrayList<>(load);
        all.addAll(notes);
        var coverage = nextCursor == null && all.isEmpty() ? Evidence.Coverage.COMPLETE : Evidence.Coverage.PARTIAL;
        return new Page<>(items, nextCursor, coverage, all.isEmpty() ? null : String.join("; ", all),
                load.isEmpty() ? null : String.join("; ", load));
    }

    /** Whether the section starting at baseY has any block within minY..maxY. */
    static boolean sectionOverlaps(int baseY, int minY, int maxY) {
        return baseY + SECTION_SIZE - 1 >= minY && baseY <= maxY;
    }

    /** Distance from a coordinate to the 16-block span starting at min. */
    private static double gap(double coordinate, int min) {
        return Math.max(0, Math.max(min - coordinate, coordinate - (min + SECTION_SIZE)));
    }

    private interface BlockVisitor {
        /** Returns false to stop visiting. */
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
        final BlockKinds kinds = new BlockKinds();
        int next;
        int unloaded;
        int entitiesUnloaded;
        String nextCursor;
        boolean stopped;
        private LevelChunk current;
        private int section;

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
            if (stopped || nextCursor != null) {
                return false;
            }
            if (current == null) {
                if (next >= chunks.size()) {
                    return false;
                }
                var pos = chunks.get(next);
                var chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
                if (chunk == null) {
                    unloaded++;
                    next++;
                    return hasMore();
                }
                if (!bySection()) {
                    // A loaded chunk can still be waiting for its entities; an empty read there proves nothing.
                    if (!level.areEntitiesLoaded(pos.toLong())) {
                        entitiesUnloaded++;
                        next++;
                        return hasMore();
                    }
                    scanChunk(chunk, next);
                    next++;
                    return hasMore();
                }
                current = chunk;
                section = 0;
                enterChunk(next);
            } else if (level.getChunkSource().getChunkNow(current.getPos().x, current.getPos().z) != current) {
                unloaded++;
                leaveChunk();
                return hasMore();
            }
            var sections = current.getSections();
            while (section < sections.length && (sections[section].hasOnlyAir() || !sectionOverlaps(baseY(current, section), minY, maxY))) {
                section++;
            }
            if (section >= sections.length) {
                leaveChunk();
                return hasMore();
            }
            scanSection(current, section++, next);
            return hasMore();
        }

        /** Stops between steps; lists continue from a cursor at the chunk they stopped in. */
        @Override
        public boolean stopEarly() {
            if (!hasMore()) {
                return true;
            }
            stopped = true;
            nextCursor = stopCursor(current != null);
            notes.add(stopNote(next, chunks.size(), nextCursor != null));
            return true;
        }

        /** The cursor continuing a scan stopped at chunk {@code next}, or null when the scan has no cursor. */
        String stopCursor(boolean insideChunk) {
            return null;
        }

        private void leaveChunk() {
            current = null;
            next++;
        }

        private boolean hasMore() {
            return nextCursor == null && (current != null || next < chunks.size());
        }

        /** Block scans read one section per step; entity scans read a whole chunk per step. */
        boolean bySection() {
            return true;
        }

        void enterChunk(int index) {
        }

        void scanSection(LevelChunk chunk, int section, int index) {
        }

        void scanChunk(LevelChunk chunk, int index) {
        }

        <R> Page<R> page(List<R> items) {
            return AreaScan.page(items, nextCursor, unloaded, entitiesUnloaded, notes);
        }

        String dimension() {
            return level.dimension().location().toString();
        }

        private static int baseY(LevelChunk chunk, int section) {
            return SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(section));
        }

        void forEachBlock(LevelChunk chunk, int index, BlockVisitor visitor) {
            var section = chunk.getSections()[index];
            var baseY = baseY(chunk, index);
            var minX = chunk.getPos().getMinBlockX();
            var minZ = chunk.getPos().getMinBlockZ();
            var pos = new BlockPos.MutableBlockPos();
            for (var y = Math.max(0, minY - baseY); y <= Math.min(SECTION_SIZE - 1, maxY - baseY); y++) {
                for (var z = 0; z < SECTION_SIZE; z++) {
                    for (var x = 0; x < SECTION_SIZE; x++) {
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
        String stopCursor(boolean insideChunk) {
            return resumeCursor(next, skip(next), insideChunk, seenInChunk()).text();
        }

        /** Matches seen so far in the chunk being read section by section. */
        int seenInChunk() {
            return 0;
        }

        @Override
        public Page<T> result() {
            if (nextCursor != null && !stopped) {
                notes.add(PAGE_LIMIT_NOTE);
            }
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
        void scanSection(LevelChunk chunk, int section, int index) {
            forEachBlock(chunk, section, (pos, state) -> {
                var kind = kinds.of(state);
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
        private int seen;

        BlockListSlice(ServerLevel level, Vec3 center, int radius, KindFilter filter, Cursor start) {
            super(level, center, radius, start);
            this.filter = filter;
        }

        @Override
        void enterChunk(int index) {
            seen = 0;
        }

        @Override
        int seenInChunk() {
            return seen;
        }

        @Override
        void scanSection(LevelChunk chunk, int section, int index) {
            var skip = skip(index);
            forEachBlock(chunk, section, (pos, state) -> {
                if (!matches.computeIfAbsent(state, this::matches)) {
                    return true;
                }
                if (seen < skip) {
                    seen++;
                    return true;
                }
                if (!offer(index, seen, new BlockHandle(kinds.of(state), new BlockPlace(dimension(), pos.getX(), pos.getY(), pos.getZ())))) {
                    return false;
                }
                seen++;
                return true;
            });
        }

        private boolean matches(BlockState state) {
            var tags = filter.tags().isEmpty() ? List.<String>of() : state.getTags().map(tag -> tag.location().toString()).toList();
            var name = filter.text().isEmpty() ? null : state.getBlock().getName().getString();
            return filter.matches(kinds.of(state), tags, name);
        }
    }

    /** Revalidates incrementally copied chunk UUIDs, resuming pages after a stable sorted anchor. */
    static final class EntityListSlice implements ServerWork.Slice<Page<EntityHandle>> {
        final List<String> cursors = new ArrayList<>();
        private final ServerLevel level;
        private final Vec3 center;
        private final double radiusSquared;
        private final KindFilter filter;
        private final List<ChunkPos> chunks;
        private final List<EntityHandle> items = new ArrayList<>();
        private final Set<String> notes = new LinkedHashSet<>();
        private final Set<UUID> seen = new HashSet<>();
        private EntitySnapshot snapshot;
        private NavigableSet<UUID> entities;
        private UUID anchor;
        private int chunk;
        private int unloaded;
        private int entitiesUnloaded;
        private String nextCursor;
        private boolean stopped;
        private Long startedAt;
        private long fromGameTime;

        EntityListSlice(ServerLevel level, Vec3 center, int radius, KindFilter filter, String cursor) {
            this.level = level;
            this.center = center;
            this.radiusSquared = (double) radius * radius;
            this.filter = filter;
            this.chunks = chunksInRange(center, radius);
            if (cursor != null) {
                var parts = cursor.split(":", -1);
                if (parts.length != 2) throw new IllegalArgumentException("Invalid entity cursor: " + cursor);
                chunk = Integer.parseInt(parts[0]);
                if (chunk < 0 || chunk > chunks.size()) throw new IllegalArgumentException("Invalid entity cursor: " + cursor);
                anchor = parts[1].isEmpty() ? null : UUID.fromString(parts[1]);
            }
        }

        @Override
        public boolean step() {
            if (stopped || nextCursor != null || chunk >= chunks.size()) return false;
            if (startedAt == null) {
                startedAt = serverTick(level);
                fromGameTime = level.getGameTime();
            }
            var pos = chunks.get(chunk);
            if (level.getChunkSource().getChunkNow(pos.x, pos.z) == null || !level.areEntitiesLoaded(pos.toLong())) {
                if (level.getChunkSource().getChunkNow(pos.x, pos.z) == null) unloaded++;
                else entitiesUnloaded++;
                entities = null;
                snapshot = null;
                anchor = null;
                chunk++;
                return chunk < chunks.size();
            }
            if (entities == null) {
                if (snapshot == null) snapshot = new EntitySnapshot();
                if (snapshot.step(level, pos, notes)) return true;
                entities = snapshot.ids;
                // Pages resume after the anchor in UUID order, so an anchor that has since gone skips nothing.
                // Deliver progress before the call budget can stop us and discard this acquired snapshot.
            }
            for (int read = 0; read < LIST_PAGE; read++) {
                var id = anchor == null ? (entities.isEmpty() ? null : entities.first()) : entities.higher(anchor);
                if (id == null) break;
                var entity = level.getEntity(id);
                if (entity == null || entity.isRemoved()) notes.add("An entity from a chunk snapshot disappeared during the read");
                if (entity != null && !entity.isRemoved()) {
                    var x = SectionPos.blockToSectionCoord(entity.getBlockX());
                    var z = SectionPos.blockToSectionCoord(entity.getBlockZ());
                    if (level.getChunkSource().getChunkNow(x, z) == null || !level.areEntitiesLoaded(ChunkPos.asLong(x, z))) {
                        notes.add("An entity moved outside loaded chunks during the read");
                        entity = null;
                    }
                }
                if (entity != null && !entity.isRemoved() && !seen.contains(id) && entity.position().distanceToSqr(center) <= radiusSquared) {
                    var kind = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
                    var tags = filter.tags().isEmpty() ? List.<String>of()
                            : BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(entity.getType()).tags().map(tag -> tag.location().toString()).toList();
                    if (filter.matches(kind, tags, entity.getName().getString())) {
                        if (items.size() >= LIST_PAGE) {
                            nextCursor = chunk + ":" + (anchor == null ? "" : anchor);
                            notes.add(PAGE_LIMIT_NOTE);
                            return false;
                        }
                        items.add(new EntityHandle(kind, id, level.dimension().location().toString(),
                                entity.getX(), entity.getY(), entity.getZ(), entity.getName().getString()));
                        cursors.add(chunk + ":" + id);
                    }
                }
                seen.add(id);
                anchor = id;
            }
            if (entities.isEmpty() || anchor != null && entities.higher(anchor) == null) {
                entities = null;
                snapshot = null;
                anchor = null;
                chunk++;
            }
            return chunk < chunks.size();
        }

        @Override
        public boolean stopEarly() {
            if (snapshot != null && entities == null) return false;
            stopped = true;
            if (chunk < chunks.size()) nextCursor = chunk + ":" + (anchor == null ? "" : anchor);
            notes.add("Stopped at the per-call work limit while traversing chunk snapshots; continue with the cursor");
            return true;
        }

        @Override
        public Page<EntityHandle> result() {
            var page = AreaScan.page(items, nextCursor, unloaded, entitiesUnloaded, notes);
            if (startedAt == null || serverTick(level) == startedAt) return page;
            return new Page<>(page.items(), page.nextCursor(), page.coverage(),
                    (page.note() == null ? "" : page.note() + "; ") + SNAPSHOT_NOTE.formatted(fromGameTime, level.getGameTime()), page.loadNote());
        }
    }
}
