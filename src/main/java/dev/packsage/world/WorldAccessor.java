package dev.packsage.world;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public interface WorldAccessor {
    String adapterId();

    /** Returns the notable role of the block, or null when it is not notable. */
    String notableRole(BlockState state, BlockEntity entity);
}
