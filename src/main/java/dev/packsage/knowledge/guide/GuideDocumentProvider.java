package dev.packsage.knowledge.guide;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.knowledge.Toolbox;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Shared provider stub used while the live guide traversal contract is developed. */
public final class GuideDocumentProvider implements KnowledgeProvider {
    private final GuideDocumentReader reader;

    public GuideDocumentProvider(GuideDocumentReader reader, Supplier<Evidence.Scope> currentScope, Executor clientExecutor) {
        this.reader = Objects.requireNonNull(reader);
        Objects.requireNonNull(currentScope);
        Objects.requireNonNull(clientExecutor);
    }

    @Override public String id() { return reader.providerId(); }

    @Override public Toolbox.Metadata metadata() { return new Toolbox.Metadata(id(), List.of("guide"), false, List.of(), Map.of()); }

    @Override public ProviderStatus status() { return new ProviderStatus(Availability.AVAILABLE, reader.version(), null); }

    @Override public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
        return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE,
                Evidence.Outcome.COMPLETE, null));
    }

    @Override public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
        return new Evidence.NavigationResult(Evidence.NavigationStatus.UNSUPPORTED, "guide navigation is unavailable");
    }

    @Override public CompletionStage<Evidence.Result> searchDocument(dev.packsage.model.PackSageTools.ReadRequest request,
                                                                      Toolbox.Context context) {
        return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE,
                Evidence.Outcome.COMPLETE, null));
    }
}
