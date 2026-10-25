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
    private static final ModelBackend.ToolResult RESULT = new ModelBackend.ToolResult("inventory", """
            {"coverage":"COMPLETE","outcome":"COMPLETE","records":[{
              "reference":"native:inventory:07cd5168-5d6e-48b3-95ac-9f9889de7ef6:player:main",
              "source":{"providerId":"native","sourceHandle":"inventory:07cd5168-5d6e-48b3-95ac-9f9889de7ef6:player:main"},
              "payload":{"text":"Quote native:inventory:07cd5168-5d6e-48b3-95ac-9f9889de7ef6:player:main exactly.",
                "identity":"minecraft:bundle","count":1},"title":"Bundle"}]}
            """, false);

    @Test
    void compactTypedReferencesRoundTripToExactReaderArgumentsWithoutChangingSourceText() {
        var actual = new FakeBackend();
        var events = new ArrayList<ModelBackend.Event>();
        var exchange = new ReferenceAliasBackend(actual).start(new ModelBackend.Request("model", "instructions", List.of(), List.of(), ModelBackend.SearchMode.DISABLED, "request", 512), events::add);
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                new ModelBackend.ToolCall("inventory", "list_items", "{\"holder\":\"player\"}"), List.of(), null));
        exchange.submit(RESULT);
        var record = JsonParser.parseString(actual.results.getFirst().serializedResult()).getAsJsonObject()
                .getAsJsonArray("records").get(0).getAsJsonObject();
        String reference = record.get("reference").getAsString();

        assertTrue(reference.matches("ref:[0-9a-f]{8}\\.[0-9a-z]+\\.[0-9a-z]+"), reference);
        assertEquals(reference, record.getAsJsonObject("source").get("sourceHandle").getAsString());
        assertEquals("Quote " + REFERENCE + " exactly.", record.getAsJsonObject("payload").get("text").getAsString());
        assertEquals("minecraft:bundle", record.getAsJsonObject("payload").get("identity").getAsString());
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                new ModelBackend.ToolCall("read", "read_evidence", "{\"handles\":[\"" + reference + "\"]}"), List.of(), null));
        assertEquals(REFERENCE, JsonParser.parseString(events.getLast().toolCall().serializedArguments()).getAsJsonObject()
                .getAsJsonArray("handles").get(0).getAsString());
        for (String name : List.of("read_history", "read_config")) {
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
        for (String template : List.of("Bundle [%s].", "Bundle [native:%s].", "[Bundle label](%s).", "[Bundle label](native:%s).", "\uE200cite\uE202%s\uE202%s\uE201")) {
            for (int split = 0; ; split++) {
                var actual = new FakeBackend();
                var events = new ArrayList<ModelBackend.Event>();
                var exchange = new ReferenceAliasBackend(actual).start(new ModelBackend.Request("model", "instructions", List.of(), List.of(), ModelBackend.SearchMode.DISABLED, "request", 512), events::add);
                actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                        new ModelBackend.ToolCall("inventory", "list_items", "{\"holder\":\"player\"}"), List.of(), null));
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
                        List.of(new ModelBackend.ToolCall("inventory", "list_items", "{\"holder\":\"player\"}"),
                                actual.results.getFirst(), new ModelBackend.AssistantOutput("answer", ModelBackend.OutputStatus.COMPLETED,
                                        ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText(answer, List.of())))), null));
                String canonicalAnswer = template.replace("native:%s", "%s").formatted(REFERENCE, REFERENCE);
                assertEquals(canonicalAnswer, events.stream()
                        .filter(event -> event.kind() == ModelBackend.EventKind.TEXT_DELTA).map(ModelBackend.Event::text)
                        .reduce("", String::concat), "split " + split);
                assertEquals(RESULT, events.getLast().replayItems().get(1));
                assertEquals(canonicalAnswer, ((ModelBackend.OutputText) ((ModelBackend.AssistantOutput)
                        events.getLast().replayItems().getLast()).content().getFirst()).text(), "replay split " + split);
            }
        }
    }

    @Test
    void knownAliasClosedWithParenthesisBecomesCanonicalCitation() {
        for (String template : List.of("Done. [%s)", "Before [%s) after.", "[%s) then [%s]")) {
            for (int split = 0; ; split++) {
                var actual = new FakeBackend();
                var events = new ArrayList<ModelBackend.Event>();
                var exchange = new ReferenceAliasBackend(actual).start(new ModelBackend.Request("model", "instructions", List.of(), List.of(), ModelBackend.SearchMode.DISABLED, "request", 512), events::add);
                actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                        new ModelBackend.ToolCall("inventory", "list_items", "{\"holder\":\"player\"}"), List.of(), null));
                exchange.submit(RESULT);
                String alias = JsonParser.parseString(actual.results.getFirst().serializedResult()).getAsJsonObject()
                        .getAsJsonArray("records").get(0).getAsJsonObject().get("reference").getAsString();
                String answer = template.formatted(alias, alias);
                if (split > answer.length()) break;
                actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, answer.substring(0, split), null, List.of(), null));
                actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, answer.substring(split), null, List.of(), null));
                actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(),
                        List.of(new ModelBackend.AssistantOutput("answer", ModelBackend.OutputStatus.COMPLETED,
                                ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText(answer, List.of())))), null));

                String expected = template.replace("%s)", "%s]").formatted(REFERENCE, REFERENCE);
                assertEquals(expected, events.stream().filter(event -> event.kind() == ModelBackend.EventKind.TEXT_DELTA)
                        .map(ModelBackend.Event::text).reduce("", String::concat), "split " + split + " for " + template);
                assertEquals(expected, ((ModelBackend.OutputText) ((ModelBackend.AssistantOutput)
                        events.getLast().replayItems().getLast()).content().getFirst()).text(), "replay split " + split);
            }
        }
    }

    @Test
    void unknownParenthesisClosedBracketTextStaysVerbatim() {
        for (String answer : List.of("[see note)", "[ref:ffffffff.9.9)", "Before [see note) after.")) {
            var actual = new FakeBackend();
            var events = new ArrayList<ModelBackend.Event>();
            var exchange = new ReferenceAliasBackend(actual).start(new ModelBackend.Request("model", "instructions", List.of(), List.of(), ModelBackend.SearchMode.DISABLED, "request", 512), events::add);
            actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                    new ModelBackend.ToolCall("inventory", "list_items", "{\"holder\":\"player\"}"), List.of(), null));
            exchange.submit(RESULT);
            actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, answer, null, List.of(), null));
            actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(),
                    List.of(new ModelBackend.AssistantOutput("answer", ModelBackend.OutputStatus.COMPLETED,
                            ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText(answer, List.of())))), null));

            assertEquals(answer, events.stream().filter(event -> event.kind() == ModelBackend.EventKind.TEXT_DELTA)
                    .map(ModelBackend.Event::text).reduce("", String::concat));
            assertEquals(answer, ((ModelBackend.OutputText) ((ModelBackend.AssistantOutput)
                    events.getLast().replayItems().getLast()).content().getFirst()).text());
        }
    }

    @Test
    void everyMarkdownDestinationSplitPreservesAliasShapedLabelAndCanonicalizesDestination() {
        for (int split = 0; ; split++) {
            var actual = new FakeBackend();
            var events = new ArrayList<ModelBackend.Event>();
            var exchange = new ReferenceAliasBackend(actual).start(new ModelBackend.Request("model", "instructions", List.of(), List.of(), ModelBackend.SearchMode.DISABLED, "request", 512), events::add);
            actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                    new ModelBackend.ToolCall("inventory", "list_items", "{\"holder\":\"player\"}"), List.of(), null));
            exchange.submit(RESULT);
            String alias = JsonParser.parseString(actual.results.getFirst().serializedResult()).getAsJsonObject()
                    .getAsJsonArray("records").get(0).getAsJsonObject().get("reference").getAsString();
            String answer = "[native:" + alias + "](native:" + alias + ")";
            if (split > answer.length()) break;
            actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.ROUND_STARTED, null, null, List.of(), null));
            actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, answer.substring(0, split), null, List.of(), null));
            actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, answer.substring(split), null, List.of(), null));
            actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(),
                    List.of(new ModelBackend.ToolCall("inventory", "list_items", "{\"holder\":\"player\"}"),
                            actual.results.getFirst(), new ModelBackend.AssistantOutput("answer", ModelBackend.OutputStatus.COMPLETED,
                                    ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText(answer, List.of())))), null));
            String expected = "[native:" + alias + "](" + REFERENCE + ")";
            assertEquals(expected, events.stream().filter(event -> event.kind() == ModelBackend.EventKind.TEXT_DELTA)
                    .map(ModelBackend.Event::text).reduce("", String::concat), "split " + split);
            assertEquals(expected, ((ModelBackend.OutputText) ((ModelBackend.AssistantOutput)
                    events.getLast().replayItems().getLast()).content().getFirst()).text(), "replay split " + split);
        }
    }

    @Test
    void everySpacedMarkdownDestinationSplitCanonicalizesOnlyKnownEvidenceAliases() {
        for (String template : List.of("[Springy Boot] (%s)", "[Springy Boot] (native:%s)")) {
            for (int split = 0; ; split++) {
                var actual = new FakeBackend();
                var events = new ArrayList<ModelBackend.Event>();
                var exchange = new ReferenceAliasBackend(actual).start(new ModelBackend.Request("model", "instructions", List.of(), List.of(), ModelBackend.SearchMode.DISABLED, "request", 512), events::add);
                actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                        new ModelBackend.ToolCall("inventory", "list_items", "{\"holder\":\"player\"}"), List.of(), null));
                exchange.submit(RESULT);
                var record = JsonParser.parseString(actual.results.getFirst().serializedResult()).getAsJsonObject()
                        .getAsJsonArray("records").get(0).getAsJsonObject();
                String alias = record.get("reference").getAsString();
                String answer = template.formatted(alias);
                if (split > answer.length()) break;
                actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.ROUND_STARTED, null, null, List.of(), null));
                actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, answer.substring(0, split), null, List.of(), null));
                actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, answer.substring(split), null, List.of(), null));
                actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(),
                        List.of(new ModelBackend.ToolCall("inventory", "list_items", "{\"holder\":\"player\"}"),
                                actual.results.getFirst(), new ModelBackend.AssistantOutput("answer", ModelBackend.OutputStatus.COMPLETED,
                                        ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText(answer, List.of())))), null));
                String expected = "[Springy Boot](" + REFERENCE + ")";
                assertEquals(expected, events.stream().filter(event -> event.kind() == ModelBackend.EventKind.TEXT_DELTA)
                        .map(ModelBackend.Event::text).reduce("", String::concat), "split " + split + " for " + template);
                assertEquals(expected, ((ModelBackend.OutputText) ((ModelBackend.AssistantOutput)
                        events.getLast().replayItems().getLast()).content().getFirst()).text(), "replay split " + split);
            }
        }

        var actual = new FakeBackend();
        var events = new ArrayList<ModelBackend.Event>();
        var exchange = new ReferenceAliasBackend(actual).start(new ModelBackend.Request("model", "instructions", List.of(), List.of(), ModelBackend.SearchMode.DISABLED, "request", 512), events::add);
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                new ModelBackend.ToolCall("inventory", "list_items", "{\"holder\":\"player\"}"), List.of(), null));
        exchange.submit(RESULT);
        var record = JsonParser.parseString(actual.results.getFirst().serializedResult()).getAsJsonObject()
                .getAsJsonArray("records").get(0).getAsJsonObject();
        String alias = record.get("reference").getAsString();
        String unchanged = "[Springy Boot] (ref:00000000.0.0) [Springy Boot] (wrong:" + alias
                + ") and [label] remains prose.";
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, unchanged, null, List.of(), null));
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.FAILED, null, null, List.of(), "fixture"));
        assertEquals(unchanged, events.stream().filter(event -> event.kind() == ModelBackend.EventKind.TEXT_DELTA)
                .map(ModelBackend.Event::text).reduce("", String::concat));
    }

    @Test
    void unknownBracketedProseDoesNotHideFollowingKnownCitation() {
        var actual = new FakeBackend();
        var events = new ArrayList<ModelBackend.Event>();
        var exchange = new ReferenceAliasBackend(actual).start(new ModelBackend.Request("model", "instructions", List.of(), List.of(), ModelBackend.SearchMode.DISABLED, "request", 512), events::add);
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                new ModelBackend.ToolCall("inventory", "list_items", "{\"holder\":\"player\"}"), List.of(), null));
        exchange.submit(RESULT);
        String alias = JsonParser.parseString(actual.results.getFirst().serializedResult()).getAsJsonObject()
                .getAsJsonArray("records").get(0).getAsJsonObject().get("reference").getAsString();
        String answer = "[label][" + alias + "]";
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, answer, null, List.of(), null));
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.FAILED, null, null, List.of(), "fixture"));
        assertEquals("[label][" + REFERENCE + "]", events.stream().filter(event -> event.kind() == ModelBackend.EventKind.TEXT_DELTA)
                .map(ModelBackend.Event::text).reduce("", String::concat));
    }

    @Test
    void standaloneKnownAliasWithWhitespaceAndProseExpandsEvenAtFinished() {
        var actual = new FakeBackend();
        var events = new ArrayList<ModelBackend.Event>();
        var exchange = new ReferenceAliasBackend(actual).start(new ModelBackend.Request("model", "instructions", List.of(), List.of(), ModelBackend.SearchMode.DISABLED, "request", 512), events::add);
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                new ModelBackend.ToolCall("inventory", "list_items", "{\"holder\":\"player\"}"), List.of(), null));
        exchange.submit(RESULT);
        String alias = JsonParser.parseString(actual.results.getFirst().serializedResult()).getAsJsonObject()
                .getAsJsonArray("records").get(0).getAsJsonObject().get("reference").getAsString();
        String answer = "[" + alias + "] remains prose [" + alias + "] ";
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, answer, null, List.of(), null));
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(),
                List.of(actual.results.getFirst(), new ModelBackend.AssistantOutput("answer", ModelBackend.OutputStatus.COMPLETED,
                        ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText(answer, List.of())))), null));
        String expected = "[" + REFERENCE + "] remains prose [" + REFERENCE + "] ";
        assertEquals(expected, events.stream().filter(event -> event.kind() == ModelBackend.EventKind.TEXT_DELTA)
                .map(ModelBackend.Event::text).reduce("", String::concat));
        assertEquals(expected, ((ModelBackend.OutputText) ((ModelBackend.AssistantOutput)
                events.getLast().replayItems().getLast()).content().getFirst()).text());
    }

    @Test
    void everyCitationWithParenthesizedProseSplitCanonicalizesKnownLabel() {
        for (int split = 0; ; split++) {
            var actual = new FakeBackend();
            var events = new ArrayList<ModelBackend.Event>();
            var exchange = new ReferenceAliasBackend(actual).start(new ModelBackend.Request("model", "instructions", List.of(), List.of(), ModelBackend.SearchMode.DISABLED, "request", 512), events::add);
            actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                    new ModelBackend.ToolCall("inventory", "list_items", "{\"holder\":\"player\"}"), List.of(), null));
            exchange.submit(RESULT);
            String alias = JsonParser.parseString(actual.results.getFirst().serializedResult()).getAsJsonObject()
                    .getAsJsonArray("records").get(0).getAsJsonObject().get("reference").getAsString();
            String answer = "[" + alias + "] (note)";
            if (split > answer.length()) break;
            actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, answer.substring(0, split), null, List.of(), null));
            actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, answer.substring(split), null, List.of(), null));
            actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(),
                    List.of(actual.results.getFirst(), new ModelBackend.AssistantOutput("answer", ModelBackend.OutputStatus.COMPLETED,
                            ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText(answer, List.of())))), null));

            assertEquals("[" + REFERENCE + "] (note)", events.stream().filter(event -> event.kind() == ModelBackend.EventKind.TEXT_DELTA)
                    .map(ModelBackend.Event::text).reduce("", String::concat), "split " + split);
            assertEquals("[" + REFERENCE + "] (note)", ((ModelBackend.OutputText) ((ModelBackend.AssistantOutput)
                    events.getLast().replayItems().getLast()).content().getFirst()).text(), "replay split " + split);
        }
    }

    @Test
    void wrongProviderAndUnknownQualifiedCitationAliasesRemainUnchanged() {
        var actual = new FakeBackend();
        var events = new ArrayList<ModelBackend.Event>();
        var exchange = new ReferenceAliasBackend(actual).start(new ModelBackend.Request("model", "instructions", List.of(), List.of(), ModelBackend.SearchMode.DISABLED, "request", 512), events::add);
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                new ModelBackend.ToolCall("inventory", "list_items", "{\"holder\":\"player\"}"), List.of(), null));
        exchange.submit(RESULT);
        String alias = JsonParser.parseString(actual.results.getFirst().serializedResult()).getAsJsonObject()
                .getAsJsonArray("records").get(0).getAsJsonObject().get("reference").getAsString();
        String answer = "[other:" + alias + "] [native:" + alias + "0] [native:ref:00000000.0.0]";
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, answer, null, List.of(), null));
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.FAILED, null, null, List.of(), "fixture"));
        assertEquals(answer, events.stream().filter(event -> event.kind() == ModelBackend.EventKind.TEXT_DELTA)
                .map(ModelBackend.Event::text).collect(java.util.stream.Collectors.joining()));
    }

    @Test
    void referenceKindsUnknownAlteredAndPriorRequestAliasesNeverSelectAnotherEntry() {
        var actual = new FakeBackend();
        var events = new ArrayList<ModelBackend.Event>();
        var backend = new ReferenceAliasBackend(actual);
        var exchange = backend.start(new ModelBackend.Request("model", "instructions", List.of(), List.of(), ModelBackend.SearchMode.DISABLED, "request", 512), events::add);
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                new ModelBackend.ToolCall("inventory", "list_items", "{\"holder\":\"player\"}"), List.of(), null));
        exchange.submit(RESULT);
        var record = JsonParser.parseString(actual.results.getFirst().serializedResult()).getAsJsonObject()
                .getAsJsonArray("records").get(0).getAsJsonObject();
        String reference = record.get("reference").getAsString();
        for (String invalid : List.of(reference + "0", reference.substring(0, reference.length() - 1) + "z", "ref:00000000.0.0")) {
            var call = new ModelBackend.ToolCall("invalid-" + invalid, "read_evidence", "{\"handles\":[\"" + invalid + "\"]}");
            actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null, call, List.of(), null));
            assertEquals(call, events.getLast().toolCall());
        }
        var wrongKind = new ModelBackend.ToolCall("wrong-kind", "inspect_item", "{\"path\":\"" + reference + "\"}");
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null, wrongKind, List.of(), null));
        assertEquals(wrongKind, events.getLast().toolCall());
        exchange.cancel();
        events.clear();
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, "late [" + reference + "]", null, List.of(), null));
        assertTrue(events.isEmpty());
        exchange = backend.start(new ModelBackend.Request("model", "instructions", List.of(new ModelBackend.Message(ModelBackend.Role.ASSISTANT,
                List.of(new ModelBackend.TextContent("Previous [" + REFERENCE + "]")))), List.of(), ModelBackend.SearchMode.DISABLED, "request", 512), events::add);
        var prior = new ModelBackend.ToolCall("prior", "read_evidence", "{\"handles\":[\"" + reference + "\"]}");
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null, prior, List.of(), null));
        assertEquals(prior, events.getLast().toolCall());
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, "[" + reference + "].", null, List.of(), null));
        assertEquals("[" + reference + "].", events.getLast().text());
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                new ModelBackend.ToolCall("inventory", "list_items", "{\"holder\":\"player\"}"), List.of(), null));
        exchange.submit(RESULT);
        String fresh = JsonParser.parseString(actual.results.getLast().serializedResult()).getAsJsonObject()
                .getAsJsonArray("records").get(0).getAsJsonObject().get("reference").getAsString();
        assertNotEquals(reference, fresh);
        assertEquals(actual.requests.getLast().input(), List.of(new ModelBackend.Message(ModelBackend.Role.ASSISTANT,
                List.of(new ModelBackend.TextContent("Previous [" + REFERENCE + "]")))));
    }

    @Test
    void continuationArgumentsTranslateWhileProvenanceLocationsAndCursorsStayExact() {
        var json = JsonParser.parseString(RESULT.serializedResult()).getAsJsonObject();
        var record = json.getAsJsonArray("records").get(0).getAsJsonObject();
        record.getAsJsonObject("payload").add("location", JsonParser.parseString("{\"slotReference\":\"" + REFERENCE + "\"}"));
        record.getAsJsonObject("source").add("provenance", JsonParser.parseString("[\"" + REFERENCE + "\"]"));
        json.add("nextCall", JsonParser.parseString("{\"name\":\"list_items\",\"arguments\":{\"holder\":\"player\",\"cursor\":\"" + REFERENCE + "\"}}"));
        json.add("restartCall", JsonParser.parseString("{\"name\":\"read_evidence\",\"arguments\":{\"handles\":[\"" + REFERENCE + "\"]}}"));
        var actual = new FakeBackend();
        var exchange = new ReferenceAliasBackend(actual).start(new ModelBackend.Request("model", "instructions", List.of(), List.of(), ModelBackend.SearchMode.DISABLED, "request", 512), ignored -> { });
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                new ModelBackend.ToolCall("inventory", "list_items", "{\"holder\":\"player\"}"), List.of(), null));
        exchange.submit(new ModelBackend.ToolResult("inventory", json.toString(), false));
        var encoded = JsonParser.parseString(actual.results.getFirst().serializedResult()).getAsJsonObject();
        var encodedRecord = encoded.getAsJsonArray("records").get(0).getAsJsonObject();
        assertTrue(encodedRecord.get("reference").getAsString().startsWith("ref:"));
        assertEquals(REFERENCE, encodedRecord.getAsJsonObject("payload").getAsJsonObject("location").get("slotReference").getAsString());
        assertEquals(REFERENCE, encodedRecord.getAsJsonObject("source").getAsJsonArray("provenance").get(0).getAsString());
        assertEquals(REFERENCE, encoded.getAsJsonObject("nextCall").getAsJsonObject("arguments").get("cursor").getAsString());
        assertEquals(encodedRecord.get("reference"), encoded.getAsJsonObject("restartCall").getAsJsonObject("arguments").getAsJsonArray("handles").get(0));
    }

    @Test
    void concurrentResultsDeduplicateCanonicalReferencesWithoutMixingTheirReplay() {
        var actual = new FakeBackend();
        var events = new ArrayList<ModelBackend.Event>();
        var exchange = new ReferenceAliasBackend(actual).start(new ModelBackend.Request("model", "instructions", List.of(), List.of(), ModelBackend.SearchMode.DISABLED, "request", 512), events::add);
        var calls = List.of(new ModelBackend.ToolCall("first", "list_items", "{\"holder\":\"player\"}"),
                new ModelBackend.ToolCall("second", "list_items", "{\"holder\":\"player\"}"));
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
            var call = new ModelBackend.ToolCall("inventory", "list_items", "{\"holder\":\"player\"}");
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
        var call = new ModelBackend.ToolCall("inventory", "list_items", "{\"holder\":\"player\"}");
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
    void webAnnotationRangesFollowSpacedLinkWhitespaceDeletionAndAliasExpansion() {
        var actual = new FakeBackend();
        var events = new ArrayList<ModelBackend.Event>();
        var exchange = new ReferenceAliasBackend(actual).start(new ModelBackend.Request("model", "instructions", List.of(), List.of(), ModelBackend.SearchMode.DISABLED, "request", 512), events::add);
        var call = new ModelBackend.ToolCall("inventory", "list_items", "{\"holder\":\"player\"}");
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null, call, List.of(), null));
        exchange.submit(RESULT);
        String alias = JsonParser.parseString(actual.results.getFirst().serializedResult()).getAsJsonObject()
                .getAsJsonArray("records").get(0).getAsJsonObject().get("reference").getAsString();
        String answer = "🌋 [Bundle] (" + alias + ") then docs.";
        actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), List.of(call, actual.results.getFirst(),
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
                new ModelBackend.ToolCall("inventory", "list_items", "{\"holder\":\"player\"}"), List.of(), null));
        var json = JsonParser.parseString(RESULT.serializedResult()).getAsJsonObject();
        var record = json.getAsJsonArray("records").get(0).getAsJsonObject();
        record.addProperty("reference", "native:item:minecraft:bundle");
        record.getAsJsonObject("source").addProperty("sourceHandle", "item:minecraft:bundle");
        exchange.submit(new ModelBackend.ToolResult("inventory", json.toString(), false));
        var wire = JsonParser.parseString(actual.results.getFirst().serializedResult()).getAsJsonObject().getAsJsonArray("records").get(0).getAsJsonObject();
        assertEquals("native:item:minecraft:bundle", wire.get("reference").getAsString());
        assertEquals("item:minecraft:bundle", wire.getAsJsonObject("source").get("sourceHandle").getAsString());
        String answer = "Use [item:minecraft:bundle] as an unqualified short reference, and `[` as code. [native:item:minecraft:bundle].";
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
                new ModelBackend.ToolCall("inventory", "list_items", "{\"holder\":\"player\"}"), List.of(), null));
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
            var json = JsonParser.parseString("{\"records\":[{\"reference\":\"native:a\",\"source\":{\"providerId\":\"native\",\"sourceHandle\":\"a\"},\"payload\":{\"padding\":\"\"}}]}").getAsJsonObject();
            if (outputLimit) {
                json.getAsJsonArray("records").get(0).getAsJsonObject().getAsJsonObject("payload").addProperty("padding", "x".repeat(131_073 - json.toString().length()));
                assertEquals(131_073, json.toString().length());
            } else {
                json = new JsonObject();
                json.addProperty("detail", "x".repeat(100_000));
            }
            for (int index = 0; index < (outputLimit ? 1 : 30); index++) {
                actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                        new ModelBackend.ToolCall("call-" + index, "list_items", "{\"holder\":\"player\"}"), List.of(), null));
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
