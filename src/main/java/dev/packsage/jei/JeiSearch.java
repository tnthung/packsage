package dev.packsage.jei;

import dev.packsage.knowledge.Evidence;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class JeiSearch {
    private JeiSearch() { }

    public record Page(List<Evidence.Record> records, int nextOffset, boolean exhausted, boolean truncated, Evidence.Coverage coverage, String detail) { }

    public static Page search(JeiIndex index, String outputItem, String inputItem, String text,
                              int offset, int limit, Evidence.Scope scope, String version, Instant now) {
        if (offset < 0 || limit < 1) throw new IllegalArgumentException("invalid page");
        var candidates = new LinkedHashSet<JeiIndex.RecipeRef>();
        if (outputItem != null) candidates.addAll(index.byFocus(JeiLayout.Role.OUTPUT, outputItem));
        if (inputItem != null) {
            var inputs = index.byFocus(JeiLayout.Role.INPUT, inputItem);
            if (outputItem == null) candidates.addAll(inputs);
            else candidates.retainAll(new LinkedHashSet<>(inputs));
        }
        boolean truncated = false;
        Set<String> categories = Set.of();
        List<JeiIndex.ItemVariant> variants = List.of();
        boolean textFilter = text != null && !text.isBlank();
        if (textFilter || outputItem == null && inputItem == null) {
            var query = text == null ? "" : text;
            categories = new LinkedHashSet<>(index.categoriesMatching(query));
            variants = index.itemsMatching(query);
            truncated = index.itemsMatchingTruncated();
            if (outputItem == null && inputItem == null) {
                for (var category : categories) candidates.addAll(index.inCategory(category));
                for (var variant : variants) candidates.addAll(index.byVariant(JeiLayout.Role.OUTPUT, variant));
            }
        }
        var refs = List.copyOf(candidates);
        int start = Math.min(offset, refs.size());
        int end = (int) Math.min((long) start + limit, refs.size());
        var records = new ArrayList<Evidence.Record>();
        for (var ref : refs.subList(start, end)) {
            var layout = index.layout(ref);
            boolean matches = !textFilter || outputItem == null && inputItem == null || categories.contains(ref.categoryUid());
            if (!matches && layout.readable()) {
                for (var slot : layout.slots()) {
                    if (slot.role() != JeiLayout.Role.OUTPUT) continue;
                    for (var ingredient : slot.alternatives()) {
                        if (!ingredient.type().equals("item")) continue;
                        for (var variant : variants) {
                            if (ingredient.identity().equals(variant.itemId()) && Evidence.deserializeObject(ingredient.componentsJson())
                                    .equals(Evidence.deserializeObject(variant.componentsJson()))) matches = true;
                        }
                    }
                }
            }
            if (matches) records.add(JeiProjection.record(layout, scope, version, now));
        }
        return new Page(List.copyOf(records), end, end == refs.size(), truncated, truncated ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE, truncated ? "JEI item-name search truncated at 64 variants" : null);
    }
}
