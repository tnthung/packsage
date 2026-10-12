package dev.packsage.chat;

import dev.packsage.client.AssistantScreen;
import dev.packsage.client.GameplayContextCapture;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeService;
import dev.packsage.model.ModelBackend;
import dev.packsage.model.PackSageTools;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
    private final ConversationStore store;
    private final GameplayContextCapture capture;
    private final Executor controllerExecutor;
    private final Executor clientExecutor;
    private final Function<ChatContracts.PreparedRequest, ModelBackend.Request> requestBuilder;
    private final ToIntFunction<ModelBackend.ModelInfo> outputBudget;
    private final Consumer<Boolean> pauseGame;
    private final Consumer<String> externalNavigation;
    private final Function<Supplier<Evidence.Scope>, KnowledgeService> knowledgeFactory;
    private KnowledgeService knowledge;
    private PackSageTools tools;
    private final Object stateLock = new Object();
    private final Object sendLock = new Object();
    private final List<String> runtimeErrors = new ArrayList<>();
    private final RequestController controller;
    private ChatContracts.WorldIdentity world;
    private Evidence.Scope scope = EMPTY_SCOPE;
    private ModelBackend.Capabilities capabilities = new ModelBackend.Capabilities(List.of(), null, null, null, null, null);
    private ChatContracts.SignInState signIn = ChatContracts.SignInState.SIGNED_OUT;
    private PackSageSettings settings = PackSageSettings.defaults();
    private AssistantScreen currentScreen;
    private long worldEpoch;
    private long publicationEpoch;
    private long authEpoch;
    private boolean requestedPaused;
    private boolean newChatPending;
    private ChatContracts.Conversation readOnlyConversation;
    private boolean sessionLoading;
    private long sessionGeneration;

    public AssistantRuntime(ConversationStore store, ModelBackend backend,
                            Function<Supplier<Evidence.Scope>, GameplayContextCapture> captureFactory,
                            Executor controllerExecutor, Executor clientExecutor,
                            Function<Supplier<Evidence.Scope>, KnowledgeService> knowledgeFactory,
                            Function<ChatContracts.Turn, Integer> historyCounter,
                            Function<ChatContracts.PreparedRequest, ModelBackend.Request> requestBuilder,
                            ToIntFunction<ModelBackend.ModelInfo> outputBudget, Consumer<Boolean> pauseGame,
                            Consumer<String> externalNavigation) {
        this.store = Objects.requireNonNull(store);
        this.backend = Objects.requireNonNull(backend);
        this.controllerExecutor = Objects.requireNonNull(controllerExecutor);
        this.clientExecutor = Objects.requireNonNull(clientExecutor);
        Objects.requireNonNull(historyCounter);
        this.requestBuilder = Objects.requireNonNull(requestBuilder);
        this.outputBudget = Objects.requireNonNull(outputBudget);
        this.pauseGame = Objects.requireNonNull(pauseGame);
        this.externalNavigation = Objects.requireNonNull(externalNavigation);
        this.knowledgeFactory = Objects.requireNonNull(knowledgeFactory);
        this.knowledge = Objects.requireNonNull(this.knowledgeFactory.apply(this::currentScope));
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
            publicationEpoch++;
            this.world = world;
            this.scope = scope;
            readOnlyConversation = null;
            sessionLoading = false;
            sessionGeneration++;
            runtimeErrors.clear();
            requestedPaused = settings.openPaused();
            newChatPending = false;
        }
        controller.openWorld(world, scope);
        KnowledgeService nextKnowledge;
        try {
            nextKnowledge = Objects.requireNonNull(knowledgeFactory.apply(this::currentScope));
        } catch (RuntimeException failure) {
            synchronized (stateLock) {
                if (epoch == worldEpoch && world.equals(this.world) && scope.equals(this.scope)) {
                    runtimeErrors.add("Knowledge providers could not be refreshed: " + failureMessage(failure));
                }
            }
            publishCurrentView(epoch);
            return;
        }
        PackSageTools nextTools = new PackSageTools(nextKnowledge::execute);
        synchronized (stateLock) {
            if (epoch != worldEpoch || !world.equals(this.world) || !scope.equals(this.scope)) return;
            knowledge = nextKnowledge;
            tools = nextTools;
        }
        publishCurrentView(epoch);
    }

    public void invalidate(Evidence.Scope newScope) {
        Objects.requireNonNull(newScope);
        synchronized (stateLock) {
            publicationEpoch++;
            scope = newScope;
            newChatPending = false;
            readOnlyConversation = null;
            sessionLoading = false;
            sessionGeneration++;
        }
        controller.invalidate(newScope.worldSession());
        publishCurrentView(worldEpoch);
    }

    public void disconnect() {
        synchronized (stateLock) {
            worldEpoch++;
            publicationEpoch++;
            world = null;
            scope = EMPTY_SCOPE;
            requestedPaused = false;
            newChatPending = false;
            readOnlyConversation = null;
            sessionLoading = false;
            sessionGeneration++;
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
    public CompletionStage<List<ChatContracts.SessionSummary>> sessions() {
        return store.sessions();
    }

    @Override
    public void openSession(ChatContracts.SessionSummary session) {
        Objects.requireNonNull(session);
        long requestGeneration;
        long epoch;
        synchronized (stateLock) {
            if (world == null) return;
            requestGeneration = ++sessionGeneration;
            publicationEpoch++;
            epoch = worldEpoch;
            readOnlyConversation = new ChatContracts.Conversation(session.id(), session.world(), List.of(),
                    new ChatContracts.Outcome(ChatContracts.OutcomeStatus.ACTIVE, null));
            sessionLoading = true;
        }
        controller.stop();
        publishCurrentView(epoch);
        store.openConversation(session.world(), session.id()).whenCompleteAsync((conversation, failure) -> {
            synchronized (stateLock) {
                if (requestGeneration != sessionGeneration || epoch != worldEpoch || world == null) return;
                sessionLoading = false;
                if (failure != null) {
                    readOnlyConversation = null;
                    runtimeErrors.add("Session could not be opened: " + failureMessage(failure));
                } else if (world.equals(conversation.world())) {
                    readOnlyConversation = null;
                    controller.showConversation(conversation, scope);
                } else {
                    readOnlyConversation = conversation;
                }
            }
            publishCurrentView(epoch);
        }, clientExecutor);
    }

    @Override
    public ChatContracts.AccountInfo account() {
        boolean signedIn;
        synchronized (stateLock) {
            signedIn = signIn == ChatContracts.SignInState.SIGNED_IN;
        }
        return new ChatContracts.AccountInfo(backend.providerInfo(), signedIn ? "Connected" : "");
    }

    public List<ModelBackend.ModelInfo> modelCatalog() {
        synchronized (stateLock) {
            return capabilities.modelCatalog();
        }
    }

    public String providerSelectedModelId() {
        synchronized (stateLock) {
            return capabilities.selectedModelId();
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
            finishSignIn(epoch, null, failure, false);
            return;
        }
        attempt.whenCompleteAsync((state, failure) -> finishSignIn(epoch, state, failure, false), clientExecutor);
    }

    public void restoreSession() {
        long epoch;
        synchronized (stateLock) {
            if (signIn != ChatContracts.SignInState.SIGNED_OUT) return;
            epoch = ++authEpoch;
            signIn = ChatContracts.SignInState.SIGNING_IN;
        }
        publishCurrentView(worldEpoch);
        CompletionStage<ModelBackend.AuthState> attempt;
        try {
            attempt = backend.restoreSession();
        } catch (RuntimeException failure) {
            finishSignIn(epoch, null, failure, true);
            return;
        }
        attempt.whenCompleteAsync((state, failure) -> finishSignIn(epoch, state, failure, true), clientExecutor);
    }

    @Override
    public void cancelSignIn() {
        boolean cancel;
        synchronized (stateLock) {
            cancel = signIn == ChatContracts.SignInState.SIGNING_IN;
            if (cancel) {
                authEpoch++;
                signIn = ChatContracts.SignInState.SIGNED_OUT;
                capabilities = new ModelBackend.Capabilities(List.of(), null, null, null, null, null);
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
            capabilities = new ModelBackend.Capabilities(List.of(), null, null, null, null, null);
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
        synchronized (sendLock) {
            ChatContracts.Submission submission;
            long epoch;
            Evidence.Scope previousScope;
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
                if (controller.view().active()) {
                    runtimeErrors.add("A request is already active");
                    return;
                }
                if (sessionLoading || readOnlyConversation != null) {
                    runtimeErrors.add(sessionLoading ? "Session is still loading"
                            : "This session is read-only until its world is active");
                    publishCurrentView(worldEpoch);
                    return;
                }
                String configuredModelId = settings.modelId();
                ModelBackend.ModelInfo selected = capabilities.modelCatalog().stream()
                        .filter(model -> model.id().equals(configuredModelId == null ? capabilities.selectedModelId() : configuredModelId))
                        .findFirst().orElse(null);
                if (selected == null) {
                    runtimeErrors.add(configuredModelId == null
                            ? "No selected model is available in the account model catalog"
                            : "Configured model is unavailable in the account model catalog: " + configuredModelId);
                    publishCurrentView(worldEpoch);
                    return;
                }
                ModelBackend.SearchMode searchMode = searchMode(selected, settings.automaticWebSearch(), explicitWebSearch);
                if (explicitWebSearch && searchMode == ModelBackend.SearchMode.DISABLED) {
                    runtimeErrors.add("Hosted web search is unavailable for the selected model; using local evidence");
                }
                if (includeScreenshot && Boolean.FALSE.equals(selected.imageInput())) {
                    runtimeErrors.add("Screenshot omitted from the model request because image input is unsupported");
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
                ChatContracts.HistoryLimits historyLimits;
                try {
                    historyLimits = settings.historyLimits();
                } catch (RuntimeException failure) {
                    runtimeErrors.add("Request settings are invalid: " + failureMessage(failure));
                    return;
                }
                previousScope = scope;
                Evidence.Scope nextScope = new Evidence.Scope(scope.worldSession(), scope.resourcesLocale(),
                        scope.playerTeam(), scope.providerRuntime(), scope.requestGeneration() + 1);
                try {
                    submission = new ChatContracts.Submission(new ChatContracts.RequestId(UUID.randomUUID().toString()), world,
                            nextScope, question, includeScreenshot, historyLimits, selected, searchMode, budget,
                            settings.reasoningEffort());
                } catch (RuntimeException failure) {
                    runtimeErrors.add("Request settings are invalid: " + failureMessage(failure));
                    return;
                }
                scope = nextScope;
                newChatPending = false;
                epoch = worldEpoch;
            }
            try {
                controller.begin(submission).whenCompleteAsync((ignored, failure) -> {
                    if (failure == null) return;
                    boolean current;
                    synchronized (stateLock) {
                        current = epoch == worldEpoch && scope.equals(submission.scope());
                        if (current) {
                            scope = previousScope;
                            runtimeErrors.add("Request could not start: " + failureMessage(failure));
                        }
                    }
                    if (current) publishCurrentView(epoch);
                }, controllerExecutor);
            } catch (RuntimeException failure) {
                boolean current;
                synchronized (stateLock) {
                    current = epoch == worldEpoch && scope.equals(submission.scope());
                    if (current) {
                        scope = previousScope;
                        runtimeErrors.add("Request could not start: " + failureMessage(failure));
                    }
                }
                if (current) publishCurrentView(epoch);
            }
            publishCurrentView(epoch);
        }
    }

    @Override
    public void stop() {
        controller.stop();
    }

    @Override
    public void newChat() {
        ChatContracts.WorldIdentity currentWorld;
        Evidence.Scope currentScope;
        synchronized (stateLock) {
            publicationEpoch++;
            newChatPending = true;
            readOnlyConversation = null;
            sessionLoading = false;
            sessionGeneration++;
            currentWorld = world;
            currentScope = scope;
        }
        if (currentWorld == null) controller.newChat();
        else controller.newChat(currentWorld, currentScope);
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
        if (current.readOnly()) {
            addError("Join this session's world before opening its sources");
            publishCurrentView(worldEpoch);
            return;
        }
        if (current.navigationTargets().stream().noneMatch(value -> value.equals(target))
                || current.sources().stream().noneMatch(source -> source.scope().equals(target.scope())
                && source.providerId().equals(target.providerId()) && source.sourceHandle().equals(target.sourceHandle()))) {
            addError("Navigation target is stale");
            publishCurrentView(worldEpoch);
            return;
        }
        Evidence.Scope expected;
        long epoch;
        synchronized (stateLock) {
            expected = scope;
            epoch = worldEpoch;
        }
        if (!target.scope().equals(expected)) {
            addError("Navigation target is stale");
            publishCurrentView(epoch);
            return;
        }
        boolean stale;
        synchronized (stateLock) {
            stale = epoch != worldEpoch || !expected.equals(scope);
        }
        if (stale) {
            addError("Navigation target is stale");
            return;
        }
        if ("web".equals(target.providerId())) {
            if (!target.sourceHandle().equals(target.targetId()) || externalUrl(target.targetId()) == null) {
                addError("Navigation target is invalid");
                return;
            }
            try {
                externalNavigation.accept(target.targetId());
            } catch (RuntimeException failure) {
                addError("Navigation failed: " + failureMessage(failure));
            }
            return;
        }
        Evidence.NavigationResult result;
        KnowledgeService currentKnowledge;
        synchronized (stateLock) {
            currentKnowledge = knowledge;
        }
        try {
            result = currentKnowledge.open(target, expected);
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
        PackSageTools currentTools;
        synchronized (stateLock) {
            currentTools = tools;
        }
        return currentTools.execute(request.call(), request.submission().scope()).thenApply(response -> {
            List<Evidence.Source> sources = response.records().stream().map(Evidence.Record::source).distinct().toList();
            List<Evidence.NavigationTarget> targets = response.records().stream().flatMap(record -> record.targets().stream())
                    .distinct().toList();
            return new ChatContracts.ToolResponse(response.result(), new ChatContracts.EvidenceSelection(sources, targets));
        });
    }

    private void finishSignIn(long epoch, ModelBackend.AuthState state, Throwable failure, boolean restore) {
        synchronized (stateLock) {
            if (authEpoch != epoch || signIn != ChatContracts.SignInState.SIGNING_IN) return;
            if (restore && (failure != null || state == null || state.status() != ModelBackend.AuthStatus.SIGNED_IN)) {
                signIn = ChatContracts.SignInState.SIGNED_OUT;
            } else if (failure != null) {
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
                capabilities = new ModelBackend.Capabilities(List.of(), null, null, null, null, null);
            }
        }
        publishCurrentView(worldEpoch);
        if (failure == null && state != null && state.status() == ModelBackend.AuthStatus.SIGNED_IN) loadCapabilities(epoch, restore);
    }

    private void loadCapabilities(long epoch, boolean restored) {
        CompletionStage<ModelBackend.Capabilities> loading;
        try {
            loading = backend.capabilities();
        } catch (RuntimeException failure) {
            finishCapabilities(epoch, null, failure, restored);
            return;
        }
        loading.whenCompleteAsync((found, failure) -> finishCapabilities(epoch, found, failure, restored), clientExecutor);
    }

    private void finishCapabilities(long epoch, ModelBackend.Capabilities found, Throwable failure, boolean restored) {
        synchronized (stateLock) {
            if (authEpoch != epoch || signIn != ChatContracts.SignInState.SIGNED_IN) return;
            if (restored && authRejected(failure)) {
                signIn = ChatContracts.SignInState.SIGNED_OUT;
                capabilities = new ModelBackend.Capabilities(List.of(), null, null, null, null, null);
                runtimeErrors.add("Saved sign-in is no longer valid; sign in again");
            } else if (failure != null || found == null) {
                runtimeErrors.add("Model catalog unavailable: " + failureMessage(failure == null
                        ? new IllegalStateException("empty catalog response") : failure));
                return;
            } else {
                capabilities = found;
                if (found.selectedModelId() == null || found.modelCatalog().stream()
                        .noneMatch(model -> model.id().equals(found.selectedModelId()))) {
                    runtimeErrors.add("No selected model is available in the account model catalog");
                }
            }
        }
        publishCurrentView(worldEpoch);
    }

    private void publishControllerView(ChatContracts.View view) {
        ChatContracts.WorldIdentity expectedWorld;
        Evidence.Scope expectedScope;
        long epoch;
        long publication;
        synchronized (stateLock) {
            expectedWorld = world;
            expectedScope = scope;
            epoch = worldEpoch;
            publication = publicationEpoch;
        }
        if (expectedWorld == null || !expectedWorld.equals(view.conversation().world())) return;
        if (!expectedScope.equals(view.context().scope())) return;
        long callbackEpoch = epoch;
        long callbackPublication = publication;
        clientExecutor.execute(() -> {
            AssistantScreen screen;
            synchronized (stateLock) {
                if (callbackEpoch != worldEpoch || callbackPublication != publicationEpoch || currentScreen == null
                        || world == null || !world.equals(expectedWorld) || !expectedScope.equals(scope)) return;
                screen = currentScreen;
            }
            ChatContracts.View latest = view();
            synchronized (stateLock) {
                if (callbackEpoch != worldEpoch || callbackPublication != publicationEpoch || currentScreen != screen
                        || !expectedScope.equals(scope) || !expectedScope.equals(latest.context().scope())) return;
            }
            screen.refresh(latest);
        });
    }

    private void publishCurrentView(long epoch) {
        Evidence.Scope expectedScope;
        long publication;
        synchronized (stateLock) {
            expectedScope = scope;
            publication = publicationEpoch;
        }
        clientExecutor.execute(() -> {
            AssistantScreen screen;
            synchronized (stateLock) {
                if (epoch != worldEpoch || publication != publicationEpoch || currentScreen == null
                        || !expectedScope.equals(scope)) return;
                screen = currentScreen;
            }
            ChatContracts.View latest = view();
            synchronized (stateLock) {
                if (epoch != worldEpoch || publication != publicationEpoch || currentScreen != screen
                        || !expectedScope.equals(scope) || !expectedScope.equals(latest.context().scope())) return;
            }
            screen.refresh(latest);
        });
    }

    private ChatContracts.View wrap(ChatContracts.View view) {
        ChatContracts.SignInState currentSignIn;
        boolean paused;
        List<String> errors;
        Evidence.Scope currentScope;
        ChatContracts.WorldIdentity currentWorld;
        boolean hideConversation;
        boolean loading;
        ChatContracts.Conversation displayed;
        synchronized (stateLock) {
            currentSignIn = signIn;
            paused = requestedPaused;
            errors = new ArrayList<>(view.errors());
            errors.addAll(runtimeErrors);
            currentScope = scope;
            currentWorld = world;
            hideConversation = newChatPending;
            loading = sessionLoading;
            displayed = readOnlyConversation;
        }
        ChatContracts.WorldIdentity activeWorld = currentWorld == null ? view.conversation().world() : currentWorld;
        ChatContracts.Conversation conversation = displayed == null ? view.conversation() : displayed;
        List<Evidence.Source> sources = new ArrayList<>();
        List<Evidence.NavigationTarget> targets = new ArrayList<>();
        if (!hideConversation && displayed == null) {
            view.sources().forEach(source -> {
                if (source.scope().equals(currentScope) && sources.stream().noneMatch(existing ->
                        existing.providerId().equals(source.providerId())
                                && existing.sourceHandle().equals(source.sourceHandle()))) sources.add(source);
            });
            view.navigationTargets().forEach(target -> {
                if (target.scope().equals(currentScope)
                        && sources.stream().anyMatch(source -> source.providerId().equals(target.providerId())
                        && source.sourceHandle().equals(target.sourceHandle()))
                        && !targets.contains(target)) targets.add(target);
            });
        }
        if (!hideConversation && currentWorld != null && (displayed != null || currentWorld.equals(conversation.world()))) {
            conversation.turns().forEach(turn -> {
                turn.sources().forEach(source -> { if (!sources.contains(source)) sources.add(source); });
                turn.navigationTargets().forEach(target -> { if (!targets.contains(target)) targets.add(target); });
            });
        }
        if (!hideConversation && currentWorld != null && (displayed != null || currentWorld.equals(conversation.world()))) {
            for (ChatContracts.Turn turn : conversation.turns()) {
                if (turn.outcome().status() != ChatContracts.OutcomeStatus.COMPLETED) continue;
                LinkedHashSet<String> urls = new LinkedHashSet<>();
                turn.replayItems().forEach(item -> {
                    if (item instanceof ModelBackend.AssistantOutput output
                            && output.status() == ModelBackend.OutputStatus.COMPLETED) {
                        output.content().forEach(content -> {
                            if (content instanceof ModelBackend.OutputText text) {
                                text.citations().forEach(citation -> {
                                    if (externalUrl(citation.url()) != null) urls.add(citation.url());
                                });
                            }
                        });
                    } else if (item instanceof ModelBackend.WebSearchCall search
                            && search.status() == ModelBackend.WebSearchStatus.COMPLETED) {
                        if (search.action() instanceof ModelBackend.SearchAction action) {
                            action.sources().forEach(source -> {
                                if (externalUrl(source.url()) != null) urls.add(source.url());
                            });
                        } else if (search.action() instanceof ModelBackend.OpenPageAction action
                                && externalUrl(action.url()) != null) {
                            urls.add(action.url());
                        } else if (search.action() instanceof ModelBackend.FindInPageAction action
                                && externalUrl(action.url()) != null) {
                            urls.add(action.url());
                        }
                    }
                });
                Instant receivedAt = Instant.now();
                urls.forEach(url -> {
                    if (sources.stream().noneMatch(source -> source.scope().equals(turn.context().scope())
                            && source.providerId().equals("web")
                            && source.sourceHandle().equals(url))) {
                        sources.add(new Evidence.Source(turn.context().scope(), "web", "typed-replay", url,
                                turn.context().scope().requestGeneration(), receivedAt, Evidence.Activity.ACTIVE,
                                Evidence.Coverage.COMPLETE, Evidence.Authority.EXTERNAL, List.of(url)));
                    }
                    Evidence.NavigationTarget target = new Evidence.NavigationTarget(turn.context().scope(), "web", url, url);
                    if (!targets.contains(target)) targets.add(target);
                });
            }
        }
        if (displayed != null && currentWorld != null) {
            return new ChatContracts.View(false, currentSignIn, paused, displayed,
                    new ChatContracts.Context("", null, currentScope), sources, targets, errors, "",
                    loading ? "Loading session..." : "", activeWorld, true);
        }
        return new ChatContracts.View(view.active(), currentSignIn, paused, view.conversation(), view.context(),
                sources, targets, errors, view.streamingAnswer(), view.activity(), activeWorld, false);
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

    private static ModelBackend.SearchMode searchMode(ModelBackend.ModelInfo model, boolean automatic, boolean explicit) {
        if (Boolean.FALSE.equals(model.hostedSearch())) return ModelBackend.SearchMode.DISABLED;
        if (explicit) return ModelBackend.SearchMode.EXPLICIT_REQUEST;
        return automatic ? ModelBackend.SearchMode.AUTOMATIC : ModelBackend.SearchMode.EXPLICIT_ONLY;
    }

    private static String externalUrl(String value) {
        try {
            URI uri = new URI(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getHost().isBlank()) return null;
            return value;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean authRejected(Throwable failure) {
        Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        return cause instanceof ModelBackend.AuthRejectedException;
    }

    private static String failureMessage(Throwable failure) {
        if (failure == null) return "unknown failure";
        Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        return cause.getMessage() == null ? cause.getClass().getName() : cause.getMessage();
    }
}
