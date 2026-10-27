package dev.packsage.jei;

import com.google.gson.JsonParser;
import dev.packsage.knowledge.Evidence;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JeiProjectionTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "jei", 4);
    private static final Instant NOW = Instant.parse("2026-10-27T00:00:00Z");

    @Test
    void rolesMapToPayloadLists() {
        var layout = new JeiLayout("naturesaura:altar", "Altar", "mod:recipe", 3, 7, false, true, null, List.of(
                new JeiLayout.Slot(0, JeiLayout.Role.INPUT, List.of(new JeiLayout.Ingredient("item", "mod:input", "{\"foo\":1}", 2, null)), List.of()),
                new JeiLayout.Slot(1, JeiLayout.Role.OUTPUT, List.of(new JeiLayout.Ingredient("item", "mod:output", "{}", 3, null)), List.of()),
                new JeiLayout.Slot(2, JeiLayout.Role.CATALYST, List.of(new JeiLayout.Ingredient("item", "mod:tool", "{}", 1, null)), List.of()),
                new JeiLayout.Slot(3, JeiLayout.Role.RENDER_ONLY, List.of(new JeiLayout.Ingredient("item", "mod:decoration", "{}", 1, null)), List.of())), List.of("recipe text"));
        var record = JeiProjection.record(layout, SCOPE, "19.27", NOW);
        assertEquals(Evidence.RecordKind.RECIPE, record.kind());
        assertEquals("jei", record.source().providerId());
        assertEquals("19.27", record.source().providerVersion());
        assertEquals(7, record.source().observedGeneration());
        assertEquals(NOW, record.source().observedAt());
        assertEquals("slot-0", record.recipePayload().inputs().getFirst().groupId());
        assertEquals("mod:output", record.recipePayload().outputs().getFirst().comparisonIdentity());
        assertEquals("mod:tool", record.recipePayload().catalysts().getFirst().comparisonIdentity());
        var payload = JsonParser.parseString(Evidence.serialize(record.payload())).getAsJsonObject();
        assertEquals(1, payload.getAsJsonArray("inputs").size());
        assertEquals(1, payload.getAsJsonArray("outputs").size());
        assertEquals(1, payload.getAsJsonArray("catalysts").size());
        assertEquals(1, payload.getAsJsonArray("possibleExtras").size());
        assertEquals("Altar", payload.get("category").getAsString());
        assertEquals("recipe text", payload.getAsJsonArray("recipeTooltip").get(0).getAsString());
        assertEquals(1, payload.getAsJsonArray("inputs").get(0).getAsJsonObject().getAsJsonArray("alternatives").get(0).getAsJsonObject().getAsJsonObject("components").get("foo").getAsInt());
        assertEquals(new Evidence.NavigationTarget(SCOPE, "jei", "naturesaura:altar|mod:recipe", "recipe"), record.targets().getFirst());
    }

    @Test
    void brewingCategoryCarriesSharedBatchConstraint() {
        var brewing = JeiProjection.record(new JeiLayout("minecraft:brewing", "Brewing", null, 3, 7, false, true, null, List.of(), List.of()), SCOPE, "19.27", NOW);
        var other = JeiProjection.record(new JeiLayout("naturesaura:altar", "Altar", "mod:recipe", 3, 7, false, true, null, List.of(), List.of()), SCOPE, "19.27", NOW);
        assertTrue(brewing.recipePayload().constraints().contains(dev.packsage.knowledge.RecipePlanner.BREWING_BATCH_CONSTRAINT));
        assertTrue(JsonParser.parseString(Evidence.serialize(brewing.payload())).getAsJsonObject().getAsJsonArray("constraints").toString().contains("brewing-batch="));
        assertTrue(other.recipePayload().constraints().stream().noneMatch(value -> value.startsWith("brewing-batch=")));
    }

    @Test
    void fluidAlternativeKeepsMillibuckets() {
        var record = JeiProjection.record(new JeiLayout("naturesaura:altar", "Title", "mod:recipe", 3, 7, false, true, null, List.of(new JeiLayout.Slot(0, JeiLayout.Role.INPUT,
                List.of(new JeiLayout.Ingredient("fluid", "minecraft:water", "{}", 1000, null)), List.of())), List.of()), SCOPE, "19.27", NOW);
        assertEquals("fluid", record.recipePayload().inputs().getFirst().alternatives().getFirst().type());
        assertEquals(1000, record.recipePayload().inputs().getFirst().alternatives().getFirst().quantity());
    }

    @Test
    void modCategoryIsPartialWithAssumedConsumption() {
        var record = JeiProjection.record(new JeiLayout("naturesaura:altar", "Title", "mod:recipe", 3, 7, false, true, null, List.of(new JeiLayout.Slot(0, JeiLayout.Role.INPUT, List.of(new JeiLayout.Ingredient("item", "mod:input", "{}", 1, null)), List.of()),
                new JeiLayout.Slot(1, JeiLayout.Role.CATALYST, List.of(new JeiLayout.Ingredient("item", "mod:tool", "{}", 1, null)), List.of())), List.of()), SCOPE, "19.27", NOW);
        assertEquals(Evidence.Coverage.PARTIAL, record.source().coverage());
        assertEquals(Evidence.Consumption.CONSUMED, record.recipePayload().inputs().getFirst().alternatives().getFirst().consumption());
        assertEquals(Evidence.Consumption.REUSED, record.recipePayload().catalysts().getFirst().consumption());
        assertTrue(record.recipePayload().constraints().containsAll(List.of("consumption=assumed", "output=assumed-guaranteed", "recipeType=naturesaura:altar", "not-read=values drawn by the category")));
    }

    @Test
    void containerInputReturnsContainer() {
        var record = JeiProjection.record(new JeiLayout("naturesaura:altar", "Title", "mod:recipe", 3, 7, false, true, null, List.of(new JeiLayout.Slot(0, JeiLayout.Role.INPUT,
                List.of(new JeiLayout.Ingredient("item", "minecraft:water_bucket", "{}", 1, "minecraft:bucket")), List.of())), List.of()), SCOPE, "19.27", NOW);
        assertEquals("minecraft:bucket", record.recipePayload().remainders().getFirst().comparisonIdentity());
        assertTrue(record.recipePayload().constraints().contains("container-returned=minecraft:water_bucket>minecraft:bucket"));
    }

    @Test
    void vanillaCraftingIsComplete() {
        var record = JeiProjection.record(new JeiLayout("minecraft:crafting", "Title", "mod:recipe", 3, 7, false, true, Evidence.DecodingStatus.VERIFIED, List.of(new JeiLayout.Slot(0, JeiLayout.Role.INPUT, List.of(new JeiLayout.Ingredient("item", "minecraft:stone", "{}", 1, null)), List.of()),
                new JeiLayout.Slot(1, JeiLayout.Role.OUTPUT, List.of(new JeiLayout.Ingredient("item", "minecraft:button", "{}", 1, null)), List.of())), List.of()), SCOPE, "19.27", NOW);
        assertEquals(Evidence.Coverage.COMPLETE, record.source().coverage());
        assertFalse(record.recipePayload().constraints().contains("consumption=assumed"));
        assertFalse(record.recipePayload().constraints().contains("output=assumed-guaranteed"));
    }

    @Test
    void smithingKeepsNativePartialCoverage() {
        var record = JeiProjection.record(new JeiLayout("minecraft:smithing", "Smithing", "minecraft:smithing", 0, 7, false, true,
                null, List.of(new JeiLayout.Slot(0, JeiLayout.Role.OUTPUT, List.of(new JeiLayout.Ingredient("item", "minecraft:diamond_sword", "{}", 1, null)), List.of())), List.of()), SCOPE, "19.27", NOW);
        assertEquals(Evidence.Coverage.PARTIAL, record.source().coverage());
    }
    @Test
    void vanillaWithoutNativeDecodingIsPartial() {
        var record = JeiProjection.record(new JeiLayout("minecraft:crafting", "Crafting", "minecraft:test", 0, 7, false, true, null,
                List.of(new JeiLayout.Slot(0, JeiLayout.Role.OUTPUT, List.of(new JeiLayout.Ingredient("item", "minecraft:stone", "{}", 1, null)), List.of())), List.of()), SCOPE, "19.27", NOW);
        assertEquals(Evidence.Coverage.PARTIAL, record.source().coverage());
        assertEquals(Evidence.DecodingStatus.PARTIAL, record.recipePayload().decodingStatus());
    }

    @Test
    void vanillaKeepsNativeOpaque() {
        var record = JeiProjection.record(new JeiLayout("minecraft:crafting", "Crafting", "minecraft:test", 0, 7, false, true, Evidence.DecodingStatus.OPAQUE,
                List.of(new JeiLayout.Slot(0, JeiLayout.Role.OUTPUT, List.of(new JeiLayout.Ingredient("item", "minecraft:stone", "{}", 1, null)), List.of())), List.of()), SCOPE, "19.27", NOW);
        assertEquals(Evidence.Coverage.OPAQUE, record.source().coverage());
        assertEquals(Evidence.DecodingStatus.OPAQUE, record.recipePayload().decodingStatus());
    }

    @Test
    void unrepresentableFluidQuantitiesAreMarked() {
        var record = JeiProjection.record(new JeiLayout("naturesaura:altar", "Altar", "mod:recipe", 0, 7, false, true, null,
                List.of(new JeiLayout.Slot(2, JeiLayout.Role.INPUT, List.of(new JeiLayout.Ingredient("fluid", "mod:fluid", "{}", 0, null)), List.of()),
                        new JeiLayout.Slot(3, JeiLayout.Role.OUTPUT, List.of(new JeiLayout.Ingredient("fluid", "mod:fluid", "{}", (long) Integer.MAX_VALUE + 1, null)), List.of())), List.of()), SCOPE, "19.27", NOW);
        assertNull(record.recipePayload().inputs().getFirst().alternatives().getFirst().quantity());
        assertNull(record.recipePayload().outputs().getFirst().quantity());
        assertTrue(record.recipePayload().constraints().contains("quantity-unrepresentable:slot-2"));
        assertTrue(record.recipePayload().constraints().contains("quantity-unrepresentable:slot-3"));
    }

    @Test
    void nativeDecodingRespectsReadabilityAndCategory() {
        for (var status : List.of(Evidence.DecodingStatus.PARTIAL, Evidence.DecodingStatus.UNKNOWN)) {
            var record = JeiProjection.record(new JeiLayout("minecraft:crafting", "Crafting", "minecraft:test", 0, 7, false, true, status,
                    List.of(), List.of()), SCOPE, "19.27", NOW);
            assertEquals(status, record.recipePayload().decodingStatus());
            assertEquals(Evidence.Coverage.PARTIAL, record.source().coverage());
        }
        var unreadable = JeiProjection.record(new JeiLayout("minecraft:crafting", "Crafting", "minecraft:test", 0, 7, false, false,
                Evidence.DecodingStatus.VERIFIED, List.of(), List.of()), SCOPE, "19.27", NOW);
        assertEquals(Evidence.DecodingStatus.OPAQUE, unreadable.recipePayload().decodingStatus());
        var modded = JeiProjection.record(new JeiLayout("naturesaura:altar", "Altar", "mod:test", 0, 7, false, true,
                Evidence.DecodingStatus.VERIFIED, List.of(), List.of()), SCOPE, "19.27", NOW);
        assertEquals(Evidence.DecodingStatus.PARTIAL, modded.recipePayload().decodingStatus());
        assertEquals(Evidence.Coverage.PARTIAL, modded.source().coverage());
    }
    @Test
    void hiddenRecipeIsMarked() {
        assertTrue(JeiProjection.record(new JeiLayout("minecraft:crafting", "Title", "mod:recipe", 3, 7, true, true, null, List.of(), List.of()), SCOPE, "19.27", NOW).recipePayload().constraints().contains("hidden"));
    }

    @Test
    void chanceTooltipIsKeptVerbatim() {
        var record = JeiProjection.record(new JeiLayout("naturesaura:altar", "Title", "mod:recipe", 3, 7, false, true, null, List.of(new JeiLayout.Slot(2, JeiLayout.Role.OUTPUT, List.of(new JeiLayout.Ingredient("item", "mod:output", "{}", 1, null)), List.of("25% Chance", "no chance")),
                new JeiLayout.Slot(3, JeiLayout.Role.OUTPUT, List.of(new JeiLayout.Ingredient("item", "mod:other", "{}", 1, null)), List.of("ordinary"))), List.of()), SCOPE, "19.27", NOW);
        assertTrue(record.recipePayload().constraints().contains("chance:slot-2=25% Chance"));
        assertFalse(record.recipePayload().constraints().stream().anyMatch(value -> value.startsWith("chance:slot-3=")));
    }

    @Test
    void unreadableLayoutIsOpaque() {
        var record = JeiProjection.record(new JeiLayout("naturesaura:altar", "Title", "mod:recipe", 3, 7, false, false, null, List.of(), List.of()), SCOPE, "19.27", NOW);
        assertEquals(Evidence.Coverage.OPAQUE, record.source().coverage());
        assertEquals(Evidence.DecodingStatus.OPAQUE, record.recipePayload().decodingStatus());
    }

    @Test
    void missingRecipeIdGetsTemporaryHandle() {
        var layout = new JeiLayout("naturesaura:altar", "Altar", null, 3, 7, false, true, null, List.of(), List.of());
        var record = JeiProjection.record(layout, SCOPE, "19.27", NOW);
        assertEquals("naturesaura:altar|#3@7", JeiProjection.handle(layout));
        assertNull(record.recipePayload().canonicalRecipeId());
        assertEquals("temporary", record.targets().getFirst().targetId());
        assertEquals(new JeiProjection.Handle("naturesaura:altar", null, 3, 7L), JeiProjection.parseHandle(JeiProjection.handle(layout)).orElseThrow());
        assertEquals(new JeiProjection.Handle("naturesaura:altar", "mod:recipe", null, null), JeiProjection.parseHandle("naturesaura:altar|mod:recipe").orElseThrow());
        assertTrue(JeiProjection.parseHandle("naturesaura:altar|#bad@7").isEmpty());
    }

}
