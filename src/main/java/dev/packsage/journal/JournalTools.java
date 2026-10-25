package dev.packsage.journal;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.packsage.client.highlight.WorldHighlighter;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.model.PackSageTools;
import dev.packsage.world.Anchor;
import dev.packsage.world.BlockPlace;
import dev.packsage.world.WorldTools;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

/** Recorded world history and player reports. Reads run on the worker; note edits serialize on the writer. */
public final class JournalTools implements KnowledgeProvider {
    public static final String ID = "journal";
    public static final String VERSION = "1";

    private record Cursor(String tool, Evidence.JsonObject signature, Evidence.Scope scope, JournalFilter filter, String keyset, long nowWall) {
    }

    private final Journal journal;
    private final Supplier<Evidence.Scope> scope;
    private final Executor worker;
    private final Supplier<MinecraftServer> server;
    private final Function<Anchor, CompletionStage<BlockPlace>> anchors;
    private final JournalNavigation.Client client;
    private final Map<String, Cursor> cursors = new LinkedHashMap<>(16, 0.75f, true);

    public JournalTools(Journal journal, Supplier<Evidence.Scope> scope, Executor worker, Supplier<MinecraftServer> server,
                        Function<Anchor, CompletionStage<BlockPlace>> anchors) {
        this(journal, scope, worker, server, anchors, new JournalNavigation.Client() {
            @Override
            public String dimension() {
                var level = Minecraft.getInstance().level;
                return level == null ? null : level.dimension().location().toString();
            }

            @Override
            public void highlightBlock(BlockPlace place, long untilMillis) {
                WorldHighlighter.highlightPlace(place, untilMillis);
            }
        });
    }

    JournalTools(Journal journal, Supplier<Evidence.Scope> scope, Executor worker, Supplier<MinecraftServer> server,
                 Function<Anchor, CompletionStage<BlockPlace>> anchors, JournalNavigation.Client client) {
        this.journal = journal;
        this.scope = scope;
        this.worker = worker;
        this.server = server;
        this.anchors = anchors;
        this.client = client;
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
        return !enabled() ? new ProviderStatus(Availability.UNAVAILABLE, VERSION, "journal tools need an integrated singleplayer server")
                : !journal.ready() ? new ProviderStatus(Availability.UNAVAILABLE, VERSION, journal.unavailableReason())
                : new ProviderStatus(Availability.AVAILABLE, VERSION, null);
    }

    private void available(Evidence.Scope requested) {
        if (!requested.equals(scope.get())) throw new Failure(Code.STALE_SCOPE, "journal scope changed; repeat the lookup");
        var status = status();
        if (status.availability() != Availability.AVAILABLE) throw new Failure(Code.UNAVAILABLE, status.detail());
    }

    @Override
    public CompletionStage<Evidence.Result> handle(PackSageTools.ReadRequest request, Context context) {
        if (!List.of("search_journal", "remember", "update_note", "forget_note").contains(request.tool())) {
            return CompletableFuture.failedFuture(new Failure(Code.NOT_HANDLED, request.tool()));
        }
        try {
            available(request.scope());
            return switch (request.tool()) {
                case "search_journal" -> search(request);
                case "remember", "update_note" -> save(request, context);
                default -> forget(request);
            };
        } catch (Failure failure) {
            return CompletableFuture.failedFuture(failure);
        } catch (IllegalArgumentException | java.time.DateTimeException failure) {
            return CompletableFuture.failedFuture(new Failure(Code.INVALID_ARGUMENTS, failure.getMessage()));
        }
    }

