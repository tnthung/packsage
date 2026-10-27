package dev.packsage.jei;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.knowledge.RecipePlanSupport;
import dev.packsage.knowledge.RecipePlanner;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.model.PackSageTools;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

public final class JeiProvider implements KnowledgeProvider {
    private final String version;
    private final Supplier<Optional<JeiIndex>> index;
    private final Minecraft client;
    private final Executor executor;
    private final Supplier<Evidence.Scope> currentScope;
    private final LinkedHashMap<String, Cursor> cursors = new LinkedHashMap<>();

    public JeiProvider(Supplier<Optional<JeiIndex>> index, Executor clientExecutor, Supplier<Evidence.Scope> scope) {
        this.index = index;
        this.client = null;
        this.executor = clientExecutor;
        this.currentScope = scope;
        this.version = "19.27";
    }

    public JeiProvider(Minecraft client, Executor clientExecutor, Supplier<Evidence.Scope> scope) {
        this.client = client;
        this.executor = clientExecutor;
        this.currentScope = scope;
        var mods = ModList.get();
        this.version = mods == null ? "unknown" : mods.getModContainerById("jei")
                .map(container -> container.getModInfo().getVersion().toString()).orElse("unknown");
        this.index = new Supplier<>() {
            private long generation = -1;
            private JeiIndex cached;
            @Override public synchronized Optional<JeiIndex> get() {
                var runtime = JeiRuntimeHolder.current();
                long current = JeiRuntimeHolder.generation();
                if (runtime.isEmpty()) { cached = null; generation = current; return Optional.empty(); }
                if (cached == null || generation != current) { cached = new JeiRuntimeIndex(runtime.get()); generation = current; }
                return Optional.of(cached);
            }
        };
    }

    @Override public String id() { return "jei"; }
    @Override public Metadata metadata() { return new Metadata("jei", List.of(), true, List.of()); }
    @Override public boolean enabled() { return index.get().isPresent(); }
    @Override public ProviderStatus status() { return new ProviderStatus(enabled() ? Availability.AVAILABLE : Availability.UNAVAILABLE, version, null); }

