package dev.packsage.jei;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.RecipePlanner;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class JeiProjection {
    public static final Set<String> VANILLA_CATEGORIES = Set.of("minecraft:crafting", "minecraft:smelting", "minecraft:blasting",
            "minecraft:smoking", "minecraft:campfire_cooking", "minecraft:stonecutting", "minecraft:smithing", "minecraft:brewing");

    private JeiProjection() { }

    /** A parsed handle; temporary handles carry the ordinal, the generation and the session of the process that made them (null when saved before sessions). */
    public record Handle(String categoryUid, String recipeId, Integer ordinal, Long generation, String session) {
        /** Whether a temporary handle names a recipe in this process and the index's current generation; stable handles never depend on it. */
        public boolean current(JeiIndex index) {
            return session != null && session.equals(JeiRuntimeHolder.session()) && generation == index.generation();
        }
    }

    public static String handle(JeiLayout layout) {
        return layout.categoryUid() + "|" + (layout.recipeId() == null
                ? "#" + layout.ordinal() + "@" + layout.generation() + "~" + JeiRuntimeHolder.session() : layout.recipeId());
    }

    /**
     * The recipe handle a reference draws and opens: the handle itself, or for a plan route, the recipe handle the route wraps.
     * The plan handle stays the evidence and link identity; empty when a plan handle is malformed.
     */
    public static Optional<String> destination(String value) {
        if (value == null || !value.startsWith("plan|")) return Optional.ofNullable(value);
        var parts = value.split("\\|", 6);
        if (parts.length != 6 || parts[1].isEmpty()) return Optional.empty();
        try {
            long quantity = Long.parseLong(parts[2]);
            int depth = Integer.parseInt(parts[3]);
            int route = Integer.parseInt(parts[4]);
            if (quantity < 1 || quantity > 1_000_000 || depth < 0 || depth > 8 || route < 0) return Optional.empty();
        } catch (NumberFormatException failure) {
            return Optional.empty();
        }
        return Optional.of(parts[5]);
    }

    public static Optional<Handle> parseHandle(String value) {
        if (value == null) return Optional.empty();
        int separator = value.indexOf('|');
        if (separator <= 0 || separator == value.length() - 1 || value.indexOf('|', separator + 1) >= 0) return Optional.empty();
        var category = value.substring(0, separator);
        var recipe = value.substring(separator + 1);
        if (!recipe.startsWith("#")) return Optional.of(new Handle(category, recipe, null, null, null));
        int at = recipe.indexOf('@');
        if (at <= 1 || at == recipe.length() - 1 || recipe.indexOf('@', at + 1) >= 0) return Optional.empty();
        try {
            // Handles saved before sessions have none; they parse but never resolve as current.
            int tilde = recipe.indexOf('~', at);
            if (tilde == recipe.length() - 1 || tilde >= 0 && recipe.indexOf('~', tilde + 1) >= 0) return Optional.empty();
            var generation = tilde < 0 ? recipe.substring(at + 1) : recipe.substring(at + 1, tilde);
            return Optional.of(new Handle(category, null, Integer.parseInt(recipe.substring(1, at)), Long.parseLong(generation),
                    tilde < 0 ? null : recipe.substring(tilde + 1)));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    public static Evidence.Record record(JeiLayout layout, Evidence.Scope scope, String providerVersion, Instant observedAt) {
        boolean vanilla = VANILLA_CATEGORIES.contains(layout.categoryUid());
        var inputs = new ArrayList<Evidence.RecipeSlot>();
        var outputs = new ArrayList<Evidence.RecipeOutput>();
        var remainders = new ArrayList<Evidence.RecipeOutput>();
        var catalysts = new ArrayList<Evidence.RecipeAlternative>();
        var inputJson = new ArrayList<Evidence.JsonValue>();
        var outputJson = new ArrayList<Evidence.JsonValue>();
        var remainderJson = new ArrayList<Evidence.JsonValue>();
        var catalystJson = new ArrayList<Evidence.JsonValue>();
        var constraints = new ArrayList<String>();
        constraints.add("recipeType=" + layout.categoryUid());
        if (!vanilla) {
            constraints.add("consumption=assumed");
            constraints.add("output=assumed-guaranteed");
            constraints.add("not-read=values drawn by the category");
        }
        if (layout.categoryUid().equals("minecraft:brewing")) constraints.add(RecipePlanner.BREWING_BATCH_CONSTRAINT);
        if (layout.hidden()) constraints.add("hidden");
        for (var slot : layout.slots()) {
            if (slot.role() == JeiLayout.Role.RENDER_ONLY) continue;
            var groupId = "slot-" + slot.index();
            var alternatives = new ArrayList<Evidence.RecipeAlternative>();
            var alternativesJson = new ArrayList<Evidence.JsonValue>();
            var tooltips = slot.extraTooltip().stream().map(Evidence.JsonString::new).map(value -> (Evidence.JsonValue) value).toList();
            if (slot.role() == JeiLayout.Role.OUTPUT) {
                for (var line : slot.extraTooltip()) if (line.contains("%")) constraints.add("chance:" + groupId + "=" + line);
            }
            for (var ingredient : slot.alternatives()) {
                var components = Evidence.deserializeObject(ingredient.componentsJson());
                Integer quantity = ingredient.amount() > 0 && ingredient.amount() <= Integer.MAX_VALUE ? (int) ingredient.amount() : null;
                if (quantity == null && !constraints.contains("quantity-unrepresentable:" + groupId)) constraints.add("quantity-unrepresentable:" + groupId);
                var fields = new LinkedHashMap<String, Evidence.JsonValue>();
                fields.put("type", new Evidence.JsonString(ingredient.type()));
                fields.put("comparisonIdentity", new Evidence.JsonString(ingredient.identity()));
                fields.put("components", components);
                fields.put("quantity", quantity == null ? new Evidence.JsonNull() : new Evidence.JsonNumber(BigDecimal.valueOf(quantity)));
                if (slot.role() == JeiLayout.Role.OUTPUT) {
                    outputs.add(new Evidence.RecipeOutput(ingredient.type(), ingredient.identity(), components, quantity));
                    fields.put("groupId", new Evidence.JsonString(groupId));
                    fields.put("extraTooltip", new Evidence.JsonArray(tooltips));
                    outputJson.add(new Evidence.JsonObject(fields));
                } else {
                    var consumption = slot.role() == JeiLayout.Role.INPUT ? Evidence.Consumption.CONSUMED : Evidence.Consumption.REUSED;
                    var alternative = new Evidence.RecipeAlternative(ingredient.type(), ingredient.identity(), components, quantity, consumption);
                    fields.put("consumption", new Evidence.JsonString(consumption.name()));
                    if (slot.role() == JeiLayout.Role.INPUT) {
                        alternatives.add(alternative);
                        alternativesJson.add(new Evidence.JsonObject(fields));
                        if (ingredient.type().equals("item") && ingredient.remainderIdentity() != null) {
                            remainders.add(new Evidence.RecipeOutput("item", ingredient.remainderIdentity(), new Evidence.JsonObject(Map.of()), quantity));
                            remainderJson.add(new Evidence.JsonObject(Map.of("type", new Evidence.JsonString("item"),
                                    "comparisonIdentity", new Evidence.JsonString(ingredient.remainderIdentity()),
                                    "components", new Evidence.JsonObject(Map.of()), "quantity", fields.get("quantity"))));
                            constraints.add("container-returned=" + ingredient.identity() + ">" + ingredient.remainderIdentity());
                        }
                    } else {
                        catalysts.add(alternative);
                        fields.put("groupId", new Evidence.JsonString(groupId));
                        fields.put("extraTooltip", new Evidence.JsonArray(tooltips));
                        catalystJson.add(new Evidence.JsonObject(fields));
                    }
                }
            }
            if (slot.role() == JeiLayout.Role.INPUT) {
                inputs.add(new Evidence.RecipeSlot(groupId, alternatives));
                inputJson.add(new Evidence.JsonObject(Map.of("groupId", new Evidence.JsonString(groupId),
                        "alternatives", new Evidence.JsonArray(alternativesJson), "extraTooltip", new Evidence.JsonArray(tooltips))));
            }
        }
        var decoding = !layout.readable() ? Evidence.DecodingStatus.OPAQUE
                : vanilla && layout.nativeDecoding() != null ? layout.nativeDecoding() : Evidence.DecodingStatus.PARTIAL;
        var coverage = decoding == Evidence.DecodingStatus.OPAQUE ? Evidence.Coverage.OPAQUE
                : decoding == Evidence.DecodingStatus.VERIFIED ? Evidence.Coverage.COMPLETE : Evidence.Coverage.PARTIAL;
        var typed = new Evidence.RecipePayload(layout.recipeId(), inputs, outputs, remainders, catalysts, constraints, decoding);
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("canonicalRecipeId", layout.recipeId() == null ? new Evidence.JsonNull() : new Evidence.JsonString(layout.recipeId()));
        payload.put("inputs", new Evidence.JsonArray(inputJson));
        payload.put("outputs", new Evidence.JsonArray(outputJson));
        payload.put("remainders", new Evidence.JsonArray(remainderJson));
        payload.put("catalysts", new Evidence.JsonArray(catalystJson));
        payload.put("constraints", new Evidence.JsonArray(constraints.stream().map(Evidence.JsonString::new).map(value -> (Evidence.JsonValue) value).toList()));
        payload.put("decodingStatus", new Evidence.JsonString(decoding.name()));
        payload.put("category", new Evidence.JsonString(layout.categoryTitle()));
        payload.put("recipeTooltip", new Evidence.JsonArray(layout.recipeTooltip().stream().map(Evidence.JsonString::new).map(value -> (Evidence.JsonValue) value).toList()));
        var source = new Evidence.Source(scope, "jei", providerVersion, handle(layout), layout.generation(), observedAt,
                Evidence.Activity.ACTIVE, coverage, Evidence.Authority.VIEWER, List.of("jei"));
        return new Evidence.Record(source, Evidence.RecordKind.RECIPE, layout.categoryTitle(), new Evidence.JsonObject(payload),
                List.of(new Evidence.NavigationTarget(scope, "jei", source.sourceHandle(), layout.recipeId() == null ? "temporary" : "recipe")), typed);
    }
}
