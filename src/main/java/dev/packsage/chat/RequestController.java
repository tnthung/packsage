package dev.packsage.chat;

import dev.packsage.knowledge.Evidence;
import dev.packsage.model.ModelBackend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

public final class RequestController {
    @FunctionalInterface
    public interface ContextCapture {
        CompletionStage<ChatContracts.CapturedContext> capture(ChatContracts.RequestCapture request,
                                                               boolean includeScreenshot);
    }

    @FunctionalInterface
    public interface ToolExecutor {
        CompletionStage<ChatContracts.ToolResponse> execute(ChatContracts.ToolRequest request);
    }

    private final ConversationStore store;
    private final ModelBackend backend;
    private final Executor executor;
    private final ContextCapture contextCapture;
    private final Consumer<ChatContracts.RequestId> captureCancellation;
    private final Function<ChatContracts.RequestCapture, ChatContracts.EvidenceSelection> evidenceCapture;
    private final ToolExecutor toolExecutor;
    private final Function<ChatContracts.Turn, Integer> tokenCounter;
    private final Function<ChatContracts.PreparedRequest, ModelBackend.Request> requestBuilder;
    private final Consumer<ChatContracts.View> publisher;
    private final Object stateLock = new Object();
    private final List<String> errors = new ArrayList<>();
    private ChatContracts.Conversation conversation;
    private ChatContracts.Context context;
    private List<Evidence.Source> sources = List.of();
    private List<Evidence.NavigationTarget> navigationTargets = List.of();
    private ChatContracts.RequestId visibleRequest;
    private ActiveRequest active;
    private WorldLoad worldLoad;
    private ChatContracts.WorldIdentity openedWorld;
    private Evidence.Scope openedScope;
    private boolean forceNew;
    private long generation;
    private long worldLoadGeneration;

    public RequestController(ConversationStore store, ModelBackend backend, Executor executor,
                             ContextCapture contextCapture, Consumer<ChatContracts.RequestId> captureCancellation,
                             Function<ChatContracts.RequestCapture, ChatContracts.EvidenceSelection> evidenceCapture,
                             ToolExecutor toolExecutor, Function<ChatContracts.Turn, Integer> tokenCounter,
                             Function<ChatContracts.PreparedRequest, ModelBackend.Request> requestBuilder,
                             Consumer<ChatContracts.View> publisher) {
        this.store = store;
        this.backend = backend;
        this.executor = executor;
        this.contextCapture = contextCapture;
        this.captureCancellation = captureCancellation;
        this.evidenceCapture = evidenceCapture;
        this.toolExecutor = toolExecutor;
        this.tokenCounter = tokenCounter;
        this.requestBuilder = requestBuilder;
        this.publisher = publisher;
        this.conversation = new ChatContracts.Conversation("pending", new ChatContracts.WorldIdentity("none", "No world"),
                List.of(), new ChatContracts.Outcome(ChatContracts.OutcomeStatus.ACTIVE, null));
        this.context = new ChatContracts.Context("", null,
                new Evidence.Scope("none", "none", "none", "none", 0));
    }

    public CompletionStage<ChatContracts.RequestId> begin(ChatContracts.Submission submission) {
        ActiveRequest request;
        boolean createNew;
        ChatContracts.Conversation reusableConversation;
        synchronized (stateLock) {
            if (active != null) return CompletableFuture.failedFuture(new IllegalStateException("a request is already active"));
            if (worldLoad != null) return CompletableFuture.failedFuture(new IllegalStateException("world history is still loading"));
            generation++;
            createNew = forceNew;
            forceNew = false;
            request = new ActiveRequest(submission, generation);
            active = request;
            openedWorld = submission.world();
            openedScope = submission.scope();
            visibleRequest = submission.requestId();
            errors.clear();
            context = new ChatContracts.Context("", null, submission.scope());
            sources = List.of();
            navigationTargets = List.of();
            reusableConversation = !createNew && conversation.world().equals(submission.world())
                    && !conversation.id().startsWith("pending") ? conversation : null;
            if (reusableConversation == null) {
                conversation = new ChatContracts.Conversation("pending:" + submission.requestId().value(), submission.world(), List.of(),
                        new ChatContracts.Outcome(ChatContracts.OutcomeStatus.ACTIVE, null));
            }
        }
        CompletableFuture<ChatContracts.Conversation> target;
        try {
            target = reusableConversation == null
                    ? (createNew ? store.newConversation(submission.world()) : store.openLatest(submission.world()))
                    .toCompletableFuture()
                    : CompletableFuture.completedFuture(reusableConversation);
        } catch (RuntimeException exception) {
            failStart(request, exception);
            return request.started;
        }
        request.target = target;

        ChatContracts.RequestCapture capture = new ChatContracts.RequestCapture(submission.requestId(), submission.world(),
                submission.scope());
        try {
            request.evidence = evidenceCapture.apply(capture);
            request.capture = contextCapture.capture(capture, submission.includeScreenshot()).toCompletableFuture();
        } catch (RuntimeException exception) {
            failStart(request, exception);
            return request.started;
        }
        publish();
        target.whenCompleteAsync((loaded, failure) -> targetReady(request, loaded, failure), executor);
        request.capture.whenCompleteAsync((captured, failure) -> captureReady(request, captured, failure), executor);
        CompletableFuture.allOf(target, request.capture).whenCompleteAsync((ignored, failure) -> {
            if (failure == null) prepareAndStart(request);
        }, executor);
        return request.started;
    }

