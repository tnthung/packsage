package dev.packsage.chat;

import dev.packsage.PackSageLog;
import dev.packsage.client.AssistantScreen;
import dev.packsage.client.GameplayContextCapture;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeService;
import dev.packsage.knowledge.Toolbox;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

public final class AssistantRuntime implements ChatActions {
    private static final Evidence.Scope EMPTY_SCOPE = new Evidence.Scope("none", "none", "none", "none", 0);
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
    private dev.packsage.world.CommandRunner commandRunner;
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
        boolean sessionActivityChanged;
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
                sessionActivityChanged = previous.stream().anyMatch(holder -> holder.active || holder.requestStarting);
                previous.forEach(holder -> {
                    holder.viewReconciliationGeneration++;
                    holder.requestAuthEpochs.clear();
                    if (holder.requestActivation != null) holder.requestActivation.set(false);
                    holder.active = false;
                    holder.requestStarting = false;
                    holder.loadGeneration++;
                    if (holder.loading) {
                        holder.loading = false;
                        holder.creating = false;
                    }
                });
            }
        }
        previous.forEach(holder -> { if (holder.controller != null) holder.controller.worldChanged(); });
        if (sessionActivityChanged) refreshSessionList();
        try {
            store.openLatest(world).toCompletableFuture().orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .whenCompleteAsync((conversation, failure) -> {
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
                Holder holder;
                long loadGeneration;
                synchronized (stateLock) {
                    if (epoch != worldEpoch || selectionGeneration != sessionSelectionGeneration || selected != null || this.world == null
                            || !this.world.worldOrServer().equals(conversation.world().worldOrServer())) return;
                    HolderKey key = new HolderKey(conversation.world().worldOrServer(), conversation.id());
                    holder = holders.get(key);
                    if (holder == null) {
                        holder = new Holder(conversation.world(), conversation.id(), worldScope);
                        holders.put(key, holder);
                    }
                    holder.scope = worldScope;
                    holder.loading = true;
                    holder.creating = false;
                    loadGeneration = ++holder.loadGeneration;
                }
                CompletionStage<List<ChatContracts.SessionSummary>> summaries;
                try {
                    summaries = store.summary(conversation.world(), conversation.id())
                            .thenApply(summary -> summary == null ? List.of() : List.of(summary));
                } catch (RuntimeException ignored) {
                    summaries = CompletableFuture.completedFuture(List.of());
                }
                Holder loadingHolder = holder;
                long holderLoadGeneration = loadGeneration;
                summaries.whenCompleteAsync((storedSessions, summaryFailure) -> {
                    synchronized (stateLock) {
                        if (epoch != worldEpoch || holders.get(new HolderKey(loadingHolder.world.worldOrServer(), loadingHolder.id)) != loadingHolder
                                || loadingHolder.loadGeneration != holderLoadGeneration
                                || loadingHolder.deleting || world == null
                                || !world.worldOrServer().equals(conversation.world().worldOrServer())) return;
                        if (summaryFailure == null && storedSessions != null) {
                            ChatContracts.SessionSummary summary = storedSessions.stream().filter(value ->
                                    value.world().worldOrServer().equals(conversation.world().worldOrServer())
                                            && value.id().equals(conversation.id())).findFirst().orElse(null);
                            if (summary != null) loadingHolder.unreadRequestId = summary.unreadRequestId();
                        }
                    }
                }, clientExecutor);
                initializeHolder(loadingHolder).whenCompleteAsync((ignored, initializationFailure) -> {
                    Throwable setupFailure = initializationFailure;
                    Evidence.Scope attachmentScope = null;
                    if (setupFailure == null) {
                        synchronized (stateLock) {
                            if (epoch != worldEpoch || holders.get(new HolderKey(loadingHolder.world.worldOrServer(), loadingHolder.id)) != loadingHolder
                                    || loadingHolder.loadGeneration != holderLoadGeneration || !loadingHolder.loading
                                    || loadingHolder.deleting || world == null
                                    || !world.worldOrServer().equals(conversation.world().worldOrServer())) return;
                            attachmentScope = worldScope;
                            loadingHolder.scope = attachmentScope;
                        }
                        try {
                            loadingHolder.controller.showConversation(conversation, attachmentScope);
                        } catch (RuntimeException attachFailure) {
                            setupFailure = attachFailure;
                        }
                    }
                    if (setupFailure != null) {
                        boolean publish;
                        long currentEpoch;
                        synchronized (stateLock) {
                            if (epoch != worldEpoch || holders.get(new HolderKey(loadingHolder.world.worldOrServer(), loadingHolder.id)) != loadingHolder
                                    || loadingHolder.loadGeneration != holderLoadGeneration || !loadingHolder.loading
                                    || loadingHolder.deleting || world == null
                                    || !world.worldOrServer().equals(conversation.world().worldOrServer())) return;
                            loadingHolder.loading = false;
                            if (selected == loadingHolder || selected == null && selectionGeneration == sessionSelectionGeneration) {
                                sessionLoading = false;
                                runtimeErrors.add("Knowledge providers could not be refreshed: " + failureMessage(setupFailure));
                            } else {
                                loadingHolder.errors.add("Knowledge providers could not be refreshed: " + failureMessage(setupFailure));
                            }
                            publish = selected == loadingHolder || selected == null && selectionGeneration == sessionSelectionGeneration;
                            currentEpoch = worldEpoch;
                        }
                        if (publish) publishCurrentView(currentEpoch);
                        return;
                    }
                    boolean publish;
                    long currentEpoch;
                    synchronized (stateLock) {
                        if (epoch != worldEpoch || holders.get(new HolderKey(loadingHolder.world.worldOrServer(), loadingHolder.id)) != loadingHolder
                                || loadingHolder.loadGeneration != holderLoadGeneration || !loadingHolder.loading
                                || loadingHolder.deleting || world == null
                                || !world.worldOrServer().equals(conversation.world().worldOrServer())) return;
                        loadingHolder.loaded = true;
                        loadingHolder.loading = false;
                        loadingHolder.creating = false;
                        if (selected == loadingHolder) sessionLoading = false;
                        else if (selectionGeneration == sessionSelectionGeneration && selected == null) {
                            selected = loadingHolder;
                            sessionLoading = false;
                        }
                        newChatPending = false;
                        publish = selected == loadingHolder;
                        currentEpoch = worldEpoch;
                    }
                    if (publish) publishCurrentView(currentEpoch);
                }, clientExecutor);
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
        boolean sessionActivityChanged;
        synchronized (stateLock) {
            publicationEpoch++;
            worldScope = newScope;
            requestGeneration = Math.max(requestGeneration, newScope.requestGeneration());
            newChatPending = false;
            if (world != null) holders.values().stream()
                    .filter(holder -> holder.world.worldOrServer().equals(world.worldOrServer()))
                    .forEach(holder -> holder.scope = newScope);
            invalidate = List.copyOf(holders.values());
            sessionActivityChanged = invalidate.stream().anyMatch(holder -> holder.active || holder.requestStarting);
            invalidate.forEach(holder -> {
                holder.viewReconciliationGeneration++;
                holder.requestAuthEpochs.clear();
                if (holder.requestActivation != null) holder.requestActivation.set(false);
                holder.active = false;
                holder.requestStarting = false;
            });
        }
        invalidate.forEach(holder -> { if (holder.controller != null) holder.controller.invalidate(newScope.worldSession()); });
        if (sessionActivityChanged) refreshSessionList();
        publishCurrentView(worldEpoch);
    }

    public void disconnect() {
        List<Holder> disconnect;
        boolean sessionActivityChanged;
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
                sessionActivityChanged = disconnect.stream().anyMatch(holder -> holder.active || holder.requestStarting);
                disconnect.forEach(holder -> {
                    holder.viewReconciliationGeneration++;
                    holder.requestAuthEpochs.clear();
                    if (holder.requestActivation != null) holder.requestActivation.set(false);
                    holder.active = false;
                    holder.requestStarting = false;
                    holder.loadGeneration++;
                    if (holder.loading) {
                        holder.loading = false;
                        holder.creating = false;
                    }
                });
            }
        }
        disconnect.forEach(holder -> { if (holder.controller != null) holder.controller.disconnect(); });
        if (sessionActivityChanged) refreshSessionList();
    }

    @Override
    public Map<String, Boolean> adapterAvailability() {
        Holder holder;
        boolean activeWorld;
        synchronized (stateLock) {
            holder = selected;
            activeWorld = world != null && holder != null && holder.world.worldOrServer().equals(world.worldOrServer());
        }
        if (!activeWorld || !holder.loaded || holder.knowledge == null || holder.knowledgeUnavailable) return Map.ofEntries(
                Map.entry("native", false), Map.entry("config", false), Map.entry("world", false), Map.entry("progress", false), Map.entry("journal", false), Map.entry("history", false));
        var availability = new java.util.LinkedHashMap<String, Boolean>();
        Map<String, Boolean> providers = holder.knowledge.adapterAvailability();
        for (String id : List.of("native", "config", "world", "progress", "journal")) {
            availability.put(id, providers.getOrDefault(id, false));
        }
        availability.put("history", true);
        return Map.copyOf(availability);
    }

    @Override
    public Map<String, String> adapterDetails() {
        KnowledgeService knowledge;
        synchronized (stateLock) {
            if (world == null || selected == null || !selected.world.worldOrServer().equals(world.worldOrServer())
                    || !selected.loaded || selected.knowledge == null || selected.knowledgeUnavailable) return Map.of();
            knowledge = selected.knowledge;
        }
        return knowledge.adapterDetails();
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
    public CompletionStage<Void> loadSessions(Consumer<List<ChatContracts.SessionSummary>> refresh) {
        return sessionPage(0, settings().sessionsBeforeCollapse()).thenAccept(page -> refresh.accept(page.sessions()));
    }

    @Override
    public CompletionStage<ConversationStore.SessionPage> sessionPage(int offset, int limit) {
        ChatContracts.WorldIdentity currentWorld;
        boolean otherWorlds;
        synchronized (stateLock) {
            currentWorld = world;
            otherWorlds = settings.showChatsFromOtherSessions();
        }
        if (currentWorld == null) return CompletableFuture.completedFuture(new ConversationStore.SessionPage(List.of(), false));
        return store.sessionPage(currentWorld, otherWorlds, offset, limit).thenApply(page -> {
            Set<HolderKey> listed = page.sessions().stream().map(session -> new HolderKey(session.world().worldOrServer(), session.id()))
                    .collect(java.util.stream.Collectors.toSet());
            return new ConversationStore.SessionPage(mergeSessions(page.sessions()).stream()
                    .filter(session -> listed.contains(new HolderKey(session.world().worldOrServer(), session.id()))).toList(),
                    page.hasMore(), page.nextOffset());
        });
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
                    || !expectedCompletion.equals(session.unreadRequestId())) return;
            selectionGeneration = sessionSelectionGeneration;
        }
        ChatContracts.View displayed = holder.controller == null ? null : holder.controller.view();
        if (displayed == null || displayed.conversation().turns().stream().noneMatch(turn ->
                turn.requestId().equals(expectedCompletion)
                        && turn.outcome().status() == ChatContracts.OutcomeStatus.COMPLETED)) return;
        synchronized (stateLock) {
            if (selected != holder || selectionGeneration != sessionSelectionGeneration || holder.loading || holder.deleting
                    || currentScreen != screen) return;
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
                } else if (!holder.deleting
                        && holders.get(new HolderKey(holder.world.worldOrServer(), holder.id)) == holder) {
                    if (expectedCompletion.equals(holder.unreadRequestId)) holder.unreadRequestId = null;
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
            if (holder != null) {
                holder.deleting = true;
                holder.requestAuthEpochs.clear();
            }
            deletingSelected = holder != null && selected == holder || holder == null && selected == null
                    && sessionLoading && world.worldOrServer().equals(session.world().worldOrServer());
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
                        Holder currentHolder = holders.get(new HolderKey(session.world().worldOrServer(), session.id()));
                        if (currentHolder != null && (holder == null || currentHolder == holder)) {
                            currentHolder.deleting = false;
                            if (failure == null) holders.remove(new HolderKey(currentHolder.world.worldOrServer(), currentHolder.id), currentHolder);
                            else currentHolder.errors.add("Session could not be deleted: " + failureMessage(failure));
                        }
                        if (deletionGeneration == sessionDeletionGeneration) sessionDeletion = false;
                        if (failure == null && deletingSelected && epoch == worldEpoch && world != null
                                && (holder == null ? selected == null || selected == currentHolder : selected == holder)) {
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
        long loadGeneration = -1;
        synchronized (stateLock) {
            if (world == null) return;
            sessionSelectionGeneration++;
            publicationEpoch++;
            epoch = worldEpoch;
            holder = holders.get(new HolderKey(session.world().worldOrServer(), session.id()));
            if (holder == null) {
                holder = new Holder(session.world(), session.id(), worldScope);
                holders.put(new HolderKey(session.world().worldOrServer(), session.id()), holder);
            }
            if (holder.deleting) return;
            if (holder.unreadRequestId == null) holder.unreadRequestId = session.unreadRequestId();
            if (!holder.active && !holder.requestStarting) holder.scope = worldScope;
            selected = holder;
            startLoad = !holder.loading && !holder.active && !holder.requestStarting;
            if (startLoad) {
                holder.loading = true;
                loadGeneration = ++holder.loadGeneration;
            }
            sessionLoading = holder.loading;
            newChatPending = false;
        }
        if (!startLoad) {
            publishCurrentView(epoch);
            if (holder.loaded) refreshSessionList();
            return;
        }
        publishCurrentView(epoch);
        Holder loadingHolder = holder;
        long holderLoadGeneration = loadGeneration;
        initializeHolder(loadingHolder).whenCompleteAsync((ignored, initializationFailure) -> {
            if (initializationFailure != null) {
                boolean publish;
                long selectedEpoch;
                synchronized (stateLock) {
                    if (epoch != worldEpoch || holders.get(new HolderKey(loadingHolder.world.worldOrServer(), loadingHolder.id)) != loadingHolder
                            || loadingHolder.loadGeneration != holderLoadGeneration || !loadingHolder.loading
                            || loadingHolder.deleting) return;
                    loadingHolder.loading = false;
                    if (selected == loadingHolder) {
                        sessionLoading = false;
                        runtimeErrors.add("Knowledge providers could not be refreshed: " + failureMessage(initializationFailure));
                    } else {
                        loadingHolder.errors.add("Knowledge providers could not be refreshed: " + failureMessage(initializationFailure));
                    }
                    publish = selected == loadingHolder;
                    selectedEpoch = worldEpoch;
                }
                if (publish) publishCurrentView(selectedEpoch);
                return;
            }
            synchronized (stateLock) {
                if (epoch != worldEpoch || holders.get(new HolderKey(loadingHolder.world.worldOrServer(), loadingHolder.id)) != loadingHolder
                        || loadingHolder.loadGeneration != holderLoadGeneration || !loadingHolder.loading
                        || loadingHolder.deleting || world == null) return;
                loadingHolder.scope = worldScope;
            }
            CompletionStage<ChatContracts.Conversation> opening;
            try {
                opening = loadingHolder.controller.awaitPersistence().thenComposeAsync(persisted -> {
                    synchronized (stateLock) {
                        if (epoch != worldEpoch || holders.get(new HolderKey(loadingHolder.world.worldOrServer(), loadingHolder.id)) != loadingHolder
                                || loadingHolder.loadGeneration != holderLoadGeneration || !loadingHolder.loading
                                || loadingHolder.deleting || world == null) {
                            return CompletableFuture.failedFuture(new IllegalStateException("session load is no longer current"));
                        }
                    }
                    return store.openConversation(session.world(), session.id());
                }, clientExecutor);
            } catch (RuntimeException failure) {
                opening = CompletableFuture.failedFuture(failure);
            }
            opening.toCompletableFuture().orTimeout(5, java.util.concurrent.TimeUnit.SECONDS).whenCompleteAsync((conversation, openFailure) -> {
                Throwable loadFailure = openFailure;
                Evidence.Scope attachmentScope = null;
                synchronized (stateLock) {
                    if (epoch != worldEpoch || holders.get(new HolderKey(loadingHolder.world.worldOrServer(), loadingHolder.id)) != loadingHolder
                            || loadingHolder.loadGeneration != holderLoadGeneration || !loadingHolder.loading
                            || loadingHolder.deleting || world == null) return;
                    if (loadFailure == null) {
                        attachmentScope = worldScope;
                        loadingHolder.scope = attachmentScope;
                    }
                }
                if (loadFailure == null) {
                    try {
                        loadingHolder.controller.showConversation(conversation, attachmentScope);
                    } catch (RuntimeException attachFailure) {
                        loadFailure = attachFailure;
                    }
                }
                boolean selectedHolder;
                long selectedEpoch;
                synchronized (stateLock) {
                    if (epoch != worldEpoch || holders.get(new HolderKey(loadingHolder.world.worldOrServer(), loadingHolder.id)) != loadingHolder
                            || loadingHolder.loadGeneration != holderLoadGeneration || !loadingHolder.loading
                            || loadingHolder.deleting) return;
                    if (loadFailure == null) loadingHolder.loaded = true;
                    else loadingHolder.errors.add("Session could not be opened: " + failureMessage(loadFailure));
                    loadingHolder.loading = false;
                    selectedHolder = selected == loadingHolder;
                    if (selectedHolder) sessionLoading = false;
                    selectedEpoch = worldEpoch;
                }
                if (selectedHolder) publishCurrentView(selectedEpoch);
                refreshSessionList();
            }, clientExecutor);
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
        return holder == null ? idleView() : wrap(holder.controller == null ? idleView() : holder.controller.view(), holder);
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
        boolean sessionActivityChanged;
        synchronized (stateLock) {
            authEpoch++;
            signIn = ChatContracts.SignInState.SIGNED_OUT;
            capabilities = new ModelBackend.Capabilities(List.of(), null, null, null, null, null);
            invalidate = List.copyOf(holders.values());
            sessionActivityChanged = invalidate.stream().anyMatch(holder -> holder.active || holder.requestStarting);
            invalidate.forEach(holder -> {
                holder.viewReconciliationGeneration++;
                holder.requestAuthEpochs.clear();
                if (holder.requestActivation != null) holder.requestActivation.set(false);
                holder.active = false;
                holder.requestStarting = false;
            });
        }
        invalidate.forEach(holder -> { if (holder.controller != null) holder.controller.invalidate("account signed out"); });
        if (sessionActivityChanged) refreshSessionList();
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

    /** Client-thread submission through the same validation and controller path as the screen. */
    public ChatContracts.RequestId sendTracked(String question) {
        return sendTracked(question, null, null);
    }

    public ChatContracts.RequestId sendTracked(String question, Boolean includeScreenshot, Boolean explicitWebSearch) {
        if (view().active()) throw new IllegalStateException("Conversation already has an active request");
        return send(question, includeScreenshot == null ? currentScreen == null || currentScreen.includeScreenshot() : includeScreenshot,
                explicitWebSearch == null ? currentScreen != null && currentScreen.explicitWebSearch() : explicitWebSearch, null);
    }

    public boolean sessionReady() {
        synchronized (stateLock) {
            return world != null && selected != null && selected.controller != null && selected.loaded
                    && !selected.loading && !selected.deleting && !sessionLoading;
        }
    }

    public ChatContracts.View requestView(ChatContracts.RequestId requestId) {
        List<Holder> current;
        synchronized (stateLock) {
            current = List.copyOf(holders.values());
        }
        for (Holder holder : current) {
            if (holder.controller == null) continue;
            var view = holder.controller.view();
            if (requestId.value().equals(view.activeRequestId()) || view.conversation().turns().stream()
                    .anyMatch(turn -> turn.requestId().equals(requestId))) {
                var wrapped = wrap(view, holder);
                var turn = view.conversation().turns().stream().filter(value -> value.requestId().equals(requestId)).findFirst().orElse(null);
                return new ChatContracts.View(wrapped.active(), wrapped.signIn(), wrapped.paused(), wrapped.conversation(),
                        wrapped.context(), wrapped.sources(), (turn == null ? view.navigationTargets() : turn.navigationTargets()).stream()
                        .filter(wrapped.navigationTargets()::contains).toList(), wrapped.errors(), wrapped.streamingAnswer(),
                        wrapped.activity(), wrapped.activeWorld(), wrapped.readOnly(), wrapped.activeQuestion(), wrapped.activeSentAt(),
                        wrapped.activities(), wrapped.activeRequestId(), wrapped.activeSteeringMessages());
            }
            ChatContracts.Turn failed;
            synchronized (stateLock) {
                failed = holder.failedStarts.get(requestId);
            }
            if (failed != null) return wrap(new ChatContracts.View(false, view.signIn(), view.paused(),
                    new ChatContracts.Conversation(view.conversation().id(), holder.world, List.of(failed), failed.outcome()),
                    failed.context(), List.of(), List.of(), view.errors(), "", ""), holder);
        }
        return null;
    }

    public CompletionStage<ChatContracts.Conversation> savedRequest(ChatContracts.RequestId requestId) {
        List<Holder> current;
        synchronized (stateLock) {
            current = List.copyOf(holders.values());
        }
        for (Holder holder : current) {
            if (holder.controller == null) continue;
            var view = holder.controller.view();
            if (view.conversation().turns().stream().noneMatch(turn -> turn.requestId().equals(requestId))) {
                synchronized (stateLock) {
                    if (holder.failedStarts.containsKey(requestId)) return CompletableFuture.completedFuture(null);
                }
                continue;
            }
            return holder.controller.awaitPersistence().thenCompose(ignored ->
                    store.openConversation(view.conversation().world(), view.conversation().id()));
        }
        return CompletableFuture.failedFuture(new IllegalArgumentException("No saved request"));
    }

    private ChatContracts.RequestId send(String question, boolean includeScreenshot, boolean explicitWebSearch,
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
            return null;
        }
        if (holder == null || holder.controller == null) {
            String error;
            synchronized (stateLock) {
                error = sessionLoading ? "The world history is still loading"
                        : world == null ? "Cannot send without an active world" : "Cannot send without an active conversation";
            }
            addError(error);
            return null;
        }
        if (holder.active || holder.requestStarting || holder.controller.view().active()) {
            if (!holder.controller.steer(question)) {
                synchronized (stateLock) {
                    holder.errors.add("Follow-up could not be accepted while the response is preparing or has finished");
                }
                publishCurrentView(worldEpoch);
            }
            return null;
        }
        String configuredModelId = currentSettings.modelId();
        ModelBackend.ModelInfo selectedModel = currentCapabilities.modelCatalog().stream()
                .filter(model -> model.id().equals(configuredModelId == null
                        ? currentCapabilities.selectedModelId() : configuredModelId)).findFirst().orElse(null);
        if (selectedModel == null) {
            addError(configuredModelId == null ? "No selected model is available in the account model catalog"
                    : "Configured model is unavailable in the account model catalog: " + configuredModelId);
            return null;
        }
        int budget;
        try {
            budget = outputBudget.applyAsInt(selectedModel);
        } catch (RuntimeException failure) {
            addError("Output budget is unavailable: " + failureMessage(failure));
            return null;
        }
        if (budget < 1) {
            addError("Output budget is unavailable");
            return null;
        }
        ChatContracts.HistoryLimits historyLimits;
        try {
            int historicalTokens = currentSettings.historicalTokens();
            if (selectedModel.contextTokens() != null) historicalTokens = Math.min(historicalTokens, selectedModel.contextTokens());
            historyLimits = new ChatContracts.HistoryLimits(currentSettings.historyTurns(), historicalTokens);
        } catch (RuntimeException failure) {
            addError("Request settings are invalid: " + failureMessage(failure));
            return null;
        }
        ModelBackend.SearchMode searchMode = searchMode(selectedModel, currentSettings.automaticWebSearch(), explicitWebSearch);
        ChatContracts.Submission submission;
        Evidence.Scope previousScope;
        AtomicBoolean activation;
        long epoch;
        String rejection = null;
        synchronized (stateLock) {
            if (holder != selected || holder.loading || sessionLoading || holder.deleting || !holder.loaded
                    || world == null || !holder.world.worldOrServer().equals(world.worldOrServer())) {
                rejection = holder.deleting ? "Session deletion is still in progress" : sessionLoading || holder.loading
                        || !holder.loaded ? "Session is still loading" : "This session is read-only until its world is active";
                submission = null;
                previousScope = null;
                activation = null;
            } else if (signIn != ChatContracts.SignInState.SIGNED_IN) {
                rejection = "Sign in before sending a question";
                submission = null;
                previousScope = null;
                activation = null;
            } else if (holder.active || holder.requestStarting) {
                rejection = "Follow-up could not be accepted while the response is preparing or has finished";
                submission = null;
                previousScope = null;
                activation = null;
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
                    activation = new AtomicBoolean(true);
                    holder.viewReconciliationGeneration++;
                    holder.requestActivation = activation;
                    holder.scope = nextScope;
                    holder.active = true;
                    holder.requestStarting = true;
                    holder.requestAuthEpochs.put(submission.requestId(), authEpoch);
                    newChatPending = false;
                } else {
                    activation = null;
                }
            }
            epoch = worldEpoch;
        }
        if (rejection != null) {
            synchronized (stateLock) {
                runtimeErrors.add(rejection);
            }
            publishCurrentView(epoch);
            return null;
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
        AtomicBoolean requestActivation = activation;
        long dispatchAtNanos = System.nanoTime();
        PackSageLog.LOGGER.info("PackSage timing request={} phase=runtime_dispatch start_ns={} event_ns={} elapsed_ms={}",
                submission.requestId().value(), sendStartedAtNanos, dispatchAtNanos,
                (dispatchAtNanos - sendStartedAtNanos) / 1_000_000);
        Consumer<Throwable> recordStartFailure = failure -> {
            synchronized (stateLock) {
                if (holder.failedStarts.size() >= 256) holder.failedStarts.remove(holder.failedStarts.keySet().iterator().next());
                holder.failedStarts.put(submission.requestId(), new ChatContracts.Turn(submission.requestId(), submission.question(),
                        "", List.of(), new ChatContracts.Context("", null, submission.scope()), List.of(), List.of(),
                        new ChatContracts.Outcome(requestActivation.get() ? ChatContracts.OutcomeStatus.FAILED : ChatContracts.OutcomeStatus.CANCELLED,
                                failureMessage(failure)), 0, Instant.now()));
            }
        };
        try {
            holder.controller.begin(submission, requestActivation::get).whenCompleteAsync((ignored, failure) -> {
                if (failure != null) recordStartFailure.accept(failure);
                boolean publish = false;
                boolean refreshSessions = false;
                boolean reconcile = false;
                synchronized (stateLock) {
                    if (failure != null) holder.requestAuthEpochs.remove(submission.requestId());
                    if (holder.requestActivation == requestActivation) {
                        reconcile = true;
                        holder.viewReconciliationGeneration++;
                        boolean wasActive = holder.active;
                        holder.requestStarting = false;
                        if (failure != null) holder.active = false;
                        refreshSessions = wasActive != holder.active;
                        if (failure != null) {
                            if (requestActivation.get() && Objects.equals(holder.scope, submission.scope())) {
                                holder.scope = previousScope;
                                holder.errors.add("Request could not start: " + failureMessage(failure));
                                publish = true;
                            }
                        }
                    }
                }
                if (refreshSessions) refreshSessionList();
                if (reconcile) publishHolderView(holder, null);
                if (publish) publishCurrentView(epoch);
            }, controllerExecutor);
        } catch (RuntimeException failure) {
            recordStartFailure.accept(failure);
            boolean publish = false;
            boolean refreshSessions = false;
            boolean reconcile = false;
            synchronized (stateLock) {
                holder.requestAuthEpochs.remove(submission.requestId());
                if (holder.requestActivation == requestActivation) {
                    reconcile = true;
                    holder.viewReconciliationGeneration++;
                    boolean wasActive = holder.active;
                    holder.active = false;
                    holder.requestStarting = false;
                    refreshSessions = wasActive;
                    if (requestActivation.get() && Objects.equals(holder.scope, submission.scope())) {
                        holder.scope = previousScope;
                        holder.errors.add("Request could not start: " + failureMessage(failure));
                        publish = true;
                    }
                }
            }
            if (refreshSessions) refreshSessionList();
            if (reconcile) publishHolderView(holder, null);
            if (publish) publishCurrentView(epoch);
        }
        publishCurrentView(epoch);
        refreshSessionList();
        return submission.requestId();
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
        boolean sessionActivityChanged;
        synchronized (stateLock) {
            holder = selected;
            sessionActivityChanged = holder != null && (holder.active || holder.requestStarting);
            if (holder != null) {
                holder.viewReconciliationGeneration++;
                if (holder.requestActivation != null) holder.requestActivation.set(false);
                holder.active = false;
                holder.requestStarting = false;
            }
        }
        if (holder != null && holder.controller != null) holder.controller.stop();
        if (sessionActivityChanged) refreshSessionList();
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
                && current.world.worldOrServer().equals(currentWorld.worldOrServer())
                && (current.loading && current.creating || current.loaded && !currentView.active()
                && currentView.conversation().turns().isEmpty());
        if (reuse) {
            boolean startNewChat;
            synchronized (stateLock) {
                if (world == null || epoch != worldEpoch || !currentWorld.worldOrServer().equals(world.worldOrServer())) return;
                current.scope = currentScope;
                selected = current;
                sessionLoading = current.loading;
                newChatPending = false;
                startNewChat = current.controller != null && current.loaded && !current.loading && !current.creating;
            }
            if (startNewChat) current.controller.newChat(currentWorld, currentScope);
        } else {
            Holder created = new Holder(currentWorld, "pending-world:" + UUID.randomUUID(), currentScope);
            created.loading = true;
            created.creating = true;
            long loadGeneration;
            synchronized (stateLock) {
                if (world == null || epoch != worldEpoch || !currentWorld.worldOrServer().equals(world.worldOrServer())) return;
                holders.put(new HolderKey(created.world.worldOrServer(), created.id), created);
                selected = created;
                sessionLoading = true;
                newChatPending = false;
                loadGeneration = ++created.loadGeneration;
            }
            long holderLoadGeneration = loadGeneration;
            initializeHolder(created).whenCompleteAsync((ignored, initializationFailure) -> {
                ChatContracts.WorldIdentity creationWorld = null;
                Evidence.Scope creationScope = null;
                if (initializationFailure == null) {
                    synchronized (stateLock) {
                        if (epoch != worldEpoch || holders.get(new HolderKey(created.world.worldOrServer(), created.id)) != created
                                || created.loadGeneration != holderLoadGeneration || !created.loading || !created.creating
                                || created.deleting || world == null
                                || !world.worldOrServer().equals(created.world.worldOrServer())) return;
                        creationWorld = world;
                        creationScope = worldScope;
                        created.scope = creationScope;
                    }
                }
                CompletionStage<ChatContracts.Conversation> creation;
                if (initializationFailure == null) {
                    try {
                        creation = created.controller.newChatWithResult(creationWorld, creationScope, true);
                    } catch (RuntimeException failure) {
                        creation = CompletableFuture.failedFuture(failure);
                    }
                } else {
                    creation = CompletableFuture.failedFuture(initializationFailure);
                }
                creation.whenCompleteAsync((conversation, creationFailure) -> {
                    if (creationFailure == null) return;
                    boolean publish;
                    long currentEpoch;
                    synchronized (stateLock) {
                        if (epoch != worldEpoch || holders.get(new HolderKey(created.world.worldOrServer(), created.id)) != created
                                || created.loadGeneration != holderLoadGeneration || !created.loading || !created.creating
                                || created.deleting) return;
                        created.loading = false;
                        created.creating = false;
                        if (selected == created) {
                            sessionLoading = false;
                            runtimeErrors.add("New chat could not be created: " + failureMessage(creationFailure));
                        } else {
                            created.errors.add("New chat could not be created: " + failureMessage(creationFailure));
                        }
                        publish = selected == created;
                        currentEpoch = worldEpoch;
                    }
                    if (publish) publishCurrentView(currentEpoch);
                }, clientExecutor);
            }, clientExecutor);
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
        navigateWithResult(target);
    }

    public CompletionStage<Evidence.NavigationResult> navigateWithResult(Evidence.NavigationTarget target) {
        if (target == null) return CompletableFuture.completedFuture(new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "No target"));
        long navigationId;
        Holder holder;
        long epoch;
        long selectionGeneration;
        ChatContracts.WorldIdentity currentWorld;
        Evidence.Scope currentScope;
        synchronized (stateLock) {
            navigationId = ++navigationGeneration;
            holder = selected;
            epoch = worldEpoch;
            selectionGeneration = sessionSelectionGeneration;
            currentWorld = world;
            currentScope = holder == null ? worldScope : holder.scope;
        }
        showNavigationError(navigationId, null);
        if (holder == null || holder.controller == null || holder.knowledge == null
                || currentWorld == null || !currentWorld.worldOrServer().equals(holder.world.worldOrServer())) {
            showNavigationError(navigationId, "Join this session's world before opening its sources");
            return CompletableFuture.completedFuture(new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "Join this session's world before opening its sources"));
        }
        ChatContracts.View current = wrap(holder.controller.view(), holder);
        if (current.navigationTargets().stream().noneMatch(value -> value.equals(target))
                || current.sources().stream().noneMatch(source -> source.scope().equals(target.scope())
                && source.providerId().equals(target.providerId()) && source.sourceHandle().equals(target.sourceHandle()))) {
            showNavigationError(navigationId, "Navigation target is stale");
            return CompletableFuture.completedFuture(new Evidence.NavigationResult(Evidence.NavigationStatus.STALE, "Navigation target is stale"));
        }
        if ("web".equals(target.providerId())) {
            if (!target.sourceHandle().equals(target.targetId()) || externalUrl(target.targetId()) == null) {
                showNavigationError(navigationId, "Navigation target is invalid");
                return CompletableFuture.completedFuture(new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "Navigation target is invalid"));
            }
            try {
                externalNavigation.accept(target.targetId());
            } catch (RuntimeException failure) {
                showNavigationError(navigationId, "Navigation failed: " + failureMessage(failure));
                return CompletableFuture.completedFuture(new Evidence.NavigationResult(Evidence.NavigationStatus.ERROR, failureMessage(failure)));
            }
            return CompletableFuture.completedFuture(new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED, null));
        }
        KnowledgeService currentKnowledge = holder.knowledge;
        var completion = new CompletableFuture<Evidence.NavigationResult>();
        currentKnowledge.refreshNavigation(target, currentScope).whenCompleteAsync((refreshed, failure) -> {
            synchronized (stateLock) {
                if (navigationId != navigationGeneration || epoch != worldEpoch
                        || selectionGeneration != sessionSelectionGeneration || selected != holder
                        || currentKnowledge != holder.knowledge || !currentScope.equals(holder.scope) || world == null
                        || !world.worldOrServer().equals(holder.world.worldOrServer())) {
                    completion.complete(new Evidence.NavigationResult(Evidence.NavigationStatus.STALE, "Navigation session changed"));
                    return;
                }
            }
            if (failure != null) {
                showNavigationError(navigationId, "Navigation failed: " + failureMessage(failure));
                completion.complete(new Evidence.NavigationResult(Evidence.NavigationStatus.ERROR, failureMessage(failure)));
            } else if (refreshed == null) {
                showNavigationError(navigationId, "Navigation target is no longer available");
                completion.complete(new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "Navigation target is no longer available"));
            } else {
                try {
                    var result = currentKnowledge.open(refreshed, currentScope);
                    if (result.status() == Evidence.NavigationStatus.OPENED) {
                        if (result.detail() != null) showNavigationMessage(navigationId, result.detail(), true);
                    } else showNavigationError(navigationId,
                            "Navigation " + result.status().name().toLowerCase() + ": "
                                    + (result.detail() == null ? "target unavailable" : result.detail()));
                    completion.complete(result);
                } catch (RuntimeException navigationFailure) {
                    showNavigationError(navigationId, "Navigation failed: " + failureMessage(navigationFailure));
                    completion.complete(new Evidence.NavigationResult(Evidence.NavigationStatus.ERROR, failureMessage(navigationFailure)));
                }
            }
        }, clientExecutor);
        return completion;
    }

    private void showNavigationError(long navigationId, String error) {
        showNavigationMessage(navigationId, error, false);
    }

    /** A notice is information from a successful open, such as where a destination that cannot be drawn is. */
    private void showNavigationMessage(long navigationId, String message, boolean notice) {
        AssistantScreen screen;
        synchronized (stateLock) {
            if (navigationId != navigationGeneration) return;
            screen = currentScreen;
        }
        if (screen == null) return;
        if (notice) screen.showNavigationNotice(message);
        else screen.showNavigationError(message);
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
                request.submission().replacing() == null ? null : request.submission().replacing().value(),
                new Toolbox.Origin(holder.id, request.submission().requestId().value(), request.submission().question())), currentKnowledge.metadata(), currentKnowledge.outputPages());
        return currentTools.execute(request.call(), request.submission().scope()).thenApply(response -> {
            List<Evidence.Source> sources = response.records().stream().map(Evidence.Record::source).distinct().toList();
            List<Evidence.NavigationTarget> targets = response.records().stream().flatMap(record -> record.targets().stream())
                    .distinct().toList();
            return new ChatContracts.ToolResponse(response.result(), new ChatContracts.EvidenceSelection(sources, targets));
        });
    }

    public void setCommandRunner(dev.packsage.world.CommandRunner commandRunner) { this.commandRunner = commandRunner; }

    @Override
    public List<dev.packsage.world.CommandRunner.Confirmation> commandConfirmations() {
        String requestId;
        synchronized (stateLock) {
            requestId = selected == null || selected.controller == null ? null : selected.controller.view().activeRequestId();
        }
        return commandRunner == null || requestId == null ? List.of() : commandRunner.pending(requestId);
    }

    @Override
    public void confirmCommand(String id, boolean run) {
        if (commandRunner != null && commandConfirmations().stream().anyMatch(value -> value.id().equals(id))) commandRunner.decide(id, run);
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

    private void publishHolderView(Holder holder, ChatContracts.View ignored) {
        long ticket;
        ChatContracts.RequestId expectedUnread;
        RequestController controller;
        synchronized (stateLock) {
            if (holder.deleting || holder.controller == null
                    || holders.get(new HolderKey(holder.world.worldOrServer(), holder.id)) != holder) return;
            ticket = ++holder.viewReconciliationGeneration;
            expectedUnread = holder.unreadRequestId;
            controller = holder.controller;
        }
        ChatContracts.View latest = controller.view();
        reconcileHolderView(holder, latest, ticket, expectedUnread);
    }

    private void reconcileHolderView(Holder holder, ChatContracts.View latest, long ticket,
                                     ChatContracts.RequestId expectedUnread) {
        long epoch;
        long publication;
        boolean isSelected;
        boolean sessionListChanged;
        synchronized (stateLock) {
            if (holder.deleting || holder.viewReconciliationGeneration != ticket
                    || holders.get(new HolderKey(holder.world.worldOrServer(), holder.id)) != holder) return;
            boolean wasActive = holder.active;
            boolean wasLoaded = holder.loaded;
            boolean wasLoading = holder.loading;
            boolean wasCreating = holder.creating;
            String oldId = holder.id;
            ChatContracts.RequestId oldUnreadRequestId = holder.unreadRequestId;
            holder.active = latest.active() || holder.requestStarting;
            if (holder.id.startsWith("pending-world:") && !latest.conversation().id().startsWith("pending")) {
                holders.remove(new HolderKey(holder.world.worldOrServer(), holder.id));
                holder.id = latest.conversation().id();
                holder.world = latest.conversation().world();
                holders.put(new HolderKey(holder.world.worldOrServer(), holder.id), holder);
                holder.loaded = true;
                holder.loading = false;
                holder.creating = false;
                if (selected == holder) sessionLoading = false;
            }
            if (!latest.conversation().id().startsWith("pending")) {
                holder.loaded = true;
                holder.creating = false;
            }
            ChatContracts.Conversation conversation = latest.conversation();
            boolean sameConversation = holder.id.equals(conversation.id())
                    && holder.world.worldOrServer().equals(conversation.world().worldOrServer());
            if (expectedUnread != null && Objects.equals(holder.unreadRequestId, expectedUnread) && sameConversation
                    && conversation.turns().stream().noneMatch(turn -> turn.requestId().equals(expectedUnread))
                    && !(latest.active() && expectedUnread.value().equals(latest.activeRequestId()))) {
                holder.unreadRequestId = null;
            }
            sessionListChanged = wasActive != holder.active || wasLoaded != holder.loaded
                    || wasLoading != holder.loading || wasCreating != holder.creating
                    || !oldId.equals(holder.id) || !Objects.equals(oldUnreadRequestId, holder.unreadRequestId);
            isSelected = selected == holder;
            epoch = isSelected ? worldEpoch : 0;
            publication = isSelected ? publicationEpoch : 0;
        }
        if (sessionListChanged) refreshSessionList();
        if (!isSelected) return;
        clientExecutor.execute(() -> {
            AssistantScreen screen;
            synchronized (stateLock) {
                if (epoch != worldEpoch || publication != publicationEpoch || selected != holder || currentScreen == null) return;
                screen = currentScreen;
            }
            ChatContracts.View current = view();
            synchronized (stateLock) {
                if (epoch != worldEpoch || publication != publicationEpoch || selected != holder || currentScreen != screen) return;
            }
            screen.refresh(current);
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
                boolean publish;
                long epoch;
                synchronized (stateLock) {
                    if (holder.deleting || holders.get(new HolderKey(holder.world.worldOrServer(), holder.id)) != holder) {
                        holder.requestAuthEpochs.remove(turn.requestId());
                        return;
                    }
                    holder.errors.add("Completion state could not be saved: " + failureMessage(failure));
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
                ChatContracts.SessionSummary summary = summaryFailure == null ? summaries.stream()
                        .filter(value -> value.world().worldOrServer().equals(holder.world.worldOrServer())
                                && value.id().equals(holder.id)).findFirst().orElse(null) : null;
                synchronized (stateLock) {
                    if (holder.deleting || holders.get(new HolderKey(holder.world.worldOrServer(), holder.id)) != holder) {
                        holder.requestAuthEpochs.remove(turn.requestId());
                        return;
                    }
                    if (summaryFailure != null) {
                        holder.errors.add("Session list could not be refreshed: " + failureMessage(summaryFailure));
                        holder.requestAuthEpochs.remove(turn.requestId());
                    } else {
                        Long requestAuthEpoch = holder.requestAuthEpochs.remove(turn.requestId());
                        if (summary != null && turn.requestId().equals(summary.unreadRequestId())) {
                            holder.unreadRequestId = turn.requestId();
                            notify = requestAuthEpoch != null && requestAuthEpoch == authEpoch
                                    && signIn == ChatContracts.SignInState.SIGNED_IN && world != null
                                    && world.worldOrServer().equals(holder.world.worldOrServer())
                                    && holder.notifiedCompletions.add(turn.requestId());
                        }
                    }
                    publish = selected == holder;
                    epoch = worldEpoch;
                }
                refreshSessionList();
                if (publish) publishCurrentView(epoch);
                if (notify) {
                    synchronized (stateLock) {
                        notify = !holder.deleting
                                && holders.get(new HolderKey(holder.world.worldOrServer(), holder.id)) == holder;
                    }
                    if (notify) completionNotice.accept(turn);
                }
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
        clientExecutor.execute(() -> {
            synchronized (stateLock) {
                if (generation != sessionListGeneration || screen != currentScreen) return;
            }
            screen.loadSessions();
        });
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
            summaries.put(new HolderKey(holder.world.worldOrServer(), holder.id), new ChatContracts.SessionSummary(
                    holder.world, holder.id,
                    last != null ? conversation.turns().getFirst().question() : view.active()
                            ? view.activeQuestion() : base == null ? "New chat" : base.title(),
                    view.active() ? view.streamingAnswer() : last == null
                            ? base == null ? "" : base.lastMessage() : last.answer(),
                    view.active() && view.activeSentAt() != null ? view.activeSentAt()
                            : last != null && last.sentAt() != null ? last.sentAt()
                            : base == null ? Instant.EPOCH : base.updatedAt(),
                    view.active(), base == null ? null : base.unreadRequestId()));
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
            for (String error : runtimeErrors) if (!errors.contains(error)) errors.add(error);
            if (holder != null) for (String error : holder.errors) if (!errors.contains(error)) errors.add(error);
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
        Set<Evidence.Source> sources = new LinkedHashSet<>();
        Set<Evidence.NavigationTarget> targets = new LinkedHashSet<>();
        if (!loading && !readOnly) {
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
                sources.addAll(turn.sources());
                targets.addAll(turn.navigationTargets());
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
                    new ChatContracts.Context("", null, currentScope), List.copyOf(sources), List.copyOf(targets), errors, "",
                    loading ? "Loading session..." : "", activeWorld, true);
        }
        return new ChatContracts.View(view.active(), currentSignIn, paused, conversation,
                holder == null || holder.loaded ? view.context() : new ChatContracts.Context("", null, currentScope),
                List.copyOf(sources), List.copyOf(targets), errors, view.streamingAnswer(), view.activity(), activeWorld, false, view.activeQuestion(),
                view.activeSentAt(), view.activities(), view.activeRequestId(), view.activeSteeringMessages());
    }

    private CompletionStage<Void> initializeHolder(Holder holder) {
        CompletableFuture<Void> initialization;
        boolean initialize = false;
        boolean ready;
        synchronized (stateLock) {
            ready = holder.controller != null;
            if (ready) {
                initialization = null;
            } else {
                if (holder.initialization == null || holder.initialization.isCompletedExceptionally()) {
                    holder.initialization = new CompletableFuture<>();
                    initialize = true;
                }
                initialization = holder.initialization;
            }
        }
        if (ready) return CompletableFuture.completedFuture(null);
        if (!initialize) return initialization;

        try {
            GameplayContextCapture capture = Objects.requireNonNull(captureFactory.apply(() -> holder.scope));
            KnowledgeService knowledge;
            boolean knowledgeUnavailable = false;
            String knowledgeError = null;
            try {
                knowledge = Objects.requireNonNull(knowledgeFactory.apply(() -> holder.scope));
            } catch (RuntimeException failure) {
                knowledge = new KnowledgeService(List.of(), () -> holder.scope);
                knowledgeUnavailable = true;
                knowledgeError = "Knowledge providers could not be refreshed: " + failureMessage(failure);
            }
            KnowledgeService initializedKnowledge = knowledge;
            if (commandRunner != null) knowledge.register(new Toolbox() {
                @Override public Metadata metadata() { return new Metadata("command", List.of(), false, List.of()); }
                @Override public CompletionStage<Evidence.Result> handle(PackSageTools.ReadRequest request, Context context) {
                    if (!request.tool().equals("run_command")) return Toolbox.super.handle(request, context);
                    if (context.origin() == null) return CompletableFuture.failedFuture(new Failure(Code.UNAVAILABLE, "command needs an active player request"));
                    var requestId = new ChatContracts.RequestId(context.origin().requestId());
                    return commandRunner.run(((Evidence.JsonString) request.arguments().values().get("command")).value(),
                            requestId.value(), request.scope(), () -> {
                                synchronized (stateLock) {
                                    return holder.active && signIn == ChatContracts.SignInState.SIGNED_IN
                                            && Objects.equals(holder.requestAuthEpochs.get(requestId), authEpoch)
                                            && request.scope().equals(holder.scope)
                                            && world != null && world.worldOrServer().equals(holder.world.worldOrServer())
                                            && holder.controller != null && holder.controller.view().active()
                                            && requestId.value().equals(holder.controller.view().activeRequestId());
                                }
                            });
                }
            });
            HistoryAdapter historyAdapter = new HistoryAdapter(store, holder.world, () -> holder.scope);
            if (!knowledgeUnavailable) knowledge.register(historyAdapter);
            RequestController controller = new RequestController(store, backend, controllerExecutor,
                    capture::capture, capture::cancel, ignored -> new ChatContracts.EvidenceSelection(List.of(), List.of()),
                    request -> executeTool(holder, request), historyCounter, prepared -> {
                        ModelBackend.Request request = requestBuilder.apply(prepared);
                        String developerPrompt;
                        PackSageSettings.Verbosity verbosity;
                        synchronized (stateLock) {
                            developerPrompt = settings.developerPrompt();
                            verbosity = settings.verbosity();
                        }
                        return new ModelBackend.Request(request.modelId(), verbosity.apply(developerPrompt == null
                                ? request.instructions() : developerPrompt), request.input(),
                                PackSageTools.definitions(initializedKnowledge.metadata()), request.searchMode(), request.requestId(),
                                request.outputBudget(), request.reasoningEffort());
                    }, view -> publishHolderView(holder, view), turn -> recordCompletion(holder, turn));
            synchronized (stateLock) {
                holder.capture = capture;
                holder.knowledge = knowledge;
                holder.historyAdapter = historyAdapter;
                holder.knowledgeUnavailable = knowledgeUnavailable;
                if (knowledgeError != null) holder.errors.add(knowledgeError);
                holder.controller = controller;
            }
            initialization.complete(null);
        } catch (RuntimeException failure) {
            initialization.completeExceptionally(failure);
        }
        return initialization;
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
        private volatile boolean knowledgeUnavailable;
        private AtomicBoolean requestActivation;
        private CompletableFuture<Void> initialization;
        private long loadGeneration;
        private final List<String> errors = new ArrayList<>();
        private final Map<ChatContracts.RequestId, Long> requestAuthEpochs = new java.util.LinkedHashMap<>();
        private final Map<ChatContracts.RequestId, ChatContracts.Turn> failedStarts = new java.util.LinkedHashMap<>();
        private final Set<ChatContracts.RequestId> notifiedCompletions = new java.util.LinkedHashSet<>();
        private ChatContracts.RequestId unreadRequestId;
        private ChatContracts.RequestId readAttemptRequestId;
        private long readAttemptSelectionGeneration = -1;
        private AssistantScreen readAttemptScreen;
        private long viewReconciliationGeneration;

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
