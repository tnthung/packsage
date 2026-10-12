package dev.packsage.chat;

import dev.packsage.client.AssistantScreen;
import dev.packsage.client.GameplayContextCapture;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeService;
import dev.packsage.model.ModelBackend;
import dev.packsage.model.PackSageTools;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

public final class AssistantRuntime implements ChatActions {
    private static final Evidence.Scope EMPTY_SCOPE = new Evidence.Scope("none", "none", "none", "none", 0);

    private final ModelBackend backend;
    private final GameplayContextCapture capture;
    private final Executor controllerExecutor;
    private final Executor clientExecutor;
    private final Function<ChatContracts.PreparedRequest, ModelBackend.Request> requestBuilder;
    private final ToIntFunction<ModelBackend.ModelInfo> outputBudget;
    private final Consumer<Boolean> pauseGame;
    private final KnowledgeService knowledge;
    private final PackSageTools tools;
    private final Object stateLock = new Object();
    private final List<String> runtimeErrors = new ArrayList<>();
    private final RequestController controller;
    private ChatContracts.WorldIdentity world;
    private Evidence.Scope scope = EMPTY_SCOPE;
    private ModelBackend.Capabilities capabilities = new ModelBackend.Capabilities(List.of(), null, null, null, null, null);
    private ChatContracts.SignInState signIn = ChatContracts.SignInState.SIGNED_OUT;
    private PackSageSettings settings = PackSageSettings.defaults();
    private AssistantScreen currentScreen;
    private long worldEpoch;
    private long authEpoch;
    private boolean requestedPaused;

    public AssistantRuntime(ConversationStore store, ModelBackend backend,
                            Function<Supplier<Evidence.Scope>, GameplayContextCapture> captureFactory,
                            Executor controllerExecutor, Executor clientExecutor,
                            Function<Supplier<Evidence.Scope>, KnowledgeService> knowledgeFactory,
                            Function<ChatContracts.Turn, Integer> historyCounter,
                            Function<ChatContracts.PreparedRequest, ModelBackend.Request> requestBuilder,
                            ToIntFunction<ModelBackend.ModelInfo> outputBudget, Consumer<Boolean> pauseGame) {
        Objects.requireNonNull(store);
        this.backend = Objects.requireNonNull(backend);
        this.controllerExecutor = Objects.requireNonNull(controllerExecutor);
        this.clientExecutor = Objects.requireNonNull(clientExecutor);
        Objects.requireNonNull(historyCounter);
        this.requestBuilder = Objects.requireNonNull(requestBuilder);
        this.outputBudget = Objects.requireNonNull(outputBudget);
        this.pauseGame = Objects.requireNonNull(pauseGame);
        this.knowledge = Objects.requireNonNull(knowledgeFactory).apply(this::currentScope);
        this.tools = new PackSageTools(this.knowledge::execute);
        this.capture = Objects.requireNonNull(Objects.requireNonNull(captureFactory).apply(this::currentScope));
        this.controller = new RequestController(store, backend, controllerExecutor,
                capture::capture, capture::cancel, ignored -> new ChatContracts.EvidenceSelection(List.of(), List.of()),
                this::executeTool, historyCounter, requestBuilder, this::publishControllerView);
    }

    public void enterWorld(ChatContracts.WorldIdentity world, Evidence.Scope scope) {
        Objects.requireNonNull(world);
        Objects.requireNonNull(scope);
        long epoch;
        synchronized (stateLock) {
            epoch = ++worldEpoch;
            this.world = world;
            this.scope = scope;
            runtimeErrors.clear();
            requestedPaused = settings.openPaused();
        }
        controller.worldChanged();
        publishCurrentView(epoch);
    }

    public void invalidate(Evidence.Scope newScope) {
        Objects.requireNonNull(newScope);
        synchronized (stateLock) {
            scope = newScope;
        }
        controller.invalidate(newScope.worldSession());
        publishCurrentView(worldEpoch);
    }

    public void disconnect() {
        synchronized (stateLock) {
            worldEpoch++;
            world = null;
            scope = EMPTY_SCOPE;
            requestedPaused = false;
        }
        controller.disconnect();
    }

    public void onRenderGuiPost() {
        capture.onRenderGuiPost();
    }

