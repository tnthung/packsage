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
import java.util.concurrent.atomic.AtomicReference;

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
    void descriptionsGuideCommonSearchAndEvidenceReferences() {
        var definitions = PackSageTools.definitions();
        var document = definitions.stream().filter(value -> value.name().equals("search_document")).findFirst().orElseThrow().description();
        assertTrue(document.contains("one case-insensitive literal phrase"));
        assertTrue(document.contains("same query, filters and limit"));
        var recipe = definitions.stream().filter(value -> value.name().equals("search_recipe")).findFirst().orElseThrow().description();
        assertTrue(recipe.contains("outputItem without text"));
        assertTrue(recipe.contains("native and JEI evidence distinct"));
        assertTrue(recipe.contains("native lookup is partial or unavailable"));
        var inventory = definitions.stream().filter(value -> value.name().equals("search_inventory")).findFirst().orElseThrow().description();
        assertTrue(inventory.contains("Player summaries do not include nested containers"));
        assertTrue(inventory.contains("returned slotReference"));
        assertTrue(inventory.contains("does not include accessory slots"));
        for (var name : List.of("read_evidence", "read_progress")) {
            var description = definitions.stream().filter(value -> value.name().equals(name)).findFirst().orElseThrow().description();
            assertTrue(description.contains("returned record references only"));
            assertTrue(description.contains("Do not pass search cursors"));
        }
    }

    @Test
    void dimensionSelectorsAreAdvertisedAndReachDispatch() throws Exception {
        var requests = new java.util.ArrayList<PackSageTools.ReadRequest>();
        var tools = new PackSageTools(request -> {
            requests.add(request);
            return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE,
                    Evidence.Outcome.COMPLETE, null));
        });
        var schema = JsonParser.parseString(PackSageTools.definitions().stream()
                .filter(value -> value.name().equals("search_registry")).findFirst().orElseThrow().serializedSchema()).getAsJsonObject();
        for (String registry : List.of("dimension", "dimension_type")) {
            assertTrue(schema.getAsJsonObject("properties").getAsJsonObject("registry").getAsJsonArray("enum")
                    .contains(new com.google.gson.JsonPrimitive(registry)));
            assertFalse(tools.execute(new ModelBackend.ToolCall(registry, "search_registry",
                    "{\"registry\":\"" + registry + "\",\"filter\":{\"ids\":[\"minecraft:overworld\"]}}"), SCOPE)
                    .toCompletableFuture().get().result().error());
        }
        assertEquals(2, requests.size());
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

        var response = tools.execute(new ModelBackend.ToolCall("call", "search_document", "{\"query\":\"torch\"}"), SCOPE)
                .toCompletableFuture().get();

        assertEquals(SCOPE, requested.get().scope());
        assertFalse(response.result().error());
        assertEquals(List.of(record), response.records());
        var json = JsonParser.parseString(response.result().serializedResult()).getAsJsonObject();
        assertEquals(7, json.getAsJsonArray("records").get(0).getAsJsonObject().getAsJsonObject("payload").get("count").getAsInt());
        assertEquals("minecraft:torch", json.getAsJsonArray("records").get(0).getAsJsonObject()
                .getAsJsonObject("source").get("sourceHandle").getAsString());
        assertTrue(json.getAsJsonArray("records").get(0).getAsJsonObject().get("navigable").getAsBoolean());
    }

    @Test
    void serializesResultDetailSoTheModelCanCorrectItself() throws Exception {
        var tools = new PackSageTools(request -> CompletableFuture.completedFuture(new Evidence.Result(List.of(),
                Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null, "cursor does not match this query")));

        var response = tools.execute(new ModelBackend.ToolCall("a", "search_document", "{\"query\":\"none\"}"), SCOPE)
                .toCompletableFuture().get();

        assertTrue(response.result().error());
        var json = JsonParser.parseString(response.result().serializedResult()).getAsJsonObject();
        assertEquals("cursor does not match this query", json.get("detail").getAsString());

        var plain = new PackSageTools(request -> CompletableFuture.completedFuture(new Evidence.Result(List.of(),
                Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null)));
        var plainJson = JsonParser.parseString(plain.execute(new ModelBackend.ToolCall("b", "search_document", "{\"query\":\"none\"}"), SCOPE)
                .toCompletableFuture().get().result().serializedResult()).getAsJsonObject();
        assertFalse(plainJson.has("detail"));
    }

    @Test
    void marksRecordsWithoutTargetsAsNotNavigable() throws Exception {
        var record = new Evidence.Record(new Evidence.Source(SCOPE, "native", "fixture", "item:torch", 1,
                Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()),
                Evidence.RecordKind.ITEM, "Torch", new Evidence.JsonObject(Map.of()), List.of());
        var tools = new PackSageTools(request -> CompletableFuture.completedFuture(new Evidence.Result(List.of(record),
                Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null)));

        var json = JsonParser.parseString(tools.execute(new ModelBackend.ToolCall("a", "search_document", "{\"query\":\"torch\"}"), SCOPE)
                .toCompletableFuture().get().result().serializedResult()).getAsJsonObject();

        assertFalse(json.getAsJsonArray("records").get(0).getAsJsonObject().get("navigable").getAsBoolean());
    }

    @Test
    void providerStatusRecordsDoNotCountTowardTheRecordCap() throws Exception {
        var records = new java.util.ArrayList<Evidence.Record>();
        records.add(new Evidence.Record(new Evidence.Source(SCOPE, "jei", "fixture", "status:jei", 1, Instant.EPOCH,
                Evidence.Activity.UNKNOWN, Evidence.Coverage.UNAVAILABLE, Evidence.Authority.UNKNOWN, List.of()),
                Evidence.RecordKind.OTHER, "JEI unavailable", new Evidence.JsonObject(Map.of()), List.of()));
        for (int index = 0; index < 64; index++) {
            records.add(new Evidence.Record(new Evidence.Source(SCOPE, "native", "fixture", "item:" + index, 1, Instant.EPOCH,
                    Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()),
                    Evidence.RecordKind.ITEM, "Item " + index, new Evidence.JsonObject(Map.of()), List.of()));
        }
        var tools = new PackSageTools(request -> CompletableFuture.completedFuture(new Evidence.Result(records,
                Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null)));

        var response = tools.execute(new ModelBackend.ToolCall("a", "search_document", "{\"query\":\"item\"}"), SCOPE)
                .toCompletableFuture().get();

        assertEquals(65, response.records().size());
        assertEquals("COMPLETE", JsonParser.parseString(response.result().serializedResult()).getAsJsonObject().get("outcome").getAsString());
    }

    @Test
    void distinguishesEmptyMatchesFromFailedExtractionAndRejectsForeignScope() throws Exception {
        var tools = new PackSageTools(request -> CompletableFuture.completedFuture(new Evidence.Result(List.of(),
                Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null)));
        assertFalse(tools.execute(new ModelBackend.ToolCall("a", "search_document", "{\"query\":\"none\"}"), SCOPE)
                .toCompletableFuture().get().result().error());

        var failed = new PackSageTools(request -> CompletableFuture.failedFuture(new IllegalStateException("unreadable")));
        assertTrue(failed.execute(new ModelBackend.ToolCall("b", "search_document", "{\"query\":\"none\"}"), SCOPE)
                .toCompletableFuture().get().result().error());

        var foreign = new Evidence.Source(new Evidence.Scope("other-world", "resources", "team", "runtime", 1),
                "native", "1.21.1", "torch", 1, Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE,
                Evidence.Authority.LOADED, List.of());
        var stale = new PackSageTools(request -> CompletableFuture.completedFuture(new Evidence.Result(List.of(
                new Evidence.Record(foreign, Evidence.RecordKind.ITEM, "Torch", new Evidence.JsonObject(Map.of()), List.of())),
                Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null)));
        assertTrue(stale.execute(new ModelBackend.ToolCall("c", "search_document", "{\"query\":\"torch\"}"), SCOPE)
                .toCompletableFuture().get().result().error());
    }
    @Test
    void outputEnvelopeCountsUtf8BytesAndPreservesTypedAdapterErrors() throws Exception {
        var record = new Evidence.Record(new Evidence.Source(SCOPE, "native", "fixture", "large", 1, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()),
                Evidence.RecordKind.ITEM, "Large", new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("\u754C".repeat(50000)))), List.of());
        var response = new PackSageTools(request -> CompletableFuture.completedFuture(new Evidence.Result(List.of(record),
                Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null))).execute(
                new ModelBackend.ToolCall("large", "search_registry", "{\"registry\":\"item\"}"), SCOPE).toCompletableFuture().get();
        assertTrue(response.result().serializedResult().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 131072);
        assertTrue(response.result().serializedResult().contains("PARTIAL"));
        var error = new PackSageTools(request -> CompletableFuture.failedFuture(new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "repeat lookup")))
                .execute(new ModelBackend.ToolCall("bad", "search_recipe", "{}"), SCOPE).toCompletableFuture().get();
        assertTrue(error.result().error());
        assertTrue(error.result().serializedResult().contains("INVALID_CURSOR"));
        assertTrue(error.result().serializedResult().contains("repeat lookup"));
    }
}
