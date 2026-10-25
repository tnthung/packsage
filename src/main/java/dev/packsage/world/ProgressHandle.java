package dev.packsage.world;

/**
 * A reference to one progress entry; {@code title} is null exactly when the entry is hidden, and otherwise not blank.
 * An adapter whose visible entry has no display title uses the entry's local id as its title. {@code tabVisible}
 * records whether the player can open its tab, independently of the entry's progress status.
 * Unearned hidden entries use opaque world-session ids; a hidden tree root also has an opaque tree id and no tab link.
 */
public record ProgressHandle(ProgressId id, ProgressId tree, String title, ProgressStatus status, boolean tabVisible) {
    public ProgressHandle {
        if (status == ProgressStatus.HIDDEN ? title != null : title == null || title.isBlank()) {
            throw new IllegalArgumentException("A progress title must be null exactly when hidden: " + id);
        }
    }
}
