package dev.packsage.world;

import dev.packsage.knowledge.Evidence;
import net.minecraft.server.level.ServerPlayer;

/** Reads a player's progress, such as advancements or quests; server thread only. */
public interface ProgressReader {
    String adapterId();

    /** A visible entry or tree with no display title is titled with its local id, since a handle title may not be blank. */
    ServerWork.Slice<ProgressSnapshot> snapshot(ServerPlayer player);

    /** Returns null when {@code local} is not this adapter's. */
    Progress inspect(ServerPlayer player, String local);

    /** Facts this adapter adds to an entry another adapter owns; null when it has nothing to add. */
    default Evidence.JsonObject progressFacts(ServerPlayer player, ProgressId id) {
        return null;
    }
}
