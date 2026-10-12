package dev.packsage.chat;

import dev.packsage.client.GameplayContextCapture;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeService;
import dev.packsage.model.ModelBackend;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void secondSendIsRejectedBeforeScopeAdvances() throws Exception {
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
        assertTrue(runtime.view().active());
        assertEquals(1, runtime.view().context().scope().requestGeneration());
        assertTrue(runtime.view().errors().stream().anyMatch(error -> error.contains("already active")));
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
    void worldSwitchRebuildsKnowledgeAndDropsLateToolResult() throws Exception {
        FakeBackend backend = new FakeBackend();
        AtomicInteger factoryCalls = new AtomicInteger();
        CompletableFuture<Evidence.Result> pendingTool = new CompletableFuture<>();
        var knowledgeFactory = (java.util.function.Function<java.util.function.Supplier<Evidence.Scope>, KnowledgeService>) current -> {
            int factoryCall = factoryCalls.getAndIncrement();
            return new KnowledgeService(List.of(), current,
                    ignored -> factoryCall == 1 ? pendingTool : CompletableFuture.completedFuture(
                            new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null)),
                    (ignored, scopeValue) -> CompletableFuture.completedFuture(
                            new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null)));
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
                new ModelBackend.ToolCall("call", "plan_recipe", "{\"identity\":\"item\",\"quantity\":1}"),
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
                current -> new KnowledgeService(List.of(), current,
                        ignored -> CompletableFuture.completedFuture(
                                new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null)),
                        (ignored, scopeValue) -> CompletableFuture.completedFuture(
                                new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null))),
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

    private static java.util.function.Function<java.util.function.Supplier<Evidence.Scope>, GameplayContextCapture> captureFactory() {
        return scope -> new GameplayContextCapture(
                () -> new GameplayContextCapture.Observation("held", "target", "overworld", "1,2,3", "plains", "1", "block"),
                ignored -> new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1}, Instant.EPOCH), DIRECT,
                scope);
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
                externalNavigation, current -> new KnowledgeService(List.of(), current,
                        ignored -> CompletableFuture.completedFuture(
                                new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null)),
                        (ignored, scopeValue) -> CompletableFuture.completedFuture(
                                new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null))), DIRECT);
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
        return new AssistantRuntime(new ConversationStore(directory, storeExecutor), backend, captureFactory, DIRECT, DIRECT,
                knowledgeFactory,
                turn -> turn.tokenCount(), prepared -> {
                    ModelBackend.Request result = new ModelBackend.Request(prepared.submission().selectedModel().id(),
                            "instructions", List.of(new ModelBackend.Message(ModelBackend.Role.USER,
                            List.of(new ModelBackend.TextContent(prepared.submission().question())))), List.of(),
                            prepared.submission().searchMode(), prepared.submission().requestId().value(),
                            prepared.submission().outputBudget());
                    request.set(result);
                    preparedRequest.set(prepared);
                    return result;
                }, budget, pauseGame, externalNavigation);
    }

    private static final class FakeBackend implements ModelBackend {
        private final CompletableFuture<AuthState> signIn = new CompletableFuture<>();
        private final CompletableFuture<Capabilities> catalog = new CompletableFuture<>();
        private final List<Request> requests = new ArrayList<>();
        private java.util.function.Consumer<Event> events;
        private int starts;
        private int submissions;

        @Override
        public CompletionStage<AuthState> signIn() {
            return signIn;
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
            return catalog;
        }

        @Override
        public Exchange start(Request request, java.util.function.Consumer<Event> events) {
            starts++;
            requests.add(request);
            this.events = events;
            return new Exchange() {
                @Override public void submit(ToolResult result) { submissions++; }
                @Override public void cancel() { }
            };
        }

        private void emit(Event event) {
            events.accept(event);
        }
    }
}
