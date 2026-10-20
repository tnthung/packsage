package dev.packsage.knowledge.guide;

import dev.packsage.knowledge.AdapterRegistry;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.model.PackSageTools;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuideDocumentProviderTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "guides-1", 1);

    @Test
    void continuationKeepsTheDocumentHandleAndReadsCurrentFragmentText() {
        var reader = new FixtureReader();
        var provider = new GuideDocumentProvider(reader, () -> SCOPE, Runnable::run);
        var registry = new AdapterRegistry();
        registry.register(provider);
        var context = new Toolbox.Context(() -> SCOPE, Map.of(), registry, new Toolbox.DocumentBudget(), null);
        var arguments = Map.<String, Evidence.JsonValue>of(
                "query", new Evidence.JsonString("needle"),
                "filter", new Evidence.JsonObject(Map.of(
                        "provider", new Evidence.JsonString("fixture_guide"),
                        "types", new Evidence.JsonArray(List.of(new Evidence.JsonString("guide"))))),
                "limit", new Evidence.JsonNumber(java.math.BigDecimal.ONE));

        var first = provider.searchDocument(new PackSageTools.ReadRequest("search_document",
                new Evidence.JsonObject(arguments), SCOPE), context).toCompletableFuture().join();

        assertEquals(1, first.records().size());
        assertEquals("fixture:guide/entry", first.records().getFirst().source().sourceHandle());
        assertNotNull(first.nextCursor());
        assertTrue(first.records().getFirst().payload().values().containsKey("text"));
        reader.secondText.set("current continuation needle");
        var continuedArguments = new java.util.LinkedHashMap<>(arguments);
        continuedArguments.put("cursor", new Evidence.JsonString(first.nextCursor()));
        var continued = provider.searchDocument(new PackSageTools.ReadRequest("search_document",
                new Evidence.JsonObject(continuedArguments), SCOPE), context).toCompletableFuture().join();

        assertEquals(1, continued.records().size());
        assertEquals(first.records().getFirst().source().sourceHandle(), continued.records().getFirst().source().sourceHandle());
        assertEquals(new Evidence.JsonString("current continuation needle"), continued.records().getFirst().payload().values().get("text"));
    }

    @Test
    void readEvidenceFallsThroughToTheOwningGuideProvider() {
        var registry = new AdapterRegistry();
        var first = new GuideDocumentProvider(new FixtureReader("first_guide", "first:entry"), () -> SCOPE, Runnable::run);
        var second = new GuideDocumentProvider(new FixtureReader("second_guide", "second:entry"), () -> SCOPE, Runnable::run);
        registry.register(first);
        registry.register(second);
        var context = new Toolbox.Context(() -> SCOPE, Map.of("observed", new Toolbox.Reference(
                "second_guide", "second:entry", Evidence.RecordKind.GUIDE, SCOPE)), registry,
                new Toolbox.DocumentBudget(), null);
        var request = new PackSageTools.ReadRequest("read_evidence", new Evidence.JsonObject(Map.of("handles",
                new Evidence.JsonArray(List.of(new Evidence.JsonString("observed"))))), SCOPE);

        var result = registry.dispatch(request, context).toCompletableFuture().join();

        assertEquals("second_guide", result.records().getFirst().source().providerId());
        assertEquals("second:entry", result.records().getFirst().source().sourceHandle());
    }

    @Test
    void readEvidenceUsesFreshTextAndRejectsAChangedScope() {
        var reader = new FixtureReader();
        var currentScope = new AtomicReference<>(SCOPE);
        var provider = new GuideDocumentProvider(reader, currentScope::get, Runnable::run);
        var context = new Toolbox.Context(currentScope::get, Map.of("observed", new Toolbox.Reference(
                "fixture_guide", "fixture:guide/entry", Evidence.RecordKind.GUIDE, SCOPE)), new AdapterRegistry(),
                new Toolbox.DocumentBudget(), null);
        var request = new PackSageTools.ReadRequest("read_evidence", new Evidence.JsonObject(Map.of("handles",
                new Evidence.JsonArray(List.of(new Evidence.JsonString("observed"))))), SCOPE);
        reader.firstText.set("fresh live evidence");

        var result = provider.readEvidence(request, context).toCompletableFuture().join();

        assertEquals(new Evidence.JsonString("fresh live evidence"), result.records().getFirst().payload().values().get("text"));
        currentScope.set(new Evidence.Scope("other-world", "en_us", "team", "guides-1", 1));
        var failure = assertThrows(CompletionException.class, () -> provider.readEvidence(request, context).toCompletableFuture().join());
        assertEquals(Toolbox.Code.STALE_SCOPE, ((Toolbox.Failure) failure.getCause()).code());
    }

    @Test
    void cursorRejectsAChangedQueryAndSourceIdentity() {
        var reader = new FixtureReader();
        var provider = new GuideDocumentProvider(reader, () -> SCOPE, Runnable::run);
        var context = new Toolbox.Context(() -> SCOPE, Map.of(), new AdapterRegistry(), new Toolbox.DocumentBudget(), null);
        var arguments = new java.util.LinkedHashMap<String, Evidence.JsonValue>();
        arguments.put("query", new Evidence.JsonString("needle"));
        arguments.put("filter", new Evidence.JsonObject(Map.of(
                "provider", new Evidence.JsonString("fixture_guide"),
                "types", new Evidence.JsonArray(List.of(new Evidence.JsonString("guide"))))));
        arguments.put("limit", new Evidence.JsonNumber(java.math.BigDecimal.ONE));
        var first = provider.searchDocument(new PackSageTools.ReadRequest("search_document",
                new Evidence.JsonObject(arguments), SCOPE), context).toCompletableFuture().join();
        var continuedArguments = new java.util.LinkedHashMap<>(arguments);
        continuedArguments.put("cursor", new Evidence.JsonString(first.nextCursor()));
        continuedArguments.put("query", new Evidence.JsonString("changed query"));
        var queryFailure = assertThrows(CompletionException.class, () -> provider.searchDocument(new PackSageTools.ReadRequest(
                "search_document", new Evidence.JsonObject(continuedArguments), SCOPE), context).toCompletableFuture().join());
        assertEquals(Toolbox.Code.INVALID_CURSOR, ((Toolbox.Failure) queryFailure.getCause()).code());

        reader.sourceIdentity.set("fixture-source-2");
        continuedArguments.put("query", new Evidence.JsonString("needle"));
        var identityFailure = assertThrows(CompletionException.class, () -> provider.searchDocument(new PackSageTools.ReadRequest(
                "search_document", new Evidence.JsonObject(continuedArguments), SCOPE), context).toCompletableFuture().join());
        assertEquals(Toolbox.Code.INVALID_CURSOR, ((Toolbox.Failure) identityFailure.getCause()).code());
    }

    @Test
    void hiddenEntriesCanAdvanceAnEmptyCandidatePage() {
        var reader = new FixtureReader();
        reader.hiddenFirstPage.set(true);
        var provider = new GuideDocumentProvider(reader, () -> SCOPE, Runnable::run);
        var arguments = Map.<String, Evidence.JsonValue>of(
                "query", new Evidence.JsonString("needle"),
                "filter", new Evidence.JsonObject(Map.of(
                        "provider", new Evidence.JsonString("fixture_guide"),
                        "types", new Evidence.JsonArray(List.of(new Evidence.JsonString("guide"))))));

        var result = provider.searchDocument(new PackSageTools.ReadRequest("search_document",
                new Evidence.JsonObject(arguments), SCOPE), new Toolbox.Context(() -> SCOPE, Map.of(),
                new AdapterRegistry(), new Toolbox.DocumentBudget(), null)).toCompletableFuture().join();

        assertEquals(1, reader.hiddenPageCalls.get());
        assertEquals(2, result.records().size());
        assertEquals("fixture:guide/entry", result.records().getFirst().source().sourceHandle());
    }

    @Test
    void literalMatchesCanCrossOverlappingFragments() {
        var reader = new FixtureReader();
        reader.boundaryMode.set(true);
        var provider = new GuideDocumentProvider(reader, () -> SCOPE, Runnable::run);
        var arguments = Map.<String, Evidence.JsonValue>of(
                "query", new Evidence.JsonString("live guide"),
                "filter", new Evidence.JsonObject(Map.of(
                        "provider", new Evidence.JsonString("fixture_guide"),
                        "types", new Evidence.JsonArray(List.of(new Evidence.JsonString("guide"))))));

        var result = provider.searchDocument(new PackSageTools.ReadRequest("search_document",
                new Evidence.JsonObject(arguments), SCOPE), new Toolbox.Context(() -> SCOPE, Map.of(),
                new AdapterRegistry(), new Toolbox.DocumentBudget(), null)).toCompletableFuture().join();

        assertEquals(1, result.records().size());
        assertEquals("page:second", ((Evidence.JsonString) result.records().getFirst().payload().values().get("fragment")).value());
    }

    @Test
    void exhaustedEmptyCandidatePageReturnsRetryCursorAtTheSamePosition() {
        var reader = new FixtureReader();
        reader.exhaustFirstCandidatePage.set(true);
        var provider = new GuideDocumentProvider(reader, () -> SCOPE, Runnable::run);
        var arguments = new java.util.LinkedHashMap<String, Evidence.JsonValue>();
        arguments.put("query", new Evidence.JsonString("needle"));
        arguments.put("filter", new Evidence.JsonObject(Map.of(
                "provider", new Evidence.JsonString("fixture_guide"),
                "types", new Evidence.JsonArray(List.of(new Evidence.JsonString("guide"))))));
        arguments.put("limit", new Evidence.JsonNumber(java.math.BigDecimal.ONE));
        var firstContext = new Toolbox.Context(() -> SCOPE, Map.of(), new AdapterRegistry(), new Toolbox.DocumentBudget(), null);

        var first = provider.searchDocument(new PackSageTools.ReadRequest("search_document",
                new Evidence.JsonObject(arguments), SCOPE), firstContext).toCompletableFuture().join();

        assertTrue(first.records().isEmpty());
        assertEquals(Evidence.Coverage.PARTIAL, first.coverage());
        assertNotNull(first.nextCursor());
        var continuedArguments = new java.util.LinkedHashMap<>(arguments);
        continuedArguments.put("cursor", new Evidence.JsonString(first.nextCursor()));
        var secondContext = new Toolbox.Context(() -> SCOPE, Map.of(), new AdapterRegistry(), new Toolbox.DocumentBudget(), null);
        var continued = provider.searchDocument(new PackSageTools.ReadRequest("search_document",
                new Evidence.JsonObject(continuedArguments), SCOPE), secondContext).toCompletableFuture().join();

        assertEquals("fixture:guide/entry", continued.records().getFirst().source().sourceHandle());
    }

    @Test
    void exhaustedFragmentReadRetriesTheSameLiveFirstAndLaterFragments() {
        var reader = new FixtureReader();
        reader.exhaustFirstFragment.set(true);
        var provider = new GuideDocumentProvider(reader, () -> SCOPE, Runnable::run);
        var arguments = new java.util.LinkedHashMap<String, Evidence.JsonValue>();
        arguments.put("query", new Evidence.JsonString("needle"));
        arguments.put("filter", new Evidence.JsonObject(Map.of(
                "provider", new Evidence.JsonString("fixture_guide"),
                "types", new Evidence.JsonArray(List.of(new Evidence.JsonString("guide"))))));
        arguments.put("limit", new Evidence.JsonNumber(java.math.BigDecimal.ONE));

        var first = provider.searchDocument(new PackSageTools.ReadRequest("search_document",
                new Evidence.JsonObject(arguments), SCOPE), new Toolbox.Context(() -> SCOPE, Map.of(),
                new AdapterRegistry(), new Toolbox.DocumentBudget(), null)).toCompletableFuture().join();

        assertTrue(first.records().isEmpty());
        assertNotNull(first.nextCursor());
        reader.firstText.set("fresh first fragment needle");
        var firstRetryArguments = new java.util.LinkedHashMap<>(arguments);
        firstRetryArguments.put("cursor", new Evidence.JsonString(first.nextCursor()));
        var firstRetry = provider.searchDocument(new PackSageTools.ReadRequest("search_document",
                new Evidence.JsonObject(firstRetryArguments), SCOPE), new Toolbox.Context(() -> SCOPE, Map.of(),
                new AdapterRegistry(), new Toolbox.DocumentBudget(), null)).toCompletableFuture().join();

        assertEquals(new Evidence.JsonString("fresh first fragment needle"),
                firstRetry.records().getFirst().payload().values().get("text"));
        assertNotNull(firstRetry.nextCursor());
        reader.exhaustSecondFragment.set(true);
        var secondFailureArguments = new java.util.LinkedHashMap<>(arguments);
        secondFailureArguments.put("cursor", new Evidence.JsonString(firstRetry.nextCursor()));
        var secondFailure = provider.searchDocument(new PackSageTools.ReadRequest("search_document",
                new Evidence.JsonObject(secondFailureArguments), SCOPE), new Toolbox.Context(() -> SCOPE, Map.of(),
                new AdapterRegistry(), new Toolbox.DocumentBudget(), null)).toCompletableFuture().join();

        assertTrue(secondFailure.records().isEmpty());
        assertNotNull(secondFailure.nextCursor());
        reader.secondText.set("fresh continuation fragment needle");
        var secondRetryArguments = new java.util.LinkedHashMap<>(arguments);
        secondRetryArguments.put("cursor", new Evidence.JsonString(secondFailure.nextCursor()));
        var secondRetry = provider.searchDocument(new PackSageTools.ReadRequest("search_document",
                new Evidence.JsonObject(secondRetryArguments), SCOPE), new Toolbox.Context(() -> SCOPE, Map.of(),
                new AdapterRegistry(), new Toolbox.DocumentBudget(), null)).toCompletableFuture().join();

        assertEquals(new Evidence.JsonString("fresh continuation fragment needle"),
                secondRetry.records().getFirst().payload().values().get("text"));
    }

    @Test
    void readerNavigationTargetsSurviveFreshReadsAndRejectChangedScopesBeforeOpening() {
        var reader = new FixtureReader();
        reader.navigationEnabled.set(true);
        var currentScope = new AtomicReference<>(SCOPE);
        var provider = new GuideDocumentProvider(reader, currentScope::get, Runnable::run);
        var result = provider.query(new Evidence.Query(Evidence.Operation.GET,
                new Evidence.JsonObject(Map.of("id", new Evidence.JsonString("fixture:guide/entry"))), 1, null, null),
                SCOPE).toCompletableFuture().join();
        var target = result.records().getFirst().targets().getFirst();

        assertEquals("fixture:guide/entry", target.sourceHandle());
        assertEquals(Evidence.NavigationStatus.OPENED, provider.open(target, SCOPE).status());
        assertEquals(1, reader.opens.get());
        currentScope.set(new Evidence.Scope("other-world", "en_us", "team", "guides-1", 1));
        assertEquals(Evidence.NavigationStatus.STALE, provider.open(target, SCOPE).status());
        assertEquals(1, reader.opens.get());
    }

    @Test
    void ponderDestinationRefreshRunsOnClientExecutorAndRechecksCurrentScope() {
        var source = new PonderGuideReaderTest.FixtureSource();
        source.entries.add(PonderGuideReaderTest.entryForComponent("new_registration", "create:press", "Title"));
        var pending = new java.util.ArrayList<Runnable>();
        var currentScope = new AtomicReference<>(SCOPE);
        var provider = new GuideDocumentProvider(new PonderGuideReader(source), currentScope::get, pending::add);
        var saved = new Evidence.NavigationTarget(SCOPE, "ponder", "old_registration", "create:press");

        var resolving = provider.refreshNavigation(saved, SCOPE);

        assertTrue(!pending.isEmpty());
        assertTrue(!resolving.toCompletableFuture().isDone());
        assertEquals(0, source.compiles.get());
        currentScope.set(new Evidence.Scope("other-world", "en_us", "team", "guides-1", 1));
        pending.removeFirst().run();
        assertEquals(null, resolving.toCompletableFuture().join());
        assertEquals(0, source.opens.get());
        assertEquals(0, source.compiles.get());
    }

    @Test
    void lowerCandidateBoundStopsNoMatchTraversalAndReturnsALiveContinuation() {
        var reader = new FixtureReader();
        reader.manyCandidates.set(true);
        var provider = new GuideDocumentProvider(reader, () -> SCOPE, Runnable::run, 4);
        var arguments = new Evidence.JsonObject(Map.of("query", new Evidence.JsonString("absent"),
                "filter", new Evidence.JsonObject(Map.of("provider", new Evidence.JsonString("fixture_guide")))));
        var result = provider.searchDocument(new PackSageTools.ReadRequest("search_document", arguments, SCOPE),
                new Toolbox.Context(() -> SCOPE, Map.of(), new AdapterRegistry(), new Toolbox.DocumentBudget(), null))
                .toCompletableFuture().join();

        assertTrue(result.records().isEmpty());
        assertEquals(4, reader.inspectedCandidates.get());
        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        assertNotNull(result.nextCursor());
    }

    @Test
    void defaultGuideReadersKeepNavigationUnsupported() {
        var provider = new GuideDocumentProvider(new FixtureReader(), () -> SCOPE, Runnable::run);
        assertEquals(Evidence.NavigationStatus.UNSUPPORTED, provider.open(new Evidence.NavigationTarget(
                SCOPE, "fixture_guide", "fixture:guide/entry", "fixture:guide/entry"), SCOPE).status());
        assertEquals(Evidence.NavigationStatus.STALE, provider.open(new Evidence.NavigationTarget(
                SCOPE, "other", "fixture:guide/entry", "fixture:guide/entry"), SCOPE).status());
    }

    @Test
    void partialTerminalFragmentsDoNotClaimCompleteDocumentEvidence() {
        var reader = new FixtureReader();
        reader.partialTerminalFragment.set(true);
        var result = new GuideDocumentProvider(reader, () -> SCOPE, Runnable::run).query(new Evidence.Query(
                Evidence.Operation.GET, new Evidence.JsonObject(Map.of("id", new Evidence.JsonString("fixture:guide/entry"))),
                1, null, null), SCOPE).toCompletableFuture().join();
        assertEquals(new Evidence.JsonBoolean(false), result.records().getFirst().payload().values().get("complete"));
    }

    @Test
    void relationshipOnlyFragmentMatchesDeclaredIdentity() {
        var reader = new FixtureReader();
        reader.terminalFragment.set(true);
        reader.relationships.set(itemsRelationship("minecraft:diamond", Evidence.Coverage.COMPLETE));
        reader.firstText.set("");
        var result = search(reader, "minecraft:diamond");

        assertEquals(1, result.records().size());
        assertEquals(reader.relationships.get(), result.records().getFirst().payload().values().get("relationships"));
    }

    @Test
    void relationshipCoverageIsNotSearchText() {
        var reader = new FixtureReader();
        reader.relationships.set(itemsRelationship("minecraft:diamond", Evidence.Coverage.COMPLETE));

        assertTrue(search(reader, "COMPLETE").records().isEmpty());
    }

    @Test
    void freshReadPreservesCurrentRelationships() {
        var reader = new FixtureReader();
        reader.relationships.set(itemsRelationship("minecraft:diamond", Evidence.Coverage.COMPLETE));
        var provider = new GuideDocumentProvider(reader, () -> SCOPE, Runnable::run);
        reader.relationships.set(itemsRelationship("minecraft:emerald", Evidence.Coverage.COMPLETE));

        var result = provider.query(new Evidence.Query(Evidence.Operation.GET,
                new Evidence.JsonObject(Map.of("id", new Evidence.JsonString("fixture:guide/entry"))), 1, null, null),
                SCOPE).toCompletableFuture().join();

        assertEquals(itemsRelationship("minecraft:emerald", Evidence.Coverage.COMPLETE),
                result.records().getFirst().payload().values().get("relationships"));
    }

    @Test
    void relationshipContinuationKeepsHandleAndReadsCurrentValues() {
        var reader = new FixtureReader();
        reader.relationships.set(itemsRelationship("minecraft:diamond", Evidence.Coverage.PARTIAL));
        reader.firstText.set("");
        var provider = new GuideDocumentProvider(reader, () -> SCOPE, Runnable::run);
        var arguments = new java.util.LinkedHashMap<String, Evidence.JsonValue>();
        arguments.put("query", new Evidence.JsonString("needle"));
        arguments.put("filter", new Evidence.JsonObject(Map.of("provider", new Evidence.JsonString("fixture_guide"))));
        arguments.put("limit", new Evidence.JsonNumber(java.math.BigDecimal.ONE));
        var context = new Toolbox.Context(() -> SCOPE, Map.of(), new AdapterRegistry(), new Toolbox.DocumentBudget(), null);

        var first = provider.searchDocument(new PackSageTools.ReadRequest("search_document",
                new Evidence.JsonObject(arguments), SCOPE), context).toCompletableFuture().join();
        assertEquals(1, first.records().size());
        assertNotNull(first.nextCursor());

        reader.relationships.set(itemsRelationship("minecraft:emerald", Evidence.Coverage.COMPLETE));
        arguments.put("cursor", new Evidence.JsonString(first.nextCursor()));
        var continued = provider.searchDocument(new PackSageTools.ReadRequest("search_document",
                new Evidence.JsonObject(arguments), SCOPE), context).toCompletableFuture().join();

        assertEquals("fixture:guide/entry", continued.records().getFirst().source().sourceHandle());
        assertEquals(reader.relationships.get(), continued.records().getFirst().payload().values().get("relationships"));
    }

    @Test
    void textOnlyFragmentsKeepExistingPayloadShape() {
        var result = search(new FixtureReader(), "needle");

        assertFalse(result.records().getFirst().payload().values().containsKey("relationships"));
    }

    @Test
    void absentEmptyPartialAndOpaqueRelationshipGroupsRemainDistinct() {
        var absent = new FixtureReader();
        assertFalse(search(absent, "needle").records().getFirst().payload().values().containsKey("relationships"));

        for (var coverage : List.of(Evidence.Coverage.COMPLETE, Evidence.Coverage.PARTIAL, Evidence.Coverage.OPAQUE)) {
            var reader = new FixtureReader();
            reader.terminalFragment.set(true);
            reader.relationships.set(itemsRelationship(null, coverage));
            var record = search(reader, "needle").records().getFirst();
            assertEquals(reader.relationships.get(), record.payload().values().get("relationships"));
            assertEquals(new Evidence.JsonBoolean(coverage == Evidence.Coverage.COMPLETE), record.payload().values().get("complete"));
        }
    }

    @Test
    void malformedAndOversizedRelationshipGroupsAreRejected() {
        var malformed = new FixtureReader();
        malformed.relationships.set(new Evidence.JsonObject(Map.of("items", new Evidence.JsonObject(Map.of(
                "coverage", new Evidence.JsonString("COMPLETE"), "values", new Evidence.JsonString("not-an-array"))))));
        assertExtractionFailure(malformed);

        var oversized = new FixtureReader();
        oversized.relationships.set(new Evidence.JsonObject(Map.of("items", new Evidence.JsonObject(Map.of(
                "coverage", new Evidence.JsonString("COMPLETE"), "values", new Evidence.JsonArray(
                        java.util.Collections.nCopies(17, new Evidence.JsonString("minecraft:diamond"))))))));
        assertExtractionFailure(oversized);
    }

    private static Evidence.Result search(FixtureReader reader, String query) {
        var provider = new GuideDocumentProvider(reader, () -> SCOPE, Runnable::run);
        var arguments = new Evidence.JsonObject(Map.of("query", new Evidence.JsonString(query),
                "filter", new Evidence.JsonObject(Map.of("provider", new Evidence.JsonString("fixture_guide")))));
        return provider.searchDocument(new PackSageTools.ReadRequest("search_document", arguments, SCOPE),
                new Toolbox.Context(() -> SCOPE, Map.of(), new AdapterRegistry(), new Toolbox.DocumentBudget(), null))
                .toCompletableFuture().join();
    }

    private static void assertExtractionFailure(FixtureReader reader) {
        var failure = assertThrows(CompletionException.class, () -> search(reader, "needle"));
        assertEquals(Toolbox.Code.EXTRACTION_FAILED, ((Toolbox.Failure) failure.getCause()).code());
    }

    private static Evidence.JsonObject itemsRelationship(String itemId, Evidence.Coverage coverage) {
        var values = itemId == null ? List.<Evidence.JsonValue>of() : List.<Evidence.JsonValue>of(
                new Evidence.JsonObject(Map.of("kind", new Evidence.JsonString("associated"),
                        "sourcePosition", new Evidence.JsonString("page:first"),
                        "itemId", new Evidence.JsonString(itemId))));
        return new Evidence.JsonObject(Map.of("items", new Evidence.JsonObject(Map.of(
                "coverage", new Evidence.JsonString(coverage.name()), "values", new Evidence.JsonArray(values)))));
    }

    private static final class FixtureReader implements GuideDocumentReader {
        private final String providerId;
        private final String candidateId;
        private final AtomicReference<String> firstText = new AtomicReference<>("first needle");
        private final AtomicReference<String> secondText = new AtomicReference<>("old continuation needle");
        private final AtomicReference<String> sourceIdentity = new AtomicReference<>("fixture-source-1");
        private final AtomicBoolean hiddenFirstPage = new AtomicBoolean();
        private final AtomicInteger hiddenPageCalls = new AtomicInteger();
        private final AtomicBoolean boundaryMode = new AtomicBoolean();
        private final AtomicBoolean exhaustFirstCandidatePage = new AtomicBoolean();
        private final AtomicBoolean exhaustFirstFragment = new AtomicBoolean();
        private final AtomicBoolean exhaustSecondFragment = new AtomicBoolean();
        private final AtomicBoolean navigationEnabled = new AtomicBoolean();
        private final AtomicInteger opens = new AtomicInteger();
        private final AtomicBoolean manyCandidates = new AtomicBoolean();
        private final AtomicInteger inspectedCandidates = new AtomicInteger();
        private final AtomicBoolean partialTerminalFragment = new AtomicBoolean();
        private final AtomicBoolean terminalFragment = new AtomicBoolean();
        private final AtomicReference<Evidence.JsonObject> relationships = new AtomicReference<>(new Evidence.JsonObject(Map.of()));

        private FixtureReader() { this("fixture_guide", "fixture:guide/entry"); }

        private FixtureReader(String providerId, String candidateId) {
            this.providerId = providerId;
            this.candidateId = candidateId;
        }

        @Override public String providerId() { return providerId; }
        @Override public String version() { return "fixture-1"; }
        @Override public String sourceIdentity(Evidence.Scope scope) { return sourceIdentity.get(); }

        @Override
        public CandidatePage candidates(String afterPosition, int limit, Toolbox.DocumentBudget budget) {
            if (manyCandidates.get()) {
                var candidates = new java.util.ArrayList<Candidate>();
                int index = afterPosition == null ? 0 : Integer.parseInt(afterPosition);
                while (index < 10 && candidates.size() < limit && budget.value()) {
                    inspectedCandidates.incrementAndGet();
                    index++;
                    candidates.add(new Candidate("fixture:entry/" + index, "Entry " + index,
                            String.valueOf(index), List.of(), Evidence.Coverage.COMPLETE));
                }
                return new CandidatePage(candidates, String.valueOf(index), index < 10, Evidence.Coverage.COMPLETE, null);
            }
            if (exhaustFirstCandidatePage.compareAndSet(true, false)) {
                while (budget.value()) { }
                return new CandidatePage(List.of(), afterPosition, true, Evidence.Coverage.PARTIAL,
                        "source traversal budget exhausted before the next candidate");
            }
            if (!budget.value()) return new CandidatePage(List.of(), "fixture:guide/entry", true,
                    Evidence.Coverage.PARTIAL, "budget exhausted");
            if (hiddenFirstPage.get() && afterPosition == null) return new CandidatePage(List.of(), "fixture:hidden", true,
                    Evidence.Coverage.COMPLETE, null);
            if (hiddenFirstPage.get() && "fixture:hidden".equals(afterPosition)) hiddenPageCalls.incrementAndGet();
            if (afterPosition != null && !(hiddenFirstPage.get() && "fixture:hidden".equals(afterPosition))) return new CandidatePage(List.of(), "fixture:guide/end", false,
                    Evidence.Coverage.COMPLETE, null);
            var candidate = new Candidate(candidateId, "Guide entry", candidateId,
                    List.of("fixture guide"), Evidence.Coverage.COMPLETE);
            return new CandidatePage(List.of(candidate), candidate.sourcePosition(), false,
                    Evidence.Coverage.COMPLETE, null);
        }

        @Override
        public Fragment readFragment(String id, String fragmentPosition, int maxChars, Toolbox.DocumentBudget budget) {
            if (partialTerminalFragment.get()) return new Fragment("bounded text", "page:first", 0, null,
                    Evidence.Coverage.PARTIAL, "truncated source text", relationships.get());
            if (fragmentPosition == null && exhaustFirstFragment.compareAndSet(true, false)
                    || fragmentPosition != null && exhaustSecondFragment.compareAndSet(true, false)) {
                while (budget.value()) { }
                throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "fixture fragment budget exhausted");
            }
            if (!budget.value()) return new Fragment("", "page:budget", 0, null, Evidence.Coverage.PARTIAL, "budget exhausted");
            if (fragmentPosition == null) return new Fragment(boundaryMode.get() ? "intro live " : firstText.get(),
                    "page:first", 0, terminalFragment.get() ? null : "page:second", Evidence.Coverage.COMPLETE, null,
                    relationships.get());
            return new Fragment(boundaryMode.get() ? "live guide continuation" : secondText.get(), "page:second",
                    boundaryMode.get() ? 5 : 0, null, Evidence.Coverage.COMPLETE, null, relationships.get());
        }

        @Override
        public ResolvedDocument resolve(String id, int maxChars, Toolbox.DocumentBudget budget) {
            var candidate = new Candidate(id, "Guide entry", candidateId, List.of("fixture guide"), Evidence.Coverage.COMPLETE);
            return new ResolvedDocument(candidate, readFragment(id, null, maxChars, budget));
        }

        @Override
        public List<Evidence.NavigationTarget> navigationTargets(Evidence.Scope scope, Candidate candidate) {
            return navigationEnabled.get() ? List.of(new Evidence.NavigationTarget(scope, providerId,
                    candidate.id(), candidate.id())) : List.of();
        }

        @Override
        public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
            if (!navigationEnabled.get()) return new Evidence.NavigationResult(Evidence.NavigationStatus.UNSUPPORTED, "unsupported");
            opens.incrementAndGet();
            return new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED, "opened");
        }
    }
}
