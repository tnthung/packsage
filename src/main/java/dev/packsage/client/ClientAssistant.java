package dev.packsage.client;

import dev.packsage.PackSageLog;
import dev.packsage.chat.AssistantRuntime;
import dev.packsage.chat.ChatContracts;
import dev.packsage.chat.ConversationStore;
import dev.packsage.chat.PackSageSettings;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.knowledge.KnowledgeService;
import dev.packsage.knowledge.config.ConfigProvider;
import dev.packsage.knowledge.nativegame.NativeProvider;
import dev.packsage.model.GroundingInstructions;
import dev.packsage.model.ModelBackend;
import dev.packsage.model.chatgpt.ChatGptAuth;
import dev.packsage.model.chatgpt.ChatGptBackend;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RecipesUpdatedEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.minecraft.client.multiplayer.ClientLevel;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;

public final class ClientAssistant {
    private static final String JEI_CLASS = "dev.packsage.knowledge.jei.PackSageJeiPlugin";
    private static final String FTB_CLASS = "dev.packsage.knowledge.ftb.FtbProvider";
    private static final String PATCHOULI_CLASS = "dev.packsage.knowledge.patchouli.PatchouliProvider";
    private static final String JEI_VERSION = "19.27.0.340";
    private static final String FTB_VERSION = "2101.1.36";
    private static final String PATCHOULI_VERSION = "1.21.1-92";
    private static final String LOADER_VERSION_SUFFIX = "-NEOFORGE";

    public record CopiedWorld(String source, String readableLabel, boolean privateSingleplayer,
                              String serverConfigPath) {
        public CopiedWorld {
            Objects.requireNonNull(source);
            Objects.requireNonNull(readableLabel);
        }
    }

    private record OptionalStatus(String id, String modId, String expectedVersion, String actualVersion,
                                  boolean supported, boolean loaded, String detail) {
    }

    private record PendingInitialization(CopiedWorld world, String playerUuid, String ftbScopeToken,
                                         UUID sessionId, int levelToken, int sessionEpoch, long resourcesEpoch,
                                         String locale, long providerRuntimeEpoch) {
    }

    private record InitializationResult(ChatContracts.WorldIdentity world, Evidence.Scope scope) {
    }

    private final Minecraft client;
    private final Executor clientExecutor;
    private final ExecutorService workerExecutor;
    private final KeyMapping openAssistant;
    private final Path settingsPath;
    private final PackSageSettings settings;
    private final Map<String, OptionalStatus> optionalStatuses;
    private final AssistantRuntime runtime;
    private AssistantScreen assistantScreen;
    private boolean settingsOpen;
    private CopiedWorld activeWorld;
    private ChatContracts.WorldIdentity activeIdentity;
    private String activePlayerUuid;
    private String activeFtbScopeToken;
    private String activeJeiRuntimeGeneration;
    private Evidence.Scope activeScope;
    private PendingInitialization pendingInitialization;
    private int levelToken;
    private int sessionEpoch;
    private UUID sessionId = UUID.randomUUID();
    private long resourcesEpoch;
    private String locale;
    private long providerRuntimeEpoch;

