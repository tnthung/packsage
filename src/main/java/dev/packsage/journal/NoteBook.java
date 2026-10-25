package dev.packsage.journal;

import dev.packsage.world.BlockPlace;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Thread-safe in-memory mirror of player reports. Navigation reads only this mirror, never the database. */
public final class NoteBook {
    private final Map<String, Note> notes = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private boolean loaded;

    public synchronized void load(List<Note> saved) {
        notes.clear();
        for (var note : saved) {
            put(note);
        }
        loaded = true;
    }

    public synchronized boolean loaded() {
        return loaded;
    }

    public synchronized Note get(String id) {
        return notes.get(id);
    }

    public synchronized BlockPlace place(String id) {
        var note = notes.get(id);
        return note == null ? null : note.place();
    }

    public synchronized void put(Note note) {
        notes.put(note.id(), new Note(note.id(), note.text(), note.playerWords(), List.copyOf(note.tags()), note.place(),
                note.createdWall(), note.createdGameTime(), note.updatedWall(), note.originConversation(), note.originRequest()));
    }

    public synchronized void remove(String id) {
        notes.remove(id);
    }

    /** Newest creation first, matching journal note searches. */
    public synchronized List<Note> all() {
        return notes.values().stream().sorted(Comparator.comparingLong(Note::createdWall).thenComparing(Note::id).reversed()).toList();
    }

    public synchronized List<Note> similar(String text, int limit) {
        var words = Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(word -> word.length() >= 4).collect(Collectors.toSet());
        return all().stream().filter(note -> Arrays.stream(note.text().toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .anyMatch(words::contains)).limit(Math.max(0, limit)).toList();
    }

    /** Generate and put a new note on the writer thread, so another creation cannot overtake it. */
    public synchronized String newId() {
        String id;
        do {
            id = "n" + HexFormat.of().toHexDigits(random.nextInt());
        } while (notes.containsKey(id));
        return id;
    }

    public static boolean isId(String id) {
        return id != null && id.matches("n[0-9a-f]{8}");
    }

    /** Edits remain player reports: keep the original player words and creation identity. */
    public static Note apply(Note existing, NoteInput input, long nowWall, String originConversation, String originRequest) {
        return new Note(existing.id(), input.text(), existing.playerWords(),
                input.tags() == null ? List.copyOf(existing.tags()) : List.copyOf(input.tags()),
                switch (input.place()) {
                    case NoteInput.Keep ignored -> existing.place();
                    case NoteInput.Clear ignored -> null;
                    case NoteInput.Set set -> set.place();
                }, existing.createdWall(), existing.createdGameTime(), nowWall, originConversation, originRequest);
    }
}
