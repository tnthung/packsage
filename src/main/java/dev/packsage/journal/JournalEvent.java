package dev.packsage.journal;

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
 * @param detail    compact JSON or null, cut to {@link #MAX_DETAIL} characters
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
            detail = detail.substring(0, MAX_DETAIL);
        }
    }

    @Override
    public long[] positions() {
        return positions.clone();
    }
}
