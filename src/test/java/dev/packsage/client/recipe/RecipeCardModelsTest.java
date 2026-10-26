package dev.packsage.client.recipe;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RecipeCardModelsTest {
    private JsonObject record(String type, String shape, String inputs, String output) {
        return JsonParser.parseString("""
                {"reference":"native:minecraft:test","source":{},"kind":"RECIPE","title":"test",
                 "payload":{"inputs":%s,"outputs":[%s],"constraints":["recipeType=minecraft:%s"],
                 "decodingStatus":"VERIFIED"%s}}
                """.formatted(inputs, output, type, shape)).getAsJsonObject();
    }

    private String slot(String group, String type, String identity) {
        return """
                {"groupId":"%s","alternatives":[{"type":"%s","comparisonIdentity":"%s",
                "components":{},"quantity":1,"consumption":"CONSUMED"}]}
                """.formatted(group, type, identity);
    }

    private String output(String type, String identity, int count) {
        return """
                {"type":"%s","comparisonIdentity":"%s","components":{},"quantity":%d}
                """.formatted(type, identity, count);
    }

    @Test void shapedBeaconPlacesSlotsByIndex() {
        var card = RecipeCardModels.fromRecord(record("crafting", ",\"shape\":{\"width\":3,\"height\":3}",
                "[" + slot("slot-4", "item", "minecraft:nether_star") + "]", output("item", "minecraft:beacon", 2))).orElseThrow();
        assertEquals("Crafting table", card.caption());
        assertEquals(116, card.width());
        assertEquals(54, card.height());
        assertEquals("minecraft:nether_star", card.slots().get(4).stacks().getFirst().identity());
        assertEquals(19, card.slots().get(4).x());
        assertEquals(19, card.slots().get(4).y());
        assertEquals(2, card.slots().getLast().stacks().getFirst().count());
    }

    @Test void shapedTwoByTwoUsesJeiPlacement() {
        var card = RecipeCardModels.fromRecord(record("crafting", ",\"shape\":{\"width\":2,\"height\":2}",
                "[" + slot("slot-2", "item", "minecraft:stone") + "]", output("item", "minecraft:stone", 1))).orElseThrow();
        assertEquals("minecraft:stone", card.slots().get(3).stacks().getFirst().identity());
    }

    @Test void shapelessFillsInOrderAndCaptions() {
        var card = RecipeCardModels.fromRecord(record("crafting", ",\"shape\":\"shapeless\"",
                "[" + slot("slot-0", "item", "minecraft:stone") + "," + slot("slot-1", "item", "minecraft:dirt") + "]",
                output("item", "minecraft:stone", 1))).orElseThrow();
        assertEquals("Crafting table · shapeless", card.caption());
        assertEquals("minecraft:stone", card.slots().get(0).stacks().getFirst().identity());
        assertEquals("minecraft:dirt", card.slots().get(1).stacks().getFirst().identity());
    }

    @Test void smeltingShowsCookTimeAndExperience() {
        var record = record("smelting", "", "[" + slot("slot-0", "item", "minecraft:iron_ore") + "]", output("item", "minecraft:iron_ingot", 1));
        record.getAsJsonObject("payload").addProperty("cookingTime", 200);
        record.getAsJsonObject("payload").addProperty("experience", 0.1);
        var card = RecipeCardModels.fromRecord(record).orElseThrow();
        assertTrue(card.texts().contains(new RecipeCardModel.Text("10s", 82, 46, true)));
        assertTrue(card.texts().contains(new RecipeCardModel.Text("0.1 XP", 82, 0, true)));
    }

    @Test void blastingAndSmokingDifferOnlyByCaption() {
        var input = "[" + slot("slot-0", "item", "minecraft:stone") + "]";
        var blast = RecipeCardModels.fromRecord(record("blasting", "", input, output("item", "minecraft:stone", 1))).orElseThrow();
        var smoke = RecipeCardModels.fromRecord(record("smoking", "", input, output("item", "minecraft:stone", 1))).orElseThrow();
        assertEquals("Blast furnace", blast.caption());
        assertEquals("Smoker", smoke.caption());
        assertEquals(blast.slots(), smoke.slots());
    }

    @Test void smithingTransformHasThreeInputs() {
        var input = "[" + slot("slot-0", "item", "minecraft:stone") + "," + slot("slot-1", "item", "minecraft:iron_ingot")
                + "," + slot("slot-2", "item", "minecraft:diamond") + "]";
        var card = RecipeCardModels.fromRecord(record("smithing", "", input, output("item", "minecraft:diamond", 1))).orElseThrow();
        assertEquals(4, card.slots().size());
        assertEquals(1, card.slots().get(0).x());
        assertEquals(19, card.slots().get(1).x());
        assertEquals(37, card.slots().get(2).x());
        assertEquals(91, card.slots().get(3).x());
    }

    @Test void smithingTrimUnknownOutputIsUnknownStack() {
        var card = RecipeCardModels.fromRecord(record("smithing", "", "[]", output("unknown", "unknown:output:test", 1))).orElseThrow();
        assertEquals(RecipeCardModel.Stack.Kind.UNKNOWN, card.slots().getLast().stacks().getFirst().kind());
    }

    @Test void brewingUsesJeiLayout() {
        var record = record("crafting", "", "[" + slot("bottle", "item", "minecraft:potion") + ","
                + slot("reagent", "item", "minecraft:nether_wart") + "]", output("item", "minecraft:potion", 1));
        record.addProperty("reference", "native:brewing:potion:1:2:test");
        record.getAsJsonObject("source").addProperty("sourceHandle", "brewing:potion:1:2:test");
        record.getAsJsonObject("payload").getAsJsonArray("constraints").set(0, new com.google.gson.JsonPrimitive("brewing-batch=up-to-3-bottles"));
        var card = RecipeCardModels.fromRecord(record).orElseThrow();
        assertEquals(5, card.slots().size());
        assertEquals(java.util.List.of(1, 24, 47, 24, 81), card.slots().stream().map(RecipeCardModel.Slot::x).toList());
        assertEquals(java.util.List.of(37, 44, 37, 3, 3), card.slots().stream().map(RecipeCardModel.Slot::y).toList());
        assertTrue(card.texts().isEmpty());
    }

    @Test void tagAlternativeBecomesTagStack() {
        var card = RecipeCardModels.fromRecord(record("stonecutting", "", "[" + slot("slot-0", "tag", "#minecraft:stone") + "]", output("item", "minecraft:stone", 1))).orElseThrow();
        assertEquals(RecipeCardModel.Stack.Kind.TAG, card.slots().getFirst().stacks().getFirst().kind());
    }

    @Test void customAlternativeBecomesCustomStack() {
        var card = RecipeCardModels.fromRecord(record("stonecutting", "", "[" + slot("slot-0", "custom", "custom:test:0") + "]", output("item", "minecraft:stone", 1))).orElseThrow();
        assertEquals(RecipeCardModel.Stack.Kind.CUSTOM, card.slots().getFirst().stacks().getFirst().kind());
    }

    @Test void unknownDecodingHasNoCard() {
        var record = record("smelting", "", "[]", output("item", "minecraft:stone", 1));
        record.getAsJsonObject("payload").addProperty("decodingStatus", "UNKNOWN");
        assertTrue(RecipeCardModels.fromRecord(record).isEmpty());
    }

    @Test void unrecognizedRecipeTypeHasNoCard() {
        assertTrue(RecipeCardModels.fromRecord(record("other", "", "[]", output("item", "minecraft:stone", 1))).isEmpty());
    }

    @Test void truncatedPayloadHasNoCard() {
        var record = record("smelting", "", "[]", output("item", "minecraft:stone", 1));
        record.getAsJsonObject("payload").remove("inputs");
        assertTrue(RecipeCardModels.fromRecord(record).isEmpty());
        record.addProperty("payload", "[truncated]");
        assertTrue(RecipeCardModels.fromRecord(record).isEmpty());
    }
    @Test void cookingWithoutRecordedValuesHasNoTexts() {
        var card = RecipeCardModels.fromRecord(record("smelting", "", "[" + slot("slot-0", "item", "minecraft:stone") + "]", output("item", "minecraft:stone", 1))).orElseThrow();
        assertTrue(card.texts().isEmpty());
    }

    @Test void campfireAndStonecuttingUseResearchCoordinates() {
        var input = "[" + slot("slot-0", "item", "minecraft:stone") + "]";
        var campfire = RecipeCardModels.fromRecord(record("campfire_cooking", "", input, output("item", "minecraft:stone", 1))).orElseThrow();
        var stonecutting = RecipeCardModels.fromRecord(record("stonecutting", "", input, output("item", "minecraft:stone", 1))).orElseThrow();
        assertEquals(44, campfire.height());
        assertEquals(9, campfire.slots().getLast().y());
        assertEquals(34, stonecutting.height());
        assertEquals(9, stonecutting.slots().getFirst().y());
    }
    @Test void shapedOneByTwoKeepsBothIngredients() {
        var card = RecipeCardModels.fromRecord(record("crafting", ",\"shape\":{\"width\":1,\"height\":2}",
                "[" + slot("slot-0", "item", "minecraft:stone") + "," + slot("slot-1", "item", "minecraft:dirt") + "]",
                output("item", "minecraft:stone", 1))).orElseThrow();
        assertEquals("minecraft:stone", card.slots().get(1).stacks().getFirst().identity());
        assertEquals("minecraft:dirt", card.slots().get(4).stacks().getFirst().identity());
    }

    @Test void brewingBatchSurvivesPlanAndAliasedReferences() {
        var record = record("crafting", "", "[]", output("item", "minecraft:potion", 1));
        record.getAsJsonObject("payload").getAsJsonArray("constraints").set(0, new com.google.gson.JsonPrimitive("brewing-batch=up-to-3-bottles"));
        record.addProperty("reference", "native:plan|minecraft:potion|1|3|0|brewing:potion:1:2:test");
        record.getAsJsonObject("source").addProperty("sourceHandle", "plan|minecraft:potion|1|3|0|brewing:potion:1:2:test");
        assertEquals(RecipeCardModel.Category.BREWING, RecipeCardModels.fromRecord(record).orElseThrow().category());
        record.addProperty("reference", "ref:b1");
        record.getAsJsonObject("source").remove("sourceHandle");
        assertEquals(RecipeCardModel.Category.BREWING, RecipeCardModels.fromRecord(record).orElseThrow().category());
    }

    @Test void brewingSourceHandleIsFallbackWithoutBatchConstraint() {
        var record = record("crafting", "", "[]", output("item", "minecraft:potion", 1));
        record.getAsJsonObject("payload").getAsJsonArray("constraints").remove(0);
        record.addProperty("reference", "ref:b1");
        record.getAsJsonObject("source").addProperty("sourceHandle", "plan|minecraft:potion|1|3|0|brewing:potion:1:2:test");
        assertEquals(RecipeCardModel.Category.BREWING, RecipeCardModels.fromRecord(record).orElseThrow().category());
    }

    @Test void planCraftingRecordIsNotBrewing() {
        var record = record("crafting", ",\"shape\":\"shapeless\"", "[]", output("item", "minecraft:stone", 1));
        record.addProperty("reference", "native:plan|minecraft:stone|1|3|0|minecraft:stone");
        record.getAsJsonObject("source").addProperty("sourceHandle", "plan|minecraft:stone|1|3|0|minecraft:stone");
        assertEquals(RecipeCardModel.Category.CRAFTING_SHAPELESS, RecipeCardModels.fromRecord(record).orElseThrow().category());
    }

    @Test void malformedCookingValuesOnlyOmitTheirOwnText() {
        var record = record("smelting", "", "[]", output("item", "minecraft:stone", 1));
        record.getAsJsonObject("payload").addProperty("cookingTime", "bad");
        record.getAsJsonObject("payload").addProperty("experience", 0.1);
        assertEquals(java.util.List.of(new RecipeCardModel.Text("0.1 XP", 82, 0, true)), RecipeCardModels.fromRecord(record).orElseThrow().texts());
        record.getAsJsonObject("payload").addProperty("cookingTime", 200);
        record.getAsJsonObject("payload").add("experience", new JsonObject());
        assertEquals(java.util.List.of(new RecipeCardModel.Text("10s", 82, 46, true)), RecipeCardModels.fromRecord(record).orElseThrow().texts());
    }

    @Test void nullOrObjectReferenceDoesNotDropCraftingCard() {
        var record = record("crafting", ",\"shape\":\"shapeless\"", "[]", output("item", "minecraft:stone", 1));
        record.add("reference", com.google.gson.JsonNull.INSTANCE);
        assertEquals(RecipeCardModel.Category.CRAFTING_SHAPELESS, RecipeCardModels.fromRecord(record).orElseThrow().category());
        record.add("reference", new JsonObject());
        assertEquals(RecipeCardModel.Category.CRAFTING_SHAPELESS, RecipeCardModels.fromRecord(record).orElseThrow().category());
    }

    @Test void customAndUnknownDescriptionsAreReadable() {
        var record = record("stonecutting", "", "[" + slot("slot-0", "custom", "custom:example:matcher:0") + "]", output("unknown", "unknown:output:test", 1));
        var alternative = record.getAsJsonObject("payload").getAsJsonArray("inputs").get(0).getAsJsonObject().getAsJsonArray("alternatives").get(0).getAsJsonObject();
        alternative.getAsJsonObject("components").addProperty("matcherType", "example:matcher");
        var card = RecipeCardModels.fromRecord(record).orElseThrow();
        assertEquals("example:matcher", card.slots().getFirst().stacks().getFirst().description());
        assertEquals("unknown:output:test", card.slots().getLast().stacks().getFirst().description());
        alternative.getAsJsonObject("components").remove("matcherType");
        assertEquals("custom:example:matcher:0", RecipeCardModels.fromRecord(record).orElseThrow().slots().getFirst().stacks().getFirst().description());
    }

}
