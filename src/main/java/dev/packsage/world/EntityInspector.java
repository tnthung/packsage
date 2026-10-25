package dev.packsage.world;

import net.minecraft.world.entity.Entity;

public interface EntityInspector {
    String adapterId();

    /** Returns null when this inspector has nothing to add. */
    Section inspectEntity(Entity entity);
}
