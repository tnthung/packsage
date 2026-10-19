package dev.packsage.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.packsage.chat.ChatContracts;
import dev.packsage.knowledge.Evidence;
import dev.packsage.model.ModelBackend;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceDetailsScreenTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "resources", "player", "runtime", 1);
    private static final Evidence.Source SOURCE = new Evidence.Source(SCOPE, "config", "1", "common#general.range", 1,
            Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.PARTIAL, Evidence.Authority.LOADED,
            List.of("config:fixture", "view:saved-and-loaded"));

    @Test
    void recordedValuesRequireTheExactSourceScopeAndObservation() {
        JsonObject source = new JsonObject();
        source.addProperty("providerId", SOURCE.providerId());
        source.addProperty("sourceHandle", SOURCE.sourceHandle());
        source.addProperty("observedAt", SOURCE.observedAt().toString());
        source.addProperty("observedGeneration", SOURCE.observedGeneration());
        source.add("scope", new Gson().toJsonTree(SCOPE));
        JsonObject payload = new JsonObject();
        payload.addProperty("fileName", "fixture-common.toml");
        payload.addProperty("key", "general.range");
        payload.addProperty("savedValue", 24);
        payload.addProperty("savedState", "observed");
        payload.addProperty("loadedValue", 25);
        payload.addProperty("loadedState", "observed");
        JsonObject record = new JsonObject();
        record.add("source", source);
        record.add("payload", payload);
        JsonArray records = new JsonArray();
        records.add(record);
        JsonObject result = new JsonObject();
        result.add("records", records);
        var turn = new ChatContracts.Turn(new ChatContracts.RequestId("request"), "question", "answer",
                List.of(new ModelBackend.ToolResult("call", result.toString(), false)), new ChatContracts.Context("{}", null, SCOPE),
                List.of(SOURCE), new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1);
        var details = SourceDetailsScreen.details(SOURCE, turn);
        assertTrue(details.contains("File: fixture-common.toml"));
        assertTrue(details.contains("Saved value: 24"));
        assertTrue(details.contains("Loaded value: 25"));

        source.add("scope", new Gson().toJsonTree(new Evidence.Scope("other", "resources", "player", "runtime", 1)));
        var unrelated = new ChatContracts.Turn(turn.requestId(), turn.question(), turn.answer(),
                List.of(new ModelBackend.ToolResult("call", result.toString(), false)), turn.context(), turn.sources(), turn.outcome(), 1);
        assertTrue(SourceDetailsScreen.details(SOURCE, unrelated).contains("Value snapshot unavailable in this session."));
        assertThrows(IllegalArgumentException.class, () -> SourceDetailsScreen.details(
                new Evidence.Source(SCOPE, "config", "1", "unregistered", 1, Instant.EPOCH, Evidence.Activity.ACTIVE,
                        Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()), turn));
    }

    @Test
    void missingReplayKeepsMetadataAndBackPreservesTheLivePausePolicy() {
        var turn = new ChatContracts.Turn(new ChatContracts.RequestId("request"), "question", "answer", List.of(),
                new ChatContracts.Context("{}", null, SCOPE), List.of(SOURCE),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1);
        AtomicBoolean pause = new AtomicBoolean(true);
        AtomicInteger returned = new AtomicInteger();
        var screen = new SourceDetailsScreen(SOURCE, turn, returned::incrementAndGet, pause::get);
        assertTrue(screen.isPauseScreen());
        pause.set(false);
        assertFalse(screen.isPauseScreen());
        assertTrue(SourceDetailsScreen.details(SOURCE, turn).contains("Source: common#general.range"));
        screen.onClose();
        assertEquals(1, returned.get());
    }

    @Test
    void brewingDetailsKeepKnownFactsWhenAnAlternativeQuantityIsUnknown() {
        Evidence.Source brewing = new Evidence.Source(SCOPE, "native", "native", "brewing:fixture", 4,
                Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.PARTIAL, Evidence.Authority.LOADED,
                List.of("client-recipe-manager"));
        JsonObject payload = new JsonObject();
        JsonArray outputs = new JsonArray();
        JsonObject output = new JsonObject();
        output.addProperty("comparisonIdentity", "minecraft:potion");
        output.addProperty("quantity", 1);
        outputs.add(output);
        payload.add("outputs", outputs);
        JsonArray inputs = new JsonArray();
        JsonObject input = new JsonObject();
        JsonArray alternatives = new JsonArray();
        JsonObject alternative = new JsonObject();
        alternative.addProperty("comparisonIdentity", "minecraft:blaze_powder");
        alternative.addProperty("type", "item");
        alternative.add("quantity", com.google.gson.JsonNull.INSTANCE);
        alternatives.add(alternative);
        input.add("alternatives", alternatives);
        inputs.add(input);
        JsonObject flatInput = new JsonObject();
        flatInput.addProperty("comparisonIdentity", "minecraft:water");
        flatInput.addProperty("type", "fluid");
        flatInput.add("quantity", com.google.gson.JsonNull.INSTANCE);
        inputs.add(flatInput);
        payload.add("inputs", inputs);
        var turn = new ChatContracts.Turn(new ChatContracts.RequestId("request"), "question", "answer",
                List.of(new ModelBackend.ToolResult("brewing", nativeResult(brewing, payload), false)),
                new ChatContracts.Context("{}", null, SCOPE), List.of(brewing),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1);

        List<String> details = SourceDetailsScreen.details(brewing, turn);
        assertTrue(details.contains("Outputs:"));
        assertTrue(details.contains("  Potion ×1"));
        assertTrue(details.contains("Inputs:"));
        assertTrue(details.contains("  Blaze Powder ×?"));
        assertTrue(details.contains("  Water ×?"));
        assertFalse(details.contains("Value snapshot unavailable in this session."));
    }

    @Test
    void nativeDetailsUseOnlyTheExactRecordedInventoryAndRecipePayloads() {
        Evidence.Source inventory = new Evidence.Source(SCOPE, "native", "native", "inventory:observation:hotbar:0", 4,
                Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED,
                List.of("player-inventory", "hotbar:0"));
        Evidence.Source recipe = new Evidence.Source(SCOPE, "native", "native", "minecraft:stick", 4,
                Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.PARTIAL, Evidence.Authority.LOADED,
                List.of("client-recipe-manager"));
        JsonObject inventoryPayload = new JsonObject();
        inventoryPayload.addProperty("id", "minecraft:coal");
        inventoryPayload.addProperty("name", "Coal");
        inventoryPayload.addProperty("count", 64);
        inventoryPayload.addProperty("section", "hotbar");
        inventoryPayload.addProperty("slot", 0);
        JsonObject recipePayload = new JsonObject();
        recipePayload.addProperty("canonicalRecipeId", "minecraft:stick");
        JsonArray outputs = new JsonArray();
        JsonObject output = new JsonObject();
        output.addProperty("comparisonIdentity", "minecraft:stick");
        output.addProperty("quantity", 4);
        outputs.add(output);
        recipePayload.add("outputs", outputs);
        JsonArray inputs = new JsonArray();
        JsonObject input = new JsonObject();
        input.addProperty("groupId", "slot-0");
        JsonArray alternatives = new JsonArray();
        JsonObject alternative = new JsonObject();
        alternative.addProperty("comparisonIdentity", "minecraft:oak_planks");
        alternative.addProperty("type", "item");
        alternative.addProperty("quantity", 1);
        alternatives.add(alternative);
        JsonObject secondAlternative = new JsonObject();
        secondAlternative.addProperty("comparisonIdentity", "minecraft:birch_planks");
        secondAlternative.addProperty("type", "item");
        secondAlternative.addProperty("quantity", 1);
        alternatives.add(secondAlternative);
        input.add("alternatives", alternatives);
        inputs.add(input);
        recipePayload.add("inputs", inputs);
        var turn = new ChatContracts.Turn(new ChatContracts.RequestId("request"), "question", "answer",
                List.of(new ModelBackend.ToolResult("inventory", nativeResult(inventory, inventoryPayload), false),
                        new ModelBackend.ToolResult("recipe", nativeResult(recipe, recipePayload), false)),
                new ChatContracts.Context("{}", null, SCOPE), List.of(inventory, recipe),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1);

        List<String> inventoryDetails = SourceDetailsScreen.details(inventory, turn);
        assertTrue(inventoryDetails.contains("Item: Coal"));
        assertTrue(inventoryDetails.contains("Count: 64"));
        assertTrue(inventoryDetails.contains("Location: Hotbar slot 1"));
        List<String> recipeDetails = SourceDetailsScreen.details(recipe, turn);
        assertTrue(recipeDetails.contains("Outputs:"));
        assertTrue(recipeDetails.contains("  Stick ×4"));
        assertTrue(recipeDetails.contains("Inputs:"));
        assertTrue(recipeDetails.contains("  Oak Planks ×1 / Birch Planks ×1"));
        assertFalse(String.join(" ", recipeDetails).contains("comparisonIdentity"));
        assertTrue(recipeDetails.contains("Coverage is partial; this snapshot may omit information."));

        Evidence.Source wrongGeneration = new Evidence.Source(SCOPE, "native", "native", recipe.sourceHandle(), 5,
                Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.PARTIAL, Evidence.Authority.LOADED,
                recipe.provenance());
        var mismatched = new ChatContracts.Turn(turn.requestId(), turn.question(), turn.answer(), turn.replayItems(),
                turn.context(), List.of(wrongGeneration), turn.outcome(), 1);
        assertTrue(SourceDetailsScreen.details(wrongGeneration, mismatched)
                .contains("Value snapshot unavailable in this session."));
        Evidence.Source wrongTime = new Evidence.Source(SCOPE, "native", "native", recipe.sourceHandle(), 4,
                Instant.EPOCH.plusSeconds(1), Evidence.Activity.ACTIVE, Evidence.Coverage.PARTIAL,
                Evidence.Authority.LOADED, recipe.provenance());
        assertTrue(SourceDetailsScreen.details(wrongTime, new ChatContracts.Turn(turn.requestId(), turn.question(),
                turn.answer(), turn.replayItems(), turn.context(), List.of(wrongTime), turn.outcome(), 1))
                .contains("Value snapshot unavailable in this session."));
        JsonObject malformed = new JsonObject();
        malformed.addProperty("name", "Coal");
        malformed.add("count", new JsonObject());
        var malformedTurn = new ChatContracts.Turn(turn.requestId(), turn.question(), turn.answer(),
                List.of(new ModelBackend.ToolResult("malformed", nativeResult(inventory, malformed), false)),
                turn.context(), List.of(inventory), turn.outcome(), 1);
        List<String> malformedDetails = SourceDetailsScreen.details(inventory, malformedTurn);
        assertTrue(malformedDetails.contains("Value snapshot unavailable in this session."));
        assertFalse(malformedDetails.contains("Item: Coal"));
        assertThrows(IllegalArgumentException.class, () -> SourceDetailsScreen.details(recipe,
                new ChatContracts.Turn(turn.requestId(), turn.question(), turn.answer(), turn.replayItems(),
                        turn.context(), List.of(), turn.outcome(), 1)));
    }

    private static String nativeResult(Evidence.Source source, JsonObject payload) {
        JsonObject observed = new JsonObject();
        observed.addProperty("providerId", source.providerId());
        observed.addProperty("sourceHandle", source.sourceHandle());
        observed.addProperty("observedAt", source.observedAt().toString());
        observed.addProperty("observedGeneration", source.observedGeneration());
        observed.add("scope", new Gson().toJsonTree(source.scope()));
        JsonObject record = new JsonObject();
        record.add("source", observed);
        record.add("payload", payload);
        JsonArray records = new JsonArray();
        records.add(record);
        JsonObject result = new JsonObject();
        result.add("records", records);
        return result.toString();
    }
}