    @Override public CompletionStage<Evidence.Result> searchRecipe(PackSageTools.ReadRequest request, Toolbox.Context context) {
        try {
            var value = request.arguments().values().get("filter");
            if (value != null && !(value instanceof Evidence.JsonNull) && !(value instanceof Evidence.JsonObject))
                throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "filter must be an object");
            var filter = value instanceof Evidence.JsonObject object ? object : new Evidence.JsonObject(Map.of());
            var selected = filter.values().get("provider");
            if (selected != null && !(selected instanceof Evidence.JsonNull) && (!(selected instanceof Evidence.JsonString string) || !string.value().equals("jei")))
                throw new Toolbox.Failure(Toolbox.Code.NOT_HANDLED, "different recipe source");
            if (!enabled()) throw new Toolbox.Failure(selected == null || selected instanceof Evidence.JsonNull ? Toolbox.Code.NOT_HANDLED : Toolbox.Code.UNAVAILABLE, "JEI recipe source unavailable");
            for (String field : List.of("outputItem", "inputItem", "text")) {
                var fieldValue = filter.values().get(field);
                if (fieldValue != null && !(fieldValue instanceof Evidence.JsonNull) && !(fieldValue instanceof Evidence.JsonString))
                    throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, field + " must be a string");
            }
            var output = filter.values().get("outputItem") instanceof Evidence.JsonString string ? string.value() : null;
            var input = filter.values().get("inputItem") instanceof Evidence.JsonString string ? string.value() : null;
            for (var field : List.of("outputItem", "inputItem")) {
                var item = field.equals("outputItem") ? output : input;
                if (item != null && (ResourceLocation.tryParse(item) == null || !ResourceLocation.tryParse(item).toString().equals(item)))
                    throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, field + " must be a canonical item identity");
            }
            var limitValue = request.arguments().values().get("limit");
            if (limitValue != null && !(limitValue instanceof Evidence.JsonNull) && !(limitValue instanceof Evidence.JsonNumber))
                throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "limit must be an integer");
            int limit;
            try { limit = limitValue instanceof Evidence.JsonNumber number ? number.value().intValueExact() : 16; }
            catch (ArithmeticException failure) { throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "limit must be an integer"); }
            if (limit < 1 || limit > 64) throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "limit must be between 1 and 64");
            var cursorValue = request.arguments().values().get("cursor");
            if (cursorValue != null && !(cursorValue instanceof Evidence.JsonNull) && !(cursorValue instanceof Evidence.JsonString))
                throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "cursor must be a string");
            var signatureValues = new LinkedHashMap<>(request.arguments().values());
            signatureValues.remove("cursor"); signatureValues.remove("limit");
            var signature = request.tool() + "|" + Evidence.serialize(new Evidence.JsonObject(signatureValues));
            return CompletableFuture.supplyAsync(() -> {
                if (!request.scope().equals(currentScope.get())) throw new Toolbox.Failure(Toolbox.Code.STALE_SCOPE, "scope changed");
                var current = index.get().orElseThrow(() -> new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "JEI recipe source unavailable"));
                long generation = current.generation();
                int offset = 0;
                if (cursorValue instanceof Evidence.JsonString token) synchronized (cursors) {
                    var state = cursors.get(token.value());
                    if (state == null || state.createdAt() + TimeUnit.MINUTES.toNanos(5) <= System.nanoTime())
                        throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "JEI cursor unknown or expired");
                    if (!state.scope().equals(request.scope())) throw new Toolbox.Failure(Toolbox.Code.STALE_SCOPE, "JEI cursor scope changed");
                    if (state.generation() != generation || !state.signature().equals(signature))
                        throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "JEI cursor no longer matches this query");
                    offset = state.offset();
                }
                if (client != null) {
                    if (client.level == null) throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "JEI recipe source unavailable");
                    if (output != null && !client.level.registryAccess().registryOrThrow(Registries.ITEM).containsKey(ResourceLocation.parse(output)))
                        throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "recipe output item is unknown");
                    if (input != null && !client.level.registryAccess().registryOrThrow(Registries.ITEM).containsKey(ResourceLocation.parse(input)))
                        throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "recipe input item is unknown");
                }
                var page = JeiSearch.search(current, output, input,
                        filter.values().get("text") instanceof Evidence.JsonString string ? string.value() : null,
                        offset, limit, request.scope(), version, Instant.now());
                if (!request.scope().equals(currentScope.get())) throw new Toolbox.Failure(Toolbox.Code.STALE_SCOPE, "scope changed");
                if (generation != index.get().map(JeiIndex::generation).orElse(-1L))
                    throw new Toolbox.Failure(Toolbox.Code.STALE_SCOPE, "JEI runtime changed");
                String next = null;
                if (!page.exhausted() && page.nextOffset() > offset) synchronized (cursors) {
                    next = UUID.randomUUID().toString();
                    cursors.put(next, new Cursor(page.nextOffset(), signature, request.scope(), generation, System.nanoTime()));
                    while (cursors.size() > 8) cursors.remove(cursors.keySet().iterator().next());
                }
                boolean partial = !page.exhausted() || page.coverage() != Evidence.Coverage.COMPLETE
                        || page.records().stream().anyMatch(record -> record.source().coverage() != Evidence.Coverage.COMPLETE);
                return new Evidence.Result(page.records(), partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                        partial ? Evidence.Outcome.PARTIAL : Evidence.Outcome.COMPLETE, next, page.detail());
            }, executor);
        } catch (Toolbox.Failure failure) { return CompletableFuture.failedFuture(failure); }
    }

    @Override public CompletionStage<Evidence.Result> planRecipe(PackSageTools.ReadRequest request, Toolbox.Context context) {
        var selected = request.arguments().values().get("provider");
        if (selected != null && !(selected instanceof Evidence.JsonNull) && (!(selected instanceof Evidence.JsonString string) || !string.value().equals("jei")))
            return CompletableFuture.failedFuture(new Toolbox.Failure(Toolbox.Code.NOT_HANDLED, "different recipe source"));
        if (!enabled()) return CompletableFuture.failedFuture(new Toolbox.Failure(Toolbox.Code.NOT_HANDLED, "JEI recipe source unavailable"));
        try {
            var args = RecipePlanSupport.arguments(request);
            return plan(args.identity(), args.quantity(), args.depth(), request.scope());
        } catch (Toolbox.Failure failure) { return CompletableFuture.failedFuture(failure); }
    }

    private CompletionStage<Evidence.Result> plan(String identity, long quantity, int depth, Evidence.Scope scope) {
        return CompletableFuture.supplyAsync(() -> {
            if (!scope.equals(currentScope.get())) throw new Toolbox.Failure(Toolbox.Code.STALE_SCOPE, "scope changed");
            var current = index.get().orElseThrow(() -> new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "JEI recipe source unavailable"));
            if (client != null) {
                var key = ResourceLocation.tryParse(identity);
                if (client.level == null) throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "JEI recipe source unavailable");
                if (key == null || !key.toString().equals(identity) || !client.level.registryAccess().registryOrThrow(Registries.ITEM).containsKey(key))
                    throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "recipe output item is unknown");
            }
            var generation = current.generation();
            var result = RecipePlanSupport.wrap(new RecipePlanner(new JeiPlanSource(current, scope, version, Instant.now()))
                    .plan(identity, quantity, scope, depth), identity, quantity, depth);
            if (!scope.equals(currentScope.get()) || generation != index.get().map(JeiIndex::generation).orElse(-1L))
                throw new Toolbox.Failure(Toolbox.Code.STALE_SCOPE, "JEI scope or runtime changed");
            return result;
        }, executor);
    }

    @Override public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
        if (query.operation() != Evidence.Operation.GET || !(query.arguments().values().get("id") instanceof Evidence.JsonString id))
            return CompletableFuture.failedFuture(new Toolbox.Failure(Toolbox.Code.NOT_HANDLED, "unsupported JEI query"));
        return read(id.value(), scope);
    }

    @Override public CompletionStage<Evidence.Result> readEvidence(PackSageTools.ReadRequest request, Toolbox.Context context) {
        try {
            var reference = context.reference(request);
            if (!id().equals(reference.providerId())) throw new Toolbox.Failure(Toolbox.Code.NOT_HANDLED, "different evidence owner");
            return read(reference.sourceHandle(), request.scope());
        } catch (Toolbox.Failure failure) { return CompletableFuture.failedFuture(failure); }
    }

    private CompletionStage<Evidence.Result> read(String handle, Evidence.Scope scope) {
        if (handle.startsWith("plan|")) {
            var parts = handle.split("\\|", 6);
            try {
                if (parts.length != 6) throw new IllegalArgumentException();
                long quantity = Long.parseLong(parts[2]); int depth = Integer.parseInt(parts[3]); int route = Integer.parseInt(parts[4]);
                if (quantity < 1 || quantity > 1_000_000 || depth < 0 || depth > 8 || route < 0) throw new IllegalArgumentException();
                return plan(parts[1], quantity, depth, scope).thenApply(result -> {
                    if (route >= result.records().size() || !result.records().get(route).source().sourceHandle().equals(handle))
                        return new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null, "JEI plan route unavailable");
                    var record = result.records().get(route);
                    var coverage = record.source().coverage();
                    return new Evidence.Result(List.of(record), coverage,
                            coverage == Evidence.Coverage.COMPLETE ? Evidence.Outcome.COMPLETE : Evidence.Outcome.PARTIAL, null);
                });
            } catch (IllegalArgumentException failure) {
                return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null, "invalid JEI plan handle"));
            }
        }
        return CompletableFuture.supplyAsync(() -> {
            if (!scope.equals(currentScope.get())) throw new Toolbox.Failure(Toolbox.Code.STALE_SCOPE, "scope changed");
            var current = index.get().orElseThrow(() -> new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "JEI recipe source unavailable"));
            long generation = current.generation();
            var parsed = JeiProjection.parseHandle(handle);
            if (parsed.isEmpty()) return new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null, "invalid JEI handle");
            var value = parsed.get();
            var ref = value.recipeId() != null ? current.byRecipeId(value.categoryUid(), value.recipeId()).orElse(null)
                    : value.generation() == current.generation() && value.ordinal() >= 0 && value.ordinal() < current.inCategory(value.categoryUid()).size()
                    ? new JeiIndex.RecipeRef(value.categoryUid(), value.ordinal()) : null;
            if (ref == null) return new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null, "JEI recipe unavailable");
            var record = JeiProjection.record(current.layout(ref), scope, version, Instant.now());
            if (!scope.equals(currentScope.get())) throw new Toolbox.Failure(Toolbox.Code.STALE_SCOPE, "scope changed");
            if (generation != index.get().map(JeiIndex::generation).orElse(-1L))
                throw new Toolbox.Failure(Toolbox.Code.STALE_SCOPE, "JEI runtime changed");
            if (!record.source().sourceHandle().equals(handle))
                return new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null, "JEI recipe unavailable");
            var coverage = record.source().coverage();
            return new Evidence.Result(List.of(record), coverage,
                    coverage == Evidence.Coverage.COMPLETE ? Evidence.Outcome.COMPLETE : Evidence.Outcome.PARTIAL, null);
        }, executor);
    }

    @Override public CompletionStage<Evidence.NavigationTarget> refreshNavigation(Evidence.NavigationTarget target, Evidence.Scope scope) {
        return CompletableFuture.supplyAsync(() -> {
            if (!scope.equals(currentScope.get())) return null;
            try {
                var current = index.get().orElse(null);
                if (current == null) return null;
                long generation = current.generation();
                if (resolveNavigation(current, target).isEmpty() || !scope.equals(currentScope.get())
                        || index.get().orElse(null) != current || generation != current.generation()) return null;
                return new Evidence.NavigationTarget(scope, id(), target.sourceHandle(), target.targetId());
            } catch (RuntimeException failure) { return null; }
        }, executor);
    }

    @Override public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
        if (!id().equals(target.providerId()))
            return new Evidence.NavigationResult(Evidence.NavigationStatus.UNSUPPORTED, "target belongs to another provider");
        if (!scope.equals(target.scope()) || !scope.equals(currentScope.get()))
            return new Evidence.NavigationResult(Evidence.NavigationStatus.STALE, "JEI scope changed");
        try {
            var current = index.get().orElse(null);
            if (current == null) return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "JEI runtime unavailable");
            long generation = current.generation();
            var ref = resolveNavigation(current, target);
            var runtime = JeiRuntimeHolder.current();
            if (ref.isEmpty() || !(current instanceof JeiRuntimeIndex runtimeIndex) || runtime.isEmpty()
                    || index.get().orElse(null) != current || generation != current.generation())
                return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "JEI recipe unavailable");
            if (!scope.equals(currentScope.get()))
                return new Evidence.NavigationResult(Evidence.NavigationStatus.STALE, "JEI scope changed");
            return new JeiViewer(runtime.get().getRecipesGui(), runtimeIndex::resolve).showRecipe(ref.get())
                    ? new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED, null)
                    : new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "JEI recipe unavailable");
        } catch (RuntimeException failure) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "JEI recipe unavailable");
        }
    }

    private Optional<JeiIndex.RecipeRef> resolveNavigation(JeiIndex current, Evidence.NavigationTarget target) {
        if (!id().equals(target.providerId())) return Optional.empty();
        var parsed = JeiProjection.parseHandle(target.sourceHandle()).orElse(null);
        if (parsed == null) return Optional.empty();
        var category = ResourceLocation.tryParse(parsed.categoryUid());
        if (category == null || !category.toString().equals(parsed.categoryUid())) return Optional.empty();
        Optional<JeiIndex.RecipeRef> ref;
        if (parsed.recipeId() != null) {
            var recipe = ResourceLocation.tryParse(parsed.recipeId());
            if (!target.targetId().equals("recipe") || recipe == null || !recipe.toString().equals(parsed.recipeId())) return Optional.empty();
            ref = current.byRecipeId(parsed.categoryUid(), parsed.recipeId());
        } else {
            if (!target.targetId().equals("temporary") || parsed.generation() != current.generation() || parsed.ordinal() < 0)
                return Optional.empty();
            ref = Optional.of(new JeiIndex.RecipeRef(parsed.categoryUid(), parsed.ordinal()))
                    .filter(value -> current.inCategory(parsed.categoryUid()).contains(value));
        }
        if (ref.isEmpty()) return Optional.empty();
        var layout = current.layout(ref.get());
        return layout != null && layout.readable() && !layout.hidden() && JeiProjection.handle(layout).equals(target.sourceHandle())
                ? ref : Optional.empty();
    }

    private record Cursor(int offset, String signature, Evidence.Scope scope, long generation, long createdAt) { }
}
