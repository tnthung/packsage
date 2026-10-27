package dev.packsage.knowledge.nativegame;

import dev.packsage.PackSageLog;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeIndex;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.knowledge.RecipePlanner;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.model.PackSageTools;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

public final class NativeProvider implements KnowledgeProvider {
    private static final String VERSION = "1.21.1";
    private static final long CURSOR_TTL_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(5);
    private static final int MAX_CURSORS = 8;
    private static final List<String> REMOVED_HANDLE_PREFIXES = List.of("inventory:", "inventory-summary:", "storage:", "storage-entry:");
    private final Supplier<NativeSnapshot> snapshotSource;
    private final Minecraft client;
    private final Executor executor;
    private final Supplier<Evidence.Scope> currentScope;
    private final AtomicReference<Evidence.Scope> activeScope = new AtomicReference<>();
    private final LinkedHashMap<String, QueryCursor> cursors = new LinkedHashMap<>();
    private volatile ProviderStatus latestStatus = new ProviderStatus(Availability.UNAVAILABLE, VERSION, "native client evidence not read");

    public NativeProvider(Supplier<NativeSnapshot> snapshotSource, Executor executor) {
        this(snapshotSource, executor, () -> null);
    }

    public NativeProvider(Supplier<NativeSnapshot> snapshotSource, Executor executor, Supplier<Evidence.Scope> currentScope) {
        this.snapshotSource = Objects.requireNonNull(snapshotSource);
        this.client = null;
        this.executor = Objects.requireNonNull(executor);
        this.currentScope = Objects.requireNonNull(currentScope);
    }

    public NativeProvider(Minecraft client, Executor executor) {
        this(client, executor, () -> null);
    }

    public NativeProvider(Minecraft client, Executor executor, Supplier<Evidence.Scope> currentScope) {
        this.snapshotSource = null;
        this.client = Objects.requireNonNull(client);
        this.executor = Objects.requireNonNull(executor);
        this.currentScope = Objects.requireNonNull(currentScope);
    }

    @Override public String id() { return "native"; }

    @Override
    public Toolbox.Metadata metadata() {
        return new Toolbox.Metadata(id(), List.of(), true, List.of("item", "block", "item_tag", "enchantment", "mob_effect", "potion", "active_effect", "player_attribute",
                "dimension", "dimension_type"));
    }

    @Override public ProviderStatus status() { return latestStatus; }

