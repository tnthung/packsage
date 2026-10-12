package dev.packsage.knowledge;

import dev.packsage.model.PackSageTools;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

public final class KnowledgeService {
    private final List<KnowledgeProvider> providers;
    private final Supplier<Evidence.Scope> currentScope;
    private final Function<RecipePlan, CompletionStage<Evidence.Result>> planner;
    private final BiFunction<List<String>, Evidence.Scope, CompletionStage<Evidence.Result>> inventory;
    private final Map<String, Evidence.Record> observed = new LinkedHashMap<>();
    private final Map<String, Cursor> cursors = new LinkedHashMap<>();
    private Evidence.Scope cacheScope;

    public record RecipePlan(String identity, long quantity, int depth, Evidence.Scope scope) { }
    private record Cursor(String tool, Map<String, Evidence.JsonValue> arguments, Evidence.Scope scope,
                          int providerIndex, String providerCursor) { }

    public KnowledgeService(List<KnowledgeProvider> providers, Supplier<Evidence.Scope> currentScope,
                            Function<RecipePlan, CompletionStage<Evidence.Result>> planner,
                            BiFunction<List<String>, Evidence.Scope, CompletionStage<Evidence.Result>> inventory) {
        this.providers = List.copyOf(providers);
        if (providers.stream().map(KnowledgeProvider::id).distinct().count() != providers.size()) throw new IllegalArgumentException("duplicate provider id");
        this.currentScope = Objects.requireNonNull(currentScope);
        this.planner = Objects.requireNonNull(planner);
        this.inventory = Objects.requireNonNull(inventory);
    }

