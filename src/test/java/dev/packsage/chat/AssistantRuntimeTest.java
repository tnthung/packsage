package dev.packsage.chat;

import dev.packsage.client.GameplayContextCapture;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeService;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.model.PackSageTools;
import dev.packsage.model.ModelBackend;
import dev.packsage.model.GroundingInstructions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantRuntimeTest {
    private static final Executor DIRECT = Runnable::run;
    private static final ChatContracts.WorldIdentity WORLD = new ChatContracts.WorldIdentity("world", "World");
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "resources", "team", "runtime", 0);
    private static final ModelBackend.ModelInfo MODEL = new ModelBackend.ModelInfo("catalog-model", "Catalog Model",
            true, true, 16_000, 2_048);
    private static final ModelBackend.ModelInfo UNKNOWN_MODEL = new ModelBackend.ModelInfo("unknown-model", "Unknown Model",
            null, null, 16_000, 2_048);

    @Test
    void completionNoticeQueuesOnceWithoutAnOpenScreenAndDropsAfterNewChat() throws Exception {
        java.util.ArrayDeque<Runnable> clientTasks = new java.util.ArrayDeque<>();
        List<ChatContracts.Turn> notices = new ArrayList<>();
        FakeBackend backend = new FakeBackend();
        var runtime = new AssistantRuntime(new ConversationStore(Files.createTempDirectory("packsage-completion-runtime"), DIRECT),
                backend, captureFactory(), DIRECT, clientTasks::addLast,
                current -> new KnowledgeService(List.of(), current), ChatContracts.Turn::tokenCount,
                prepared -> new ModelBackend.Request(prepared.submission().selectedModel().id(), "instructions", List.of(
                        new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent(
                                prepared.submission().question())))), List.of(), prepared.submission().searchMode(),
                        prepared.submission().requestId().value(), prepared.submission().outputBudget()),
                ignored -> 512, ignored -> { }, ignored -> { }, notices::add);
        runtime.enterWorld(WORLD, SCOPE);
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        while (backend.catalogLoads == 0) clientTasks.removeFirst().run();
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), MODEL.imageInput(),
                MODEL.hostedSearch(), MODEL.contextTokens(), MODEL.outputTokens()));
        while (runtime.modelCatalog().isEmpty()) clientTasks.removeFirst().run();
        while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();

        runtime.send("first", false, false);
        ModelBackend.Event completed = new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null,
                List.of(), List.of(), null);
        backend.emit(completed);
        backend.emit(completed);

        assertTrue(notices.isEmpty());
        while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();
        assertEquals(1, notices.size());
        assertEquals("first", notices.getFirst().question());

        runtime.send("stale", false, false);
        backend.emit(completed);
        runtime.newChat();
        while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();

        assertEquals(1, notices.size());
    }

    @Test
    void completionNoticeIsSuppressedWhenNewChatPrecedesListenerEnqueue() throws Exception {
        java.util.ArrayDeque<Runnable> clientTasks = new java.util.ArrayDeque<>();
        List<ChatContracts.Turn> notices = new ArrayList<>();
        CountDownLatch tokenCounterEntered = new CountDownLatch(1);
        CountDownLatch releaseTokenCounter = new CountDownLatch(1);
        AtomicBoolean blockTokenCounter = new AtomicBoolean();
        FakeBackend backend = new FakeBackend();
        var runtime = new AssistantRuntime(new ConversationStore(Files.createTempDirectory("packsage-completion-race"), DIRECT),
                backend, captureFactory(), DIRECT, clientTasks::addLast,
                current -> new KnowledgeService(List.of(), current), turn -> {
                    if (blockTokenCounter.get()) {
                        tokenCounterEntered.countDown();
                        try {
                            if (!releaseTokenCounter.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("token counter release timed out");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        }
                    }
                    return turn.tokenCount();
                },
                prepared -> new ModelBackend.Request(prepared.submission().selectedModel().id(), "instructions", List.of(
                        new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent(
                                prepared.submission().question())))), List.of(), prepared.submission().searchMode(),
                        prepared.submission().requestId().value(), prepared.submission().outputBudget()),
                ignored -> 512, ignored -> { }, ignored -> { }, notices::add);
        runtime.enterWorld(WORLD, SCOPE);
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        while (backend.catalogLoads == 0) clientTasks.removeFirst().run();
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), MODEL.imageInput(),
                MODEL.hostedSearch(), MODEL.contextTokens(), MODEL.outputTokens()));
        while (runtime.modelCatalog().isEmpty()) clientTasks.removeFirst().run();
        while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();
        runtime.send("racing completion", false, false);
        blockTokenCounter.set(true);
        AtomicReference<Throwable> completionFailure = new AtomicReference<>();
        Thread completionThread = new Thread(() -> {
            try {
                backend.emit(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), List.of(), null));
            } catch (Throwable failure) {
                completionFailure.set(failure);
            }
        }, "packsage-completion-race-test");
        completionThread.start();
        try {
            assertTrue(tokenCounterEntered.await(5, TimeUnit.SECONDS));
            assertFalse(runtime.view().active());
            runtime.newChat();
        } finally {
            releaseTokenCounter.countDown();
            completionThread.join(5_000);
        }

        assertFalse(completionThread.isAlive());
        assertNull(completionFailure.get());
        while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();
        assertTrue(notices.isEmpty());
    }

    @Test
    void sendInstallsNextScopeAndSnapshotsCatalogSettingsBeforeCapture() throws Exception {
        AtomicReference<Evidence.Scope> currentScope = new AtomicReference<>();
        AtomicReference<ModelBackend.Request> request = new AtomicReference<>();
        FakeBackend backend = new FakeBackend();
        PackSageSettings settings = new PackSageSettings(true, false, 2, 30);
        AtomicReference<ChatContracts.PreparedRequest> prepared = new AtomicReference<>();
        var runtime = runtime(backend, captureFactory(), currentScope, request, prepared, settings, model -> 777);
        runtime.setSettings(settings);
        runtime.enterWorld(WORLD, SCOPE);
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), MODEL.imageInput(),
                MODEL.hostedSearch(), MODEL.contextTokens(), MODEL.outputTokens()));

        runtime.send("question", false, false);

        assertEquals(1, backend.starts, runtime.view().errors().toString());
        assertEquals(MODEL.id(), request.get().modelId());
        assertEquals(ModelBackend.SearchMode.EXPLICIT_ONLY, request.get().searchMode());
        assertEquals(777, request.get().outputBudget());
        assertEquals(1, request.get().input().size());
        assertEquals(new ChatContracts.HistoryLimits(2, 30), prepared.get().submission().historyLimits());
        assertEquals(1, backend.requests.size());
        assertEquals("question", runtime.view().activeQuestion());
        ChatContracts.SessionSummary activeSession = runtime.sessions().toCompletableFuture().join().stream()
                .filter(session -> session.world().worldOrServer().equals(WORLD.worldOrServer())).findFirst().orElseThrow();
        assertTrue(runtime.deleteSession(activeSession).toCompletableFuture().isCompletedExceptionally());
        runtime.stop();
        assertEquals("", runtime.view().activeQuestion());
    }

    @Test
    void developerPromptAppliesToEachNewRequestAndLeavesStartedRequestUnchanged() throws Exception {
        FakeBackend backend = new FakeBackend();
        PackSageSettings settings = PackSageSettings.defaults();
        settings.setDeveloperPrompt("prompt one");
        AtomicReference<ModelBackend.Request> request = new AtomicReference<>();
        var runtime = runtime(backend, captureFactory(), new AtomicReference<>(), request, settings, model -> 512);
        runtime.enterWorld(WORLD, SCOPE);
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), MODEL.imageInput(),
                MODEL.hostedSearch(), MODEL.contextTokens(), MODEL.outputTokens()));

        runtime.send("first", false, false);
        ModelBackend.Request started = backend.requests.getFirst();
        settings.setDeveloperPrompt("prompt two");
        assertEquals("prompt one", started.instructions());
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), null));
        runtime.send("second", false, false);

        assertEquals("prompt one", started.instructions());
        assertEquals("prompt two", backend.requests.getLast().instructions());
        runtime.stop();
    }

    @Test
    void deletingSelectedSessionStartsAnEmptyCurrentWorldChat() throws Exception {
        FakeBackend backend = new FakeBackend();
        var runtime = runtime(backend, captureFactory(), new AtomicReference<>(), new AtomicReference<>(),
                PackSageSettings.defaults(), model -> 512);
        runtime.enterWorld(WORLD, SCOPE);
        ChatContracts.WorldIdentity otherWorld = new ChatContracts.WorldIdentity("other-world", "Other World");
        runtime.enterWorld(otherWorld, new Evidence.Scope("other-world", "resources", "team", "runtime", 0));
        ChatContracts.SessionSummary otherSession = runtime.sessions().toCompletableFuture().join().stream()
                .filter(session -> session.world().worldOrServer().equals(otherWorld.worldOrServer())).findFirst().orElseThrow();
        runtime.enterWorld(WORLD, SCOPE);
        String activeId = runtime.view().conversation().id();
        runtime.deleteSession(otherSession).toCompletableFuture().join();
        assertEquals(activeId, runtime.view().conversation().id());
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), MODEL.imageInput(),
                MODEL.hostedSearch(), MODEL.contextTokens(), MODEL.outputTokens()));

        ChatContracts.SessionSummary selected = runtime.sessions().toCompletableFuture().join().stream()
                .filter(session -> session.world().worldOrServer().equals(WORLD.worldOrServer())).findFirst().orElseThrow();

        runtime.deleteSession(selected).toCompletableFuture().join();

        assertFalse(runtime.sessions().toCompletableFuture().join().stream()
                .anyMatch(session -> session.id().equals(selected.id())));
        assertTrue(runtime.view().conversation().turns().isEmpty());
        assertTrue(runtime.view().conversation().world().worldOrServer().equals(WORLD.worldOrServer()));
        assertFalse(runtime.view().conversation().id().equals(selected.id()));
        String freshId = runtime.view().conversation().id();
        assertTrue(runtime.sessions().toCompletableFuture().join().stream()
                .anyMatch(session -> session.id().equals(freshId)));
        runtime.send("after deletion", false, false);
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), List.of(), null));
        assertEquals(List.of("after deletion"), runtime.view().conversation().turns().stream()
                .map(ChatContracts.Turn::question).toList());
        assertTrue(runtime.sessions().toCompletableFuture().join().stream()
                .anyMatch(session -> session.id().equals(freshId) && session.title().equals("after deletion")));
    }

    @Test
    void deletionGateRejectsSendAndLateCompletionCannotResetAChangedWorld() throws Exception {
        var directory = Files.createTempDirectory("packsage-delete-runtime-test");
        ConversationStore store = new ConversationStore(directory, DIRECT);
        ChatContracts.Conversation selected = store.newConversation(WORLD).toCompletableFuture().join();
        java.util.ArrayDeque<Runnable> clientTasks = new java.util.ArrayDeque<>();
        FakeBackend backend = new FakeBackend();
        var runtime = new AssistantRuntime(store, backend, captureFactory(), DIRECT, clientTasks::addLast,
                current -> new KnowledgeService(List.of(), current),
                turn -> turn.tokenCount(), ignored -> { throw new AssertionError("request must be rejected"); },
                ignored -> 512, ignored -> { }, ignored -> { });
        runtime.enterWorld(WORLD, SCOPE);
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        while (backend.catalogLoads == 0) clientTasks.removeFirst().run();
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), MODEL.imageInput(),
                MODEL.hostedSearch(), MODEL.contextTokens(), MODEL.outputTokens()));
        while (runtime.modelCatalog().isEmpty()) clientTasks.removeFirst().run();
        ChatContracts.SessionSummary summary = new ChatContracts.SessionSummary(WORLD, selected.id(), "New chat", "", Instant.EPOCH);

        runtime.openSession(summary);
        assertTrue(runtime.deleteSession(summary).toCompletableFuture().isCompletedExceptionally());
        while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();

        runtime.deleteSession(summary);
        runtime.send("must not start during deletion", false, false);

        assertEquals(0, backend.starts);
        assertTrue(runtime.view().errors().stream().anyMatch(error -> error.contains("deletion is still in progress")));
        ChatContracts.WorldIdentity nextWorld = new ChatContracts.WorldIdentity("next-world", "Next World");
        Evidence.Scope nextScope = new Evidence.Scope("next-world", "resources", "team", "runtime", 0);
        runtime.enterWorld(nextWorld, nextScope);
        while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();

        assertEquals(nextWorld.worldOrServer(), runtime.view().activeWorld().worldOrServer());
        assertEquals(nextWorld.worldOrServer(), runtime.view().conversation().world().worldOrServer());
    }

    @Test
    void sameWorldInvalidationStillResetsSelectedSessionAfterDelayedDelete() throws Exception {
        var directory = Files.createTempDirectory("packsage-delete-invalidate-test");
        ConversationStore store = new ConversationStore(directory, DIRECT);
        ChatContracts.Conversation deleted = store.newConversation(WORLD).toCompletableFuture().join();
        java.util.ArrayDeque<Runnable> clientTasks = new java.util.ArrayDeque<>();
        FakeBackend backend = new FakeBackend();
        var runtime = new AssistantRuntime(store, backend, captureFactory(), DIRECT, clientTasks::addLast,
                current -> new KnowledgeService(List.of(), current),
                turn -> turn.tokenCount(), ignored -> { throw new AssertionError("No model request expected"); },
                ignored -> 512, ignored -> { }, ignored -> { });
        runtime.enterWorld(WORLD, SCOPE);
        ChatContracts.SessionSummary summary = new ChatContracts.SessionSummary(WORLD, deleted.id(), "New chat", "", Instant.EPOCH);

        runtime.deleteSession(summary);
        Evidence.Scope refreshed = new Evidence.Scope("world", "resources", "team", "runtime", 1);
        runtime.invalidate(refreshed);
        while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();

        String freshId = runtime.view().conversation().id();
        assertFalse(freshId.equals(deleted.id()));
        assertEquals(WORLD.worldOrServer(), runtime.view().conversation().world().worldOrServer());
        assertTrue(runtime.sessions().toCompletableFuture().join().stream()
                .anyMatch(session -> session.id().equals(freshId)));
        assertFalse(runtime.sessions().toCompletableFuture().join().stream()
                .anyMatch(session -> session.id().equals(deleted.id())));
    }

    @Test
    void deletionWaitsForAnOrdinaryTurnAppendToSettle() throws Exception {
        java.util.ArrayDeque<Runnable> storeTasks = new java.util.ArrayDeque<>();
        Executor storeExecutor = storeTasks::addLast;
        FakeBackend backend = new FakeBackend();
        var runtime = runtime(backend, captureFactory(), new AtomicReference<>(), new AtomicReference<>(),
                new AtomicReference<>(), PackSageSettings.defaults(), model -> 512, ignored -> { }, ignored -> { },
                current -> new KnowledgeService(List.of(), current), storeExecutor);
        runtime.enterWorld(WORLD, SCOPE);
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();
        var sessions = runtime.sessions();
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();
        ChatContracts.SessionSummary session = sessions.toCompletableFuture().join().stream()
                .filter(value -> value.world().worldOrServer().equals(WORLD.worldOrServer())).findFirst().orElseThrow();
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), MODEL.imageInput(),
                MODEL.hostedSearch(), MODEL.contextTokens(), MODEL.outputTokens()));
        runtime.send("question", false, false);
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), List.of(), null));

        assertTrue(runtime.deleteSession(session).toCompletableFuture().isCompletedExceptionally());
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();
        var deleted = runtime.deleteSession(session);
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();
        deleted.toCompletableFuture().join();

        assertTrue(runtime.view().conversation().turns().isEmpty());
        assertFalse(runtime.view().conversation().id().equals(session.id()));
    }

    @Test
    void previewAndSendUseTheSameCounterAndClampKnownModelLimits() throws Exception {
        FakeBackend backend = new FakeBackend();
        PackSageSettings settings = new PackSageSettings(true, false, 2, 100_000);
        AtomicReference<ChatContracts.PreparedRequest> prepared = new AtomicReference<>();
        var runtime = runtime(backend, captureFactory(), new AtomicReference<>(), new AtomicReference<>(), prepared,
                settings, model -> 512);
        runtime.enterWorld(WORLD, SCOPE);
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), MODEL.imageInput(),
                MODEL.hostedSearch(), MODEL.contextTokens(), MODEL.outputTokens()));
        runtime.send("first", false, false);
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), null));
        var preview = runtime.historyPreview();
        assertEquals(100_000, preview.configuredTokens());
        assertEquals(16_000, preview.effectiveTokens());
        assertEquals(1, preview.selection().turns().size());
        assertEquals(0, preview.selection().tokenCount());
        runtime.send("second", false, false);
        assertEquals(preview.selection(), prepared.get().history());
        assertEquals(16_000, prepared.get().submission().historyLimits().historicalTokens());
    }

    @Test
    void resendExcludesLatestAndRejectedEditDoesNotLeakIntoOrdinarySend() throws Exception {
        FakeBackend backend = new FakeBackend();
        AtomicReference<ChatContracts.PreparedRequest> prepared = new AtomicReference<>();
        var runtime = runtime(backend, captureFactory(), new AtomicReference<>(), new AtomicReference<>(), prepared,
                PackSageSettings.defaults(), model -> 512);
        runtime.enterWorld(WORLD, SCOPE);
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), MODEL.imageInput(),
                MODEL.hostedSearch(), MODEL.contextTokens(), MODEL.outputTokens()));
        runtime.send("first", false, false);
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), null));
        runtime.send("second", false, false);
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), null));
        ChatContracts.RequestId latest = runtime.view().conversation().turns().getLast().requestId();
        runtime.resend(latest, "", false, false);
        assertEquals(2, backend.starts);
        var preview = runtime.historyPreview(latest);
        runtime.resend(latest, "edited second", false, false);
        assertEquals(preview.selection(), prepared.get().history());
        assertEquals(List.of("first"), prepared.get().history().turns().stream().map(ChatContracts.Turn::question).toList());
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.FAILED, null, null, List.of(), "temporary failure"));
        assertEquals(List.of("first", "edited second"), runtime.view().conversation().turns().stream().map(ChatContracts.Turn::question).toList());
        runtime.send("ordinary third", false, false);
        assertNull(prepared.get().submission().replacing());
    }

    @Test
    void selectedModelAndReasoningEffortAreSnapshotBeforeCaptureSettingsChange() throws Exception {
        AtomicReference<Evidence.Scope> currentScope = new AtomicReference<>();
        AtomicReference<ModelBackend.Request> request = new AtomicReference<>();
        FakeBackend backend = new FakeBackend();
        ModelBackend.ModelInfo alternate = new ModelBackend.ModelInfo("alternate-model", "Alternate Model", true, true, 16_000, 2_048);
        PackSageSettings settings = PackSageSettings.defaults();
        settings.setModelId(alternate.id());
        settings.setReasoningEffort(ModelBackend.ReasoningEffort.HIGH);
        var runtime = runtime(backend, scope -> new GameplayContextCapture(() -> {
            settings.setModelId(MODEL.id());
            settings.setReasoningEffort(ModelBackend.ReasoningEffort.LOW);
            return new GameplayContextCapture.Observation("held", "target", "overworld", "1,2,3", "plains", "1", "block");
        }, ignored -> new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1}, Instant.EPOCH), DIRECT, scope),
                currentScope, request, settings, model -> 777);
        runtime.enterWorld(WORLD, SCOPE);
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL, alternate), MODEL.id(), MODEL.imageInput(),
                MODEL.hostedSearch(), MODEL.contextTokens(), MODEL.outputTokens()));
        settings.setModelId(alternate.id());
        settings.setReasoningEffort(ModelBackend.ReasoningEffort.HIGH);

        runtime.send("question", false, false);

        assertEquals(alternate.id(), request.get().modelId());
        assertEquals(ModelBackend.ReasoningEffort.HIGH, request.get().reasoningEffort());
    }

    @Test
    void unavailableConfiguredModelIsVisibleAndDoesNotStartBackend() throws Exception {
        FakeBackend backend = new FakeBackend();
        AtomicInteger captures = new AtomicInteger();
        PackSageSettings settings = PackSageSettings.defaults();
        settings.setModelId("missing-model");
        var runtime = runtime(backend, scope -> new GameplayContextCapture(() -> {
            captures.incrementAndGet();
            return new GameplayContextCapture.Observation("held", "target", "overworld", "1,2,3", "plains", "1", "block");
        }, ignored -> new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1}, Instant.EPOCH), DIRECT, scope),
                new AtomicReference<>(), new AtomicReference<>(), settings, model -> 512);
        runtime.enterWorld(WORLD, SCOPE);
        var screen = runtime.openScreen();
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), MODEL.imageInput(), MODEL.hostedSearch(),
                MODEL.contextTokens(), MODEL.outputTokens()));
        captures.set(0);

        runtime.send("question", false, false);

        assertEquals(0, backend.starts);
        assertEquals(0, captures.get());
        assertTrue(screen.view().errors().stream().anyMatch(error -> error.contains("Configured model is unavailable")));
    }

    @Test
    void unsupportedModelAndMissingWorldAreVisibleAndDoNotStartBackend() throws Exception {
        AtomicReference<Evidence.Scope> currentScope = new AtomicReference<>();
        FakeBackend backend = new FakeBackend();
        var runtime = runtime(backend, captureFactory(), currentScope, new AtomicReference<>(), new AtomicReference<>(), PackSageSettings.defaults(), model -> 512);

        runtime.send("question", true, true);
        assertTrue(runtime.view().errors().stream().anyMatch(error -> error.contains("world")));
        assertEquals(0, backend.starts);

        runtime.enterWorld(WORLD, SCOPE);
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(), null, null, null, null, null));
        runtime.send("question", true, true);
        assertTrue(runtime.view().errors().stream().anyMatch(error -> error.toLowerCase().contains("model")));
        assertEquals(0, backend.starts);
    }

    @Test
    void signOutInvalidatesLateCatalogAndPauseUsesInjectedGameControl() throws Exception {
        AtomicReference<Evidence.Scope> currentScope = new AtomicReference<>();
        AtomicReference<Boolean> paused = new AtomicReference<>();
        FakeBackend backend = new FakeBackend();
        var runtime = runtime(backend, captureFactory(), currentScope, new AtomicReference<>(), new AtomicReference<>(), PackSageSettings.defaults(),
                model -> 512, paused::set);
        runtime.enterWorld(WORLD, SCOPE);
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        runtime.signOut();
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), MODEL.imageInput(),
                MODEL.hostedSearch(), MODEL.contextTokens(), MODEL.outputTokens()));

        runtime.setPaused(true);
        assertEquals(true, paused.get());
        assertEquals(ChatContracts.SignInState.SIGNED_OUT, runtime.view().signIn());
        assertTrue(runtime.view().errors().stream().noneMatch(error -> error.contains("catalog-model")));
    }

    @Test
    void unknownHostedSearchKeepsAutomaticExplicitOnlyAndExplicitRequestModes() throws Exception {
        FakeBackend backend = new FakeBackend();
        var runtime = runtime(backend, captureFactory(), new AtomicReference<>(), new AtomicReference<>(),
                PackSageSettings.defaults(), model -> 512);
        runtime.enterWorld(WORLD, SCOPE);
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(UNKNOWN_MODEL), UNKNOWN_MODEL.id(), null, null,
                UNKNOWN_MODEL.contextTokens(), UNKNOWN_MODEL.outputTokens()));

        runtime.send("automatic", false, false);
        runtime.stop();
        PackSageSettings explicitOnly = new PackSageSettings(true, false, 10, 12_000);
        runtime.setSettings(explicitOnly);
        runtime.send("explicit only", false, false);
        runtime.stop();
        runtime.send("explicit request", false, true);

        assertEquals(List.of(ModelBackend.SearchMode.AUTOMATIC, ModelBackend.SearchMode.EXPLICIT_ONLY,
                ModelBackend.SearchMode.EXPLICIT_REQUEST), backend.requests.stream().map(ModelBackend.Request::searchMode).toList());
    }

    @Test
    void unknownImageCapabilityStaysAttachedWithoutUnsupportedMessage() throws Exception {
        FakeBackend backend = new FakeBackend();
        AtomicReference<ChatContracts.PreparedRequest> prepared = new AtomicReference<>();
        var runtime = runtime(backend, captureFactory(), new AtomicReference<>(), new AtomicReference<>(), prepared,
                PackSageSettings.defaults(), model -> 512);
        runtime.enterWorld(WORLD, SCOPE);
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(UNKNOWN_MODEL), UNKNOWN_MODEL.id(), null, null,
                UNKNOWN_MODEL.contextTokens(), UNKNOWN_MODEL.outputTokens()));

        runtime.send("question", true, false);

        assertNotNull(prepared.get().capturedContext().context().screenshot());
        assertTrue(runtime.view().errors().stream().noneMatch(error -> error.contains("will omit unless verified")));
    }

    @Test
    void activeFollowupDoesNotFailOrAdvanceScope() throws Exception {
        FakeBackend backend = new FakeBackend();
        var runtime = runtime(backend, captureFactory(), new AtomicReference<>(), new AtomicReference<>(),
                PackSageSettings.defaults(), model -> 512);
        runtime.enterWorld(WORLD, SCOPE);
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), MODEL.imageInput(),
                MODEL.hostedSearch(), MODEL.contextTokens(), MODEL.outputTokens()));

        runtime.send("first", false, false);
        runtime.send("second", false, false);

        assertEquals(1, backend.starts);
        assertEquals(List.of(new ModelBackend.Message(ModelBackend.Role.USER,
                List.of(new ModelBackend.TextContent("second")))), backend.steered);
        assertTrue(runtime.view().active());
        assertEquals(1, runtime.view().context().scope().requestGeneration());
        assertTrue(runtime.view().errors().isEmpty());
    }

    @Test
    void outputBudgetFailureDoesNotAdvanceScope() throws Exception {
        FakeBackend backend = new FakeBackend();
        var runtime = runtime(backend, captureFactory(), new AtomicReference<>(), new AtomicReference<>(),
                PackSageSettings.defaults(), model -> 0);
        runtime.enterWorld(WORLD, SCOPE);
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), MODEL.imageInput(),
                MODEL.hostedSearch(), MODEL.contextTokens(), MODEL.outputTokens()));

        runtime.send("question", false, false);

        assertEquals(0, runtime.view().context().scope().requestGeneration());
        assertEquals(0, backend.starts);
        assertTrue(runtime.view().errors().stream().anyMatch(error -> error.contains("Output budget")));
    }

    @Test
    void completedTypedExternalCitationCreatesValidatedNavigationTarget() throws Exception {
        FakeBackend backend = new FakeBackend();
        AtomicReference<String> opened = new AtomicReference<>();
        var runtime = runtime(backend, captureFactory(), new AtomicReference<>(), new AtomicReference<>(),
                new AtomicReference<>(), PackSageSettings.defaults(), model -> 512, ignored -> { }, opened::set);
        runtime.enterWorld(WORLD, SCOPE);
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), MODEL.imageInput(),
                MODEL.hostedSearch(), MODEL.contextTokens(), MODEL.outputTokens()));
        runtime.send("question", false, false);
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), List.of(
                new ModelBackend.AssistantOutput("answer", ModelBackend.OutputStatus.COMPLETED,
                        ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText("answer", List.of(
                        new ModelBackend.UrlCitation(0, 6, "Example", "https://example.test/path"),
                        new ModelBackend.UrlCitation(0, 6, "Invalid", "ftp://example.test/path"))))),
                new ModelBackend.WebSearchCall("search", ModelBackend.WebSearchStatus.COMPLETED,
                        new ModelBackend.SearchAction("query", List.of("query"),
                                List.of(new ModelBackend.WebSearchSource("https://example.test/search"))))), null));

        ChatContracts.View view = runtime.view();
        assertEquals(2, view.navigationTargets().size());
        assertTrue(view.sources().stream().allMatch(source -> source.providerId().equals("web")
                && source.authority() == Evidence.Authority.EXTERNAL
                && source.observedAt() != null));
        Evidence.NavigationTarget target = view.navigationTargets().get(0);
        runtime.navigate(target);
        assertEquals(target.targetId(), opened.get());
        opened.set(null);
        runtime.newChat();
        runtime.navigate(target);
        assertNull(opened.get());
    }

    @Test
    void newChatAfterInvalidationRefreshesTheOpenScreenImmediately() throws Exception {
        assertNewChatRefreshesTheOpenScreen(true);
    }

    @Test
    void newChatWithoutInvalidationRefreshesTheOpenScreenImmediately() throws Exception {
        assertNewChatRefreshesTheOpenScreen(false);
    }

    private static void assertNewChatRefreshesTheOpenScreen(boolean invalidate) throws Exception {
        FakeBackend backend = new FakeBackend();
        var runtime = runtime(backend, captureFactory(), new AtomicReference<>(), new AtomicReference<>(),
                PackSageSettings.defaults(), model -> 512);
        runtime.enterWorld(WORLD, SCOPE);
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), MODEL.imageInput(),
                MODEL.hostedSearch(), MODEL.contextTokens(), MODEL.outputTokens()));
        runtime.send("question", false, false);
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), List.of(
                new ModelBackend.AssistantOutput("answer", ModelBackend.OutputStatus.COMPLETED,
                        ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText("answer", List.of())))), null));
        var screen = runtime.openScreen();
        screen.refresh(runtime.view());
        String previousId = screen.view().conversation().id();
        assertEquals(1, screen.view().conversation().turns().size());
        if (invalidate) runtime.invalidate(new Evidence.Scope("world", "resources", "team", "runtime-2", 5));

        runtime.newChat();

        assertTrue(screen.view().conversation().turns().isEmpty(), screen.view().conversation().toString());
        assertTrue(!screen.view().conversation().id().equals(previousId) && !screen.view().conversation().id().startsWith("pending"),
                screen.view().conversation().id());
    }

    @Test
    void adapterAvailabilityTracksWorldHistoryAndFactoryFailure() throws Exception {
        FakeBackend backend = new FakeBackend();
        AtomicInteger factoryCalls = new AtomicInteger();
        var knowledgeFactory = (java.util.function.Function<java.util.function.Supplier<Evidence.Scope>, KnowledgeService>) current -> {
            if (factoryCalls.incrementAndGet() == 3) throw new IllegalStateException("provider refresh failed");
            return new KnowledgeService(List.of(), current);
        };
        var runtime = runtime(backend, captureFactory(), new AtomicReference<>(), new AtomicReference<>(),
                new AtomicReference<>(), PackSageSettings.defaults(), model -> 512, ignored -> { }, ignored -> { },
                knowledgeFactory, DIRECT);

        assertEquals(Map.of("native", false, "jei", false, "ftb_quests", false, "config", false,
                "patchouli", false, "history", false), runtime.adapterAvailability());
        runtime.enterWorld(WORLD, SCOPE);
        assertEquals(Map.of("native", false, "jei", false, "ftb_quests", false, "config", false,
                "patchouli", false, "history", true), runtime.adapterAvailability());
        runtime.enterWorld(new ChatContracts.WorldIdentity("world-2", "World 2"),
                new Evidence.Scope("world-2", "resources", "team", "runtime", 0));
        assertEquals(Map.of("native", false, "jei", false, "ftb_quests", false, "config", false,
                "patchouli", false, "history", false), runtime.adapterAvailability());
        runtime.disconnect();
        assertEquals(Map.of("native", false, "jei", false, "ftb_quests", false, "config", false,
                "patchouli", false, "history", false), runtime.adapterAvailability());
    }

    @Test
    void worldSwitchRebuildsKnowledgeAndDropsLateToolResult() throws Exception {
        FakeBackend backend = new FakeBackend();
        AtomicInteger factoryCalls = new AtomicInteger();
        CompletableFuture<Evidence.Result> pendingTool = new CompletableFuture<>();
        var knowledgeFactory = (java.util.function.Function<java.util.function.Supplier<Evidence.Scope>, KnowledgeService>) current -> {
            factoryCalls.incrementAndGet();
            return new KnowledgeService(List.of(new Toolbox() {
                @Override public Metadata metadata() { return new Metadata("native", List.of(), true, List.of(), Map.of()); }
                @Override public CompletionStage<Evidence.Result> planRecipe(PackSageTools.ReadRequest request, Context context) { return pendingTool; }
            }), current);
        };
        var runtime = runtime(backend, captureFactory(), new AtomicReference<>(), new AtomicReference<>(),
                new AtomicReference<>(), PackSageSettings.defaults(), model -> 512, ignored -> { }, ignored -> { },
                knowledgeFactory, DIRECT);
        runtime.enterWorld(WORLD, SCOPE);
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), MODEL.imageInput(),
                MODEL.hostedSearch(), MODEL.contextTokens(), MODEL.outputTokens()));
        runtime.send("question", false, false);
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                new ModelBackend.ToolCall("call", "plan_recipe", "{\"identity\":\"minecraft:torch\",\"quantity\":1}"),
                List.of(), null));

        ChatContracts.WorldIdentity nextWorld = new ChatContracts.WorldIdentity("world-2", "World 2");
        Evidence.Scope nextScope = new Evidence.Scope("world-2", "resources", "team", "runtime", 0);
        runtime.enterWorld(nextWorld, nextScope);
        pendingTool.complete(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null));

        assertEquals(3, factoryCalls.get());
        assertEquals(0, backend.submissions);
        assertEquals(nextWorld, runtime.view().conversation().world());
    }

    @Test
    void pendingWorldHistoryRejectsSendWithoutAdvancingScope() throws Exception {
        FakeBackend backend = new FakeBackend();
        var runtime = runtime(backend, captureFactory(), new AtomicReference<>(), new AtomicReference<>(),
                new AtomicReference<>(), PackSageSettings.defaults(), model -> 512, ignored -> { }, ignored -> { },
                current -> new KnowledgeService(List.of(), current),
                ignored -> { });
        runtime.enterWorld(WORLD, SCOPE);
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), MODEL.imageInput(),
                MODEL.hostedSearch(), MODEL.contextTokens(), MODEL.outputTokens()));

        runtime.send("question", false, false);

        assertEquals(0, runtime.view().context().scope().requestGeneration());
        assertEquals(0, backend.starts);
        assertTrue(runtime.view().errors().stream().anyMatch(error -> error.contains("world history is still loading")));
    }

    @Test
    void restoredSessionReachesSignedInAndLoadsCatalogWithoutSignInFlow() throws Exception {
        FakeBackend backend = new FakeBackend();
        var runtime = runtime(backend, captureFactory(), new AtomicReference<>(), new AtomicReference<>(),
                PackSageSettings.defaults(), model -> 512);
        runtime.enterWorld(WORLD, SCOPE);

        runtime.restoreSession();
        assertEquals(ChatContracts.SignInState.SIGNING_IN, runtime.view().signIn());
        backend.restore.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), MODEL.imageInput(),
                MODEL.hostedSearch(), MODEL.contextTokens(), MODEL.outputTokens()));

        assertEquals(ChatContracts.SignInState.SIGNED_IN, runtime.view().signIn());
        assertEquals(0, backend.signIns);
        assertTrue(runtime.view().errors().isEmpty(), runtime.view().errors().toString());
        runtime.send("question", false, false);
        assertEquals(1, backend.starts, runtime.view().errors().toString());
    }

    @Test
    void restoreWithoutSavedCredentialStaysSignedOutWithoutError() throws Exception {
        FakeBackend backend = new FakeBackend();
        var runtime = runtime(backend, captureFactory(), new AtomicReference<>(), new AtomicReference<>(),
                PackSageSettings.defaults(), model -> 512);
        runtime.enterWorld(WORLD, SCOPE);

        runtime.restoreSession();
        backend.restore.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_OUT, null, null));

        assertEquals(ChatContracts.SignInState.SIGNED_OUT, runtime.view().signIn());
        assertTrue(runtime.view().errors().isEmpty(), runtime.view().errors().toString());
        assertEquals(0, backend.signIns);
    }

    @Test
    void signOutRacingRestoreWinsAndLateRestoreDoesNotResurrectSession() throws Exception {
        FakeBackend backend = new FakeBackend();
        var runtime = runtime(backend, captureFactory(), new AtomicReference<>(), new AtomicReference<>(),
                PackSageSettings.defaults(), model -> 512);
        runtime.enterWorld(WORLD, SCOPE);

        runtime.restoreSession();
        runtime.signOut();
        backend.restore.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));

        assertEquals(ChatContracts.SignInState.SIGNED_OUT, runtime.view().signIn());
        assertEquals(0, backend.catalogLoads);
    }

    @Test
    void cancelSignInRacingRestoreWins() throws Exception {
        FakeBackend backend = new FakeBackend();
        var runtime = runtime(backend, captureFactory(), new AtomicReference<>(), new AtomicReference<>(),
                PackSageSettings.defaults(), model -> 512);
        runtime.enterWorld(WORLD, SCOPE);

        runtime.restoreSession();
        runtime.cancelSignIn();
        backend.restore.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));

        assertEquals(ChatContracts.SignInState.SIGNED_OUT, runtime.view().signIn());
        assertEquals(0, backend.catalogLoads);
    }

    @Test
    void rejectedRestoredCredentialReturnsToSignedOutSoSignInCanRestart() throws Exception {
        FakeBackend backend = new FakeBackend();
        var runtime = runtime(backend, captureFactory(), new AtomicReference<>(), new AtomicReference<>(),
                PackSageSettings.defaults(), model -> 512);
        runtime.enterWorld(WORLD, SCOPE);

        runtime.restoreSession();
        backend.restore.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.completeExceptionally(new ModelBackend.AuthRejectedException("refresh rejected"));

        assertEquals(ChatContracts.SignInState.SIGNED_OUT, runtime.view().signIn());
        assertTrue(runtime.view().errors().stream().anyMatch(error -> error.contains("sign in again")));
        runtime.signIn();
        assertEquals(1, backend.signIns);
        assertEquals(ChatContracts.SignInState.SIGNING_IN, runtime.view().signIn());
    }

    @Test
    void transientCatalogFailureOfRestoredSessionKeepsSignedInWithError() throws Exception {
        FakeBackend backend = new FakeBackend();
        var runtime = runtime(backend, captureFactory(), new AtomicReference<>(), new AtomicReference<>(),
                PackSageSettings.defaults(), model -> 512);
        runtime.enterWorld(WORLD, SCOPE);

        runtime.restoreSession();
        backend.restore.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.completeExceptionally(new IllegalStateException("network down"));

        assertEquals(ChatContracts.SignInState.SIGNED_IN, runtime.view().signIn());
        assertTrue(runtime.view().errors().stream().anyMatch(error -> error.contains("Model catalog unavailable")));
    }

    @Test
    void selectedSessionsPublishImmediatelyBlockPendingSendsAndKeepTheOperationalWorld() throws Exception {
        for (String scenario : List.of("same", "other", "renamed")) {
            boolean otherWorld = scenario.equals("other");
            ChatContracts.WorldIdentity activeWorld = scenario.equals("renamed")
                    ? new ChatContracts.WorldIdentity(WORLD.worldOrServer(), "Renamed World") : WORLD;
            List<Runnable> pending = new ArrayList<>();
            AtomicBoolean defer = new AtomicBoolean();
            ConversationStore store = new ConversationStore(Files.createTempDirectory("packsage-session-test"), task -> {
                if (defer.get()) pending.add(task);
                else task.run();
            });
            ChatContracts.WorldIdentity selectedWorld = otherWorld
                    ? new ChatContracts.WorldIdentity("other", "Other World") : WORLD;
            Evidence.Scope historicalScope = otherWorld ? SCOPE
                    : new Evidence.Scope("world", "old-resources", "team", "runtime", 7);
            ChatContracts.Conversation selected = store.newConversation(selectedWorld).toCompletableFuture().join();
            Evidence.Source source = new Evidence.Source(historicalScope, "web", "test", "https://example.test/history",
                    historicalScope.requestGeneration(), Instant.EPOCH, Evidence.Activity.ACTIVE,
                    Evidence.Coverage.COMPLETE, Evidence.Authority.EXTERNAL, List.of());
            Evidence.NavigationTarget target = new Evidence.NavigationTarget(historicalScope, "web", source.sourceHandle(), source.sourceHandle());
            ChatContracts.Turn historical = new ChatContracts.Turn(new ChatContracts.RequestId("history"), "First question",
                    "Historical answer", List.of(), new ChatContracts.Context("historical context", null, historicalScope),
                    List.of(source), List.of(target), new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1);
            store.appendTurn(selectedWorld, historicalScope, selected.id(), historical).toCompletableFuture().join();
            if (!otherWorld) store.newConversation(WORLD).toCompletableFuture().join();
            FakeBackend backend = new FakeBackend();
            AtomicInteger captures = new AtomicInteger();
            AtomicInteger navigations = new AtomicInteger();
            AtomicReference<java.util.function.Supplier<Evidence.Scope>> operationalScope = new AtomicReference<>();
            AtomicReference<ChatContracts.PreparedRequest> prepared = new AtomicReference<>();
            var runtime = new AssistantRuntime(store, backend, current -> new GameplayContextCapture(() -> {
                captures.incrementAndGet();
                return new GameplayContextCapture.Observation("held", "target", "overworld", "1,2,3", "plains", "1", "block");
            }, ignored -> new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1}, Instant.EPOCH), DIRECT, current),
                    DIRECT, DIRECT, current -> {
                        operationalScope.set(current);
                        return new KnowledgeService(List.of(), current);
                    }, turn -> turn.tokenCount(), request -> {
                        prepared.set(request);
                        return new ModelBackend.Request(MODEL.id(), "instructions", List.of(new ModelBackend.Message(ModelBackend.Role.USER,
                                List.of(new ModelBackend.TextContent(request.submission().question())))), List.of(),
                                request.submission().searchMode(), request.submission().requestId().value(), 512);
                    }, ignored -> 512, ignored -> { }, ignored -> navigations.incrementAndGet());
            runtime.enterWorld(activeWorld, SCOPE);
            runtime.signIn();
            backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
            backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), true, true, 16_000, 2_048));
            var screen = runtime.openScreen();
            defer.set(true);

            runtime.openSession(new ChatContracts.SessionSummary(selectedWorld, selected.id(), "First question", "Historical answer", Instant.EPOCH));
            assertTrue(screen.view().readOnly());
            assertEquals(selected.id(), screen.view().conversation().id());
            assertEquals("Loading session...", screen.view().activity());
            runtime.send("too early", false, false);
            assertEquals(0, backend.starts);
            assertEquals(0, captures.get());
            assertEquals(SCOPE, operationalScope.get().get());
            while (!pending.isEmpty()) pending.removeFirst().run();

            assertEquals(otherWorld, screen.view().readOnly());
            assertEquals(activeWorld, screen.view().activeWorld());
            assertEquals(SCOPE, screen.view().context().scope());
            assertEquals(List.of(historical), screen.view().conversation().turns());
            assertTrue(screen.view().navigationTargets().contains(target));
            assertTrue(screen.view().sources().contains(source));
            runtime.navigate(target);
            assertEquals(0, navigations.get());
            assertTrue(screen.view().errors().stream().anyMatch(error -> error.contains(otherWorld ? "Join this session" : "stale")));
            runtime.send("continue", false, false);
            if (otherWorld) {
                assertEquals(0, backend.starts);
                assertEquals(0, captures.get());
            } else {
                assertEquals(1, backend.starts, runtime.view().errors().toString());
                assertEquals(1, captures.get());
                assertEquals(List.of(historical), prepared.get().history().turns());
                assertEquals(SCOPE.worldSession(), prepared.get().capturedContext().context().scope().worldSession());
                assertEquals(1, prepared.get().capturedContext().context().scope().requestGeneration());
                backend.emit(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), List.of(
                        new ModelBackend.AssistantOutput("answer", ModelBackend.OutputStatus.COMPLETED,
                                ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText("Fresh answer", List.of())))), null));
                while (!pending.isEmpty()) pending.removeFirst().run();
                assertEquals(selected.id(), screen.view().conversation().id());
                assertEquals("continue", screen.view().conversation().turns().getLast().question());
            }
            runtime.newChat();
            while (!pending.isEmpty()) pending.removeFirst().run();
            assertFalse(screen.view().readOnly());
            assertEquals(activeWorld, screen.view().conversation().world());
            assertTrue(screen.view().conversation().turns().isEmpty());
        }
    }

    @Test
    void delayedSessionLoadsCannotReplaceNewerSelectionNewChatWorldOrDisconnect() throws Exception {
        for (String transition : List.of("selection", "new-chat", "world", "disconnect")) {
            List<Runnable> pending = new ArrayList<>();
            AtomicBoolean defer = new AtomicBoolean();
            ConversationStore store = new ConversationStore(Files.createTempDirectory("packsage-session-race-test"), task -> {
                if (defer.get()) pending.add(task);
                else task.run();
            });
            ChatContracts.WorldIdentity otherWorld = new ChatContracts.WorldIdentity("other", "Other World");
            ChatContracts.Conversation old = store.newConversation(otherWorld).toCompletableFuture().join();
            ChatContracts.Conversation selected = store.newConversation(WORLD).toCompletableFuture().join();
            FakeBackend backend = new FakeBackend();
            var runtime = new AssistantRuntime(store, backend, captureFactory(), DIRECT, DIRECT,
                    current -> new KnowledgeService(List.of(), current),
                    turn -> turn.tokenCount(), ignored -> { throw new AssertionError("No model request expected"); },
                    ignored -> 512, ignored -> { }, ignored -> { });
            runtime.enterWorld(WORLD, SCOPE);
            var screen = runtime.openScreen();
            defer.set(true);
            runtime.openSession(new ChatContracts.SessionSummary(otherWorld, old.id(), "Old", "", Instant.EPOCH));
            switch (transition) {
                case "selection" -> runtime.openSession(new ChatContracts.SessionSummary(WORLD, selected.id(), "Selected", "", Instant.EPOCH));
                case "new-chat" -> runtime.newChat();
                case "world" -> runtime.enterWorld(new ChatContracts.WorldIdentity("next", "Next"),
                        new Evidence.Scope("next", "resources", "team", "runtime", 0));
                default -> runtime.disconnect();
            }
            ChatContracts.View disconnected = runtime.view();
            while (!pending.isEmpty()) pending.removeFirst().run();

            assertFalse(runtime.view().readOnly());
            assertFalse(runtime.view().conversation().id().equals(old.id()));
            if (transition.equals("selection")) assertEquals(selected.id(), screen.view().conversation().id());
            if (transition.equals("world")) assertEquals("next", screen.view().activeWorld().worldOrServer());
            if (transition.equals("disconnect")) assertEquals(disconnected, runtime.view());
        }
    }

    private static java.util.function.Function<java.util.function.Supplier<Evidence.Scope>, GameplayContextCapture> captureFactory() {
        return scope -> new GameplayContextCapture(
                () -> new GameplayContextCapture.Observation("held", "target", "overworld", "1,2,3", "plains", "1", "block"),
                ignored -> new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1}, Instant.EPOCH), DIRECT,
                scope);
    }

    @Test
    void modelCanSearchAndReadSavedHistoryBeyondItsAutomaticWindow() throws Exception {
        var store = new ConversationStore(Files.createTempDirectory("packsage-history-runtime"), DIRECT);
        var older = store.newConversation(WORLD).toCompletableFuture().join();
        store.appendTurn(WORLD, SCOPE, older.id(), new ChatContracts.Turn(new ChatContracts.RequestId("older"),
                "Our lantern plan", "Use the old lantern route", List.of(), new ChatContracts.Context("{}", null, SCOPE),
                List.of(), List.of(), new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null),
                40, Instant.EPOCH)).toCompletableFuture().join();
        var current = store.newConversation(WORLD).toCompletableFuture().join();
        store.appendTurn(WORLD, SCOPE, current.id(), new ChatContracts.Turn(new ChatContracts.RequestId("recent"),
                "A recent unrelated topic", "Different answer", List.of(), new ChatContracts.Context("{}", null, SCOPE),
                List.of(), List.of(), new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null),
                20, Instant.EPOCH)).toCompletableFuture().join();
        var backend = new FakeBackend();
        var failKnowledge = new AtomicBoolean();
        var runtime = new AssistantRuntime(store, backend, captureFactory(), DIRECT, DIRECT,
                scope -> {
                    if (failKnowledge.get()) throw new IllegalStateException("provider refresh failed");
                    return new KnowledgeService(List.of(new Toolbox() {
                        @Override public Metadata metadata() { return new Metadata("native", List.of(), true, List.of(), Map.of()); }
                        @Override public CompletionStage<Evidence.Result> planRecipe(PackSageTools.ReadRequest request, Context context) {
                            return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null));
                        }
                    }), scope);
                },
                GroundingInstructions::historyTextBudget, GroundingInstructions::build, model -> 512,
                ignored -> { }, ignored -> { });
        runtime.setSettings(new PackSageSettings(true, false, 1, 1));
        runtime.enterWorld(WORLD, SCOPE);
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), MODEL.imageInput(),
                MODEL.hostedSearch(), MODEL.contextTokens(), MODEL.outputTokens()));
        runtime.send("Recall the lantern plan", false, false);
        assertEquals(2, backend.requests.getFirst().input().size());
        assertEquals(List.of(new ModelBackend.TextContent("Recall the lantern plan")),
                ((ModelBackend.Message) backend.requests.getFirst().input().getLast()).content());
        assertTrue(backend.requests.getFirst().tools().stream().anyMatch(tool -> tool.name().equals("search_history")));
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                new ModelBackend.ToolCall("find-history", "search_history", "{\"text\":\"lantern\",\"limit\":8,\"cursor\":null}"),
                List.of(), null));
        assertEquals(1, backend.results.size());
        assertFalse(backend.results.getFirst().error(), backend.results.getFirst().serializedResult());
        var found = com.google.gson.JsonParser.parseString(backend.results.getFirst().serializedResult()).getAsJsonObject();
        assertEquals(1, found.getAsJsonArray("records").size());
        String reference = found.getAsJsonArray("records").get(0).getAsJsonObject().get("reference").getAsString();
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                new ModelBackend.ToolCall("read-history", "read_history", "{\"handles\":[\"" + reference + "\"],\"maxChars\":8000}"),
                List.of(), null));
        assertEquals(2, backend.results.size());
        assertFalse(backend.results.getLast().error(), backend.results.getLast().serializedResult());
        assertTrue(backend.results.getLast().serializedResult().contains("Use the old lantern route"));
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                new ModelBackend.ToolCall("normal", "plan_recipe", "{\"identity\":\"minecraft:torch\",\"quantity\":1}"),
                List.of(), null));
        assertEquals(3, backend.results.size());
        assertFalse(backend.results.getLast().error(), backend.results.getLast().serializedResult());
        runtime.stop();
        failKnowledge.set(true);
        runtime.enterWorld(new ChatContracts.WorldIdentity("another-world", "Another World"),
                new Evidence.Scope("another-world", "resources", "team", "runtime", 0));
        runtime.send("Recall the lantern plan", false, false);
        assertEquals(2, backend.requests.size());
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                new ModelBackend.ToolCall("foreign", "search_history", "{\"text\":\"lantern\"}"), List.of(), null));
        assertTrue(backend.results.getLast().error(), backend.results.getLast().serializedResult());
        assertFalse(backend.results.getLast().serializedResult().contains("Use the old lantern route"));
    }

    private static AssistantRuntime runtime(FakeBackend backend,
                                            java.util.function.Function<java.util.function.Supplier<Evidence.Scope>, GameplayContextCapture> captureFactory,
                                            AtomicReference<Evidence.Scope> scope, AtomicReference<ModelBackend.Request> request,
                                            PackSageSettings settings, java.util.function.ToIntFunction<ModelBackend.ModelInfo> budget) throws Exception {
        return runtime(backend, captureFactory, scope, request, new AtomicReference<>(), settings, budget, ignored -> { });
    }

    private static AssistantRuntime runtime(FakeBackend backend,
                                            java.util.function.Function<java.util.function.Supplier<Evidence.Scope>, GameplayContextCapture> captureFactory,
                                            AtomicReference<Evidence.Scope> scope, AtomicReference<ModelBackend.Request> request,
                                            AtomicReference<ChatContracts.PreparedRequest> preparedRequest,
                                            PackSageSettings settings, java.util.function.ToIntFunction<ModelBackend.ModelInfo> budget) throws Exception {
        return runtime(backend, captureFactory, scope, request, preparedRequest, settings, budget, ignored -> { });
    }

    private static AssistantRuntime runtime(FakeBackend backend,
                                            java.util.function.Function<java.util.function.Supplier<Evidence.Scope>, GameplayContextCapture> captureFactory,
                                            AtomicReference<Evidence.Scope> scope, AtomicReference<ModelBackend.Request> request,
                                            AtomicReference<ChatContracts.PreparedRequest> preparedRequest,
                                            PackSageSettings settings, java.util.function.ToIntFunction<ModelBackend.ModelInfo> budget,
                                            java.util.function.Consumer<Boolean> pauseGame) throws Exception {
        return runtime(backend, captureFactory, scope, request, preparedRequest, settings, budget, pauseGame, ignored -> { });
    }

    private static AssistantRuntime runtime(FakeBackend backend,
                                            java.util.function.Function<java.util.function.Supplier<Evidence.Scope>, GameplayContextCapture> captureFactory,
                                            AtomicReference<Evidence.Scope> scope, AtomicReference<ModelBackend.Request> request,
                                            AtomicReference<ChatContracts.PreparedRequest> preparedRequest,
                                            PackSageSettings settings, java.util.function.ToIntFunction<ModelBackend.ModelInfo> budget,
                                            java.util.function.Consumer<Boolean> pauseGame,
                                            java.util.function.Consumer<String> externalNavigation) throws Exception {
        return runtime(backend, captureFactory, scope, request, preparedRequest, settings, budget, pauseGame,
                externalNavigation, current -> new KnowledgeService(List.of(), current), DIRECT);
    }

    private static AssistantRuntime runtime(FakeBackend backend,
                                            java.util.function.Function<java.util.function.Supplier<Evidence.Scope>, GameplayContextCapture> captureFactory,
                                            AtomicReference<Evidence.Scope> scope, AtomicReference<ModelBackend.Request> request,
                                            AtomicReference<ChatContracts.PreparedRequest> preparedRequest,
                                            PackSageSettings settings, java.util.function.ToIntFunction<ModelBackend.ModelInfo> budget,
                                            java.util.function.Consumer<Boolean> pauseGame,
                                            java.util.function.Consumer<String> externalNavigation,
                                            java.util.function.Function<java.util.function.Supplier<Evidence.Scope>, KnowledgeService> knowledgeFactory,
                                            Executor storeExecutor) throws Exception {
        var directory = Files.createTempDirectory("packsage-runtime-test");
        var runtime = new AssistantRuntime(new ConversationStore(directory, storeExecutor), backend, captureFactory, DIRECT, DIRECT,
                knowledgeFactory,
                turn -> turn.tokenCount(), prepared -> {
                    ModelBackend.Request result = new ModelBackend.Request(prepared.submission().selectedModel().id(),
                            "instructions", List.of(new ModelBackend.Message(ModelBackend.Role.USER,
                            List.of(new ModelBackend.TextContent(prepared.submission().question())))), List.of(),
                            prepared.submission().searchMode(), prepared.submission().requestId().value(),
                            prepared.submission().outputBudget(), prepared.submission().reasoningEffort());
                    request.set(result);
                    preparedRequest.set(prepared);
                    return result;
                }, budget, pauseGame, externalNavigation);
        runtime.setSettings(settings);
        return runtime;
    }

    private static final class FakeBackend implements ModelBackend {
        private final CompletableFuture<AuthState> signIn = new CompletableFuture<>();
        private CompletableFuture<AuthState> restore = new CompletableFuture<>();
        private int signIns;
        private int catalogLoads;
        private final CompletableFuture<Capabilities> catalog = new CompletableFuture<>();
        private final List<Request> requests = new ArrayList<>();
        private final List<ToolResult> results = new ArrayList<>();
        private final List<Message> steered = new ArrayList<>();
        private java.util.function.Consumer<Event> events;
        private int starts;
        private int submissions;

        @Override
        public CompletionStage<AuthState> signIn() {
            signIns++;
            return signIn;
        }

        @Override
        public CompletionStage<AuthState> restoreSession() {
            return restore;
        }

        @Override
        public void cancelSignIn() {
            signIn.cancel(false);
        }

        @Override
        public void signOut() {
        }

        @Override
        public CompletionStage<Capabilities> capabilities() {
            catalogLoads++;
            return catalog;
        }

        @Override
        public Exchange start(Request request, java.util.function.Consumer<Event> events) {
            starts++;
            requests.add(request);
            this.events = events;
            return new Exchange() {
                @Override public void submit(ToolResult result) { submissions++; results.add(result); }
                @Override public boolean steer(Message message) { steered.add(message); return true; }
                @Override public void cancel() { }
            };
        }

        private void emit(Event event) {
            events.accept(event);
        }
    }
}
