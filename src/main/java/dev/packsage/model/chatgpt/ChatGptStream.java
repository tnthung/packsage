package dev.packsage.model.chatgpt;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.packsage.PackSageLog;
import dev.packsage.model.ModelBackend;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

public final class ChatGptStream {
    private static final int MAX_PENDING_BYTES = 1_048_576;
    private static final int MAX_ERROR_MESSAGE_CHARS = 240;
    private final Consumer<ModelBackend.Event> events;
    private final Consumer<JsonObject> outputItems;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private final Map<String, String> completedArguments = new HashMap<>();
    private boolean cancelled;
    private boolean terminal;

    public ChatGptStream(Consumer<ModelBackend.Event> events) {
        this(events, ignored -> { });
    }

    ChatGptStream(Consumer<ModelBackend.Event> events, Consumer<JsonObject> outputItems) {
        this.events = events;
        this.outputItems = outputItems;
    }

    public synchronized void accept(byte[] bytes) {
        if (cancelled || terminal) return;
        pending.writeBytes(bytes);
        if (pending.size() > MAX_PENDING_BYTES) {
            fail("SSE event exceeded the 1048576-byte limit");
            return;
        }
        byte[] data = pending.toByteArray();
        int consumed = 0;
        while (consumed < data.length) {
            int frameEnd = -1;
            int delimiterLength = 0;
            for (int i = consumed; i < data.length - 1; i++) {
                if (data[i] == '\n' && data[i + 1] == '\n') {
                    frameEnd = i;
                    delimiterLength = 2;
                    break;
                }
                if (i + 3 < data.length && data[i] == '\r' && data[i + 1] == '\n'
                        && data[i + 2] == '\r' && data[i + 3] == '\n') {
                    frameEnd = i;
                    delimiterLength = 4;
                    break;
                }
            }
            if (frameEnd < 0) break;
            process(new String(data, consumed, frameEnd - consumed, StandardCharsets.UTF_8));
            consumed = frameEnd + delimiterLength;
            if (terminal) break;
        }
        if (consumed > 0) {
            pending.reset();
            if (!terminal) pending.write(data, consumed, data.length - consumed);
        }
    }

    public synchronized void complete() {
        if (cancelled || terminal) return;
        if (pending.size() > 0) {
            process(new String(pending.toByteArray(), StandardCharsets.UTF_8));
            pending.reset();
        }
        if (!terminal) fail("stream ended before response.completed");
    }

    public synchronized void cancel() {
        cancelled = true;
        pending.reset();
    }

