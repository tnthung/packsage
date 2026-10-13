package dev.packsage.chat;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.packsage.model.ModelBackend;

/** Player-facing activity text; built from tool names and allowlisted arguments only, never from results or credentials. */
final class ActivityLabels {
    static final String PREPARING = "Preparing context…";
    static final String WAITING = "Waiting for the model…";
    static final String THINKING = "Thinking…";
    static final String SEARCHING_WEB = "Searching the web…";
    static final String WRITING = "Writing answer…";
    static final String RETRYING_WITHOUT_WEB_SEARCH = "Retrying without web search…";
    private static final int ARGUMENT_LIMIT = 60;
    private static final int NAME_LIMIT = 40;

    private ActivityLabels() {
    }

    static String tool(ModelBackend.ToolCall call, int step) {
        String label = switch (call.name()) {
            case "search_pack" -> withArgument("Searching pack", call, "text");
            case "search_config" -> withArgument("Searching configs", call, "text");
            case "search_history" -> withArgument("Searching history", call, "text");
            case "plan_recipe" -> withArgument("Planning recipe", call, "identity");
            case "read_config" -> withArgument("Reading config", call, "key");
            case "read_evidence" -> "Reading evidence";
            case "read_history" -> "Reading history";
            case "read_inventory" -> "Reading inventory";
            case "read_progress" -> "Reading quest progress";
            case "list_configs" -> "Listing configs";
            default -> "Using tool: " + safeName(call.name());
        };
        return label + " (step " + step + ")";
    }

    private static String withArgument(String verb, ModelBackend.ToolCall call, String key) {
        String value = argument(call.serializedArguments(), key);
        return value == null ? verb : verb + ": " + value;
    }

    private static String argument(String serializedArguments, String key) {
        try {
            JsonElement parsed = JsonParser.parseString(serializedArguments);
            if (!parsed.isJsonObject()) return null;
            JsonElement value = parsed.getAsJsonObject().get(key);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;
            String text = value.getAsString().replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
            if (text.isEmpty()) return null;
            return text.length() > ARGUMENT_LIMIT ? text.substring(0, ARGUMENT_LIMIT).trim() + "…" : text;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String safeName(String name) {
        String safe = name.replaceAll("[^A-Za-z0-9_.-]", "");
        return safe.length() > NAME_LIMIT ? safe.substring(0, NAME_LIMIT) : safe;
    }
}
