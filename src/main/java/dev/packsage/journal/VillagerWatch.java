package dev.packsage.journal;

import dev.packsage.journal.JournalEvents.Stamp;

import java.util.LinkedHashMap;
import java.util.List;

/** Server-thread profession polling with an LRU of 4,096 villager ids. Eviction forgets the profession baseline. */
public final class VillagerWatch {
    static final int MAX_VILLAGERS = 4_096;

    private final LinkedHashMap<String, String> professions = new LinkedHashMap<>(16, 0.75f, true);

    public List<JournalEvent> observe(String villagerId, String profession, int x, int y, int z, Stamp stamp) {
        var previous = professions.put(villagerId, profession);
        if (professions.size() > MAX_VILLAGERS) {
            professions.pollFirstEntry();
        }
        if (previous == null || previous.equals(profession)) {
            return List.of();
        }
        return List.of(JournalEvents.profession(stamp, villagerId, previous, profession, x, y, z));
    }
}
