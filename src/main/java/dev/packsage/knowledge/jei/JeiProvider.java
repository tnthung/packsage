package dev.packsage.knowledge.jei;

import dev.packsage.PackSageLog;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.model.PackSageTools;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Read-only JEI evidence. Snapshots and navigation are captured on the JEI client thread. */
public final class JeiProvider implements KnowledgeProvider {
    public static final String ID = "jei";
    public static final String VERSION = "19.27.0.340";

    @FunctionalInterface
    public interface SnapshotSource {
        Snapshot capture(Evidence.Scope scope, Evidence.Query query, Toolbox.DocumentBudget budget);
    }

    public record Ingredient(String type, String comparisonIdentity, Evidence.JsonObject components,
                             Integer quantity, Evidence.Consumption consumption) {
        public Ingredient {
            Objects.requireNonNull(type);
            Objects.requireNonNull(comparisonIdentity);
            Objects.requireNonNull(components);
            Objects.requireNonNull(consumption);
            if (quantity != null && quantity < 1) throw new IllegalArgumentException("quantity must be positive");
        }
    }

    public record Slot(String groupId, List<Ingredient> alternatives, RecipeRole role) {
        public Slot {
            Objects.requireNonNull(groupId);
            alternatives = List.copyOf(alternatives);
            Objects.requireNonNull(role);
        }
    }

    public enum RecipeRole { INPUT, OUTPUT, CATALYST, RENDER_ONLY }

    public record Projection(String categoryId, String viewerHandle, String canonicalRecipeId, String title,
                             List<Slot> slots, List<Ingredient> catalysts, List<String> information,
                             boolean visible, boolean visibilityKnown, Evidence.Coverage coverage,
                             Evidence.DecodingStatus decoding, String error) {
        public Projection {
            Objects.requireNonNull(categoryId);
            Objects.requireNonNull(viewerHandle);
            Objects.requireNonNull(title);
            slots = List.copyOf(slots);
            catalysts = List.copyOf(catalysts);
            information = List.copyOf(information);
            Objects.requireNonNull(coverage);
            Objects.requireNonNull(decoding);
        }

        public Projection(String categoryId, String viewerHandle, String canonicalRecipeId, String title,
                          List<Slot> slots, List<Ingredient> catalysts, List<String> information,
                          boolean visible, Evidence.Coverage coverage, Evidence.DecodingStatus decoding,
                          String error) {
            this(categoryId, viewerHandle, canonicalRecipeId, title, slots, catalysts, information, visible, true,
                    coverage, decoding, error);
        }

        public static Projection opaque(String categoryId, String viewerHandle, String title, String error) {
            return new Projection(categoryId, viewerHandle, null, title, List.of(), List.of(), List.of(), false, false,
                    Evidence.Coverage.OPAQUE, Evidence.DecodingStatus.OPAQUE, error);
        }

        public static Projection error(String categoryId, String viewerHandle, String canonicalRecipeId,
                                       String title, String error) {
            return new Projection(categoryId, viewerHandle, canonicalRecipeId, title, List.of(), List.of(), List.of(),
                    false, false, Evidence.Coverage.ERROR, Evidence.DecodingStatus.UNKNOWN, error);
        }
    }

    public record Snapshot(Evidence.Scope scope, long generation, long snapshotVersion,
                           List<Projection> projections, Consumer<String> navigator,
                           Evidence.Coverage coverage, boolean queryScoped, String nextCursor) {
        public Snapshot {
            Objects.requireNonNull(scope);
            projections = List.copyOf(projections);
            Objects.requireNonNull(navigator);
            Objects.requireNonNull(coverage);
        }

        public Snapshot(Evidence.Scope scope, long generation, List<Projection> projections,
                        Consumer<String> navigator) {
            this(scope, generation, generation, projections, navigator, coverage(projections), false, null);
        }

        public Snapshot(Evidence.Scope scope, long generation, long snapshotVersion,
                        List<Projection> projections, Consumer<String> navigator) {
            this(scope, generation, snapshotVersion, projections, navigator, coverage(projections), false, null);
        }

        private static Evidence.Coverage coverage(List<Projection> projections) {
            if (projections.stream().anyMatch(projection -> projection.coverage() == Evidence.Coverage.ERROR)) {
                return Evidence.Coverage.ERROR;
            }
            if (projections.stream().anyMatch(projection -> projection.coverage() == Evidence.Coverage.PARTIAL)) {
                return Evidence.Coverage.PARTIAL;
            }
            if (!projections.isEmpty() && projections.stream().allMatch(projection ->
                    projection.coverage() == Evidence.Coverage.OPAQUE)) return Evidence.Coverage.OPAQUE;
            return Evidence.Coverage.COMPLETE;
        }
    }

