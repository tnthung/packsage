package dev.packsage.world;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.model.PackSageTools;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Server-owned progress and statistics, copied in slices before filtering and record building on the worker. */
public final class ProgressTools implements KnowledgeProvider {
    public static final String ID = "progress";
    static final String VERSION = "1";

    private final Supplier<MinecraftServer> server;
    private final Function<MinecraftServer, ServerPlayer> host;
    private final WorldRegistry registry;
    private final Supplier<Evidence.Scope> scope;
    private final Executor worker;
    private final ServerWork work;
    private final ProgressNavigation navigation;
    private final Minecraft client;

    public ProgressTools(Minecraft client, WorldRegistry registry, Supplier<Evidence.Scope> scope, Executor worker, ServerWork work) {
        this(client, client::getSingleplayerServer, server -> {
            var profile = server.getSingleplayerProfile();
            var player = profile == null ? null : server.getPlayerList().getPlayer(profile.getId());
            if (player == null) throw new Failure(Code.UNAVAILABLE, "the singleplayer host is not in the world");
            return player;
        }, registry, scope, worker, work, new ProgressNavigation(ProgressNavigation.client(client)));
    }

    ProgressTools(Supplier<MinecraftServer> server, Function<MinecraftServer, ServerPlayer> host, WorldRegistry registry,
                  Supplier<Evidence.Scope> scope, Executor worker, ServerWork work, ProgressNavigation navigation) {
        this(null, server, host, registry, scope, worker, work, navigation);
    }

