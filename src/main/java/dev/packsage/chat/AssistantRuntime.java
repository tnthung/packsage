package dev.packsage.chat;

import dev.packsage.PackSageLog;
import dev.packsage.client.AssistantScreen;
import dev.packsage.client.GameplayContextCapture;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeService;
import dev.packsage.model.ModelBackend;
import dev.packsage.model.PackSageTools;
import dev.packsage.model.ReferenceAliasBackend;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

public final class AssistantRuntime implements ChatActions {
    private static final Evidence.Scope EMPTY_SCOPE = new Evidence.Scope("none", "none", "none", "none", 0);
    private static final Map<String, Boolean> UNAVAILABLE_ADAPTERS = Map.ofEntries(Map.entry("native", false),
            Map.entry("jei", false), Map.entry("curios", false), Map.entry("ftb_quests", false), Map.entry("config", false),
            Map.entry("patchouli", false), Map.entry("modonomicon", false), Map.entry("guideme", false),
            Map.entry("immersiveengineering", false), Map.entry("actuallyadditions", false),
            Map.entry("relics", false), Map.entry("artifacts", false), Map.entry("ponder", false), Map.entry("history", false));

    private final ModelBackend backend;
    private final ConversationStore store;
    private final Function<Supplier<Evidence.Scope>, GameplayContextCapture> captureFactory;
    private final Executor controllerExecutor;
    private final Executor clientExecutor;
    private final Function<ChatContracts.PreparedRequest, ModelBackend.Request> requestBuilder;
    private final Function<ChatContracts.Turn, Integer> historyCounter;
    private final ToIntFunction<ModelBackend.ModelInfo> outputBudget;
    private final Consumer<Boolean> pauseGame;
    private final Consumer<String> externalNavigation;
    private final Consumer<ChatContracts.Turn> completionNotice;
    private final Function<Supplier<Evidence.Scope>, KnowledgeService> knowledgeFactory;
    private final Object stateLock = new Object();
    private final Object sendLock = new Object();
    private final List<String> runtimeErrors = new ArrayList<>();
    private final Map<HolderKey, Holder> holders = new java.util.LinkedHashMap<>();
    private Holder selected;
    private ChatContracts.WorldIdentity world;
    private Evidence.Scope worldScope = EMPTY_SCOPE;
    private ModelBackend.Capabilities capabilities = new ModelBackend.Capabilities(List.of(), null, null, null, null, null);
    private ChatContracts.SignInState signIn = ChatContracts.SignInState.SIGNED_OUT;
    private PackSageSettings settings = PackSageSettings.defaults();
    private AssistantScreen currentScreen;
    private long worldEpoch;
    private long publicationEpoch;
    private long requestGeneration;
    private long authEpoch;
    private long navigationGeneration;
    private boolean requestedPaused;
    private boolean newChatPending;
    private boolean sessionLoading;
    private boolean sessionDeletion;
    private long sessionDeletionGeneration;
    private long sessionSelectionGeneration;
    private long sessionListGeneration;

    public AssistantRuntime(ConversationStore store, ModelBackend backend,
                            Function<Supplier<Evidence.Scope>, GameplayContextCapture> captureFactory,
                            Executor controllerExecutor, Executor clientExecutor,
                            Function<Supplier<Evidence.Scope>, KnowledgeService> knowledgeFactory,
                            Function<ChatContracts.Turn, Integer> historyCounter,
                            Function<ChatContracts.PreparedRequest, ModelBackend.Request> requestBuilder,
                            ToIntFunction<ModelBackend.ModelInfo> outputBudget, Consumer<Boolean> pauseGame,
                            Consumer<String> externalNavigation) {
        this(store, backend, captureFactory, controllerExecutor, clientExecutor, knowledgeFactory, historyCounter,
                requestBuilder, outputBudget, pauseGame, externalNavigation, ignored -> { });
    }

    public AssistantRuntime(ConversationStore store, ModelBackend backend,
                            Function<Supplier<Evidence.Scope>, GameplayContextCapture> captureFactory,
                            Executor controllerExecutor, Executor clientExecutor,
                            Function<Supplier<Evidence.Scope>, KnowledgeService> knowledgeFactory,
                            Function<ChatContracts.Turn, Integer> historyCounter,
                            Function<ChatContracts.PreparedRequest, ModelBackend.Request> requestBuilder,
                            ToIntFunction<ModelBackend.ModelInfo> outputBudget, Consumer<Boolean> pauseGame,
                            Consumer<String> externalNavigation, Consumer<ChatContracts.Turn> completionNotice) {
        this.store = Objects.requireNonNull(store);
        this.backend = new ReferenceAliasBackend(Objects.requireNonNull(backend));
        this.captureFactory = Objects.requireNonNull(captureFactory);
        this.controllerExecutor = Objects.requireNonNull(controllerExecutor);
        this.clientExecutor = Objects.requireNonNull(clientExecutor);
        Objects.requireNonNull(historyCounter);
        this.historyCounter = historyCounter;
        this.requestBuilder = Objects.requireNonNull(requestBuilder);
        this.outputBudget = Objects.requireNonNull(outputBudget);
        this.pauseGame = Objects.requireNonNull(pauseGame);
        this.externalNavigation = Objects.requireNonNull(externalNavigation);
        this.completionNotice = Objects.requireNonNull(completionNotice);
        this.knowledgeFactory = Objects.requireNonNull(knowledgeFactory);
    }

