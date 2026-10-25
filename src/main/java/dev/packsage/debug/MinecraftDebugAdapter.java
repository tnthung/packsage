package dev.packsage.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import dev.packsage.chat.AssistantRuntime;
import dev.packsage.chat.ChatContracts;
import dev.packsage.chat.PackSageSettings;
import dev.packsage.client.AssistantScreen;
import dev.packsage.client.GameplayContextCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Only bridge workers wait. Every game object is read or changed on its owning thread. */
public final class MinecraftDebugAdapter implements DebugRouter.Game {
    private record ServerSession(IntegratedServer server, UUID player) { }
    private record PendingScreenshot(boolean previousHideGui, CompletableFuture<NativeImage> image) { }
    private record PendingPrompt(PackSageSettings before, PackSageSettings candidate) { }

    private final Minecraft client;
    private final AssistantRuntime runtime;
    private final Runnable openChat;
    private final DebugRequestLog timing;
    private final BiConsumer<PackSageSettings, String> promptWriter;
    private final Object conversationLock = new Object();
    private final Object promptLock = new Object();
    private PendingScreenshot screenshot;

    public MinecraftDebugAdapter(Minecraft client, AssistantRuntime runtime, Path settingsPath, Runnable openChat,
                                 DebugRequestLog timing) {
        this(client, runtime, openChat, timing, (settings, text) -> settings.saveDeveloperPrompt(settingsPath, text));
    }

    MinecraftDebugAdapter(Minecraft client, AssistantRuntime runtime, Runnable openChat,
                          DebugRequestLog timing, BiConsumer<PackSageSettings, String> promptWriter) {
        this.client = client;
        this.runtime = runtime;
        this.openChat = openChat;
        this.timing = timing;
        this.promptWriter = promptWriter;
    }

