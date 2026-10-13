package dev.packsage.knowledge;

import dev.packsage.PackSageLog;
import dev.packsage.model.PackSageTools;

import java.util.List;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

public final class KnowledgeService {
    /**
     * Upper bound on provider page requests made by one search_pack, list_configs or search_config call.
     * A call stops earlier when it has {@code limit} matching records or every eligible provider is exhausted.
     * Reaching this bound returns a cursor and a detail so the model can decide whether to continue.
     */
    public static final int MAX_PROVIDER_PAGES_PER_CALL = 8;
    private static final String SCOPE_CHANGED = "evidence scope changed (world, resources or providers reloaded); repeat the lookup";
    private static final String CONFIG_FORM = "config:<owner>:<SCOPE>:<file>#<key>";
    private static final Map<String, Set<Evidence.RecordKind>> PROVIDER_KINDS = Map.of(
            "native", EnumSet.of(Evidence.RecordKind.ITEM, Evidence.RecordKind.BLOCK, Evidence.RecordKind.RECIPE, Evidence.RecordKind.OTHER),
            "jei", EnumSet.of(Evidence.RecordKind.RECIPE),
            "ftb_quests", EnumSet.of(Evidence.RecordKind.QUEST),
            "config", EnumSet.of(Evidence.RecordKind.CONFIG),
            "patchouli", EnumSet.of(Evidence.RecordKind.GUIDE));
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
        if (!request.scope().equals(currentScope.get())) return failed(SCOPE_CHANGED);
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
                                return failed("handle " + shorten(((Evidence.JsonString) handle).value())
                                        + " was not observed in this request; use a reference returned by search_pack");
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
                                return failed("handle " + shorten(((Evidence.JsonString) handle).value())
                                        + " is not a quest observed in this request; use a QUEST reference returned by search_pack");
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
                                    PackSageLog.LOGGER.warn("PackSage provider {} failed during read_progress",
                                            providersForHandles.get(selectedIndex).id(), failure);
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
                                    if (failure != null) PackSageLog.LOGGER.warn("PackSage provider {} failed during read_progress",
                                            observedRecord.source().providerId(), failure);
                                    failureCoverage = Evidence.Coverage.ERROR;
                                    failureOutcome = Evidence.Outcome.ERROR;
                                    failureDetail = failure == null ? "provider progress query failed"
                                            : "provider progress query failed: " + PackSageLog.describe(failure);
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
                                            Evidence.Outcome.PARTIAL, null, previous.detail() == null ? failureDetail
                                            : previous.detail() + "; " + failureDetail);
                                }
                                var refreshedRecords = new ArrayList<>(previous.records());
                                refreshedRecords.add(currentRecord);
                                var partial = previous.coverage() != Evidence.Coverage.COMPLETE
                                        || retrieved.coverage() != Evidence.Coverage.COMPLETE
                                        || previous.outcome() != Evidence.Outcome.COMPLETE
                                        || retrieved.outcome() != Evidence.Outcome.COMPLETE;
                                return new Evidence.Result(refreshedRecords,
                                        partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                                        partial ? Evidence.Outcome.PARTIAL : Evidence.Outcome.COMPLETE, null, previous.detail());
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
                    var requested = ((Evidence.JsonString) arguments.get("handle")).value();
                    Evidence.Record observedConfig;
                    String example = null;
                    boolean ambiguous = false;
                    synchronized (this) {
                        observedConfig = observed.get(requested);
                        if (observedConfig == null || observedConfig.kind() != Evidence.RecordKind.CONFIG) {
                            observedConfig = null;
                            var entries = new LinkedHashMap<String, Evidence.Record>();
                            var entryHandle = requested.contains("#") ? requested.substring(0, requested.indexOf('#')) : requested;
                            for (var record : observed.values()) {
                                if (record.kind() != Evidence.RecordKind.CONFIG || !record.source().scope().equals(request.scope())
                                        || !(record.payload().values().get("handle") instanceof Evidence.JsonString handle)) continue;
                                if (example == null) example = record.source().providerId() + ":" + record.source().sourceHandle();
                                var qualified = record.source().providerId() + ":" + handle.value();
                                if (entryHandle.equals(handle.value()) || entryHandle.equals(qualified)) entries.putIfAbsent(qualified, record);
                            }
                            if (entries.size() == 1) observedConfig = entries.values().iterator().next();
                            ambiguous = entries.size() > 1;
                        }
                    }
                    if (observedConfig == null) return failed(ambiguous
                            ? "config handle matches several observed config entries; use the full reference " + CONFIG_FORM
                            : "unknown config handle; use a reference returned by search_pack or search_config in the form " + CONFIG_FORM
                            + (example == null ? "" : ", such as " + shorten(example)));
                    var selectedConfig = observedConfig;
                    var provider = providers.stream().filter(value -> value.id().equals(selectedConfig.source().providerId())).findFirst().orElse(null);
                    if (provider == null) return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE,
                            Evidence.Outcome.UNKNOWN, null, "config provider " + selectedConfig.source().providerId() + " is unavailable"));
                    var queryArguments = new LinkedHashMap<String, Evidence.JsonValue>();
                    queryArguments.put("id", selectedConfig.payload().values().get("handle"));
                    var key = arguments.get("key");
                    queryArguments.put(key instanceof Evidence.JsonString ? "key" : "text", key instanceof Evidence.JsonString ? key : arguments.get("section"));
                    int limit = arguments.get("limit") instanceof Evidence.JsonNumber number ? number.value().intValueExact() : 16;
                    result = provider.query(new Evidence.Query(key instanceof Evidence.JsonString ? Evidence.Operation.GET : Evidence.Operation.SEARCH,
                            new Evidence.JsonObject(queryArguments), limit, null, null), request.scope())
                            .thenApply(read -> read == null || read.nextCursor() == null ? read
                                    : new Evidence.Result(read.records(), read.coverage(), read.outcome(), null, read.detail() != null ? read.detail()
                                    : "more config keys match than this limit returned; read a specific key or raise limit (maximum 64)"));
                }
                case "search_pack", "list_configs", "search_config" -> {
                    var signature = new LinkedHashMap<>(arguments);
                    signature.remove("cursor");
                    signature.remove("limit");
                    Cursor cursor = null;
                    if (arguments.get("cursor") instanceof Evidence.JsonString text) {
                        synchronized (this) { cursor = cursors.get(text.value()); }
                        if (cursor == null) return failed("unknown or expired cursor; repeat the search without a cursor");
                        if (!cursor.scope().equals(request.scope())) return failed("cursor belongs to an earlier evidence scope; repeat the search without a cursor");
                        if (!cursor.tool().equals(request.tool()) || !cursor.arguments().equals(signature)) {
                            return failed("cursor does not match this query; reuse it only with the same tool and filters");
                        }
                    }
                    int limit = arguments.get("limit") instanceof Evidence.JsonNumber number ? number.value().intValueExact() : 16;
                    var selectedKinds = EnumSet.noneOf(Evidence.RecordKind.class);
                    if (signature.get("kinds") instanceof Evidence.JsonArray kinds) {
                        for (var kind : kinds.values()) selectedKinds.add(Evidence.RecordKind.valueOf(((Evidence.JsonString) kind).value()));
                    }
                    result = search(request, signature, selectedKinds, cursor == null ? 0 : cursor.providerIndex(),
                            cursor == null ? null : cursor.providerCursor(), limit, new ArrayList<>(), 0, false, 0, new ArrayList<>());
                }
                default -> { return failed("unknown read tool " + shorten(request.tool())); }
            }
            return result.handle((retrieved, failure) -> {
                if (failure != null) {
                    PackSageLog.LOGGER.warn("PackSage tool {} failed", request.tool(), failure);
                    return error(request.tool() + " failed: " + PackSageLog.describe(failure));
                }
                if (retrieved == null) return error(request.tool() + " returned no result");
                if (!request.scope().equals(currentScope.get())
                        || retrieved.records().stream().anyMatch(record -> !record.source().scope().equals(request.scope()))) {
                    return error(SCOPE_CHANGED);
                }
                synchronized (this) {
                    if (!request.scope().equals(cacheScope) || !request.scope().equals(currentScope.get())) return error(SCOPE_CHANGED);
                    for (var record : retrieved.records()) {
                        observed.put(record.source().providerId() + ":" + record.source().sourceHandle(), record);
                        while (observed.size() > 512) observed.remove(observed.keySet().iterator().next());
                    }
                }
                if (retrieved.detail() == null && retrieved.coverage() == Evidence.Coverage.UNAVAILABLE) {
                    return retrieved.withDetail(request.tool() + " evidence is unavailable in the current world or provider state");
                }
                if (retrieved.detail() == null && retrieved.outcome() == Evidence.Outcome.ERROR) {
                    return retrieved.withDetail(request.tool() + " failed in its provider without further detail");
                }
                return retrieved;
            });
        } catch (RuntimeException failure) {
            PackSageLog.LOGGER.warn("PackSage tool {} rejected its arguments", request.tool(), failure);
            return failed("invalid arguments for " + request.tool() + ": " + PackSageLog.describe(failure));
        }
    }

    private static Evidence.Result error(String detail) {
        return new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null, detail);
    }

    private static CompletionStage<Evidence.Result> failed(String detail) {
        return CompletableFuture.completedFuture(error(detail));
    }

    private static String shorten(String value) {
        return value.length() > 200 ? value.substring(0, 200) + "..." : value;
    }

    private boolean eligible(PackSageTools.ReadRequest request, Set<Evidence.RecordKind> selectedKinds, KnowledgeProvider provider) {
        if (!request.tool().equals("search_pack")) return provider.id().equals("config");
        if (request.arguments().values().get("provider") instanceof Evidence.JsonString providerFilter
                && !providerFilter.value().equals(provider.id())) return false;
        if (selectedKinds.isEmpty()) return true;
        var produced = PROVIDER_KINDS.get(provider.id());
        return produced == null || produced.stream().anyMatch(selectedKinds::contains);
    }

    private static boolean statusRecord(Evidence.Record record) {
        return record.source().sourceHandle().startsWith("status:");
    }

    private CompletionStage<Evidence.Result> search(PackSageTools.ReadRequest request, Map<String, Evidence.JsonValue> signature,
                                                    Set<Evidence.RecordKind> selectedKinds, int providerIndex, String providerCursor,
                                                    int limit, List<Evidence.Record> records, int matched, boolean partial,
                                                    int pages, List<String> notes) {
        if (!request.scope().equals(currentScope.get())) return failed(SCOPE_CHANGED);
        if (providerCursor == null) {
            while (providerIndex < providers.size() && !eligible(request, selectedKinds, providers.get(providerIndex))) providerIndex++;
        }
        if (providerIndex >= providers.size()) return CompletableFuture.completedFuture(new Evidence.Result(records,
                partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE, partial ? Evidence.Outcome.PARTIAL : Evidence.Outcome.COMPLETE,
                null, notes.isEmpty() ? null : String.join("; ", notes)));
        if (matched >= limit || pages >= MAX_PROVIDER_PAGES_PER_CALL) {
            boolean bound = matched < limit;
            if (bound) notes.add(matched == 0
                    ? "no matching records within the search work bound of " + MAX_PROVIDER_PAGES_PER_CALL
                    + " provider pages; more provider pages remain, so pass nextCursor to continue or narrow text or kinds"
                    : "search work bound of " + MAX_PROVIDER_PAGES_PER_CALL + " provider pages reached with " + matched
                    + " matching records; pass nextCursor to continue");
            var token = UUID.randomUUID().toString();
            synchronized (this) {
                if (!request.scope().equals(cacheScope)) return failed(SCOPE_CHANGED);
                cursors.put(token, new Cursor(request.tool(), Map.copyOf(signature), request.scope(), providerIndex, providerCursor));
                while (cursors.size() > 256) cursors.remove(cursors.keySet().iterator().next());
            }
            boolean incomplete = partial || bound;
            return CompletableFuture.completedFuture(new Evidence.Result(records, incomplete ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                    incomplete ? Evidence.Outcome.PARTIAL : Evidence.Outcome.COMPLETE, token, notes.isEmpty() ? null : String.join("; ", notes)));
        }
        var provider = providers.get(providerIndex);
        var arguments = new LinkedHashMap<String, Evidence.JsonValue>();
        arguments.put("text", signature.getOrDefault("text", new Evidence.JsonString("")));
        if (!selectedKinds.isEmpty()) arguments.put("kinds", signature.get("kinds"));
        if (provider.id().equals("jei") && signature.containsKey("outputItem")) arguments.put("outputItem", signature.get("outputItem"));
        CompletionStage<Evidence.Result> page;
        try {
            page = provider.query(new Evidence.Query(Evidence.Operation.SEARCH, new Evidence.JsonObject(arguments),
                    Math.max(1, limit - matched), providerCursor == null ? null : request.scope(), providerCursor), request.scope());
        } catch (RuntimeException failure) {
            page = CompletableFuture.failedFuture(failure);
        }
        int currentProvider = providerIndex;
        return page.handle((result, failure) -> {
            if (failure != null || result == null) {
                if (failure != null) PackSageLog.LOGGER.warn("PackSage provider {} failed during {}", provider.id(), request.tool(), failure);
                var detail = failure == null ? provider.id() + " returned no result"
                        : provider.id() + " extraction failed: " + PackSageLog.describe(failure);
                notes.add(detail);
                return new Evidence.Result(List.of(new Evidence.Record(new Evidence.Source(request.scope(), provider.id(),
                        "unknown", "status:" + provider.id(), request.scope().requestGeneration(), Instant.now(), Evidence.Activity.UNKNOWN,
                        Evidence.Coverage.ERROR, Evidence.Authority.UNKNOWN, List.of()), Evidence.RecordKind.OTHER,
                        provider.id() + " extraction failed", new Evidence.JsonObject(Map.of("detail", new Evidence.JsonString(detail))), List.of())),
                        Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null);
            }
            if (result.detail() != null && (result.coverage() != Evidence.Coverage.COMPLETE
                    || result.outcome() != Evidence.Outcome.COMPLETE)) notes.add(provider.id() + ": " + result.detail());
            return result;
        }).thenCompose(pageResult -> {
            if (!request.scope().equals(currentScope.get()) || pageResult.records().stream().anyMatch(record -> !record.source().scope().equals(request.scope()))) return failed(SCOPE_CHANGED);
            int found = matched;
            for (var record : pageResult.records()) {
                if (statusRecord(record)) {
                    records.add(record);
                } else if ((selectedKinds.isEmpty() || selectedKinds.contains(record.kind())) && found < limit) {
                    records.add(record);
                    found++;
                }
            }
            boolean incomplete = partial || pageResult.coverage() != Evidence.Coverage.COMPLETE;
            return pageResult.nextCursor() != null
                    ? search(request, signature, selectedKinds, currentProvider, pageResult.nextCursor(), limit, records, found, incomplete, pages + 1, notes)
                    : search(request, signature, selectedKinds, currentProvider + 1, null, limit, records, found, incomplete, pages + 1, notes);
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
