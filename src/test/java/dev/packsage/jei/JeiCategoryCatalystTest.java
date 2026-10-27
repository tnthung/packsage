package dev.packsage.jei;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.RecipePlanner;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JeiCategoryCatalystTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "jei", 4);

    private static JeiLayout.Ingredient item(String id) {
        return new JeiLayout.Ingredient("item", id, "{}", 1, null);
    }

    private static List<JeiLayout.Slot> goldPowderSlots() {
        // Nature's Aura draws the crushing catalyst in the recipe and registers the altar only as the category catalyst.
        return List.of(new JeiLayout.Slot(0, JeiLayout.Role.INPUT, List.of(item("minecraft:glowstone_dust")), List.of()),
                new JeiLayout.Slot(1, JeiLayout.Role.CATALYST, List.of(item("naturesaura:crushing_catalyst")), List.of()),
                new JeiLayout.Slot(2, JeiLayout.Role.OUTPUT, List.of(item("naturesaura:gold_powder")), List.of()));
    }

    @Test
    void categoryMachineAbsentFromTheDrawnSlotsBecomesOneCatalystSlot() {
        var slots = JeiRuntimeIndex.withCategoryCatalysts(goldPowderSlots(),
                List.of(item("naturesaura:nature_altar"), item("naturesaura:crushing_catalyst")));

        assertEquals(4, slots.size());
        assertEquals(new JeiLayout.Slot(3, JeiLayout.Role.CATEGORY_CATALYST, List.of(item("naturesaura:nature_altar")), List.of()), slots.get(3));
        assertEquals(goldPowderSlots(), JeiRuntimeIndex.withCategoryCatalysts(goldPowderSlots(), List.of(item("naturesaura:crushing_catalyst"))));
        assertEquals(goldPowderSlots(), JeiRuntimeIndex.withCategoryCatalysts(goldPowderSlots(), List.of()));
    }

    @Test
    void planListsTheCategoryMachineOnceAsEquipment() {
        var layout = new JeiLayout("naturesaura:altar", "Altar", "naturesaura:altar/gold_powder", 0, 7, false, true, null,
                JeiRuntimeIndex.withCategoryCatalysts(goldPowderSlots(), List.of(item("naturesaura:nature_altar"))), List.of());
        var record = JeiProjection.record(layout, SCOPE, "19.27", Instant.EPOCH);
        var glowstone = new Evidence.Record(new Evidence.Source(SCOPE, "jei", "19.27", "raw", 1, Instant.EPOCH, Evidence.Activity.ACTIVE,
                Evidence.Coverage.COMPLETE, Evidence.Authority.VIEWER, List.of()), Evidence.RecordKind.RECIPE, "raw", new Evidence.JsonObject(Map.of()), List.of(),
                new Evidence.RecipePayload("raw", List.of(), List.of(new Evidence.RecipeOutput("item", "minecraft:glowstone_dust",
                        new Evidence.JsonObject(Map.of()), 1)), List.of(), List.of(), List.of(), Evidence.DecodingStatus.VERIFIED));

        var result = new RecipePlanner(new RecipePlanner.Source() {
            @Override public boolean available(Evidence.Scope scope) { return true; }
            @Override public List<Evidence.Record> recipeOutputs(String identity, Evidence.JsonObject components, Evidence.Scope scope) {
                return identity.equals("naturesaura:gold_powder") ? List.of(record) : identity.equals("minecraft:glowstone_dust") ? List.of(glowstone) : List.of();
            }
        }).plan("naturesaura:gold_powder", 4, SCOPE, 4);

        assertTrue(result.detail().contains("equipment=[naturesaura:crushing_catalyst, naturesaura:nature_altar]"), result.detail());
    }

    private static Evidence.Result plan(JeiLayout layout, String identity) {
        var record = JeiProjection.record(layout, SCOPE, "19.27", Instant.EPOCH);
        return new RecipePlanner(new RecipePlanner.Source() {
            @Override public boolean available(Evidence.Scope scope) { return true; }
            @Override public List<Evidence.Record> recipeOutputs(String wanted, Evidence.JsonObject components, Evidence.Scope scope) {
                return wanted.equals(identity) ? List.of(record) : List.of();
            }
        }).plan(identity, 2, SCOPE, 4);
    }

    private static List<JeiLayout.Slot> ironToPlate() {
        return List.of(new JeiLayout.Slot(0, JeiLayout.Role.INPUT, List.of(item("minecraft:iron_ingot")), List.of()),
                new JeiLayout.Slot(1, JeiLayout.Role.OUTPUT, List.of(item("mod:plate")), List.of()));
    }

    @Test
    void severalCategoryMachinesStayAnUnresolvedSet() {
        // Create registers the mixer and the basin for mixing; they work together, which category registration does not say.
        var layout = new JeiLayout("create:mixing", "Mixing", "create:mixing/brass", 0, 7, false, true, null,
                JeiRuntimeIndex.withCategoryCatalysts(ironToPlate(), List.of(item("create:mechanical_mixer"), item("create:basin"))), List.of());
        var record = JeiProjection.record(layout, SCOPE, "19.27", Instant.EPOCH);
        var catalysts = record.payload().values().get("catalysts").toString();
        assertTrue(catalysts.contains("category"), catalysts);

        var result = plan(layout, "mod:plate");
        assertTrue(result.detail().contains("equipment=[machines shown by JEI: [create:mechanical_mixer, create:basin] "
                + "(alternatives or used together; not determined)]"), result.detail());
        assertFalse(result.detail().contains("one of"), result.detail());
    }

    @Test
    void drawnCatalystSlotAlternativesStayAChoice() {
        var slots = new java.util.ArrayList<>(ironToPlate());
        slots.add(new JeiLayout.Slot(2, JeiLayout.Role.CATALYST, List.of(item("mod:basic_press"), item("mod:advanced_press")), List.of()));
        var layout = new JeiLayout("mod:press", "Press", "mod:press/plate", 0, 7, false, true, null,
                JeiRuntimeIndex.withCategoryCatalysts(slots, List.of(item("mod:basic_press"))), List.of());

        var result = plan(layout, "mod:plate");
        assertTrue(result.detail().contains("equipment=[one of [mod:basic_press, mod:advanced_press]]"), result.detail());
    }
}
