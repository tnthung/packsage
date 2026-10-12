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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantRuntimeTest {
    private static final Executor DIRECT = Runnable::run;
    private static final ChatContracts.WorldIdentity WORLD = new ChatContracts.WorldIdentity("world", "World");
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "resources", "team", "runtime", 0);
    private static final ModelBackend.ModelInfo MODEL = new ModelBackend.ModelInfo("catalog-model", "Catalog Model",
            true, true, 16_000, 2_048);

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
        var directory = Files.createTempDirectory("packsage-runtime-test");
        return new AssistantRuntime(new ConversationStore(directory, DIRECT), backend, captureFactory, DIRECT, DIRECT,
                current -> new KnowledgeService(List.of(), current, ignored -> CompletableFuture.completedFuture(
                        new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null)),
                        (ignored, scopeValue) -> CompletableFuture.completedFuture(
                                new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null))),
                turn -> turn.tokenCount(), prepared -> {
                    ModelBackend.Request result = new ModelBackend.Request(prepared.submission().selectedModel().id(),
                            "instructions", List.of(new ModelBackend.Message(ModelBackend.Role.USER,
                            List.of(new ModelBackend.TextContent(prepared.submission().question())))), List.of(),
                            prepared.submission().searchMode(), prepared.submission().requestId().value(),
                            prepared.submission().outputBudget());
                    request.set(result);
                    preparedRequest.set(prepared);
                    return result;
                }, budget, pauseGame);
    }

    private static final class FakeBackend implements ModelBackend {
        private final CompletableFuture<AuthState> signIn = new CompletableFuture<>();
        private final CompletableFuture<Capabilities> catalog = new CompletableFuture<>();
        private final List<Request> requests = new ArrayList<>();
        private int starts;

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
            return new Exchange() {
                @Override public void submit(ToolResult result) { }
                @Override public void cancel() { }
            };
        }
    }
}
