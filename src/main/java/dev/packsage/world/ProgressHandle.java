package dev.packsage.world;

/** A reference to one progress entry; {@code title} is null exactly when the entry is hidden. */
public record ProgressHandle(ProgressId id, ProgressId tree, String title, ProgressStatus status) {
    public ProgressHandle {
        if ((title == null) != (status == ProgressStatus.HIDDEN)) {
            throw new IllegalArgumentException("A progress title must be null exactly when hidden: " + id);
        }
    }
}