    public CompletionStage<ChatContracts.Conversation> openWorld(ChatContracts.WorldIdentity world, Evidence.Scope scope) {
        return startWorldLoad(world, scope, false, "world changed");
    }

    public void showConversation(ChatContracts.Conversation loaded, Evidence.Scope scope) {
        cancelWorldLoad("conversation view changed");
        cancelActive("conversation view changed");
        synchronized (stateLock) {
            generation++;
            forceNew = false;
            conversation = loaded;
            openedWorld = loaded.world();
            openedScope = scope;
            visibleRequest = null;
            context = new ChatContracts.Context("", null, scope);
            sources = List.of();
            navigationTargets = List.of();
            errors.clear();
        }
        publish();
    }

    public void stop() {
        cancelActive("stopped");
    }

    /**
     * Starts a new chat for the caller's current world and evidence scope, so the published views pass the caller's
     * scope filter even after an invalidation. An already empty loaded conversation, or a New chat that is still
     * loading, is reused and republished instead of creating another empty conversation file.
     */
    public void newChat(ChatContracts.WorldIdentity currentWorld, Evidence.Scope currentScope) {
        if (currentWorld == null || currentScope == null) {
            newChat();
            return;
        }
        boolean reused = false;
        synchronized (stateLock) {
            if (active == null && worldLoad != null && worldLoad.createNew && worldLoad.world.equals(currentWorld)) {
                worldLoad.scope = currentScope;
                context = new ChatContracts.Context("", null, currentScope);
                reused = true;
            } else if (active == null && worldLoad == null && conversation.world().equals(currentWorld)
                    && !conversation.id().startsWith("pending") && conversation.turns().isEmpty()) {
                forceNew = false;
                openedWorld = currentWorld;
                openedScope = currentScope;
                visibleRequest = null;
                errors.clear();
                context = new ChatContracts.Context("", null, currentScope);
                sources = List.of();
                navigationTargets = List.of();
                reused = true;
            }
        }
        if (reused) publish();
        else startWorldLoad(currentWorld, currentScope, true, "new chat");
    }

    public void newChat() {
        ChatContracts.WorldIdentity world;
        Evidence.Scope scope;
        synchronized (stateLock) {
            if (active != null) {
                world = active.submission.world();
                scope = active.submission.scope();
            } else if (worldLoad != null) {
                world = worldLoad.world;
                scope = worldLoad.scope;
            } else {
                world = openedWorld;
                scope = openedScope;
            }
            if (world == null || scope == null) {
                forceNew = true;
            }
        }
        if (world == null || scope == null) cancelActive("new chat");
        else startWorldLoad(world, scope, true, "new chat");
    }

    public void disconnect() {
        cancelWorldLoad("world load was cancelled by disconnect");
        cancelActive("disconnected");
    }

    public void worldChanged() {
        cancelWorldLoad("world load was cancelled by world change");
        cancelActive("world changed");
    }

    public void invalidate(String scope) {
        cancelWorldLoad("world load was invalidated: " + scope);
        cancelActive("invalidated: " + scope);
    }

    public ChatContracts.View view() {
        synchronized (stateLock) {
            return currentView();
        }
    }

