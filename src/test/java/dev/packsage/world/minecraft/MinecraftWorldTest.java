package dev.packsage.world.minecraft;

import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
