package dev.packsage.debug;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSerializer;
import dev.packsage.chat.ChatContracts;
import dev.packsage.model.ModelBackend;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

public final class DebugChatState {
    static final Gson GSON = new GsonBuilder().serializeNulls().registerTypeAdapter(Instant.class,
            (JsonSerializer<Instant>) (value, type, context) -> new com.google.gson.JsonPrimitive(value.toString())).create();

    private DebugChatState() { }

    public static JsonObject read(ChatContracts.View view, String requestId, JsonArray timing, ChatContracts.Conversation saved) {
        var turn = (saved == null ? view.conversation() : saved).turns().stream()
                .filter(value -> value.requestId().value().equals(requestId)).findFirst().orElse(null);
        boolean active = view.active() && requestId.equals(view.activeRequestId());
        if (turn == null && !active) throw new IllegalArgumentException("Unknown request");
        var json = new JsonObject();
        json.addProperty("requestId", requestId);
        json.addProperty("conversationId", view.conversation().id());
        json.addProperty("saved", saved != null);
        json.addProperty("status", active ? "active" : turn.outcome().status().name().toLowerCase(Locale.ROOT));
        json.addProperty("reply", active ? view.streamingAnswer() : turn.answer());
        json.addProperty("detail", active ? view.activity() : turn.outcome().detail());
        json.add("errors", GSON.toJsonTree(view.errors()));
        json.add("activities", GSON.toJsonTree(active ? view.activities() : turn.activities()));
        json.add("navigationTargets", GSON.toJsonTree(active ? view.navigationTargets() : turn.navigationTargets()));
        var citations = new JsonArray();
        (active ? view.sources() : turn.sources()).forEach(source -> citations.add(GSON.toJsonTree(source)));
        var tools = new JsonArray();
        List<ModelBackend.InputItem> replay = turn == null ? List.of() : turn.replayItems();
        for (var item : replay) {
            if (item instanceof ModelBackend.ToolCall call) {
                var tool = new JsonObject();
                tool.addProperty("callId", call.callId());
                tool.addProperty("name", call.name());
                try { tool.add("arguments", JsonParser.parseString(call.serializedArguments())); }
                catch (com.google.gson.JsonParseException exception) { tool.addProperty("arguments", call.serializedArguments()); }
                var result = replay.stream().filter(value -> value instanceof ModelBackend.ToolResult found && found.callId().equals(call.callId()))
                        .map(value -> (ModelBackend.ToolResult) value).findFirst().orElse(null);
                if (result != null) {
                    JsonElement parsed;
                    try { parsed = JsonParser.parseString(result.serializedResult()); }
                    catch (com.google.gson.JsonParseException exception) { parsed = new com.google.gson.JsonPrimitive(result.serializedResult()); }
                    tool.addProperty("error", result.error());
                    tool.add("result", parsed);
                    if (parsed.isJsonObject()) {
                        tool.add("coverage", parsed.getAsJsonObject().get("coverage"));
                        tool.add("detail", parsed.getAsJsonObject().get("detail"));
                        var records = parsed.getAsJsonObject().get("records");
                        tool.addProperty("recordCount", records != null && records.isJsonArray() ? records.getAsJsonArray().size() : 0);
                    }
                }
                tools.add(tool);
            } else if (item instanceof ModelBackend.AssistantOutput output) {
                for (var content : output.content()) {
                    if (content instanceof ModelBackend.OutputText text) text.citations().forEach(citation -> citations.add(GSON.toJsonTree(citation)));
                }
            }
        }
        if (active) {
            for (var step : view.activities()) {
                if (step.serializedArguments() == null) continue;
                var tool = new JsonObject();
                tool.addProperty("callId", step.id());
                tool.addProperty("name", step.title());
                try { tool.add("arguments", JsonParser.parseString(step.serializedArguments())); }
                catch (com.google.gson.JsonParseException exception) { tool.addProperty("arguments", step.serializedArguments()); }
                tool.addProperty("detail", step.detail());
                tools.add(tool);
            }
        }
        json.add("toolCalls", tools);
        json.add("citations", citations);
        json.add("timing", timing);
        return json;
    }
}
