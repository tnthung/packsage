package dev.packsage.chat;

import dev.packsage.client.GameplayContextCapture;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.knowledge.KnowledgeService;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.model.PackSageTools;
import dev.packsage.model.ModelBackend;
import dev.packsage.model.GroundingInstructions;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    void selectedConversationAppearsBeforeTheQueuedSidebarCatalogIsRead() throws Exception {
        var root = Files.createTempDirectory("packsage-opening-selected");
        var setup = new ConversationStore(root, DIRECT);
        var conversation = setup.newConversation(WORLD).toCompletableFuture().join();
        setup.appendTurn(WORLD, SCOPE, conversation.id(), new ChatContracts.Turn(new ChatContracts.RequestId("saved"),
                "Question", "Answer", List.of(), new ChatContracts.Context("context", null, SCOPE), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1)).toCompletableFuture().join();
        List<Runnable> pending = new ArrayList<>();
        var store = new ConversationStore(root, pending::add);
        var runtime = new AssistantRuntime(store, new FakeBackend(), captureFactory(), DIRECT, DIRECT,
                current -> new KnowledgeService(List.of(), current), turn -> turn.tokenCount(),
                ignored -> { throw new AssertionError("No request expected"); }, ignored -> 512, ignored -> { }, ignored -> { });
        runtime.enterWorld(WORLD, SCOPE);
        var screen = runtime.openScreen();
        var catalog = runtime.sessions().toCompletableFuture();
        assertTrue(screen.view().readOnly());

        pending.removeFirst().run();

        assertFalse(catalog.isDone());
        assertFalse(screen.view().readOnly());
        assertEquals(conversation.id(), screen.view().conversation().id());
        assertEquals("Answer", screen.view().conversation().turns().getLast().answer());
        while (!pending.isEmpty()) pending.removeFirst().run();
    }

    @Test
    void firstCommandToolCallWaitsForConfirmationAndProducesCitableEvidence() throws Exception {
        var backend = new FakeBackend();
        var runtime = runtime(backend, captureFactory(), new AtomicReference<>(SCOPE), new AtomicReference<>(),
                new AtomicReference<>(), PackSageSettings.defaults(), ignored -> 512);
        var calls = new AtomicInteger();
        var runner = new dev.packsage.world.CommandRunner(() -> PackSageSettings.CommandConfirmation.ALWAYS, (command, active) -> {
            assertTrue(active.getAsBoolean());
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(new dev.packsage.world.CommandRunner.Execution(true, "executed", 1, List.of("done"), false, false));
        }, System::nanoTime);
        runtime.setCommandRunner(runner);
        runtime.enterWorld(WORLD, SCOPE);
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), true, true, 16_000, 2_048));
        runtime.send("Give me one diamond", false, false);
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                new ModelBackend.ToolCall("run", "run_command", "{\"command\":\"give @s diamond\"}"), List.of(), null));
        assertEquals(0, calls.get());
        assertEquals(1, runtime.commandConfirmations().size());
        runtime.confirmCommand(runtime.commandConfirmations().getFirst().id(), true);
        assertEquals(1, calls.get());
        assertEquals(1, backend.results.size());
        assertFalse(backend.results.getFirst().error(), backend.results.getFirst().serializedResult());
        assertTrue(backend.results.getFirst().serializedResult().contains("\"ran\":true"));
        assertFalse(runtime.view().sources().isEmpty());

        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                new ModelBackend.ToolCall("late", "run_command", "{\"command\":\"give @s stone\"}"), List.of(), null));
        var id = runtime.commandConfirmations().getFirst().id();
        runtime.stop();
        runner.tick();
        runner.decide(id, true);
        assertTrue(runtime.commandConfirmations().isEmpty());
        assertEquals(1, calls.get());
    }

    @Test
    void trackedChatUsesTheScreensCurrentScreenshotAndSearchDefaults() throws Exception {
        for (boolean customized : List.of(false, true)) {
            var backend = new FakeBackend();
            var prepared = new AtomicReference<ChatContracts.PreparedRequest>();
            var runtime = runtime(backend, captureFactory(), new AtomicReference<>(SCOPE), new AtomicReference<>(),
                    prepared, PackSageSettings.defaults(), ignored -> 512);
            runtime.enterWorld(WORLD, SCOPE);
            runtime.signIn();
            backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
            backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), true, true, 16_000, 2_048));
            if (customized) {
                var screen = runtime.openScreen();
                screen.setIncludeScreenshot(false);
                screen.setExplicitWebSearch(true);
            }

            assertNotNull(runtime.sendTracked("bridge question"));

            assertEquals(!customized, prepared.get().capturedContext().context().screenshot() != null);
            assertEquals(customized ? ModelBackend.SearchMode.EXPLICIT_REQUEST : ModelBackend.SearchMode.AUTOMATIC,
                    prepared.get().submission().searchMode());
        }
    }

    @Test
    void trackedChatFlagsOverrideScreenDefaultsWithoutChangingTheScreen() throws Exception {
        var backend = new FakeBackend();
        var prepared = new AtomicReference<ChatContracts.PreparedRequest>();
        var runtime = runtime(backend, captureFactory(), new AtomicReference<>(SCOPE), new AtomicReference<>(),
                prepared, PackSageSettings.defaults(), ignored -> 512);
        runtime.enterWorld(WORLD, SCOPE);
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), true, true, 16_000, 2_048));
        var screen = runtime.openScreen();

        assertNotNull(runtime.sendTracked("bridge question", false, true));

        assertNull(prepared.get().capturedContext().context().screenshot());
        assertEquals(ModelBackend.SearchMode.EXPLICIT_REQUEST, prepared.get().submission().searchMode());
        assertTrue(screen.includeScreenshot());
        assertFalse(screen.explicitWebSearch());
    }

    @Test
    void trackedRequestNavigationBelongsToItsOwnTurnEvenWhenScopesAndTargetIdsMatch() throws Exception {
        var store = new ConversationStore(Files.createTempDirectory("packsage-bridge-turn-navigation"), DIRECT);
        var conversation = store.newConversation(WORLD).toCompletableFuture().join();
        var first = new Evidence.NavigationTarget(SCOPE, "fixture", "first-source", "same-target");
        var second = new Evidence.NavigationTarget(SCOPE, "fixture", "second-source", "same-target");
        for (var target : List.of(first, second)) {
            store.appendTurn(WORLD, SCOPE, conversation.id(), new ChatContracts.Turn(
                    new ChatContracts.RequestId(target.sourceHandle()), "question", "answer", List.of(),
                    new ChatContracts.Context("{}", null, SCOPE), List.of(new Evidence.Source(SCOPE, "fixture", "v1",
                    target.sourceHandle(), 1, Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE,
                    Evidence.Authority.EXTERNAL, List.of())), List.of(target),
                    new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1, Instant.EPOCH))
                    .toCompletableFuture().join();
        }
        var backend = new FakeBackend();
        var runtime = new AssistantRuntime(store, backend, captureFactory(), DIRECT, DIRECT,
                current -> new KnowledgeService(List.of(), current), ChatContracts.Turn::tokenCount,
                prepared -> new ModelBackend.Request(MODEL.id(), "instructions", List.of(), List.of(),
                        prepared.submission().searchMode(), prepared.submission().requestId().value(), 512),
                ignored -> 512, ignored -> { }, ignored -> { });
        runtime.enterWorld(WORLD, SCOPE);
        runtime.openSession(new ChatContracts.SessionSummary(WORLD, conversation.id(), "", "", Instant.EPOCH));

        assertEquals(List.of(first), runtime.requestView(new ChatContracts.RequestId("first-source")).navigationTargets());
        assertEquals(List.of(second), runtime.requestView(new ChatContracts.RequestId("second-source")).navigationTargets());
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), true, true, 16_000, 2_048));
        var active = runtime.sendTracked("follow up");
        assertTrue(runtime.requestView(active).navigationTargets().isEmpty());
    }

    @Test
    void bridgeTracksRequestsAcrossConversationSelectionAndSharesNavigationChecks() throws Exception {
        var backend = new FakeBackend();
        var runtime = runtime(backend, captureFactory(), new AtomicReference<>(SCOPE), new AtomicReference<>(),
                PackSageSettings.defaults(), ignored -> 512);
        runtime.enterWorld(WORLD, SCOPE);
        assertTrue(runtime.sessionReady());
        assertNull(runtime.sendTracked("signed out"));
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), true, true, 16_000, 2_048));
        var id = runtime.sendTracked("bridge question");
        assertNotNull(id);
        assertEquals(id.value(), backend.requests.getFirst().requestId());
        assertTrue(runtime.requestView(id).active());
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, "bridge answer", null, List.of(), null));
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), List.of(
                new ModelBackend.AssistantOutput("answer", ModelBackend.OutputStatus.COMPLETED,
                        ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText("bridge answer", List.of())))), null));
        runtime.newChat();
        assertTrue(runtime.sessionReady());
        assertEquals("bridge answer", runtime.requestView(id).conversation().turns().getFirst().answer());
        assertEquals("bridge answer", runtime.savedRequest(id).toCompletableFuture().join().turns().getFirst().answer());
        assertNull(runtime.requestView(new ChatContracts.RequestId("missing")));
        var target = new Evidence.NavigationTarget(SCOPE, "fixture", "source", "missing");
        assertEquals(Evidence.NavigationStatus.STALE, runtime.navigateWithResult(target).toCompletableFuture().join().status());
    }

    @Test
    void bridgeCanInspectARequestThatFailsBeforeItHasASavedTurn() throws Exception {
        var backend = new FakeBackend();
        var runtime = runtime(backend, current -> new GameplayContextCapture(() -> {
            throw new IllegalStateException("capture failed");
        }, request -> new GameplayContextCapture.CapturedFrame("image/png", new byte[0], Instant.EPOCH), DIRECT, current),
                new AtomicReference<>(SCOPE), new AtomicReference<>(), PackSageSettings.defaults(), ignored -> 512);
        runtime.enterWorld(WORLD, SCOPE);
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), true, true, 16_000, 2_048));
        var id = runtime.sendTracked("failing capture");
        assertNotNull(id);
        var failed = runtime.requestView(id);
        assertNotNull(failed);
        assertFalse(failed.active());
        assertEquals(ChatContracts.OutcomeStatus.FAILED, failed.conversation().turns().getFirst().outcome().status());
        assertEquals("failing capture", failed.conversation().turns().getFirst().question());
        assertNull(runtime.savedRequest(id).toCompletableFuture().join());
    }

    @Test
    void toolCallsCarryTheirConversationRequestAndQuestion() throws Exception {
        var received = new AtomicReference<Toolbox.Origin>();
        var provider = new KnowledgeProvider() {
            @Override public String id() { return "fixture"; }
            @Override public ProviderStatus status() { return new ProviderStatus(Availability.AVAILABLE, "fixture", "fixture"); }
            @Override public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
                return CompletableFuture.failedFuture(new IllegalStateException("not queried"));
            }
            @Override public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
                return new Evidence.NavigationResult(Evidence.NavigationStatus.UNSUPPORTED, "fixture");
            }
            @Override public CompletionStage<Evidence.Result> handle(PackSageTools.ReadRequest request, Context context) {
                received.set(context.origin());
                return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE,
                        Evidence.Outcome.COMPLETE, null));
            }
        };
        var store = new ConversationStore(Files.createTempDirectory("packsage-tool-origin"), DIRECT);
        var backend = new FakeBackend();
        var runtime = new AssistantRuntime(store, backend, captureFactory(), DIRECT, DIRECT,
                current -> new KnowledgeService(List.of(provider), current), ChatContracts.Turn::tokenCount,
                prepared -> new ModelBackend.Request(MODEL.id(), "instructions", List.of(new ModelBackend.Message(
                        ModelBackend.Role.USER, List.of(new ModelBackend.TextContent(prepared.submission().question())))),
                        List.of(), prepared.submission().searchMode(), prepared.submission().requestId().value(), 512),
                ignored -> 512, ignored -> { }, ignored -> { });
        runtime.enterWorld(WORLD, SCOPE);
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), true, true, 16_000, 2_048));
        runtime.send("I built an iron farm here", false, false);
        String conversationId = runtime.view().conversation().id();
        String requestId = backend.requests.getFirst().requestId();
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                new ModelBackend.ToolCall("origin", "inspect_area", "{}"), List.of(), null));
        assertFalse(backend.results.getFirst().error(), backend.results.getFirst().serializedResult());
        assertEquals(new Toolbox.Origin(conversationId, requestId, "I built an iron farm here"), received.get());

        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), List.of(
                new ModelBackend.AssistantOutput("answer", ModelBackend.OutputStatus.COMPLETED,
                        ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText("Understood", List.of())))), null));
        var saved = store.openConversation(WORLD, conversationId).toCompletableFuture().join().turns().getFirst();
        assertEquals(received.get().requestId(), saved.requestId().value());
        assertEquals(received.get().playerWords(), saved.question());
    }

    @Test
    void reservedSendCannotCrossLifecycleTransitionsBeforeControllerActivation() throws Exception {
        for (String transition : List.of("world", "invalidate", "disconnect", "signOut", "stop", "selection")) {
            ConversationStore store = new ConversationStore(Files.createTempDirectory("packsage-runtime-dispatch"), DIRECT);
            FakeBackend backend = new FakeBackend();
            AtomicInteger captureObservations = new AtomicInteger();
            List<ChatContracts.PreparedRequest> preparedRequests = new ArrayList<>();
            List<ChatContracts.Turn> notices = new ArrayList<>();
            var runtime = new AssistantRuntime(store, backend, current -> new GameplayContextCapture(() -> {
                captureObservations.incrementAndGet();
                return new GameplayContextCapture.Observation("held", "target", "overworld", "1,2,3", "plains", "1", "block");
            }, request -> new GameplayContextCapture.CapturedFrame("image/png",
                    request.requestId().value().getBytes(java.nio.charset.StandardCharsets.UTF_8), Instant.EPOCH), DIRECT, current),
                    DIRECT, DIRECT, current -> new KnowledgeService(List.of(), current), ChatContracts.Turn::tokenCount,
                    prepared -> {
                        preparedRequests.add(prepared);
                        return new ModelBackend.Request(prepared.submission().selectedModel().id(), "instructions", List.of(
                                new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent(
                                        prepared.submission().question())))), List.of(), prepared.submission().searchMode(),
                                prepared.submission().requestId().value(), prepared.submission().outputBudget());
                    }, ignored -> 512, ignored -> { }, ignored -> { }, notices::add);
            runtime.enterWorld(WORLD, SCOPE);
            String originalSessionId = runtime.view().conversation().id();
            runtime.signIn();
            backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
            backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), MODEL.imageInput(),
                    MODEL.hostedSearch(), MODEL.contextTokens(), MODEL.outputTokens()));

            Runnable lifecycle = switch (transition) {
                case "world" -> () -> {
                    runtime.enterWorld(new ChatContracts.WorldIdentity("next-world", "Next World"),
                            new Evidence.Scope("next-world", "next-resources", "next-team", "next-runtime", 0));
                    runtime.send("new-world request", false, false);
                };
                case "invalidate" -> () -> {
                    runtime.invalidate(new Evidence.Scope("world", "updated-resources", "team", "runtime", 1));
                    runtime.send("replacement request", false, false);
                };
                case "disconnect" -> runtime::disconnect;
                case "signOut" -> runtime::signOut;
                case "stop" -> () -> {
                    runtime.stop();
                    runtime.send("replacement request", false, false);
                };
                case "selection" -> runtime::newChat;
                default -> throw new IllegalStateException(transition);
            };
            AtomicBoolean armed = new AtomicBoolean(true);
            AtomicInteger barrierHits = new AtomicInteger();
            AtomicReference<Thread> barrierThread = new AtomicReference<>();
            AtomicReference<Throwable> transitionFailure = new AtomicReference<>();
            Logger logger = (Logger) LogManager.getLogger("PackSage");
            Level previousLevel = logger.getLevel();
            AbstractAppender appender = new AbstractAppender("runtime-dispatch-" + transition, null, null, true,
                    Property.EMPTY_ARRAY) {
                @Override
                public void append(LogEvent event) {
                    if (event.getMessage().getFormattedMessage().contains("phase=runtime_dispatch")
                            && armed.compareAndSet(true, false)) {
                        barrierHits.incrementAndGet();
                        barrierThread.set(Thread.currentThread());
                        try {
                            lifecycle.run();
                        } catch (Throwable failure) {
                            transitionFailure.set(failure);
                        }
                    }
                }
            };
            Thread sendThread = Thread.currentThread();
            appender.start();
            logger.addAppender(appender);
            logger.setLevel(Level.INFO);
            try {
                runtime.send("reserved request", false, false);
            } finally {
                logger.removeAppender(appender);
                appender.stop();
                logger.setLevel(previousLevel);
            }

            assertEquals(1, barrierHits.get(), transition);
            assertEquals(sendThread, barrierThread.get(), transition);
            assertNull(transitionFailure.get(), transition);
            assertTrue(store.openConversation(WORLD, originalSessionId).toCompletableFuture().join().turns().isEmpty(), transition);
            assertTrue(notices.isEmpty(), transition);
            assertTrue(runtime.sessions().toCompletableFuture().join().stream()
                    .allMatch(session -> session.unreadRequestId() == null), transition);

            if (transition.equals("selection")) {
                assertEquals(1, backend.starts);
                assertEquals(1, captureObservations.get());
                assertEquals(1, preparedRequests.size());
                String selectedBId = runtime.view().conversation().id();
                assertNotEquals(originalSessionId, selectedBId);
                Evidence.Scope scopeA = preparedRequests.getFirst().submission().scope();
                runtime.send("request for B", false, false);
                assertEquals(2, backend.starts);
                assertEquals(2, captureObservations.get());
                assertNotEquals(scopeA, preparedRequests.getLast().submission().scope());
                assertTrue(runtime.view().active());
                assertEquals(selectedBId, runtime.view().conversation().id());

                List<ChatContracts.SessionSummary> sessions = runtime.sessions().toCompletableFuture().join();
                ChatContracts.SessionSummary sessionA = sessions.stream().filter(session -> session.id().equals(originalSessionId))
                        .findFirst().orElseThrow();
                ChatContracts.SessionSummary sessionB = sessions.stream().filter(session -> session.id().equals(selectedBId))
                        .findFirst().orElseThrow();
                runtime.openSession(sessionA);
                assertTrue(runtime.view().active());
                runtime.stop();
                assertTrue(backend.cancelled.get(preparedRequests.getFirst().submission().requestId().value()));
                runtime.openSession(sessionB);
                assertTrue(runtime.view().active());
                assertFalse(backend.cancelled.get(preparedRequests.getLast().submission().requestId().value()));
                runtime.stop();
            } else if (transition.equals("world") || transition.equals("invalidate") || transition.equals("stop")) {
                assertEquals(1, backend.starts, transition);
                assertEquals(1, captureObservations.get(), transition);
                assertEquals(1, preparedRequests.size(), transition);
                String expectedQuestion = transition.equals("world") ? "new-world request" : "replacement request";
                assertEquals(expectedQuestion, preparedRequests.getFirst().submission().question(), transition);
                assertTrue(runtime.view().active(), transition);
                assertEquals(preparedRequests.getFirst().submission().scope(), runtime.view().context().scope(), transition);
                assertEquals(preparedRequests.getFirst().submission().requestId().value(), runtime.view().activeRequestId(), transition);
                runtime.stop();
            } else {
                assertEquals(0, backend.starts, transition);
                assertEquals(0, captureObservations.get(), transition);
                assertFalse(runtime.view().active(), transition);
                assertTrue(preparedRequests.isEmpty(), transition);
            }
        }
    }

    @Test
    void holderReconciliationRejectsAnOldReadAndPreservesANewerUnreadIdentity() throws Exception {
        FakeBackend backend = new FakeBackend();
        ConversationStore store = new ConversationStore(Files.createTempDirectory("packsage-holder-reconcile-order"), DIRECT);
        var runtime = new AssistantRuntime(store, backend, captureFactory(), DIRECT, DIRECT,
                current -> new KnowledgeService(List.of(), current), ChatContracts.Turn::tokenCount,
                prepared -> new ModelBackend.Request(prepared.submission().selectedModel().id(), "instructions", List.of(
                        new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent(
                                prepared.submission().question())))), List.of(), prepared.submission().searchMode(),
                        prepared.submission().requestId().value(), prepared.submission().outputBudget()),
                ignored -> 512, ignored -> { }, ignored -> { });
        runtime.enterWorld(WORLD, SCOPE);
        String conversationId = runtime.view().conversation().id();
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), MODEL.imageInput(),
                MODEL.hostedSearch(), MODEL.contextTokens(), MODEL.outputTokens()));

        runtime.send("Question A1", false, false);
        String firstRequest = backend.requests.getFirst().requestId();
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), List.of(
                new ModelBackend.AssistantOutput("answer-a1", ModelBackend.OutputStatus.COMPLETED,
                        ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText("Answer A1", List.of())))), null));

        var holdersField = AssistantRuntime.class.getDeclaredField("holders");
        holdersField.setAccessible(true);
        Object holder = ((Map<?, ?>) holdersField.get(runtime)).values().iterator().next();
        var generationField = holder.getClass().getDeclaredField("viewReconciliationGeneration");
        generationField.setAccessible(true);
        var unreadField = holder.getClass().getDeclaredField("unreadRequestId");
        unreadField.setAccessible(true);
        var activeField = holder.getClass().getDeclaredField("active");
        activeField.setAccessible(true);
        var controllerField = holder.getClass().getDeclaredField("controller");
        controllerField.setAccessible(true);
        RequestController controller = (RequestController) controllerField.get(holder);
        var reconcile = AssistantRuntime.class.getDeclaredMethod("reconcileHolderView", holder.getClass(),
                ChatContracts.View.class, long.class, ChatContracts.RequestId.class);
        reconcile.setAccessible(true);
        ChatContracts.RequestId firstUnread = (ChatContracts.RequestId) unreadField.get(holder);
        assertEquals(new ChatContracts.RequestId(firstRequest), firstUnread);

        runtime.send("Question A2", false, false);
        ChatContracts.View oldActiveRead = controller.view();
        long oldActiveTicket = generationField.getLong(holder);
        assertTrue(oldActiveRead.active());
        assertEquals(firstUnread, unreadField.get(holder));
        String secondRequest = backend.requests.getLast().requestId();
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), List.of(
                new ModelBackend.AssistantOutput("answer-a2", ModelBackend.OutputStatus.COMPLETED,
                        ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText("Answer A2", List.of())))), null));
        assertFalse(runtime.view().active());
        assertEquals(new ChatContracts.RequestId(secondRequest), unreadField.get(holder));

        reconcile.invoke(runtime, holder, oldActiveRead, oldActiveTicket, firstUnread);
        assertFalse(runtime.view().active());
        assertFalse(activeField.getBoolean(holder));
        assertEquals(new ChatContracts.RequestId(secondRequest), unreadField.get(holder));
        ChatContracts.SessionSummary afterOldRead = runtime.sessions().toCompletableFuture().join().stream()
                .filter(session -> session.id().equals(conversationId)).findFirst().orElseThrow();
        assertFalse(afterOldRead.active());
        assertEquals(new ChatContracts.RequestId(secondRequest), afterOldRead.unreadRequestId());

        ChatContracts.RequestId expectedBeforeThirdMetadata = (ChatContracts.RequestId) unreadField.get(holder);
        assertEquals(new ChatContracts.RequestId(secondRequest), expectedBeforeThirdMetadata);
        ChatContracts.View completedA2Read = controller.view();
        long ticketBeforeThirdMetadata = generationField.getLong(holder);
        ChatContracts.View staleA2Read = new ChatContracts.View(false, completedA2Read.signIn(), completedA2Read.paused(),
                new ChatContracts.Conversation(conversationId, WORLD, completedA2Read.conversation().turns().stream()
                        .filter(turn -> !turn.requestId().equals(expectedBeforeThirdMetadata)).toList(),
                        completedA2Read.conversation().outcome()), completedA2Read.context(), completedA2Read.sources(),
                completedA2Read.navigationTargets(), completedA2Read.errors(), "", completedA2Read.activity(),
                completedA2Read.activeWorld(), false, "", null, completedA2Read.activities(), null, List.of());
        ChatContracts.RequestId thirdRequest = new ChatContracts.RequestId("metadata-only-a3");
        ChatContracts.Turn thirdTurn = new ChatContracts.Turn(thirdRequest, "Question A3", "Answer A3", List.of(),
                new ChatContracts.Context("", null, SCOPE), List.of(), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 0, Instant.EPOCH);
        store.appendTurn(WORLD, SCOPE, conversationId, thirdTurn).toCompletableFuture().join();
        var recordCompletion = AssistantRuntime.class.getDeclaredMethod("recordCompletion", holder.getClass(),
                ChatContracts.Turn.class);
        recordCompletion.setAccessible(true);
        recordCompletion.invoke(runtime, holder, thirdTurn);
        assertEquals(thirdRequest, unreadField.get(holder));
        assertEquals(ticketBeforeThirdMetadata, generationField.getLong(holder));
        reconcile.invoke(runtime, holder, staleA2Read, ticketBeforeThirdMetadata, expectedBeforeThirdMetadata);
        assertEquals(thirdRequest, unreadField.get(holder));
        assertEquals(thirdRequest, runtime.sessions().toCompletableFuture().join().stream()
                .filter(session -> session.id().equals(conversationId)).findFirst().orElseThrow().unreadRequestId());
    }

    @Test
    void reservedStartSurvivesAnOldIdlePublicationAndSettlesAfterEarlyCompletion() throws Exception {
        FakeBackend backend = new FakeBackend();
        var delayedControllerTasks = new java.util.ArrayDeque<Runnable>();
        AtomicBoolean delaySettlement = new AtomicBoolean();
        ConversationStore store = new ConversationStore(Files.createTempDirectory("packsage-reserved-publication"), DIRECT);
        var runtime = new AssistantRuntime(store, backend, captureFactory(), task -> {
            if (delaySettlement.get() && backend.starts > 0) delayedControllerTasks.addLast(task);
            else task.run();
        }, DIRECT,
                current -> new KnowledgeService(List.of(), current), ChatContracts.Turn::tokenCount,
                prepared -> new ModelBackend.Request(prepared.submission().selectedModel().id(), "instructions", List.of(
                        new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent(
                                prepared.submission().question())))), List.of(), prepared.submission().searchMode(),
                        prepared.submission().requestId().value(), prepared.submission().outputBudget()),
                ignored -> 512, ignored -> { }, ignored -> { });
        runtime.enterWorld(WORLD, SCOPE);
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), MODEL.imageInput(),
                MODEL.hostedSearch(), MODEL.contextTokens(), MODEL.outputTokens()));

        var holdersField = AssistantRuntime.class.getDeclaredField("holders");
        holdersField.setAccessible(true);
        Object holder = ((Map<?, ?>) holdersField.get(runtime)).values().iterator().next();
        var generationField = holder.getClass().getDeclaredField("viewReconciliationGeneration");
        generationField.setAccessible(true);
        var activeField = holder.getClass().getDeclaredField("active");
        activeField.setAccessible(true);
        var startingField = holder.getClass().getDeclaredField("requestStarting");
        startingField.setAccessible(true);
        var controllerField = holder.getClass().getDeclaredField("controller");
        controllerField.setAccessible(true);
        RequestController controller = (RequestController) controllerField.get(holder);
        ChatContracts.View oldIdleRead = controller.view();
        long oldIdleTicket = generationField.getLong(holder);
        var reconcile = AssistantRuntime.class.getDeclaredMethod("reconcileHolderView", holder.getClass(),
                ChatContracts.View.class, long.class, ChatContracts.RequestId.class);
        reconcile.setAccessible(true);
        var publish = AssistantRuntime.class.getDeclaredMethod("publishHolderView", holder.getClass(),
                ChatContracts.View.class);
        publish.setAccessible(true);

        AtomicBoolean armed = new AtomicBoolean(true);
        AtomicInteger barrierHits = new AtomicInteger();
        AtomicReference<Throwable> barrierFailure = new AtomicReference<>();
        Logger logger = (Logger) LogManager.getLogger("PackSage");
        Level previousLevel = logger.getLevel();
        AbstractAppender appender = new AbstractAppender("reserved-publication-dispatch", null, null, true,
                Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                if (event.getMessage().getFormattedMessage().contains("phase=runtime_dispatch")
                        && armed.compareAndSet(true, false)) {
                    barrierHits.incrementAndGet();
                    try {
                        assertTrue(generationField.getLong(holder) > oldIdleTicket);
                        reconcile.invoke(runtime, holder, oldIdleRead, oldIdleTicket, null);
                        assertTrue(startingField.getBoolean(holder));
                        assertTrue(activeField.getBoolean(holder));
                        publish.invoke(runtime, holder, oldIdleRead);
                        assertTrue(startingField.getBoolean(holder));
                        assertTrue(activeField.getBoolean(holder));
                    } catch (Throwable failure) {
                        barrierFailure.set(failure);
                    }
                }
            }
        };
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        delaySettlement.set(true);
        try {
            runtime.send("reserved completion", false, false);
        } finally {
            logger.removeAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
        }

        assertEquals(1, barrierHits.get());
        assertNull(barrierFailure.get());
        assertEquals(1, backend.starts);
        assertFalse(delayedControllerTasks.isEmpty());
        String requestId = backend.requests.getFirst().requestId();
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), List.of(
                new ModelBackend.AssistantOutput("reserved-answer", ModelBackend.OutputStatus.COMPLETED,
                        ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText("Finished early", List.of())))), null));
        assertTrue(startingField.getBoolean(holder));
        assertTrue(activeField.getBoolean(holder));

        delaySettlement.set(false);
        while (!delayedControllerTasks.isEmpty()) delayedControllerTasks.removeFirst().run();
        assertFalse(startingField.getBoolean(holder));
        assertFalse(activeField.getBoolean(holder));
        assertFalse(runtime.view().active());
        assertEquals(1, backend.starts);
        assertEquals(new ChatContracts.RequestId(requestId), store.openConversation(WORLD,
                runtime.view().conversation().id()).toCompletableFuture().join().turns().getLast().requestId());
    }

    @Test
    void completionNoticeQueuesOnceWithoutAnOpenScreenAndSurvivesNewChat() throws Exception {
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

        assertEquals(2, notices.size());
    }

    @Test
    void completionNoticeSurvivesNewChatBeforeListenerEnqueue() throws Exception {
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
        assertEquals(1, notices.size());
    }

    @Test
    void switchingToNewChatKeepsThePreviousRequestAndItsCapturedScopeAndHistory() throws Exception {
        ConversationStore store = new ConversationStore(Files.createTempDirectory("packsage-runtime-parallel"), DIRECT);
        ChatContracts.Conversation firstConversation = store.newConversation(WORLD).toCompletableFuture().join();
        ChatContracts.Turn previous = new ChatContracts.Turn(new ChatContracts.RequestId("previous-turn"), "Earlier question",
                "Earlier answer", List.of(), new ChatContracts.Context("earlier context", null, SCOPE), List.of(), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1);
        store.appendTurn(WORLD, SCOPE, firstConversation.id(), previous).toCompletableFuture().join();

        FakeBackend backend = new FakeBackend();
        Map<String, ChatContracts.PreparedRequest> preparedByRequest = new java.util.LinkedHashMap<>();
        var runtime = new AssistantRuntime(store, backend, current -> new GameplayContextCapture(
                () -> new GameplayContextCapture.Observation("held", "target", "overworld", "1,2,3", "plains", "1", "block"),
                request -> new GameplayContextCapture.CapturedFrame("image/png",
                        request.requestId().value().getBytes(java.nio.charset.StandardCharsets.UTF_8), Instant.EPOCH),
                DIRECT, current), DIRECT, DIRECT, current -> new KnowledgeService(List.of(), current),
                ChatContracts.Turn::tokenCount, prepared -> {
                    preparedByRequest.put(prepared.submission().requestId().value(), prepared);
                    return new ModelBackend.Request(prepared.submission().selectedModel().id(), "instructions", List.of(
                            new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent(
                                    prepared.submission().question())))), List.of(), prepared.submission().searchMode(),
                            prepared.submission().requestId().value(), prepared.submission().outputBudget());
                }, ignored -> 512, ignored -> { }, ignored -> { });
        runtime.enterWorld(WORLD, SCOPE);
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), true, true, 16_000, 2_048));

        runtime.send("Question for A", true, false);
        String firstRequestId = backend.requests.getFirst().requestId();
        String firstScopeWorldSession = preparedByRequest.get(firstRequestId).capturedContext().context().scope().worldSession();
        long firstScopeGeneration = preparedByRequest.get(firstRequestId).capturedContext().context().scope().requestGeneration();
        byte[] firstScreenshot = preparedByRequest.get(firstRequestId).capturedContext().screenshotBytes();

        runtime.newChat();
        String secondConversationId = runtime.view().conversation().id();
        runtime.send("Question for B", true, false);
        String secondRequestId = backend.requests.getLast().requestId();

        assertEquals(2, backend.requests.size());
        assertFalse(backend.cancelled.get(firstRequestId), "switching chats must leave A's backend request running");
        assertFalse(firstConversation.id().equals(secondConversationId));
        assertFalse(firstRequestId.equals(secondRequestId));
        assertEquals(List.of(previous), preparedByRequest.get(firstRequestId).history().turns());
        assertTrue(preparedByRequest.get(secondRequestId).history().turns().isEmpty());
        assertEquals(firstScopeWorldSession, preparedByRequest.get(firstRequestId).capturedContext().context().scope().worldSession());
        assertEquals(firstScopeGeneration, preparedByRequest.get(firstRequestId).capturedContext().context().scope().requestGeneration());
        assertTrue(preparedByRequest.get(secondRequestId).capturedContext().context().scope().requestGeneration() > firstScopeGeneration);
        assertEquals(firstRequestId, new String(firstScreenshot, java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(secondRequestId, new String(preparedByRequest.get(secondRequestId).capturedContext().screenshotBytes(),
                java.nio.charset.StandardCharsets.UTF_8));

        backend.emit(secondRequestId, new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), List.of(
                new ModelBackend.AssistantOutput("answer-b", ModelBackend.OutputStatus.COMPLETED,
                        ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText("Answer B", List.of())))), null));
        backend.emit(firstRequestId, new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), List.of(
                new ModelBackend.AssistantOutput("answer-a", ModelBackend.OutputStatus.COMPLETED,
                        ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText("Answer A", List.of())))), null));

        ChatContracts.Conversation savedA = store.openConversation(WORLD, firstConversation.id()).toCompletableFuture().join();
        ChatContracts.Conversation savedB = store.openConversation(WORLD, secondConversationId).toCompletableFuture().join();
        assertEquals("Question for A", savedA.turns().getLast().question());
        assertEquals(firstScopeGeneration, savedA.turns().getLast().context().scope().requestGeneration());
        assertEquals("Question for B", savedB.turns().getLast().question());
        assertEquals(preparedByRequest.get(secondRequestId).capturedContext().context().scope(),
                savedB.turns().getLast().context().scope());
        assertEquals(secondConversationId, runtime.view().conversation().id());
        assertEquals(new ChatContracts.RequestId(firstRequestId), store.sessions().toCompletableFuture().join().stream()
                .filter(summary -> summary.id().equals(firstConversation.id())).findFirst().orElseThrow().unreadRequestId());
        assertEquals(new ChatContracts.RequestId(secondRequestId), store.sessions().toCompletableFuture().join().stream()
                .filter(summary -> summary.id().equals(secondConversationId)).findFirst().orElseThrow().unreadRequestId());
    }

    @Test
    void parallelRecipeToolsKeepEvidenceReplayAndCompletionWithTheirConversation() throws Exception {
        ConversationStore store = new ConversationStore(Files.createTempDirectory("packsage-parallel-recipe-tools"), DIRECT);
        ChatContracts.Conversation conversationA = store.newConversation(WORLD).toCompletableFuture().join();
        ChatContracts.Turn previousA = new ChatContracts.Turn(new ChatContracts.RequestId("recipe-history-a"),
                "Earlier A question", "Earlier A answer", List.of(), new ChatContracts.Context("A context", null, SCOPE),
                List.of(), List.of(), new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1);
        store.appendTurn(WORLD, SCOPE, conversationA.id(), previousA).toCompletableFuture().join();

        FakeBackend backend = new FakeBackend();
        List<java.util.ArrayDeque<Runnable>> clientQueues = new ArrayList<>();
        Map<String, ChatContracts.PreparedRequest> preparedByRequest = new java.util.LinkedHashMap<>();
        var runtime = new AssistantRuntime(store, backend, captureFactory(), DIRECT, DIRECT, current -> {
            String label = clientQueues.isEmpty() ? "A" : "B";
            var queue = new java.util.ArrayDeque<Runnable>();
            clientQueues.add(queue);
            return new KnowledgeService(List.of(new KnowledgeProvider() {
                @Override public String id() { return "fixture"; }
                @Override public Metadata metadata() { return new Metadata(id(), List.of(), true, List.of()); }
                @Override public ProviderStatus status() { return new ProviderStatus(Availability.AVAILABLE, "fixture-1", null); }
                @Override public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope requestScope) {
                    return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE,
                            Evidence.Outcome.COMPLETE, null));
                }
                @Override public CompletionStage<Evidence.Result> searchRecipe(PackSageTools.ReadRequest request, Context context) {
                    var result = new CompletableFuture<Evidence.Result>();
                    queue.addLast(() -> {
                        var source = new Evidence.Source(request.scope(), id(), "fixture-1", "recipe-" + label,
                                request.scope().requestGeneration(), Instant.EPOCH, Evidence.Activity.ACTIVE,
                                Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of("recipe provenance " + label));
                        result.complete(new Evidence.Result(List.of(new Evidence.Record(source, Evidence.RecordKind.RECIPE,
                                label + " recipe", new Evidence.JsonObject(Map.of("itemId",
                                new Evidence.JsonString("example:" + label.toLowerCase() + "_item"))),
                                List.of(new Evidence.NavigationTarget(request.scope(), id(), "recipe-" + label, "page-" + label)))),
                                Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null));
                    });
                    return result;
                }
                @Override public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope requestScope) {
                    return new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED, "opened");
                }
            }), current);
        }, ChatContracts.Turn::tokenCount, prepared -> {
            preparedByRequest.put(prepared.submission().requestId().value(), prepared);
            return new ModelBackend.Request(prepared.submission().selectedModel().id(), "instructions", List.of(
                    new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent(
                            prepared.submission().question())))), List.of(), prepared.submission().searchMode(),
                    prepared.submission().requestId().value(), prepared.submission().outputBudget());
        }, ignored -> 512, ignored -> { }, ignored -> { });
        runtime.enterWorld(WORLD, SCOPE);
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), true, true, 16_000, 2_048));

        runtime.send("Question for recipe A", false, false);
        String requestA = backend.requests.getLast().requestId();
        var toolCallA = new ModelBackend.ToolCall("recipe-a", "search_recipe",
                "{\"filter\":{\"provider\":\"fixture\"}}");
        backend.emit(requestA, new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                toolCallA, List.of(), null));
        assertEquals(1, clientQueues.get(0).size(), "A's recipe search should remain queued");

        runtime.newChat();
        String conversationB = runtime.view().conversation().id();
        runtime.send("Question for recipe B", false, false);
        String requestB = backend.requests.getLast().requestId();
        assertNotEquals(preparedByRequest.get(requestA).capturedContext().context().scope(),
                preparedByRequest.get(requestB).capturedContext().context().scope());
        var toolCallB = new ModelBackend.ToolCall("recipe-b", "search_recipe",
                "{\"filter\":{\"provider\":\"fixture\"}}");
        backend.emit(requestB, new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                toolCallB, List.of(), null));
        assertEquals(1, clientQueues.get(1).size(), "B's recipe search should be independently queued");

        clientQueues.get(1).removeFirst().run();
        ModelBackend.ToolResult resultB = backend.results.stream().filter(result -> result.callId().equals("recipe-b")).findFirst().orElseThrow();
        assertFalse(resultB.error(), resultB.serializedResult());
        assertTrue(resultB.serializedResult().contains("example:b_item"), resultB.serializedResult());
        assertFalse(resultB.serializedResult().contains("example:a_item"), resultB.serializedResult());
        assertTrue(resultB.serializedResult().contains("recipe provenance B"), resultB.serializedResult());
        var answerB = new ModelBackend.AssistantOutput("answer-b", ModelBackend.OutputStatus.COMPLETED,
                ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText("Answer B", List.of())));
        backend.emit(requestB, new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, "Answer B", null, List.of(), null));
        backend.emit(requestB, new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), List.of(
                toolCallB, resultB, answerB), null));
        assertEquals(1, clientQueues.get(0).size(), "A remains pending after B completes");

        clientQueues.get(0).removeFirst().run();
        ModelBackend.ToolResult resultA = backend.results.stream().filter(result -> result.callId().equals("recipe-a")).findFirst().orElseThrow();
        assertFalse(resultA.error(), resultA.serializedResult());
        assertTrue(resultA.serializedResult().contains("example:a_item"), resultA.serializedResult());
        assertFalse(resultA.serializedResult().contains("example:b_item"), resultA.serializedResult());
        assertTrue(resultA.serializedResult().contains("recipe provenance A"), resultA.serializedResult());
        var answerA = new ModelBackend.AssistantOutput("answer-a", ModelBackend.OutputStatus.COMPLETED,
                ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText("Answer A", List.of())));
        backend.emit(requestA, new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, "Answer A", null, List.of(), null));
        backend.emit(requestA, new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), List.of(
                toolCallA, resultA, answerA), null));

        ChatContracts.Conversation savedA = store.openConversation(WORLD, conversationA.id()).toCompletableFuture().join();
        ChatContracts.Conversation savedB = store.openConversation(WORLD, conversationB).toCompletableFuture().join();
        assertEquals(WORLD, savedA.world());
        assertEquals(WORLD, savedB.world());
        assertEquals(List.of(previousA, savedA.turns().getLast()), savedA.turns());
        assertEquals(List.of(previousA), preparedByRequest.get(requestA).history().turns());
        assertTrue(preparedByRequest.get(requestB).history().turns().isEmpty());
        assertEquals("Question for recipe A", savedA.turns().getLast().question());
        assertEquals("Answer A", savedA.turns().getLast().answer());
        assertEquals(ChatContracts.OutcomeStatus.COMPLETED, savedA.turns().getLast().outcome().status());
        assertEquals(requestA, savedA.turns().getLast().requestId().value());
        assertEquals(List.of(toolCallA, resultA, answerA),
                savedA.turns().getLast().replayItems());
        assertEquals(1, savedA.turns().getLast().sources().size());
        assertEquals(List.of("recipe provenance A"), savedA.turns().getLast().sources().getFirst().provenance());
        assertEquals("fixture", savedA.turns().getLast().sources().getFirst().providerId());
        assertEquals("recipe-A", savedA.turns().getLast().sources().getFirst().sourceHandle());
        assertEquals(Evidence.Authority.LOADED, savedA.turns().getLast().sources().getFirst().authority());
        assertEquals(preparedByRequest.get(requestA).capturedContext().context().scope(),
                savedA.turns().getLast().sources().getFirst().scope());
        assertEquals(preparedByRequest.get(requestA).capturedContext().context().scope(), savedA.turns().getLast().context().scope());
        assertEquals(List.of(new Evidence.NavigationTarget(savedA.turns().getLast().context().scope(), "fixture", "recipe-A", "page-A")),
                savedA.turns().getLast().navigationTargets());
        assertEquals("Question for recipe B", savedB.turns().getLast().question());
        assertEquals(1, savedB.turns().size());
        assertEquals("Answer B", savedB.turns().getLast().answer());
        assertEquals(ChatContracts.OutcomeStatus.COMPLETED, savedB.turns().getLast().outcome().status());
        assertEquals(requestB, savedB.turns().getLast().requestId().value());
        assertEquals("recipe-b", savedB.turns().getLast().replayItems().stream().filter(ModelBackend.ToolResult.class::isInstance)
                .map(ModelBackend.ToolResult.class::cast).findFirst().orElseThrow().callId());
        assertEquals(List.of(toolCallB, resultB, answerB), savedB.turns().getLast().replayItems());
        assertEquals(List.of("recipe provenance B"), savedB.turns().getLast().sources().getFirst().provenance());
        assertEquals(1, savedB.turns().getLast().sources().size());
        assertEquals("recipe-B", savedB.turns().getLast().sources().getFirst().sourceHandle());
        assertEquals(preparedByRequest.get(requestB).capturedContext().context().scope(),
                savedB.turns().getLast().sources().getFirst().scope());
        assertEquals(Evidence.Authority.LOADED, savedB.turns().getLast().sources().getFirst().authority());
        assertEquals(preparedByRequest.get(requestB).capturedContext().context().scope(), savedB.turns().getLast().context().scope());
        assertEquals(List.of(new Evidence.NavigationTarget(savedB.turns().getLast().context().scope(), "fixture", "recipe-B", "page-B")),
                savedB.turns().getLast().navigationTargets());
        assertEquals(conversationB, runtime.view().conversation().id());
        assertTrue(runtime.view().conversation().turns().stream().anyMatch(turn -> turn.answer().equals("Answer B")));
        assertEquals(new ChatContracts.RequestId(requestA), store.sessions().toCompletableFuture().join().stream()
                .filter(summary -> summary.id().equals(conversationA.id())).findFirst().orElseThrow().unreadRequestId());
        assertEquals(new ChatContracts.RequestId(requestB), store.sessions().toCompletableFuture().join().stream()
                .filter(summary -> summary.id().equals(conversationB)).findFirst().orElseThrow().unreadRequestId());
        assertEquals(2, clientQueues.size());
    }

    @Test
    void concurrentOpenOfInitializingHolderReturnsAndKeepsTheNewerSelection() throws Exception {
        ConversationStore store = new ConversationStore(Files.createTempDirectory("packsage-holder-init-open-race"), DIRECT);
        ChatContracts.Conversation first = store.newConversation(WORLD).toCompletableFuture().join();
        ChatContracts.Conversation second = store.newConversation(WORLD).toCompletableFuture().join();
        ChatContracts.SessionSummary firstSummary = new ChatContracts.SessionSummary(WORLD, first.id(), "First", "", Instant.EPOCH);
        ChatContracts.SessionSummary secondSummary = new ChatContracts.SessionSummary(WORLD, second.id(), "Second", "", Instant.EPOCH);
        java.util.concurrent.ConcurrentLinkedQueue<Runnable> clientTasks = new java.util.concurrent.ConcurrentLinkedQueue<>();
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        CountDownLatch secondOpenStarted = new CountDownLatch(1);
        CountDownLatch secondOpenReturned = new CountDownLatch(1);
        AtomicInteger captureCalls = new AtomicInteger();
        AtomicInteger knowledgeCalls = new AtomicInteger();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        FakeBackend backend = new FakeBackend();
        var runtime = new AssistantRuntime(store, backend, scope -> {
            if (captureCalls.incrementAndGet() == 1) {
                factoryEntered.countDown();
                try {
                    if (!releaseFactory.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("capture factory release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
            return captureFactory().apply(scope);
        }, DIRECT, clientTasks::add, scope -> {
            knowledgeCalls.incrementAndGet();
            return new KnowledgeService(List.of(), scope);
        }, ChatContracts.Turn::tokenCount, GroundingInstructions::build,
                ignored -> 512, ignored -> { }, ignored -> { });
        runtime.enterWorld(WORLD, SCOPE);
        Thread firstOpen = new Thread(() -> {
            try { runtime.openSession(firstSummary); }
            catch (Throwable failure) { firstFailure.set(failure); }
        }, "packsage-first-holder-open");
        Thread secondOpen = new Thread(() -> {
            secondOpenStarted.countDown();
            try { runtime.openSession(firstSummary); }
            catch (Throwable failure) { secondFailure.set(failure); }
            finally { secondOpenReturned.countDown(); }
        }, "packsage-second-holder-open");
        try {
            firstOpen.start();
            assertTrue(factoryEntered.await(5, TimeUnit.SECONDS));
            secondOpen.start();
            assertTrue(secondOpenStarted.await(5, TimeUnit.SECONDS));
            assertTrue(secondOpenReturned.await(1, TimeUnit.SECONDS),
                    "a second open must join initialization without waiting for the factory");
            runtime.openSession(secondSummary);
            assertEquals(second.id(), runtime.view().conversation().id());
        } finally {
            releaseFactory.countDown();
            if (firstOpen.getState() != Thread.State.NEW) firstOpen.join(5_000);
            if (secondOpen.getState() != Thread.State.NEW) secondOpen.join(5_000);
            while (!clientTasks.isEmpty()) clientTasks.remove().run();
        }

        assertFalse(firstOpen.isAlive());
        assertFalse(secondOpen.isAlive());
        assertNull(firstFailure.get());
        assertNull(secondFailure.get());
        assertEquals(second.id(), runtime.view().conversation().id());
        runtime.openSession(firstSummary);
        while (!clientTasks.isEmpty()) clientTasks.remove().run();
        assertEquals(first.id(), runtime.view().conversation().id());
        assertEquals(2, captureCalls.get());
        assertEquals(2, knowledgeCalls.get());

        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        while (!clientTasks.isEmpty()) clientTasks.remove().run();
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), true, true, 16_000, 2_048));
        while (!clientTasks.isEmpty()) clientTasks.remove().run();
        runtime.send("one controller request", false, false);
        assertEquals(1, backend.starts);
        runtime.openSession(firstSummary);
        assertTrue(runtime.view().active());
        assertEquals(1, backend.starts);
    }

    @Test
    void captureFactoryReentrySharesEnterWorldInitializationForTheSameHolder() throws Exception {
        ConversationStore store = new ConversationStore(Files.createTempDirectory("packsage-holder-init-reentry"), DIRECT);
        ChatContracts.Conversation conversation = store.newConversation(WORLD).toCompletableFuture().join();
        ChatContracts.SessionSummary summary = new ChatContracts.SessionSummary(WORLD, conversation.id(), "Stored", "", Instant.EPOCH);
        AtomicReference<AssistantRuntime> runtimeReference = new AtomicReference<>();
        AtomicInteger captureCalls = new AtomicInteger();
        AtomicInteger knowledgeCalls = new AtomicInteger();
        AtomicBoolean reentered = new AtomicBoolean();
        AtomicBoolean nestedCallReturned = new AtomicBoolean();
        FakeBackend backend = new FakeBackend();
        var runtime = new AssistantRuntime(store, backend, scope -> {
            captureCalls.incrementAndGet();
            if (reentered.compareAndSet(false, true)) {
                runtimeReference.get().openSession(summary);
                nestedCallReturned.set(true);
            }
            return captureFactory().apply(scope);
        }, DIRECT, DIRECT, scope -> {
            knowledgeCalls.incrementAndGet();
            return new KnowledgeService(List.of(), scope);
        }, ChatContracts.Turn::tokenCount, GroundingInstructions::build,
                ignored -> 512, ignored -> { }, ignored -> { });
        runtimeReference.set(runtime);

        runtime.enterWorld(WORLD, SCOPE);

        assertTrue(nestedCallReturned.get(), "same-key open from the factory must return while initialization is pending");
        assertEquals(1, captureCalls.get());
        assertEquals(1, knowledgeCalls.get());
        assertEquals(conversation.id(), runtime.view().conversation().id());
        assertFalse(runtime.view().activity().equals("Loading session..."));
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), true, true, 16_000, 2_048));
        runtime.send("request after reentry", false, false);
        assertEquals(1, backend.starts);
    }

    @Test
    void providerMetadataReentrySharesEnterWorldInitializationForTheSameHolder() throws Exception {
        ConversationStore store = new ConversationStore(Files.createTempDirectory("packsage-holder-metadata-reentry"), DIRECT);
        ChatContracts.Conversation conversation = store.newConversation(WORLD).toCompletableFuture().join();
        ChatContracts.SessionSummary summary = new ChatContracts.SessionSummary(WORLD, conversation.id(), "Stored", "", Instant.EPOCH);
        AtomicReference<AssistantRuntime> runtimeReference = new AtomicReference<>();
        AtomicInteger captureCalls = new AtomicInteger();
        AtomicInteger knowledgeCalls = new AtomicInteger();
        AtomicInteger metadataCalls = new AtomicInteger();
        AtomicBoolean reentered = new AtomicBoolean();
        AtomicBoolean nestedCallReturned = new AtomicBoolean();
        FakeBackend backend = new FakeBackend();
        Toolbox provider = new Toolbox() {
            @Override public Metadata metadata() {
                if (metadataCalls.incrementAndGet() == 2 && reentered.compareAndSet(false, true)) {
                    runtimeReference.get().openSession(summary);
                    nestedCallReturned.set(true);
                }
                return new Metadata("fixture", List.of(), false, List.of());
            }
        };
        var runtime = new AssistantRuntime(store, backend, scope -> {
            captureCalls.incrementAndGet();
            return captureFactory().apply(scope);
        }, DIRECT, DIRECT, scope -> {
            knowledgeCalls.incrementAndGet();
            return new KnowledgeService(List.of(provider), scope);
        }, ChatContracts.Turn::tokenCount, GroundingInstructions::build,
                ignored -> 512, ignored -> { }, ignored -> { });
        runtimeReference.set(runtime);

        runtime.enterWorld(WORLD, SCOPE);

        assertTrue(nestedCallReturned.get(), "provider metadata must be able to reenter same-key open");
        assertEquals(1, captureCalls.get());
        assertEquals(1, knowledgeCalls.get());
        assertEquals(conversation.id(), runtime.view().conversation().id());
        assertFalse(runtime.view().activity().equals("Loading session..."));
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), true, true, 16_000, 2_048));
        runtime.send("request after metadata reentry", false, false);
        assertEquals(1, backend.starts);
    }

    @Test
    void staleNewChatInitializationDoesNotCreateAConversationAfterWorldSwitch() throws Exception {
        ConversationStore store = new ConversationStore(Files.createTempDirectory("packsage-stale-holder-creation"), DIRECT);
        java.util.concurrent.ConcurrentLinkedQueue<Runnable> clientTasks = new java.util.concurrent.ConcurrentLinkedQueue<>();
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        AtomicInteger captureCalls = new AtomicInteger();
        AtomicReference<Throwable> creationFailure = new AtomicReference<>();
        ChatContracts.WorldIdentity nextWorld = new ChatContracts.WorldIdentity("next-world", "Next World");
        Evidence.Scope nextScope = new Evidence.Scope("next-world", "resources", "team", "runtime", 0);
        var runtime = new AssistantRuntime(store, new FakeBackend(), scope -> {
            if (captureCalls.incrementAndGet() == 1) {
                factoryEntered.countDown();
                try {
                    if (!releaseFactory.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("capture factory release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
            return captureFactory().apply(scope);
        }, DIRECT, clientTasks::add, scope -> new KnowledgeService(List.of(), scope), ChatContracts.Turn::tokenCount,
                ignored -> { throw new AssertionError("no model request expected"); }, ignored -> 512,
                ignored -> { }, ignored -> { });
        runtime.enterWorld(WORLD, SCOPE);
        long originalWorldSessions = store.sessions().toCompletableFuture().join().stream()
                .filter(session -> session.world().worldOrServer().equals(WORLD.worldOrServer())).count();
        Thread creation = new Thread(() -> {
            try { runtime.newChat(); }
            catch (Throwable failure) { creationFailure.set(failure); }
        }, "packsage-stale-new-chat");
        try {
            creation.start();
            assertTrue(factoryEntered.await(5, TimeUnit.SECONDS));
            runtime.enterWorld(nextWorld, nextScope);
        } finally {
            releaseFactory.countDown();
            if (creation.getState() != Thread.State.NEW) creation.join(5_000);
            while (!clientTasks.isEmpty()) clientTasks.remove().run();
        }

        assertFalse(creation.isAlive());
        assertNull(creationFailure.get());
        assertEquals(originalWorldSessions, store.sessions().toCompletableFuture().join().stream()
                .filter(session -> session.world().worldOrServer().equals(WORLD.worldOrServer())).count());
        assertEquals(nextWorld.worldOrServer(), runtime.view().activeWorld().worldOrServer());
        assertFalse(runtime.view().activity().equals("Loading session..."));
    }

    @Test
    void failedCaptureInitializationSettlesAndCanBeRetried() throws Exception {
        ConversationStore store = new ConversationStore(Files.createTempDirectory("packsage-capture-init-retry"), DIRECT);
        ChatContracts.Conversation conversation = store.newConversation(WORLD).toCompletableFuture().join();
        ChatContracts.SessionSummary summary = new ChatContracts.SessionSummary(WORLD, conversation.id(), "Stored", "", Instant.EPOCH);
        AtomicInteger captureCalls = new AtomicInteger();
        var runtime = new AssistantRuntime(store, new FakeBackend(), scope -> {
            if (captureCalls.incrementAndGet() == 1) throw new IllegalStateException("capture failed once");
            return captureFactory().apply(scope);
        }, DIRECT, DIRECT, scope -> new KnowledgeService(List.of(), scope), ChatContracts.Turn::tokenCount,
                ignored -> { throw new AssertionError("no model request expected"); }, ignored -> 512,
                ignored -> { }, ignored -> { });

        runtime.enterWorld(WORLD, SCOPE);

        assertFalse(runtime.view().activity().equals("Loading session..."));
        assertTrue(runtime.view().errors().stream().anyMatch(error -> error.contains("capture failed once")));
        assertFalse(conversation.id().equals(runtime.view().conversation().id()));
        runtime.openSession(summary);

        assertEquals(conversation.id(), runtime.view().conversation().id());
        assertFalse(runtime.view().activity().equals("Loading session..."));
        assertEquals(2, captureCalls.get());
    }

    @Test
    void failedProviderRegistrationInitializationSettlesAndCanBeRetried() throws Exception {
        ConversationStore store = new ConversationStore(Files.createTempDirectory("packsage-metadata-init-retry"), DIRECT);
        ChatContracts.Conversation conversation = store.newConversation(WORLD).toCompletableFuture().join();
        ChatContracts.SessionSummary summary = new ChatContracts.SessionSummary(WORLD, conversation.id(), "Stored", "", Instant.EPOCH);
        AtomicInteger captureCalls = new AtomicInteger();
        AtomicInteger knowledgeCalls = new AtomicInteger();
        AtomicInteger metadataCalls = new AtomicInteger();
        AtomicBoolean failRegistration = new AtomicBoolean(true);
        Toolbox provider = new Toolbox() {
            @Override public Metadata metadata() {
                if (metadataCalls.incrementAndGet() == 2 && failRegistration.compareAndSet(true, false)) {
                    throw new IllegalStateException("metadata registration failed once");
                }
                return new Metadata("fixture", List.of(), false, List.of());
            }
        };
        var runtime = new AssistantRuntime(store, new FakeBackend(), scope -> {
            captureCalls.incrementAndGet();
            return captureFactory().apply(scope);
        }, DIRECT, DIRECT, scope -> {
            knowledgeCalls.incrementAndGet();
            return new KnowledgeService(List.of(provider), scope);
        }, ChatContracts.Turn::tokenCount, ignored -> { throw new AssertionError("no model request expected"); },
                ignored -> 512, ignored -> { }, ignored -> { });

        runtime.enterWorld(WORLD, SCOPE);

        assertFalse(runtime.view().activity().equals("Loading session..."));
        assertTrue(runtime.view().errors().stream().anyMatch(error -> error.contains("metadata registration failed once")));
        assertFalse(conversation.id().equals(runtime.view().conversation().id()));
        runtime.openSession(summary);

        assertEquals(conversation.id(), runtime.view().conversation().id());
        assertFalse(runtime.view().activity().equals("Loading session..."));
        assertEquals(2, captureCalls.get());
        assertEquals(2, knowledgeCalls.get());
        assertTrue(metadataCalls.get() >= 4);
    }

    @Test
    void reopeningAnActiveHolderKeepsItsRequestScopeForToolCalls() throws Exception {
        FakeBackend backend = new FakeBackend();
        AtomicInteger toolCalls = new AtomicInteger();
        var runtime = runtime(backend, captureFactory(), new AtomicReference<>(), new AtomicReference<>(),
                new AtomicReference<>(), PackSageSettings.defaults(), model -> 512, ignored -> { }, ignored -> { },
                current -> new KnowledgeService(List.of(new Toolbox() {
                    @Override public Metadata metadata() { return new Metadata("native", List.of(), true, List.of()); }
                    @Override public CompletionStage<Evidence.Result> planRecipe(PackSageTools.ReadRequest request, Context context) {
                        assertEquals(request.scope(), current.get());
                        toolCalls.incrementAndGet();
                        return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE,
                                Evidence.Outcome.COMPLETE, null));
                    }
                }), current), DIRECT);
        runtime.enterWorld(WORLD, SCOPE);
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), true, true, 16_000, 2_048));

        runtime.send("Question for A", false, false);
        String requestId = backend.requests.getFirst().requestId();
        String sessionId = runtime.view().conversation().id();
        Evidence.Scope requestScope = runtime.view().context().scope();
        runtime.newChat();
        runtime.openSession(new ChatContracts.SessionSummary(WORLD, sessionId, "Question for A", "", Instant.EPOCH));
        assertTrue(runtime.view().active());

        backend.emit(requestId, new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                new ModelBackend.ToolCall("recipe", "plan_recipe", "{\"identity\":\"minecraft:torch\",\"quantity\":1}"),
                List.of(), null));

        assertEquals(1, toolCalls.get());
        assertEquals(requestScope, runtime.view().context().scope());
        assertFalse(backend.results.getFirst().error(), backend.results.getFirst().serializedResult());
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
    void evidenceInvalidationDuringWorldEntryKeepsTheConversationSelection() throws Exception {
        java.util.ArrayDeque<Runnable> storeTasks = new java.util.ArrayDeque<>();
        Executor storeExecutor = storeTasks::addLast;
        var runtime = runtime(new FakeBackend(), captureFactory(), new AtomicReference<>(), new AtomicReference<>(),
                new AtomicReference<>(), PackSageSettings.defaults(), model -> 512, ignored -> { }, ignored -> { },
                current -> new KnowledgeService(List.of(), current), storeExecutor);

        runtime.enterWorld(WORLD, SCOPE);
        runtime.invalidate(new Evidence.Scope("world", "updated-resources", "team", "runtime", 1));
        runtime.invalidate(new Evidence.Scope("world", "updated-tags", "team", "runtime", 2));
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();

        assertFalse(runtime.view().readOnly(), "view must not remain in the loading state");
        assertEquals(WORLD.worldOrServer(), runtime.view().conversation().world().worldOrServer());
        assertFalse(runtime.view().conversation().id().isBlank());
        runtime.send("question", false, false);
        assertFalse(runtime.view().errors().stream().anyMatch(error -> error.contains("Cannot send without an active conversation")),
                runtime.view().errors().toString());
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

        var deleted = runtime.deleteSession(session);
        assertFalse(deleted.toCompletableFuture().isDone());
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();
        deleted.toCompletableFuture().join();

        assertTrue(runtime.view().conversation().turns().isEmpty());
        assertFalse(runtime.view().conversation().id().equals(session.id()));
    }

    @Test
    void deletingInactiveHolderWaitsForItsPersistenceWithoutBlockingSelectedHolder() throws Exception {
        java.util.ArrayDeque<Runnable> storeTasks = new java.util.ArrayDeque<>();
        ConversationStore store = new ConversationStore(Files.createTempDirectory("packsage-delete-inactive-store"), storeTasks::addLast);
        var firstCreation = store.newConversation(WORLD);
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();
        ChatContracts.Conversation first = firstCreation.toCompletableFuture().join();
        var secondCreation = store.newConversation(WORLD);
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();
        ChatContracts.Conversation second = secondCreation.toCompletableFuture().join();
        ChatContracts.SessionSummary firstSummary = new ChatContracts.SessionSummary(WORLD, first.id(), "A", "", Instant.EPOCH);
        ChatContracts.SessionSummary secondSummary = new ChatContracts.SessionSummary(WORLD, second.id(), "B", "", Instant.EPOCH);
        FakeBackend backend = new FakeBackend();
        AtomicInteger toolCalls = new AtomicInteger();
        var runtime = new AssistantRuntime(store, backend, captureFactory(), DIRECT, DIRECT,
                current -> new KnowledgeService(List.of(new Toolbox() {
                    @Override public Metadata metadata() { return new Metadata("native", List.of(), true, List.of()); }
                    @Override public CompletionStage<Evidence.Result> planRecipe(PackSageTools.ReadRequest request, Context context) {
                        toolCalls.incrementAndGet();
                        return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE,
                                Evidence.Outcome.COMPLETE, null));
                    }
                }), current), ChatContracts.Turn::tokenCount,
                prepared -> new ModelBackend.Request(prepared.submission().selectedModel().id(), "instructions", List.of(
                        new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent(
                                prepared.submission().question())))), List.of(), prepared.submission().searchMode(),
                        prepared.submission().requestId().value(), prepared.submission().outputBudget(),
                        prepared.submission().reasoningEffort()),
                ignored -> 512, ignored -> { }, ignored -> { });
        runtime.setSettings(PackSageSettings.defaults());
        runtime.enterWorld(WORLD, SCOPE);
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();
        runtime.openSession(firstSummary);
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), true, true, 16_000, 2_048));

        runtime.send("Question for A", false, false);
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();
        String requestA = backend.requests.getLast().requestId();
        runtime.openSession(secondSummary);
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();
        runtime.send("Question for B", false, false);
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();
        String requestB = backend.requests.getLast().requestId();
        assertEquals(second.id(), runtime.view().conversation().id());
        assertTrue(runtime.view().active());
        assertTrue(runtime.deleteSession(secondSummary).toCompletableFuture().isCompletedExceptionally());

        backend.emit(requestA, new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, "Answer A", null, List.of(), null));
        backend.emit(requestA, new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), List.of(
                new ModelBackend.AssistantOutput("answer-a", ModelBackend.OutputStatus.COMPLETED,
                        ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText("Answer A", List.of())))), null));
        var deletion = runtime.deleteSession(firstSummary);
        assertFalse(deletion.toCompletableFuture().isDone());
        assertEquals(second.id(), runtime.view().conversation().id());
        assertTrue(runtime.view().active());
        assertTrue(runtime.steer("B follow-up"));
        assertEquals(1, backend.steered.size());

        ModelBackend.ToolCall toolCall = new ModelBackend.ToolCall("recipe-b", "plan_recipe",
                "{\"identity\":\"minecraft:torch\",\"quantity\":1}");
        backend.emit(requestB, new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null, toolCall, List.of(), null));
        assertEquals(1, toolCalls.get());
        assertEquals(1, backend.results.size());
        assertFalse(backend.results.getLast().error(), backend.results.getLast().serializedResult());
        assertFalse(storeTasks.isEmpty(), "A's queued append must remain pending behind the controlled store executor");
        storeTasks.removeFirst().run();
        assertFalse(deletion.toCompletableFuture().isDone());
        assertFalse(storeTasks.isEmpty(), "A's completion metadata remains ahead of its delete");
        storeTasks.removeFirst().run();
        assertFalse(deletion.toCompletableFuture().isDone());
        assertFalse(storeTasks.isEmpty(), "the target delete is queued after A's persistence");

        backend.emit(requestB, new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, "Answer B", null, List.of(), null));
        backend.emit(requestB, new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), List.of(
                new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent("B follow-up"))),
                toolCall, backend.results.getLast(), new ModelBackend.AssistantOutput("answer-b", ModelBackend.OutputStatus.COMPLETED,
                        ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText("Answer B", List.of())))), null));

        assertEquals(second.id(), runtime.view().conversation().id());
        assertEquals(1, runtime.view().conversation().turns().size());
        assertEquals("Answer B", runtime.view().conversation().turns().getFirst().answer());
        assertFalse(deletion.toCompletableFuture().isDone());
        storeTasks.removeFirst().run();
        deletion.toCompletableFuture().join();
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();

        backend.emit(requestA, new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, "late A", null, List.of(), null));
        backend.emit(requestA, new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), List.of(), null));
        assertEquals(second.id(), runtime.view().conversation().id());
        assertTrue(runtime.view().conversation().turns().stream().anyMatch(turn -> turn.answer().equals("Answer B")));
        var summaries = store.sessions();
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();
        assertFalse(summaries.toCompletableFuture().join().stream().anyMatch(summary -> summary.id().equals(first.id())));
        assertTrue(summaries.toCompletableFuture().join().stream().anyMatch(summary -> summary.id().equals(second.id())));
        var persistedB = store.openConversation(WORLD, second.id());
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();
        assertEquals(1, persistedB.toCompletableFuture().join().turns().size());
        assertEquals("Answer B", persistedB.toCompletableFuture().join().turns().getFirst().answer());
    }

    @Test
    void reopeningTerminalHolderDuringScreenshotSaveKeepsTheCompletedTurn() throws Exception {
        java.util.ArrayDeque<Runnable> storeTasks = new java.util.ArrayDeque<>();
        ConversationStore store = new ConversationStore(Files.createTempDirectory("packsage-screenshot-reopen-store"), storeTasks::addLast);
        var conversationCreation = store.newConversation(WORLD);
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();
        ChatContracts.Conversation conversation = conversationCreation.toCompletableFuture().join();
        ChatContracts.SessionSummary summary = new ChatContracts.SessionSummary(WORLD, conversation.id(), "Question A", "", Instant.EPOCH);
        FakeBackend backend = new FakeBackend();
        var runtime = new AssistantRuntime(store, backend, captureFactory(), DIRECT, DIRECT,
                current -> new KnowledgeService(List.of(), current), ChatContracts.Turn::tokenCount,
                prepared -> new ModelBackend.Request(prepared.submission().selectedModel().id(), "instructions", List.of(
                        new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent(
                                prepared.submission().question())))), List.of(), prepared.submission().searchMode(),
                        prepared.submission().requestId().value(), prepared.submission().outputBudget(),
                        prepared.submission().reasoningEffort()),
                ignored -> 512, ignored -> { }, ignored -> { });
        runtime.setSettings(PackSageSettings.defaults());
        runtime.enterWorld(WORLD, SCOPE);
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), true, true, 16_000, 2_048));
        runtime.send("Question A", true, false);
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();
        String requestId = backend.requests.getLast().requestId();
        backend.emit(requestId, new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, "Answer A", null, List.of(), null));
        backend.emit(requestId, new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), List.of(
                new ModelBackend.AssistantOutput("answer-a", ModelBackend.OutputStatus.COMPLETED,
                        ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText("Answer A", List.of())))), null));

        assertEquals(1, storeTasks.size(), "screenshot media is queued before its turn append");
        runtime.openSession(summary);
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();

        assertEquals(conversation.id(), runtime.view().conversation().id());
        assertEquals(1, runtime.view().conversation().turns().size(),
                "reopening while screenshot persistence is pending must retain the completed turn");
        assertEquals("Answer A", runtime.view().conversation().turns().getFirst().answer());
        var persisted = store.openConversation(WORLD, conversation.id());
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();
        assertEquals(1, persisted.toCompletableFuture().join().turns().size());
        assertEquals("Answer A", persisted.toCompletableFuture().join().turns().getFirst().answer());
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
        assertTrue(runtime.view().errors().isEmpty(), runtime.view().errors().toString());
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
    void adapterDetailsReadsProviderStatusOutsideTheRuntimeLock() throws Exception {
        var lock = new AtomicReference<Object>();
        var provider = new KnowledgeProvider() {
            @Override public String id() { return "journal"; }
            @Override public ProviderStatus status() {
                assertFalse(Thread.holdsLock(lock.get()), "provider status must run outside stateLock");
                return new ProviderStatus(Availability.UNAVAILABLE, "fixture", "database refused");
            }
            @Override public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
                return CompletableFuture.failedFuture(new AssertionError("not queried"));
            }
            @Override public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
                return new Evidence.NavigationResult(Evidence.NavigationStatus.UNSUPPORTED, "fixture");
            }
        };
        var runtime = runtime(new FakeBackend(), captureFactory(), new AtomicReference<>(), new AtomicReference<>(),
                new AtomicReference<>(), PackSageSettings.defaults(), model -> 512, ignored -> { }, ignored -> { },
                current -> new KnowledgeService(List.of(provider), current), DIRECT);
        var stateLock = AssistantRuntime.class.getDeclaredField("stateLock");
        stateLock.setAccessible(true);
        lock.set(stateLock.get(runtime));
        runtime.enterWorld(WORLD, SCOPE);
        assertEquals(Map.of("journal", "database refused"), runtime.adapterDetails());
    }

    @Test
    void adapterAvailabilityTracksWorldHistoryAndFactoryFailure() throws Exception {
        FakeBackend backend = new FakeBackend();
        Map<String, Boolean> unavailableAdapters = Map.ofEntries(Map.entry("native", false), Map.entry("config", false), Map.entry("world", false),
                Map.entry("progress", false), Map.entry("journal", false), Map.entry("history", false));
        AtomicInteger factoryCalls = new AtomicInteger();
        var knowledgeFactory = (java.util.function.Function<java.util.function.Supplier<Evidence.Scope>, KnowledgeService>) current -> {
            if (factoryCalls.incrementAndGet() == 2) throw new IllegalStateException("provider refresh failed");
            return new KnowledgeService(List.of(new KnowledgeProvider() {
                @Override public String id() { return "config"; }
                @Override public ProviderStatus status() { return new ProviderStatus(Availability.AVAILABLE, "fixture", "fixture"); }
                @Override public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
                    return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE,
                            Evidence.Outcome.COMPLETE, null));
                }
                @Override public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
                    return new Evidence.NavigationResult(Evidence.NavigationStatus.UNSUPPORTED, "fixture");
                }
            }), current);
        };
        var runtime = runtime(backend, captureFactory(), new AtomicReference<>(), new AtomicReference<>(),
                new AtomicReference<>(), PackSageSettings.defaults(), model -> 512, ignored -> { }, ignored -> { },
                knowledgeFactory, DIRECT);

        assertEquals(unavailableAdapters, runtime.adapterAvailability());
        runtime.enterWorld(WORLD, SCOPE);
        Map<String, Boolean> activeAdapters = new java.util.HashMap<>(unavailableAdapters);
        activeAdapters.put("history", true);
        activeAdapters.put("config", true);
        assertEquals(activeAdapters, runtime.adapterAvailability());
        runtime.enterWorld(new ChatContracts.WorldIdentity("world-2", "World 2"),
                new Evidence.Scope("world-2", "resources", "team", "runtime", 0));
        assertEquals(unavailableAdapters, runtime.adapterAvailability());
        runtime.disconnect();
        assertEquals(unavailableAdapters, runtime.adapterAvailability());
    }

    @Test
    void selectedHolderPublishesCurrentToolSourcesAndNavigationWhileTurnIsActive() throws Exception {
        FakeBackend backend = new FakeBackend();
        AtomicReference<Evidence.Source> observedSource = new AtomicReference<>();
        AtomicReference<Evidence.NavigationTarget> observedTarget = new AtomicReference<>();
        var runtime = runtime(backend, captureFactory(), new AtomicReference<>(), new AtomicReference<>(),
                new AtomicReference<>(), PackSageSettings.defaults(), model -> 512, ignored -> { }, ignored -> { },
                current -> new KnowledgeService(List.of(new Toolbox() {
                    @Override public Metadata metadata() { return new Metadata("fixture", List.of(), true, List.of()); }
                    @Override public CompletionStage<Evidence.Result> searchRecipe(PackSageTools.ReadRequest request, Context context) {
                        Evidence.Scope scope = request.scope();
                        Evidence.Source source = new Evidence.Source(scope, "fixture", "fixture", "recipe-id",
                                scope.requestGeneration(), Instant.EPOCH, Evidence.Activity.ACTIVE,
                                Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of());
                        Evidence.NavigationTarget target = new Evidence.NavigationTarget(scope, "fixture", "recipe-id", "recipe-page");
                        observedSource.set(source);
                        observedTarget.set(target);
                        return CompletableFuture.completedFuture(new Evidence.Result(List.of(new Evidence.Record(source,
                                Evidence.RecordKind.RECIPE, "Fixture recipe", new Evidence.JsonObject(Map.of()), List.of(target))),
                                Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null));
                    }
                }), current), DIRECT);
        runtime.enterWorld(WORLD, SCOPE);
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), true, true, 16_000, 2_048));

        runtime.send("Find the recipe", false, false);
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                new ModelBackend.ToolCall("recipe", "search_recipe",
                        "{\"filter\":{\"text\":\"fixture\",\"provider\":\"fixture\"}}"),
                List.of(), null));

        assertTrue(runtime.view().active());
        assertTrue(runtime.view().sources().contains(observedSource.get()));
        assertTrue(runtime.view().navigationTargets().contains(observedTarget.get()));
    }

    @Test
    void lateNavigationResultCannotReplaceFeedbackFromNewerClick() throws Exception {
        ChatContracts.WorldIdentity world = WORLD;
        Evidence.Scope historicalScope = new Evidence.Scope("world", "old-resources", "team", "runtime", 2);
        var firstSource = new Evidence.Source(historicalScope, "fixture", "fixture", "first", 2, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of());
        var secondSource = new Evidence.Source(historicalScope, "fixture", "fixture", "second", 2, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of());
        var firstTarget = new Evidence.NavigationTarget(historicalScope, "fixture", "first", "first-page");
        var secondTarget = new Evidence.NavigationTarget(historicalScope, "fixture", "second", "second-page");
        var historical = new ChatContracts.Turn(new ChatContracts.RequestId("navigation-history"), "question", "answer",
                List.of(), new ChatContracts.Context("historical context", null, historicalScope),
                List.of(firstSource, secondSource), List.of(firstTarget, secondTarget),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1);
        ConversationStore store = new ConversationStore(Files.createTempDirectory("packsage-navigation-race-store"), DIRECT);
        ChatContracts.Conversation conversation = store.newConversation(world).toCompletableFuture().join();
        store.appendTurn(world, historicalScope, conversation.id(), historical).toCompletableFuture().join();
        var firstRead = new CompletableFuture<Evidence.Result>();
        var secondRead = new CompletableFuture<Evidence.Result>();
        KnowledgeProvider provider = new KnowledgeProvider() {
            @Override public String id() { return "fixture"; }
            @Override public ProviderStatus status() { return new ProviderStatus(Availability.AVAILABLE, "fixture", "fixture"); }
            @Override public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
                String id = ((Evidence.JsonString) query.arguments().values().get("id")).value();
                return id.equals("first") ? firstRead : secondRead;
            }
            @Override public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
                return new Evidence.NavigationResult(Evidence.NavigationStatus.UNSUPPORTED, "fixture");
            }
        };
        FakeBackend backend = new FakeBackend();
        var runtime = new AssistantRuntime(store, backend, captureFactory(), DIRECT, DIRECT,
                current -> new KnowledgeService(List.of(provider), current), ChatContracts.Turn::tokenCount,
                prepared -> new ModelBackend.Request(MODEL.id(), "instructions", List.of(new ModelBackend.Message(
                        ModelBackend.Role.USER, List.of(new ModelBackend.TextContent(prepared.submission().question())))),
                        List.of(), prepared.submission().searchMode(), prepared.submission().requestId().value(), 512),
                ignored -> 512, ignored -> { }, ignored -> { });
        runtime.enterWorld(world, SCOPE);
        var screen = runtime.openScreen();
        runtime.openSession(new ChatContracts.SessionSummary(world, conversation.id(), "question", "answer", Instant.EPOCH));

        runtime.navigate(firstTarget);
        runtime.navigate(secondTarget);
        var currentSecondTarget = new Evidence.NavigationTarget(SCOPE, "fixture", "second", "second-page");
        var currentSecondSource = new Evidence.Source(SCOPE, "fixture", "fixture", "second", 0, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of());
        secondRead.complete(new Evidence.Result(List.of(new Evidence.Record(currentSecondSource, Evidence.RecordKind.GUIDE,
                "Second page", new Evidence.JsonObject(Map.of()), List.of(currentSecondTarget))),
                Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null));
        firstRead.complete(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null));

        var navigationError = screen.getClass().getDeclaredMethod("navigationError", long.class);
        navigationError.setAccessible(true);
        assertEquals("Navigation unsupported: fixture", navigationError.invoke(screen, 0L));
    }

    @Test
    void openedNavigationDetailIsShownAsANoticeAndOpenedWithoutDetailShowsNothing() throws Exception {
        ChatContracts.WorldIdentity world = WORLD;
        Evidence.Scope historicalScope = new Evidence.Scope("world", "old-resources", "team", "runtime", 2);
        var source = new Evidence.Source(historicalScope, "fixture", "fixture", "chest", 2, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of());
        var elsewhere = new Evidence.NavigationTarget(historicalScope, "fixture", "chest", "elsewhere");
        var here = new Evidence.NavigationTarget(historicalScope, "fixture", "chest", "here");
        var historical = new ChatContracts.Turn(new ChatContracts.RequestId("navigation-notice"), "question", "answer",
                List.of(), new ChatContracts.Context("historical context", null, historicalScope),
                List.of(source), List.of(elsewhere, here),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1);
        ConversationStore store = new ConversationStore(Files.createTempDirectory("packsage-navigation-notice-store"), DIRECT);
        ChatContracts.Conversation conversation = store.newConversation(world).toCompletableFuture().join();
        store.appendTurn(world, historicalScope, conversation.id(), historical).toCompletableFuture().join();
        KnowledgeProvider provider = new KnowledgeProvider() {
            @Override public String id() { return "fixture"; }
            @Override public ProviderStatus status() { return new ProviderStatus(Availability.AVAILABLE, "fixture", "fixture"); }
            @Override public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
                return CompletableFuture.failedFuture(new IllegalStateException("not read"));
            }
            @Override public CompletionStage<Evidence.NavigationTarget> refreshNavigation(Evidence.NavigationTarget target, Evidence.Scope scope) {
                return CompletableFuture.completedFuture(new Evidence.NavigationTarget(scope, target.providerId(), target.sourceHandle(), target.targetId()));
            }
            @Override public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
                return new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED,
                        target.targetId().equals("elsewhere") ? "10, 64, -3 in minecraft:the_nether" : null);
            }
        };
        var runtime = new AssistantRuntime(store, new FakeBackend(), captureFactory(), DIRECT, DIRECT,
                current -> new KnowledgeService(List.of(provider), current), ChatContracts.Turn::tokenCount,
                prepared -> new ModelBackend.Request(MODEL.id(), "instructions", List.of(new ModelBackend.Message(
                        ModelBackend.Role.USER, List.of(new ModelBackend.TextContent(prepared.submission().question())))),
                        List.of(), prepared.submission().searchMode(), prepared.submission().requestId().value(), 512),
                ignored -> 512, ignored -> { }, ignored -> { });
        runtime.enterWorld(world, SCOPE);
        var screen = runtime.openScreen();
        runtime.openSession(new ChatContracts.SessionSummary(world, conversation.id(), "question", "answer", Instant.EPOCH));
        var navigationMessage = screen.getClass().getDeclaredMethod("navigationError", long.class);
        navigationMessage.setAccessible(true);
        var notice = screen.getClass().getDeclaredMethod("navigationNotice");
        notice.setAccessible(true);

        runtime.navigate(elsewhere);
        assertEquals("10, 64, -3 in minecraft:the_nether", navigationMessage.invoke(screen, 0L));
        assertEquals(true, notice.invoke(screen));

        runtime.navigate(here);
        assertNull(navigationMessage.invoke(screen, 0L));
    }

    @Test
    void worldSwitchRebuildsKnowledgeAndDropsLateToolResult() throws Exception {
        FakeBackend backend = new FakeBackend();
        AtomicInteger factoryCalls = new AtomicInteger();
        CompletableFuture<Evidence.Result> pendingTool = new CompletableFuture<>();
        var knowledgeFactory = (java.util.function.Function<java.util.function.Supplier<Evidence.Scope>, KnowledgeService>) current -> {
            factoryCalls.incrementAndGet();
            return new KnowledgeService(List.of(new Toolbox() {
                @Override public Metadata metadata() { return new Metadata("native", List.of(), true, List.of()); }
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

        assertEquals(2, factoryCalls.get());
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
            List<String> requestErrors = runtime.view().errors();
            runtime.navigate(target);
            assertEquals(otherWorld ? 0 : 1, navigations.get());
            assertEquals(requestErrors, runtime.view().errors());
            var navigationError = screen.getClass().getDeclaredMethod("navigationError", long.class);
            navigationError.setAccessible(true);
            if (otherWorld) assertTrue(String.valueOf(navigationError.invoke(screen, 0L)).contains("Join this session"));
            else assertNull(navigationError.invoke(screen, 0L));
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
    void modelAliasesStayCanonicalInSavedAnswersSteeringReadsNavigationDetailsAndReopenedHistory() throws Exception {
        String handle = "recipe:example:recipes/carried_storage/very_long_container_recipe";
        String reference = "fixture:" + handle;
        var reads = new AtomicInteger();
        var navigations = new AtomicInteger();
        KnowledgeProvider provider = new KnowledgeProvider() {
            @Override public String id() { return "fixture"; }
            @Override public Metadata metadata() { return new Metadata(id(), List.of(), true, List.of()); }
            @Override public ProviderStatus status() { return new ProviderStatus(Availability.AVAILABLE, "fixture", "fixture"); }
            @Override public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
                var source = new Evidence.Source(scope, id(), "fixture", handle, scope.requestGeneration(), Instant.EPOCH,
                        Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of());
                var target = new Evidence.NavigationTarget(scope, id(), handle, "container-page");
                return CompletableFuture.completedFuture(new Evidence.Result(List.of(new Evidence.Record(source,
                        Evidence.RecordKind.RECIPE, "Carried storage recipe", new Evidence.JsonObject(Map.of(
                        "text", new Evidence.JsonString("Inspect every relevant carried container."))), List.of(target))),
                        Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null));
            }
            @Override public CompletionStage<Evidence.Result> searchRecipe(PackSageTools.ReadRequest request, Context context) {
                return query(new Evidence.Query(Evidence.Operation.GET, request.arguments(), 1, null, null), request.scope());
            }
            @Override public CompletionStage<Evidence.Result> readEvidence(PackSageTools.ReadRequest request, Context context) {
                assertEquals(handle, context.reference(request).sourceHandle());
                assertEquals(reference, ((Evidence.JsonString) ((Evidence.JsonArray) request.arguments().values().get("handles")).values().getFirst()).value());
                reads.incrementAndGet();
                return query(new Evidence.Query(Evidence.Operation.GET, request.arguments(), 1, null, null), request.scope());
            }
            @Override public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
                assertEquals(handle, target.sourceHandle());
                assertEquals("container-page", target.targetId());
                navigations.incrementAndGet();
                return new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED, null);
            }
        };
        var store = new ConversationStore(Files.createTempDirectory("packsage-canonical-reference-runtime"), DIRECT);
        var backend = new FakeBackend();
        var runtime = new AssistantRuntime(store, backend, captureFactory(), DIRECT, DIRECT,
                current -> new KnowledgeService(List.of(provider), current), GroundingInstructions::historyTextBudget,
                GroundingInstructions::build, ignored -> 512, ignored -> { }, ignored -> { });
        runtime.enterWorld(WORLD, SCOPE);
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), true, true, 16_000, 2_048));
        runtime.send("Check carried storage", false, false);
        var search = new ModelBackend.ToolCall("search", "search_recipe", "{\"filter\":{\"text\":\"carried storage\",\"provider\":\"fixture\"}}");
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null, search, List.of(), null));
        assertEquals(1, backend.results.size());
        String alias = com.google.gson.JsonParser.parseString(backend.results.getFirst().serializedResult()).getAsJsonObject()
                .getAsJsonArray("records").get(0).getAsJsonObject().get("reference").getAsString();
        assertTrue(alias.startsWith("ref:"), alias);
        var read = new ModelBackend.ToolCall("read", "read_evidence", "{\"handles\":[\"" + alias + "\"]}");
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null, read, List.of(), null));
        assertEquals(1, reads.get());
        String wireAnswer = "Inspect the bundle [" + alias + "].";
        String canonicalAnswer = "Inspect the bundle [" + reference + "].";
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, wireAnswer, null, List.of(), null));
        assertEquals(canonicalAnswer, runtime.view().streamingAnswer());
        assertTrue(runtime.steer("Also check the second bundle"));
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, " Check both.", null, List.of(), null));
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), List.of(search,
                backend.results.getFirst(), read, backend.results.getLast(), backend.steered.getFirst(),
                new ModelBackend.AssistantOutput("answer", ModelBackend.OutputStatus.COMPLETED, ModelBackend.OutputPhase.FINAL_ANSWER,
                        List.of(new ModelBackend.OutputText(wireAnswer + " Check both.", List.of())))), null));
        var turn = runtime.view().conversation().turns().getLast();
        assertEquals(ChatContracts.OutcomeStatus.COMPLETED, turn.outcome().status(), turn.outcome().detail());
        assertEquals(canonicalAnswer + " Check both.", turn.answer());
        assertEquals(canonicalAnswer.length(), turn.steeringMessages().getFirst().answerOffset());
        assertEquals(handle, turn.sources().getFirst().sourceHandle());
        assertEquals(reference, com.google.gson.JsonParser.parseString(((ModelBackend.ToolCall) turn.replayItems().get(2)).serializedArguments()).getAsJsonObject()
                .getAsJsonArray("handles").get(0).getAsString());
        var saved = store.openConversation(WORLD, runtime.view().conversation().id()).toCompletableFuture().join();
        assertEquals(turn, saved.turns().getLast());
        var details = Class.forName("dev.packsage.client.SourceDetailsScreen").getDeclaredMethod("details", Evidence.Source.class, ChatContracts.Turn.class);
        details.setAccessible(true);
        assertTrue(((List<?>) details.invoke(null, turn.sources().getFirst(), saved.turns().getLast())).contains("Carried storage recipe"));
        runtime.newChat();
        runtime.openSession(new ChatContracts.SessionSummary(WORLD, saved.id(), turn.question(), turn.answer(), Instant.EPOCH));
        assertEquals(turn, runtime.view().conversation().turns().getLast());
        assertTrue(runtime.view().navigationTargets().contains(turn.navigationTargets().getFirst()));
        runtime.navigate(turn.navigationTargets().getFirst());
        assertEquals(1, navigations.get());
        runtime.send("Which bundle did we check?", false, false);
        assertTrue(backend.requests.getLast().input().stream().filter(ModelBackend.AssistantOutput.class::isInstance)
                .map(ModelBackend.AssistantOutput.class::cast).flatMap(output -> output.content().stream())
                .filter(ModelBackend.OutputText.class::isInstance).map(ModelBackend.OutputText.class::cast)
                .anyMatch(text -> text.text().equals(canonicalAnswer)));
        assertTrue(backend.requests.getLast().input().stream().noneMatch(item -> item instanceof ModelBackend.ToolResult));
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
                        @Override public Metadata metadata() { return new Metadata("native", List.of(), true, List.of()); }
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

    @Test
    void failedNewChatCreationSettlesLoadingAndCanRetryBeforeSending() throws Exception {
        AtomicBoolean deferStoreTasks = new AtomicBoolean();
        java.util.ArrayDeque<Runnable> storeTasks = new java.util.ArrayDeque<>();
        ConversationStore store = new ConversationStore(Files.createTempDirectory("packsage-new-chat-failure"), task -> {
            if (deferStoreTasks.get()) storeTasks.addLast(task);
            else task.run();
        });
        ChatContracts.Conversation saved = store.newConversation(WORLD).toCompletableFuture().join();
        store.appendTurn(WORLD, SCOPE, saved.id(), new ChatContracts.Turn(new ChatContracts.RequestId("saved-turn"),
                "Saved question", "Saved answer", List.of(), new ChatContracts.Context("", null, SCOPE), List.of(),
                List.of(), new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1, Instant.EPOCH))
                .toCompletableFuture().join();
        FakeBackend backend = new FakeBackend();
        AssistantRuntime runtime = new AssistantRuntime(store, backend, captureFactory(), DIRECT, DIRECT,
                current -> new KnowledgeService(List.of(), current), ChatContracts.Turn::tokenCount,
                prepared -> new ModelBackend.Request(prepared.submission().selectedModel().id(), "instructions", List.of(
                        new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent(
                                prepared.submission().question())))), List.of(), prepared.submission().searchMode(),
                        prepared.submission().requestId().value(), prepared.submission().outputBudget()),
                ignored -> 512, ignored -> { }, ignored -> { });
        runtime.enterWorld(WORLD, SCOPE);
        runtime.openSession(new ChatContracts.SessionSummary(WORLD, saved.id(), "Saved question", "Saved answer", Instant.EPOCH));
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), true, true, 16_000, 2_048));

        assertEquals(saved.id(), runtime.view().conversation().id());
        deferStoreTasks.set(true);
        runtime.newChat();
        assertFalse(storeTasks.isEmpty(), "new chat creation should be queued in the store executor");
        java.nio.file.Path latest = store.conversationPath(WORLD, saved.id()).resolveSibling("latest");
        Files.delete(latest);
        Files.createDirectory(latest);
        Files.writeString(latest.resolve("occupied"), "keep the target directory non-empty");
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();

        assertFalse(store.errors().isEmpty(), "the queued creation should fail while replacing the occupied latest marker");
        assertFalse(runtime.view().readOnly());
        assertFalse(runtime.view().activity().equals("Loading session..."));
        assertTrue(runtime.view().errors().stream().anyMatch(error -> error.contains("New chat")));
        String failedHolderId = runtime.view().conversation().id();

        Files.delete(latest.resolve("occupied"));
        Files.delete(latest);
        deferStoreTasks.set(false);
        runtime.newChat();
        assertFalse(runtime.view().readOnly());
        assertFalse(runtime.view().conversation().id().startsWith("pending"));
        assertNotEquals(failedHolderId, runtime.view().conversation().id());
        runtime.send("send after retry", false, false);

        assertEquals(1, backend.starts);
        assertEquals("send after retry", runtime.view().activeQuestion());
    }

    @Test
    void invalidatedNewChatCreationSettlesLoadingAndCanRetryBeforeSending() throws Exception {
        AtomicBoolean deferStoreTasks = new AtomicBoolean();
        java.util.ArrayDeque<Runnable> storeTasks = new java.util.ArrayDeque<>();
        ConversationStore store = new ConversationStore(Files.createTempDirectory("packsage-new-chat-invalidation"), task -> {
            if (deferStoreTasks.get()) storeTasks.addLast(task);
            else task.run();
        });
        ChatContracts.Conversation saved = store.newConversation(WORLD).toCompletableFuture().join();
        store.appendTurn(WORLD, SCOPE, saved.id(), new ChatContracts.Turn(new ChatContracts.RequestId("saved-turn"),
                "Saved question", "Saved answer", List.of(), new ChatContracts.Context("", null, SCOPE), List.of(),
                List.of(), new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1, Instant.EPOCH))
                .toCompletableFuture().join();
        FakeBackend backend = new FakeBackend();
        AssistantRuntime runtime = new AssistantRuntime(store, backend, captureFactory(), DIRECT, DIRECT,
                current -> new KnowledgeService(List.of(), current), ChatContracts.Turn::tokenCount,
                prepared -> new ModelBackend.Request(prepared.submission().selectedModel().id(), "instructions", List.of(
                        new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent(
                                prepared.submission().question())))), List.of(), prepared.submission().searchMode(),
                        prepared.submission().requestId().value(), prepared.submission().outputBudget()),
                ignored -> 512, ignored -> { }, ignored -> { });
        runtime.enterWorld(WORLD, SCOPE);
        runtime.openSession(new ChatContracts.SessionSummary(WORLD, saved.id(), "Saved question", "Saved answer", Instant.EPOCH));
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), true, true, 16_000, 2_048));

        assertEquals(saved.id(), runtime.view().conversation().id());
        deferStoreTasks.set(true);
        runtime.newChat();
        assertEquals(1, storeTasks.size());
        runtime.invalidate(new Evidence.Scope("world", "updated-resources", "team", "runtime", 1));

        assertFalse(runtime.view().readOnly());
        assertFalse(runtime.view().activity().equals("Loading session..."));
        deferStoreTasks.set(false);
        storeTasks.removeFirst().run();
        runtime.newChat();
        runtime.send("send after invalidation retry", false, false);

        assertEquals(1, backend.starts);
        assertEquals("send after invalidation retry", runtime.view().activeQuestion());
    }

    @Test
    void newChatDoesNotReuseAnEmptyForeignWorldHolder() throws Exception {
        ConversationStore store = new ConversationStore(Files.createTempDirectory("packsage-new-chat-foreign-empty"), DIRECT);
        ChatContracts.WorldIdentity foreignWorld = new ChatContracts.WorldIdentity("foreign-world", "Foreign World");
        ChatContracts.Conversation foreign = store.newConversation(foreignWorld).toCompletableFuture().join();
        FakeBackend backend = new FakeBackend();
        AssistantRuntime runtime = new AssistantRuntime(store, backend, captureFactory(), DIRECT, DIRECT,
                current -> new KnowledgeService(List.of(), current), ChatContracts.Turn::tokenCount,
                prepared -> new ModelBackend.Request(prepared.submission().selectedModel().id(), "instructions", List.of(
                        new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent(
                                prepared.submission().question())))), List.of(), prepared.submission().searchMode(),
                        prepared.submission().requestId().value(), prepared.submission().outputBudget()),
                ignored -> 512, ignored -> { }, ignored -> { });
        runtime.enterWorld(WORLD, SCOPE);
        runtime.openSession(new ChatContracts.SessionSummary(foreignWorld, foreign.id(), "New chat", "", Instant.EPOCH));
        assertTrue(runtime.view().readOnly());
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), true, true, 16_000, 2_048));

        runtime.newChat();

        ChatContracts.View view = runtime.view();
        var selectedField = AssistantRuntime.class.getDeclaredField("selected");
        selectedField.setAccessible(true);
        Object holder = selectedField.get(runtime);
        var idField = holder.getClass().getDeclaredField("id");
        idField.setAccessible(true);
        var worldField = holder.getClass().getDeclaredField("world");
        worldField.setAccessible(true);
        var controllerField = holder.getClass().getDeclaredField("controller");
        controllerField.setAccessible(true);
        assertEquals(WORLD.worldOrServer(), view.conversation().world().worldOrServer());
        assertEquals(WORLD, worldField.get(holder));
        assertEquals(view.conversation().id(), idField.get(holder));
        assertEquals(view.conversation().id(), ((RequestController) controllerField.get(holder)).view().conversation().id());
        assertFalse(view.readOnly());
        runtime.send("send in new current-world chat", false, false);
        assertEquals(1, backend.starts);
    }

    @Test
    void deletedHolderCannotConsumeARetainedCompletionSessionSnapshot() throws Exception {
        java.util.ArrayDeque<Runnable> storeTasks = new java.util.ArrayDeque<>();
        AtomicBoolean holdNextClientTask = new AtomicBoolean();
        java.util.ArrayDeque<Runnable> heldClientTasks = new java.util.ArrayDeque<>();
        ConversationStore store = new ConversationStore(Files.createTempDirectory("packsage-deleted-completion-snapshot"), storeTasks::addLast);
        var firstCreation = store.newConversation(WORLD);
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();
        ChatContracts.Conversation first = firstCreation.toCompletableFuture().join();
        var secondCreation = store.newConversation(WORLD);
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();
        ChatContracts.Conversation second = secondCreation.toCompletableFuture().join();
        ChatContracts.SessionSummary firstSummary = new ChatContracts.SessionSummary(WORLD, first.id(), "A", "", Instant.EPOCH);
        ChatContracts.SessionSummary secondSummary = new ChatContracts.SessionSummary(WORLD, second.id(), "B", "", Instant.EPOCH);
        FakeBackend backend = new FakeBackend();
        List<ChatContracts.Turn> notices = new ArrayList<>();
        AssistantRuntime runtime = new AssistantRuntime(store, backend, captureFactory(), DIRECT, task -> {
            if (holdNextClientTask.compareAndSet(true, false)) heldClientTasks.addLast(task);
            else task.run();
        }, current -> new KnowledgeService(List.of(), current), ChatContracts.Turn::tokenCount,
                prepared -> new ModelBackend.Request(prepared.submission().selectedModel().id(), "instructions", List.of(
                        new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent(
                                prepared.submission().question())))), List.of(), prepared.submission().searchMode(),
                        prepared.submission().requestId().value(), prepared.submission().outputBudget()),
                ignored -> 512, ignored -> { }, ignored -> { }, notices::add);
        runtime.enterWorld(WORLD, SCOPE);
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();
        runtime.openSession(firstSummary);
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), true, true, 16_000, 2_048));

        runtime.send("A completion before delete", false, false);
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), List.of(
                new ModelBackend.AssistantOutput("answer-a", ModelBackend.OutputStatus.COMPLETED,
                        ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText("Answer A", List.of())))), null));
        assertFalse(storeTasks.isEmpty());
        storeTasks.removeFirst().run();
        assertFalse(storeTasks.isEmpty());
        storeTasks.removeFirst().run();
        assertFalse(storeTasks.isEmpty());
        holdNextClientTask.set(true);
        storeTasks.removeFirst().run();
        assertEquals(1, heldClientTasks.size());

        runtime.openSession(secondSummary);
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();
        var deletion = runtime.deleteSession(firstSummary);
        while (!deletion.toCompletableFuture().isDone()) storeTasks.removeFirst().run();
        deletion.toCompletableFuture().join();
        heldClientTasks.removeFirst().run();

        assertTrue(notices.isEmpty());
        var sessions = runtime.sessions();
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();
        assertFalse(sessions.toCompletableFuture().join().stream().anyMatch(session -> session.id().equals(first.id())));
    }

    @Test
    void missingCompletionSessionSnapshotCanBeReopenedAndDurablyAcknowledgedWithNewerIdFencing() throws Exception {
        java.util.ArrayDeque<Runnable> storeTasks = new java.util.ArrayDeque<>();
        AtomicBoolean holdNextClientTask = new AtomicBoolean();
        java.util.ArrayDeque<Runnable> heldClientTasks = new java.util.ArrayDeque<>();
        ConversationStore store = new ConversationStore(Files.createTempDirectory("packsage-completion-list-recovery"), storeTasks::addLast);
        FakeBackend backend = new FakeBackend();
        AssistantRuntime runtime = new AssistantRuntime(store, backend, captureFactory(), DIRECT, task -> {
            if (holdNextClientTask.compareAndSet(true, false)) heldClientTasks.addLast(task);
            else task.run();
        }, current -> new KnowledgeService(List.of(), current), ChatContracts.Turn::tokenCount,
                prepared -> new ModelBackend.Request(prepared.submission().selectedModel().id(), "instructions", List.of(
                        new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent(
                                prepared.submission().question())))), List.of(), prepared.submission().searchMode(),
                        prepared.submission().requestId().value(), prepared.submission().outputBudget()),
                ignored -> 512, ignored -> { }, ignored -> { });
        runtime.enterWorld(WORLD, SCOPE);
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), true, true, 16_000, 2_048));

        runtime.send("first completion", false, false);
        String firstRequestId = backend.requests.getLast().requestId();
        String conversationId = runtime.view().conversation().id();
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), List.of(
                new ModelBackend.AssistantOutput("answer-one", ModelBackend.OutputStatus.COMPLETED,
                        ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText("Answer one", List.of())))), null));
        assertFalse(storeTasks.isEmpty());
        storeTasks.removeFirst().run();
        assertFalse(storeTasks.isEmpty());
        storeTasks.removeFirst().run();
        assertFalse(storeTasks.isEmpty());
        java.nio.file.Path conversationFile = store.conversationPath(WORLD, conversationId);
        java.nio.file.Path retainedConversationFile = conversationFile.resolveSibling(conversationFile.getFileName() + ".held");
        Files.move(conversationFile, retainedConversationFile);
        try {
            storeTasks.removeFirst().run();
        } finally {
            Files.move(retainedConversationFile, conversationFile);
        }

        var recoveredSessions = runtime.sessions();
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();
        ChatContracts.SessionSummary recovered = recoveredSessions.toCompletableFuture().join().stream()
                .filter(session -> session.id().equals(conversationId)).findFirst().orElseThrow();
        assertEquals(new ChatContracts.RequestId(firstRequestId), recovered.unreadRequestId());
        var screen = runtime.openScreen();
        screen.refresh(runtime.view());
        runtime.openSession(recovered);
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();
        screen.refresh(runtime.view());
        runtime.markRead(recovered, new ChatContracts.RequestId(firstRequestId));
        assertFalse(storeTasks.isEmpty(), "the reopened completion should queue a durable acknowledgement");
        holdNextClientTask.set(true);
        storeTasks.removeFirst().run();
        assertEquals(1, heldClientTasks.size());

        runtime.send("second completion", false, false);
        String secondRequestId = backend.requests.getLast().requestId();
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), List.of(
                new ModelBackend.AssistantOutput("answer-two", ModelBackend.OutputStatus.COMPLETED,
                        ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText("Answer two", List.of())))), null));
        assertFalse(storeTasks.isEmpty(), "the second completion turn should be persisted before its metadata");
        storeTasks.removeFirst().run();
        assertFalse(storeTasks.isEmpty(), "the second completion metadata should be persisted before its list snapshot");
        storeTasks.removeFirst().run();
        assertFalse(storeTasks.isEmpty(), "the second completion list snapshot should be queued");
        Files.move(conversationFile, retainedConversationFile);
        try {
            storeTasks.removeFirst().run();
        } finally {
            Files.move(retainedConversationFile, conversationFile);
        }

        var newerSessions = runtime.sessions();
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();
        List<ChatContracts.SessionSummary> newerValues = newerSessions.toCompletableFuture().join();
        ChatContracts.SessionSummary newer = newerValues.stream()
                .filter(session -> session.id().equals(conversationId)).findFirst().orElseThrow();
        assertEquals(new ChatContracts.RequestId(secondRequestId), newer.unreadRequestId());
        screen.refreshSessions(newerValues);
        screen.refresh(runtime.view());
        runtime.markRead(new ChatContracts.SessionSummary(newer.world(), newer.id(), newer.title(), newer.lastMessage(),
                        newer.updatedAt(), newer.active(), new ChatContracts.RequestId(firstRequestId)),
                new ChatContracts.RequestId(secondRequestId));
        assertTrue(storeTasks.isEmpty(), "a rendered summary with a different unread ID must not authorize the expected ID");
        heldClientTasks.removeFirst().run();
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();
        runtime.markRead(newer, new ChatContracts.RequestId(secondRequestId));
        assertFalse(storeTasks.isEmpty(), "the selected recovered completion should allow its durable acknowledgement without reopening");
        storeTasks.removeFirst().run();
        assertFalse(storeTasks.isEmpty(), "a successful acknowledgement should refresh sessions when the cached hint does not match");
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();
        var durableSessions = store.sessions();
        while (!storeTasks.isEmpty()) storeTasks.removeFirst().run();
        assertNull(durableSessions.toCompletableFuture().join().stream()
                .filter(session -> session.id().equals(conversationId)).findFirst().orElseThrow().unreadRequestId());
    }

    @Test
    void loadedAndActiveFollowupSessionTitleRemainsTheFirstQuestion() throws Exception {
        ConversationStore store = new ConversationStore(Files.createTempDirectory("packsage-first-question-title"), DIRECT);
        ChatContracts.Conversation saved = store.newConversation(WORLD).toCompletableFuture().join();
        store.appendTurn(WORLD, SCOPE, saved.id(), new ChatContracts.Turn(new ChatContracts.RequestId("first-turn"),
                "First question", "First answer", List.of(), new ChatContracts.Context("", null, SCOPE), List.of(),
                List.of(), new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1, Instant.EPOCH))
                .toCompletableFuture().join();
        store.appendTurn(WORLD, SCOPE, saved.id(), new ChatContracts.Turn(new ChatContracts.RequestId("second-turn"),
                "Second question", "Second answer", List.of(), new ChatContracts.Context("", null, SCOPE), List.of(),
                List.of(), new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1, Instant.EPOCH.plusSeconds(1)))
                .toCompletableFuture().join();
        FakeBackend backend = new FakeBackend();
        AssistantRuntime runtime = new AssistantRuntime(store, backend, captureFactory(), DIRECT, DIRECT,
                current -> new KnowledgeService(List.of(), current), ChatContracts.Turn::tokenCount,
                prepared -> new ModelBackend.Request(prepared.submission().selectedModel().id(), "instructions", List.of(
                        new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent(
                                prepared.submission().question())))), List.of(), prepared.submission().searchMode(),
                        prepared.submission().requestId().value(), prepared.submission().outputBudget()),
                ignored -> 512, ignored -> { }, ignored -> { });
        runtime.enterWorld(WORLD, SCOPE);
        runtime.openSession(new ChatContracts.SessionSummary(WORLD, saved.id(), "First question", "Second answer", Instant.EPOCH));
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "account", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), true, true, 16_000, 2_048));

        assertEquals("First question", runtime.sessions().toCompletableFuture().join().stream()
                .filter(session -> session.id().equals(saved.id())).findFirst().orElseThrow().title());

        runtime.send("Follow-up question", false, false);

        ChatContracts.SessionSummary active = runtime.sessions().toCompletableFuture().join().stream()
                .filter(session -> session.id().equals(saved.id())).findFirst().orElseThrow();
        assertTrue(active.active());
        assertEquals("First question", active.title());
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
        private final Map<String, java.util.function.Consumer<Event>> eventConsumers = new java.util.LinkedHashMap<>();
        private final Map<String, Boolean> cancelled = new java.util.LinkedHashMap<>();
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
            eventConsumers.put(request.requestId(), events);
            cancelled.put(request.requestId(), false);
            return new Exchange() {
                @Override public void submit(ToolResult result) { submissions++; results.add(result); }
                @Override public boolean steer(Message message) { steered.add(message); return true; }
                @Override public void cancel() { cancelled.put(request.requestId(), true); }
            };
        }

        private void emit(Event event) {
            events.accept(event);
        }

        private void emit(String requestId, Event event) {
            eventConsumers.get(requestId).accept(event);
        }
    }
}
