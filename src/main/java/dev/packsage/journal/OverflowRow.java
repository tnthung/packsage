package dev.packsage.journal;

/**
 * A count-only tally of events that arrived while the queue was full.
 *
 * @param actor   the events' actor, or {@code *} once the tally keys ran out
 * @param subject the events' subject, or {@code *} once the tally keys ran out
 * @param count   the number of events folded in
 * @param amount  the sum of their amounts
 */
public record OverflowRow(EventType type, String dimension, String actor, String subject, long count, long amount,
                          long firstGameTime, long lastGameTime, long firstWall, long lastWall) {
}
