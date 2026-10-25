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
    private static final String WORLD_ANCHOR = "{\"type\":[\"object\",\"null\"],\"properties\":{\"type\":{\"type\":\"string\",\"enum\":[\"player\",\"entity\",\"block\"]},\"id\":{\"type\":[\"string\",\"null\"],\"maxLength\":36},\"place\":{\"type\":[\"string\",\"null\"],\"maxLength\":300}},\"required\":[\"type\"],\"additionalProperties\":false}";
    private static final String WORLD_RADIUS = "{\"type\":[\"integer\",\"null\"],\"minimum\":1,\"maximum\":256}";
    private static final String WORLD_FILTER = "{\"type\":\"object\",\"properties\":{\"ids\":{\"type\":[\"array\",\"null\"],\"maxItems\":64,\"items\":{\"type\":\"string\",\"maxLength\":256,\"pattern\":\"^[a-z0-9_.-]+:[a-z0-9/._-]+$\"}},\"tags\":{\"type\":[\"array\",\"null\"],\"maxItems\":64,\"items\":{\"type\":\"string\",\"maxLength\":256,\"pattern\":\"^[a-z0-9_.-]+:[a-z0-9/._-]+$\"}},\"text\":{\"type\":[\"string\",\"null\"],\"maxLength\":256}},\"additionalProperties\":false}";
    private static final String WORLD_ITEM_FILTER = "{\"type\":\"object\",\"properties\":{\"ids\":{\"type\":[\"array\",\"null\"],\"maxItems\":64,\"items\":{\"type\":\"string\",\"maxLength\":256,\"pattern\":\"^[a-z0-9_.-]+:[a-z0-9/._-]+$\"}},\"tags\":{\"type\":[\"array\",\"null\"],\"maxItems\":64,\"items\":{\"type\":\"string\",\"maxLength\":256,\"pattern\":\"^[a-z0-9_.-]+:[a-z0-9/._-]+$\"}},\"text\":{\"type\":[\"string\",\"null\"],\"maxLength\":256},\"tooltip\":{\"type\":[\"array\",\"null\"],\"maxItems\":32,\"items\":{\"type\":\"string\",\"maxLength\":256}}},\"additionalProperties\":false}";
    private static final String WORLD_CURSOR = "{\"type\":[\"string\",\"null\"],\"maxLength\":1024}";
    private static final List<String> NATIVE_REGISTRIES = List.of("item", "block", "item_tag", "enchantment", "mob_effect", "potion", "active_effect", "player_attribute", "dimension", "dimension_type");
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
        return List.of(new Toolbox.Metadata("native", List.of(), true, NATIVE_REGISTRIES, NATIVE_INVENTORY_TARGETS));
    }

    public static List<ModelBackend.ToolDefinition> definitions(List<Toolbox.Metadata> metadata) {
        var targetChoices = new JsonArray();
        metadata.forEach(adapter -> adapter.inventoryTargets().values().forEach(schema -> targetChoices.add(JsonParser.parseString(schema))));
        var targetSchema = new JsonObject();
        targetSchema.add("oneOf", targetChoices);
        var definitions = new ArrayList<ModelBackend.ToolDefinition>();
        for (var name : List.of("search_recipe", "search_registry", "search_inventory", "read_evidence",
                "plan_recipe", "list_configs", "search_config", "read_config", "search_history", "read_history",
                "inspect_area", "list_blocks", "list_entities", "inspect_block", "inspect_entity", "list_items", "inspect_item", "find_items")) {
            var properties = new JsonObject();
            var required = new JsonArray();
            switch (name) {
                case "search_recipe" -> properties.add("filter", JsonParser.parseString("{\"type\":[\"object\",\"null\"],\"properties\":{\"text\":{\"type\":[\"string\",\"null\"],\"maxLength\":512},\"outputItem\":{\"type\":[\"string\",\"null\"],\"maxLength\":256,\"pattern\":\"^[a-z0-9_.-]+:[a-z0-9/._-]+$\"}},\"additionalProperties\":false}"));
                case "search_registry" -> {
                    var registry = JsonParser.parseString("{\"type\":\"string\",\"maxLength\":64}").getAsJsonObject();
                    registry.add("enum", JSON.toJsonTree(metadata.stream().flatMap(adapter -> adapter.registries().stream()).distinct().toList()));
                    properties.add("registry", registry);
                    required.add("registry");
                    properties.add("filter", JsonParser.parseString("{\"type\":[\"object\",\"null\"],\"properties\":{\"text\":{\"type\":[\"string\",\"null\"],\"maxLength\":512},\"ids\":{\"type\":[\"array\",\"null\"],\"maxItems\":64,\"items\":{\"type\":\"string\",\"maxLength\":256,\"pattern\":\"^[a-z0-9_.-]+:[a-z0-9/._-]+$\"}},\"tags\":{\"type\":[\"array\",\"null\"],\"maxItems\":64,\"items\":{\"type\":\"string\",\"maxLength\":256,\"pattern\":\"^[a-z0-9_.-]+:[a-z0-9/._-]+$\"}}},\"additionalProperties\":false}"));
                }
                case "search_inventory" -> {
                    properties.add("target", targetSchema);
                    required.add("target");
                    properties.add("filter", JsonParser.parseString("{\"type\":[\"object\",\"null\"],\"properties\":{\"names\":{\"type\":[\"array\",\"null\"],\"maxItems\":32,\"items\":{\"type\":\"string\",\"maxLength\":256}},\"ids\":{\"type\":[\"array\",\"null\"],\"maxItems\":32,\"items\":{\"type\":\"string\",\"maxLength\":256,\"pattern\":\"^[a-z0-9_.-]+:[a-z0-9/._-]+$\"}},\"tags\":{\"type\":[\"array\",\"null\"],\"maxItems\":32,\"items\":{\"type\":\"string\",\"maxLength\":256,\"pattern\":\"^[a-z0-9_.-]+:[a-z0-9/._-]+$\"}},\"tooltip\":{\"type\":[\"array\",\"null\"],\"maxItems\":32,\"items\":{\"type\":\"string\",\"maxLength\":512}},\"sections\":{\"type\":[\"array\",\"null\"],\"maxItems\":32,\"items\":{\"type\":\"string\",\"maxLength\":64}}},\"additionalProperties\":false}"));
                }
                case "read_evidence" -> { properties.add("handles", JsonParser.parseString("{\"type\":\"array\",\"minItems\":1,\"maxItems\":16,\"items\":{\"type\":\"string\",\"maxLength\":1024}}")); required.add("handles"); }
                case "read_history" -> {
                    properties.add("handles", JsonParser.parseString("{\"type\":\"array\",\"minItems\":1,\"maxItems\":8,\"items\":{\"type\":\"string\",\"maxLength\":1024}}"));
                    properties.add("maxChars", JsonParser.parseString("{\"type\":[\"integer\",\"null\"],\"minimum\":256,\"maximum\":16000}"));
                    required.add("handles");
                }
                case "plan_recipe" -> {
                    properties.add("identity", JsonParser.parseString("{\"type\":\"string\",\"maxLength\":256,\"pattern\":\"^[a-z0-9_.-]+:[a-z0-9/._-]+$\"}"));
                    properties.add("quantity", JsonParser.parseString("{\"type\":\"integer\",\"minimum\":1,\"maximum\":1000000}"));
                    properties.add("depth", JsonParser.parseString("{\"type\":[\"integer\",\"null\"],\"minimum\":0,\"maximum\":8}"));
                    required.add("identity");
                    required.add("quantity");
                }
                case "search_config", "search_history" -> { properties.add("text", JsonParser.parseString("{\"type\":\"string\",\"maxLength\":512}")); required.add("text"); }
                case "read_config" -> {
                    properties.add("handle", JsonParser.parseString("{\"type\":\"string\",\"maxLength\":1024}"));
                    properties.add("key", JsonParser.parseString("{\"type\":[\"string\",\"null\"],\"maxLength\":512}"));
                    properties.add("section", JsonParser.parseString("{\"type\":[\"string\",\"null\"],\"maxLength\":512}"));
                    properties.add("limit", JsonParser.parseString("{\"type\":[\"integer\",\"null\"],\"minimum\":1,\"maximum\":64}"));
                    required.add("handle");
                }
                case "list_configs" -> { }
                case "inspect_area" -> {
                    properties.add("center", JsonParser.parseString(WORLD_ANCHOR));
                    properties.add("radius", JsonParser.parseString(WORLD_RADIUS));
                }
                case "list_blocks", "list_entities" -> {
                    properties.add("center", JsonParser.parseString(WORLD_ANCHOR));
                    properties.add("radius", JsonParser.parseString(WORLD_RADIUS));
                    properties.add("filter", JsonParser.parseString(WORLD_FILTER));
                    properties.add("cursor", JsonParser.parseString(WORLD_CURSOR));
                    required.add("filter");
                }
                case "inspect_block" -> { properties.add("place", JsonParser.parseString("{\"type\":\"string\",\"maxLength\":300}")); required.add("place"); }
                case "inspect_entity" -> { properties.add("id", JsonParser.parseString("{\"type\":\"string\",\"maxLength\":36,\"pattern\":\"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$\"}")); required.add("id"); }
                case "list_items" -> {
                    properties.add("holder", JsonParser.parseString("{\"type\":\"string\",\"maxLength\":2048}"));
                    properties.add("inventory", JsonParser.parseString("{\"type\":[\"string\",\"null\"],\"maxLength\":256}"));
                    var filter = JsonParser.parseString(WORLD_ITEM_FILTER).getAsJsonObject();
                    filter.add("type", JsonParser.parseString("[\"object\",\"null\"]"));
                    properties.add("filter", filter);
                    properties.add("cursor", JsonParser.parseString(WORLD_CURSOR));
                    required.add("holder");
                }
                case "inspect_item" -> { properties.add("path", JsonParser.parseString("{\"type\":\"string\",\"maxLength\":2048}")); required.add("path"); }
                case "find_items" -> {
                    properties.add("filter", JsonParser.parseString(WORLD_ITEM_FILTER));
                    properties.add("center", JsonParser.parseString(WORLD_ANCHOR));
                    properties.add("radius", JsonParser.parseString(WORLD_RADIUS));
                    properties.add("includeNested", JsonParser.parseString("{\"type\":[\"boolean\",\"null\"]}"));
                    properties.add("cursor", JsonParser.parseString(WORLD_CURSOR));
                    required.add("filter");
                }
                default -> throw new IllegalStateException(name);
            }
            if (List.of("search_recipe", "search_registry").contains(name)) {
                var provider = JsonParser.parseString("{\"type\":[\"string\",\"null\"],\"maxLength\":32}").getAsJsonObject();
                var choices = new JsonArray();
                metadata.stream().filter(adapter -> name.equals("search_recipe") ? adapter.recipes() : !adapter.registries().isEmpty())
                        .map(Toolbox.Metadata::id).distinct().forEach(choices::add);
                choices.add(com.google.gson.JsonNull.INSTANCE);
                provider.add("enum", choices);
                properties.getAsJsonObject("filter").getAsJsonObject("properties").add("provider", provider);
            }
            if (List.of("search_recipe", "search_registry", "search_inventory", "list_configs", "search_config", "search_history").contains(name)) {
                var limit = JsonParser.parseString("{\"type\":[\"integer\",\"null\"],\"minimum\":1,\"maximum\":64}").getAsJsonObject();
                limit.addProperty("maximum", name.equals("search_inventory") ? 32 : name.equals("search_history") ? 16 : 64);
                properties.add("limit", limit);
                properties.add("cursor", JsonParser.parseString("{\"type\":[\"string\",\"null\"],\"maxLength\":1024}"));
            }
            var schema = new JsonObject();
            schema.addProperty("type", "object");
            schema.addProperty("additionalProperties", false);
            schema.add("properties", properties);
            schema.add("required", required);
            definitions.add(new ModelBackend.ToolDefinition(name, switch (name) {
                case "search_recipe" -> "Find Minecraft's loaded recipes, including supported brewing transformations. Text is a case-insensitive literal match. For an exact output, use outputItem without text unless text is an additional intended filter; variants can differ in components. requiredInputTotals, when present, totals only unambiguous returned inputs and inherits record coverage. An advancing nextCall can scan other recipes before brewing and return empty pages.";
                case "search_registry" -> "Find identities in one registry catalog. active_effect lists the player's current effect instances (durationTicks, amplifier, ambient, attributeModifiers), with description when the mod provides one; descriptionStatus reports not_provided, unknown, or bounded for a truncated description. player_attribute lists current attribute base and value with modifiers whose IDs name their sources, such as minecraft:effect.luck or an item's modifier ID. Minecraft does not record who applied an effect.";
                case "search_inventory" -> "Read current contents of one target. Target item (mainhand, offhand or a slotReference) returns the selected item's own record first, in section item with components, potion contents and enchantmentEligibility, before any contents it holds. Target opened reads the open menu named by openedMenu when it is a supported vanilla menu. Player summaries do not include nested containers; inspect each relevant carried container, including multiple matching items, with its returned slotReference. Player reads cover vanilla inventory and equipment slots.";
                case "search_config" -> "Find accessible config keys and comments, including machine speed, energy and upgrade settings. Use returned config references with read_config; preserve scope and effective-value authority.";
                case "read_config" -> "Read a discovered config key or section at call time. A file value is not necessarily the effective server setting; preserve returned authority and scope.";
                case "plan_recipe" -> "Plan recipe routes for an item identity and quantity, including brewing routes, with unresolved dependencies and coverage. It does not read owned materials or establish affordability. Routes cover every component variant of the identity; match output components. The live recipe scan is bounded, so a missing route is not absence. Brewing routes withhold operation counts (batches null); state only what their operationSemantics supports.";
                case "read_evidence" -> "Read current evidence for returned record references only. Do not pass search cursors, labels or guessed references. An inventory slot record's reference rereads that item's current record, including enchantmentEligibility.";
                case "inspect_area" -> "Summarize the loaded world around center (default the player, radius 16): block counts, notable blocks such as containers, workstations, beds and spawners grouped by role and block, and nearby entities. Then list_blocks or list_entities with a filter for linkable places, and inspect_block or inspect_entity what matters.";
                case "list_blocks" -> "List blocks matching filter (ids, tags or name text) within radius of center, 64 per page, each with its place and a highlight link. Inspect what matters with inspect_block.";
                case "list_entities" -> "List entities such as mobs, villagers, item frames and minecarts matching filter within radius of center, 64 per page, each with its id, position and a highlight link. Inspect what matters with inspect_entity.";
                case "inspect_block" -> "Read one block by place (dimension|x,y,z): its state, facts from every adapter and the inventories it has. List an inventory's items with list_items, passing the returned holder.";
                case "inspect_entity" -> "Read one loaded entity by id: facts from every adapter, such as health, villager profession, bed and job site, and the inventories it has. List its items with list_items, passing the returned holder.";
                case "list_items" -> "List the items in one holder (player, a returned holder, an item path whose item holdsItems, or a word: held, offhand, targeted for the block or entity under the crosshair, ridden, optionally followed by >steps) grouped by item with counts, variants, up to 8 location paths and slot facts (selected hotbar slot, equipmentSlot); the first record summarizes the holder's inventories with emptySlots and the player's selectedSlot; a word that cannot resolve, such as held with an empty hand, returns no records and says why; inventory limits it to one section; filter.tooltip matches the tooltip lines shown to the player, for example [\"Luck\"]. Inspect a location with inspect_item, or list_items it again to step inside.";
                case "inspect_item" -> "Read one item by its location path or by held or offhand (optionally followed by >steps, as can targeted and ridden): its tooltip lines as the player sees them (tooltipStatus bounded or unknown when cut or unreadable), enchantments, durability, full potion contents, custom name, attribute modifiers, enchantment eligibility (always present, with a none note when nothing applies), raw components, a component fingerprint that tells same-id variants apart, and the inventories it has.";
                case "find_items" -> "Search every inventory in range at once: the player's own, containers and entities, including items nested in shulker boxes and bundles, grouped by item with location paths; filter.tooltip matches the tooltip lines shown to the player, for example [\"Luck\"]. Prefer it over opening containers one by one; then inspect_item a returned path. Each container or entity holding a match comes back as its own record with a highlight link.";
                default -> "Read current evidence for " + name + ". Preserve source authority, partial coverage and observed references.";
            }, schema.toString()));
        }
        return List.copyOf(definitions);
    }

    private static void validateValue(com.google.gson.JsonElement json, JsonObject rule, String name) {
        if (rule.has("oneOf")) {
            for (var choice : rule.getAsJsonArray("oneOf")) {
                try { validateValue(json, choice.getAsJsonObject(), name); return; }
                catch (IllegalArgumentException ignored) { }
            }
            throw new IllegalArgumentException("invalid selection: " + name);
        }
        if (json.isJsonNull()) {
            if (!(rule.has("type") && rule.get("type").isJsonArray() && rule.getAsJsonArray("type").asList().stream().anyMatch(type -> type.getAsString().equals("null")))) {
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
                if (failure != null || result == null) {
                    while (failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null) failure = failure.getCause();
                    var error = new JsonObject();
                    error.addProperty("coverage", "ERROR");
                    error.addProperty("outcome", "ERROR");
                    error.addProperty("detail", failure == null ? "evidence extraction returned no result" : failure.getMessage());
                    if (failure instanceof Toolbox.Failure typed) {
                        error.addProperty("code", typed.code().name());
                        if (typed.code() == Toolbox.Code.INVALID_CURSOR && properties.has("cursor")) {
                            var restart = new JsonObject();
                            restart.addProperty("name", call.name());
                            var restartArguments = JsonParser.parseString(call.serializedArguments()).getAsJsonObject();
                            restartArguments.add("cursor", com.google.gson.JsonNull.INSTANCE);
                            restart.add("arguments", restartArguments);
                            error.add("restartCall", restart);
                        }
                    }
                    error.add("records", new JsonArray());
                    return new ToolResponse(new ModelBackend.ToolResult(call.callId(), error.toString(), true), List.of());
                }
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
                if (result.nextCursor() != null && properties.has("cursor")) {
                    var next = new JsonObject();
                    next.addProperty("name", call.name());
                    var nextArguments = JsonParser.parseString(call.serializedArguments()).getAsJsonObject();
                    nextArguments.addProperty("cursor", result.nextCursor());
                    next.add("arguments", nextArguments);
                    json.add("nextCall", next);
                }
                if (result.detail() != null) json.addProperty("detail", result.detail());
                var records = new JsonArray();
                json.add("records", records);
                var accepted = new ArrayList<Evidence.Record>();
                int outputBytes = json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                for (var record : result.records()) {
                    var encoded = new JsonObject();
                    encoded.addProperty("reference", record.source().providerId() + ":" + record.source().sourceHandle());
                    encoded.add("source", JSON.toJsonTree(record.source()));
                    encoded.addProperty("kind", record.kind().name());
                    encoded.addProperty("navigable", !record.targets().isEmpty());
                    encoded.addProperty("title", record.title());
                    encoded.add("payload", JsonParser.parseString(Evidence.serialize(record.payload())));
                    var recipe = JSON.toJsonTree(record.recipePayload());
                    if (record.recipePayload() != null && !record.recipePayload().inputs().isEmpty()) {
                        record RecipeTotalKey(String type, String comparisonIdentity, Evidence.JsonObject components) { }
                        var totals = new java.util.LinkedHashMap<RecipeTotalKey, Long>();
                        boolean canTotal = true;
                        for (var slot : record.recipePayload().inputs()) {
                            if (slot.alternatives().size() != 1) { canTotal = false; break; }
                            var input = slot.alternatives().getFirst();
                            if (!List.of("item", "item_stack").contains(input.type()) || input.quantity() == null
                                    || input.quantity() < 1 || input.consumption() != Evidence.Consumption.CONSUMED) {
                                canTotal = false;
                                break;
                            }
                            var key = new RecipeTotalKey(input.type(), input.comparisonIdentity(), input.components());
                            var previous = totals.getOrDefault(key, 0L);
                            if (Long.MAX_VALUE - previous < input.quantity()) { canTotal = false; break; }
                            totals.put(key, previous + input.quantity());
                        }
                        if (canTotal) {
                            var requiredInputTotals = new JsonArray();
                            totals.forEach((key, quantity) -> {
                                var total = new JsonObject();
                                total.addProperty("type", key.type());
                                total.addProperty("comparisonIdentity", key.comparisonIdentity());
                                total.add("components", JSON.toJsonTree(key.components()));
                                total.addProperty("quantity", quantity);
                                requiredInputTotals.add(total);
                            });
                            recipe.getAsJsonObject().add("requiredInputTotals", requiredInputTotals);
                        }
                    }
                    encoded.add("recipe", recipe);
                    int recordBytes = encoded.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length + (records.isEmpty() ? 0 : 1);
                    if (!record.source().sourceHandle().startsWith("status:")
                            && accepted.stream().filter(value -> !value.source().sourceHandle().startsWith("status:")).count() == 64
                            || outputBytes + recordBytes > 131_072 - 128) {
                        json.addProperty("coverage", "PARTIAL");
                        json.addProperty("outcome", "PARTIAL");
                        json.addProperty("detail", "tool output limit reached; narrow the lookup");
                        json.addProperty("nextCursor", (String) null);
                        json.remove("nextCall");
                        break;
                    }
                    records.add(encoded);
                    accepted.add(record);
                    outputBytes += recordBytes;
                }
                if (json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 131_072) {
                    json.addProperty("coverage", "PARTIAL");
                    json.addProperty("outcome", "PARTIAL");
                    json.addProperty("nextCursor", (String) null);
                    json.remove("nextCall");
                    json.addProperty("detail", "tool output limit reached; narrow the lookup");
                    json.add("records", new JsonArray());
                    accepted.clear();
                }
                return new ToolResponse(new ModelBackend.ToolResult(call.callId(), json.toString(), result.outcome() == Evidence.Outcome.ERROR), accepted);
            });
        } catch (RuntimeException failure) {
            var error = new JsonObject();
            error.addProperty("coverage", "ERROR");
            error.addProperty("outcome", "ERROR");
            error.addProperty("detail", failure instanceof IllegalArgumentException || failure instanceof Toolbox.Failure ? failure.getMessage() : "invalid read tool arguments");
            if (failure instanceof Toolbox.Failure typed) error.addProperty("code", typed.code().name());
            return CompletableFuture.completedFuture(new ToolResponse(new ModelBackend.ToolResult(call.callId(), error.toString(), true), List.of()));
        }
    }
}
