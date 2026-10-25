package dev.packsage.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolTimingTest {
    @Test
    void unionCountsOverlapOnce() {
        assertEquals(30, ToolTiming.unionMillis(List.of(new ToolTiming.Interval(0, 10_000_000),
                new ToolTiming.Interval(5_000_000, 20_000_000), new ToolTiming.Interval(30_000_000, 40_000_000))));
        assertEquals(50, ToolTiming.unionMillis(List.of(new ToolTiming.Interval(0, 50_000_000),
                new ToolTiming.Interval(10_000_000, 20_000_000))));
        assertEquals(0, ToolTiming.unionMillis(List.of()));
        assertEquals(2, ToolTiming.unionMillis(List.of(new ToolTiming.Interval(5_000_000, 6_600_000),
                new ToolTiming.Interval(0, 600_000))));
    }
}
