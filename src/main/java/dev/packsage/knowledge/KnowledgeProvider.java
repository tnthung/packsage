package dev.packsage.knowledge;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public interface KnowledgeProvider extends Toolbox {
    enum Availability { AVAILABLE, PARTIAL, OPAQUE, UNAVAILABLE, ERROR }

    record ProviderStatus(Availability availability, String version, String detail) {
        public ProviderStatus {
            Objects.requireNonNull(availability);
            Objects.requireNonNull(version);
        }
    }

    String id();

    @Override
    default Metadata metadata() { return new Metadata(id(), java.util.List.of(), false, java.util.List.of(), java.util.Map.of()); }

    /** Whether this logical provider is enabled for the current runtime, independent of coverage. */
    default boolean enabled() { return true; }

    ProviderStatus status();

    CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope);

    /** Resolves a saved destination against current data without authorizing model evidence reads. */
    default CompletionStage<Evidence.NavigationTarget> refreshNavigation(Evidence.NavigationTarget target, Evidence.Scope scope) {
        return query(new Evidence.Query(Evidence.Operation.GET, new Evidence.JsonObject(Map.of(
                "id", new Evidence.JsonString(target.sourceHandle()))), 1, null, null), scope).thenApply(result -> {
            if (result == null || result.outcome() == Evidence.Outcome.ERROR || result.records().size() != 1) return null;
            var record = result.records().getFirst();
            var current = new Evidence.NavigationTarget(scope, target.providerId(), target.sourceHandle(), target.targetId());
            return record.source().scope().equals(scope) && record.source().providerId().equals(id())
                    && id().equals(target.providerId()) && record.source().sourceHandle().equals(target.sourceHandle())
                    && record.targets().contains(current) ? current : null;
        });
    }

    Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope);
}
