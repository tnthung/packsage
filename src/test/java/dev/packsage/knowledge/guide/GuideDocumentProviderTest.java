package dev.packsage.knowledge.guide;

import dev.packsage.knowledge.AdapterRegistry;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.model.PackSageTools;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    private static final class FixtureReader implements GuideDocumentReader {
        private final AtomicReference<String> secondText = new AtomicReference<>("old continuation needle");

        @Override public String providerId() { return "fixture_guide"; }
        @Override public String version() { return "fixture-1"; }
        @Override public String sourceIdentity(Evidence.Scope scope) { return "fixture-source-1"; }

        @Override
        public CandidatePage candidates(String afterPosition, int limit, Toolbox.DocumentBudget budget) {
            if (!budget.value()) return new CandidatePage(List.of(), "fixture:guide/entry", true,
                    Evidence.Coverage.PARTIAL, "budget exhausted");
            if (afterPosition != null) return new CandidatePage(List.of(), "fixture:guide/end", false,
                    Evidence.Coverage.COMPLETE, null);
            var candidate = new Candidate("fixture:guide/entry", "Guide entry", "fixture:guide/entry",
                    List.of("fixture guide"), Evidence.Coverage.COMPLETE);
            return new CandidatePage(List.of(candidate), candidate.sourcePosition(), false,
                    Evidence.Coverage.COMPLETE, null);
        }

        @Override
        public Fragment readFragment(String id, String fragmentPosition, int maxChars, Toolbox.DocumentBudget budget) {
            if (!budget.value()) return new Fragment("", "page:budget", 0, null, Evidence.Coverage.PARTIAL, "budget exhausted");
            if (fragmentPosition == null) return new Fragment("first needle", "page:first", 0, "page:second",
                    Evidence.Coverage.PARTIAL, null);
            return new Fragment(secondText.get(), "page:second", 0, null, Evidence.Coverage.COMPLETE, null);
        }

        @Override
        public ResolvedDocument resolve(String id, int maxChars, Toolbox.DocumentBudget budget) {
            var candidate = new Candidate(id, "Guide entry", "fixture:guide/entry", List.of("fixture guide"), Evidence.Coverage.COMPLETE);
            return new ResolvedDocument(candidate, readFragment(id, null, maxChars, budget));
        }
    }
}
