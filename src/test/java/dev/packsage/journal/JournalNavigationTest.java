package dev.packsage.journal;

import dev.packsage.chat.ChatContracts;
import dev.packsage.chat.ConversationStore;
import dev.packsage.knowledge.Evidence;
import dev.packsage.world.BlockPlace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JournalNavigationTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "resources", "team", "runtime", 1);
    private static final String ID = "n0000abcd";
    private static final BlockPlace PLACE = new BlockPlace("minecraft:overworld", 1, 64, 1);

    private static final class FakeClient implements JournalNavigation.Client {
        String dimension = "minecraft:overworld";
        final List<BlockPlace> drawn = new ArrayList<>();
        long until;

        @Override
        public String dimension() {
            return dimension;
        }

        @Override
        public void highlightBlock(BlockPlace place, long untilMillis) {
            drawn.add(place);
            until = untilMillis;
        }
    }

    @Test
    void navigationIdsRoundTrip() {
        assertEquals("journal-place|minecraft:overworld|1,64,1", JournalNavigation.placeId(PLACE));
        assertEquals("journal-note|n0000abcd", JournalNavigation.noteId(ID));
        assertEquals(new JournalNavigation.Destination.Place(PLACE), JournalNavigation.parse(JournalNavigation.placeId(PLACE)));
        assertEquals(new JournalNavigation.Destination.Note(ID), JournalNavigation.parse(JournalNavigation.noteId(ID)));
        for (var id : List.of("", "journal-place|", "journal-place|minecraft:overworld|1,2", "journal-note|nope",
                "journal-note|n0000abcd|other", "world-place|minecraft:overworld|1,64,1")) {
            assertThrows(IllegalArgumentException.class, () -> JournalNavigation.parse(id), id);
        }
    }

    @Test
    void updatedNoteLinkHighlightsTheNewPlace() {
        var notes = new NoteBook();
        var note = new Note(ID, "iron farm", "my farm", List.of(), PLACE, 1, 1, 1, null, null);
        notes.load(List.of(note));
        var client = new FakeClient();
        var navigation = new JournalNavigation(notes, client);
        var target = new Evidence.NavigationTarget(SCOPE, JournalTools.ID, "note:" + ID, JournalNavigation.noteId(ID));
        var before = System.currentTimeMillis();
        assertEquals(Evidence.NavigationStatus.OPENED, navigation.open(target).status());
        assertTrue(client.until >= before + 30_000 && client.until <= System.currentTimeMillis() + 30_000);
        var moved = new BlockPlace("minecraft:overworld", 40, 70, -8);
        notes.put(NoteBook.apply(note, new NoteInput("moved farm", new NoteInput.Set(moved), null), 2, null, null));
        assertEquals(Evidence.NavigationStatus.OPENED, navigation.open(target).status());
        assertEquals(List.of(PLACE, moved), client.drawn);
    }

    @Test
    void forgottenNoteIsUnavailableAndDrawsNothing() throws Exception {
        var notes = new NoteBook();
        notes.load(List.of(new Note(ID, "farm", null, List.of(), PLACE, 1, 1, 1, null, null)));
        var client = new FakeClient();
        var navigation = new JournalNavigation(notes, client);
        var target = new Evidence.NavigationTarget(SCOPE, JournalTools.ID, "note:" + ID, JournalNavigation.noteId(ID));
        notes.remove(ID);
        assertEquals(new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "this note was forgotten"), navigation.open(target));
        assertNull(navigation.refresh(target, SCOPE).toCompletableFuture().get());
        assertTrue(client.drawn.isEmpty());
    }

    @Test
    void loadingAndUnplacedNotesAreUnavailable() throws Exception {
        var notes = new NoteBook();
        var client = new FakeClient();
        var navigation = new JournalNavigation(notes, client);
        var target = new Evidence.NavigationTarget(SCOPE, JournalTools.ID, "note:" + ID, JournalNavigation.noteId(ID));
        assertEquals(new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "the journal is still opening"), navigation.open(target));
        assertNotNull(navigation.refresh(target, SCOPE).toCompletableFuture().get());
        notes.load(List.of(new Note(ID, "farm", null, List.of(), null, 1, 1, 1, null, null)));
        assertEquals(new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "this note has no saved place"), navigation.open(target));
        assertTrue(client.drawn.isEmpty());
    }

    @Test
    void otherDimensionOpensWithCoordinatesOnly() {
        var client = new FakeClient();
        client.dimension = "minecraft:the_nether";
        var navigation = new JournalNavigation(new NoteBook(), client);
        assertEquals(new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED, "1, 64, 1 in minecraft:overworld"),
                navigation.open(new Evidence.NavigationTarget(SCOPE, JournalTools.ID, "event:1", JournalNavigation.placeId(PLACE))));
        assertTrue(client.drawn.isEmpty());
    }

    @Test
    void savedTargetsResolveInANewScopeAfterReload(@TempDir Path directory) throws Exception {
        var world = new ChatContracts.WorldIdentity("world", "World");
        var store = new ConversationStore(directory.resolve("chat"), Runnable::run);
        var conversation = store.newConversation(world).toCompletableFuture().join();
        var targets = List.of(new Evidence.NavigationTarget(SCOPE, JournalTools.ID, "note:" + ID, JournalNavigation.noteId(ID)),
                new Evidence.NavigationTarget(SCOPE, JournalTools.ID, "note:" + ID, JournalNavigation.placeId(PLACE)));
        var source = new Evidence.Source(SCOPE, JournalTools.ID, JournalTools.VERSION, "note:" + ID, 1, Instant.EPOCH,
                Evidence.Activity.INACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.PLAYER_REPORTED, List.of());
        store.appendTurn(world, SCOPE, conversation.id(), new ChatContracts.Turn(new ChatContracts.RequestId("saved"), "Where is my farm?",
                "Here.", List.of(), new ChatContracts.Context("context", null, SCOPE), List.of(source), targets,
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1)).toCompletableFuture().join();
        var file = directory.resolve("journal.db");
        try (var database = JournalStore.open(file)) {
            database.putNote(new Note(ID, "farm", "my farm", List.of(), PLACE, 1, 1, 1, null, null));
        }
        var notes = new NoteBook();
        try (var database = JournalStore.open(file)) {
            notes.load(database.notes());
        }
        var saved = new ConversationStore(directory.resolve("chat"), Runnable::run).openLatest(world).toCompletableFuture().join()
                .turns().getFirst().navigationTargets();
        assertEquals(targets, saved);
        var client = new FakeClient();
        var navigation = new JournalNavigation(notes, client);
        var reloaded = new Evidence.Scope("world", "resources-2", "team", "runtime-2", 7);
        for (var target : saved) {
            var refreshed = navigation.refresh(target, reloaded).toCompletableFuture().get();
            assertEquals(new Evidence.NavigationTarget(reloaded, JournalTools.ID, target.sourceHandle(), target.targetId()), refreshed);
            assertEquals(Evidence.NavigationStatus.OPENED, navigation.open(refreshed).status());
        }
        assertEquals(List.of(PLACE, PLACE), client.drawn);
        client.dimension = null;
        assertEquals(Evidence.NavigationStatus.UNAVAILABLE, navigation.open(saved.getFirst()).status());
        assertNull(navigation.refresh(new Evidence.NavigationTarget(SCOPE, JournalTools.ID, "note:" + ID, "journal-note|bad"), reloaded).toCompletableFuture().get());
        assertNull(navigation.refresh(new Evidence.NavigationTarget(SCOPE, "world", "note:" + ID, JournalNavigation.noteId(ID)), reloaded)
                .toCompletableFuture().get());
    }
}
