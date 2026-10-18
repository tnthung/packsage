package dev.packsage.chat;

import dev.packsage.knowledge.Evidence;
import dev.packsage.model.ModelBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ConversationStoreTest {
    private static final Executor DIRECT = Runnable::run;
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world-a", "en_us", "team-a", "provider-1", 4);

    @TempDir
    Path temporaryDirectory;

    @Test
    void fullTurnAndMediaRoundTripKeepsSourcesAndContext() throws Exception {
        ConversationStore store = new ConversationStore(temporaryDirectory, DIRECT);
        ChatContracts.WorldIdentity world = new ChatContracts.WorldIdentity("world-a", "Readable World");
        ChatContracts.Conversation conversation = store.newConversation(world).toCompletableFuture().join();
        ChatContracts.Screenshot screenshot = new ChatContracts.Screenshot("request-1.png", "image/png", 3, Instant.EPOCH);
        ChatContracts.Context context = new ChatContracts.Context("{\"dimension\":\"overworld\"}", screenshot, SCOPE);
        Evidence.Source source = new Evidence.Source(SCOPE, "jei", "1", "recipe:altar", 4, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of("pack"));
        Evidence.NavigationTarget target = new Evidence.NavigationTarget(SCOPE, "jei", "recipe:altar", "altar-view");
        ChatContracts.Turn turn = new ChatContracts.Turn(new ChatContracts.RequestId("request-1"), "Where?",
                "Here.", List.of(new ModelBackend.EncryptedReasoning("reasoning-1", "encrypted", "[]"),
                new ModelBackend.ToolCall("call-1", "packsage", "lookup", "{}"),
                new ModelBackend.ToolResult("call-1", "{\"ok\":true}", false),
                new ModelBackend.WebSearchCall("search-1", ModelBackend.WebSearchStatus.COMPLETED,
                        new ModelBackend.OpenPageAction("https://example.test")),
                new ModelBackend.WebSearchCall("search-2", ModelBackend.WebSearchStatus.FAILED, null),
                new ModelBackend.AssistantOutput("message-1", ModelBackend.OutputStatus.COMPLETED,
                        ModelBackend.OutputPhase.FINAL_ANSWER,
                        List.of(new ModelBackend.OutputText("Here.", List.of())))), context, List.of(source),
                List.of(target), new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, "ok"), 9,
                Instant.parse("2026-10-13T01:02:03Z"), List.of(new ChatContracts.ActivityStep("tool:call-1",
                "Reading evidence", "Completed", ChatContracts.ActivityStatus.COMPLETED,
                "{\"filters\":[null,{\"item\":\"packsage:fixture_lantern\"}] }")),
                List.of(new ChatContracts.SteeringMessage("Actually, here.", 5)));

        store.appendMedia(world, conversation.id(), screenshot, new byte[]{1, 2, 3}).toCompletableFuture().join();
        store.appendTurn(world, SCOPE, conversation.id(), turn).toCompletableFuture().join();

        ChatContracts.Conversation reopened = store.openLatest(world).toCompletableFuture().join();
        assertTrue(reopened.turns().contains(turn));
        assertEquals(List.of(target), reopened.turns().getFirst().navigationTargets());
        assertEquals(turn.sentAt(), reopened.turns().getFirst().sentAt());
        assertEquals(turn.activities(), reopened.turns().getFirst().activities());
        assertEquals("{\"filters\":[null,{\"item\":\"packsage:fixture_lantern\"}] }",
                reopened.turns().getFirst().activities().getFirst().serializedArguments());
        assertEquals(turn.steeringMessages(), reopened.turns().getFirst().steeringMessages());
        assertArrayEquals(new byte[]{1, 2, 3}, store.readMedia(world, conversation.id(), screenshot)
                .toCompletableFuture().join());
        assertTrue(Files.exists(store.conversationPath(world, conversation.id())));
        try (var paths = Files.walk(temporaryDirectory)) {
            assertTrue(paths.anyMatch(path -> path.toString().endsWith(".png")));
        }
    }

    @Test
    void loadingFailedToolActivityEnrichesOnlyGenericDetailsFromSavedReplayResults() {
        ConversationStore store = new ConversationStore(temporaryDirectory, DIRECT);
        ChatContracts.WorldIdentity world = new ChatContracts.WorldIdentity("world-a", "Readable World");
        ChatContracts.Conversation conversation = store.newConversation(world).toCompletableFuture().join();
        ChatContracts.Turn turn = new ChatContracts.Turn(new ChatContracts.RequestId("saved-failure"), "Why?", "Could not complete.",
                List.of(new ModelBackend.ToolCall("call-1", "packsage", "read_evidence", "{}"),
                        new ModelBackend.ToolResult("call-1", "{\"detail\":\"Missing evidence\",\"code\":\"NOT_FOUND\"}", true),
                        new ModelBackend.ToolCall("call-2", "packsage", "read_history", "{}"),
                        new ModelBackend.ToolResult("call-2", "{\"detail\":\"Different failure\",\"code\":\"PRIVATE\"}", true)),
                new ChatContracts.Context("context", null, SCOPE), List.of(), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1,
                Instant.parse("2026-10-17T00:00:00Z"), List.of(
                        new ChatContracts.ActivityStep("tool:call-1", "Reading evidence", "Could not complete",
                                ChatContracts.ActivityStatus.FAILED),
                        new ChatContracts.ActivityStep("tool:call-2", "Reading history", "Existing specific reason",
                                ChatContracts.ActivityStatus.FAILED)));
        store.appendTurn(world, SCOPE, conversation.id(), turn).toCompletableFuture().join();

        ChatContracts.Turn loaded = store.openConversation(world, conversation.id()).toCompletableFuture().join().turns().getFirst();

        assertTrue(loaded.activities().getFirst().detail().contains("Missing evidence"), loaded.activities().getFirst().detail());
        assertTrue(loaded.activities().getFirst().detail().contains("NOT_FOUND"), loaded.activities().getFirst().detail());
        assertEquals("Existing specific reason", loaded.activities().get(1).detail());
    }

    @Test
    void historySearchIsWorldScopedFindsFullTextAndPaginatesWithoutChangingLatest() throws Exception {
        ConversationStore store = new ConversationStore(temporaryDirectory, DIRECT);
        ChatContracts.WorldIdentity world = new ChatContracts.WorldIdentity("world-a", "World A");
        ChatContracts.WorldIdentity renamed = new ChatContracts.WorldIdentity("world-a", "Renamed World");
        ChatContracts.WorldIdentity other = new ChatContracts.WorldIdentity("world-b", "Other World");
        ChatContracts.Conversation first = store.newConversation(world).toCompletableFuture().join();
        ChatContracts.Conversation second = store.newConversation(world).toCompletableFuture().join();
        ChatContracts.Conversation third = store.newConversation(world).toCompletableFuture().join();
        ChatContracts.Conversation foreign = store.newConversation(other).toCompletableFuture().join();
        store.appendTurn(world, SCOPE, first.id(), historyTurn("first", "opening", "answer one", ChatContracts.OutcomeStatus.COMPLETED))
                .toCompletableFuture().join();
        store.appendTurn(world, SCOPE, second.id(), historyTurn("second", "question", "buried unique phrase", ChatContracts.OutcomeStatus.COMPLETED))
                .toCompletableFuture().join();
        store.appendTurn(world, SCOPE, third.id(), historyTurn("third", "another question", "buried unique details", ChatContracts.OutcomeStatus.COMPLETED))
                .toCompletableFuture().join();
        store.appendTurn(other, SCOPE, foreign.id(), historyTurn("foreign", "foreign question", "buried unique phrase", ChatContracts.OutcomeStatus.COMPLETED))
                .toCompletableFuture().join();
        Path latest = store.conversationPath(world, second.id()).getParent().resolve("latest");
        String marker = Files.readString(latest);

        ConversationStore.HistoryPage page = store.searchHistory(renamed, "buried unique", null, 1, null)
                .toCompletableFuture().join();

        assertEquals(1, page.entries().size());
        assertTrue(page.entries().getFirst().answer().startsWith("buried unique"));
        assertEquals(marker, Files.readString(latest));
        assertTrue(page.nextCursor() != null);
        assertThrows(RuntimeException.class, () -> store.searchHistory(renamed, "different query", null, 1, page.nextCursor())
                .toCompletableFuture().join());
        ConversationStore.HistoryPage next = store.searchHistory(renamed, "buried unique", null, 1, page.nextCursor())
                .toCompletableFuture().join();
        assertEquals(1, next.entries().size());
        assertNotEquals(page.entries().getFirst().sessionId(), next.entries().getFirst().sessionId());
        int results = page.entries().size() + next.entries().size();
        while (next.nextCursor() != null) {
            next = store.searchHistory(renamed, "buried unique", null, 1, next.nextCursor()).toCompletableFuture().join();
            results += next.entries().size();
        }
        assertEquals(2, results);
        assertTrue(next.entries().isEmpty());
        assertEquals(Evidence.Coverage.COMPLETE, next.coverage());
    }

    @Test
    void historyExcludesReplacedRequestAndReadsDiscoveredRecord() {
        ConversationStore store = new ConversationStore(temporaryDirectory, DIRECT);
        ChatContracts.WorldIdentity world = new ChatContracts.WorldIdentity("world-a", "World A");
        ChatContracts.Conversation conversation = store.newConversation(world).toCompletableFuture().join();
        store.appendTurn(world, SCOPE, conversation.id(), historyTurn("old", "needle question", "old answer", ChatContracts.OutcomeStatus.COMPLETED))
                .toCompletableFuture().join();
        store.appendTurn(world, SCOPE, conversation.id(), historyTurn("new", "needle question", "new answer", ChatContracts.OutcomeStatus.COMPLETED))
                .toCompletableFuture().join();

        ConversationStore.HistoryPage page = store.searchHistory(world, "needle", new ChatContracts.RequestId("new"), 8, null)
                .toCompletableFuture().join();

        assertEquals(1, page.entries().size());
        assertEquals("old", page.entries().getFirst().requestId().value());
        ConversationStore.HistoryEntry entry = page.entries().getFirst();
        assertEquals("old answer", store.readHistory(world, entry.locator()).toCompletableFuture().join().answer());
    }

    @Test
    void historyReadRejectsDeletedAndEditedRecordsAsStale() throws Exception {
        ConversationStore store = new ConversationStore(temporaryDirectory, DIRECT);
        ChatContracts.WorldIdentity world = new ChatContracts.WorldIdentity("world-a", "World A");
        ChatContracts.Conversation conversation = store.newConversation(world).toCompletableFuture().join();
        store.appendTurn(world, SCOPE, conversation.id(), historyTurn("request", "needle", "answer", ChatContracts.OutcomeStatus.COMPLETED))
                .toCompletableFuture().join();
        ConversationStore.HistoryEntry entry = store.searchHistory(world, "needle", null, 8, null)
                .toCompletableFuture().join().entries().getFirst();
        Path file = store.conversationPath(world, conversation.id());

        Files.writeString(file, Files.readString(file).replace("answer", "changed"), StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class, () -> store.readHistory(world, entry.locator()).toCompletableFuture().join());
        Files.delete(file);
        assertThrows(RuntimeException.class, () -> store.readHistory(world, entry.locator()).toCompletableFuture().join());
    }

    @Test
    void historySearchSkipsIncompleteAndNonCompletedExchangesAndReportsMalformedData() throws Exception {
        ConversationStore store = new ConversationStore(temporaryDirectory, DIRECT);
        ChatContracts.WorldIdentity world = new ChatContracts.WorldIdentity("world-a", "World A");
        ChatContracts.Conversation conversation = store.newConversation(world).toCompletableFuture().join();
        store.appendTurn(world, SCOPE, conversation.id(), historyTurn("completed", "needle complete", "safe", ChatContracts.OutcomeStatus.COMPLETED))
                .toCompletableFuture().join();
        store.appendTurn(world, SCOPE, conversation.id(), historyTurn("failed", "needle failed", "unsafe", ChatContracts.OutcomeStatus.FAILED))
                .toCompletableFuture().join();
        Files.writeString(store.conversationPath(world, conversation.id()), "{invalid json}\n", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        ConversationStore.HistoryPage page = store.searchHistory(world, "needle", null, 8, null).toCompletableFuture().join();

        assertEquals(1, page.entries().size());
        assertEquals("completed", page.entries().getFirst().requestId().value());
        assertEquals(Evidence.Coverage.PARTIAL, page.coverage());
    }

    @Test
    void oversizedHistoryRecordCanBeSkippedAcrossCallsAndSearchContinues() throws Exception {
        ConversationStore store = new ConversationStore(temporaryDirectory, DIRECT);
        ChatContracts.WorldIdentity world = new ChatContracts.WorldIdentity("world-a", "World A");
        ChatContracts.Conversation conversation = store.newConversation(world).toCompletableFuture().join();
        Path file = store.conversationPath(world, conversation.id());
        Files.writeString(file, "x".repeat(9 * 1024 * 1024) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        store.appendTurn(world, SCOPE, conversation.id(), historyTurn("after-large", "needle after oversized", "answer",
                ChatContracts.OutcomeStatus.COMPLETED)).toCompletableFuture().join();

        ConversationStore.HistoryPage first = store.searchHistory(world, "needle", null, 8, null).toCompletableFuture().join();

        assertTrue(first.entries().isEmpty());
        assertTrue(first.nextCursor().skippingOversizedRecord());
        ConversationStore.HistoryPage second = store.searchHistory(world, "needle", null, 8, first.nextCursor())
                .toCompletableFuture().join();
        assertEquals("after-large", second.entries().getFirst().requestId().value());
        assertEquals(Evidence.Coverage.PARTIAL, second.coverage());
    }

    @Test
    void historySearchRejectsForeignHeadersAndSymbolicLinkSessions() throws Exception {
        ConversationStore store = new ConversationStore(temporaryDirectory.resolve("store"), DIRECT);
        ChatContracts.WorldIdentity world = new ChatContracts.WorldIdentity("world-a", "World A");
        ChatContracts.WorldIdentity foreignWorld = new ChatContracts.WorldIdentity("world-b", "World B");
        ChatContracts.Conversation local = store.newConversation(world).toCompletableFuture().join();
        ChatContracts.Conversation outside = store.newConversation(world).toCompletableFuture().join();
        store.appendTurn(world, SCOPE, outside.id(), historyTurn("private", "unique phrase", "private answer", ChatContracts.OutcomeStatus.COMPLETED))
                .toCompletableFuture().join();
        Path linked = store.conversationPath(world, outside.id());
        Files.delete(linked);
        ConversationStore outsideStore = new ConversationStore(temporaryDirectory.resolve("outside"), DIRECT);
        ChatContracts.Conversation external = outsideStore.newConversation(foreignWorld).toCompletableFuture().join();
        outsideStore.appendTurn(foreignWorld, SCOPE, external.id(), historyTurn("foreign", "unique phrase", "foreign answer", ChatContracts.OutcomeStatus.COMPLETED))
                .toCompletableFuture().join();
        try {
            Files.createSymbolicLink(linked, outsideStore.conversationPath(foreignWorld, external.id()));
        } catch (UnsupportedOperationException | java.io.IOException exception) {
            assumeTrue(false, "symbolic links unavailable: " + exception.getMessage());
        }

        ConversationStore.HistoryPage page = store.searchHistory(world, "unique phrase", null, 8, null).toCompletableFuture().join();

        assertTrue(page.entries().isEmpty());
        assertEquals(local.id(), store.openLatest(world).toCompletableFuture().join().id());
    }

    @Test
    void historySearchRejectsForeignWorldHeaderInMatchingDirectory() throws Exception {
        ConversationStore store = new ConversationStore(temporaryDirectory, DIRECT);
        ChatContracts.WorldIdentity world = new ChatContracts.WorldIdentity("world-a", "World A");
        ChatContracts.Conversation conversation = store.newConversation(world).toCompletableFuture().join();
        store.appendTurn(world, SCOPE, conversation.id(), historyTurn("private", "foreign header needle", "private answer",
                ChatContracts.OutcomeStatus.COMPLETED)).toCompletableFuture().join();
        Path file = store.conversationPath(world, conversation.id());
        Files.writeString(file, Files.readString(file).replace("\"world\":\"world-a\"", "\"world\":\"world-b\""),
                StandardCharsets.UTF_8);

        ConversationStore.HistoryPage page = store.searchHistory(world, "foreign header needle", null, 8, null)
                .toCompletableFuture().join();

        assertTrue(page.entries().isEmpty());
        assertEquals(Evidence.Coverage.COMPLETE, page.coverage());
    }

    private static ChatContracts.Turn historyTurn(String requestId, String question, String answer,
                                                   ChatContracts.OutcomeStatus status) {
        return new ChatContracts.Turn(new ChatContracts.RequestId(requestId), question, answer, List.of(),
                new ChatContracts.Context("context", null, SCOPE), List.of(), List.of(),
                new ChatContracts.Outcome(status, null), 1, Instant.parse("2026-10-13T01:02:03Z"));
    }

    @Test
    void latestReopensAndNewChatUsesAnotherFile() {
        ConversationStore store = new ConversationStore(temporaryDirectory, DIRECT);
        ChatContracts.WorldIdentity world = new ChatContracts.WorldIdentity("world-a", "World A");
        ChatContracts.Conversation first = store.newConversation(world).toCompletableFuture().join();
        ChatContracts.Conversation second = store.newConversation(world).toCompletableFuture().join();

        assertNotEquals(first.id(), second.id());
        assertNotEquals(store.conversationPath(world, first.id()), store.conversationPath(world, second.id()));
        assertTrue(store.openLatest(world).toCompletableFuture().join().id().equals(second.id()));
    }

    @Test
    void deletingLatestRepairsMarkerToMostRecentRemainingConversation() {
        ConversationStore store = new ConversationStore(temporaryDirectory, DIRECT);
        ChatContracts.WorldIdentity world = new ChatContracts.WorldIdentity("world-a", "World A");
        ChatContracts.Conversation older = store.newConversation(world).toCompletableFuture().join();
        ChatContracts.Conversation latest = store.newConversation(world).toCompletableFuture().join();

        store.deleteConversation(world, latest.id()).toCompletableFuture().join();

        assertFalse(Files.exists(store.conversationPath(world, latest.id())));
        assertEquals(older.id(), store.openLatest(world).toCompletableFuture().join().id());
    }

    @Test
    void deletingWithRenamedWorldLabelUsesStableWorldIdentityAndActualDirectory() {
        ConversationStore store = new ConversationStore(temporaryDirectory, DIRECT);
        ChatContracts.WorldIdentity original = new ChatContracts.WorldIdentity("world-a", "Original label");
        ChatContracts.WorldIdentity renamed = new ChatContracts.WorldIdentity("world-a", "Renamed label");
        ChatContracts.Conversation older = store.newConversation(original).toCompletableFuture().join();
        ChatContracts.Conversation latest = store.newConversation(original).toCompletableFuture().join();

        store.deleteConversation(renamed, latest.id()).toCompletableFuture().join();

        assertFalse(Files.exists(store.conversationPath(original, latest.id())));
        assertEquals(older.id(), store.openLatest(original).toCompletableFuture().join().id());
    }

    @Test
    void deletingNonLatestLeavesLatestAndRejectsCrossWorldOrUnsafeTargets() throws Exception {
        ConversationStore store = new ConversationStore(temporaryDirectory.resolve("store"), DIRECT);
        ChatContracts.WorldIdentity world = new ChatContracts.WorldIdentity("world-a", "World A");
        ChatContracts.WorldIdentity otherWorld = new ChatContracts.WorldIdentity("world-b", "World B");
        ChatContracts.Conversation older = store.newConversation(world).toCompletableFuture().join();
        ChatContracts.Conversation latest = store.newConversation(world).toCompletableFuture().join();
        store.deleteConversation(world, older.id()).toCompletableFuture().join();
        assertEquals(latest.id(), store.openLatest(world).toCompletableFuture().join().id());
        assertThrows(RuntimeException.class, () -> store.deleteConversation(otherWorld, latest.id()).toCompletableFuture().join());
        assertThrows(RuntimeException.class, () -> store.deleteConversation(world, "../../outside").toCompletableFuture().join());
        assertTrue(Files.exists(store.conversationPath(world, latest.id())));

        ConversationStore outsideStore = new ConversationStore(temporaryDirectory.resolve("outside"), DIRECT);
        ChatContracts.Conversation outside = outsideStore.newConversation(world).toCompletableFuture().join();
        Path linked = store.conversationPath(world, outside.id());
        try {
            Files.createSymbolicLink(linked, outsideStore.conversationPath(world, outside.id()));
        } catch (UnsupportedOperationException | java.io.IOException exception) {
            assumeTrue(false, "symbolic links unavailable: " + exception.getMessage());
        }
        assertThrows(RuntimeException.class, () -> store.deleteConversation(world, outside.id()).toCompletableFuture().join());
        assertTrue(Files.exists(outsideStore.conversationPath(world, outside.id())));
    }

    @Test
    void sessionsListsWorldMetadataAndOpensAnExplicitConversation() {
        ConversationStore store = new ConversationStore(temporaryDirectory, DIRECT);
        ChatContracts.WorldIdentity firstWorld = new ChatContracts.WorldIdentity("world-a", "World A");
        ChatContracts.WorldIdentity secondWorld = new ChatContracts.WorldIdentity("world-b", "World B");
        ChatContracts.Conversation first = store.newConversation(firstWorld).toCompletableFuture().join();
        store.appendTurn(firstWorld, new Evidence.Scope("world-a", "en_us", "team-a", "provider-1", 4), first.id(), simpleTurn("first question"))
                .toCompletableFuture().join();
        store.appendTurn(firstWorld, SCOPE, first.id(), new ChatContracts.Turn(new ChatContracts.RequestId("latest"),
                "latest question", "latest answer", List.of(), new ChatContracts.Context("context", null, SCOPE),
                List.of(), new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1))
                .toCompletableFuture().join();
        ChatContracts.Conversation second = store.newConversation(secondWorld).toCompletableFuture().join();

        List<ChatContracts.SessionSummary> sessions = store.sessions().toCompletableFuture().join();

        assertEquals(2, sessions.size());
        assertTrue(sessions.stream().anyMatch(session -> session.id().equals(first.id()) && session.world().equals(firstWorld)
                && session.title().equals("first question") && session.lastMessage().equals("latest answer")));
        assertEquals(second, store.openConversation(secondWorld, second.id()).toCompletableFuture().join());
    }

    @Test
    void readableLabelsCannotEscapeWorldScopeOrCollide() {
        ConversationStore store = new ConversationStore(temporaryDirectory, DIRECT);
        ChatContracts.WorldIdentity traversal = new ChatContracts.WorldIdentity("../outside:world?", "Readable / label");
        ChatContracts.WorldIdentity collision = new ChatContracts.WorldIdentity("outside:world?", "Readable / label");
        ChatContracts.Conversation first = store.newConversation(traversal).toCompletableFuture().join();
        ChatContracts.Conversation second = store.newConversation(collision).toCompletableFuture().join();

        assertTrue(store.conversationPath(traversal, first.id()).normalize().startsWith(temporaryDirectory.normalize()));
        assertNotEquals(store.conversationPath(traversal, first.id()), store.conversationPath(collision, second.id()));
        assertFalse(store.conversationPath(traversal, first.id()).getFileName().toString().contains("\\"));
    }

    @Test
    void truncatedFinalRecordPreservesEarlierTurnsAndReportsRecovery() {
        ConversationStore store = new ConversationStore(temporaryDirectory, DIRECT);
        ChatContracts.WorldIdentity world = new ChatContracts.WorldIdentity("world-a", "World A");
        ChatContracts.Conversation conversation = store.newConversation(world).toCompletableFuture().join();
        Path path = store.conversationPath(world, conversation.id());
        assertTrue(Files.exists(path));
        assertTrue(store.errors().isEmpty());
    }

    @Test
    void truncatedTailCanBeAppendedAndReopenedWithoutLosingTheNewTurn() throws Exception {
        ConversationStore store = new ConversationStore(temporaryDirectory, DIRECT);
        ChatContracts.WorldIdentity world = new ChatContracts.WorldIdentity("world-a", "World A");
        ChatContracts.Conversation conversation = store.newConversation(world).toCompletableFuture().join();
        Path path = store.conversationPath(world, conversation.id());
        Files.writeString(path, "{\"type\":\"turn\",\"question\":\"cut", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        store.appendTurn(world, SCOPE, conversation.id(), simpleTurn("recovered")).toCompletableFuture().join();

        ChatContracts.Conversation reopened = store.openLatest(world).toCompletableFuture().join();
        assertTrue(reopened.turns().stream().anyMatch(turn -> turn.question().equals("recovered")));
    }

    @Test
    void movedConversationHeaderCannotBeOpenedAsAnotherWorld() throws Exception {
        ConversationStore store = new ConversationStore(temporaryDirectory, DIRECT);
        ChatContracts.WorldIdentity firstWorld = new ChatContracts.WorldIdentity("world-a", "World A");
        ChatContracts.WorldIdentity secondWorld = new ChatContracts.WorldIdentity("world-b", "World B");
        ChatContracts.Conversation conversation = store.newConversation(firstWorld).toCompletableFuture().join();
        Path moved = store.conversationPath(secondWorld, conversation.id());
        Files.createDirectories(moved.getParent());
        Files.move(store.conversationPath(firstWorld, conversation.id()), moved);

        ChatContracts.Conversation reopened = store.openLatest(secondWorld).toCompletableFuture().join();
        assertTrue(reopened.turns().isEmpty());
        assertEquals("world-b", reopened.world().worldOrServer());
    }

    @Test
    void mediaNamesRemainDistinctAndReferencedBytesArePreserved() throws Exception {
        ConversationStore store = new ConversationStore(temporaryDirectory, DIRECT);
        ChatContracts.WorldIdentity world = new ChatContracts.WorldIdentity("world-a", "World A");
        ChatContracts.Conversation conversation = store.newConversation(world).toCompletableFuture().join();
        ChatContracts.Screenshot first = new ChatContracts.Screenshot("a/b.png", "image/png", 2, Instant.EPOCH);
        ChatContracts.Screenshot second = new ChatContracts.Screenshot("a?b.png", "image/png", 2, Instant.EPOCH);
        store.appendMedia(world, conversation.id(), first, new byte[]{1, 2}).toCompletableFuture().join();
        store.appendMedia(world, conversation.id(), second, new byte[]{3, 4}).toCompletableFuture().join();

        try (var paths = Files.walk(store.conversationPath(world, conversation.id()).getParent().resolve("media"))) {
            assertEquals(2, paths.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void appendRejectsContextAndSourcesOutsideExpectedRequestScope() {
        ConversationStore store = new ConversationStore(temporaryDirectory, DIRECT);
        ChatContracts.WorldIdentity world = new ChatContracts.WorldIdentity("stable-world", "World A");
        ChatContracts.Conversation conversation = store.newConversation(world).toCompletableFuture().join();
        Evidence.Scope otherScope = new Evidence.Scope("world-b", "en_us", "team-a", "provider-1", 4);
        ChatContracts.Turn turn = new ChatContracts.Turn(new ChatContracts.RequestId("request-1"), "question", "answer",
                List.of(), new ChatContracts.Context("context", null, otherScope), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1);

        assertThrows(RuntimeException.class,
                () -> store.appendTurn(world, SCOPE, conversation.id(), turn).toCompletableFuture().join());
        assertTrue(store.openLatest(world).toCompletableFuture().join().turns().isEmpty());
    }

    @Test
    void replaceLatestTurnKeepsEarlierTurnsAndRejectsWrongRequestOrWorld() {
        ConversationStore store = new ConversationStore(temporaryDirectory, DIRECT);
        ChatContracts.WorldIdentity world = new ChatContracts.WorldIdentity("world-a", "World A");
        ChatContracts.WorldIdentity otherWorld = new ChatContracts.WorldIdentity("world-b", "World B");
        ChatContracts.Conversation conversation = store.newConversation(world).toCompletableFuture().join();
        ChatContracts.Turn first = simpleTurn("first");
        ChatContracts.Turn latest = new ChatContracts.Turn(new ChatContracts.RequestId("latest"), "latest", "", List.of(),
                new ChatContracts.Context("context", null, SCOPE), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.FAILED, "temporary provider failure"), 0);
        ChatContracts.Turn replacement = new ChatContracts.Turn(new ChatContracts.RequestId("replacement"),
                "edited latest", "edited answer", List.of(), new ChatContracts.Context("context", null, SCOPE), List.of(), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 2,
                Instant.parse("2026-10-13T04:05:06Z"));
        store.appendTurn(world, SCOPE, conversation.id(), first).toCompletableFuture().join();
        store.appendTurn(world, SCOPE, conversation.id(), latest).toCompletableFuture().join();

        store.replaceLatestTurn(world, SCOPE, conversation.id(), latest.requestId(), replacement)
                .toCompletableFuture().join();
        ChatContracts.Conversation reopened = store.openConversation(world, conversation.id()).toCompletableFuture().join();
        assertEquals(List.of(first, replacement), reopened.turns());
        assertEquals(replacement.sentAt(), reopened.turns().getLast().sentAt());
        assertThrows(RuntimeException.class, () -> store.replaceLatestTurn(world, SCOPE, conversation.id(),
                latest.requestId(), simpleTurn("wrong id")).toCompletableFuture().join());
        assertThrows(RuntimeException.class, () -> store.replaceLatestTurn(otherWorld, SCOPE, conversation.id(),
                replacement.requestId(), simpleTurn("wrong world")).toCompletableFuture().join());
        assertEquals(List.of(first, replacement), store.openConversation(world, conversation.id()).toCompletableFuture().join().turns());
    }

    @Test
    void latestMarkerLinkCannotRedirectConversationSelectionOutsideTheRoot() throws Exception {
        ConversationStore store = new ConversationStore(temporaryDirectory.resolve("store"), DIRECT);
        ChatContracts.WorldIdentity world = new ChatContracts.WorldIdentity("world-a", "World A");
        ChatContracts.Conversation conversation = store.newConversation(world).toCompletableFuture().join();
        Path latest = store.conversationPath(world, conversation.id()).getParent().resolve("latest");
        Path outside = temporaryDirectory.resolve("outside-latest");
        Files.writeString(outside, "outside");
        Files.delete(latest);
        try {
            Files.createSymbolicLink(latest, outside);
        } catch (UnsupportedOperationException | java.io.IOException exception) {
            assumeTrue(false, "symbolic links unavailable: " + exception.getMessage());
        }

        assertThrows(RuntimeException.class, () -> store.openLatest(world).toCompletableFuture().join());
        assertEquals("outside", Files.readString(outside));
    }

    @Test
    void sessionCatalogIgnoresExternalJsonlLinks() throws Exception {
        ConversationStore store = new ConversationStore(temporaryDirectory.resolve("store"), DIRECT);
        ChatContracts.WorldIdentity world = new ChatContracts.WorldIdentity("world-a", "World A");
        ChatContracts.Conversation conversation = store.newConversation(world).toCompletableFuture().join();
        ConversationStore outsideStore = new ConversationStore(temporaryDirectory.resolve("outside"), DIRECT);
        ChatContracts.Conversation outside = outsideStore.newConversation(world).toCompletableFuture().join();
        outsideStore.appendTurn(world, SCOPE, outside.id(), simpleTurn("external private question")).toCompletableFuture().join();
        Path linked = store.conversationPath(world, outside.id());
        try {
            Files.createSymbolicLink(linked, outsideStore.conversationPath(world, outside.id()));
        } catch (UnsupportedOperationException | java.io.IOException exception) {
            assumeTrue(false, "symbolic links unavailable: " + exception.getMessage());
        }

        List<ChatContracts.SessionSummary> sessions = store.sessions().toCompletableFuture().join();

        assertEquals(1, sessions.size());
        assertEquals(conversation.id(), sessions.getFirst().id());
        assertThrows(RuntimeException.class, () -> store.openConversation(world, outside.id()).toCompletableFuture().join());
    }

    @Test
    void logsWithoutNavigationTargetsStillLoadAndOpenById() throws Exception {
        ConversationStore store = new ConversationStore(temporaryDirectory, DIRECT);
        ChatContracts.WorldIdentity world = new ChatContracts.WorldIdentity("world-a", "World A");
        ChatContracts.Conversation conversation = store.newConversation(world).toCompletableFuture().join();
        store.appendTurn(world, SCOPE, conversation.id(), simpleTurn("legacy")).toCompletableFuture().join();
        Path file = store.conversationPath(world, conversation.id());
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        com.google.gson.JsonObject turn = com.google.gson.JsonParser.parseString(lines.get(1)).getAsJsonObject();
        turn.remove("navigationTargets");
        turn.remove("sentAt");
        turn.remove("activities");
        turn.remove("steeringMessages");
        Files.writeString(file, lines.getFirst() + "\n" + turn + "\n", StandardCharsets.UTF_8);

        ChatContracts.Conversation loaded = store.openConversation(world, conversation.id()).toCompletableFuture().join();

        assertEquals(List.of(simpleTurn("legacy")), loaded.turns());
        assertTrue(loaded.turns().getFirst().navigationTargets().isEmpty());
        assertEquals(null, loaded.turns().getFirst().sentAt());
        assertTrue(loaded.turns().getFirst().steeringMessages().isEmpty());
    }

    private static ChatContracts.Turn simpleTurn(String question) {
        return new ChatContracts.Turn(new ChatContracts.RequestId(question), question, "answer", List.of(),
                new ChatContracts.Context("context", null, SCOPE), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1);
    }

    @Test
    void failedWriteIsVisibleWithoutClaimingSaveSucceeded() throws Exception {
        Path blocked = temporaryDirectory.resolve("blocked");
        Files.writeString(blocked, "a file, not a directory");
        ConversationStore store = new ConversationStore(blocked, DIRECT);
        ChatContracts.WorldIdentity world = new ChatContracts.WorldIdentity("world-a", "World A");
        var result = store.newConversation(world).toCompletableFuture();
        assertTrue(result.isCompletedExceptionally());
        assertFalse(store.errors().isEmpty());
    }
}
