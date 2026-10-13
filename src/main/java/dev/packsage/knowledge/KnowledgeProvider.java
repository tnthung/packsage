package dev.packsage.knowledge;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

public interface KnowledgeProvider {
    enum Availability { AVAILABLE, PARTIAL, OPAQUE, UNAVAILABLE, ERROR }

    record ProviderStatus(Availability availability, String version, String detail) {
        public ProviderStatus {
            Objects.requireNonNull(availability);
            Objects.requireNonNull(version);
        }
    }

    String id();

    /** Whether this logical provider is enabled for the current runtime, independent of coverage. */
    default boolean enabled() { return true; }

    ProviderStatus status();

    CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope);

    Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope);
}
