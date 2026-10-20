package dev.packsage.chat;

import dev.packsage.client.GameplayContextCapture;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.knowledge.KnowledgeService;
import dev.packsage.model.GroundingInstructions;
import dev.packsage.model.ModelBackend;
import dev.packsage.model.PackSageTools;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AssistantIntegrationTest {
    private static final Executor DIRECT = Runnable::run;
    private static final ChatContracts.WorldIdentity WORLD = new ChatContracts.WorldIdentity("world", "World");
    private static final ModelBackend.ModelInfo MODEL = new ModelBackend.ModelInfo("fixture-model", "Fixture Model",
            true, true, 16_000, 2_048);
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "resources", "team", "runtime", 1);

    @Test
    void completesGroundedMultiToolTurnPersistsItAndReplaysItWithFreshContext() throws Exception {
        var loaded = record(SCOPE, "native", "torch", Evidence.Authority.LOADED, "7 torches",
                new Evidence.NavigationTarget(SCOPE, "native", "torch", "torch-view"));
        var documentation = record(SCOPE, "guide", "torch", Evidence.Authority.INSTALLED_DOCUMENTATION,
                "4 torches", new Evidence.NavigationTarget(SCOPE, "guide", "torch", "guide-page"));
        var backend = new RuntimeBackend();
        var captures = new AtomicInteger();
        var storeRoot = Files.createTempDirectory("packsage-integration");
        var opened = new AtomicReference<String>();
        var runtime = new AssistantRuntime(new ConversationStore(storeRoot, DIRECT), backend,
                scope -> new GameplayContextCapture(
                        () -> new GameplayContextCapture.Observation("held-" + captures.incrementAndGet(), "target", "overworld",
                                "1,2,3", "plains", "1", "block"),
                        ignored -> new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1, 2, 3}, Instant.EPOCH),
                        DIRECT, scope), DIRECT, DIRECT,
                current -> new KnowledgeService(List.of(new Provider("native", List.of(loaded), false) {
                                    @Override public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
                                        return CompletableFuture.completedFuture(new Evidence.Result(List.of(record(scope, "native", "torch",
                                                Evidence.Authority.LOADED, "7 torches",
                                                new Evidence.NavigationTarget(scope, "native", "torch", "torch-view"))),
                                                Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null));
                                    }
                                    @Override public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
                                        opened.set(target.targetId());
                                        return new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED, target.targetId());
                                    }
                                },
                                new Provider("guide", List.of(documentation), false)), current),
                turn -> turn.tokenCount(), GroundingInstructions::build, ignored -> 512, ignored -> { }, ignored -> { });

        runtime.enterWorld(WORLD, new Evidence.Scope("world", "resources", "team", "runtime", 0));
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "fixture", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), MODEL.imageInput(), MODEL.hostedSearch(),
                MODEL.contextTokens(), MODEL.outputTokens()));

        runtime.send("How do I make a torch?", true, false);
        assertEquals(1, backend.requests.size());
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, "Checking installed evidence. ", null, List.of(), List.of(), null));
        assertEquals("Checking installed evidence. ", runtime.view().streamingAnswer());
        var searchCall = new ModelBackend.ToolCall("search-1", "search_recipe", "{\"filter\":{\"text\":\"torch\"}}");
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null, searchCall, List.of(), List.of(), null));
        assertEquals(1, backend.submitted.size());
        assertTrue(backend.submitted.get(0).serializedResult().contains("native:torch"));
        var readCall = new ModelBackend.ToolCall("read-1", "read_evidence", "{\"handles\":[\"native:torch\"]}");
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null, readCall, List.of(), List.of(), null));
        assertEquals(2, backend.submitted.size());
        assertEquals(backend.submitted.get(0).callId(), "search-1");
        assertEquals(backend.submitted.get(1).callId(), "read-1");
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA,
                "Use the loaded recipe; the installed guide differs. See the external version note.", null, List.of(), List.of(), null));
        var assistant = new ModelBackend.AssistantOutput("answer-1", ModelBackend.OutputStatus.COMPLETED,
                ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText(
                        "Use the loaded recipe; the installed guide differs. See the external version note.",
                        List.of(new ModelBackend.UrlCitation(0, 7, "Version note", "https://example.test/torch")) )));
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(),
                List.of(searchCall, backend.submitted.get(0), readCall, backend.submitted.get(1), assistant), null));

        var firstView = runtime.view();
        assertEquals("", firstView.streamingAnswer());
        assertEquals(ChatContracts.OutcomeStatus.COMPLETED, firstView.conversation().turns().get(0).outcome().status());
        assertEquals(1, firstView.conversation().turns().size());
        assertTrue(firstView.sources().stream().anyMatch(source -> source.providerId().equals("native")
                && source.authority() == Evidence.Authority.LOADED));
        assertTrue(firstView.sources().stream().anyMatch(source -> source.providerId().equals("guide")
                && source.authority() == Evidence.Authority.INSTALLED_DOCUMENTATION));
        assertTrue(firstView.sources().stream().anyMatch(source -> source.providerId().equals("web")
                && source.authority() == Evidence.Authority.EXTERNAL));
        assertEquals(3, firstView.navigationTargets().size());
        var localTarget = firstView.navigationTargets().stream().filter(target -> target.providerId().equals("native")).findFirst().orElseThrow();
        runtime.navigate(localTarget);
        assertEquals("torch-view", opened.get());
        String persistedText;
        try (var files = Files.walk(storeRoot)) {
            var persisted = files.filter(path -> path.getFileName().toString().endsWith(".jsonl")).findFirst().orElseThrow();
            persistedText = Files.readString(persisted);
        }
        assertTrue(persistedText.contains("toolCall") && persistedText.contains("toolResult") && persistedText.contains("answer-1"));

        runtime.send("What did the earlier evidence say?", false, false);
        assertEquals(2, backend.requests.size());
        var replayed = backend.requests.get(1).input();
        ModelBackend.AssistantOutput replayedAnswer = (ModelBackend.AssistantOutput) replayed.get(1);
        assertEquals(List.of(new ModelBackend.AssistantOutput("answer-1", ModelBackend.OutputStatus.COMPLETED,
                ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText(
                        "Checking installed evidence. Use the loaded recipe; the installed guide differs. See the external version note.", List.of())))),
                List.of(replayedAnswer));
        assertEquals("How do I make a torch?",
                ((ModelBackend.TextContent) ((ModelBackend.Message) replayed.get(0)).content().getFirst()).text());
        var currentMessage = replayed.stream().filter(ModelBackend.Message.class::isInstance).map(ModelBackend.Message.class::cast)
                .reduce((first, second) -> second).orElseThrow();
        assertEquals(ModelBackend.Role.USER, currentMessage.role());
        assertEquals(List.of(new ModelBackend.TextContent("What did the earlier evidence say?")), currentMessage.content());
        var background = (ModelBackend.Message) replayed.get(replayed.size() - 2);
        assertTrue(background.content().stream().anyMatch(content -> content instanceof ModelBackend.TextContent text
                && text.text().contains("held-2")));
        assertTrue(background.content().stream().noneMatch(ModelBackend.ImageContent.class::isInstance));
        assertEquals(2, runtime.view().context().scope().requestGeneration());
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(),
                List.of(new ModelBackend.AssistantOutput("answer-2", ModelBackend.OutputStatus.COMPLETED,
                        ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText("replayed", List.of())))), null));
        assertEquals(2, runtime.view().conversation().turns().size());
        opened.set(null);
        runtime.navigate(localTarget);
        assertEquals("torch-view", opened.get(), "An older recorded destination must be revalidated after a follow-up");
    }

    @Test
    void revalidatesRestoredLocalReferencesAndDiscardsResolutionAfterContextChanges() throws Exception {
        for (var scenario : List.of("restored", "new_chat", "invalidate", "world")) {
            var historicalScope = new Evidence.Scope("previous-session", "previous-resources", "previous-team", "previous-runtime", 9);
            var historicalTarget = new Evidence.NavigationTarget(historicalScope, "ftb_quests", "quest", "quest");
            var historicalRecord = record(historicalScope, "ftb_quests", "quest", Evidence.Authority.LOADED,
                    "Quest", historicalTarget);
            var store = new ConversationStore(Files.createTempDirectory("packsage-historical-navigation"), DIRECT);
            var conversation = store.newConversation(WORLD).toCompletableFuture().join();
            store.appendTurn(WORLD, historicalScope, conversation.id(), new ChatContracts.Turn(
                    new ChatContracts.RequestId("historical"), "Quest question", "Historical quest answer", List.of(),
                    new ChatContracts.Context("old context", null, historicalScope), List.of(historicalRecord.source()),
                    List.of(historicalTarget), new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1))
                    .toCompletableFuture().join();
            var pending = new CompletableFuture<Evidence.Result>();
            var queriedScope = new AtomicReference<Evidence.Scope>();
            var opened = new AtomicReference<Evidence.NavigationTarget>();
            var queries = new AtomicInteger();
            var runtime = new AssistantRuntime(store, new RuntimeBackend(), current -> new GameplayContextCapture(
                    () -> new GameplayContextCapture.Observation("held", "target", "overworld", "1,2,3", "plains", "1", "block"),
                    ignored -> new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1}, Instant.EPOCH), DIRECT, current),
                    DIRECT, DIRECT, current -> new KnowledgeService(List.of(new Provider("ftb_quests", List.of(), false) {
                        @Override public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
                            queries.incrementAndGet();
                            queriedScope.set(scope);
                            return pending;
                        }
                        @Override public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
                            assertEquals(scope, target.scope());
                            opened.set(target);
                            return new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED, "quest opened");
                        }
                    }), current),
                    turn -> turn.tokenCount(), GroundingInstructions::build, ignored -> 512, ignored -> { }, ignored -> { });
            runtime.enterWorld(WORLD, new Evidence.Scope("world", "resources", "team", "runtime", 0));
            assertEquals("Historical quest answer", runtime.view().conversation().turns().getFirst().answer());
            runtime.navigate(new Evidence.NavigationTarget(historicalScope, "ftb_quests", "unobserved", "quest"));
            assertEquals(0, queries.get());
            runtime.navigate(historicalTarget);
            assertEquals(1, queries.get());
            assertNull(opened.get());
            if (scenario.equals("new_chat")) runtime.newChat();
            if (scenario.equals("invalidate")) runtime.invalidate(new Evidence.Scope("world", "resources-2", "team", "runtime", 0));
            if (scenario.equals("world")) runtime.enterWorld(new ChatContracts.WorldIdentity("other", "Other World"),
                    new Evidence.Scope("other", "resources", "team", "runtime", 0));
            var refreshed = new Evidence.NavigationTarget(queriedScope.get(), "ftb_quests", "quest", "quest");
            pending.complete(new Evidence.Result(List.of(record(queriedScope.get(), "ftb_quests", "quest", Evidence.Authority.LOADED,
                    "Current quest", refreshed)), Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null));
            if (scenario.equals("restored")) {
                assertEquals(refreshed, opened.get());
                assertEquals("Historical quest answer", runtime.view().conversation().turns().getFirst().answer());
            } else {
                assertNull(opened.get(), "Late navigation must not open a page after " + scenario);
            }
        }
    }

    @Test
    void searchesIndependentProvidersAndReadsOnlyObservedScopedHandles() throws Exception {
        var loaded = record("native", "torch", Evidence.Authority.LOADED, "7 torches");
        var external = record("guide", "torch", Evidence.Authority.INSTALLED_DOCUMENTATION, "4 torches");
        var current = new AtomicReference<>(SCOPE);
        var service = new KnowledgeService(List.of(new Provider("native", List.of(loaded), false),
                new Provider("guide", List.of(external), false)), current::get);
        var tools = new PackSageTools(service::execute);

        var search = tools.execute(new ModelBackend.ToolCall("search", "search_recipe", "{\"filter\":{\"text\":\"torch\"}}"), SCOPE)
                .toCompletableFuture().get();
        assertEquals(List.of(loaded, external), search.records());
        var read = tools.execute(new ModelBackend.ToolCall("read", "read_evidence", "{\"handles\":[\"native:torch\",\"guide:torch\"]}"), SCOPE)
                .toCompletableFuture().get();
        assertEquals(List.of(loaded, external), read.records());
        assertTrue(tools.execute(new ModelBackend.ToolCall("bad", "read_evidence", "{\"handles\":[\"config:../../secret\"]}"), SCOPE)
                .toCompletableFuture().get().result().error());

        current.set(new Evidence.Scope("another-world", "resources", "team", "runtime", 2));
        assertTrue(tools.execute(new ModelBackend.ToolCall("old", "read_evidence", "{\"handles\":[\"native:torch\"]}"), SCOPE)
                .toCompletableFuture().get().result().error());
    }

    @Test
    void readProgressRequeriesObservedQuestAndPreservesProviderUnavailable() throws Exception {
        var initial = questRecord(false);
        var refreshed = questRecord(true);
        var current = new AtomicReference<Evidence.Result>(new Evidence.Result(List.of(refreshed), Evidence.Coverage.COMPLETE,
                Evidence.Outcome.COMPLETE, null));
        var queries = new ArrayList<Evidence.Query>();
        KnowledgeProvider provider = new Provider("ftb_quests", List.of(initial), false) {
            @Override public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
                queries.add(query);
                return query.operation() == Evidence.Operation.SEARCH
                        ? CompletableFuture.completedFuture(new Evidence.Result(List.of(initial), Evidence.Coverage.COMPLETE,
                        Evidence.Outcome.COMPLETE, null)) : CompletableFuture.completedFuture(current.get());
            }
        };
        var service = new KnowledgeService(List.of(provider), () -> SCOPE);

        service.execute(new PackSageTools.ReadRequest("search_document",
                new Evidence.JsonObject(Map.of("query", new Evidence.JsonString("quest"))), SCOPE))
                .toCompletableFuture().get();
        var progress = service.execute(new PackSageTools.ReadRequest("read_progress",
                new Evidence.JsonObject(Map.of("handles", new Evidence.JsonArray(List.of(new Evidence.JsonString("ftb_quests:quest"))))), SCOPE))
                .toCompletableFuture().get();
        assertEquals(List.of(refreshed), progress.records());
        assertEquals(Evidence.Operation.GET, queries.get(1).operation());
        assertEquals("quest", ((Evidence.JsonString) queries.get(1).arguments().values().get("id")).value());

        current.set(new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null));
        var unavailable = service.execute(new PackSageTools.ReadRequest("read_progress",
                new Evidence.JsonObject(Map.of("handles", new Evidence.JsonArray(List.of(new Evidence.JsonString("ftb_quests:quest"))))), SCOPE))
                .toCompletableFuture().get();
        assertEquals(Evidence.Coverage.PARTIAL, unavailable.coverage());
        assertEquals(Evidence.Outcome.PARTIAL, unavailable.outcome());
        assertEquals(1, unavailable.records().size());
        assertEquals(Evidence.Coverage.UNAVAILABLE, unavailable.records().get(0).source().coverage());
        assertEquals(Evidence.RecordKind.OTHER, unavailable.records().get(0).kind());
    }

    @Test
    void readProgressKeepsFreshHandlesWhenAnotherSelectedProviderIsUnavailable() throws Exception {
        var unavailableInitial = questRecord("quest-a", "ftb_quests", false);
        var freshInitial = questRecord("quest-b", "other_quests", false);
        var freshCurrent = questRecord("quest-b", "other_quests", true);
        var unavailableQueries = new ArrayList<Evidence.Query>();
        var freshQueries = new ArrayList<Evidence.Query>();
        KnowledgeProvider unavailableProvider = new Provider("ftb_quests", List.of(unavailableInitial), false) {
            @Override public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
                unavailableQueries.add(query);
                return query.operation() == Evidence.Operation.SEARCH
                        ? CompletableFuture.completedFuture(new Evidence.Result(List.of(unavailableInitial), Evidence.Coverage.COMPLETE,
                        Evidence.Outcome.COMPLETE, null))
                        : CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE,
                        Evidence.Outcome.UNKNOWN, null));
            }
        };
        KnowledgeProvider freshProvider = new Provider("other_quests", List.of(freshInitial), false) {
            @Override public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
                freshQueries.add(query);
                return query.operation() == Evidence.Operation.SEARCH
                        ? CompletableFuture.completedFuture(new Evidence.Result(List.of(freshInitial), Evidence.Coverage.COMPLETE,
                        Evidence.Outcome.COMPLETE, null))
                        : CompletableFuture.completedFuture(new Evidence.Result(List.of(freshCurrent), Evidence.Coverage.COMPLETE,
                        Evidence.Outcome.COMPLETE, null));
            }
        };
        var service = new KnowledgeService(List.of(unavailableProvider, freshProvider), () -> SCOPE);

        service.execute(new PackSageTools.ReadRequest("search_document",
                new Evidence.JsonObject(Map.of("query", new Evidence.JsonString("quest"))), SCOPE))
                .toCompletableFuture().get();
        var progress = service.execute(new PackSageTools.ReadRequest("read_progress",
                new Evidence.JsonObject(Map.of("handles", new Evidence.JsonArray(List.of(
                        new Evidence.JsonString("ftb_quests:quest-a"), new Evidence.JsonString("other_quests:quest-b"))))), SCOPE))
                .toCompletableFuture().get();

        assertEquals(Evidence.Coverage.PARTIAL, progress.coverage());
        assertEquals(Evidence.Outcome.PARTIAL, progress.outcome());
        assertTrue(progress.records().contains(freshCurrent));
        assertFalse(progress.records().contains(unavailableInitial));
        var failure = progress.records().stream().filter(record -> record.source().providerId().equals("ftb_quests")).findFirst().orElseThrow();
        assertEquals(Evidence.RecordKind.OTHER, failure.kind());
        assertEquals(Evidence.Coverage.UNAVAILABLE, failure.source().coverage());
        assertEquals("quest-a", ((Evidence.JsonString) failure.payload().values().get("handle")).value());
        assertEquals(Evidence.Operation.GET, unavailableQueries.get(1).operation());
        assertEquals(Evidence.Operation.GET, freshQueries.get(1).operation());
    }

    @Test
    void preservesUnaffectedEvidenceWhenAnotherProviderFails() throws Exception {
        var loaded = record("native", "torch", Evidence.Authority.LOADED, "7 torches");
        var service = new KnowledgeService(List.of(new Provider("native", List.of(loaded), false),
                new Provider("broken", List.of(), true)), () -> SCOPE);

        var response = new PackSageTools(service::execute).execute(new ModelBackend.ToolCall("search", "search_recipe", "{\"filter\":{\"text\":\"torch\"}}"), SCOPE)
                .toCompletableFuture().get();
        assertTrue(response.records().contains(loaded));
        assertTrue(response.result().serializedResult().contains("broken"));
        assertTrue(response.result().serializedResult().contains("PARTIAL"));
    }

    @Test
    void rejectsLateEvidenceAfterWorldChange() throws Exception {
        var pending = new CompletableFuture<Evidence.Result>();
        var current = new AtomicReference<>(SCOPE);
        KnowledgeProvider provider = new Provider("native", List.of(), false) {
            @Override public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) { return pending; }
        };
        var service = new KnowledgeService(List.of(provider), current::get);
        var request = service.execute(new PackSageTools.ReadRequest("search_recipe", new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("torch"))), SCOPE));
        current.set(new Evidence.Scope("another-world", "resources", "team", "runtime", 2));
        pending.complete(new Evidence.Result(List.of(record("native", "torch", Evidence.Authority.LOADED, "7 torches")),
                Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null));
        assertThrows(java.util.concurrent.ExecutionException.class, () -> request.toCompletableFuture().get());
    }

    @Test
    void accountAndWorldTransitionsCancelEveryConversationBeforeLateCallbacks() throws Exception {
        for (String transition : List.of("sign-out", "account-replacement", "disconnect", "scope-invalidation", "world-replacement")) {
            var backend = new RuntimeBackend();
            var notices = new ArrayList<ChatContracts.Turn>();
            var providerQueries = new AtomicInteger();
            var store = new ConversationStore(Files.createTempDirectory("packsage-lifecycle"), DIRECT);
            var runtime = new AssistantRuntime(store, backend, current -> new GameplayContextCapture(
                    () -> new GameplayContextCapture.Observation("held", "target", "overworld", "1,2,3", "plains", "1", "block"),
                    ignored -> new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1}, Instant.EPOCH), DIRECT, current),
                    DIRECT, DIRECT, current -> new KnowledgeService(List.of(new Provider("native", List.of(), false) {
                        @Override public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
                            providerQueries.incrementAndGet();
                            return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE,
                                    Evidence.Outcome.COMPLETE, null));
                        }
                    }), current),
                    turn -> turn.tokenCount(), GroundingInstructions::build, ignored -> 512, ignored -> { }, ignored -> { }, notices::add);
            runtime.enterWorld(WORLD, new Evidence.Scope("world", "resources", "team", "runtime", 0));
            runtime.signIn();
            backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "fixture", null));
            backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), MODEL.imageInput(), MODEL.hostedSearch(),
                    MODEL.contextTokens(), MODEL.outputTokens()));

            runtime.send("Question A", false, false);
            backend.listeners.get(0).accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA,
                    "partial A", null, List.of(), List.of(), null));
            runtime.newChat();
            runtime.send("Question B", false, false);
            backend.listeners.get(1).accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA,
                    "partial B", null, List.of(), List.of(), null));
            assertEquals(2, backend.requests.size(), transition);
            var toolCall = new ModelBackend.ToolCall("late-tool", "search_recipe",
                    "{\"filter\":{\"provider\":\"native\",\"text\":\"torch\"}}");
            var lateCompletion = new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(),
                    List.of(new ModelBackend.AssistantOutput("late-answer", ModelBackend.OutputStatus.COMPLETED,
                            ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText("late", List.of())))), null);
            Runnable lateCallbacks = () -> {
                assertTrue(backend.cancelled.get(0).get(), "A must be cancelled before " + transition + " reaches the backend");
                assertTrue(backend.cancelled.get(1).get(), "B must be cancelled before " + transition + " reaches the backend");
                for (int request = 0; request < 2; request++) {
                    backend.listeners.get(request).accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA,
                            " late text", null, List.of(), List.of(), null));
                    backend.listeners.get(request).accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL,
                            null, toolCall, List.of(), List.of(), null));
                    backend.listeners.get(request).accept(lateCompletion);
                }
            };
            if (transition.equals("sign-out") || transition.equals("account-replacement")) {
                backend.duringSignOut = lateCallbacks;
                runtime.signOut();
                if (transition.equals("account-replacement")) {
                    backend.signIn = new CompletableFuture<>();
                    runtime.signIn();
                    backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "replacement", null));
                    assertEquals(ChatContracts.SignInState.SIGNED_IN, runtime.view().signIn(), transition);
                    lateCallbacks.run();
                }
            } else {
                if (transition.equals("disconnect")) runtime.disconnect();
                else if (transition.equals("scope-invalidation")) {
                    runtime.invalidate(new Evidence.Scope("world", "resources-2", "team", "runtime", 1));
                } else {
                    runtime.enterWorld(new ChatContracts.WorldIdentity("world-next", "Next World"),
                            new Evidence.Scope("world-next", "resources", "team", "runtime", 1));
                }
                lateCallbacks.run();
            }

            assertEquals(0, providerQueries.get(), transition + " must reject late client tool calls");
            assertTrue(notices.isEmpty(), transition + " must not issue completion notices");
            var summaries = store.sessions().toCompletableFuture().join().stream()
                    .filter(summary -> summary.world().worldOrServer().equals(WORLD.worldOrServer())).toList();
            assertEquals(2, summaries.size(), transition);
            assertTrue(summaries.stream().allMatch(summary -> summary.unreadRequestId() == null), transition);
            for (int request = 0; request < 2; request++) {
                String question = request == 0 ? "Question A" : "Question B";
                var summary = summaries.stream().filter(value -> value.title().equals(question)).findFirst().orElseThrow();
                var conversation = store.openConversation(WORLD, summary.id())
                        .toCompletableFuture().join();
                assertEquals(1, conversation.turns().size(), transition);
                var turn = conversation.turns().getFirst();
                assertEquals(backend.requests.get(request).requestId(), turn.requestId().value(), transition);
                assertEquals(ChatContracts.OutcomeStatus.CANCELLED, turn.outcome().status(), transition);
                assertEquals("partial " + (request == 0 ? "A" : "B"), turn.answer(), transition);
            }
        }
    }

    @Test
    void completionNoticesRequireCurrentScopeButSurviveNewChatSelection() throws Exception {
        for (String transition : List.of("resource-invalidation", "disconnect-reenter", "new-chat")) {
            var clientTasks = new ArrayDeque<Runnable>();
            var notices = new ArrayList<ChatContracts.Turn>();
            var backend = new RuntimeBackend();
            var store = new ConversationStore(Files.createTempDirectory("packsage-notice-scope"), DIRECT);
            var runtime = new AssistantRuntime(store, backend, current -> new GameplayContextCapture(
                    () -> new GameplayContextCapture.Observation("held", "target", "overworld", "1,2,3", "plains", "1", "block"),
                    ignored -> new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1}, Instant.EPOCH), DIRECT, current),
                    DIRECT, clientTasks::addLast, current -> new KnowledgeService(List.of(), current), ChatContracts.Turn::tokenCount,
                    GroundingInstructions::build, ignored -> 512, ignored -> { }, ignored -> { }, notices::add);
            runtime.enterWorld(WORLD, new Evidence.Scope("world-session", "resources", "team", "runtime", 0));
            while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();
            backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), MODEL.imageInput(), MODEL.hostedSearch(),
                    MODEL.contextTokens(), MODEL.outputTokens()));
            runtime.signIn();
            backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "fixture", null));
            while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();

            runtime.send("Question A", false, false);
            String conversationA = runtime.view().conversation().id();
            while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();
            runtime.newChat();
            while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();
            runtime.send("Question B", false, false);
            String conversationB = runtime.view().conversation().id();
            while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();
            assertEquals(2, backend.requests.size(), transition);
            var requestA = new ChatContracts.RequestId(backend.requests.get(0).requestId());
            var requestB = new ChatContracts.RequestId(backend.requests.get(1).requestId());
            for (int request = 0; request < 2; request++) {
                backend.listeners.get(request).accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA,
                        "Completed " + (request == 0 ? "A" : "B"), null, List.of(), List.of(), null));
                backend.listeners.get(request).accept(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(),
                        List.of(new ModelBackend.AssistantOutput("completed-" + request, ModelBackend.OutputStatus.COMPLETED,
                                ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText(
                                "Completed " + (request == 0 ? "A" : "B"), List.of())))), null));
            }
            assertEquals(0, notices.size(), "completion notices are still queued before the lifecycle transition");

            if (transition.equals("resource-invalidation")) {
                runtime.invalidate(new Evidence.Scope("world-session", "resources-reloaded", "team", "runtime", 1));
            } else if (transition.equals("disconnect-reenter")) {
                runtime.disconnect();
                runtime.enterWorld(WORLD, new Evidence.Scope("reconnected-session", "resources", "team", "runtime", 1));
            } else {
                runtime.newChat();
            }
            while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();

            assertEquals(transition.equals("new-chat") ? 2 : 0, notices.size(), transition);
            if (transition.equals("new-chat")) {
                assertTrue(notices.stream().anyMatch(turn -> turn.requestId().equals(requestA)));
                assertTrue(notices.stream().anyMatch(turn -> turn.requestId().equals(requestB)));
            }
            for (int request = 0; request < 2; request++) {
                String conversation = request == 0 ? conversationA : conversationB;
                var expectedRequest = request == 0 ? requestA : requestB;
                var completed = store.openConversation(WORLD, conversation).toCompletableFuture().join().turns().getFirst();
                assertEquals(expectedRequest, completed.requestId(), transition);
                assertEquals(ChatContracts.OutcomeStatus.COMPLETED, completed.outcome().status(), transition);
                assertEquals(expectedRequest, store.sessions().toCompletableFuture().join().stream()
                        .filter(summary -> summary.id().equals(conversation)).findFirst().orElseThrow().unreadRequestId(), transition);
            }
            runtime.sessions().toCompletableFuture().join();
            runtime.sessions().toCompletableFuture().join();
            assertEquals(transition.equals("new-chat") ? 2 : 0, notices.size(),
                    transition + " repeated summary refresh does not recreate or duplicate notices");
        }
    }

    @Test
    void cancellingOneQueuedScreenshotCaptureLeavesTheOtherHolderCaptureRunning() throws Exception {
        var backend = new RuntimeBackend();
        var store = new ConversationStore(Files.createTempDirectory("packsage-independent-captures"), DIRECT);
        var captureTasks = new ArrayDeque<Runnable>();
        var captures = new ArrayList<GameplayContextCapture>();
        var capturedFrames = new ArrayList<ChatContracts.RequestCapture>();
        var runtime = new AssistantRuntime(store, backend, current -> {
            int captureIndex = captures.size();
            var capture = new GameplayContextCapture(
                    () -> new GameplayContextCapture.Observation("held-" + captureIndex, "target", "overworld", "1,2,3", "plains", "1", "block"),
                    request -> {
                        capturedFrames.add(request);
                        return new GameplayContextCapture.CapturedFrame("image/png", new byte[]{(byte) (captureIndex + 1)}, Instant.EPOCH);
                    }, captureTasks::addLast, current);
            captures.add(capture);
            return capture;
        }, DIRECT, DIRECT, current -> new KnowledgeService(List.of(), current), ChatContracts.Turn::tokenCount,
                GroundingInstructions::build, ignored -> 512, ignored -> { }, ignored -> { });
        runtime.enterWorld(WORLD, new Evidence.Scope("world", "resources", "team", "runtime", 0));
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "fixture", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), MODEL.imageInput(), MODEL.hostedSearch(),
                MODEL.contextTokens(), MODEL.outputTokens()));
        runtime.openScreen();

        runtime.send("Question A", true, false);
        String conversationA = runtime.view().conversation().id();
        runtime.newChat();
        runtime.send("Question B", true, false);
        String conversationB = runtime.view().conversation().id();
        assertNotEquals(conversationA, conversationB);
        assertEquals(2, captureTasks.size(), "both holders have queued capture work before either frame callback runs");
        assertEquals(2, captures.size());
        assertNotSame(captures.get(0), captures.get(1));

        var sessionA = store.sessions().toCompletableFuture().join().stream()
                .filter(summary -> summary.id().equals(conversationA)).findFirst().orElseThrow();
        runtime.openSession(sessionA);
        runtime.stop();
        assertFalse(runtime.view().active());
        while (!captureTasks.isEmpty()) captureTasks.removeFirst().run();

        assertEquals(1, capturedFrames.size(), "A's cancelled queued capture cannot invoke its frame callback");
        assertEquals(1, backend.requests.size(), "only B reaches the backend after capture work drains");
        assertEquals(backend.requests.getFirst().requestId(), capturedFrames.getFirst().requestId().value());
        var cancelledA = store.openConversation(WORLD, conversationA).toCompletableFuture().join().turns().getFirst();
        assertEquals(ChatContracts.OutcomeStatus.CANCELLED, cancelledA.outcome().status());
        var sessionB = store.sessions().toCompletableFuture().join().stream()
                .filter(summary -> summary.id().equals(conversationB)).findFirst().orElseThrow();
        runtime.openSession(sessionB);
        assertTrue(runtime.view().active(), "B's exchange remains active after cancelling A's capture");
        assertFalse(backend.cancelled.getFirst().get());
    }

    @Test
    void steeringStoppingAndResendingAffectsOnlySelectedConversation() throws Exception {
        var backend = new RuntimeBackend();
        var store = new ConversationStore(Files.createTempDirectory("packsage-independent-controls"), DIRECT);
        var runtime = new AssistantRuntime(store, backend, current -> new GameplayContextCapture(
                () -> new GameplayContextCapture.Observation("held", "target", "overworld", "1,2,3", "plains", "1", "block"),
                ignored -> new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1}, Instant.EPOCH), DIRECT, current),
                DIRECT, DIRECT, current -> new KnowledgeService(List.of(), current), ChatContracts.Turn::tokenCount,
                GroundingInstructions::build, ignored -> 512, ignored -> { }, ignored -> { });
        runtime.enterWorld(WORLD, new Evidence.Scope("world", "resources", "team", "runtime", 0));
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "fixture", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), MODEL.imageInput(), MODEL.hostedSearch(),
                MODEL.contextTokens(), MODEL.outputTokens()));
        runtime.openScreen();

        runtime.send("Question A", false, false);
        String conversationA = runtime.view().conversation().id();
        runtime.newChat();
        runtime.send("Question B", false, false);
        String conversationB = runtime.view().conversation().id();
        var sessions = store.sessions().toCompletableFuture().join();
        var sessionA = sessions.stream().filter(summary -> summary.id().equals(conversationA)).findFirst().orElseThrow();
        var sessionB = sessions.stream().filter(summary -> summary.id().equals(conversationB)).findFirst().orElseThrow();
        var requestA = new ChatContracts.RequestId(backend.requests.get(0).requestId());
        var requestB = new ChatContracts.RequestId(backend.requests.get(1).requestId());

        runtime.openSession(sessionA);
        assertTrue(runtime.steer("Steer A"));
        assertEquals(1, backend.steered.get(0).size());
        assertTrue(backend.steered.get(1).isEmpty(), "steering A does not send follow-up input to B");
        assertEquals("Steer A", ((ModelBackend.TextContent) backend.steered.get(0).getFirst().content().getFirst()).text());
        runtime.stop();
        assertTrue(backend.cancelled.get(0).get());
        assertFalse(backend.cancelled.get(1).get(), "stopping A leaves B active");
        var stoppedA = store.openConversation(WORLD, conversationA).toCompletableFuture().join().turns().getFirst();
        assertEquals(ChatContracts.OutcomeStatus.CANCELLED, stoppedA.outcome().status());
        assertEquals("Steer A", stoppedA.steeringMessages().getFirst().text());

        runtime.resend(requestA, "Replacement A", false, false);
        assertEquals(3, backend.requests.size(), "resending A starts its own replacement while B keeps running");
        assertFalse(backend.cancelled.get(1).get());
        runtime.openSession(sessionB);
        assertTrue(runtime.view().active());
        runtime.resend(requestB, "Rejected resend B", false, false);
        assertEquals(3, backend.requests.size(), "resend is rejected while selected B is active");
        assertTrue(runtime.view().errors().stream().anyMatch(error -> error.contains("Only the latest exchange can be resent")));
        assertFalse(backend.cancelled.get(1).get());

        backend.listeners.get(2).accept(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(),
                List.of(new ModelBackend.AssistantOutput("replacement-output", ModelBackend.OutputStatus.COMPLETED,
                        ModelBackend.OutputPhase.FINAL_ANSWER,
                        List.of(new ModelBackend.OutputText("Replacement answer", List.of())))), null));
        var replacedA = store.openConversation(WORLD, conversationA).toCompletableFuture().join();
        assertEquals(1, replacedA.turns().size(), "A's resend replaces only A's latest cancelled turn");
        assertEquals(backend.requests.get(2).requestId(), replacedA.turns().getFirst().requestId().value());
        assertEquals("Replacement A", replacedA.turns().getFirst().question());
        assertFalse(backend.cancelled.get(1).get(), "B remains active after A's replacement completes");
        assertTrue(runtime.view().active());
    }

    @Test
    void delayedActivePublicationCannotLoseUnreadMarkerBeforeReadAcknowledgement() throws Exception {
        var storeRoot = Files.createTempDirectory("packsage-delayed-unread-publication");
        var clientTasks = new ArrayDeque<Runnable>();
        var backend = new RuntimeBackend();
        var store = new ConversationStore(storeRoot, DIRECT);
        var runtime = new AssistantRuntime(store, backend, current -> new GameplayContextCapture(
                () -> new GameplayContextCapture.Observation("held", "target", "overworld", "1,2,3", "plains", "1", "block"),
                ignored -> new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1}, Instant.EPOCH), DIRECT, current),
                DIRECT, clientTasks::addLast, current -> new KnowledgeService(List.of(), current), ChatContracts.Turn::tokenCount,
                GroundingInstructions::build, ignored -> 512, ignored -> { }, ignored -> { });
        runtime.enterWorld(WORLD, new Evidence.Scope("world", "resources", "team", "runtime", 0));
        while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "fixture", null));
        while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), MODEL.imageInput(), MODEL.hostedSearch(),
                MODEL.contextTokens(), MODEL.outputTokens()));
        while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();
        runtime.openScreen();
        runtime.send("Question", false, false);
        String conversationId = runtime.view().conversation().id();
        ChatContracts.View delayedActiveView = runtime.view();

        backend.listeners.getFirst().accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, "Answer", null,
                List.of(), List.of(), null));
        backend.listeners.getFirst().accept(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(),
                List.of(new ModelBackend.AssistantOutput("final-answer", ModelBackend.OutputStatus.COMPLETED,
                        ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText("Answer", List.of())))), null));
        while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();
        ChatContracts.RequestId completion = new ChatContracts.RequestId(backend.requests.getFirst().requestId());
        var completedSummary = store.sessions().toCompletableFuture().join().stream()
                .filter(summary -> summary.id().equals(conversationId)).findFirst().orElseThrow();
        assertEquals(completion, completedSummary.unreadRequestId());

        var selectedField = AssistantRuntime.class.getDeclaredField("selected");
        selectedField.setAccessible(true);
        Object holder = selectedField.get(runtime);
        var publish = AssistantRuntime.class.getDeclaredMethod("publishHolderView", holder.getClass(), ChatContracts.View.class);
        publish.setAccessible(true);
        publish.invoke(runtime, holder, delayedActiveView);
        while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();
        runtime.markRead(completedSummary, completion);
        while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();

        assertFalse(runtime.view().active(), "the controller remains terminal after the delayed active callback");
        assertEquals(completion, runtime.view().conversation().turns().getLast().requestId());
        assertAll(
                () -> assertFalse(runtime.sessions().toCompletableFuture().join().stream()
                        .filter(summary -> summary.id().equals(conversationId)).findFirst().orElseThrow().active(),
                        "the runtime catalog reflects terminal activity after the delayed active callback"),
                () -> assertNull(store.sessions().toCompletableFuture().join().stream()
                        .filter(summary -> summary.id().equals(conversationId)).findFirst().orElseThrow().unreadRequestId(),
                        "the exact completed turn can still be acknowledged in persistent metadata"));
    }

    @Test
    void markReadRequiresSelectedLoadedUnreadAndAnOldAcknowledgementCannotClearANewerCompletion() throws Exception {
        var storeRoot = Files.createTempDirectory("packsage-read-race");
        var setupStore = new ConversationStore(storeRoot, DIRECT);
        var completedConversation = setupStore.newConversation(WORLD).toCompletableFuture().join();
        var firstCompletion = new ChatContracts.RequestId("completion-a1");
        setupStore.appendTurn(WORLD, SCOPE, completedConversation.id(), new ChatContracts.Turn(firstCompletion,
                "Question A1", "Answer A1", List.of(), new ChatContracts.Context("context", null, SCOPE), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1)).toCompletableFuture().join();
        setupStore.recordCompletion(WORLD, completedConversation.id(), firstCompletion).toCompletableFuture().join();
        setupStore.newConversation(WORLD).toCompletableFuture().join();

        var clientTasks = new ArrayDeque<Runnable>();
        var backend = new RuntimeBackend();
        var notices = new ArrayList<ChatContracts.Turn>();
        var store = new ConversationStore(storeRoot, DIRECT);
        var runtime = new AssistantRuntime(store, backend, current -> new GameplayContextCapture(
                () -> new GameplayContextCapture.Observation("held", "target", "overworld", "1,2,3", "plains", "1", "block"),
                ignored -> new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1}, Instant.EPOCH), DIRECT, current),
                DIRECT, clientTasks::addLast, current -> new KnowledgeService(List.of(), current), ChatContracts.Turn::tokenCount,
                GroundingInstructions::build, ignored -> 512, ignored -> { }, ignored -> { }, notices::add);
        runtime.enterWorld(WORLD, new Evidence.Scope("world", "resources", "team", "runtime", 0));
        while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "fixture", null));
        while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), MODEL.imageInput(), MODEL.hostedSearch(),
                MODEL.contextTokens(), MODEL.outputTokens()));
        while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();
        runtime.openScreen();

        var firstSummary = store.sessions().toCompletableFuture().join().stream()
                .filter(summary -> summary.id().equals(completedConversation.id())).findFirst().orElseThrow();
        assertEquals(firstCompletion, firstSummary.unreadRequestId());
        runtime.markRead(firstSummary, firstCompletion);
        assertEquals(firstCompletion, store.sessions().toCompletableFuture().join().stream()
                .filter(summary -> summary.id().equals(completedConversation.id())).findFirst().orElseThrow().unreadRequestId(),
                "an unselected summary cannot be acknowledged");

        runtime.openSession(firstSummary);
        runtime.markRead(firstSummary, firstCompletion);
        while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();
        assertEquals(firstSummary.id(), runtime.view().conversation().id());
        assertTrue(runtime.view().conversation().turns().stream().anyMatch(turn -> turn.requestId().equals(firstCompletion)
                && turn.outcome().status() == ChatContracts.OutcomeStatus.COMPLETED));
        runtime.markRead(firstSummary, new ChatContracts.RequestId("not-the-completion"));
        assertEquals(firstCompletion, store.sessions().toCompletableFuture().join().stream()
                .filter(summary -> summary.id().equals(completedConversation.id())).findFirst().orElseThrow().unreadRequestId(),
                "loading and mismatched completion acknowledgements are ignored");

        var unseenNewerCompletion = new ChatContracts.RequestId("unseen-newer-completion");
        runtime.markRead(new ChatContracts.SessionSummary(WORLD, firstSummary.id(), firstSummary.title(), firstSummary.lastMessage(),
                firstSummary.updatedAt(), false, unseenNewerCompletion), unseenNewerCompletion);
        assertEquals(firstCompletion, store.sessions().toCompletableFuture().join().stream()
                .filter(summary -> summary.id().equals(completedConversation.id())).findFirst().orElseThrow().unreadRequestId(),
                "a newer summary-only completion absent from the displayed turn list cannot acknowledge A1");

        runtime.markRead(firstSummary, firstCompletion);
        assertEquals(1, clientTasks.size(), "one read acknowledgement is in flight");
        runtime.markRead(firstSummary, firstCompletion);
        assertEquals(1, clientTasks.size(), "repeated read acknowledgements coalesce");
        Runnable delayedFirstAcknowledgement = clientTasks.removeFirst();

        runtime.send("Question A2", false, false);
        assertEquals(1, backend.requests.size());
        while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();
        runtime.newChat();
        while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();
        assertFalse(backend.cancelled.get(0).get(), "selection change leaves A2 running");
        backend.listeners.get(0).accept(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(),
                List.of(new ModelBackend.AssistantOutput("answer-a2", ModelBackend.OutputStatus.COMPLETED,
                        ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText("Answer A2", List.of())))), null));
        while (!clientTasks.isEmpty()) clientTasks.removeLast().run();

        var secondCompletion = new ChatContracts.RequestId(backend.requests.get(0).requestId());
        assertEquals(secondCompletion, store.sessions().toCompletableFuture().join().stream()
                .filter(summary -> summary.id().equals(completedConversation.id())).findFirst().orElseThrow().unreadRequestId());
        assertEquals(1, notices.size(), "A2 completion reports once while another conversation is selected");
        delayedFirstAcknowledgement.run();
        assertEquals(secondCompletion, store.sessions().toCompletableFuture().join().stream()
                .filter(summary -> summary.id().equals(completedConversation.id())).findFirst().orElseThrow().unreadRequestId(),
                "the stale A1 acknowledgement cannot clear A2's marker");
        assertEquals(secondCompletion, new ConversationStore(storeRoot, DIRECT).sessions().toCompletableFuture().join().stream()
                .filter(summary -> summary.id().equals(completedConversation.id())).findFirst().orElseThrow().unreadRequestId(),
                "the newer unread marker survives store reconstruction");
        runtime.sessions().toCompletableFuture().join();
        runtime.sessions().toCompletableFuture().join();
        assertEquals(1, notices.size(), "repeated session refresh does not duplicate completion notice");

        var secondSummary = runtime.sessions().toCompletableFuture().join().stream()
                .filter(summary -> summary.id().equals(completedConversation.id())).findFirst().orElseThrow();
        runtime.openSession(secondSummary);
        while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();
        runtime.markRead(secondSummary, secondCompletion);
        Runnable delayedSecondAcknowledgement = clientTasks.removeFirst();
        var nextWorld = new ChatContracts.WorldIdentity("next-world", "Next World");
        runtime.enterWorld(nextWorld, new Evidence.Scope("next-world", "resources", "team", "runtime", 0));
        while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();
        delayedSecondAcknowledgement.run();
        assertEquals(nextWorld.worldOrServer(), runtime.view().conversation().world().worldOrServer(),
                "a delayed read acknowledgement cannot republish the prior world's chat");
        while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();
        assertNull(new ConversationStore(storeRoot, DIRECT).sessions().toCompletableFuture().join().stream()
                .filter(summary -> summary.id().equals(completedConversation.id())).findFirst().orElseThrow().unreadRequestId());
        assertEquals(1, notices.size());
    }

    @Test
    void failedReadPersistenceKeepsUnreadAndRowReopenAllowsRetry() throws Exception {
        for (String retryPath : List.of("row-reopen", "screen-reopen")) {
            var storeRoot = Files.createTempDirectory("packsage-read-retry");
            var setupStore = new ConversationStore(storeRoot, DIRECT);
            var conversation = setupStore.newConversation(WORLD).toCompletableFuture().join();
            var completion = new ChatContracts.RequestId("completion-read-retry");
            setupStore.appendTurn(WORLD, SCOPE, conversation.id(), new ChatContracts.Turn(completion, "Question", "Answer", List.of(),
                    new ChatContracts.Context("context", null, SCOPE), List.of(),
                    new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1)).toCompletableFuture().join();
            setupStore.recordCompletion(WORLD, conversation.id(), completion).toCompletableFuture().join();
            setupStore.newConversation(WORLD).toCompletableFuture().join();
            var backend = new RuntimeBackend();
            var store = new ConversationStore(storeRoot, DIRECT);
            var runtime = new AssistantRuntime(store, backend, current -> new GameplayContextCapture(
                    () -> new GameplayContextCapture.Observation("held", "target", "overworld", "1,2,3", "plains", "1", "block"),
                    ignored -> new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1}, Instant.EPOCH), DIRECT, current),
                    DIRECT, DIRECT, current -> new KnowledgeService(List.of(), current), ChatContracts.Turn::tokenCount,
                    GroundingInstructions::build, ignored -> 512, ignored -> { }, ignored -> { });
            runtime.enterWorld(WORLD, new Evidence.Scope("world", "resources", "team", "runtime", 0));
            runtime.signIn();
            backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "fixture", null));
            backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), MODEL.imageInput(), MODEL.hostedSearch(),
                    MODEL.contextTokens(), MODEL.outputTokens()));
            runtime.openScreen();
            var summary = store.sessions().toCompletableFuture().join().stream()
                    .filter(value -> value.id().equals(conversation.id())).findFirst().orElseThrow();
            runtime.openSession(summary);
            var conversationFile = store.conversationPath(WORLD, conversation.id());
            Path movedConversationFile = conversationFile.resolveSibling("temporarily-hidden.jsonl");
            Files.move(conversationFile, movedConversationFile);
            runtime.markRead(summary, completion);
            assertTrue(runtime.view().errors().stream().anyMatch(error -> error.contains("could not be marked as read")), retryPath);
            var persistedErrors = store.errors().size();
            Files.move(movedConversationFile, conversationFile);
            assertEquals(completion, store.sessions().toCompletableFuture().join().stream()
                    .filter(value -> value.id().equals(conversation.id())).findFirst().orElseThrow().unreadRequestId(), retryPath);
            runtime.markRead(summary, completion);
            assertEquals(persistedErrors, store.errors().size(), "failed per-frame retries are suppressed for the same row display");
            assertEquals(completion, store.sessions().toCompletableFuture().join().stream()
                    .filter(value -> value.id().equals(conversation.id())).findFirst().orElseThrow().unreadRequestId(),
                    "the same display does not retry persistence after the file is restored");
            if (retryPath.equals("row-reopen")) runtime.openSession(summary);
            else {
                var oldScreen = runtime.openScreen();
                assertNotSame(oldScreen, runtime.openScreen());
            }
            runtime.markRead(summary, completion);
            assertNull(store.sessions().toCompletableFuture().join().stream()
                    .filter(value -> value.id().equals(conversation.id())).findFirst().orElseThrow().unreadRequestId(), retryPath);
        }
    }

    @Test
    void failedReadThenFailedOrCancelledResendClearsReplacedUnreadMarker() throws Exception {
        for (String terminal : List.of("failed", "cancelled")) {
            var storeRoot = Files.createTempDirectory("packsage-replace-unread");
            var setupStore = new ConversationStore(storeRoot, DIRECT);
            var conversation = setupStore.newConversation(WORLD).toCompletableFuture().join();
            var completion = new ChatContracts.RequestId("completion-replace-unread");
            setupStore.appendTurn(WORLD, SCOPE, conversation.id(), new ChatContracts.Turn(completion, "Original question", "Original answer",
                    List.of(), new ChatContracts.Context("context", null, SCOPE), List.of(),
                    new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1)).toCompletableFuture().join();
            setupStore.recordCompletion(WORLD, conversation.id(), completion).toCompletableFuture().join();
            setupStore.newConversation(WORLD).toCompletableFuture().join();

            var backend = new RuntimeBackend();
            var notices = new ArrayList<ChatContracts.Turn>();
            var pendingStoreTasks = new ArrayDeque<Runnable>();
            var holdStoreTasks = new AtomicBoolean();
            var store = new ConversationStore(storeRoot, task -> {
                if (holdStoreTasks.get()) pendingStoreTasks.addLast(task);
                else task.run();
            });
            var runtime = new AssistantRuntime(store, backend, current -> new GameplayContextCapture(
                    () -> new GameplayContextCapture.Observation("held", "target", "overworld", "1,2,3", "plains", "1", "block"),
                    ignored -> new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1}, Instant.EPOCH), DIRECT, current),
                    DIRECT, DIRECT, current -> new KnowledgeService(List.of(), current), ChatContracts.Turn::tokenCount,
                    GroundingInstructions::build, ignored -> 512, ignored -> { }, ignored -> { }, notices::add);
            runtime.enterWorld(WORLD, new Evidence.Scope("world", "resources", "team", "runtime", 0));
            runtime.signIn();
            backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "fixture", null));
            backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), MODEL.imageInput(), MODEL.hostedSearch(),
                    MODEL.contextTokens(), MODEL.outputTokens()));
            runtime.openScreen();
            var summary = store.sessions().toCompletableFuture().join().stream()
                    .filter(value -> value.id().equals(conversation.id())).findFirst().orElseThrow();
            runtime.openSession(summary);

            Path conversationFile = store.conversationPath(WORLD, conversation.id());
            Path movedConversationFile = conversationFile.resolveSibling("temporarily-hidden.jsonl");
            Files.move(conversationFile, movedConversationFile);
            runtime.markRead(summary, completion);
            assertTrue(runtime.view().errors().stream().anyMatch(error -> error.contains("could not be marked as read")), terminal);
            Files.move(movedConversationFile, conversationFile);
            assertEquals(completion, store.sessions().toCompletableFuture().join().stream()
                    .filter(value -> value.id().equals(conversation.id())).findFirst().orElseThrow().unreadRequestId(), terminal);

            runtime.resend(completion, "Replacement question", false, false);
            assertEquals(1, backend.requests.size(), terminal);
            assertEquals(completion, store.sessions().toCompletableFuture().join().stream()
                    .filter(value -> value.id().equals(conversation.id())).findFirst().orElseThrow().unreadRequestId(),
                    "the old marker remains until the replacement settles");
            assertEquals(completion, runtime.sessions().toCompletableFuture().join().stream()
                    .filter(value -> value.id().equals(conversation.id())).findFirst().orElseThrow().unreadRequestId(), terminal);

            holdStoreTasks.set(true);
            if (terminal.equals("failed")) {
                backend.listeners.getFirst().accept(new ModelBackend.Event(ModelBackend.EventKind.FAILED, null, null,
                        List.of(), List.of(), "fixture failure"));
            } else {
                runtime.stop();
            }
            assertEquals(completion, runtime.view().conversation().turns().getLast().requestId(),
                    terminal + " prior completed answer remains visible while replacement persistence is pending");
            assertEquals(ChatContracts.OutcomeStatus.COMPLETED,
                    runtime.view().conversation().turns().getLast().outcome().status(),
                    terminal + " prior completed outcome remains visible while replacement persistence is pending");
            assertEquals(completion, new ConversationStore(storeRoot, DIRECT).sessions().toCompletableFuture().join().stream()
                    .filter(value -> value.id().equals(conversation.id())).findFirst().orElseThrow().unreadRequestId(),
                    terminal + " independent store reader sees the original unread marker before queued replacement persistence");
            assertFalse(pendingStoreTasks.isEmpty(), "replacement persistence remains queued until explicitly drained");
            while (!pendingStoreTasks.isEmpty()) pendingStoreTasks.removeFirst().run();
            holdStoreTasks.set(false);
            var stored = store.sessions().toCompletableFuture().join().stream()
                    .filter(value -> value.id().equals(conversation.id())).findFirst().orElseThrow();
            assertNull(stored.unreadRequestId(), terminal + " replacement removes the old persisted unread marker");
            assertNull(runtime.sessions().toCompletableFuture().join().stream()
                    .filter(value -> value.id().equals(conversation.id())).findFirst().orElseThrow().unreadRequestId(),
                    terminal + " replacement is reflected in runtime session summaries");
            var replacement = store.openConversation(WORLD, conversation.id()).toCompletableFuture().join().turns().getFirst();
            assertEquals(backend.requests.getFirst().requestId(), replacement.requestId().value(), terminal);
            assertEquals(terminal.equals("failed") ? ChatContracts.OutcomeStatus.FAILED : ChatContracts.OutcomeStatus.CANCELLED,
                    replacement.outcome().status(), terminal);
            assertTrue(notices.isEmpty(), terminal + " replacement does not issue a successful completion notice");
        }
    }

    @Test
    void latestRestoredUnreadCompletionCanBeMarkedReadFromPersistedSummary() throws Exception {
        var storeRoot = Files.createTempDirectory("packsage-restored-unread");
        var setupStore = new ConversationStore(storeRoot, DIRECT);
        var conversation = setupStore.newConversation(WORLD).toCompletableFuture().join();
        var completion = new ChatContracts.RequestId("completion-restored-unread");
        setupStore.appendTurn(WORLD, SCOPE, conversation.id(), new ChatContracts.Turn(completion, "Question", "Answer", List.of(),
                new ChatContracts.Context("context", null, SCOPE), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1)).toCompletableFuture().join();
        setupStore.recordCompletion(WORLD, conversation.id(), completion).toCompletableFuture().join();

        var store = new ConversationStore(storeRoot, DIRECT);
        var runtime = new AssistantRuntime(store, new RuntimeBackend(), current -> new GameplayContextCapture(
                () -> new GameplayContextCapture.Observation("held", "target", "overworld", "1,2,3", "plains", "1", "block"),
                ignored -> new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1}, Instant.EPOCH), DIRECT, current),
                DIRECT, DIRECT, current -> new KnowledgeService(List.of(), current), ChatContracts.Turn::tokenCount,
                GroundingInstructions::build, ignored -> 512, ignored -> { }, ignored -> { });
        runtime.enterWorld(WORLD, new Evidence.Scope("world", "resources", "team", "runtime", 0));
        runtime.openScreen();

        var summary = store.sessions().toCompletableFuture().join().stream()
                .filter(value -> value.id().equals(conversation.id())).findFirst().orElseThrow();
        assertEquals(completion, summary.unreadRequestId());
        assertEquals(conversation.id(), runtime.view().conversation().id());
        assertTrue(runtime.view().conversation().turns().stream().anyMatch(turn -> turn.requestId().equals(completion)
                && turn.outcome().status() == ChatContracts.OutcomeStatus.COMPLETED));
        runtime.markRead(summary, completion);
        assertNull(store.sessions().toCompletableFuture().join().stream()
                .filter(value -> value.id().equals(conversation.id())).findFirst().orElseThrow().unreadRequestId());
    }

    @Test
    void displayedForeignWorldCompletionCanBeReadButCannotStartARequest() throws Exception {
        var foreignWorld = new ChatContracts.WorldIdentity("foreign", "Foreign World");
        var foreignScope = new Evidence.Scope("foreign", "resources", "team", "runtime", 0);
        var storeRoot = Files.createTempDirectory("packsage-foreign-read");
        var setupStore = new ConversationStore(storeRoot, DIRECT);
        var foreignConversation = setupStore.newConversation(foreignWorld).toCompletableFuture().join();
        var completion = new ChatContracts.RequestId("foreign-completion");
        setupStore.appendTurn(foreignWorld, foreignScope, foreignConversation.id(), new ChatContracts.Turn(completion,
                "Old question", "Old answer", List.of(), new ChatContracts.Context("old context", null, foreignScope), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1)).toCompletableFuture().join();
        setupStore.recordCompletion(foreignWorld, foreignConversation.id(), completion).toCompletableFuture().join();
        setupStore.newConversation(WORLD).toCompletableFuture().join();

        var backend = new RuntimeBackend();
        var store = new ConversationStore(storeRoot, DIRECT);
        var runtime = new AssistantRuntime(store, backend, current -> new GameplayContextCapture(
                () -> new GameplayContextCapture.Observation("held", "target", "overworld", "1,2,3", "plains", "1", "block"),
                ignored -> new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1}, Instant.EPOCH), DIRECT, current),
                DIRECT, DIRECT, current -> new KnowledgeService(List.of(), current), ChatContracts.Turn::tokenCount,
                GroundingInstructions::build, ignored -> 512, ignored -> { }, ignored -> { });
        runtime.enterWorld(WORLD, new Evidence.Scope("world", "resources", "team", "runtime", 0));
        runtime.signIn();
        backend.signIn.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "fixture", null));
        backend.catalog.complete(new ModelBackend.Capabilities(List.of(MODEL), MODEL.id(), MODEL.imageInput(), MODEL.hostedSearch(),
                MODEL.contextTokens(), MODEL.outputTokens()));
        runtime.openScreen();
        var foreignSummary = store.sessions().toCompletableFuture().join().stream()
                .filter(value -> value.id().equals(foreignConversation.id())).findFirst().orElseThrow();
        runtime.openSession(foreignSummary);
        runtime.send("Should stay read only", false, false);
        assertTrue(backend.requests.isEmpty());
        runtime.markRead(foreignSummary, completion);
        assertNull(store.sessions().toCompletableFuture().join().stream()
                .filter(value -> value.id().equals(foreignConversation.id())).findFirst().orElseThrow().unreadRequestId());
    }

    private static Evidence.Record record(String provider, String handle, Evidence.Authority authority, String title) {
        return record(SCOPE, provider, handle, authority, title);
    }

    private static Evidence.Record questRecord(boolean completed) {
        return questRecord("quest", "ftb_quests", completed);
    }

    private static Evidence.Record questRecord(String handle, String provider, boolean completed) {
        return new Evidence.Record(new Evidence.Source(SCOPE, provider, "fixture", handle, SCOPE.requestGeneration(),
                Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()),
                Evidence.RecordKind.QUEST, "Quest", new Evidence.JsonObject(Map.of("completed", new Evidence.JsonBoolean(completed))), List.of());
    }

    private static Evidence.Record record(Evidence.Scope scope, String provider, String handle, Evidence.Authority authority,
                                          String title, Evidence.NavigationTarget... targets) {
        return new Evidence.Record(new Evidence.Source(scope, provider, "fixture", handle, scope.requestGeneration(), Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, authority, List.of()), Evidence.RecordKind.RECIPE,
                title, new Evidence.JsonObject(Map.of()), List.of(targets));
    }

    private static class Provider implements KnowledgeProvider {
        private final String id;
        private final List<Evidence.Record> records;
        private final boolean fail;

        private Provider(String id, List<Evidence.Record> records, boolean fail) {
            this.id = id;
            this.records = records;
            this.fail = fail;
        }

        @Override public Metadata metadata() { return new Metadata(id, List.of("quest"), true, List.of(), Map.of()); }
        @Override public CompletionStage<Evidence.Result> searchRecipe(PackSageTools.ReadRequest request, Context context) {
            var filter = request.arguments().values().get("filter") instanceof Evidence.JsonObject value ? value.values() : Map.<String, Evidence.JsonValue>of();
            if (!new Evidence.JsonString(id).equals(filter.get("provider"))) return CompletableFuture.failedFuture(new Failure(Code.NOT_HANDLED, "other source"));
            return query(new Evidence.Query(Evidence.Operation.SEARCH, new Evidence.JsonObject(filter), 16, null, null), request.scope());
        }
        @Override public CompletionStage<Evidence.Result> searchDocument(PackSageTools.ReadRequest request, Context context) { return searchRecipe(request, context); }
        @Override public CompletionStage<Evidence.Result> readEvidence(PackSageTools.ReadRequest request, Context context) {
            var reference = context.reference(request);
            if (!reference.providerId().equals(id)) return CompletableFuture.failedFuture(new Failure(Code.NOT_HANDLED, "other source"));
            return query(new Evidence.Query(Evidence.Operation.GET, new Evidence.JsonObject(Map.of("id", new Evidence.JsonString(reference.sourceHandle()))), 1, null, null), request.scope());
        }
        @Override public CompletionStage<Evidence.Result> readProgress(PackSageTools.ReadRequest request, Context context) { return readEvidence(request, context); }
        @Override public String id() { return id; }
        @Override public ProviderStatus status() { return new ProviderStatus(Availability.AVAILABLE, "fixture", "fixture"); }
        @Override public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
            return fail ? CompletableFuture.failedFuture(new dev.packsage.knowledge.Toolbox.Failure(dev.packsage.knowledge.Toolbox.Code.EXTRACTION_FAILED, "failed capture"))
                    : CompletableFuture.completedFuture(new Evidence.Result(records, Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null));
        }
        @Override public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.UNSUPPORTED, "fixture");
        }
    }

    private static final class RuntimeBackend implements ModelBackend {
        private CompletableFuture<AuthState> signIn = new CompletableFuture<>();
        private final CompletableFuture<Capabilities> catalog = new CompletableFuture<>();
        private final List<Request> requests = new ArrayList<>();
        private final List<ToolResult> submitted = new ArrayList<>();
        private final List<java.util.function.Consumer<Event>> listeners = new ArrayList<>();
        private final List<java.util.concurrent.atomic.AtomicBoolean> cancelled = new ArrayList<>();
        private final List<List<Message>> steered = new ArrayList<>();
        private java.util.function.Consumer<Event> events;
        private Runnable duringSignOut = () -> { };

        @Override public CompletionStage<AuthState> signIn() { return signIn; }
        @Override public void cancelSignIn() { signIn.cancel(false); }
        @Override public void signOut() { duringSignOut.run(); }
        @Override public CompletionStage<Capabilities> capabilities() { return catalog; }
        @Override public Exchange start(Request request, java.util.function.Consumer<Event> events) {
            int requestIndex = requests.size();
            requests.add(request);
            this.events = events;
            listeners.add(events);
            steered.add(new ArrayList<>());
            var wasCancelled = new java.util.concurrent.atomic.AtomicBoolean();
            cancelled.add(wasCancelled);
            return new Exchange() {
                @Override public void submit(ToolResult result) { submitted.add(result); }
                @Override public boolean steer(Message message) { steered.get(requestIndex).add(message); return true; }
                @Override public void cancel() { wasCancelled.set(true); }
            };
        }

        private void emit(Event event) {
            events.accept(event);
        }
    }
}