    private CompletionStage<Evidence.Result> forget(PackSageTools.ReadRequest request) {
        if (!(request.arguments().values().get("id") instanceof Evidence.JsonString text) || !NoteBook.isId(text.value())) {
            throw new Failure(Code.INVALID_ARGUMENTS, "id must be a note id from search_journal notesOnly");
        }
        var notes = journal.notes();
        return journal.write(store -> {
            available(request.scope());
            if (notes.get(text.value()) == null || !store.deleteNote(text.value())) {
                throw new Failure(Code.INVALID_ARGUMENTS, "no note " + text.value() + "; find it with search_journal notesOnly");
            }
            notes.remove(text.value());
            return null;
        }).thenApplyAsync(ignored -> {
            available(request.scope());
            return new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null,
                    "note " + text.value() + " forgotten");
        }, worker);
    }

    private CompletionStage<Evidence.Result> search(PackSageTools.ReadRequest request) {
        if (!(request.arguments().values().get("filter") instanceof Evidence.JsonObject filter)) {
            throw new Failure(Code.INVALID_ARGUMENTS, "filter is required and must be an object");
        }
        var signature = (Evidence.JsonObject) withoutNulls(new Evidence.JsonObject(Map.of("filter", filter)));
        Cursor cursor = null;
        var token = request.arguments().values().get("cursor");
        if (token != null && !(token instanceof Evidence.JsonNull)) {
            if (!(token instanceof Evidence.JsonString text)) throw new Failure(Code.INVALID_CURSOR, "cursor must be a returned token");
            synchronized (cursors) {
                cursor = cursors.get(text.value());
            }
            if (cursor == null || !cursor.tool().equals(request.tool()) || !cursor.signature().equals(signature)) {
                throw new Failure(Code.INVALID_CURSOR, "cursor does not match this query; restart without a cursor");
            }
            if (!cursor.scope().equals(request.scope())) throw new Failure(Code.STALE_SCOPE, "journal cursor scope changed");
        }
        var now = cursor == null ? System.currentTimeMillis() : cursor.nowWall();
        var keyset = cursor == null ? null : cursor.keyset();
        return (cursor == null ? filter(filter, now) : CompletableFuture.completedFuture(cursor.filter())).thenComposeAsync(query -> {
            available(request.scope());
            return journal.search(query, keyset, now).thenCompose(page -> journal.read(store -> store.meta()).thenApplyAsync(meta -> {
                available(request.scope());
                String next = null;
                if (page.nextCursor() != null) {
                    next = UUID.randomUUID().toString();
                    synchronized (cursors) {
                        cursors.put(next, new Cursor(request.tool(), signature, request.scope(), query, page.nextCursor(), now));
                        while (cursors.size() > 256) cursors.remove(cursors.keySet().iterator().next());
                    }
                }
                return new Evidence.Result(page.items().stream().map(entry -> record(entry, request.scope(), now, query.area() != null, page.coverage())).toList(),
                        page.coverage(), page.coverage() == Evidence.Coverage.COMPLETE ? Evidence.Outcome.COMPLETE : Evidence.Outcome.PARTIAL, next,
                        "recording since " + (meta == null ? "unknown (not recording yet)" : Instant.ofEpochMilli(meta.createdWall()))
                                + (page.note() == null ? "" : "; " + page.note()));
            }, worker));
        }, worker);
    }

    private CompletionStage<JournalFilter> filter(Evidence.JsonObject filter, long now) {
        var json = JsonParser.parseString(Evidence.serialize(filter)).getAsJsonObject();
        Long after = null;
        Long before = null;
        if (json.has("time") && !json.get("time").isJsonNull()) {
            var time = json.getAsJsonObject("time");
            if (time.has("after") && !time.get("after").isJsonNull()) after = Instant.parse(time.get("after").getAsString()).toEpochMilli();
            if (time.has("before") && !time.get("before").isJsonNull()) before = Instant.parse(time.get("before").getAsString()).toEpochMilli();
            if (time.has("lastMinutes") && !time.get("lastMinutes").isJsonNull()) {
                if (after != null) throw new Failure(Code.INVALID_ARGUMENTS, "lastMinutes cannot be combined with after");
                after = now - integer(time.get("lastMinutes"), "lastMinutes", 1, 5_256_000) * 60_000L;
            }
            if (after != null && before != null && after > before) throw new Failure(Code.INVALID_ARGUMENTS,
                    time.has("lastMinutes") && !time.get("lastMinutes").isJsonNull()
                            ? "lastMinutes selects a start later than before" : "after must not be later than before");
        }
        var types = strings(json, "types", EventType.values().length + 1, false);
        for (var type : types) {
            if (!type.equals("note")) EventType.byId(type);
        }
        var subjects = strings(json, "subjects", 32, true);
        var dimension = json.has("dimension") && !json.get("dimension").isJsonNull() ? json.get("dimension").getAsString() : null;
        if (dimension != null && !dimension.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new Failure(Code.INVALID_ARGUMENTS, "dimension must be a resource id");
        var text = json.has("text") && !json.get("text").isJsonNull() ? json.get("text").getAsString() : null;
        if (text != null && text.length() > 256) throw new Failure(Code.INVALID_ARGUMENTS, "text must be at most 256 characters");
        var notesOnly = json.has("notesOnly") && !json.get("notesOnly").isJsonNull() && json.get("notesOnly").getAsBoolean();
        var result = new JournalFilter(after, before, null, dimension, Set.copyOf(types), subjects, text, notesOnly);
        if (!json.has("area") || json.get("area").isJsonNull()) return CompletableFuture.completedFuture(result);
        var area = json.getAsJsonObject("area");
        var radius = integer(area.get("radius"), "radius", 0, 256);
        var center = filter.values().get("area") instanceof Evidence.JsonObject object ? object.values().get("center") : null;
        return anchors.apply(WorldTools.parseAnchor(center instanceof Evidence.JsonNull ? null : center)).thenApply(place -> {
            if (place == null) throw new Failure(Code.UNAVAILABLE, "area center is unavailable");
            return new JournalFilter(result.afterWall(), result.beforeWall(), new JournalFilter.Area(place.dimension(), place.x(), place.y(), place.z(), radius),
                    dimension, result.types(), subjects, text, notesOnly);
        });
    }

    private CompletionStage<Evidence.Result> save(PackSageTools.ReadRequest request, Context context) {
        var arguments = JsonParser.parseString(Evidence.serialize(request.arguments())).getAsJsonObject();
        if (!arguments.has("text") || !arguments.get("text").isJsonPrimitive() || !arguments.getAsJsonPrimitive("text").isString()) {
            throw new Failure(Code.INVALID_ARGUMENTS, "text must be a string");
        }
        var updating = request.tool().equals("update_note");
        var id = updating && arguments.has("id") ? arguments.get("id").getAsString() : null;
        if (updating && !NoteBook.isId(id)) throw new Failure(Code.INVALID_ARGUMENTS, "id must be a note id from search_journal notesOnly");
        var at = request.arguments().values().get("at");
        var hasPlace = at != null && !(at instanceof Evidence.JsonNull);
        var clear = arguments.has("clearPlace") && !arguments.get("clearPlace").isJsonNull() && arguments.get("clearPlace").getAsBoolean();
        if (hasPlace && clear) throw new Failure(Code.INVALID_ARGUMENTS, "at and clearPlace cannot be combined");
        var input = NoteInput.validated(arguments.get("text").getAsString(), clear ? new NoteInput.Clear() : new NoteInput.Keep(),
                arguments.has("tags") && !arguments.get("tags").isJsonNull() ? strings(arguments, "tags", 8, false) : null);
        var notes = journal.notes();
        return (hasPlace ? anchors.apply(WorldTools.parseAnchor(at)) : CompletableFuture.<BlockPlace>completedFuture(null)).thenComposeAsync(place -> {
            if (hasPlace && place == null) throw new Failure(Code.UNAVAILABLE, "note place is unavailable");
            return journal.write(store -> {
                available(request.scope());
                var now = System.currentTimeMillis();
                var origin = context.origin();
                Note note;
                if (updating) {
                    var existing = notes.get(id);
                    if (existing == null) throw new Failure(Code.INVALID_ARGUMENTS, "no note " + id + "; find it with search_journal notesOnly");
                    note = NoteBook.apply(existing, new NoteInput(input.text(), hasPlace ? new NoteInput.Set(place) : input.place(), input.tags()),
                            now, origin == null ? null : origin.conversationId(), origin == null ? null : origin.requestId());
                } else {
                    note = new Note(notes.newId(), input.text(), origin == null ? null : origin.playerWords(), input.tags() == null ? List.of() : input.tags(),
                            place, now, journal.gameTime(), now, origin == null ? null : origin.conversationId(), origin == null ? null : origin.requestId());
                }
                store.putNote(note);
                var duplicates = updating ? List.<Note>of() : notes.similar(note.text(), 3);
                notes.put(note);
                return Map.entry(note, duplicates);
            }).thenApplyAsync(saved -> {
                available(request.scope());
                var record = record(new JournalEntry.NoteEntry(saved.getKey()), request.scope(), saved.getKey().updatedWall(), false, Evidence.Coverage.COMPLETE);
                if (!updating) {
                    var payload = new LinkedHashMap<>(record.payload().values());
                    payload.put("possibleDuplicates", new Evidence.JsonArray(saved.getValue().stream()
                            .map(other -> (Evidence.JsonValue) new Evidence.JsonObject(Map.of("id", new Evidence.JsonString(other.id()), "text", new Evidence.JsonString(other.text()),
                                    "place", other.place() == null ? new Evidence.JsonNull() : new Evidence.JsonString(other.place().text())))).toList()));
                    record = new Evidence.Record(record.source(), record.kind(), record.title(), new Evidence.JsonObject(payload), record.targets());
                }
                return new Evidence.Result(List.of(record), Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null);
            }, worker);
        }, worker);
    }

    @Override
    public CompletionStage<Evidence.Result> readEvidence(PackSageTools.ReadRequest request, Context context) {
        try {
            var reference = context.reference(request);
            if (!reference.providerId().equals(ID)) return CompletableFuture.failedFuture(new Failure(Code.NOT_HANDLED, "different evidence owner"));
            return reread(reference.sourceHandle(), request.scope());
        } catch (Failure failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private CompletionStage<Evidence.Result> reread(String handle, Evidence.Scope requested) {
        available(requested);
        return journal.read(store -> {
            if (handle.startsWith("note:") && NoteBook.isId(handle.substring(5))) {
                return store.notes().stream().filter(note -> note.id().equals(handle.substring(5))).findFirst()
                        .<JournalEntry>map(JournalEntry.NoteEntry::new).orElse(null);
            }
            if (handle.matches("event:[1-9][0-9]*")) {
                try (var statement = store.reader().prepareStatement("SELECT * FROM events WHERE id = ?")) {
                    statement.setLong(1, Long.parseLong(handle.substring(6)));
                    try (var rows = statement.executeQuery()) {
                        return rows.next() ? JournalSearch.event(rows, null) : null;
                    }
                }
            }
            throw new Failure(Code.INVALID_REFERENCE, "invalid journal evidence handle");
        }).thenApplyAsync(entry -> {
            available(requested);
            return new Evidence.Result(entry == null ? List.of() : List.of(record(entry, requested, System.currentTimeMillis(), false, Evidence.Coverage.COMPLETE)),
                    Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null, entry == null ? "journal entry no longer exists" : null);
        }, worker);
    }

    @Override
    public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope requested) {
        if (query.operation() != Evidence.Operation.GET || !(query.arguments().values().get("id") instanceof Evidence.JsonString handle)) {
            return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null, "use search_journal"));
        }
        try {
            return reread(handle.value(), requested);
        } catch (Failure failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override
    public CompletionStage<Evidence.NavigationTarget> refreshNavigation(Evidence.NavigationTarget target, Evidence.Scope requested) {
        if (!requested.equals(scope.get())) return CompletableFuture.completedFuture(null);
        return new JournalNavigation(journal.notes(), client).refresh(target, requested);
    }

    @Override
    public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope requested) {
        if (!requested.equals(scope.get()) || !target.scope().equals(requested)) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.STALE, "journal scope changed; repeat the lookup");
        }
        if (!ID.equals(target.providerId())) return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "different navigation owner");
        if (!enabled()) return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, status().detail());
        return new JournalNavigation(journal.notes(), client).open(target);
    }

    private static Evidence.Record record(JournalEntry entry, Evidence.Scope scope, long now, boolean area, Evidence.Coverage coverage) {
        var payload = new JsonObject();
        String handle;
        String title;
        String target;
        Evidence.Authority authority;
        if (entry instanceof JournalEntry.NoteEntry report) {
            var note = report.note();
            handle = "note:" + note.id();
            title = note.text();
            target = note.place() == null ? null : JournalNavigation.noteId(note.id());
            authority = report.authority();
            payload.addProperty("type", "note");
            payload.addProperty("id", note.id());
            payload.addProperty("text", note.text());
            payload.addProperty("playerWords", note.playerWords());
            payload.addProperty("trust", report.trust());
            var tags = new JsonArray();
            note.tags().forEach(tags::add);
            payload.add("tags", tags);
            if (note.place() != null) payload.addProperty("place", note.place().text());
            payload.addProperty("created", Instant.ofEpochMilli(note.createdWall()).toString());
            payload.addProperty("updated", Instant.ofEpochMilli(note.updatedWall()).toString());
            var origin = new JsonObject();
            origin.addProperty("conversation", note.originConversation());
            origin.addProperty("request", note.originRequest());
            payload.add("origin", origin);
        } else {
            var event = (JournalEntry.EventEntry) entry;
            handle = "event:" + event.id();
            authority = Evidence.Authority.SERVER_READ;
            target = event.at() == null ? null : JournalNavigation.placeId(event.at());
            if (event.detail() != null) {
                try {
                    payload.add("detail", JsonParser.parseString(event.detail()));
                } catch (RuntimeException ignored) {
                    payload.addProperty("detail", event.detail());
                }
            } else {
                payload.add("detail", com.google.gson.JsonNull.INSTANCE);
            }
            String actorName = null;
            if (payload.get("detail").isJsonObject()) {
                var name = payload.getAsJsonObject("detail").get("actorName");
                if (name != null && name.isJsonPrimitive() && name.getAsJsonPrimitive().isString()) actorName = name.getAsString();
            }
            title = RunSummary.describe(event, actorName);
            payload.addProperty("type", event.type().id());
            payload.addProperty("actor", event.actor());
            if (actorName != null) payload.addProperty("actorName", actorName);
            payload.addProperty("subject", event.subject());
            payload.addProperty("dimension", event.dimension());
            payload.addProperty("count", event.count());
            payload.addProperty("amount", event.amount());
            payload.addProperty("from", Instant.ofEpochMilli(event.firstWall()).toString());
            payload.addProperty("to", Instant.ofEpochMilli(event.lastWall()).toString());
            payload.addProperty("fromGameTime", event.firstGameTime());
            payload.addProperty("toGameTime", event.lastGameTime());
            payload.addProperty("minutesAgo", Math.max(0, (now - event.lastWall()) / 60_000.0));
            payload.addProperty("at", event.at() == null ? null : event.at().text());
            if (event.box() != null) {
                var box = new JsonObject();
                var min = new JsonArray();
                min.add(event.box().minX());
                min.add(event.box().minY());
                min.add(event.box().minZ());
                var max = new JsonArray();
                max.add(event.box().maxX());
                max.add(event.box().maxY());
                max.add(event.box().maxZ());
                box.add("min", min);
                box.add("max", max);
                payload.add("box", box);
            } else {
                payload.add("box", com.google.gson.JsonNull.INSTANCE);
            }
            payload.addProperty("summary", title);
            payload.addProperty("countOnly", event.countOnly());
            if (area) {
                var positions = new JsonArray();
                event.inArea().forEach(place -> positions.add(place.text()));
                payload.add("inArea", positions);
                payload.addProperty("inAreaCount", event.inAreaCount());
            }
        }
        return new Evidence.Record(new Evidence.Source(scope, ID, VERSION, handle, scope.requestGeneration(), Instant.ofEpochMilli(now),
                Evidence.Activity.INACTIVE, coverage, authority, List.of()), Evidence.RecordKind.OTHER, title, Evidence.deserializeObject(payload.toString()),
                target == null ? List.of() : List.of(new Evidence.NavigationTarget(scope, ID, handle, target)));
    }

    /** Cursor signatures ignore optional null fields just like world-tool cursors. */
    private static Evidence.JsonValue withoutNulls(Evidence.JsonValue value) {
        if (!(value instanceof Evidence.JsonObject object)) return value;
        var result = new LinkedHashMap<String, Evidence.JsonValue>();
        object.values().forEach((key, nested) -> {
            if (!(nested instanceof Evidence.JsonNull)) result.put(key, withoutNulls(nested));
        });
        return new Evidence.JsonObject(result);
    }

    private static int integer(com.google.gson.JsonElement value, String name, int min, int max) {
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            try {
                var number = value.getAsBigDecimal().intValueExact();
                if (number >= min && number <= max) return number;
            } catch (ArithmeticException ignored) {
            }
        }
        throw new Failure(Code.INVALID_ARGUMENTS, name + " must be an integer from " + min + " to " + max);
    }

    private static List<String> strings(JsonObject object, String name, int max, boolean resourceIds) {
        if (!object.has(name) || object.get(name).isJsonNull()) return List.of();
        if (!object.get(name).isJsonArray() || object.getAsJsonArray(name).size() > max) {
            throw new Failure(Code.INVALID_ARGUMENTS, name + " must be an array of at most " + max + " strings");
        }
        var result = new ArrayList<String>();
        for (var value : object.getAsJsonArray(name)) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                    || resourceIds && !value.getAsString().matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
                throw new Failure(Code.INVALID_ARGUMENTS, name + " contains an invalid string");
            }
            result.add(value.getAsString());
        }
        return List.copyOf(result);
    }
}
