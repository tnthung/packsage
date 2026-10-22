package dev.packsage.world;

import dev.packsage.knowledge.Evidence;
import net.minecraft.world.entity.Entity;

public interface EntityInspector {
    String adapterId();

    /** Returns null when this inspector has nothing to add. */
    Evidence.JsonObject inspectEntity(Entity entity);
}
