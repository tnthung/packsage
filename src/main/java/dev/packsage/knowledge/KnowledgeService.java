package dev.packsage.knowledge;

import dev.packsage.model.PackSageTools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public final class KnowledgeService {
    public static final int MAX_PROVIDER_PAGES_PER_CALL = 8;
    private final AdapterRegistry registry = new AdapterRegistry();
    private final List<KnowledgeProvider> providers;
    private final Supplier<Evidence.Scope> currentScope;
    private final Map<String, Toolbox.Reference> observed = new LinkedHashMap<>();
    private final Set<Evidence.NavigationTarget> refreshedNavigation = new LinkedHashSet<>();
    private Evidence.Scope cacheScope;
    private final PackSageTools.OutputPages outputPages = new PackSageTools.OutputPages(this::observe);

    public KnowledgeService(List<? extends Toolbox> adapters, Supplier<Evidence.Scope> currentScope) {
        this.providers = adapters.stream().filter(KnowledgeProvider.class::isInstance).map(KnowledgeProvider.class::cast).toList();
        this.currentScope = currentScope;
        registry.register(new Discovery());
        adapters.forEach(registry::register);
    }

    public void register(Toolbox adapter) { registry.register(adapter); }
    public PackSageTools.OutputPages outputPages() { return outputPages; }
    public List<Toolbox.Metadata> metadata() { return registry.metadata(); }

    public Set<String> optionalTools() {
        return providers.stream().anyMatch(provider -> provider.id().equals("jei_bookmarks") && provider.enabled())
                ? Set.of("bookmark") : Set.of();
    }

    public CompletionStage<Evidence.Result> execute(PackSageTools.ReadRequest request) { return execute(request, null); }

    public CompletionStage<Evidence.Result> execute(PackSageTools.ReadRequest request, String replacingRequestId) {
        return execute(request, replacingRequestId, null);
    }

    public CompletionStage<Evidence.Result> execute(PackSageTools.ReadRequest request, String replacingRequestId, Toolbox.Origin origin) {
        Toolbox.Context context;
        synchronized (this) {
            if (!request.scope().equals(currentScope.get())) return CompletableFuture.failedFuture(
                    new Toolbox.Failure(Toolbox.Code.STALE_SCOPE, "repeat the lookup in the current scope"));
            if (!request.scope().equals(cacheScope)) {
                cacheScope = request.scope();
                observed.clear();
                refreshedNavigation.clear();
            }
            context = new Toolbox.Context(currentScope, Map.copyOf(observed), registry, new Toolbox.DocumentBudget(), replacingRequestId, origin);
        }
        CompletionStage<Evidence.Result> read;
        if (request.tool().equals("read_evidence")) {
            if (!(request.arguments().values().get("handles") instanceof Evidence.JsonArray handles)
                    || handles.values().isEmpty() || handles.values().size() > 16) return CompletableFuture.failedFuture(
                    new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "select 1..16 observed handles"));
            for (var handle : handles.values()) {
                context.reference(new PackSageTools.ReadRequest(request.tool(),
                        new Evidence.JsonObject(Map.of("handles", new Evidence.JsonArray(List.of(handle)))), request.scope()));
            }
            read = CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null));
            for (var handle : handles.values()) {
                var arguments = new LinkedHashMap<>(request.arguments().values());
                arguments.put("handles", new Evidence.JsonArray(List.of(handle)));
                var single = new PackSageTools.ReadRequest(request.tool(), new Evidence.JsonObject(arguments), request.scope());
                read = read.thenCompose(previous -> {
                    var reference = context.reference(single);
                    return registry.dispatch(single, context).handle((result, failure) -> {
                        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
                        boolean mismatched = result != null && result.records().stream().anyMatch(record ->
                                !record.source().scope().equals(request.scope()) || !record.source().providerId().equals(reference.providerId())
                                || !record.source().sourceHandle().equals(reference.sourceHandle()) || record.kind() != reference.kind());
                        boolean unavailable = result != null && (result.coverage() == Evidence.Coverage.ERROR
                                || result.coverage() == Evidence.Coverage.UNAVAILABLE || result.outcome() == Evidence.Outcome.ERROR);
                        if (failure != null) throw new CompletionException(failure);
                        if (result == null || unavailable) throw new Toolbox.Failure(result != null && result.coverage() == Evidence.Coverage.UNAVAILABLE
                                ? Toolbox.Code.UNAVAILABLE : Toolbox.Code.EXTRACTION_FAILED,
                                result != null && result.detail() != null ? result.detail() : "current evidence is unavailable");
                        if (mismatched) throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE, "adapter returned a different reference identity");
                        var records = new ArrayList<>(previous.records());
                        records.addAll(result.records());
                        boolean partial = previous.coverage() != Evidence.Coverage.COMPLETE || result.coverage() != Evidence.Coverage.COMPLETE;
                        return new Evidence.Result(records, partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                                partial ? Evidence.Outcome.PARTIAL : Evidence.Outcome.COMPLETE, null,
                                result.detail() == null ? previous.detail() : result.detail());
                    });
                });
            }
        } else read = registry.dispatch(request, context);
        return read.thenApply(result -> {
            observe(request.scope(), result.records());
            return result;
        });
    }

    private synchronized void observe(Evidence.Scope scope, List<Evidence.Record> records) {
        if (!scope.equals(currentScope.get()) || !scope.equals(cacheScope)
                || records.stream().anyMatch(record -> !record.source().scope().equals(scope))) {
            throw new Toolbox.Failure(Toolbox.Code.STALE_SCOPE, "scope changed while reading");
        }
        for (var record : records) {
            observed.put(record.source().providerId() + ":" + record.source().sourceHandle(),
                    new Toolbox.Reference(record.source().providerId(), record.source().sourceHandle(), record.kind(), scope));
            refreshedNavigation.addAll(record.targets());
        }
        while (observed.size() > 512) observed.remove(observed.keySet().iterator().next());
        while (refreshedNavigation.size() > 64) refreshedNavigation.remove(refreshedNavigation.iterator().next());
    }

    public Map<String, Boolean> adapterAvailability() {
        var availability = new LinkedHashMap<String, Boolean>();
        for (var provider : providers) {
            availability.put(provider.id(), provider.enabled() && (!provider.id().equals("journal")
                    || provider.status().availability() == KnowledgeProvider.Availability.AVAILABLE));
        }
        return Map.copyOf(availability);
    }

    public Map<String, String> adapterDetails() {
        var details = new LinkedHashMap<String, String>();
        for (var provider : providers) {
            var status = provider.status();
            if (status.detail() != null) details.put(provider.id(), status.detail());
        }
        return Map.copyOf(details);
    }

    private final class Discovery implements Toolbox {
        private record Cursor(String tool, Map<String, Evidence.JsonValue> arguments, Evidence.Scope scope,
                              List<String> sources, int index, String sourceCursor) { }
        private final Map<String, Cursor> cursors = new LinkedHashMap<>();

        @Override public Metadata metadata() { return new Metadata("discovery", List.of(), false, List.of()); }
        @Override public CompletionStage<Evidence.Result> searchDocument(PackSageTools.ReadRequest request, Context context) { return search(request, context); }
        @Override public CompletionStage<Evidence.Result> searchRecipe(PackSageTools.ReadRequest request, Context context) { return search(request, context); }

        private CompletionStage<Evidence.Result> search(PackSageTools.ReadRequest request, Context context) {
            var filter = request.arguments().values().get("filter") instanceof Evidence.JsonObject value ? value.values() : Map.<String, Evidence.JsonValue>of();
            if (filter.get("provider") instanceof Evidence.JsonString) return CompletableFuture.failedFuture(new Failure(Code.NOT_HANDLED, "source-qualified discovery"));
            var signature = new LinkedHashMap<>(request.arguments().values());
            signature.remove("cursor");
            signature.remove("limit");
            var sources = registry.metadata().stream().filter(value -> request.tool().equals("search_recipe") ? value.recipes()
                    : !value.documentTypes().isEmpty() && (!(filter.get("types") instanceof Evidence.JsonArray types) || types.values().isEmpty()
                    || types.values().stream().anyMatch(type -> value.documentTypes().contains(((Evidence.JsonString) type).value())))).map(Metadata::id)
                    .filter(id -> !request.tool().equals("search_recipe") || providers.stream().filter(provider -> provider.id().equals(id)).allMatch(KnowledgeProvider::enabled))
                    .limit(request.tool().equals("search_recipe") ? 1 : Long.MAX_VALUE).toList();
            Cursor cursor = null;
            if (request.arguments().values().get("cursor") instanceof Evidence.JsonString token) {
                synchronized (this) { cursor = cursors.get(token.value()); }
                if (cursor == null || !cursor.tool().equals(request.tool()) || !cursor.arguments().equals(signature)
                        || !cursor.sources().equals(sources)) throw new Failure(Code.INVALID_CURSOR, "cursor does not match this discovery query");
                if (!cursor.scope().equals(request.scope())) throw new Failure(Code.STALE_SCOPE, "discovery cursor scope changed");
            }
            int limit = request.arguments().values().get("limit") instanceof Evidence.JsonNumber value ? value.value().intValueExact() : 16;
            return page(request, context, signature, sources, cursor == null ? 0 : cursor.index(), cursor == null ? null : cursor.sourceCursor(),
                    limit, new ArrayList<>(), false, 0, new ArrayList<>());
        }

        private CompletionStage<Evidence.Result> page(PackSageTools.ReadRequest request, Context context,
                Map<String, Evidence.JsonValue> signature, List<String> sources, int index, String sourceCursor,
                int limit, List<Evidence.Record> records, boolean partial, int pages, List<String> notes) {
            if (index == sources.size()) return CompletableFuture.completedFuture(new Evidence.Result(records,
                    partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE, partial ? Evidence.Outcome.PARTIAL : Evidence.Outcome.COMPLETE,
                    null, notes.isEmpty() ? null : String.join("; ", notes)));
            if (records.size() >= limit || pages >= MAX_PROVIDER_PAGES_PER_CALL || context.documentBudget().exhausted()) {
                var token = UUID.randomUUID().toString();
                synchronized (this) {
                    cursors.put(token, new Cursor(request.tool(), Map.copyOf(signature), request.scope(), sources, index, sourceCursor));
                    while (cursors.size() > 256) cursors.remove(cursors.keySet().iterator().next());
                }
                boolean bounded = pages >= MAX_PROVIDER_PAGES_PER_CALL || context.documentBudget().exhausted();
                if (bounded) notes.add("discovery work bound reached; continue with nextCursor");
                return CompletableFuture.completedFuture(new Evidence.Result(records, partial || bounded ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                        partial || bounded ? Evidence.Outcome.PARTIAL : Evidence.Outcome.COMPLETE, token, notes.isEmpty() ? null : String.join("; ", notes)));
            }
            var arguments = new LinkedHashMap<>(signature);
            var filter = new LinkedHashMap<>(signature.get("filter") instanceof Evidence.JsonObject value ? value.values() : Map.<String, Evidence.JsonValue>of());
            filter.put("provider", new Evidence.JsonString(sources.get(index)));
            arguments.put("filter", new Evidence.JsonObject(filter));
            arguments.put("limit", new Evidence.JsonNumber(java.math.BigDecimal.valueOf(limit - records.size())));
            if (sourceCursor != null) arguments.put("cursor", new Evidence.JsonString(sourceCursor));
            return registry.dispatch(new PackSageTools.ReadRequest(request.tool(), new Evidence.JsonObject(arguments), request.scope()), context)
                    .handle((result, failure) -> {
                        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
                        if (failure instanceof Failure typed && (typed.code() == Code.UNAVAILABLE || typed.code() == Code.EXTRACTION_FAILED)) {
                            notes.add(sources.get(index) + ": " + typed.getMessage());
                            return new Evidence.Result(List.of(), Evidence.Coverage.PARTIAL, Evidence.Outcome.PARTIAL, null);
                        }
                        if (failure != null) throw new CompletionException(failure);
                        return result;
                    }).thenCompose(result -> {
                        if (result.records().size() > limit - records.size()) throw new Failure(Code.EXTRACTION_FAILED, "adapter exceeded its requested page limit");
                        if (result.records().stream().anyMatch(record -> !record.source().providerId().equals(sources.get(index))
                                || !record.source().scope().equals(request.scope())
                                || record.targets().stream().anyMatch(target -> !target.scope().equals(request.scope())
                                || !target.providerId().equals(record.source().providerId()) || !target.sourceHandle().equals(record.source().sourceHandle()))
                                || !(record.kind() == Evidence.RecordKind.OTHER && record.source().sourceHandle().startsWith("status:"))
                                && (request.tool().equals("search_recipe") ? record.kind() != Evidence.RecordKind.RECIPE
                                : record.kind() != Evidence.RecordKind.GUIDE && record.kind() != Evidence.RecordKind.QUEST))) {
                            throw new Failure(Code.INVALID_REFERENCE, "adapter returned evidence outside its discovery source or kind");
                        }
                        records.addAll(result.records());
                        if (result.detail() != null) notes.add(sources.get(index) + ": " + result.detail());
                        if (result.nextCursor() != null && result.nextCursor().equals(sourceCursor)) throw new Failure(Code.INVALID_CURSOR, "source continuation did not advance");
                        return page(request, context, signature, sources, result.nextCursor() == null ? index + 1 : index, result.nextCursor(),
                                limit, records, partial || result.coverage() != Evidence.Coverage.COMPLETE, pages + 1, notes);
                    });
        }
    }

    public CompletionStage<Evidence.NavigationTarget> refreshNavigation(Evidence.NavigationTarget target, Evidence.Scope scope) {
        if (!scope.equals(currentScope.get())) return CompletableFuture.completedFuture(null);
        var provider = providers.stream().filter(value -> value.id().equals(target.providerId())).findFirst().orElse(null);
        if (provider == null) return CompletableFuture.completedFuture(null);
        CompletionStage<Evidence.NavigationTarget> resolution;
        try {
            resolution = provider.refreshNavigation(target, scope);
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(null);
        }
        if (resolution == null) return CompletableFuture.completedFuture(null);
        return resolution.handle((currentTarget, failure) -> {
            if (failure != null || currentTarget == null || !scope.equals(currentTarget.scope())
                    || !target.providerId().equals(currentTarget.providerId())) return null;
            synchronized (this) {
                if (!scope.equals(currentScope.get())) return null;
                refreshedNavigation.add(currentTarget);
                while (refreshedNavigation.size() > 64) refreshedNavigation.remove(refreshedNavigation.iterator().next());
            }
            return currentTarget;
        });
    }

    public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
        if (!scope.equals(currentScope.get()) || !target.scope().equals(scope)) return new Evidence.NavigationResult(Evidence.NavigationStatus.STALE, "observation scope changed");
        synchronized (this) {
            if (!refreshedNavigation.contains(target)) {
                return new Evidence.NavigationResult(Evidence.NavigationStatus.STALE, "navigation target is no longer observed");
            }
        }
        return providers.stream().filter(provider -> provider.id().equals(target.providerId())).findFirst()
                .map(provider -> provider.open(target, scope)).orElseGet(() -> new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "provider unavailable"));
    }
}
