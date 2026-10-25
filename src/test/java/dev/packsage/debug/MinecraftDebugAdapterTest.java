package dev.packsage.debug;

import dev.packsage.chat.AssistantRuntime;
import dev.packsage.chat.ChatContracts;
import dev.packsage.chat.ConversationStore;
import dev.packsage.client.GameplayContextCapture;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.knowledge.KnowledgeService;
import dev.packsage.model.ModelBackend;
import net.minecraft.client.Minecraft;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.*;

class MinecraftDebugAdapterTest {
    @Test
    void promptPublishesOnClientBeforeTheSettingsWriterCompletes(@TempDir Path directory) throws Exception {
        class Client extends Minecraft {
            java.util.concurrent.Executor executor;
            Client() { super(null); }
            @Override public void execute(Runnable task) { executor.execute(task); }
        }
        var field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = field.get(null);
        var client = (Client) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, Client.class);
        var backend = (ModelBackend) Proxy.newProxyInstance(ModelBackend.class.getClassLoader(), new Class<?>[]{ModelBackend.class},
                (proxy, method, args) -> { throw new AssertionError("prompt editing must not call the model"); });
        var runtime = new AssistantRuntime(new ConversationStore(directory, Runnable::run), backend,
                current -> { throw new AssertionError("prompt editing must not capture context"); }, Runnable::run, Runnable::run,
                current -> new KnowledgeService(List.of(), current), turn -> 0,
                prepared -> { throw new AssertionError("prompt editing must not submit requests"); }, model -> 0, paused -> { }, url -> { });
        var path = directory.resolve("settings.properties");
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var settings = new dev.packsage.chat.PackSageSettings(true, true, 10, 12_000,
                new dev.packsage.chat.SettingsWriter((target, snapshot) -> {
                    started.countDown();
                    try { assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { throw new RuntimeException(failure); }
                    dev.packsage.chat.SettingsWriter.write(target, snapshot);
                }));
        runtime.setSettings(settings);
        try (var clientThread = java.util.concurrent.Executors.newSingleThreadExecutor();
             var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            client.executor = clientThread;
            var adapter = new MinecraftDebugAdapter(client, runtime, path, () -> { }, new DebugRequestLog());
            var body = new com.google.gson.JsonObject();
            body.addProperty("text", "bridge prompt");
            var response = worker.submit(() -> adapter.execute(DebugRouter.Operation.PROMPT_SET, body));
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var screenSave = clientThread.submit(() -> {
                assertEquals("bridge prompt", runtime.settings().developerPrompt());
                runtime.settings().setOpenPaused(false);
                return runtime.settings().save(path);
            }).get(1, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(screenSave.isDone(), "the writer remains stalled while the client heartbeat and save return");
            release.countDown();
            assertEquals("bridge prompt", response.get(5, java.util.concurrent.TimeUnit.SECONDS)
                    .get("effectiveText").getAsString());
            screenSave.get(5, java.util.concurrent.TimeUnit.SECONDS);
            settings.flushAndClose(path).join();
            assertEquals("bridge prompt", dev.packsage.chat.PackSageSettings.load(path).developerPrompt());
            assertFalse(dev.packsage.chat.PackSageSettings.load(path).openPaused());
        }
    }

    @Test
    void timedOutClientPublicationDoesNotChangeRuntimeOrDisk(@TempDir Path directory) throws Exception {
        class Client extends Minecraft {
            Runnable queued;
            Client() { super(null); }
            @Override public void execute(Runnable task) { queued = task; }
        }
        var field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = field.get(null);
        var client = (Client) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, Client.class);
        var backend = (ModelBackend) Proxy.newProxyInstance(ModelBackend.class.getClassLoader(), new Class<?>[]{ModelBackend.class},
                (proxy, method, args) -> { throw new AssertionError("prompt editing must not call the model"); });
        var runtime = new AssistantRuntime(new ConversationStore(directory, Runnable::run), backend,
                current -> { throw new AssertionError("prompt editing must not capture context"); }, Runnable::run, Runnable::run,
                current -> new KnowledgeService(List.of(), current), turn -> 0,
                prepared -> { throw new AssertionError("prompt editing must not submit requests"); }, model -> 0, paused -> { }, url -> { });
        var settingsPath = directory.resolve("settings.properties");
        var adapter = new MinecraftDebugAdapter(client, runtime, settingsPath, () -> { }, new DebugRequestLog(), 25);
        var body = new com.google.gson.JsonObject();
        body.addProperty("text", "never published");

        assertThrows(java.util.concurrent.TimeoutException.class,
                () -> adapter.execute(DebugRouter.Operation.PROMPT_SET, body));
        client.queued.run();

        assertNull(runtime.settings().developerPrompt());
        assertFalse(java.nio.file.Files.exists(settingsPath));
    }

