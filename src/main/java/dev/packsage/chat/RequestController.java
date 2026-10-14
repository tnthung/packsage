package dev.packsage.chat;

import dev.packsage.PackSageLog;
import dev.packsage.knowledge.Evidence;
import dev.packsage.model.ModelBackend;

import java.util.ArrayList;
import java.time.Instant;
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
    private ActiveRequest pendingReplacement;
    private WorldLoad worldLoad;
    private ChatContracts.WorldIdentity openedWorld;
    private Evidence.Scope openedScope;
    private int pendingPersistenceCount;
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
            if (pendingReplacement != null && !pendingReplacement.persistenceSettled) {
                return CompletableFuture.failedFuture(new IllegalStateException("replacement is still being saved"));
            }
            if (submission.replacing() != null && (forceNew
                    || !conversation.world().worldOrServer().equals(submission.world().worldOrServer())
                    || conversation.turns().isEmpty()
                    || !conversation.turns().getLast().requestId().equals(submission.replacing()))) {
                return CompletableFuture.failedFuture(new IllegalStateException("latest exchange changed before resend"));
            }
            generation++;
            createNew = forceNew;
            forceNew = false;
            request = new ActiveRequest(submission, generation, Instant.now(), System.nanoTime());
            active = request;
            openedWorld = submission.world();
            openedScope = submission.scope();
            visibleRequest = submission.requestId();
            errors.clear();
            context = new ChatContracts.Context("", null, submission.scope());
            sources = List.of();
            navigationTargets = List.of();
            reusableConversation = !createNew
                    && conversation.world().worldOrServer().equals(submission.world().worldOrServer())
                    && !conversation.id().startsWith("pending") ? conversation : null;
            if (reusableConversation == null) {
                conversation = new ChatContracts.Conversation("pending:" + submission.requestId().value(), submission.world(), List.of(),
                        new ChatContracts.Outcome(ChatContracts.OutcomeStatus.ACTIVE, null));
            }
        }
        PackSageLog.LOGGER.info("PackSage timing request={} phase=accepted start_ns={} event_ns={} model={} effort={} screenshot={} search={}",
                submission.requestId().value(), request.acceptedAtNanos, request.acceptedAtNanos,
                submission.selectedModel().id(), submission.reasoningEffort(), submission.includeScreenshot(), submission.searchMode());
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
        ActiveRequest replacement;
        WorldLoad reload = null;
        ChatContracts.WorldIdentity storedWorld = null;
        synchronized (stateLock) {
            generation++;
            forceNew = false;
            replacement = pendingReplacement;
            ChatContracts.Conversation target = replacement == null ? null : replacement.target.getNow(null);
            boolean replacingLoaded = replacement != null && (target != null
                    && target.world().worldOrServer().equals(loaded.world().worldOrServer()) && target.id().equals(loaded.id())
                    || loaded.world().worldOrServer().equals(replacement.submission.world().worldOrServer())
                    && !loaded.turns().isEmpty()
                    && loaded.turns().getLast().requestId().equals(replacement.submission.replacing()));
            boolean staleReplacement = replacingLoaded && !loaded.turns().isEmpty()
                    && loaded.turns().getLast().requestId().equals(replacement.submission.replacing());
            if (replacingLoaded && (!replacement.persistenceSettled || staleReplacement)) {
                storedWorld = target == null ? loaded.world() : target.world();
                worldLoadGeneration++;
                reload = new WorldLoad(loaded.world(), scope, false, worldLoadGeneration);
                worldLoad = reload;
                openedWorld = null;
                openedScope = null;
                visibleRequest = null;
                errors.clear();
            } else {
                conversation = loaded;
                openedWorld = loaded.world();
                openedScope = scope;
                visibleRequest = null;
                context = new ChatContracts.Context("", null, scope);
                sources = List.of();
                navigationTargets = List.of();
                errors.clear();
            }
        }
        if (reload != null) {
            WorldLoad pendingLoad = reload;
            ChatContracts.WorldIdentity persistenceWorld = storedWorld;
            replacement.persistence.whenCompleteAsync((ignored, persistenceFailure) -> {
                CompletionStage<ChatContracts.Conversation> operation;
                synchronized (stateLock) {
                    pendingLoad.persistenceFailure = replacement.persistenceFailure;
                }
                try {
                    operation = store.openConversation(persistenceWorld, loaded.id());
                } catch (RuntimeException exception) {
                    worldLoadReady(pendingLoad, null, exception);
                    return;
                }
                operation.whenCompleteAsync((fresh, failure) -> worldLoadReady(pendingLoad, fresh, failure), executor);
            }, executor);
            return;
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
        newChat(currentWorld, currentScope, false);
    }

    public void newChat(ChatContracts.WorldIdentity currentWorld, Evidence.Scope currentScope, boolean forceNewConversation) {
        if (currentWorld == null || currentScope == null) {
            newChat();
            return;
        }
        boolean reused = false;
        synchronized (stateLock) {
            if (!forceNewConversation && active == null && worldLoad != null && worldLoad.createNew
                    && worldLoad.world.worldOrServer().equals(currentWorld.worldOrServer())) {
                worldLoad.scope = currentScope;
                context = new ChatContracts.Context("", null, currentScope);
                reused = true;
            } else if (!forceNewConversation && active == null && worldLoad == null
                    && conversation.world().worldOrServer().equals(currentWorld.worldOrServer())
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
        synchronized (stateLock) { visibleRequest = null; }
    }

    public void worldChanged() {
        cancelWorldLoad("world load was cancelled by world change");
        cancelActive("world changed");
        synchronized (stateLock) { visibleRequest = null; }
    }

    public void invalidate(String scope) {
        cancelWorldLoad("world load was invalidated: " + scope);
        cancelActive("invalidated: " + scope);
        synchronized (stateLock) { visibleRequest = null; }
    }

    public ChatContracts.View view() {
        synchronized (stateLock) {
            return currentView();
        }
    }

    public boolean persistencePending() {
        synchronized (stateLock) {
            return pendingPersistenceCount > 0;
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
        ActiveRequest replacement;
        synchronized (stateLock) {
            replacement = !createNew && pendingReplacement != null && !pendingReplacement.persistenceSettled
                    && pendingReplacement.submission.world().worldOrServer().equals(world.worldOrServer())
                    ? pendingReplacement : null;
        }
        CompletionStage<Void> barrier = replacement == null
                ? CompletableFuture.completedFuture(null) : replacement.persistence;
        barrier.whenCompleteAsync((ignored, persistenceFailure) -> {
            CompletionStage<ChatContracts.Conversation> operation;
            try {
                ChatContracts.Conversation target = replacement == null ? null : replacement.target.getNow(null);
                operation = createNew ? store.newConversation(world)
                        : store.openLatest(target == null ? world : target.world());
            } catch (RuntimeException exception) {
                worldLoadReady(load, null, exception);
                return;
            }
            operation.whenCompleteAsync((loaded, failure) -> worldLoadReady(load, loaded, failure), executor);
        }, executor);
        return load.result;
    }

    private void worldLoadReady(WorldLoad load, ChatContracts.Conversation loaded, Throwable failure) {
        ChatContracts.View snapshot = null;
        boolean current;
        synchronized (stateLock) {
            current = worldLoad == load && worldLoadGeneration == load.generation;
            if (current) {
                worldLoad = null;
                if (failure == null && loaded.world().worldOrServer().equals(load.world.worldOrServer())) {
                    openedWorld = load.world;
                    openedScope = load.scope;
                    conversation = loaded;
                    context = new ChatContracts.Context("", null, load.scope);
                    if (load.persistenceFailure != null) errors.add(load.persistenceFailure);
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
        if (failure == null && loaded.world().worldOrServer().equals(load.world.worldOrServer())) {
            load.result.complete(loaded);
        }
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
            long historyFailedAtNanos = System.nanoTime();
            PackSageLog.LOGGER.info("PackSage timing request={} phase=history_failed event_ns={} elapsed_ms={} failureType={}",
                    request.submission.requestId().value(), historyFailedAtNanos,
                    (historyFailedAtNanos - request.acceptedAtNanos) / 1_000_000, failure.getClass().getSimpleName());
            failStart(request, failure);
            return;
        }
        long historyReadyAtNanos = System.nanoTime();
        PackSageLog.LOGGER.info("PackSage timing request={} phase=history_ready event_ns={} elapsed_ms={} conversationTurns={}",
                request.submission.requestId().value(), historyReadyAtNanos,
                (historyReadyAtNanos - request.acceptedAtNanos) / 1_000_000, loaded.turns().size());
        ChatContracts.View snapshot = null;
        synchronized (stateLock) {
            if (isCurrent(request)
                    && loaded.world().worldOrServer().equals(request.submission.world().worldOrServer())) {
                conversation = loaded;
                snapshot = currentView();
            }
        }
        if (snapshot != null) publisher.accept(snapshot);
    }

    private void captureReady(ActiveRequest request, ChatContracts.CapturedContext captured, Throwable failure) {
        if (failure != null) {
            long captureFailedAtNanos = System.nanoTime();
            PackSageLog.LOGGER.info("PackSage timing request={} phase=capture_failed event_ns={} elapsed_ms={} failureType={}",
                    request.submission.requestId().value(), captureFailedAtNanos,
                    (captureFailedAtNanos - request.acceptedAtNanos) / 1_000_000, failure.getClass().getSimpleName());
            failStart(request, failure);
            return;
        }
        long captureReadyAtNanos = System.nanoTime();
        PackSageLog.LOGGER.info("PackSage timing request={} phase=capture_ready event_ns={} elapsed_ms={} screenshotBytes={}",
                request.submission.requestId().value(), captureReadyAtNanos,
                (captureReadyAtNanos - request.acceptedAtNanos) / 1_000_000,
                captured.context().screenshot() == null ? 0 : captured.context().screenshot().bytes());
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
        long preparationStartedAtNanos = System.nanoTime();
        long historyReadyAtNanos;
        long requestBuiltAtNanos;
        try {
            history = HistorySelector.select(target.turns(), request.submission.historyLimits(), tokenCounter::apply,
                    request.submission.replacing());
            historyReadyAtNanos = System.nanoTime();
            modelRequest = requestBuilder.apply(new ChatContracts.PreparedRequest(request.submission, history, captured,
                    request.evidence));
            requestBuiltAtNanos = System.nanoTime();
            if (!request.submission.selectedModel().id().equals(modelRequest.modelId())
                    || request.submission.searchMode() != modelRequest.searchMode()
                    || !request.submission.requestId().value().equals(modelRequest.requestId())
                    || request.submission.outputBudget() != modelRequest.outputBudget()) {
                throw new IllegalArgumentException("request builder changed immutable submission settings");
            }
        } catch (RuntimeException exception) {
            long prepareFailedAtNanos = System.nanoTime();
            PackSageLog.LOGGER.info("PackSage timing request={} phase=prepare_failed event_ns={} elapsed_ms={} failureType={}",
                    request.submission.requestId().value(), prepareFailedAtNanos,
                    (prepareFailedAtNanos - request.acceptedAtNanos) / 1_000_000, exception.getClass().getSimpleName());
            failStart(request, exception);
            return;
        }
        PackSageLog.LOGGER.info("PackSage timing request={} phase=history_selected event_ns={} preparationMs={} historyTurns={} historyTokens={} historyErrors={}",
                request.submission.requestId().value(), historyReadyAtNanos,
                (historyReadyAtNanos - preparationStartedAtNanos) / 1_000_000,
                history.turns().size(), history.tokenCount(), history.errors().size());
        PackSageLog.LOGGER.info("PackSage timing request={} phase=request_built event_ns={} buildMs={} inputItems={} tools={} model={} effort={}",
                request.submission.requestId().value(), requestBuiltAtNanos,
                (requestBuiltAtNanos - historyReadyAtNanos) / 1_000_000,
                modelRequest.input().size(), modelRequest.tools().size(), modelRequest.modelId(), modelRequest.reasoningEffort());
        synchronized (stateLock) {
            if (!isCurrent(request)) {
                request.started.completeExceptionally(new IllegalStateException("request was invalidated"));
                return;
            }
            errors.addAll(history.errors());
        }

        ModelBackend.Exchange startedExchange;
        try {
            request.backendStartedAtNanos = System.nanoTime();
            PackSageLog.LOGGER.info("PackSage timing request={} phase=backend_start event_ns={} elapsed_ms={} model={} effort={} inputItems={} tools={} search={}",
                    request.submission.requestId().value(), request.backendStartedAtNanos,
                    (request.backendStartedAtNanos - request.acceptedAtNanos) / 1_000_000,
                    modelRequest.modelId(), modelRequest.reasoningEffort(), modelRequest.input().size(),
                    modelRequest.tools().size(), modelRequest.searchMode());
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
                if (!request.firstTextLogged) {
                    request.firstTextLogged = true;
                    long firstTextAtNanos = System.nanoTime();
                    PackSageLog.LOGGER.info("PackSage timing request={} phase=first_text event_ns={} elapsed_ms={} backendMs={} deltaChars={}",
                            request.submission.requestId().value(), firstTextAtNanos,
                            (firstTextAtNanos - request.acceptedAtNanos) / 1_000_000,
                            (firstTextAtNanos - request.backendStartedAtNanos) / 1_000_000, event.text().length());
                }
                request.answer.append(event.text());
                request.activity = ActivityLabels.WRITING;
            } else if (event.kind() == ModelBackend.EventKind.RETRYING) {
                boolean providerRetry = event.detail() != null && event.detail().startsWith("temporary provider failure");
                if (!providerRetry) request.answer.setLength(0);
                request.activity = providerRetry ? "Retrying provider request…" : ActivityLabels.RETRYING_WITHOUT_WEB_SEARCH;
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
            markPersistencePending(request);
            active = null;
            if (request.submission.replacing() != null) pendingReplacement = request;
            target = request.target.join();
            captured = request.captured;
        }
        long terminalAtNanos = System.nanoTime();
        PackSageLog.LOGGER.info("PackSage timing request={} phase=model_terminal event_ns={} elapsed_ms={} backendMs={} outcome={}",
                request.submission.requestId().value(), terminalAtNanos,
                (terminalAtNanos - request.acceptedAtNanos) / 1_000_000,
                request.backendStartedAtNanos == 0 ? -1 : (terminalAtNanos - request.backendStartedAtNanos) / 1_000_000,
                status);
        ChatContracts.Turn untokened = new ChatContracts.Turn(request.submission.requestId(), request.submission.question(),
                request.answer.toString(), replay, captured.context(), request.evidence.sources(),
                request.evidence.navigationTargets(),
                new ChatContracts.Outcome(status, detail), 0, request.sentAt);
        ChatContracts.Turn turn;
        try {
            turn = new ChatContracts.Turn(untokened.requestId(), untokened.question(), untokened.answer(), untokened.replayItems(),
                    untokened.context(), untokened.sources(), untokened.navigationTargets(), untokened.outcome(), tokenCounter.apply(untokened), untokened.sentAt());
        } catch (RuntimeException exception) {
            turn = new ChatContracts.Turn(untokened.requestId(), untokened.question(), untokened.answer(), untokened.replayItems(),
                    untokened.context(), untokened.sources(),
                    untokened.navigationTargets(),
                    new ChatContracts.Outcome(ChatContracts.OutcomeStatus.FAILED, failureMessage(exception)), 0, untokened.sentAt());
        }
        if (request.submission.replacing() == null) updateVisibleTurn(request, target, turn);
        else publish();
        persist(request, target, turn, captured.screenshotBytes());
    }

    private void cancelActive(String detail) {
        ActiveRequest request;
        ModelBackend.Exchange exchange;
        ChatContracts.CapturedContext captured;
        synchronized (stateLock) {
            request = active;
            if (request == null) return;
            markPersistencePending(request);
            active = null;
            if (request.submission.replacing() != null) pendingReplacement = request;
            generation++;
            exchange = request.exchange;
            captured = request.captured == null
                    ? new ChatContracts.CapturedContext(new ChatContracts.Context("", null, request.submission.scope()), new byte[0])
                    : request.captured;
            if (visibleRequest != null && visibleRequest.equals(request.submission.requestId())) {
                context = captured.context();
            }
        }
        long cancelledAtNanos = System.nanoTime();
        PackSageLog.LOGGER.info("PackSage timing request={} phase=cancel event_ns={} elapsed_ms={} backendMs={}",
                request.submission.requestId().value(), cancelledAtNanos,
                (cancelledAtNanos - request.acceptedAtNanos) / 1_000_000,
                request.backendStartedAtNanos == 0 ? -1 : (cancelledAtNanos - request.backendStartedAtNanos) / 1_000_000);
        captureCancellation.accept(request.submission.requestId());
        if (exchange != null) exchange.cancel();
        request.started.completeExceptionally(new IllegalStateException("request was cancelled"));
        publish();
        ChatContracts.CapturedContext persisted = captured;
        request.target.whenCompleteAsync((target, failure) -> {
            if (failure != null) {
                finishPersistence(request);
                if (request.submission.replacing() == null) publishPersistenceFailure(request, null, failure);
                else finishReplacementPersistence(request, null, null, failure);
                return;
            }
            ChatContracts.Turn turn = new ChatContracts.Turn(request.submission.requestId(), request.submission.question(),
                    request.answer.toString(), List.copyOf(request.partialReplay), persisted.context(), request.evidence.sources(),
                    request.evidence.navigationTargets(),
                    new ChatContracts.Outcome(ChatContracts.OutcomeStatus.CANCELLED, detail), 0, request.sentAt);
            if (request.submission.replacing() == null) updateVisibleTurn(request, target, turn);
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
        long failedAtNanos = System.nanoTime();
        PackSageLog.LOGGER.info("PackSage timing request={} phase=start_failed event_ns={} elapsed_ms={} failureType={}",
                request.submission.requestId().value(), failedAtNanos,
                (failedAtNanos - request.acceptedAtNanos) / 1_000_000, failure.getClass().getSimpleName());
        captureCancellation.accept(request.submission.requestId());
        request.started.completeExceptionally(failure);
        publish();
    }

    private void updateVisibleTurn(ActiveRequest request, ChatContracts.Conversation target, ChatContracts.Turn turn) {
        ChatContracts.View snapshot = null;
        synchronized (stateLock) {
            if (isVisible(request, target)) {
                List<ChatContracts.Turn> turns = new ArrayList<>(target.turns());
                turns.removeIf(existing -> existing.requestId().equals(turn.requestId())
                        || request.submission.replacing() != null && existing.requestId().equals(request.submission.replacing()));
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
        request.persistenceStartedAtNanos = System.nanoTime();
        PackSageLog.LOGGER.info("PackSage timing request={} phase=persist_start event_ns={} elapsed_ms={} screenshotBytes={}",
                request.submission.requestId().value(), request.persistenceStartedAtNanos,
                (request.persistenceStartedAtNanos - request.acceptedAtNanos) / 1_000_000, screenshotBytes.length);
        CompletionStage<Void> media = turn.context().screenshot() == null
                ? CompletableFuture.completedFuture(null)
                : store.appendMedia(target.world(), target.id(), turn.context().screenshot(), screenshotBytes);
        media.whenCompleteAsync((ignored, mediaFailure) -> {
            ChatContracts.Turn persistedTurn = turn;
            if (mediaFailure != null) {
                persistedTurn = new ChatContracts.Turn(turn.requestId(), turn.question(), turn.answer(), turn.replayItems(),
                        new ChatContracts.Context(turn.context().serializedGameplayContext(), null, turn.context().scope()),
                        turn.sources(), turn.navigationTargets(), new ChatContracts.Outcome(ChatContracts.OutcomeStatus.FAILED,
                        "media persistence failed: " + failureMessage(mediaFailure)), turn.tokenCount(), turn.sentAt());
                if (request.submission.replacing() == null) updateVisibleTurn(request, target, persistedTurn);
                publishPersistenceFailure(request, target, mediaFailure);
            }
            ChatContracts.Turn finalTurn = persistedTurn;
            CompletionStage<Void> persisted = request.submission.replacing() == null
                    ? store.appendTurn(target.world(), request.submission.scope(), target.id(), finalTurn)
                    : store.replaceLatestTurn(target.world(), request.submission.scope(), target.id(), request.submission.replacing(), finalTurn);
            persisted
                    .whenCompleteAsync((saved, appendFailure) -> {
                        finishPersistence(request);
                        if (request.submission.replacing() == null) {
                            if (appendFailure != null) publishPersistenceFailure(request, target, appendFailure);
                            return;
                        }
                        finishReplacementPersistence(request, target, finalTurn, appendFailure);
                    }, executor);
        }, executor);
    }

    private void finishReplacementPersistence(ActiveRequest request, ChatContracts.Conversation target,
                                              ChatContracts.Turn turn, Throwable failure) {
        ChatContracts.View snapshot = null;
        synchronized (stateLock) {
            request.persistenceSettled = true;
            request.persistenceFailure = failure == null ? null : failureMessage(failure);
            if (failure == null && target != null
                    && conversation.world().worldOrServer().equals(target.world().worldOrServer())
                    && conversation.id().equals(target.id())) {
                boolean publish = isVisible(request, target);
                List<ChatContracts.Turn> turns = new ArrayList<>(target.turns());
                turns.removeIf(existing -> existing.requestId().equals(request.submission.replacing()));
                turns.add(turn);
                conversation = new ChatContracts.Conversation(target.id(), target.world(), turns, turn.outcome());
                if (publish) {
                    context = turn.context();
                    sources = turn.sources();
                    navigationTargets = turn.navigationTargets();
                    snapshot = currentView();
                }
            } else if (failure != null && (target == null
                    ? visibleRequest != null && visibleRequest.equals(request.submission.requestId())
                    : isVisible(request, target))) {
                errors.add(failureMessage(failure));
                snapshot = currentView();
            }
        }
        request.persistence.complete(null);
        if (snapshot != null) publisher.accept(snapshot);
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

    private void markPersistencePending(ActiveRequest request) {
        if (request.persistencePending) return;
        request.persistencePending = true;
        pendingPersistenceCount++;
    }

    private void finishPersistence(ActiveRequest request) {
        long persistedAtNanos = System.nanoTime();
        synchronized (stateLock) {
            if (!request.persistencePending) return;
            request.persistencePending = false;
            pendingPersistenceCount--;
        }
        if (request.persistenceStartedAtNanos != 0) {
            PackSageLog.LOGGER.info("PackSage timing request={} phase=persist_done event_ns={} elapsed_ms={} persistMs={}",
                    request.submission.requestId().value(), persistedAtNanos,
                    (persistedAtNanos - request.acceptedAtNanos) / 1_000_000,
                    (persistedAtNanos - request.persistenceStartedAtNanos) / 1_000_000);
        }
    }

    private boolean isVisible(ActiveRequest request, ChatContracts.Conversation target) {
        return visibleRequest != null && visibleRequest.equals(request.submission.requestId())
                && conversation.world().worldOrServer().equals(target.world().worldOrServer())
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
        ActiveRequest visible = active != null ? active : pendingReplacement != null && !pendingReplacement.persistenceSettled
                && visibleRequest != null && visibleRequest.equals(pendingReplacement.submission.requestId())
                && conversation.world().worldOrServer().equals(pendingReplacement.submission.world().worldOrServer())
                ? pendingReplacement : null;
        return new ChatContracts.View(visible != null, ChatContracts.SignInState.SIGNED_OUT, false, conversation, context,
                sources, navigationTargets, List.copyOf(errors), visible == null ? "" : visible.answer.toString(),
                visible == null ? "" : active == null ? "Saving exchange…" : visible.activity, conversation.world(), false,
                visible == null ? "" : visible.submission.question(), visible == null ? null : visible.sentAt);
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
        private final Instant sentAt;
        private final long acceptedAtNanos;
        private final CompletableFuture<ChatContracts.RequestId> started = new CompletableFuture<>();
        private final CompletableFuture<Void> persistence = new CompletableFuture<>();
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
        private boolean persistenceSettled;
        private boolean persistencePending;
        private String persistenceFailure;
        private long backendStartedAtNanos;
        private long persistenceStartedAtNanos;
        private boolean firstTextLogged;

        private ActiveRequest(ChatContracts.Submission submission, long generation, Instant sentAt, long acceptedAtNanos) {
            this.submission = submission;
            this.generation = generation;
            this.sentAt = sentAt;
            this.acceptedAtNanos = acceptedAtNanos;
        }
    }

    private static final class WorldLoad {
        private final ChatContracts.WorldIdentity world;
        private Evidence.Scope scope;
        private final boolean createNew;
        private final long generation;
        private final CompletableFuture<ChatContracts.Conversation> result = new CompletableFuture<>();
        private String persistenceFailure;

        private WorldLoad(ChatContracts.WorldIdentity world, Evidence.Scope scope, boolean createNew, long generation) {
            this.world = world;
            this.scope = scope;
            this.createNew = createNew;
            this.generation = generation;
        }
    }
}
