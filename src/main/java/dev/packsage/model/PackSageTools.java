package dev.packsage.model;

import dev.packsage.knowledge.Evidence;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

public final class PackSageTools {
    private static final com.google.gson.Gson JSON = new com.google.gson.GsonBuilder().serializeNulls()
            .registerTypeAdapter(Instant.class, (com.google.gson.JsonSerializer<Instant>) (value, type, context) -> new com.google.gson.JsonPrimitive(value.toString()))
            .registerTypeHierarchyAdapter(Evidence.JsonValue.class, (com.google.gson.JsonSerializer<Evidence.JsonValue>) (value, type, context) -> JsonParser.parseString(Evidence.serialize(value)))
            .create();
    private final Function<ReadRequest, CompletionStage<Evidence.Result>> dispatch;

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
        this.dispatch = Objects.requireNonNull(dispatch);
    }

    public static List<ModelBackend.ToolDefinition> definitions() {
        var definitions = new ArrayList<ModelBackend.ToolDefinition>();
        for (var name : List.of("search_pack", "read_evidence", "plan_recipe", "read_inventory", "search_inventory", "read_storage", "read_progress",
                "list_configs", "search_config", "read_config", "search_history", "read_history")) {
            var properties = new JsonObject();
            var description = switch (name) {
                case "search_pack" -> "Search current client-visible pack evidence. Preserve partial coverage and source authority. "
                        + "Text is a literal substring: use one name, ID or short phrase, not concatenated keywords. "
                        + "Use provider to limit results to one source. outputItem is an exact registered item focus and requires provider=jei; use empty text for focused JEI displays. "
                        + "Native recipes describe loaded pack facts; JEI references describe observed viewer entries and are navigable only when navigable is true. "
                        + "Search a bare chapter ID to discover its quest references. A non-null nextCursor means more matches may exist; "
                        + "reuse it only with the same text, kinds, provider and outputItem, or set cursor to null when changing a filter. "
                        + "Detail explains errors and bounded partial results.";
                case "read_evidence" -> "Read only previously observed provider-qualified source handles in this request.";
                case "plan_recipe" -> "Retrieve bounded recipe routes, preserving alternatives, quantities and unknown semantics.";
                case "read_inventory" -> "Read occupied vanilla player inventory and equipment slots; optionally filter exact item IDs. Returns scoped slotReference values and selected mainhand/offhand locations, including empty selections. Slot indices are local to each section; main inventory starts at 0 after the hotbar. Nested storage contents require read_storage. Follow cursors with unchanged filters.";
                case "search_inventory" -> "Search occupied vanilla player inventory and equipment using one literal case-insensitive phrase. Select fields name, id, tag or tooltip; null fields searches name and ID. Results identify their slotReference, location, capture time and coverage. This runtime does not search storage networks or opened containers. Missing selected fields make coverage partial; no matches then do not prove absence. Follow cursors with unchanged text and fields.";
                case "read_storage" -> "Read bounded storage observations: opened is the last container captured with the PackSage shortcut, item inspects storage at a slotReference returned by read_inventory or search_inventory in this request, and targeted reports available target evidence. Hands are ordinary slot selections. Changed items or stale references require a fresh inventory read. Opened menu slots are partial visible evidence, not full mod storage/network totals. Preserve capture time and unknown contents; follow cursors with unchanged target, slotReference and filters.";
                case "read_progress" -> "Read previously observed provider-qualified quest references and current team progression, "
                        + "in batches of up to 16. Raw quest IDs listed in a chapter must first be discovered with search_pack.";
                case "list_configs" -> "List accessible observed configuration values and handles; saved and loaded views differ.";
                case "search_config" -> "Search bounded accessible config keys and comments using a literal substring. "
                        + "Use one key, file name or short phrase. Set cursor to null when changing the text.";
                case "read_config" -> "Read a key or bounded section from an observed config handle, either a returned reference "
                        + "config:<owner>:<SCOPE>:<file>#<key> or its entry handle <owner>:<SCOPE>:<file>. No paths, writes or reloads.";
                case "search_history" -> "Search completed saved question/answer text across sessions in the current world/server, beyond the automatic history window. "
                        + "Use one nonblank literal phrase or keyword; matching is case-insensitive. Results are historical excerpts, not current game facts. "
                        + "Reuse nextCursor only with the same text; partial coverage does not prove history is absent.";
                case "read_history" -> "Read selected saved exchanges using exact history references returned by search_history in this request. "
                        + "maxChars bounds total question/answer characters per exchange; truncated text is marked partial. Deleted or replaced exchanges may be stale. "
                        + "Historical answers are recollections, not authoritative current game evidence.";
                default -> throw new IllegalStateException(name);
            };
            if (name.equals("search_pack") || name.equals("search_config") || name.equals("search_history") || name.equals("search_inventory")) {
                properties.add("text", JsonParser.parseString("{\"type\":\"string\",\"maxLength\":" + (name.equals("search_inventory") ? 256 : 512) + "}"));
            }
            if (name.equals("search_pack")) {
                properties.add("kinds", JsonParser.parseString("{\"type\":[\"array\",\"null\"],\"maxItems\":7,\"items\":{\"type\":\"string\",\"enum\":[\"RECIPE\",\"QUEST\",\"CONFIG\",\"GUIDE\",\"ITEM\",\"BLOCK\",\"OTHER\"]}}"));
                properties.add("provider", JsonParser.parseString("{\"type\":[\"string\",\"null\"],\"maxLength\":32,\"enum\":[\"native\",\"jei\",\"ftb_quests\",\"config\",\"patchouli\",null]}"));
                properties.add("outputItem", JsonParser.parseString("{\"type\":[\"string\",\"null\"],\"maxLength\":256,\"pattern\":\"^[a-z0-9_.-]+:[a-z0-9/._-]+$\"}"));
            }
            if (name.equals("read_evidence") || name.equals("read_progress") || name.equals("read_history")) {
                properties.add("handles", JsonParser.parseString("{\"type\":\"array\",\"minItems\":1,\"maxItems\":"
                        + (name.equals("read_history") ? 8 : 16) + ",\"items\":{\"type\":\"string\",\"maxLength\":1024}}"));
            }
            if (name.equals("read_history")) {
                properties.add("maxChars", JsonParser.parseString("{\"type\":[\"integer\",\"null\"],\"minimum\":256,\"maximum\":16000}"));
            }
            if (name.equals("read_inventory")) {
                properties.add("identities", JsonParser.parseString("{\"type\":[\"array\",\"null\"],\"minItems\":1,\"maxItems\":32,\"items\":{\"type\":\"string\",\"maxLength\":256}}"));
            }
            if (name.equals("search_inventory")) {
                properties.add("fields", JsonParser.parseString("{\"type\":[\"array\",\"null\"],\"minItems\":1,\"maxItems\":4,\"items\":{\"type\":\"string\",\"enum\":[\"name\",\"id\",\"tag\",\"tooltip\"]}}"));
            }
            if (name.equals("read_storage")) {
                properties.add("target", JsonParser.parseString("{\"type\":\"string\",\"maxLength\":32,\"enum\":[\"opened\",\"targeted\",\"item\"]}"));
                properties.add("slotReference", JsonParser.parseString("{\"type\":[\"string\",\"null\"],\"maxLength\":1024}"));
                properties.add("text", JsonParser.parseString("{\"type\":[\"string\",\"null\"],\"maxLength\":256}"));
                properties.add("identities", JsonParser.parseString("{\"type\":[\"array\",\"null\"],\"minItems\":1,\"maxItems\":32,\"items\":{\"type\":\"string\",\"maxLength\":256}}"));
            }
            if (name.equals("plan_recipe")) {
                properties.add("identity", JsonParser.parseString("{\"type\":\"string\",\"maxLength\":256}"));
                properties.add("quantity", JsonParser.parseString("{\"type\":\"integer\",\"minimum\":1,\"maximum\":1000000}"));
                properties.add("depth", JsonParser.parseString("{\"type\":[\"integer\",\"null\"],\"minimum\":0,\"maximum\":8}"));
            }
            if (name.equals("read_config")) {
                properties.add("handle", JsonParser.parseString("{\"type\":\"string\",\"maxLength\":1024}"));
                properties.add("key", JsonParser.parseString("{\"type\":[\"string\",\"null\"],\"maxLength\":512}"));
                properties.add("section", JsonParser.parseString("{\"type\":[\"string\",\"null\"],\"maxLength\":512}"));
            }
            if (!name.equals("plan_recipe") && !name.equals("read_evidence")
                    && !name.equals("read_progress") && !name.equals("read_history")) {
                properties.add("limit", JsonParser.parseString("{\"type\":[\"integer\",\"null\"],\"minimum\":1,\"maximum\":"
                        + (name.equals("search_history") ? 16 : name.equals("read_storage") || name.equals("read_inventory") || name.equals("search_inventory") ? 32 : 64) + "}"));
                if (!name.equals("read_config")) properties.add("cursor", JsonParser.parseString("{\"type\":[\"string\",\"null\"],\"maxLength\":1024}"));
            }
            var schema = new JsonObject();
            schema.addProperty("type", "object");
            schema.addProperty("additionalProperties", false);
            schema.add("properties", properties);
            var required = new JsonArray();
            properties.keySet().forEach(required::add);
            schema.add("required", required);
            definitions.add(new ModelBackend.ToolDefinition(name, description, schema.toString()));
        }
        return List.copyOf(definitions);
    }

    public CompletionStage<ToolResponse> execute(ModelBackend.ToolCall call, Evidence.Scope scope) {
        try {
            if (call.serializedArguments().length() > 16_384) throw new IllegalArgumentException("tool arguments exceed limit");
            var schema = definitions().stream().filter(definition -> definition.name().equals(call.name())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown read tool"));
            var arguments = Evidence.deserializeObject(call.serializedArguments());
            var properties = JsonParser.parseString(schema.serializedSchema()).getAsJsonObject().getAsJsonObject("properties");
            for (var entry : arguments.values().entrySet()) {
                if (!properties.has(entry.getKey())) throw new IllegalArgumentException("unknown argument: " + entry.getKey());
                var rule = properties.getAsJsonObject(entry.getKey());
                var value = entry.getValue();
                if (value instanceof Evidence.JsonNull) {
                    if (!rule.get("type").isJsonArray()) throw new IllegalArgumentException("argument cannot be null: " + entry.getKey());
                } else if (rule.has("items")) {
                    if (!(value instanceof Evidence.JsonArray array) || rule.has("minItems") && array.values().size() < rule.get("minItems").getAsInt()
                            || array.values().size() > rule.get("maxItems").getAsInt()) throw new IllegalArgumentException("invalid selection: " + entry.getKey());
                    for (var selected : ((Evidence.JsonArray) value).values()) {
                        if (!(selected instanceof Evidence.JsonString string) || string.value().isBlank()
                                || rule.getAsJsonObject("items").has("maxLength") && string.value().length() > rule.getAsJsonObject("items").get("maxLength").getAsInt()) {
                            throw new IllegalArgumentException("invalid selected identity");
                        }
                        if (entry.getKey().equals("kinds")) Evidence.RecordKind.valueOf(((Evidence.JsonString) selected).value());
                        if (entry.getKey().equals("fields") && !List.of("name", "id", "tag", "tooltip").contains(((Evidence.JsonString) selected).value())) {
                            throw new IllegalArgumentException("invalid inventory search field");
                        }
                        if (entry.getKey().equals("identities") && !((Evidence.JsonString) selected).value().matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
                            throw new IllegalArgumentException("identity must be a valid item resource ID");
                        }
                    }
                } else if (rule.has("minimum")) {
                    if (!(value instanceof Evidence.JsonNumber)) throw new IllegalArgumentException("integer argument required");
                    long number = ((Evidence.JsonNumber) value).value().longValueExact();
                    if (number < rule.get("minimum").getAsLong() || number > rule.get("maximum").getAsLong()) throw new IllegalArgumentException("argument outside bounds: " + entry.getKey());
                } else if (!(value instanceof Evidence.JsonString string) || string.value().length() > rule.get("maxLength").getAsInt()) {
                    throw new IllegalArgumentException("invalid text argument: " + entry.getKey());
                } else if (entry.getKey().equals("provider") && !List.of("native", "jei", "ftb_quests", "config", "patchouli").contains(string.value())) {
                    throw new IllegalArgumentException("invalid provider");
                } else if (entry.getKey().equals("target") && !List.of("opened", "targeted", "item").contains(string.value())) {
                    throw new IllegalArgumentException("invalid storage target");
                } else if (entry.getKey().equals("outputItem") && !string.value().matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
                    throw new IllegalArgumentException("outputItem must be a valid item resource ID");
                }
            }
            for (var required : switch (call.name()) {
                case "search_pack", "search_config", "search_history", "search_inventory" -> List.of("text");
                case "read_evidence", "read_progress", "read_history" -> List.of("handles");
                case "read_storage" -> List.of("target");
                case "plan_recipe" -> List.of("identity", "quantity");
                case "read_config" -> List.of("handle");
                default -> List.<String>of();
            }) {
                if (!arguments.values().containsKey(required) || arguments.values().get(required) instanceof Evidence.JsonNull) throw new IllegalArgumentException("missing argument: " + required);
            }
            if ((call.name().equals("search_history") || call.name().equals("search_inventory"))
                    && ((Evidence.JsonString) arguments.values().get("text")).value().isBlank()) {
                throw new IllegalArgumentException("search text must not be blank");
            }
            if (call.name().equals("read_storage")) {
                var item = ((Evidence.JsonString) arguments.values().get("target")).value().equals("item");
                var reference = arguments.values().get("slotReference");
                if (item && (!(reference instanceof Evidence.JsonString value) || value.value().isBlank())) {
                    throw new IllegalArgumentException("item storage requires an observed slotReference");
                }
                if (!item && reference instanceof Evidence.JsonString) throw new IllegalArgumentException("slotReference requires target=item");
            }
            if (arguments.values().get("outputItem") instanceof Evidence.JsonString
                    && (!(arguments.values().get("provider") instanceof Evidence.JsonString provider) || !provider.value().equals("jei"))) {
                throw new IllegalArgumentException("outputItem requires provider=jei");
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