    private CompletionStage<ChatContracts.Conversation> startWorldLoad(ChatContracts.WorldIdentity world,
                                                                        Evidence.Scope scope, boolean createNew,
                                                                        String cancellationDetail) {
        WorldLoad load;
        WorldLoad superseded;
        boolean hadActive;
        synchronized (stateLock) {
            worldLoadGeneration++;
            load = new WorldLoad(world, scope, createNew, worldLoadGeneration);
            superseded = worldLoad;
            worldLoad = load;
            hadActive = active != null;
            forceNew = false;
            openedWorld = null;
            openedScope = null;
            visibleRequest = null;
            errors.clear();
            conversation = new ChatContracts.Conversation("pending-world:" + worldLoadGeneration, world, List.of(),
                    new ChatContracts.Outcome(ChatContracts.OutcomeStatus.ACTIVE, null));
            context = new ChatContracts.Context("", null, scope);
            sources = List.of();
            navigationTargets = List.of();
        }
        if (superseded != null) {
            superseded.result.completeExceptionally(new IllegalStateException("world load was superseded"));
        }
        if (hadActive) cancelActive(cancellationDetail);
        else publish();

        CompletionStage<ChatContracts.Conversation> operation;
        try {
            operation = createNew ? store.newConversation(world) : store.openLatest(world);
        } catch (RuntimeException exception) {
            worldLoadReady(load, null, exception);
            return load.result;
        }
        operation.whenCompleteAsync((loaded, failure) -> worldLoadReady(load, loaded, failure), executor);
        return load.result;
    }

    private void worldLoadReady(WorldLoad load, ChatContracts.Conversation loaded, Throwable failure) {
        ChatContracts.View snapshot = null;
        boolean current;
        synchronized (stateLock) {
            current = worldLoad == load && worldLoadGeneration == load.generation;
            if (current) {
                worldLoad = null;
                if (failure == null && loaded.world().equals(load.world)) {
                    openedWorld = load.world;
                    openedScope = load.scope;
                    conversation = loaded;
                    context = new ChatContracts.Context("", null, load.scope);
                } else {
                    errors.add(failure == null ? "loaded conversation belongs to another world" : failureMessage(failure));
                }
                snapshot = currentView();
            }
        }
        if (!current) {
            load.result.completeExceptionally(new IllegalStateException("world load was superseded"));
            return;
        }
        if (snapshot != null) publisher.accept(snapshot);
        if (failure == null && loaded.world().equals(load.world)) load.result.complete(loaded);
        else load.result.completeExceptionally(failure == null
                ? new IllegalStateException("loaded conversation belongs to another world") : failure);
    }

    private void cancelWorldLoad(String detail) {
        WorldLoad load;
        synchronized (stateLock) {
            load = worldLoad;
            if (load == null) return;
            worldLoad = null;
            worldLoadGeneration++;
        }
        load.result.completeExceptionally(new IllegalStateException(detail));
    }

    private void targetReady(ActiveRequest request, ChatContracts.Conversation loaded, Throwable failure) {
        if (failure != null) {
            failStart(request, failure);
            return;
        }
        ChatContracts.View snapshot = null;
        synchronized (stateLock) {
            if (isCurrent(request) && loaded.world().equals(request.submission.world())) {
                conversation = loaded;
                snapshot = currentView();
            }
        }
        if (snapshot != null) publisher.accept(snapshot);
    }

    private void captureReady(ActiveRequest request, ChatContracts.CapturedContext captured, Throwable failure) {
        if (failure != null) {
            failStart(request, failure);
            return;
        }
        ChatContracts.View snapshot = null;
        synchronized (stateLock) {
            if (isCurrent(request)) {
                request.captured = captured;
                request.activity = ActivityLabels.WAITING;
                context = captured.context();
                sources = request.evidence.sources();
                navigationTargets = request.evidence.navigationTargets();
                snapshot = currentView();
            }
        }
        if (snapshot != null) publisher.accept(snapshot);
    }

