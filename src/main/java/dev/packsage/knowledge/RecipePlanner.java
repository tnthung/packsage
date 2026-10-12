package dev.packsage.knowledge;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class RecipePlanner {
    private static final int MAX_NODES = 256;
    private static final int MAX_BRANCHES = 64;
    private final KnowledgeIndex index;

    public RecipePlanner(KnowledgeIndex index) {
        this.index = index;
    }

    public Evidence.Result plan(String itemIdentity, long quantity, Evidence.Scope scope, int maxDepth) {
        if (quantity < 1 || maxDepth < 0) return new Evidence.Result(List.of(), Evidence.Coverage.PARTIAL, Evidence.Outcome.PARTIAL, null);
        if (!index.available(scope)) return new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null);
        var state = new PlanState();
        boolean complete = visit(itemIdentity, new Evidence.JsonObject(Map.of()), quantity, scope, maxDepth,
                new HashSet<>(), state);
        if (state.routes.isEmpty()) return new Evidence.Result(List.of(), Evidence.Coverage.PARTIAL, Evidence.Outcome.PARTIAL, null);
        return new Evidence.Result(state.routes, complete && !state.partial ? Evidence.Coverage.COMPLETE : Evidence.Coverage.PARTIAL,
                complete && !state.partial ? Evidence.Outcome.COMPLETE : Evidence.Outcome.PARTIAL, null);
    }

    private boolean visit(String itemIdentity, Evidence.JsonObject components, long quantity, Evidence.Scope scope,
                          int depth, Set<RequirementKey> visiting, PlanState state) {
        var key = new RequirementKey("item", itemIdentity, components);
        if (!visiting.add(key)) {
            state.partial = true;
            var cycle = index.recipeOutputs(itemIdentity, components, scope);
            if (!cycle.isEmpty() && state.nodes++ < MAX_NODES) {
                var candidate = cycle.getFirst();
                var output = candidate.recipePayload().outputs().stream().filter(value -> itemIdentity.equals(value.comparisonIdentity())).findFirst().orElse(null);
                state.routes.add(planned(candidate, quantity, output == null ? null : output.quantity(), output == null || output.quantity() == null ? null
                        : batches(quantity, output.quantity()), "bounded-depth"));
            }
            return false;
        }
        var candidates = index.recipeOutputs(itemIdentity, components, scope);
        if (candidates.isEmpty()) {
            visiting.remove(key);
            state.partial = true;
            return false;
        }
        boolean anyComplete = false;
        boolean anyIncomplete = candidates.size() > MAX_BRANCHES;
        int branchCount = 0;
        for (Evidence.Record candidate : candidates) {
            if (branchCount++ == MAX_BRANCHES || state.nodes++ == MAX_NODES) {
                anyIncomplete = true;
                state.partial = true;
                break;
            }
            var output = candidate.recipePayload().outputs().stream().filter(value -> itemIdentity.equals(value.comparisonIdentity())
                    && (components.values().isEmpty() || components.equals(value.components()))).findFirst().orElse(null);
            var batches = output == null || output.quantity() == null ? null : batches(quantity, output.quantity());
            boolean candidateComplete = output != null && batches != null;
            Evidence.Record planned = planned(candidate, quantity, output == null ? null : output.quantity(), batches, depth <= 0 ? "bounded-depth" : null);
            state.routes.add(planned);
            if (depth <= 0) {
                anyIncomplete = true;
                state.partial = true;
                continue;
            }
            if (output == null || batches == null || candidate.recipePayload().decodingStatus() != Evidence.DecodingStatus.VERIFIED
                    || candidate.recipePayload().constraints().stream().anyMatch(value -> value.toLowerCase().contains("unknown"))) {
                candidateComplete = false;
            }
            var required = new LinkedHashMap<RequirementKey, Long>();
            for (Evidence.RecipeSlot slot : candidate.recipePayload().inputs()) {
                if (slot.alternatives().isEmpty()) {
                    candidateComplete = false;
                    state.partial = true;
                } else if (slot.alternatives().size() == 1) {
                    var alternative = slot.alternatives().getFirst();
                    if (alternative.consumption() != Evidence.Consumption.CONSUMED || alternative.quantity() == null
                            || batches == null || !alternative.type().equals("item")) {
                        candidateComplete = false;
                        state.partial = true;
                    } else {
                        var requiredQuantity = multiply(alternative.quantity(), batches);
                        if (requiredQuantity == null) {
                            candidateComplete = false;
                            state.partial = true;
                        } else {
                            var requirement = new RequirementKey(alternative.type(), alternative.comparisonIdentity(), alternative.components());
                            var previous = required.get(requirement);
                            var total = previous == null ? requiredQuantity : add(previous, requiredQuantity);
                            if (total == null) {
                                candidateComplete = false;
                                state.partial = true;
                            } else {
                                required.put(requirement, total);
                            }
                        }
                    }
                } else {
                    boolean alternativeComplete = false;
                    boolean alternativeIncomplete = false;
                    for (Evidence.RecipeAlternative alternative : slot.alternatives()) {
                        if (state.branches++ == MAX_BRANCHES) {
                            alternativeIncomplete = true;
                            state.partial = true;
                            break;
                        }
                        if (alternative.consumption() != Evidence.Consumption.CONSUMED || alternative.quantity() == null
                                || batches == null || !alternative.type().equals("item")) {
                            alternativeIncomplete = true;
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
        private int nodes;
        private int branches;
        private boolean partial;
    }
}
