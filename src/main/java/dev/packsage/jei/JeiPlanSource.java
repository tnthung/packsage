package dev.packsage.jei;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.RecipePlanner;
import java.time.Instant;
import java.util.List;

public final class JeiPlanSource implements RecipePlanner.Source {
    private static final int MAX_REFS = 512;
    private final JeiIndex index;
    private final Evidence.Scope scope;
    private final String version;
    private final Instant now;

    public JeiPlanSource(JeiIndex index, Evidence.Scope scope, String version, Instant now) {
        this.index = index;
        this.scope = scope;
        this.version = version;
        this.now = now;
    }

    @Override public boolean available(Evidence.Scope requestedScope) { return scope.equals(requestedScope); }

    @Override public List<Evidence.Record> recipeOutputs(String identity, Evidence.JsonObject components, Evidence.Scope requestedScope) {
        if (!available(requestedScope)) return List.of();
        return (components.values().isEmpty() ? index.byFocus(JeiLayout.Role.OUTPUT, identity)
                : index.byVariant(JeiLayout.Role.OUTPUT, new JeiIndex.ItemVariant(identity, Evidence.serialize(components))))
                .stream().limit(MAX_REFS).map(ref -> JeiProjection.record(index.layout(ref), scope, version, now)).toList();
    }

    @Override public boolean recipesComplete(String identity, Evidence.JsonObject components) {
        return (components.values().isEmpty() ? index.byFocus(JeiLayout.Role.OUTPUT, identity)
                : index.byVariant(JeiLayout.Role.OUTPUT, new JeiIndex.ItemVariant(identity, Evidence.serialize(components))))
                .size() < MAX_REFS;
    }
}