    private void prepareAndStart(ActiveRequest request) {
        ChatContracts.Conversation target;
        ChatContracts.CapturedContext captured;
        synchronized (stateLock) {
            if (!isCurrent(request)) {
                request.started.completeExceptionally(new IllegalStateException("request was invalidated"));
                return;
            }
            target = request.target.join();
            captured = request.capture.join();
        }

        ChatContracts.HistorySelection history;
        ModelBackend.Request modelRequest;
        try {
            history = HistorySelector.select(target.turns(), request.submission.historyLimits(), tokenCounter::apply);
            modelRequest = requestBuilder.apply(new ChatContracts.PreparedRequest(request.submission, history, captured,
                    request.evidence));
            if (!request.submission.selectedModel().id().equals(modelRequest.modelId())
                    || request.submission.searchMode() != modelRequest.searchMode()
                    || !request.submission.requestId().value().equals(modelRequest.requestId())
                    || request.submission.outputBudget() != modelRequest.outputBudget()) {
                throw new IllegalArgumentException("request builder changed immutable submission settings");
            }
        } catch (RuntimeException exception) {
            failStart(request, exception);
            return;
        }
        synchronized (stateLock) {
            if (!isCurrent(request)) {
                request.started.completeExceptionally(new IllegalStateException("request was invalidated"));
                return;
            }
            errors.addAll(history.errors());
        }

        ModelBackend.Exchange startedExchange;
        try {
            startedExchange = backend.start(modelRequest, event -> onEvent(request, event));
        } catch (RuntimeException exception) {
            failStart(request, exception);
            return;
        }
        List<ModelBackend.ToolResult> pending = new ArrayList<>();
        boolean install;
        synchronized (stateLock) {
            install = isCurrent(request);
            if (install) {
                request.exchange = startedExchange;
                request.toolResults.forEach((callId, result) -> {
                    if (request.submittedResults.add(callId)) pending.add(result);
                });
            }
        }
        if (!install) startedExchange.cancel();
        else pending.forEach(startedExchange::submit);
        request.started.complete(request.submission.requestId());
    }

    private void onEvent(ActiveRequest request, ModelBackend.Event event) {
        if (event.kind() == ModelBackend.EventKind.CLIENT_TOOL_CALL && event.toolCall() != null) {
            startTool(request, event.toolCall());
            return;
        }
        if (event.kind() == ModelBackend.EventKind.COMPLETED || event.kind() == ModelBackend.EventKind.FAILED) {
            finishModel(request, event);
            return;
        }
        ChatContracts.View snapshot = null;
        synchronized (stateLock) {
            if (!isCurrent(request)) return;
            if (event.kind() == ModelBackend.EventKind.TEXT_DELTA && event.text() != null) {
                request.answer.append(event.text());
                request.activity = ActivityLabels.WRITING;
            } else if (event.kind() == ModelBackend.EventKind.RETRYING) {
                request.answer.setLength(0);
                request.activity = ActivityLabels.RETRYING_WITHOUT_WEB_SEARCH;
            } else if (event.kind() == ModelBackend.EventKind.REASONING) {
                if (request.activity.equals(ActivityLabels.THINKING)) return;
                request.activity = ActivityLabels.THINKING;
            } else if (event.kind() == ModelBackend.EventKind.HOSTED_SEARCH
                    && event.blocks().isEmpty()) {
                if (request.activity.equals(ActivityLabels.SEARCHING_WEB)) return;
                request.activity = ActivityLabels.SEARCHING_WEB;
            }
            snapshot = currentView();
        }
        publisher.accept(snapshot);
    }

    private void startTool(ActiveRequest request, ModelBackend.ToolCall call) {
        ChatContracts.ToolRequest toolRequest;
        ChatContracts.View started;
        synchronized (stateLock) {
            if (!isCurrent(request) || !request.toolCalls.add(call.callId())) return;
            request.partialReplay.add(call);
            toolRequest = new ChatContracts.ToolRequest(request.submission, call);
            request.activity = ActivityLabels.tool(call, request.toolCalls.size());
            started = currentView();
        }
        publisher.accept(started);
        CompletionStage<ChatContracts.ToolResponse> execution;
        try {
            execution = toolExecutor.execute(toolRequest);
        } catch (RuntimeException exception) {
            toolFailed(request, call, exception);
            return;
        }
        execution.whenCompleteAsync((response, failure) -> {
            if (failure != null) toolFailed(request, call, failure);
            else toolReady(request, call, response);
        }, executor);
    }

