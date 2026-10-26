package dev.packsage.client;

import com.google.gson.JsonObject;
import dev.packsage.chat.AssistantRuntime;
import dev.packsage.chat.ChatContracts;
import dev.packsage.chat.ConversationStore;
import dev.packsage.chat.PackSageSettings;
import dev.packsage.client.highlight.WorldHighlighter;
import dev.packsage.debug.DebugBridge;
import dev.packsage.debug.DebugRequestLog;
import dev.packsage.debug.MinecraftDebugAdapter;
import dev.packsage.journal.Journal;
import dev.packsage.journal.JournalHooks;
import dev.packsage.journal.JournalTools;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.knowledge.KnowledgeService;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.knowledge.config.ConfigProvider;
import dev.packsage.knowledge.nativegame.NativeProvider;
import dev.packsage.model.GroundingInstructions;
import dev.packsage.model.ModelBackend;
import dev.packsage.model.chatgpt.ChatGptAuth;
import dev.packsage.model.chatgpt.ChatGptBackend;
import dev.packsage.world.PausedWorkPump;
import dev.packsage.world.ProgressTools;
import dev.packsage.world.ServerWork;
import dev.packsage.world.WorldRegistry;
import dev.packsage.world.WorldTools;
import dev.packsage.world.minecraft.MinecraftEntities;
import dev.packsage.world.minecraft.MinecraftInventories;
import dev.packsage.world.minecraft.MinecraftProgress;
import dev.packsage.world.minecraft.MinecraftStatistics;
import dev.packsage.world.minecraft.MinecraftWorld;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RecipesUpdatedEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public final class ClientAssistant {
    public record CopiedWorld(String source, String readableLabel, boolean privateSingleplayer,
                              String serverConfigPath) {
        public CopiedWorld {
            Objects.requireNonNull(source);
            Objects.requireNonNull(readableLabel);
        }
    }

    private record PendingInitialization(CopiedWorld world, String playerUuid, UUID sessionId, int levelToken, int sessionEpoch, long resourcesEpoch,
                                         String locale, long providerRuntimeEpoch) {
    }

    private record InitializationResult(ChatContracts.WorldIdentity world, Evidence.Scope scope) {
    }

    private final Minecraft client;
    private final Executor clientExecutor;
    private final ExecutorService workerExecutor;
    private final Journal journal;
    private final KeyMapping openAssistant;
    private final Path settingsPath;
    private final PackSageSettings settings;
    private final AssistantRuntime runtime;
    private final dev.packsage.world.CommandRunner commandRunner;
    private final ServerWork serverWork = new ServerWork(System::nanoTime);
    private final PausedWorkPump pausedWorkPump = new PausedWorkPump(serverWork);
    private AssistantScreen assistantScreen;
    private boolean settingsOpen;
    private CopiedWorld activeWorld;
    private ChatContracts.WorldIdentity activeIdentity;
    private String activePlayerUuid;
    private Evidence.Scope activeScope;
    private PendingInitialization pendingInitialization;
    private int levelToken;
    private int sessionEpoch;
    private UUID sessionId = UUID.randomUUID();
    private long resourcesEpoch;
    private String locale;
    private long providerRuntimeEpoch;
    private DebugBridge debugBridge;
    private MinecraftDebugAdapter debugAdapter;
    private DebugRequestLog debugTiming;
    private boolean previousPauseOnLostFocus;

    public ClientAssistant(KeyMapping openAssistant) {
        this.openAssistant = openAssistant;
        client = Minecraft.getInstance();
        clientExecutor = client::execute;
        workerExecutor = Executors.newFixedThreadPool(2, runnable -> {
            var thread = new Thread(runnable, "packsage-worker");
            thread.setDaemon(true);
            return thread;
        });
        // Initialize the text tokenizer during client startup instead of blocking the first chat frame.
        workerExecutor.execute(() -> GroundingInstructions.historyTokenCounts(new ChatContracts.Turn(
                new ChatContracts.RequestId("tokenizer-warmup"), "", "", List.of(),
                new ChatContracts.Context("", null, new Evidence.Scope("none", "none", "none", "none", 0)), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 0)));
        journal = new Journal(workerExecutor);
        new JournalHooks(journal, client::getSingleplayerServer).register(NeoForge.EVENT_BUS);
        locale = client.getLanguageManager().getSelected();
        settingsPath = client.gameDirectory.toPath().resolve("config").resolve("packsage.properties");
        settings = PackSageSettings.load(settingsPath);
        var backend = new ChatGptBackend(new ChatGptAuth(ChatGptAuth.defaultCredentialFile()));
        runtime = new AssistantRuntime(
                new ConversationStore(client.gameDirectory.toPath().resolve("packsage"), workerExecutor,
                        Executors.newSingleThreadExecutor(runnable -> {
                            var thread = new Thread(runnable, "packsage-chat-catalog");
                            thread.setDaemon(true);
                            return thread;
                        })), backend,
                scope -> GameplayContextCapture.forMinecraft(clientExecutor, scope), workerExecutor, clientExecutor,
                this::knowledge, GroundingInstructions::historyTextBudget, GroundingInstructions::build,
                model -> model.outputTokens() == null ? 4096 : Math.min(4096, model.outputTokens()),
                ignored -> { }, this::openExternal, ignored -> {
                    if (client.level == null || client.player == null || client.screen instanceof AssistantScreen) return;
                    client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f));
                    SystemToast.add(client.getToasts(), new SystemToast.SystemToastId(5000L), Component.literal("PackSage"),
                            Component.literal("Answer ready. Press " + openAssistant.getTranslatedKeyMessage().getString()
                                    + " to open chat."));
                });
        runtime.setSettings(settings);
        commandRunner = dev.packsage.world.CommandRunner.forMinecraft(client, settings);
        runtime.setCommandRunner(commandRunner);
        runtime.restoreSession();

        WorldHighlighter.register();
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post ignored) -> tick());
        NeoForge.EVENT_BUS.addListener((ScreenEvent.KeyPressed.Pre event) -> {
            if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen) || client.level == null
                    || client.player == null || activeScope == null
                    || !openAssistant.matches(event.getKeyCode(), event.getScanCode())) return;
            if (screen.getFocused() instanceof EditBox && (event.getKeyCode() >= org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE
                    && event.getKeyCode() <= org.lwjgl.glfw.GLFW.GLFW_KEY_GRAVE_ACCENT
                    || event.getKeyCode() >= org.lwjgl.glfw.GLFW.GLFW_KEY_KP_0
                    && event.getKeyCode() <= org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ADD
                    || event.getKeyCode() == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_EQUAL)) return;
            event.setCanceled(true);
            openAssistantScreen(screen);
        });
        NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Post ignored) -> runtime.onRenderGuiPost());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn ignored) -> loggingIn());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut ignored) -> loggingOut());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.Clone ignored) -> playerReplaced());
        NeoForge.EVENT_BUS.addListener((LevelEvent.Load event) -> levelLoaded(event));
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> levelUnloaded(event));
        NeoForge.EVENT_BUS.addListener((RecipesUpdatedEvent ignored) -> invalidateEvidence());
        NeoForge.EVENT_BUS.addListener((TagsUpdatedEvent ignored) -> invalidateEvidence());
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post ignored) -> serverWork.tick());
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent ignored) -> {
            serverWork.closeAll();
            pausedWorkPump.reset();
        });
        if (DebugBridge.enabled(settings)) {
            debugTiming = new DebugRequestLog();
            debugAdapter = new MinecraftDebugAdapter(client, runtime, settingsPath, screen -> {
                switch (screen) {
                    case "chat" -> openAssistantScreen(null);
                    case "settings" -> { openAssistantScreen(null); openSettings(false); }
                    case "adapters" -> openAssistantScreen(null);
                    default -> throw new IllegalArgumentException("Unknown PackSage screen");
                }
                var json = new JsonObject();
                json.addProperty("screen", screen);
                json.addProperty("open", screen.equals("settings") ? client.screen instanceof AssistantSettingsScreen
                        : client.screen instanceof AssistantScreen);
                if (screen.equals("adapters")) {
                    var layout = assistantScreen.openAdapterPopup();
                    json.addProperty("width", layout.width());
                    json.addProperty("scrollbar", layout.scrollbar());
                }
                return json;
            }, debugTiming);
            debugBridge = new DebugBridge(client.gameDirectory.toPath(), settings.debugBridgePort(), debugAdapter,
                    message -> dev.packsage.PackSageLog.LOGGER.info(message));
            if (debugBridge.start()) {
                previousPauseOnLostFocus = client.options.pauseOnLostFocus;
                debugTiming.start();
                NeoForge.EVENT_BUS.addListener((ScreenEvent.Opening ignored) -> {
                    client.options.pauseOnLostFocus = previousPauseOnLostFocus;
                });
                NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.RenderFrameEvent.Post ignored) -> debugAdapter.onRenderFrame());
                NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.GameShuttingDownEvent ignored) -> {
                    debugBridge.close();
                    debugAdapter.close();
                    debugTiming.close();
                    client.options.pauseOnLostFocus = previousPauseOnLostFocus;
                    client.options.save();
                });
            }
        }
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.GameShuttingDownEvent ignored) -> {
            try { settings.flushAndClose(settingsPath).get(3, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                dev.packsage.PackSageLog.LOGGER.error("Could not flush PackSage settings during shutdown", failure);
            }
            catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
                dev.packsage.PackSageLog.LOGGER.error("Could not flush PackSage settings during shutdown", failure);
            }
        });
    }

    private void openAssistantScreen(AbstractContainerScreen<?> containerScreen) {
        assistantScreen = runtime.openScreen(() -> openSettings(false));
        assistantScreen.setContainerScreen(containerScreen);
        assistantScreen.setAdvancedSettingsOpener(() -> openSettings(true));
        assistantScreen.setSettingsSaver(() -> settings.save(settingsPath));
        client.setScreen(assistantScreen);
    }

    /** Lets a paused integrated server, which skips ServerTickEvent, keep stepping pending world reads. */
    private void tickPausedServerWork() {
        var server = client.getSingleplayerServer();
        if (server == null) return;
        pausedWorkPump.poll(new PausedWorkPump.Server() {
            @Override public Object identity() { return server; }
            @Override public boolean isPaused() { return server.isPaused(); }
            @Override public boolean isStopped() { return server.isStopped(); }
            @Override public boolean isSameThread() { return server.isSameThread(); }
            @Override public void execute(Runnable task) { server.execute(task); }
        });
    }

    public void resourceReloaded() {
        resourcesEpoch++;
        locale = client.getLanguageManager().getSelected();
        invalidateEvidence();
    }

    static ChatContracts.WorldIdentity identityFor(CopiedWorld world) {
        return new ChatContracts.WorldIdentity("world-" + digest("world:" + world.source()), world.readableLabel());
    }

    static Evidence.Scope scopeFor(String worldId, UUID session, long resourcesEpoch, String locale,
                                   String playerUuid, long providerRuntimeEpoch) {
        String player = playerUuid == null || playerUuid.isBlank() ? "unknown" : playerUuid;
        return new Evidence.Scope(worldId + ":" + session, resourcesEpoch + ":" + locale,
                player, "runtime-" + providerRuntimeEpoch, 0);
    }

    private void tick() {
        if (!client.isSameThread()) return;
        commandRunner.tick();
        if (debugTiming != null && debugTiming.isStarted()) {
            client.options.pauseOnLostFocus = client.screen != null && !(client.screen instanceof AssistantScreen) && previousPauseOnLostFocus;
        }
        tickPausedServerWork();
        if (settingsOpen && client.screen == null && assistantScreen != null) {
            settingsOpen = false;
            client.setScreen(assistantScreen);
        }
        while (openAssistant.consumeClick()) {
            if (client.level == null || client.screen != null) continue;
            openAssistantScreen(null);
        }
        if (client.level == null) {
            if (activeWorld != null || pendingInitialization != null) resetSession();
            return;
        }
        CopiedWorld copied = copyWorld();
        if (copied == null) return;
        String playerUuid = copyPlayerUuid();
        if (activeWorld == null || !activeWorld.source().equals(copied.source())
                || !Objects.equals(activePlayerUuid, playerUuid)) {
            beginInitialization(copied, playerUuid);
        } else if (pendingInitialization == null && !activeWorld.equals(copied)) {
            // Same world, now open or closed to LAN: keep the session and its history.
            activeWorld = copied;
        } else if (pendingInitialization != null
                && (pendingInitialization.levelToken() != levelToken
                || pendingInitialization.sessionEpoch() != sessionEpoch)) {
            beginInitialization(copied, playerUuid);
        }
    }

    private void beginInitialization(CopiedWorld copied, String playerUuid) {
        if (pendingInitialization != null
                && pendingInitialization.levelToken() == levelToken
                && pendingInitialization.sessionEpoch() == sessionEpoch
                && pendingInitialization.world().source().equals(copied.source())
                && Objects.equals(pendingInitialization.playerUuid(), playerUuid)) return;
        if (activeWorld != null || pendingInitialization != null) resetSession();
        long capturedResourcesEpoch = resourcesEpoch;
        String capturedLocale = locale;
        long capturedProviderRuntimeEpoch = providerRuntimeEpoch;
        PendingInitialization pending = new PendingInitialization(copied, playerUuid,
                sessionId, levelToken, sessionEpoch, capturedResourcesEpoch, capturedLocale,
                capturedProviderRuntimeEpoch);
        pendingInitialization = pending;
        CompletableFuture.supplyAsync(() -> {
                    ChatContracts.WorldIdentity identity = identityFor(copied);
                    return new InitializationResult(identity,
                            scopeFor(identity.worldOrServer(), pending.sessionId(), capturedResourcesEpoch, capturedLocale,
                                    playerUuid, capturedProviderRuntimeEpoch));
                }, workerExecutor)
                .whenComplete((result, failure) -> {
                    try {
                        client.execute(() -> finishInitialization(pending, result, failure));
                    } catch (RuntimeException ignored) {
                    }
                });
    }

    private void finishInitialization(PendingInitialization pending, InitializationResult result, Throwable failure) {
        if (pendingInitialization != pending || pending.levelToken() != levelToken
                || pending.sessionEpoch() != sessionEpoch || client.level == null) return;
        if (failure != null || result == null) {
            pendingInitialization = null;
            return;
        }
        if (pending.resourcesEpoch() != resourcesEpoch || !Objects.equals(pending.locale(), locale)
                || pending.providerRuntimeEpoch() != providerRuntimeEpoch) {
            pendingInitialization = null;
            return;
        }
        CopiedWorld copied = copyWorld();
        if (copied == null || !copied.source().equals(pending.world().source())) return;
        pendingInitialization = null;
        activeWorld = copied;
        activeIdentity = result.world();
        activePlayerUuid = pending.playerUuid();
        activeScope = result.scope();
        runtime.enterWorld(activeIdentity, activeScope);
    }

    private void loggingIn() {
        if (!client.isSameThread()) return;
        resetSession();
        if (client.level != null) tick();
    }

    private void loggingOut() {
        if (!client.isSameThread()) return;
        resetSession();
    }

    private void playerReplaced() {
        if (!client.isSameThread()) return;
        invalidateEvidence();
    }

    private void levelLoaded(LevelEvent event) {
        if (!client.isSameThread() || !(event.getLevel() instanceof ClientLevel)) return;
        resetSession();
        tick();
    }

    private void levelUnloaded(LevelEvent event) {
        if (!client.isSameThread() || !(event.getLevel() instanceof ClientLevel)) return;
        resetSession();
    }

    private void resetSession() {
        levelToken++;
        sessionEpoch++;
        sessionId = UUID.randomUUID();
        providerRuntimeEpoch++;
        pendingInitialization = null;
        activeWorld = null;
        activeIdentity = null;
        activePlayerUuid = null;
        activeScope = null;
        settingsOpen = false;
        if (client.screen == assistantScreen) {
            client.setScreen(null);
            assistantScreen = null;
        }
        runtime.disconnect();
    }

    private void invalidateEvidence() {
        if (!client.isSameThread() || activeIdentity == null || activeWorld == null) return;
        providerRuntimeEpoch++;
        activeScope = scopeFor(activeIdentity.worldOrServer(), sessionId, resourcesEpoch, locale,
                activePlayerUuid, providerRuntimeEpoch);
        runtime.invalidate(activeScope);
    }

    private CopiedWorld copyWorld() {
        if (client.level == null) return null;
        IntegratedServer integrated = client.getSingleplayerServer();
        // Opening to LAN publishes the same save, so its identity, conversations and saved links stay with the save.
        if (integrated != null && client.hasSingleplayerServer()) {
            try {
                return integratedWorld(integrated.getWorldPath(LevelResource.ROOT), integrated.isPublished());
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        ServerData server = client.getCurrentServer();
        String endpoint = server == null || server.ip == null || server.ip.isBlank() ? null : server.ip;
        if (endpoint == null && client.getConnection() != null && client.getConnection().getConnection() != null
                && client.getConnection().getConnection().getRemoteAddress() != null) {
            endpoint = client.getConnection().getConnection().getRemoteAddress().toString();
        }
        endpoint = endpoint == null || endpoint.isBlank() ? "unknown" : endpoint;
        try { endpoint = ServerAddress.parseString(endpoint).toString(); } catch (RuntimeException ignored) { }
        String type = server == null || server.type() == null ? "remote" : server.type().name().toLowerCase();
        String label = server == null || server.name == null || server.name.isBlank() ? "Remote " + type : server.name;
        return new CopiedWorld("remote:" + type + ":" + endpoint, label, false, null);
    }

    /** The local save's identity, the same whether or not it is open to LAN. */
    static CopiedWorld integratedWorld(Path worldRoot, boolean published) {
        Path root = worldRoot.toAbsolutePath().normalize();
        Path name = root.getFileName();
        return new CopiedWorld("private:" + root, name == null ? "Singleplayer" : name.toString(), !published,
                root.resolve("serverconfig").toString());
    }

    private String copyPlayerUuid() {
        return client.player == null ? "unavailable" : client.player.getUUID().toString();
    }

    private List<Path> configRoots() {
        Path instance = client.gameDirectory.toPath().toAbsolutePath().normalize();
        var roots = new ArrayList<Path>();
        roots.add(instance.resolve("config"));
        Path defaults = instance.resolve("defaultconfigs");
        if (Files.isDirectory(defaults)) roots.add(defaults);
        if (activeWorld != null && activeWorld.privateSingleplayer() && activeWorld.serverConfigPath() != null) {
            Path serverConfig = Path.of(activeWorld.serverConfigPath());
            if (Files.isDirectory(serverConfig)) roots.add(serverConfig);
        }
        return List.copyOf(roots);
    }

    private KnowledgeService knowledge(Supplier<Evidence.Scope> scope) {
        var providers = new ArrayList<KnowledgeProvider>();
        providers.add(new NativeProvider(client, workerExecutor, scope));
        providers.add(ConfigProvider.production(configRoots(), clientExecutor, workerExecutor, client::isSameThread));
        var world = new WorldRegistry();
        world.register(new MinecraftInventories(() -> client.getSingleplayerServer().registryAccess()));
        world.register(new MinecraftWorld());
        world.register(new MinecraftEntities());
        world.register(new MinecraftProgress());
        world.register(new MinecraftStatistics());
        var worldTools = new WorldTools(client, world, scope, workerExecutor, serverWork,
                () -> client.submit(() -> GameplayContextCapture.focus(client)), id -> {
                    var notes = journal.notes();
                    if (!notes.loaded()) throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, journal.unavailableReason());
                    return notes.place(id);
                });
        providers.add(worldTools);
        providers.add(new ProgressTools(client, world, scope, workerExecutor, serverWork));
        providers.add(new JournalTools(journal, scope, workerExecutor, client::getSingleplayerServer,
                worldTools::resolveAnchor));
        var knowledge = new KnowledgeService(providers, scope);
        knowledge.register(new GameStateReader(client));
        return knowledge;
    }

    private void openSettings(boolean advanced) {
        settingsOpen = true;
        if (advanced) {
            client.setScreen(new DeveloperSettingsScreen(settings, settingsPath, runtime, () -> {
                settingsOpen = false;
                if (assistantScreen != null) client.setScreen(assistantScreen);
            }));
            return;
        }
        client.setScreen(new AssistantSettingsScreen(settings, settingsPath, Component.literal("PackSage Settings"),
                runtime, () -> {
                    settingsOpen = false;
                    if (assistantScreen != null) client.setScreen(assistantScreen);
                }));
    }

    private void openExternal(String value) {
        try {
            URI uri = new URI(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null) return;
            if (client.screen != null) ConfirmLinkScreen.confirmLinkNow(client.screen, uri, true);
        } catch (Exception ignored) {
        }
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            var result = new StringBuilder(bytes.length * 2);
            for (byte byteValue : bytes) result.append(String.format("%02x", byteValue));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
