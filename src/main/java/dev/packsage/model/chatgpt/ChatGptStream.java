package dev.packsage.model.chatgpt;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.packsage.model.ModelBackend;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class ChatGptStream {
    private static final int MAX_PENDING_BYTES = 1_048_576;
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
                        new ModelBackend.ToolCall(item.get("call_id").getAsString(), item.get("name").getAsString(),
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
            JsonObject error = response.has("error") && response.get("error").isJsonObject() ? response.getAsJsonObject("error") : null;
            String detail = "response failed";
            if (error != null) {
                if (error.has("code") && !error.get("code").isJsonNull()) detail += ": " + error.get("code").getAsString();
                if (error.has("message") && !error.get("message").isJsonNull()) detail += " - " + error.get("message").getAsString();
                if (error.has("param") && !error.get("param").isJsonNull()) detail += " (param " + error.get("param").getAsString() + ")";
            }
            if (event.has("request_id") && !event.get("request_id").isJsonNull()) detail += " [request " + event.get("request_id").getAsString() + "]";
            fail(detail);
        } else if ("response.incomplete".equals(type)) {
            JsonObject response = event.has("response") && event.get("response").isJsonObject() ? event.getAsJsonObject("response") : event;
            JsonObject incomplete = response.has("incomplete_details") && response.get("incomplete_details").isJsonObject()
                    ? response.getAsJsonObject("incomplete_details") : null;
            fail("response incomplete" + (incomplete != null && incomplete.has("reason") ? ": " + incomplete.get("reason").getAsString() : ""));
        } else if ("error".equals(type)) {
            String detail = "response error";
            if (event.has("code") && !event.get("code").isJsonNull()) detail += ": " + event.get("code").getAsString();
            if (event.has("message") && !event.get("message").isJsonNull()) detail += " - " + event.get("message").getAsString();
            if (event.has("param") && !event.get("param").isJsonNull()) detail += " (param " + event.get("param").getAsString() + ")";
            if (event.has("request_id") && !event.get("request_id").isJsonNull()) detail += " [request " + event.get("request_id").getAsString() + "]";
            fail(detail);
        }
    }

    private void fail(String detail) {
        if (cancelled || terminal) return;
        terminal = true;
        pending.reset();
        events.accept(new ModelBackend.Event(ModelBackend.EventKind.FAILED, null, null, List.of(), detail));
    }
}
