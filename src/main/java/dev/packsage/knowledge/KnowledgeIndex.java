package dev.packsage.knowledge;

import dev.packsage.knowledge.nativegame.NativeSnapshot;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class KnowledgeIndex {
    private List<Evidence.Record> records = List.of();
    private Evidence.Scope scope;
    private Evidence.Coverage snapshotCoverage = Evidence.Coverage.UNAVAILABLE;
    private boolean snapshotAvailable;
    private boolean mixedScopes;

    public synchronized void replace(NativeSnapshot snapshot) {
        Objects.requireNonNull(snapshot);
        scope = snapshot.scope();
        mixedScopes = false;
        if (snapshot.records().isEmpty()) {
            records = List.of();
            snapshotCoverage = Evidence.Coverage.UNAVAILABLE;
            snapshotAvailable = false;
            return;
        }
        var winning = new LinkedHashMap<String, Evidence.Record>();
        snapshot.records().stream().sorted(Comparator.<Evidence.Record>comparingInt(record -> switch (record.source().activity()) {
                    case ACTIVE -> 0;
                    case SHIPPED -> 1;
                    case UNKNOWN -> 2;
                    case INACTIVE -> 3;
                }))
                .forEach(record -> {
                    if (!record.source().scope().equals(snapshot.scope())) {
                        mixedScopes = true;
                    } else if (record.source().activity() != Evidence.Activity.INACTIVE) {
                        winning.putIfAbsent(record.kind() + "\u0000" + record.source().sourceHandle(), record);
                    }
                });
        records = List.copyOf(winning.values());
        snapshotCoverage = snapshot.records().stream().anyMatch(record -> record.source().coverage() == Evidence.Coverage.ERROR)
                ? Evidence.Coverage.ERROR : snapshot.records().stream().anyMatch(record -> record.source().coverage() == Evidence.Coverage.UNAVAILABLE)
                ? Evidence.Coverage.UNAVAILABLE : snapshot.records().stream().anyMatch(record -> record.source().coverage() != Evidence.Coverage.COMPLETE)
                ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE;
        snapshotAvailable = !snapshot.records().isEmpty() && snapshotCoverage != Evidence.Coverage.UNAVAILABLE
                && snapshotCoverage != Evidence.Coverage.ERROR;
    }

    public synchronized CompletionStage<Evidence.Result> search(Evidence.Query query, Evidence.Scope requestedScope) {
        return CompletableFuture.completedFuture(searchNow(query, requestedScope));
    }

    public synchronized Evidence.Result replaceAndSearch(NativeSnapshot snapshot, Evidence.Query query, Evidence.Scope requestedScope) {
        replace(snapshot);
        return searchNow(query, requestedScope);
    }

    public synchronized List<Evidence.Record> recipeOutputs(String identity, Evidence.Scope requestedScope) {
        return recipeOutputs(identity, new Evidence.JsonObject(Map.of()), requestedScope);
    }

    public synchronized List<Evidence.Record> recipeOutputs(String identity, Evidence.JsonObject components,
                                                             Evidence.Scope requestedScope) {
        if (!available(requestedScope)) return List.of();
        return records.stream().filter(record -> record.recipePayload() != null)
                .filter(record -> record.recipePayload().outputs().stream().anyMatch(output -> identity.equals(output.comparisonIdentity())
                        && (components.values().isEmpty() || output.components().equals(components)))).toList();
    }

    synchronized boolean available(Evidence.Scope requestedScope) {
        return scope != null && scope.equals(requestedScope) && snapshotAvailable;
    }

    private Evidence.Result searchNow(Evidence.Query query, Evidence.Scope requestedScope) {
        Objects.requireNonNull(query);
        Objects.requireNonNull(requestedScope);
        if (scope == null || !requestedScope.equals(scope)) return new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE,
                Evidence.Outcome.UNKNOWN, null);
        if (query.cursor() != null && query.cursorScope() == null) {
            return new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null);
        }
        if (query.cursor() != null && !query.cursorScope().equals(requestedScope)) {
            return new Evidence.Result(List.of(), Evidence.Coverage.PARTIAL, Evidence.Outcome.UNKNOWN, null);
        }
        if (!snapshotAvailable) return new Evidence.Result(List.of(), snapshotCoverage, outcome(snapshotCoverage), null);
        var matches = records.stream().filter(record -> matches(record, query)).toList();
        int offset = 0;
        if (query.cursor() != null) {
            var parsed = parseCursor(query.cursor());
            if (parsed == null || parsed < 0 || parsed >= matches.size()) return new Evidence.Result(List.of(), Evidence.Coverage.ERROR,
                    Evidence.Outcome.ERROR, null);
            offset = parsed;
        }
        int end = (int) Math.min(matches.size(), (long) offset + query.limit());
        var page = matches.subList(offset, end);
        String next = end < matches.size() ? Integer.toString(end) : null;
        var coverage = page.stream().anyMatch(record -> record.source().coverage() == Evidence.Coverage.ERROR)
                ? Evidence.Coverage.ERROR : page.stream().anyMatch(record -> record.source().coverage() != Evidence.Coverage.COMPLETE)
                || snapshotCoverage != Evidence.Coverage.COMPLETE || mixedScopes ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE;
        return new Evidence.Result(page, coverage, outcome(coverage), next);
    }

    private boolean matches(Evidence.Record record, Evidence.Query query) {
        var args = query.arguments().values();
        String id = string(args.get("id"));
        if (query.operation() == Evidence.Operation.GET) {
            return id != null && (id.equals(record.source().sourceHandle()) || id.equals(string(record.payload().values().get("id")))
                    || id.equals(string(record.payload().values().get("identity")))
                    || id.equals(string(record.payload().values().get("registryId"))));
        }
        if (id != null && !(id.equals(record.source().sourceHandle()) || id.equals(string(record.payload().values().get("id")))
                || id.equals(string(record.payload().values().get("identity"))))) return false;
        if (args.get("kinds") instanceof Evidence.JsonArray kinds && !kinds.values().isEmpty()
                && kinds.values().stream().noneMatch(kind -> record.kind().name().equals(string(kind)))) return false;
        String text = string(args.get("text"));
        if (text != null && !containsText(record, text.toLowerCase(Locale.ROOT))) return false;
        String tag = string(args.get("tag"));
        if (tag != null && !containsJson(record.payload().values().get("tags"), tag) && !recipeContains(record, tag, true)) return false;
        String component = string(args.get("component"));
        return component == null || containsJson(record.payload().values().get("components"), component)
                || recipeContains(record, component, false);
    }

    private static boolean containsText(Evidence.Record record, String expected) {
        return record.source().sourceHandle().toLowerCase(Locale.ROOT).contains(expected)
                || record.title().toLowerCase(Locale.ROOT).contains(expected)
                || textJson(record.payload(), expected);
    }

    private static boolean textJson(Evidence.JsonValue value, String expected) {
        if (value instanceof Evidence.JsonString string) return string.value().toLowerCase(Locale.ROOT).contains(expected);
        if (value instanceof Evidence.JsonArray array) return array.values().stream().anyMatch(nested -> textJson(nested, expected));
        if (value instanceof Evidence.JsonObject object) return object.values().entrySet().stream()
                .anyMatch(entry -> entry.getKey().toLowerCase(Locale.ROOT).contains(expected) || textJson(entry.getValue(), expected));
        return false;
    }

    private static boolean recipeContains(Evidence.Record record, String expected, boolean tag) {
        if (record.recipePayload() == null) return false;
        return record.recipePayload().inputs().stream().flatMap(slot -> slot.alternatives().stream())
                .anyMatch(alternative -> (tag == alternative.type().equals("tag")) && expected.equals(alternative.comparisonIdentity()))
                || record.recipePayload().outputs().stream().anyMatch(output -> expected.equals(output.comparisonIdentity())
                && tag == output.type().equals("tag"));
    }

    private static boolean containsJson(Evidence.JsonValue value, String expected) {
        if (value instanceof Evidence.JsonString string) return expected.equals(string.value());
        if (value instanceof Evidence.JsonArray array) return array.values().stream().anyMatch(nested -> containsJson(nested, expected));
        if (value instanceof Evidence.JsonObject object) return object.values().entrySet().stream()
                .anyMatch(entry -> expected.equals(entry.getKey()) || containsJson(entry.getValue(), expected));
        return false;
    }

    private static String string(Evidence.JsonValue value) {
        return value instanceof Evidence.JsonString string ? string.value() : null;
    }

    private static Integer parseCursor(String cursor) {
        try {
            return Integer.valueOf(cursor);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Evidence.Outcome outcome(Evidence.Coverage coverage) {
        return switch (coverage) {
            case COMPLETE -> Evidence.Outcome.COMPLETE;
            case ERROR -> Evidence.Outcome.ERROR;
            case UNAVAILABLE -> Evidence.Outcome.UNKNOWN;
            case PARTIAL, OPAQUE -> Evidence.Outcome.PARTIAL;
        };
    }
}
