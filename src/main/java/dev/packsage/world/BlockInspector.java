package dev.packsage.world;

import dev.packsage.knowledge.Evidence;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public interface BlockInspector {
    String adapterId();

    /** Returns null when this inspector has nothing to add. */
    Evidence.JsonObject inspectBlock(ServerLevel level, BlockPos pos);
}
