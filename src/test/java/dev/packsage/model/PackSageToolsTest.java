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
    void exposesOnlyTheBoundedReadTools() {
        assertEquals(List.of("search_pack", "read_evidence", "plan_recipe", "read_inventory", "read_storage", "read_progress",
                "list_configs", "search_config", "read_config", "search_history", "read_history"), PackSageTools.definitions().stream()
                .map(ModelBackend.ToolDefinition::name).toList());
        for (var definition : PackSageTools.definitions()) {
            var schema = JsonParser.parseString(definition.serializedSchema()).getAsJsonObject();
            assertFalse(schema.get("additionalProperties").getAsBoolean());
            assertEquals(schema.getAsJsonObject("properties").size(), schema.getAsJsonArray("required").size());
        }
    }

    @Test
    void storageToolsValidateTargetsFiltersAndPageBoundsBeforeDispatch() throws Exception {
        var requested = new AtomicInteger();
        var tools = new PackSageTools(request -> {
            requested.incrementAndGet();
            return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE,
                    Evidence.Outcome.UNKNOWN, null));
        });
        for (var target : List.of("opened", "targeted", "held")) {
            assertFalse(tools.execute(new ModelBackend.ToolCall("storage", "read_storage", "{\"target\":\"" + target
                    + "\",\"text\":null,\"identities\":null,\"limit\":32,\"cursor\":null}"), SCOPE)
                    .toCompletableFuture().get().result().error());
        }
        for (var arguments : List.of("{}", "{\"target\":null}", "{\"target\":\"nearby\"}",
                "{\"target\":\"held\",\"limit\":33}", "{\"target\":\"opened\",\"limit\":0}",
                "{\"target\":\"held\",\"identities\":[1]}", "{\"target\":\"opened\",\"write\":true}")) {
            assertTrue(tools.execute(new ModelBackend.ToolCall("invalid", "read_storage", arguments), SCOPE)
                    .toCompletableFuture().get().result().error());
        }
        assertEquals(3, requested.get());
    }

    @Test
    void inventoryAllowsOmittedOrNullIdentitiesAndDispatchesAnEmptySelection() throws Exception {
        var requested = new java.util.ArrayList<PackSageTools.ReadRequest>();
        var tools = new PackSageTools(request -> {
            requested.add(request);
            return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE,
                    Evidence.Outcome.COMPLETE, null));
        });

        for (var arguments : List.of("{}", "{\"identities\":null}")) {
            assertFalse(tools.execute(new ModelBackend.ToolCall("inventory", "read_inventory", arguments), SCOPE)
                    .toCompletableFuture().get().result().error());
        }

        assertEquals(2, requested.size());
        assertFalse(requested.getFirst().arguments().values().containsKey("identities"));
        assertInstanceOf(Evidence.JsonNull.class, requested.get(1).arguments().values().get("identities"));
        assertTrue(tools.execute(new ModelBackend.ToolCall("empty", "read_inventory", "{\"identities\":[]}"), SCOPE)
                .toCompletableFuture().get().result().error());
    }

    @Test
    void historyToolsDispatchValidRequestsAndRejectOutOfBoundsArguments() throws Exception {
        var dispatched = new AtomicInteger();
        var tools = new PackSageTools(request -> {
            dispatched.incrementAndGet();
            return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE,
                    Evidence.Outcome.COMPLETE, null));
        });
        assertFalse(tools.execute(new ModelBackend.ToolCall("search", "search_history",
                "{\"text\":\"old lantern\",\"limit\":8,\"cursor\":null}"), SCOPE)
                .toCompletableFuture().get().result().error());
        assertFalse(tools.execute(new ModelBackend.ToolCall("read", "read_history",
                "{\"handles\":[\"history:discovered\"],\"maxChars\":8000}"), SCOPE)
                .toCompletableFuture().get().result().error());
        assertEquals(2, dispatched.get());
        for (var call : List.of(new ModelBackend.ToolCall("blank", "search_history", "{\"text\":\"  \"}"),
                new ModelBackend.ToolCall("limit", "search_history", "{\"text\":\"lantern\",\"limit\":17}"),
                new ModelBackend.ToolCall("missing", "search_history", "{}"),
                new ModelBackend.ToolCall("empty", "read_history", "{\"handles\":[]}"),
                new ModelBackend.ToolCall("chars", "read_history", "{\"handles\":[\"h\"],\"maxChars\":16001}"),
                new ModelBackend.ToolCall("extra", "search_history", "{\"text\":\"lantern\",\"world\":\"another\"}"))) {
            assertTrue(tools.execute(call, SCOPE).toCompletableFuture().get().result().error(), call.callId());
        }
        assertEquals(2, dispatched.get());
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
    void jeiFiltersAreDeclaredAndOutputItemRequiresAValidJeiQuery() throws Exception {
        var schema = JsonParser.parseString(PackSageTools.definitions().stream()
                .filter(definition -> definition.name().equals("search_pack")).findFirst().orElseThrow()
                .serializedSchema()).getAsJsonObject();
        var properties = schema.getAsJsonObject("properties");
        assertEquals(List.of("native", "jei", "ftb_quests", "config", "patchouli"),
                properties.getAsJsonObject("provider").getAsJsonArray("enum")
                        .asList().stream().filter(com.google.gson.JsonElement::isJsonPrimitive)
                        .map(value -> value.getAsString()).toList());
        assertEquals(256, properties.getAsJsonObject("outputItem").get("maxLength").getAsInt());

        var dispatched = new AtomicInteger();
        var tools = new PackSageTools(request -> {
            dispatched.incrementAndGet();
            return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE,
                    Evidence.Outcome.COMPLETE, null));
        });
        for (var arguments : List.of("{\"text\":\"\",\"outputItem\":\"minecraft:stone\"}",
                "{\"text\":\"\",\"provider\":\"other\"}",
                "{\"text\":\"\",\"provider\":\"jei\",\"outputItem\":\"bad id\"}",
                "{\"text\":\"\",\"provider\":\"jei\",\"outputItem\":\"\"}")) {
            assertTrue(tools.execute(new ModelBackend.ToolCall("invalid", "search_pack", arguments), SCOPE)
                    .toCompletableFuture().get().result().error(), arguments);
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
        assertTrue(json.getAsJsonArray("records").get(0).getAsJsonObject().get("navigable").getAsBoolean());
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
    void marksRecordsWithoutTargetsAsNotNavigable() throws Exception {
        var record = new Evidence.Record(new Evidence.Source(SCOPE, "native", "fixture", "item:torch", 1,
                Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()),
                Evidence.RecordKind.ITEM, "Torch", new Evidence.JsonObject(Map.of()), List.of());
        var tools = new PackSageTools(request -> CompletableFuture.completedFuture(new Evidence.Result(List.of(record),
                Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null)));

        var json = JsonParser.parseString(tools.execute(new ModelBackend.ToolCall("a", "search_pack", "{\"text\":\"torch\"}"), SCOPE)
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

        var response = tools.execute(new ModelBackend.ToolCall("a", "search_pack", "{\"text\":\"item\"}"), SCOPE)
                .toCompletableFuture().get();

        assertEquals(65, response.records().size());
        assertEquals("COMPLETE", JsonParser.parseString(response.result().serializedResult()).getAsJsonObject().get("outcome").getAsString());
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