    public AssistantScreen openScreen(Runnable settingsOpener) {
        Objects.requireNonNull(settingsOpener);
        AssistantScreen screen = new AssistantScreen(this);
        screen.setSettingsOpener(settingsOpener);
        boolean pause;
        synchronized (stateLock) {
            currentScreen = screen;
            requestedPaused = settings.openPaused();
            pause = requestedPaused;
        }
        if (pause) setPaused(true);
        return screen;
    }

    public AssistantScreen openScreen() {
        return openScreen(() -> { });
    }

    public void setSettings(PackSageSettings settings) {
        synchronized (stateLock) {
            this.settings = Objects.requireNonNull(settings);
        }
    }

    @Override
    public ChatContracts.View view() {
        return wrap(controller.view());
    }

    @Override
    public void signIn() {
        long epoch;
        synchronized (stateLock) {
            if (signIn == ChatContracts.SignInState.SIGNING_IN || signIn == ChatContracts.SignInState.SIGNED_IN) return;
            epoch = ++authEpoch;
            signIn = ChatContracts.SignInState.SIGNING_IN;
        }
        publishCurrentView(worldEpoch);
        CompletionStage<ModelBackend.AuthState> attempt;
        try {
            attempt = backend.signIn();
        } catch (RuntimeException failure) {
            finishSignIn(epoch, null, failure);
            return;
        }
        attempt.whenCompleteAsync((state, failure) -> finishSignIn(epoch, state, failure), clientExecutor);
    }

    @Override
    public void cancelSignIn() {
        boolean cancel;
        synchronized (stateLock) {
            cancel = signIn == ChatContracts.SignInState.SIGNING_IN;
            if (cancel) {
                authEpoch++;
                signIn = ChatContracts.SignInState.SIGNED_OUT;
                capabilities = emptyCapabilities();
            }
        }
        if (cancel) {
            try {
                backend.cancelSignIn();
            } catch (RuntimeException failure) {
                addError("Sign-in cancellation failed: " + failureMessage(failure));
            }
            publishCurrentView(worldEpoch);
        }
    }

    @Override
    public void signOut() {
        synchronized (stateLock) {
            authEpoch++;
            signIn = ChatContracts.SignInState.SIGNED_OUT;
            capabilities = emptyCapabilities();
        }
        controller.stop();
        try {
            backend.signOut();
        } catch (RuntimeException failure) {
            addError("Sign-out failed: " + failureMessage(failure));
        }
        publishCurrentView(worldEpoch);
    }

    @Override
    public void send(String question, boolean includeScreenshot, boolean explicitWebSearch) {
        Objects.requireNonNull(question);
        ChatContracts.Submission submission;
        long epoch;
        synchronized (stateLock) {
            if (question.isBlank()) {
                runtimeErrors.add("Question cannot be blank");
                return;
            }
            if (world == null) {
                runtimeErrors.add("Cannot send without an active world");
                return;
            }
            if (signIn != ChatContracts.SignInState.SIGNED_IN) {
                runtimeErrors.add("Sign in before sending a question");
                return;
            }
            ModelBackend.ModelInfo selected = selectedModel(capabilities);
            if (selected == null) {
                runtimeErrors.add("No selected model is available in the account model catalog");
                return;
            }
            Evidence.Scope nextScope = new Evidence.Scope(scope.worldSession(), scope.resourcesLocale(),
                    scope.playerTeam(), scope.providerRuntime(), scope.requestGeneration() + 1);
            scope = nextScope;
            ModelBackend.SearchMode searchMode = searchMode(selected, settings.automaticWebSearch(), explicitWebSearch);
            if (explicitWebSearch && searchMode == ModelBackend.SearchMode.DISABLED) {
                runtimeErrors.add("Hosted web search is unavailable for the selected model; using local evidence");
            } else if (searchMode == ModelBackend.SearchMode.DISABLED && selected.hostedSearch() == null) {
                runtimeErrors.add("Hosted search capability is unknown; using local evidence");
            }
            if (includeScreenshot && Boolean.FALSE.equals(selected.imageInput())) {
                runtimeErrors.add("Screenshot omitted from the model request because image input is unsupported");
            } else if (includeScreenshot && selected.imageInput() == null) {
                runtimeErrors.add("Screenshot capability is unknown; the model request will omit it unless verified");
            }
            int budget;
            try {
                budget = outputBudget.applyAsInt(selected);
            } catch (RuntimeException failure) {
                runtimeErrors.add("Output budget is unavailable: " + failureMessage(failure));
                return;
            }
            if (budget < 1) {
                runtimeErrors.add("Output budget is unavailable");
                return;
            }
            try {
                submission = new ChatContracts.Submission(new ChatContracts.RequestId(UUID.randomUUID().toString()), world,
                        nextScope, question, includeScreenshot, settings.historyLimits(), selected, searchMode, budget);
            } catch (RuntimeException failure) {
                runtimeErrors.add("Request settings are invalid: " + failureMessage(failure));
                return;
            }
            epoch = worldEpoch;
        }
        try {
            controller.begin(submission).whenCompleteAsync((ignored, failure) -> {
                if (failure != null) publishCurrentView(epoch);
            }, controllerExecutor);
        } catch (RuntimeException failure) {
            addError("Request could not start: " + failureMessage(failure));
        }
        publishCurrentView(epoch);
    }

