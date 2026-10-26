package dev.packsage.model;

import dev.packsage.journal.EventType;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;

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
    public static final int MAX_RECORD_BYTES = 64 * 1024 - 1;
    private static final String WORLD_ANCHOR = "{\"type\":[\"object\",\"null\"],\"properties\":{\"type\":{\"type\":\"string\",\"enum\":[\"player\",\"entity\",\"block\",\"note\"]},\"id\":{\"type\":[\"string\",\"null\"],\"maxLength\":36},\"place\":{\"type\":[\"string\",\"null\"],\"maxLength\":300}},\"required\":[\"type\"],\"additionalProperties\":false}";
    private static final String WORLD_RADIUS = "{\"type\":[\"integer\",\"null\"],\"minimum\":1,\"maximum\":256}";
    private static final String WORLD_FILTER = "{\"type\":\"object\",\"properties\":{\"ids\":{\"type\":[\"array\",\"null\"],\"maxItems\":64,\"items\":{\"type\":\"string\",\"maxLength\":256,\"pattern\":\"^[a-z0-9_.-]+:[a-z0-9/._-]+$\"}},\"tags\":{\"type\":[\"array\",\"null\"],\"maxItems\":64,\"items\":{\"type\":\"string\",\"maxLength\":256,\"pattern\":\"^[a-z0-9_.-]+:[a-z0-9/._-]+$\"}},\"text\":{\"type\":[\"string\",\"null\"],\"maxLength\":256}},\"additionalProperties\":false}";
    private static final String WORLD_ITEM_FILTER = "{\"type\":\"object\",\"properties\":{\"ids\":{\"type\":[\"array\",\"null\"],\"maxItems\":64,\"items\":{\"type\":\"string\",\"maxLength\":256,\"pattern\":\"^[a-z0-9_.-]+:[a-z0-9/._-]+$\"}},\"tags\":{\"type\":[\"array\",\"null\"],\"maxItems\":64,\"items\":{\"type\":\"string\",\"maxLength\":256,\"pattern\":\"^[a-z0-9_.-]+:[a-z0-9/._-]+$\"}},\"text\":{\"type\":[\"string\",\"null\"],\"maxLength\":256},\"tooltip\":{\"type\":[\"array\",\"null\"],\"maxItems\":32,\"items\":{\"type\":\"string\",\"maxLength\":256}}},\"additionalProperties\":false}";
    private static final String WORLD_ID = "{\"type\":[\"string\",\"null\"],\"maxLength\":256,\"pattern\":\"^[a-z0-9_.-]+:[a-z0-9/._-]+$\"}";
    private static final String WORLD_ID_OR_TAG = "{\"type\":[\"string\",\"null\"],\"maxLength\":257,\"pattern\":\"^#?[a-z0-9_.-]+:[a-z0-9/._-]+$\"}";
    private static final String WORLD_CURSOR = "{\"type\":[\"string\",\"null\"],\"maxLength\":1024}";
    private static final List<String> NATIVE_REGISTRIES = List.of("item", "block", "item_tag", "enchantment", "mob_effect", "potion", "active_effect", "player_attribute", "dimension", "dimension_type");
    private static final com.google.gson.Gson JSON = new com.google.gson.GsonBuilder().serializeNulls()
            .registerTypeAdapter(Instant.class, (com.google.gson.JsonSerializer<Instant>) (value, type, context) -> new com.google.gson.JsonPrimitive(value.toString()))
            .registerTypeHierarchyAdapter(Evidence.JsonValue.class, (com.google.gson.JsonSerializer<Evidence.JsonValue>) (value, type, context) -> JsonParser.parseString(Evidence.serialize(value)))
            .create();
    private final Function<ReadRequest, CompletionStage<Evidence.Result>> dispatch;
    private final List<Toolbox.Metadata> metadata;
    private final OutputPages outputPages;

    /** Shared by short-lived tool facades in one knowledge service. Cursors retain undelivered records. */
    public static final class OutputPages {
        private record Pending(ReadRequest request, Evidence.Result result, List<JsonObject> encoded, int bytes) { }
        private final java.util.Map<String, Pending> pending = new java.util.LinkedHashMap<>();
        private int retainedBytes;
        private final java.util.function.BiConsumer<Evidence.Scope, List<Evidence.Record>> delivered;

        public OutputPages() {
            this((scope, records) -> { });
        }

        public OutputPages(java.util.function.BiConsumer<Evidence.Scope, List<Evidence.Record>> delivered) {
            this.delivered = Objects.requireNonNull(delivered);
        }
    }

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
        this(dispatch, metadata, new OutputPages());
    }

    public PackSageTools(Function<ReadRequest, CompletionStage<Evidence.Result>> dispatch, List<Toolbox.Metadata> metadata,
                         OutputPages outputPages) {
        this.dispatch = Objects.requireNonNull(dispatch);
        this.metadata = List.copyOf(metadata);
        this.outputPages = Objects.requireNonNull(outputPages);
    }

    public static List<ModelBackend.ToolDefinition> definitions() {
        return definitions(nativeMetadata());
    }

    private static List<Toolbox.Metadata> nativeMetadata() {
        return List.of(new Toolbox.Metadata("native", List.of(), true, NATIVE_REGISTRIES));
    }

    public static List<ModelBackend.ToolDefinition> definitions(List<Toolbox.Metadata> metadata) {
        var definitions = new ArrayList<ModelBackend.ToolDefinition>();
        for (var name : List.of("search_recipe", "search_registry", "read_evidence",
                "plan_recipe", "list_configs", "search_config", "read_config", "search_history", "read_history",
                "inspect_area", "list_blocks", "list_entities", "inspect_block", "inspect_entity", "list_items", "inspect_item", "find_items",
                "list_progress", "inspect_progress", "list_statistics",
                "read_loot", "locate", "search_commands", "read_command",
                "search_journal", "remember", "update_note", "forget_note")) {
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
                case "list_progress" -> {
                    properties.add("tree", JsonParser.parseString("{\"type\":[\"string\",\"null\"],\"maxLength\":600}"));
                    properties.add("status", JsonParser.parseString("{\"type\":[\"array\",\"null\"],\"maxItems\":5,\"items\":{\"type\":\"string\",\"enum\":[\"done\",\"in_progress\",\"available\",\"locked\",\"hidden\"]}}"));
                    properties.add("text", JsonParser.parseString("{\"type\":[\"string\",\"null\"],\"maxLength\":256}"));
                    properties.add("cursor", JsonParser.parseString(WORLD_CURSOR));
                }
                case "inspect_progress" -> { properties.add("id", JsonParser.parseString("{\"type\":\"string\",\"maxLength\":600}")); required.add("id"); }
                case "list_statistics" -> {
                    properties.add("categories", JsonParser.parseString("{\"type\":[\"array\",\"null\"],\"maxItems\":16,\"items\":{\"type\":\"string\",\"maxLength\":128,\"pattern\":\"^[a-z0-9_.:-]+$\"}}"));
                    var subjects = JsonParser.parseString(WORLD_FILTER).getAsJsonObject();
                    subjects.add("type", JsonParser.parseString("[\"object\",\"null\"]"));
                    properties.add("subjects", subjects);
                    properties.add("cursor", JsonParser.parseString(WORLD_CURSOR));
                }
                case "read_loot" -> {
                    properties.add("table", JsonParser.parseString(WORLD_ID));
                    properties.add("block", JsonParser.parseString(WORLD_ID));
                    properties.add("entity", JsonParser.parseString("{\"type\":[\"string\",\"null\"],\"maxLength\":256}"));
                    properties.add("place", JsonParser.parseString("{\"type\":[\"string\",\"null\"],\"maxLength\":300}"));
                    properties.add("cursor", JsonParser.parseString(WORLD_CURSOR));
                }
                case "locate" -> {
                    properties.add("biome", JsonParser.parseString(WORLD_ID_OR_TAG));
                    properties.add("structure", JsonParser.parseString(WORLD_ID_OR_TAG));
                    properties.add("center", JsonParser.parseString(WORLD_ANCHOR));
                    properties.add("radius", JsonParser.parseString("{\"type\":[\"integer\",\"null\"],\"minimum\":1,\"maximum\":512}"));
                    properties.add("cursor", JsonParser.parseString(WORLD_CURSOR));
                }
                case "search_commands" -> {
                    properties.add("text", JsonParser.parseString("{\"type\":[\"string\",\"null\"],\"maxLength\":256}"));
                    properties.add("cursor", JsonParser.parseString(WORLD_CURSOR));
                }
                case "read_command" -> {
                    properties.add("name", JsonParser.parseString("{\"type\":\"string\",\"maxLength\":512}"));
                    properties.add("cursor", JsonParser.parseString(WORLD_CURSOR));
                    required.add("name");
                }
                case "search_journal" -> {
                    var filter = JsonParser.parseString("{\"type\":\"object\",\"properties\":{\"time\":{\"type\":[\"object\",\"null\"],\"properties\":{\"after\":{\"type\":[\"string\",\"null\"],\"maxLength\":64},\"before\":{\"type\":[\"string\",\"null\"],\"maxLength\":64},\"lastMinutes\":{\"type\":[\"integer\",\"null\"],\"minimum\":1,\"maximum\":5256000}},\"additionalProperties\":false},\"area\":{\"type\":[\"object\",\"null\"],\"properties\":{\"radius\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":256}},\"required\":[\"radius\"],\"additionalProperties\":false},\"dimension\":{\"type\":[\"string\",\"null\"],\"maxLength\":256,\"pattern\":\"^[a-z0-9_.-]+:[a-z0-9/._-]+$\"},\"types\":{\"type\":[\"array\",\"null\"],\"maxItems\":17,\"items\":{\"type\":\"string\"}},\"subjects\":{\"type\":[\"array\",\"null\"],\"maxItems\":32,\"items\":{\"type\":\"string\",\"maxLength\":256,\"pattern\":\"^[a-z0-9_.-]+:[a-z0-9/._-]+$\"}},\"text\":{\"type\":[\"string\",\"null\"],\"maxLength\":256},\"notesOnly\":{\"type\":[\"boolean\",\"null\"]}},\"additionalProperties\":false}").getAsJsonObject();
                    filter.getAsJsonObject("properties").getAsJsonObject("area").getAsJsonObject("properties").add("center", JsonParser.parseString(WORLD_ANCHOR));
                    var types = new JsonArray();
                    for (var type : EventType.values()) types.add(type.id());
                    types.add("note");
                    filter.getAsJsonObject("properties").getAsJsonObject("types").addProperty("maxItems", types.size());
                    filter.getAsJsonObject("properties").getAsJsonObject("types").getAsJsonObject("items").add("enum", types);
                    properties.add("filter", filter);
                    properties.add("cursor", JsonParser.parseString(WORLD_CURSOR));
                    required.add("filter");
                }
                case "remember", "update_note" -> {
                    properties.add("text", JsonParser.parseString("{\"type\":\"string\",\"minLength\":1,\"maxLength\":1000}"));
                    var at = JsonParser.parseString(WORLD_ANCHOR).getAsJsonObject();
                    at.getAsJsonObject("properties").getAsJsonObject("type").add("enum", JsonParser.parseString("[\"player\",\"block\",\"entity\"]"));
                    properties.add("at", at);
                    properties.add("tags", JsonParser.parseString("{\"type\":[\"array\",\"null\"],\"maxItems\":8,\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":32,\"pattern\":\"^[a-z0-9_-]+$\"}}"));
                    if (name.equals("update_note")) {
                        properties.add("id", JsonParser.parseString("{\"type\":\"string\",\"pattern\":\"^n[0-9a-f]{8}$\",\"maxLength\":9}"));
                        properties.add("clearPlace", JsonParser.parseString("{\"type\":[\"boolean\",\"null\"]}"));
                        required.add("id");
                    }
                    required.add("text");
                }
                case "forget_note" -> {
                    properties.add("id", JsonParser.parseString("{\"type\":\"string\",\"pattern\":\"^n[0-9a-f]{8}$\",\"maxLength\":9}"));
                    required.add("id");
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
            if (List.of("search_recipe", "search_registry", "list_configs", "search_config", "search_history").contains(name)) {
                var limit = JsonParser.parseString("{\"type\":[\"integer\",\"null\"],\"minimum\":1,\"maximum\":64}").getAsJsonObject();
                limit.addProperty("maximum", name.equals("search_history") ? 16 : 64);
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
                case "search_registry" -> "Find identities in one registry catalog. active_effect lists the player's current effect instances (durationTicks, amplifier, ambient, attributeModifiers), with description when the mod provides one; descriptionStatus reports not_provided, unknown, or bounded for a truncated description; one read shows a duration, not whether it refreshes. player_attribute lists current attribute base and value with modifiers whose IDs name their sources, such as minecraft:effect.luck or an item's modifier ID. Runtime dimensions differ from dimension-type definitions, client-known lists can be partial, dimension tags are unsupported, and searches do not create or load worlds.";
                case "search_config" -> "Find accessible config keys and comments, including machine speed, energy and upgrade settings. Read values with read_config, passing a returned config reference.";
                case "read_config" -> "Read a discovered config key or section at call time, with its authority and scope. A file value is not necessarily the effective server setting.";
                case "plan_recipe" -> "Plan recipe routes for an item identity and quantity, including brewing routes, with unresolved dependencies and coverage. It does not read owned materials or establish affordability. Routes cover every component variant of the identity; match output components. The live recipe scan is bounded, so a missing route is not absence. Brewing routes withhold operation counts (batches null) and report operationSemantics instead.";
                case "read_evidence" -> "Reread current evidence for returned record references only. Do not pass search cursors, labels or guessed references. An item record's reference rereads that item's current record, including enchantmentEligibility.";
                case "inspect_area" -> "Summarize the loaded world around center (default the player, radius 16): block counts, notable blocks such as containers, workstations, beds and spawners grouped by role and block, and nearby entities. Then list_blocks or list_entities with a filter for linkable places, and inspect_block or inspect_entity what matters.";
                case "list_blocks" -> "List blocks matching filter (ids, tags or name text) within radius of center, 64 per page, each with its place and a highlight link. Inspect what matters with inspect_block.";
                case "list_entities" -> "List entities such as mobs, villagers, item frames and minecarts matching filter within radius of center, 64 per page, each with its id, position and a highlight link. Inspect what matters with inspect_entity.";
                case "inspect_block" -> "Read one block by place (dimension|x,y,z): its state, facts from every adapter and the inventories it has. List an inventory's items with list_items, passing the returned holder.";
                case "inspect_entity" -> "Read one loaded entity by id: facts from every adapter, such as health, villager profession, bed and job site, and the inventories it has. Villager lastSlept, lastWorkedAtPoi and golemDetectedUntil are game ticks to compare with the record's gameTime. Villagers and wandering traders carry stored offers in offers.entries, in offer order; each costA, costB or result itemFactsIndex refers to the shared offers.itemFacts table. Entry counts and prices stay with each offer, offerCount gives the total, and this player's prices are included when available. Offers may be not generated yet; offersStatus bounded means only the first 64 offers are listed. List its items with list_items, passing the returned holder.";
                case "list_items" -> "List the items in one holder (player, a returned holder, an item path whose item holdsItems, or a word: held, offhand, targeted for the block or entity under the crosshair, ridden, opened for the open menu, optionally followed by >steps) grouped by item with counts, variants, up to 8 location paths and slot facts (selected hotbar slot, equipmentSlot, menuSlot); the first record summarizes the holder's inventories with emptySlots and the player's selectedSlot, or an open menu's menuType, title and excludedSlots, the output previews such as a crafting result that are never listed as contents; opened:<id> paths last only while that menu stays open; a word that cannot resolve, such as held with an empty hand, returns no records and says why; inventory limits it to one section; filter.tooltip matches the tooltip lines shown to the player, for example [\"Luck\"]. Inspect a location with inspect_item, or list_items it again to step inside. To locate an item across holders, use find_items first.";
                case "inspect_item" -> "Read one item by its location path or by held or offhand (optionally followed by >steps, as can targeted and ridden): its tooltip lines as the player sees them (tooltipStatus bounded or unknown when cut or unreadable), enchantments, durability, full potion contents, custom name, attribute modifiers, enchantmentEligibility lists each enchantment that can still apply, with its conflicts (compatibleWithCurrent, exclusiveWith; always present, with a none note when nothing applies), raw components, a component fingerprint that tells same-id variants apart, and the inventories it has.";
                case "find_items" -> "Search every inventory in range at once: the player's own, containers and entities, including items nested in shulker boxes and bundles, grouped by item with location paths; filter.tooltip matches the tooltip lines shown to the player, for example [\"Luck\"]. Each container or entity holding a match comes back as its own record with a highlight link; inspect a returned path with inspect_item. For a question about everything stored, follow nextCall until it ends or the answer cannot change, and say what remains unread.";
                case "list_configs" -> "List every accessible config key and section, page by page. To find specific keys or comments, use search_config.";
                case "search_history" -> "Search the earlier saved PackSage questions and answers in this world by text, up to 16 per page, with excerpts. Read whole turns with read_history, passing returned history references.";
                case "read_history" -> "Read up to 8 returned history references: the earlier question, answer and steering messages, cut to maxChars.";
                case "search_journal" -> "Search what this world's journal recorded since it began: blocks placed and broken (also by explosions), items put into or taken from containers, deaths with their cause, kills, dimension changes with the portal, advancements, villager profession changes, curing, conversion and trades, raids, first structure entries, explosions, and the player's notes. Filter by time, area around an anchor, dimension, types, subject ids and text. Newest first, 64 per page. Repeated similar events come back as one run with a count, time span and box. Each event gives minutesAgo, real from and to times and fromGameTime and toGameTime in ticks.";
                case "remember" -> "Save the player's report of something they did, built, achieved or found as a note. Pass their statement as text, at for the place they mean (the player for 'here'), and a few lowercase tags such as build, achievement or resource. If possibleDuplicates shows the same thing, update_note it instead.";
                case "update_note" -> "Correct a saved note by id when the player changes, moves or renames what they reported: replace its text, move it with at, clear its place or replace its tags. Find its id with search_journal notesOnly.";
                case "forget_note" -> "Delete a saved note by id when the player says it no longer applies or asks you to forget it.";
                case "list_progress" -> "List progress entries: Minecraft advancements now, quest books later. After acquisition, pages start with one record per tree (an advancement tab) with done and total counts, then entries in tree order with status done, in_progress, available, locked or hidden, 64 records per page. Filter by tree id, statuses, or text in the title or description. Hidden entries have no title until earned; do not guess what they are. Read criteria, requirements and rewards with inspect_progress. A work-budget page can be empty with nextCursor; pass it with the same filters to resume. Cursors are single-use and bound to this world session.";
                case "inspect_progress" -> "Read one progress entry by its returned id: description, each criterion with its done state and when it was met (for example the biomes still missing for Adventuring Time), what it requires and unlocks, and rewards. A hidden entry that is not earned returns only its status.";
                case "list_statistics" -> "Read the player's own counters since the world began: blocks mined; items crafted, used, broken, picked up and dropped; mobs killed and killed by; and custom counters such as deaths, jumps, play time and distance walked. categories narrows to mined, crafted, used, broken, picked_up, dropped, killed, killed_by or custom. subjects filters block, item, entity or counter ids, tags or name text. Distances are in blocks, times in seconds and damage in health points. Counters never incremented are zero and are listed only when named in subjects.ids. Pass categories together with subjects, or a named id gets a zero row in every category that can count it. 64 per page. A work-budget page can be empty with nextCursor; pass it with the same filters to resume. Cursors are single-use and bound to this world session.";
                case "read_loot" -> "Read a loot table as the game defines it, from a table id, a block id (its drops), an entity type id or loaded entity id (its death drops) or a container place (its pending loot and seed, before it is opened). Returns pools, rolls, entries, conditions and functions with item and tag ids, plus the item ids it can produce. It never rolls the table.";
                case "locate" -> "Find the nearest biome or structure (ids or tags such as #minecraft:village) around center within radius chunks (default 100), without loading or generating chunks. Biomes come back as sampled (read from the biome source at sampled columns); structures come back as found (present in a generated chunk) or predicted (placement and biome checks pass for an ungenerated chunk). Up to 5 nearest first, each with its kind and a link that marks the place.";
                case "search_commands" -> "List the server's commands matching text in their name or usage, with whether the player can run them and short usage. Read one with read_command.";
                case "read_command" -> "Read one command or subcommand path such as fill or execute if block: its usage forms for the player's permission level, every argument with its type and examples, redirects and whether the player can run it.";
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
            if (call.name().equals("read_config") && !(arguments.values().get("key") instanceof Evidence.JsonString)
                    && !(arguments.values().get("section") instanceof Evidence.JsonString)) throw new IllegalArgumentException("select a config key or section");
            var request = new ReadRequest(call.name(), arguments, scope);
            CompletionStage<Evidence.Result> read;
            List<JsonObject> pendingRecords;
            if (arguments.values().get("cursor") instanceof Evidence.JsonString token && token.value().startsWith("output:")) {
                OutputPages.Pending pending;
                synchronized (outputPages) { pending = outputPages.pending.get(token.value()); }
                var signature = new java.util.LinkedHashMap<>(arguments.values());
                signature.remove("cursor");
                if (pending == null || !pending.request().tool().equals(call.name()) || !pending.request().arguments().values().equals(signature)) {
                    throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "output cursor does not match this query; restart without a cursor");
                }
                if (!pending.request().scope().equals(scope)) throw new Toolbox.Failure(Toolbox.Code.STALE_SCOPE, "output cursor scope changed");
                synchronized (outputPages) {
                    if (outputPages.pending.remove(token.value()) == null) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "output cursor was consumed or evicted; restart without a cursor");
                    outputPages.retainedBytes -= pending.bytes();
                }
                pendingRecords = pending.encoded();
                read = CompletableFuture.completedFuture(pending.result());
            } else {
                pendingRecords = null;
                read = dispatch.apply(request);
            }
            return read.thenApply(result -> {
                if (result == null) throw new IllegalStateException("evidence extraction returned no result");
                if (result.records().stream().anyMatch(record -> !record.source().scope().equals(scope)
                        || record.targets().stream().anyMatch(target -> !target.scope().equals(scope)
                        || !target.providerId().equals(record.source().providerId()) || !target.sourceHandle().equals(record.source().sourceHandle())))) {
                    return new ToolResponse(new ModelBackend.ToolResult(call.callId(),
                            "{\"coverage\":\"ERROR\",\"outcome\":\"ERROR\",\"detail\":\"stale or invalid evidence scope\",\"records\":[]}", true), List.of());
                }
                var json = new JsonObject();
                json.addProperty("coverage", result.coverage().name());
                json.addProperty("outcome", result.outcome().name());
                if (result.detail() != null) {
                    json.addProperty("detail", boundedString(result.detail()));
                    if (result.detail().length() > 2048) {
                        json.addProperty("coverage", "PARTIAL");
                        json.addProperty("outcome", "PARTIAL");
                    }
                }
                java.util.function.Consumer<String> continuation = cursor -> {
                    json.addProperty("nextCursor", cursor);
                    json.remove("nextCall");
                    if (cursor != null && properties.has("cursor")) {
                        var next = new JsonObject();
                        next.addProperty("name", call.name());
                        var nextArguments = JsonParser.parseString(call.serializedArguments()).getAsJsonObject();
                        nextArguments.addProperty("cursor", cursor);
                        next.add("arguments", nextArguments);
                        json.add("nextCall", next);
                    }
                };
                // Reserve the complete envelope, including repeated query arguments, before choosing any rows.
                var overflowCursor = properties.has("cursor") ? "output:" + java.util.UUID.randomUUID() : null;
                continuation.accept(overflowCursor == null || result.nextCursor() != null && result.nextCursor().length() > overflowCursor.length()
                        ? result.nextCursor() : overflowCursor);
                var records = new JsonArray();
                json.add("records", records);
                var accepted = new ArrayList<Evidence.Record>();
                int outputBytes = json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                int ordinaryRecords = 0;
                boolean trimmed = false;
                int skipped = 0;
                int index = 0;
                for (; index < result.records().size(); index++) {
                    var record = result.records().get(index);
                    if (!record.source().sourceHandle().startsWith("status:") && ordinaryRecords == 64) break;
                    var encoded = pendingRecords == null ? encodeRecord(record) : pendingRecords.get(index).deepCopy();
                    int recordBytes = encoded.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length + (records.isEmpty() ? 0 : 1);
                    boolean crossesBudget = outputBytes + recordBytes > 131_072 - 12_800;
                    if (crossesBudget) {
                        if (overflowCursor != null && !accepted.isEmpty()) break;
                        // Keep complete leading routes; only the crossing record can use the remaining space.
                        trimRecord(encoded, 131_072 - outputBytes - 12_800 - (records.isEmpty() ? 0 : 1));
                        recordBytes = encoded.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length + (records.isEmpty() ? 0 : 1);
                        if (outputBytes + recordBytes > 131_072 - 12_800) {
                            if (overflowCursor == null) break;
                            skipped++;
                            continue;
                        }
                    }
                    records.add(encoded);
                    if (encoded.has("truncationNote")) {
                        trimmed = true;
                        var source = record.source();
                        record = new Evidence.Record(new Evidence.Source(source.scope(), source.providerId(), source.providerVersion(),
                                source.sourceHandle(), source.observedGeneration(), source.observedAt(), source.activity(), Evidence.Coverage.PARTIAL,
                                source.authority(), source.provenance()), record.kind(), record.title(), record.payload(), record.targets(), record.recipePayload());
                    }
                    accepted.add(record);
                    if (!record.source().sourceHandle().startsWith("status:")) ordinaryRecords++;
                    outputBytes += recordBytes;
                    if (crossesBudget) {
                        index++;
                        break;
                    }
                }
                if (index < result.records().size() || skipped > 0) {
                    if (index < result.records().size() && overflowCursor != null && !accepted.isEmpty()) {
                        var signature = new java.util.LinkedHashMap<>(arguments.values());
                        signature.remove("cursor");
                        var tail = new ArrayList<Evidence.Record>();
                        var encodedTail = new ArrayList<JsonObject>();
                        int tailBytes = Evidence.serialize(new Evidence.JsonObject(signature)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                                + JSON.toJsonTree(scope).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 12_800
                                + (result.nextCursor() == null ? 0 : result.nextCursor().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                        int skippedBeforeTail = skipped;
                        for (int i = index; i < result.records().size(); i++) {
                            var record = result.records().get(i);
                            var encoded = pendingRecords == null ? encodeRecord(record) : pendingRecords.get(i);
                            int bytes = encoded.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                            if (bytes > MAX_RECORD_BYTES) {
                                if (pendingRecords != null) encoded = encoded.deepCopy();
                                trimRecord(encoded, MAX_RECORD_BYTES);
                                bytes = encoded.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                                // Skip only a record whose protected identity alone cannot fit, and keep the rows after it.
                                if (bytes > MAX_RECORD_BYTES) {
                                    skipped++;
                                    continue;
                                }
                            }
                            if (tailBytes + bytes > 4 * 1024 * 1024) break;
                            var source = record.source();
                            // Keep the bounded wire content and delivery metadata, never the provider's raw payload/recipe.
                            var retained = new Evidence.Record(new Evidence.Source(source.scope(), source.providerId(), boundedString(source.providerVersion()),
                                    source.sourceHandle(), source.observedGeneration(), source.observedAt(), source.activity(),
                                    encoded.has("truncationNote") ? Evidence.Coverage.PARTIAL : source.coverage(), source.authority(),
                                    encoded.getAsJsonObject("source").has("provenance") ? encoded.getAsJsonObject("source").getAsJsonArray("provenance")
                                            .asList().stream().map(com.google.gson.JsonElement::getAsString).toList() : List.of()),
                                    record.kind(), encoded.get("title").getAsString(), Evidence.deserializeObject(encoded.get("payload").toString()), record.targets());
                            bytes += JSON.toJsonTree(retained).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                            if (tailBytes + bytes > 4 * 1024 * 1024) break;
                            tail.add(retained);
                            encodedTail.add(encoded);
                            tailBytes += bytes;
                        }
                        int omitted = result.records().size() - index - tail.size() - (skipped - skippedBeforeTail);
                        var skippedDetail = skipped == 0 ? "" : skipped == 1
                                ? "1 record omitted because its canonical identity exceeded the output budget; refine the request to reduce output size"
                                : skipped + " records omitted because their canonical identities exceeded the output budget; refine the request to reduce output size";
                        var tailDetail = boundedString((omitted == 0 ? "" : omitted + " records omitted at the retained-output byte limit; refine the request to reduce output size")
                                + (skippedDetail.isEmpty() ? "" : (omitted == 0 ? "" : "; ") + skippedDetail)
                                + (result.detail() == null ? "" : ((omitted == 0 && skippedDetail.isEmpty() ? "" : "; ") + result.detail())));
                        synchronized (outputPages) {
                            outputPages.pending.put(overflowCursor, new OutputPages.Pending(new ReadRequest(call.name(), new Evidence.JsonObject(signature), scope),
                                    new Evidence.Result(tail, omitted == 0 && skipped == 0 ? result.coverage() : Evidence.Coverage.PARTIAL,
                                            omitted == 0 && skipped == 0 ? result.outcome() : Evidence.Outcome.PARTIAL, result.nextCursor(), tailDetail), encodedTail, tailBytes));
                            outputPages.retainedBytes += tailBytes;
                            while (outputPages.pending.size() > 256 || outputPages.retainedBytes > 8 * 1024 * 1024) {
                                outputPages.retainedBytes -= outputPages.pending.remove(outputPages.pending.keySet().iterator().next()).bytes();
                            }
                        }
                        continuation.accept(overflowCursor);
                    } else {
                        continuation.accept(index == result.records().size() ? result.nextCursor() : null);
                    }
                    json.addProperty("coverage", "PARTIAL");
                    json.addProperty("outcome", "PARTIAL");
                    var skippedDetail = skipped == 0 ? "" : skipped == 1
                            ? "1 record omitted because its canonical identity exceeded the output budget; refine the request to reduce output size"
                            : skipped + " records omitted because their canonical identities exceeded the output budget; refine the request to reduce output size";
                    var outputDetail = index < result.records().size() && overflowCursor != null && !accepted.isEmpty()
                            ? "encoded page byte or record budget reached; continue with nextCursor"
                            : index < result.records().size()
                                    ? (result.records().size() - index) + " records omitted at the output byte or record limit; refine the request to reduce output size"
                                    : "";
                    json.addProperty("detail", boundedString((skippedDetail.isEmpty() ? outputDetail
                            : skippedDetail + (outputDetail.isEmpty() ? "" : "; " + outputDetail))
                            + (result.detail() == null ? "" : ((skippedDetail.isEmpty() && outputDetail.isEmpty() ? "" : "; ") + result.detail()))));
                } else continuation.accept(result.nextCursor());
                if (trimmed) {
                    json.addProperty("coverage", "PARTIAL");
                    json.addProperty("outcome", "PARTIAL");
                    json.addProperty("detail", boundedString("record content truncated: string values are capped at 2048 UTF-16 units and oversized collections are trimmed"
                            + (json.has("detail") ? "; " + json.get("detail").getAsString() : "")));
                }
                var serialized = json.toString();
                if (serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 131_072) {
                    synchronized (outputPages) {
                        var discarded = outputPages.pending.remove(overflowCursor);
                        if (discarded != null) outputPages.retainedBytes -= discarded.bytes();
                    }
                    return new ToolResponse(new ModelBackend.ToolResult(call.callId(),
                            "{\"coverage\":\"PARTIAL\",\"outcome\":\"PARTIAL\",\"records\":[],\"detail\":\""
                                    + result.records().size() + " records omitted because the response envelope exceeds the byte limit; narrow the requested selection\"}", false), List.of());
                }
                try {
                    outputPages.delivered.accept(scope, accepted);
                } catch (RuntimeException | Error failure) {
                    synchronized (outputPages) {
                        var discarded = outputPages.pending.remove(overflowCursor);
                        if (discarded != null) outputPages.retainedBytes -= discarded.bytes();
                    }
                    throw failure;
                }
                return new ToolResponse(new ModelBackend.ToolResult(call.callId(), serialized, result.outcome() == Evidence.Outcome.ERROR), accepted);
            }).exceptionally(failure -> {
                while (failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null) failure = failure.getCause();
                var error = new JsonObject();
                error.addProperty("coverage", "ERROR");
                error.addProperty("outcome", "ERROR");
                error.addProperty("detail", boundedString(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
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
            });
        } catch (RuntimeException failure) {
            var error = new JsonObject();
            error.addProperty("coverage", "ERROR");
            error.addProperty("outcome", "ERROR");
            error.addProperty("detail", failure instanceof IllegalArgumentException || failure instanceof Toolbox.Failure ? boundedString(failure.getMessage()) : "invalid read tool arguments");
            if (failure instanceof Toolbox.Failure typed) error.addProperty("code", typed.code().name());
            return CompletableFuture.completedFuture(new ToolResponse(new ModelBackend.ToolResult(call.callId(), error.toString(), true), List.of()));
        }
    }

    /** The wire representation shared by tool output and provider page budgets. */
    public static JsonObject encodeRecord(Evidence.Record record) {
        return encodeRecord(record, MAX_RECORD_BYTES);
    }

    /** Providers with their own content-trimming policy can measure the string-bounded, untrimmed record. */
    public static JsonObject encodeRecord(Evidence.Record record, int byteBudget) {
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
        class Strings {
            boolean bound(com.google.gson.JsonElement value) {
                boolean changed = false;
                if (value.isJsonObject()) {
                    for (var entry : value.getAsJsonObject().entrySet()) {
                        // ReferenceAliasBackend compacts these transport identities before model delivery.
                        // Truncating a canonical reference here would change the reread destination.
                        if (value == encoded && entry.getKey().equals("reference") || value == encoded.get("source")
                                && List.of("providerId", "sourceHandle").contains(entry.getKey())) continue;
                        if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()
                                && entry.getValue().getAsString().length() > 2048) {
                            entry.setValue(new com.google.gson.JsonPrimitive(boundedString(entry.getValue().getAsString())));
                            changed = true;
                        } else changed |= bound(entry.getValue());
                    }
                } else if (value.isJsonArray()) {
                    var array = value.getAsJsonArray();
                    for (int i = 0; i < array.size(); i++) {
                        if (array.get(i).isJsonPrimitive() && array.get(i).getAsJsonPrimitive().isString() && array.get(i).getAsString().length() > 2048) {
                            array.set(i, new com.google.gson.JsonPrimitive(boundedString(array.get(i).getAsString())));
                            changed = true;
                        } else changed |= bound(array.get(i));
                    }
                }
                return changed;
            }
        }
        if (new Strings().bound(encoded)) {
            encoded.getAsJsonObject("source").addProperty("coverage", "PARTIAL");
            encoded.addProperty("truncationNote", "string values exceeding 2048 UTF-16 units were truncated");
        }
        trimRecord(encoded, byteBudget);
        return encoded;
    }

    private static String boundedString(String value) {
        if (value == null || value.length() <= 2048) return value;
        int end = 2048 - " [truncated]".length();
        if (Character.isHighSurrogate(value.charAt(end - 1)) && Character.isLowSurrogate(value.charAt(end))) end--;
        return value.substring(0, end) + " [truncated]";
    }

    /** Keep record identity and links, reducing bulky nested content before any compact scalar facts. */
    private static void trimRecord(JsonObject record, int budget) {
        if (record.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= budget) return;
        record.getAsJsonObject("source").addProperty("coverage", "PARTIAL");
        record.addProperty("truncationNote", "record content exceeded its encoded byte budget; collection tails, oversized fields or source metadata were omitted");
        class Trimmer {
            record Field(String key, com.google.gson.JsonElement value, int keyBytes) { }
            final java.util.Map<com.google.gson.JsonElement, Integer> sizes = new java.util.IdentityHashMap<>();
            final java.util.Map<JsonObject, java.util.PriorityQueue<Field>> fields = new java.util.IdentityHashMap<>();

            int bytes(com.google.gson.JsonElement value) {
                var cached = sizes.get(value);
                if (cached != null) return cached;
                int size;
                if (value.isJsonArray()) {
                    size = 2 + Math.max(0, value.getAsJsonArray().size() - 1);
                    for (var child : value.getAsJsonArray()) size += bytes(child);
                } else if (value.isJsonObject()) {
                    size = 2 + Math.max(0, value.getAsJsonObject().size() - 1);
                    for (var entry : value.getAsJsonObject().entrySet()) size += new com.google.gson.JsonPrimitive(entry.getKey())
                            .toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1 + bytes(entry.getValue());
                } else size = value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                sizes.put(value, size);
                return size;
            }

            int trim(com.google.gson.JsonElement value, int excess) {
                int before = bytes(value);
                int removed = 0;
                if (value.isJsonArray()) {
                    var array = value.getAsJsonArray();
                    while (!array.isEmpty() && removed < excess) {
                        removed += bytes(array.get(array.size() - 1)) + (array.size() > 1 ? 1 : 0);
                        array.remove(array.size() - 1);
                    }
                } else if (value.isJsonObject()) {
                    var object = value.getAsJsonObject();
                    var queue = fields.computeIfAbsent(object, current -> {
                        var ordered = new java.util.PriorityQueue<Field>(java.util.Comparator.comparingInt(
                                (Field field) -> field.keyBytes() + bytes(field.value())).reversed());
                        current.entrySet().forEach(entry -> ordered.add(new Field(entry.getKey(), entry.getValue(),
                                new com.google.gson.JsonPrimitive(entry.getKey()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1)));
                        return ordered;
                    });
                    while (!queue.isEmpty() && removed < excess) {
                        var largest = queue.remove();
                        int cut = trim(largest.value(), excess - removed);
                        if (cut == 0) {
                            removed += largest.keyBytes() + bytes(largest.value()) + (object.size() > 1 ? 1 : 0);
                            object.remove(largest.key());
                        } else {
                            removed += cut;
                            queue.add(largest);
                        }
                    }
                }
                sizes.put(value, before - removed);
                return removed;
            }
        }
        var trimmer = new Trimmer();
        while (trimmer.bytes(record) > budget) {
            var largest = java.util.stream.Stream.of(record.get("payload"), record.get("recipe"), record.getAsJsonObject("source").get("provenance"))
                    .filter(value -> value != null && (value.isJsonObject() && !value.getAsJsonObject().isEmpty()
                            || value.isJsonArray() && !value.getAsJsonArray().isEmpty()))
                    .max(java.util.Comparator.comparingInt(trimmer::bytes)).orElse(null);
            if (largest == null || trimmer.trim(largest, trimmer.bytes(record) - budget) == 0) {
                var title = record.get("title").getAsString();
                if (title.isEmpty()) {
                    var source = record.getAsJsonObject("source");
                    var metadata = source.entrySet().stream().filter(entry -> !List.of("providerId", "sourceHandle", "coverage").contains(entry.getKey()))
                            .max(java.util.Comparator.comparingInt(entry -> trimmer.bytes(entry.getValue()))).orElse(null);
                    if (metadata == null) break;
                    source.remove(metadata.getKey());
                } else {
                    int end = title.length() > 2 * " [truncated]".length() ? title.length() / 2 : 0;
                    if (end > 0 && Character.isHighSurrogate(title.charAt(end - 1)) && Character.isLowSurrogate(title.charAt(end))) end--;
                    record.addProperty("title", end == 0 ? "" : title.substring(0, end) + " [truncated]");
                }
            }
            trimmer.sizes.remove(record.get("source"));
            trimmer.sizes.remove(record);
        }
    }
}