    private void process(String frame) {
        if (cancelled || terminal) return;
        StringBuilder data = new StringBuilder();
        for (String line : frame.split("\\r?\\n", -1)) {
            if (line.startsWith("data:")) {
                if (data.length() > 0) data.append('\n');
                data.append(line.substring(5).stripLeading());
            }
        }
        if (data.isEmpty() || "[DONE]".contentEquals(data)) return;
        final JsonObject event;
        try {
            event = JsonParser.parseString(data.toString()).getAsJsonObject();
        } catch (RuntimeException error) {
            fail("invalid SSE JSON: " + error.getMessage());
            return;
        }
        JsonElement typeValue = event.get("type");
        if (typeValue == null || !typeValue.isJsonPrimitive() || !typeValue.getAsJsonPrimitive().isString()) {
            fail("SSE event type is missing");
            return;
        }
        String type = typeValue.getAsString();
        if ("response.output_text.delta".equals(type)) {
            if (!event.has("delta") || !event.get("delta").isJsonPrimitive()) {
                fail("text delta is missing");
                return;
            }
            events.accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, event.get("delta").getAsString(), null, List.of(), null));
        } else if ("response.output_item.added".equals(type)) {
            String itemType = event.has("item") && event.get("item").isJsonObject()
                    && event.getAsJsonObject("item").has("type") && event.getAsJsonObject("item").get("type").isJsonPrimitive()
                    ? event.getAsJsonObject("item").get("type").getAsString() : "";
            if ("reasoning".equals(itemType)) {
                events.accept(new ModelBackend.Event(ModelBackend.EventKind.REASONING, null, null, List.of(), null));
            } else if ("web_search_call".equals(itemType)) {
                events.accept(new ModelBackend.Event(ModelBackend.EventKind.HOSTED_SEARCH, null, null, List.of(), null));
            }
        } else if (type.startsWith("response.reasoning") && type.endsWith(".delta")) {
            events.accept(new ModelBackend.Event(ModelBackend.EventKind.REASONING, null, null, List.of(), null));
        } else if ("response.function_call_arguments.done".equals(type)) {
            if (!event.has("item_id") || !event.has("arguments")) {
                fail("function arguments event is incomplete");
                return;
            }
            completedArguments.put(event.get("item_id").getAsString(), event.get("arguments").getAsString());
        } else if ("response.output_item.done".equals(type)) {
            if (!event.has("item") || !event.get("item").isJsonObject()) {
                fail("completed output item is missing");
                return;
            }
            JsonObject item = event.getAsJsonObject("item").deepCopy();
            outputItems.accept(item);
            if (item.has("type") && "function_call".equals(item.get("type").getAsString())) {
                if (!item.has("id") || !item.has("call_id") || !item.has("name") || !item.has("arguments")) {
                    fail("completed function call is incomplete");
                    return;
                }
                String finalized = completedArguments.get(item.get("id").getAsString());
                if (finalized != null && !finalized.equals(item.get("arguments").getAsString())) {
                    fail("function arguments do not match completed output");
                    return;
                }
                events.accept(new ModelBackend.Event(ModelBackend.EventKind.CLIENT_TOOL_CALL, null,
                        new ModelBackend.ToolCall(item.get("call_id").getAsString(),
                                item.has("namespace") && !item.get("namespace").isJsonNull() ? item.get("namespace").getAsString() : null,
                                item.get("name").getAsString(),
                                item.get("arguments").getAsString()), List.of(), null));
            } else if (item.has("type") && "web_search_call".equals(item.get("type").getAsString())) {
                events.accept(new ModelBackend.Event(ModelBackend.EventKind.HOSTED_SEARCH, null, null,
                        List.of(new ModelBackend.ResponseBlock(ModelBackend.ResponseKind.TEXT, item.toString(), null)), null));
            }
        } else if ("response.output_text.annotation.added".equals(type)) {
            if (event.has("annotation") && event.get("annotation").isJsonObject()) {
                JsonObject annotation = event.getAsJsonObject("annotation");
                if (annotation.has("type") && "url_citation".equals(annotation.get("type").getAsString())
                        && annotation.has("url")) {
                    events.accept(new ModelBackend.Event(ModelBackend.EventKind.HOSTED_SEARCH, null, null,
                            List.of(new ModelBackend.ResponseBlock(ModelBackend.ResponseKind.CITATION,
                                    annotation.toString(), annotation.get("url").getAsString())), null));
                }
            }
        } else if ("response.completed".equals(type)) {
            terminal = true;
            events.accept(new ModelBackend.Event(ModelBackend.EventKind.COMPLETED, null, null, List.of(), null));
        } else if ("response.failed".equals(type)) {
            JsonObject response = event.has("response") && event.get("response").isJsonObject() ? event.getAsJsonObject("response") : event;
            JsonObject error = response.has("error") && response.get("error").isJsonObject() ? response.getAsJsonObject("error") : response;
            fail(errorDetail("Response failed", error, event));
        } else if ("response.incomplete".equals(type)) {
            JsonObject response = event.has("response") && event.get("response").isJsonObject() ? event.getAsJsonObject("response") : event;
            JsonObject incomplete = response.has("incomplete_details") && response.get("incomplete_details").isJsonObject()
                    ? response.getAsJsonObject("incomplete_details") : null;
            PackSageLog.LOGGER.warn("PackSage model response incomplete: {}", incompleteSummary(response));
            fail("response incomplete" + (incomplete != null && incomplete.has("reason") ? ": " + incomplete.get("reason").getAsString() : ""));
        } else if ("error".equals(type)) {
            JsonObject error = event.has("error") && event.get("error").isJsonObject() ? event.getAsJsonObject("error") : event;
            fail(errorDetail("Response error", error, event));
        }
    }

    /**
     * One log-safe line for an incomplete response: its reason, a count of output item types and the output token
     * count when reported. Never includes item content, arguments, queries or identifiers.
     */
    static String incompleteSummary(JsonObject response) {
        String reason = null;
        if (response.has("incomplete_details") && response.get("incomplete_details").isJsonObject()) {
            reason = text(response.getAsJsonObject("incomplete_details"), "reason", 100);
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String type : List.of("reasoning", "web_search_call", "function_call", "message")) counts.put(type, 0);
        Map<String, Integer> others = new TreeMap<>();
        if (response.has("output") && response.get("output").isJsonArray()) {
            for (JsonElement item : response.getAsJsonArray("output")) {
                String type = item.isJsonObject() ? text(item.getAsJsonObject(), "type", 60) : null;
                if (type == null || !type.matches("[A-Za-z0-9_.-]+")) type = "unknown";
                if (counts.containsKey(type)) counts.merge(type, 1, Integer::sum);
                else others.merge(type, 1, Integer::sum);
            }
        }
        counts.putAll(others);
        StringBuilder summary = new StringBuilder("reason=").append(reason == null ? "unknown" : reason).append(" output:");
        counts.forEach((type, count) -> summary.append(' ').append(type).append('=').append(count));
        if (response.has("usage") && response.get("usage").isJsonObject()) {
            JsonElement outputTokens = response.getAsJsonObject("usage").get("output_tokens");
            if (outputTokens != null && outputTokens.isJsonPrimitive() && outputTokens.getAsJsonPrimitive().isNumber()) {
                summary.append(" output_tokens=").append(outputTokens.getAsLong());
            }
        }
        return summary.toString();
    }

    private static String errorDetail(String prefix, JsonObject error, JsonObject event) {
        String code = text(error, "code", 100);
        String message = text(error, "message", MAX_ERROR_MESSAGE_CHARS);
        String param = text(error, "param", 100);
        String requestId = text(event, "request_id", 100);
        if (requestId == null) requestId = text(error, "request_id", 100);
        StringBuilder detail = new StringBuilder(prefix);
        if (code == null && message == null) detail.append(": no error detail was provided");
        if (code != null) detail.append(": ").append(code);
        if (message != null) detail.append(": ").append(message);
        if (param != null) detail.append(" (param ").append(param).append(')');
        if (requestId != null) detail.append(" [request ").append(requestId).append(']');
        return detail.toString();
    }

    private static String text(JsonObject object, String name, int limit) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive()) return null;
        String text = value.getAsString().strip();
        if (text.isEmpty()) return null;
        return text.length() > limit ? text.substring(0, limit) + "..." : text;
    }

    private void fail(String detail) {
        if (cancelled || terminal) return;
        terminal = true;
        pending.reset();
        events.accept(new ModelBackend.Event(ModelBackend.EventKind.FAILED, null, null, List.of(), detail));
    }
}
