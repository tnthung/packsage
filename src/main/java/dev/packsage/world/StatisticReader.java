package dev.packsage.world;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/** Reads a player's statistics; server thread only. */
public interface StatisticReader {
    String adapterId();

    List<String> categories();

    /** An empty {@code categories} means all of them. */
    ServerWork.Slice<List<Statistic>> statistics(ServerPlayer player, List<String> categories, KindFilter subjects);
}
