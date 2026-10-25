package dev.packsage.world;

/** A listed progress entry; {@code description} is null when hidden. */
public record ProgressEntry(ProgressHandle handle, String description) {
}
