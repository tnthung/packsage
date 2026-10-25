package dev.packsage.chat;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.packsage.model.ModelBackend;

/** Player-facing activity text; tool arguments are allowlisted and result JSON is never shown. */
final class ActivityLabels {
    static final String PREPARING = "Preparing context…";
    static final String WAITING = "Waiting for the model…";
    static final String THINKING = "Thinking…";
    static final String SEARCHING_WEB = "Searching the web…";
    static final String WRITING = "Writing answer…";
    static final String RETRYING_WITHOUT_WEB_SEARCH = "Retrying without web search…";
    private static final int ARGUMENT_LIMIT = 60;
    private static final int NAME_LIMIT = 40;
    private static final int ERROR_DETAIL_LIMIT = 120;
    private static final int ERROR_CODE_LIMIT = 40;

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
            case "list_configs" -> "Listing configs";
            default -> "Using tool: " + safeName(call.name());
        };
        return label + " (step " + step + ")";
    }

    static String toolError(String serializedResult) {
        String detail = null;
        String code = null;
        if (serializedResult != null && serializedResult.startsWith("tool failed:")) {
            detail = serializedResult.substring("tool failed:".length());
        } else if (serializedResult != null) {
            try {
                JsonElement parsed = JsonParser.parseString(serializedResult);
                if (parsed.isJsonObject()) {
                    JsonElement detailValue = parsed.getAsJsonObject().get("detail");
                    if (detailValue != null && detailValue.isJsonPrimitive() && detailValue.getAsJsonPrimitive().isString()) {
                        detail = detailValue.getAsString();
                    }
                    JsonElement codeValue = parsed.getAsJsonObject().get("code");
                    if (codeValue != null && codeValue.isJsonPrimitive() && codeValue.getAsJsonPrimitive().isString()) {
                        code = codeValue.getAsString();
                    }
                }
            } catch (RuntimeException ignored) {
                return "Could not complete";
            }
        }
        if (detail == null) return "Could not complete";
        detail = detail.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        if (detail.length() > ERROR_DETAIL_LIMIT) detail = detail.substring(0, ERROR_DETAIL_LIMIT).trim() + "...";
        if (code != null) {
            code = code.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
            if (code.length() > ERROR_CODE_LIMIT) code = code.substring(0, ERROR_CODE_LIMIT).trim() + "...";
        }
        if (detail.isEmpty()) return "Could not complete";
        return code == null || code.isEmpty() ? detail : detail + " (" + code + ")";
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
