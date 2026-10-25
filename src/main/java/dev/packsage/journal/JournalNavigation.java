package dev.packsage.journal;

import dev.packsage.knowledge.Evidence;
import dev.packsage.world.BlockPlace;
import dev.packsage.world.WorldNavigation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Persistent places and note identities; opening reads the current in-memory note, never historical evidence or disk. */
final class JournalNavigation {
    private static final String PLACE = "journal-place|";
    private static final String NOTE = "journal-note|";

    sealed interface Destination {
        record Place(BlockPlace place) implements Destination {
        }

        record Note(String id) implements Destination {
        }
    }

    interface Client {
        String dimension();

        void highlightBlock(BlockPlace place, long untilMillis);
    }

    private final NoteBook notes;
    private final Client client;

    JournalNavigation(NoteBook notes, Client client) {
        this.notes = notes;
        this.client = client;
    }

    static String placeId(BlockPlace place) {
        return PLACE + place.text();
    }

    static String noteId(String id) {
        return NOTE + id;
    }

    static Destination parse(String targetId) {
        if (targetId != null && targetId.startsWith(PLACE)) return new Destination.Place(BlockPlace.parse(targetId.substring(PLACE.length())));
        if (targetId != null && targetId.startsWith(NOTE) && NoteBook.isId(targetId.substring(NOTE.length()))) {
            return new Destination.Note(targetId.substring(NOTE.length()));
        }
        throw new IllegalArgumentException("Invalid journal target: " + targetId);
    }

    CompletionStage<Evidence.NavigationTarget> refresh(Evidence.NavigationTarget target, Evidence.Scope scope) {
        if (!JournalTools.ID.equals(target.providerId())) return CompletableFuture.completedFuture(null);
        try {
            if (parse(target.targetId()) instanceof Destination.Note note && notes.loaded() && notes.get(note.id()) == null) {
                return CompletableFuture.completedFuture(null);
            }
        } catch (IllegalArgumentException failure) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.completedFuture(new Evidence.NavigationTarget(scope, JournalTools.ID, target.sourceHandle(), target.targetId()));
    }

    Evidence.NavigationResult open(Evidence.NavigationTarget target) {
        Destination destination;
        try {
            destination = parse(target.targetId());
        } catch (IllegalArgumentException failure) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.ERROR, failure.getMessage());
        }
        BlockPlace place;
        if (destination instanceof Destination.Note note) {
            if (!notes.loaded()) return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "the journal is still opening");
            var current = notes.get(note.id());
            if (current == null) return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "this note was forgotten");
            place = current.place();
            if (place == null) return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "this note has no saved place");
        } else {
            place = ((Destination.Place) destination).place();
        }
        var here = client.dimension();
        if (here == null) return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "the player is not in a world");
        if (!here.equals(place.dimension())) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED, place.x() + ", " + place.y() + ", " + place.z() + " in " + place.dimension());
        }
        client.highlightBlock(place, System.currentTimeMillis() + WorldNavigation.HIGHLIGHT_MILLIS);
        return new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED, null);
    }
}
