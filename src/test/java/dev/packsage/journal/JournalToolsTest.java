package dev.packsage.journal;

import dev.packsage.knowledge.AdapterRegistry;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.knowledge.KnowledgeService;
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
    void maximumLengthNotesKeepContinuationThroughTheToolResponsePipeline() throws Exception {
        var journal = open();
        try {
            journal.write(store -> {
                for (int i = 1; i <= 150; i++) {
                    store.putNote(new Note(String.format(java.util.Locale.ROOT, "n%08x", i), "\u0001".repeat(1_000),
                            "\u0002".repeat(1_000), List.of("build"), PLACE, 1_000, 400, 1_000, "conversation", "request"));
                }
                return null;
            }).toCompletableFuture().get();
            var provider = tools(journal);
            var tools = new PackSageTools(request -> provider.handle(request, context()));
            var arguments = "{\"filter\":{\"notesOnly\":true}}";
            var seen = new java.util.HashSet<String>();
            var pages = 0;
            while (true) {
                assertTrue(++pages <= 150, "continuation did not advance");
                var response = tools.execute(new dev.packsage.model.ModelBackend.ToolCall("notes", "search_journal", arguments), SCOPE)
                        .toCompletableFuture().get();
                assertFalse(response.result().error());
                assertTrue(response.result().serializedResult().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 131_072);
                var json = com.google.gson.JsonParser.parseString(response.result().serializedResult()).getAsJsonObject();
                assertFalse(json.get("detail").getAsString().contains("tool output limit"), json.toString());
                assertFalse(response.records().isEmpty());
                assertTrue(json.getAsJsonArray("records").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 96 * 1024 + 2);
                for (int i = 0; i < response.records().size(); i++) {
                    var record = response.records().get(i);
                    var encoded = PackSageTools.encodeRecord(record).toString();
                    assertEquals(json.getAsJsonArray("records").get(i).toString(), encoded);
                    assertEquals(json.getAsJsonArray("records").get(i).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                            encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                    assertTrue(seen.add(record.source().sourceHandle()), "repeated " + record.source().sourceHandle());
                    assertEquals(new Evidence.JsonString("\u0001".repeat(1_000)), record.payload().values().get("text"));
                    assertEquals(new Evidence.JsonString("\u0002".repeat(1_000)), record.payload().values().get("playerWords"));
                }
                if (!json.has("nextCall")) {
                    assertEquals(150, seen.size(), "the cursor was lost before all notes were delivered");
                    break;
                }
                assertFalse(json.get("nextCursor").isJsonNull());
                arguments = json.getAsJsonObject("nextCall").getAsJsonObject("arguments").toString();
            }
            assertTrue(pages > 1);
        } finally {
            journal.close().get();
        }
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
    void noteEvidenceDistinguishesMatchingOriginalWordsFromAbsentWords() throws Exception {
        var journal = open();
        try {
            journal.write(store -> {
                store.putNote(new Note("n00000001", "farm", "farm", List.of(), null, 1_000, 400, 1_000, null, null));
                store.putNote(new Note("n00000002", "farm", null, List.of(), null, 2_000, 400, 2_000, null, null));
                return null;
            }).toCompletableFuture().get();
            var records = call(tools(journal), "search_journal", "{\"filter\":{\"notesOnly\":true}}")
                    .toCompletableFuture().get().records();
            assertFalse(records.getFirst().payload().values().containsKey("playerWords"));
            assertEquals(new Evidence.JsonString("farm"), records.getLast().payload().values().get("playerWords"));
        } finally {
            journal.close().get();
        }
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
            var scope = new java.util.concurrent.atomic.AtomicReference<>(SCOPE);
            var tools = new JournalTools(journal, scope::get, Runnable::run, () -> server, anchor -> CompletableFuture.completedFuture(PLACE));
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
            failure(Toolbox.Code.NOT_HANDLED, call(tools, "search_recipe", "{\"filter\":{\"notesOnly\":true},\"cursor\":\"" + first.nextCursor() + "\"}"));
            var schema = new PackSageTools(request -> tools.handle(request, context()));
            assertTrue(schema.execute(new dev.packsage.model.ModelBackend.ToolCall("wrong-tool", "remember",
                    "{\"text\":\"farm\",\"cursor\":\"" + first.nextCursor() + "\"}"), SCOPE).toCompletableFuture().get().result().error());
            scope.set(new Evidence.Scope("world", "resources", "team", "runtime", 2));
            failure(Toolbox.Code.STALE_SCOPE, tools.handle(new PackSageTools.ReadRequest("search_journal", Evidence.deserializeObject(
                    "{\"filter\":{\"notesOnly\":true},\"cursor\":\"" + first.nextCursor() + "\"}"), scope.get()), context()));
        } finally {
            journal.close().get();
        }
    }

    @Test
    void timeRangeErrorsNameTheFieldsThatWereSent() throws Exception {
        var journal = open();
        try {
            var failed = call(tools(journal), "search_journal", "{\"filter\":{\"time\":{\"lastMinutes\":1,\"before\":\"1970-01-01T00:00:01Z\"}}}");
            var error = assertThrows(java.util.concurrent.ExecutionException.class, () -> failed.toCompletableFuture().get());
            assertInstanceOf(Toolbox.Failure.class, error.getCause());
            assertTrue(error.getCause().getMessage().contains("lastMinutes"));
            assertTrue(error.getCause().getMessage().contains("before"));
            assertFalse(error.getCause().getMessage().contains("after"));
        } finally {
            journal.close().get();
        }
    }

    @Test
    void areaSearchReportsExactPositionsCountsAndEventPayload() throws Exception {
        var journal = open();
        try {
            journal.write(store -> {
                var run = new Run(JournalEvents.broken(new JournalEvents.Stamp(400, 1_000, PLACE.dimension()),
                        "player:A", "Alex", "minecraft:sand", 10, 64, -3));
                run.add(JournalEvents.broken(new JournalEvents.Stamp(420, 2_000, PLACE.dimension()),
                        "player:A", "Alex", "minecraft:sand", 12, 64, -3));
                store.writeBatch(List.of(run), List.of());
                return null;
            }).toCompletableFuture().get();
            var record = call(tools(journal), "search_journal", "{\"filter\":{\"area\":{\"center\":{\"type\":\"player\"},\"radius\":0}}}")
                    .toCompletableFuture().get().records().getFirst();
            var payload = com.google.gson.JsonParser.parseString(Evidence.serialize(record.payload())).getAsJsonObject();
            assertEquals("break", payload.get("type").getAsString());
            assertEquals("player:A", payload.get("actor").getAsString());
            assertEquals("Alex", payload.get("actorName").getAsString());
            assertEquals("minecraft:sand", payload.get("subject").getAsString());
            assertEquals(PLACE.dimension(), payload.get("dimension").getAsString());
            assertEquals(2, payload.get("count").getAsInt());
            assertEquals(2, payload.get("amount").getAsInt());
            assertEquals(400, payload.get("fromGameTime").getAsLong());
            assertEquals(420, payload.get("toGameTime").getAsLong());
            assertEquals("1970-01-01T00:00:01Z", payload.get("from").getAsString());
            assertEquals("1970-01-01T00:00:02Z", payload.get("to").getAsString());
            assertEquals(PLACE.text(), payload.get("at").getAsString());
            assertEquals("[10,64,-3]", payload.getAsJsonObject("box").get("min").toString());
            assertEquals("[12,64,-3]", payload.getAsJsonObject("box").get("max").toString());
            assertFalse(payload.get("countOnly").getAsBoolean());
            assertTrue(payload.get("minutesAgo").getAsDouble() > 0);
            assertEquals(record.title(), payload.get("summary").getAsString());
            assertEquals(1, payload.get("inAreaCount").getAsInt());
            assertEquals(List.of(PLACE.text()), payload.getAsJsonArray("inArea").asList().stream().map(value -> value.getAsString()).toList());
            assertEquals("Alex", payload.getAsJsonObject("detail").get("actorName").getAsString());
        } finally {
            journal.close().get();
        }
    }

    @Test
    void observedNoteEvidenceRereadsTheCurrentNoteAndReportsDeletion() throws Exception {
        var journal = open();
        try {
            var knowledge = new KnowledgeService(List.of(tools(journal)), () -> SCOPE);
            var remembered = knowledge.execute(new PackSageTools.ReadRequest("remember", Evidence.deserializeObject("{\"text\":\"farm\"}"), SCOPE))
                    .toCompletableFuture().get().records().getFirst();
            var handle = remembered.source().sourceHandle();
            var read = new PackSageTools.ReadRequest("read_evidence", Evidence.deserializeObject("{\"handles\":[\"journal:" + handle + "\"]}"), SCOPE);
            var result = knowledge.execute(read).toCompletableFuture().get().records().getFirst();
            assertEquals(handle, result.source().sourceHandle());
            assertEquals(Evidence.Authority.PLAYER_REPORTED, result.source().authority());
            var id = handle.substring(5);
            call(tools(journal), "update_note", "{\"id\":\"" + id + "\",\"text\":\"moved farm\"}").toCompletableFuture().get();
            assertEquals("moved farm", knowledge.execute(read).toCompletableFuture().get().records().getFirst().title());
            call(tools(journal), "forget_note", "{\"id\":\"" + id + "\"}").toCompletableFuture().get();
            var deleted = knowledge.execute(read).toCompletableFuture().get();
            assertTrue(deleted.records().isEmpty());
            assertEquals("journal entry no longer exists", deleted.detail());
        } finally {
            journal.close().get();
        }
    }

    @Test
    void noteWriteCompletionsRunOnTheWorker() throws Exception {
        try (var worker = Executors.newSingleThreadExecutor(action -> new Thread(action, "test-journal-worker"))) {
            var journal = new Journal(worker);
            journal.open(dir.resolve("journal.db"), () -> 400);
            journal.write(store -> null).toCompletableFuture().get();
            try {
                var tools = new JournalTools(journal, () -> SCOPE, worker, () -> server, anchor -> CompletableFuture.completedFuture(PLACE));
                for (var name : List.of("remember", "update_note", "forget_note")) {
                    var release = new CountDownLatch(1);
                    var entered = new CountDownLatch(1);
                    journal.write(store -> {
                        entered.countDown();
                        try {
                            assertTrue(release.await(10, TimeUnit.SECONDS));
                        } catch (InterruptedException failure) {
                            throw new AssertionError(failure);
                        }
                        return null;
                    });
                    assertTrue(entered.await(10, TimeUnit.SECONDS));
                    try {
                        var arguments = name.equals("remember") ? "{\"text\":\"farm\"}" : "{\"id\":\"" + journal.notes().all().getFirst().id()
                                + "\"" + (name.equals("update_note") ? ",\"text\":\"moved farm\"" : "") + "}";
                        var result = call(tools, name, arguments).thenApply(value -> Thread.currentThread().getName());
                        worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
                        release.countDown();
                        assertEquals("test-journal-worker", result.toCompletableFuture().get(10, TimeUnit.SECONDS));
                    } finally {
                        release.countDown();
                    }
                }
            } finally {
                journal.close().get();
            }
        }
    }

    @Test
    void failedOpenMakesTheJournalAdapterUnavailableWithItsReason() throws Exception {
        var journal = new Journal(Runnable::run, file -> { throw new java.sql.SQLException("database refused"); });
        journal.open(dir.resolve("refused.db"), () -> 400);
        try {
            assertThrows(java.util.concurrent.ExecutionException.class, () -> journal.write(store -> null).toCompletableFuture().get());
            var tools = tools(journal);
            var knowledge = new KnowledgeService(List.of(tools), () -> SCOPE);
            assertFalse(knowledge.adapterAvailability().get("journal"));
            assertTrue(knowledge.adapterDetails().get("journal").contains("database refused"));
            var result = tools.open(new Evidence.NavigationTarget(SCOPE, "journal", "saved-note", "journal-note|n0000abcd"), SCOPE);
            assertEquals(Evidence.NavigationStatus.UNAVAILABLE, result.status());
            assertTrue(result.detail().contains("database refused"), result.detail());
        } finally {
            journal.close().handle((value, failure) -> null).get();
        }
    }

    @Test
    void failedWritesLeaveTheNoteMirrorUnchanged() throws Exception {
        var journal = open();
        try {
            var tools = tools(journal);
            call(tools, "remember", "{\"text\":\"farm\"}").toCompletableFuture().get();
            var original = journal.notes().all().getFirst();
            journal.write(store -> {
                try (var statement = store.writer().createStatement()) {
                    for (var operation : List.of("INSERT", "UPDATE", "DELETE")) {
                        statement.execute("CREATE TRIGGER fail_" + operation + " BEFORE " + operation + " ON notes BEGIN SELECT RAISE(ABORT, 'refused'); END");
                    }
                }
                return null;
            }).toCompletableFuture().get();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> call(tools, "remember", "{\"text\":\"other\"}").toCompletableFuture().get());
            assertThrows(java.util.concurrent.ExecutionException.class, () -> call(tools, "update_note", "{\"id\":\"" + original.id() + "\",\"text\":\"changed\"}").toCompletableFuture().get());
            assertThrows(java.util.concurrent.ExecutionException.class, () -> call(tools, "forget_note", "{\"id\":\"" + original.id() + "\"}").toCompletableFuture().get());
            assertEquals(List.of(original), journal.notes().all());
            assertEquals(List.of(original), journal.read(store -> store.notes()).toCompletableFuture().get());
        } finally {
            journal.close().get();
        }
    }

    @Test
    void searchAndRecordBuildingRunOnTheWorker() throws Exception {
        try (var worker = Executors.newSingleThreadExecutor(action -> new Thread(action, "test-journal-worker"))) {
            var journal = new Journal(worker);
            journal.open(dir.resolve("journal.db"), () -> 400);
            journal.write(store -> null).toCompletableFuture().get();
            try {
                var tools = new JournalTools(journal, () -> SCOPE, worker, () -> server, anchor -> CompletableFuture.completedFuture(PLACE));
                var completedOn = new CompletableFuture<String>();
                var start = new CountDownLatch(1);
                worker.submit(() -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return null;
                });
                call(tools, "search_journal", "{\"filter\":{}}").thenAccept(result -> completedOn.complete(Thread.currentThread().getName()));
                start.countDown();
                assertEquals("test-journal-worker", completedOn.get(10, TimeUnit.SECONDS));
            } finally {
                journal.close().get();
            }
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
