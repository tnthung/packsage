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
            if (!budget.value()) return new Fragment("", "page:budget", 0, null, Evidence.Coverage.PARTIAL, "budget exhausted");
            if (fragmentPosition == null) return new Fragment(boundaryMode.get() ? "intro live " : firstText.get(),
                    "page:first", 0, "page:second", Evidence.Coverage.COMPLETE, null);
            return new Fragment(boundaryMode.get() ? "live guide continuation" : secondText.get(), "page:second",
                    boundaryMode.get() ? 5 : 0, null, Evidence.Coverage.COMPLETE, null);
        }

        @Override
        public ResolvedDocument resolve(String id, int maxChars, Toolbox.DocumentBudget budget) {
            var candidate = new Candidate(id, "Guide entry", candidateId, List.of("fixture guide"), Evidence.Coverage.COMPLETE);
            return new ResolvedDocument(candidate, readFragment(id, null, maxChars, budget));
        }
    }
}
