package dev.packsage.model;

import com.google.gson.JsonParser;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PackSageToolsTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "resources", "team", "runtime", 1);
    private static final List<String> NAMES = List.of("search_recipe", "search_registry", "read_evidence", "plan_recipe", "list_configs", "search_config", "read_config", "search_history", "read_history",
            "inspect_area", "list_blocks", "list_entities", "inspect_block", "inspect_entity", "list_items", "inspect_item", "find_items",
            "list_progress", "inspect_progress", "list_statistics",
            "read_loot", "locate", "search_commands", "read_command",
            "search_journal", "remember", "update_note", "forget_note");

    @Test
    void continuationCallPreservesQueryAndExecutesAnotherLiveRead() throws Exception {
        var calls = new java.util.ArrayList<PackSageTools.ReadRequest>();
        var tools = new PackSageTools(request -> {
            calls.add(request);
            return CompletableFuture.completedFuture(new Evidence.Result(List.of(),
                    calls.size() == 1 ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                    calls.size() == 1 ? Evidence.Outcome.PARTIAL : Evidence.Outcome.COMPLETE,
                    calls.size() == 1 ? "next-live-position" : null));
        });
        var response = tools.execute(new ModelBackend.ToolCall("first", "search_recipe",
                "{\"filter\":{\"text\":\"fall damage\"},\"limit\":10}"), SCOPE).toCompletableFuture().get();
        var next = JsonParser.parseString(response.result().serializedResult()).getAsJsonObject().getAsJsonObject("nextCall");
        assertNotNull(next);
        var continued = tools.execute(new ModelBackend.ToolCall("second", next.get("name").getAsString(),
                next.get("arguments").toString()), SCOPE).toCompletableFuture().get();
        assertEquals(2, calls.size());
        assertEquals(Evidence.deserializeObject("{\"filter\":{\"text\":\"fall damage\"},\"limit\":10,\"cursor\":\"next-live-position\"}"), calls.get(1).arguments());
        assertFalse(JsonParser.parseString(continued.result().serializedResult()).getAsJsonObject().has("nextCall"));
    }

    @Test
    void invalidCursorOffersExplicitFreshRestartWithoutRetryingSilently() throws Exception {
        var calls = new AtomicInteger();
        var tools = new PackSageTools(request -> {
            calls.incrementAndGet();
            return CompletableFuture.failedFuture(new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "query changed"));
        });
        var response = tools.execute(new ModelBackend.ToolCall("bad", "search_recipe",
                "{\"filter\":{\"text\":\"backpack\"},\"limit\":20,\"cursor\":\"old\"}"), SCOPE).toCompletableFuture().get();
        var json = JsonParser.parseString(response.result().serializedResult()).getAsJsonObject();
        assertTrue(response.result().error());
        assertEquals(1, calls.get());
        assertNotNull(json.getAsJsonObject("restartCall"));
        assertEquals(JsonParser.parseString("{\"name\":\"search_recipe\",\"arguments\":{\"filter\":{\"text\":\"backpack\"},\"limit\":20,\"cursor\":null}}"), json.get("restartCall"));
    }

    @Test
    void exposesExactlyTwentyEightToolsAndKeepsOptionalArgumentsOptional() {
        var definitions = PackSageTools.definitions();
        assertEquals(NAMES, definitions.stream().map(ModelBackend.ToolDefinition::name).toList());
        for (var definition : definitions) {
            var schema = JsonParser.parseString(definition.serializedSchema()).getAsJsonObject();
            assertFalse(schema.get("additionalProperties").getAsBoolean());
            assertTrue(schema.getAsJsonArray("required").size() < schema.getAsJsonObject("properties").size()
                    || List.of("read_evidence", "read_history", "inspect_block", "inspect_entity", "inspect_item", "inspect_progress", "forget_note").contains(definition.name()));
        }
        for (var name : List.of("search_recipe", "search_registry")) {
            var schema = definitions.stream().filter(value -> value.name().equals(name)).findFirst().orElseThrow();
            assertEquals(64, JsonParser.parseString(schema.serializedSchema()).getAsJsonObject().getAsJsonObject("properties").getAsJsonObject("limit").get("maximum").getAsInt());
        }
    }

    @Test
    void listsJournalTools() throws Exception {
        var definitions = PackSageTools.definitions();
        for (var name : List.of("search_journal", "remember", "update_note", "forget_note")) {
            var schema = JsonParser.parseString(definitions.stream().filter(value -> value.name().equals(name)).findFirst().orElseThrow().serializedSchema()).getAsJsonObject();
            assertEquals(switch (name) {
                case "search_journal" -> List.of("filter");
                case "remember" -> List.of("text");
                case "update_note" -> List.of("id", "text");
                default -> List.of("id");
            }, schema.getAsJsonArray("required").asList().stream().map(value -> value.getAsString()).toList());
            assertEquals(switch (name) {
                case "search_journal" -> java.util.Set.of("filter", "cursor");
                case "remember" -> java.util.Set.of("text", "at", "tags");
                case "update_note" -> java.util.Set.of("id", "text", "at", "clearPlace", "tags");
                default -> java.util.Set.of("id");
            }, schema.getAsJsonObject("properties").keySet());
        }
        var search = JsonParser.parseString(definitions.stream().filter(value -> value.name().equals("search_journal")).findFirst().orElseThrow().serializedSchema())
                .getAsJsonObject().getAsJsonObject("properties").getAsJsonObject("filter").getAsJsonObject("properties");
        assertEquals(java.util.Set.of("time", "area", "dimension", "types", "subjects", "text", "notesOnly"), search.keySet());
        assertEquals(17, search.getAsJsonObject("types").getAsJsonObject("items").getAsJsonArray("enum").size());
        assertEquals(32, search.getAsJsonObject("subjects").get("maxItems").getAsInt());
        assertEquals(256, search.getAsJsonObject("text").get("maxLength").getAsInt());
        assertEquals(256, search.getAsJsonObject("area").getAsJsonObject("properties").getAsJsonObject("radius").get("maximum").getAsInt());
        assertEquals(5_256_000, search.getAsJsonObject("time").getAsJsonObject("properties").getAsJsonObject("lastMinutes").get("maximum").getAsInt());
        var calls = new AtomicInteger();
        var tools = new PackSageTools(request -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null));
        });
        assertFalse(tools.execute(new ModelBackend.ToolCall("valid", "search_journal", "{\"filter\":{}}"), SCOPE).toCompletableFuture().get().result().error());
        for (var args : List.of("{}", "{\"filter\":null}", "{\"filter\":{\"area\":{}}}", "{\"filter\":{\"types\":[\"invented\"]}}",
                "{\"filter\":{\"subjects\":[\"invalid\"]}}")) {
            assertTrue(tools.execute(new ModelBackend.ToolCall("invalid", "search_journal", args), SCOPE).toCompletableFuture().get().result().error());
        }
        assertEquals(1, calls.get());
    }

    @Test
    void descriptionsGuideCommonSearchAndEvidenceReferences() {
        var definitions = PackSageTools.definitions();
        assertTrue(definitions.stream().noneMatch(value -> value.name().equals("search_document") || value.name().equals("read_progress")));
        var recipe = definitions.stream().filter(value -> value.name().equals("search_recipe")).findFirst().orElseThrow().description();
        assertTrue(recipe.contains("outputItem without text"));
        assertTrue(recipe.contains("Find Minecraft's loaded recipes"));
        assertTrue(recipe.contains("case-insensitive literal match"));
        var registry = definitions.stream().filter(value -> value.name().equals("search_registry")).findFirst().orElseThrow().description();
        assertTrue(registry.contains("active_effect lists the player's current effect instances"));
        assertTrue(registry.contains("(durationTicks, amplifier, ambient, attributeModifiers)"));
        assertTrue(registry.contains("with description when the mod provides one"));
        assertTrue(registry.contains("descriptionStatus reports not_provided, unknown, or bounded"));
        assertTrue(registry.contains("player_attribute lists current attribute base and value with modifiers whose IDs name their sources"));
        assertTrue(registry.contains("one read shows a duration, not whether it refreshes"));
        var find = definitions.stream().filter(value -> value.name().equals("find_items")).findFirst().orElseThrow().description();
        assertTrue(find.contains("For a question about everything stored, follow nextCall until it ends or the answer cannot change, and say what remains unread."));
        var list = definitions.stream().filter(value -> value.name().equals("list_items")).findFirst().orElseThrow().description();
        assertTrue(list.contains("To locate an item across holders, use find_items first."));
        var entity = definitions.stream().filter(value -> value.name().equals("inspect_entity")).findFirst().orElseThrow().description();
        for (var phrase : List.of("lastSlept", "lastWorkedAtPoi", "golemDetectedUntil", "gameTime", "offers", "offerCount")) {
            assertTrue(entity.contains(phrase), phrase);
        }
        var item = definitions.stream().filter(value -> value.name().equals("inspect_item")).findFirst().orElseThrow().description();
        assertTrue(item.contains("enchantmentEligibility lists each enchantment that can still apply, with its conflicts"));
        var plan = definitions.stream().filter(value -> value.name().equals("plan_recipe")).findFirst().orElseThrow().description();
        assertTrue(plan.contains("including brewing routes"));
        assertTrue(plan.contains("Brewing routes withhold operation counts"));
        assertTrue(plan.contains("Routes cover every component variant of the identity; match output components."));
        assertTrue(plan.contains("The live recipe scan is bounded, so a missing route is not absence."));
        for (var name : List.of("read_evidence")) {
            var description = definitions.stream().filter(value -> value.name().equals(name)).findFirst().orElseThrow().description();
            assertTrue(description.contains("returned record references only"));
            assertTrue(description.contains("Do not pass search cursors"));
        }
        var evidence = definitions.stream().filter(value -> value.name().equals("read_evidence")).findFirst().orElseThrow().description();
        assertTrue(evidence.contains("An item record's reference rereads that item's current record, including enchantmentEligibility."));
    }

    @Test
    void searchInventoryIsNoLongerOffered() throws Exception {
        assertTrue(PackSageTools.definitions().stream().noneMatch(value -> value.name().equals("search_inventory")));
        var calls = new AtomicInteger();
        var tools = new PackSageTools(request -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null)); });
        var response = tools.execute(new ModelBackend.ToolCall("inv", "search_inventory", "{\"target\":{\"type\":\"player\"}}"), SCOPE)
                .toCompletableFuture().get();
        assertTrue(response.result().error());
        assertEquals("unknown read tool", JsonParser.parseString(response.result().serializedResult()).getAsJsonObject().get("detail").getAsString());
        assertEquals(0, calls.get());
    }

    @Test
    void toolDescriptionsNameOnlyDefinedTools() {
        for (var definition : PackSageTools.definitions()) {
            assertFalse(definition.description().contains("search_inventory"), definition.name());
            assertEquals(List.of(), undefinedToolNames(definition.description()), definition.name());
        }
    }

    @Test
    void everyToolHasItsOwnDescription() {
        for (var definition : PackSageTools.definitions()) {
            assertFalse(definition.description().startsWith("Read current evidence for"), definition.name());
        }
    }

    /** Tool-like names in the text that no current tool definition carries. */
    static List<String> undefinedToolNames(String text) {
        var names = PackSageTools.definitions().stream().map(ModelBackend.ToolDefinition::name).toList();
        var undefined = new java.util.ArrayList<String>();
        var mentioned = java.util.regex.Pattern.compile("\\b(?:search|read|list|inspect|find|plan|update|forget)_[a-z_]+\\b").matcher(text);
        while (mentioned.find()) if (!names.contains(mentioned.group())) undefined.add(mentioned.group());
        return undefined;
    }

    @Test
    void serializesDeterministicTotalsForSingletonConsumedRecipeInputs() throws Exception {
        var iron = new Evidence.RecipeAlternative("item_stack", "minecraft:iron_ingot", new Evidence.JsonObject(Map.of()), 1, Evidence.Consumption.CONSUMED);
        var redstone = new Evidence.RecipeAlternative("item_stack", "minecraft:redstone", new Evidence.JsonObject(Map.of()), 1, Evidence.Consumption.CONSUMED);
        var casing = new Evidence.RecipeAlternative("item_stack", "fixture:iron_casing", new Evidence.JsonObject(Map.of()), 1, Evidence.Consumption.CONSUMED);
        var recipe = new Evidence.RecipePayload("fixture:atomic_reconstructor", List.of(
                new Evidence.RecipeSlot("iron-1", List.of(iron)), new Evidence.RecipeSlot("redstone-1", List.of(redstone)),
                new Evidence.RecipeSlot("iron-2", List.of(iron)), new Evidence.RecipeSlot("redstone-2", List.of(redstone)),
                new Evidence.RecipeSlot("casing", List.of(casing)), new Evidence.RecipeSlot("iron-3", List.of(iron)),
                new Evidence.RecipeSlot("redstone-3", List.of(redstone)), new Evidence.RecipeSlot("iron-4", List.of(iron)),
                new Evidence.RecipeSlot("redstone-4", List.of(redstone))), List.of(), List.of(), List.of(), List.of(), Evidence.DecodingStatus.VERIFIED);
        var record = new Evidence.Record(new Evidence.Source(SCOPE, "native", "fixture", "recipe:atomic_reconstructor", 1,
                Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()),
                Evidence.RecordKind.RECIPE, "Atomic Reconstructor", new Evidence.JsonObject(Map.of()), List.of(), recipe);
        var tools = new PackSageTools(request -> CompletableFuture.completedFuture(new Evidence.Result(List.of(record),
                Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null)));

        var json = JsonParser.parseString(tools.execute(new ModelBackend.ToolCall("recipe", "search_recipe", "{}"), SCOPE)
                .toCompletableFuture().get().result().serializedResult()).getAsJsonObject();
        var encoded = json.getAsJsonArray("records").get(0).getAsJsonObject();

        assertEquals(9, encoded.getAsJsonObject("recipe").getAsJsonArray("inputs").size());
        var totals = encoded.getAsJsonObject("recipe").getAsJsonArray("requiredInputTotals");
        assertEquals(3, totals.size());
        assertEquals(4, totals.get(0).getAsJsonObject().get("quantity").getAsInt());
        assertEquals("minecraft:iron_ingot", totals.get(0).getAsJsonObject().get("comparisonIdentity").getAsString());
        assertEquals(4, totals.get(1).getAsJsonObject().get("quantity").getAsInt());
        assertEquals("minecraft:redstone", totals.get(1).getAsJsonObject().get("comparisonIdentity").getAsString());
        assertEquals(1, totals.get(2).getAsJsonObject().get("quantity").getAsInt());
        assertEquals("fixture:iron_casing", totals.get(2).getAsJsonObject().get("comparisonIdentity").getAsString());
        assertEquals("COMPLETE", json.get("coverage").getAsString());
        assertEquals(9, encoded.getAsJsonObject("recipe").getAsJsonArray("inputs").size());
    }

    @Test
    void omitsRecipeTotalsWhenAnyInputIsAmbiguousOrUnsupported() throws Exception {
        var iron = new Evidence.RecipeAlternative("item", "minecraft:iron_ingot", new Evidence.JsonObject(Map.of()), 1, Evidence.Consumption.CONSUMED);
        var alternateIron = new Evidence.RecipeAlternative("item", "forge:ingots/iron", new Evidence.JsonObject(Map.of()), 1, Evidence.Consumption.CONSUMED);
        var unknownQuantity = new Evidence.RecipeAlternative("item", "minecraft:iron_ingot", new Evidence.JsonObject(Map.of()), null, Evidence.Consumption.CONSUMED);
        var reusable = new Evidence.RecipeAlternative("item", "minecraft:iron_ingot", new Evidence.JsonObject(Map.of()), 1, Evidence.Consumption.REUSED);
        var custom = new Evidence.RecipeAlternative("fluid", "minecraft:iron_ingot", new Evidence.JsonObject(Map.of()), 1, Evidence.Consumption.CONSUMED);
        for (var alternatives : List.of(List.of(iron, alternateIron), List.of(unknownQuantity), List.of(reusable), List.of(custom))) {
            var recipe = new Evidence.RecipePayload("fixture:recipe", List.of(new Evidence.RecipeSlot("input", alternatives)),
                    List.of(), List.of(), List.of(), List.of("machine constraints retained"), Evidence.DecodingStatus.PARTIAL);
            var record = new Evidence.Record(new Evidence.Source(SCOPE, "native", "fixture", "recipe:ambiguous", 1,
                    Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.PARTIAL, Evidence.Authority.LOADED, List.of()),
                    Evidence.RecordKind.RECIPE, "Recipe", new Evidence.JsonObject(Map.of()), List.of(), recipe);
            var tools = new PackSageTools(request -> CompletableFuture.completedFuture(new Evidence.Result(List.of(record),
                    Evidence.Coverage.PARTIAL, Evidence.Outcome.PARTIAL, null)));
            var json = JsonParser.parseString(tools.execute(new ModelBackend.ToolCall("recipe", "search_recipe", "{}"), SCOPE)
                    .toCompletableFuture().get().result().serializedResult()).getAsJsonObject();
            var encodedRecipe = json.getAsJsonArray("records").get(0).getAsJsonObject().getAsJsonObject("recipe");

            assertFalse(encodedRecipe.has("requiredInputTotals"));
            assertEquals(1, encodedRecipe.getAsJsonArray("inputs").size());
            assertEquals("machine constraints retained", encodedRecipe.getAsJsonArray("constraints").get(0).getAsString());
            assertEquals("PARTIAL", encodedRecipe.get("decodingStatus").getAsString());
            assertEquals("PARTIAL", json.get("coverage").getAsString());
        }
    }

    @Test
    void recipeTotalsKeepComponentVariantsSeparate() throws Exception {
        var plain = new Evidence.RecipeAlternative("item", "minecraft:iron_ingot", new Evidence.JsonObject(Map.of()), 1, Evidence.Consumption.CONSUMED);
        var damaged = new Evidence.RecipeAlternative("item", "minecraft:iron_ingot", new Evidence.JsonObject(Map.of("minecraft:damage",
                new Evidence.JsonNumber(java.math.BigDecimal.ONE))), 2, Evidence.Consumption.CONSUMED);
        var recipe = new Evidence.RecipePayload("fixture:recipe", List.of(new Evidence.RecipeSlot("plain", List.of(plain)),
                new Evidence.RecipeSlot("damaged", List.of(damaged))), List.of(), List.of(), List.of(), List.of(), Evidence.DecodingStatus.VERIFIED);
        var record = new Evidence.Record(new Evidence.Source(SCOPE, "native", "fixture", "recipe:components", 1,
                Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()),
                Evidence.RecordKind.RECIPE, "Recipe", new Evidence.JsonObject(Map.of()), List.of(), recipe);
        var tools = new PackSageTools(request -> CompletableFuture.completedFuture(new Evidence.Result(List.of(record),
                Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null)));

        var json = JsonParser.parseString(tools.execute(new ModelBackend.ToolCall("recipe", "search_recipe", "{}"), SCOPE)
                .toCompletableFuture().get().result().serializedResult()).getAsJsonObject();
        var totals = json.getAsJsonArray("records").get(0).getAsJsonObject().getAsJsonObject("recipe").getAsJsonArray("requiredInputTotals");

        assertEquals(2, totals.size());
        assertEquals(1, totals.get(0).getAsJsonObject().get("quantity").getAsInt());
        assertEquals(2, totals.get(1).getAsJsonObject().get("quantity").getAsInt());
        assertNotEquals(totals.get(0).getAsJsonObject().get("components"), totals.get(1).getAsJsonObject().get("components"));
    }

    @Test
    void dimensionSelectorsAreAdvertisedAndReachDispatch() throws Exception {
        var requests = new java.util.ArrayList<PackSageTools.ReadRequest>();
        var tools = new PackSageTools(request -> {
            requests.add(request);
            return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE,
                    Evidence.Outcome.COMPLETE, null));
        });
        var schema = JsonParser.parseString(PackSageTools.definitions().stream()
                .filter(value -> value.name().equals("search_registry")).findFirst().orElseThrow().serializedSchema()).getAsJsonObject();
        for (String registry : List.of("dimension", "dimension_type")) {
            assertTrue(schema.getAsJsonObject("properties").getAsJsonObject("registry").getAsJsonArray("enum")
                    .contains(new com.google.gson.JsonPrimitive(registry)));
            assertFalse(tools.execute(new ModelBackend.ToolCall(registry, "search_registry",
                    "{\"registry\":\"" + registry + "\",\"filter\":{\"ids\":[\"minecraft:overworld\"]}}"), SCOPE)
                    .toCompletableFuture().get().result().error());
        }
        assertEquals(2, requests.size());
    }

    @Test
    void validatesRegisteredMetadataBeforeDispatch() throws Exception {
        var calls = new AtomicInteger();
        var custom = new Toolbox.Metadata("ae2", List.of("network_doc"), false, List.of("channel"));
        var tools = new PackSageTools(request -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null)); }, List.of(custom));
        assertFalse(tools.execute(new ModelBackend.ToolCall("recipe", "search_recipe", "{}"), SCOPE).toCompletableFuture().get().result().error());
        assertFalse(tools.execute(new ModelBackend.ToolCall("registry", "search_registry", "{\"registry\":\"channel\"}"), SCOPE).toCompletableFuture().get().result().error());
        assertTrue(tools.execute(new ModelBackend.ToolCall("bad", "search_registry", "{\"registry\":\"item\"}"), SCOPE).toCompletableFuture().get().result().error());
        assertTrue(tools.execute(new ModelBackend.ToolCall("bad", "search_recipe", "{\"filter\":{\"unknown\":true}}"), SCOPE).toCompletableFuture().get().result().error());
        assertEquals(2, calls.get());
    }

    @Test
    void omittedOptionalArgumentsAreAcceptedAndRequiredArgumentsRemainRequired() throws Exception {
        var requests = new java.util.ArrayList<PackSageTools.ReadRequest>();
        var tools = new PackSageTools(request -> { requests.add(request); return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null)); });
        for (var call : List.of(new ModelBackend.ToolCall("items", "list_items", "{\"holder\":\"player\"}"),
                new ModelBackend.ToolCall("plan", "plan_recipe", "{\"identity\":\"minecraft:iron_ingot\",\"quantity\":1}"),
                new ModelBackend.ToolCall("recipe", "search_recipe", "{}"),
                new ModelBackend.ToolCall("registry", "search_registry", "{\"registry\":\"item\"}"),
                new ModelBackend.ToolCall("history", "search_history", "{\"text\":\"iron\"}"))) {
            assertFalse(tools.execute(call, SCOPE).toCompletableFuture().get().result().error(), call.name());
        }
        assertEquals(5, requests.size());
        assertTrue(tools.execute(new ModelBackend.ToolCall("missing", "plan_recipe", "{}"), SCOPE).toCompletableFuture().get().result().error());
        assertTrue(tools.execute(new ModelBackend.ToolCall("badid", "search_registry", "{\"registry\":\"item\",\"filter\":{\"ids\":[\"bad\"]}}"), SCOPE).toCompletableFuture().get().result().error());
        assertEquals(5, requests.size());
    }
    @Test
    void preservesExactScopeAndReturnsReadablePayloadWithSourceAndTargets() throws Exception {
        var requested = new AtomicReference<PackSageTools.ReadRequest>();
        var source = new Evidence.Source(SCOPE, "native", "1.21.1", "minecraft:torch", 1, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of("pack:override"));
        var target = new Evidence.NavigationTarget(SCOPE, "native", "minecraft:torch", "minecraft:torch");
        var record = new Evidence.Record(source, Evidence.RecordKind.RECIPE, "Torch",
                new Evidence.JsonObject(Map.of("count", new Evidence.JsonNumber(java.math.BigDecimal.valueOf(7)))), List.of(target));
        var tools = new PackSageTools(request -> {
            requested.set(request);
            return CompletableFuture.completedFuture(new Evidence.Result(List.of(record), Evidence.Coverage.COMPLETE,
                    Evidence.Outcome.COMPLETE, null));
        });

        var response = tools.execute(new ModelBackend.ToolCall("call", "search_recipe", "{\"filter\":{\"text\":\"torch\"}}"), SCOPE)
                .toCompletableFuture().get();

        assertEquals(SCOPE, requested.get().scope());
        assertFalse(response.result().error());
        assertEquals(List.of(record), response.records());
        var json = JsonParser.parseString(response.result().serializedResult()).getAsJsonObject();
        assertEquals(7, json.getAsJsonArray("records").get(0).getAsJsonObject().getAsJsonObject("payload").get("count").getAsInt());
        assertEquals("minecraft:torch", json.getAsJsonArray("records").get(0).getAsJsonObject()
                .getAsJsonObject("source").get("sourceHandle").getAsString());
        assertTrue(json.getAsJsonArray("records").get(0).getAsJsonObject().get("navigable").getAsBoolean());
    }

    @Test
    void serializesResultDetailSoTheModelCanCorrectItself() throws Exception {
        var tools = new PackSageTools(request -> CompletableFuture.completedFuture(new Evidence.Result(List.of(),
                Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null, "cursor does not match this query")));

        var response = tools.execute(new ModelBackend.ToolCall("a", "search_recipe", "{\"filter\":{\"text\":\"none\"}}"), SCOPE)
                .toCompletableFuture().get();

        assertTrue(response.result().error());
        var json = JsonParser.parseString(response.result().serializedResult()).getAsJsonObject();
        assertEquals("cursor does not match this query", json.get("detail").getAsString());

        var plain = new PackSageTools(request -> CompletableFuture.completedFuture(new Evidence.Result(List.of(),
                Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null)));
        var plainJson = JsonParser.parseString(plain.execute(new ModelBackend.ToolCall("b", "search_recipe", "{\"filter\":{\"text\":\"none\"}}"), SCOPE)
                .toCompletableFuture().get().result().serializedResult()).getAsJsonObject();
        assertFalse(plainJson.has("detail"));
    }

    @Test
    void marksRecordsWithoutTargetsAsNotNavigable() throws Exception {
        var record = new Evidence.Record(new Evidence.Source(SCOPE, "native", "fixture", "item:torch", 1,
                Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()),
                Evidence.RecordKind.ITEM, "Torch", new Evidence.JsonObject(Map.of()), List.of());
        var tools = new PackSageTools(request -> CompletableFuture.completedFuture(new Evidence.Result(List.of(record),
                Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null)));

        var json = JsonParser.parseString(tools.execute(new ModelBackend.ToolCall("a", "search_recipe", "{\"filter\":{\"text\":\"torch\"}}"), SCOPE)
                .toCompletableFuture().get().result().serializedResult()).getAsJsonObject();

        assertFalse(json.getAsJsonArray("records").get(0).getAsJsonObject().get("navigable").getAsBoolean());
    }

    @Test
    void providerStatusRecordsDoNotCountTowardTheRecordCap() throws Exception {
        var records = new java.util.ArrayList<Evidence.Record>();
        records.add(new Evidence.Record(new Evidence.Source(SCOPE, "fixture", "fixture", "status:fixture", 1, Instant.EPOCH,
                Evidence.Activity.UNKNOWN, Evidence.Coverage.UNAVAILABLE, Evidence.Authority.UNKNOWN, List.of()),
                Evidence.RecordKind.OTHER, "Fixture unavailable", new Evidence.JsonObject(Map.of()), List.of()));
        for (int index = 0; index < 64; index++) {
            records.add(new Evidence.Record(new Evidence.Source(SCOPE, "native", "fixture", "item:" + index, 1, Instant.EPOCH,
                    Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()),
                    Evidence.RecordKind.ITEM, "Item " + index, new Evidence.JsonObject(Map.of()), List.of()));
        }
        var tools = new PackSageTools(request -> CompletableFuture.completedFuture(new Evidence.Result(records,
                Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null)));

        var response = tools.execute(new ModelBackend.ToolCall("a", "search_recipe", "{\"filter\":{\"text\":\"item\"}}"), SCOPE)
                .toCompletableFuture().get();

        assertEquals(65, response.records().size());
        assertEquals("COMPLETE", JsonParser.parseString(response.result().serializedResult()).getAsJsonObject().get("outcome").getAsString());
    }

    @Test
    void distinguishesEmptyMatchesFromFailedExtractionAndRejectsForeignScope() throws Exception {
        var tools = new PackSageTools(request -> CompletableFuture.completedFuture(new Evidence.Result(List.of(),
                Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null)));
        assertFalse(tools.execute(new ModelBackend.ToolCall("a", "search_recipe", "{\"filter\":{\"text\":\"none\"}}"), SCOPE)
                .toCompletableFuture().get().result().error());

        var failed = new PackSageTools(request -> CompletableFuture.failedFuture(new IllegalStateException("unreadable")));
        assertTrue(failed.execute(new ModelBackend.ToolCall("b", "search_recipe", "{\"filter\":{\"text\":\"none\"}}"), SCOPE)
                .toCompletableFuture().get().result().error());

        var foreign = new Evidence.Source(new Evidence.Scope("other-world", "resources", "team", "runtime", 1),
                "native", "1.21.1", "torch", 1, Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE,
                Evidence.Authority.LOADED, List.of());
        var stale = new PackSageTools(request -> CompletableFuture.completedFuture(new Evidence.Result(List.of(
                new Evidence.Record(foreign, Evidence.RecordKind.ITEM, "Torch", new Evidence.JsonObject(Map.of()), List.of())),
                Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null)));
        assertTrue(stale.execute(new ModelBackend.ToolCall("c", "search_recipe", "{\"filter\":{\"text\":\"torch\"}}"), SCOPE)
                .toCompletableFuture().get().result().error());
    }
    @Test
    void outputEnvelopeCountsUtf8BytesAndPreservesTypedAdapterErrors() throws Exception {
        var record = new Evidence.Record(new Evidence.Source(SCOPE, "native", "fixture", "large", 1, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()),
                Evidence.RecordKind.ITEM, "Large", new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("\u754C".repeat(50000)))), List.of());
        var response = new PackSageTools(request -> CompletableFuture.completedFuture(new Evidence.Result(List.of(record),
                Evidence.Coverage.PARTIAL, Evidence.Outcome.PARTIAL, "after-hidden-record"))).execute(
                new ModelBackend.ToolCall("large", "search_registry", "{\"registry\":\"item\"}"), SCOPE).toCompletableFuture().get();
        assertTrue(response.result().serializedResult().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 131072);
        assertTrue(response.result().serializedResult().contains("PARTIAL"));
        var truncated = JsonParser.parseString(response.result().serializedResult()).getAsJsonObject();
        assertTrue(truncated.get("nextCursor").isJsonNull());
        assertFalse(truncated.has("nextCall"));
        var error = new PackSageTools(request -> CompletableFuture.failedFuture(new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "repeat lookup")))
                .execute(new ModelBackend.ToolCall("bad", "search_recipe", "{}"), SCOPE).toCompletableFuture().get();
        assertTrue(error.result().error());
        assertTrue(error.result().serializedResult().contains("INVALID_CURSOR"));
        assertTrue(error.result().serializedResult().contains("repeat lookup"));
    }

    @Test void registrySchemaOffersActiveEffect() {
        var registry = PackSageTools.definitions().stream().filter(tool -> tool.name().equals("search_registry")).findFirst().orElseThrow();
        assertTrue(registry.serializedSchema().contains("\"active_effect\""));
    }

    @Test void registrySchemaOffersPlayerAttribute() {
        var registry = PackSageTools.definitions().stream().filter(tool -> tool.name().equals("search_registry")).findFirst().orElseThrow();
        assertTrue(registry.serializedSchema().contains("\"player_attribute\""));
    }

    @Test void onlyItemToolsAcceptATooltipFilter() throws Exception {
        var definitions = PackSageTools.definitions();
        for (var name : List.of("list_items", "find_items")) {
            var definition = definitions.stream().filter(value -> value.name().equals(name)).findFirst().orElseThrow();
            var tooltip = JsonParser.parseString(definition.serializedSchema()).getAsJsonObject().getAsJsonObject("properties")
                    .getAsJsonObject("filter").getAsJsonObject("properties").getAsJsonObject("tooltip");
            assertEquals(JsonParser.parseString("{\"type\":[\"array\",\"null\"],\"maxItems\":32,\"items\":{\"type\":\"string\",\"maxLength\":256}}"), tooltip, name);
            assertTrue(definition.description().contains("filter.tooltip"), name);
            assertTrue(definition.description().contains("[\"Luck\"]"), name);
        }
        for (var name : List.of("list_blocks", "list_entities")) {
            var definition = definitions.stream().filter(value -> value.name().equals(name)).findFirst().orElseThrow();
            assertFalse(JsonParser.parseString(definition.serializedSchema()).getAsJsonObject().getAsJsonObject("properties")
                    .getAsJsonObject("filter").getAsJsonObject("properties").has("tooltip"), name);
        }
        var tools = new PackSageTools(request -> CompletableFuture.completedFuture(
                new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null)));
        assertFalse(tools.execute(new ModelBackend.ToolCall("items", "find_items", "{\"filter\":{\"tooltip\":[\"Luck\"]}}"), SCOPE)
                .toCompletableFuture().get().result().error());
        assertFalse(tools.execute(new ModelBackend.ToolCall("held", "list_items", "{\"holder\":\"player\",\"filter\":{\"tooltip\":[\"Luck\"]}}"), SCOPE)
                .toCompletableFuture().get().result().error());
        assertTrue(tools.execute(new ModelBackend.ToolCall("blocks", "list_blocks", "{\"filter\":{\"tooltip\":[\"Luck\"]}}"), SCOPE)
                .toCompletableFuture().get().result().error());
    }

    @Test void listsWorldTools() throws Exception {
        var expected = Map.of(
                "inspect_area", List.of(List.of("center", "radius"), List.<String>of()),
                "list_blocks", List.of(List.of("center", "radius", "filter", "cursor"), List.of("filter")),
                "list_entities", List.of(List.of("center", "radius", "filter", "cursor"), List.of("filter")),
                "inspect_block", List.of(List.of("place"), List.of("place")),
                "inspect_entity", List.of(List.of("id"), List.of("id")),
                "list_items", List.of(List.of("holder", "inventory", "filter", "cursor"), List.of("holder")),
                "inspect_item", List.of(List.of("path"), List.of("path")),
                "find_items", List.of(List.of("filter", "center", "radius", "includeNested", "cursor"), List.of("filter")));
        var definitions = PackSageTools.definitions();
        for (var tool : expected.entrySet()) {
            var definition = definitions.stream().filter(value -> value.name().equals(tool.getKey())).findFirst().orElseThrow();
            var schema = JsonParser.parseString(definition.serializedSchema()).getAsJsonObject();
            var properties = schema.getAsJsonObject("properties");
            assertEquals(java.util.Set.copyOf(tool.getValue().get(0)), properties.keySet(), tool.getKey());
            assertEquals(tool.getValue().get(1), schema.getAsJsonArray("required").asList().stream().map(value -> value.getAsString()).toList(), tool.getKey());
            assertFalse(definition.description().startsWith("Read current evidence for"), tool.getKey());
            if (properties.has("radius")) {
                assertEquals(1, properties.getAsJsonObject("radius").get("minimum").getAsInt());
                assertEquals(256, properties.getAsJsonObject("radius").get("maximum").getAsInt());
            }
        }
        var center = JsonParser.parseString(definitions.stream().filter(value -> value.name().equals("inspect_area")).findFirst().orElseThrow()
                .serializedSchema()).getAsJsonObject().getAsJsonObject("properties").getAsJsonObject("center");
        assertEquals(List.of("player", "entity", "block", "note"), center.getAsJsonObject("properties").getAsJsonObject("type")
                .getAsJsonArray("enum").asList().stream().map(value -> value.getAsString()).toList());
        var tools = new PackSageTools(request -> CompletableFuture.completedFuture(
                new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null)));
        assertTrue(tools.execute(new ModelBackend.ToolCall("far", "inspect_area", "{\"radius\":300}"), SCOPE).toCompletableFuture().get().result().error());
        assertFalse(tools.execute(new ModelBackend.ToolCall("near", "find_items",
                "{\"filter\":{\"ids\":[\"minecraft:stone\"]},\"center\":{\"type\":\"player\"},\"radius\":32,\"includeNested\":false}"), SCOPE)
                .toCompletableFuture().get().result().error());
    }

    @Test void listsProgressAndStatisticTools() throws Exception {
        var expected = Map.of(
                "list_progress", List.of(List.of("tree", "status", "text", "cursor"), List.<String>of()),
                "inspect_progress", List.of(List.of("id"), List.of("id")),
                "list_statistics", List.of(List.of("categories", "subjects", "cursor"), List.<String>of()));
        var definitions = PackSageTools.definitions();
        for (var tool : expected.entrySet()) {
            var definition = definitions.stream().filter(value -> value.name().equals(tool.getKey())).findFirst().orElseThrow();
            var schema = JsonParser.parseString(definition.serializedSchema()).getAsJsonObject();
            assertFalse(schema.get("additionalProperties").getAsBoolean(), tool.getKey());
            assertEquals(java.util.Set.copyOf(tool.getValue().get(0)), schema.getAsJsonObject("properties").keySet(), tool.getKey());
            assertEquals(tool.getValue().get(1), schema.getAsJsonArray("required").asList().stream().map(value -> value.getAsString()).toList(), tool.getKey());
            assertFalse(definition.description().startsWith("Read current evidence for"), tool.getKey());
        }
        var progress = JsonParser.parseString(definitions.stream().filter(value -> value.name().equals("list_progress")).findFirst().orElseThrow()
                .serializedSchema()).getAsJsonObject().getAsJsonObject("properties");
        assertEquals(JsonParser.parseString("{\"type\":[\"array\",\"null\"],\"maxItems\":5,\"items\":{\"type\":\"string\",\"enum\":[\"done\",\"in_progress\",\"available\",\"locked\",\"hidden\"]}}"), progress.get("status"));
        var statistics = definitions.stream().filter(value -> value.name().equals("list_statistics")).findFirst().orElseThrow();
        var properties = JsonParser.parseString(statistics.serializedSchema()).getAsJsonObject().getAsJsonObject("properties");
        assertEquals(JsonParser.parseString("[\"object\",\"null\"]"), properties.getAsJsonObject("subjects").get("type"));
        assertFalse(properties.getAsJsonObject("subjects").getAsJsonObject("properties").has("tooltip"));
        assertTrue(statistics.description().contains("categories together with subjects"));
        var tools = new PackSageTools(request -> CompletableFuture.completedFuture(
                new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null)));
        for (var call : List.of(new ModelBackend.ToolCall("progress", "list_progress", "{}"),
                new ModelBackend.ToolCall("inspect", "inspect_progress", "{\"id\":\"minecraft/minecraft:story/root\"}"),
                new ModelBackend.ToolCall("stats", "list_statistics", "{\"categories\":[\"mined\"],\"subjects\":{\"ids\":[\"minecraft:diamond_ore\"]}}"))) {
            assertFalse(tools.execute(call, SCOPE).toCompletableFuture().get().result().error(), call.name());
        }
    }

    @Test void progressStatusRejectsUnknownValues() throws Exception {
        var calls = new AtomicInteger();
        var tools = new PackSageTools(request -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null)); });
        assertTrue(tools.execute(new ModelBackend.ToolCall("finished", "list_progress", "{\"status\":[\"finished\"]}"), SCOPE).toCompletableFuture().get().result().error());
        assertEquals(0, calls.get());
    }

    @Test void listsWorldKnowledgeTools() {
        var expected = Map.of(
                "read_loot", List.of(List.of("table", "block", "entity", "place", "cursor"), List.<String>of()),
                "locate", List.of(List.of("biome", "structure", "center", "radius", "cursor"), List.<String>of()),
                "search_commands", List.of(List.of("text", "cursor"), List.<String>of()),
                "read_command", List.of(List.of("name", "cursor"), List.of("name")));
        var definitions = PackSageTools.definitions();
        for (var tool : expected.entrySet()) {
            var definition = definitions.stream().filter(value -> value.name().equals(tool.getKey())).findFirst().orElseThrow();
            var schema = JsonParser.parseString(definition.serializedSchema()).getAsJsonObject();
            assertEquals(java.util.Set.copyOf(tool.getValue().get(0)), schema.getAsJsonObject("properties").keySet(), tool.getKey());
            assertEquals(tool.getValue().get(1), schema.getAsJsonArray("required").asList().stream().map(value -> value.getAsString()).toList(), tool.getKey());
            assertFalse(definition.description().startsWith("Read current evidence for"), tool.getKey());
        }
        var locate = JsonParser.parseString(definitions.stream().filter(value -> value.name().equals("locate")).findFirst().orElseThrow()
                .serializedSchema()).getAsJsonObject().getAsJsonObject("properties");
        assertEquals(1, locate.getAsJsonObject("radius").get("minimum").getAsInt());
        assertEquals(512, locate.getAsJsonObject("radius").get("maximum").getAsInt());
        assertTrue("#minecraft:village".matches(locate.getAsJsonObject("structure").get("pattern").getAsString()));
    }
}
