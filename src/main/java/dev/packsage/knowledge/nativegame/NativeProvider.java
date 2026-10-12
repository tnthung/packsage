package dev.packsage.knowledge.nativegame;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeIndex;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.knowledge.RecipePlanner;

import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

public final class NativeProvider implements KnowledgeProvider {
    private static final String VERSION = "1.21.1";
    private final Supplier<NativeSnapshot> snapshotSource;
    private final Minecraft client;
    private final Executor executor;
    private final AtomicLong querySequence = new AtomicLong();
    private volatile NativeSnapshot current;

    public NativeProvider(Supplier<NativeSnapshot> snapshotSource, Executor executor) {
        this.snapshotSource = Objects.requireNonNull(snapshotSource);
        this.client = null;
        this.executor = Objects.requireNonNull(executor);
    }

    public NativeProvider(Minecraft client, Executor executor) {
        this.snapshotSource = null;
        this.client = Objects.requireNonNull(client);
        this.executor = Objects.requireNonNull(executor);
    }

    @Override
    public String id() {
        return "native";
    }

    @Override
    public ProviderStatus status() {
        var snapshot = current;
        if (snapshot == null) return new ProviderStatus(Availability.UNAVAILABLE, VERSION, "native client evidence not captured");
        var coverage = snapshot.records().stream().anyMatch(record -> record.source().coverage() == Evidence.Coverage.ERROR)
                ? Availability.ERROR : snapshot.records().stream().anyMatch(record -> record.source().coverage() == Evidence.Coverage.UNAVAILABLE)
                ? Availability.UNAVAILABLE : snapshot.records().stream().anyMatch(record -> record.source().coverage() != Evidence.Coverage.COMPLETE)
                ? Availability.PARTIAL : Availability.AVAILABLE;
        return new ProviderStatus(coverage, VERSION, "client-visible copied evidence");
    }

