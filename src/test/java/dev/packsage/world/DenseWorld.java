package dev.packsage.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.entity.LevelEntityGetterAdapter;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Loaded chunks with counted entity visits and no world files. Constructors are bypassed. */
final class DenseWorld {
    static final class Chunk extends LevelChunk {
        Map<BlockPos, BlockEntity> blockEntities;
        ChunkPos position;
        Chunk() { super(null, new ChunkPos(0, 0)); }
        @Override public ChunkPos getPos() { return position; }
        @Override public Map<BlockPos, BlockEntity> getBlockEntities() { return blockEntities; }
        @Override public BlockState getBlockState(BlockPos pos) { return Blocks.BARREL.defaultBlockState(); }
    }

    static final class Chunks extends ServerChunkCache {
        LevelChunk chunk;
        LevelChunk neighbor;
        Chunks() { super(null, null, null, null, null, null, 0, 0, false, null, null, null); }
        @Override public LevelChunk getChunkNow(int x, int z) { return z == 0 ? (x == 0 ? chunk : x == 1 ? neighbor : null) : null; }
    }

    static final class World extends ServerLevel {
        Chunks chunks;
        List<Entity> entities;
        int visited;
        int candidates;
        boolean forbidGlobal;
        boolean entitiesUnloaded;
        long gameTime;
        World() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
        @Override public ServerChunkCache getChunkSource() { return chunks; }
        @Override public int getMinBuildHeight() { return -64; }
        @Override public int getHeight() { return 384; }
        @Override public boolean areEntitiesLoaded(long chunk) { return !entitiesUnloaded; }
        @Override public long getGameTime() { return gameTime; }
        @Override public long getDayTime() { return 600; }
        @Override public Entity getEntity(java.util.UUID id) { visited++; return entities.stream().filter(entity -> entity.getUUID().equals(id)).findFirst().orElse(null); }
        @Override public LevelEntityGetter<Entity> getEntities() {
            var sections = new EntitySectionStorage<>(Entity.class, key -> Visibility.TICKING);
            for (var entity : entities) sections.getOrCreateSection(SectionPos.asLong(entity.blockPosition())).add(entity);
            // Count the engine's actual candidate traversal, before its AABB/ownership filters.
            sections.getAllChunksWithExistingSections().forEach((long chunk) -> sections.getExistingSectionsInChunk(chunk).forEach(section -> {
                try {
                    var storageField = section.getClass().getDeclaredField("storage");
                    storageField.setAccessible(true);
                    var storage = storageField.get(section);
                    var all = storage.getClass().getDeclaredField("allInstances");
                    all.setAccessible(true);
                    var original = (List<Entity>) all.get(storage);
                    var counted = new java.util.AbstractList<Entity>() {
                        @Override public Entity get(int index) { candidates++; return original.get(index); }
                        @Override public int size() { return original.size(); }
                    };
                    all.set(storage, counted);
                    var byClass = storage.getClass().getDeclaredField("byClass");
                    byClass.setAccessible(true);
                    ((Map<Class<?>, List<Entity>>) byClass.get(storage)).put(Entity.class, counted);
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError(failure);
                }
            }));
            return new LevelEntityGetterAdapter<>(new EntityLookup<>(), sections);
        }
        @Override public net.minecraft.core.RegistryAccess registryAccess() { return net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY); }
        @Override public Iterable<Entity> getAllEntities() {
            if (forbidGlobal) throw new AssertionError("global entity traversal");
            return () -> new Iterator<>() {
                final Iterator<Entity> iterator = entities.iterator();
                @Override public boolean hasNext() { return iterator.hasNext(); }
                @Override public Entity next() { visited++; return iterator.next(); }
            };
        }
        @Override public List<Entity> getEntities(Entity except, AABB box, Predicate<? super Entity> predicate) {
            var found = new ArrayList<Entity>();
            for (var entity : getAllEntities()) {
                if (entity != except && box.intersects(entity.getBoundingBox()) && predicate.test(entity)) found.add(entity);
            }
            return found;
        }
    }

    static World create(int count) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) field.get(null);
        var world = (World) unsafe.allocateInstance(World.class);
        world.chunks = (Chunks) unsafe.allocateInstance(Chunks.class);
        world.chunks.chunk = (Chunk) unsafe.allocateInstance(Chunk.class);
        ((Chunk) world.chunks.chunk).position = new ChunkPos(0, 0);
        ((Chunk) world.chunks.chunk).blockEntities = new Object2ObjectOpenHashMap<>();
        world.entities = new ArrayList<>();
        world.gameTime = 400;
        var dimension = Level.class.getDeclaredField("dimension");
        dimension.setAccessible(true);
        dimension.set(world, Level.OVERWORLD);
        for (int i = 0; i < count; i++) {
            var entity = new ItemEntity(EntityType.ITEM, world);
            entity.setPos(8, 64, 8);
            world.entities.add(entity);
        }
        return world;
    }
}
