package dev.packsage.knowledge.nativegame;

import dev.packsage.PackSageLog;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeIndex;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.knowledge.RecipePlanner;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.knowledge.inventory.InventoryReader;
import dev.packsage.knowledge.storage.NativeStorageReader;
import dev.packsage.knowledge.storage.StorageReader;
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
import java.util.Set;
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
    private final Supplier<NativeSnapshot> snapshotSource;
    private final Minecraft client;
    private final Executor executor;
    private final Supplier<Evidence.Scope> currentScope;
    private final NativeInventoryReader inventory;
    private final NativeStorageReader storage;
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
        this.inventory = null;
        this.storage = null;
    }

    public NativeProvider(Minecraft client, Executor executor) {
        this(client, executor, () -> null);
    }

    public NativeProvider(Minecraft client, Executor executor, Supplier<Evidence.Scope> currentScope) {
        this.snapshotSource = null;
        this.client = Objects.requireNonNull(client);
        this.executor = Objects.requireNonNull(executor);
        this.currentScope = Objects.requireNonNull(currentScope);
        this.inventory = new NativeInventoryReader(client, executor, currentScope);
        this.storage = new NativeStorageReader(client, executor, currentScope, inventory::resolve);
    }

    @Override public String id() { return "native"; }

    @Override
    public Toolbox.Metadata metadata() {
        return new Toolbox.Metadata(id(), List.of(), true, List.of("item", "block", "item_tag", "enchantment", "mob_effect", "potion"),
                PackSageTools.NATIVE_INVENTORY_TARGETS);
    }

    @Override public ProviderStatus status() { return latestStatus; }

    @Override
    public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
        Objects.requireNonNull(query);
        Objects.requireNonNull(scope);
        return execute(scope, null, index -> index.search(query, scope).toCompletableFuture().join(), false);
    }

    public CompletionStage<Evidence.Result> planRecipe(String itemIdentity, long quantity, Evidence.Scope scope, int maxDepth) {
        Objects.requireNonNull(itemIdentity);
        Objects.requireNonNull(scope);
        if (client == null) return execute(scope, null, index -> new RecipePlanner(index).plan(itemIdentity, quantity, scope, maxDepth), false);
        activeScope.set(scope);
        return client.submit(() -> {
            if (!scope.equals(currentScope.get()) || client.level == null) return new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE,
                    Evidence.Outcome.UNKNOWN, null, "native recipe source unavailable");
            var key = ResourceLocation.tryParse(itemIdentity);
            if (key == null || !key.toString().equals(itemIdentity)
                    || client.level.registryAccess().registryOrThrow(Registries.ITEM).get(key) == null) {
                throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "recipe output item is unknown");
            }
            var candidates = NativeRecipeReader.planCandidates(client, scope);
            var index = new KnowledgeIndex();
            index.replace(new NativeSnapshot(scope, candidates));
            var result = new RecipePlanner(index).plan(itemIdentity, quantity, scope, maxDepth);
            boolean scanPartial = client.level.getRecipeManager().getRecipes().size() > NativeRecipeReader.MAX_CANDIDATES;
            if (scanPartial && result.coverage() == Evidence.Coverage.COMPLETE) return partial(result, "bounded-live-recipe-scan");
            return result;
        }).thenApply(result -> publish(result, scope));
    }

    public CompletionStage<Evidence.Result> readInventory(List<String> identities, Evidence.Scope scope) {
        Objects.requireNonNull(identities);
        Objects.requireNonNull(scope);
        if (identities.size() > 32 || identities.stream().anyMatch(identity -> identity == null || identity.isBlank())) {
            return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null,
                    "select up to 32 non-blank item identities"));
        }
        var requested = List.copyOf(identities);
        return execute(scope, requested, index -> index.search(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of()), 64, null, null), scope).toCompletableFuture().join(), true);
    }

    @Override
    public CompletionStage<Evidence.Result> searchInventory(PackSageTools.ReadRequest request, Toolbox.Context context) {
        if (client == null || inventory == null || storage == null) return unavailable(request.scope(), "native inventory source unavailable");
        var target = request.arguments().values().get("target");
        if (!(target instanceof Evidence.JsonObject object)
                || !(object.values().get("type") instanceof Evidence.JsonString type)) return CompletableFuture.failedFuture(
                new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "inventory target is invalid"));
        var filterValue = request.arguments().values().get("filter");
        if (filterValue != null && !(filterValue instanceof Evidence.JsonObject) && !(filterValue instanceof Evidence.JsonNull)) {
            return CompletableFuture.failedFuture(new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "inventory filter is invalid"));
        }
        var fields = filterValue instanceof Evidence.JsonObject value ? value : new Evidence.JsonObject(Map.of());
        try {
            var filter = new InventoryReader.Filter(strings(fields, "names"), strings(fields, "ids"), strings(fields, "tags"),
                    strings(fields, "tooltip"), strings(fields, "sections"));
            int limit = integer(request.arguments(), "limit", 32);
            String cursor = string(request.arguments(), "cursor");
            if (type.value().equals("player")) return inventory.read(new InventoryReader.Request(InventoryReader.Operation.SEARCH,
                    filter, limit, cursor, request.scope()));
            if (type.value().equals("opened") || type.value().equals("targeted") || type.value().equals("ridden") || type.value().equals("item")) {
                var storageTarget = switch (type.value()) {
                    case "opened" -> StorageReader.Target.OPENED;
                    case "targeted" -> StorageReader.Target.TARGETED;
                    case "ridden" -> StorageReader.Target.RIDDEN;
                    case "item" -> StorageReader.Target.ITEM;
                    default -> throw new IllegalStateException(type.value());
                };
                var reference = object.values().get("slotReference") instanceof Evidence.JsonString value ? value.value() : null;
                if (storageTarget == StorageReader.Target.ITEM && reference == null
                        && object.values().get("slot") instanceof Evidence.JsonString hand) {
                    return client.submit(() -> inventory.selectedReference(hand.value(), request.scope())).thenCompose(value ->
                            storage.read(new StorageReader.Request(storageTarget, value, null, List.of(), limit, cursor, request.scope(), filter)));
                }
                return storage.read(new StorageReader.Request(storageTarget, reference, null, List.of(), limit, cursor, request.scope(), filter));
            }
            return CompletableFuture.failedFuture(new Toolbox.Failure(Toolbox.Code.NOT_HANDLED, "native inventory target"));
        } catch (IllegalArgumentException failure) {
            return CompletableFuture.failedFuture(new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, failure.getMessage()));
        }
    }

    @Override
    public CompletionStage<Evidence.Result> searchRegistry(PackSageTools.ReadRequest request, Toolbox.Context context) {
        var filter = object(request.arguments(), "filter");
        if (!providerSelected(filter)) return CompletableFuture.failedFuture(new Toolbox.Failure(Toolbox.Code.NOT_HANDLED, "different registry source"));
        if (client == null) return unavailable(request.scope(), "live native registry source unavailable");
        var registryValue = request.arguments().values().get("registry");
        if (!(registryValue instanceof Evidence.JsonString registry)) return invalid("registry is required");
        if (!metadata().registries().contains(registry.value())) return CompletableFuture.failedFuture(
                new Toolbox.Failure(Toolbox.Code.NOT_HANDLED, "unsupported native registry"));
        try {
            String text = string(filter, "text");
            var ids = strings(filter, "ids");
            var tags = strings(filter, "tags");
            if (registry.value().equals("item_tag") && !tags.isEmpty()) return CompletableFuture.failedFuture(
                    new Toolbox.Failure(Toolbox.Code.UNSUPPORTED_FILTER, "item_tag does not support a tags membership filter"));
            int limit = integer(request.arguments(), "limit", 16);
            var signature = signature(request);
            int offset = cursorOffset(request, signature);
            return client.submit(() -> NativeRegistryReader.search(client, registry.value(), text, ids, tags, offset, limit, request.scope()))
                    .thenApply(page -> {
                        String next = page.exhausted() || page.nextOffset() <= offset ? null : storeCursor(page.nextOffset(), signature, request.scope());
                        boolean partial = page.partial() || !page.exhausted();
                        var result = new Evidence.Result(page.records(), partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                                partial ? Evidence.Outcome.PARTIAL : Evidence.Outcome.COMPLETE, next);
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
        var filter = object(request.arguments(), "filter");
        if (!providerSelected(filter)) return CompletableFuture.failedFuture(new Toolbox.Failure(Toolbox.Code.NOT_HANDLED, "different recipe source"));
        if (client == null) return unavailable(request.scope(), "live native recipe source unavailable");
        var output = string(filter, "outputItem");
        var text = string(filter, "text");
        if (output != null) {
            var key = ResourceLocation.tryParse(output);
            if (key == null || !key.toString().equals(output)) return invalid("outputItem must be a canonical item identity");
        }
        try {
            int limit = integer(request.arguments(), "limit", 16);
            var signature = signature(request);
            int offset = cursorOffset(request, signature);
            return client.submit(() -> {
                if (client.level == null) throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "native recipe source unavailable");
                if (output != null && client.level.registryAccess().registryOrThrow(Registries.ITEM)
                        .get(ResourceLocation.parse(output)) == null) throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS,
                        "recipe output item is unknown");
                return NativeRecipeReader.search(client, output, text, offset, limit, request.scope());
            }).thenApply(page -> {
                String next = page.exhausted() || page.nextOffset() <= offset ? null : storeCursor(page.nextOffset(), signature, request.scope());
                boolean partial = !page.exhausted() || page.records().stream().anyMatch(record -> record.source().coverage() != Evidence.Coverage.COMPLETE);
                var result = new Evidence.Result(page.records(), partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                        partial ? Evidence.Outcome.PARTIAL : Evidence.Outcome.COMPLETE, next);
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
        var identity = string(request.arguments(), "identity");
        if (identity == null) return invalid("identity is required");
        var quantity = integer(request.arguments(), "quantity", 1);
        var depth = integer(request.arguments(), "depth", 4);
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
        if (client == null) return unavailable(request.scope(), "live native evidence source unavailable");
        var source = reference.sourceHandle();
        if (source.startsWith("storage:")) return storage.readEvidence(source, request.scope());
        if (source.startsWith("inventory:")) return client.submit(() -> inventory.readEvidence(source, request.scope()))
                .thenApply(value -> value == null ? null : new Evidence.Result(List.of(value), value.source().coverage(),
                        value.source().coverage() == Evidence.Coverage.COMPLETE ? Evidence.Outcome.COMPLETE : Evidence.Outcome.PARTIAL, null));
        if (source.startsWith("item:") || source.startsWith("block:") || source.startsWith("tag:")
                || source.startsWith("enchantment:") || source.startsWith("mob_effect:") || source.startsWith("potion:")) {
            int separator = source.indexOf(':');
            var registry = source.substring(0, separator).equals("tag") ? "item_tag" : source.substring(0, separator);
            var identity = source.substring(separator + 1);
            return client.submit(() -> NativeRegistryReader.read(client, registry, identity, request.scope()))
                    .thenApply(value -> value == null ? unknownReference(request.scope()) : result(value));
        }
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

    private CompletionStage<Evidence.Result> execute(Evidence.Scope scope, List<String> selectedIdentities,
                                                       Function<KnowledgeIndex, Evidence.Result> operation,
                                                       boolean emptySelectionIsComplete) {
        var previousScope = activeScope.getAndSet(scope);
        if (previousScope != null && !previousScope.equals(scope)) {
            clearCursors();
            latestStatus = new ProviderStatus(Availability.UNAVAILABLE, VERSION, "native evidence scope changed");
        }
        CompletionStage<Captured> captured;
        try {
            captured = client == null ? CompletableFuture.supplyAsync(() -> {
                var snapshot = NativeSnapshot.capture(snapshotSource);
                return new Captured(selectedIdentities == null ? snapshot : selectedInventory(snapshot, selectedIdentities), 0);
            }, executor) : client.submit(() -> new Captured(selectedIdentities == null ? NativeSnapshot.capture(client, scope)
                    : NativeSnapshot.captureSelectedInventory(client, scope, selectedIdentities),
                    client.level == null ? 0 : System.identityHashCode(client.level)));
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(failed("native capture could not start", failure));
        }
        return captured.handle((result, failure) -> new CaptureResult(result, failure)).thenApplyAsync(result -> {
            if (result.failure() != null || result.captured() == null) return failed("native capture failed", result.failure());
            if (!scope.equals(activeScope.get()) || currentScope.get() != null && !scope.equals(currentScope.get())) return unknownResult();
            if (result.captured().snapshot() == null || !result.captured().snapshot().scope().equals(scope)) return unknownResult();
            if (emptySelectionIsComplete && result.captured().snapshot().records().isEmpty()) return new Evidence.Result(List.of(),
                    Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null);
            try {
                var index = new KnowledgeIndex();
                index.replace(result.captured().snapshot());
                return operation.apply(index);
            } catch (RuntimeException failure) {
                return failed("native index query failed", failure);
            }
        }, executor).thenCompose(result -> client == null ? CompletableFuture.completedFuture(publish(result, scope))
                : client.submit(() -> {
                    int levelToken = client.level == null ? 0 : System.identityHashCode(client.level);
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
        var token = string(request.arguments(), "cursor");
        if (token == null) return 0;
        synchronized (cursors) {
            var state = cursors.remove(token);
            if (state == null || state.createdAtNanos() + CURSOR_TTL_NANOS <= System.nanoTime()) throw new Toolbox.Failure(
                    Toolbox.Code.INVALID_CURSOR, "native cursor is unknown or expired; repeat the lookup");
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

    private static Evidence.JsonObject object(Evidence.JsonObject parent, String field) {
        var value = parent.values().get(field);
        if (value == null || value instanceof Evidence.JsonNull) return new Evidence.JsonObject(Map.of());
        if (value instanceof Evidence.JsonObject object) return object;
        throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, field + " must be an object");
    }

    private static List<String> strings(Evidence.JsonObject object, String field) {
        var value = object.values().get(field);
        if (value == null || value instanceof Evidence.JsonNull) return List.of();
        if (!(value instanceof Evidence.JsonArray array) || array.values().stream().anyMatch(item -> !(item instanceof Evidence.JsonString))) {
            throw new IllegalArgumentException(field + " must be an array of strings");
        }
        return array.values().stream().map(Evidence.JsonString.class::cast).map(Evidence.JsonString::value).toList();
    }

    private static String string(Evidence.JsonObject object, String field) {
        var value = object.values().get(field);
        if (value == null || value instanceof Evidence.JsonNull) return null;
        if (value instanceof Evidence.JsonString text) return text.value();
        throw new IllegalArgumentException(field + " must be a string");
    }

    private static int integer(Evidence.JsonObject object, String field, int fallback) {
        var value = object.values().get(field);
        if (value == null || value instanceof Evidence.JsonNull) return fallback;
        if (!(value instanceof Evidence.JsonNumber number)) throw new IllegalArgumentException(field + " must be an integer");
        return number.value().intValueExact();
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
        return new Evidence.Result(records, Evidence.Coverage.PARTIAL, Evidence.Outcome.PARTIAL, result.nextCursor(), result.detail());
    }

    private static CompletionStage<Evidence.Result> invalid(String detail) {
        return CompletableFuture.failedFuture(new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, detail));
    }

    private static Evidence.Result unavailable(Evidence.Scope scope, String detail) {
        return new Evidence.Result(List.of(statusRecord(scope, detail, Evidence.Coverage.UNAVAILABLE)), Evidence.Coverage.UNAVAILABLE,
                Evidence.Outcome.UNKNOWN, null, detail);
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
        return new Evidence.Result(List.of(), Evidence.Coverage.PARTIAL, Evidence.Outcome.UNKNOWN, null);
    }

    private void clearCursors() {
        synchronized (cursors) { cursors.clear(); }
    }

    private static NativeSnapshot selectedInventory(NativeSnapshot snapshot, List<String> identities) {
        var requested = Set.copyOf(identities);
        var selected = snapshot.records().stream().filter(record -> record.kind() == Evidence.RecordKind.OTHER
                && (record.source().coverage() == Evidence.Coverage.UNAVAILABLE || record.source().coverage() == Evidence.Coverage.ERROR)
                || record.kind() == Evidence.RecordKind.ITEM
                && ("selected".equals(record.payload().values().get("inventoryObservation") instanceof Evidence.JsonString value ? value.value() : null)
                || "occupied".equals(record.payload().values().get("inventoryObservation") instanceof Evidence.JsonString value ? value.value() : null))
                && (requested.isEmpty() || requested.contains(record.payload().values().get("id") instanceof Evidence.JsonString value ? value.value() : null))).toList();
        return new NativeSnapshot(snapshot.scope(), selected);
    }

    private record QueryCursor(int offset, String signature, Evidence.Scope scope, long createdAtNanos) { }
    private record Captured(NativeSnapshot snapshot, int levelToken) { }
    private record CaptureResult(Captured captured, Throwable failure) { }
}