    private void toolReady(ActiveRequest request, ModelBackend.ToolCall call, ChatContracts.ToolResponse response) {
        ModelBackend.Exchange targetExchange = null;
        ChatContracts.View snapshot;
        synchronized (stateLock) {
            if (!isCurrent(request) || !call.callId().equals(response.result().callId())
                    || request.toolResults.containsKey(call.callId())
                    || response.evidence().sources().stream().anyMatch(source -> !request.submission.scope().equals(source.scope()))
                    || response.evidence().navigationTargets().stream()
                    .anyMatch(target -> !request.submission.scope().equals(target.scope()))) return;
            request.toolResults.put(call.callId(), response.result());
            request.partialReplay.add(response.result());
            List<Evidence.Source> mergedSources = new ArrayList<>(request.evidence.sources());
            response.evidence().sources().forEach(source -> {
                if (!mergedSources.contains(source)) mergedSources.add(source);
            });
            List<Evidence.NavigationTarget> mergedTargets = new ArrayList<>(request.evidence.navigationTargets());
            response.evidence().navigationTargets().forEach(target -> {
                if (!mergedTargets.contains(target)) mergedTargets.add(target);
            });
            request.evidence = new ChatContracts.EvidenceSelection(mergedSources, mergedTargets);
            request.activity = ActivityLabels.WAITING;
            sources = request.evidence.sources();
            navigationTargets = request.evidence.navigationTargets();
            if (request.exchange != null && request.submittedResults.add(call.callId())) targetExchange = request.exchange;
            snapshot = currentView();
        }
        if (targetExchange != null) targetExchange.submit(response.result());
        publisher.accept(snapshot);
    }

    private void toolFailed(ActiveRequest request, ModelBackend.ToolCall call, Throwable failure) {
        toolReady(request, call, new ChatContracts.ToolResponse(new ModelBackend.ToolResult(call.callId(),
                "tool failed: " + failureMessage(failure), true), new ChatContracts.EvidenceSelection(List.of(), List.of())));
    }

    private void finishModel(ActiveRequest request, ModelBackend.Event event) {
        ChatContracts.Conversation target;
        ChatContracts.CapturedContext captured;
        List<ModelBackend.InputItem> replay;
        ChatContracts.OutcomeStatus status = event.kind() == ModelBackend.EventKind.COMPLETED
                ? ChatContracts.OutcomeStatus.COMPLETED : ChatContracts.OutcomeStatus.FAILED;
        String detail = event.kind() == ModelBackend.EventKind.FAILED
                ? event.detail() == null ? "model failed" : event.detail() : null;
        synchronized (stateLock) {
            if (!isCurrent(request)) return;
            if (status == ChatContracts.OutcomeStatus.COMPLETED) {
                try {
                    replay = validateReplay(event.replayItems(), request.toolResults);
                } catch (RuntimeException exception) {
                    replay = completePairs(request);
                    status = ChatContracts.OutcomeStatus.FAILED;
                    detail = exception.getMessage();
                }
            } else {
                replay = completePairs(request);
            }
            active = null;
            target = request.target.join();
            captured = request.captured;
        }
        ChatContracts.Turn untokened = new ChatContracts.Turn(request.submission.requestId(), request.submission.question(),
                request.answer.toString(), replay, captured.context(), request.evidence.sources(),
                request.evidence.navigationTargets(),
                new ChatContracts.Outcome(status, detail), 0);
        ChatContracts.Turn turn;
        try {
            turn = new ChatContracts.Turn(untokened.requestId(), untokened.question(), untokened.answer(), untokened.replayItems(),
                    untokened.context(), untokened.sources(), untokened.navigationTargets(), untokened.outcome(), tokenCounter.apply(untokened));
        } catch (RuntimeException exception) {
            turn = new ChatContracts.Turn(untokened.requestId(), untokened.question(), untokened.answer(), untokened.replayItems(),
                    untokened.context(), untokened.sources(),
                    untokened.navigationTargets(),
                    new ChatContracts.Outcome(ChatContracts.OutcomeStatus.FAILED, failureMessage(exception)), 0);
        }
        updateVisibleTurn(request, target, turn);
        persist(request, target, turn, captured.screenshotBytes());
    }

