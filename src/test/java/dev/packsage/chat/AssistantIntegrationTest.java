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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
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
                                    @Override public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
                                        opened.set(target.targetId());
                                        return new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED, target.targetId());
                                    }
                                },
                                new Provider("guide", List.of(documentation), false)), current,
                        ignored -> CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE,
                                Evidence.Outcome.UNKNOWN, null)),
                        (ignored, ignoredScope) -> CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE,
                                Evidence.Outcome.UNKNOWN, null))),
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
        var searchCall = new ModelBackend.ToolCall("search-1", "search_pack", "{\"text\":\"torch\"}");
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null, searchCall, List.of(), List.of(), null));
        assertEquals(1, backend.submitted.size());
        assertTrue(backend.submitted.get(0).serializedResult().contains("native:torch"));
        var readCall = new ModelBackend.ToolCall("read-1", "read_evidence", "{\"handles\":[\"native:torch\"]}");
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null, readCall, List.of(), List.of(), null));
        assertEquals(2, backend.submitted.size());
        assertEquals(backend.submitted.get(0).callId(), "search-1");
        assertEquals(backend.submitted.get(1).callId(), "read-1");
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
        assertEquals(List.of(searchCall, backend.submitted.get(0), readCall, backend.submitted.get(1), assistant),
                replayed.stream().filter(item -> !(item instanceof ModelBackend.Message)).toList());
        var currentMessage = replayed.stream().filter(ModelBackend.Message.class::isInstance).map(ModelBackend.Message.class::cast)
                .reduce((first, second) -> second).orElseThrow();
        assertTrue(currentMessage.content().stream().anyMatch(content -> content instanceof ModelBackend.TextContent text
                && text.text().contains("held-2")));
        assertTrue(currentMessage.content().stream().noneMatch(ModelBackend.ImageContent.class::isInstance));
        assertEquals(2, runtime.view().context().scope().requestGeneration());
        backend.emit(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(),
                List.of(new ModelBackend.AssistantOutput("answer-2", ModelBackend.OutputStatus.COMPLETED,
                        ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText("replayed", List.of())))), null));
        assertEquals(2, runtime.view().conversation().turns().size());
    }

    @Test
    void searchesIndependentProvidersAndReadsOnlyObservedScopedHandles() throws Exception {
        var loaded = record("native", "torch", Evidence.Authority.LOADED, "7 torches");
        var external = record("guide", "torch", Evidence.Authority.INSTALLED_DOCUMENTATION, "4 torches");
        var current = new AtomicReference<>(SCOPE);
        var service = new KnowledgeService(List.of(new Provider("native", List.of(loaded), false),
                new Provider("guide", List.of(external), false)), current::get,
                request -> CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null)),
                (identities, scope) -> CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null)));
        var tools = new PackSageTools(service::execute);

        var search = tools.execute(new ModelBackend.ToolCall("search", "search_pack", "{\"text\":\"torch\"}"), SCOPE)
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
        var service = new KnowledgeService(List.of(provider), () -> SCOPE,
                request -> CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE,
                        Evidence.Outcome.UNKNOWN, null)),
                (identities, scope) -> CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE,
                        Evidence.Outcome.UNKNOWN, null)));

        service.execute(new PackSageTools.ReadRequest("search_pack",
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("quest"))), SCOPE))
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
        assertEquals(Evidence.Coverage.UNAVAILABLE, unavailable.coverage());
        assertTrue(unavailable.records().isEmpty());
    }

    @Test
    void preservesUnaffectedEvidenceWhenAnotherProviderFails() throws Exception {
        var loaded = record("native", "torch", Evidence.Authority.LOADED, "7 torches");
        var service = new KnowledgeService(List.of(new Provider("native", List.of(loaded), false),
                new Provider("broken", List.of(), true)), () -> SCOPE,
                request -> CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null)),
                (identities, scope) -> CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null)));

        var response = new PackSageTools(service::execute).execute(new ModelBackend.ToolCall("search", "search_pack", "{\"text\":\"torch\"}"), SCOPE)
                .toCompletableFuture().get();
        assertTrue(response.records().contains(loaded));
        assertTrue(response.records().stream().anyMatch(record -> record.source().providerId().equals("broken")
                && record.source().coverage() == Evidence.Coverage.ERROR));
        assertTrue(response.result().serializedResult().contains("PARTIAL"));
    }

    @Test
    void rejectsLateEvidenceAfterWorldChange() throws Exception {
        var pending = new CompletableFuture<Evidence.Result>();
        var current = new AtomicReference<>(SCOPE);
        KnowledgeProvider provider = new Provider("native", List.of(), false) {
            @Override public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) { return pending; }
        };
        var service = new KnowledgeService(List.of(provider), current::get,
                request -> pending, (identities, scope) -> pending);
        var request = service.execute(new PackSageTools.ReadRequest("search_pack", new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("torch"))), SCOPE));
        current.set(new Evidence.Scope("another-world", "resources", "team", "runtime", 2));
        pending.complete(new Evidence.Result(List.of(record("native", "torch", Evidence.Authority.LOADED, "7 torches")),
                Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null));
        assertEquals(Evidence.Outcome.ERROR, request.toCompletableFuture().get().outcome());
    }

    private static Evidence.Record record(String provider, String handle, Evidence.Authority authority, String title) {
        return record(SCOPE, provider, handle, authority, title);
    }

    private static Evidence.Record questRecord(boolean completed) {
        return new Evidence.Record(new Evidence.Source(SCOPE, "ftb_quests", "fixture", "quest", SCOPE.requestGeneration(),
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

        @Override public String id() { return id; }
        @Override public ProviderStatus status() { return new ProviderStatus(Availability.AVAILABLE, "fixture", "fixture"); }
        @Override public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
            return fail ? CompletableFuture.failedFuture(new IllegalStateException("failed capture"))
                    : CompletableFuture.completedFuture(new Evidence.Result(records, Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null));
        }
        @Override public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.UNSUPPORTED, "fixture");
        }
    }

    private static final class RuntimeBackend implements ModelBackend {
        private final CompletableFuture<AuthState> signIn = new CompletableFuture<>();
        private final CompletableFuture<Capabilities> catalog = new CompletableFuture<>();
        private final List<Request> requests = new ArrayList<>();
        private final List<ToolResult> submitted = new ArrayList<>();
        private java.util.function.Consumer<Event> events;

        @Override public CompletionStage<AuthState> signIn() { return signIn; }
        @Override public void cancelSignIn() { signIn.cancel(false); }
        @Override public void signOut() { }
        @Override public CompletionStage<Capabilities> capabilities() { return catalog; }
        @Override public Exchange start(Request request, java.util.function.Consumer<Event> events) {
            requests.add(request);
            this.events = events;
            return new Exchange() {
                @Override public void submit(ToolResult result) { submitted.add(result); }
                @Override public void cancel() { }
            };
        }

        private void emit(Event event) {
            events.accept(event);
        }
    }
}