    public void enterWorld(ChatContracts.WorldIdentity world, Evidence.Scope scope) {
        Objects.requireNonNull(world);
        Objects.requireNonNull(scope);
        long epoch;
        long selectionGeneration;
        List<Holder> previous;
        synchronized (sendLock) {
            synchronized (stateLock) {
                epoch = ++worldEpoch;
                publicationEpoch++;
                this.world = world;
                worldScope = scope;
                requestGeneration = Math.max(requestGeneration, scope.requestGeneration());
                selected = null;
                sessionLoading = true;
                sessionSelectionGeneration++;
                selectionGeneration = sessionSelectionGeneration;
                runtimeErrors.clear();
                requestedPaused = settings.openPaused();
                newChatPending = false;
                sessionDeletion = false;
                sessionDeletionGeneration++;
                previous = List.copyOf(holders.values());
            }
        }
        previous.forEach(holder -> { if (holder.controller != null) holder.controller.worldChanged(); });
        try {
            store.openLatest(world).whenCompleteAsync((conversation, failure) -> {
                if (failure != null) {
                    boolean publish;
                    synchronized (stateLock) {
                        if (epoch == worldEpoch && selectionGeneration == sessionSelectionGeneration && selected == null
                                && this.world != null && this.world.worldOrServer().equals(world.worldOrServer())) {
                            sessionLoading = false;
                            runtimeErrors.add("Conversation could not be opened: " + failureMessage(failure));
                        }
                        publish = selectionGeneration == sessionSelectionGeneration && selected == null;
                    }
                    if (publish) publishCurrentView(epoch);
                    return;
                }
                Holder holder = reserveHolder(conversation.world(), conversation.id(), scope);
                try {
                    initializeHolder(holder);
                    holder.scope = scope;
                    holder.controller.showConversation(conversation, scope);
                    holder.loaded = true;
                    holder.loading = false;
                } catch (RuntimeException setupFailure) {
                    boolean publish;
                    synchronized (stateLock) {
                        if (epoch == worldEpoch && selectionGeneration == sessionSelectionGeneration && selected == null) {
                            sessionLoading = false;
                            runtimeErrors.add("Knowledge providers could not be refreshed: " + failureMessage(setupFailure));
                        }
                        publish = selectionGeneration == sessionSelectionGeneration && selected == null;
                    }
                    if (publish) publishCurrentView(epoch);
                    return;
                }
                synchronized (stateLock) {
                    if (epoch != worldEpoch || selectionGeneration != sessionSelectionGeneration || selected != null || this.world == null
                            || !this.world.worldOrServer().equals(conversation.world().worldOrServer())) return;
                    selected = holder;
                    sessionLoading = false;
                    newChatPending = false;
                }
                publishCurrentView(epoch);
                refreshSessionList();
            }, clientExecutor);
        } catch (RuntimeException failure) {
            boolean publish;
            synchronized (stateLock) {
                if (epoch == worldEpoch && selectionGeneration == sessionSelectionGeneration && selected == null) {
                    sessionLoading = false;
                    runtimeErrors.add("Conversation could not be opened: " + failureMessage(failure));
                }
                publish = selectionGeneration == sessionSelectionGeneration && selected == null;
            }
            if (publish) publishCurrentView(epoch);
        }
    }

    public void invalidate(Evidence.Scope newScope) {
        Objects.requireNonNull(newScope);
        List<Holder> invalidate;
        synchronized (stateLock) {
            publicationEpoch++;
            worldScope = newScope;
            requestGeneration = Math.max(requestGeneration, newScope.requestGeneration());
            newChatPending = false;
            sessionLoading = false;
            sessionSelectionGeneration++;
            if (world != null) holders.values().stream()
                    .filter(holder -> holder.world.worldOrServer().equals(world.worldOrServer()))
                    .forEach(holder -> holder.scope = newScope);
            invalidate = List.copyOf(holders.values());
        }
        invalidate.forEach(holder -> { if (holder.controller != null) holder.controller.invalidate(newScope.worldSession()); });
        publishCurrentView(worldEpoch);
    }

    public void disconnect() {
        List<Holder> disconnect;
        synchronized (sendLock) {
            synchronized (stateLock) {
                worldEpoch++;
                publicationEpoch++;
                world = null;
                worldScope = EMPTY_SCOPE;
                requestedPaused = false;
                newChatPending = false;
                sessionLoading = false;
                sessionSelectionGeneration++;
                selected = null;
                disconnect = List.copyOf(holders.values());
            }
        }
        disconnect.forEach(holder -> { if (holder.controller != null) holder.controller.disconnect(); });
    }

    @Override
    public Map<String, Boolean> adapterAvailability() {
        Holder holder;
        boolean activeWorld;
        synchronized (stateLock) {
            holder = selected;
            activeWorld = world != null && holder != null && holder.world.worldOrServer().equals(world.worldOrServer());
        }
        if (!activeWorld || !holder.loaded || holder.knowledge == null) return UNAVAILABLE_ADAPTERS;
        var availability = new java.util.LinkedHashMap<String, Boolean>();
        Map<String, Boolean> providers = holder.knowledge.adapterAvailability();
        for (String id : List.of("native", "jei", "curios", "ftb_quests", "config", "patchouli", "modonomicon",
                "guideme", "immersiveengineering", "actuallyadditions", "relics", "artifacts", "ponder")) {
            availability.put(id, providers.getOrDefault(id, false));
        }
        availability.put("history", true);
        return Map.copyOf(availability);
    }

