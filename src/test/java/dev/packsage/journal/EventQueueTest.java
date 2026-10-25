package dev.packsage.journal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventQueueTest {
    private static final String OVERWORLD = "minecraft:overworld";

    @Test
    void drainReturnsEventsInOfferOrder() {
        var queue = new EventQueue(EventQueue.CAPACITY, EventQueue.OVERFLOW_KEYS);
        var first = sand(1, 0);
        var second = event("minecraft:gravel", 2, 1);
        var third = sand(3, 2);
        assertTrue(queue.offer(first));
        assertTrue(queue.offer(second));
        assertTrue(queue.offer(third));
        assertEquals(List.of(first, second), queue.drain(2));
        assertEquals(List.of(third), queue.drain(10));
        assertEquals(List.of(), queue.drain(10));
        assertEquals(0, queue.overflowed());
    }

    @Test
    void offersBeyondCapacityFoldIntoCountOnlyTallies() {
        var queue = new EventQueue(10, EventQueue.OVERFLOW_KEYS);
        for (int i = 1; i <= 25; i++) {
            assertEquals(i <= 10, queue.offer(sand(i, i)));
        }
        assertEquals(10, queue.drain(100).size());
        assertEquals(15, queue.overflowed());
        var rows = queue.drainOverflow();
        assertEquals(List.of(new OverflowRow(EventType.BREAK, OVERWORLD, "player:A", "minecraft:sand", 15, 15,
                11, 25, wall(11), wall(25))), rows);
        assertEquals(List.of(), queue.drainOverflow());
        assertTrue(queue.offer(sand(26, 26)));
    }

    @Test
    void overflowKeysBeyondTheCapCollapsePerTypeAndDimension() {
        var queue = new EventQueue(0, 2);
        for (int s = 0; s < 5; s++) {
            for (int i = 0; i < 3; i++) {
                assertFalse(queue.offer(event("minecraft:block_" + s, 10L * s + i, i)));
            }
        }
        var rows = queue.drainOverflow();
        assertEquals(List.of(
                new OverflowRow(EventType.BREAK, OVERWORLD, "player:A", "minecraft:block_0", 3, 3, 0, 2, wall(0), wall(2)),
                new OverflowRow(EventType.BREAK, OVERWORLD, "player:A", "minecraft:block_1", 3, 3, 10, 12, wall(10), wall(12)),
                new OverflowRow(EventType.BREAK, OVERWORLD, "*", "*", 9, 9, 20, 42, wall(20), wall(42))), rows);
        assertEquals(15, queue.overflowed());
    }

    @Test
    void offerAfterCloseIsCountedAsDropped() {
        var queue = new EventQueue(10, EventQueue.OVERFLOW_KEYS);
        assertTrue(queue.offer(sand(1, 1)));
        queue.close();
        assertFalse(queue.offer(sand(2, 2)));
        assertFalse(queue.offer(sand(3, 3)));
        assertEquals(2, queue.dropped());
        assertEquals(0, queue.overflowed());
        assertEquals(List.of(sand(1, 1)), queue.drain(10));
        assertEquals(List.of(), queue.drainOverflow());
    }

    @Test
    void offerNeverBlocksWhileAnotherThreadDrains() throws InterruptedException {
        var queue = new EventQueue(1_000, EventQueue.OVERFLOW_KEYS);
        var drained = new AtomicLong();
        var overflow = new AtomicLong();
        var done = new AtomicBoolean();
        var drainer = new Thread(() -> {
            while (!done.get()) {
                drained.addAndGet(queue.drain(256).size());
                overflow.addAndGet(queue.drainOverflow().stream().mapToLong(OverflowRow::count).sum());
            }
        });
        drainer.start();
        var event = sand(1, 1);
        var offerer = new Thread(() -> {
            for (int i = 0; i < 200_000; i++) {
                if (i == 150_000) {
                    queue.close();
                }
                queue.offer(event);
            }
        });
        offerer.start();
        offerer.join(10_000);
        assertFalse(offerer.isAlive());
        done.set(true);
        drainer.join(10_000);
        assertFalse(drainer.isAlive());
        drained.addAndGet(queue.drain(Integer.MAX_VALUE).size());
        overflow.addAndGet(queue.drainOverflow().stream().mapToLong(OverflowRow::count).sum());
        assertEquals(50_000, queue.dropped());
        assertEquals(queue.overflowed(), overflow.get());
        assertEquals(200_000, drained.get() + overflow.get() + queue.dropped());
    }

    private static JournalEvent sand(long tick, int x) {
        return event("minecraft:sand", tick, x);
    }

    private static JournalEvent event(String subject, long tick, int x) {
        return new JournalEvent(EventType.BREAK, tick, wall(tick), OVERWORLD, "player:A", subject, null, 1,
                new long[]{Positions.pack(x, 64, 0)}, null);
    }

    private static long wall(long tick) {
        return 1_000L + tick * 50;
    }
}
