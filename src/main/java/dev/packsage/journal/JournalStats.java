package dev.packsage.journal;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Hook and writer counters, safe to update from any thread. Every offered event ends up written (including count-only
 * rows), failed, dropped after close or still queued.
 */
public final class JournalStats {
    static final List<String> LOSS_KINDS = List.of("events failed to record", "overflow events without positions",
            "hook failures", "dropped explosion positions", "unread explosion positions", "tracker evictions",
            "oversized container snapshots");

    /** Persisted counters and the earliest possible unpersisted loss, including event timestamps before the last sample. */
    record LossCheckpoint(long sinceWall, long sinceGameTime, long[] counts) {
    }

    volatile LossCheckpoint lossCheckpoint = new LossCheckpoint(Long.MIN_VALUE, Long.MIN_VALUE, new long[7]);

    /** Events handed to the queue. */
    final LongAdder offered = new LongAdder();
    /** Events that reached the writer as count-only overflow tallies. */
    final LongAdder overflowed = new LongAdder();
    /**
     * Events stored, as run rows or count-only rows. A repeated entry into an already visited structure start is handled
     * by not storing it, and counts as written.
     */
    final LongAdder written = new LongAdder();
    /** Events lost to a failed batch or a journal that never opened. */
    final LongAdder failedEvents = new LongAdder();
    final LongAdder hookFailures = new LongAdder();
    final LongAdder droppedExplosionPositions = new LongAdder();
    final LongAdder unreadExplosionPositions = new LongAdder();
    final LongAdder trackerEvictions = new LongAdder();
    final LongAdder oversizedSnapshots = new LongAdder();
    final AtomicLong maxBatchMillis = new AtomicLong();
    final AtomicLong maxHookNanosPerTick = new AtomicLong();
    private final LongAdder tickHookNanos = new LongAdder();
    private final LongAdder totalHookNanos = new LongAdder();
    private final LongAdder worldStartStopNanos = new LongAdder();
    private long slowestHookNanos;
    private String slowestHook = "none";

    /** Adds one hook's running time to the current tick. */
    public void hookNanos(long nanos) {
        tickHookNanos.add(nanos);
        totalHookNanos.add(nanos);
    }

    /** Adds one gameplay hook's running time to the current tick and remembers the slowest hook by its event. */
    public void hookNanos(String kind, long nanos) {
        hookNanos(nanos);
        synchronized (this) {
            if (nanos > slowestHookNanos) {
                slowestHookNanos = nanos;
                slowestHook = kind;
            }
        }
    }

    /** Opening and closing the journal with the world; kept out of the per-tick gameplay figure. */
    public void worldStartStopNanos(long nanos) {
        worldStartStopNanos.add(nanos);
        totalHookNanos.add(nanos);
    }

    /** Ends the tick, keeping its hook total when it is the largest so far. */
    public void endTick() {
        var nanos = tickHookNanos.sumThenReset();
        maxHookNanosPerTick.accumulateAndGet(nanos, Math::max);
    }

    public long failedEvents() {
        return failedEvents.sum();
    }

    public long hookFailures() {
        return hookFailures.sum();
    }

    public long droppedExplosionPositions() {
        return droppedExplosionPositions.sum();
    }

    public long unreadExplosionPositions() {
        return unreadExplosionPositions.sum();
    }

    public long trackerEvictions() {
        return trackerEvictions.sum();
    }

    public long oversizedSnapshots() {
        return oversizedSnapshots.sum();
    }

    void batchMillis(long millis) {
        maxBatchMillis.accumulateAndGet(millis, Math::max);
    }

    public String summary() {
        return "journal: offered " + offered.sum() + ", written " + written.sum() + " (overflowed " + overflowed.sum()
                + "), failed " + failedEvents.sum() + ", hook failures " + hookFailures.sum()
                + ", dropped explosion positions " + droppedExplosionPositions.sum() + ", unread explosion positions "
                + unreadExplosionPositions.sum() + ", tracker evictions "
                + trackerEvictions.sum() + ", oversized snapshots " + oversizedSnapshots.sum()
                + ", slowest batch " + maxBatchMillis.get() + " ms, hooks "
                + totalHookNanos.sum() / 1_000_000 + " ms total, slowest tick " + maxHookNanosPerTick.get() / 1_000 + " us" + slowestHookText()
                + ", world start and stop " + worldStartStopNanos.sum() / 1_000_000 + " ms";
    }

    private synchronized String slowestHookText() {
        return ", slowest hook " + slowestHook + " " + slowestHookNanos / 1_000 + " us";
    }
}
