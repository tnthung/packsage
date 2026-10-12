package dev.packsage.client;

import com.google.gson.JsonObject;
import dev.packsage.chat.ChatContracts;
import dev.packsage.knowledge.Evidence;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import com.mojang.blaze3d.platform.NativeImage;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class GameplayContextCapture {
    @FunctionalInterface
    public interface ObservationSource {
        Observation capture();
    }

    @FunctionalInterface
    public interface FrameCapture {
        CapturedFrame capture(ChatContracts.RequestCapture request);
    }

    @FunctionalInterface
    public interface AsyncFrameCapture {
        CompletionStage<CapturedFrame> capture(ChatContracts.RequestCapture request);
    }

    public record Observation(String hovered, String held, String targeted, String dimension, String position,
                              String biome, String time, String focusKind) {
        public Observation(String held, String targeted, String dimension, String position, String biome,
                           String time, String focusKind) {
            this("unavailable", held, targeted, dimension, position, biome, time, focusKind);
        }
    }

    public record CapturedFrame(String mediaType, byte[] bytes, Instant capturedAt) {
        public CapturedFrame {
            Objects.requireNonNull(mediaType);
            bytes = Arrays.copyOf(bytes, bytes.length);
            Objects.requireNonNull(capturedAt);
        }

        @Override
        public byte[] bytes() {
            return Arrays.copyOf(bytes, bytes.length);
        }
    }

    private record CaptureMetadata(ChatContracts.RequestCapture request, Observation observation,
                                   Evidence.Scope scope, Instant capturedAt, long epoch) {
        private CaptureMetadata {
            Objects.requireNonNull(request);
            Objects.requireNonNull(observation);
            Objects.requireNonNull(scope);
            Objects.requireNonNull(capturedAt);
        }
    }

    private record PendingFrame(ChatContracts.RequestCapture request, Object level, Evidence.Scope scope,
                                long epoch, CompletableFuture<CapturedFrame> result) {
    }

    private final ObservationSource observations;
    private final AsyncFrameCapture frameCapture;
    private final Executor executor;
    private final java.util.function.Supplier<Evidence.Scope> scope;
    private final AtomicReference<PendingFrame> pendingFrame;
    private final AtomicReference<ChatContracts.RequestCapture> activeRequest;
    private final AtomicLong captureEpoch;
    private final Object frameLock;
    private Minecraft minecraft;

    public GameplayContextCapture(ObservationSource observations, FrameCapture frameCapture, Executor executor,
                                  java.util.function.Supplier<Evidence.Scope> scope) {
        this(observations, request -> CompletableFuture.completedFuture(frameCapture.capture(request)), executor, scope,
                new AtomicReference<>(), new AtomicReference<>(), new AtomicLong(), new Object());
    }

    private GameplayContextCapture(ObservationSource observations, AsyncFrameCapture frameCapture, Executor executor,
                                   java.util.function.Supplier<Evidence.Scope> scope,
                                   AtomicReference<PendingFrame> pendingFrame,
                                   AtomicReference<ChatContracts.RequestCapture> activeRequest,
                                   AtomicLong captureEpoch,
                                   Object frameLock) {
        this.observations = Objects.requireNonNull(observations);
        this.frameCapture = Objects.requireNonNull(frameCapture);
        this.executor = Objects.requireNonNull(executor);
        this.scope = Objects.requireNonNull(scope);
        this.pendingFrame = pendingFrame;
        this.activeRequest = Objects.requireNonNull(activeRequest);
        this.captureEpoch = captureEpoch;
        this.frameLock = Objects.requireNonNull(frameLock);
    }

    public static GameplayContextCapture forMinecraft(Executor clientExecutor,
                                                       java.util.function.Supplier<Evidence.Scope> scope) {
        Minecraft minecraft = Minecraft.getInstance();
        Objects.requireNonNull(clientExecutor);
        Objects.requireNonNull(scope);
        AtomicReference<PendingFrame> pendingFrame = new AtomicReference<>();
        AtomicReference<ChatContracts.RequestCapture> activeRequest = new AtomicReference<>();
        AtomicLong captureEpoch = new AtomicLong();
        Object frameLock = new Object();
        ObservationSource observations = () -> {
            var player = minecraft.player;
            var level = minecraft.level;
            if (player == null || level == null) {
                return new Observation(null, null, null, null, null, null, null, "unavailable");
            }
            String targeted = null;
            String focusKind = "unavailable";
            HitResult hitResult = minecraft.hitResult;
            if (hitResult instanceof BlockHitResult blockHit) {
                var blockState = level.getBlockState(blockHit.getBlockPos());
                targeted = "block:" + BuiltInRegistries.BLOCK.getKey(blockState.getBlock())
                        + "@" + blockHit.getBlockPos() + ":" + blockState;
                focusKind = "block";
            } else if (hitResult instanceof EntityHitResult entityHit) {
                var entity = entityHit.getEntity();
                targeted = "entity:" + BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())
                        + "@" + entity.getUUID();
                focusKind = "entity";
            } else if (hitResult != null) {
                focusKind = hitResult.getType().name().toLowerCase(Locale.ROOT);
            }
            var stack = player.getMainHandItem();
            String held = null;
            if (!stack.isEmpty()) {
                held = "item:" + BuiltInRegistries.ITEM.getKey(stack.getItem())
                        + ";count=" + stack.getCount()
                        + ";name=" + stack.getHoverName().getString()
                        + ";components=" + stack.getComponents();
            }
            String dimension = level.dimension().location().toString();
            String position = player.blockPosition().toString();
            String biome = level.getBiome(player.blockPosition()).unwrapKey()
                    .map(key -> key.location().toString()).orElse(null);
            return new Observation(targeted, held, targeted, dimension, position, biome,
                    Long.toString(level.getGameTime()), focusKind);
        };
        AsyncFrameCapture frame = request -> {
            CompletableFuture<CapturedFrame> result = new CompletableFuture<>();
            PendingFrame pending;
            synchronized (frameLock) {
                Evidence.Scope frameScope;
                try {
                    frameScope = Objects.requireNonNull(scope.get());
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                    return result;
                }
                long epoch = captureEpoch.get();
                Object level = minecraft.level;
                if (!request.equals(activeRequest.get()) || level == null || !request.scope().equals(frameScope)) {
                    result.completeExceptionally(new java.util.concurrent.CancellationException("capture scope changed"));
                    return result;
                }
                pending = new PendingFrame(request, level, frameScope, epoch, result);
                if (!pendingFrame.compareAndSet(null, pending)) {
                    result.completeExceptionally(new IllegalStateException("a clean frame capture is already pending"));
                    return result;
                }
            }
            result.orTimeout(5, TimeUnit.SECONDS).whenComplete((ignored, failure) -> {
                if (failure == null) return;
                synchronized (frameLock) {
                    if (pendingFrame.compareAndSet(pending, null)) {
                        captureEpoch.incrementAndGet();
                        activeRequest.compareAndSet(pending.request(), null);
                    }
                }
            });
            return result;
        };
        GameplayContextCapture capture = new GameplayContextCapture(observations, frame, clientExecutor, scope,
                pendingFrame, activeRequest, captureEpoch, frameLock);
        capture.minecraft = minecraft;
        return capture;
    }

    public static GameplayContextCapture forMinecraft(Path ignoredMediaRoot, Executor ignoredIoExecutor,
                                                       java.util.function.Supplier<Evidence.Scope> scope) {
        return forMinecraft(Minecraft.getInstance()::execute, scope);
    }

    public CompletionStage<ChatContracts.CapturedContext> capture(ChatContracts.RequestCapture request,
                                                                   boolean includeScreenshot) {
        Objects.requireNonNull(request);
        PendingFrame superseded;
        long requestEpoch;
        synchronized (frameLock) {
            requestEpoch = captureEpoch.incrementAndGet();
            activeRequest.set(request);
            superseded = pendingFrame.getAndSet(null);
        }
        if (superseded != null) {
            superseded.result().completeExceptionally(new java.util.concurrent.CancellationException("capture superseded"));
        }
        CompletionStage<CaptureMetadata> metadata = CompletableFuture.supplyAsync(() -> {
            Observation observation = Objects.requireNonNull(observations.capture());
            Evidence.Scope currentScope = Objects.requireNonNull(scope.get());
            if (!request.scope().equals(currentScope)) {
                throw new java.util.concurrent.CancellationException("capture scope changed");
            }
            return new CaptureMetadata(request, observation, currentScope, Instant.now(), requestEpoch);
        }, executor);
        return metadata.thenCompose(captured -> {
            if (!includeScreenshot) {
                return CompletableFuture.supplyAsync(() -> {
                    if (captureEpoch.get() != captured.epoch() || !sameScope(captured.scope())) {
                        throw new java.util.concurrent.CancellationException("capture invalidated");
                    }
                    return new ChatContracts.CapturedContext(context(captured, null), new byte[0]);
                }, executor);
            }
            if (captureEpoch.get() != captured.epoch() || !request.equals(activeRequest.get())) {
                return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("capture invalidated"));
            }
            return frameCapture.capture(request).thenApplyAsync(frame -> {
                Objects.requireNonNull(frame);
                if (captureEpoch.get() != captured.epoch() || !sameScope(captured.scope())) {
                    throw new java.util.concurrent.CancellationException("capture invalidated");
                }
                byte[] bytes = frame.bytes();
                ChatContracts.Screenshot screenshot = new ChatContracts.Screenshot(
                        request.requestId().value() + ".png", frame.mediaType(), bytes.length, frame.capturedAt());
                return new ChatContracts.CapturedContext(context(captured, screenshot), bytes);
            }, executor);
        });
    }

    /** Called by the coordinator from {@code RenderGuiEvent.Post}, before the current screen is rendered. */
    public void onRenderGuiPost() {
        PendingFrame pending;
        CapturedFrame captured = null;
        Throwable failure = null;
        synchronized (frameLock) {
            pending = pendingFrame.getAndSet(null);
            if (pending == null) return;
            if (minecraft == null) {
                failure = new IllegalStateException("Minecraft client is unavailable");
            } else if (pending.result().isDone() || minecraft.level != pending.level()
                    || captureEpoch.get() != pending.epoch() || !pending.request().equals(activeRequest.get())
                    || !sameScope(pending.scope())) {
                failure = new java.util.concurrent.CancellationException("clean frame scope changed");
            } else {
                try (NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
                    captured = new CapturedFrame("image/png", image.asByteArray(), Instant.now());
                } catch (Throwable captureFailure) {
                    failure = captureFailure;
                }
            }
        }
        if (failure != null) pending.result().completeExceptionally(failure);
        else if (captured != null) pending.result().complete(captured);
    }

    public void onRenderFramePost() {
        onRenderGuiPost();
    }

    public void cancel(ChatContracts.RequestId requestId) {
        Objects.requireNonNull(requestId);
        PendingFrame pending;
        synchronized (frameLock) {
            var request = activeRequest.get();
            if (request == null || !request.requestId().equals(requestId)) return;
            captureEpoch.incrementAndGet();
            activeRequest.set(null);
            pending = pendingFrame.getAndSet(null);
        }
        if (pending != null) {
            pending.result().completeExceptionally(new java.util.concurrent.CancellationException("capture cancelled"));
        }
    }

    private boolean sameScope(Evidence.Scope expected) {
        try {
            return expected.equals(scope.get());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private ChatContracts.Context context(CaptureMetadata captured, ChatContracts.Screenshot screenshot) {
        Observation observation = captured.observation();
        JsonObject json = new JsonObject();
        json.addProperty("requestId", captured.request().requestId().value());
        json.addProperty("world", captured.request().world().worldOrServer());
        json.addProperty("worldLabel", captured.request().world().readableLabel());
        json.addProperty("hovered", observation.hovered() == null ? "unavailable" : observation.hovered());
        json.addProperty("held", observation.held() == null ? "unavailable" : observation.held());
        json.addProperty("targeted", observation.targeted() == null ? "unavailable" : observation.targeted());
        json.addProperty("dimension", observation.dimension() == null ? "unavailable" : observation.dimension());
        json.addProperty("position", observation.position() == null ? "unavailable" : observation.position());
        json.addProperty("biome", observation.biome() == null ? "unavailable" : observation.biome());
        json.addProperty("time", observation.time() == null ? "unavailable" : observation.time());
        json.addProperty("focusKind", observation.focusKind() == null ? "unavailable" : observation.focusKind());
        json.addProperty("capturedAt", captured.capturedAt().toString());
        return new ChatContracts.Context(json.toString(), screenshot, captured.scope());
    }
}
