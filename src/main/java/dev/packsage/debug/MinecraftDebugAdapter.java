package dev.packsage.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import dev.packsage.PackSageLog;
import dev.packsage.chat.AssistantRuntime;
import dev.packsage.chat.ChatContracts;
import dev.packsage.chat.PackSageSettings;
import dev.packsage.client.AssistantScreen;
import dev.packsage.client.GameplayContextCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/** Only bridge workers wait. Every game object is read or changed on its owning thread. */
public final class MinecraftDebugAdapter implements DebugRouter.Game {
    private record ServerSession(IntegratedServer server, UUID player) { }
    private record PendingScreenshot(boolean previousHideGui, CompletableFuture<NativeImage> image) { }
    private final Minecraft client;
    private final AssistantRuntime runtime;
    private final Path settingsPath;
    private final Function<String, JsonObject> openScreen;
    private final DebugRequestLog timing;
    private final int clientCallTimeoutMillis;
    private final Object conversationLock = new Object();
    private final AtomicBoolean quitQueued = new AtomicBoolean();
    private PendingScreenshot screenshot;

    public MinecraftDebugAdapter(Minecraft client, AssistantRuntime runtime, Path settingsPath, Function<String, JsonObject> openScreen,
                                 DebugRequestLog timing) {
        this(client, runtime, settingsPath, openScreen, timing, 10_000);
    }

    MinecraftDebugAdapter(Minecraft client, AssistantRuntime runtime, Path settingsPath, Function<String, JsonObject> openScreen,
                          DebugRequestLog timing, int clientCallTimeoutMillis) {
        this.client = client;
        this.runtime = runtime;
        this.openScreen = openScreen;
        this.timing = timing;
        this.settingsPath = settingsPath;
        if (clientCallTimeoutMillis < 1) throw new IllegalArgumentException("client call timeout must be positive");
        this.clientCallTimeoutMillis = clientCallTimeoutMillis;
    }

