package dev.packsage.knowledge;

import dev.packsage.chat.ChatContracts;
import dev.packsage.chat.ConversationStore;
import dev.packsage.model.PackSageTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeServiceTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "resources", "team", "runtime", 1);

    @Test
    void savedDestinationResolvesWithANewProviderAndSourceHandle(@TempDir Path directory) throws Exception {
        var historicalScope = new Evidence.Scope("old-session", "old-resources", "team", "old-runtime", 3);
        var historical = new Evidence.NavigationTarget(historicalScope, "fixture_viewer", "old-registration", "create:shaft");
        var world = new ChatContracts.WorldIdentity("world", "World");
        var store = new ConversationStore(directory, Runnable::run);
        var conversation = store.newConversation(world).toCompletableFuture().join();
        store.appendTurn(world, historicalScope, conversation.id(), new ChatContracts.Turn(
                new ChatContracts.RequestId("saved"), "Question", "Answer", List.of(),
                new ChatContracts.Context("context", null, historicalScope), List.of(new Evidence.Source(historicalScope,
                "fixture_viewer", "fixture", "old-registration", 1, Instant.EPOCH, Evidence.Activity.ACTIVE,
                Evidence.Coverage.PARTIAL, Evidence.Authority.INSTALLED_DOCUMENTATION, List.of())), List.of(historical),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1)).toCompletableFuture().join();
        var saved = new ConversationStore(directory, Runnable::run).openLatest(world).toCompletableFuture().join()
                .turns().getFirst().navigationTargets().getFirst();
        var current = new Evidence.NavigationTarget(SCOPE, "fixture_viewer", "new-registration", "create:shaft");
        var received = new AtomicReference<Evidence.NavigationTarget>();
        var provider = new PagedProvider("fixture_viewer", query -> page(List.of(), null)) {
            @Override public CompletionStage<Evidence.NavigationTarget> refreshNavigation(Evidence.NavigationTarget target, Evidence.Scope scope) {
                received.set(target);
                assertEquals(SCOPE, scope);
                return CompletableFuture.completedFuture(current);
            }
        };
        var service = service(provider);

        assertEquals(current, service.refreshNavigation(saved, SCOPE).toCompletableFuture().join());
        assertEquals(saved, received.get());
        assertEquals(Evidence.NavigationStatus.STALE, service.open(saved, SCOPE).status());
        assertEquals(Evidence.NavigationStatus.UNSUPPORTED, service.open(current, SCOPE).status());
        assertEquals(Evidence.Outcome.ERROR, execute(service, "read_evidence", Map.of("handles",
                strings("fixture_viewer:new-registration"))).outcome());
    }

    @Test
    void destinationResolversCannotAuthorizeForeignScopesProvidersOrLateResults() {
        var historical = new Evidence.NavigationTarget(SCOPE, "fixture_viewer", "old", "create:shaft");
        for (var invalid : List.of(new Evidence.NavigationTarget(SCOPE, "fixture_recipes", "new", "create:shaft"),
                new Evidence.NavigationTarget(new Evidence.Scope("other", "resources", "team", "runtime", 1),
                        "fixture_viewer", "new", "create:shaft"))) {
            var provider = new PagedProvider("fixture_viewer", query -> page(List.of(), null)) {
                @Override public CompletionStage<Evidence.NavigationTarget> refreshNavigation(Evidence.NavigationTarget target, Evidence.Scope scope) {
                    return CompletableFuture.completedFuture(invalid);
                }
            };
            var service = service(provider);
            assertNull(service.refreshNavigation(historical, SCOPE).toCompletableFuture().join());
            assertEquals(Evidence.NavigationStatus.STALE, service.open(invalid, SCOPE).status());
        }
        var pending = new CompletableFuture<Evidence.NavigationTarget>();
        var active = new AtomicReference<>(SCOPE);
        var service = new KnowledgeService(List.of(new PagedProvider("fixture_viewer", query -> page(List.of(), null)) {
            @Override public CompletionStage<Evidence.NavigationTarget> refreshNavigation(Evidence.NavigationTarget target, Evidence.Scope scope) {
                return pending;
            }
        }), active::get);
        var refresh = service.refreshNavigation(historical, SCOPE).toCompletableFuture();
        assertFalse(refresh.isDone());
        active.set(new Evidence.Scope("next", "resources", "team", "runtime", 2));
        pending.complete(new Evidence.NavigationTarget(SCOPE, "fixture_viewer", "new", "create:shaft"));
        assertNull(refresh.join());
        active.set(SCOPE);
        assertEquals(Evidence.NavigationStatus.STALE, service.open(new Evidence.NavigationTarget(SCOPE,
                "fixture_viewer", "new", "create:shaft"), SCOPE).status());
    }

    @Test
    void refreshNavigationRevalidatesHistoricalTargetsAndKeepsNavigationHandlesOutOfModelEvidence() {
        var historicalScope = new Evidence.Scope("world", "resources", "team", "runtime", 0);
        var historical = new Evidence.NavigationTarget(historicalScope, "fixture_docs", "book:guide", "page/old");
        var current = new Evidence.NavigationTarget(SCOPE, "fixture_docs", "book:guide", "page/old");
        var currentRecord = new Evidence.Record(new Evidence.Source(SCOPE, "fixture_docs", "fixture", "book:guide", 1,
                Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()),
                Evidence.RecordKind.GUIDE, "Guide", new Evidence.JsonObject(Map.of()), List.of(current));
        var provider = new PagedProvider("fixture_docs", query -> page(List.of(currentRecord), null));
        var service = service(provider);

        assertEquals(current, service.refreshNavigation(historical, SCOPE).toCompletableFuture().join());
        assertEquals(Evidence.NavigationStatus.STALE, service.open(historical, SCOPE).status());
        assertEquals(Evidence.NavigationStatus.UNSUPPORTED, service.open(current, SCOPE).status());
        assertEquals(Evidence.Operation.GET, provider.queries.getFirst().operation());
        assertEquals(Map.of("id", text("book:guide")), provider.queries.getFirst().arguments().values());
        assertEquals(1, provider.queries.getFirst().limit());
        assertNull(provider.queries.getFirst().cursor());

        var unobserved = execute(service, "read_evidence", Map.of("handles",
                new Evidence.JsonArray(List.of(text("fixture_docs:book:guide")))));
        assertEquals(Evidence.Outcome.ERROR, unobserved.outcome());
    }

    @Test
    void refreshNavigationRejectsMismatchedEvidenceAndChangedScopesIncludingLateCompletion() {
        var historicalScope = new Evidence.Scope("world", "resources", "team", "runtime", 0);
        var historical = new Evidence.NavigationTarget(historicalScope, "fixture_docs", "book:guide", "page/old");
        var wrongSource = new Evidence.Record(new Evidence.Source(SCOPE, "fixture_docs", "fixture", "book:other", 1,
                Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()),
                Evidence.RecordKind.GUIDE, "Guide", new Evidence.JsonObject(Map.of()),
                List.of(new Evidence.NavigationTarget(SCOPE, "fixture_docs", "book:other", "page/old")));
        var wrongTarget = new Evidence.Record(new Evidence.Source(SCOPE, "fixture_docs", "fixture", "book:guide", 1,
                Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()),
                Evidence.RecordKind.GUIDE, "Guide", new Evidence.JsonObject(Map.of()),
                List.of(new Evidence.NavigationTarget(SCOPE, "fixture_docs", "book:guide", "page/other")));
        assertNull(service(new PagedProvider("fixture_docs", query -> page(List.of(wrongSource), null)))
                .refreshNavigation(historical, SCOPE).toCompletableFuture().join());
        assertNull(service(new PagedProvider("fixture_docs", query -> page(List.of(wrongTarget), null)))
                .refreshNavigation(historical, SCOPE).toCompletableFuture().join());
        assertNull(service(new PagedProvider("fixture_docs", query -> new Evidence.Result(List.of(wrongTarget),
                Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null)))
                .refreshNavigation(historical, SCOPE).toCompletableFuture().join());
        assertNull(service().refreshNavigation(historical, SCOPE).toCompletableFuture().join());

        var currentScope = new AtomicReference<>(SCOPE);
        var pending = new CompletableFuture<Evidence.Result>();
        KnowledgeProvider provider = new PagedProvider("fixture_docs", query -> page(List.of(), null)) {
            @Override public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
                return pending;
            }
        };
        var service = new KnowledgeService(List.of(provider), currentScope::get);
        var refresh = service.refreshNavigation(historical, SCOPE);
        currentScope.set(new Evidence.Scope("world", "resources-2", "team", "runtime", 2));
        pending.complete(page(List.of(new Evidence.Record(new Evidence.Source(SCOPE, "fixture_docs", "fixture", "book:guide", 1,
                Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()),
                Evidence.RecordKind.GUIDE, "Guide", new Evidence.JsonObject(Map.of()),
                List.of(new Evidence.NavigationTarget(SCOPE, "fixture_docs", "book:guide", "page/old")))), null));
        assertNull(refresh.toCompletableFuture().join());
        assertNull(service.refreshNavigation(historical, SCOPE).toCompletableFuture().join());
    }

    @Test
    void refreshNavigationAuthorizationIsBounded() {
        var historicalScope = new Evidence.Scope("world", "resources", "team", "runtime", 0);
        var provider = new PagedProvider("fixture_docs", query -> {
            var handle = ((Evidence.JsonString) query.arguments().values().get("id")).value();
            var target = new Evidence.NavigationTarget(SCOPE, "fixture_docs", handle, "page");
            var source = new Evidence.Source(SCOPE, "fixture_docs", "fixture", handle, 1, Instant.EPOCH,
                    Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of());
            return page(List.of(new Evidence.Record(source, Evidence.RecordKind.GUIDE, handle,
                    new Evidence.JsonObject(Map.of()), List.of(target))), null);
        });
        var service = service(provider);
        Evidence.NavigationTarget first = null;
        Evidence.NavigationTarget last = null;
        for (var index = 0; index < 65; index++) {
            var historical = new Evidence.NavigationTarget(historicalScope, "fixture_docs", "book:" + index, "page");
            var refreshed = service.refreshNavigation(historical, SCOPE).toCompletableFuture().join();
            if (index == 0) first = refreshed;
            if (index == 64) last = refreshed;
        }

        assertEquals(Evidence.NavigationStatus.STALE, service.open(first, SCOPE).status());
        assertEquals(Evidence.NavigationStatus.UNSUPPORTED, service.open(last, SCOPE).status());
    }

    @Test
    void adapterAvailabilityUsesLogicalIdsAndIsIndependentOfProviderCoverage() {
        var partial = new PagedProvider("fixture_recipes", query -> new Evidence.Result(List.of(), Evidence.Coverage.PARTIAL,
                Evidence.Outcome.PARTIAL, null));
        KnowledgeProvider disabled = new PagedProvider("fixture_docs", query -> page(List.of(), null)) {
            @Override public boolean enabled() { return false; }
        };
        var availability = service(partial, disabled).adapterAvailability();

        assertEquals(Map.of("fixture_recipes", true, "fixture_docs", false), availability);
        assertThrows(UnsupportedOperationException.class, () -> availability.put("fixture_recipes", false));
    }

    @Test
    void unqualifiedDiscoveryUsesMetadataAndQualifiedRequests() {
        var first = new PagedProvider("source_a", query -> page(List.of(record("source_a", "first", Evidence.RecordKind.GUIDE)), null));
        var second = new PagedProvider("source_b", query -> page(List.of(record("source_b", "second", Evidence.RecordKind.GUIDE)), null));
        assertEquals(List.of("first", "second"), handles(search(service(first, second), Map.of("query", text("")))));
        assertEquals(1, first.queries.size());
        assertEquals(1, second.queries.size());
        assertEquals(List.of("first"), handles(search(service(first, second), Map.of("query", text(""),
                "filter", new Evidence.JsonObject(Map.of("provider", text("source_a")))))));
    }

    @Test
    void unqualifiedRecipeSearchReadsOnlyTheFirstAvailableRecipeSource() {
        var viewerEnabled = new java.util.concurrent.atomic.AtomicBoolean(true);
        PagedProvider viewer = new RecipeProvider("viewer", viewerEnabled::get);
        PagedProvider loaded = new RecipeProvider("loaded", () -> true);
        var guide = new PagedProvider("guide", query -> page(List.of(record("guide", "entry", Evidence.RecordKind.GUIDE)), null));
        var service = service(viewer, guide, loaded);

        var fromViewer = execute(service, "search_recipe", Map.of());
        assertEquals(List.of("viewer-recipe"), handles(fromViewer));
        assertEquals(Evidence.Coverage.COMPLETE, fromViewer.coverage());
        assertTrue(loaded.queries.isEmpty());
        assertEquals(List.of("loaded-recipe"), handles(execute(service, "search_recipe", Map.of(
                "filter", new Evidence.JsonObject(Map.of("provider", text("loaded")))))));

        viewerEnabled.set(false);
        viewer.queries.clear();
        var fromLoaded = execute(service, "search_recipe", Map.of());
        assertEquals(List.of("loaded-recipe"), handles(fromLoaded));
        assertEquals(Evidence.Coverage.COMPLETE, fromLoaded.coverage());
        assertNull(fromLoaded.detail());
        assertTrue(viewer.queries.isEmpty());
        var unavailable = execute(service, "search_recipe", Map.of(
                "filter", new Evidence.JsonObject(Map.of("provider", text("viewer")))));
        assertEquals(Evidence.Outcome.ERROR, unavailable.outcome());
        assertTrue(unavailable.detail().contains("UNAVAILABLE"));
        assertEquals(List.of("entry"), handles(search(service, Map.of("query", text("")))));
    }

    @Test
    void recipeContinuationIsRejectedAfterTheDefaultSourceChanges() {
        var viewerEnabled = new java.util.concurrent.atomic.AtomicBoolean(true);
        var service = service(new RecipeProvider("viewer", viewerEnabled::get), new RecipeProvider("loaded", () -> true));
        var first = execute(service, "search_recipe", Map.of("limit", number(1)));
        assertNotNull(first.nextCursor());
        viewerEnabled.set(false);
        var continued = execute(service, "search_recipe", Map.of("limit", number(1), "cursor", text(first.nextCursor())));
        assertEquals(Evidence.Outcome.ERROR, continued.outcome());
        assertTrue(continued.detail().contains("INVALID_CURSOR"));
    }

    @Test
    void discoveryPreservesContinuationAtResultAndWorkBounds() {
        var source = new PagedProvider("guide", query -> {
            int position = query.cursor() == null ? 0 : Integer.parseInt(query.cursor());
            return page(position < 9 ? List.of() : List.of(record("guide", "found", Evidence.RecordKind.GUIDE)),
                    position < 9 ? Integer.toString(position + 1) : null);
        });
        var service = service(source);
        var first = search(service, Map.of("query", text("")));
        assertEquals(8, source.queries.size());
        assertEquals(Evidence.Coverage.PARTIAL, first.coverage());
        assertNotNull(first.nextCursor());
        assertEquals(List.of("found"), handles(search(service, Map.of("query", text(""), "cursor", text(first.nextCursor())))));
        var two = service(new PagedProvider("a", query -> page(List.of(record("a", "one", Evidence.RecordKind.GUIDE)), null)),
                new PagedProvider("b", query -> page(List.of(record("b", "two", Evidence.RecordKind.GUIDE)), null)));
        var page = search(two, Map.of("query", text(""), "limit", number(1)));
        assertNotNull(page.nextCursor());
        assertEquals(List.of("two"), handles(search(two, Map.of("query", text(""), "limit", number(8), "cursor", text(page.nextCursor())))));
        assertEquals(Evidence.Outcome.ERROR, search(two, Map.of("query", text("changed"), "cursor", text(page.nextCursor()))).outcome());
    }

    @Test
    void discoveryPropagatesRequestFailuresAndOnlyCombinesSourceFailures() {
        for (var code : List.of(Toolbox.Code.INVALID_ARGUMENTS, Toolbox.Code.INVALID_CURSOR, Toolbox.Code.INVALID_REFERENCE,
                Toolbox.Code.STALE_SCOPE, Toolbox.Code.UNSUPPORTED_FILTER, Toolbox.Code.UNAVAILABLE, Toolbox.Code.EXTRACTION_FAILED)) {
            var broken = new PagedProvider("broken", query -> { throw new Toolbox.Failure(code, "test failure"); });
            var service = service(new PagedProvider("good", query -> page(List.of(record("good", "one", Evidence.RecordKind.GUIDE)), null)), broken);
            var result = search(service, Map.of("query", text("")));
            if (code == Toolbox.Code.UNAVAILABLE || code == Toolbox.Code.EXTRACTION_FAILED) {
                assertEquals(List.of("one"), handles(result));
                assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
            } else {
                assertEquals(Evidence.Outcome.ERROR, result.outcome());
                assertTrue(result.detail().contains(code.name()));
            }
        }
    }

    @Test
    void discoveryRejectsForeignProviderKindsAndNavigationOwnership() {
        var current = record("guide", "entry", Evidence.RecordKind.GUIDE);
        for (var invalid : List.of(record("foreign", "entry", Evidence.RecordKind.GUIDE),
                record("guide", "entry", Evidence.RecordKind.ITEM),
                new Evidence.Record(current.source(), current.kind(), current.title(), current.payload(),
                        List.of(new Evidence.NavigationTarget(SCOPE, "foreign", "entry", "page"))))) {
            var result = search(service(new PagedProvider("guide", query -> page(List.of(invalid), null))), Map.of("query", text("")));
            assertEquals(Evidence.Outcome.ERROR, result.outcome());
            assertTrue(result.records().isEmpty());
            assertTrue(result.detail().contains("INVALID_REFERENCE"));
        }
    }

    @Test
    void referencesReadCurrentBodiesAndRejectUnknownOrChangedIdentity() {
        var current = new AtomicReference<>(record("guide", "same", Evidence.RecordKind.GUIDE));
        var source = new PagedProvider("guide", query -> page(List.of(current.get()), null));
        var service = service(source);
        search(service, Map.of("query", text("")));
        var replacement = new Evidence.Record(current.get().source(), current.get().kind(), "changed now",
                new Evidence.JsonObject(Map.of("live", text("new value"))), List.of());
        current.set(replacement);
        assertEquals(List.of(replacement), execute(service, "read_evidence", Map.of("handles", strings("guide:same"))).records());
        assertEquals(Evidence.Operation.GET, source.queries.getLast().operation());
        current.set(record("guide", "different", Evidence.RecordKind.GUIDE));
        assertEquals(Evidence.Outcome.ERROR, execute(service, "read_evidence", Map.of("handles", strings("guide:same"))).outcome());
        assertEquals(Evidence.Outcome.ERROR, execute(service, "read_evidence", Map.of("handles", strings("guide:unknown"))).outcome());
    }

    @Test
    void mixedReadsValidateWholeBatchBeforeReadingAndShareDocumentBudget() {
        var budgets = new ArrayList<Toolbox.DocumentBudget>();
        var one = new PagedProvider("one", query -> page(List.of(record("one", "a", Evidence.RecordKind.GUIDE)), null)) {
            @Override public CompletionStage<Evidence.Result> readEvidence(PackSageTools.ReadRequest request, Context context) {
                if (context.reference(request).providerId().equals(id())) budgets.add(context.documentBudget());
                return super.readEvidence(request, context);
            }
        };
        var two = new PagedProvider("two", query -> page(List.of(record("two", "b", Evidence.RecordKind.GUIDE)), null)) {
            @Override public CompletionStage<Evidence.Result> readEvidence(PackSageTools.ReadRequest request, Context context) {
                if (context.reference(request).providerId().equals(id())) budgets.add(context.documentBudget());
                return super.readEvidence(request, context);
            }
        };
        var service = service(one, two);
        search(service, Map.of("query", text("")));
        assertEquals(Evidence.Outcome.ERROR, execute(service, "read_evidence", Map.of("handles", strings("one:a", "two:unknown"))).outcome());
        assertTrue(budgets.isEmpty());
        assertEquals(2, execute(service, "read_evidence", Map.of("handles", strings("one:a", "two:b"))).records().size());
        assertEquals(budgets.get(0), budgets.get(1));
    }

    @Test
    void progressFailuresReturnCurrentStatusAndKeepOtherFreshResults() {
        var failed = new PagedProvider("quests_a", query -> query.operation() == Evidence.Operation.GET
                ? new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null)
                : page(List.of(record("quests_a", "a", Evidence.RecordKind.QUEST)), null));
        var good = new PagedProvider("quests_b", query -> page(List.of(record("quests_b", "b", Evidence.RecordKind.QUEST)), null));
        var service = service(failed, good);
        search(service, Map.of("query", text("")));
        var result = execute(service, "read_progress", Map.of("handles", strings("quests_a:a", "quests_b:b")));
        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        assertEquals(List.of("progress-status:a", "b"), handles(result));
        assertEquals(Evidence.Coverage.UNAVAILABLE, result.records().getFirst().source().coverage());
    }

    @Test
    void failedFreshReadsNeverPromoteReturnedBodiesToSuccessfulEvidence() {
        for (var coverage : List.of(Evidence.Coverage.ERROR, Evidence.Coverage.UNAVAILABLE)) {
            var failed = new PagedProvider("quests", query -> query.operation() == Evidence.Operation.GET
                    ? new Evidence.Result(List.of(record("quests", "a", Evidence.RecordKind.QUEST)), coverage,
                    coverage == Evidence.Coverage.ERROR ? Evidence.Outcome.ERROR : Evidence.Outcome.UNKNOWN, null, "current source unavailable")
                    : page(List.of(record("quests", "a", Evidence.RecordKind.QUEST)), null));
            var service = service(failed);
            search(service, Map.of("query", text("")));
            var progress = execute(service, "read_progress", Map.of("handles", strings("quests:a")));
            assertEquals(List.of("progress-status:a"), handles(progress));
            assertEquals(Evidence.Coverage.PARTIAL, progress.coverage());
            var evidence = execute(service, "read_evidence", Map.of("handles", strings("quests:a")));
            assertEquals(Evidence.Outcome.ERROR, evidence.outcome());
            assertTrue(evidence.records().isEmpty());
        }
    }

    private static KnowledgeService service(KnowledgeProvider... providers) { return new KnowledgeService(List.of(providers), () -> SCOPE); }
    private static Evidence.Result search(KnowledgeService service, Map<String, Evidence.JsonValue> arguments) { return execute(service, "search_document", arguments); }
    private static Evidence.Result execute(KnowledgeService service, String tool, Map<String, Evidence.JsonValue> arguments) {
        try {
            return service.execute(new PackSageTools.ReadRequest(tool, new Evidence.JsonObject(new LinkedHashMap<>(arguments)), SCOPE)).toCompletableFuture().join();
        } catch (RuntimeException failure) {
            return new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null, failure.getMessage());
        }
    }
    private static List<String> handles(Evidence.Result result) { return result.records().stream().map(record -> record.source().sourceHandle()).toList(); }
    private static Evidence.Result page(List<Evidence.Record> records, String next) { return new Evidence.Result(records, Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, next); }
    private static Evidence.JsonString text(String value) { return new Evidence.JsonString(value); }
    private static Evidence.JsonNumber number(int value) { return new Evidence.JsonNumber(BigDecimal.valueOf(value)); }
    private static Evidence.JsonArray strings(String... values) { return new Evidence.JsonArray(java.util.Arrays.stream(values).map(value -> (Evidence.JsonValue) text(value)).toList()); }
    private static Evidence.Record record(String provider, String handle, Evidence.RecordKind kind) {
        return new Evidence.Record(new Evidence.Source(SCOPE, provider, "fixture", handle, 1, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()), kind, handle,
                new Evidence.JsonObject(Map.of()), List.of());
    }

    private static class PagedProvider implements KnowledgeProvider {
        private final String id;
        private final Function<Evidence.Query, Evidence.Result> pages;
        private final List<Evidence.Query> queries = new ArrayList<>();
        private PagedProvider(String id, Function<Evidence.Query, Evidence.Result> pages) { this.id = id; this.pages = pages; }
        @Override public String id() { return id; }
        @Override public Metadata metadata() { return new Metadata(id, List.of("guide", "quest"), false, List.of(), Map.of()); }
        @Override public ProviderStatus status() { return new ProviderStatus(Availability.AVAILABLE, "fixture", "fixture"); }
        @Override public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) { queries.add(query); return CompletableFuture.completedFuture(pages.apply(query)); }
        @Override public CompletionStage<Evidence.Result> searchDocument(PackSageTools.ReadRequest request, Context context) {
            var filter = (Evidence.JsonObject) request.arguments().values().get("filter");
            if (!text(id).equals(filter.values().get("provider"))) return CompletableFuture.failedFuture(new Failure(Code.NOT_HANDLED, "other source"));
            return query(new Evidence.Query(Evidence.Operation.SEARCH, new Evidence.JsonObject(Map.of("text", request.arguments().values().get("query"))),
                    request.arguments().values().get("limit") instanceof Evidence.JsonNumber limit ? limit.value().intValueExact() : 16,
                    request.arguments().values().get("cursor") instanceof Evidence.JsonString ? SCOPE : null,
                    request.arguments().values().get("cursor") instanceof Evidence.JsonString cursor ? cursor.value() : null), request.scope());
        }
        @Override public CompletionStage<Evidence.Result> readEvidence(PackSageTools.ReadRequest request, Context context) {
            var reference = context.reference(request);
            if (!id.equals(reference.providerId())) return CompletableFuture.failedFuture(new Failure(Code.NOT_HANDLED, "other source"));
            return query(new Evidence.Query(Evidence.Operation.GET, new Evidence.JsonObject(Map.of("id", text(reference.sourceHandle()))), 1, null, null), request.scope());
        }
        @Override public CompletionStage<Evidence.Result> readProgress(PackSageTools.ReadRequest request, Context context) { return readEvidence(request, context); }
        @Override public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) { return new Evidence.NavigationResult(Evidence.NavigationStatus.UNSUPPORTED, "fixture"); }
    }

    private static class RecipeProvider extends PagedProvider {
        private final java.util.function.BooleanSupplier enabled;
        private RecipeProvider(String id, java.util.function.BooleanSupplier enabled) {
            super(id, query -> page(List.of(record(id, id + "-recipe", Evidence.RecordKind.RECIPE)),
                    query.cursor() == null && query.limit() == 1 ? "more" : null));
            this.enabled = enabled;
        }
        @Override public Metadata metadata() { return new Metadata(id(), List.of(), true, List.of(), Map.of()); }
        @Override public boolean enabled() { return enabled.getAsBoolean(); }
        @Override public CompletionStage<Evidence.Result> searchRecipe(PackSageTools.ReadRequest request, Context context) {
            var filter = request.arguments().values().get("filter") instanceof Evidence.JsonObject value ? value : new Evidence.JsonObject(Map.of());
            if (!text(id()).equals(filter.values().get("provider"))) return CompletableFuture.failedFuture(new Failure(Code.NOT_HANDLED, "other source"));
            if (!enabled()) return CompletableFuture.failedFuture(new Failure(Code.UNAVAILABLE, id() + " runtime is unavailable"));
            return query(new Evidence.Query(Evidence.Operation.SEARCH, new Evidence.JsonObject(Map.of()),
                    request.arguments().values().get("limit") instanceof Evidence.JsonNumber limit ? limit.value().intValueExact() : 16,
                    request.arguments().values().get("cursor") instanceof Evidence.JsonString ? SCOPE : null,
                    request.arguments().values().get("cursor") instanceof Evidence.JsonString cursor ? cursor.value() : null), request.scope());
        }
    }
}
