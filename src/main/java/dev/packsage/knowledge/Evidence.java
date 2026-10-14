package dev.packsage.knowledge;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

public final class Evidence {
    private Evidence() {
    }

    public sealed interface JsonValue permits JsonObject, JsonArray, JsonString, JsonNumber, JsonBoolean, JsonNull {
    }

    public record JsonObject(Map<String, JsonValue> values) implements JsonValue {
        public JsonObject {
            values = Map.copyOf(values);
        }
    }

    public record JsonArray(List<JsonValue> values) implements JsonValue {
        public JsonArray {
            values = List.copyOf(values);
        }
    }

    public record JsonString(String value) implements JsonValue {
        public JsonString {
            Objects.requireNonNull(value);
        }
    }

    public record JsonNumber(BigDecimal value) implements JsonValue {
        public JsonNumber {
            Objects.requireNonNull(value);
        }
    }

    public record JsonBoolean(boolean value) implements JsonValue {
    }

    public record JsonNull() implements JsonValue {
    }

    public static String serialize(JsonValue value) {
        return new com.google.gson.GsonBuilder().serializeNulls().create().toJson(toGson(value));
    }

    public static JsonObject deserializeObject(String serialized) {
        return (JsonObject) fromGson(JsonParser.parseString(serialized));
    }

    private static JsonElement toGson(JsonValue value) {
        if (value instanceof JsonObject object) {
            var result = new com.google.gson.JsonObject();
            object.values().forEach((key, nested) -> result.add(key, toGson(nested)));
            return result;
        }
        if (value instanceof JsonArray array) {
            var result = new com.google.gson.JsonArray();
            array.values().forEach(nested -> result.add(toGson(nested)));
            return result;
        }
        if (value instanceof JsonString string) return new com.google.gson.JsonPrimitive(string.value());
        if (value instanceof JsonNumber number) return new com.google.gson.JsonPrimitive(number.value());
        if (value instanceof JsonBoolean booleanValue) return new com.google.gson.JsonPrimitive(booleanValue.value());
        return com.google.gson.JsonNull.INSTANCE;
    }

    private static JsonValue fromGson(JsonElement value) {
        if (value.isJsonNull()) return new JsonNull();
        if (value.isJsonObject()) {
            var result = new java.util.LinkedHashMap<String, JsonValue>();
            value.getAsJsonObject().entrySet().forEach(entry -> result.put(entry.getKey(), fromGson(entry.getValue())));
            return new JsonObject(result);
        }
        if (value.isJsonArray()) {
            var result = new java.util.ArrayList<JsonValue>();
            value.getAsJsonArray().forEach(nested -> result.add(fromGson(nested)));
            return new JsonArray(result);
        }
        if (value.getAsJsonPrimitive().isBoolean()) return new JsonBoolean(value.getAsBoolean());
        if (value.getAsJsonPrimitive().isNumber()) return new JsonNumber(value.getAsBigDecimal());
        return new JsonString(value.getAsString());
    }

    public enum Activity { ACTIVE, SHIPPED, INACTIVE, UNKNOWN }
    public enum Coverage { COMPLETE, PARTIAL, OPAQUE, UNAVAILABLE, ERROR }
    public enum Authority { LOADED, SERVER_READ, VIEWER, INSTALLED_DOCUMENTATION, SAVED_CONFIG, EXTERNAL, UNKNOWN }
    public enum RecordKind { RECIPE, QUEST, CONFIG, GUIDE, ITEM, BLOCK, OTHER }
    public enum Outcome { COMPLETE, PARTIAL, UNKNOWN, ERROR }
    public enum NavigationStatus { OPENED, STALE, UNSUPPORTED, UNAVAILABLE, ERROR }
    public enum Operation { SEARCH, GET, RELATED }
    public enum Consumption { CONSUMED, REUSED, UNKNOWN }
    public enum DecodingStatus { VERIFIED, PARTIAL, OPAQUE, UNKNOWN }

    public record Scope(
            String worldSession,
            String resourcesLocale,
            String playerTeam,
            String providerRuntime,
            long requestGeneration) {
        public Scope {
            Objects.requireNonNull(worldSession);
            Objects.requireNonNull(resourcesLocale);
            Objects.requireNonNull(playerTeam);
            Objects.requireNonNull(providerRuntime);
        }
    }

