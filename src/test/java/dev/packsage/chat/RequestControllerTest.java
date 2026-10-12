package dev.packsage.chat;

import dev.packsage.knowledge.Evidence;
import dev.packsage.model.ModelBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestControllerTest {
    private static final Executor DIRECT = Runnable::run;
    private static final Evidence.Scope SCOPE_A = new Evidence.Scope("session-a", "en_us", "team-a", "provider-1", 2);
    private static final Evidence.Scope SCOPE_B = new Evidence.Scope("session-b", "en_us", "team-b", "provider-1", 3);
    private static final ChatContracts.WorldIdentity WORLD_A = new ChatContracts.WorldIdentity("stable-world-a", "World A");
    private static final ChatContracts.WorldIdentity WORLD_B = new ChatContracts.WorldIdentity("stable-world-b", "World B");
    private static final ModelBackend.ModelInfo MODEL = new ModelBackend.ModelInfo("verified-model", "Verified Model",
            true, true, 128_000, 4_096);

    @TempDir
    Path temporaryDirectory;

    @Test
    void submissionAndCapturedImageBytesReachTheInjectedRequestBuilderUnchanged() {
        FakeBackend backend = new FakeBackend();
        AtomicReference<ChatContracts.PreparedRequest> prepared = new AtomicReference<>();
        byte[] screenshotBytes = {1, 2, 3};
        ChatContracts.Submission submission = submission("request-1", WORLD_A, SCOPE_A, true,
                new ChatContracts.HistoryLimits(7, 700), ModelBackend.SearchMode.EXPLICIT_REQUEST, 321);
        RequestController controller = new RequestController(new ConversationStore(temporaryDirectory, DIRECT), backend, DIRECT,
                (capture, includeScreenshot) -> CompletableFuture.completedFuture(new ChatContracts.CapturedContext(
                        new ChatContracts.Context("fresh", new ChatContracts.Screenshot(capture.requestId().value(),
                        "image/png", 3, Instant.EPOCH), capture.scope()), screenshotBytes)), ignored -> { },
                capture -> new ChatContracts.EvidenceSelection(List.of(), List.of()),
                request -> CompletableFuture.failedFuture(new IllegalStateException("no tools expected")),
                turn -> turn.tokenCount(), request -> {
                    prepared.set(request);
                    return new ModelBackend.Request(request.submission().selectedModel().id(), "instructions", List.of(
                            new ModelBackend.Message(ModelBackend.Role.USER, List.of(
                                    new ModelBackend.TextContent(request.submission().question()),
                                    new ModelBackend.ImageContent("data:image/png;base64," + Base64.getEncoder()
                                            .encodeToString(request.capturedContext().screenshotBytes()))))), List.of(),
                            request.submission().searchMode(), request.submission().requestId().value(),
                            request.submission().outputBudget());
                }, ignored -> { });

        controller.begin(submission).toCompletableFuture().join();
        screenshotBytes[0] = 9;

        assertSame(submission, prepared.get().submission());
        assertEquals(new ChatContracts.HistoryLimits(7, 700), prepared.get().submission().historyLimits());
        assertArrayEquals(new byte[]{1, 2, 3}, prepared.get().capturedContext().screenshotBytes());
        assertEquals("verified-model", backend.requests.getFirst().modelId());
        assertEquals(ModelBackend.SearchMode.EXPLICIT_REQUEST, backend.requests.getFirst().searchMode());
        assertEquals(321, backend.requests.getFirst().outputBudget());
        ModelBackend.ImageContent image = (ModelBackend.ImageContent) ((ModelBackend.Message) backend.requests.getFirst()
                .input().getFirst()).content().get(1);
        assertEquals("data:image/png;base64,AQID", image.dataUrl());
    }

    @Test
    void stopBeforeCaptureAndStoreCompletionPersistsTheSubmittedWorldWithoutReplacingRetryView() {
        ManualExecutor storeExecutor = new ManualExecutor();
        ConversationStore store = new ConversationStore(temporaryDirectory, storeExecutor);
        FakeBackend backend = new FakeBackend();
        Map<String, CompletableFuture<ChatContracts.CapturedContext>> captures = new LinkedHashMap<>();
        List<String> cancelledCaptures = new ArrayList<>();
        RequestController controller = controller(store, backend,
                (capture, ignored) -> captures.computeIfAbsent(capture.requestId().value(), key -> new CompletableFuture<>()),
                requestId -> cancelledCaptures.add(requestId.value()), ignored -> CompletableFuture.failedFuture(
                        new IllegalStateException("no tools expected")), ignored -> { });

        controller.begin(submission("old", WORLD_A, SCOPE_A, false, new ChatContracts.HistoryLimits(10, 12_000),
                ModelBackend.SearchMode.AUTOMATIC, 4_096));
        controller.stop();
        controller.begin(submission("new", WORLD_B, SCOPE_B, false, new ChatContracts.HistoryLimits(10, 12_000),
                ModelBackend.SearchMode.DISABLED, 2_048));
        captures.get("new").complete(new ChatContracts.CapturedContext(new ChatContracts.Context("new-context", null, SCOPE_B),
                new byte[0]));
        storeExecutor.drain();
        ChatContracts.View retryView = controller.view();

        captures.get("old").complete(new ChatContracts.CapturedContext(new ChatContracts.Context("late-old", null, SCOPE_A),
                new byte[0]));
        storeExecutor.drain();

        assertEquals(List.of("old"), cancelledCaptures);
        assertEquals(retryView, controller.view());
        assertEquals(WORLD_B, controller.view().conversation().world());
        CompletionStage<ChatContracts.Conversation> oldConversation = store.openLatest(WORLD_A);
        storeExecutor.drain();
        ChatContracts.Turn cancelled = oldConversation.toCompletableFuture().join().turns().getFirst();
        assertEquals(SCOPE_A, cancelled.context().scope());
        assertEquals(ChatContracts.OutcomeStatus.CANCELLED, cancelled.outcome().status());
    }

    @Test
    void newChatWinsAPendingLatestWorldLoadAndSendWaitsForTheReplacement() {
        ManualExecutor storeExecutor = new ManualExecutor();
        ConversationStore store = new ConversationStore(temporaryDirectory, storeExecutor);
        FakeBackend backend = new FakeBackend();
        RequestController controller = controller(store, backend,
                (capture, ignored) -> CompletableFuture.completedFuture(new ChatContracts.CapturedContext(
                        new ChatContracts.Context("fresh", null, capture.scope()), new byte[0])), ignored -> { },
                ignored -> CompletableFuture.failedFuture(new IllegalStateException("no tools expected")), ignored -> { });

        CompletionStage<ChatContracts.Conversation> staleLatest = controller.openWorld(WORLD_A, SCOPE_A);
        assertTrue(controller.begin(submission("too-early", WORLD_A, SCOPE_A, false,
                new ChatContracts.HistoryLimits(10, 12_000), ModelBackend.SearchMode.AUTOMATIC, 4_096))
                .toCompletableFuture().isCompletedExceptionally());
        controller.newChat();
        storeExecutor.drain();

        assertTrue(staleLatest.toCompletableFuture().isCompletedExceptionally());
        assertEquals(WORLD_A, controller.view().conversation().world());
        assertEquals(SCOPE_A, controller.view().context().scope());
        assertTrue(controller.view().conversation().turns().isEmpty());
        String replacementId = controller.view().conversation().id();
        CompletionStage<ChatContracts.Conversation> reopened = store.openLatest(WORLD_A);
        storeExecutor.drain();
        assertEquals(replacementId, reopened.toCompletableFuture().join().id());
    }

    @Test
    void newerWorldLoadSuppressesTheOlderCompletion() {
        ManualExecutor storeExecutor = new ManualExecutor();
        RequestController controller = controller(new ConversationStore(temporaryDirectory, storeExecutor), new FakeBackend(),
                (capture, ignored) -> CompletableFuture.completedFuture(new ChatContracts.CapturedContext(
                        new ChatContracts.Context("fresh", null, capture.scope()), new byte[0])), ignored -> { },
                ignored -> CompletableFuture.failedFuture(new IllegalStateException("no tools expected")), ignored -> { });

        CompletionStage<ChatContracts.Conversation> old = controller.openWorld(WORLD_A, SCOPE_A);
        CompletionStage<ChatContracts.Conversation> current = controller.openWorld(WORLD_B, SCOPE_B);
        storeExecutor.drain();

        assertTrue(old.toCompletableFuture().isCompletedExceptionally());
        assertEquals(WORLD_B, current.toCompletableFuture().join().world());
        assertEquals(WORLD_B, controller.view().conversation().world());
        assertEquals(SCOPE_B, controller.view().context().scope());
    }

    @Test
    void synchronousCompletionAndPublisherReentryCannotInstallTheOldExchange() {
        FakeBackend backend = new FakeBackend();
        backend.completeSynchronouslyOnce = true;
        RequestController[] holder = new RequestController[1];
        AtomicBoolean retried = new AtomicBoolean();
        Consumer<ChatContracts.View> publisher = view -> {
            if (!view.active() && view.conversation().outcome().status() == ChatContracts.OutcomeStatus.COMPLETED
                    && retried.compareAndSet(false, true)) {
                holder[0].begin(submission("second", WORLD_A, SCOPE_A, false,
                        new ChatContracts.HistoryLimits(10, 12_000), ModelBackend.SearchMode.AUTOMATIC, 4_096));
            }
        };
        holder[0] = controller(new ConversationStore(temporaryDirectory, DIRECT), backend,
                (capture, ignored) -> CompletableFuture.completedFuture(new ChatContracts.CapturedContext(
                        new ChatContracts.Context(capture.requestId().value(), null, capture.scope()), new byte[0])),
                ignored -> { }, ignored -> CompletableFuture.failedFuture(new IllegalStateException("no tools expected")), publisher);

        holder[0].begin(submission("first", WORLD_A, SCOPE_A, false, new ChatContracts.HistoryLimits(10, 12_000),
                ModelBackend.SearchMode.AUTOMATIC, 4_096)).toCompletableFuture().join();
        holder[0].stop();

        assertEquals(2, backend.requests.size());
        assertTrue(backend.cancelled.get("first"));
        assertTrue(backend.cancelled.get("second"));
    }

    @Test
    void toolResultAndProviderReplayPersistAsOneOrderedCompleteExchange() {
        FakeBackend backend = new FakeBackend();
        Evidence.Source source = new Evidence.Source(SCOPE_A, "native", "1", "recipe:altar", 2, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of());
        Evidence.NavigationTarget target = new Evidence.NavigationTarget(SCOPE_A, "native", "recipe:altar", "target:altar");
        RequestController controller = controller(new ConversationStore(temporaryDirectory, DIRECT), backend,
                (capture, ignored) -> CompletableFuture.completedFuture(new ChatContracts.CapturedContext(
                        new ChatContracts.Context("fresh", null, capture.scope()), new byte[0])), ignored -> { },
                request -> CompletableFuture.completedFuture(new ChatContracts.ToolResponse(
                        new ModelBackend.ToolResult(request.call().callId(), "{\"found\":\"" + request.call().callId() + "\"}", false),
                        new ChatContracts.EvidenceSelection(List.of(source), List.of(target)))), ignored -> { });
        controller.begin(submission("tool-request", WORLD_A, SCOPE_A, false,
                new ChatContracts.HistoryLimits(10, 12_000), ModelBackend.SearchMode.AUTOMATIC, 4_096)).toCompletableFuture().join();
        ModelBackend.ToolCall firstCall = new ModelBackend.ToolCall("call-1", "lookup", "{}");
        ModelBackend.ToolCall secondCall = new ModelBackend.ToolCall("call-2", "lookup", "{}");

        backend.emit("tool-request", new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null, firstCall,
                List.of(), List.of(), null));
        backend.emit("tool-request", new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null, secondCall,
                List.of(), List.of(), null));
        backend.emit("tool-request", new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, "answer", null,
                List.of(), List.of(), null));
        backend.emit("tool-request", new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), List.of(
                new ModelBackend.EncryptedReasoning("reasoning-1", "encrypted", "[]"), firstCall, secondCall,
                new ModelBackend.ToolResult("call-1", "{\"found\":\"call-1\"}", false),
                new ModelBackend.ToolResult("call-2", "{\"found\":\"call-2\"}", false),
                new ModelBackend.AssistantOutput("message-1", ModelBackend.OutputStatus.COMPLETED,
                        ModelBackend.OutputPhase.FINAL_ANSWER,
                        List.of(new ModelBackend.OutputText("answer", List.of())))), null));

        ChatContracts.Turn turn = controller.view().conversation().turns().getFirst();
        assertEquals(List.of(new ModelBackend.EncryptedReasoning("reasoning-1", "encrypted", "[]"), firstCall, secondCall,
                new ModelBackend.ToolResult("call-1", "{\"found\":\"call-1\"}", false),
                new ModelBackend.ToolResult("call-2", "{\"found\":\"call-2\"}", false),
                new ModelBackend.AssistantOutput("message-1", ModelBackend.OutputStatus.COMPLETED,
                        ModelBackend.OutputPhase.FINAL_ANSWER,
                        List.of(new ModelBackend.OutputText("answer", List.of())))),
                turn.replayItems());
        assertEquals(List.of(source), turn.sources());
        assertEquals(List.of(target), controller.view().navigationTargets());
        assertEquals(List.of("call-1", "call-2"), backend.submitted.stream().map(ModelBackend.ToolResult::callId).toList());
    }

    @Test
    void staleToolCompletionWithReusedCallIdCannotSubmitIntoTheNewRequest() {
        FakeBackend backend = new FakeBackend();
        List<CompletableFuture<ChatContracts.ToolResponse>> toolResponses = new ArrayList<>();
        RequestController controller = controller(new ConversationStore(temporaryDirectory, DIRECT), backend,
                (capture, ignored) -> CompletableFuture.completedFuture(new ChatContracts.CapturedContext(
                        new ChatContracts.Context("fresh", null, capture.scope()), new byte[0])), ignored -> { }, request -> {
                    CompletableFuture<ChatContracts.ToolResponse> response = new CompletableFuture<>();
                    toolResponses.add(response);
                    return response;
                }, ignored -> { });
        ModelBackend.ToolCall call = new ModelBackend.ToolCall("reused-call", "lookup", "{}");
        controller.begin(submission("old-tool", WORLD_A, SCOPE_A, false, new ChatContracts.HistoryLimits(10, 12_000),
                ModelBackend.SearchMode.AUTOMATIC, 4_096)).toCompletableFuture().join();
        backend.emit("old-tool", new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null, call,
                List.of(), List.of(), null));
        controller.stop();
        controller.begin(submission("new-tool", WORLD_A, SCOPE_A, false, new ChatContracts.HistoryLimits(10, 12_000),
                ModelBackend.SearchMode.AUTOMATIC, 4_096)).toCompletableFuture().join();
        backend.emit("new-tool", new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null, call,
                List.of(), List.of(), null));

        toolResponses.getFirst().complete(new ChatContracts.ToolResponse(
                new ModelBackend.ToolResult("reused-call", "old", false),
                new ChatContracts.EvidenceSelection(List.of(), List.of())));
        toolResponses.get(1).complete(new ChatContracts.ToolResponse(
                new ModelBackend.ToolResult("reused-call", "new", false),
                new ChatContracts.EvidenceSelection(List.of(), List.of())));

        assertEquals(List.of("new"), backend.submittedByRequest.get("new-tool").stream()
                .map(ModelBackend.ToolResult::serializedResult).toList());
    }

    @Test
    void rejectsARequestBuilderThatChangesTheSubmissionSnapshot() {
        FakeBackend backend = new FakeBackend();
        RequestController controller = new RequestController(new ConversationStore(temporaryDirectory, DIRECT), backend, DIRECT,
                (capture, ignored) -> CompletableFuture.completedFuture(new ChatContracts.CapturedContext(
                        new ChatContracts.Context("fresh", null, capture.scope()), new byte[0])), ignored -> { },
                ignored -> new ChatContracts.EvidenceSelection(List.of(), List.of()),
                ignored -> CompletableFuture.failedFuture(new IllegalStateException("no tools expected")), turn -> 1,
                prepared -> new ModelBackend.Request("different-model", "instructions", List.of(), List.of(),
                        ModelBackend.SearchMode.DISABLED, prepared.submission().requestId().value(), 1), ignored -> { });

        assertTrue(controller.begin(submission("builder", WORLD_A, SCOPE_A, false,
                new ChatContracts.HistoryLimits(10, 12_000), ModelBackend.SearchMode.AUTOMATIC, 4_096))
                .toCompletableFuture().isCompletedExceptionally());
        assertTrue(backend.requests.isEmpty());
        assertFalse(controller.view().errors().isEmpty());
    }

    @Test
    void mediaFailurePersistsFailedTurnWithoutBrokenAssetReferenceAndPublishesError() throws Exception {
        ConversationStore store = new ConversationStore(temporaryDirectory, DIRECT);
        ChatContracts.Conversation conversation = store.openLatest(WORLD_A).toCompletableFuture().join();
        Files.writeString(store.conversationPath(WORLD_A, conversation.id()).getParent().resolve("media"), "blocked");
        FakeBackend backend = new FakeBackend();
        RequestController controller = controller(store, backend,
                (capture, ignored) -> CompletableFuture.completedFuture(new ChatContracts.CapturedContext(new ChatContracts.Context(
                        "fresh", new ChatContracts.Screenshot(capture.requestId().value(), "image/png", 3, Instant.EPOCH),
                        capture.scope()), new byte[]{1, 2, 3})), ignored -> { },
                ignored -> CompletableFuture.failedFuture(new IllegalStateException("no tools expected")), ignored -> { });
        controller.begin(submission("media", WORLD_A, SCOPE_A, true, new ChatContracts.HistoryLimits(10, 12_000),
                ModelBackend.SearchMode.AUTOMATIC, 4_096)).toCompletableFuture().join();

        backend.emit("media", new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), List.of(), null));

        ChatContracts.Turn persisted = store.openLatest(WORLD_A).toCompletableFuture().join().turns().getFirst();
        assertEquals(ChatContracts.OutcomeStatus.FAILED, persisted.outcome().status());
        assertEquals(null, persisted.context().screenshot());
        assertFalse(controller.view().errors().isEmpty());
    }

    private static RequestController controller(ConversationStore store, FakeBackend backend,
                                                RequestController.ContextCapture capture,
                                                Consumer<ChatContracts.RequestId> cancelCapture,
                                                RequestController.ToolExecutor tools,
                                                Consumer<ChatContracts.View> publisher) {
        return new RequestController(store, backend, DIRECT, capture, cancelCapture,
                ignored -> new ChatContracts.EvidenceSelection(List.of(), List.of()), tools, turn -> turn.tokenCount(),
                prepared -> new ModelBackend.Request(prepared.submission().selectedModel().id(), "instructions", List.of(
                        new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent(
                                prepared.submission().question())))), List.of(), prepared.submission().searchMode(),
                        prepared.submission().requestId().value(), prepared.submission().outputBudget()), publisher);
    }

    private static ChatContracts.Submission submission(String requestId, ChatContracts.WorldIdentity world,
                                                       Evidence.Scope scope, boolean screenshot,
                                                       ChatContracts.HistoryLimits limits,
                                                       ModelBackend.SearchMode searchMode, int outputBudget) {
        return new ChatContracts.Submission(new ChatContracts.RequestId(requestId), world, scope, requestId, screenshot,
                limits, MODEL, searchMode, outputBudget);
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private void drain() {
            while (!tasks.isEmpty()) tasks.remove().run();
        }
    }

    private static final class FakeBackend implements ModelBackend {
        private final List<Request> requests = new ArrayList<>();
        private final List<ToolResult> submitted = new ArrayList<>();
        private final Map<String, Consumer<Event>> events = new LinkedHashMap<>();
        private final Map<String, Boolean> cancelled = new LinkedHashMap<>();
        private final Map<String, List<ToolResult>> submittedByRequest = new LinkedHashMap<>();
        private boolean completeSynchronouslyOnce;

        @Override
        public CompletableFuture<AuthState> signIn() {
            return CompletableFuture.completedFuture(new AuthState(AuthStatus.SIGNED_IN, "test", null));
        }

        @Override
        public void cancelSignIn() {
        }

        @Override
        public void signOut() {
        }

        @Override
        public CompletableFuture<Capabilities> capabilities() {
            return CompletableFuture.completedFuture(new Capabilities(List.of(MODEL), MODEL.id(), true, true, 128_000, 4_096));
        }

        @Override
        public Exchange start(Request request, Consumer<Event> listener) {
            requests.add(request);
            events.put(request.requestId(), listener);
            cancelled.put(request.requestId(), false);
            submittedByRequest.put(request.requestId(), new ArrayList<>());
            if (completeSynchronouslyOnce) {
                completeSynchronouslyOnce = false;
                listener.accept(new Event(EventKind.TEXT_DELTA, "answer", null, List.of(), List.of(), null));
                listener.accept(new Event(EventKind.COMPLETED, null, null, List.of(), List.of(
                        new AssistantOutput("message-1", OutputStatus.COMPLETED, OutputPhase.FINAL_ANSWER,
                                List.of(new OutputText("answer", List.of())))), null));
            }
            return new Exchange() {
                @Override
                public void submit(ToolResult result) {
                    submitted.add(result);
                    submittedByRequest.get(request.requestId()).add(result);
                }

                @Override
                public void cancel() {
                    cancelled.put(request.requestId(), true);
                }
            };
        }

        private void emit(String requestId, Event event) {
            events.get(requestId).accept(event);
        }
    }
}
