package dev.packsage.knowledge;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class RecipePlanner {
    private static final int MAX_NODES = 256;
    private static final int MAX_BRANCHES = 64;
    private final Source index;

    public interface Source {
        boolean available(Evidence.Scope scope);
        List<Evidence.Record> recipeOutputs(String identity, Evidence.JsonObject components, Evidence.Scope scope);

        /** False when a cap, an unreadable recipe or a computed result could have hidden recipes for this variant from recipeOutputs. */
        default boolean recipesComplete(String identity, Evidence.JsonObject components) {
            return true;
        }
    }

    public RecipePlanner(Source index) {
        this.index = index;
    }

    public Evidence.Result plan(String itemIdentity, long quantity, Evidence.Scope scope, int maxDepth) {
        if (quantity < 1 || maxDepth < 0) return new Evidence.Result(List.of(), Evidence.Coverage.PARTIAL, Evidence.Outcome.PARTIAL, null,
                "invalid-request: quantity must be positive and depth nonnegative");
        if (!index.available(scope)) return new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null,
                "recipe-source-unavailable");
        var state = new PlanState();
        boolean complete = visit(itemIdentity, new Evidence.JsonObject(Map.of()), quantity, scope, maxDepth,
                new HashSet<>(), state);
        if (state.routes.isEmpty()) return new Evidence.Result(List.of(), Evidence.Coverage.PARTIAL, Evidence.Outcome.PARTIAL, null, state.detail());
        return new Evidence.Result(state.routes, complete && !state.partial ? Evidence.Coverage.COMPLETE : Evidence.Coverage.PARTIAL,
                complete && !state.partial ? Evidence.Outcome.COMPLETE : Evidence.Outcome.PARTIAL, null,
                complete && !state.partial ? state.equipment.isEmpty() ? null : state.equipmentLine() : state.detail());
    }

    private boolean visit(String itemIdentity, Evidence.JsonObject components, long quantity, Evidence.Scope scope,
                          int depth, Set<RequirementKey> visiting, PlanState state) {
        if (state.nodes >= MAX_NODES) {
            state.partial = true;
            state.reasons.add("node-cap=" + MAX_NODES);
            return false;
        }
        state.nodes++;
        var key = new RequirementKey("item", itemIdentity, components);
        if (!visiting.add(key)) {
            state.partial = true;
            state.reasons.add("bounded-depth:" + itemIdentity);
            var cycle = index.recipeOutputs(itemIdentity, components, scope);
            if (!cycle.isEmpty() && state.routes.size() < MAX_NODES) {
                var candidate = cycle.getFirst();
                var output = candidate.recipePayload().outputs().stream().filter(value -> itemIdentity.equals(value.comparisonIdentity())).findFirst().orElse(null);
                var rules = MachineRules.of(candidate);
                var chance = output == null ? null : rules.chanceReason(candidate, output);
                if (chance != null) state.reasons.add(chance);
                state.routes.add(route(candidate, rules, output, quantity, output == null || output.quantity() == null
                        || sharedBatch(candidate) || chance != null ? null : batches(quantity, output.quantity()), "bounded-depth", state));
            } else if (!cycle.isEmpty()) state.reasons.add("node-cap=" + MAX_NODES);
            return false;
        }
        var candidates = index.recipeOutputs(itemIdentity, components, scope);
        if (candidates.isEmpty()) {
            visiting.remove(key);
            state.partial = true;
            if (!index.recipesComplete(itemIdentity, components)) {
                if (state.uncheckedLeaves.size() < 16) state.uncheckedLeaves.add(itemIdentity);
                else if (!state.uncheckedLeaves.contains(itemIdentity)) state.omittedUncheckedLeaves.add(itemIdentity);
            } else if (state.noRecipeLeaves.size() < 16) state.noRecipeLeaves.add(itemIdentity);
            else if (!state.noRecipeLeaves.contains(itemIdentity)) state.omittedNoRecipeLeaves.add(itemIdentity);
            return false;
        }
        boolean anyComplete = false;
        boolean anyIncomplete = candidates.size() > MAX_BRANCHES;
        if (anyIncomplete) state.reasons.add("recipe-branch-cap=" + MAX_BRANCHES);
        int branchCount = 0;
        for (Evidence.Record candidate : candidates) {
            if (branchCount >= MAX_BRANCHES || state.nodes >= MAX_NODES || state.routes.size() >= MAX_NODES) {
                anyIncomplete = true;
                state.partial = true;
                state.reasons.add(state.nodes >= MAX_NODES || state.routes.size() >= MAX_NODES
                        ? "node-cap=" + MAX_NODES : "recipe-branch-cap=" + MAX_BRANCHES);
                break;
            }
            branchCount++;
            var output = candidate.recipePayload().outputs().stream().filter(value -> itemIdentity.equals(value.comparisonIdentity())
                    && (components.values().isEmpty() || components.equals(value.components()))).findFirst().orElse(null);
            var rules = MachineRules.of(candidate);
            var chance = output == null ? null : rules.chanceReason(candidate, output);
            var batches = output == null || output.quantity() == null || sharedBatch(candidate) || chance != null ? null : batches(quantity, output.quantity());
            boolean candidateComplete = output != null && batches != null
                    && candidate.source().coverage() == Evidence.Coverage.COMPLETE;
            if (output == null) state.reasons.add("unknown-output:" + candidate.source().sourceHandle());
            else if (output.quantity() == null || output.quantity() < 1) state.reasons.add("unknown-output-quantity:" + candidate.source().sourceHandle());
            if (sharedBatch(candidate)) state.reasons.add("operation-count-unknown:" + candidate.source().sourceHandle());
            else if (chance != null) state.reasons.add(chance);
            else if (batches == null && output != null && output.quantity() != null && output.quantity() > 0)
                state.reasons.add("batch-count-overflow:" + candidate.source().sourceHandle());
            if (rules.assumed) {
                candidateComplete = false;
                state.reasons.add("assumed-consumption:" + candidate.source().sourceHandle());
            }
            if (candidate.source().coverage() != Evidence.Coverage.COMPLETE)
                state.reasons.add("source-coverage=" + candidate.source().coverage() + ":" + candidate.source().sourceHandle());
            for (String constraint : candidate.recipePayload().constraints()) {
                if (constraint.toLowerCase().contains("unknown") || constraint.toLowerCase().contains("ambiguous")) {
                    candidateComplete = false;
                    state.reasons.add("constraint=" + constraint + ":" + candidate.source().sourceHandle());
                }
            }
            if (candidate.recipePayload().decodingStatus() != Evidence.DecodingStatus.VERIFIED)
                state.reasons.add("decoding-status=" + candidate.recipePayload().decodingStatus() + ":" + candidate.source().sourceHandle());
            state.routes.add(route(candidate, rules, output, quantity, batches, depth <= 0 ? "bounded-depth" : null, state));
            if (chance != null) {
                // A chance output cannot be planned for, so its inputs are not expanded.
                anyIncomplete = true;
                state.partial = true;
                continue;
            }
            if (depth <= 0) {
                anyIncomplete = true;
                state.partial = true;
                state.reasons.add("bounded-depth:" + itemIdentity);
                continue;
            }
            if (output == null || batches == null) candidateComplete = false;
            var required = new LinkedHashMap<RequirementKey, Long>();
            var processedAlternativeSets = new HashSet<List<Evidence.RecipeAlternative>>();
            for (Evidence.RecipeSlot slot : candidate.recipePayload().inputs()) {
                if (slot.alternatives().isEmpty()) {
                    candidateComplete = false;
                    state.partial = true;
                    state.reasons.add("empty-input-slot:" + candidate.source().sourceHandle() + ":" + slot.groupId());
                } else if (slot.alternatives().size() == 1) {
                    var alternative = slot.alternatives().getFirst();
                    if (batches != null && rules.unexpanded(alternative)) continue;
                    if (alternative.consumption() != Evidence.Consumption.CONSUMED || alternative.quantity() == null
                            || batches == null || !alternative.type().equals("item")) {
                        candidateComplete = false;
                        state.partial = true;
                        state.reasons.add("unknown-consumption-or-requirement:" + candidate.source().sourceHandle() + ":" + slot.groupId());
                    } else {
                        var requiredQuantity = multiply(alternative.quantity(), batches);
                        if (requiredQuantity == null) {
                            candidateComplete = false;
                            state.partial = true;
                            state.reasons.add("input-quantity-overflow:" + candidate.source().sourceHandle() + ":" + slot.groupId());
                        } else {
                            var requirement = new RequirementKey(alternative.type(), alternative.comparisonIdentity(), alternative.components());
                            var previous = required.get(requirement);
                            var total = previous == null ? requiredQuantity : add(previous, requiredQuantity);
                            if (total == null) {
                                candidateComplete = false;
                                state.partial = true;
                                state.reasons.add("input-quantity-overflow:" + candidate.source().sourceHandle() + ":" + slot.groupId());
                            } else {
                                required.put(requirement, total);
                            }
                        }
                    }
                } else {
                    if (!processedAlternativeSets.add(slot.alternatives())) continue;
                    boolean alternativeComplete = false;
                    boolean alternativeIncomplete = false;
                    var groupedBatches = batches == null ? null : multiply(batches, candidate.recipePayload().inputs().stream()
                            .filter(other -> other.alternatives().equals(slot.alternatives())).count());
                    for (Evidence.RecipeAlternative alternative : slot.alternatives()) {
                        if (state.branches >= MAX_BRANCHES) {
                            alternativeIncomplete = true;
                            state.partial = true;
                            state.reasons.add("ingredient-branch-cap=" + MAX_BRANCHES);
                            break;
                        }
                        state.branches++;
                        if (groupedBatches != null && rules.unexpanded(alternative)) {
                            // Listed by route() as an unexpanded choice; it satisfies the slot without a refill route.
                            alternativeComplete = true;
                        } else if (alternative.consumption() != Evidence.Consumption.CONSUMED || alternative.quantity() == null
                                || groupedBatches == null || !alternative.type().equals("item")) {
                            alternativeIncomplete = true;
                            state.partial = true;
                            state.reasons.add("unknown-consumption-or-requirement:" + candidate.source().sourceHandle() + ":" + slot.groupId());
                        } else {
                            var requiredQuantity = multiply(alternative.quantity(), groupedBatches);
                            alternativeComplete |= requiredQuantity != null && visit(alternative.comparisonIdentity(), alternative.components(),
                                    requiredQuantity, scope, depth - 1, visiting, state);
                            alternativeIncomplete |= requiredQuantity == null;
                            if (requiredQuantity == null) state.reasons.add("input-quantity-overflow:" + candidate.source().sourceHandle() + ":" + slot.groupId());
                        }
                    }
                    if (!alternativeComplete || alternativeIncomplete) {
                        candidateComplete = false;
                        state.partial = true;
                    }
                }
            }
            for (var requirement : required.entrySet()) {
                if (!visit(requirement.getKey().identity(), requirement.getKey().components(), requirement.getValue(), scope,
                        depth - 1, visiting, state)) candidateComplete = false;
            }
            if (!candidate.recipePayload().catalysts().isEmpty() && !rules.reusedCatalysts(candidate)) {
                candidateComplete = false;
                state.partial = true;
                state.reasons.add("catalysts-present:" + candidate.source().sourceHandle());
            }
            if (!candidateComplete) {
                anyIncomplete = true;
                state.partial = true;
            } else {
                anyComplete = true;
            }
        }
        visiting.remove(key);
        return anyComplete && !anyIncomplete;
    }

    /** Plans one route, adding the mod machine fields and plan equipment only for records that carry the machine constraints. */
    private static Evidence.Record route(Evidence.Record candidate, MachineRules rules, Evidence.RecipeOutput output, long quantity, Long batches,
                                         String bound, PlanState state) {
        if (!rules.extended) return planned(candidate, quantity, output == null ? null : output.quantity(), batches, bound, Map.of());
        var needs = new LinkedHashMap<List<String>, Long>();
        if (batches != null) {
            var processedAlternativeSets = new HashSet<List<Evidence.RecipeAlternative>>();
            for (Evidence.RecipeSlot slot : candidate.recipePayload().inputs()) {
                boolean single = slot.alternatives().size() == 1;
                // Alternatives are choices: one entry per alternative, tied by oneOf to the first slot of that alternative set,
                // at the quantity for every slot sharing the set, as the alternative branch in visit() groups them.
                if (!single && !processedAlternativeSets.add(slot.alternatives())) continue;
                var multiplier = single ? Long.valueOf(batches) : multiply(batches, candidate.recipePayload().inputs().stream()
                        .filter(other -> other.alternatives().equals(slot.alternatives())).count());
                for (int index = 0; index < slot.alternatives().size(); index++) {
                    var alternative = slot.alternatives().get(index);
                    if (!rules.unexpanded(alternative)) continue;
                    // Required needs aggregate by identity; each choice keeps its own entry, even when it repeats an identity.
                    var key = List.of(alternative.type().equals("fluid") ? "fluid" : "contents", alternative.comparisonIdentity(),
                            single ? "" : slot.groupId(), single ? "" : Integer.toString(index));
                    var amount = multiplier == null ? null : multiply(alternative.quantity(), multiplier);
                    var total = amount == null ? null : needs.containsKey(key) ? needs.get(key) == null ? null : add(needs.get(key), amount) : amount;
                    if (total == null) {
                        state.partial = true;
                        state.reasons.add("input-quantity-overflow:" + candidate.source().sourceHandle() + ":" + slot.groupId());
                    }
                    needs.put(key, total);
                    if (key.getFirst().equals("contents")) state.equipment.add(rules.containers.get(alternative.comparisonIdentity()));
                }
            }
        }
        if (batches != null && rules.reusedCatalysts(candidate)) candidate.recipePayload().catalysts().forEach(catalyst -> state.equipment.add(catalyst.comparisonIdentity()));
        var values = new LinkedHashMap<String, Evidence.JsonValue>();
        values.put("unexpandedNeeds", new Evidence.JsonArray(needs.entrySet().stream().map(need -> {
            var fields = new LinkedHashMap<String, Evidence.JsonValue>();
            fields.put("type", new Evidence.JsonString(need.getKey().get(0)));
            fields.put("identity", new Evidence.JsonString(need.getKey().get(1)));
            fields.put("quantity", need.getValue() == null ? new Evidence.JsonNull() : new Evidence.JsonNumber(BigDecimal.valueOf(need.getValue())));
            if (!need.getKey().get(2).isEmpty()) fields.put("oneOf", new Evidence.JsonString(need.getKey().get(2)));
            return (Evidence.JsonValue) new Evidence.JsonObject(fields);
        }).toList()));
        var counted = batches == null ? null : output;
        values.put("possibleExtras", new Evidence.JsonArray(candidate.recipePayload().outputs().stream().filter(value -> value != counted).map(value -> {
            var fields = new LinkedHashMap<String, Evidence.JsonValue>();
            fields.put("type", new Evidence.JsonString(value.type()));
            fields.put("comparisonIdentity", new Evidence.JsonString(value.comparisonIdentity()));
            fields.put("components", value.components());
            fields.put("quantity", value.quantity() == null ? new Evidence.JsonNull() : new Evidence.JsonNumber(BigDecimal.valueOf(value.quantity())));
            return (Evidence.JsonValue) new Evidence.JsonObject(fields);
        }).toList()));
        if (rules.assumed) values.put("assumed", new Evidence.JsonBoolean(true));
        return planned(candidate, quantity, output == null ? null : output.quantity(), batches, bound, values);
    }

    private static Evidence.Record planned(Evidence.Record record, long requestedQuantity, Integer outputQuantity, Long batches, String bound,
                                           Map<String, Evidence.JsonValue> machine) {
        var payload = new LinkedHashMap<>(record.payload().values());
        payload.putAll(machine);
        payload.put("requestedQuantity", new Evidence.JsonNumber(BigDecimal.valueOf(requestedQuantity)));
        payload.put("batches", batches == null ? new Evidence.JsonNull() : new Evidence.JsonNumber(BigDecimal.valueOf(batches)));
        if (sharedBatch(record)) {
            payload.put("operationSemantics", new Evidence.JsonString("brewing: up to 3 bottles per operation, one reagent per operation; "
                    + "operation count depends on bottle grouping and is not computed"));
        }
        if (batches != null && outputQuantity != null) {
            var produced = multiply(batches, outputQuantity);
            payload.put("producedQuantity", produced == null ? new Evidence.JsonNull() : new Evidence.JsonNumber(BigDecimal.valueOf(produced)));
        } else {
            payload.put("producedQuantity", new Evidence.JsonNull());
        }
        var source = bound == null ? record.source() : new Evidence.Source(record.source().scope(), record.source().providerId(),
                record.source().providerVersion(), record.source().sourceHandle(), record.source().observedGeneration(), record.source().observedAt(),
                record.source().activity(), Evidence.Coverage.PARTIAL, record.source().authority(),
                Stream.concat(record.source().provenance().stream(), Stream.of(bound)).toList());
        return new Evidence.Record(source, record.kind(), record.title(), new Evidence.JsonObject(payload), record.targets(), record.recipePayload());
    }

    private static boolean sharedBatch(Evidence.Record record) {
        return record.recipePayload() != null && record.recipePayload().constraints().stream().anyMatch(value -> value.startsWith("brewing-batch="));
    }

    private static Long batches(long quantity, int outputQuantity) {
        if (outputQuantity < 1) return null;
        var result = quantity / outputQuantity;
        return quantity % outputQuantity == 0 ? result : result == Long.MAX_VALUE ? null : result + 1;
    }

    private static Long multiply(long left, long right) {
        if (left < 0 || right < 0 || left != 0 && right > Long.MAX_VALUE / left) return null;
        return left * right;
    }

    private static Long add(long left, long right) {
        return right > Long.MAX_VALUE - left ? null : left + right;
    }

    private record RequirementKey(String type, String identity, Evidence.JsonObject components) {
    }

    /** Planning conventions a mod machine record declares through its constraints; native records declare none. */
    private record MachineRules(boolean assumed, boolean extended, Map<String, String> containers, Set<String> chanceGroups) {
        static MachineRules of(Evidence.Record record) {
            var payload = record.recipePayload();
            boolean assumed = false;
            boolean guaranteed = false;
            var containers = new LinkedHashMap<String, String>();
            var chanceGroups = new HashSet<String>();
            for (String constraint : payload.constraints()) {
                if (constraint.equals("consumption=assumed")) assumed = true;
                else if (constraint.equals("output=assumed-guaranteed")) guaranteed = true;
                else if (constraint.startsWith("container-returned=")) {
                    var value = constraint.substring("container-returned=".length());
                    int separator = value.indexOf('>');
                    if (separator > 0 && separator < value.length() - 1) containers.put(value.substring(0, separator), value.substring(separator + 1));
                } else if (constraint.startsWith("chance:") && constraint.indexOf('=') > "chance:".length()) {
                    chanceGroups.add(constraint.substring("chance:".length(), constraint.indexOf('=')));
                }
            }
            boolean fluid = payload.inputs().stream().flatMap(slot -> slot.alternatives().stream()).anyMatch(value -> value.type().equals("fluid"));
            return new MachineRules(assumed, assumed || guaranteed || fluid || !containers.isEmpty() || !chanceGroups.isEmpty(), containers, chanceGroups);
        }

        /** A consumed fluid or container contents, listed as a need rather than expanded into routes. */
        boolean unexpanded(Evidence.RecipeAlternative alternative) {
            return alternative.consumption() == Evidence.Consumption.CONSUMED && alternative.quantity() != null
                    && (alternative.type().equals("fluid") || alternative.type().equals("item") && containers.containsKey(alternative.comparisonIdentity()));
        }

        boolean reusedCatalysts(Evidence.Record record) {
            var catalysts = record.recipePayload().catalysts();
            return assumed && !catalysts.isEmpty() && catalysts.stream().allMatch(value -> value.consumption() == Evidence.Consumption.REUSED);
        }

        /** Null when the planned output counts; otherwise why it cannot. The output's group comes from the payload JSON outputs[i].groupId. */
        String chanceReason(Evidence.Record record, Evidence.RecipeOutput output) {
            if (chanceGroups.isEmpty()) return null;
            int index = -1;
            for (int i = 0; i < record.recipePayload().outputs().size(); i++) if (record.recipePayload().outputs().get(i) == output) index = i;
            String group = null;
            if (index >= 0 && record.payload().values().get("outputs") instanceof Evidence.JsonArray outputs && index < outputs.values().size()
                    && outputs.values().get(index) instanceof Evidence.JsonObject value && value.values().get("groupId") instanceof Evidence.JsonString id) {
                group = id.value();
            }
            if (group == null) return "chance-output-group-unknown:" + record.source().sourceHandle();
            return chanceGroups.contains(group) ? "chance-output:" + record.source().sourceHandle() : null;
        }
    }

    private static final class PlanState {
        private final List<Evidence.Record> routes = new ArrayList<>();
        private final Set<String> reasons = new LinkedHashSet<>();
        private final Set<String> noRecipeLeaves = new LinkedHashSet<>();
        private final Set<String> uncheckedLeaves = new LinkedHashSet<>();
        private int nodes;
        private int branches;
        private final Set<String> omittedNoRecipeLeaves = new HashSet<>();
        // Bounded by the planner's node cap.
        private final Set<String> omittedUncheckedLeaves = new HashSet<>();
        // Reused machines and returned containers, needed once for the whole plan.
        private final Set<String> equipment = new LinkedHashSet<>();
        private boolean partial;

        private String equipmentLine() {
            return "equipment=" + List.copyOf(equipment);
        }

        private String detail() {
            var detail = new ArrayList<>(reasons.stream().limit(16).toList());
            if (reasons.size() > 16) detail.add("reason-cap=16; omitted=" + (reasons.size() - 16));
            if (!equipment.isEmpty()) detail.add(equipmentLine());
            if (!noRecipeLeaves.isEmpty()) detail.add("materials with no recipe here, obtained rather than crafted=" + noRecipeLeaves.stream().limit(16).toList());
            if (!uncheckedLeaves.isEmpty()) detail.add("materials with no recipe found, though a capped, unreadable or computed-result recipe could still make them="
                    + uncheckedLeaves);
            if (!omittedUncheckedLeaves.isEmpty()) detail.add("more materials with no recipe found, " + omittedUncheckedLeaves.size() + " beyond the 16 listed");
            if (!omittedNoRecipeLeaves.isEmpty()) detail.add("more materials with no recipe, " + omittedNoRecipeLeaves.size() + " beyond the 16 listed");
            if (detail.isEmpty()) detail.add("planning-stopped-before-route: node cap or unresolved recipe output");
            return String.join("; ", detail);
        }
    }
}
