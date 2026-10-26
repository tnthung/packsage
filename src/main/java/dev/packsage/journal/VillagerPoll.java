package dev.packsage.journal;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.LevelEntityGetterAdapter;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Walks each level's loaded entity sections a few entries per tick and hands over the villagers it passes. It keeps only
 * a section key and an index per level, so it holds no entity and no engine iterator between ticks, and an entity that
 * joins, leaves or reloads is simply met, or not met, on a later cycle.
 */
final class VillagerPoll {
    /** Section moves and entity visits per level and tick. */
    static final int VISITS_PER_TICK = 64;

    private final Map<String, Position> positions = new HashMap<>();

    void step(ServerLevel level, Consumer<Villager> observe) {
        if (!(level.getEntities() instanceof LevelEntityGetterAdapter<Entity> getter)) return;
        step(level.dimension().location().toString(), getter.sectionStorage, Villager.class, observe);
    }

    <T extends Entity> void step(String dimension, EntitySectionStorage<Entity> storage, Class<T> type, Consumer<T> observe) {
        var position = positions.computeIfAbsent(dimension, ignored -> new Position());
        for (int visits = 0; visits < VISITS_PER_TICK; visits++) {
            var current = position.started ? storage.getSection(position.section) : null;
            if (current != null && current.getStatus().isAccessible() && position.index < current.storage.allInstances.size()) {
                // Removals can shift the list under the index; a skipped entity is met on the next cycle.
                var entity = current.storage.allInstances.get(position.index++);
                if (type.isInstance(entity) && !entity.isRemoved()) observe.accept(type.cast(entity));
                continue;
            }
            // Choose the next section from a fresh sorted view; no engine iterator survives a step.
            var rest = !position.started ? storage.sectionIds
                    : position.section == Long.MAX_VALUE ? null : storage.sectionIds.tailSet(position.section + 1);
            if (rest == null || rest.isEmpty()) {
                // A cycle is complete; the next tick starts over from the first section.
                position.started = false;
                position.index = 0;
                return;
            }
            position.section = rest.firstLong();
            position.started = true;
            position.index = 0;
        }
    }

    private static final class Position {
        boolean started;
        long section;
        int index;
    }
}
