package dev.packsage.model;

import com.google.gson.JsonParser;
import dev.packsage.knowledge.Evidence;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PackSageToolsTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "resources", "team", "runtime", 1);

    @Test
    void exposesOnlyTheEightBoundedReadTools() {
        assertEquals(List.of("search_pack", "read_evidence", "plan_recipe", "read_inventory", "read_progress",
                "list_configs", "search_config", "read_config"), PackSageTools.definitions().stream()
                .map(ModelBackend.ToolDefinition::name).toList());
        for (var definition : PackSageTools.definitions()) {
            var schema = JsonParser.parseString(definition.serializedSchema()).getAsJsonObject();
            assertFalse(schema.get("additionalProperties").getAsBoolean());
            assertEquals(schema.getAsJsonObject("properties").size(), schema.getAsJsonArray("required").size());
        }
    }

    @Test
    void rejectsUnknownToolsExtraArgumentsAndInvalidBoundsBeforeDispatch() throws Exception {
        var dispatched = new AtomicInteger();
        var tools = new PackSageTools(request -> {
            dispatched.incrementAndGet();
            return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE,
                    Evidence.Outcome.COMPLETE, null));
        });
        for (var call : List.of(new ModelBackend.ToolCall("a", "run_command", "{}"),
                new ModelBackend.ToolCall("b", "search_pack", "{\"text\":\"iron\",\"path\":\"../config\"}"),
                new ModelBackend.ToolCall("c", "plan_recipe", "{\"identity\":\"minecraft:torch\",\"quantity\":0}"),
                new ModelBackend.ToolCall("d", "search_pack", "{\"text\":\"iron\",\"limit\":1000}"),
                new ModelBackend.ToolCall("e", "read_inventory", "{\"identities\":[1]}"))) {
            var response = tools.execute(call, SCOPE).toCompletableFuture().get();
            assertTrue(response.result().error(), call.callId());
            assertEquals(call.callId(), response.result().callId());
        }
        assertEquals(0, dispatched.get());
    }

    @Test
    void preservesExactScopeAndReturnsReadablePayloadWithSourceAndTargets() throws Exception {
        var requested = new AtomicReference<PackSageTools.ReadRequest>();
        var source = new Evidence.Source(SCOPE, "native", "1.21.1", "minecraft:torch", 1, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of("pack:override"));
        var target = new Evidence.NavigationTarget(SCOPE, "native", "minecraft:torch", "minecraft:torch");
        var record = new Evidence.Record(source, Evidence.RecordKind.RECIPE, "Torch",
                new Evidence.JsonObject(Map.of("count", new Evidence.JsonNumber(java.math.BigDecimal.valueOf(7)))), List.of(target));
        var tools = new PackSageTools(request -> {
            requested.set(request);
            return CompletableFuture.completedFuture(new Evidence.Result(List.of(record), Evidence.Coverage.COMPLETE,
                    Evidence.Outcome.COMPLETE, null));
        });

        var response = tools.execute(new ModelBackend.ToolCall("call", "search_pack", "{\"text\":\"torch\"}"), SCOPE)
                .toCompletableFuture().get();

        assertEquals(SCOPE, requested.get().scope());
        assertFalse(response.result().error());
        assertEquals(List.of(record), response.records());
        var json = JsonParser.parseString(response.result().serializedResult()).getAsJsonObject();
        assertEquals(7, json.getAsJsonArray("records").get(0).getAsJsonObject().getAsJsonObject("payload").get("count").getAsInt());
        assertEquals("minecraft:torch", json.getAsJsonArray("records").get(0).getAsJsonObject()
                .getAsJsonObject("source").get("sourceHandle").getAsString());
    }

    @Test
    void serializesResultDetailSoTheModelCanCorrectItself() throws Exception {
        var tools = new PackSageTools(request -> CompletableFuture.completedFuture(new Evidence.Result(List.of(),
                Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null, "cursor does not match this query")));

        var response = tools.execute(new ModelBackend.ToolCall("a", "search_pack", "{\"text\":\"none\"}"), SCOPE)
                .toCompletableFuture().get();

        assertTrue(response.result().error());
        var json = JsonParser.parseString(response.result().serializedResult()).getAsJsonObject();
        assertEquals("cursor does not match this query", json.get("detail").getAsString());

        var plain = new PackSageTools(request -> CompletableFuture.completedFuture(new Evidence.Result(List.of(),
                Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null)));
        var plainJson = JsonParser.parseString(plain.execute(new ModelBackend.ToolCall("b", "search_pack", "{\"text\":\"none\"}"), SCOPE)
                .toCompletableFuture().get().result().serializedResult()).getAsJsonObject();
        assertFalse(plainJson.has("detail"));
    }

    @Test
    void distinguishesEmptyMatchesFromFailedExtractionAndRejectsForeignScope() throws Exception {
        var tools = new PackSageTools(request -> CompletableFuture.completedFuture(new Evidence.Result(List.of(),
                Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null)));
        assertFalse(tools.execute(new ModelBackend.ToolCall("a", "search_pack", "{\"text\":\"none\"}"), SCOPE)
                .toCompletableFuture().get().result().error());

        var failed = new PackSageTools(request -> CompletableFuture.failedFuture(new IllegalStateException("unreadable")));
        assertTrue(failed.execute(new ModelBackend.ToolCall("b", "search_pack", "{\"text\":\"none\"}"), SCOPE)
                .toCompletableFuture().get().result().error());

        var foreign = new Evidence.Source(new Evidence.Scope("other-world", "resources", "team", "runtime", 1),
                "native", "1.21.1", "torch", 1, Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE,
                Evidence.Authority.LOADED, List.of());
        var stale = new PackSageTools(request -> CompletableFuture.completedFuture(new Evidence.Result(List.of(
                new Evidence.Record(foreign, Evidence.RecordKind.ITEM, "Torch", new Evidence.JsonObject(Map.of()), List.of())),
                Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null)));
        assertTrue(stale.execute(new ModelBackend.ToolCall("c", "search_pack", "{\"text\":\"torch\"}"), SCOPE)
                .toCompletableFuture().get().result().error());
    }
}
