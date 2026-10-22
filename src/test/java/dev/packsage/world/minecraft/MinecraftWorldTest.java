package dev.packsage.world.minecraft;

import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
}
