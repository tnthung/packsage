package dev.packsage.knowledge.jei;

import dev.packsage.PackSageLog;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;

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
        Snapshot capture(Evidence.Scope scope, Evidence.Query query);
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

    private final SnapshotSource snapshots;
    private final Supplier<Snapshot> refreshSnapshot;
    private final Executor clientExecutor;
    private final BooleanSupplier owningThread;
    private final java.util.function.LongSupplier clientTick;
    private final Executor laterTick;
    private static final int MAX_NAVIGATION_SNAPSHOTS = 16;
    private final List<Snapshot> navigationSnapshots = new ArrayList<>();
    private long lastCaptureTick = Long.MIN_VALUE;
    private volatile Snapshot current;
    private volatile boolean runtimeAvailable;

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
        this((scope, query) -> snapshots.apply(scope), clientExecutor, owningThread);
    }

    public JeiProvider(Function<Evidence.Scope, Snapshot> snapshots, Executor clientExecutor) {
        this(snapshots, clientExecutor, () -> false);
    }

    public JeiProvider(Function<Evidence.Scope, Snapshot> snapshots) {
        this(snapshots, Runnable::run, () -> true);
    }

    public JeiProvider(Supplier<Snapshot> snapshots) {
        this((scope, query) -> snapshots.get(), snapshots, Runnable::run, () -> true);
    }

    public JeiProvider(Supplier<Snapshot> snapshots, Executor clientExecutor, BooleanSupplier owningThread) {
        this((scope, query) -> snapshots.get(), snapshots, clientExecutor, owningThread);
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
    public ProviderStatus status() {
        var snapshot = current;
        if (!runtimeAvailable) return new ProviderStatus(Availability.UNAVAILABLE, VERSION, "JEI runtime unavailable");
        if (snapshot == null) return new ProviderStatus(Availability.AVAILABLE, VERSION,
                "JEI runtime available; no snapshot captured");
        var availability = snapshot.coverage() == Evidence.Coverage.ERROR ? Availability.ERROR
                : snapshot.coverage() == Evidence.Coverage.OPAQUE ? Availability.OPAQUE
                : snapshot.coverage() != Evidence.Coverage.COMPLETE ? Availability.PARTIAL : Availability.AVAILABLE;
        return new ProviderStatus(availability, VERSION, "JEI runtime generation " + snapshot.generation()
                + " snapshot " + snapshot.snapshotVersion());
    }

    public void refresh() {
        if (refreshSnapshot == null) return;
        synchronized (navigationSnapshots) {
            navigationSnapshots.clear();
        }
        try {
            var snapshot = refreshSnapshot.get();
            current = snapshot;
            rememberSnapshot(snapshot, true);
            runtimeAvailable = true;
        } catch (RuntimeException failure) {
            PackSageLog.LOGGER.warn("PackSage JEI snapshot refresh failed", failure);
            current = null;
            synchronized (navigationSnapshots) {
                navigationSnapshots.clear();
            }
            runtimeAvailable = false;
        }
    }

    public void unavailable() {
        runtimeAvailable = false;
        current = null;
        synchronized (navigationSnapshots) {
            navigationSnapshots.clear();
        }
    }

    @Override
    public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
        Objects.requireNonNull(query);
        Objects.requireNonNull(scope);
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
                    captured.complete(snapshots.capture(scope, query));
                } catch (Throwable failure) {
                    captured.completeExceptionally(failure);
                }
            }
        });
        return captured
                .handle((snapshot, failure) -> {
                    if (failure != null || !runtimeAvailable || snapshot == null) {
                        if (failure != null || snapshot == null) clearSnapshots();
                        return unavailableOrError(failure);
                    }
                    if (!sameScope(snapshot.scope(), scope) || query.cursor() != null && query.cursorScope() != null
                            && !sameScope(query.cursorScope(), scope)) {
                        clearSnapshots();
                        return new Evidence.Result(List.of(), Evidence.Coverage.PARTIAL, Evidence.Outcome.UNKNOWN, null);
                    }
                    var result = snapshot.queryScoped() ? queryScopedResult(snapshot)
                            : querySnapshot(snapshot, query, cursor);
                    rememberSnapshot(snapshot, result.records().stream().anyMatch(record -> !record.targets().isEmpty()));
                    current = snapshot;
                    return result;
                });
    }

    @Override
    public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
        Objects.requireNonNull(target);
        Objects.requireNonNull(scope);
        var snapshot = current;
        if (!runtimeAvailable || snapshot == null) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "JEI runtime unavailable");
        }
        snapshot = snapshotForTarget(target, scope);
        if (snapshot == null) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.STALE,
                    "JEI target is outside the active runtime snapshot");
        }
        var projection = matchingProjection(snapshot, target, scope);
        if (projection.coverage() == Evidence.Coverage.ERROR) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.UNSUPPORTED,
                    "JEI projection failed and cannot be opened");
        }
        if (owningThread.getAsBoolean()) {
            return isRetained(snapshot, scope) ? navigate(snapshot, projection.viewerHandle())
                    : new Evidence.NavigationResult(Evidence.NavigationStatus.STALE,
                    "JEI runtime snapshot changed before navigation");
        }
        var selected = snapshot;
        var result = new CompletableFuture<Evidence.NavigationResult>();
        clientExecutor.execute(() -> {
            if (!runtimeAvailable || !isRetained(selected, scope)) {
                result.complete(new Evidence.NavigationResult(Evidence.NavigationStatus.STALE,
                        "JEI runtime snapshot changed before navigation"));
                return;
            }
            result.complete(navigate(selected, projection.viewerHandle()));
        });
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
        var targets = projection.coverage() == Evidence.Coverage.ERROR ? List.<Evidence.NavigationTarget>of()
                : List.of(new Evidence.NavigationTarget(snapshot.scope(), ID, projection.viewerHandle(), projection.viewerHandle()));
        return new Evidence.Record(source, Evidence.RecordKind.RECIPE, projection.title(), new Evidence.JsonObject(payloadValues),
                targets, payload);
    }

    private static Evidence.Result querySnapshot(Snapshot snapshot, Evidence.Query query, int start) {
        var text = stringArgument(query.arguments(), "text");
        text = text == null ? "" : text.toLowerCase(Locale.ROOT);
        var id = stringArgument(query.arguments(), "id");
        var selected = new ArrayList<Evidence.Record>();
        int index = start;
        for (; index < snapshot.projections().size() && selected.size() < query.limit(); index++) {
            var projection = snapshot.projections().get(index);
            if (!matchesQuery(projection, query.operation(), id, text)) continue;
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

    private static Evidence.Result queryScopedResult(Snapshot snapshot) {
        var records = snapshot.projections().stream().map(projection -> toRecord(projection, snapshot)).toList();
        return result(records, snapshot.coverage(), snapshot.nextCursor());
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

    private void rememberSnapshot(Snapshot snapshot, boolean hasNavigationTargets) {
        synchronized (navigationSnapshots) {
            if (!navigationSnapshots.isEmpty()) {
                var latest = navigationSnapshots.getLast();
                if (!sameScope(latest.scope(), snapshot.scope()) || latest.generation() != snapshot.generation()) {
                    navigationSnapshots.clear();
                }
            }
            if (!hasNavigationTargets && !navigationSnapshots.contains(snapshot)) return;
            navigationSnapshots.remove(snapshot);
            navigationSnapshots.add(snapshot);
            while (navigationSnapshots.size() > MAX_NAVIGATION_SNAPSHOTS) navigationSnapshots.removeFirst();
        }
    }

    private void clearSnapshots() {
        current = null;
        synchronized (navigationSnapshots) {
            navigationSnapshots.clear();
        }
    }

    private Snapshot snapshotForTarget(Evidence.NavigationTarget target, Evidence.Scope scope) {
        synchronized (navigationSnapshots) {
            for (var index = navigationSnapshots.size() - 1; index >= 0; index--) {
                var snapshot = navigationSnapshots.get(index);
                if (matchingProjection(snapshot, target, scope) != null && isRetained(snapshot, scope)) return snapshot;
            }
        }
        return null;
    }

    private boolean isRetained(Snapshot snapshot, Evidence.Scope scope) {
        if (!runtimeAvailable || !sameScope(snapshot.scope(), scope)) return false;
        var active = current;
        if (active == null || !sameScope(active.scope(), scope) || active.generation() != snapshot.generation()) return false;
        synchronized (navigationSnapshots) {
            return navigationSnapshots.contains(snapshot);
        }
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
        return snapshot.projections().stream().filter(value -> value.viewerHandle().equals(target.sourceHandle())
                && value.viewerHandle().equals(target.targetId())).findFirst().orElse(null);
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

    private static boolean matches(Projection projection, String text) {
        return text.isEmpty() || projection.title().toLowerCase(Locale.ROOT).contains(text)
                || projection.categoryId().toLowerCase(Locale.ROOT).contains(text)
                || projection.viewerHandle().toLowerCase(Locale.ROOT).contains(text)
                || projection.canonicalRecipeId() != null && projection.canonicalRecipeId().toLowerCase(Locale.ROOT).contains(text)
                || projection.information().stream().anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(text));
    }

    private static String stringArgument(Evidence.JsonObject arguments, String key) {
        var value = arguments.values().get(key);
        return value instanceof Evidence.JsonString string ? string.value() : null;
    }

    private static int parseCursor(String cursor) {
        if (cursor == null) return 0;
        try {
            var value = Integer.parseInt(cursor);
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
}