    public void onRenderGuiPost() {
        List<GameplayContextCapture> captures;
        synchronized (stateLock) {
            captures = holders.values().stream().map(holder -> holder.capture).filter(Objects::nonNull).toList();
        }
        captures.forEach(GameplayContextCapture::onRenderGuiPost);
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
    public PackSageSettings settings() {
        synchronized (stateLock) {
            return settings;
        }
    }

    @Override
    public ChatContracts.HistoryPreview historyPreview() {
        return historyPreview(null);
    }

    @Override
    public ChatContracts.HistoryPreview historyPreview(ChatContracts.RequestId replacing) {
        Holder holder;
        synchronized (stateLock) {
            holder = selected;
        }
        ChatContracts.View current = holder == null || holder.controller == null ? idleView() : wrap(holder.controller.view(), holder);
        ModelBackend.ModelInfo selected;
        PackSageSettings currentSettings;
        synchronized (stateLock) {
            currentSettings = settings;
            selected = capabilities.modelCatalog().stream().filter(model -> model.id().equals(
                    currentSettings.modelId() == null ? capabilities.selectedModelId() : currentSettings.modelId())).findFirst().orElse(null);
        }
        int configured = currentSettings.historicalTokens();
        Integer modelTokens = selected == null ? null : selected.contextTokens();
        int effective = modelTokens == null ? configured : Math.min(configured, modelTokens);
        ChatContracts.HistorySelection selection = HistorySelector.select(current.conversation().turns(),
                new ChatContracts.HistoryLimits(currentSettings.historyTurns(), effective), historyCounter::apply, replacing);
        return new ChatContracts.HistoryPreview(selection, configured, effective, modelTokens, currentSettings.historyTurns());
    }

    @Override
    public CompletionStage<List<ChatContracts.SessionSummary>> sessions() {
        return store.sessions().thenApply(this::mergeSessions);
    }

    @Override
    public void markRead(ChatContracts.SessionSummary session, ChatContracts.RequestId expectedCompletion) {
        Objects.requireNonNull(session);
        Objects.requireNonNull(expectedCompletion);
        Holder holder;
        AssistantScreen screen;
        long selectionGeneration;
        synchronized (stateLock) {
            holder = selected;
            screen = currentScreen;
            if (holder == null || !holder.loaded || holder.loading || holder.deleting
                    || screen == null || !holder.id.equals(session.id())
                    || !holder.world.worldOrServer().equals(session.world().worldOrServer())
                    || !expectedCompletion.equals(holder.unreadRequestId)) return;
            selectionGeneration = sessionSelectionGeneration;
        }
        ChatContracts.View displayed = holder.controller == null ? null : holder.controller.view();
        if (displayed == null || displayed.conversation().turns().stream().noneMatch(turn ->
                turn.requestId().equals(expectedCompletion)
                        && turn.outcome().status() == ChatContracts.OutcomeStatus.COMPLETED)) return;
        synchronized (stateLock) {
            if (selected != holder || selectionGeneration != sessionSelectionGeneration || holder.loading || holder.deleting
                    || currentScreen != screen || !expectedCompletion.equals(holder.unreadRequestId)) return;
            if (holder.readAttemptRequestId != null && holder.readAttemptRequestId.equals(expectedCompletion)
                    && holder.readAttemptSelectionGeneration == selectionGeneration
                    && holder.readAttemptScreen == screen) return;
            holder.readAttemptRequestId = expectedCompletion;
            holder.readAttemptSelectionGeneration = selectionGeneration;
            holder.readAttemptScreen = screen;
        }
        CompletionStage<Void> acknowledgment;
        try {
            acknowledgment = store.markRead(holder.world, holder.id, expectedCompletion);
        } catch (RuntimeException failure) {
            acknowledgment = java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
        acknowledgment.whenCompleteAsync((ignored, failure) -> {
            boolean refresh = false;
            boolean publish;
            synchronized (stateLock) {
                if (failure != null) {
                    if (expectedCompletion.equals(holder.readAttemptRequestId)
                            && holder.readAttemptSelectionGeneration == selectionGeneration
                            && holder.readAttemptScreen == screen) {
                        holder.errors.add("Completion state could not be marked as read: " + failureMessage(failure));
                    }
                } else if (!holder.deleting && expectedCompletion.equals(holder.unreadRequestId)) {
                    holder.unreadRequestId = null;
                    refresh = true;
                }
                publish = selected == holder;
            }
            if (refresh) refreshSessionList();
            if (publish) publishCurrentView(worldEpoch);
        }, clientExecutor);
    }

    @Override
    public CompletionStage<Void> deleteSession(ChatContracts.SessionSummary session) {
        Objects.requireNonNull(session);
        Holder holder;
        boolean deletingSelected;
        long epoch;
        long deletionGeneration;
        synchronized (stateLock) {
            if (world == null) return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("cannot delete a session without an active world"));
            holder = holders.get(new HolderKey(session.world().worldOrServer(), session.id()));
            if (sessionDeletion || holder == null && session.active()
                    || holder != null && (holder.loading || holder.deleting || holder.active || holder.requestStarting)) {
                return java.util.concurrent.CompletableFuture.failedFuture(
                        new IllegalStateException("sessions cannot be deleted while the target chat is active or changing"));
            }
            if (holder != null) holder.deleting = true;
            deletingSelected = holder != null && selected == holder;
            sessionDeletion = true;
            deletionGeneration = ++sessionDeletionGeneration;
            epoch = worldEpoch;
            publicationEpoch++;
        }
        CompletionStage<Void> barrier = holder == null || holder.controller == null
                ? java.util.concurrent.CompletableFuture.completedFuture(null) : holder.controller.awaitPersistence();
        return barrier.thenCompose(ignored -> store.deleteConversation(session.world(), session.id()))
                .whenCompleteAsync((ignored, failure) -> {
                    boolean createChat = false;
                    synchronized (stateLock) {
                        if (holder != null) {
                            holder.deleting = false;
                            if (failure == null) holders.remove(new HolderKey(holder.world.worldOrServer(), holder.id), holder);
                            else holder.errors.add("Session could not be deleted: " + failureMessage(failure));
                        }
                        if (deletionGeneration == sessionDeletionGeneration) sessionDeletion = false;
                        if (failure == null && deletingSelected && selected == holder && epoch == worldEpoch && world != null) {
                            selected = null;
                            sessionLoading = false;
                            newChatPending = false;
                            sessionSelectionGeneration++;
                            createChat = true;
                        }
                    }
                    if (createChat) newChat();
                    else publishCurrentView(epoch);
                    refreshSessionList();
                }, clientExecutor);
    }

    @Override
    public void openSession(ChatContracts.SessionSummary session) {
        Objects.requireNonNull(session);
        Holder holder;
        boolean startLoad;
        long epoch;
        synchronized (stateLock) {
            if (world == null) return;
            sessionSelectionGeneration++;
            publicationEpoch++;
            epoch = worldEpoch;
            holder = holders.get(new HolderKey(session.world().worldOrServer(), session.id()));
            if (holder == null) {
                holder = new Holder(session.world(), session.id(),
                        session.world().worldOrServer().equals(world.worldOrServer()) ? worldScope : EMPTY_SCOPE);
                holder.unreadRequestId = session.unreadRequestId();
                holders.put(new HolderKey(session.world().worldOrServer(), session.id()), holder);
            }
            if (holder.deleting) return;
            selected = holder;
            startLoad = !holder.loaded && !holder.loading;
            holder.loading = !holder.loaded;
            sessionLoading = holder.loading;
            newChatPending = false;
        }
        try {
            initializeHolder(holder);
        } catch (RuntimeException failure) {
            synchronized (stateLock) {
                holder.loading = false;
                if (selected == holder) sessionLoading = false;
                holder.errors.add("Knowledge providers could not be refreshed: " + failureMessage(failure));
            }
            publishCurrentView(epoch);
            return;
        }
        if (!startLoad) {
            publishCurrentView(epoch);
            if (holder.loaded) refreshSessionList();
            return;
        }
        publishCurrentView(epoch);
        Holder loadingHolder = holder;
        store.openConversation(session.world(), session.id()).whenCompleteAsync((conversation, failure) -> {
            if (failure == null) {
                synchronized (stateLock) {
                    loadingHolder.scope = world != null && world.worldOrServer().equals(session.world().worldOrServer())
                            ? worldScope : EMPTY_SCOPE;
                }
                loadingHolder.controller.showConversation(conversation, loadingHolder.scope);
                loadingHolder.loaded = true;
            }
            long selectedEpoch;
            boolean selectedHolder;
            synchronized (stateLock) {
                loadingHolder.loading = false;
                selectedHolder = selected == loadingHolder;
                if (selectedHolder) sessionLoading = false;
                if (failure != null) {
                    loadingHolder.errors.add("Session could not be opened: " + failureMessage(failure));
                }
                selectedEpoch = worldEpoch;
            }
            if (selectedHolder) publishCurrentView(selectedEpoch);
            refreshSessionList();
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
        Holder holder;
        synchronized (stateLock) {
            holder = selected;
        }
        return holder == null || holder.controller == null ? idleView() : wrap(holder.controller.view(), holder);
    }

    @Override
    public ChatContracts.Context gameplayContext() {
        Holder holder;
        Evidence.Scope currentScope;
        synchronized (stateLock) {
            holder = selected;
            currentScope = worldScope;
        }
        return holder == null || holder.capture == null ? new ChatContracts.Context("", null, currentScope)
                : holder.capture.preview();
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
        List<Holder> invalidate;
        synchronized (stateLock) {
            authEpoch++;
            signIn = ChatContracts.SignInState.SIGNED_OUT;
            capabilities = new ModelBackend.Capabilities(List.of(), null, null, null, null, null);
            invalidate = List.copyOf(holders.values());
        }
        invalidate.forEach(holder -> { if (holder.controller != null) holder.controller.invalidate("account signed out"); });
        try {
            backend.signOut();
        } catch (RuntimeException failure) {
            addError("Sign-out failed: " + failureMessage(failure));
        }
        publishCurrentView(worldEpoch);
    }

    @Override
    public void resend(ChatContracts.RequestId expectedLatest, String question, boolean includeScreenshot,
                       boolean explicitWebSearch) {
        Objects.requireNonNull(expectedLatest);
        Holder holder;
        synchronized (stateLock) {
            holder = selected;
        }
        ChatContracts.View current = holder == null || holder.controller == null ? idleView() : holder.controller.view();
        boolean allowed;
        synchronized (stateLock) {
            allowed = holder != null && selected == holder && !holder.active && !holder.requestStarting && !current.active()
                    && !sessionLoading && !holder.loading && !holder.deleting && world != null
                    && world.worldOrServer().equals(holder.world.worldOrServer())
                    && !current.conversation().turns().isEmpty()
                    && current.conversation().turns().getLast().requestId().equals(expectedLatest);
            if (!allowed) runtimeErrors.add("Only the latest exchange can be resent");
        }
        if (!allowed) {
            publishCurrentView(worldEpoch);
            return;
        }
        send(question, includeScreenshot, explicitWebSearch, expectedLatest);
    }

    @Override
    public void send(String question, boolean includeScreenshot, boolean explicitWebSearch) {
        Objects.requireNonNull(question);
        send(question, includeScreenshot, explicitWebSearch, null);
    }

    private void send(String question, boolean includeScreenshot, boolean explicitWebSearch,
                      ChatContracts.RequestId replacing) {
        Objects.requireNonNull(question);
        long sendStartedAtNanos = System.nanoTime();
        Holder holder;
        PackSageSettings currentSettings;
        ModelBackend.Capabilities currentCapabilities;
        synchronized (stateLock) {
            holder = selected;
            currentSettings = settings;
            currentCapabilities = capabilities;
        }
        if (question.isBlank()) {
            addError("Question cannot be blank");
            return;
        }
        if (holder == null || holder.controller == null) {
            addError("Cannot send without an active conversation");
            return;
        }
        if (holder.active || holder.requestStarting || holder.controller.view().active()) {
            if (!holder.controller.steer(question)) {
                synchronized (stateLock) {
                    holder.errors.add("Follow-up could not be accepted while the response is preparing or has finished");
                }
                publishCurrentView(worldEpoch);
            }
            return;
        }
        String configuredModelId = currentSettings.modelId();
        ModelBackend.ModelInfo selectedModel = currentCapabilities.modelCatalog().stream()
                .filter(model -> model.id().equals(configuredModelId == null
                        ? currentCapabilities.selectedModelId() : configuredModelId)).findFirst().orElse(null);
        if (selectedModel == null) {
            addError(configuredModelId == null ? "No selected model is available in the account model catalog"
                    : "Configured model is unavailable in the account model catalog: " + configuredModelId);
            return;
        }
        int budget;
        try {
            budget = outputBudget.applyAsInt(selectedModel);
        } catch (RuntimeException failure) {
            addError("Output budget is unavailable: " + failureMessage(failure));
            return;
        }
        if (budget < 1) {
            addError("Output budget is unavailable");
            return;
        }
        ChatContracts.HistoryLimits historyLimits;
        try {
            int historicalTokens = currentSettings.historicalTokens();
            if (selectedModel.contextTokens() != null) historicalTokens = Math.min(historicalTokens, selectedModel.contextTokens());
            historyLimits = new ChatContracts.HistoryLimits(currentSettings.historyTurns(), historicalTokens);
        } catch (RuntimeException failure) {
            addError("Request settings are invalid: " + failureMessage(failure));
            return;
        }
        ModelBackend.SearchMode searchMode = searchMode(selectedModel, currentSettings.automaticWebSearch(), explicitWebSearch);
        ChatContracts.Submission submission;
        Evidence.Scope previousScope;
        long epoch;
        String rejection = null;
        synchronized (stateLock) {
            if (holder != selected || holder.loading || sessionLoading || holder.deleting || !holder.loaded
                    || world == null || !holder.world.worldOrServer().equals(world.worldOrServer())) {
                rejection = holder.deleting ? "Session deletion is still in progress" : sessionLoading || holder.loading
                        || !holder.loaded ? "Session is still loading" : "This session is read-only until its world is active";
                submission = null;
                previousScope = null;
            } else if (signIn != ChatContracts.SignInState.SIGNED_IN) {
                rejection = "Sign in before sending a question";
                submission = null;
                previousScope = null;
            } else if (holder.active || holder.requestStarting) {
                rejection = "Follow-up could not be accepted while the response is preparing or has finished";
                submission = null;
                previousScope = null;
            } else {
                previousScope = holder.scope;
                Evidence.Scope nextScope = new Evidence.Scope(worldScope.worldSession(), worldScope.resourcesLocale(),
                        worldScope.playerTeam(), worldScope.providerRuntime(), ++requestGeneration);
                ChatContracts.Submission prepared;
                try {
                    prepared = new ChatContracts.Submission(new ChatContracts.RequestId(UUID.randomUUID().toString()),
                            holder.world, nextScope, question, includeScreenshot, historyLimits, selectedModel, searchMode,
                            budget, currentSettings.reasoningEffort(), replacing);
                } catch (RuntimeException failure) {
                    rejection = "Request settings are invalid: " + failureMessage(failure);
                    prepared = null;
                }
                submission = prepared;
                if (prepared != null) {
                    holder.scope = nextScope;
                    holder.active = true;
                    holder.requestStarting = true;
                    holder.requestAuthEpochs.put(submission.requestId(), authEpoch);
                    newChatPending = false;
                }
            }
            epoch = worldEpoch;
        }
        if (rejection != null) {
            synchronized (stateLock) {
                runtimeErrors.add(rejection);
            }
            publishCurrentView(epoch);
            return;
        }
        if (explicitWebSearch && searchMode == ModelBackend.SearchMode.DISABLED) {
            synchronized (stateLock) {
                runtimeErrors.add("Hosted web search is unavailable for the selected model; using local evidence");
            }
        }
        if (includeScreenshot && Boolean.FALSE.equals(selectedModel.imageInput())) {
            synchronized (stateLock) {
                runtimeErrors.add("Screenshot omitted from the model request because image input is unsupported");
            }
        }
        long dispatchAtNanos = System.nanoTime();
        PackSageLog.LOGGER.info("PackSage timing request={} phase=runtime_dispatch start_ns={} event_ns={} elapsed_ms={}",
                submission.requestId().value(), sendStartedAtNanos, dispatchAtNanos,
                (dispatchAtNanos - sendStartedAtNanos) / 1_000_000);
        try {
            holder.controller.begin(submission).whenCompleteAsync((ignored, failure) -> {
                boolean publish = false;
                synchronized (stateLock) {
                    holder.requestStarting = false;
                    if (failure != null) {
                        holder.requestAuthEpochs.remove(submission.requestId());
                        if (holder.scope.equals(submission.scope())) {
                            holder.scope = previousScope;
                            holder.active = false;
                            holder.errors.add("Request could not start: " + failureMessage(failure));
                            publish = true;
                        }
                    }
                }
                if (publish) publishCurrentView(epoch);
            }, controllerExecutor);
        } catch (RuntimeException failure) {
            synchronized (stateLock) {
                holder.requestStarting = false;
                holder.requestAuthEpochs.remove(submission.requestId());
                if (holder.scope.equals(submission.scope())) {
                    holder.scope = previousScope;
                    holder.active = false;
                    holder.errors.add("Request could not start: " + failureMessage(failure));
                }
            }
            publishCurrentView(epoch);
        }
        publishCurrentView(epoch);
        refreshSessionList();
    }

    @Override
    public boolean steer(String text) {
        Holder holder;
        synchronized (stateLock) {
            holder = selected;
        }
        if (holder != null && holder.controller != null && holder.controller.steer(text)) return true;
        synchronized (stateLock) {
            if (holder != null) holder.errors.add(text == null || text.isBlank() ? "Message cannot be blank"
                    : "Follow-up could not be accepted while the response is preparing or has finished");
            else runtimeErrors.add(text == null || text.isBlank() ? "Message cannot be blank"
                    : "Follow-up could not be accepted while the response is preparing or has finished");
        }
        publishCurrentView(worldEpoch);
        return false;
    }

    @Override
    public void stop() {
        Holder holder;
        synchronized (stateLock) {
            holder = selected;
        }
        if (holder != null && holder.controller != null) holder.controller.stop();
    }

    @Override
    public void newChat() {
        Holder current;
        ChatContracts.WorldIdentity currentWorld;
        Evidence.Scope currentScope;
        long epoch;
        synchronized (stateLock) {
            current = selected;
            currentWorld = world;
            currentScope = worldScope;
            publicationEpoch++;
            newChatPending = true;
            sessionSelectionGeneration++;
            epoch = worldEpoch;
        }
        if (currentWorld == null) {
            synchronized (stateLock) {
                selected = null;
                sessionLoading = false;
                newChatPending = false;
            }
            publishCurrentView(epoch);
            return;
        }
        ChatContracts.View currentView = current == null || current.controller == null ? idleView() : current.controller.view();
        boolean reuse = current != null && !current.deleting && !current.active && !current.requestStarting
                && (current.loading && current.creating || current.loaded && !currentView.active()
                && currentView.conversation().turns().isEmpty());
        if (reuse) {
            synchronized (stateLock) {
                if (world == null || epoch != worldEpoch || !currentWorld.worldOrServer().equals(world.worldOrServer())) return;
                current.scope = currentScope;
                selected = current;
                sessionLoading = current.loading;
                newChatPending = false;
            }
            if (current.controller != null) current.controller.newChat(currentWorld, currentScope);
        } else {
            Holder created = new Holder(currentWorld, "pending-world:" + UUID.randomUUID(), currentScope);
            created.loading = true;
            created.creating = true;
            synchronized (stateLock) {
                if (world == null || epoch != worldEpoch || !currentWorld.worldOrServer().equals(world.worldOrServer())) return;
                holders.put(new HolderKey(created.world.worldOrServer(), created.id), created);
                selected = created;
                sessionLoading = true;
                newChatPending = false;
            }
            initializeHolder(created);
            created.controller.newChat(currentWorld, currentScope, true);
        }
        publishCurrentView(epoch);
        refreshSessionList();
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
        long navigationId;
        Holder holder;
        long epoch;
        long selectionGeneration;
        ChatContracts.WorldIdentity currentWorld;
        synchronized (stateLock) {
            navigationId = ++navigationGeneration;
            holder = selected;
            epoch = worldEpoch;
            selectionGeneration = sessionSelectionGeneration;
            currentWorld = world;
        }
        showNavigationError(navigationId, null);
        if (holder == null || currentWorld == null || !currentWorld.worldOrServer().equals(holder.world.worldOrServer())) {
            showNavigationError(navigationId, "Join this session's world before opening its sources");
            return;
        }
        ChatContracts.View current = holder.controller.view();
        if (current.navigationTargets().stream().noneMatch(value -> value.equals(target))
                || current.sources().stream().noneMatch(source -> source.scope().equals(target.scope())
                && source.providerId().equals(target.providerId()) && source.sourceHandle().equals(target.sourceHandle()))) {
            showNavigationError(navigationId, "Navigation target is stale");
            return;
        }
        if ("web".equals(target.providerId())) {
            if (!target.sourceHandle().equals(target.targetId()) || externalUrl(target.targetId()) == null) {
                showNavigationError(navigationId, "Navigation target is invalid");
                return;
            }
            try {
                externalNavigation.accept(target.targetId());
            } catch (RuntimeException failure) {
                showNavigationError(navigationId, "Navigation failed: " + failureMessage(failure));
            }
            return;
        }
        KnowledgeService currentKnowledge = holder.knowledge;
        currentKnowledge.refreshNavigation(target, target.scope()).whenCompleteAsync((refreshed, failure) -> {
            synchronized (stateLock) {
                if (navigationId != navigationGeneration || epoch != worldEpoch
                        || selectionGeneration != sessionSelectionGeneration || selected != holder
                        || currentKnowledge != holder.knowledge || world == null
                        || !world.worldOrServer().equals(holder.world.worldOrServer())) return;
            }
            if (failure != null) {
                showNavigationError(navigationId, "Navigation failed: " + failureMessage(failure));
            } else if (refreshed == null) {
                showNavigationError(navigationId, "Navigation target is no longer available");
            } else {
                try {
                    var result = currentKnowledge.open(refreshed, target.scope());
                    if (result.status() != Evidence.NavigationStatus.OPENED) showNavigationError(navigationId,
                            "Navigation " + result.status().name().toLowerCase() + ": "
                                    + (result.detail() == null ? "target unavailable" : result.detail()));
                } catch (RuntimeException navigationFailure) {
                    showNavigationError(navigationId, "Navigation failed: " + failureMessage(navigationFailure));
                }
            }
        }, clientExecutor);
    }

    private void showNavigationError(long navigationId, String error) {
        AssistantScreen screen;
        synchronized (stateLock) {
            if (navigationId != navigationGeneration) return;
            screen = currentScreen;
        }
        if (screen != null) screen.showNavigationError(error);
    }

    private CompletionStage<ChatContracts.ToolResponse> executeTool(Holder holder, ChatContracts.ToolRequest request) {
        KnowledgeService currentKnowledge;
        synchronized (stateLock) {
            Long requestAuthEpoch = holder.requestAuthEpochs.get(request.submission().requestId());
            if (requestAuthEpoch == null || requestAuthEpoch != authEpoch || signIn != ChatContracts.SignInState.SIGNED_IN
                    || world == null || !world.worldOrServer().equals(holder.world.worldOrServer())
                    || !request.submission().scope().equals(holder.scope)) {
                return java.util.concurrent.CompletableFuture.failedFuture(
                        new IllegalStateException("request scope is no longer active"));
            }
            currentKnowledge = holder.knowledge;
        }
        PackSageTools currentTools = new PackSageTools(read -> currentKnowledge.execute(read,
                request.submission().replacing() == null ? null : request.submission().replacing().value()), currentKnowledge.metadata());
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

    private void publishHolderView(Holder holder, ChatContracts.View view) {
        long epoch;
        long publication;
        boolean isSelected;
        synchronized (stateLock) {
            holder.active = view.active();
            holder.requestStarting = false;
            if (holder.id.startsWith("pending-world:") && !view.conversation().id().startsWith("pending")) {
                holders.remove(new HolderKey(holder.world.worldOrServer(), holder.id));
                holder.id = view.conversation().id();
                holder.world = view.conversation().world();
                holders.put(new HolderKey(holder.world.worldOrServer(), holder.id), holder);
                holder.loaded = true;
                holder.loading = false;
                holder.creating = false;
                if (selected == holder) sessionLoading = false;
            }
            if (!view.conversation().id().startsWith("pending")) {
                holder.loaded = true;
                holder.creating = false;
            }
            isSelected = selected == holder;
            if (!isSelected) return;
            epoch = worldEpoch;
            publication = publicationEpoch;
        }
        clientExecutor.execute(() -> {
            AssistantScreen screen;
            synchronized (stateLock) {
                if (epoch != worldEpoch || publication != publicationEpoch || selected != holder || currentScreen == null) return;
                screen = currentScreen;
            }
            ChatContracts.View latest = view();
            synchronized (stateLock) {
                if (epoch != worldEpoch || publication != publicationEpoch || selected != holder || currentScreen != screen) return;
            }
            screen.refresh(latest);
        });
    }

    private void recordCompletion(Holder holder, ChatContracts.Turn turn) {
        synchronized (stateLock) {
            if (holder.deleting || holders.get(new HolderKey(holder.world.worldOrServer(), holder.id)) != holder) return;
        }
        CompletionStage<Void> recorded;
        try {
            recorded = store.recordCompletion(holder.world, holder.id, turn.requestId());
        } catch (RuntimeException failure) {
            recorded = java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
        recorded.whenCompleteAsync((ignored, failure) -> {
            if (failure != null) {
                synchronized (stateLock) {
                    holder.errors.add("Completion state could not be saved: " + failureMessage(failure));
                }
                boolean publish;
                long epoch;
                synchronized (stateLock) {
                    publish = selected == holder;
                    epoch = worldEpoch;
                }
                if (publish) publishCurrentView(epoch);
                return;
            }
            store.sessions().whenCompleteAsync((summaries, summaryFailure) -> {
                boolean notify = false;
                boolean publish;
                long epoch;
                if (summaryFailure != null) {
                    synchronized (stateLock) {
                        holder.errors.add("Session list could not be refreshed: " + failureMessage(summaryFailure));
                        holder.requestAuthEpochs.remove(turn.requestId());
                        publish = selected == holder;
                        epoch = worldEpoch;
                    }
                } else {
                    ChatContracts.SessionSummary summary = summaries.stream()
                            .filter(value -> value.world().worldOrServer().equals(holder.world.worldOrServer())
                                    && value.id().equals(holder.id)).findFirst().orElse(null);
                    synchronized (stateLock) {
                        Long requestAuthEpoch = holder.requestAuthEpochs.remove(turn.requestId());
                        if (summary != null && turn.requestId().equals(summary.unreadRequestId())) {
                            holder.unreadRequestId = turn.requestId();
                            notify = requestAuthEpoch != null && requestAuthEpoch == authEpoch
                                    && signIn == ChatContracts.SignInState.SIGNED_IN && world != null
                                    && world.worldOrServer().equals(holder.world.worldOrServer())
                                    && holder.notifiedCompletions.add(turn.requestId());
                        }
                        publish = selected == holder;
                        epoch = worldEpoch;
                    }
                }
                refreshSessionList();
                if (publish) publishCurrentView(epoch);
                if (notify) completionNotice.accept(turn);
            }, clientExecutor);
        }, clientExecutor);
    }

    private void refreshSessionList() {
        AssistantScreen screen;
        long generation;
        synchronized (stateLock) {
            screen = currentScreen;
            generation = ++sessionListGeneration;
        }
        if (screen == null) return;
        sessions().whenCompleteAsync((values, failure) -> {
            if (failure != null) return;
            synchronized (stateLock) {
                if (generation != sessionListGeneration || screen != currentScreen) return;
            }
            screen.refreshSessions(values);
        }, clientExecutor);
    }

    private List<ChatContracts.SessionSummary> mergeSessions(List<ChatContracts.SessionSummary> stored) {
        Map<HolderKey, ChatContracts.SessionSummary> summaries = new java.util.LinkedHashMap<>();
        stored.forEach(summary -> summaries.put(new HolderKey(summary.world().worldOrServer(), summary.id()), summary));
        List<Holder> current;
        synchronized (stateLock) {
            current = List.copyOf(holders.values());
        }
        for (Holder holder : current) {
            if (!holder.loaded || holder.id.startsWith("pending") || holder.controller == null) continue;
            ChatContracts.View view = holder.controller.view();
            ChatContracts.Conversation conversation = view.conversation();
            ChatContracts.SessionSummary base = summaries.get(new HolderKey(holder.world.worldOrServer(), holder.id));
            ChatContracts.Turn last = conversation.turns().isEmpty() ? null : conversation.turns().getLast();
            String title = view.active() ? view.activeQuestion() : last == null
                    ? base == null ? "New chat" : base.title() : last.question();
            String lastMessage = view.active() ? view.streamingAnswer() : last == null
                    ? base == null ? "" : base.lastMessage() : last.answer();
            Instant updatedAt = view.active() && view.activeSentAt() != null ? view.activeSentAt()
                    : last != null && last.sentAt() != null ? last.sentAt() : base == null ? Instant.EPOCH : base.updatedAt();
            ChatContracts.RequestId unread = holder.unreadRequestId == null && base != null
                    ? base.unreadRequestId() : holder.unreadRequestId;
            summaries.put(new HolderKey(holder.world.worldOrServer(), holder.id), new ChatContracts.SessionSummary(
                    holder.world, holder.id, title, lastMessage, updatedAt, view.active(), unread));
        }
        return summaries.values().stream().sorted(java.util.Comparator.comparing(ChatContracts.SessionSummary::updatedAt).reversed())
                .toList();
    }

    private void publishCurrentView(long epoch) {
        long publication;
        Holder holder;
        synchronized (stateLock) {
            publication = publicationEpoch;
            holder = selected;
        }
        clientExecutor.execute(() -> {
            AssistantScreen screen;
            synchronized (stateLock) {
                if (epoch != worldEpoch || publication != publicationEpoch || currentScreen == null || selected != holder) return;
                screen = currentScreen;
            }
            ChatContracts.View latest = view();
            synchronized (stateLock) {
                if (epoch != worldEpoch || publication != publicationEpoch || selected != holder || currentScreen != screen) return;
            }
            screen.refresh(latest);
        });
    }

    private ChatContracts.View wrap(ChatContracts.View view, Holder holder) {
        ChatContracts.SignInState currentSignIn;
        boolean paused;
        List<String> errors;
        Evidence.Scope currentScope;
        ChatContracts.WorldIdentity currentWorld;
        boolean loading;
        boolean readOnly;
        synchronized (stateLock) {
            currentSignIn = signIn;
            paused = requestedPaused;
            errors = new ArrayList<>(view.errors());
            errors.addAll(runtimeErrors);
            if (holder != null) errors.addAll(holder.errors);
            currentScope = holder == null ? worldScope : holder.scope;
            currentWorld = world;
            loading = sessionLoading || holder != null && holder.loading;
            readOnly = loading || holder != null && currentWorld != null
                    && !currentWorld.worldOrServer().equals(holder.world.worldOrServer());
        }
        ChatContracts.WorldIdentity activeWorld = currentWorld == null ? view.conversation().world() : currentWorld;
        ChatContracts.Conversation conversation = holder == null ? view.conversation() : holder.loaded
                ? view.conversation() : new ChatContracts.Conversation(holder.id, holder.world, List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.ACTIVE, null));
        List<Evidence.Source> sources = new ArrayList<>();
        List<Evidence.NavigationTarget> targets = new ArrayList<>();
        if (!loading && holder == null) {
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
        if (!loading && currentWorld != null && (readOnly || currentWorld.worldOrServer().equals(conversation.world().worldOrServer()))) {
            conversation.turns().forEach(turn -> {
                turn.sources().forEach(source -> { if (!sources.contains(source)) sources.add(source); });
                turn.navigationTargets().forEach(target -> { if (!targets.contains(target)) targets.add(target); });
            });
        }
        if (!loading && currentWorld != null && (readOnly || currentWorld.worldOrServer().equals(conversation.world().worldOrServer()))) {
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
        if (readOnly) {
            return new ChatContracts.View(false, currentSignIn, paused, conversation,
                    new ChatContracts.Context("", null, currentScope), sources, targets, errors, "",
                    loading ? "Loading session..." : "", activeWorld, true);
        }
        return new ChatContracts.View(view.active(), currentSignIn, paused, view.conversation(), view.context(),
                sources, targets, errors, view.streamingAnswer(), view.activity(), activeWorld, false, view.activeQuestion(),
                view.activeSentAt(), view.activities(), view.activeRequestId(), view.activeSteeringMessages());
    }

    private Holder reserveHolder(ChatContracts.WorldIdentity world, String conversationId, Evidence.Scope scope) {
        synchronized (stateLock) {
            HolderKey key = new HolderKey(world.worldOrServer(), conversationId);
            Holder holder = holders.get(key);
            if (holder == null) {
                holder = new Holder(world, conversationId, scope);
                holders.put(key, holder);
            }
            return holder;
        }
    }

    private void initializeHolder(Holder holder) {
        synchronized (holder) {
            if (holder.controller != null) return;
            holder.capture = Objects.requireNonNull(captureFactory.apply(() -> holder.scope));
            holder.knowledge = Objects.requireNonNull(knowledgeFactory.apply(() -> holder.scope));
            holder.historyAdapter = new HistoryAdapter(store, holder.world, () -> holder.scope);
            holder.knowledge.register(holder.historyAdapter);
            holder.controller = new RequestController(store, backend, controllerExecutor,
                    holder.capture::capture, holder.capture::cancel, ignored -> new ChatContracts.EvidenceSelection(List.of(), List.of()),
                    request -> executeTool(holder, request), historyCounter, prepared -> {
                        ModelBackend.Request request = requestBuilder.apply(prepared);
                        String developerPrompt;
                        synchronized (stateLock) {
                            developerPrompt = settings.developerPrompt();
                        }
                        return new ModelBackend.Request(request.modelId(), developerPrompt == null
                                ? request.instructions() : developerPrompt, request.input(),
                                PackSageTools.definitions(holder.knowledge.metadata()), request.searchMode(), request.requestId(),
                                request.outputBudget(), request.reasoningEffort());
                    }, view -> publishHolderView(holder, view), turn -> recordCompletion(holder, turn));
        }
    }

    private ChatContracts.View idleView() {
        ChatContracts.WorldIdentity activeWorld;
        ChatContracts.SignInState currentSignIn;
        boolean paused;
        List<String> errors;
        Evidence.Scope currentScope;
        boolean loading;
        synchronized (stateLock) {
            activeWorld = world == null ? new ChatContracts.WorldIdentity("none", "No world") : world;
            currentSignIn = signIn;
            paused = requestedPaused;
            errors = List.copyOf(runtimeErrors);
            currentScope = worldScope;
            loading = sessionLoading;
        }
        ChatContracts.Conversation conversation = new ChatContracts.Conversation("pending", activeWorld, List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.ACTIVE, null));
        return new ChatContracts.View(false, currentSignIn, paused, conversation,
                new ChatContracts.Context("", null, currentScope), List.of(), List.of(), errors, "",
                loading ? "Loading session..." : "", activeWorld, loading);
    }

    private void addError(String error) {
        synchronized (stateLock) {
            runtimeErrors.add(error);
        }
        publishCurrentView(worldEpoch);
    }

    private record HolderKey(String worldId, String conversationId) { }

    private static final class Holder {
        private volatile ChatContracts.WorldIdentity world;
        private volatile String id;
        private volatile Evidence.Scope scope;
        private volatile GameplayContextCapture capture;
        private volatile KnowledgeService knowledge;
        private volatile HistoryAdapter historyAdapter;
        private volatile RequestController controller;
        private volatile boolean loaded;
        private volatile boolean loading;
        private volatile boolean deleting;
        private volatile boolean active;
        private volatile boolean requestStarting;
        private volatile boolean creating;
        private final List<String> errors = new ArrayList<>();
        private final Map<ChatContracts.RequestId, Long> requestAuthEpochs = new java.util.LinkedHashMap<>();
        private final Set<ChatContracts.RequestId> notifiedCompletions = new java.util.LinkedHashSet<>();
        private ChatContracts.RequestId unreadRequestId;
        private ChatContracts.RequestId readAttemptRequestId;
        private long readAttemptSelectionGeneration = -1;
        private AssistantScreen readAttemptScreen;

        private Holder(ChatContracts.WorldIdentity world, String id, Evidence.Scope scope) {
            this.world = world;
            this.id = id;
            this.scope = scope;
        }
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
