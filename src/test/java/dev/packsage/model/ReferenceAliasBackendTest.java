package dev.packsage.model;

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
    private static final String SLOT = "inventory:07cd5168-5d6e-48b3-95ac-9f9889de7ef6:player:main:12";
    private static final ModelBackend.ToolResult RESULT = new ModelBackend.ToolResult("inventory", """
            {"coverage":"COMPLETE","outcome":"COMPLETE","records":[{
              "reference":"native:inventory:07cd5168-5d6e-48b3-95ac-9f9889de7ef6:player:main",
              "source":{"providerId":"native","sourceHandle":"inventory:07cd5168-5d6e-48b3-95ac-9f9889de7ef6:player:main"},
              "payload":{"slotReference":"inventory:07cd5168-5d6e-48b3-95ac-9f9889de7ef6:player:main:12",
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
    }

    @Test
    void everyCitationSplitExpandsToCanonicalTextAndTerminalReplayRetainsExactCanonicalResult() {
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
            String answer = "Bundle [" + alias + "].";
            if (split > answer.length()) break;
            actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.ROUND_STARTED, null, null, List.of(), null));
            actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, answer.substring(0, split), null, List.of(), null));
            actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, answer.substring(split), null, List.of(), null));
            actual.events.accept(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(),
                    List.of(new ModelBackend.ToolCall("inventory", "search_inventory", "{\"target\":{\"type\":\"player\"}}"),
                            actual.results.getFirst(), new ModelBackend.AssistantOutput("answer", ModelBackend.OutputStatus.COMPLETED,
                                    ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText(answer, List.of())))), null));
            assertEquals("Bundle [" + REFERENCE + "].", events.stream()
                    .filter(event -> event.kind() == ModelBackend.EventKind.TEXT_DELTA).map(ModelBackend.Event::text)
                    .reduce("", String::concat), "split " + split);
            assertEquals(RESULT, events.getLast().replayItems().get(1));
        }
    }

    private static final class FakeBackend implements ModelBackend {
        private final List<ToolResult> results = new ArrayList<>();
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
            return new Exchange() {
                @Override
                public void submit(ToolResult result) { results.add(result); }

                @Override
                public void cancel() { }
            };
        }
    }
}
