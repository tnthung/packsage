package dev.packsage.world;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One inspected progress entry; {@code facts} holds sections other adapters add, keyed by adapter id. */
public record Progress(ProgressHandle handle, String description, List<ProgressCriterion> criteria, List<ProgressHandle> requires,
                       List<ProgressHandle> unlocks, List<String> rewards, Instant doneAt, Map<String, Section> facts) {
    public Progress {
        criteria = List.copyOf(criteria);
        requires = List.copyOf(requires);
        unlocks = List.copyOf(unlocks);
        rewards = List.copyOf(rewards);
        facts = Collections.unmodifiableMap(new LinkedHashMap<>(facts));
    }

    /** A hidden entry: no description, criteria, requirements, unlocks, rewards or facts. */
    public static Progress hidden(ProgressHandle handle) {
        return new Progress(handle, null, List.of(), List.of(), List.of(), List.of(), null, Map.of());
    }
}
