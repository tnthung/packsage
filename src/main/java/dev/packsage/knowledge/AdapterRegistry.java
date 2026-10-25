package dev.packsage.knowledge;

import dev.packsage.model.PackSageTools;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

public final class AdapterRegistry {
    private final List<Toolbox> adapters = new ArrayList<>();

    public void register(Toolbox adapter) {
        if (adapters.stream().anyMatch(value -> value.metadata().id().equals(adapter.metadata().id()))) {
            throw new IllegalArgumentException("duplicate adapter id: " + adapter.metadata().id());
        }
        adapters.add(adapter);
    }

    public List<Toolbox.Metadata> metadata() { return adapters.stream().map(Toolbox::metadata).toList(); }

    public CompletionStage<Evidence.Result> dispatch(PackSageTools.ReadRequest request, Toolbox.Context context) {
        return dispatch(request, context, 0);
    }

    private CompletionStage<Evidence.Result> dispatch(PackSageTools.ReadRequest request, Toolbox.Context context, int index) {
        if (!request.scope().equals(context.currentScope().get())) return CompletableFuture.failedFuture(
                new Toolbox.Failure(Toolbox.Code.STALE_SCOPE, "repeat the lookup in the current scope"));
        if (index == adapters.size()) return CompletableFuture.failedFuture(
                new Toolbox.Failure(Toolbox.Code.NOT_HANDLED, "no registered adapter handles this request"));
        CompletionStage<Evidence.Result> result;
        try {
            result = switch (request.tool()) {
                case "search_document" -> adapters.get(index).searchDocument(request, context);
                case "search_recipe" -> adapters.get(index).searchRecipe(request, context);
                case "search_registry" -> adapters.get(index).searchRegistry(request, context);
                case "read_evidence" -> adapters.get(index).readEvidence(request, context);
                case "read_progress" -> adapters.get(index).readProgress(request, context);
                case "plan_recipe" -> adapters.get(index).planRecipe(request, context);
                case "list_configs" -> adapters.get(index).listConfigs(request, context);
                case "search_config" -> adapters.get(index).searchConfig(request, context);
                case "read_config" -> adapters.get(index).readConfig(request, context);
                case "search_history" -> adapters.get(index).searchHistory(request, context);
                case "read_history" -> adapters.get(index).readHistory(request, context);
                default -> adapters.get(index).handle(request, context);
            };
        } catch (RuntimeException failure) {
            result = CompletableFuture.failedFuture(failure);
        }
        return result.handle((value, failure) -> {
            while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
            if (failure instanceof Toolbox.Failure typed && typed.code() == Toolbox.Code.NOT_HANDLED) {
                return dispatch(request, context, index + 1);
            }
            if (failure != null) return CompletableFuture.<Evidence.Result>failedFuture(failure);
            if (!request.scope().equals(context.currentScope().get())) return CompletableFuture.<Evidence.Result>failedFuture(
                    new Toolbox.Failure(Toolbox.Code.STALE_SCOPE, "scope changed while reading"));
            return CompletableFuture.completedFuture(value);
        }).thenCompose(value -> value);
    }
}
