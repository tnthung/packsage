package dev.packsage.model;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

public final class PackSageTools {
    public static final Map<String, String> NATIVE_INVENTORY_TARGETS = Map.of(
            "player", "{\"type\":\"object\",\"properties\":{\"type\":{\"const\":\"player\"}},\"required\":[\"type\"],\"additionalProperties\":false}",
            "opened", "{\"type\":\"object\",\"properties\":{\"type\":{\"const\":\"opened\"}},\"required\":[\"type\"],\"additionalProperties\":false}",
            "targeted", "{\"type\":\"object\",\"properties\":{\"type\":{\"const\":\"targeted\"}},\"required\":[\"type\"],\"additionalProperties\":false}",
            "ridden", "{\"type\":\"object\",\"properties\":{\"type\":{\"const\":\"ridden\"}},\"required\":[\"type\"],\"additionalProperties\":false}",
            "item", "{\"type\":\"object\",\"properties\":{\"type\":{\"const\":\"item\"},\"slot\":{\"type\":\"string\",\"enum\":[\"mainhand\",\"offhand\"]},\"slotReference\":{\"type\":\"string\",\"maxLength\":1024}},\"required\":[\"type\"],\"additionalProperties\":false}");
    private static final List<String> NATIVE_REGISTRIES = List.of("item", "block", "item_tag", "enchantment", "mob_effect", "potion");
    private static final com.google.gson.Gson JSON = new com.google.gson.GsonBuilder().serializeNulls()
            .registerTypeAdapter(Instant.class, (com.google.gson.JsonSerializer<Instant>) (value, type, context) -> new com.google.gson.JsonPrimitive(value.toString()))
            .registerTypeHierarchyAdapter(Evidence.JsonValue.class, (com.google.gson.JsonSerializer<Evidence.JsonValue>) (value, type, context) -> JsonParser.parseString(Evidence.serialize(value)))
            .create();
    private final Function<ReadRequest, CompletionStage<Evidence.Result>> dispatch;
    private final List<Toolbox.Metadata> metadata;

    public record ReadRequest(String tool, Evidence.JsonObject arguments, Evidence.Scope scope) {
        public ReadRequest {
            Objects.requireNonNull(tool);
            Objects.requireNonNull(arguments);
            Objects.requireNonNull(scope);
        }
    }

    public record ToolResponse(ModelBackend.ToolResult result, List<Evidence.Record> records) {
        public ToolResponse {
            Objects.requireNonNull(result);
            records = List.copyOf(records);
        }
    }

    public PackSageTools(Function<ReadRequest, CompletionStage<Evidence.Result>> dispatch) {
        this(dispatch, nativeMetadata());
    }

    public PackSageTools(Function<ReadRequest, CompletionStage<Evidence.Result>> dispatch, List<Toolbox.Metadata> metadata) {
        this.dispatch = Objects.requireNonNull(dispatch);
        this.metadata = List.copyOf(metadata);
    }

    public static List<ModelBackend.ToolDefinition> definitions() {
        return definitions(nativeMetadata());
    }

    private static List<Toolbox.Metadata> nativeMetadata() {
        return List.of(new Toolbox.Metadata("native", List.of(), true, NATIVE_REGISTRIES, NATIVE_INVENTORY_TARGETS),
                new Toolbox.Metadata("ftb_quests", List.of("quest"), false, List.of(), Map.of()),
                new Toolbox.Metadata("patchouli", List.of("guide"), false, List.of(), Map.of()),
                new Toolbox.Metadata("jei", List.of(), true, List.of(), Map.of()));
    }

