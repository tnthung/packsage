package dev.packsage.model.chatgpt;

import dev.packsage.model.ModelBackend;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatGptStreamTest {
    @Test
    void parsesOfficialSseAcrossArbitraryByteBoundariesAndPreservesUnicodeAndCalls() {
        List<ModelBackend.Event> events = new ArrayList<>();
        ChatGptStream stream = new ChatGptStream(events::add);
        byte[] bytes = ChatGptStreamTestFixtures.unicodeAndToolStream().getBytes(StandardCharsets.UTF_8);

        for (int i = 0; i < bytes.length; i++) {
            stream.accept(new byte[]{bytes[i]});
        }
        stream.complete();

        assertEquals(List.of(
                new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, "你", null, List.of(), null),
                new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, "好", null, List.of(), null),
                new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                        new ModelBackend.ToolCall("call_123", "packsage", "read_evidence", "{\"id\":\"x\"}"), List.of(), null),
                new ModelBackend.Event(ModelBackend.EventKind.HOSTED_SEARCH, null, null,
                        List.of(new ModelBackend.ResponseBlock(ModelBackend.ResponseKind.CITATION,
                                "{\"type\":\"url_citation\",\"title\":\"PackSage docs\",\"url\":\"https://example.test/docs\"}",
                                "https://example.test/docs")), null),
                new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), null)
        ), events);
    }

    @Test
    void eofBeforeResponseCompletedIsATerminalFailure() {
        List<ModelBackend.Event> events = new ArrayList<>();
        ChatGptStream stream = new ChatGptStream(events::add);

        stream.accept(("data: " + ChatGptStreamTestFixtures.textDelta("partial") + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        stream.complete();

        assertEquals(2, events.size());
        assertEquals(ModelBackend.EventKind.FAILED, events.getLast().kind());
        assertTrue(events.getLast().detail().contains("ended before response.completed"));
    }

    @Test
    void providerFailureRetainsCodeMessageAndRequestId() {
        List<ModelBackend.Event> events = new ArrayList<>();
        ChatGptStream stream = new ChatGptStream(events::add);

        stream.accept(("data: {\"type\":\"error\",\"sequence_number\":1,\"code\":\"subscription_sharing_unsupported_capability\",\"message\":\"Search unavailable\",\"param\":\"tools\",\"request_id\":\"req_fixture\"}\n\n").getBytes(StandardCharsets.UTF_8));
        stream.complete();

        assertEquals(1, events.size());
        assertEquals(ModelBackend.EventKind.FAILED, events.getFirst().kind());
        assertTrue(events.getFirst().detail().contains("subscription_sharing_unsupported_capability"));
        assertTrue(events.getFirst().detail().contains("Search unavailable"));
        assertTrue(events.getFirst().detail().contains("req_fixture"));
    }

    @Test
    void nestedStreamErrorShowsCodeAndMessage() {
        List<ModelBackend.Event> events = new ArrayList<>();
        ChatGptStream stream = new ChatGptStream(events::add);

        stream.accept(("data: {\"type\":\"error\",\"sequence_number\":3,\"error\":{\"type\":\"invalid_request_error\",\"code\":\"context_length_exceeded\",\"message\":\"Input is too long.\",\"param\":\"input\"}}\n\n")
                .getBytes(StandardCharsets.UTF_8));

        assertEquals(1, events.size());
        assertEquals(ModelBackend.EventKind.FAILED, events.getFirst().kind());
        assertTrue(events.getFirst().detail().startsWith("Response error: context_length_exceeded: Input is too long."),
                events.getFirst().detail());
        assertTrue(events.getFirst().detail().contains("(param input)"), events.getFirst().detail());
    }

    @Test
    void topLevelStreamErrorKeepsTheSameReadableShape() {
        List<ModelBackend.Event> events = new ArrayList<>();
        ChatGptStream stream = new ChatGptStream(events::add);

        stream.accept(("data: {\"type\":\"error\",\"code\":\"server_error\",\"message\":\"Try again.\"}\n\n").getBytes(StandardCharsets.UTF_8));

        assertEquals("Response error: server_error: Try again.", events.getFirst().detail());
    }

    @Test
    void streamErrorWithoutDetailsSaysSoAndLongMessagesAreTruncated() {
        List<ModelBackend.Event> events = new ArrayList<>();
        new ChatGptStream(events::add).accept("data: {\"type\":\"error\"}\n\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("Response error: no error detail was provided", events.getFirst().detail());

        List<ModelBackend.Event> longEvents = new ArrayList<>();
        new ChatGptStream(longEvents::add).accept(("data: {\"type\":\"error\",\"error\":{\"code\":\"x\",\"message\":\""
                + "m".repeat(2000) + "\"}}\n\n").getBytes(StandardCharsets.UTF_8));
        assertTrue(longEvents.getFirst().detail().length() <= 400, Integer.toString(longEvents.getFirst().detail().length()));
        assertTrue(longEvents.getFirst().detail().startsWith("Response error: x: mmm"));
    }

    @Test
    void responseFailedReadsTheNestedErrorAndRequestId() {
        List<ModelBackend.Event> events = new ArrayList<>();
        new ChatGptStream(events::add).accept(("data: {\"type\":\"response.failed\",\"response\":{\"status\":\"failed\",\"error\":{\"code\":\"server_error\",\"message\":\"Internal failure.\"}},\"request_id\":\"req_1\"}\n\n")
                .getBytes(StandardCharsets.UTF_8));
        assertEquals("Response failed: server_error: Internal failure. [request req_1]", events.getFirst().detail());
    }

    @Test
    void incompleteResponseKeepsTheProviderReasonAndSummarizesOutputTypesWithoutContent() {
        String incomplete = "{\"type\":\"response.incomplete\",\"sequence_number\":40,\"response\":{\"status\":\"incomplete\","
                + "\"incomplete_details\":{\"reason\":\"max_messages\"},\"output\":["
                + "{\"id\":\"rs_1\",\"type\":\"reasoning\",\"encrypted_content\":\"SECRET-BLOB\",\"summary\":[]},"
                + "{\"id\":\"ws_1\",\"type\":\"web_search_call\",\"status\":\"completed\",\"action\":{\"type\":\"search\",\"query\":\"private query\"}},"
                + "{\"id\":\"ws_2\",\"type\":\"web_search_call\",\"status\":\"completed\"},"
                + "{\"id\":\"rs_2\",\"type\":\"reasoning\",\"encrypted_content\":\"SECRET-BLOB\",\"summary\":[]},"
                + "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"secret answer\"}]}],"
                + "\"usage\":{\"output_tokens\":321}}}";
        List<ModelBackend.Event> events = new ArrayList<>();
        new ChatGptStream(events::add).accept(("data: " + incomplete + "\n\n").getBytes(StandardCharsets.UTF_8));

        assertEquals(1, events.size());
        assertEquals(ModelBackend.EventKind.FAILED, events.getFirst().kind());
        assertEquals("response incomplete: max_messages", events.getFirst().detail());
        String summary = ChatGptStream.incompleteSummary(com.google.gson.JsonParser.parseString(incomplete).getAsJsonObject()
                .getAsJsonObject("response"));
        assertEquals("reason=max_messages output: reasoning=2 web_search_call=2 function_call=0 message=1 output_tokens=321", summary);
        assertTrue(!summary.contains("SECRET") && !summary.contains("secret") && !summary.contains("private"), summary);

        assertEquals("reason=unknown output: reasoning=0 web_search_call=0 function_call=0 message=0",
                ChatGptStream.incompleteSummary(new com.google.gson.JsonObject()));
    }

    @Test
    void cancellationStopsEventsAlreadyBufferedAfterCancellation() {
        List<ModelBackend.Event> events = new ArrayList<>();
        ChatGptStream stream = new ChatGptStream(events::add);

        stream.accept(("data: " + ChatGptStreamTestFixtures.textDelta("before") + "\n\n").getBytes(StandardCharsets.UTF_8));
        stream.cancel();
        stream.accept(("data: " + ChatGptStreamTestFixtures.textDelta("after") + "\n\n").getBytes(StandardCharsets.UTF_8));
        stream.complete();

        assertEquals(1, events.size());
        assertEquals("before", events.getFirst().text());
    }

    @Test
    void reasoningAndHostedSearchItemsEmitSecretFreeProgressEvents() {
        List<ModelBackend.Event> events = new ArrayList<>();
        ChatGptStream stream = new ChatGptStream(events::add);

        String stream1 = "data: {\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"id\":\"rs_1\",\"type\":\"reasoning\","
                + "\"encrypted_content\":\"SECRET-BLOB\",\"summary\":[]}}\n\n"
                + "data: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"private thoughts\"}\n\n"
                + "data: {\"type\":\"response.output_item.added\",\"output_index\":1,\"item\":{\"id\":\"ws_1\",\"type\":\"web_search_call\","
                + "\"status\":\"in_progress\"}}\n\n"
                + "data: {\"type\":\"response.output_item.added\",\"output_index\":2,\"item\":{\"id\":\"msg_1\",\"type\":\"message\"}}\n\n";
        stream.accept(stream1.getBytes(StandardCharsets.UTF_8));

        assertEquals(List.of(
                new ModelBackend.Event(ModelBackend.EventKind.REASONING, null, null, List.of(), null),
                new ModelBackend.Event(ModelBackend.EventKind.REASONING, null, null, List.of(), null),
                new ModelBackend.Event(ModelBackend.EventKind.HOSTED_SEARCH, null, null, List.of(), null)
        ), events);
    }

    private static final class ChatGptStreamTestFixtures {
        private static String unicodeAndToolStream() {
            return "data: " + textDelta("你") + "\r\n\r\n"
                    + "data: " + textDelta("好") + "\r\n\r\n"
                    + "data: " + toolArgumentsDone("fc_123", "{\\\"id\\\":\\\"x\\\"}") + "\r\n\r\n"
                    + "data: " + toolOutputDone("fc_123", "call_123", "read_evidence", "{\\\"id\\\":\\\"x\\\"}") + "\r\n\r\n"
                    + "data: " + citation("PackSage docs", "https://example.test/docs") + "\r\n\r\n"
                    + "data: {\"type\":\"response.completed\",\"sequence_number\":6,\"response\":{\"id\":\"resp_123\",\"status\":\"completed\",\"output\":[]}}\r\n\r\n";
        }

        private static String textDelta(String text) {
            return "{\"type\":\"response.output_text.delta\",\"delta\":\"" + text + "\"}";
        }

        private static String toolArgumentsDone(String itemId, String arguments) {
            return "{\"type\":\"response.function_call_arguments.done\",\"sequence_number\":3,\"item_id\":\"" + itemId
                    + "\",\"output_index\":0,\"arguments\":\"" + arguments + "\"}";
        }

        private static String toolOutputDone(String itemId, String callId, String name, String arguments) {
            return "{\"type\":\"response.output_item.done\",\"sequence_number\":4,\"output_index\":0,\"item\":{\"id\":\"" + itemId
                    + "\",\"type\":\"function_call\",\"status\":\"completed\",\"call_id\":\"" + callId + "\",\"namespace\":\"packsage\",\"name\":\"" + name
                    + "\",\"arguments\":\"" + arguments + "\"}}";
        }

        private static String citation(String title, String url) {
            return "{\"type\":\"response.output_text.annotation.added\",\"annotation\":{\"type\":\"url_citation\",\"title\":\""
                    + title + "\",\"url\":\"" + url + "\"}}";
        }
    }
}
