package dev.packsage.journal;

import java.util.Arrays;
import java.util.Objects;

/**
 * One recorded occurrence, plain data safe to build on any thread.
 *
 * @param actor     {@code player:<uuid>}, {@code entity:<type id>}, {@code explosion} for blocks an explosion broke, or
 *                  {@code environment} when there is no attacker
 * @param group     an extra merge discriminator, null for most types
 * @param amount    items moved for container transfers, blocks destroyed for an explosion, items received for a trade,
 *                  and the number of positions otherwise
 * @param positions {@link Positions#pack} values, at least one
 * @param detail    compact JSON or null, cut to at most {@link #MAX_DETAIL} characters without splitting a surrogate pair
 */
public record JournalEvent(EventType type, long gameTime, long wallMillis, String dimension, String actor, String subject,
                           String group, long amount, long[] positions, String detail) {
    static final int MAX_DETAIL = 2048;

    public JournalEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(actor, "actor");
        if (positions == null || positions.length == 0) {
            throw new IllegalArgumentException("a journal event needs at least one position");
        }
        positions = positions.clone();
        if (detail != null && detail.length() > MAX_DETAIL) {
            var end = Character.isHighSurrogate(detail.charAt(MAX_DETAIL - 1)) ? MAX_DETAIL - 1 : MAX_DETAIL;
            detail = detail.substring(0, end);
        }
    }

    @Override
    public long[] positions() {
        return positions.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JournalEvent event
                && type == event.type && gameTime == event.gameTime && wallMillis == event.wallMillis
                && dimension.equals(event.dimension) && actor.equals(event.actor) && Objects.equals(subject, event.subject)
                && Objects.equals(group, event.group) && amount == event.amount && Arrays.equals(positions, event.positions)
                && Objects.equals(detail, event.detail);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(type, gameTime, wallMillis, dimension, actor, subject, group, amount, detail)
                + Arrays.hashCode(positions);
    }

    @Override
    public String toString() {
        return "JournalEvent[type=" + type + ", gameTime=" + gameTime + ", wallMillis=" + wallMillis + ", dimension=" + dimension
                + ", actor=" + actor + ", subject=" + subject + ", group=" + group + ", amount=" + amount
                + ", positions=" + Arrays.toString(positions) + ", detail=" + detail + "]";
    }
}