    @ParameterizedTest
    @ValueSource(strings = {"chest", "advancement", "collision", "missing", "jei", "ftb_quests"})
    void replyNavigationAcceptsOnlyOneDestination(String scenario, @TempDir Path directory) throws Exception {
        class Client extends Minecraft {
            Client() { super(null); }
            @Override public void execute(Runnable task) { task.run(); }
        }
        var field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = field.get(null);
        var client = (Client) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, Client.class);
        var scope = new Evidence.Scope("world", "resources", "player", "runtime", 0);
        var world = new ChatContracts.WorldIdentity("world", "World");
        var targetId = scenario.equals("advancement") ? "advancement|minecraft:story/root" : "world-block|minecraft:overworld|1,64,2";
        var first = new Evidence.NavigationTarget(scope, scenario.equals("jei") || scenario.equals("ftb_quests") ? scenario : "fixture", "first", targetId);
        var second = new Evidence.NavigationTarget(scope, scenario.equals("collision") ? "other" : first.providerId(), "second", targetId);
        var store = new ConversationStore(directory, Runnable::run);
        var conversation = store.newConversation(world).toCompletableFuture().join();
        store.appendTurn(world, scope, conversation.id(), new ChatContracts.Turn(new ChatContracts.RequestId("reply"),
                "question", "answer", List.of(), new ChatContracts.Context("{}", null, scope), List.of(first, second).stream()
                .map(target -> new Evidence.Source(scope, target.providerId(), "v1", target.sourceHandle(), 1, Instant.EPOCH,
                        Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.SERVER_READ, List.of())).toList(),
                List.of(first, second), new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1)).toCompletableFuture().join();
        var opened = new ArrayList<Evidence.NavigationTarget>();
        var provider = new KnowledgeProvider() {
            @Override public String id() { return "fixture"; }
            @Override public ProviderStatus status() { return new ProviderStatus(Availability.AVAILABLE, "v1", null); }
            @Override public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope requested) {
                throw new AssertionError("navigation must not query evidence");
            }
            @Override public CompletionStage<Evidence.NavigationTarget> refreshNavigation(Evidence.NavigationTarget target, Evidence.Scope requested) {
                return CompletableFuture.completedFuture(new Evidence.NavigationTarget(requested, target.providerId(), target.sourceHandle(), target.targetId()));
            }
            @Override public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope requested) {
                opened.add(target);
                return new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED, null);
            }
        };
        var backend = (ModelBackend) Proxy.newProxyInstance(ModelBackend.class.getClassLoader(), new Class<?>[]{ModelBackend.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("providerInfo")) return new ModelBackend.ProviderInfo("fixture", "Fixture");
                    throw new AssertionError("navigation must not call the model: " + method.getName());
                });
        var runtime = new AssistantRuntime(store, backend, current -> new GameplayContextCapture(
                () -> { throw new AssertionError("navigation must not capture context"); },
                request -> { throw new AssertionError("navigation must not capture a frame"); }, Runnable::run, current),
                Runnable::run, Runnable::run, current -> new KnowledgeService(List.of(provider), current),
                turn -> 0, prepared -> { throw new AssertionError("navigation must not submit a request"); },
                model -> 0, paused -> { }, url -> { });
        runtime.enterWorld(world, scope);
        assertTrue(runtime.sessionReady());
        var adapter = new MinecraftDebugAdapter(client, runtime, directory.resolve("settings"), () -> { }, new DebugRequestLog());
        var response = new DebugRouter("test-token", adapter).route(new DebugHttp.Request("POST", "/navigate",
                Map.of("authorization", "Bearer test-token"), "{\"requestId\":\"reply\",\"targetId\":\""
                + (scenario.equals("missing") ? "missing" : targetId) + "\"}"));
        if (scenario.equals("collision") || scenario.equals("missing")) {
            assertEquals(400, response.status());
            assertTrue(opened.isEmpty());
        } else if (scenario.equals("jei") || scenario.equals("ftb_quests")) {
            assertEquals(200, response.status(), response.body().toString());
            assertEquals("UNAVAILABLE", response.body().get("status").getAsString());
            assertTrue(opened.isEmpty(), "an installed provider must not substitute for the saved provider");
        } else {
            assertEquals(200, response.status(), response.body().toString());
            assertEquals("OPENED", response.body().get("status").getAsString());
            assertEquals(1, opened.size());
            assertEquals("first", opened.getFirst().sourceHandle());
        }
    }
}