    private final Map<String, RecipeCursorState> recipeCursors = new LinkedHashMap<>();
    private final SnapshotSource snapshots;
    private final Supplier<Snapshot> refreshSnapshot;
    private final Executor clientExecutor;
    private final BooleanSupplier owningThread;
    private final java.util.function.LongSupplier clientTick;
    private final Executor laterTick;
    private long lastCaptureTick = Long.MIN_VALUE;
    private volatile SnapshotStatus currentStatus;
    private volatile boolean runtimeAvailable;

    private record SnapshotStatus(Evidence.Scope scope, long generation, long snapshotVersion,
                                  Evidence.Coverage coverage) { }

    public JeiProvider(SnapshotSource snapshots, Executor clientExecutor, BooleanSupplier owningThread) {
        this(snapshots, clientExecutor, owningThread, new java.util.concurrent.atomic.AtomicLong()::getAndIncrement, clientExecutor);
    }

    public JeiProvider(SnapshotSource snapshots, Executor clientExecutor, BooleanSupplier owningThread,
                       java.util.function.LongSupplier clientTick, Executor laterTick) {
        this.snapshots = Objects.requireNonNull(snapshots);
        this.refreshSnapshot = null;
        this.clientExecutor = Objects.requireNonNull(clientExecutor);
        this.owningThread = Objects.requireNonNull(owningThread);
        this.clientTick = Objects.requireNonNull(clientTick);
        this.laterTick = Objects.requireNonNull(laterTick);
        runtimeAvailable = true;
    }

    public JeiProvider(SnapshotSource snapshots, Executor clientExecutor) {
        this(snapshots, clientExecutor, () -> false);
    }

    public JeiProvider(Function<Evidence.Scope, Snapshot> snapshots, Executor clientExecutor,
                       BooleanSupplier owningThread) {
        this((scope, query, budget) -> snapshots.apply(scope), clientExecutor, owningThread);
    }

    public JeiProvider(Function<Evidence.Scope, Snapshot> snapshots, Executor clientExecutor) {
        this(snapshots, clientExecutor, () -> false);
    }

    public JeiProvider(Function<Evidence.Scope, Snapshot> snapshots) {
        this(snapshots, Runnable::run, () -> true);
    }

    public JeiProvider(Supplier<Snapshot> snapshots) {
        this((scope, query, budget) -> snapshots.get(), snapshots, Runnable::run, () -> true);
    }

    public JeiProvider(Supplier<Snapshot> snapshots, Executor clientExecutor, BooleanSupplier owningThread) {
        this((scope, query, budget) -> snapshots.get(), snapshots, clientExecutor, owningThread);
    }

    public JeiProvider(Supplier<Snapshot> snapshots, Executor clientExecutor) {
        this(snapshots, clientExecutor, () -> false);
    }

    public JeiProvider(Snapshot snapshot) {
        this(() -> snapshot);
    }

