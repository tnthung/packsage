package dev.packsage.world.minecraft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import dev.packsage.knowledge.Evidence;
import dev.packsage.world.WorldRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftWorldTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.bootStrap();
    }

    @Test
    void notableRolesClassifyBedsWorkstationsFluidsLightRedstoneAndContainers() {
        var world = new MinecraftWorld();
        assertEquals("bed", world.notableRole(Blocks.RED_BED.defaultBlockState(), null));
        assertEquals("workstation", world.notableRole(Blocks.COMPOSTER.defaultBlockState(), null));
        assertEquals("fluid", world.notableRole(Blocks.WATER.defaultBlockState(), null));
        assertEquals("light", world.notableRole(Blocks.TORCH.defaultBlockState(), null));
        assertEquals("redstone", world.notableRole(Blocks.REDSTONE_WIRE.defaultBlockState(), null));
        var chest = Blocks.CHEST.defaultBlockState();
        assertEquals("container", world.notableRole(chest, new ChestBlockEntity(BlockPos.ZERO, chest)));
        assertNull(world.notableRole(Blocks.STONE.defaultBlockState(), null));
    }

    @Test
    void containerBlockEntitiesClassifyAsContainersEvenWhenTheyAreWorkstations() {
        var world = new MinecraftWorld();
        var barrel = Blocks.BARREL.defaultBlockState();
        assertEquals("container", world.notableRole(barrel, new BarrelBlockEntity(BlockPos.ZERO, barrel)));
        assertEquals("workstation", world.notableRole(Blocks.COMPOSTER.defaultBlockState(), null));
    }

    @Test
    void signalChunksCoverEveryChunkWithinTwoBlocks() {
        assertEquals(Set.of(new ChunkPos(0, 0)), Set.copyOf(MinecraftWorld.signalChunks(new BlockPos(8, 64, 8))));
        assertEquals(Set.of(new ChunkPos(0, 0), new ChunkPos(1, 0)), Set.copyOf(MinecraftWorld.signalChunks(new BlockPos(14, 64, 8))));
        assertEquals(Set.of(new ChunkPos(-1, -1), new ChunkPos(0, -1), new ChunkPos(-1, 0), new ChunkPos(0, 0)),
                Set.copyOf(MinecraftWorld.signalChunks(new BlockPos(1, 64, 0))));
    }

    @Test
    void anUncachedPoiColumnNeverEntersTheStorageLoadingPath() throws Exception {
        // The sentinel rejects storage access without creating an IOWorker or touching a world file.
        class Storage extends net.minecraft.world.level.chunk.storage.SimpleRegionStorage {
            int reads;
            Storage() { super(null, null, null, false, null); }
            @Override
            public java.util.concurrent.CompletableFuture<Optional<net.minecraft.nbt.CompoundTag>> read(ChunkPos pos) {
                reads++;
                throw new AssertionError("POI storage was accessed");
            }
        }
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) field.get(null);
        var manager = (PoiManager) unsafe.allocateInstance(PoiManager.class);
        var disk = (Storage) unsafe.allocateInstance(Storage.class);
        var backing = SectionStorage.class.getDeclaredField("simpleRegionStorage");
        backing.setAccessible(true);
        backing.set(manager, disk);
        var height = SectionStorage.class.getDeclaredField("levelHeightAccessor");
        height.setAccessible(true);
        height.set(manager, net.minecraft.world.level.LevelHeightAccessor.create(-64, 384));
        var storage = SectionStorage.class.getDeclaredField("storage");
        storage.setAccessible(true);
        var cached = new Long2ObjectOpenHashMap<Optional<PoiSection>>();
        storage.set(manager, cached);

        var facts = MinecraftWorld.poi(manager, BlockPos.ZERO);

        assertEquals(new Evidence.JsonString("unavailable: POI section is not cached"), facts.values().get("status"));
        assertEquals(0, cached.size());
        assertEquals(0, disk.reads);
        assertEquals("POI storage was accessed", assertThrows(AssertionError.class, () -> manager.getType(BlockPos.ZERO)).getMessage());
        assertEquals(1, disk.reads, "the vanilla path proves the storage sentinel is armed");
        cached.put(SectionPos.asLong(BlockPos.ZERO), Optional.empty());
        assertNull(MinecraftWorld.poi(manager, BlockPos.ZERO), "a cached empty section is known to have no POI");
        assertEquals(1, disk.reads);
    }

    @Test
    void uncachedPoiFactsMakeTheRegistrySectionPartial() throws Exception {
        class Chunk extends LevelChunk {
            Chunk() { super(null, new ChunkPos(0, 0)); }
            @Override public BlockState getBlockState(BlockPos pos) { return Blocks.STONE.defaultBlockState(); }
        }
        class Chunks extends ServerChunkCache {
            LevelChunk chunk;
            Chunks() { super(null, null, null, null, null, null, 0, 0, false, null, null, null); }
            @Override public LevelChunk getChunkNow(int x, int z) { return chunk; }
        }
        class World extends ServerLevel {
            Chunks chunks;
            PoiManager poi;
            World() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
            @Override public ServerChunkCache getChunkSource() { return chunks; }
            @Override public PoiManager getPoiManager() { return poi; }
            @Override public int getBrightness(LightLayer layer, BlockPos pos) { return 0; }
            @Override public int getBestNeighborSignal(BlockPos pos) { return 0; }
        }
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) field.get(null);
        var world = (World) unsafe.allocateInstance(World.class);
        world.chunks = (Chunks) unsafe.allocateInstance(Chunks.class);
        world.chunks.chunk = (Chunk) unsafe.allocateInstance(Chunk.class);
        world.poi = (PoiManager) unsafe.allocateInstance(PoiManager.class);
        var storage = SectionStorage.class.getDeclaredField("storage");
        storage.setAccessible(true);
        var cached = new Long2ObjectOpenHashMap<Optional<PoiSection>>();
        storage.set(world.poi, cached);
        var registry = new WorldRegistry();
        registry.register(new MinecraftWorld());

        var section = registry.inspectBlock(world, BlockPos.ZERO).get("minecraft");

        assertEquals(Evidence.Coverage.PARTIAL, section.coverage());
        assertTrue(section.note().contains("POI"));
        cached.put(SectionPos.asLong(BlockPos.ZERO), Optional.empty());
        assertEquals(Evidence.Coverage.COMPLETE, registry.inspectBlock(world, BlockPos.ZERO).get("minecraft").coverage());
    }
}
