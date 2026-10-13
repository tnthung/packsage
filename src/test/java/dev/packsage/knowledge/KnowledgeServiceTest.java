package dev.packsage.knowledge;

import dev.packsage.model.PackSageTools;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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
    void adapterAvailabilityUsesLogicalIdsAndIsIndependentOfProviderCoverage() {
        var partial = new PagedProvider("jei", query -> new Evidence.Result(List.of(), Evidence.Coverage.PARTIAL,
                Evidence.Outcome.PARTIAL, null));
        KnowledgeProvider disabled = new PagedProvider("patchouli", query -> page(List.of(), null)) {
            @Override public boolean enabled() { return false; }
        };
        var availability = service(partial, disabled).adapterAvailability();

        assertEquals(Map.of("jei", true, "patchouli", false), availability);
        assertThrows(UnsupportedOperationException.class, () -> availability.put("jei", false));
    }

    @Test
    void inventoryToolRoutesOmittedAndNullIdentitiesAsAnEmptySelection() {
        var selections = new ArrayList<List<String>>();
        var service = new KnowledgeService(List.of(), () -> SCOPE,
                plan -> CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE,
                        Evidence.Outcome.COMPLETE, null)),
                (identities, scope) -> {
                    selections.add(identities);
                    return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE,
                            Evidence.Outcome.COMPLETE, null));
                });

        execute(service, "read_inventory", Map.of());
        execute(service, "read_inventory", Map.of("identities", new Evidence.JsonNull()));

        assertEquals(List.of(List.of(), List.of()), selections);
    }

    @Test
    void searchKeepsPagingAcrossEmptyProviderPagesUntilTheLimitIsFilled() {
        var pages = List.of(page(List.of(), "1"), page(List.of(), "2"),
                page(List.of(record("jei", "r1", Evidence.RecordKind.RECIPE), record("jei", "r2", Evidence.RecordKind.RECIPE)), "3"),
                page(List.of(record("jei", "r3", Evidence.RecordKind.RECIPE), record("jei", "r4", Evidence.RecordKind.RECIPE)), "4"),
                page(List.of(record("jei", "r5", Evidence.RecordKind.RECIPE)), null));
        var provider = new PagedProvider("jei", query -> pages.get(query.cursor() == null ? 0 : Integer.parseInt(query.cursor())));
        var service = service(provider);

        var first = search(service, Map.of("text", text("r"), "limit", number(4)));

        assertEquals(List.of("r1", "r2", "r3", "r4"), handles(first));
        assertNotNull(first.nextCursor());
        assertEquals(Evidence.Outcome.COMPLETE, first.outcome());

        var second = search(service, Map.of("text", text("r"), "limit", number(2), "cursor", text(first.nextCursor())));

        assertEquals(List.of("r5"), handles(second));
        assertNull(second.nextCursor());
        assertEquals(Evidence.Outcome.COMPLETE, second.outcome());
    }

    @Test
    void kindsAreForwardedAndFilterBeforeAPageCanEndTheCall() {
        var nativePages = List.of(
                page(List.of(record("native", "torch-item", Evidence.RecordKind.ITEM), record("native", "torch-tag", Evidence.RecordKind.OTHER)), "1"),
                page(List.of(record("native", "torch-recipe", Evidence.RecordKind.RECIPE)), null));
        var nativeProvider = new PagedProvider("native", query -> nativePages.get(query.cursor() == null ? 0 : Integer.parseInt(query.cursor())));
        var guideProvider = new PagedProvider("patchouli", query -> page(List.of(record("patchouli", "guide", Evidence.RecordKind.GUIDE)), null));
        var service = service(nativeProvider, guideProvider);

        var recipes = search(service, Map.of("text", text("torch"), "kinds", kinds("RECIPE")));

        assertEquals(List.of("torch-recipe"), handles(recipes));
        assertNull(recipes.nextCursor());
        assertTrue(guideProvider.queries.isEmpty(), "a GUIDE-only provider must be skipped for RECIPE searches");
        assertEquals(kinds("RECIPE"), nativeProvider.queries.getFirst().arguments().values().get("kinds"));
    }

    @Test
    void providersThatCannotProduceTheRequestedKindsAreSkipped() {
        var jeiProvider = new PagedProvider("jei", query -> page(List.of(), "1"));
        var nativeProvider = new PagedProvider("native", query -> page(List.of(), null));
        var guideProvider = new PagedProvider("patchouli", query -> page(List.of(record("patchouli", "intro/aura", Evidence.RecordKind.GUIDE)), null));
        var service = service(nativeProvider, jeiProvider, guideProvider);

        var guides = search(service, Map.of("text", text("aura"), "kinds", kinds("GUIDE")));

        assertEquals(List.of("intro/aura"), handles(guides));
        assertTrue(jeiProvider.queries.isEmpty());
        assertTrue(nativeProvider.queries.isEmpty());
    }

    @Test
    void providerAndOutputItemFiltersSkipEarlierProvidersAndArePartOfCursorIdentity() {
        var nativeProvider = new PagedProvider("native", query -> page(List.of(record("native", "wrong", Evidence.RecordKind.RECIPE)), null));
        var jeiProvider = new PagedProvider("jei", query -> page(List.of(), "1"));
        var service = service(nativeProvider, jeiProvider);
        Map<String, Evidence.JsonValue> args = Map.of("text", text(""), "provider", text("jei"), "outputItem", text("minecraft:stone"));

        var first = search(service, args);

        assertTrue(nativeProvider.queries.isEmpty(), "provider filtering must skip native before its page consumes the budget");
        assertEquals(text("minecraft:stone"), jeiProvider.queries.getFirst().arguments().values().get("outputItem"));
        Map<String, Evidence.JsonValue> changedFilter = new LinkedHashMap<>(args);
        changedFilter.put("outputItem", text("minecraft:dirt"));
        changedFilter.put("cursor", text(first.nextCursor()));
        var mismatch = search(service, changedFilter);
        assertEquals(Evidence.Outcome.ERROR, mismatch.outcome());
        assertTrue(mismatch.detail().contains("cursor does not match"), mismatch.detail());
    }

    @Test
    void providerStatusRecordsSurviveTheKindsFilter() {
        var unsupported = new PagedProvider("patchouli", query -> new Evidence.Result(List.of(new Evidence.Record(
                new Evidence.Source(SCOPE, "patchouli", "1.21.1-92", "status:patchouli", 1, Instant.EPOCH, Evidence.Activity.UNKNOWN,
                        Evidence.Coverage.PARTIAL, Evidence.Authority.UNKNOWN, List.of()), Evidence.RecordKind.OTHER,
                "patchouli version is outside the tested version", new Evidence.JsonObject(Map.of()), List.of())),
                Evidence.Coverage.PARTIAL, Evidence.Outcome.PARTIAL, null));
        var service = service(unsupported);

        var result = search(service, Map.of("text", text("aura"), "kinds", kinds("GUIDE")));

        assertEquals(List.of("status:patchouli"), handles(result));
        assertEquals(Evidence.Outcome.PARTIAL, result.outcome());
    }

    @Test
    void emptyPartialProviderResultKeepsItsDiagnosticDetail() {
        var provider = new PagedProvider("jei", query -> new Evidence.Result(List.of(), Evidence.Coverage.PARTIAL,
                Evidence.Outcome.PARTIAL, null, "JEI projection is incomplete"));

        var result = search(service(provider), Map.of("text", text("lantern")));

        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        assertEquals(Evidence.Outcome.PARTIAL, result.outcome());
        assertTrue(result.detail() != null && result.detail().contains("JEI projection is incomplete"), result.detail());
    }

    @Test
    void reachingTheWorkBoundWithoutMatchesSaysSoAndReturnsACursor() {
        var provider = new PagedProvider("jei", query -> page(List.of(),
                Integer.toString(query.cursor() == null ? 1 : Integer.parseInt(query.cursor()) + 1)));
        var service = service(provider);

        var result = search(service, Map.of("text", text("nothing")));

        assertTrue(result.records().isEmpty());
        assertEquals(KnowledgeService.MAX_PROVIDER_PAGES_PER_CALL, provider.queries.size());
        assertNotNull(result.nextCursor());
        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        assertEquals(Evidence.Outcome.PARTIAL, result.outcome());
        assertNotNull(result.detail());
        assertTrue(result.detail().contains("bound"), result.detail());
        assertTrue(result.detail().contains("no matching records"), result.detail());

        var next = search(service, Map.of("text", text("nothing"), "cursor", text(result.nextCursor())));
        assertEquals(Evidence.Outcome.PARTIAL, next.outcome());
        assertEquals(2 * KnowledgeService.MAX_PROVIDER_PAGES_PER_CALL, provider.queries.size());
    }

    @Test
    void cursorReusedWithADifferentLimitContinuesButADifferentQueryExplainsTheError() {
        var pages = List.of(page(List.of(record("native", "a", Evidence.RecordKind.ITEM)), "1"),
                page(List.of(record("native", "b", Evidence.RecordKind.ITEM)), null));
        var service = service(new PagedProvider("native", query -> pages.get(query.cursor() == null ? 0 : Integer.parseInt(query.cursor()))));

        var first = search(service, Map.of("text", text("x"), "limit", number(1)));
        assertEquals(List.of("a"), handles(first));

        var continued = search(service, Map.of("text", text("x"), "limit", number(64), "cursor", text(first.nextCursor())));
        assertEquals(List.of("b"), handles(continued));
        assertEquals(Evidence.Outcome.COMPLETE, continued.outcome());

        var mismatched = search(service, Map.of("text", text("y"), "cursor", text(first.nextCursor())));
        assertEquals(Evidence.Outcome.ERROR, mismatched.outcome());
        assertTrue(mismatched.detail().contains("cursor does not match"), mismatched.detail());

        var unknown = search(service, Map.of("text", text("x"), "cursor", text("missing")));
        assertEquals(Evidence.Outcome.ERROR, unknown.outcome());
        assertTrue(unknown.detail().contains("cursor"), unknown.detail());
    }

    @Test
    void errorsCarryANonEmptyDetailIncludingTheProviderException() {
        var failing = new PagedProvider("native", query -> { throw new IllegalStateException("snapshot capture exploded"); });
        var service = service(failing);

        var search = search(service, Map.of("text", text("x")));
        var status = search.records().getFirst();
        assertEquals("status:native", status.source().sourceHandle());
        assertTrue(Evidence.serialize(status.payload()).contains("IllegalStateException"));
        assertTrue(Evidence.serialize(status.payload()).contains("snapshot capture exploded"));
        assertNotNull(search.detail());

        var unknownHandle = execute(service, "read_evidence", Map.of("handles",
                new Evidence.JsonArray(List.of(text("native:missing")))));
        assertEquals(Evidence.Outcome.ERROR, unknownHandle.outcome());
        assertTrue(unknownHandle.detail().contains("native:missing"), unknownHandle.detail());

        var unknownTool = execute(service, "run_command", Map.of());
        assertEquals(Evidence.Outcome.ERROR, unknownTool.outcome());
        assertFalse(unknownTool.detail().isBlank());

        var malformed = execute(service, "read_evidence", Map.of("handles", text("not-an-array")));
        assertEquals(Evidence.Outcome.ERROR, malformed.outcome());
        assertTrue(malformed.detail().contains("ClassCastException"), malformed.detail());

        var planFailure = new KnowledgeService(List.of(), () -> SCOPE,
                plan -> CompletableFuture.failedFuture(new IllegalStateException("planner broke")),
                (identities, scope) -> CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE,
                        Evidence.Outcome.UNKNOWN, null)));
        var plan = planFailure.execute(new PackSageTools.ReadRequest("plan_recipe", new Evidence.JsonObject(Map.of(
                "identity", text("minecraft:torch"), "quantity", number(1))), SCOPE)).toCompletableFuture().join();
        assertEquals(Evidence.Outcome.ERROR, plan.outcome());
        assertTrue(plan.detail().contains("planner broke"), plan.detail());
        var inventory = planFailure.execute(new PackSageTools.ReadRequest("read_inventory", new Evidence.JsonObject(Map.of(
                "identities", new Evidence.JsonArray(List.of(text("minecraft:torch"))))), SCOPE)).toCompletableFuture().join();
        assertEquals(Evidence.Coverage.UNAVAILABLE, inventory.coverage());
        assertNotNull(inventory.detail());
    }

    @Test
    void readConfigAcceptsTheShortEntryHandleAndDropsTheUnusableCursor() {
        var entry = "naturesaura:COMMON:naturesaura-common.toml";
        var queries = new ArrayList<Evidence.Query>();
        var config = new PagedProvider("config", query -> {
            queries.add(query);
            return page(List.of(configRecord(entry, "general.fieldCreatorRange")), "7");
        });
        var service = service(config);

        var unknown = execute(service, "read_config", Map.of("handle", text(entry), "key", text("general.fieldCreatorRange")));
        assertEquals(Evidence.Outcome.ERROR, unknown.outcome());
        assertTrue(unknown.detail().contains("config:<owner>:<SCOPE>:<file>#<key>"), unknown.detail());

        execute(service, "search_config", Map.of("text", text("range")));
        var read = execute(service, "read_config", Map.of("handle", text(entry), "key", text("general.fieldCreatorRange")));

        assertEquals(List.of(entry + "#general.fieldCreatorRange"), handles(read));
        assertNull(read.nextCursor());
        var get = queries.getLast();
        assertEquals(Evidence.Operation.GET, get.operation());
        assertEquals(text(entry), get.arguments().values().get("id"));

        var prefixed = execute(service, "read_config", Map.of("handle", text("config:" + entry), "key", text("general.fieldCreatorRange")));
        assertEquals(Evidence.Outcome.COMPLETE, prefixed.outcome());

        var stillUnknown = execute(service, "read_config", Map.of("handle", text("naturesaura:CLIENT:other.toml"), "key", text("a")));
        assertEquals(Evidence.Outcome.ERROR, stillUnknown.outcome());
        assertTrue(stillUnknown.detail().contains("config:" + entry + "#general.fieldCreatorRange"), stillUnknown.detail());
    }

    private static KnowledgeService service(KnowledgeProvider... providers) {
        return new KnowledgeService(List.of(providers), () -> SCOPE,
                plan -> CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null)),
                (identities, scope) -> CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null)));
    }

    private static Evidence.Result search(KnowledgeService service, Map<String, Evidence.JsonValue> arguments) {
        return execute(service, "search_pack", arguments);
    }

    private static Evidence.Result execute(KnowledgeService service, String tool, Map<String, Evidence.JsonValue> arguments) {
        return service.execute(new PackSageTools.ReadRequest(tool, new Evidence.JsonObject(new LinkedHashMap<>(arguments)), SCOPE))
                .toCompletableFuture().join();
    }

    private static List<String> handles(Evidence.Result result) {
        return result.records().stream().map(record -> record.source().sourceHandle()).toList();
    }

    private static Evidence.Result page(List<Evidence.Record> records, String next) {
        return new Evidence.Result(records, Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, next);
    }

    private static Evidence.JsonString text(String value) {
        return new Evidence.JsonString(value);
    }

    private static Evidence.JsonNumber number(int value) {
        return new Evidence.JsonNumber(BigDecimal.valueOf(value));
    }

    private static Evidence.JsonArray kinds(String... values) {
        return new Evidence.JsonArray(java.util.Arrays.stream(values).map(value -> (Evidence.JsonValue) new Evidence.JsonString(value)).toList());
    }

    private static Evidence.Record record(String provider, String handle, Evidence.RecordKind kind) {
        return new Evidence.Record(new Evidence.Source(SCOPE, provider, "fixture", handle, 1, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()), kind, handle,
                new Evidence.JsonObject(Map.of()), List.of());
    }

    private static Evidence.Record configRecord(String entry, String key) {
        return new Evidence.Record(new Evidence.Source(SCOPE, "config", "fixture", entry + "#" + key, 1, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.SAVED_CONFIG, List.of()),
                Evidence.RecordKind.CONFIG, key, new Evidence.JsonObject(Map.of("handle", text(entry), "key", text(key))), List.of());
    }

    private static class PagedProvider implements KnowledgeProvider {
        private final String id;
        private final Function<Evidence.Query, Evidence.Result> pages;
        private final List<Evidence.Query> queries = new ArrayList<>();

        private PagedProvider(String id, Function<Evidence.Query, Evidence.Result> pages) {
            this.id = id;
            this.pages = pages;
        }

        @Override public String id() { return id; }
        @Override public ProviderStatus status() { return new ProviderStatus(Availability.AVAILABLE, "fixture", "fixture"); }
        @Override public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
            queries.add(query);
            return CompletableFuture.completedFuture(pages.apply(query));
        }
        @Override public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.UNSUPPORTED, "fixture");
        }
    }
}
