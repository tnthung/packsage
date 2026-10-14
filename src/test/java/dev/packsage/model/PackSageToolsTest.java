package dev.packsage.model;

import com.google.gson.JsonParser;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PackSageToolsTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "resources", "team", "runtime", 1);
    private static final List<String> NAMES = List.of("search_document", "search_recipe", "search_registry", "search_inventory", "read_evidence", "read_progress", "plan_recipe", "list_configs", "search_config", "read_config", "search_history", "read_history");

    @Test
    void exposesExactlyTwelveToolsAndKeepsOptionalArgumentsOptional() {
        var definitions = PackSageTools.definitions();
        assertEquals(NAMES, definitions.stream().map(ModelBackend.ToolDefinition::name).toList());
        for (var definition : definitions) {
            var schema = JsonParser.parseString(definition.serializedSchema()).getAsJsonObject();
            assertFalse(schema.get("additionalProperties").getAsBoolean());
            assertTrue(schema.getAsJsonArray("required").size() < schema.getAsJsonObject("properties").size()
                    || definition.name().equals("read_evidence") || definition.name().equals("read_progress") || definition.name().equals("read_history"));
        }
        var inventory = JsonParser.parseString(definitions.get(3).serializedSchema()).getAsJsonObject();
        assertTrue(inventory.getAsJsonObject("properties").getAsJsonObject("target").has("oneOf"));
        assertEquals(32, inventory.getAsJsonObject("properties").getAsJsonObject("limit").get("maximum").getAsInt());
        for (var name : List.of("search_document", "search_recipe", "search_registry")) {
            var schema = definitions.stream().filter(value -> value.name().equals(name)).findFirst().orElseThrow();
            assertEquals(64, JsonParser.parseString(schema.serializedSchema()).getAsJsonObject().getAsJsonObject("properties").getAsJsonObject("limit").get("maximum").getAsInt());
        }
    }

    @Test
    void validatesNestedTargetFilterAndRegisteredMetadataBeforeDispatch() throws Exception {
        var calls = new AtomicInteger();
        var custom = new Toolbox.Metadata("ae2", List.of("network_doc"), false, List.of("channel"), Map.of(
                "ae2:network", "{\"type\":\"object\",\"properties\":{\"type\":{\"const\":\"ae2:network\"},\"reference\":{\"type\":\"string\",\"maxLength\":128}},\"required\":[\"type\",\"reference\"],\"additionalProperties\":false}"));
        var tools = new PackSageTools(request -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null)); }, List.of(custom));
        for (var arguments : List.of(
                "{\"target\":{\"type\":\"ae2:network\",\"reference\":\"seen\"}}",
                "{\"target\":{\"type\":\"ae2:network\",\"reference\":\"seen\"},\"filter\":{\"ids\":[\"minecraft:iron_ingot\"],\"tags\":[\"minecraft:ingots/iron\"]}}")) {
            assertFalse(tools.execute(new ModelBackend.ToolCall("ok", "search_inventory", arguments), SCOPE).toCompletableFuture().get().result().error());
        }
        assertFalse(tools.execute(new ModelBackend.ToolCall("doc", "search_document", "{\"query\":\"quest\"}"), SCOPE).toCompletableFuture().get().result().error());
        assertFalse(tools.execute(new ModelBackend.ToolCall("registry", "search_registry", "{\"registry\":\"channel\"}"), SCOPE).toCompletableFuture().get().result().error());
        for (var arguments : List.of(
                "{\"target\":{\"type\":\"unknown\"}}",
                "{\"target\":{\"type\":\"ae2:network\",\"reference\":\"seen\",\"extra\":true}}",
                "{\"target\":{\"type\":\"ae2:network\",\"reference\":5}}",
                "{\"target\":{\"type\":\"ae2:network\",\"reference\":\"seen\"},\"filter\":{\"ids\":[\"Bad:ID\"]}}",
                "{\"target\":{\"type\":\"ae2:network\",\"reference\":\"seen\"},\"filter\":{\"unknown\":true}}")) {
            assertTrue(tools.execute(new ModelBackend.ToolCall("bad", "search_inventory", arguments), SCOPE).toCompletableFuture().get().result().error());
        }
        assertEquals(4, calls.get());
    }

    @Test
    void omittedOptionalArgumentsAreAcceptedAndRequiredArgumentsRemainRequired() throws Exception {
        var requests = new java.util.ArrayList<PackSageTools.ReadRequest>();
        var tools = new PackSageTools(request -> { requests.add(request); return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null)); });
        for (var call : List.of(new ModelBackend.ToolCall("inv", "search_inventory", "{\"target\":{\"type\":\"player\"}}"),
                new ModelBackend.ToolCall("doc", "search_document", "{\"query\":\"iron\"}"),
                new ModelBackend.ToolCall("recipe", "search_recipe", "{}"),
                new ModelBackend.ToolCall("registry", "search_registry", "{\"registry\":\"item\"}"),
                new ModelBackend.ToolCall("history", "search_history", "{\"text\":\"iron\"}"))) {
            assertFalse(tools.execute(call, SCOPE).toCompletableFuture().get().result().error(), call.name());
        }
        assertEquals(5, requests.size());
        assertTrue(tools.execute(new ModelBackend.ToolCall("missing", "search_document", "{}"), SCOPE).toCompletableFuture().get().result().error());
        assertTrue(tools.execute(new ModelBackend.ToolCall("badid", "search_registry", "{\"registry\":\"item\",\"filter\":{\"ids\":[\"bad\"]}}"), SCOPE).toCompletableFuture().get().result().error());
        assertEquals(5, requests.size());
    }
}
