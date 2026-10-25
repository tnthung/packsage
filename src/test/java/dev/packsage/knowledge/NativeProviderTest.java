package dev.packsage.knowledge;

import dev.packsage.knowledge.nativegame.NativeProvider;
import dev.packsage.knowledge.nativegame.NativeSnapshot;
import dev.packsage.model.PackSageTools;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeProviderTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "native", 1);

    @Test
    void advertisesAllNativeRegistryKinds() {
        var provider = new NativeProvider(() -> new NativeSnapshot(SCOPE, List.of()), Runnable::run);

        assertEquals(List.of("item", "block", "item_tag", "enchantment", "mob_effect", "potion", "active_effect", "player_attribute", "dimension", "dimension_type"),
                provider.metadata().registries());
    }

    @Test
    void activeRecipeReplacesShippedRecipeAndRemovedRecipeIsAbsent() throws Exception {
        var active = record("minecraft:torch", Evidence.Activity.ACTIVE, "Torch", "minecraft:coal");
        var shipped = record("minecraft:torch", Evidence.Activity.SHIPPED, "Torch", "minecraft:charcoal");
        var removed = record("packsage:removed", Evidence.Activity.INACTIVE, "Removed", "minecraft:stone");
        var provider = new NativeProvider(() -> new NativeSnapshot(SCOPE, List.of(active, shipped, removed)), Runnable::run);

        var result = provider.query(new Evidence.Query(Evidence.Operation.GET,
                new Evidence.JsonObject(Map.of("id", new Evidence.JsonString("minecraft:torch"))), 10, null, null), SCOPE)
                .toCompletableFuture().get();

        assertEquals(List.of("minecraft:torch"), result.records().stream().map(record -> record.source().sourceHandle()).toList());
        assertEquals("minecraft:coal", result.records().get(0).recipePayload().inputs().get(0).alternatives().get(0)
                .comparisonIdentity());
        assertTrue(result.records().stream().noneMatch(record -> record.source().sourceHandle().equals("packsage:removed")));
    }

    @Test
    void failedDecodingRemainsVisibleAsUnknownEvidence() throws Exception {
        var unknown = record("mod:custom", Evidence.Activity.UNKNOWN, "Custom", null);
        var provider = new NativeProvider(() -> new NativeSnapshot(SCOPE, List.of(unknown)), Runnable::run);

        var result = provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("custom"))), 10, null, null), SCOPE)
                .toCompletableFuture().get();

        assertEquals(Evidence.DecodingStatus.UNKNOWN, result.records().get(0).recipePayload().decodingStatus());
        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
    }

    @Test
    void failedOwningThreadCaptureIsAnErrorResult() throws Exception {
        var provider = new NativeProvider(() -> {
            throw new IllegalStateException("capture failed");
        }, Runnable::run);

        var result = provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of()), 10, null, null), SCOPE).toCompletableFuture().get();

        assertEquals(Evidence.Coverage.ERROR, result.coverage());
        assertEquals(Evidence.Outcome.ERROR, result.outcome());
    }

    @Test
    void scopeMismatchDoesNotPublishCapturedSnapshot() throws Exception {
        var otherScope = new Evidence.Scope("other", "en_us", "team", "native", 1);
        var provider = new NativeProvider(() -> new NativeSnapshot(otherScope, List.of(record("mod:other", Evidence.Activity.ACTIVE,
                "Other", "minecraft:stone"))), Runnable::run);

        var result = provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of()), 10, null, null), SCOPE).toCompletableFuture().get();

        assertEquals(Evidence.Outcome.UNKNOWN, result.outcome());
        assertEquals(KnowledgeProvider.Availability.UNAVAILABLE, provider.status().availability());
    }

    @Test
    void planRecipeUsesFreshCopiedSnapshot() throws Exception {
        var provider = new NativeProvider(() -> new NativeSnapshot(SCOPE, List.of(recipe("mod:free"))), Runnable::run);

        var planned = provider.planRecipe("mod:free", 3, SCOPE, 4).toCompletableFuture().get();

        assertEquals(Evidence.Outcome.COMPLETE, planned.outcome());
    }

    @Test
    void removedInventoryHandlesRereadAsUnavailable() {
        var provider = new NativeProvider(() -> new NativeSnapshot(SCOPE, List.of()), Runnable::run, () -> SCOPE);

        for (String handle : List.of("inventory:main:0", "inventory-summary:player", "storage:block:0", "storage-entry:block:0:1")) {
            var context = new Toolbox.Context(() -> SCOPE, Map.of(handle, new Toolbox.Reference("native", handle,
                    Evidence.RecordKind.ITEM, SCOPE)), new AdapterRegistry(), new Toolbox.DocumentBudget(), null);
            var request = new PackSageTools.ReadRequest("read_evidence", new Evidence.JsonObject(Map.of("handles",
                    new Evidence.JsonArray(List.of(new Evidence.JsonString(handle))))), SCOPE);

            var thrown = assertThrows(CompletionException.class, () -> provider.readEvidence(request, context).toCompletableFuture().join());

            var failure = assertInstanceOf(Toolbox.Failure.class, thrown.getCause(), handle);
            assertEquals(Toolbox.Code.UNAVAILABLE, failure.code(), handle);
            assertEquals("UNAVAILABLE: this inventory record came from a reader that was removed; read the items again with list_items or inspect_item",
                    failure.getMessage(), handle);
        }
    }

    @Test
    void sameScopeParallelRequestsBothPublishEvidence() throws Exception {
        var queued = new ArrayDeque<Runnable>();
        Executor executor = command -> queued.add(command);
        var provider = new NativeProvider(() -> new NativeSnapshot(SCOPE, List.of(recipe("mod:parallel"))), executor);
        var query = new Evidence.Query(Evidence.Operation.GET,
                new Evidence.JsonObject(Map.of("id", new Evidence.JsonString("mod:parallel"))), 10, null, null);

        var first = provider.query(query, SCOPE).toCompletableFuture();
        var second = provider.planRecipe("mod:parallel", 1, SCOPE, 4).toCompletableFuture();
        while (!queued.isEmpty()) queued.removeFirst().run();

        assertEquals(Evidence.Outcome.COMPLETE, first.get().outcome());
        assertEquals(Evidence.Outcome.COMPLETE, second.get().outcome());
    }

    @Test
    void scopeChangeRejectsOlderCompletion() throws Exception {
        var otherScope = new Evidence.Scope("other", "en_us", "team", "native", 2);
        var queued = new ArrayDeque<Runnable>();
        Executor executor = command -> queued.add(command);
        var provider = new NativeProvider(() -> new NativeSnapshot(SCOPE, List.of(record("mod:scope", Evidence.Activity.ACTIVE,
                "Scope", "minecraft:stone"))), executor);
        var query = new Evidence.Query(Evidence.Operation.GET,
                new Evidence.JsonObject(Map.of("id", new Evidence.JsonString("mod:scope"))), 10, null, null);

        var initial = provider.query(query, SCOPE).toCompletableFuture();
        while (!queued.isEmpty()) queued.removeFirst().run();
        assertEquals(Evidence.Outcome.COMPLETE, initial.get().outcome());

        var older = provider.query(query, SCOPE).toCompletableFuture();
        var newer = provider.query(query, otherScope).toCompletableFuture();
        while (!queued.isEmpty()) queued.removeFirst().run();

        assertEquals(Evidence.Outcome.UNKNOWN, older.get().outcome());
        assertEquals(Evidence.Outcome.UNKNOWN, newer.get().outcome());
        assertEquals(KnowledgeProvider.Availability.UNAVAILABLE, provider.status().availability());
    }

    private static Evidence.Record record(String id, Evidence.Activity activity, String title, String input) {
        var source = new Evidence.Source(SCOPE, "native", "1.21.1", id, 1, Instant.EPOCH, activity,
                input == null ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                Evidence.Authority.LOADED, List.of("fixture"));
        Evidence.RecipePayload payload = input == null ? new Evidence.RecipePayload(id, List.of(), List.of(), List.of(), List.of(),
                List.of(), Evidence.DecodingStatus.UNKNOWN) : new Evidence.RecipePayload(id,
                List.of(new Evidence.RecipeSlot("slot", List.of(new Evidence.RecipeAlternative("item", input,
                        new Evidence.JsonObject(Map.of()), 1, Evidence.Consumption.CONSUMED)))),
                List.of(new Evidence.RecipeOutput("item", id, new Evidence.JsonObject(Map.of()), 1)), List.of(), List.of(), List.of(),
                Evidence.DecodingStatus.VERIFIED);
        return new Evidence.Record(source, Evidence.RecordKind.RECIPE, title, new Evidence.JsonObject(Map.of()), List.of(), payload);
    }

    private static Evidence.Record recipe(String id) {
        var source = new Evidence.Source(SCOPE, "native", "1.21.1", id, 1, Instant.EPOCH, Evidence.Activity.ACTIVE,
                Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of("fixture"));
        var payload = new Evidence.RecipePayload(id, List.of(), List.of(new Evidence.RecipeOutput("item", id,
                new Evidence.JsonObject(Map.of()), 1)), List.of(), List.of(), List.of(), Evidence.DecodingStatus.VERIFIED);
        return new Evidence.Record(source, Evidence.RecordKind.RECIPE, id, new Evidence.JsonObject(Map.of()), List.of(), payload);
    }
}
