package dev.packsage.journal;

import dev.packsage.journal.JournalEvents.Stamp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Server-thread menu snapshots: one per player, at most 64 menus and 4,096 item ids per menu. */
public final class ContainerSessions {
    static final int MAX_SESSIONS = 64;
    static final int MAX_ITEMS = 4_096;

    public record Holder(String kind, String dimension, int x, int y, int z, String entityId) {
    }

    private record Session(int containerId, Holder holder, Map<String, Long> contents) {
    }

    private final LinkedHashMap<String, Session> sessions = new LinkedHashMap<>(16, 0.75f, true);
    private long evictions;
    private long oversizedSnapshots;

    /** Oversized snapshots are rejected rather than truncated into fictitious transfers. */
    public void open(String playerRef, int containerId, Holder holder, Map<String, Long> contents) {
        if (contents.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("a container snapshot exceeds 4,096 item ids");
        }
        sessions.put(playerRef, new Session(containerId, holder, Map.copyOf(contents)));
        if (sessions.size() > MAX_SESSIONS) {
            sessions.pollFirstEntry();
            evictions++;
        }
    }

    public List<JournalEvent> close(String playerRef, String playerName, int containerId, Map<String, Long> contents, Stamp stamp) {
        var session = sessions.get(playerRef);
        if (session == null || session.containerId() != containerId) {
            return List.of();
        }
        sessions.remove(playerRef);
        if (contents.size() > MAX_ITEMS) {
            oversizedSnapshots++;
            return List.of();
        }
        var items = new TreeSet<>(session.contents().keySet());
        items.addAll(contents.keySet());
        var events = new ArrayList<JournalEvent>();
        var holder = session.holder();
        for (var item : items) {
            var delta = Math.subtractExact(contents.getOrDefault(item, 0L), session.contents().getOrDefault(item, 0L));
            if (delta != 0) {
                events.add(JournalEvents.transfer(new Stamp(stamp.gameTime(), stamp.wallMillis(), holder.dimension()),
                        playerRef, playerName, item, delta, holder.kind(), holder.x(), holder.y(), holder.z(), holder.entityId()));
            }
        }
        return List.copyOf(events);
    }

    public Set<String> openPlayers() {
        return Set.copyOf(sessions.keySet());
    }

    public boolean has(String playerRef) {
        return sessions.containsKey(playerRef);
    }

    public long evictions() {
        return evictions;
    }

    public long oversizedSnapshots() {
        return oversizedSnapshots;
    }
}
