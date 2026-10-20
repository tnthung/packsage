package dev.packsage.knowledge;

import dev.packsage.model.PackSageTools;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ToolboxTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "resources", "team", "runtime", 1);

    @Test
    void onlyNotHandledFallsThroughIncludingSynchronousDeclines() {
        for (var coverage : Evidence.Coverage.values()) {
            var called = new ArrayList<String>();
            var registry = new AdapterRegistry();
            registry.register(new Adapter("decline") {
                @Override public CompletionStage<Evidence.Result> searchInventory(PackSageTools.ReadRequest request, Context context) {
                    called.add("decline");
                    throw new Failure(Code.NOT_HANDLED, "not mine");
                }
            });
            var expected = new Evidence.Result(List.of(), coverage, Evidence.Outcome.PARTIAL, null);
            registry.register(new Adapter("owner") {
                @Override public CompletionStage<Evidence.Result> searchInventory(PackSageTools.ReadRequest request, Context context) {
                    called.add("owner");
                    return CompletableFuture.completedFuture(expected);
                }
            });
            registry.register(new Adapter("later") {
                @Override public CompletionStage<Evidence.Result> searchInventory(PackSageTools.ReadRequest request, Context context) {
                    fail("handled empty/partial result must stop dispatch");
                    return null;
                }
            });
            assertSame(expected, registry.dispatch(new PackSageTools.ReadRequest("search_inventory", new Evidence.JsonObject(Map.of()), SCOPE),
                    new Toolbox.Context(() -> SCOPE, Map.of(), registry, new Toolbox.DocumentBudget(), null)).toCompletableFuture().join());
            assertEquals(List.of("decline", "owner"), called);
        }
    }

    @Test
    void realFailuresStopAndDefaultsDeclineEveryOperation() {
        for (var code : Toolbox.Code.values()) {
            var registry = new AdapterRegistry();
            registry.register(new Adapter("owner") {
                @Override public CompletionStage<Evidence.Result> searchRecipe(PackSageTools.ReadRequest request, Context context) {
                    return CompletableFuture.failedFuture(new Failure(code, "original"));
                }
            });
            registry.register(new Adapter("later") {
                @Override public CompletionStage<Evidence.Result> searchRecipe(PackSageTools.ReadRequest request, Context context) {
                    assertEquals(Code.NOT_HANDLED, code);
                    return CompletableFuture.failedFuture(new Failure(Code.NOT_HANDLED, "later"));
                }
            });
            var failure = assertThrows(CompletionException.class, () -> registry.dispatch(
                    new PackSageTools.ReadRequest("search_recipe", new Evidence.JsonObject(Map.of()), SCOPE),
                    new Toolbox.Context(() -> SCOPE, Map.of(), registry, new Toolbox.DocumentBudget(), null)).toCompletableFuture().join());
            assertEquals(code, ((Toolbox.Failure) failure.getCause()).code());
        }
        var registry = new AdapterRegistry();
        registry.register(new Adapter("empty"));
        for (var definition : PackSageTools.definitions()) {
            var failure = assertThrows(CompletionException.class, () -> registry.dispatch(
                    new PackSageTools.ReadRequest(definition.name(), new Evidence.JsonObject(Map.of()), SCOPE),
                    new Toolbox.Context(() -> SCOPE, Map.of(), registry, new Toolbox.DocumentBudget(), null)).toCompletableFuture().join());
            assertEquals(Toolbox.Code.NOT_HANDLED, ((Toolbox.Failure) failure.getCause()).code());
        }
    }

    @Test
    void providerQualifiedRelationshipSearchAndFreshReadStayWithTheOwningAdapter() {
        var relationships = new AtomicReference<>(Evidence.deserializeObject(
                "{\"items\":{\"coverage\":\"COMPLETE\",\"values\":[{\"kind\":\"associated\",\"itemId\":\"minecraft:diamond\"}]}}"));
        var registry = new AdapterRegistry();
        registry.register(new Adapter("other_guide"));
        registry.register(new Adapter("relationship_guide") {
            @Override public CompletionStage<Evidence.Result> searchDocument(PackSageTools.ReadRequest request, Context context) {
                var source = new Evidence.Source(SCOPE, id, "fixture-1", "guide:entry", 1, java.time.Instant.EPOCH,
                        Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of());
                return CompletableFuture.completedFuture(new Evidence.Result(List.of(new Evidence.Record(source,
                        Evidence.RecordKind.GUIDE, "Entry", new Evidence.JsonObject(Map.of(
                                "relationships", relationships.get())), List.of())), Evidence.Coverage.COMPLETE,
                        Evidence.Outcome.COMPLETE, null));
            }

            @Override public CompletionStage<Evidence.Result> readEvidence(PackSageTools.ReadRequest request, Context context) {
                var reference = context.reference(request);
                var source = new Evidence.Source(SCOPE, reference.providerId(), "fixture-1", reference.sourceHandle(), 1,
                        java.time.Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE,
                        Evidence.Authority.LOADED, List.of());
                return CompletableFuture.completedFuture(new Evidence.Result(List.of(new Evidence.Record(source,
                        reference.kind(), "Entry", new Evidence.JsonObject(Map.of("relationships", relationships.get())),
                        List.of())), Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null));
            }
        });
        var context = new Toolbox.Context(() -> SCOPE, Map.of("relationship_guide:guide:entry", new Toolbox.Reference(
                "relationship_guide", "guide:entry", Evidence.RecordKind.GUIDE, SCOPE)), registry,
                new Toolbox.DocumentBudget(), null);
        var searchArguments = new Evidence.JsonObject(Map.of("query", new Evidence.JsonString("minecraft:diamond"),
                "filter", new Evidence.JsonObject(Map.of("provider", new Evidence.JsonString("relationship_guide")))));
        var search = new PackSageTools.ReadRequest("search_document", searchArguments, SCOPE);

        var found = registry.dispatch(search, context).toCompletableFuture().join();

        assertEquals("relationship_guide", found.records().getFirst().source().providerId());
        assertTrue(Evidence.serialize((Evidence.JsonObject) found.records().getFirst().payload().values().get("relationships"))
                .contains("minecraft:diamond"));
        relationships.set(Evidence.deserializeObject(
                "{\"items\":{\"coverage\":\"COMPLETE\",\"values\":[{\"kind\":\"associated\",\"itemId\":\"minecraft:emerald\"}]}}"));
        var read = new PackSageTools.ReadRequest("read_evidence", new Evidence.JsonObject(Map.of("handles",
                new Evidence.JsonArray(List.of(new Evidence.JsonString("relationship_guide:guide:entry"))))), SCOPE);
        var fresh = registry.dispatch(read, context).toCompletableFuture().join();

        assertEquals("relationship_guide", fresh.records().getFirst().source().providerId());
        assertTrue(Evidence.serialize((Evidence.JsonObject) fresh.records().getFirst().payload().values().get("relationships"))
                .contains("minecraft:emerald"));
        var invalid = new PackSageTools.ReadRequest("read_evidence", new Evidence.JsonObject(Map.of("handles",
                new Evidence.JsonArray(List.of(new Evidence.JsonString("relationship_guide:guessed"))))), SCOPE);
        var failure = assertThrows(CompletionException.class, () -> registry.dispatch(invalid, context).toCompletableFuture().join());
        assertEquals(Toolbox.Code.INVALID_REFERENCE, ((Toolbox.Failure) failure.getCause()).code());
    }

    @Test
    void registrationRejectsDuplicateIdentityAndTargetButAllowsOverlappingSources() {
        var registry = new AdapterRegistry();
        registry.register(new Adapter("first"));
        registry.register(new Adapter("second"));
        assertEquals(2, registry.metadata().size());
        assertThrows(IllegalArgumentException.class, () -> registry.register(new Adapter("first")));
        registry.register(new Adapter("target") {
            @Override public Metadata metadata() { return new Metadata(id, List.of(), false, List.of(), Map.of("example:bag", "{}")); }
        });
        assertThrows(IllegalArgumentException.class, () -> registry.register(new Adapter("duplicate_target") {
            @Override public Metadata metadata() { return new Metadata(id, List.of(), false, List.of(), Map.of("example:bag", "{}")); }
        }));
    }

    @Test
    void sharedDocumentBudgetRefusesWorkBeforeCrossingEachBound() {
        var budget = new Toolbox.DocumentBudget();
        for (int i = 0; i < 256; i++) assertTrue(budget.document());
        assertFalse(budget.document());
        assertTrue(budget.exhausted());
        budget = new Toolbox.DocumentBudget();
        for (int i = 0; i < 2048; i++) assertTrue(budget.value());
        assertFalse(budget.value());
        budget = new Toolbox.DocumentBudget();
        assertTrue(budget.bytes(96 * 1024));
        assertFalse(budget.bytes(1));
        assertEquals(0, budget.remainingBytes());
    }

    private static class Adapter implements Toolbox {
        final String id;
        Adapter(String id) { this.id = id; }
        @Override public Metadata metadata() { return new Metadata(id, List.of("guide"), true, List.of(), Map.of()); }
    }
}