    public static List<ModelBackend.ToolDefinition> definitions(List<Toolbox.Metadata> metadata) {
        var documentProviders = metadata.stream().filter(value -> !value.documentTypes().isEmpty()).map(Toolbox.Metadata::id).distinct().toList();
        var recipeProviders = metadata.stream().filter(Toolbox.Metadata::recipes).map(Toolbox.Metadata::id).distinct().toList();
        var registryProviders = metadata.stream().filter(value -> !value.registries().isEmpty()).map(Toolbox.Metadata::id).distinct().toList();
        var types = metadata.stream().flatMap(value -> value.documentTypes().stream()).distinct().toList();
        var registries = metadata.stream().flatMap(value -> value.registries().stream()).distinct().toList();
        var targetChoices = new JsonArray();
        metadata.forEach(value -> value.inventoryTargets().forEach((type, schema) -> {
            var targetSchema = JsonParser.parseString(schema).getAsJsonObject();
            targetChoices.add(targetSchema);
        }));
        var targetSchema = new JsonObject(); targetSchema.add("oneOf", targetChoices);
        var definitions = new ArrayList<ModelBackend.ToolDefinition>();
        for (var name : List.of("search_document", "search_recipe", "search_registry", "search_inventory", "read_evidence", "read_progress", "plan_recipe", "list_configs", "search_config", "read_config", "search_history", "read_history")) {
            var properties = new JsonObject();
            var requiredNames = new ArrayList<String>();
            var description = "Read current evidence for " + name + ". Preserve source authority, partial coverage and observed references.";
            switch (name) {
                case "search_document" -> { properties.add("query", stringSchema(512)); requiredNames.add("query"); properties.add("filter", objectSchema(Map.of("types", arraySchema(types, 16), "provider", enumStringSchema(documentProviders, 32)))); }
                case "search_recipe" -> properties.add("filter", objectSchema(Map.of("text", stringSchema(512), "outputItem", idSchema(), "provider", enumStringSchema(recipeProviders, 32))));
                case "search_registry" -> { properties.add("registry", enumRequiredStringSchema(registries, 64)); requiredNames.add("registry"); properties.add("filter", objectSchema(Map.of("text", stringSchema(512), "ids", idArraySchema(64), "tags", idArraySchema(64), "provider", enumStringSchema(registryProviders, 32)))); }
                case "search_inventory" -> { properties.add("target", targetSchema); requiredNames.add("target"); properties.add("filter", objectSchema(Map.of("names", stringArraySchema(32, 256), "ids", idArraySchema(32), "tags", idArraySchema(32), "tooltip", stringArraySchema(32, 512), "sections", stringArraySchema(32, 64)))); }
                case "read_evidence", "read_progress" -> { properties.add("handles", handlesSchema(16)); requiredNames.add("handles"); }
                case "plan_recipe" -> { properties.add("identity", idSchema()); properties.add("quantity", integerSchema(1, 1000000)); properties.add("depth", nullableIntegerSchema(0, 8)); requiredNames.addAll(List.of("identity", "quantity")); }
                case "search_config", "search_history" -> { properties.add("text", stringSchema(512)); requiredNames.add("text"); }
                case "read_config" -> { properties.add("handle", stringSchema(1024)); properties.add("key", nullableStringSchema(512)); properties.add("section", nullableStringSchema(512)); requiredNames.add("handle"); }
                case "read_history" -> { properties.add("handles", handlesSchema(8)); properties.add("maxChars", nullableIntegerSchema(256, 16000)); requiredNames.add("handles"); }
                case "list_configs" -> { }
                default -> throw new IllegalStateException(name);
            }
            if (List.of("search_document", "search_recipe", "search_registry", "search_inventory", "list_configs", "search_config", "search_history").contains(name)) {
                properties.add("limit", nullableIntegerSchema(1, name.equals("search_inventory") ? 32 : name.equals("search_history") ? 16 : 64));
                properties.add("cursor", nullableStringSchema(1024));
            }
            if (name.equals("read_config")) properties.add("limit", nullableIntegerSchema(1, 64));
            var schema = new JsonObject(); schema.addProperty("type", "object"); schema.addProperty("additionalProperties", false); schema.add("properties", properties);
            var required = new JsonArray(); requiredNames.forEach(required::add); schema.add("required", required);
            definitions.add(new ModelBackend.ToolDefinition(name, description, schema.toString()));
        }
        return List.copyOf(definitions);
    }

