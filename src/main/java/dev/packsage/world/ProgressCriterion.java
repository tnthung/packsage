package dev.packsage.world;

import java.time.Instant;

/** One criterion of a progress entry; {@code doneAt} is null when not done or not recorded. */
public record ProgressCriterion(String id, String label, boolean done, Instant doneAt) {
}
