package dev.packsage.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.serialization.JsonOps;
import dev.packsage.PackSageLog;
import dev.packsage.chat.ChatContracts;
import dev.packsage.knowledge.Evidence;
import dev.packsage.world.ClientFocus;
import dev.packsage.world.ItemPath;
import dev.packsage.world.PathRoot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class GameplayContextCapture {
    private static final int OPENED_MENU_TITLE_LIMIT = 64;

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
                              String biome, String time, String focusKind, String cameraPerspective,
                              String openedMenu, String openedMenuTitle, String targetedHolder, String ridden,
                              String playerName, String playerUuid) {
        public Observation {
            if ("miss".equals(focusKind)) {
                hovered = null;
                targeted = null;
                targetedHolder = null;
            }
        }

        public Observation(String hovered, String held, String targeted, String dimension, String position,
                           String biome, String time, String focusKind, String cameraPerspective,
                           String openedMenu, String openedMenuTitle, String targetedHolder, String ridden) {
            this(hovered, held, targeted, dimension, position, biome, time, focusKind, cameraPerspective,
                    openedMenu, openedMenuTitle, targetedHolder, ridden, null, null);
        }

        public Observation(String hovered, String held, String targeted, String dimension, String position,
                           String biome, String time, String focusKind, String cameraPerspective,
                           String openedMenu, String openedMenuTitle) {
            this(hovered, held, targeted, dimension, position, biome, time, focusKind, cameraPerspective,
                    openedMenu, openedMenuTitle, null, null);
        }

        public Observation(String hovered, String held, String targeted, String dimension, String position,
                           String biome, String time, String focusKind, String cameraPerspective) {
            this(hovered, held, targeted, dimension, position, biome, time, focusKind, cameraPerspective, null, null);
        }

        public Observation(String hovered, String held, String targeted, String dimension, String position,
                           String biome, String time, String focusKind) {
            this(hovered, held, targeted, dimension, position, biome, time, focusKind, null);
        }

        public Observation(String held, String targeted, String dimension, String position, String biome,
                           String time, String focusKind) {
            this("unavailable", held, targeted, dimension, position, biome, time, focusKind, null);
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
            if (player == null) {
                return new Observation(null, null, null, null, null, null, null, "unavailable");
            }
            if (level == null) {
                return new Observation(null, null, null, null, null, null, null, "unavailable", null,
                        null, null, null, null, player.getGameProfile().getName(), player.getGameProfile().getId().toString());
            }
            String targeted = null;
            String focusKind = "unavailable";
            HitResult hitResult = minecraft.hitResult;
            if (hitResult != null && hitResult.getType() == HitResult.Type.MISS) {
                focusKind = "miss";
            } else if (hitResult instanceof BlockHitResult blockHit) {
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
            String dimension = level.dimension().location().toString();
            String position = player.blockPosition().toString();
            String biome = level.getBiome(player.blockPosition()).unwrapKey()
                    .map(key -> key.location().toString()).orElse(null);
            String openedMenu = openedMenuKey(player.containerMenu, player.inventoryMenu);
            String openedMenuTitle = null;
            if (!"none".equals(openedMenu)) {
                var screen = minecraft.screen;
                if (screen instanceof AssistantScreen assistant) screen = assistant.containerScreen();
                if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> container
                        && container.getMenu() == player.containerMenu) openedMenuTitle = openedMenuTitle(container.getTitle());
            }
            var vehicle = player.getVehicle();
            return new Observation(targeted, stack.isEmpty() ? null : heldItem(stack, level.registryAccess()),
                    targeted, dimension, position, biome,
                    Long.toString(level.getGameTime()), focusKind,
                    minecraft.options.getCameraType().name().toLowerCase(Locale.ROOT), openedMenu, openedMenuTitle,
                    targetedHolder(ClientFocus.targetedRoot(hitResult, dimension)),
                    ridden(vehicle == null ? null : vehicle.getUUID()),
                    player.getGameProfile().getName(), player.getGameProfile().getId().toString());
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
        long captureStartedAtNanos = System.nanoTime();
        PackSageLog.LOGGER.info("PackSage timing request={} phase=context_capture_start start_ns={} screenshot={}",
                request.requestId().value(), captureStartedAtNanos, includeScreenshot);
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
            long observationStartedAtNanos = System.nanoTime();
            Observation observation;
            try {
                observation = Objects.requireNonNull(observations.capture());
            } catch (RuntimeException failure) {
                long observationFailedAtNanos = System.nanoTime();
                PackSageLog.LOGGER.info("PackSage timing request={} phase=observation_failed event_ns={} observationMs={} failureType={}",
                        request.requestId().value(), observationFailedAtNanos,
                        (observationFailedAtNanos - observationStartedAtNanos) / 1_000_000,
                        failure.getClass().getSimpleName());
                throw failure;
            }
            long observationReadyAtNanos = System.nanoTime();
            PackSageLog.LOGGER.info("PackSage timing request={} phase=observation_ready event_ns={} queueMs={} observationMs={}",
                    request.requestId().value(), observationReadyAtNanos,
                    (observationStartedAtNanos - captureStartedAtNanos) / 1_000_000,
                    (observationReadyAtNanos - observationStartedAtNanos) / 1_000_000);
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
                    return new ChatContracts.CapturedContext(context(captured.observation(), captured.scope(), captured.request(), captured.capturedAt(), null), new byte[0]);
                }, executor);
            }
            if (captureEpoch.get() != captured.epoch() || !request.equals(activeRequest.get())) {
                return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("capture invalidated"));
            }
            long frameStartedAtNanos = System.nanoTime();
            CompletionStage<CapturedFrame> frameCaptureStage;
            try {
                frameCaptureStage = frameCapture.capture(request);
            } catch (RuntimeException failure) {
                long frameCaptureFailedAtNanos = System.nanoTime();
                PackSageLog.LOGGER.info("PackSage timing request={} phase=frame_capture_failed event_ns={} waitMs={} failureType={}",
                        request.requestId().value(), frameCaptureFailedAtNanos,
                        (frameCaptureFailedAtNanos - frameStartedAtNanos) / 1_000_000, failure.getClass().getSimpleName());
                throw failure;
            }
            return frameCaptureStage.whenComplete((frame, failure) -> {
                if (failure != null) {
                    long frameFailedAtNanos = System.nanoTime();
                    PackSageLog.LOGGER.info("PackSage timing request={} phase=frame_failed event_ns={} waitMs={} failureType={}",
                            request.requestId().value(), frameFailedAtNanos,
                            (frameFailedAtNanos - frameStartedAtNanos) / 1_000_000,
                            failure.getClass().getSimpleName());
                }
            }).thenApplyAsync(frame -> {
                Objects.requireNonNull(frame);
                if (captureEpoch.get() != captured.epoch() || !sameScope(captured.scope())) {
                    throw new java.util.concurrent.CancellationException("capture invalidated");
                }
                byte[] bytes = frame.bytes();
                long frameReadyAtNanos = System.nanoTime();
                PackSageLog.LOGGER.info("PackSage timing request={} phase=frame_ready event_ns={} waitMs={} frameBytes={}",
                        request.requestId().value(), frameReadyAtNanos,
                        (frameReadyAtNanos - frameStartedAtNanos) / 1_000_000, bytes.length);
                ChatContracts.Screenshot screenshot = new ChatContracts.Screenshot(
                        request.requestId().value() + ".png", frame.mediaType(), bytes.length, frame.capturedAt());
                return new ChatContracts.CapturedContext(context(captured.observation(), captured.scope(), captured.request(), captured.capturedAt(), screenshot), bytes);
            }, executor);
        });
    }

    public ChatContracts.Context preview() {
        Observation observation = Objects.requireNonNull(observations.capture());
        Evidence.Scope currentScope = Objects.requireNonNull(scope.get());
        return context(observation, currentScope, null, null, null);
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
                long frameStartedAtNanos = System.nanoTime();
                try (NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
                    byte[] bytes = image.asByteArray();
                    captured = new CapturedFrame("image/png", bytes, Instant.now());
                    long frameImageReadyAtNanos = System.nanoTime();
                    PackSageLog.LOGGER.info("PackSage timing request={} phase=frame_image event_ns={} frameMs={} frameBytes={}",
                            pending.request().requestId().value(), frameImageReadyAtNanos,
                            (frameImageReadyAtNanos - frameStartedAtNanos) / 1_000_000, bytes.length);
                } catch (Throwable captureFailure) {
                    failure = captureFailure;
                    PackSageLog.LOGGER.info("PackSage timing request={} phase=frame_image_failed event_ns={} failureType={}",
                            pending.request().requestId().value(), System.nanoTime(), captureFailure.getClass().getSimpleName());
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

    /**
     * The crosshair holder and the open container screen, whose menu must be the player's open one, for the world
     * tools' holder words; client thread only.
     */
    public static ClientFocus focus(Minecraft minecraft) {
        var player = minecraft.player;
        var level = minecraft.level;
        if (player == null || level == null) return ClientFocus.NONE;
        var targeted = ClientFocus.targetedRoot(minecraft.hitResult, level.dimension().location().toString());
        var screen = minecraft.screen;
        if (screen instanceof AssistantScreen assistant) screen = assistant.containerScreen();
        if (player.containerMenu != player.inventoryMenu
                && screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> container
                && container.getMenu() == player.containerMenu) {
            var title = container.getTitle().getString();
            return new ClientFocus(targeted, player.containerMenu.containerId,
                    title.length() > OPENED_MENU_TITLE_LIMIT ? title.substring(0, OPENED_MENU_TITLE_LIMIT) : title);
        }
        return new ClientFocus(targeted, -1, null);
    }

    static String openedMenuKey(AbstractContainerMenu menu, AbstractContainerMenu inventoryMenu) {
        if (menu == null || menu == inventoryMenu) return "none";
        try {
            var type = menu.getType();
            var key = type == null ? null : BuiltInRegistries.MENU.getKey(type);
            return key == null ? "unknown" : key.toString();
        } catch (RuntimeException ignored) {
            return "unknown";
        }
    }

    static String openedMenuTitle(Component title) {
        if (title == null || !(title.getContents() instanceof net.minecraft.network.chat.contents.PlainTextContents)
                || !title.getSiblings().isEmpty()) return null;
        String text = title.getString();
        return text.isEmpty() || text.length() > OPENED_MENU_TITLE_LIMIT ? null : text;
    }

    /** The root text of the holder under the crosshair, as the world tools name it; null when nothing is targeted. */
    static String targetedHolder(PathRoot root) {
        return root == null ? null : new ItemPath(root, List.of()).text();
    }

    /** The entity the player rides, as the world tools name it; null when the player rides nothing. */
    static String ridden(UUID vehicle) {
        return vehicle == null ? null : "entity:" + vehicle;
    }

    static String heldItem(ItemStack stack, RegistryAccess registries) {
        String components;
        try {
            components = DataComponentMap.CODEC.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE),
                    stack.getComponents()).result().map(com.google.gson.JsonElement::toString).orElse("unknown");
        } catch (RuntimeException ignored) {
            components = "unknown";
        }
        return "item:" + BuiltInRegistries.ITEM.getKey(stack.getItem())
                + ";count=" + stack.getCount()
                + ";name=" + stack.getHoverName().getString()
                + ";components=" + components;
    }

    private ChatContracts.Context context(Observation observation, Evidence.Scope currentScope,
                                          ChatContracts.RequestCapture request, Instant capturedAt,
                                          ChatContracts.Screenshot screenshot) {
        JsonObject json = new JsonObject();
        if (request != null) {
            json.addProperty("requestId", request.requestId().value());
            json.addProperty("world", request.world().worldOrServer());
            json.addProperty("worldLabel", request.world().readableLabel());
        }
        json.addProperty("playerName", observation.playerName() == null ? "unavailable" : observation.playerName());
        json.addProperty("playerUuid", observation.playerUuid() == null ? "unavailable" : observation.playerUuid());
        json.addProperty("hovered", observation.hovered() == null ? "unavailable" : observation.hovered());
        json.addProperty("held", observation.held() == null ? "unavailable" : observation.held());
        json.addProperty("targeted", observation.targeted() == null ? "unavailable" : observation.targeted());
        json.addProperty("dimension", observation.dimension() == null ? "unavailable" : observation.dimension());
        json.addProperty("position", observation.position() == null ? "unavailable" : observation.position());
        json.addProperty("biome", observation.biome() == null ? "unavailable" : observation.biome());
        json.addProperty("time", observation.time() == null ? "unavailable" : observation.time());
        json.addProperty("focusKind", observation.focusKind() == null ? "unavailable" : observation.focusKind());
        json.addProperty("cameraPerspective", observation.cameraPerspective() == null ? "unavailable" : observation.cameraPerspective());
        json.addProperty("openedMenu", observation.openedMenu() == null ? "none" : observation.openedMenu());
        if (observation.openedMenuTitle() != null) json.addProperty("openedMenuTitle", observation.openedMenuTitle());
        json.addProperty("targetedHolder", observation.targetedHolder() == null ? "unavailable" : observation.targetedHolder());
        json.addProperty("ridden", observation.ridden() == null ? "none" : observation.ridden());
        if (capturedAt != null) {
            json.addProperty("capturedAt", capturedAt.toString());
            // The player's own clock and offset, so day words such as today follow their calendar, not UTC.
            json.addProperty("playerLocalTime", capturedAt.atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime()
                    .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
        }
        return new ChatContracts.Context(json.toString(), screenshot, currentScope);
    }
}
