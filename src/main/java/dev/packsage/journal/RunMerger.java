package dev.packsage.journal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Merges journal events into runs, keeping one open run per merge key so that interleaved actions keep their own
 * runs. Not thread-safe; the writer thread owns it.
 */
public final class RunMerger {
    private final Map<Run.MergeKey, Run> open = new HashMap<>();
    private final TreeMap<Long, Run> changed = new TreeMap<>();
    private long nextSequence;

    public void add(JournalEvent e) {
        if (e.type().merges()) {
            var key = Run.MergeKey.of(e);
            var run = open.get(key);
            if (run != null && run.accepts(e)) {
                run.add(e);
                changed.putIfAbsent(run.sequence, run);
                return;
            }
            open.put(key, create(e));
        } else {
            create(e);
        }
    }

    /** The runs created or extended since the last call, in creation order. */
    public List<Run> takeChanged() {
        var runs = new ArrayList<>(changed.values());
        changed.clear();
        return runs;
    }

    /** Forgets the open runs whose last event is at least {@link Run#IDLE_TICKS} old. */
    public void closeIdle(long nowGameTime) {
        open.values().removeIf(run -> nowGameTime - run.lastGameTime() >= Run.IDLE_TICKS);
    }

    public int openRuns() {
        return open.size();
    }

    private Run create(JournalEvent e) {
        var run = new Run(e);
        run.sequence = nextSequence++;
        changed.put(run.sequence, run);
        return run;
    }
}