    private JeiProvider(SnapshotSource snapshots, Supplier<Snapshot> refreshSnapshot,
                        Executor clientExecutor, BooleanSupplier owningThread) {
        this.snapshots = Objects.requireNonNull(snapshots);
        this.refreshSnapshot = Objects.requireNonNull(refreshSnapshot);
        this.clientExecutor = Objects.requireNonNull(clientExecutor);
        this.owningThread = Objects.requireNonNull(owningThread);
        this.clientTick = new java.util.concurrent.atomic.AtomicLong()::getAndIncrement;
        this.laterTick = clientExecutor;
        runtimeAvailable = true;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean enabled() { return runtimeAvailable; }

    @Override
    public ProviderStatus status() {
        if (!runtimeAvailable) return new ProviderStatus(Availability.UNAVAILABLE, VERSION, "JEI runtime unavailable");
        var status = currentStatus;
        if (status == null) return new ProviderStatus(Availability.AVAILABLE, VERSION, "JEI runtime available");
        var availability = status.coverage() == Evidence.Coverage.ERROR ? Availability.ERROR
                : status.coverage() == Evidence.Coverage.OPAQUE ? Availability.OPAQUE
                : status.coverage() != Evidence.Coverage.COMPLETE ? Availability.PARTIAL : Availability.AVAILABLE;
        return new ProviderStatus(availability, VERSION, "JEI runtime generation " + status.generation()
                + " snapshot " + status.snapshotVersion());
    }

    @Override
    public Toolbox.Metadata metadata() {
        return new Toolbox.Metadata(ID, List.of(), true, List.of(), Map.of());
    }

    @Override
    public CompletionStage<Evidence.Result> searchRecipe(PackSageTools.ReadRequest request, Toolbox.Context context) {
        var filter = request.arguments().values().get("filter");
        var values = filter instanceof Evidence.JsonObject selected ? selected.values() : Map.<String, Evidence.JsonValue>of();
        if (!(values.get("provider") instanceof Evidence.JsonString provider) || !ID.equals(provider.value())) {
            return CompletableFuture.failedFuture(new Toolbox.Failure(Toolbox.Code.NOT_HANDLED,
                    "JEI only handles provider-qualified recipe searches"));
        }
        if (values.containsKey("outputItem") && !(values.get("outputItem") instanceof Evidence.JsonNull)) {
            if (!(values.get("outputItem") instanceof Evidence.JsonString outputItem)) return CompletableFuture.failedFuture(
                    new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "outputItem must be a valid item resource ID"));
            var itemId = ResourceLocation.tryParse(outputItem.value());
            if (itemId == null || !itemId.toString().equals(outputItem.value()) || !BuiltInRegistries.ITEM.containsKey(itemId)) return CompletableFuture.failedFuture(
                    new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "outputItem must name a registered item"));
        }
        if (!request.scope().equals(context.currentScope().get())) return CompletableFuture.failedFuture(
                new Toolbox.Failure(Toolbox.Code.STALE_SCOPE, "repeat the lookup in the current scope"));
        var arguments = new LinkedHashMap<String, Evidence.JsonValue>();
        if (values.get("text") instanceof Evidence.JsonString text) arguments.put("text", text);
        if (values.get("outputItem") instanceof Evidence.JsonString outputItem) {
            arguments.put("outputItem", outputItem);
        }
        var cursor = request.arguments().values().get("cursor") instanceof Evidence.JsonString value ? value.value() : null;
        String sourceCursor;
        try {
            if (cursor != null && !context.documentBudget().bytes(cursor.length() * 2))
                throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "JEI cursor exceeded the shared read budget");
            sourceCursor = decodeRecipeCursor(cursor, arguments, request.scope());
        } catch (Toolbox.Failure failure) {
            return CompletableFuture.failedFuture(failure);
        }
        var limit = request.arguments().values().get("limit") instanceof Evidence.JsonNumber value
                ? value.value().intValue() : 16;
        try {
            return query(new Evidence.Query(Evidence.Operation.SEARCH, new Evidence.JsonObject(arguments), limit,
                    sourceCursor == null ? null : request.scope(), sourceCursor), request.scope(), context.documentBudget()).thenApply(result -> {
                if (!request.scope().equals(context.currentScope().get())) throw new Toolbox.Failure(Toolbox.Code.STALE_SCOPE,
                        "scope changed while reading JEI");
                if (result.coverage() == Evidence.Coverage.ERROR) throw new Toolbox.Failure(
                        result.detail() != null && result.detail().contains("cursor")
                                ? Toolbox.Code.INVALID_CURSOR : Toolbox.Code.EXTRACTION_FAILED,
                        result.detail() == null ? "JEI extraction failed" : result.detail());
                return result.nextCursor() == null ? result : new Evidence.Result(result.records(), result.coverage(),
                        result.outcome(), encodeRecipeCursor(arguments, result.nextCursor(), request.scope()), result.detail());
            });
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override
    public CompletionStage<Evidence.Result> readEvidence(PackSageTools.ReadRequest request, Toolbox.Context context) {
        var reference = context.reference(request);
        if (!ID.equals(reference.providerId())) return CompletableFuture.failedFuture(
                new Toolbox.Failure(Toolbox.Code.NOT_HANDLED, "JEI does not own this reference"));
        if (reference.kind() != Evidence.RecordKind.RECIPE) return CompletableFuture.failedFuture(
                new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE, "expected an observed JEI recipe reference"));
        return query(new Evidence.Query(Evidence.Operation.GET, new Evidence.JsonObject(Map.of(
                "id", new Evidence.JsonString(reference.sourceHandle()))), 1, null, null), request.scope(),
                context.documentBudget())
                .thenApply(result -> {
                    if (!request.scope().equals(context.currentScope().get())) throw new Toolbox.Failure(
                            Toolbox.Code.STALE_SCOPE, "scope changed while reading JEI");
                    if (result.coverage() == Evidence.Coverage.ERROR) throw new Toolbox.Failure(
                            Toolbox.Code.EXTRACTION_FAILED, result.detail() == null ? "current JEI extraction failed" : result.detail());
                    if (result.records().isEmpty()) throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE,
                            "the observed JEI recipe is no longer registered");
                    return result;
                });
    }

    private record RecipeCursorState(Map<String, Evidence.JsonValue> arguments, String position, Evidence.Scope scope) { }

    private synchronized String encodeRecipeCursor(Map<String, Evidence.JsonValue> arguments, String position,
                                                    Evidence.Scope scope) {
        var cursor = "jei:" + java.util.UUID.randomUUID();
        recipeCursors.put(cursor, new RecipeCursorState(Map.copyOf(arguments), position, scope));
        while (recipeCursors.size() > 128) recipeCursors.remove(recipeCursors.keySet().iterator().next());
        return cursor;
    }

    private synchronized String decodeRecipeCursor(String cursor, Map<String, Evidence.JsonValue> arguments,
                                                    Evidence.Scope scope) {
        if (cursor == null) return null;
        var saved = cursor.length() <= 1024 && cursor.startsWith("jei:") ? recipeCursors.get(cursor) : null;
        if (saved == null || !saved.arguments().equals(arguments) || !saved.scope().equals(scope)
                || parseCursor(saved.position()) < 0) {
            throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "JEI cursor is invalid for this query or scope; restart the search");
        }
        return saved.position();
    }

    public void refresh() {
        if (refreshSnapshot == null) return;
        try {
            var snapshot = refreshSnapshot.get();
            currentStatus = snapshot == null ? null : new SnapshotStatus(snapshot.scope(), snapshot.generation(),
                    snapshot.snapshotVersion(), snapshot.coverage());
            runtimeAvailable = true;
        } catch (RuntimeException failure) {
            PackSageLog.LOGGER.warn("PackSage JEI snapshot refresh failed", failure);
            currentStatus = null;
            runtimeAvailable = false;
        }
    }

    public void unavailable() {
        runtimeAvailable = false;
        currentStatus = null;
    }

    @Override
    public CompletionStage<Evidence.NavigationTarget> refreshNavigation(Evidence.NavigationTarget target,
                                                                         Evidence.Scope scope) {
        if (target == null || scope == null || !ID.equals(target.providerId())
                || !(target.targetId().startsWith("recipe-v1|") || target.targetId().startsWith("category-v1|")
                || target.targetId().startsWith("jei/") && target.targetId().contains("/recipe-"))
                && !sameEnvironment(target.scope(), scope)) return CompletableFuture.completedFuture(null);
        return query(new Evidence.Query(Evidence.Operation.GET, new Evidence.JsonObject(Map.of(
                "id", new Evidence.JsonString(target.targetId()))), 1, null, null), scope)
                .thenApply(result -> {
                    if (result == null || result.records().size() != 1 || result.outcome() == Evidence.Outcome.ERROR) {
                        return null;
                    }
                    var record = result.records().getFirst();
                    if (!record.source().scope().equals(scope) || !ID.equals(record.source().providerId())
                            || record.targets().size() != 1) return null;
                    var refreshed = record.targets().getFirst();
                    if (!scope.equals(refreshed.scope()) || !ID.equals(refreshed.providerId())) return null;
                    if (target.targetId().startsWith("recipe-v1|")) {
                        return target.targetId().equals(refreshed.targetId()) ? refreshed : null;
                    }
                    if (target.targetId().startsWith("category-v1|")) {
                        var category = record.payload().values().get("categoryId");
                        if (!(category instanceof Evidence.JsonString categoryId)) return null;
                        var categoryIdValue = categoryId.value();
                        var parsedCategory = ResourceLocation.tryParse(categoryIdValue);
                        var currentCategoryTarget = parsedCategory != null
                                && parsedCategory.toString().equals(categoryIdValue)
                                ? "category-v1|" + categoryIdValue : null;
                        return target.targetId().equals(currentCategoryTarget)
                                && refreshed.targetId().equals(currentCategoryTarget) ? refreshed : null;
                    }
                    if (!target.targetId().equals(target.sourceHandle()) || record.recipePayload() == null) return null;
                    var category = record.payload().values().get("categoryId");
                    if (record.recipePayload().canonicalRecipeId() == null) {
                        return sameEnvironment(target.scope(), scope) && target.sourceHandle().equals(refreshed.sourceHandle())
                                && target.targetId().equals(refreshed.targetId()) ? refreshed : null;
                    }
                    if (!(category instanceof Evidence.JsonString categoryId)) return null;
                    var categoryIdValue = categoryId.value();
                    var parsedCategory = ResourceLocation.tryParse(categoryIdValue);
                    var parsedRecipe = ResourceLocation.tryParse(record.recipePayload().canonicalRecipeId());
                    return parsedCategory != null && parsedRecipe != null
                            && parsedCategory.toString().equals(categoryIdValue)
                            && parsedRecipe.toString().equals(record.recipePayload().canonicalRecipeId())
                            && refreshed.targetId().equals("recipe-v1|" + categoryIdValue + "|"
                            + record.recipePayload().canonicalRecipeId()) ? refreshed : null;
                });
    }

    @Override
    public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
        return query(query, scope, new Toolbox.DocumentBudget());
    }

    private CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope,
                                                   Toolbox.DocumentBudget budget) {
        Objects.requireNonNull(query);
        Objects.requireNonNull(scope);
        Objects.requireNonNull(budget);
        if (!runtimeAvailable) return CompletableFuture.completedFuture(unavailableResult());
        var cursor = parseCursor(query.cursor());
        if (cursor < 0) return CompletableFuture.completedFuture(errorResult("JEI cursor is invalid; repeat the search without a cursor"));
        var captured = new CompletableFuture<Snapshot>();
        clientExecutor.execute(new Runnable() {
            @Override
            public void run() {
                // At most one scan window per client tick: the client drains chained tasks within one tick.
                long tick = clientTick.getAsLong();
                if (tick == lastCaptureTick) {
                    laterTick.execute(this);
                    return;
                }
                lastCaptureTick = tick;
                try {
                    captured.complete(snapshots.capture(scope, query, budget));
                } catch (Throwable failure) {
                    captured.completeExceptionally(failure);
                }
            }
        });
        return captured
                .handle((snapshot, failure) -> {
                    if (failure != null || !runtimeAvailable || snapshot == null) {
                        if (failure != null || snapshot == null) currentStatus = null;
                        return unavailableOrError(failure);
                    }
                    if (!sameScope(snapshot.scope(), scope) || query.cursor() != null && query.cursorScope() != null
                            && !sameScope(query.cursorScope(), scope)) {
                        currentStatus = null;
                        return new Evidence.Result(List.of(), Evidence.Coverage.PARTIAL, Evidence.Outcome.UNKNOWN, null);
                    }
                    var result = snapshot.queryScoped() ? queryScopedResult(snapshot, query)
                            : querySnapshot(snapshot, query, cursor);
                    currentStatus = new SnapshotStatus(snapshot.scope(), snapshot.generation(), snapshot.snapshotVersion(),
                            snapshot.coverage());
                    return result;
                });
    }

    @Override
    public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
        Objects.requireNonNull(target);
        Objects.requireNonNull(scope);
        if (!runtimeAvailable) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "JEI runtime unavailable");
        }
        if (!ID.equals(target.providerId()) || !sameScope(target.scope(), scope)) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.STALE,
                    "JEI target is outside the active scope");
        }
        var result = new CompletableFuture<Evidence.NavigationResult>();
        Runnable action = () -> {
            if (!runtimeAvailable) {
                result.complete(new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "JEI runtime unavailable"));
                return;
            }
            try {
                var query = new Evidence.Query(Evidence.Operation.GET, new Evidence.JsonObject(Map.of(
                        "id", new Evidence.JsonString(target.sourceHandle()))), 1, null, null);
                var snapshot = snapshots.capture(scope, query, new Toolbox.DocumentBudget());
                if (snapshot == null || !sameScope(snapshot.scope(), scope)) {
                    result.complete(new Evidence.NavigationResult(Evidence.NavigationStatus.STALE,
                            "JEI target is outside the active runtime"));
                    return;
                }
                var projection = matchingProjection(snapshot, target, scope);
                if (projection == null) {
                    result.complete(new Evidence.NavigationResult(Evidence.NavigationStatus.STALE,
                            "JEI target is no longer registered"));
                    return;
                }
                if (projection.coverage() == Evidence.Coverage.ERROR) {
                    result.complete(new Evidence.NavigationResult(Evidence.NavigationStatus.UNSUPPORTED,
                            "JEI projection failed and cannot be opened"));
                    return;
                }
                if (!projection.visible() || !projection.visibilityKnown()) {
                    result.complete(new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE,
                            "JEI recipe visibility is unavailable"));
                    return;
                }
                result.complete(navigate(snapshot, projection.viewerHandle()));
            } catch (RuntimeException failure) {
                result.complete(new Evidence.NavigationResult(Evidence.NavigationStatus.ERROR, failure.toString()));
            }
        };
        if (owningThread.getAsBoolean()) action.run();
        else clientExecutor.execute(action);
        try {
            return result.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException failure) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.ERROR, "JEI client thread did not respond");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return new Evidence.NavigationResult(Evidence.NavigationStatus.ERROR, "JEI navigation was interrupted");
        } catch (java.util.concurrent.ExecutionException failure) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.ERROR, failure.getCause().toString());
        }
    }

    static Evidence.Record toRecord(Projection projection, Snapshot snapshot) {
        var inputs = projection.coverage() == Evidence.Coverage.OPAQUE || projection.coverage() == Evidence.Coverage.ERROR
                || projection.decoding() == Evidence.DecodingStatus.OPAQUE
                ? List.<Evidence.RecipeSlot>of() : projection.slots().stream().filter(slot -> slot.role() == RecipeRole.INPUT)
                .map(slot -> new Evidence.RecipeSlot(slot.groupId(), slot.alternatives().stream()
                        .map(JeiProvider::toAlternative).toList())).toList();
        var outputs = projection.slots().stream().filter(slot -> slot.role() == RecipeRole.OUTPUT)
                .flatMap(slot -> slot.alternatives().stream()).map(JeiProvider::toOutput).toList();
        var catalysts = new ArrayList<Ingredient>(projection.catalysts());
        projection.slots().stream().filter(slot -> slot.role() == RecipeRole.CATALYST)
                .flatMap(slot -> slot.alternatives().stream()).forEach(value -> {
                    if (!projection.catalysts().contains(value)) catalysts.add(value);
                });
        var renderOnly = projection.slots().stream().filter(slot -> slot.role() == RecipeRole.RENDER_ONLY)
                .map(Slot::groupId).toList();
        var payloadValues = new LinkedHashMap<String, Evidence.JsonValue>();
        payloadValues.put("categoryId", new Evidence.JsonString(projection.categoryId()));
        payloadValues.put("viewerHandle", new Evidence.JsonString(projection.viewerHandle()));
        payloadValues.put("visible", new Evidence.JsonBoolean(projection.visible()));
        payloadValues.put("visibilityKnown", new Evidence.JsonBoolean(projection.visibilityKnown()));
        payloadValues.put("canonicalRecipeId", projection.canonicalRecipeId() == null ? new Evidence.JsonNull()
                : new Evidence.JsonString(projection.canonicalRecipeId()));
        payloadValues.put("slots", new Evidence.JsonArray(projection.slots().stream().map(JeiProvider::slotJson)
                .map(value -> (Evidence.JsonValue) value).toList()));
        payloadValues.put("catalysts", new Evidence.JsonArray(catalysts.stream().map(JeiProvider::ingredientJson)
                .map(value -> (Evidence.JsonValue) value).toList()));
        payloadValues.put("information", new Evidence.JsonArray(projection.information().stream()
                .map(Evidence.JsonString::new).map(value -> (Evidence.JsonValue) value).toList()));
        payloadValues.put("coverage", new Evidence.JsonString(projection.coverage().name()));
        payloadValues.put("decoding", new Evidence.JsonString(projection.decoding().name()));
        payloadValues.put("renderOnlySlots", new Evidence.JsonArray(renderOnly.stream().map(Evidence.JsonString::new)
                .map(value -> (Evidence.JsonValue) value).toList()));
        payloadValues.put("error", projection.error() == null ? new Evidence.JsonNull() : new Evidence.JsonString(projection.error()));
        var payload = new Evidence.RecipePayload(projection.canonicalRecipeId(), inputs, outputs, List.of(),
                catalysts.stream().map(JeiProvider::toAlternative).toList(), projection.information(), projection.decoding());
        var source = new Evidence.Source(snapshot.scope(), ID, VERSION, projection.viewerHandle(), snapshot.generation(),
                Instant.now(), Evidence.Activity.ACTIVE, projection.coverage(), Evidence.Authority.VIEWER,
                List.of("jei:" + projection.categoryId(), "runtime-generation:" + snapshot.generation(),
                        "snapshot-version:" + snapshot.snapshotVersion()));
        var categoryId = ResourceLocation.tryParse(projection.categoryId());
        var canonicalRecipeId = projection.canonicalRecipeId() == null ? null
                : ResourceLocation.tryParse(projection.canonicalRecipeId());
        var persistentTarget = projection.viewerHandle().endsWith("/category")
                ? categoryId != null && categoryId.toString().equals(projection.categoryId())
                ? "category-v1|" + projection.categoryId() : null
                : categoryId != null && canonicalRecipeId != null
                && categoryId.toString().equals(projection.categoryId())
                && canonicalRecipeId.toString().equals(projection.canonicalRecipeId())
                ? "recipe-v1|" + projection.categoryId() + "|" + projection.canonicalRecipeId() : null;
        var targets = projection.coverage() == Evidence.Coverage.ERROR ? List.<Evidence.NavigationTarget>of()
                : List.of(new Evidence.NavigationTarget(snapshot.scope(), ID, projection.viewerHandle(),
                persistentTarget == null ? projection.viewerHandle() : persistentTarget));
        return new Evidence.Record(source, Evidence.RecordKind.RECIPE, projection.title(), new Evidence.JsonObject(payloadValues),
                targets, payload);
    }

    private static Evidence.Result querySnapshot(Snapshot snapshot, Evidence.Query query, int start) {
        var text = stringArgument(query.arguments(), "text");
        text = text == null ? "" : text.toLowerCase(Locale.ROOT);
        var id = stringArgument(query.arguments(), "id");
        var outputItem = query.operation() == Evidence.Operation.SEARCH || query.operation() == Evidence.Operation.RELATED
                ? stringArgument(query.arguments(), "outputItem") : null;
        var selected = new ArrayList<Evidence.Record>();
        int index = start;
        for (; index < snapshot.projections().size() && selected.size() < query.limit(); index++) {
            var projection = snapshot.projections().get(index);
            if (!matchesQuery(projection, query.operation(), id, text)) continue;
            if (outputItem != null && !projection.slots().stream()
                    .filter(slot -> slot.role() == RecipeRole.OUTPUT)
                    .flatMap(slot -> slot.alternatives().stream())
                    .anyMatch(ingredient -> ingredient.type().equals("item")
                            && ingredient.comparisonIdentity().split("\\|componentsHash=", 2)[0].equals(outputItem))) continue;
            selected.add(toRecord(projection, snapshot));
        }
        var next = index < snapshot.projections().size() ? Integer.toString(index) : null;
        var coverage = snapshot.coverage();
        if (coverage == Evidence.Coverage.COMPLETE && selected.stream().anyMatch(record ->
                record.source().coverage() != Evidence.Coverage.COMPLETE)) {
            coverage = selected.stream().anyMatch(record -> record.source().coverage() == Evidence.Coverage.ERROR)
                    ? Evidence.Coverage.ERROR : Evidence.Coverage.PARTIAL;
        }
        return result(selected, coverage, next);
    }

    private static Evidence.Result queryScopedResult(Snapshot snapshot, Evidence.Query query) {
        var rawText = stringArgument(query.arguments(), "text");
        var text = rawText == null ? "" : rawText.toLowerCase(Locale.ROOT);
        var outputItem = query.operation() == Evidence.Operation.SEARCH || query.operation() == Evidence.Operation.RELATED
                ? stringArgument(query.arguments(), "outputItem") : null;
        var records = snapshot.projections().stream().filter(projection -> query.operation() != Evidence.Operation.SEARCH
                        && query.operation() != Evidence.Operation.RELATED || matchesQuery(projection,
                        query.operation(), stringArgument(query.arguments(), "id"), text))
                .filter(projection -> outputItem == null || projection.slots().stream()
                        .filter(slot -> slot.role() == RecipeRole.OUTPUT)
                        .flatMap(slot -> slot.alternatives().stream())
                        .anyMatch(ingredient -> ingredient.type().equals("item")
                                && ingredient.comparisonIdentity().split("\\|componentsHash=", 2)[0].equals(outputItem)))
                .map(projection -> toRecord(projection, snapshot)).toList();
        var detail = snapshot.projections().stream().map(Projection::error).filter(Objects::nonNull)
                .findFirst().orElse(null);
        var result = result(records, snapshot.coverage(), snapshot.nextCursor());
        return detail == null ? result : new Evidence.Result(result.records(), result.coverage(), result.outcome(),
                result.nextCursor(), detail);
    }

    private static Evidence.Result result(List<Evidence.Record> records, Evidence.Coverage coverage, String next) {
        var outcome = coverage == Evidence.Coverage.UNAVAILABLE ? Evidence.Outcome.UNKNOWN
                : coverage == Evidence.Coverage.ERROR ? Evidence.Outcome.ERROR
                : coverage == Evidence.Coverage.COMPLETE ? Evidence.Outcome.COMPLETE : Evidence.Outcome.PARTIAL;
        return new Evidence.Result(records, coverage, outcome, next);
    }

    private Evidence.Result unavailableOrError(Throwable failure) {
        if (!runtimeAvailable) return unavailableResult();
        if (failure != null) PackSageLog.LOGGER.warn("PackSage JEI query failed", failure);
        return errorResult(failure == null ? "JEI returned no snapshot" : "JEI query failed: " + PackSageLog.describe(failure));
    }

    private static Evidence.Result unavailableResult() {
        return new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null, "JEI runtime is unavailable");
    }

    private static Evidence.Result errorResult(String detail) {
        return new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null, detail);
    }

    private static Evidence.NavigationResult navigate(Snapshot snapshot, String handle) {
        try {
            snapshot.navigator().accept(handle);
            return new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED, "JEI recipe view opened");
        } catch (RuntimeException failure) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.ERROR, failure.toString());
        }
    }

    private static Projection matchingProjection(Snapshot snapshot, Evidence.NavigationTarget target,
                                                 Evidence.Scope scope) {
        if (!ID.equals(target.providerId()) || !sameScope(snapshot.scope(), scope)
                || !sameScope(target.scope(), scope)) return null;
        return snapshot.projections().stream().filter(value -> {
            var category = ResourceLocation.tryParse(value.categoryId());
            var canonicalRecipeId = value.canonicalRecipeId() == null ? null
                    : ResourceLocation.tryParse(value.canonicalRecipeId());
            var persistentTarget = value.viewerHandle().endsWith("/category")
                    ? category != null && category.toString().equals(value.categoryId())
                    ? "category-v1|" + value.categoryId() : null
                    : category != null && canonicalRecipeId != null
                    && category.toString().equals(value.categoryId())
                    && canonicalRecipeId.toString().equals(value.canonicalRecipeId())
                    ? "recipe-v1|" + value.categoryId() + "|" + value.canonicalRecipeId() : null;
            return value.viewerHandle().equals(target.sourceHandle())
                    && Objects.equals(target.targetId(), persistentTarget == null ? value.viewerHandle() : persistentTarget);
        }).findFirst().orElse(null);
    }

    private static boolean matchesQuery(Projection projection, Evidence.Operation operation, String id, String text) {
        if (operation == Evidence.Operation.GET && id != null && !id.equals(projection.canonicalRecipeId())
                && !id.equals(projection.viewerHandle()) && !id.equals(projection.categoryId())) return false;
        return operation != Evidence.Operation.SEARCH && operation != Evidence.Operation.RELATED
                || matches(projection, text);
    }

    private static Evidence.RecipeAlternative toAlternative(Ingredient value) {
        return new Evidence.RecipeAlternative(value.type(), value.comparisonIdentity(), value.components(), value.quantity(), value.consumption());
    }

    private static Evidence.RecipeOutput toOutput(Ingredient value) {
        return new Evidence.RecipeOutput(value.type(), value.comparisonIdentity(), value.components(), value.quantity());
    }

    private static Evidence.JsonObject slotJson(Slot slot) {
        return new Evidence.JsonObject(Map.of(
                "groupId", new Evidence.JsonString(slot.groupId()),
                "role", new Evidence.JsonString(slot.role().name()),
                "alternatives", new Evidence.JsonArray(slot.alternatives().stream().map(JeiProvider::ingredientJson)
                        .map(value -> (Evidence.JsonValue) value).toList())));
    }

    private static Evidence.JsonObject ingredientJson(Ingredient ingredient) {
        var values = new LinkedHashMap<String, Evidence.JsonValue>();
        values.put("type", new Evidence.JsonString(ingredient.type()));
        values.put("comparisonIdentity", new Evidence.JsonString(ingredient.comparisonIdentity()));
        values.put("components", ingredient.components());
        values.put("quantity", ingredient.quantity() == null ? new Evidence.JsonNull()
                : new Evidence.JsonNumber(java.math.BigDecimal.valueOf(ingredient.quantity())));
        values.put("consumption", new Evidence.JsonString(ingredient.consumption().name()));
        return new Evidence.JsonObject(values);
    }

    static boolean matches(Projection projection, String text) {
        var normalizedText = text.toLowerCase(Locale.ROOT);
        return normalizedText.isEmpty() || projection.title().toLowerCase(Locale.ROOT).contains(normalizedText)
                || projection.categoryId().toLowerCase(Locale.ROOT).contains(normalizedText)
                || projection.viewerHandle().toLowerCase(Locale.ROOT).contains(normalizedText)
                || projection.canonicalRecipeId() != null && projection.canonicalRecipeId().toLowerCase(Locale.ROOT).contains(normalizedText)
                || projection.information().stream().anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(normalizedText))
                || projection.slots().stream().flatMap(slot -> slot.alternatives().stream())
                .anyMatch(value -> Evidence.serialize(ingredientJson(value)).toLowerCase(Locale.ROOT).contains(normalizedText))
                || projection.catalysts().stream()
                .anyMatch(value -> Evidence.serialize(ingredientJson(value)).toLowerCase(Locale.ROOT).contains(normalizedText));
    }

    private static String stringArgument(Evidence.JsonObject arguments, String key) {
        var value = arguments.values().get(key);
        return value instanceof Evidence.JsonString string ? string.value() : null;
    }

    private static int parseCursor(String cursor) {
        if (cursor == null) return 0;
        try {
            var parts = cursor.split("\\|", -1);
            var value = Integer.parseInt(parts.length == 2 ? parts[1] : cursor);
            if (parts.length == 2 && Long.parseLong(parts[0]) < 0) return -1;
            if (parts.length > 2) return -1;
            return value < 0 ? -1 : value;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static boolean sameScope(Evidence.Scope left, Evidence.Scope right) {
        return left.worldSession().equals(right.worldSession()) && left.resourcesLocale().equals(right.resourcesLocale())
                && left.playerTeam().equals(right.playerTeam()) && left.providerRuntime().equals(right.providerRuntime())
                && left.requestGeneration() == right.requestGeneration();
    }

    private static boolean sameEnvironment(Evidence.Scope left, Evidence.Scope right) {
        return left.worldSession().equals(right.worldSession()) && left.resourcesLocale().equals(right.resourcesLocale())
                && left.playerTeam().equals(right.playerTeam()) && left.providerRuntime().equals(right.providerRuntime());
    }
}
