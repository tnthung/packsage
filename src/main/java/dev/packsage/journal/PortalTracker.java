package dev.packsage.journal;

import dev.packsage.world.BlockPlace;

import java.util.LinkedHashMap;
import java.util.Optional;

/** Server-thread departure pairing, retaining at most 64 players; a departure is consumed by arrival. */
public final class PortalTracker {
    static final int MAX_PLAYERS = 64;
    static final long MAX_AGE = 200;

    private record Departure(String dimension, BlockPlace at, long gameTime) {
    }

    private final LinkedHashMap<String, Departure> departures = new LinkedHashMap<>(16, 0.75f, true);
    private long evictions;

    public void leaving(String playerRef, String fromDimension, BlockPlace at, long gameTime) {
        departures.put(playerRef, new Departure(fromDimension, at, gameTime));
        if (departures.size() > MAX_PLAYERS) {
            departures.pollFirstEntry();
            evictions++;
        }
    }

    public Optional<BlockPlace> arrived(String playerRef, String fromDimension, long gameTime) {
        var departure = departures.remove(playerRef);
        if (departure == null || !departure.dimension().equals(fromDimension)
                || gameTime < departure.gameTime() || gameTime - departure.gameTime() > MAX_AGE) {
            return Optional.empty();
        }
        return Optional.of(departure.at());
    }

    public long evictions() {
        return evictions;
    }
}
