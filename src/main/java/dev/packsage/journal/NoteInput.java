package dev.packsage.journal;

import dev.packsage.world.BlockPlace;

import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

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
        var normalized = new TreeSet<String>();
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
        if (text.length() > 1_000) {
            text = text.substring(0, Character.isHighSurrogate(text.charAt(999)) ? 999 : 1_000);
        }
        return new NoteInput(text, place, tags == null ? null : List.copyOf(normalized));
    }
}
