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

    ProviderStatus status();

    CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope);

    Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope);
}
