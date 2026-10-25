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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    @ParameterizedTest
    @ValueSource(strings = {"settings", "prompt", "save", "failure", "default", "clear", "invalid", "conflict",
            "screen_before_restore", "screen_during_restore", "restore_blocked"})
    void promptSaveLeavesTheClientResponsiveAndPreservesConcurrentScreenSaves(String concurrent, @TempDir Path directory) throws Exception {
        class Client extends Minecraft {
            java.util.concurrent.Executor executor;
            Runnable afterCall;
            Thread thread;
            Client() { super(null); }
            @Override public void execute(Runnable task) {
                executor.execute(() -> {
                    thread = Thread.currentThread();
                    var after = afterCall;
                    afterCall = null;
                    task.run();
                    if (after != null) after.run();
                });
            }
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
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        var screenSaved = new java.util.concurrent.CountDownLatch(1);
        var restoring = new java.util.concurrent.CountDownLatch(1);
        var releaseRestore = new java.util.concurrent.CountDownLatch(1);
        try (var clientThread = java.util.concurrent.Executors.newSingleThreadExecutor();
             var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            client.executor = clientThread;
            if (concurrent.equals("default") || concurrent.equals("clear")) runtime.settings().setDeveloperPrompt("previous prompt");
            var adapter = new MinecraftDebugAdapter(client, runtime, () -> { }, new DebugRequestLog(), (settings, text) -> {
                assertNotSame(client.thread, Thread.currentThread(), "prompt persistence, including conflict restoration, must run on the worker");
                if (writes.incrementAndGet() == 1) {
                    // Also cover a screen save after the worker wrote but before the client can publish.
                    if (concurrent.equals("save")) settings.saveDeveloperPrompt(path, text);
                    started.countDown();
                    try { assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { throw new RuntimeException(failure); }
                    if (concurrent.equals("save")) return;
                }
                if (concurrent.equals("failure")) throw new IllegalStateException("writer failed");
                // The fourth write restores the in-memory settings after the third conflict.
                if ((concurrent.equals("conflict") || concurrent.equals("screen_before_restore")
                        || concurrent.equals("screen_during_restore") || concurrent.equals("restore_blocked")) && writes.get() <= 3) {
                    try { clientThread.submit(() -> runtime.settings().save(path)).get(1, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (Exception failure) { throw new RuntimeException(failure); }
                    if (concurrent.equals("screen_before_restore") && writes.get() == 3) {
                        // Save after the third conflict check. The queued restore check must see the newer generation.
                        client.afterCall = () -> {
                            runtime.settings().saveDeveloperPrompt(path, "newest screen prompt");
                            screenSaved.countDown();
                        };
                    }
                }
                if ((concurrent.equals("restore_blocked") || concurrent.equals("screen_during_restore")) && writes.get() == 4) {
                    restoring.countDown();
                    try { assertTrue(releaseRestore.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { throw new RuntimeException(failure); }
                }
                settings.saveDeveloperPrompt(path, text);
            });
            var body = new com.google.gson.JsonObject();
            body.addProperty("text", concurrent.equals("default") ? dev.packsage.model.GroundingInstructions.defaultInstructions()
                    : concurrent.equals("clear") ? null : concurrent.equals("invalid") ? " " : "bridge prompt");
            var response = worker.submit(() -> adapter.execute(DebugRouter.Operation.PROMPT_SET, body));
            if (concurrent.equals("invalid")) {
                assertThrows(java.util.concurrent.ExecutionException.class, () -> response.get(5, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(0, writes.get());
                assertNull(runtime.settings().developerPrompt());
                assertFalse(java.nio.file.Files.exists(path));
                return;
            }
            try {
                assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
                clientThread.submit(() -> {
                    assertEquals(concurrent.equals("default") || concurrent.equals("clear") ? "previous prompt" : null,
                            runtime.settings().developerPrompt(), "unpersisted prompt must not be published");
                    if (concurrent.equals("settings")) {
                        runtime.settings().setOpenPaused(false);
                        runtime.settings().setHistoryTurns(25);
                    } else if (concurrent.equals("prompt")) {
                        runtime.settings().saveDeveloperPrompt(path, "screen prompt");
                    }
                    runtime.settings().save(path);
                }).get(1, java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
            if (concurrent.equals("failure")) {
                assertThrows(java.util.concurrent.ExecutionException.class, () -> response.get(5, java.util.concurrent.TimeUnit.SECONDS));
                assertNull(runtime.settings().developerPrompt());
                assertNull(dev.packsage.chat.PackSageSettings.load(path).developerPrompt());
                return;
            }
            if (concurrent.equals("restore_blocked")) {
                try {
                    assertTrue(restoring.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    clientThread.submit(() -> { }).get(1, java.util.concurrent.TimeUnit.SECONDS);
                } finally {
                    releaseRestore.countDown();
                }
            }
            if (concurrent.equals("screen_during_restore")) {
                java.util.concurrent.Future<?> screen;
                try {
                    assertTrue(restoring.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    screen = clientThread.submit(() -> runtime.settings().saveDeveloperPrompt(path, "newest screen prompt"));
                    assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
                        while (client.thread.getState() != Thread.State.BLOCKED) Thread.sleep(1);
                    });
                } finally {
                    releaseRestore.countDown();
                }
                screen.get(5, java.util.concurrent.TimeUnit.SECONDS);
                screenSaved.countDown();
            }
            if (concurrent.equals("conflict") || concurrent.equals("screen_before_restore")
                    || concurrent.equals("screen_during_restore") || concurrent.equals("restore_blocked")) {
                var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> response.get(5, java.util.concurrent.TimeUnit.SECONDS));
                assertInstanceOf(IllegalStateException.class, failure.getCause());
                assertTrue(failure.getCause().getMessage().contains("conflict"));
                if (concurrent.equals("screen_before_restore") || concurrent.equals("screen_during_restore")) {
                    assertTrue(screenSaved.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    assertEquals("newest screen prompt", runtime.settings().developerPrompt());
                } else assertNull(runtime.settings().developerPrompt());
                assertEquals(concurrent.equals("screen_before_restore") ? 3 : 4, writes.get(), concurrent);
                assertEquals(runtime.settings().developerPrompt(), dev.packsage.chat.PackSageSettings.load(path).developerPrompt());
                return;
            }
            assertEquals(concurrent.equals("default") || concurrent.equals("clear")
                    ? dev.packsage.model.GroundingInstructions.defaultInstructions()
                    : concurrent.equals("prompt") ? "screen prompt" : "bridge prompt",
                    response.get(5, java.util.concurrent.TimeUnit.SECONDS).get("effectiveText").getAsString());
            var saved = dev.packsage.chat.PackSageSettings.load(path);
            assertEquals(concurrent.equals("default") || concurrent.equals("clear") ? null
                    : concurrent.equals("prompt") ? "screen prompt" : "bridge prompt", saved.developerPrompt());
            assertEquals(saved.developerPrompt(), runtime.settings().developerPrompt());
            assertEquals(!concurrent.equals("settings"), saved.openPaused());
            assertEquals(concurrent.equals("settings") ? 25 : 10, saved.historyTurns());
            assertEquals(saved.historyTurns(), runtime.settings().historyTurns());
        }
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