    @Override
    public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
        Objects.requireNonNull(query);
        Objects.requireNonNull(scope);
        return execute(scope, index -> index.search(query, scope).toCompletableFuture().join());
    }

    public CompletionStage<Evidence.Result> planRecipe(String itemIdentity, long quantity, Evidence.Scope scope, int maxDepth) {
        Objects.requireNonNull(itemIdentity);
        Objects.requireNonNull(scope);
        if (client == null) return execute(scope, index -> new RecipePlanner(index).plan(itemIdentity, quantity, scope, maxDepth));
        activeScope.set(scope);
        return client.submit(() -> {
            if (!scope.equals(currentScope.get()) || client.level == null) return new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE,
                    Evidence.Outcome.UNKNOWN, null, "native recipe source unavailable");
            var key = ResourceLocation.tryParse(itemIdentity);
            if (key == null || !key.toString().equals(itemIdentity)
                    || !client.level.registryAccess().registryOrThrow(Registries.ITEM).containsKey(key)) {
                throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "recipe output item is unknown");
            }
            return planRecipe(NativeRecipeReader.planSource(client, scope), itemIdentity, quantity, scope, maxDepth);
        }).thenApply(result -> publish(result, scope));
    }

    static Evidence.Result planRecipe(NativeRecipeReader.PlanSource candidates, String itemIdentity, long quantity,
                                      Evidence.Scope scope, int maxDepth) {
        var result = new RecipePlanner(candidates).plan(itemIdentity, quantity, scope, maxDepth);
        var planRecords = new ArrayList<Evidence.Record>();
        for (int route = 0; route < result.records().size(); route++) {
            var record = result.records().get(route);
            var sourceHandle = "plan|" + itemIdentity + "|" + quantity + "|" + maxDepth + "|" + route + "|"
                    + record.source().sourceHandle();
            var source = record.source();
            var targets = record.targets().stream().map(target -> new Evidence.NavigationTarget(target.scope(), target.providerId(),
                    sourceHandle, target.targetId())).toList();
            planRecords.add(new Evidence.Record(new Evidence.Source(source.scope(), source.providerId(), source.providerVersion(),
                    sourceHandle, source.observedGeneration(), source.observedAt(), source.activity(), source.coverage(),
                    source.authority(), source.provenance()), record.kind(), record.title(), record.payload(), targets,
                    record.recipePayload()));
        }
        result = new Evidence.Result(planRecords, result.coverage(), result.outcome(), result.nextCursor(), result.detail());
        if (candidates.reasons.isEmpty()) return result;
        var sourceReasons = new ArrayList<String>();
        candidates.reasons.stream().filter(reason -> reason.contains("cap")).limit(8).forEach(sourceReasons::add);
        candidates.reasons.stream().filter(reason -> !reason.contains("cap")).limit(8).forEach(sourceReasons::add);
        if (candidates.reasons.size() > sourceReasons.size()) sourceReasons.add("native-recipe-source-reason-cap=16; omitted="
                + (candidates.reasons.size() - sourceReasons.size()));
        return partial(result, String.join("; ", sourceReasons));
    }

    @Override
    public CompletionStage<Evidence.Result> searchRegistry(PackSageTools.ReadRequest request, Toolbox.Context context) {
        var filterValue = request.arguments().values().get("filter");
        if (filterValue != null && !(filterValue instanceof Evidence.JsonObject) && !(filterValue instanceof Evidence.JsonNull)) {
            return invalid("filter must be an object");
        }
        var filter = filterValue instanceof Evidence.JsonObject value ? value : new Evidence.JsonObject(Map.of());
        if (!providerSelected(filter)) return CompletableFuture.failedFuture(new Toolbox.Failure(Toolbox.Code.NOT_HANDLED, "different registry source"));
        if (client == null) return unavailable(request.scope(), "live native registry source unavailable");
        var registryValue = request.arguments().values().get("registry");
        if (!(registryValue instanceof Evidence.JsonString registry)) return invalid("registry is required");
        if (!metadata().registries().contains(registry.value())) return CompletableFuture.failedFuture(
                new Toolbox.Failure(Toolbox.Code.NOT_HANDLED, "unsupported native registry"));
        try {
            var textValue = filter.values().get("text");
            if (textValue != null && !(textValue instanceof Evidence.JsonNull) && !(textValue instanceof Evidence.JsonString)) {
                throw new IllegalArgumentException("text must be a string");
            }
            String text = textValue instanceof Evidence.JsonString value ? value.value() : null;
            var filterValues = new LinkedHashMap<String, List<String>>();
            for (String field : List.of("ids", "tags")) {
                var value = filter.values().get(field);
                if (value == null || value instanceof Evidence.JsonNull) {
                    filterValues.put(field, List.of());
                } else if (value instanceof Evidence.JsonArray array
                        && array.values().stream().allMatch(Evidence.JsonString.class::isInstance)) {
                    filterValues.put(field, array.values().stream().map(Evidence.JsonString.class::cast)
                            .map(Evidence.JsonString::value).toList());
                } else {
                    throw new IllegalArgumentException(field + " must be an array of strings");
                }
            }
            var ids = filterValues.get("ids");
            var tags = filterValues.get("tags");
            if (registry.value().equals("item_tag") && !tags.isEmpty()) return CompletableFuture.failedFuture(
                    new Toolbox.Failure(Toolbox.Code.UNSUPPORTED_FILTER, "item_tag does not support a tags membership filter"));
            if (registry.value().equals("dimension") && !tags.isEmpty()) return CompletableFuture.failedFuture(
                    new Toolbox.Failure(Toolbox.Code.UNSUPPORTED_FILTER, "dimension does not support tag membership filters"));
            if (ids.size() > 32 || tags.size() > 32 || java.util.stream.Stream.concat(ids.stream(), tags.stream()).anyMatch(identity -> {
                var parsed = ResourceLocation.tryParse(identity);
                return parsed == null || !parsed.toString().equals(identity);
            })) throw new IllegalArgumentException("registry IDs and tags must be canonical identities, up to 32 each");
            var limitValue = request.arguments().values().get("limit");
            if (limitValue != null && !(limitValue instanceof Evidence.JsonNull) && !(limitValue instanceof Evidence.JsonNumber)) {
                throw new IllegalArgumentException("limit must be an integer");
            }
            int limit = limitValue instanceof Evidence.JsonNumber number ? number.value().intValueExact() : 16;
            if (limit < 1 || limit > 64) throw new IllegalArgumentException("limit must be between 1 and 64");
            var signature = signature(request);
            int offset = cursorOffset(request, signature);
            CompletionStage<NativeRegistryReader.Page> search = registry.value().equals("dimension")
                    ? client.submit(() -> NativeDimensionReader.search(client, offset, limit, text, ids, request.scope(), currentScope))
                    .thenCompose(Function.identity())
                    : client.submit(() -> NativeRegistryReader.search(client, registry.value(), text, ids, tags, offset, limit, request.scope()));
            return search
                    .thenApply(page -> {
                        String next = page.exhausted() || page.nextOffset() <= offset ? null : storeCursor(page.nextOffset(), signature, request.scope());
                        boolean partial = page.partial() || !page.exhausted();
                        var result = new Evidence.Result(page.records(), partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                                partial ? Evidence.Outcome.PARTIAL : Evidence.Outcome.COMPLETE, next, page.detail());
                        return publish(result, request.scope());
                    });
        } catch (Toolbox.Failure failure) {
            return CompletableFuture.failedFuture(failure);
        } catch (IllegalArgumentException failure) {
            return invalid(failure.getMessage());
        }
    }

    @Override
    public CompletionStage<Evidence.Result> searchRecipe(PackSageTools.ReadRequest request, Toolbox.Context context) {
        var filterValue = request.arguments().values().get("filter");
        if (filterValue != null && !(filterValue instanceof Evidence.JsonObject) && !(filterValue instanceof Evidence.JsonNull)) {
            return invalid("filter must be an object");
        }
        var filter = filterValue instanceof Evidence.JsonObject value ? value : new Evidence.JsonObject(Map.of());
        if (!providerSelected(filter)) return CompletableFuture.failedFuture(new Toolbox.Failure(Toolbox.Code.NOT_HANDLED, "different recipe source"));
        if (client == null) return unavailable(request.scope(), "live native recipe source unavailable");
        var outputValue = filter.values().get("outputItem");
        var inputValue = filter.values().get("inputItem");
        var textValue = filter.values().get("text");
        if (outputValue != null && !(outputValue instanceof Evidence.JsonNull) && !(outputValue instanceof Evidence.JsonString)) {
            return invalid("outputItem must be a string");
        }
        if (inputValue != null && !(inputValue instanceof Evidence.JsonNull) && !(inputValue instanceof Evidence.JsonString)) {
            return invalid("inputItem must be a string");
        }
        if (textValue != null && !(textValue instanceof Evidence.JsonNull) && !(textValue instanceof Evidence.JsonString)) {
            return invalid("text must be a string");
        }
        var output = outputValue instanceof Evidence.JsonString value ? value.value() : null;
        var input = inputValue instanceof Evidence.JsonString value ? value.value() : null;
        var text = textValue instanceof Evidence.JsonString value ? value.value() : null;
        if (output != null) {
            var key = ResourceLocation.tryParse(output);
            if (key == null || !key.toString().equals(output)) return invalid("outputItem must be a canonical item identity");
        }
        if (input != null) {
            var key = ResourceLocation.tryParse(input);
            if (key == null || !key.toString().equals(input)) return invalid("inputItem must be a canonical item identity");
        }
        try {
            var limitValue = request.arguments().values().get("limit");
            if (limitValue != null && !(limitValue instanceof Evidence.JsonNull) && !(limitValue instanceof Evidence.JsonNumber)) {
                throw new IllegalArgumentException("limit must be an integer");
            }
            int limit = limitValue instanceof Evidence.JsonNumber number ? number.value().intValueExact() : 16;
            if (limit < 1 || limit > 64) throw new IllegalArgumentException("limit must be between 1 and 64");
            var signature = signature(request);
            int offset = cursorOffset(request, signature);
            return client.submit(() -> {
                if (client.level == null) throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "native recipe source unavailable");
                if (output != null && !client.level.registryAccess().registryOrThrow(Registries.ITEM)
                        .containsKey(ResourceLocation.parse(output))) throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS,
                        "recipe output item is unknown");
                if (input != null && !client.level.registryAccess().registryOrThrow(Registries.ITEM)
                        .containsKey(ResourceLocation.parse(input))) throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS,
                        "recipe input item is unknown");
                return NativeRecipeReader.search(client, output, input, text, offset, limit, request.scope());
            }).thenApply(page -> {
                String next = page.exhausted() || page.nextOffset() <= offset ? null : storeCursor(page.nextOffset(), signature, request.scope());
                boolean partial = page.partial() || !page.exhausted()
                        || page.records().stream().anyMatch(record -> record.source().coverage() != Evidence.Coverage.COMPLETE);
                var result = new Evidence.Result(page.records(), partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                        partial ? Evidence.Outcome.PARTIAL : Evidence.Outcome.COMPLETE, next, page.detail());
                return publish(result, request.scope());
            });
        } catch (Toolbox.Failure failure) {
            return CompletableFuture.failedFuture(failure);
        } catch (IllegalArgumentException failure) {
            return invalid(failure.getMessage());
        }
    }

    @Override
    public CompletionStage<Evidence.Result> planRecipe(PackSageTools.ReadRequest request, Toolbox.Context context) {
        try {
            providerSelected(request.arguments());
        } catch (Toolbox.Failure failure) {
            return CompletableFuture.failedFuture(failure);
        }
        var identityValue = request.arguments().values().get("identity");
        if (identityValue != null && !(identityValue instanceof Evidence.JsonNull) && !(identityValue instanceof Evidence.JsonString)) {
            return invalid("identity must be a string");
        }
        var identity = identityValue instanceof Evidence.JsonString value ? value.value() : null;
        if (identity == null) return invalid("identity is required");
        var quantityValue = request.arguments().values().get("quantity");
        var depthValue = request.arguments().values().get("depth");
        if (quantityValue != null && !(quantityValue instanceof Evidence.JsonNull) && !(quantityValue instanceof Evidence.JsonNumber)) {
            return invalid("quantity must be an integer");
        }
        if (depthValue != null && !(depthValue instanceof Evidence.JsonNull) && !(depthValue instanceof Evidence.JsonNumber)) {
            return invalid("depth must be an integer");
        }
        var quantity = quantityValue instanceof Evidence.JsonNumber number ? number.value().longValueExact() : 1;
        var depth = depthValue instanceof Evidence.JsonNumber number ? number.value().intValueExact() : 4;
        if (quantity < 1 || quantity > 1_000_000 || depth < 0 || depth > 8) return invalid("recipe quantity or depth is outside bounds");
        return planRecipe(identity, quantity, request.scope(), depth);
    }

    @Override
    public CompletionStage<Evidence.Result> readEvidence(PackSageTools.ReadRequest request, Toolbox.Context context) {
        Toolbox.Reference reference;
        try {
            reference = context.reference(request);
        } catch (Toolbox.Failure failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (!id().equals(reference.providerId())) return CompletableFuture.failedFuture(
                new Toolbox.Failure(Toolbox.Code.NOT_HANDLED, "different evidence owner"));
        var source = reference.sourceHandle();
        if (REMOVED_HANDLE_PREFIXES.stream().anyMatch(source::startsWith)) return CompletableFuture.failedFuture(new Toolbox.Failure(
                Toolbox.Code.UNAVAILABLE, "this inventory record came from a reader that was removed; read the items again with list_items or inspect_item"));
        if (client == null) return unavailable(request.scope(), "live native evidence source unavailable");
        if (source.startsWith("plan|")) {
            var parts = source.split("\\|", 6);
            if (parts.length != 6 || parts[5].isBlank()) return CompletableFuture.completedFuture(unknownReference(request.scope()));
            var identity = ResourceLocation.tryParse(parts[1]);
            if (identity == null || !identity.toString().equals(parts[1])) return CompletableFuture.completedFuture(unknownReference(request.scope()));
            long quantity;
            int depth;
            int route;
            try {
                quantity = Long.parseLong(parts[2]);
                depth = Integer.parseInt(parts[3]);
                route = Integer.parseInt(parts[4]);
            } catch (NumberFormatException failure) {
                return CompletableFuture.completedFuture(unknownReference(request.scope()));
            }
            if (quantity < 1 || quantity > 1_000_000 || depth < 0 || depth > 8 || route < 0) {
                return CompletableFuture.completedFuture(unknownReference(request.scope()));
            }
            return planRecipe(parts[1], quantity, request.scope(), depth).thenApply(current -> {
                var record = route < current.records().size() ? current.records().get(route) : null;
                if (record != null && record.source().sourceHandle().equals(source)) return result(record);
                if (current.coverage() == Evidence.Coverage.UNAVAILABLE || current.coverage() == Evidence.Coverage.ERROR) return current;
                if (current.coverage() == Evidence.Coverage.PARTIAL || current.coverage() == Evidence.Coverage.OPAQUE) {
                    return new Evidence.Result(List.of(), current.coverage(), Evidence.Outcome.UNKNOWN, null,
                            "derived recipe plan route is no longer available at this position");
                }
                return unknownReference(request.scope());
            });
        }
        if (source.startsWith("item:") || source.startsWith("block:") || source.startsWith("tag:")
                || source.startsWith("enchantment:") || source.startsWith("mob_effect:") || source.startsWith("potion:")
                || source.startsWith("active_effect:") || source.startsWith("player_attribute:") || source.startsWith("dimension_type:")) {
            int separator = source.indexOf(':');
            var registry = source.substring(0, separator).equals("tag") ? "item_tag" : source.substring(0, separator);
            var identity = source.substring(separator + 1);
            return client.submit(() -> NativeRegistryReader.read(client, registry, identity, request.scope()))
                    .thenApply(value -> currentScope.get() != null && !request.scope().equals(currentScope.get())
                            ? unknownReference(request.scope()) : value == null ? unknownReference(request.scope()) : result(value));
        }
        if (source.startsWith("dimension:")) return client.submit(() -> NativeDimensionReader.read(client,
                source.substring("dimension:".length()), request.scope(), currentScope)).thenCompose(Function.identity())
                .thenApply(value -> value == null ? unknownReference(request.scope()) : result(value));
        return client.submit(() -> NativeRecipeReader.read(client, source, request.scope()))
                .thenApply(value -> value == null ? unknownReference(request.scope()) : result(value));
    }

    @Override
    public CompletionStage<Evidence.Result> searchDocument(PackSageTools.ReadRequest request, Toolbox.Context context) {
        return CompletableFuture.failedFuture(new Toolbox.Failure(Toolbox.Code.NOT_HANDLED, "native has no document source"));
    }

    @Override
    public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
        Objects.requireNonNull(target);
        Objects.requireNonNull(scope);
        if (!id().equals(target.providerId())) return new Evidence.NavigationResult(Evidence.NavigationStatus.UNSUPPORTED, "target belongs to another provider");
        if (!target.scope().equals(scope) || !scope.equals(currentScope.get())) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.STALE, "native evidence scope changed");
        }
        return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "native navigation is runtime-wired pending client bridge");
    }

    private CompletionStage<Evidence.Result> execute(Evidence.Scope scope, Function<KnowledgeIndex, Evidence.Result> operation) {
        var previousScope = activeScope.getAndSet(scope);
        if (previousScope != null && !previousScope.equals(scope)) {
            clearCursors();
            latestStatus = new ProviderStatus(Availability.UNAVAILABLE, VERSION, "native evidence scope changed");
        }
        CompletionStage<Captured> captured;
        try {
            captured = client == null ? CompletableFuture.supplyAsync(() -> new Captured(NativeSnapshot.capture(snapshotSource)), executor)
                    : client.submit(() -> new Captured(NativeSnapshot.capture(client, scope)));
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(failed("native capture could not start", failure));
        }
        return captured.handle((result, failure) -> new CaptureResult(result, failure)).thenApplyAsync(result -> {
            if (result.failure() != null || result.captured() == null) return failed("native capture failed", result.failure());
            if (!scope.equals(activeScope.get()) || currentScope.get() != null && !scope.equals(currentScope.get())) return unknownResult();
            if (result.captured().snapshot() == null || !result.captured().snapshot().scope().equals(scope)) return unknownResult();
            try {
                var index = new KnowledgeIndex();
                index.replace(result.captured().snapshot());
                return operation.apply(index);
            } catch (RuntimeException failure) {
                return failed("native index query failed", failure);
            }
        }, executor).thenCompose(result -> client == null ? CompletableFuture.completedFuture(publish(result, scope))
                : client.submit(() -> {
                    if (!scope.equals(activeScope.get()) || currentScope.get() != null && !scope.equals(currentScope.get())
                            || result == null) return unknownResult();
                    return publish(result, scope);
                }));
    }

    private Evidence.Result publish(Evidence.Result result, Evidence.Scope scope) {
        if (result == null || currentScope.get() != null && !scope.equals(currentScope.get())) {
            latestStatus = new ProviderStatus(Availability.UNAVAILABLE, VERSION, "native evidence scope is no longer current");
            return unknownResult();
        }
        latestStatus = new ProviderStatus(switch (result.coverage()) {
            case COMPLETE -> Availability.AVAILABLE;
            case PARTIAL -> Availability.PARTIAL;
            case OPAQUE -> Availability.OPAQUE;
            case UNAVAILABLE -> Availability.UNAVAILABLE;
            case ERROR -> Availability.ERROR;
        }, VERSION, "fresh client-visible native evidence");
        return result;
    }

    private int cursorOffset(PackSageTools.ReadRequest request, String signature) {
        var cursorValue = request.arguments().values().get("cursor");
        if (cursorValue != null && !(cursorValue instanceof Evidence.JsonNull) && !(cursorValue instanceof Evidence.JsonString)) {
            throw new IllegalArgumentException("cursor must be a string");
        }
        var token = cursorValue instanceof Evidence.JsonString value ? value.value() : null;
        if (token == null) return 0;
        synchronized (cursors) {
            var state = cursors.get(token);
            if (state == null) throw new Toolbox.Failure(
                    Toolbox.Code.INVALID_CURSOR, "native cursor is unknown or expired; repeat the lookup");
            if (state.createdAtNanos() + CURSOR_TTL_NANOS <= System.nanoTime()) {
                cursors.remove(token);
                throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "native cursor is unknown or expired; repeat the lookup");
            }
            if (!state.scope().equals(request.scope()) || !state.signature().equals(signature)) throw new Toolbox.Failure(
                    Toolbox.Code.INVALID_CURSOR, "native cursor does not match this query or scope");
            return state.offset();
        }
    }

    private String storeCursor(int offset, String signature, Evidence.Scope scope) {
        var token = UUID.randomUUID().toString();
        synchronized (cursors) {
            cursors.put(token, new QueryCursor(offset, signature, scope, System.nanoTime()));
            while (cursors.size() > MAX_CURSORS) cursors.remove(cursors.keySet().iterator().next());
        }
        return token;
    }

    private String signature(PackSageTools.ReadRequest request) {
        var values = new LinkedHashMap<>(request.arguments().values());
        values.remove("cursor");
        values.remove("limit");
        return request.tool() + "|" + request.scope() + "|" + Evidence.serialize(new Evidence.JsonObject(values));
    }

    private static boolean providerSelected(Evidence.JsonObject filter) {
        var provider = filter.values().get("provider");
        if (provider == null || provider instanceof Evidence.JsonNull) return true;
        if (provider instanceof Evidence.JsonString value && value.value().equals("native")) return true;
        throw new Toolbox.Failure(Toolbox.Code.NOT_HANDLED, "different native source");
    }

    private static Evidence.Result result(Evidence.Record record) {
        var coverage = record.source().coverage();
        return new Evidence.Result(List.of(record), coverage, coverage == Evidence.Coverage.COMPLETE ? Evidence.Outcome.COMPLETE
                : coverage == Evidence.Coverage.ERROR ? Evidence.Outcome.ERROR : coverage == Evidence.Coverage.UNAVAILABLE
                ? Evidence.Outcome.UNKNOWN : Evidence.Outcome.PARTIAL, null);
    }

    private static Evidence.Result partial(Evidence.Result result, String provenance) {
        var records = result.records().stream().map(record -> {
            var source = record.source();
            return new Evidence.Record(new Evidence.Source(source.scope(), source.providerId(), source.providerVersion(), source.sourceHandle(),
                    source.observedGeneration(), source.observedAt(), source.activity(), Evidence.Coverage.PARTIAL, source.authority(),
                    java.util.stream.Stream.concat(source.provenance().stream(), java.util.stream.Stream.of(provenance)).toList()),
                    record.kind(), record.title(), record.payload(), record.targets(), record.recipePayload());
        }).toList();
        return new Evidence.Result(records, Evidence.Coverage.PARTIAL, Evidence.Outcome.PARTIAL, result.nextCursor(),
                result.detail() == null ? provenance : result.detail() + "; " + provenance);
    }

    private static CompletionStage<Evidence.Result> invalid(String detail) {
        return CompletableFuture.failedFuture(new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, detail));
    }

    private static CompletionStage<Evidence.Result> unavailable(Evidence.Scope scope, String detail) {
        return CompletableFuture.completedFuture(new Evidence.Result(List.of(statusRecord(scope, detail, Evidence.Coverage.UNAVAILABLE)),
                Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null, detail));
    }

    private static Evidence.Result unknownReference(Evidence.Scope scope) {
        return new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null,
                "native reference no longer exists");
    }

    private static Evidence.Record statusRecord(Evidence.Scope scope, String detail, Evidence.Coverage coverage) {
        var source = new Evidence.Source(scope, "native", VERSION, "status:native", scope.requestGeneration(), Instant.now(),
                Evidence.Activity.UNKNOWN, coverage, Evidence.Authority.UNKNOWN, List.of(detail));
        return new Evidence.Record(source, Evidence.RecordKind.OTHER, detail,
                new Evidence.JsonObject(Map.of("detail", new Evidence.JsonString(detail))), List.of());
    }

    private static Evidence.Result failed(String detail, Throwable failure) {
        String reason = detail + (failure == null ? "" : ": " + PackSageLog.describe(failure));
        return new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null, reason);
    }

    private static Evidence.Result unknownResult() {
        return new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null);
    }

    private void clearCursors() {
        synchronized (cursors) { cursors.clear(); }
    }

    private record QueryCursor(int offset, String signature, Evidence.Scope scope, long createdAtNanos) { }
    private record Captured(NativeSnapshot snapshot) { }
    private record CaptureResult(Captured captured, Throwable failure) { }
}
