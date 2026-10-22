package dev.packsage.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

public sealed interface LiveHolder {
    record OfPlayer(ServerPlayer player) implements LiveHolder {
    }

    record OfBlock(ServerLevel level, BlockPos pos) implements LiveHolder {
    }

    record OfEntity(Entity entity) implements LiveHolder {
    }

    record OfItem(ItemStack stack) implements LiveHolder {
    }
}
