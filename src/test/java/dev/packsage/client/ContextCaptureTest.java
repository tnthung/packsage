package dev.packsage.client;

import com.google.gson.JsonParser;
import dev.packsage.chat.ChatContracts;
import dev.packsage.knowledge.Evidence;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContextCaptureTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "runtime", 1);
    private static final ChatContracts.WorldIdentity WORLD = new ChatContracts.WorldIdentity("world", "World");

    @Test
    void cancellingAnOldRequestCannotCancelANewerCapture() {
        var queued = new java.util.ArrayDeque<Runnable>();
        var capture = new GameplayContextCapture(
                () -> new GameplayContextCapture.Observation("held", "target", "overworld", "1,2,3", "plains", "1", "block"),
                request -> new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1}, Instant.EPOCH),
                queued::add, () -> SCOPE);
        var newer = new ChatContracts.RequestId("new");
        var result = capture.capture(new ChatContracts.RequestCapture(newer, WORLD, SCOPE), true).toCompletableFuture();

        capture.cancel(new ChatContracts.RequestId("old"));
        while (!queued.isEmpty()) queued.remove().run();

        assertEquals("new.png", result.join().context().screenshot().assetId());
    }

    @Test
    void captureUsesFreshImmutableObservationAndOptionalScreenshot() {
        Executor direct = Runnable::run;
        var observations = new GameplayContextCapture.ObservationSource() {
            private int count;
            @Override public GameplayContextCapture.Observation capture() {
                count++;
                return new GameplayContextCapture.Observation("held-" + count, "target-" + count,
                        "overworld", "1,2,3", "plains", "time-" + count, "crosshair");
            }
        };
        var frames = (GameplayContextCapture.FrameCapture) request ->
                new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1, 2, 3}, Instant.now());
        GameplayContextCapture capture = new GameplayContextCapture(observations, frames, direct, () -> SCOPE);

        ChatContracts.CapturedContext first = capture.capture(new ChatContracts.RequestCapture(
                new ChatContracts.RequestId("one"), WORLD, SCOPE), true).toCompletableFuture().join();
        ChatContracts.CapturedContext second = capture.capture(new ChatContracts.RequestCapture(
                new ChatContracts.RequestId("two"), WORLD, SCOPE), false).toCompletableFuture().join();

        assertNotEquals(first.context().serializedGameplayContext(), second.context().serializedGameplayContext());
        assertEquals("one.png", first.context().screenshot().assetId());
        assertEquals(3, first.context().screenshot().bytes());
        assertArrayEquals(new byte[]{1, 2, 3}, first.screenshotBytes());
        byte[] copy = first.screenshotBytes();
        copy[0] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, first.screenshotBytes());
        assertNull(second.context().screenshot());
        assertEquals(0, second.screenshotBytes().length);
    }

    @Test
    void screenshotReferenceUsesRequestAssetAndEscapesGameplayValues() {
        Executor direct = Runnable::run;
        GameplayContextCapture capture = new GameplayContextCapture(
                () -> new GameplayContextCapture.Observation("hovered", "held\"\\\n", "target", "overworld",
                        "1,2,3", "plains", "1", "block"),
                request -> new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1}, Instant.EPOCH),
                direct, () -> SCOPE);

        ChatContracts.CapturedContext captured = capture.capture(new ChatContracts.RequestCapture(
                new ChatContracts.RequestId("request-id"), WORLD, SCOPE), true).toCompletableFuture().join();

        JsonParser.parseString(captured.context().serializedGameplayContext());
        assertEquals("request-id.png", captured.context().screenshot().assetId());
        assertEquals(1, captured.screenshotBytes().length);
    }

    @Test
    void changedScopePreventsPublishingScreenshotReference() {
        Executor direct = Runnable::run;
        Evidence.Scope other = new Evidence.Scope("other-world", "en_us", "team", "runtime", 2);
        AtomicReference<Evidence.Scope> current = new AtomicReference<>(SCOPE);
        GameplayContextCapture capture = new GameplayContextCapture(
                () -> new GameplayContextCapture.Observation("held", "target", "overworld", "1,2,3", "plains", "1", "block"),
                request -> {
                    current.set(other);
                    return new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1}, Instant.EPOCH);
                }, direct, current::get);

        var result = capture.capture(new ChatContracts.RequestCapture(
                new ChatContracts.RequestId("request-id"), WORLD, SCOPE), true).toCompletableFuture();

        assertThrows(java.util.concurrent.CompletionException.class, result::join);
    }

    @Test
    void previewReadsLiveGameplayAndMatchesNextRequestWithoutStartingCapture() {
        Executor direct = Runnable::run;
        AtomicReference<GameplayContextCapture.Observation> observation = new AtomicReference<>(
                new GameplayContextCapture.Observation("hovered-one", "held-one", "target-one", "overworld",
                        "1,2,3", "plains", "100", "block"));
        AtomicInteger observationReads = new AtomicInteger();
        AtomicInteger frameCaptures = new AtomicInteger();
        GameplayContextCapture capture = new GameplayContextCapture(
                () -> {
                    observationReads.incrementAndGet();
                    return observation.get();
                },
                request -> {
                    frameCaptures.incrementAndGet();
                    return new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1}, Instant.EPOCH);
                }, direct, () -> SCOPE);

        ChatContracts.Context firstPreview = capture.preview();
        observation.set(new GameplayContextCapture.Observation("hovered-two", "held-two", "target-two", "nether",
                "4,5,6", "crimson_forest", "200", "entity"));
        ChatContracts.Context preview = capture.preview();
        ChatContracts.CapturedContext actual = capture.capture(new ChatContracts.RequestCapture(
                new ChatContracts.RequestId("preview-check"), WORLD, SCOPE), false).toCompletableFuture().join();

        var firstJson = JsonParser.parseString(firstPreview.serializedGameplayContext()).getAsJsonObject();
        var previewJson = JsonParser.parseString(preview.serializedGameplayContext()).getAsJsonObject();
        var actualJson = JsonParser.parseString(actual.context().serializedGameplayContext()).getAsJsonObject();
        for (String key : new String[]{"hovered", "held", "targeted", "dimension", "position", "biome", "time", "focusKind"}) {
            assertEquals(previewJson.get(key), actualJson.get(key), key);
        }
        assertEquals("hovered-one", firstJson.get("hovered").getAsString());
        assertEquals("hovered-two", previewJson.get("hovered").getAsString());
        assertEquals("crimson_forest", previewJson.get("biome").getAsString());
        assertNull(preview.screenshot());
        assertNull(actual.context().screenshot());
        assertEquals(3, observationReads.get());
        assertEquals(0, frameCaptures.get());
    }
}
