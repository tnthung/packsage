package dev.packsage.journal;

import java.util.Arrays;

/** A merged journal row under construction: consecutive events of one key, close in time and place. */
public final class Run {
    /**
     * The most positions a run holds. A first event may carry more; the recording rules cap a single event at this many
     * positions (the explosion hook in Task 9), so a run never exceeds it.
     */
    static final int MAX_POSITIONS = 4096;
    static final long IDLE_TICKS = 100;
    static final int MARGIN = 16;

    /** What consecutive events must share to merge. */
    public record MergeKey(EventType type, String actor, String subject, String dimension, String group) {
        static MergeKey of(JournalEvent event) {
            return new MergeKey(event.type(), event.actor(), event.subject(), event.dimension(), event.group());
        }
    }

    private final MergeKey key;
    private final long firstGameTime;
    private final long firstWall;
    private final String detail;
    private long[] positions;
    private int count;
    private long amount;
    private long lastGameTime;
    private long lastWall;
    private Box box;
    private long rowId = -1;
    final long sequence;

    /** Starts a run from its first event, which the recording rules keep within {@link #MAX_POSITIONS} positions. */
    public Run(JournalEvent first) {
        this(first, 0);
    }

    /** Starts a run with the merger's creation sequence, which orders changed runs. */
    Run(JournalEvent first, long sequence) {
        this.sequence = sequence;
        key = MergeKey.of(first);
        firstGameTime = first.gameTime();
        firstWall = first.wallMillis();
        detail = first.detail();
        positions = first.positions();
        count = positions.length;
        amount = first.amount();
        lastGameTime = first.gameTime();
        lastWall = first.wallMillis();
        box = boxOf(positions);
    }

    public MergeKey key() {
        return key;
    }

    public boolean accepts(JournalEvent e) {
        if (!key.type().merges() || !key.equals(MergeKey.of(e))) {
            return false;
        }
        var elapsed = e.gameTime() - lastGameTime;
        if (elapsed < 0 || elapsed >= IDLE_TICKS) {
            return false;
        }
        var added = e.positions();
        return count + added.length <= MAX_POSITIONS && boxOf(added).intersects(box.grow(MARGIN));
    }

    public void add(JournalEvent e) {
        var added = e.positions();
        if (count + added.length > positions.length) {
            positions = Arrays.copyOf(positions, Math.max(count + added.length, Math.min(MAX_POSITIONS, positions.length * 2)));
        }
        System.arraycopy(added, 0, positions, count, added.length);
        count += added.length;
        amount += e.amount();
        lastGameTime = e.gameTime();
        lastWall = Math.max(lastWall, e.wallMillis());
        box = box.union(boxOf(added));
    }

    /** The number of positions added. */
    public int count() {
        return count;
    }

    /** The sum of the events' amounts. */
    public long amount() {
        return amount;
    }

    public long firstGameTime() {
        return firstGameTime;
    }

    public long lastGameTime() {
        return lastGameTime;
    }

    public long firstWall() {
        return firstWall;
    }

    public long lastWall() {
        return lastWall;
    }

    public Box box() {
        return box;
    }

    /** The first packed position. */
    public long first() {
        return positions[0];
    }

    /** The packed positions in the order they were added. */
    public long[] positions() {
        return Arrays.copyOf(positions, count);
    }

    /** The first event's detail. */
    public String detail() {
        return detail;
    }

    /** The journal row id, or -1 until the run is inserted. */
    public long rowId() {
        return rowId;
    }

    public void rowId(long rowId) {
        this.rowId = rowId;
    }

    private static Box boxOf(long[] positions) {
        var box = Box.of(Positions.x(positions[0]), Positions.y(positions[0]), Positions.z(positions[0]));
        for (int i = 1; i < positions.length; i++) {
            box = box.include(Positions.x(positions[i]), Positions.y(positions[i]), Positions.z(positions[i]));
        }
        return box;
    }
}
