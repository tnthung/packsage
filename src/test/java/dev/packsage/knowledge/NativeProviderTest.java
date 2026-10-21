package dev.packsage.knowledge;

import dev.packsage.knowledge.nativegame.NativeProvider;
import dev.packsage.knowledge.nativegame.NativeSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeProviderTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "native", 1);

    @Test
    void advertisesAllNativeRegistryKinds() {
        var provider = new NativeProvider(() -> new NativeSnapshot(SCOPE, List.of()), Runnable::run);

        assertEquals(List.of("item", "block", "item_tag", "enchantment", "mob_effect", "potion", "active_effect", "dimension", "dimension_type"),
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
    void planRecipeUsesFreshCopiedSnapshotAndInventoryReadsOnlySelectedIdentities() throws Exception {
        var recipe = recipe("mod:free");
        var selected = inventory("mod:selected");
        var other = inventory("mod:other");
        var provider = new NativeProvider(() -> new NativeSnapshot(SCOPE, List.of(recipe, selected, other)), Runnable::run);

        var planned = provider.planRecipe("mod:free", 3, SCOPE, 4).toCompletableFuture().get();
        var inventory = provider.readInventory(List.of("mod:selected"), SCOPE).toCompletableFuture().get();

        assertEquals(Evidence.Outcome.COMPLETE, planned.outcome());
        assertEquals(List.of("inventory:mod:selected"), inventory.records().stream()
                .map(record -> record.source().sourceHandle()).toList());
    }

    @Test
    void emptyInventorySelectionReturnsEveryCapturedInventoryStackSeparately() throws Exception {
        var first = inventory("mod:duplicate", "hotbar", 0);
        var second = inventory("mod:duplicate", "main", 0);
        var provider = new NativeProvider(() -> new NativeSnapshot(SCOPE, List.of(recipe("mod:unrelated"), first, second)), Runnable::run);

        var result = provider.readInventory(List.of(), SCOPE).toCompletableFuture().get();

        assertEquals(Evidence.Outcome.COMPLETE, result.outcome());
        assertEquals(List.of("inventory:hotbar:0", "inventory:main:0"), result.records().stream()
                .map(record -> record.source().sourceHandle()).toList());
    }

    @Test
    void unavailableInventoryIsNotReportedAsAnEmptyCompleteObservation() throws Exception {
        var source = new Evidence.Source(SCOPE, "native", "1.21.1", "native:unavailable", 1, Instant.EPOCH,
                Evidence.Activity.UNKNOWN, Evidence.Coverage.UNAVAILABLE, Evidence.Authority.UNKNOWN, List.of());
        var unavailable = new Evidence.Record(source, Evidence.RecordKind.OTHER, "Player unavailable",
                new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unavailable"))), List.of());
        var provider = new NativeProvider(() -> new NativeSnapshot(SCOPE, List.of(unavailable)), Runnable::run);

        var result = provider.readInventory(List.of(), SCOPE).toCompletableFuture().get();

        assertEquals(Evidence.Coverage.UNAVAILABLE, result.coverage());
        assertEquals(Evidence.Outcome.UNKNOWN, result.outcome());
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

    private static Evidence.Record inventory(String id) {
        var source = new Evidence.Source(SCOPE, "native", "1.21.1", "inventory:" + id, 1, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of("selected-inventory"));
        var payload = new Evidence.JsonObject(Map.of("id", new Evidence.JsonString(id), "inventoryObservation",
                new Evidence.JsonString("selected")));
        return new Evidence.Record(source, Evidence.RecordKind.ITEM, id, payload, List.of());
    }

    private static Evidence.Record inventory(String id, String section, int slot) {
        var source = new Evidence.Source(SCOPE, "native", "1.21.1", "inventory:" + section + ":" + slot, 1,
                Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED,
                List.of("player-inventory"));
        var payload = new Evidence.JsonObject(Map.of("id", new Evidence.JsonString(id), "inventoryObservation",
                new Evidence.JsonString("occupied"), "section", new Evidence.JsonString(section),
                "slot", new Evidence.JsonNumber(java.math.BigDecimal.valueOf(slot))));
        return new Evidence.Record(source, Evidence.RecordKind.ITEM, id, payload, List.of());
    }
}