    private ProgressTools(Minecraft client, Supplier<MinecraftServer> server, Function<MinecraftServer, ServerPlayer> host, WorldRegistry registry,
                          Supplier<Evidence.Scope> scope, Executor worker, ServerWork work, ProgressNavigation navigation) {
        this.client = client;
        this.server = server;
        this.host = host;
        this.registry = registry;
        this.scope = scope;
        this.worker = worker;
        this.work = work;
        this.navigation = navigation;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean enabled() {
        return server.get() != null;
    }

    @Override
    public ProviderStatus status() {
        return server.get() == null
                ? new ProviderStatus(Availability.UNAVAILABLE, VERSION, "progress tools need an integrated singleplayer server")
                : new ProviderStatus(Availability.AVAILABLE, VERSION, null);
    }

    @Override
    public CompletionStage<Evidence.Result> handle(PackSageTools.ReadRequest request, Context context) {
        try {
            return switch (request.tool()) {
                case "list_progress" -> listProgress(request);
                case "inspect_progress" -> {
                    if (!(request.arguments().values().get("id") instanceof Evidence.JsonString id)) throw new IllegalArgumentException("id must be a string");
                    yield inspect(ProgressId.parse(id.value()), request.scope());
                }
                case "list_statistics" -> listStatistics(request);
                default -> CompletableFuture.failedFuture(new Failure(Code.NOT_HANDLED, request.tool()));
            };
        } catch (Failure failure) {
            return CompletableFuture.failedFuture(failure);
        } catch (IllegalArgumentException failure) {
            return CompletableFuture.failedFuture(new Failure(Code.INVALID_ARGUMENTS, failure.getMessage()));
        }
    }

    @Override
    public CompletionStage<Evidence.Result> readEvidence(PackSageTools.ReadRequest request, Context context) {
        try {
            var reference = context.reference(request);
            if (!ID.equals(reference.providerId())) return CompletableFuture.failedFuture(new Failure(Code.NOT_HANDLED, "different evidence owner"));
            return reread(reference.sourceHandle(), request.scope());
        } catch (Failure failure) {
            return CompletableFuture.failedFuture(failure);
        } catch (IllegalArgumentException failure) {
            return CompletableFuture.failedFuture(new Failure(Code.INVALID_REFERENCE, failure.getMessage()));
        }
    }

    @Override
    public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
        if (query.operation() != Evidence.Operation.GET || !(query.arguments().values().get("id") instanceof Evidence.JsonString handle)) {
            return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null,
                    "progress evidence is read through the progress tools"));
        }
        if (!scope.equals(this.scope.get())) {
            return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null, "observation scope changed"));
        }
        try {
            return reread(handle.value(), scope).exceptionally(ProgressTools::failed);
        } catch (Failure | IllegalArgumentException failure) {
            return CompletableFuture.completedFuture(failed(failure));
        }
    }

    @Override
    public CompletionStage<Evidence.NavigationTarget> refreshNavigation(Evidence.NavigationTarget target, Evidence.Scope scope) {
        if (!scope.equals(this.scope.get())) return CompletableFuture.completedFuture(null);
        return navigation.refresh(target, scope);
    }

    @Override
    public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
        if (client != null && !client.isSameThread()) return client.submit(() -> navigation.open(target)).join();
        return navigation.open(target);
    }

    private CompletionStage<Evidence.Result> listProgress(PackSageTools.ReadRequest request) {
        var arguments = request.arguments().values();
        var tree = arguments.get("tree");
        if (tree != null && !(tree instanceof Evidence.JsonNull) && !(tree instanceof Evidence.JsonString)) throw new IllegalArgumentException("tree must be a string");
        var text = arguments.get("text");
        if (text != null && !(text instanceof Evidence.JsonNull) && !(text instanceof Evidence.JsonString)) throw new IllegalArgumentException("text must be a string");
        var filter = new ProgressListing.Filter(tree instanceof Evidence.JsonString id ? ProgressId.parse(id.value()) : null,
                strings(arguments.get("status"), "status").stream().map(ProgressStatus::fromWire).collect(Collectors.toSet()),
                text instanceof Evidence.JsonString string ? string.value() : null);
        var offset = offset(request);
        var current = server();
        var readers = registry.progressReaders();
        return ServerReads.run(work, worker, () -> server.get() == current && current.isRunning(), new ServerWork.Slice<List<ProgressSnapshot>>() {
            private final List<ProgressSnapshot> snapshots = new ArrayList<>();
            private int index;
            private ServerWork.Slice<ProgressSnapshot> slice;

            @Override
            public boolean step() {
                if (index == readers.size()) return false;
                if (slice == null) slice = readers.get(index).snapshot(host.apply(current));
                if (!slice.step()) {
                    snapshots.add(slice.result());
                    slice = null;
                    index++;
                }
                return index < readers.size();
            }

            @Override
            public List<ProgressSnapshot> result() {
                return List.copyOf(snapshots);
            }

            @Override
            public boolean stopEarly() {
                if (slice == null || !slice.stopEarly()) return false;
                snapshots.add(slice.result());
                snapshots.add(new ProgressSnapshot(List.of(), List.of(), "server work budget reached; repeat list_progress"));
                return true;
            }
        }).thenApply(snapshots -> {
            var page = ProgressListing.page(snapshots, filter, offset);
            var records = new ArrayList<Evidence.Record>();
            for (var item : page.items()) {
                if (item.tree() != null) {
                    var value = item.tree();
                    var payload = new LinkedHashMap<String, Evidence.JsonValue>();
                    payload.put("tree", new Evidence.JsonString(value.id().text()));
                    payload.put("adapter", new Evidence.JsonString(value.id().adapter()));
                    payload.put("title", new Evidence.JsonString(value.title()));
                    payload.put("done", new Evidence.JsonNumber(BigDecimal.valueOf(value.done())));
                    payload.put("total", new Evidence.JsonNumber(BigDecimal.valueOf(value.total())));
                    records.add(evidence(request.scope(), "tree|" + value.id().text(), Evidence.RecordKind.OTHER, value.title(), payload, page.coverage(),
                            ProgressNavigation.targetId(new ProgressHandle(value.id(), value.id(), value.title(), ProgressStatus.AVAILABLE, value.tabVisible()))));
                } else {
                    var value = item.entry();
                    records.add(evidence(request.scope(), "progress|" + value.handle().id().text(), Evidence.RecordKind.QUEST,
                            value.handle().title() == null ? "Hidden progress entry" : value.handle().title(), entryPayload(value.handle(), value.description()), page.coverage(), ProgressNavigation.targetId(value.handle())));
                }
            }
            return result(records, page.coverage(), page.nextCursor() == null ? null : page.nextCursor() + "." + Integer.toHexString(List.of(request.tool(), signature(request.arguments())).hashCode()), page.note());
        }).exceptionally(failure -> {
            while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
            if (failure instanceof Failure typed) throw typed;
            throw new Failure(Code.EXTRACTION_FAILED, failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        });
    }

    private CompletionStage<Evidence.Result> inspect(ProgressId id, Evidence.Scope scope) {
        var current = server();
        return ServerReads.run(work, worker, () -> server.get() == current && current.isRunning(), ServerReads.once(() -> {
            var player = host.apply(current);
            for (var reader : registry.progressReaders()) {
                if (id.adapter() != null && !id.adapter().equals(reader.adapterId())) continue;
                var progress = reader.inspect(player, id.local());
                if (progress == null) continue;
                if (progress.handle().status() == ProgressStatus.HIDDEN) return Progress.hidden(progress.handle());
                var facts = new LinkedHashMap<>(progress.facts());
                facts.putAll(registry.progressFacts(player, progress.handle().id(), reader.adapterId()));
                return new Progress(progress.handle(), progress.description(), progress.criteria(), progress.requires(), progress.unlocks(), progress.rewards(), progress.doneAt(), facts);
            }
            throw new Failure(Code.INVALID_ARGUMENTS, "no progress entry has id " + id.text() + "; find ids with list_progress");
        })).thenApply(progress -> {
            var payload = entryPayload(progress.handle(), progress.description());
            var sections = new LinkedHashMap<String, Evidence.JsonValue>();
            var notes = new ArrayList<String>();
            var coverage = Evidence.Coverage.COMPLETE;
            for (var entry : progress.facts().entrySet()) {
                var section = new LinkedHashMap<String, Evidence.JsonValue>();
                section.put("coverage", new Evidence.JsonString(entry.getValue().coverage().name()));
                section.put("facts", entry.getValue().facts());
                if (entry.getValue().note() != null) {
                    section.put("note", new Evidence.JsonString(entry.getValue().note()));
                    notes.add(entry.getKey() + ": " + entry.getValue().note());
                }
                if (entry.getValue().coverage() != Evidence.Coverage.COMPLETE) coverage = Evidence.Coverage.PARTIAL;
                sections.put(entry.getKey(), new Evidence.JsonObject(section));
            }
            if (progress.handle().status() != ProgressStatus.HIDDEN) {
                payload.put("criteria", new Evidence.JsonArray(progress.criteria().stream().map(criterion -> {
                    var value = new LinkedHashMap<String, Evidence.JsonValue>();
                    value.put("id", new Evidence.JsonString(criterion.id()));
                    value.put("label", new Evidence.JsonString(criterion.label()));
                    value.put("done", new Evidence.JsonBoolean(criterion.done()));
                    value.put("doneAt", criterion.doneAt() == null ? new Evidence.JsonNull() : new Evidence.JsonString(criterion.doneAt().toString()));
                    return (Evidence.JsonValue) new Evidence.JsonObject(value);
                }).toList()));
                payload.put("requires", new Evidence.JsonArray(progress.requires().stream().map(handle -> {
                    var value = entryPayload(handle, null);
                    value.remove("description");
                    return (Evidence.JsonValue) new Evidence.JsonObject(value);
                }).toList()));
                payload.put("unlocks", new Evidence.JsonArray(progress.unlocks().stream().map(handle -> {
                    var value = entryPayload(handle, null);
                    value.remove("description");
                    return (Evidence.JsonValue) new Evidence.JsonObject(value);
                }).toList()));
                payload.put("rewards", new Evidence.JsonArray(progress.rewards().stream().map(reward -> (Evidence.JsonValue) new Evidence.JsonString(reward)).toList()));
                payload.put("doneAt", progress.doneAt() == null ? new Evidence.JsonNull() : new Evidence.JsonString(progress.doneAt().toString()));
                payload.put("sections", new Evidence.JsonObject(sections));
            }
            return result(List.of(evidence(scope, "progress|" + progress.handle().id().text(), Evidence.RecordKind.QUEST,
                    progress.handle().title() == null ? "Hidden progress entry" : progress.handle().title(), payload, coverage, ProgressNavigation.targetId(progress.handle()))), coverage, null, notes.isEmpty() ? null : String.join("; ", notes));
        }).exceptionally(failure -> {
            while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
            if (failure instanceof Failure typed) throw typed;
            throw new Failure(Code.EXTRACTION_FAILED, failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        });
    }

    private CompletionStage<Evidence.Result> listStatistics(PackSageTools.ReadRequest request) {
        var subjects = request.arguments().values().get("subjects");
        KindFilter filter;
        if (subjects == null || subjects instanceof Evidence.JsonNull) {
            filter = KindFilter.ANY;
        } else if (subjects instanceof Evidence.JsonObject object) {
            var text = object.values().get("text");
            if (text != null && !(text instanceof Evidence.JsonNull) && !(text instanceof Evidence.JsonString)) throw new IllegalArgumentException("subjects.text must be a string");
            if (object.values().get("tooltip") != null && !(object.values().get("tooltip") instanceof Evidence.JsonNull)) throw new IllegalArgumentException("subjects.tooltip applies only to item tools");
            filter = new KindFilter(strings(object.values().get("ids"), "subjects.ids"), strings(object.values().get("tags"), "subjects.tags"), text instanceof Evidence.JsonString string ? string.value() : null);
        } else {
            throw new IllegalArgumentException("subjects must be an object");
        }
        return statistics(request.scope(), strings(request.arguments().values().get("categories"), "categories"), filter, offset(request), List.of(request.tool(), signature(request.arguments())).hashCode(), null);
    }

    private CompletionStage<Evidence.Result> statistics(Evidence.Scope scope, List<String> categories, KindFilter subjects, int offset, int signature, String adapter) {
        var current = server();
        var readers = registry.statisticReaders().stream().filter(reader -> adapter == null || adapter.equals(reader.adapterId())).toList();
        var notes = new ArrayList<String>();
        return ServerReads.run(work, worker, () -> server.get() == current && current.isRunning(), new ServerWork.Slice<List<List<Statistic>>>() {
            private final List<List<Statistic>> rows = new ArrayList<>();
            private int index;
            private ServerWork.Slice<List<Statistic>> slice;

            @Override
            public boolean step() {
                if (index == readers.size()) return false;
                // A missing host makes the tool unavailable; only adapter failures become partial reads.
                var player = host.apply(current);
                try {
                    if (slice == null) slice = readers.get(index).statistics(player, categories, subjects);
                    if (slice.step()) return true;
                    rows.add(List.copyOf(slice.result()));
                    if (slice.note() != null) notes.add(readers.get(index).adapterId() + ": " + slice.note());
                } catch (VirtualMachineError failure) {
                    throw failure;
                } catch (Throwable failure) {
                    fail(failure);
                }
                slice = null;
                index++;
                return index < readers.size();
            }

            @Override
            public List<List<Statistic>> result() {
                return List.copyOf(rows);
            }

            @Override
            public boolean stopEarly() {
                if (slice == null) return false;
                try {
                    if (!slice.stopEarly()) return false;
                    rows.add(List.copyOf(slice.result()));
                } catch (VirtualMachineError failure) {
                    throw failure;
                } catch (Throwable failure) {
                    // The failing adapter is dropped and the remaining adapters keep stepping.
                    fail(failure);
                    slice = null;
                    index++;
                    return false;
                }
                notes.add("server work budget reached; repeat list_statistics");
                return true;
            }

            private void fail(Throwable failure) {
                notes.add(readers.get(index).adapterId() + ": " + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
            }
        }).thenApply(rows -> {
            var page = StatisticListing.page(rows, offset, notes);
            return result(page.items().stream().map(row -> {
                var payload = new LinkedHashMap<String, Evidence.JsonValue>();
                payload.put("adapter", new Evidence.JsonString(row.adapter()));
                payload.put("category", new Evidence.JsonString(row.category()));
                payload.put("subject", new Evidence.JsonString(row.subject()));
                payload.put("label", new Evidence.JsonString(row.label()));
                payload.put("value", new Evidence.JsonNumber(BigDecimal.valueOf(row.value())));
                payload.put("unit", new Evidence.JsonString(row.unit().name().toLowerCase(Locale.ROOT)));
                payload.put("raw", new Evidence.JsonNumber(BigDecimal.valueOf(row.raw())));
                return evidence(scope, "stat|" + row.adapter() + "|" + row.category() + "|" + row.subject(), Evidence.RecordKind.OTHER,
                        row.label() + " = " + BigDecimal.valueOf(row.value()).stripTrailingZeros().toPlainString(), payload, page.coverage(), null);
            }).toList(), page.coverage(), page.nextCursor() == null ? null : page.nextCursor() + "." + Integer.toHexString(signature), page.note());
        }).exceptionally(failure -> {
            while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
            if (failure instanceof Failure typed) throw typed;
            throw new Failure(Code.EXTRACTION_FAILED, failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        });
    }

    private CompletionStage<Evidence.Result> reread(String handle, Evidence.Scope scope) {
        if (handle.startsWith("tree|")) throw new Failure(Code.INVALID_REFERENCE, "repeat list_progress");
        if (handle.startsWith("progress|")) return inspect(ProgressId.parse(handle.substring(9)), scope);
        if (handle.startsWith("stat|")) {
            var parts = handle.split("\\|", -1);
            if (parts.length != 4 || parts[1].isEmpty() || parts[2].isEmpty() || parts[3].isEmpty()) throw new Failure(Code.INVALID_REFERENCE, "repeat list_statistics");
            return statistics(scope, List.of(parts[2]), new KindFilter(List.of(parts[3]), List.of(), ""), 0, 0, parts[1]).thenApply(result -> {
                var records = result.records().stream().filter(record -> record.source().sourceHandle().equals(handle)).toList();
                if (records.isEmpty()) {
                    if (result.coverage() != Evidence.Coverage.COMPLETE) throw new Failure(Code.EXTRACTION_FAILED, result.detail());
                    throw new Failure(Code.INVALID_REFERENCE, "statistic is no longer available; repeat list_statistics");
                }
                return result(records, result.coverage(), null, result.detail());
            });
        }
        throw new Failure(Code.INVALID_REFERENCE, "unknown progress evidence handle");
    }

    private MinecraftServer server() {
        var current = server.get();
        if (current == null) throw new Failure(Code.UNAVAILABLE, "progress tools need an integrated singleplayer server");
        return current;
    }

    private static List<String> strings(Evidence.JsonValue value, String name) {
        if (value == null || value instanceof Evidence.JsonNull) return List.of();
        if (!(value instanceof Evidence.JsonArray array)) throw new IllegalArgumentException(name + " must be an array of strings");
        var strings = new ArrayList<String>();
        for (var item : array.values()) {
            if (!(item instanceof Evidence.JsonString string)) throw new IllegalArgumentException(name + " must be an array of strings");
            strings.add(string.value());
        }
        return strings;
    }

    /** Drops null object fields recursively, preserving array order, like the world tool signatures. */
    private static Evidence.JsonObject signature(Evidence.JsonObject arguments) {
        var values = new LinkedHashMap<String, Evidence.JsonValue>();
        arguments.values().forEach((key, value) -> {
            if (!key.equals("cursor") && !key.equals("limit") && !(value instanceof Evidence.JsonNull)) {
                values.put(key, value instanceof Evidence.JsonObject object ? signature(object) : value);
            }
        });
        return new Evidence.JsonObject(values);
    }

    private static int offset(PackSageTools.ReadRequest request) {
        var cursor = request.arguments().values().get("cursor");
        if (cursor == null || cursor instanceof Evidence.JsonNull) return 0;
        if (cursor instanceof Evidence.JsonString string) {
            var parts = string.value().split("\\.", -1);
            if (parts.length == 2 && parts[1].equals(Integer.toHexString(List.of(request.tool(), signature(request.arguments())).hashCode()))) {
                try {
                    var offset = Integer.parseInt(parts[0]);
                    if (offset >= 0 && Integer.toString(offset).equals(parts[0])) return offset;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        throw new Failure(Code.INVALID_CURSOR, "cursor does not match this query; restart without a cursor");
    }

    private static Map<String, Evidence.JsonValue> entryPayload(ProgressHandle handle, String description) {
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("id", new Evidence.JsonString(handle.id().text()));
        payload.put("adapter", new Evidence.JsonString(handle.id().adapter()));
        payload.put("tree", new Evidence.JsonString(handle.tree().text()));
        payload.put("title", handle.title() == null ? new Evidence.JsonNull() : new Evidence.JsonString(handle.title()));
        payload.put("status", new Evidence.JsonString(handle.status().wire()));
        if (handle.status() != ProgressStatus.HIDDEN) payload.put("description", description == null ? new Evidence.JsonNull() : new Evidence.JsonString(description));
        return payload;
    }

    private static Evidence.Record evidence(Evidence.Scope scope, String handle, Evidence.RecordKind kind, String title,
                                            Map<String, Evidence.JsonValue> payload, Evidence.Coverage coverage, String targetId) {
        return new Evidence.Record(new Evidence.Source(scope, ID, VERSION, handle, scope.requestGeneration(), Instant.now(),
                Evidence.Activity.ACTIVE, coverage, Evidence.Authority.SERVER_READ, List.of()), kind, title, new Evidence.JsonObject(payload),
                targetId == null ? List.of() : List.of(new Evidence.NavigationTarget(scope, ProgressNavigation.PROVIDER, handle, targetId)));
    }

    private static Evidence.Result result(List<Evidence.Record> records, Evidence.Coverage coverage, String cursor, String note) {
        var complete = cursor == null && coverage == Evidence.Coverage.COMPLETE;
        return new Evidence.Result(records, complete ? Evidence.Coverage.COMPLETE : Evidence.Coverage.PARTIAL,
                complete ? Evidence.Outcome.COMPLETE : Evidence.Outcome.PARTIAL, cursor, note);
    }

    private static Evidence.Result failed(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        return new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null, failure.getMessage());
    }
}
