package dev.packsage.chat;

import java.util.Comparator;
import java.util.List;

final class ToolTiming {
    record Interval(long startNanos, long endNanos) {
    }

    private ToolTiming() {
    }

    static long unionMillis(List<Interval> intervals) {
        if (intervals.isEmpty()) return 0;
        List<Interval> ordered = intervals.stream().sorted(Comparator.comparingLong(Interval::startNanos)).toList();
        long startNanos = ordered.getFirst().startNanos();
        long endNanos = ordered.getFirst().endNanos();
        long coveredNanos = 0;
        for (Interval interval : ordered) {
            if (interval.startNanos() > endNanos) {
                coveredNanos += endNanos - startNanos;
                startNanos = interval.startNanos();
            }
            endNanos = Math.max(endNanos, interval.endNanos());
        }
        return (coveredNanos + endNanos - startNanos) / 1_000_000;
    }
}
