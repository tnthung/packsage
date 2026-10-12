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
