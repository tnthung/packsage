package dev.packsage.knowledge;

import dev.packsage.knowledge.nativegame.NativeSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeIndexTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "native", 1);

    @Test
    void exactIdNameTagAndComponentQueriesAreBoundedAndScopeCursorAware() {
        var index = new KnowledgeIndex();
        index.replace(new NativeSnapshot(SCOPE, List.of(record("mod:lantern", "Lantern", "#lights", "glowing"),
                record("mod:lamp", "Lamp", "#lights", "warm"))));

        var exact = index.search(new Evidence.Query(Evidence.Operation.GET,
                new Evidence.JsonObject(Map.of("id", new Evidence.JsonString("mod:lantern"))), 1, null, null), SCOPE);
        assertEquals(List.of("mod:lantern"), exact.toCompletableFuture().join().records().stream()
                .map(record -> record.source().sourceHandle()).toList());

        var page = index.search(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of("tag", new Evidence.JsonString("#lights"))), 1, null, null), SCOPE)
                .toCompletableFuture().join();
        assertEquals(1, page.records().size());
        assertNotEquals(null, page.nextCursor());
        var stale = index.search(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of("tag", new Evidence.JsonString("#lights"))), 1, SCOPE, page.nextCursor()),
                new Evidence.Scope("world", "en_us", "team", "native", 2));
        assertEquals(Evidence.Outcome.UNKNOWN, stale.toCompletableFuture().join().outcome());

        var text = index.search(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("lantern"))), 10, null, null), SCOPE)
                .toCompletableFuture().join();
        assertEquals(List.of("mod:lantern"), text.records().stream().map(record -> record.source().sourceHandle()).toList());

        var component = index.search(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of("component", new Evidence.JsonString("glowing"))), 10, null, null), SCOPE)
                .toCompletableFuture().join();
        assertEquals(List.of("mod:lantern"), component.records().stream().map(record -> record.source().sourceHandle()).toList());

        var invalid = index.search(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of()), 1, null, "not-a-cursor"), SCOPE).toCompletableFuture().join();
        assertEquals(Evidence.Outcome.ERROR, invalid.outcome());

        var outOfRange = index.search(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of()), 1, SCOPE, "99"), SCOPE).toCompletableFuture().join();
        assertEquals(Evidence.Outcome.ERROR, outOfRange.outcome());

        var mixedScope = new Evidence.Scope("other", "en_us", "team", "native", 1);
        index.replace(new NativeSnapshot(SCOPE, List.of(record("mod:lantern", "Lantern", "#lights", "glowing"),
                record(mixedScope, "mod:stale", "Stale", "#lights", "stale"))));
        var mixed = index.search(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("stale"))), 10, null, null), SCOPE)
                .toCompletableFuture().join();
        assertTrue(mixed.records().isEmpty());
        assertEquals(Evidence.Outcome.PARTIAL, mixed.outcome());
    }

    @Test
    void emptySnapshotIsUnavailableInsteadOfCompleteNoMatch() {
        var index = new KnowledgeIndex();
        index.replace(new NativeSnapshot(SCOPE, List.of()));

        var result = index.search(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of()), 10, null, null), SCOPE).toCompletableFuture().join();

        assertEquals(Evidence.Coverage.UNAVAILABLE, result.coverage());
        assertEquals(Evidence.Outcome.UNKNOWN, result.outcome());
    }

    private static Evidence.Record record(String id, String title, String tag, String component) {
        return record(SCOPE, id, title, tag, component);
    }

    private static Evidence.Record record(Evidence.Scope scope, String id, String title, String tag, String component) {
        var source = new Evidence.Source(scope, "native", "1.21.1", id, 1, Instant.EPOCH, Evidence.Activity.ACTIVE,
                Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of("fixture"));
        var payload = new Evidence.JsonObject(Map.of("tags", new Evidence.JsonArray(List.of(new Evidence.JsonString(tag))),
                "components", new Evidence.JsonObject(Map.of("minecraft:name", new Evidence.JsonString(component)))));
        return new Evidence.Record(source, Evidence.RecordKind.ITEM, title, payload, List.of());
    }
}