    @Override
    public JsonObject execute(DebugRouter.Operation operation, JsonObject body) throws Exception {
        String bridgeRequest = UUID.randomUUID().toString();
        long bridgeStartNs = System.nanoTime();
        PackSageLog.LOGGER.info("PackSage timing request={} phase=bridge_request operation={} start_ns={} event_ns={} elapsed_ms=0",
                bridgeRequest, operation.name().toLowerCase(java.util.Locale.ROOT), bridgeStartNs, bridgeStartNs);
        try {
            JsonObject response = switch (operation) {
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
                                if (body.has("openInventory") && body.get("openInventory").getAsBoolean()) {
                                    if (!(entity instanceof AbstractHorse horse)) throw new IllegalArgumentException("Entity does not have a horse inventory");
                                    horse.openCustomInventoryScreen(player);
                                    if (player.containerMenu == player.inventoryMenu) throw new IllegalStateException("Horse inventory could not be opened");
                                    json.addProperty("interaction", "SUCCESS");
                                } else {
                                    var location = new Vec3(0, entity.getBbHeight() / 2.0, 0);
                                    var interaction = net.neoforged.neoforge.common.CommonHooks.onInteractEntityAt(player, entity, location, InteractionHand.MAIN_HAND);
                                    if (interaction == null) interaction = entity.interactAt(player, location, InteractionHand.MAIN_HAND);
                                    if (!interaction.consumesAction()) interaction = player.interactOn(entity, InteractionHand.MAIN_HAND);
                                    json.addProperty("interaction", interaction.name());
                                }
                            } else {
                                var pos = new BlockPos(body.get("x").getAsInt(), body.get("y").getAsInt(), body.get("z").getAsInt());
                                json.addProperty("interaction", player.gameMode.useItemOn(player, player.serverLevel(), player.getMainHandItem(),
                                        InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)).name());
                            }
                            json.addProperty("menuId", player.containerMenu.containerId);
                            json.addProperty("opened", player.containerMenu != player.inventoryMenu);
                            json.addProperty("menuType", menuType(player.containerMenu));
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
                String text = body.get("text").isJsonNull() ? null : body.get("text").getAsString();
                String prompt = dev.packsage.model.GroundingInstructions.defaultInstructions().equals(text) ? null : text;
                if (prompt != null && (prompt.isBlank() || prompt.length() > PackSageSettings.MAX_DEVELOPER_PROMPT_LENGTH)) {
                    throw new IllegalArgumentException("developer prompt is out of bounds");
                }
                var saved = call(client, () -> {
                    var settings = runtime.settings();
                    if (!settings.setDeveloperPrompt(prompt)) throw new IllegalArgumentException("developer prompt is out of bounds");
                    return settings.save(settingsPath);
                });
                saved.get(10, TimeUnit.SECONDS);
                yield execute(DebugRouter.Operation.PROMPT_GET, body);
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
            case CHAT_SCREEN -> {
                if (body.get("open").getAsBoolean()) {
                    var screenBody = new JsonObject();
                    screenBody.addProperty("screen", "chat");
                    if (body.has("conversationId")) screenBody.add("conversationId", body.get("conversationId").deepCopy());
                    yield execute(DebugRouter.Operation.SCREEN_OPEN, screenBody);
                }
                yield call(client, () -> {
                    if (client.screen instanceof AssistantScreen) client.setScreen(null);
                    var json = new JsonObject();
                    json.addProperty("open", client.screen instanceof AssistantScreen);
                    return json;
                });
            }
            case WORLD_LEAVE -> {
                call(client, () -> {
                    if (client.level == null && client.getConnection() == null && client.screen instanceof TitleScreen) return null;
                    if (client.level != null) {
                        boolean local = client.isLocalServer();
                        client.level.disconnect();
                        if (local) client.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
                        else client.disconnect();
                    } else if (client.getConnection() != null) client.disconnect();
                    client.setScreen(new TitleScreen());
                    return null;
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!call(client, () -> client.level == null && client.screen instanceof TitleScreen)) {
                    if (System.nanoTime() >= deadline) throw new TimeoutException("Title screen transition timeout");
                    Thread.sleep(10);
                }
                var json = new JsonObject();
                json.addProperty("screen", "title");
                json.addProperty("world", (String) null);
                yield json;
            }
            case WORLD_OPEN -> {
                String save = body.get("save").getAsString();
                var candidates = call(client, () -> {
                    try { return client.getLevelSource().findLevelCandidates(); }
                    catch (Exception failure) { throw new IllegalStateException("Could not list saves", failure); }
                });
                var summaries = call(client, () -> client.getLevelSource().loadLevelSummaries(candidates))
                        .get(10, TimeUnit.SECONDS);
                var folderMatches = summaries.stream().filter(summary -> summary.getLevelId().equals(save)).toList();
                String levelId;
                if (!folderMatches.isEmpty()) levelId = folderMatches.getFirst().getLevelId();
                else {
                    var nameMatches = summaries.stream().filter(summary -> summary.getLevelName().equals(save)).toList();
                    if (nameMatches.isEmpty()) throw new IllegalArgumentException("Save not found");
                    if (nameMatches.size() > 1) throw new IllegalArgumentException("Level name is ambiguous; use its save folder");
                    levelId = nameMatches.getFirst().getLevelId();
                }
                boolean alreadyOpen = call(client, () -> {
                    if (client.level == null) return false;
                    var server = client.getSingleplayerServer();
                    if (server != null && server.getWorldPath(LevelResource.ROOT).getFileName().toString().equals(levelId)) return true;
                    throw new IllegalStateException("Return to the title screen before opening a save");
                });
                if (!alreadyOpen) call(client, () -> {
                    if (!(client.screen instanceof TitleScreen)) throw new IllegalStateException("Title screen required");
                    client.createWorldOpenFlows().openWorld(levelId, () -> client.setScreen(new TitleScreen()));
                    return null;
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (!call(client, () -> client.level != null && client.player != null && client.isLocalServer())) {
                    if (System.nanoTime() >= deadline) throw new TimeoutException("World load timeout");
                    Thread.sleep(10);
                }
                var json = new JsonObject();
                json.addProperty("opened", true);
                json.addProperty("save", levelId);
                yield json;
            }
            case SERVER_CONNECT -> {
                String address = body.get("address").getAsString();
                if (!ServerAddress.isValidAddress(address)) throw new IllegalArgumentException("Invalid server address");
                var parsedAddress = ServerAddress.parseString(address);
                boolean alreadyConnected = call(client, () -> {
                    if (client.level == null) return false;
                    var current = client.getCurrentServer();
                    if (current != null && !client.isLocalServer()
                            && ServerAddress.parseString(current.ip).toString().equalsIgnoreCase(parsedAddress.toString())) return true;
                    throw new IllegalStateException("Disconnect before connecting to another server");
                });
                if (!alreadyConnected) call(client, () -> {
                    if (!(client.screen instanceof TitleScreen)) throw new IllegalStateException("Title screen required");
                    var server = new ServerData("PackSage debug", parsedAddress.toString(), ServerData.Type.OTHER);
                    ConnectScreen.startConnecting(new TitleScreen(), client, parsedAddress, server, false, null);
                    return null;
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (!call(client, () -> client.level != null && client.player != null && !client.isLocalServer())) {
                    if (System.nanoTime() >= deadline) throw new TimeoutException("Server connection timeout");
                    Thread.sleep(10);
                }
                var json = new JsonObject();
                json.addProperty("connected", true);
                json.addProperty("address", parsedAddress.toString());
                yield json;
            }
            case SCREEN_OPEN -> {
                String screen = body.get("screen").getAsString();
                if (!screen.equals("chat")) yield call(client, () -> {
                    if (client.level == null || !runtime.sessionReady()) throw new IllegalStateException("World history is not ready");
                    return openScreen.apply(screen);
                });
                synchronized (conversationLock) {
                    var world = call(client, () -> {
                        var currentWorld = runtime.view().activeWorld();
                        if (client.level == null) throw new IllegalStateException("World history is not ready");
                        return currentWorld;
                    });
                    ChatContracts.SessionSummary selected = null;
                    if (body.has("conversationId")) {
                        String conversation = body.get("conversationId").getAsString();
                        long selectionStartNs = System.nanoTime();
                        selected = runtime.sessionSummary(world, conversation).toCompletableFuture().get(10, TimeUnit.SECONDS);
                        long selectionEventNs = System.nanoTime();
                        PackSageLog.LOGGER.info("PackSage timing request={} phase=bridge_selection conversation={} start_ns={} event_ns={} elapsed_ms={} found={}",
                                bridgeRequest, conversation, selectionStartNs, selectionEventNs,
                                TimeUnit.NANOSECONDS.toMillis(selectionEventNs - selectionStartNs), selected != null);
                        if (selected == null) throw new IllegalArgumentException("Conversation not found in this world");
                    }
                    var chosen = selected;
                    yield call(client, () -> {
                        if (!world.equals(runtime.view().activeWorld())) {
                            throw new IllegalStateException("Conversation selection changed");
                        }
                        if (chosen != null) runtime.openSession(chosen);
                        var json = openScreen.apply(screen);
                        json.addProperty("open", client.screen instanceof AssistantScreen);
                        if (chosen != null) json.addProperty("conversationId", chosen.id());
                        long eventNs = System.nanoTime();
                        PackSageLog.LOGGER.info("PackSage timing request={} phase=bridge_readiness conversation={} ready={} start_ns={} event_ns={} elapsed_ms={}",
                                bridgeRequest, chosen == null ? "none" : chosen.id(), runtime.sessionReady(), bridgeStartNs, eventNs,
                                TimeUnit.NANOSECONDS.toMillis(eventNs - bridgeStartNs));
                        return json;
                    });
                }
            }
            case RESOURCEPACKS -> {
                var reload = call(client, () -> {
                    var repository = client.getResourcePackRepository();
                    // Rescan so packs added since startup can be selected; the current selection is kept.
                    repository.reload();
                    var available = repository.getAvailableIds();
                    var enabled = new ArrayList<String>();
                    var disabled = new ArrayList<String>();
                    for (var id : body.getAsJsonArray("enable")) {
                        String pack = id.getAsString();
                        if (!available.contains(pack)) throw new IllegalArgumentException("Resource pack not found: " + pack);
                        enabled.add(pack);
                    }
                    for (var id : body.getAsJsonArray("disable")) {
                        String pack = id.getAsString();
                        if (!available.contains(pack)) throw new IllegalArgumentException("Resource pack not found: " + pack);
                        disabled.add(pack);
                    }
                    var selected = new LinkedHashSet<>(repository.getSelectedIds());
                    var original = new LinkedHashSet<>(selected);
                    selected.removeAll(disabled);
                    selected.addAll(enabled);
                    if (selected.equals(original)) return null;
                    repository.setSelected(selected);
                    client.options.updateResourcePacks(repository);
                    return client.reloadResourcePacks();
                });
                if (reload != null) reload.get(10, TimeUnit.SECONDS);
                yield call(client, () -> {
                    var json = new JsonObject();
                    json.add("selected", DebugChatState.GSON.toJsonTree(client.getResourcePackRepository().getSelectedIds()));
                    json.addProperty("reloaded", reload != null);
                    return json;
                });
            }
            case GAME_QUIT -> {
                var json = new JsonObject();
                json.addProperty("stopping", true);
                yield json;
            }
            case COMMANDS_PENDING -> call(client, () -> {
                var pending = new com.google.gson.JsonArray();
                for (var confirmation : runtime.commandConfirmations()) {
                    var entry = new JsonObject();
                    entry.addProperty("id", confirmation.id());
                    entry.addProperty("requestId", confirmation.requestId());
                    entry.addProperty("command", confirmation.command());
                    pending.add(entry);
                }
                var json = new JsonObject();
                json.add("pending", pending);
                return json;
            });
            case COMMANDS_DECIDE -> call(client, () -> {
                String id = body.get("id").getAsString();
                boolean known = runtime.commandConfirmations().stream().anyMatch(value -> value.id().equals(id));
                if (!known) throw new IllegalArgumentException("No pending command confirmation with that id");
                runtime.confirmCommand(id, body.get("run").getAsBoolean());
                var json = new JsonObject();
                json.addProperty("decided", true);
                return json;
            });
            case SETTINGS -> {
                call(client, () -> {
                    var settings = runtime.settings();
                    if (body.has("commandConfirmation")) settings.setCommandConfirmation(PackSageSettings.CommandConfirmation.valueOf(
                            body.get("commandConfirmation").getAsString().toUpperCase(java.util.Locale.ROOT)));
                    if (body.has("verbosity")) settings.setVerbosity(PackSageSettings.Verbosity.valueOf(
                            body.get("verbosity").getAsString().toUpperCase(java.util.Locale.ROOT)));
                    if (body.has("showChatsFromOtherSessions")) settings.setShowChatsFromOtherSessions(body.get("showChatsFromOtherSessions").getAsBoolean());
                    return settings.save(settingsPath);
                }).get(10, TimeUnit.SECONDS);
                yield call(client, () -> {
                    var settings = runtime.settings();
                    var json = new JsonObject();
                    json.addProperty("commandConfirmation", settings.commandConfirmation().name().toLowerCase(java.util.Locale.ROOT));
                    json.addProperty("verbosity", settings.verbosity().name().toLowerCase(java.util.Locale.ROOT));
                    json.addProperty("showChatsFromOtherSessions", settings.showChatsFromOtherSessions());
                    return json;
                });
            }
            };
            long responseEventNs = System.nanoTime();
            PackSageLog.LOGGER.info("PackSage timing request={} phase=bridge_response operation={} start_ns={} event_ns={} elapsed_ms={} success=true",
                    bridgeRequest, operation.name().toLowerCase(java.util.Locale.ROOT), bridgeStartNs, responseEventNs,
                    TimeUnit.NANOSECONDS.toMillis(responseEventNs - bridgeStartNs));
            return response;
        } catch (Exception | Error failure) {
            long responseEventNs = System.nanoTime();
            PackSageLog.LOGGER.info("PackSage timing request={} phase=bridge_response operation={} start_ns={} event_ns={} elapsed_ms={} failureType={}",
                    bridgeRequest, operation.name().toLowerCase(java.util.Locale.ROOT), bridgeStartNs, responseEventNs,
                    TimeUnit.NANOSECONDS.toMillis(responseEventNs - bridgeStartNs), failure.getClass().getSimpleName());
            throw failure;
        }
    }

    @Override
    public void afterResponse(DebugRouter.Operation operation) {
        if (operation == DebugRouter.Operation.GAME_QUIT && quitQueued.compareAndSet(false, true)) client.execute(client::stop);
    }

    /** The registered menu type, or the class name for menus such as the player and horse inventories that have none. */
    static String menuType(net.minecraft.world.inventory.AbstractContainerMenu menu) {
        try {
            return BuiltInRegistries.MENU.getKey(menu.getType()).toString();
        } catch (UnsupportedOperationException untyped) {
            return menu.getClass().getSimpleName();
        }
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

    private <T> T call(Executor executor, Supplier<T> action) throws Exception {
        var result = new CompletableFuture<T>();
        var claimed = new AtomicBoolean();
        try {
            executor.execute(() -> {
                if (!claimed.compareAndSet(false, true) || result.isDone()) return;
                try { result.complete(action.get()); }
                catch (Throwable failure) { result.completeExceptionally(failure); }
            });
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            throw new IllegalStateException("Game executor is unavailable", exception);
        }
        try {
            return result.get(executor == client ? clientCallTimeoutMillis : 10_000, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException exception) {
            if (claimed.compareAndSet(false, true)) result.cancel(false);
            throw exception;
        }
    }
}