    public CompletionStage<Evidence.Result> execute(PackSageTools.ReadRequest request) {
        if (!request.scope().equals(currentScope.get())) return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null));
        synchronized (this) {
            if (!request.scope().equals(cacheScope)) {
                cacheScope = request.scope();
                observed.clear();
                cursors.clear();
            }
        }
        try {
            var arguments = request.arguments().values();
            CompletionStage<Evidence.Result> result;
            switch (request.tool()) {
                case "read_evidence" -> {
                    var records = new ArrayList<Evidence.Record>();
                    synchronized (this) {
                        for (var handle : ((Evidence.JsonArray) arguments.get("handles")).values()) {
                            var record = observed.get(((Evidence.JsonString) handle).value());
                            if (record == null || !record.source().scope().equals(request.scope())) {
                                return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null));
                            }
                            records.add(record);
                        }
                    }
                    var partial = records.stream().anyMatch(record -> record.source().coverage() != Evidence.Coverage.COMPLETE);
                    result = CompletableFuture.completedFuture(new Evidence.Result(records, partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                            partial ? Evidence.Outcome.PARTIAL : Evidence.Outcome.COMPLETE, null));
                }
                case "read_progress" -> {
                    var records = new ArrayList<Evidence.Record>();
                    var providersForHandles = new ArrayList<KnowledgeProvider>();
                    synchronized (this) {
                        for (var handle : ((Evidence.JsonArray) arguments.get("handles")).values()) {
                            var record = observed.get(((Evidence.JsonString) handle).value());
                            if (record == null || !record.source().scope().equals(request.scope())
                                    || record.kind() != Evidence.RecordKind.QUEST) {
                                return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null));
                            }
                            var provider = providers.stream().filter(value -> value.id().equals(record.source().providerId())).findFirst().orElse(null);
                            records.add(record);
                            providersForHandles.add(provider);
                        }
                    }
                    var refreshed = CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE,
                            Evidence.Outcome.COMPLETE, null));
                    for (var index = 0; index < records.size(); index++) {
                        var selectedIndex = index;
                        refreshed = refreshed.thenCompose(previous -> {
                            var observedRecord = records.get(selectedIndex);
                            CompletionStage<Evidence.Result> current;
                            if (providersForHandles.get(selectedIndex) == null) {
                                current = CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE,
                                        Evidence.Outcome.UNKNOWN, null));
                            } else {
                                var queryArguments = Map.of("id", (Evidence.JsonValue) new Evidence.JsonString(
                                        observedRecord.source().sourceHandle()));
                                try {
                                    current = providersForHandles.get(selectedIndex).query(new Evidence.Query(Evidence.Operation.GET,
                                            new Evidence.JsonObject(queryArguments), 1, null, null), request.scope());
                                } catch (RuntimeException failure) {
                                    current = CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.ERROR,
                                            Evidence.Outcome.ERROR, null));
                                }
                            }
                            return current.handle((retrieved, failure) -> {
                                Evidence.Coverage failureCoverage = null;
                                Evidence.Outcome failureOutcome = null;
                                String failureDetail = null;
                                Evidence.Record currentRecord = null;
                                if (failure != null || retrieved == null) {
                                    failureCoverage = Evidence.Coverage.ERROR;
                                    failureOutcome = Evidence.Outcome.ERROR;
                                    failureDetail = "provider progress query failed";
                                } else if (retrieved.coverage() == Evidence.Coverage.ERROR
                                        || retrieved.coverage() == Evidence.Coverage.UNAVAILABLE) {
                                    failureCoverage = retrieved.coverage();
                                    failureOutcome = retrieved.outcome();
                                    failureDetail = retrieved.coverage() == Evidence.Coverage.UNAVAILABLE
                                            ? "provider progress is unavailable" : "provider progress query failed";
                                } else if (retrieved.records().isEmpty()) {
                                    failureCoverage = retrieved.coverage() == Evidence.Coverage.COMPLETE
                                            ? Evidence.Coverage.UNAVAILABLE : retrieved.coverage();
                                    failureOutcome = retrieved.coverage() == Evidence.Coverage.COMPLETE
                                            ? Evidence.Outcome.UNKNOWN : retrieved.outcome();
                                    failureDetail = retrieved.coverage() == Evidence.Coverage.COMPLETE
                                            ? "quest is absent from the current provider snapshot" : "current quest progress is incomplete";
                                } else if (retrieved.records().size() != 1) {
                                    failureCoverage = Evidence.Coverage.ERROR;
                                    failureOutcome = Evidence.Outcome.ERROR;
                                    failureDetail = "provider returned an invalid quest result";
                                } else {
                                    currentRecord = retrieved.records().get(0);
                                    if (!currentRecord.source().scope().equals(request.scope())
                                            || !currentRecord.source().providerId().equals(observedRecord.source().providerId())
                                            || !currentRecord.source().sourceHandle().equals(observedRecord.source().sourceHandle())
                                            || currentRecord.kind() != Evidence.RecordKind.QUEST) {
                                        currentRecord = null;
                                        failureCoverage = Evidence.Coverage.ERROR;
                                        failureOutcome = Evidence.Outcome.ERROR;
                                        failureDetail = "provider returned a stale or mismatched quest result";
                                    }
                                }
                                if (failureCoverage != null) {
                                    var statusHandle = "progress-status:" + observedRecord.source().sourceHandle();
                                    var payload = new Evidence.JsonObject(Map.of(
                                            "handle", new Evidence.JsonString(observedRecord.source().sourceHandle()),
                                            "status", new Evidence.JsonString(failureCoverage.name()),
                                            "outcome", new Evidence.JsonString(failureOutcome.name()),
                                            "detail", new Evidence.JsonString(failureDetail)));
                                    var status = new Evidence.Record(new Evidence.Source(request.scope(),
                                            observedRecord.source().providerId(), observedRecord.source().providerVersion(), statusHandle,
                                            request.scope().requestGeneration(), Instant.now(), Evidence.Activity.UNKNOWN,
                                            failureCoverage, Evidence.Authority.UNKNOWN, List.of("read_progress")),
                                            Evidence.RecordKind.OTHER, "Quest progress refresh unavailable", payload, List.of());
                                    var recordsWithStatus = new ArrayList<>(previous.records());
                                    recordsWithStatus.add(status);
                                    return new Evidence.Result(recordsWithStatus, Evidence.Coverage.PARTIAL,
                                            Evidence.Outcome.PARTIAL, null);
                                }
                                var refreshedRecords = new ArrayList<>(previous.records());
                                refreshedRecords.add(currentRecord);
                                var partial = previous.coverage() != Evidence.Coverage.COMPLETE
                                        || retrieved.coverage() != Evidence.Coverage.COMPLETE
                                        || previous.outcome() != Evidence.Outcome.COMPLETE
                                        || retrieved.outcome() != Evidence.Outcome.COMPLETE;
                                return new Evidence.Result(refreshedRecords,
                                        partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                                        partial ? Evidence.Outcome.PARTIAL : Evidence.Outcome.COMPLETE, null);
                            });
                        });
                    }
                    result = refreshed;
                }
                case "plan_recipe" -> result = planner.apply(new RecipePlan(((Evidence.JsonString) arguments.get("identity")).value(),
                        ((Evidence.JsonNumber) arguments.get("quantity")).value().longValueExact(),
                        arguments.get("depth") instanceof Evidence.JsonNumber number ? number.value().intValueExact() : 4, request.scope()));
                case "read_inventory" -> result = inventory.apply(((Evidence.JsonArray) arguments.get("identities")).values().stream()
                        .map(value -> ((Evidence.JsonString) value).value()).toList(), request.scope());
                case "read_config" -> {
                    Evidence.Record observedConfig;
                    synchronized (this) { observedConfig = observed.get(((Evidence.JsonString) arguments.get("handle")).value()); }
                    if (observedConfig == null || observedConfig.kind() != Evidence.RecordKind.CONFIG) return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null));
                    var provider = providers.stream().filter(value -> value.id().equals(observedConfig.source().providerId())).findFirst().orElse(null);
                    if (provider == null) return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null));
                    var queryArguments = new LinkedHashMap<String, Evidence.JsonValue>();
                    queryArguments.put("id", observedConfig.payload().values().get("handle"));
                    var key = arguments.get("key");
                    queryArguments.put(key instanceof Evidence.JsonString ? "key" : "text", key instanceof Evidence.JsonString ? key : arguments.get("section"));
                    int limit = arguments.get("limit") instanceof Evidence.JsonNumber number ? number.value().intValueExact() : 16;
                    result = provider.query(new Evidence.Query(key instanceof Evidence.JsonString ? Evidence.Operation.GET : Evidence.Operation.SEARCH,
                            new Evidence.JsonObject(queryArguments), limit, null, null), request.scope());
                }
                case "search_pack", "list_configs", "search_config" -> {
                    var signature = new LinkedHashMap<>(arguments);
                    signature.remove("cursor");
                    Cursor cursor = null;
                    if (arguments.get("cursor") instanceof Evidence.JsonString text) {
                        synchronized (this) { cursor = cursors.get(text.value()); }
                        if (cursor == null || !cursor.scope().equals(request.scope()) || !cursor.tool().equals(request.tool())
                                || !cursor.arguments().equals(signature)) return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null));
                    }
                    int limit = arguments.get("limit") instanceof Evidence.JsonNumber number ? number.value().intValueExact() : 16;
                    result = search(request, signature, cursor == null ? 0 : cursor.providerIndex(), cursor == null ? null : cursor.providerCursor(),
                            limit, new ArrayList<>(), false);
                }
                default -> { return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null)); }
            }
            return result.handle((retrieved, failure) -> {
                if (failure != null || retrieved == null || !request.scope().equals(currentScope.get())
                        || retrieved.records().stream().anyMatch(record -> !record.source().scope().equals(request.scope()))) {
                    return new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null);
                }
                synchronized (this) {
                    if (!request.scope().equals(cacheScope) || !request.scope().equals(currentScope.get())) return new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null);
                    for (var record : retrieved.records()) {
                        observed.put(record.source().providerId() + ":" + record.source().sourceHandle(), record);
                        while (observed.size() > 512) observed.remove(observed.keySet().iterator().next());
                    }
                }
                return retrieved;
            });
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null));
        }
    }

    private CompletionStage<Evidence.Result> search(PackSageTools.ReadRequest request, Map<String, Evidence.JsonValue> signature,
                                                    int providerIndex, String providerCursor, int limit,
                                                    List<Evidence.Record> records, boolean partial) {
        if (!request.scope().equals(currentScope.get())) return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null));
        if (providerIndex == providers.size()) return CompletableFuture.completedFuture(new Evidence.Result(records,
                partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE, partial ? Evidence.Outcome.PARTIAL : Evidence.Outcome.COMPLETE, null));
        var provider = providers.get(providerIndex);
        if (!request.tool().equals("search_pack") && !provider.id().equals("config")) return search(request, signature, providerIndex + 1, null, limit, records, partial);
        var arguments = new LinkedHashMap<String, Evidence.JsonValue>();
        arguments.put("text", signature.getOrDefault("text", new Evidence.JsonString("")));
        CompletionStage<Evidence.Result> page;
        try {
            page = provider.query(new Evidence.Query(Evidence.Operation.SEARCH, new Evidence.JsonObject(arguments),
                    Math.max(1, limit - records.size()), providerCursor == null ? null : request.scope(), providerCursor), request.scope());
        } catch (RuntimeException failure) {
            page = CompletableFuture.failedFuture(failure);
        }
        return page.handle((result, failure) -> {
            if (failure != null || result == null) {
                return new Evidence.Result(List.of(new Evidence.Record(new Evidence.Source(request.scope(), provider.id(),
                        "unknown", "status:" + provider.id(), request.scope().requestGeneration(), Instant.now(), Evidence.Activity.UNKNOWN,
                        Evidence.Coverage.ERROR, Evidence.Authority.UNKNOWN, List.of()), Evidence.RecordKind.OTHER,
                        provider.id() + " extraction failed", new Evidence.JsonObject(Map.of("detail", new Evidence.JsonString("provider extraction failed"))), List.of())),
                        Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null);
            }
            return result;
        }).thenCompose(pageResult -> {
            if (!request.scope().equals(currentScope.get()) || pageResult.records().stream().anyMatch(record -> !record.source().scope().equals(request.scope()))) return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null));
            var selectedKinds = signature.get("kinds") instanceof Evidence.JsonArray kinds ? kinds.values() : List.<Evidence.JsonValue>of();
            for (var record : pageResult.records()) {
                if ((selectedKinds.isEmpty() || selectedKinds.stream().anyMatch(kind -> ((Evidence.JsonString) kind).value().equals(record.kind().name())))
                        && records.size() < limit) records.add(record);
            }
            boolean incomplete = partial || pageResult.coverage() != Evidence.Coverage.COMPLETE;
            if (records.size() >= limit || pageResult.nextCursor() != null) {
                int nextProvider = pageResult.nextCursor() == null ? providerIndex + 1 : providerIndex;
                String token = null;
                if (nextProvider < providers.size()) {
                    token = UUID.randomUUID().toString();
                    synchronized (this) {
                        if (!request.scope().equals(cacheScope)) return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null));
                        cursors.put(token, new Cursor(request.tool(), Map.copyOf(signature), request.scope(), nextProvider, pageResult.nextCursor()));
                        while (cursors.size() > 256) cursors.remove(cursors.keySet().iterator().next());
                    }
                }
                return CompletableFuture.completedFuture(new Evidence.Result(records, incomplete ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                        incomplete ? Evidence.Outcome.PARTIAL : Evidence.Outcome.COMPLETE, token));
            }
            return search(request, signature, providerIndex + 1, null, limit, records, incomplete);
        });
    }

    public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
        if (!scope.equals(currentScope.get()) || !target.scope().equals(scope)) return new Evidence.NavigationResult(Evidence.NavigationStatus.STALE, "observation scope changed");
        synchronized (this) {
            var record = observed.get(target.providerId() + ":" + target.sourceHandle());
            if (record == null || !record.targets().contains(target)) return new Evidence.NavigationResult(Evidence.NavigationStatus.STALE, "navigation target is no longer observed");
        }
        return providers.stream().filter(provider -> provider.id().equals(target.providerId())).findFirst()
                .map(provider -> provider.open(target, scope)).orElseGet(() -> new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "provider unavailable"));
    }
}
