package dev.packsage.chat;

import dev.packsage.knowledge.Evidence;
import dev.packsage.model.ModelBackend;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationOpeningTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world-opening", "en_us", "team", "provider", 1);

    @TempDir
    Path temporaryDirectory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void listingSessionsReadsOnlySmallFilesAndDefersFullBodiesToIoThread(boolean legacy) throws Exception {
        Path root = temporaryDirectory.resolve("conversations");
        var world = new ChatContracts.WorldIdentity("opening-world", "Opening World");
        var setup = new ConversationStore(root, Runnable::run);
        String large = "x".repeat(128 * 1024);
        for (int index = 0; index < 24; index++) {
            var conversation = setup.newConversation(world).toCompletableFuture().join();
            setup.appendTurn(world, SCOPE, conversation.id(), new ChatContracts.Turn(
                    new ChatContracts.RequestId("request-" + index), "question-" + index, large,
                    List.of(new ModelBackend.EncryptedReasoning("replay-" + index, large, "[]")),
                    new ChatContracts.Context("context", null, SCOPE), List.of(), List.of(),
                    new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1,
                    Instant.parse("2026-10-01T00:00:00Z"))).toCompletableFuture().join();
        }
        if (legacy) {
            try (var files = Files.walk(root)) {
                for (Path summary : files.filter(path -> path.toString().endsWith(".summary.json")).toList()) Files.delete(summary);
            }
        }

        BlockingQueue<Runnable> queued = new LinkedBlockingQueue<>();
        var store = new ConversationStore(root, queued::add);
        long callerStarted = System.nanoTime();
        var sessions = store.sessions().toCompletableFuture();
        var latest = store.openLatest(world).toCompletableFuture();
        long callerMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - callerStarted);
        assertFalse(sessions.isDone());
        assertFalse(latest.isDone());
        assertEquals(1, queued.size(), "expected first store read to be queued before it is released");

        Path recordingPath = temporaryDirectory.resolve("opening.jfr");
        CountDownLatch drained = new CountDownLatch(2);
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        long readsWallMillis;
        long[] operationMillis = new long[2];
        try (var recording = new Recording()) {
            recording.enable("jdk.FileRead").withThreshold(Duration.ZERO);
            recording.start();
            long readStarted = System.nanoTime();
            Thread ioThread = new Thread(() -> {
                try {
                    for (int index = 0; index < 2; index++) {
                        Runnable task = queued.poll(10, TimeUnit.SECONDS);
                        if (task == null) throw new AssertionError("timed out waiting for queued conversation read");
                        long operationStarted = System.nanoTime();
                        task.run();
                        operationMillis[index] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - operationStarted);
                        drained.countDown();
                    }
                } catch (Throwable failure) {
                    workerFailure.set(failure);
                }
            }, "conversation-opening-io");
            ioThread.start();
            assertTrue(drained.await(30, TimeUnit.SECONDS), "background reads did not finish");
            ioThread.join();
            readsWallMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - readStarted);
            recording.stop();
            recording.dump(recordingPath);
        }
        if (workerFailure.get() != null) throw new AssertionError("background read failed", workerFailure.get());
        assertTrue(sessions.join().size() >= 24);
        assertTrue(latest.join().turns().size() == 1);

        Map<Path, Long> bytesByFile = new HashMap<>();
        long totalBytes = 0;
        for (var event : RecordingFile.readAllEvents(recordingPath)) {
            if (!event.getEventType().getName().equals("jdk.FileRead")) continue;
            String path = event.getString("path");
            if (path == null || !Path.of(path).toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) continue;
            long bytes = event.getLong("bytesRead");
            Path readPath = Path.of(path).toAbsolutePath().normalize();
            totalBytes += bytes;
            bytesByFile.merge(readPath, bytes, Long::sum);
            assertFalse(event.getThread().getJavaName().equals(Thread.currentThread().getName()),
                    "conversation files were read on the caller thread");
        }
        List<Path> largeReads = bytesByFile.entrySet().stream().filter(entry -> entry.getValue() > 64 * 1024)
                .map(Map.Entry::getKey).toList();
        System.out.printf("Conversation opening legacy=%s: caller scheduling=%dms, sessions=%dms, selected=%dms, sessions+selected wall=%dms, "
                + "JFR bytes=%d, files over 64KB=%d/%d%n", legacy, callerMillis, operationMillis[0], operationMillis[1], readsWallMillis, totalBytes,
                largeReads.size(), 24);
        assertTrue(largeReads.size() <= 1, "full conversation bodies read: " + largeReads);
    }
}
