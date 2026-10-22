package dev.packsage.world;

import java.util.List;
import java.util.Map;

public record BlockSummary(Map<String, Integer> counts, List<NotableBlock> notable, int unloadedChunks) {
    public BlockSummary {
        counts = Map.copyOf(counts);
        notable = List.copyOf(notable);
    }
}
