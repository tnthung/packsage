package dev.packsage.chat;

import dev.packsage.knowledge.Evidence;
import dev.packsage.model.ModelBackend;
import dev.packsage.model.PackSageTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HistoryAdapterTest {
    private static final ChatContracts.WorldIdentity WORLD = new ChatContracts.WorldIdentity("world", "World");
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "resources", "team", "runtime", 1);

    @TempDir
    Path directory;

    @Test
    void searchAndReadReturnHistoricalTextWithoutReplayOrCurrentAuthority() {
        var store = new ConversationStore(directory, Runnable::run);
        var session = store.newConversation(WORLD).toCompletableFuture().join();
        store.appendTurn(WORLD, SCOPE, session.id(), new ChatContracts.Turn(new ChatContracts.RequestId("old"),
                "Earlier lantern discussion", "Remember the lantern. " + "details ".repeat(100),
                List.of(new ModelBackend.ToolResult("secret", "private-tool-replay", false)),
                new ChatContracts.Context("private-gameplay-snapshot", null, SCOPE), List.of(), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 30, Instant.EPOCH))
                .toCompletableFuture().join();
        var adapter = new HistoryAdapter(store, WORLD, () -> SCOPE);
        var search = adapter.execute(new PackSageTools.ReadRequest("search_history",
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("LANTERN"))), SCOPE), null)
                .toCompletableFuture().join();

        assertEquals(1, search.records().size(), search.detail());
        var record = search.records().getFirst();
        assertEquals(Evidence.Authority.UNKNOWN, record.source().authority());
        assertEquals(Evidence.Activity.INACTIVE, record.source().activity());
        assertTrue(record.targets().isEmpty());
        assertEquals(new Evidence.JsonString(Instant.EPOCH.toString()), record.payload().values().get("sentAt"));
        assertFalse(Evidence.serialize(record.payload()).contains("private-"));
        var read = adapter.execute(new PackSageTools.ReadRequest("read_history", new Evidence.JsonObject(Map.of(
                "handles", new Evidence.JsonArray(List.of(new Evidence.JsonString("history:" + record.source().sourceHandle()))),
                "maxChars", new Evidence.JsonNumber(java.math.BigDecimal.valueOf(256)))), SCOPE), null)
                .toCompletableFuture().join();
        assertEquals(1, read.records().size(), read.detail());
        var payload = read.records().getFirst().payload().values();
        assertEquals(new Evidence.JsonBoolean(true), payload.get("truncated"));
        assertTrue(((Evidence.JsonString) payload.get("question")).value().length()
                + ((Evidence.JsonString) payload.get("answer")).value().length() <= 256);
        assertEquals(Evidence.Coverage.PARTIAL, read.coverage());
        assertFalse(Evidence.serialize(read.records().getFirst().payload()).contains("private-"));
    }

    @Test
    void searchAndReadIncludeOrderedSteeringEvenWhenItIsAbsentFromAnswer() {
        var store = new ConversationStore(directory, Runnable::run);
        var session = store.newConversation(WORLD).toCompletableFuture().join();
        store.appendTurn(WORLD, SCOPE, session.id(), new ChatContracts.Turn(new ChatContracts.RequestId("steered"),
                "help me plan", "", List.of(), new ChatContracts.Context("private-context", null, SCOPE), List.of(),
                List.of(), new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1, Instant.EPOCH, List.of(),
                List.of(new ChatContracts.SteeringMessage("avoid uranium", 0),
                        new ChatContracts.SteeringMessage("prefer renewable power", 0)))).toCompletableFuture().join();
        var adapter = new HistoryAdapter(store, WORLD, () -> SCOPE);
        var search = adapter.execute(new PackSageTools.ReadRequest("search_history", new Evidence.JsonObject(Map.of(
                "text", new Evidence.JsonString("avoid uranium"))), SCOPE), null).toCompletableFuture().join();

        assertEquals(1, search.records().size(), search.detail());
        var handle = search.records().getFirst().source().sourceHandle();
        var read = adapter.execute(new PackSageTools.ReadRequest("read_history", new Evidence.JsonObject(Map.of(
                "handles", new Evidence.JsonArray(List.of(new Evidence.JsonString("history:" + handle))),
                "maxChars", new Evidence.JsonNumber(java.math.BigDecimal.valueOf(256)))), SCOPE), null)
                .toCompletableFuture().join();

        assertEquals(1, read.records().size(), read.detail());
        assertEquals(new Evidence.JsonArray(List.of(
                        new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("avoid uranium"),
                                "answerOffset", new Evidence.JsonNumber(java.math.BigDecimal.ZERO))),
                        new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("prefer renewable power"),
                                "answerOffset", new Evidence.JsonNumber(java.math.BigDecimal.ZERO))))),
                read.records().getFirst().payload().values().get("steeringMessages"));
    }

    @Test
    void clippedWhitespaceSteeringReturnsPartialHistoryInsteadOfFailing() {
        var store = new ConversationStore(directory, Runnable::run);
        var session = store.newConversation(WORLD).toCompletableFuture().join();
        store.appendTurn(WORLD, SCOPE, session.id(), new ChatContracts.Turn(new ChatContracts.RequestId("clipped-steering"),
                "q".repeat(250), "", List.of(), new ChatContracts.Context("{}", null, SCOPE), List.of(), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1, Instant.EPOCH, List.of(),
                List.of(new ChatContracts.SteeringMessage("          x", 0)))).toCompletableFuture().join();
        var adapter = new HistoryAdapter(store, WORLD, () -> SCOPE);
        var search = adapter.execute(new PackSageTools.ReadRequest("search_history", new Evidence.JsonObject(Map.of(
                "text", new Evidence.JsonString("q"))), SCOPE), null).toCompletableFuture().join();
        String handle = search.records().getFirst().source().sourceHandle();

        var read = adapter.execute(new PackSageTools.ReadRequest("read_history", new Evidence.JsonObject(Map.of(
                "handles", new Evidence.JsonArray(List.of(new Evidence.JsonString("history:" + handle))),
                "maxChars", new Evidence.JsonNumber(java.math.BigDecimal.valueOf(256)))), SCOPE), null)
                .toCompletableFuture().join();

        assertEquals(Evidence.Outcome.PARTIAL, read.outcome());
        assertEquals(new Evidence.JsonBoolean(true), read.records().getFirst().payload().values().get("truncated"));
        assertEquals(new Evidence.JsonArray(List.of()), read.records().getFirst().payload().values().get("steeringMessages"));
    }

    @Test
    void cursorsBindQueryAndReadsRejectUndiscoveredAndPreviousScopeHandles() {
        var store = new ConversationStore(directory, Runnable::run);
        var session = store.newConversation(WORLD).toCompletableFuture().join();
        for (int index = 0; index < 2; index++) {
            store.appendTurn(WORLD, SCOPE, session.id(), new ChatContracts.Turn(new ChatContracts.RequestId("old-" + index),
                    "lantern " + index, "answer", List.of(), new ChatContracts.Context("{}", null, SCOPE),
                    List.of(), List.of(), new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null),
                    5, Instant.EPOCH)).toCompletableFuture().join();
        }
        var scope = new AtomicReference<>(SCOPE);
        var adapter = new HistoryAdapter(store, WORLD, scope::get);
        var search = adapter.execute(new PackSageTools.ReadRequest("search_history", new Evidence.JsonObject(Map.of(
                "text", new Evidence.JsonString("lantern"), "limit", new Evidence.JsonNumber(java.math.BigDecimal.ONE))),
                SCOPE), null).toCompletableFuture().join();
        assertNotNull(search.nextCursor(), search.detail());
        var changed = adapter.execute(new PackSageTools.ReadRequest("search_history", new Evidence.JsonObject(Map.of(
                "text", new Evidence.JsonString("answer"), "cursor", new Evidence.JsonString(search.nextCursor()))),
                SCOPE), null).toCompletableFuture().join();
        assertEquals(Evidence.Outcome.ERROR, changed.outcome());
        var next = adapter.execute(new PackSageTools.ReadRequest("search_history", new Evidence.JsonObject(Map.of(
                "text", new Evidence.JsonString("lantern"), "cursor", new Evidence.JsonString(search.nextCursor()))),
                SCOPE), null).toCompletableFuture().join();
        assertEquals(1, next.records().size(), next.detail());
        assertNotEquals(search.records().getFirst().payload().values().get("requestId"),
                next.records().getFirst().payload().values().get("requestId"));
        var handles = new Evidence.JsonArray(List.of(new Evidence.JsonString("history:"
                + search.records().getFirst().source().sourceHandle())));
        var unknown = adapter.execute(new PackSageTools.ReadRequest("read_history", new Evidence.JsonObject(Map.of(
                "handles", new Evidence.JsonArray(List.of(new Evidence.JsonString("history:unknown"))))), SCOPE), null)
                .toCompletableFuture().join();
        assertEquals(Evidence.Outcome.ERROR, unknown.outcome());
        scope.set(new Evidence.Scope("world", "resources", "team", "runtime", 2));
        assertEquals(Evidence.Outcome.ERROR, adapter.execute(new PackSageTools.ReadRequest("read_history",
                new Evidence.JsonObject(Map.of("handles", handles)), scope.get()), null)
                .toCompletableFuture().join().outcome());
        assertEquals(Evidence.Outcome.ERROR, adapter.execute(new PackSageTools.ReadRequest("search_history",
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("lantern"))), SCOPE), null)
                .toCompletableFuture().join().outcome());
    }

    @Test
    void readRevalidatesDeletedSessionsAndExcludesTheExchangeBeingReplaced() {
        var store = new ConversationStore(directory, Runnable::run);
        var session = store.newConversation(WORLD).toCompletableFuture().join();
        var id = new ChatContracts.RequestId("latest");
        store.appendTurn(WORLD, SCOPE, session.id(), new ChatContracts.Turn(id, "lantern", "answer", List.of(),
                new ChatContracts.Context("{}", null, SCOPE), List.of(), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 5, Instant.EPOCH))
                .toCompletableFuture().join();
        var adapter = new HistoryAdapter(store, WORLD, () -> SCOPE);
        var query = new PackSageTools.ReadRequest("search_history",
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("lantern"))), SCOPE);
        assertTrue(adapter.execute(query, id).toCompletableFuture().join().records().isEmpty());
        var found = adapter.execute(query, null).toCompletableFuture().join();
        assertEquals(1, found.records().size(), found.detail());
        var read = new PackSageTools.ReadRequest("read_history", new Evidence.JsonObject(Map.of("handles",
                new Evidence.JsonArray(List.of(new Evidence.JsonString("history:"
                        + found.records().getFirst().source().sourceHandle()))))), SCOPE);
        assertEquals(Evidence.Outcome.ERROR, adapter.execute(read, id).toCompletableFuture().join().outcome());
        store.deleteConversation(WORLD, session.id()).toCompletableFuture().join();
        assertEquals(Evidence.Outcome.ERROR, adapter.execute(read, null).toCompletableFuture().join().outcome());
    }

    @Test
    void scopeChangeDiscardsLateDiskResults() {
        var store = new ConversationStore(directory, Runnable::run);
        var session = store.newConversation(WORLD).toCompletableFuture().join();
        store.appendTurn(WORLD, SCOPE, session.id(), new ChatContracts.Turn(new ChatContracts.RequestId("old"),
                "lantern", "answer", List.of(), new ChatContracts.Context("{}", null, SCOPE), List.of(), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 5, Instant.EPOCH))
                .toCompletableFuture().join();
        var work = new ArrayList<Runnable>();
        var scope = new AtomicReference<>(SCOPE);
        var adapter = new HistoryAdapter(new ConversationStore(directory, work::add), WORLD, scope::get);
        var result = adapter.execute(new PackSageTools.ReadRequest("search_history",
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("lantern"))), SCOPE), null)
                .toCompletableFuture();
        assertFalse(result.isDone());
        scope.set(new Evidence.Scope("another-world", "resources", "team", "runtime", 2));
        while (!work.isEmpty()) work.removeFirst().run();
        assertEquals(Evidence.Outcome.ERROR, result.join().outcome());
        assertTrue(result.join().records().isEmpty());
    }

    @Test
    void overlappingSearchesKeepBothDiscoveredHandlesReadable() {
        var store = new ConversationStore(directory, Runnable::run);
        var session = store.newConversation(WORLD).toCompletableFuture().join();
        for (var name : List.of("lantern", "coal")) {
            store.appendTurn(WORLD, SCOPE, session.id(), new ChatContracts.Turn(new ChatContracts.RequestId(name),
                    name, "answer for " + name, List.of(), new ChatContracts.Context("{}", null, SCOPE),
                    List.of(), List.of(), new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null),
                    5, Instant.EPOCH)).toCompletableFuture().join();
        }
        var work = new ArrayList<Runnable>();
        var adapter = new HistoryAdapter(new ConversationStore(directory, work::add), WORLD, () -> SCOPE);
        var first = adapter.execute(new PackSageTools.ReadRequest("search_history",
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("lantern"))), SCOPE), null)
                .toCompletableFuture();
        var second = adapter.execute(new PackSageTools.ReadRequest("search_history",
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("coal"))), SCOPE), null)
                .toCompletableFuture();
        assertFalse(first.isDone());
        assertFalse(second.isDone());
        while (!work.isEmpty()) work.removeFirst().run();
        var read = adapter.execute(new PackSageTools.ReadRequest("read_history", new Evidence.JsonObject(Map.of(
                "handles", new Evidence.JsonArray(List.of(
                        new Evidence.JsonString("history:" + first.join().records().getFirst().source().sourceHandle()),
                        new Evidence.JsonString("history:" + second.join().records().getFirst().source().sourceHandle()))))),
                SCOPE), null).toCompletableFuture();
        while (!work.isEmpty()) work.removeFirst().run();
        assertEquals(2, read.join().records().size(), read.join().detail());
        assertEquals(Evidence.Outcome.COMPLETE, read.join().outcome());
    }
}
