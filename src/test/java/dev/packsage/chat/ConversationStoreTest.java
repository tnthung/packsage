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
        ChatContracts.Turn turn = new ChatContracts.Turn(new ChatContracts.RequestId("request-1"), "Where?",
                "Here.", List.of(new ModelBackend.EncryptedReasoning("reasoning-1", "encrypted", "[]"),
                new ModelBackend.ToolCall("call-1", "lookup", "{}"),
                new ModelBackend.ToolResult("call-1", "{\"ok\":true}", false)), context, List.of(source),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, "ok"), 9);

        store.appendMedia(world, conversation.id(), screenshot, new byte[]{1, 2, 3}).toCompletableFuture().join();
        store.appendTurn(world, SCOPE, conversation.id(), turn).toCompletableFuture().join();

        ChatContracts.Conversation reopened = store.openLatest(world).toCompletableFuture().join();
        assertTrue(reopened.turns().contains(turn));
        assertArrayEquals(new byte[]{1, 2, 3}, store.readMedia(world, conversation.id(), screenshot)
                .toCompletableFuture().join());
        assertTrue(Files.exists(store.conversationPath(world, conversation.id())));
        try (var paths = Files.walk(temporaryDirectory)) {
            assertTrue(paths.anyMatch(path -> path.toString().endsWith(".png")));
        }
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