    public ClientAssistant(KeyMapping openAssistant) {
        this.openAssistant = openAssistant;
        client = Minecraft.getInstance();
        clientExecutor = client::execute;
        workerExecutor = Executors.newFixedThreadPool(2, runnable -> {
            var thread = new Thread(runnable, "packsage-worker");
            thread.setDaemon(true);
            return thread;
        });
        locale = client.getLanguageManager().getSelected();
        optionalStatuses = Map.of(
                "jei", optionalStatus("jei", JEI_VERSION),
                "ftb_quests", optionalStatus("ftb_quests", "ftbquests", FTB_VERSION),
                "patchouli", optionalStatus("patchouli", PATCHOULI_VERSION));
        settingsPath = client.gameDirectory.toPath().resolve("config").resolve("packsage.properties");
        settings = PackSageSettings.load(settingsPath);
        var backend = new ChatGptBackend(new ChatGptAuth(ChatGptAuth.defaultCredentialFile()));
        runtime = new AssistantRuntime(
                new ConversationStore(client.gameDirectory.toPath().resolve("packsage"), workerExecutor), backend,
                scope -> GameplayContextCapture.forMinecraft(clientExecutor, scope), workerExecutor, clientExecutor,
                this::knowledge, GroundingInstructions::historyTextBudget, GroundingInstructions::build,
                model -> model.outputTokens() == null ? 4096 : Math.min(4096, model.outputTokens()),
                ignored -> { }, this::openExternal);
        runtime.setSettings(settings);
        runtime.restoreSession();

        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post ignored) -> tick());
        NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Post ignored) -> runtime.onRenderGuiPost());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn ignored) -> loggingIn());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut ignored) -> loggingOut());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.Clone ignored) -> playerReplaced());
        NeoForge.EVENT_BUS.addListener((LevelEvent.Load event) -> levelLoaded(event));
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> levelUnloaded(event));
        NeoForge.EVENT_BUS.addListener((RecipesUpdatedEvent ignored) -> invalidateEvidence());
        NeoForge.EVENT_BUS.addListener((TagsUpdatedEvent ignored) -> invalidateEvidence());
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
                                   String playerUuid, String ftbScopeToken, long providerRuntimeEpoch) {
        String player = playerUuid == null || playerUuid.isBlank() ? "unknown" : playerUuid;
        String token = ftbScopeToken == null || ftbScopeToken.isBlank() ? "unavailable" : ftbScopeToken;
        return new Evidence.Scope(worldId + ":" + session, resourcesEpoch + ":" + locale,
                player + ":" + digest("ftb:" + token), "runtime-" + providerRuntimeEpoch, 0);
    }

    private void tick() {
        if (!client.isSameThread()) return;
        if (settingsOpen && client.screen == null && assistantScreen != null) {
            settingsOpen = false;
            client.setScreen(assistantScreen);
        }
        while (openAssistant.consumeClick()) {
            if (client.level == null || client.screen != null) continue;
            assistantScreen = runtime.openScreen(this::openSettings);
            client.setScreen(assistantScreen);
        }
        if (client.level == null) {
            if (activeWorld != null || pendingInitialization != null) resetSession();
            return;
        }
        CopiedWorld copied = copyWorld();
        if (copied == null) return;
        String playerUuid = copyPlayerUuid();
        String ftbScopeToken = copyFtbScopeToken();
        String jeiRuntimeGeneration = copyJeiRuntimeGeneration();
        boolean providerChanged = false;
        if (activeWorld != null && !Objects.equals(activeJeiRuntimeGeneration, jeiRuntimeGeneration)) {
            activeJeiRuntimeGeneration = jeiRuntimeGeneration;
            providerChanged = true;
        }
        boolean ftbScopeChanged = !Objects.equals(activeFtbScopeToken, ftbScopeToken);
        if (activeWorld != null && ftbScopeChanged) {
            activeFtbScopeToken = ftbScopeToken;
            providerChanged = true;
        }
        if (providerChanged) {
            invalidateEvidence();
        }
        if (activeWorld == null || !activeWorld.source().equals(copied.source())
                || !Objects.equals(activePlayerUuid, playerUuid)) {
            beginInitialization(copied, playerUuid, ftbScopeToken);
        } else if (pendingInitialization != null
                && (pendingInitialization.levelToken() != levelToken
                || pendingInitialization.sessionEpoch() != sessionEpoch
                || !Objects.equals(pendingInitialization.ftbScopeToken(), ftbScopeToken))) {
            beginInitialization(copied, playerUuid, ftbScopeToken);
        }
    }

    private void beginInitialization(CopiedWorld copied, String playerUuid, String ftbScopeToken) {
        if (pendingInitialization != null
                && pendingInitialization.levelToken() == levelToken
                && pendingInitialization.sessionEpoch() == sessionEpoch
                && pendingInitialization.world().source().equals(copied.source())
                && Objects.equals(pendingInitialization.playerUuid(), playerUuid)
                && Objects.equals(pendingInitialization.ftbScopeToken(), ftbScopeToken)) return;
        if (activeWorld != null || pendingInitialization != null) resetSession();
        long capturedResourcesEpoch = resourcesEpoch;
        String capturedLocale = locale;
        long capturedProviderRuntimeEpoch = providerRuntimeEpoch;
        PendingInitialization pending = new PendingInitialization(copied, playerUuid, ftbScopeToken,
                sessionId, levelToken, sessionEpoch, capturedResourcesEpoch, capturedLocale,
                capturedProviderRuntimeEpoch);
        pendingInitialization = pending;
        CompletableFuture.supplyAsync(() -> {
                    ChatContracts.WorldIdentity identity = identityFor(copied);
                    return new InitializationResult(identity,
                            scopeFor(identity.worldOrServer(), pending.sessionId(), capturedResourcesEpoch, capturedLocale,
                                    playerUuid, ftbScopeToken, capturedProviderRuntimeEpoch));
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
        activeFtbScopeToken = pending.ftbScopeToken();
        activeJeiRuntimeGeneration = copyJeiRuntimeGeneration();
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
        activeFtbScopeToken = null;
        activeJeiRuntimeGeneration = null;
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
                activePlayerUuid, activeFtbScopeToken, providerRuntimeEpoch);
        runtime.invalidate(activeScope);
    }

    private CopiedWorld copyWorld() {
        if (client.level == null) return null;
        IntegratedServer integrated = client.getSingleplayerServer();
        if (client.isSingleplayer() && integrated != null && !integrated.isPublished()) {
            try {
                Path root = integrated.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
                Path name = root.getFileName();
                return new CopiedWorld("private:" + root, name == null ? "Singleplayer" : name.toString(), true,
                        root.resolve("serverconfig").toString());
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

    private String copyPlayerUuid() {
        return client.player == null ? "unavailable" : client.player.getUUID().toString();
    }

    private String copyFtbScopeToken() {
        OptionalStatus status = optionalStatuses.get("ftb_quests");
        if (status == null || !status.supported()) return "unavailable";
        try {
            Class<?> type = Class.forName(FTB_CLASS, false, ClientAssistant.class.getClassLoader());
            return String.valueOf(type.getMethod("scopeToken").invoke(null));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return "unavailable";
        }
    }

    private String copyJeiRuntimeGeneration() {
        OptionalStatus status = optionalStatuses.get("jei");
        if (status == null || !status.supported()) return "unavailable";
        try {
            Class<?> type = Class.forName(JEI_CLASS, false, ClientAssistant.class.getClassLoader());
            return String.valueOf(type.getMethod("runtimeGeneration").invoke(null));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return "unavailable";
        }
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
        NativeProvider nativeProvider = new NativeProvider(client, workerExecutor);
        var providers = new ArrayList<KnowledgeProvider>();
        providers.add(nativeProvider);
        providers.add(jeiProvider());
        providers.add(optionalMinecraftProvider("ftb_quests", FTB_CLASS, "forMinecraft", scope));
        providers.add(ConfigProvider.production(configRoots(), clientExecutor, workerExecutor, client::isSameThread));
        providers.add(optionalMinecraftProvider("patchouli", PATCHOULI_CLASS, "forMinecraft", scope));
        return new KnowledgeService(providers, scope,
                plan -> nativeProvider.planRecipe(plan.identity(), plan.quantity(), plan.scope(), plan.depth()),
                (identities, scopeValue) -> nativeProvider.readInventory(identities, scopeValue));
    }

    private KnowledgeProvider jeiProvider() {
        OptionalStatus status = optionalStatuses.get("jei");
        if (status == null || !status.supported()) return unavailableProvider(status, "JEI runtime is not supported");
        return new ReflectiveProvider(status, () -> {
            try {
                Class<?> type = Class.forName(JEI_CLASS, false, ClientAssistant.class.getClassLoader());
                Field active = type.getField("activeProvider");
                return active.get(null);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        });
    }

    private KnowledgeProvider optionalMinecraftProvider(String key, String className, String methodName,
                                                        Supplier<Evidence.Scope> scope) {
        OptionalStatus status = optionalStatuses.get(key);
        if (status == null || !status.supported()) return unavailableProvider(status, key + " runtime is not supported");
        try {
            Class<?> type = Class.forName(className, false, ClientAssistant.class.getClassLoader());
            Object provider = type.getMethod(methodName, Supplier.class, Executor.class)
                    .invoke(null, scope, clientExecutor);
            if (provider instanceof KnowledgeProvider knowledgeProvider) {
                return new ReflectiveProvider(status, () -> knowledgeProvider);
            }
            return unavailableProvider(status, key + " factory returned no knowledge provider");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            PackSageLog.LOGGER.warn("PackSage optional provider {} factory could not be loaded", key, failure);
            return unavailableProvider(status, key + " factory could not be loaded: " + PackSageLog.describe(failure));
        }
    }

    private static KnowledgeProvider unavailableProvider(OptionalStatus status, String detail) {
        String id = status == null ? "optional" : status.id();
        String version = status == null ? "unknown" : status.expectedVersion();
        boolean unsupported = status != null && status.loaded() && !status.supported();
        return new StatusProvider(id, version, unsupported ? Evidence.Coverage.PARTIAL : Evidence.Coverage.UNAVAILABLE,
                status == null ? detail : status.detail() + "; " + detail);
    }

    private static OptionalStatus optionalStatus(String id, String expectedVersion) {
        return optionalStatus(id, id, expectedVersion);
    }

    private static OptionalStatus optionalStatus(String id, String modId, String expectedVersion) {
        try {
            Class<?> type = Class.forName("net.neoforged.fml.ModList", false, ClientAssistant.class.getClassLoader());
            Object modList = type.getMethod("get").invoke(null);
            boolean loaded = (Boolean) type.getMethod("isLoaded", String.class).invoke(modList, modId);
            if (!loaded) return new OptionalStatus(id, modId, expectedVersion, "unloaded", false, false, modId + " is not loaded");
            Object container = type.getMethod("getModContainerById", String.class).invoke(modList, modId);
            Object info = container instanceof Optional<?> optional && optional.isPresent()
                    ? optional.get().getClass().getMethod("getModInfo").invoke(optional.get()) : null;
            String actualVersion = info == null ? "unknown" : String.valueOf(info.getClass().getMethod("getVersion").invoke(info));
            if (!versionMatches(expectedVersion, actualVersion)) {
                return new OptionalStatus(id, modId, expectedVersion, actualVersion, false, true,
                        modId + " version " + actualVersion + " is outside tested version " + expectedVersion);
            }
            return new OptionalStatus(id, modId, expectedVersion, actualVersion, true, true, modId + " tested version loaded");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            PackSageLog.LOGGER.warn("PackSage could not verify the {} version", modId, failure);
            return new OptionalStatus(id, modId, expectedVersion, "unknown", false, false,
                    modId + " version could not be verified");
        }
    }

    static boolean versionMatches(String expectedVersion, String actualVersion) {
        if (expectedVersion.equals(actualVersion)) return true;
        return actualVersion != null && actualVersion.equals(expectedVersion + LOADER_VERSION_SUFFIX);
    }

    private void openSettings() {
        settingsOpen = true;
        client.setScreen(new AssistantSettingsScreen(settings, settingsPath));
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

    private static class ReflectiveProvider implements KnowledgeProvider {
        private final OptionalStatus status;
        private final Supplier<Object> delegate;

        private ReflectiveProvider(OptionalStatus status, Supplier<Object> delegate) {
            this.status = Objects.requireNonNull(status);
            this.delegate = Objects.requireNonNull(delegate);
        }

        @Override
        public String id() { return status.id(); }

        @Override
        public ProviderStatus status() {
            Object current = delegate.get();
            if (!(current instanceof KnowledgeProvider provider)) {
                return new ProviderStatus(Availability.UNAVAILABLE, status.expectedVersion(),
                        status.id() + " runtime is unavailable");
            }
            try { return provider.status(); } catch (RuntimeException | LinkageError failure) {
                PackSageLog.LOGGER.warn("PackSage optional provider {} status failed", status.id(), failure);
                return new ProviderStatus(Availability.ERROR, status.expectedVersion(),
                        status.id() + " status failed: " + PackSageLog.describe(failure));
            }
        }

        @Override
        public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
            Object current = delegate.get();
            if (!(current instanceof KnowledgeProvider provider)) return statusResult(scope, status.id(),
                    status.expectedVersion(), Evidence.Coverage.UNAVAILABLE, status.id() + " runtime is unavailable");
            try {
                CompletionStage<Evidence.Result> result = provider.query(query, scope);
                return result == null ? statusResult(scope, status.id(), status.expectedVersion(), Evidence.Coverage.ERROR,
                        status.id() + " returned no result") : result.exceptionally(failure -> {
                    PackSageLog.LOGGER.warn("PackSage optional provider {} query failed", status.id(), failure);
                    return statusResultNow(scope, status.id(), status.expectedVersion(), Evidence.Coverage.ERROR,
                            status.id() + " query failed: " + PackSageLog.describe(failure));
                });
            } catch (RuntimeException | LinkageError failure) {
                PackSageLog.LOGGER.warn("PackSage optional provider {} query failed", status.id(), failure);
                return statusResult(scope, status.id(), status.expectedVersion(), Evidence.Coverage.ERROR,
                        status.id() + " query failed: " + PackSageLog.describe(failure));
            }
        }

        @Override
        public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
            Object current = delegate.get();
            if (!(current instanceof KnowledgeProvider provider)) {
                return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE,
                        status.id() + " runtime is unavailable");
            }
            try { return provider.open(target, scope); } catch (RuntimeException | LinkageError failure) {
                PackSageLog.LOGGER.warn("PackSage optional provider {} navigation failed", status.id(), failure);
                return new Evidence.NavigationResult(Evidence.NavigationStatus.ERROR, status.id() + " navigation failed");
            }
        }
    }

    private static final class StatusProvider implements KnowledgeProvider {
        private final String id;
        private final String version;
        private final Evidence.Coverage coverage;
        private final String detail;

        private StatusProvider(String id, String version, Evidence.Coverage coverage, String detail) {
            this.id = id;
            this.version = version;
            this.coverage = coverage;
            this.detail = detail;
        }

        @Override public String id() { return id; }

        @Override
        public ProviderStatus status() {
            return new ProviderStatus(coverage == Evidence.Coverage.PARTIAL ? Availability.PARTIAL : Availability.UNAVAILABLE,
                    version, detail);
        }

        @Override
        public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
            return statusResult(scope, id, version, coverage, detail);
        }

        @Override
        public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, detail);
        }
    }

    private static CompletionStage<Evidence.Result> statusResult(Evidence.Scope scope, String id, String version,
                                                                 Evidence.Coverage coverage, String detail) {
        return CompletableFuture.completedFuture(statusResultNow(scope, id, version, coverage, detail));
    }

    private static Evidence.Result statusResultNow(Evidence.Scope scope, String id, String version,
                                                   Evidence.Coverage coverage, String detail) {
        var values = Map.<String, Evidence.JsonValue>of("detail", new Evidence.JsonString(detail));
        var source = new Evidence.Source(scope, id, version, "status:" + id, scope.requestGeneration(), Instant.now(),
                Evidence.Activity.UNKNOWN, coverage, Evidence.Authority.UNKNOWN, List.of());
        var record = new Evidence.Record(source, Evidence.RecordKind.OTHER, detail, new Evidence.JsonObject(values), List.of());
        return new Evidence.Result(List.of(record), coverage,
                coverage == Evidence.Coverage.ERROR ? Evidence.Outcome.ERROR
                        : coverage == Evidence.Coverage.COMPLETE ? Evidence.Outcome.COMPLETE
                        : coverage == Evidence.Coverage.PARTIAL ? Evidence.Outcome.PARTIAL : Evidence.Outcome.UNKNOWN, null, detail);
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            var result = new StringBuilder(bytes.length * 2);
            for (byte byteValue : bytes) result.append(String.format("%02x", byteValue));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
