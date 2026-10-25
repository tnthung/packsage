package dev.packsage.journal;

import dev.packsage.world.BlockPlace;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** A player-reported note edit; null tags retain the existing tags. */
public record NoteInput(String text, PlaceChange place, List<String> tags) {
    public sealed interface PlaceChange permits Keep, Clear, Set {
    }

    public record Keep() implements PlaceChange {
    }

    public record Clear() implements PlaceChange {
    }

    public record Set(BlockPlace place) implements PlaceChange {
        public Set {
            if (place == null) {
                throw new IllegalArgumentException("Invalid note place: null");
            }
        }
    }

    public static NoteInput validated(String text, PlaceChange place, List<String> tags) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Invalid note text: " + text);
        }
        if (place == null) {
            throw new IllegalArgumentException("Invalid note place change: null");
        }
        var normalized = new LinkedHashSet<String>();
        if (tags != null) {
            for (var tag : tags) {
                if (tag == null || !tag.trim().toLowerCase(Locale.ROOT).matches("[a-z0-9_-]{1,32}")) {
                    throw new IllegalArgumentException("Invalid note tag: " + tag);
                }
                normalized.add(tag.trim().toLowerCase(Locale.ROOT));
            }
            if (normalized.size() > 8) {
                throw new IllegalArgumentException("Too many note tags: " + tags);
            }
        }
        return new NoteInput(text.length() > 1_000 ? text.substring(0, 1_000) : text, place,
                tags == null ? null : List.copyOf(normalized));
    }
}
