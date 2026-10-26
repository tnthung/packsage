package dev.packsage.chat;

import dev.packsage.knowledge.Evidence;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationSidebarTest {
    @TempDir
    Path root;

    @Test
    void otherWorldRowsWithValidSummariesNeverOpenConversationFiles() throws Exception {
        var world = new ChatContracts.WorldIdentity("world", "World");
        var other = new ChatContracts.WorldIdentity("other", "Other");
        var store = new ConversationStore(root, Runnable::run);
        store.newConversation(world).toCompletableFuture().join();
        var foreign = store.newConversation(other).toCompletableFuture().join();
        Path file = store.conversationPath(other, foreign.id());
        Path summaryFile = file.resolveSibling(file.getFileName().toString().replace(".jsonl", ".summary.json"));
        var legacy = com.google.gson.JsonParser.parseString(Files.readString(summaryFile)).getAsJsonObject();
        legacy.remove("label");
        Files.writeString(summaryFile, legacy.toString());
        Path recordingFile = root.resolve("other-rows.jfr");
        try (var recording = new Recording()) {
            recording.enable("jdk.FileRead").withThreshold(Duration.ZERO);
            recording.start();
            var page = store.sessionPage(world, true, 0, 2).toCompletableFuture().join();
            assertEquals(2, page.sessions().size());
            assertTrue(page.sessions().stream().anyMatch(session -> session.world().equals(other)));
            recording.stop();
            recording.dump(recordingFile);
        }
        assertTrue(RecordingFile.readAllEvents(recordingFile).stream().filter(event -> event.getEventType().getName().equals("jdk.FileRead"))
                .map(event -> event.getString("path")).filter(java.util.Objects::nonNull).noneMatch(path -> path.endsWith(".jsonl")));
    }

    @Test
    void openingReadsOnlyNewestNSummariesAndShowMoreReadsTenNewSummariesWithoutTurns() throws Exception {
        var world = new ChatContracts.WorldIdentity("world", "World");
        var scope = new Evidence.Scope("world", "resources", "player", "runtime", 0);
        var store = new ConversationStore(root, Runnable::run);
        var expected = new ArrayList<String>();
        for (int index = 0; index < 18; index++) {
            var conversation = store.newConversation(world).toCompletableFuture().join();
            store.appendTurn(world, scope, conversation.id(), new ChatContracts.Turn(
                    new ChatContracts.RequestId("request-" + index), "title " + index, "body ".repeat(500), List.of(),
                    new ChatContracts.Context("", null, scope), List.of(),
                    new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1)).toCompletableFuture().join();
            expected.addFirst(conversation.id());
            Thread.sleep(2);
        }
        var foreign = new ChatContracts.WorldIdentity("other", "Other");
        store.newConversation(foreign).toCompletableFuture().join();
        Path initialRecording = root.resolve("initial.jfr");
        Path allRecording = root.resolve("all.jfr");
        try (var recording = new Recording()) {
            recording.enable("jdk.FileRead").withThreshold(Duration.ZERO);
            recording.start();
            var first = store.sessionPage(world, false, 0, 3).toCompletableFuture().join();
            assertEquals(expected.subList(0, 3), first.sessions().stream().map(ChatContracts.SessionSummary::id).toList());
            assertTrue(first.hasMore());
            recording.dump(initialRecording);
            var next = store.sessionPage(world, false, 3, 10).toCompletableFuture().join();
            assertEquals(expected.subList(3, 13), next.sessions().stream().map(ChatContracts.SessionSummary::id).toList());
            assertTrue(next.hasMore());
            recording.stop();
            recording.dump(allRecording);
        }
        for (var sample : List.of(java.util.Map.entry(initialRecording, 3), java.util.Map.entry(allRecording, 13))) {
            var reads = RecordingFile.readAllEvents(sample.getKey()).stream()
                    .filter(event -> event.getEventType().getName().equals("jdk.FileRead") && event.getLong("bytesRead") > 0)
                    .map(event -> event.getString("path")).filter(java.util.Objects::nonNull)
                    .filter(path -> Path.of(path).startsWith(root)).toList();
            assertEquals(sample.getValue().longValue(), reads.stream().filter(path -> path.endsWith(".summary.json")).distinct().count());
            assertTrue(reads.stream().noneMatch(path -> path.endsWith(".jsonl")), reads.toString());
        }
        var last = store.sessionPage(world, false, 13, 10).toCompletableFuture().join();
        assertEquals(5, last.sessions().size());
        assertFalse(last.hasMore());
    }

    @Test
    void selectedChatCompletesWhileCatalogIsBlockedOrItsExecutorFails() {
        var world = new ChatContracts.WorldIdentity("world", "World");
        var setup = new ConversationStore(root, Runnable::run);
        var selected = setup.newConversation(world).toCompletableFuture().join();
        List<Runnable> foreground = new ArrayList<>();
        List<Runnable> catalog = new ArrayList<>();
        var store = new ConversationStore(root, foreground::add, catalog::add);
        var listing = store.sessionPage(world, false, 0, 3).toCompletableFuture();
        var opening = store.openLatest(world).toCompletableFuture();
        assertEquals(1, catalog.size());
        assertEquals(1, foreground.size());
        foreground.removeFirst().run();
        assertEquals(selected.id(), opening.join().id());
        assertFalse(listing.isDone());

        var failing = new ConversationStore(root, Runnable::run, task -> { throw new RejectedExecutionException("catalog offline"); });
        assertTrue(failing.sessionPage(world, false, 0, 3).toCompletableFuture().isCompletedExceptionally());
        assertEquals(selected.id(), failing.openLatest(world).toCompletableFuture().join().id());
    }

    @Test
    void missingSummaryNeverBackfillsUnclickedConversationTurns() throws Exception {
        var world = new ChatContracts.WorldIdentity("world", "World");
        var store = new ConversationStore(root, Runnable::run);
        var selected = store.newConversation(world).toCompletableFuture().join();
        Path file = store.conversationPath(world, selected.id());
        Files.delete(file.resolveSibling(file.getFileName().toString().replace(".jsonl", ".summary.json")));
        Files.writeString(file, "{\"type\":\"turn\",broken-body", java.nio.file.StandardOpenOption.APPEND);
        var first = store.sessionPage(world, false, 0, 3).toCompletableFuture().join();
        assertEquals("Saved chat", first.sessions().getFirst().title());
        assertTrue(store.errors().isEmpty(), "listing must not attempt to parse the damaged turn");
    }
}