    @Override
    public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
        Objects.requireNonNull(query);
        Objects.requireNonNull(scope);
        return execute(scope, null, index -> index.search(query, scope).toCompletableFuture().join(), false, true);
    }

    public CompletionStage<Evidence.Result> planRecipe(String itemIdentity, long quantity, Evidence.Scope scope, int maxDepth) {
        Objects.requireNonNull(itemIdentity);
        Objects.requireNonNull(scope);
        return execute(scope, null, index -> new RecipePlanner(index).plan(itemIdentity, quantity, scope, maxDepth), false, true);
    }

    public CompletionStage<Evidence.Result> readInventory(List<String> identities, Evidence.Scope scope) {
        Objects.requireNonNull(identities);
        Objects.requireNonNull(scope);
        if (identities.isEmpty() || identities.size() > 32 || identities.stream().anyMatch(identity -> identity == null || identity.isBlank())) {
            return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null));
        }
        var requested = List.copyOf(identities);
        return execute(scope, requested, index -> index.search(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(java.util.Map.of()), 64, null, null), scope).toCompletableFuture().join(), client != null, false);
    }

    private CompletionStage<Evidence.Result> execute(Evidence.Scope scope, List<String> selectedIdentities,
                                                     Function<KnowledgeIndex, Evidence.Result> operation,
                                                     boolean emptySelectionIsComplete, boolean publishSnapshot) {
        long sequence = querySequence.incrementAndGet();
        CompletionStage<Captured> captured;
        try {
            captured = capture(scope, selectedIdentities);
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null));
        }
        return captured.handle((result, failure) -> new CaptureResult(result, failure)).thenApplyAsync(result -> {
            if (result.failure() != null || result.captured() == null) return new Indexed(null, null,
                    new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null));
            if (sequence != querySequence.get()) return new Indexed(result.captured().snapshot(), result.captured().levelToken(),
                    new Evidence.Result(List.of(), Evidence.Coverage.PARTIAL, Evidence.Outcome.UNKNOWN, null));
            if (result.captured().snapshot() == null || !result.captured().snapshot().scope().equals(scope)) return new Indexed(
                    result.captured().snapshot(), result.captured().levelToken(), new Evidence.Result(List.of(), Evidence.Coverage.PARTIAL,
                            Evidence.Outcome.UNKNOWN, null));
            if (emptySelectionIsComplete && result.captured().snapshot().records().isEmpty()) return new Indexed(
                    result.captured().snapshot(), result.captured().levelToken(), new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE,
                            Evidence.Outcome.COMPLETE, null));
            try {
                var index = new KnowledgeIndex();
                index.replace(result.captured().snapshot());
                return new Indexed(result.captured().snapshot(), result.captured().levelToken(), operation.apply(index));
            } catch (RuntimeException failure) {
                return new Indexed(result.captured().snapshot(), result.captured().levelToken(),
                        new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null));
            }
        }, executor).thenCompose(indexed -> publish(indexed, scope, sequence, publishSnapshot));
    }

    private CompletionStage<Captured> capture(Evidence.Scope scope, List<String> selectedIdentities) {
        if (client == null) return CompletableFuture.supplyAsync(() -> {
            var snapshot = NativeSnapshot.capture(snapshotSource);
            return new Captured(selectedIdentities == null ? snapshot : selectedInventory(snapshot, selectedIdentities), 0);
        }, executor);
        return client.submit(() -> {
            var snapshot = selectedIdentities == null ? NativeSnapshot.capture(client, scope)
                    : NativeSnapshot.captureSelectedInventory(client, scope, selectedIdentities);
            return new Captured(snapshot, client.level == null ? 0 : System.identityHashCode(client.level));
        });
    }

    private CompletionStage<Evidence.Result> publish(Indexed indexed, Evidence.Scope scope, long sequence, boolean publishSnapshot) {
        if (client == null) {
            if (publishSnapshot && indexed.snapshot() != null && indexed.snapshot().scope().equals(scope)) current = indexed.snapshot();
            return CompletableFuture.completedFuture(indexed.result());
        }
        try {
            return client.submit(() -> {
                int levelToken = client.level == null ? 0 : System.identityHashCode(client.level);
                if (sequence != querySequence.get() || levelToken != indexed.levelToken()) return new Evidence.Result(List.of(),
                        Evidence.Coverage.PARTIAL, Evidence.Outcome.UNKNOWN, null);
                if (publishSnapshot && indexed.snapshot() != null && indexed.snapshot().scope().equals(scope)) current = indexed.snapshot();
                return indexed.result();
            });
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null));
        }
    }

    private static NativeSnapshot selectedInventory(NativeSnapshot snapshot, List<String> identities) {
        var requested = java.util.Set.copyOf(identities);
        var selected = snapshot.records().stream().filter(record -> record.kind() == Evidence.RecordKind.ITEM
                && "selected".equals(record.payload().values().get("inventoryObservation") instanceof Evidence.JsonString value ? value.value() : null)
                && requested.contains(record.payload().values().get("id") instanceof Evidence.JsonString value ? value.value() : null)).toList();
        return new NativeSnapshot(snapshot.scope(), selected);
    }

    @Override
    public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
        Objects.requireNonNull(target);
        Objects.requireNonNull(scope);
        var snapshot = current;
        if (!id().equals(target.providerId())) return new Evidence.NavigationResult(Evidence.NavigationStatus.UNSUPPORTED, "target belongs to another provider");
        if (!target.scope().equals(scope) || snapshot == null || !snapshot.scope().equals(scope)) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.STALE, "native evidence scope changed");
        }
        return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "native navigation is runtime-wired pending client bridge");
    }

    private record Captured(NativeSnapshot snapshot, int levelToken) {
    }

    private record CaptureResult(Captured captured, Throwable failure) {
    }

    private record Indexed(NativeSnapshot snapshot, int levelToken, Evidence.Result result) {
    }
}
