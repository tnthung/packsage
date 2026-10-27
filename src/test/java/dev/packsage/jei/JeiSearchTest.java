package dev.packsage.jei;

import dev.packsage.knowledge.Evidence;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JeiSearchTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "jei", 4);
    private static final Instant NOW = Instant.parse("2026-10-27T00:00:00Z");

    static class Fake implements JeiIndex {
        final Map<RecipeRef, JeiLayout> layouts = new LinkedHashMap<>();
        final Map<String, List<RecipeRef>> focuses = new HashMap<>();
        final Map<String, List<RecipeRef>> categories = new LinkedHashMap<>();
        final Map<String, List<String>> categoryMatches = new HashMap<>();
        final Map<String, List<ItemVariant>> itemMatches = new HashMap<>();
        final Map<ItemVariant, List<RecipeRef>> variants = new HashMap<>();
        boolean truncated;
        int layoutCalls;
        boolean forbidCategories;
        void add(int n, String category, String title, boolean readable) {
            var ref = new RecipeRef(category, n);
            layouts.put(ref, new JeiLayout(category, title, "test:recipe" + n, n, 7, false, readable, null, List.of(), List.of()));
            categories.computeIfAbsent(category, ignored -> new ArrayList<>()).add(ref);
        }
        public long generation() { return 7; }
        public List<RecipeRef> byFocus(JeiLayout.Role role, String id) { return focuses.getOrDefault(role + ":" + id, List.of()); }
        public List<String> categoriesMatching(String text) { return categoryMatches.getOrDefault(text, List.of()); }
        public List<ItemVariant> itemsMatching(String text) { return itemMatches.getOrDefault(text, List.of()); }
        public boolean itemsMatchingTruncated() { return truncated; }
        public List<RecipeRef> byVariant(JeiLayout.Role role, ItemVariant variant) { return variants.getOrDefault(variant, List.of()); }
        public List<RecipeRef> inCategory(String id) {
            if (forbidCategories) fail("item focus must not scan categories");
            return categories.getOrDefault(id, List.of());
        }
        public Optional<RecipeRef> byRecipeId(String category, String id) { return Optional.empty(); }
        public JeiLayout layout(RecipeRef ref) { layoutCalls++; return layouts.get(ref); }
    }

    @Test
    void outputFocusUsesIndexOnly() {
        var index = new Fake(); index.add(0, "mod:altar", "Altar", true); index.forbidCategories = true;
        index.focuses.put("OUTPUT:mod:result", List.copyOf(index.layouts.keySet()));
        assertEquals(1, JeiSearch.search(index, "mod:result", null, null, 0, 10, SCOPE, "19.27", NOW).records().size());
    }
    @Test
    void inputFocusFindsUses() {
        var index = new Fake(); index.add(0, "mod:altar", "Altar", true); index.add(1, "mod:altar", "Altar", true);
        index.focuses.put("INPUT:naturesaura:gold_powder", List.copyOf(index.layouts.keySet()));
        assertEquals(2, JeiSearch.search(index, null, "naturesaura:gold_powder", null, 0, 10, SCOPE, "19.27", NOW).records().size());
    }
    @Test
    void textMatchesCategoryAndItemNames() {
        var index = new Fake(); index.add(0, "mod:infusion", "Infusion", true); index.add(1, "mod:other", "Other", true);
        var refs = List.copyOf(index.layouts.keySet());
        index.categoryMatches.put("infusion", List.of("mod:infusion")); var variant = new JeiIndex.ItemVariant("mod:item", "{}"); index.itemMatches.put("infusion", List.of(variant));
        index.variants.put(variant, List.of(refs.get(0), refs.get(1)));
        var page = JeiSearch.search(index, null, null, "infusion", 0, 10, SCOPE, "19.27", NOW);
        assertEquals(List.of("test:recipe0", "test:recipe1"), page.records().stream().map(r -> r.recipePayload().canonicalRecipeId()).toList());
    }
    @Test
    void subtypeOutputExpandsKnownVariants() {
        var index = new Fake(); index.add(0, "minecraft:brewing", "Brewing", true); index.add(1, "minecraft:brewing", "Brewing", true);
        index.focuses.put("OUTPUT:minecraft:potion", List.copyOf(index.layouts.keySet()));
        assertEquals(2, JeiSearch.search(index, "minecraft:potion", null, null, 0, 10, SCOPE, "19.27", NOW).records().size());
    }
    @Test
    void pagingAdvancesAndExhausts() {
        var index = new Fake(); for (int i = 0; i < 5; i++) index.add(i, "mod:altar", "Altar", true);
        index.focuses.put("OUTPUT:mod:item", List.copyOf(index.layouts.keySet()));
        var a = JeiSearch.search(index, "mod:item", null, null, 0, 2, SCOPE, "19.27", NOW);
        var b = JeiSearch.search(index, "mod:item", null, null, 2, 2, SCOPE, "19.27", NOW);
        var c = JeiSearch.search(index, "mod:item", null, null, 4, 2, SCOPE, "19.27", NOW);
        assertEquals(2, a.nextOffset()); assertFalse(a.exhausted()); assertEquals(4, b.nextOffset()); assertFalse(b.exhausted());
        assertEquals(5, c.nextOffset()); assertTrue(c.exhausted()); assertEquals(1, c.records().size());
    }
    @Test
    void unknownFocusReturnsEmptyComplete() {
        var page = JeiSearch.search(new Fake(), "mod:unknown", null, null, 0, 2, SCOPE, "19.27", NOW);
        assertTrue(page.records().isEmpty()); assertTrue(page.exhausted()); assertEquals(0, page.nextOffset());
    }
    @Test
    void failingRecipeDoesNotAbortPage() {
        var index = new Fake(); index.add(0, "mod:altar", "Altar", true); index.add(1, "mod:altar", "Altar", false); index.add(2, "mod:altar", "Altar", true);
        index.focuses.put("OUTPUT:mod:item", List.copyOf(index.layouts.keySet()));
        var page = JeiSearch.search(index, "mod:item", null, null, 0, 10, SCOPE, "19.27", NOW);
        assertEquals(3, page.records().size()); assertEquals(Evidence.Coverage.OPAQUE, page.records().get(1).source().coverage());
    }
    @Test
    void intersectAndTextFilterKeepCandidateOrder() {
        var index = new Fake(); for (int i = 0; i < 4; i++) index.add(i, i == 1 ? "mod:infusion" : "mod:other", i == 1 ? "Infusion" : "Other", true);
        var refs = List.copyOf(index.layouts.keySet());
        index.focuses.put("OUTPUT:mod:result", refs); index.focuses.put("INPUT:mod:input", List.of(refs.get(1), refs.get(2), refs.get(3)));
        index.categoryMatches.put("infusion", List.of("mod:infusion")); index.itemMatches.put("infusion", List.of(new JeiIndex.ItemVariant("mod:named", "{}")));
        index.layouts.put(refs.get(2), new JeiLayout("mod:other", "Other", "test:recipe2", 2, 7, false, true, null, List.of(new JeiLayout.Slot(0, JeiLayout.Role.OUTPUT, List.of(new JeiLayout.Ingredient("item", "mod:named", "{}", 1, null)), List.of())), List.of()));
        var page = JeiSearch.search(index, "mod:result", "mod:input", "infusion", 0, 10, SCOPE, "19.27", NOW);
        assertEquals(List.of("test:recipe1", "test:recipe2"), page.records().stream().map(r -> r.recipePayload().canonicalRecipeId()).toList());
    }
    @Test
    void textMatchesOnlyNamedVariant() {
        var index = new Fake(); index.add(0, "minecraft:brewing", "Brewing", true); index.add(1, "minecraft:brewing", "Brewing", true);
        var refs = List.copyOf(index.layouts.keySet());
        var strength = new JeiIndex.ItemVariant("minecraft:potion", "{\"potion\":\"strength\"}");
        index.itemMatches.put("strength", List.of(strength)); index.variants.put(strength, List.of(refs.get(0)));
        index.focuses.put("OUTPUT:minecraft:potion", refs);
        index.layouts.put(refs.get(0), new JeiLayout("minecraft:brewing", "Brewing", "test:recipe0", 0, 7, false, true, null,
                List.of(new JeiLayout.Slot(0, JeiLayout.Role.OUTPUT, List.of(new JeiLayout.Ingredient("item", "minecraft:potion", strength.componentsJson(), 1, null)), List.of())), List.of()));
        index.layouts.put(refs.get(1), new JeiLayout("minecraft:brewing", "Brewing", "test:recipe1", 1, 7, false, true, null,
                List.of(new JeiLayout.Slot(0, JeiLayout.Role.OUTPUT, List.of(new JeiLayout.Ingredient("item", "minecraft:potion", "{\"potion\":\"weakness\"}", 1, null)), List.of())), List.of()));
        for (var output : new String[] { null, "minecraft:potion" }) {
            var page = JeiSearch.search(index, output, null, "strength", 0, 10, SCOPE, "19.27", NOW);
            assertEquals(List.of("test:recipe0"), page.records().stream().map(r -> r.recipePayload().canonicalRecipeId()).toList());
        }
    }
    @Test
    void truncatedVariantsMarkPagePartialEvenWhenEmpty() {
        var index = new Fake(); index.truncated = true;
        var page = JeiSearch.search(index, null, null, "broad", 0, 10, SCOPE, "19.27", NOW);
        assertTrue(page.truncated()); assertTrue(page.records().isEmpty()); assertEquals(Evidence.Coverage.PARTIAL, page.coverage()); assertEquals("JEI item-name search truncated at 64 variants", page.detail());
    }
    @Test
    void laterPageOfLargeLookupResolvesAcrossRepeatedSearches() {
        var index = new Fake();
        for (int i = 0; i < 4102; i++) index.add(i, "mod:altar", "Altar", true);
        index.focuses.put("OUTPUT:mod:item", List.copyOf(index.layouts.keySet()));
        var first = JeiSearch.search(index, "mod:item", null, null, 4096, 2, SCOPE, "19.27", NOW);
        var repeated = JeiSearch.search(index, "mod:item", null, null, 4096, 2, SCOPE, "19.27", NOW);
        assertEquals(List.of("test:recipe4096", "test:recipe4097"), first.records().stream().map(r -> r.recipePayload().canonicalRecipeId()).toList());
        assertEquals(first.records(), repeated.records());
    }
    @Test
    void combinedTextFilterReadsAtMostPageLimitLayouts() {
        var index = new Fake();
        for (int i = 0; i < 5; i++) index.add(i, "mod:altar", "Altar", true);
        index.focuses.put("OUTPUT:mod:item", List.copyOf(index.layouts.keySet()));
        var page = JeiSearch.search(index, "mod:item", null, "absent", 0, 2, SCOPE, "19.27", NOW);
        assertTrue(page.records().isEmpty()); assertEquals(2, page.nextOffset()); assertFalse(page.exhausted());
        assertEquals(2, index.layoutCalls);
    }
    @Test
    void matchingComponentsIgnoresJsonObjectKeyOrder() {
        var index = new Fake(); index.add(0, "mod:other", "Other", true);
        var ref = index.layouts.keySet().iterator().next();
        index.focuses.put("OUTPUT:mod:item", List.of(ref));
        index.itemMatches.put("name", List.of(new JeiIndex.ItemVariant("mod:item", "{\"a\":1,\"b\":2}")));
        index.layouts.put(ref, new JeiLayout("mod:other", "Other", "test:recipe0", 0, 7, false, true, null,
                List.of(new JeiLayout.Slot(0, JeiLayout.Role.OUTPUT, List.of(new JeiLayout.Ingredient("item", "mod:item", "{\"b\":2,\"a\":1}", 1, null)), List.of())), List.of()));
        assertEquals(1, JeiSearch.search(index, "mod:item", null, "name", 0, 2, SCOPE, "19.27", NOW).records().size());
    }
}
