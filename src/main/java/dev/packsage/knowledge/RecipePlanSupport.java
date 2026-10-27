package dev.packsage.knowledge;

import dev.packsage.model.PackSageTools;
import java.util.ArrayList;

public final class RecipePlanSupport {
    private RecipePlanSupport() { }

    public record Arguments(String identity, long quantity, int depth) { }

    public static Arguments arguments(PackSageTools.ReadRequest request) {
        var identityValue = request.arguments().values().get("identity");
        if (identityValue != null && !(identityValue instanceof Evidence.JsonNull) && !(identityValue instanceof Evidence.JsonString))
            throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "identity must be a string");
        var identity = identityValue instanceof Evidence.JsonString value ? value.value() : null;
        if (identity == null) throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "identity is required");
        var quantityValue = request.arguments().values().get("quantity");
        var depthValue = request.arguments().values().get("depth");
        if (quantityValue != null && !(quantityValue instanceof Evidence.JsonNull) && !(quantityValue instanceof Evidence.JsonNumber))
            throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "quantity must be an integer");
        if (depthValue != null && !(depthValue instanceof Evidence.JsonNull) && !(depthValue instanceof Evidence.JsonNumber))
            throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "depth must be an integer");
        try {
            var quantity = quantityValue instanceof Evidence.JsonNumber number ? number.value().longValueExact() : 1;
            var depth = depthValue instanceof Evidence.JsonNumber number ? number.value().intValueExact() : 4;
            if (quantity < 1 || quantity > 1_000_000 || depth < 0 || depth > 8)
                throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "recipe quantity or depth is outside bounds");
            return new Arguments(identity, quantity, depth);
        } catch (ArithmeticException failure) {
            throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "quantity and depth must be integers");
        }
    }

    public static Evidence.Result wrap(Evidence.Result result, String identity, long quantity, int depth) {
        var records = new ArrayList<Evidence.Record>();
        for (int route = 0; route < result.records().size(); route++) {
            var record = result.records().get(route);
            var handle = "plan|" + identity + "|" + quantity + "|" + depth + "|" + route + "|" + record.source().sourceHandle();
            var source = record.source();
            records.add(new Evidence.Record(new Evidence.Source(source.scope(), source.providerId(), source.providerVersion(), handle,
                    source.observedGeneration(), source.observedAt(), source.activity(), source.coverage(), source.authority(), source.provenance()),
                    record.kind(), record.title(), record.payload(), record.targets().stream().map(target -> new Evidence.NavigationTarget(
                    target.scope(), target.providerId(), handle, target.targetId())).toList(), record.recipePayload()));
        }
        return new Evidence.Result(records, result.coverage(), result.outcome(), result.nextCursor(), result.detail());
    }
}
