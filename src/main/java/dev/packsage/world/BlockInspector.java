package dev.packsage.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public interface BlockInspector {
    String adapterId();

    /** Returns null when this inspector has nothing to add. */
    Section inspectBlock(ServerLevel level, BlockPos pos);
}
