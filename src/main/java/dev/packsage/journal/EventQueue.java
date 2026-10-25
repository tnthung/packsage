package dev.packsage.journal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A bounded queue between the server thread and the journal writer. Offers never block: once the queue is full, events
 * fold into count-only tallies, and after {@link #close()} they are counted as dropped.
 */
public final class EventQueue {
    static final int CAPACITY = 16_384;
    static final int OVERFLOW_KEYS = 1_024;
    private static final String ANY = "*";

    private record TallyKey(EventType type, String dimension, String actor, String subject) {
    }

    private static final class Tally {
        long count;
        long amount;
        long firstGameTime = Long.MAX_VALUE;
        long lastGameTime = Long.MIN_VALUE;
        long firstWall = Long.MAX_VALUE;
        long lastWall = Long.MIN_VALUE;
    }

    private final int capacity;
    private final int overflowKeys;
    private final ConcurrentLinkedQueue<JournalEvent> events = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicLong overflowed = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final Object tallyLock = new Object();
    private Map<TallyKey, Tally> tallies = new LinkedHashMap<>();
    private volatile boolean closed;

    public EventQueue(int capacity, int overflowKeys) {
        if (capacity < 0 || overflowKeys < 0) {
            throw new IllegalArgumentException("capacity and overflowKeys must not be negative");
        }
        this.capacity = capacity;
        this.overflowKeys = overflowKeys;
    }

    /** Queues the event, or tallies it when the queue is full; false when it was not queued. */
    public boolean offer(JournalEvent e) {
        if (closed) {
            dropped.incrementAndGet();
            return false;
        }
        if (size.incrementAndGet() > capacity) {
            size.decrementAndGet();
            fold(e);
            return false;
        }
        events.add(e);
        return true;
    }

    /** Takes up to {@code max} queued events in offer order. */
    public List<JournalEvent> drain(int max) {
        if (max < 0) {
            throw new IllegalArgumentException("max must not be negative");
        }
        var drained = new ArrayList<JournalEvent>(Math.min(max, 1_024));
        JournalEvent e;
        while (drained.size() < max && (e = events.poll()) != null) {
            size.decrementAndGet();
            drained.add(e);
        }
        return drained;
    }

    /** Takes the overflow tallies gathered since the last call, in the order their keys first appeared. */
    public List<OverflowRow> drainOverflow() {
        Map<TallyKey, Tally> taken;
        synchronized (tallyLock) {
            if (tallies.isEmpty()) {
                return List.of();
            }
            taken = tallies;
            tallies = new LinkedHashMap<>();
        }
        var rows = new ArrayList<OverflowRow>(taken.size());
        taken.forEach((key, tally) -> rows.add(new OverflowRow(key.type(), key.dimension(), key.actor(), key.subject(),
                tally.count, tally.amount, tally.firstGameTime, tally.lastGameTime, tally.firstWall, tally.lastWall)));
        return rows;
    }

    /** The number of events folded into tallies so far. */
    public long overflowed() {
        return overflowed.get();
    }

    /** Refuses later offers, counting them as dropped. Queued events and tallies stay drainable. */
    public void close() {
        closed = true;
    }

    /** The number of events offered after {@link #close()}. */
    public long dropped() {
        return dropped.get();
    }

    private void fold(JournalEvent e) {
        var key = new TallyKey(e.type(), e.dimension(), e.actor(), e.subject());
        synchronized (tallyLock) {
            var tally = tallies.get(key);
            if (tally == null) {
                if (tallies.size() >= overflowKeys) {
                    key = new TallyKey(e.type(), e.dimension(), ANY, ANY);
                }
                tally = tallies.computeIfAbsent(key, k -> new Tally());
            }
            tally.count++;
            tally.amount += e.amount();
            tally.firstGameTime = Math.min(tally.firstGameTime, e.gameTime());
            tally.lastGameTime = Math.max(tally.lastGameTime, e.gameTime());
            tally.firstWall = Math.min(tally.firstWall, e.wallMillis());
            tally.lastWall = Math.max(tally.lastWall, e.wallMillis());
            overflowed.incrementAndGet();
        }
    }
}
