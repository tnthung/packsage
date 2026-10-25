package dev.packsage.journal;

import dev.packsage.journal.JournalEvents.Stamp;

import java.util.LinkedHashMap;
import java.util.List;

/** Server-thread entry polling: 4,096 LRU starts per session. The writer deduplicates entries after eviction too. */
public final class StructureWatch {
    static final int MAX_STARTS = 4_096;

    private record Start(String dimension, String structure, int chunkX, int chunkZ) {
    }

    private final LinkedHashMap<Start, Boolean> visited = new LinkedHashMap<>(16, 0.75f, true);

    public List<JournalEvent> inside(String playerRef, String structure, int chunkX, int chunkZ, int x, int y, int z, Stamp stamp) {
        if (visited.put(new Start(stamp.dimension(), structure, chunkX, chunkZ), true) != null) {
            return List.of();
        }
        if (visited.size() > MAX_STARTS) {
            visited.pollFirstEntry();
        }
        return List.of(JournalEvents.structure(stamp, playerRef, structure, chunkX, chunkZ, x, y, z));
    }
}
