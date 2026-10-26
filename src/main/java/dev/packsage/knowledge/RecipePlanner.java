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
                complete && !state.partial ? null : state.detail());
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
                state.routes.add(planned(candidate, quantity, output == null ? null : output.quantity(), output == null || output.quantity() == null
                        || sharedBatch(candidate) ? null : batches(quantity, output.quantity()), "bounded-depth"));
            } else if (!cycle.isEmpty()) state.reasons.add("node-cap=" + MAX_NODES);
            return false;
        }
        var candidates = index.recipeOutputs(itemIdentity, components, scope);
        if (candidates.isEmpty()) {
            visiting.remove(key);
            state.partial = true;
            if (state.noRecipeLeaves.size() < 16) state.noRecipeLeaves.add(itemIdentity);
            else state.omittedNoRecipeLeaves++;
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
            var batches = output == null || output.quantity() == null || sharedBatch(candidate) ? null : batches(quantity, output.quantity());
            boolean candidateComplete = output != null && batches != null
                    && candidate.source().coverage() == Evidence.Coverage.COMPLETE;
            if (output == null) state.reasons.add("unknown-output:" + candidate.source().sourceHandle());
            else if (output.quantity() == null || output.quantity() < 1) state.reasons.add("unknown-output-quantity:" + candidate.source().sourceHandle());
            if (sharedBatch(candidate)) state.reasons.add("operation-count-unknown:" + candidate.source().sourceHandle());
            else if (batches == null && output != null && output.quantity() != null && output.quantity() > 0)
                state.reasons.add("batch-count-overflow:" + candidate.source().sourceHandle());
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
            Evidence.Record planned = planned(candidate, quantity, output == null ? null : output.quantity(), batches, depth <= 0 ? "bounded-depth" : null);
            state.routes.add(planned);
            if (depth <= 0) {
                anyIncomplete = true;
                state.partial = true;
                state.reasons.add("bounded-depth:" + itemIdentity);
                continue;
            }
            if (output == null || batches == null) candidateComplete = false;
            var required = new LinkedHashMap<RequirementKey, Long>();
            for (Evidence.RecipeSlot slot : candidate.recipePayload().inputs()) {
                if (slot.alternatives().isEmpty()) {
                    candidateComplete = false;
                    state.partial = true;
                    state.reasons.add("empty-input-slot:" + candidate.source().sourceHandle() + ":" + slot.groupId());
                } else if (slot.alternatives().size() == 1) {
                    var alternative = slot.alternatives().getFirst();
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
                    boolean alternativeComplete = false;
                    boolean alternativeIncomplete = false;
                    for (Evidence.RecipeAlternative alternative : slot.alternatives()) {
                        if (state.branches >= MAX_BRANCHES) {
                            alternativeIncomplete = true;
                            state.partial = true;
                            state.reasons.add("ingredient-branch-cap=" + MAX_BRANCHES);
                            break;
                        }
                        state.branches++;
                        if (alternative.consumption() != Evidence.Consumption.CONSUMED || alternative.quantity() == null
                                || batches == null || !alternative.type().equals("item")) {
                            alternativeIncomplete = true;
                            state.reasons.add("unknown-consumption-or-requirement:" + candidate.source().sourceHandle() + ":" + slot.groupId());
                        } else {
                            var requiredQuantity = multiply(alternative.quantity(), batches);
                            alternativeComplete |= requiredQuantity != null && visit(alternative.comparisonIdentity(), alternative.components(),
                                    requiredQuantity, scope, depth - 1, visiting, state);
                            alternativeIncomplete |= requiredQuantity == null;
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
            if (!candidate.recipePayload().catalysts().isEmpty()) {
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

    private static Evidence.Record planned(Evidence.Record record, long requestedQuantity, Integer outputQuantity, Long batches, String bound) {
        var payload = new LinkedHashMap<>(record.payload().values());
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

    private static final class PlanState {
        private final List<Evidence.Record> routes = new ArrayList<>();
        private final Set<String> reasons = new LinkedHashSet<>();
        private final Set<String> noRecipeLeaves = new LinkedHashSet<>();
        private int nodes;
        private int branches;
        private int omittedNoRecipeLeaves;
        private boolean partial;

        private String detail() {
            var detail = new ArrayList<>(reasons.stream().limit(16).toList());
            if (reasons.size() > 16) detail.add("reason-cap=16; omitted=" + (reasons.size() - 16));
            if (!noRecipeLeaves.isEmpty()) detail.add("no-recipe-leaves=" + noRecipeLeaves.stream().limit(16).toList());
            if (omittedNoRecipeLeaves > 0) detail.add("no-recipe-leaf-cap=16; omitted-at-least=" + omittedNoRecipeLeaves);
            if (detail.isEmpty()) detail.add("planning-stopped-before-route: node cap or unresolved recipe output");
            return String.join("; ", detail);
        }
    }
}