    private static JsonObject stringSchema(int maxLength) { var schema = new JsonObject(); schema.addProperty("type", "string"); schema.addProperty("maxLength", maxLength); return schema; }
    private static JsonObject nullableStringSchema(int maxLength) { var schema = stringSchema(maxLength); var types = new JsonArray(); types.add("string"); types.add("null"); schema.add("type", types); return schema; }
    private static JsonObject idSchema() { var schema = nullableStringSchema(256); schema.addProperty("pattern", "^[a-z0-9_.-]+:[a-z0-9/._-]+$"); return schema; }
    private static JsonObject enumStringSchema(List<String> values, int maxLength) { var schema = nullableStringSchema(maxLength); var array = new JsonArray(); values.forEach(array::add); array.add(com.google.gson.JsonNull.INSTANCE); schema.add("enum", array); return schema; }
    private static JsonObject enumRequiredStringSchema(List<String> values, int maxLength) { var schema = stringSchema(maxLength); var array = new JsonArray(); values.forEach(array::add); schema.add("enum", array); return schema; }
    private static JsonObject integerSchema(int min, int max) { var schema = new JsonObject(); schema.addProperty("type", "integer"); schema.addProperty("minimum", min); schema.addProperty("maximum", max); return schema; }
    private static JsonObject nullableIntegerSchema(int min, int max) { var schema = integerSchema(min, max); var types = new JsonArray(); types.add("integer"); types.add("null"); schema.add("type", types); return schema; }
    private static JsonObject stringArraySchema(int maxItems, int maxLength) { var schema = new JsonObject(); var types = new JsonArray(); types.add("array"); types.add("null"); schema.add("type", types); schema.addProperty("maxItems", maxItems); schema.add("items", stringSchema(maxLength)); return schema; }
    private static JsonObject idArraySchema(int maxItems) { var schema = stringArraySchema(maxItems, 256); schema.getAsJsonObject("items").addProperty("pattern", "^[a-z0-9_.-]+:[a-z0-9/._-]+$"); return schema; }
    private static JsonObject arraySchema(List<String> values, int maxItems) { var schema = stringArraySchema(maxItems, 64); var choices = new JsonArray(); values.forEach(choices::add); schema.getAsJsonObject("items").add("enum", choices); return schema; }
    private static JsonObject handlesSchema(int maxItems) { var schema = new JsonObject(); schema.addProperty("type", "array"); schema.addProperty("minItems", 1); schema.addProperty("maxItems", maxItems); schema.add("items", stringSchema(1024)); return schema; }
    private static JsonObject objectSchema(Map<String, JsonObject> fields) { var schema = new JsonObject(); var types = new JsonArray(); types.add("object"); types.add("null"); schema.add("type", types); var props = new JsonObject(); fields.forEach(props::add); schema.add("properties", props); schema.addProperty("additionalProperties", false); return schema; }