    @Override
    public JsonObject execute(DebugRouter.Operation operation, JsonObject body) throws Exception {
        return switch (operation) {
            case STATE -> call(client, () -> {
                var json = new JsonObject();
                var world = runtime.view().activeWorld();
                json.addProperty("world", client.level == null || world == null ? null : world.readableLabel());
                var server = client.getSingleplayerServer();
                json.addProperty("save", server == null ? null : server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                        .toAbsolutePath().normalize().getFileName().toString());
                json.addProperty("paused", client.isPaused());
                json.addProperty("running", client.level != null && !client.isPaused());
                json.addProperty("screen", client.screen == null ? null : client.screen.getClass().getName());
                if (client.player != null && client.level != null) {
                    var player = client.player;
                    json.addProperty("dimension", client.level.dimension().location().toString());
                    json.add("position", DebugChatState.GSON.toJsonTree(new double[] { player.getX(), player.getY(), player.getZ() }));
                    json.addProperty("yaw", player.getYRot());
                    json.addProperty("pitch", player.getXRot());
                    json.addProperty("health", player.getHealth());
                    json.addProperty("gamemode", client.gameMode == null ? null : client.gameMode.getPlayerMode().getName());
                    json.addProperty("gameTime", client.level.getGameTime());
                    json.addProperty("dayTime", client.level.getDayTime());
                    json.addProperty("selectedHotbarSlot", player.getInventory().selected);
                    json.add("crosshairTarget", DebugChatState.GSON.toJsonTree(GameplayContextCapture.focus(client).targeted()));
                    json.addProperty("riddenEntity", player.getVehicle() == null ? null : player.getVehicle().getStringUUID());
                    json.addProperty("menuId", player.containerMenu.containerId);
                }
                return json;
            });
            case LOOK -> call(client, () -> {
                if (client.player == null) throw new IllegalStateException("No player");
                float yaw;
                float pitch;
                if (body.has("yaw")) {
                    yaw = body.get("yaw").getAsFloat();
                    pitch = body.get("pitch").getAsFloat();
                } else {
                    Vec3 delta = new Vec3(body.get("x").getAsDouble(), body.get("y").getAsDouble(), body.get("z").getAsDouble())
                            .subtract(client.player.getEyePosition());
                    yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
                    pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.sqrt(delta.x * delta.x + delta.z * delta.z)));
                }
                client.player.setYRot(yaw);
                client.player.setYHeadRot(yaw);
                client.player.setXRot(pitch);
                client.player.connection.send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, client.player.onGround()));
                client.gameRenderer.pick(1.0f);
                var json = new JsonObject();
                json.addProperty("yaw", yaw);
                json.addProperty("pitch", pitch);
                return json;
            });
            case COMMAND, BREAK, OPEN, MENU_MOVE, MENU_CLOSE -> {
                var session = call(client, () -> {
                    if (client.getSingleplayerServer() == null || client.player == null) throw new IllegalStateException("Singleplayer world required");
                    return new ServerSession(client.getSingleplayerServer(), client.player.getUUID());
                });
                yield call(session.server()::executeIfPossible, () -> {
                    var server = session.server();
                    if (server.isStopped()) throw new IllegalStateException("Server stopped");
                    var player = server.getPlayerList().getPlayer(session.player());
                    if (player == null) throw new IllegalStateException("Player left the world");
                    var json = new JsonObject();
                    switch (operation) {
                        case COMMAND -> {
                            var feedback = new JsonArray();
                            json.addProperty("success", false);
                            server.getCommands().performPrefixedCommand(player.createCommandSourceStack().withPermission(4).withSource(new CommandSource() {
                                @Override public void sendSystemMessage(Component message) { feedback.add(message.getString()); }
                                @Override public boolean acceptsSuccess() { return true; }
                                @Override public boolean acceptsFailure() { return true; }
                                @Override public boolean shouldInformAdmins() { return false; }
                            }).withCallback((success, result) -> {
                                json.addProperty("success", success);
                                json.addProperty("result", result);
                            }), body.get("command").getAsString());
                            json.add("feedback", feedback);
                        }
                        case BREAK -> json.addProperty("success", player.gameMode.destroyBlock(new BlockPos(
                                body.get("x").getAsInt(), body.get("y").getAsInt(), body.get("z").getAsInt())));
                        case OPEN -> {
                            if (body.has("entity")) {
                                String id = body.get("entity").getAsString();
                                var entity = player.serverLevel().getEntity(UUID.fromString(id.startsWith("entity:") ? id.substring(7) : id));
                                if (entity == null) throw new IllegalArgumentException("Entity not found in this dimension");
                                var location = new Vec3(0, entity.getBbHeight() / 2.0, 0);
                                var interaction = net.neoforged.neoforge.common.CommonHooks.onInteractEntityAt(player, entity, location, InteractionHand.MAIN_HAND);
                                if (interaction == null) interaction = entity.interactAt(player, location, InteractionHand.MAIN_HAND);
                                if (!interaction.consumesAction()) interaction = player.interactOn(entity, InteractionHand.MAIN_HAND);
                                json.addProperty("interaction", interaction.name());
                            } else {
                                var pos = new BlockPos(body.get("x").getAsInt(), body.get("y").getAsInt(), body.get("z").getAsInt());
                                json.addProperty("interaction", player.gameMode.useItemOn(player, player.serverLevel(), player.getMainHandItem(),
                                        InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)).name());
                            }
                            json.addProperty("menuId", player.containerMenu.containerId);
                            json.addProperty("opened", player.containerMenu != player.inventoryMenu);
                        }
                        case MENU_MOVE -> {
                            var menu = player.containerMenu;
                            if (menu == player.inventoryMenu) throw new IllegalStateException("No open container menu");
                            if (player.isSpectator()) {
                                menu.sendAllDataToRemote();
                                throw new IllegalStateException("Spectators cannot move menu items");
                            }
                            if (!menu.stillValid(player)) throw new IllegalStateException("Open menu is no longer valid");
                            int slot = body.get("slot").getAsInt();
                            if (slot < 0 || slot >= menu.slots.size()) throw new IllegalArgumentException("Slot is outside the open menu");
                            player.resetLastActionTime();
                            menu.suppressRemoteUpdates();
                            try {
                                menu.clicked(slot, 0, body.has("shift") && body.get("shift").getAsBoolean() ? ClickType.QUICK_MOVE : ClickType.PICKUP, player);
                            } finally {
                                menu.resumeRemoteUpdates();
                            }
                            menu.broadcastChanges();
                            json.addProperty("menuId", menu.containerId);
                            json.addProperty("success", true);
                        }
                        case MENU_CLOSE -> { player.closeContainer(); json.addProperty("success", true); }
                        default -> throw new IllegalStateException("Unsupported server operation");
                    }
                    return json;
                });
            }
            case CHAT -> {
                synchronized (conversationLock) {
                    var world = call(client, () -> {
                        if (client.level == null || !runtime.sessionReady()) throw new IllegalStateException("World history is not ready");
                        return runtime.view().activeWorld();
                    });
                    String conversation = body.has("conversation") ? body.get("conversation").getAsString() : "new";
                    ChatContracts.SessionSummary existing = null;
                    if (!conversation.equals("new")) {
                        existing = call(client, runtime::sessions).toCompletableFuture().get(10, TimeUnit.SECONDS).stream()
                                .filter(session -> session.id().equals(conversation) && session.world().equals(world)).findFirst()
                                .orElseThrow(() -> new IllegalArgumentException("Conversation not found in this world"));
                    }
                    var selected = existing;
                    call(client, () -> {
                        if (selected == null) runtime.newChat(); else runtime.openSession(selected);
                        return null;
                    });
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (!call(client, runtime::sessionReady)) {
                        if (System.nanoTime() >= deadline) throw new TimeoutException("Conversation load timeout");
                        Thread.sleep(10);
                    }
                    yield call(client, () -> {
                        if (!runtime.view().activeWorld().equals(world) || selected != null && !runtime.view().conversation().id().equals(selected.id())) {
                            throw new IllegalStateException("Conversation selection changed");
                        }
                        var id = runtime.sendTracked(body.get("question").getAsString(),
                                body.has("screenshot") ? body.get("screenshot").getAsBoolean() : null,
                                body.has("webSearch") ? body.get("webSearch").getAsBoolean() : null);
                        if (id == null) throw new IllegalStateException("Chat rejected: " + String.join("; ", runtime.view().errors()));
                        var json = new JsonObject();
                        json.addProperty("conversationId", runtime.view().conversation().id());
                        json.addProperty("requestId", id.value());
                        return json;
                    });
                }
            }
            case CHAT_STATUS -> {
                String id = body.get("requestId").getAsString();
                var view = call(client, () -> runtime.requestView(new ChatContracts.RequestId(id)));
                if (view == null) throw new IllegalArgumentException("Unknown request");
                var saved = view.active() && id.equals(view.activeRequestId()) ? null
                        : call(client, () -> runtime.savedRequest(new ChatContracts.RequestId(id))).toCompletableFuture().get(10, TimeUnit.SECONDS);
                yield DebugChatState.read(view, id, timing.read(id), saved);
            }
            case PROMPT_SET -> {
                synchronized (promptLock) {
                    var pending = call(client, () -> {
                        var settings = runtime.settings();
                        String text = body.get("text").isJsonNull() ? null : body.get("text").getAsString();
                        return new PendingPrompt(settings.withDeveloperPrompt(settings.developerPrompt()), settings.withDeveloperPrompt(
                                dev.packsage.model.GroundingInstructions.defaultInstructions().equals(text) ? null : text));
                    });
                    for (int attempt = 0; pending != null && attempt < 3; attempt++) {
                        var written = pending;
                        promptWriter.accept(written.candidate(), written.candidate().developerPrompt());
                        pending = call(client, () -> {
                            var settings = runtime.settings();
                            if (settings.sameSnapshot(written.before())) {
                                settings.setDeveloperPrompt(written.candidate().developerPrompt());
                                return null;
                            }
                            // A screen save wins for fields it changed; persist the latest snapshot before publishing.
                            return new PendingPrompt(settings.withDeveloperPrompt(settings.developerPrompt()), settings.withDeveloperPrompt(
                                    Objects.equals(settings.developerPrompt(), written.before().developerPrompt())
                                            ? written.candidate().developerPrompt() : settings.developerPrompt()));
                        });
                    }
                    if (pending != null) {
                        // A newer screen save already persisted itself; do not restore a stale conflict snapshot.
                        var restore = pending.before();
                        if (call(client, () -> runtime.settings().sameSnapshot(restore))) {
                            promptWriter.accept(restore, restore.developerPrompt());
                        }
                        throw new IllegalStateException("Prompt save conflict: settings kept changing; retry the request");
                    }
                    yield execute(DebugRouter.Operation.PROMPT_GET, body);
                }
            }
            case PROMPT_GET -> call(client, () -> {
                var settings = runtime.settings();
                var json = new JsonObject();
                json.addProperty("text", settings.developerPrompt());
                json.addProperty("effectiveText", settings.developerPrompt() == null
                        ? dev.packsage.model.GroundingInstructions.defaultInstructions() : settings.developerPrompt());
                return json;
            });
            case NAVIGATE -> {
                synchronized (conversationLock) {
                    String id = body.get("requestId").getAsString();
                    var view = call(client, () -> runtime.requestView(new ChatContracts.RequestId(id)));
                    if (view == null) throw new IllegalArgumentException("Unknown request");
                    String targetId = body.get("targetId").getAsString();
                    var matching = view.navigationTargets().stream().filter(target -> target.targetId().equals(targetId)).toList();
                    if (matching.isEmpty() || matching.stream().anyMatch(target -> !target.providerId().equals(matching.getFirst().providerId()))) {
                        throw new IllegalArgumentException("Target id is missing or ambiguous for this reply");
                    }
                    call(client, () -> {
                        if (!runtime.view().conversation().id().equals(view.conversation().id())) runtime.openSession(new ChatContracts.SessionSummary(
                                view.conversation().world(), view.conversation().id(), "", "", java.time.Instant.now()));
                        return null;
                    });
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (!call(client, runtime::sessionReady)) {
                        if (System.nanoTime() >= deadline) throw new TimeoutException("Conversation load timeout");
                        Thread.sleep(10);
                    }
                    yield DebugChatState.GSON.toJsonTree(call(client, () -> runtime.navigateWithResult(matching.getFirst()))
                            .toCompletableFuture().get(10, TimeUnit.SECONDS)).getAsJsonObject();
                }
            }
            case SCREENSHOT -> {
                var pending = call(client, () -> {
                    if (screenshot != null) throw new IllegalStateException("Screenshot already pending");
                    var next = new PendingScreenshot(client.options.hideGui, new CompletableFuture<>());
                    screenshot = next;
                    if (body.has("hideGui")) client.options.hideGui = body.get("hideGui").getAsBoolean();
                    return next;
                });
                try (var image = pending.image().get(10, TimeUnit.SECONDS)) {
                    Path path = client.gameDirectory.toPath().resolve("screenshots/packsage-debug-" + UUID.randomUUID() + ".png");
                    Files.createDirectories(path.getParent());
                    image.writeToFile(path);
                    var json = new JsonObject();
                    json.addProperty("path", path.toAbsolutePath().toString());
                    yield json;
                } catch (TimeoutException | InterruptedException exception) {
                    if (!pending.image().cancel(false)) {
                        var image = pending.image().getNow(null);
                        if (image != null) image.close();
                    }
                    throw exception;
                } finally {
                    client.execute(() -> {
                        if (screenshot == pending) {
                            screenshot = null;
                            client.options.hideGui = pending.previousHideGui();
                            pending.image().cancel(false);
                        }
                    });
                }
            }
            case CHAT_SCREEN -> call(client, () -> {
                if (body.get("open").getAsBoolean()) {
                    if (client.level == null || !runtime.sessionReady()) throw new IllegalStateException("World history is not ready");
                    openChat.run();
                } else if (client.screen instanceof AssistantScreen) client.setScreen(null);
                var json = new JsonObject();
                json.addProperty("open", client.screen instanceof AssistantScreen);
                return json;
            });
        };
    }

    /** Runs after rendering a whole frame, including when hideGui is true. Client thread only. */
    public void onRenderFrame() {
        var pending = screenshot;
        if (pending == null) return;
        screenshot = null;
        try {
            var image = Screenshot.takeScreenshot(client.getMainRenderTarget());
            if (!pending.image().complete(image)) image.close();
        } catch (RuntimeException exception) {
            pending.image().completeExceptionally(exception);
        } finally {
            client.options.hideGui = pending.previousHideGui();
        }
    }

    public void close() {
        if (screenshot == null) return;
        client.options.hideGui = screenshot.previousHideGui();
        screenshot.image().cancel(false);
        screenshot = null;
    }

    private static <T> T call(Executor executor, Supplier<T> action) throws Exception {
        var result = new CompletableFuture<T>();
        try {
            executor.execute(() -> {
                if (result.isDone()) return;
                try { result.complete(action.get()); }
                catch (Throwable failure) { result.completeExceptionally(failure); }
            });
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            throw new IllegalStateException("Game executor is unavailable", exception);
        }
        try {
            return result.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException | InterruptedException exception) {
            result.cancel(false);
            throw exception;
        }
    }
}
