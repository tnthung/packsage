package dev.packsage.journal;

import dev.packsage.journal.JournalEvents.Stamp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Server-thread raid polling, retaining at most 1,024 dimension/id pairs, including finished raids until absent. */
public final class RaidWatch {
    static final int MAX_RAIDS = 1_024;

    public record RaidState(boolean started, String outcome, int omen, int waves, int x, int y, int z) {
    }

    private record Key(String dimension, int id) {
    }

    private record Tracked(RaidState state, boolean started, boolean finished) {
    }

    private final LinkedHashMap<Key, Tracked> raids = new LinkedHashMap<>(16, 0.75f, true);

    public List<JournalEvent> observe(int raidId, RaidState state, Stamp stamp) {
        var key = new Key(stamp.dimension(), raidId);
        var previous = raids.get(key);
        var started = previous != null && previous.started();
        var finished = previous != null && previous.finished();
        var events = new ArrayList<JournalEvent>();
        if (state.started() && !started && !finished) {
            events.add(JournalEvents.raidStarted(stamp, raidId, state.omen(), state.waves(), state.x(), state.y(), state.z()));
            started = true;
        }
        if (state.outcome() != null && !finished) {
            events.add(JournalEvents.raidFinished(stamp, raidId, state.outcome(), state.waves(), state.x(), state.y(), state.z()));
            finished = true;
        }
        raids.put(key, new Tracked(state, started, finished));
        if (raids.size() > MAX_RAIDS) {
            raids.pollFirstEntry();
        }
        return List.copyOf(events);
    }

    /** The caller supplies all ids still present in the stamp's dimension, including raids no longer near a player. */
    public List<JournalEvent> missing(Set<Integer> present, Stamp stamp) {
        var events = new ArrayList<JournalEvent>();
        var iterator = raids.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!entry.getKey().dimension().equals(stamp.dimension()) || present.contains(entry.getKey().id())) {
                continue;
            }
            var tracked = entry.getValue();
            if (!tracked.finished()) {
                var state = tracked.state();
                events.add(JournalEvents.raidFinished(stamp, entry.getKey().id(), "unknown", state.waves(), state.x(), state.y(), state.z()));
            }
            iterator.remove();
        }
        return List.copyOf(events);
    }
}
