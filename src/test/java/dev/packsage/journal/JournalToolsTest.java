package dev.packsage.journal;

import dev.packsage.knowledge.AdapterRegistry;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.model.PackSageTools;
import dev.packsage.world.Anchor;
import dev.packsage.world.BlockPlace;
import net.minecraft.SharedConstants;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class JournalToolsTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "resources", "team", "runtime", 1);
    private static final BlockPlace PLACE = new BlockPlace("minecraft:overworld", 10, 64, -3);
    private static IntegratedServer server;

    @TempDir
    Path dir;

    @BeforeAll
    static void bootstrap() throws ReflectiveOperationException {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = field.get(null);
        server = (IntegratedServer) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, IntegratedServer.class);
    }

    private static Toolbox.Context context() {
        return new Toolbox.Context(() -> SCOPE, Map.of(), new AdapterRegistry(), new Toolbox.DocumentBudget(), null,
                new Toolbox.Origin("conversation", "request", "I built my iron farm here"));
    }

    private static CompletionStage<Evidence.Result> call(JournalTools tools, String name, String arguments) {
        return tools.handle(new PackSageTools.ReadRequest(name, Evidence.deserializeObject(arguments), SCOPE), context());
    }

    private static void failure(Toolbox.Code code, CompletionStage<?> result) {
        var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> result.toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertInstanceOf(Toolbox.Failure.class, failure.getCause());
        assertEquals(code, ((Toolbox.Failure) failure.getCause()).code());
    }

    private Journal open() throws Exception {
        var journal = new Journal(Runnable::run);
        journal.open(dir.resolve("journal.db"), () -> 400);
        journal.write(store -> null).toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertTrue(journal.ready());
        return journal;
    }

    private static JournalTools tools(Journal journal) {
        return new JournalTools(journal, () -> SCOPE, Runnable::run, () -> server, anchor -> {
            assertInstanceOf(Anchor.Player.class, anchor);
            return CompletableFuture.completedFuture(PLACE);
        });
    }

    @Test
    void toolsAreUnavailableWithoutAnIntegratedServerOrBeforeTheJournalOpens() {
        var journal = new Journal(Runnable::run);
        var tools = new JournalTools(journal, () -> SCOPE, Runnable::run, () -> null,
                anchor -> CompletableFuture.completedFuture(PLACE));
        assertFalse(tools.enabled());
        assertEquals(KnowledgeProvider.Availability.UNAVAILABLE, tools.status().availability());
        for (var name : List.of("search_journal", "remember", "update_note", "forget_note")) {
            failure(Toolbox.Code.UNAVAILABLE, call(tools, name, "{}"));
            failure(Toolbox.Code.UNAVAILABLE, call(tools(journal), name, "{}"));
        }
        assertEquals(journal.unavailableReason(), tools(journal).status().detail());
    }

    @Test
    void searchJournalReturnsRunSummariesWithPlaceLinks() throws Exception {
        var journal = open();
        try {
            journal.write(store -> {
                store.writeBatch(List.of(new Run(JournalEvents.broken(new JournalEvents.Stamp(400, 1_000, PLACE.dimension()),
                        "player:A", "Alex", "minecraft:sand", 10, 64, -3))), List.of());
                return null;
            }).toCompletableFuture().get();
            var result = call(tools(journal), "search_journal", "{\"filter\":{}}").toCompletableFuture().get();
            var record = result.records().getFirst();
            assertEquals("event:1", record.source().sourceHandle());
            assertEquals(Evidence.Authority.SERVER_READ, record.source().authority());
            assertEquals(Evidence.Activity.INACTIVE, record.source().activity());
            assertTrue(record.title().contains("Alex broke 1 minecraft:sand"));
            assertEquals("journal-place|minecraft:overworld|10,64,-3", record.targets().getFirst().targetId());
            assertTrue(result.detail().startsWith("recording since "));
            journal.stats().unreadExplosionPositions.increment();
            var partial = call(tools(journal), "search_journal", "{\"filter\":{}}").toCompletableFuture().get();
            assertEquals(Evidence.Coverage.PARTIAL, partial.coverage());
            assertTrue(partial.detail().contains("unread explosion positions"));
        } finally {
            journal.close().get();
        }
    }

    @Test
    void rememberSavesThePlayersWordsPlaceAndOriginAndReportsPossibleDuplicates() throws Exception {
        var journal = open();
        try {
            var tools = tools(journal);
            var first = call(tools, "remember", "{\"text\":\"iron farm\",\"at\":{\"type\":\"player\"},\"tags\":[\"build\"]}")
                    .toCompletableFuture().get().records().getFirst();
            var note = journal.notes().all().getFirst();
            assertEquals("I built my iron farm here", note.playerWords());
            assertEquals(400, note.createdGameTime());
            assertEquals(PLACE, note.place());
            assertEquals("conversation", note.originConversation());
            assertEquals("request", note.originRequest());
            assertEquals("journal-note|" + note.id(), first.targets().getFirst().targetId());
            var second = call(tools, "remember", "{\"text\":\"iron farm moved\"}").toCompletableFuture().get().records().getFirst();
            var duplicates = (Evidence.JsonArray) second.payload().values().get("possibleDuplicates");
            assertEquals(1, duplicates.values().size());
            assertEquals(new Evidence.JsonString(note.id()), ((Evidence.JsonObject) duplicates.values().getFirst()).values().get("id"));
            assertEquals(2, journal.read(store -> store.notes().size()).toCompletableFuture().get());
        } finally {
            journal.close().get();
        }
    }

    @Test
    void noteRecordsAreMarkedPlayerSaid() throws Exception {
        var journal = open();
        try {
            var record = call(tools(journal), "remember", "{\"text\":\"iron farm\"}").toCompletableFuture().get().records().getFirst();
            assertEquals(Evidence.Authority.PLAYER_REPORTED, record.source().authority());
            assertEquals(new Evidence.JsonString("player-said"), record.payload().values().get("trust"));
            var entry = new JournalEntry.NoteEntry(journal.notes().all().getFirst());
            assertEquals(Evidence.Authority.PLAYER_REPORTED, entry.authority());
            assertEquals("player-said", entry.trust());
        } finally {
            journal.close().get();
        }
    }

    @Test
    void updateAndForgetChangeTheNoteAndUnknownIdsFail() throws Exception {
        var journal = open();
        try {
            var tools = tools(journal);
            call(tools, "remember", "{\"text\":\"iron farm\",\"at\":{\"type\":\"player\"}}").toCompletableFuture().get();
            var id = journal.notes().all().getFirst().id();
            var updated = call(tools, "update_note", "{\"id\":\"" + id + "\",\"text\":\"new farm\",\"clearPlace\":true,\"tags\":[\"resource\"]}")
                    .toCompletableFuture().get().records().getFirst();
            assertEquals(new Evidence.JsonString("new farm"), updated.payload().values().get("text"));
            assertNull(journal.notes().get(id).place());
            assertEquals(List.of("resource"), journal.notes().get(id).tags());
            failure(Toolbox.Code.INVALID_ARGUMENTS, call(tools, "update_note", "{\"id\":\"" + id + "\",\"text\":\"bad\",\"at\":{\"type\":\"player\"},\"clearPlace\":true}"));
            var forgotten = call(tools, "forget_note", "{\"id\":\"" + id + "\"}").toCompletableFuture().get();
            assertTrue(forgotten.records().isEmpty());
            assertEquals("note " + id + " forgotten", forgotten.detail());
            assertNull(journal.notes().get(id));
            assertTrue(journal.read(store -> store.notes().isEmpty()).toCompletableFuture().get());
            failure(Toolbox.Code.INVALID_ARGUMENTS, call(tools, "update_note", "{\"id\":\"" + id + "\",\"text\":\"missing\"}"));
            failure(Toolbox.Code.INVALID_ARGUMENTS, call(tools, "forget_note", "{\"id\":\"" + id + "\"}"));
        } finally {
            journal.close().get();
        }
    }

    @Test
    void queuedForgetThenUpdateCannotResurrectTheNote() throws Exception {
        var journal = open();
        var release = new CountDownLatch(1);
        try {
            var tools = tools(journal);
            call(tools, "remember", "{\"text\":\"farm\"}").toCompletableFuture().get();
            var id = journal.notes().all().getFirst().id();
            var entered = new CountDownLatch(1);
            journal.write(store -> {
                entered.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                return null;
            });
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var forgotten = call(tools, "forget_note", "{\"id\":\"" + id + "\"}");
            var updated = call(tools, "update_note", "{\"id\":\"" + id + "\",\"text\":\"resurrected\"}");
            release.countDown();
            forgotten.toCompletableFuture().get();
            failure(Toolbox.Code.INVALID_ARGUMENTS, updated);
            assertNull(journal.notes().get(id));
            assertTrue(journal.read(store -> store.notes().isEmpty()).toCompletableFuture().get());
        } finally {
            release.countDown();
            journal.close().get();
        }
    }

    @Test
    void timeArgumentsRejectMalformedInstantsAndConflictingFields() throws Exception {
        var journal = open();
        try {
            for (var time : List.of("{\"after\":\"yesterday\"}", "{\"before\":\"noon\"}", "{\"lastMinutes\":0}",
                    "{\"lastMinutes\":5256001}", "{\"lastMinutes\":1.5}", "{\"after\":\"2026-01-01T00:00:00Z\",\"lastMinutes\":1}")) {
                failure(Toolbox.Code.INVALID_ARGUMENTS, call(tools(journal), "search_journal", "{\"filter\":{\"time\":" + time + "}}"));
            }
        } finally {
            journal.close().get();
        }
    }

    @Test
    void cursorsBindToTheirArguments() throws Exception {
        var journal = open();
        try {
            journal.write(store -> {
                for (int i = 0; i < 65; i++) store.putNote(new Note("n" + String.format("%08x", i), "farm", "words", List.of(), null,
                        1_000 + i, 400, 1_000 + i, null, null));
                return null;
            }).toCompletableFuture().get();
            var tools = tools(journal);
            var first = call(tools, "search_journal", "{\"filter\":{\"notesOnly\":true}}").toCompletableFuture().get();
            assertEquals(64, first.records().size());
            assertNotNull(first.nextCursor());
            assertDoesNotThrow(() -> java.util.UUID.fromString(first.nextCursor()));
            failure(Toolbox.Code.INVALID_CURSOR, call(tools, "search_journal", "{\"filter\":{},\"cursor\":\"" + first.nextCursor() + "\"}"));
            var second = call(tools, "search_journal", "{\"filter\":{\"notesOnly\":true,\"text\":null},\"cursor\":\"" + first.nextCursor() + "\"}")
                    .toCompletableFuture().get();
            assertEquals(1, second.records().size());
            assertNull(second.nextCursor());
            assertFalse(first.records().stream().anyMatch(record -> record.source().sourceHandle().equals(second.records().getFirst().source().sourceHandle())));
        } finally {
            journal.close().get();
        }
    }

    @Test
    void truncatedEventDetailsFallBackToRawStringsAndEvidenceReadsRequireReferences() throws Exception {
        var journal = open();
        try {
            journal.write(store -> {
                store.writeBatch(List.of(new Run(new JournalEvent(EventType.DEATH, 400, 1_000, PLACE.dimension(), "environment",
                        "minecraft:player", null, 1, new long[]{Positions.pack(10, 64, -3)}, "{\"message\":\"cut"))), List.of());
                return null;
            }).toCompletableFuture().get();
            var tools = tools(journal);
            var request = new PackSageTools.ReadRequest("read_evidence", Evidence.deserializeObject("{\"handles\":[\"seen\"]}"), SCOPE);
            failure(Toolbox.Code.INVALID_REFERENCE, tools.readEvidence(request, context()));
            var context = new Toolbox.Context(() -> SCOPE, Map.of("seen", new Toolbox.Reference("journal", "event:1", Evidence.RecordKind.OTHER, SCOPE)),
                    new AdapterRegistry(), new Toolbox.DocumentBudget(), null);
            var record = tools.readEvidence(request, context).toCompletableFuture().get().records().getFirst();
            assertEquals(new Evidence.JsonString("{\"message\":\"cut"), record.payload().values().get("detail"));
        } finally {
            journal.close().get();
        }
    }
}