    private void cancelActive(String detail) {
        ActiveRequest request;
        ModelBackend.Exchange exchange;
        ChatContracts.CapturedContext captured;
        synchronized (stateLock) {
            request = active;
            if (request == null) return;
            active = null;
            generation++;
            exchange = request.exchange;
            captured = request.captured == null
                    ? new ChatContracts.CapturedContext(new ChatContracts.Context("", null, request.submission.scope()), new byte[0])
                    : request.captured;
            if (visibleRequest != null && visibleRequest.equals(request.submission.requestId())) {
                context = captured.context();
            }
        }
        captureCancellation.accept(request.submission.requestId());
        if (exchange != null) exchange.cancel();
        request.started.completeExceptionally(new IllegalStateException("request was cancelled"));
        publish();
        ChatContracts.CapturedContext persisted = captured;
        request.target.whenCompleteAsync((target, failure) -> {
            if (failure != null) {
                publishPersistenceFailure(request, null, failure);
                return;
            }
            ChatContracts.Turn turn = new ChatContracts.Turn(request.submission.requestId(), request.submission.question(),
                    request.answer.toString(), List.copyOf(request.partialReplay), persisted.context(), request.evidence.sources(),
                    request.evidence.navigationTargets(),
                    new ChatContracts.Outcome(ChatContracts.OutcomeStatus.CANCELLED, detail), 0);
            updateVisibleTurn(request, target, turn);
            persist(request, target, turn, persisted.screenshotBytes());
        }, executor);
    }

    private void failStart(ActiveRequest request, Throwable failure) {
        boolean current;
        synchronized (stateLock) {
            current = isCurrent(request);
            if (current) {
                active = null;
                errors.add(failureMessage(failure));
            }
        }
        if (!current) return;
        captureCancellation.accept(request.submission.requestId());
        request.started.completeExceptionally(failure);
        publish();
    }

    private void updateVisibleTurn(ActiveRequest request, ChatContracts.Conversation target, ChatContracts.Turn turn) {
        ChatContracts.View snapshot = null;
        synchronized (stateLock) {
            if (isVisible(request, target)) {
                List<ChatContracts.Turn> turns = new ArrayList<>(target.turns());
                turns.removeIf(existing -> existing.requestId().equals(turn.requestId()));
                turns.add(turn);
                conversation = new ChatContracts.Conversation(target.id(), target.world(), turns, turn.outcome());
                context = turn.context();
                sources = turn.sources();
                snapshot = currentView();
            }
        }
        if (snapshot != null) publisher.accept(snapshot);
    }

    private void persist(ActiveRequest request, ChatContracts.Conversation target, ChatContracts.Turn turn, byte[] screenshotBytes) {
        CompletionStage<Void> media = turn.context().screenshot() == null
                ? CompletableFuture.completedFuture(null)
                : store.appendMedia(target.world(), target.id(), turn.context().screenshot(), screenshotBytes);
        media.whenCompleteAsync((ignored, mediaFailure) -> {
            ChatContracts.Turn persistedTurn = turn;
            if (mediaFailure != null) {
                persistedTurn = new ChatContracts.Turn(turn.requestId(), turn.question(), turn.answer(), turn.replayItems(),
                        new ChatContracts.Context(turn.context().serializedGameplayContext(), null, turn.context().scope()),
                        turn.sources(), turn.navigationTargets(), new ChatContracts.Outcome(ChatContracts.OutcomeStatus.FAILED,
                        "media persistence failed: " + failureMessage(mediaFailure)), turn.tokenCount());
                updateVisibleTurn(request, target, persistedTurn);
                publishPersistenceFailure(request, target, mediaFailure);
            }
            ChatContracts.Turn finalTurn = persistedTurn;
            store.appendTurn(target.world(), request.submission.scope(), target.id(), finalTurn)
                    .whenCompleteAsync((saved, appendFailure) -> {
                        if (appendFailure != null) publishPersistenceFailure(request, target, appendFailure);
                    }, executor);
        }, executor);
    }

    private void publishPersistenceFailure(ActiveRequest request, ChatContracts.Conversation target, Throwable failure) {
        ChatContracts.View snapshot = null;
        synchronized (stateLock) {
            if (target == null ? visibleRequest != null && visibleRequest.equals(request.submission.requestId())
                    : isVisible(request, target)) {
                errors.add(failureMessage(failure));
                snapshot = currentView();
            }
        }
        if (snapshot != null) publisher.accept(snapshot);
    }

    private boolean isCurrent(ActiveRequest request) {
        return active == request && generation == request.generation;
    }

