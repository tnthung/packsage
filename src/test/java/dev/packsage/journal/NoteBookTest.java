package dev.packsage.journal;

import dev.packsage.world.BlockPlace;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoteBookTest {
    @Test
    void similarFindsNotesSharingALongWord() {
        var book = new NoteBook();
        var older = new Note("n00000001", "Built an iron farm by the river", "my farm", List.of(), null, 10, 20, 50, null, null);
        var newer = new Note("n00000002", "IRON! warehouse 1234", "warehouse", List.of(), null, 20, 30, 20, null, null);
        book.load(List.of(older, newer));
        assertEquals(List.of(newer, older), book.similar("my iron farm moved", 3));
        assertEquals(List.of(newer), book.similar("IRON", 1));
        assertEquals(List.of(newer), book.similar("1234", 3));
        assertTrue(book.similar("a cat", 3).isEmpty());
        assertTrue(book.similar("iron", 0).isEmpty());
    }

    @Test
    void updateKeepsCreationAndReplacesTextPlaceAndTags() {
        var place = new BlockPlace("minecraft:overworld", 1, 64, 1);
        var existing = new Note("n00000001", "Built an iron farm", "I built an iron farm here", List.of("build"),
                place, 10, 200, 10, "chat1", "request1");
        var kept = NoteBook.apply(existing, NoteInput.validated("my iron farm moved", new NoteInput.Keep(), null),
                30, "chat2", "request2");
        assertEquals(new Note("n00000001", "my iron farm moved", "I built an iron farm here", List.of("build"),
                place, 10, 200, 30, "chat2", "request2"), kept);
        var moved = new BlockPlace("minecraft:the_nether", 40, 70, -8);
        var updated = NoteBook.apply(kept, NoteInput.validated("moved farm", new NoteInput.Set(moved), List.of("farm")),
                40, "chat3", "request3");
        assertEquals(moved, updated.place());
        assertEquals(List.of("farm"), updated.tags());
        assertEquals("n00000001", updated.id());
        assertEquals(10, updated.createdWall());
        assertEquals(200, updated.createdGameTime());
        var cleared = NoteBook.apply(updated, NoteInput.validated("old farm", new NoteInput.Clear(), List.of()), 50, null, null);
        assertNull(cleared.place());
        assertTrue(cleared.tags().isEmpty());
    }

    @Test
    void inputValidationRejectsBlankTextAndBadTags() {
        assertEquals(List.of("build", "iron_farm", "base-1"), NoteInput.validated("farm", new NoteInput.Keep(),
                List.of("Build", " build ", "IRON_FARM", "base-1")).tags());
        for (var text : new String[]{null, "", " \t\n"}) {
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> NoteInput.validated(text, new NoteInput.Keep(), null)).getMessage().contains(String.valueOf(text)));
        }
        for (var tag : new String[]{null, "", "has space", "bad!", "a".repeat(33)}) {
            var tags = new ArrayList<String>();
            tags.add(tag);
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> NoteInput.validated("farm", new NoteInput.Keep(), tags)).getMessage().contains(String.valueOf(tag)));
        }
        assertThrows(IllegalArgumentException.class, () -> NoteInput.validated("farm", new NoteInput.Keep(),
                IntStream.range(0, 9).mapToObj(i -> "tag" + i).toList()));
        assertThrows(IllegalArgumentException.class, () -> NoteInput.validated("farm", null, null));
        assertThrows(IllegalArgumentException.class, () -> new NoteInput.Set(null));
        assertEquals("x".repeat(1_000), NoteInput.validated("x".repeat(1_001), new NoteInput.Keep(), null).text());
        assertNull(NoteInput.validated("farm", new NoteInput.Keep(), null).tags());
    }

    @Test
    void mirrorLoadsReplacesAndForgetsWithoutKeepingMutableTags() {
        var book = new NoteBook();
        assertFalse(book.loaded());
        assertNull(book.place("n00000001"));
        var tags = new ArrayList<>(List.of("build"));
        var place = new BlockPlace("minecraft:overworld", 1, 64, 1);
        book.load(List.of(new Note("n00000001", "old base", "old base", tags, place, 10, 20, 10, null, null)));
        tags.clear();
        assertTrue(book.loaded());
        assertEquals(List.of("build"), book.get("n00000001").tags());
        assertEquals(place, book.place("n00000001"));
        var snapshot = book.all();
        book.put(NoteBook.apply(book.get("n00000001"), NoteInput.validated("new base", new NoteInput.Clear(), null), 20, null, null));
        assertEquals(1, book.all().size());
        assertEquals("new base", book.get("n00000001").text());
        assertNull(book.place("n00000001"));
        assertEquals("old base", snapshot.getFirst().text());
        book.remove("n00000001");
        assertNull(book.get("n00000001"));
        assertTrue(book.all().isEmpty());
        book.load(snapshot);
        book.load(List.of());
        assertTrue(book.all().isEmpty());
    }

    @Test
    void generatedIdsAreValidAndDoNotReuseExistingNotes() {
        var book = new NoteBook();
        for (int i = 0; i < 100; i++) {
            var id = book.newId();
            assertTrue(NoteBook.isId(id));
            assertNull(book.get(id));
            book.put(new Note(id, "farm", "farm", List.of(), null, i, i, i, null, null));
        }
        assertEquals(100, book.all().size());
        for (var id : new String[]{null, "n1234567", "n123456789", "nABCDEF12", "x12345678", "n1234567g", "n12345678\n"}) {
            assertFalse(NoteBook.isId(id));
        }
    }
}