    public record RecipeAlternative(String type, String comparisonIdentity, JsonObject components,
                                    Integer quantity, Consumption consumption) {
        public RecipeAlternative {
            Objects.requireNonNull(type);
            Objects.requireNonNull(comparisonIdentity);
            Objects.requireNonNull(components);
            Objects.requireNonNull(consumption);
            if (quantity != null && quantity < 1) throw new IllegalArgumentException("quantity must be positive");
        }
    }

    public record RecipeSlot(String groupId, List<RecipeAlternative> alternatives) {
        public RecipeSlot {
            Objects.requireNonNull(groupId);
            alternatives = List.copyOf(alternatives);
        }
    }

    public record RecipeOutput(String type, String comparisonIdentity, JsonObject components, Integer quantity) {
        public RecipeOutput {
            Objects.requireNonNull(type);
            Objects.requireNonNull(comparisonIdentity);
            Objects.requireNonNull(components);
            if (quantity != null && quantity < 1) throw new IllegalArgumentException("quantity must be positive");
        }
    }

    public record RecipePayload(String canonicalRecipeId, List<RecipeSlot> inputs, List<RecipeOutput> outputs,
                                List<RecipeOutput> remainders, List<RecipeAlternative> catalysts,
                                List<String> constraints, DecodingStatus decodingStatus) {
        public RecipePayload {
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
            remainders = List.copyOf(remainders);
            catalysts = List.copyOf(catalysts);
            constraints = List.copyOf(constraints);
            Objects.requireNonNull(decodingStatus);
        }
    }

    public record Source(
            Scope scope,
            String providerId,
            String providerVersion,
            String sourceHandle,
            long observedGeneration,
            Instant observedAt,
            Activity activity,
            Coverage coverage,
            Authority authority,
            List<String> provenance) {
        public Source {
            Objects.requireNonNull(scope);
            Objects.requireNonNull(providerId);
            Objects.requireNonNull(providerVersion);
            Objects.requireNonNull(sourceHandle);
            Objects.requireNonNull(observedAt);
            Objects.requireNonNull(activity);
            Objects.requireNonNull(coverage);
            Objects.requireNonNull(authority);
            provenance = List.copyOf(provenance);
        }
    }

    public record NavigationTarget(Scope scope, String providerId, String sourceHandle, String targetId) {
        public NavigationTarget {
            Objects.requireNonNull(scope);
            Objects.requireNonNull(providerId);
            Objects.requireNonNull(sourceHandle);
            Objects.requireNonNull(targetId);
        }
    }

    public record Record(Source source, RecordKind kind, String title, JsonObject payload, List<NavigationTarget> targets,
                         RecipePayload recipePayload) {
        public Record {
            Objects.requireNonNull(source);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(title);
            Objects.requireNonNull(payload);
            targets = List.copyOf(targets);
        }

        public Record(Source source, RecordKind kind, String title, JsonObject payload, List<NavigationTarget> targets) {
            this(source, kind, title, payload, targets, null);
        }
    }

    public record Query(Operation operation, JsonObject arguments, int limit, Scope cursorScope, String cursor) {
        public Query {
            Objects.requireNonNull(operation);
            Objects.requireNonNull(arguments);
            if (limit < 1) {
                throw new IllegalArgumentException("limit must be positive");
            }
            if (cursor == null && cursorScope != null) {
                throw new IllegalArgumentException("cursor scope requires a cursor");
            }
        }
    }

    public record Result(List<Record> records, Coverage coverage, Outcome outcome, String nextCursor, String detail) {
        public Result {
            records = List.copyOf(records);
            Objects.requireNonNull(coverage);
            Objects.requireNonNull(outcome);
        }

        public Result(List<Record> records, Coverage coverage, Outcome outcome, String nextCursor) {
            this(records, coverage, outcome, nextCursor, null);
        }

        public Result withDetail(String value) {
            return new Result(records, coverage, outcome, nextCursor, value);
        }
    }

    public record NavigationResult(NavigationStatus status, String detail) {
        public NavigationResult {
            Objects.requireNonNull(status);
        }
    }
}
