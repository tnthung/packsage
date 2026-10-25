package dev.packsage.journal;

import java.util.List;
import java.util.Set;

/** Resolved journal filters; null bounds, area, dimension and text leave that part unrestricted. */
public record JournalFilter(Long afterWall, Long beforeWall, Area area, String dimension, Set<String> types,
                            List<String> subjects, String text, boolean notesOnly) {
    public JournalFilter {
        types = notesOnly ? Set.of("note") : Set.copyOf(types);
        subjects = List.copyOf(subjects);
        notesOnly = types.equals(Set.of("note"));
    }

    public record Area(String dimension, int x, int y, int z, int radius) {
        public Area {
            if (radius < 0) {
                throw new IllegalArgumentException("area radius must not be negative");
            }
        }
    }
}