    private boolean isVisible(ActiveRequest request, ChatContracts.Conversation target) {
        return visibleRequest != null && visibleRequest.equals(request.submission.requestId())
                && conversation.world().equals(target.world())
                && (conversation.id().equals(target.id()) || conversation.id().equals("pending:" + request.submission.requestId().value()));
    }

    private void publish() {
        ChatContracts.View snapshot;
        synchronized (stateLock) {
            snapshot = currentView();
        }
        publisher.accept(snapshot);
    }

    private ChatContracts.View currentView() {
        return new ChatContracts.View(active != null, ChatContracts.SignInState.SIGNED_OUT, false, conversation, context,
                sources, navigationTargets, List.copyOf(errors), active == null ? "" : active.answer.toString(),
                active == null ? "" : active.activity, conversation.world(), false,
                active == null ? "" : active.submission.question());
    }

    /** The request's tool calls that received a result, each followed by its results, in their original order. */
    private static List<ModelBackend.InputItem> completePairs(ActiveRequest request) {
        List<ModelBackend.InputItem> pairs = new ArrayList<>();
        for (ModelBackend.InputItem item : request.partialReplay) {
            if (!(item instanceof ModelBackend.ToolCall call) || request.toolResults.containsKey(call.callId())) pairs.add(item);
        }
        return List.copyOf(pairs);
    }

    private static List<ModelBackend.InputItem> validateReplay(List<ModelBackend.InputItem> providerReplay,
                                                                Map<String, ModelBackend.ToolResult> submittedResults) {
        Set<String> calls = new LinkedHashSet<>();
        Set<String> results = new LinkedHashSet<>();
        for (ModelBackend.InputItem item : providerReplay) {
            if (!(item instanceof ModelBackend.EncryptedReasoning
                    || item instanceof ModelBackend.ToolCall
                    || item instanceof ModelBackend.ToolResult
                    || item instanceof ModelBackend.AssistantOutput
                    || item instanceof ModelBackend.WebSearchCall)) {
                throw new IllegalArgumentException("backend replay contains a non-durable item");
            }
            if (item instanceof ModelBackend.ToolCall call) {
                if (!calls.add(call.callId())) throw new IllegalArgumentException("duplicate tool call: " + call.callId());
            }
            if (item instanceof ModelBackend.ToolResult result) {
                if (!calls.contains(result.callId()) || !results.add(result.callId())) {
                    throw new IllegalArgumentException("invalid tool result: " + result.callId());
                }
                if (!result.equals(submittedResults.get(result.callId()))) {
                    throw new IllegalArgumentException("backend replay changed submitted tool result: " + result.callId());
                }
            }
        }
        if (!calls.equals(results) || !results.equals(submittedResults.keySet())) {
            throw new IllegalArgumentException("incomplete tool replay");
        }
        return List.copyOf(providerReplay);
    }

    private static String failureMessage(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        return cause.getMessage() == null ? cause.getClass().getName() : cause.getMessage();
    }

    private static final class ActiveRequest {
        private final ChatContracts.Submission submission;
        private final long generation;
        private final CompletableFuture<ChatContracts.RequestId> started = new CompletableFuture<>();
        private final StringBuilder answer = new StringBuilder();
        private String activity = ActivityLabels.PREPARING;
        private final List<ModelBackend.InputItem> partialReplay = new ArrayList<>();
        private final Set<String> toolCalls = new LinkedHashSet<>();
        private final Map<String, ModelBackend.ToolResult> toolResults = new LinkedHashMap<>();
        private final Set<String> submittedResults = new LinkedHashSet<>();
        private CompletableFuture<ChatContracts.Conversation> target;
        private CompletableFuture<ChatContracts.CapturedContext> capture;
        private ChatContracts.CapturedContext captured;
        private ChatContracts.EvidenceSelection evidence;
        private ModelBackend.Exchange exchange;

        private ActiveRequest(ChatContracts.Submission submission, long generation) {
            this.submission = submission;
            this.generation = generation;
        }
    }

    private static final class WorldLoad {
        private final ChatContracts.WorldIdentity world;
        private Evidence.Scope scope;
        private final boolean createNew;
        private final long generation;
        private final CompletableFuture<ChatContracts.Conversation> result = new CompletableFuture<>();

        private WorldLoad(ChatContracts.WorldIdentity world, Evidence.Scope scope, boolean createNew, long generation) {
            this.world = world;
            this.scope = scope;
            this.createNew = createNew;
            this.generation = generation;
        }
    }
}