    @Override
    public void stop() {
        controller.stop();
    }

    @Override
    public void newChat() {
        controller.newChat();
    }

    @Override
    public void setPaused(boolean paused) {
        synchronized (stateLock) {
            requestedPaused = paused;
        }
        try {
            pauseGame.accept(paused);
        } catch (RuntimeException failure) {
            addError("Pause state could not be changed: " + failureMessage(failure));
        }
        publishCurrentView(worldEpoch);
    }

    @Override
    public void navigate(Evidence.NavigationTarget target) {
        if (target == null) return;
        ChatContracts.View current = view();
        if (current.navigationTargets().stream().noneMatch(value -> value.equals(target))
                || current.sources().stream().noneMatch(source -> source.scope().equals(target.scope())
                && source.providerId().equals(target.providerId()) && source.sourceHandle().equals(target.sourceHandle()))) {
            addError("Navigation target is stale");
            return;
        }
        Evidence.Scope expected;
        synchronized (stateLock) {
            expected = scope;
        }
        if (!target.scope().equals(expected)) {
            addError("Navigation target is stale");
            return;
        }
        Evidence.NavigationResult result;
        try {
            result = knowledge.open(target, expected);
        } catch (RuntimeException failure) {
            addError("Navigation failed: " + failureMessage(failure));
            return;
        }
        if (result.status() != Evidence.NavigationStatus.OPENED) addError(
                "Navigation " + result.status().name().toLowerCase() + ": "
                        + (result.detail() == null ? "target unavailable" : result.detail()));
        publishCurrentView(worldEpoch);
    }

    private CompletionStage<ChatContracts.ToolResponse> executeTool(ChatContracts.ToolRequest request) {
        return tools.execute(request.call(), request.submission().scope()).thenApply(response -> {
            List<Evidence.Source> sources = response.records().stream().map(Evidence.Record::source).distinct().toList();
            List<Evidence.NavigationTarget> targets = response.records().stream().flatMap(record -> record.targets().stream())
                    .distinct().toList();
            return new ChatContracts.ToolResponse(response.result(), new ChatContracts.EvidenceSelection(sources, targets));
        });
    }

    private void finishSignIn(long epoch, ModelBackend.AuthState state, Throwable failure) {
        synchronized (stateLock) {
            if (authEpoch != epoch || signIn != ChatContracts.SignInState.SIGNING_IN) return;
            if (failure != null) {
                signIn = ChatContracts.SignInState.FAILED;
                runtimeErrors.add("Sign-in failed: " + failureMessage(failure));
            } else if (state == null || state.status() == ModelBackend.AuthStatus.FAILED
                    || state.status() == ModelBackend.AuthStatus.EXPIRED) {
                signIn = ChatContracts.SignInState.FAILED;
                runtimeErrors.add(state == null || state.detail() == null ? "Sign-in failed" : state.detail());
            } else if (state.status() != ModelBackend.AuthStatus.SIGNED_IN) {
                signIn = ChatContracts.SignInState.SIGNED_OUT;
                runtimeErrors.add(state.detail() == null ? "Sign-in was not completed" : state.detail());
            } else {
                signIn = ChatContracts.SignInState.SIGNED_IN;
                capabilities = emptyCapabilities();
            }
        }
        publishCurrentView(worldEpoch);
        if (failure == null && state != null && state.status() == ModelBackend.AuthStatus.SIGNED_IN) loadCapabilities(epoch);
    }