    private static void validateValue(com.google.gson.JsonElement json, JsonObject rule, String name) {
        if (rule.has("oneOf")) {
            for (var choice : rule.getAsJsonArray("oneOf")) {
                try { validateValue(json, choice.getAsJsonObject(), name); return; }
                catch (IllegalArgumentException ignored) { }
            }
            throw new IllegalArgumentException("invalid selection: " + name);
        }
        if (json.isJsonNull()) {
            if (!(rule.get("type").isJsonArray() && rule.getAsJsonArray("type").asList().stream().anyMatch(type -> type.getAsString().equals("null")))) {
                throw new IllegalArgumentException("argument cannot be null: " + name);
            }
            return;
        }
        if (rule.has("type")) {
            var types = rule.get("type").isJsonArray() ? rule.getAsJsonArray("type") : new JsonArray();
            if (!rule.get("type").isJsonArray()) types.add(rule.get("type"));
            var validType = types.asList().stream().anyMatch(type -> switch (type.getAsString()) {
                case "object" -> json.isJsonObject();
                case "array" -> json.isJsonArray();
                case "string" -> json.isJsonPrimitive() && json.getAsJsonPrimitive().isString();
                case "integer" -> json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber() && json.getAsBigDecimal().stripTrailingZeros().scale() <= 0;
                case "number" -> json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber();
                case "boolean" -> json.isJsonPrimitive() && json.getAsJsonPrimitive().isBoolean();
                default -> false;
            });
            if (!validType) throw new IllegalArgumentException("invalid argument type: " + name);
        }
        if (rule.has("const") && !rule.get("const").equals(json)) throw new IllegalArgumentException("invalid target type: " + name);
        if (rule.has("enum") && !rule.getAsJsonArray("enum").contains(json)) throw new IllegalArgumentException("invalid selection: " + name);
        if (json.isJsonObject()) {
            var object = json.getAsJsonObject();
            var fields = rule.getAsJsonObject("properties");
            for (var key : object.keySet()) if (fields == null || !fields.has(key) && rule.has("additionalProperties") && !rule.get("additionalProperties").getAsBoolean()) {
                throw new IllegalArgumentException("unknown field: " + key);
            }
            if (fields != null) for (var key : fields.keySet()) if (object.has(key)) validateValue(object.get(key), fields.getAsJsonObject(key), name + "." + key);
            if (rule.has("required")) for (var required : rule.getAsJsonArray("required")) if (!object.has(required.getAsString())) throw new IllegalArgumentException("missing field: " + name + "." + required.getAsString());
        } else if (json.isJsonArray()) {
            var array = json.getAsJsonArray();
            if (rule.has("minItems") && array.size() < rule.get("minItems").getAsInt() || rule.has("maxItems") && array.size() > rule.get("maxItems").getAsInt()) throw new IllegalArgumentException("invalid selection: " + name);
            if (rule.has("items")) for (var item : array) validateValue(item, rule.getAsJsonObject("items"), name);
        } else if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
            var text = json.getAsString();
            if (rule.has("maxLength") && text.length() > rule.get("maxLength").getAsInt()) throw new IllegalArgumentException("invalid text argument: " + name);
            if (rule.has("pattern") && !text.matches(rule.get("pattern").getAsString())) throw new IllegalArgumentException("invalid identity: " + name);
        } else if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) {
            var number = json.getAsBigDecimal();
            if (rule.has("minimum") && number.compareTo(rule.get("minimum").getAsBigDecimal()) < 0 || rule.has("maximum") && number.compareTo(rule.get("maximum").getAsBigDecimal()) > 0 || rule.get("type").isJsonPrimitive() && rule.get("type").getAsString().equals("integer") && number.stripTrailingZeros().scale() > 0) throw new IllegalArgumentException("argument outside bounds: " + name);
        } else if (rule.has("type") && rule.get("type").isJsonPrimitive() && rule.get("type").getAsString().equals("string")) {
            throw new IllegalArgumentException("invalid text argument: " + name);
        }
    }

    public CompletionStage<ToolResponse> execute(ModelBackend.ToolCall call, Evidence.Scope scope) {
        try {
            if (call.serializedArguments().length() > 16_384) throw new IllegalArgumentException("tool arguments exceed limit");
            var schema = definitions(metadata).stream().filter(definition -> definition.name().equals(call.name())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown read tool"));
            var arguments = Evidence.deserializeObject(call.serializedArguments());
            var properties = JsonParser.parseString(schema.serializedSchema()).getAsJsonObject().getAsJsonObject("properties");
            var toolSchema = JsonParser.parseString(schema.serializedSchema()).getAsJsonObject();
            for (var required : toolSchema.getAsJsonArray("required")) if (!arguments.values().containsKey(required.getAsString())
                    || arguments.values().get(required.getAsString()) instanceof Evidence.JsonNull) throw new IllegalArgumentException("missing argument: " + required.getAsString());
            for (var entry : arguments.values().entrySet()) {
                if (!properties.has(entry.getKey())) throw new IllegalArgumentException("unknown argument: " + entry.getKey());
                validateValue(JsonParser.parseString(Evidence.serialize(entry.getValue())), properties.getAsJsonObject(entry.getKey()), entry.getKey());
            }
            if (call.name().equals("search_history")
                    && ((Evidence.JsonString) arguments.values().get("text")).value().isBlank()) {
                throw new IllegalArgumentException("search text must not be blank");
            }
            if (call.name().equals("search_inventory")) {
                var target = JsonParser.parseString(Evidence.serialize(arguments.values().get("target"))).getAsJsonObject();
                if (target.get("type").getAsString().equals("item") && target.has("slot") == target.has("slotReference")) {
                    throw new IllegalArgumentException("item target requires exactly one of slot or slotReference");
                }
                if (target.has("slotReference") && target.get("slotReference").getAsString().isBlank()) throw new IllegalArgumentException("slotReference must not be blank");
            }
            if (call.name().equals("read_config") && !(arguments.values().get("key") instanceof Evidence.JsonString)
                    && !(arguments.values().get("section") instanceof Evidence.JsonString)) throw new IllegalArgumentException("select a config key or section");
            return dispatch.apply(new ReadRequest(call.name(), arguments, scope)).handle((result, failure) -> {
                if (failure != null || result == null) return new ToolResponse(new ModelBackend.ToolResult(call.callId(),
                        "{\"coverage\":\"ERROR\",\"outcome\":\"ERROR\",\"detail\":\"evidence extraction failed\",\"records\":[]}", true), List.of());
                if (result.records().stream().anyMatch(record -> !record.source().scope().equals(scope)
                        || record.targets().stream().anyMatch(target -> !target.scope().equals(scope)
                        || !target.providerId().equals(record.source().providerId()) || !target.sourceHandle().equals(record.source().sourceHandle())))) {
                    return new ToolResponse(new ModelBackend.ToolResult(call.callId(),
                            "{\"coverage\":\"ERROR\",\"outcome\":\"ERROR\",\"detail\":\"stale or invalid evidence scope\",\"records\":[]}", true), List.of());
                }
                var json = new JsonObject();
                json.addProperty("coverage", result.coverage().name());
                json.addProperty("outcome", result.outcome().name());
                json.addProperty("nextCursor", result.nextCursor());
                if (result.detail() != null) json.addProperty("detail", result.detail());
                var records = new JsonArray();
                var accepted = new ArrayList<Evidence.Record>();
                for (var record : result.records()) {
                    var encoded = new JsonObject();
                    encoded.addProperty("reference", record.source().providerId() + ":" + record.source().sourceHandle());
                    encoded.add("source", JSON.toJsonTree(record.source()));
                    encoded.addProperty("kind", record.kind().name());
                    encoded.addProperty("navigable", !record.targets().isEmpty());
                    encoded.addProperty("title", record.title());
                    encoded.add("payload", JsonParser.parseString(Evidence.serialize(record.payload())));
                    encoded.add("recipe", JSON.toJsonTree(record.recipePayload()));
                    if (!record.source().sourceHandle().startsWith("status:")
                            && accepted.stream().filter(value -> !value.source().sourceHandle().startsWith("status:")).count() == 64 ||records.toString().length() + encoded.toString().length() > 131_072) {
                        json.addProperty("coverage", "PARTIAL");
                        json.addProperty("outcome", "PARTIAL");
                        json.addProperty("detail", "tool output limit reached; narrow the lookup");
                        break;
                    }
                    records.add(encoded);
                    accepted.add(record);
                }
                json.add("records", records);
                return new ToolResponse(new ModelBackend.ToolResult(call.callId(), json.toString(), result.outcome() == Evidence.Outcome.ERROR), accepted);
            });
        } catch (RuntimeException failure) {
            var error = new JsonObject();
            error.addProperty("coverage", "ERROR");
            error.addProperty("outcome", "ERROR");
            error.addProperty("detail", failure instanceof IllegalArgumentException ? failure.getMessage() : "invalid read tool arguments");
            return CompletableFuture.completedFuture(new ToolResponse(new ModelBackend.ToolResult(call.callId(), error.toString(), true), List.of()));
        }
    }
}
