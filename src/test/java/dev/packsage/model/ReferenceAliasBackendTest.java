package dev.packsage.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ReferenceAliasBackendTest {
    private static final String REFERENCE = "native:inventory:07cd5168-5d6e-48b3-95ac-9f9889de7ef6:player:main";
    private static final String SLOT = "native:inventory:07cd5168-5d6e-48b3-95ac-9f9889de7ef6:player:main:12";
    private static final ModelBackend.ToolResult RESULT = new ModelBackend.ToolResult("inventory", """
            {"coverage":"COMPLETE","outcome":"COMPLETE","records":[{
              "reference":"native:inventory:07cd5168-5d6e-48b3-95ac-9f9889de7ef6:player:main",
              "source":{"providerId":"native","sourceHandle":"inventory:07cd5168-5d6e-48b3-95ac-9f9889de7ef6:player:main"},
              "payload":{"slotReference":"native:inventory:07cd5168-5d6e-48b3-95ac-9f9889de7ef6:player:main:12",
                "text":"Quote native:inventory:07cd5168-5d6e-48b3-95ac-9f9889de7ef6:player:main exactly.",
                "identity":"minecraft:bundle","count":1},"title":"Bundle"}]}
            """, false);

    @Test
    void compactTypedReferencesRoundTripToExactReaderArgumentsWithoutChangingSourceText() {
        var actual = new FakeBackend();
        var events = new ArrayList<ModelBackend.Event>();
        var exchange = new ReferenceAliasBackend(actual).start(new ModelBackend.Request("model", "instructions", List.of(), List.of(), ModelBackend.SearchMode.DISABLED, "request", 512), events::add);
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                new ModelBackend.ToolCall("inventory", "search_inventory", "{\"target\":{\"type\":\"player\"}}"), List.of(), null));
        exchange.submit(RESULT);
        var record = JsonParser.parseString(actual.results.getFirst().serializedResult()).getAsJsonObject()
                .getAsJsonArray("records").get(0).getAsJsonObject();
        String reference = record.get("reference").getAsString();
        String slot = record.getAsJsonObject("payload").get("slotReference").getAsString();

        assertTrue(reference.matches("ref:[0-9a-f]{8}\\.[0-9a-z]+\\.[0-9a-z]+"), reference);
        assertTrue(slot.matches("slot:[0-9a-f]{8}\\.[0-9a-z]+\\.[0-9a-z]+"), slot);
        assertEquals(reference, record.getAsJsonObject("source").get("sourceHandle").getAsString());
        assertEquals("Quote " + REFERENCE + " exactly.", record.getAsJsonObject("payload").get("text").getAsString());
        assertEquals("minecraft:bundle", record.getAsJsonObject("payload").get("identity").getAsString());
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                new ModelBackend.ToolCall("nested", "search_inventory", "{\"target\":{\"type\":\"item\",\"slotReference\":\"" + slot + "\"}}"), List.of(), null));
        assertEquals(SLOT, JsonParser.parseString(events.getLast().toolCall().serializedArguments()).getAsJsonObject()
                .getAsJsonObject("target").get("slotReference").getAsString());
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                new ModelBackend.ToolCall("read", "read_evidence", "{\"handles\":[\"" + reference + "\"]}"), List.of(), null));
        assertEquals(REFERENCE, JsonParser.parseString(events.getLast().toolCall().serializedArguments()).getAsJsonObject()
                .getAsJsonArray("handles").get(0).getAsString());
        for (String name : List.of("read_progress", "read_history", "read_config")) {
            actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                    new ModelBackend.ToolCall(name, name, name.equals("read_config") ? "{\"handle\":\"" + reference + "\",\"key\":\"" + reference + "\"}"
                            : "{\"handles\":[\"" + reference + "\"]}"), List.of(), null));
            var arguments = JsonParser.parseString(events.getLast().toolCall().serializedArguments()).getAsJsonObject();
            if (name.equals("read_config")) {
                assertEquals(REFERENCE, arguments.get("handle").getAsString());
                assertEquals(reference, arguments.get("key").getAsString());
            } else assertEquals(REFERENCE, arguments.getAsJsonArray("handles").get(0).getAsString());
        }
    }

    @Test
    void everyCitationSplitExpandsToCanonicalTextAndTerminalReplayRetainsExactCanonicalResult() {
        for (String template : List.of("Bundle [%s].", "[Bundle label](%s).", "\uE200cite\uE202%s\uE202%s\uE201")) {
            for (int split = 0; ; split++) {
                var actual = new FakeBackend();
                var events = new ArrayList<ModelBackend.Event>();
                var exchange = new ReferenceAliasBackend(actual).start(new ModelBackend.Request("model", "instructions", List.of(), List.of(), ModelBackend.SearchMode.DISABLED, "request", 512), events::add);
                actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                        new ModelBackend.ToolCall("inventory", "search_inventory", "{\"target\":{\"type\":\"player\"}}"), List.of(), null));
                exchange.submit(RESULT);
                String alias = JsonParser.parseString(actual.results.getFirst().serializedResult()).getAsJsonObject()
                        .getAsJsonArray("records").get(0).getAsJsonObject().get("reference").getAsString();
                assertTrue(alias.length() < REFERENCE.length());
                String answer = template.formatted(alias, alias);
                if (split > answer.length()) break;
                actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.ROUND_STARTED, null, null, List.of(), null));
                actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, answer.substring(0, split), null, List.of(), null));
                actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, answer.substring(split), null, List.of(), null));
                actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(),
                        List.of(new ModelBackend.ToolCall("inventory", "search_inventory", "{\"target\":{\"type\":\"player\"}}"),
                                actual.results.getFirst(), new ModelBackend.AssistantOutput("answer", ModelBackend.OutputStatus.COMPLETED,
                                        ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText(answer, List.of())))), null));
                assertEquals(template.formatted(REFERENCE, REFERENCE), events.stream()
                        .filter(event -> event.kind() == ModelBackend.EventKind.TEXT_DELTA).map(ModelBackend.Event::text)
                        .reduce("", String::concat), "split " + split);
                assertEquals(RESULT, events.getLast().replayItems().get(1));
            }
        }
    }

    @Test
    void referenceKindsUnknownAlteredAndPriorRequestAliasesNeverSelectAnotherEntry() {
        var actual = new FakeBackend();
        var events = new ArrayList<ModelBackend.Event>();
        var backend = new ReferenceAliasBackend(actual);
        var exchange = backend.start(new ModelBackend.Request("model", "instructions", List.of(), List.of(), ModelBackend.SearchMode.DISABLED, "request", 512), events::add);
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                new ModelBackend.ToolCall("inventory", "search_inventory", "{\"target\":{\"type\":\"player\"}}"), List.of(), null));
        exchange.submit(RESULT);
        var record = JsonParser.parseString(actual.results.getFirst().serializedResult()).getAsJsonObject()
                .getAsJsonArray("records").get(0).getAsJsonObject();
        String reference = record.get("reference").getAsString();
        String slot = record.getAsJsonObject("payload").get("slotReference").getAsString();
        for (String invalid : List.of(reference, slot + "0", slot.substring(0, slot.length() - 1) + "z", "slot:00000000.0.0")) {
            var call = new ModelBackend.ToolCall("invalid-" + invalid, "search_inventory", "{\"target\":{\"type\":\"item\",\"slotReference\":\"" + invalid + "\"}}");
            actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null, call, List.of(), null));
            assertEquals(call, events.getLast().toolCall());
        }
        var wrongKind = new ModelBackend.ToolCall("wrong-kind", "read_evidence", "{\"handles\":[\"" + slot + "\"]}");
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null, wrongKind, List.of(), null));
        assertEquals(wrongKind, events.getLast().toolCall());
        exchange.cancel();
        events.clear();
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, "late [" + reference + "]", null, List.of(), null));
        assertTrue(events.isEmpty());
        exchange = backend.start(new ModelBackend.Request("model", "instructions", List.of(new ModelBackend.Message(ModelBackend.Role.ASSISTANT,
                List.of(new ModelBackend.TextContent("Previous [" + REFERENCE + "]")))), List.of(), ModelBackend.SearchMode.DISABLED, "request", 512), events::add);
        var prior = new ModelBackend.ToolCall("prior", "search_inventory", "{\"target\":{\"type\":\"item\",\"slotReference\":\"" + slot + "\"}}");
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null, prior, List.of(), null));
        assertEquals(prior, events.getLast().toolCall());
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, "[" + reference + "].", null, List.of(), null));
        assertEquals("[" + reference + "].", events.getLast().text());
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                new ModelBackend.ToolCall("inventory", "search_inventory", "{\"target\":{\"type\":\"player\"}}"), List.of(), null));
        exchange.submit(RESULT);
        String fresh = JsonParser.parseString(actual.results.getLast().serializedResult()).getAsJsonObject()
                .getAsJsonArray("records").get(0).getAsJsonObject().get("reference").getAsString();
        assertNotEquals(reference, fresh);
        assertEquals(actual.requests.getLast().input(), List.of(new ModelBackend.Message(ModelBackend.Role.ASSISTANT,
                List.of(new ModelBackend.TextContent("Previous [" + REFERENCE + "]")))));
    }

    @Test
    void structuralLocationsAndContinuationArgumentsTranslateWhileProvenanceOwnerIdsAndCursorsStayExact() {
        var json = JsonParser.parseString(RESULT.serializedResult()).getAsJsonObject();
        var record = json.getAsJsonArray("records").get(0).getAsJsonObject();
        var payload = record.getAsJsonObject("payload");
        payload.add("owner", JsonParser.parseString("{\"slotReference\":\"" + SLOT + "\",\"ownerIdentity\":\"" + SLOT + "\"}"));
        payload.add("mainhandLocation", JsonParser.parseString("{\"slotReference\":\"" + SLOT + "\"}"));
        payload.add("metadata", JsonParser.parseString("{\"slotReference\":\"" + SLOT + "\"}"));
        record.getAsJsonObject("source").add("provenance", JsonParser.parseString("[\"" + SLOT + "\"]"));
        json.add("nextCall", JsonParser.parseString("{\"name\":\"search_inventory\",\"arguments\":{\"target\":{\"type\":\"item\",\"slotReference\":\"" + SLOT + "\"},\"cursor\":\"" + SLOT + "\"}}"));
        json.add("restartCall", JsonParser.parseString("{\"name\":\"read_evidence\",\"arguments\":{\"handles\":[\"" + REFERENCE + "\"]}}"));
        var actual = new FakeBackend();
        var exchange = new ReferenceAliasBackend(actual).start(new ModelBackend.Request("model", "instructions", List.of(), List.of(), ModelBackend.SearchMode.DISABLED, "request", 512), ignored -> { });
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                new ModelBackend.ToolCall("inventory", "search_inventory", "{\"target\":{\"type\":\"player\"}}"), List.of(), null));
        exchange.submit(new ModelBackend.ToolResult("inventory", json.toString(), false));
        var encoded = JsonParser.parseString(actual.results.getFirst().serializedResult()).getAsJsonObject();
        var encodedRecord = encoded.getAsJsonArray("records").get(0).getAsJsonObject();
        var encodedPayload = encodedRecord.getAsJsonObject("payload");
        String slot = encodedPayload.get("slotReference").getAsString();
        assertTrue(slot.startsWith("slot:"));
        assertEquals(slot, encodedPayload.getAsJsonObject("owner").get("slotReference").getAsString());
        assertEquals(slot, encodedPayload.getAsJsonObject("mainhandLocation").get("slotReference").getAsString());
        assertEquals(SLOT, encodedPayload.getAsJsonObject("owner").get("ownerIdentity").getAsString());
        assertEquals(SLOT, encodedPayload.getAsJsonObject("metadata").get("slotReference").getAsString());
        assertEquals(SLOT, encodedRecord.getAsJsonObject("source").getAsJsonArray("provenance").get(0).getAsString());
        assertEquals(slot, encoded.getAsJsonObject("nextCall").getAsJsonObject("arguments").getAsJsonObject("target").get("slotReference").getAsString());
        assertEquals(SLOT, encoded.getAsJsonObject("nextCall").getAsJsonObject("arguments").get("cursor").getAsString());
        assertEquals(encodedRecord.get("reference"), encoded.getAsJsonObject("restartCall").getAsJsonObject("arguments").getAsJsonArray("handles").get(0));
    }

    @Test
    void nativeStorageOwnersAndEntryPayloadLocationsReuseTheTypedSlotAlias() {
        var json = JsonParser.parseString(RESULT.serializedResult()).getAsJsonObject();
        var payload = json.getAsJsonArray("records").get(0).getAsJsonObject().getAsJsonObject("payload");
        payload.add("owners", JsonParser.parseString("[{\"slotReference\":\"" + SLOT + "\",\"ownerIdentity\":\"" + SLOT + "\"}]"));
        payload.add("entries", JsonParser.parseString("[{\"kind\":\"item\",\"payload\":{\"slotReference\":\"" + SLOT
                + "\",\"location\":{\"slotReference\":\"" + SLOT + "\"},\"ownerIdentity\":\"" + SLOT + "\"}}]"));
        payload.add("metadata", JsonParser.parseString("{\"owners\":[{\"slotReference\":\"" + SLOT
                + "\"}],\"entries\":[{\"payload\":{\"slotReference\":\"" + SLOT + "\"}}]}"));
        var actual = new FakeBackend();
        var exchange = new ReferenceAliasBackend(actual).start(new ModelBackend.Request("model", "instructions", List.of(), List.of(), ModelBackend.SearchMode.DISABLED, "request", 512), ignored -> { });
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                new ModelBackend.ToolCall("inventory", "search_inventory", "{\"target\":{\"type\":\"item\",\"slotReference\":\"" + SLOT + "\"}}"), List.of(), null));
        exchange.submit(new ModelBackend.ToolResult("inventory", json.toString(), false));
        var encoded = JsonParser.parseString(actual.results.getFirst().serializedResult()).getAsJsonObject()
                .getAsJsonArray("records").get(0).getAsJsonObject().getAsJsonObject("payload");
        String alias = encoded.get("slotReference").getAsString();
        assertTrue(alias.startsWith("slot:"));
        var owner = encoded.getAsJsonArray("owners").get(0).getAsJsonObject();
        var entry = encoded.getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonObject("payload");
        assertEquals(alias, owner.get("slotReference").getAsString());
        assertEquals(alias, entry.get("slotReference").getAsString());
        assertEquals(alias, entry.getAsJsonObject("location").get("slotReference").getAsString());
        assertEquals(SLOT, owner.get("ownerIdentity").getAsString());
        assertEquals(SLOT, entry.get("ownerIdentity").getAsString());
        assertEquals(payload.get("metadata"), encoded.get("metadata"));
    }

    @Test
    void matchingJeiViewerHandleUsesEvidenceAliasWithoutRewritingOtherPayloadValues() {
        var actual = new FakeBackend();
        var exchange = new ReferenceAliasBackend(actual).start(new ModelBackend.Request("model", "instructions", List.of(), List.of(), ModelBackend.SearchMode.DISABLED, "request", 512), ignored -> { });
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                new ModelBackend.ToolCall("recipes", "search_recipe", "{}"), List.of(), null));
        exchange.submit(new ModelBackend.ToolResult("recipes", """
                {"records":[
                  {"reference":"jei:jei/1/10/minecraft:crafting/recipe-minecraft:torch-1",
                   "source":{"providerId":"jei","sourceHandle":"jei/1/10/minecraft:crafting/recipe-minecraft:torch-1"},
                   "payload":{"viewerHandle":"jei/1/10/minecraft:crafting/recipe-minecraft:torch-1",
                     "text":"jei/1/10/minecraft:crafting/recipe-minecraft:torch-1","recipeId":"minecraft:torch"}},
                  {"reference":"jei:jei/1/10/minecraft:crafting/recipe-minecraft:torch-1",
                   "source":{"providerId":"jei","sourceHandle":"jei/1/10/minecraft:crafting/recipe-minecraft:torch-1"},
                   "payload":{"viewerHandle":"jei/1/10/minecraft:crafting/recipe-minecraft:torch-2"}},
                  {"reference":"patchouli:jei/1/10/minecraft:crafting/recipe-minecraft:torch-1",
                   "source":{"providerId":"patchouli","sourceHandle":"jei/1/10/minecraft:crafting/recipe-minecraft:torch-1"},
                   "payload":{"viewerHandle":"jei/1/10/minecraft:crafting/recipe-minecraft:torch-1"}}
                ]}
                """, false));
        var records = JsonParser.parseString(actual.results.getFirst().serializedResult()).getAsJsonObject().getAsJsonArray("records");
        var matching = records.get(0).getAsJsonObject();
        assertTrue(matching.get("reference").getAsString().startsWith("ref:"));
        assertEquals(matching.get("reference"), matching.getAsJsonObject("payload").get("viewerHandle"));
        assertEquals(matching.get("reference"), matching.getAsJsonObject("source").get("sourceHandle"));
        assertEquals("jei/1/10/minecraft:crafting/recipe-minecraft:torch-1", matching.getAsJsonObject("payload").get("text").getAsString());
        assertEquals("minecraft:torch", matching.getAsJsonObject("payload").get("recipeId").getAsString());
        assertEquals("jei/1/10/minecraft:crafting/recipe-minecraft:torch-2", records.get(1).getAsJsonObject().getAsJsonObject("payload").get("viewerHandle").getAsString());
        assertEquals("jei/1/10/minecraft:crafting/recipe-minecraft:torch-1", records.get(2).getAsJsonObject().getAsJsonObject("payload").get("viewerHandle").getAsString());
    }

    @Test
    void concurrentResultsDeduplicateCanonicalReferencesWithoutMixingTheirReplay() {
        var actual = new FakeBackend();
        var events = new ArrayList<ModelBackend.Event>();
        var exchange = new ReferenceAliasBackend(actual).start(new ModelBackend.Request("model", "instructions", List.of(), List.of(), ModelBackend.SearchMode.DISABLED, "request", 512), events::add);
        var calls = List.of(new ModelBackend.ToolCall("first", "search_inventory", "{\"target\":{\"type\":\"player\"}}"),
                new ModelBackend.ToolCall("second", "search_inventory", "{\"target\":{\"type\":\"player\"}}"));
        calls.forEach(call -> actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null, call, List.of(), null)));
        CompletableFuture.allOf(CompletableFuture.runAsync(() -> exchange.submit(new ModelBackend.ToolResult("first", RESULT.serializedResult(), false))),
                CompletableFuture.runAsync(() -> exchange.submit(new ModelBackend.ToolResult("second", RESULT.serializedResult(), false)))).join();
        assertEquals(2, actual.results.size());
        assertEquals(JsonParser.parseString(actual.results.getFirst().serializedResult()), JsonParser.parseString(actual.results.getLast().serializedResult()));
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(),
                List.of(calls.getFirst(), actual.results.stream().filter(result -> result.callId().equals("first")).findFirst().orElseThrow(),
                        calls.getLast(), actual.results.stream().filter(result -> result.callId().equals("second")).findFirst().orElseThrow()), null));
        assertEquals(new ModelBackend.ToolResult("first", RESULT.serializedResult(), false), events.getLast().replayItems().get(1));
        assertEquals(new ModelBackend.ToolResult("second", RESULT.serializedResult(), false), events.getLast().replayItems().get(3));
    }

    @Test
    void replayChecksWireResultsAndObservedCallsBeforeReturningCanonicalValues() {
        for (boolean changeResult : List.of(true, false)) {
            var actual = new FakeBackend();
            var events = new ArrayList<ModelBackend.Event>();
            var exchange = new ReferenceAliasBackend(actual).start(new ModelBackend.Request("model", "instructions", List.of(), List.of(), ModelBackend.SearchMode.DISABLED, "request", 512), events::add);
            var call = new ModelBackend.ToolCall("inventory", "search_inventory", "{\"target\":{\"type\":\"player\"}}");
            actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null, call, List.of(), null));
            exchange.submit(RESULT);
            actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), List.of(
                    changeResult ? call : new ModelBackend.ToolCall(call.callId(), call.name(), "{\"target\":{\"type\":\"opened\"}}"),
                    changeResult ? RESULT : actual.results.getFirst()), null));
            assertEquals(ModelBackend.EventKind.FAILED, events.getLast().kind());
            assertTrue(events.getLast().detail().contains(changeResult ? "changed submitted tool result" : "changed observed tool call"));
            assertTrue(events.stream().noneMatch(event -> event.kind() == ModelBackend.EventKind.COMPLETED));
        }
    }

    @Test
    void unicodeWebAnnotationRangesFollowCanonicalExpansionAndSteeringRemainsCanonical() {
        var actual = new FakeBackend();
        var events = new ArrayList<ModelBackend.Event>();
        var exchange = new ReferenceAliasBackend(actual).start(new ModelBackend.Request("model", "instructions", List.of(), List.of(), ModelBackend.SearchMode.DISABLED, "request", 512), events::add);
        var call = new ModelBackend.ToolCall("inventory", "search_inventory", "{\"target\":{\"type\":\"player\"}}");
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null, call, List.of(), null));
        exchange.submit(RESULT);
        String alias = JsonParser.parseString(actual.results.getFirst().serializedResult()).getAsJsonObject()
                .getAsJsonArray("records").get(0).getAsJsonObject().get("reference").getAsString();
        String answer = "🌋 [Bundle](" + alias + ") then docs.";
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, answer.substring(0, 12), null, List.of(), null));
        var steering = new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent("Check " + REFERENCE)));
        assertTrue(exchange.steer(steering));
        assertEquals(List.of(steering), actual.steered);
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, answer.substring(12), null, List.of(), null));
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), List.of(call, actual.results.getFirst(), steering,
                new ModelBackend.AssistantOutput("answer", ModelBackend.OutputStatus.COMPLETED, ModelBackend.OutputPhase.FINAL_ANSWER,
                        List.of(new ModelBackend.OutputText(answer, List.of(new ModelBackend.UrlCitation(answer.indexOf("docs"), answer.length(), "Docs", "https://example.org/docs")))))), null));
        var text = (ModelBackend.OutputText) ((ModelBackend.AssistantOutput) events.getLast().replayItems().getLast()).content().getFirst();
        assertEquals("🌋 [Bundle](" + REFERENCE + ") then docs.", text.text());
        assertEquals("docs.", text.text().substring(text.citations().getFirst().startIndex(), text.citations().getFirst().endIndex()));
        assertEquals(text.text().indexOf("docs"), text.citations().getFirst().startIndex());
    }

    @Test
    void shortCanonicalReferencesAndArbitraryAnswerTextAreNotRewritten() {
        var actual = new FakeBackend();
        var events = new ArrayList<ModelBackend.Event>();
        var exchange = new ReferenceAliasBackend(actual).start(new ModelBackend.Request("model", "instructions", List.of(), List.of(), ModelBackend.SearchMode.DISABLED, "request", 512), events::add);
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                new ModelBackend.ToolCall("inventory", "search_inventory", "{\"target\":{\"type\":\"player\"}}"), List.of(), null));
        var json = JsonParser.parseString(RESULT.serializedResult()).getAsJsonObject();
        var record = json.getAsJsonArray("records").get(0).getAsJsonObject();
        record.addProperty("reference", "native:item:minecraft:bundle");
        record.getAsJsonObject("source").addProperty("sourceHandle", "item:minecraft:bundle");
        exchange.submit(new ModelBackend.ToolResult("inventory", json.toString(), false));
        var wire = JsonParser.parseString(actual.results.getFirst().serializedResult()).getAsJsonObject().getAsJsonArray("records").get(0).getAsJsonObject();
        assertEquals("native:item:minecraft:bundle", wire.get("reference").getAsString());
        assertEquals("item:minecraft:bundle", wire.getAsJsonObject("source").get("sourceHandle").getAsString());
        String slot = wire.getAsJsonObject("payload").get("slotReference").getAsString();
        String answer = "Use " + slot + " as prose, [" + slot + "] as a wrong-kind citation, and `[` as code. [native:item:minecraft:bundle].";
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, answer, null, List.of(), null));
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.FAILED, null, null, List.of(), "fixture"));
        assertEquals(answer, events.stream().filter(event -> event.kind() == ModelBackend.EventKind.TEXT_DELTA)
                .map(ModelBackend.Event::text).collect(java.util.stream.Collectors.joining()));
    }

    @Test
    void retryDiscardsPartialCitationBufferAndKeepsAcceptedAliasesForFreshOutput() {
        var actual = new FakeBackend();
        var events = new ArrayList<ModelBackend.Event>();
        var exchange = new ReferenceAliasBackend(actual).start(new ModelBackend.Request("model", "instructions", List.of(), List.of(), ModelBackend.SearchMode.DISABLED, "request", 512), events::add);
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                new ModelBackend.ToolCall("inventory", "search_inventory", "{\"target\":{\"type\":\"player\"}}"), List.of(), null));
        exchange.submit(RESULT);
        String alias = JsonParser.parseString(actual.results.getFirst().serializedResult()).getAsJsonObject()
                .getAsJsonArray("records").get(0).getAsJsonObject().get("reference").getAsString();
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, "Discard [" + alias.substring(0, 5), null, List.of(), null));
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.RETRYING, null, null, List.of(), "max_messages"));
        events.clear();
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, "Fresh [" + alias + "].", null, List.of(), null));
        assertEquals("Fresh [" + REFERENCE + "].", events.getLast().text());
    }

    @Test
    void boundedCitationBufferReleasesUnfinishedLabelsWithoutGrowingAcrossTheAnswer() {
        var actual = new FakeBackend();
        var events = new ArrayList<ModelBackend.Event>();
        new ReferenceAliasBackend(actual).start(new ModelBackend.Request("model", "instructions", List.of(), List.of(), ModelBackend.SearchMode.DISABLED, "request", 512), events::add);
        String answer = "[" + "x".repeat(20_000);
        for (int index = 0; index < answer.length(); index++) {
            actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, answer.substring(index, index + 1), null, List.of(), null));
        }
        assertFalse(events.isEmpty());
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), List.of(), null));
        assertEquals(answer, events.stream().filter(event -> event.kind() == ModelBackend.EventKind.TEXT_DELTA)
                .map(ModelBackend.Event::text).collect(java.util.stream.Collectors.joining()));
    }

    @Test
    void encodedOutputCeilingAndRetainedStateFailExplicitlyWithoutSubmittingOrEvicting() {
        for (boolean outputLimit : List.of(true, false)) {
            var actual = new FakeBackend();
            var events = new ArrayList<ModelBackend.Event>();
            var exchange = new ReferenceAliasBackend(actual).start(new ModelBackend.Request("model", "instructions", List.of(), List.of(), ModelBackend.SearchMode.DISABLED, "request", 512), events::add);
            var json = JsonParser.parseString("{\"records\":[{\"reference\":\"native:a\",\"source\":{\"providerId\":\"native\",\"sourceHandle\":\"a\"},\"payload\":{\"slotReference\":\"x\",\"mainhandSlotReference\":\"x\",\"offhandSlotReference\":\"x\",\"padding\":\"\"}}]}").getAsJsonObject();
            if (outputLimit) {
                json.getAsJsonArray("records").get(0).getAsJsonObject().getAsJsonObject("payload").addProperty("padding", "x".repeat(131_071 - json.toString().length()));
                assertEquals(131_071, json.toString().length());
            } else {
                json = new JsonObject();
                json.addProperty("detail", "x".repeat(100_000));
            }
            for (int index = 0; index < (outputLimit ? 1 : 30); index++) {
                actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                        new ModelBackend.ToolCall("call-" + index, "search_inventory", "{\"target\":{\"type\":\"player\"}}"), List.of(), null));
                exchange.submit(new ModelBackend.ToolResult("call-" + index, json.toString(), false));
                if (events.getLast().kind() == ModelBackend.EventKind.FAILED) break;
            }
            assertEquals(ModelBackend.EventKind.FAILED, events.getLast().kind());
            assertTrue(events.getLast().detail().contains(outputLimit ? "encoded tool output limit" : "retained state limit"));
            if (outputLimit) assertTrue(actual.results.isEmpty());
            else assertTrue(actual.results.size() > 1 && actual.results.size() < 30);
        }
    }

    @Test
    void plainTextAndNonObjectToolErrorsPassThroughWithExactReplay() {
        for (String error : List.of("tool failed: reader unavailable", "[\"legacy error\"]", "null")) {
            var actual = new FakeBackend();
            var events = new ArrayList<ModelBackend.Event>();
            var exchange = new ReferenceAliasBackend(actual).start(new ModelBackend.Request("model", "instructions", List.of(), List.of(), ModelBackend.SearchMode.DISABLED, "request", 512), events::add);
            var call = new ModelBackend.ToolCall("error", "read_evidence", "{\"handles\":[\"native:missing\"]}");
            actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null, call, List.of(), null));
            var result = new ModelBackend.ToolResult("error", error, true);
            exchange.submit(result);
            assertEquals(result, actual.results.getFirst());
            actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), List.of(call, result), null));
            assertEquals(ModelBackend.EventKind.COMPLETED, events.getLast().kind());
            assertEquals(List.of(call, result), events.getLast().replayItems());
        }
    }

    private static final class FakeBackend implements ModelBackend {
        private final List<ToolResult> results = java.util.Collections.synchronizedList(new ArrayList<>());
        private final List<Request> requests = new ArrayList<>();
        private final List<Message> steered = new ArrayList<>();
        private Consumer<Event> events;

        @Override
        public CompletionStage<AuthState> signIn() { return CompletableFuture.completedFuture(new AuthState(AuthStatus.SIGNED_IN, "account", null)); }

        @Override
        public void cancelSignIn() { }

        @Override
        public void signOut() { }

        @Override
        public CompletionStage<Capabilities> capabilities() { return CompletableFuture.completedFuture(new Capabilities(true, false, 16000, 2048)); }

        @Override
        public Exchange start(Request request, Consumer<Event> events) {
            this.events = events;
            requests.add(request);
            return new Exchange() {
                @Override
                public void submit(ToolResult result) { results.add(result); }

                @Override
                public boolean steer(Message message) { steered.add(message); return true; }

                @Override
                public void cancel() { }
            };
        }
    }
}