    private void loadCapabilities(long epoch) {
        CompletionStage<ModelBackend.Capabilities> loading;
        try {
            loading = backend.capabilities();
        } catch (RuntimeException failure) {
            finishCapabilities(epoch, null, failure);
            return;
        }
        loading.whenCompleteAsync((found, failure) -> finishCapabilities(epoch, found, failure), clientExecutor);
    }

    private void finishCapabilities(long epoch, ModelBackend.Capabilities found, Throwable failure) {
        synchronized (stateLock) {
            if (authEpoch != epoch || signIn != ChatContracts.SignInState.SIGNED_IN) return;
            if (failure != null || found == null) {
                runtimeErrors.add("Model catalog unavailable: " + failureMessage(failure == null
                        ? new IllegalStateException("empty catalog response") : failure));
                return;
            }
            capabilities = found;
            if (selectedModel(found) == null) runtimeErrors.add("No selected model is available in the account model catalog");
        }
        publishCurrentView(worldEpoch);
    }

    private void publishControllerView(ChatContracts.View view) {
        ChatContracts.WorldIdentity expectedWorld;
        Evidence.Scope expectedScope;
        long epoch;
        synchronized (stateLock) {
            expectedWorld = world;
            expectedScope = scope;
            epoch = worldEpoch;
        }
        if (expectedWorld == null || !expectedWorld.equals(view.conversation().world())) return;
        if (!"none".equals(view.context().scope().worldSession())
                && !expectedScope.worldSession().equals(view.context().scope().worldSession())) return;
        long callbackEpoch = epoch;
        clientExecutor.execute(() -> {
            synchronized (stateLock) {
                if (callbackEpoch != worldEpoch || currentScreen == null || world == null
                        || !world.equals(view.conversation().world())) return;
            }
            currentScreen.refresh(wrap(view));
        });
    }

    private void publishCurrentView(long epoch) {
        clientExecutor.execute(() -> {
            synchronized (stateLock) {
                if (epoch != worldEpoch || currentScreen == null) return;
            }
            currentScreen.refresh(view());
        });
    }

    private ChatContracts.View wrap(ChatContracts.View view) {
        ChatContracts.SignInState currentSignIn;
        boolean paused;
        List<String> errors;
        synchronized (stateLock) {
            currentSignIn = signIn;
            paused = requestedPaused;
            errors = new ArrayList<>(view.errors());
            errors.addAll(runtimeErrors);
        }
        return new ChatContracts.View(view.active(), currentSignIn, paused, view.conversation(), view.context(),
                view.sources(), view.navigationTargets(), errors);
    }

    private Evidence.Scope currentScope() {
        synchronized (stateLock) {
            return scope;
        }
    }

    private void addError(String error) {
        synchronized (stateLock) {
            runtimeErrors.add(error);
        }
        publishCurrentView(worldEpoch);
    }

    private static ModelBackend.Capabilities emptyCapabilities() {
        return new ModelBackend.Capabilities(List.of(), null, null, null, null, null);
    }

    private static ModelBackend.ModelInfo selectedModel(ModelBackend.Capabilities capabilities) {
        if (capabilities.selectedModelId() == null) return null;
        return capabilities.modelCatalog().stream().filter(model -> model.id().equals(capabilities.selectedModelId()))
                .findFirst().orElse(null);
    }

    private static ModelBackend.SearchMode searchMode(ModelBackend.ModelInfo model, boolean automatic, boolean explicit) {
        if (!Boolean.TRUE.equals(model.hostedSearch())) return ModelBackend.SearchMode.DISABLED;
        if (explicit) return ModelBackend.SearchMode.EXPLICIT_REQUEST;
        return automatic ? ModelBackend.SearchMode.AUTOMATIC : ModelBackend.SearchMode.EXPLICIT_ONLY;
    }

    private static String failureMessage(Throwable failure) {
        if (failure == null) return "unknown failure";
        Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        return cause.getMessage() == null ? cause.getClass().getName() : cause.getMessage();
    }
}
