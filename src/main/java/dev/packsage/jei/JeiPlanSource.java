package dev.packsage.jei;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.RecipePlanner;
import java.time.Instant;
import java.util.AbstractList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class JeiPlanSource implements RecipePlanner.Source {
    private static final int MAX_REFS = 512;
    private final JeiIndex index;
    private final Evidence.Scope scope;
    private final String version;
    private final Instant now;
    private final Map<JeiIndex.ItemVariant, List<JeiIndex.RecipeRef>> refs = new HashMap<>();
    // Each recipe is drawn and projected at most once per plan, however many items or visits reach it.
    private final Map<JeiIndex.RecipeRef, Evidence.Record> records = new HashMap<>();

    public JeiPlanSource(JeiIndex index, Evidence.Scope scope, String version, Instant now) {
        this.index = index;
        this.scope = scope;
        this.version = version;
        this.now = now;
    }

    @Override public boolean available(Evidence.Scope requestedScope) { return scope.equals(requestedScope); }

    /** Projects a recipe only when the planner reads it; the planner reads at most its branch cap of an item's recipes. */
    @Override public List<Evidence.Record> recipeOutputs(String identity, Evidence.JsonObject components, Evidence.Scope requestedScope) {
        if (!available(requestedScope)) return List.of();
        var candidates = refs(identity, components);
        return new AbstractList<>() {
            @Override public Evidence.Record get(int position) {
                return records.computeIfAbsent(candidates.get(position), ref -> JeiProjection.record(index.layout(ref), scope, version, now));
            }

            @Override public int size() { return candidates.size(); }
        };
    }

    @Override public boolean recipesComplete(String identity, Evidence.JsonObject components) {
        var candidates = refs(identity, components);
        return candidates.size() < MAX_REFS
                && (!candidates.isEmpty() || components.values().isEmpty() || !index.hasSubtypes(identity));
    }

    private List<JeiIndex.RecipeRef> refs(String identity, Evidence.JsonObject components) {
        return refs.computeIfAbsent(new JeiIndex.ItemVariant(identity, Evidence.serialize(components)), variant ->
                (variant.components().values().isEmpty() ? index.byFocus(JeiLayout.Role.OUTPUT, identity)
                        : index.byVariant(JeiLayout.Role.OUTPUT, variant)).stream().limit(MAX_REFS).toList());
    }
}
