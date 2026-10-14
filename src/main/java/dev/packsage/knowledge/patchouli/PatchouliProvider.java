package dev.packsage.knowledge.patchouli;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.model.PackSageTools;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import vazkii.patchouli.api.PatchouliAPI;
import vazkii.patchouli.client.book.BookContents;
import vazkii.patchouli.client.book.BookEntry;
import vazkii.patchouli.client.book.BookPage;
import vazkii.patchouli.client.book.page.PageBlasting;
import vazkii.patchouli.client.book.page.PageCampfireCooking;
import vazkii.patchouli.client.book.page.PageCrafting;
import vazkii.patchouli.client.book.page.PageSmelting;
import vazkii.patchouli.client.book.page.PageSmithing;
import vazkii.patchouli.client.book.page.PageSmoking;
import vazkii.patchouli.client.book.page.PageSpotlight;
import vazkii.patchouli.client.book.page.PageStonecutting;
import vazkii.patchouli.client.book.page.PageText;
import vazkii.patchouli.client.book.page.abstr.PageWithText;
import vazkii.patchouli.common.book.Book;
import vazkii.patchouli.common.book.BookRegistry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Client-visible loaded Patchouli books and their immutable entry snapshots. */
public final class PatchouliProvider implements KnowledgeProvider {
    public static final String ID = "patchouli";
    public static final String VERSION = "1.21.1-92";

    public record PageRecord(int index, String type, Evidence.JsonObject payload, String text,
                             List<String> itemLinks, List<String> recipeLinks, boolean visible,
                             Evidence.DecodingStatus decoding) {
        public PageRecord {
            Objects.requireNonNull(type);
            Objects.requireNonNull(payload);
            itemLinks = List.copyOf(itemLinks);
            recipeLinks = List.copyOf(recipeLinks);
            Objects.requireNonNull(decoding);
        }
    }

    public record EntryRecord(String bookId, String id, String title, List<PageRecord> pages,
                              boolean visible, boolean locked, boolean secret, boolean hidden,
                              List<String> recipeMappings, List<String> provenance,
                              Evidence.Coverage coverage, String failure) {
        public EntryRecord {
            Objects.requireNonNull(bookId);
            Objects.requireNonNull(id);
            Objects.requireNonNull(title);
            pages = List.copyOf(pages);
            recipeMappings = List.copyOf(recipeMappings);
            provenance = List.copyOf(provenance);
            Objects.requireNonNull(coverage);
        }

        public EntryRecord(String bookId, String id, String title, List<PageRecord> pages,
                           boolean visible, boolean locked, boolean secret, boolean hidden,
                           List<String> recipeMappings) {
            this(bookId, id, title, pages, visible, locked, secret, hidden, recipeMappings, List.of(),
                    Evidence.Coverage.COMPLETE, null);
        }

        public EntryRecord(String bookId, String id, String title, List<PageRecord> pages,
                           boolean visible, boolean locked, boolean secret, boolean hidden,
                           List<String> recipeMappings, List<String> provenance) {
            this(bookId, id, title, pages, visible, locked, secret, hidden, recipeMappings, provenance,
                    Evidence.Coverage.COMPLETE, null);
        }
    }

    public record BookRecord(String id, String title, List<EntryRecord> entries, List<String> provenance,
                             Evidence.Coverage coverage, String failure) {
        public BookRecord {
            Objects.requireNonNull(id);
            Objects.requireNonNull(title);
            entries = List.copyOf(entries);
            provenance = List.copyOf(provenance);
            Objects.requireNonNull(coverage);
        }

        public BookRecord(String id, String title, List<EntryRecord> entries, List<String> provenance) {
            this(id, title, entries, provenance, Evidence.Coverage.COMPLETE, null);
        }
    }

    public record Navigation(String bookId, String entryId, int page) {
        public Navigation {
            Objects.requireNonNull(bookId);
            Objects.requireNonNull(entryId);
            if (page < 0) throw new IllegalArgumentException("page must not be negative");
        }
    }

    private record LivePosition(int bookIndex, int entryIndex, int pageIndex, int mappingIndex, int phase,
                                String bookId, String entryId) { }

    private record BoundedJson(Evidence.JsonValue value, boolean complete) { }

    private record BoundedPage(PageRecord page, boolean complete) { }

    private record BoundedEntry(EntryRecord entry, int nextPage, int nextMapping, int nextPhase, boolean complete) { }

    public record Snapshot(Evidence.Scope scope, long generation, List<BookRecord> books,
                           Consumer<Navigation> navigator, Evidence.Coverage coverage, String failure) {
        public Snapshot {
            Objects.requireNonNull(scope);
            books = List.copyOf(books);
            Objects.requireNonNull(navigator);
            Objects.requireNonNull(coverage);
        }

        public Snapshot(Evidence.Scope scope, long generation, List<BookRecord> books,
                        Consumer<Navigation> navigator) {
            this(scope, generation, books, navigator, Evidence.Coverage.COMPLETE, null);
        }

        /** Captures the currently loaded Patchouli registry on its owning client thread. */
        public static Snapshot capture(Evidence.Scope scope) {
            var books = new ArrayList<BookRecord>();
            var coverage = Evidence.Coverage.COMPLETE;
            String failure = null;
            try {
                for (var book : BookRegistry.INSTANCE.books.values()) {
                    var captured = captureBook(book);
                    books.add(captured);
                    if (captured.coverage() == Evidence.Coverage.ERROR) coverage = Evidence.Coverage.ERROR;
                    else if (captured.coverage() != Evidence.Coverage.COMPLETE
                            && coverage == Evidence.Coverage.COMPLETE) coverage = Evidence.Coverage.PARTIAL;
                    if (failure == null) failure = captured.failure();
                }
            } catch (RuntimeException | LinkageError captureFailure) {
                coverage = Evidence.Coverage.ERROR;
                failure = "Patchouli registry capture failed: " + captureFailure;
            }
            var capturedCoverage = coverage;
            var capturedFailure = failure;
            return new Snapshot(scope, scope.requestGeneration(), books, navigation ->
                    PatchouliAPI.get().openBookEntry(ResourceLocation.parse(navigation.bookId()),
                            ResourceLocation.parse(navigation.entryId()), navigation.page()), capturedCoverage, capturedFailure);
        }

        private static BookRecord captureBook(Book book) {
            var entries = new ArrayList<EntryRecord>();
            var coverage = Evidence.Coverage.COMPLETE;
            String failure = null;
            try {
                BookContents contents = book.getContents();
                if (contents == null) {
                    coverage = Evidence.Coverage.ERROR;
                    failure = "book contents are unavailable";
                } else {
                    if (contents.isErrored()) {
                        coverage = Evidence.Coverage.ERROR;
                        failure = "book contents failed to reload: " + contents.getException();
                    }
                    try {
                        for (var entry : contents.entries.values()) {
                            try {
                                var captured = captureEntry(book, entry);
                                entries.add(captured);
                                if (captured.coverage() == Evidence.Coverage.ERROR) coverage = Evidence.Coverage.ERROR;
                                else if (captured.coverage() != Evidence.Coverage.COMPLETE
                                        && coverage == Evidence.Coverage.COMPLETE) coverage = Evidence.Coverage.PARTIAL;
                                if (failure == null) failure = captured.failure();
                            } catch (RuntimeException entryFailure) {
                                coverage = Evidence.Coverage.ERROR;
                                failure = "entry capture failed: " + entryFailure;
                            }
                        }
                    } catch (RuntimeException entriesFailure) {
                        coverage = Evidence.Coverage.ERROR;
                        failure = "book entries capture failed: " + entriesFailure;
                    }
                }
            } catch (RuntimeException | LinkageError contentsFailure) {
                coverage = Evidence.Coverage.ERROR;
                failure = "book contents capture failed: " + contentsFailure;
            }
            var provenance = new ArrayList<String>();
            provenance.add("patchouli:book:" + book.id);
            if (book.owner != null) provenance.add("patchouli:owner:" + book.owner.getId());
            return new BookRecord(book.id.toString(), book.name, entries, provenance, coverage, failure);
        }

        private static EntryRecord captureEntry(Book book, BookEntry entry) {
            var pages = new ArrayList<PageRecord>();
            var coverage = Evidence.Coverage.COMPLETE;
            String failure = null;
            var id = "unknown";
            var title = "unknown";
            var hidden = true;
            var locked = true;
            var secret = false;
            try {
                id = entry.getId().toString();
                title = entry.getName().getString();
                hidden = entry.shouldHide();
                locked = entry.isLocked();
                secret = entry.isSecret();
                if (!hidden && !locked) {
                    var index = 0;
                    try {
                        for (var page : entry.getPages()) {
                            try {
                                var captured = capturePage(page, index);
                                pages.add(captured);
                                if (captured.decoding() == Evidence.DecodingStatus.UNKNOWN
                                        || !captured.visible()) coverage = Evidence.Coverage.PARTIAL;
                            } catch (RuntimeException pageFailure) {
                                coverage = Evidence.Coverage.PARTIAL;
                                if (failure == null) failure = "page " + index + " capture failed: " + pageFailure;
                                pages.add(new PageRecord(index, "unknown", new Evidence.JsonObject(Map.of()), null,
                                        List.of(), List.of(), false, Evidence.DecodingStatus.UNKNOWN));
                            }
                            index++;
                        }
                    } catch (RuntimeException pagesFailure) {
                        coverage = Evidence.Coverage.ERROR;
                        failure = "entry pages capture failed: " + pagesFailure;
                    }
                }
            } catch (RuntimeException entryFailure) {
                coverage = Evidence.Coverage.ERROR;
                failure = "entry metadata capture failed: " + entryFailure;
            }
            var mappings = new ArrayList<String>();
            // Patchouli does not expose this map publicly in the pinned release. The loaded BookEntry field is
            // read reflectively so the provider preserves the runtime mapping instead of rereading shipped JSON.
            try {
                var field = BookEntry.class.getDeclaredField("extraRecipeMappings");
                field.setAccessible(true);
                var values = field.get(entry);
                if (values instanceof Map<?, ?> map) {
                    map.forEach((item, page) -> mappings.add(String.valueOf(item) + "@" + page));
                }
            } catch (ReflectiveOperationException | RuntimeException mappingFailure) {
                if (coverage == Evidence.Coverage.COMPLETE) coverage = Evidence.Coverage.PARTIAL;
                if (failure == null) failure = "recipe mapping capture failed: " + mappingFailure;
            }
            var addedBy = "";
            try { addedBy = entry.getAddedBy(); } catch (RuntimeException provenanceFailure) {
                if (coverage == Evidence.Coverage.COMPLETE) coverage = Evidence.Coverage.PARTIAL;
                if (failure == null) failure = "entry provenance capture failed: " + provenanceFailure;
            }
            var provenance = addedBy == null || addedBy.isBlank() ? List.<String>of()
                    : List.of("patchouli:resource:" + addedBy);
            return new EntryRecord(book.id.toString(), id, title, pages, !hidden && !locked, locked, secret, hidden,
                    mappings, provenance, coverage, failure);
        }

        private static PageRecord capturePage(BookPage page, int index) {
            var unlocked = page.isPageUnlocked();
            var source = page.sourceObject;
            var type = source != null && source.has("type") ? source.get("type").getAsString()
                    : page.getClass().getSimpleName();
            if (!unlocked) return new PageRecord(index, type, new Evidence.JsonObject(Map.of()), null,
                    List.of(), List.of(), false, Evidence.DecodingStatus.UNKNOWN);
            source = source == null ? new JsonObject() : source.deepCopy();
            type = source.has("type") ? source.get("type").getAsString() : type;
            var text = page instanceof PageWithText && source.has("text") && source.get("text").isJsonPrimitive()
                    ? page.i18n(source.get("text").getAsString()) : null;
            var items = values(source, "item", "items");
            var recipes = values(source, "recipe", "recipes", "recipe_id", "recipeId");
            var supported = page instanceof PageText || page instanceof PageSpotlight || page instanceof PageCrafting
                    || page instanceof PageSmelting || page instanceof PageBlasting || page instanceof PageSmoking
                    || page instanceof PageCampfireCooking || page instanceof PageSmithing
                    || page instanceof PageStonecutting;
            return new PageRecord(index, type, fromGson(source), text, items, recipes, true,
                    supported ? Evidence.DecodingStatus.VERIFIED : Evidence.DecodingStatus.OPAQUE);
        }

        private static List<String> values(JsonObject source, String... keys) {
            var result = new ArrayList<String>();
            for (var key : keys) {
                var value = source.get(key);
                if (value == null || value.isJsonNull()) continue;
                if (value.isJsonArray()) value.getAsJsonArray().forEach(item -> result.add(item.isJsonPrimitive()
                        ? item.getAsString() : item.toString()));
                else result.add(value.isJsonPrimitive() ? value.getAsString() : value.toString());
            }
            return result;
        }

        private static Evidence.JsonObject fromGson(JsonObject source) {
            var values = new java.util.LinkedHashMap<String, Evidence.JsonValue>();
            source.entrySet().forEach(entry -> values.put(entry.getKey(), fromGson(entry.getValue())));
            return new Evidence.JsonObject(values);
        }

        private static Evidence.JsonValue fromGson(JsonElement value) {
            if (value.isJsonNull()) return new Evidence.JsonNull();
            if (value.isJsonObject()) return fromGson(value.getAsJsonObject());
            if (value.isJsonArray()) return new Evidence.JsonArray(value.getAsJsonArray().asList().stream().map(Snapshot::fromGson).toList());
            if (value.getAsJsonPrimitive().isBoolean()) return new Evidence.JsonBoolean(value.getAsBoolean());
            if (value.getAsJsonPrimitive().isNumber()) return new Evidence.JsonNumber(value.getAsBigDecimal());
            return new Evidence.JsonString(value.getAsString());
        }
    }

    private static final int MAX_QUERY_SCAN = 256;

    private final Supplier<Snapshot> snapshots;
    private final Executor clientExecutor;
    private final Supplier<Evidence.Scope> scopeSource;
    private final BooleanSupplier owningThread;
    private volatile Snapshot current;
    private volatile long lifecycleGeneration;
    private volatile long currentLifecycleGeneration = -1;
    private volatile String failure;
    private volatile boolean unavailable;

    public PatchouliProvider(Supplier<Snapshot> snapshots, Executor clientExecutor) {
        this(snapshots, clientExecutor, null, () -> false);
    }

    private PatchouliProvider(Supplier<Snapshot> snapshots, Executor clientExecutor,
                              Supplier<Evidence.Scope> scopeSource, BooleanSupplier owningThread) {
        this.snapshots = Objects.requireNonNull(snapshots);
        this.clientExecutor = Objects.requireNonNull(clientExecutor);
        this.scopeSource = scopeSource;
        this.owningThread = Objects.requireNonNull(owningThread);
    }

    public static PatchouliProvider forMinecraft(Supplier<Evidence.Scope> scopeSource, Executor clientExecutor) {
        Objects.requireNonNull(scopeSource);
        return new PatchouliProvider(() -> Snapshot.capture(scopeSource.get()), clientExecutor, scopeSource,
                () -> Minecraft.getInstance().isSameThread());
    }

    public static boolean isRuntimeAvailable() {
        try {
            Class.forName("vazkii.patchouli.common.book.BookRegistry", false, PatchouliProvider.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    @Override
    public String id() { return ID; }

    @Override
    public boolean enabled() { return !unavailable; }

    @Override
    public ProviderStatus status() {
        var snapshot = current;
        if (failure != null) return new ProviderStatus(Availability.ERROR, VERSION, failure);
        if (unavailable || snapshot == null) {
            return new ProviderStatus(Availability.UNAVAILABLE, VERSION, "Patchouli runtime unavailable");
        }
        if (currentLifecycleGeneration != lifecycleGeneration) {
            return new ProviderStatus(Availability.PARTIAL, VERSION, "Patchouli snapshot was invalidated");
        }
        if (snapshot.coverage() == Evidence.Coverage.ERROR) {
            return new ProviderStatus(Availability.ERROR, VERSION,
                    snapshot.failure() == null ? "Patchouli snapshot capture failed" : snapshot.failure());
        }
        var opaque = snapshot.books().stream().flatMap(book -> book.entries().stream())
                .flatMap(entry -> entry.pages().stream()).anyMatch(page -> page.decoding() == Evidence.DecodingStatus.OPAQUE);
        var partial = snapshot.coverage() != Evidence.Coverage.COMPLETE || opaque;
        return new ProviderStatus(partial ? Availability.PARTIAL : Availability.AVAILABLE, VERSION,
                snapshot.failure() == null ? "loaded Patchouli books generation " + snapshot.generation() : snapshot.failure());
    }

    @Override
    public Toolbox.Metadata metadata() {
        return new Toolbox.Metadata(ID, List.of("guide"), false, List.of(), Map.of());
    }

    @Override
    public CompletionStage<Evidence.Result> searchDocument(PackSageTools.ReadRequest request, Toolbox.Context context) {
        var filter = request.arguments().values().get("filter");
        if (!(filter instanceof Evidence.JsonObject selected)
                || !(selected.values().get("provider") instanceof Evidence.JsonString provider)
                || !ID.equals(provider.value())) return CompletableFuture.failedFuture(
                new Toolbox.Failure(Toolbox.Code.NOT_HANDLED, "Patchouli only handles provider-qualified document searches"));
        if (selected.values().get("types") instanceof Evidence.JsonArray types && !types.values().isEmpty()
                && types.values().stream().noneMatch(type -> type instanceof Evidence.JsonString string
                && "guide".equals(string.value()))) {
            return CompletableFuture.failedFuture(new Toolbox.Failure(Toolbox.Code.NOT_HANDLED,
                    "Patchouli does not contribute the requested document type"));
        }
        if (!(request.arguments().values().get("query") instanceof Evidence.JsonString query)) {
            return CompletableFuture.failedFuture(new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS,
                    "search_document requires a query"));
        }
        if (!request.scope().equals(context.currentScope().get())) return CompletableFuture.failedFuture(
                new Toolbox.Failure(Toolbox.Code.STALE_SCOPE, "repeat the lookup in the current scope"));
        var arguments = new java.util.LinkedHashMap<String, Evidence.JsonValue>();
        arguments.put("text", query);
        var cursor = request.arguments().values().get("cursor") instanceof Evidence.JsonString value ? value.value() : null;
        String sourceCursor;
        try {
            sourceCursor = decodeDocumentCursor(cursor, query.value(), request.scope());
        } catch (Toolbox.Failure failure) {
            return CompletableFuture.failedFuture(failure);
        }
        var limit = request.arguments().values().get("limit") instanceof Evidence.JsonNumber value
                ? value.value().intValue() : 16;
        if (scopeSource != null) return searchLiveDocument(request, context, query.value(), sourceCursor, limit);
        try {
            return query(new Evidence.Query(Evidence.Operation.SEARCH, new Evidence.JsonObject(arguments), limit,
                    sourceCursor == null ? null : request.scope(), sourceCursor), request.scope()).thenApply(result -> {
                if (!request.scope().equals(context.currentScope().get())) throw new Toolbox.Failure(Toolbox.Code.STALE_SCOPE,
                        "scope changed while reading Patchouli");
                if (result.coverage() == Evidence.Coverage.ERROR) throw new Toolbox.Failure(
                        result.detail() != null && result.detail().contains("cursor")
                                ? Toolbox.Code.INVALID_CURSOR : Toolbox.Code.EXTRACTION_FAILED,
                        result.detail() == null ? "Patchouli extraction failed" : result.detail());
                return result.nextCursor() == null ? result : new Evidence.Result(result.records(), result.coverage(),
                        result.outcome(), encodeDocumentCursor(query.value(), result.nextCursor(), request.scope()), result.detail());
            });
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override
    public CompletionStage<Evidence.Result> readEvidence(PackSageTools.ReadRequest request, Toolbox.Context context) {
        var reference = context.reference(request);
        if (!ID.equals(reference.providerId())) return CompletableFuture.failedFuture(
                new Toolbox.Failure(Toolbox.Code.NOT_HANDLED, "Patchouli does not own this reference"));
        if (reference.kind() != Evidence.RecordKind.GUIDE) return CompletableFuture.failedFuture(
                new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE, "expected an observed Patchouli guide reference"));
        var id = reference.sourceHandle();
        if (scopeSource != null) {
            var arguments = new java.util.LinkedHashMap<String, Evidence.JsonValue>();
            arguments.put("query", new Evidence.JsonString(id.substring(id.indexOf('#') + 1)));
            arguments.put("filter", new Evidence.JsonObject(Map.of("provider", new Evidence.JsonString(ID),
                    "types", new Evidence.JsonArray(List.of(new Evidence.JsonString("guide"))))));
            arguments.put("limit", new Evidence.JsonNumber(BigDecimal.valueOf(256)));
            return searchLiveDocument(new PackSageTools.ReadRequest("search_document",
                    new Evidence.JsonObject(arguments), request.scope()), context,
                    ((Evidence.JsonString) arguments.get("query")).value(), null, 256).thenApply(result -> {
                var record = result.records().stream().filter(value -> value.source().sourceHandle().equals(id))
                        .findFirst().orElseThrow(() -> new Toolbox.Failure(Toolbox.Code.UNAVAILABLE,
                                "the observed guide entry is no longer available"));
                return new Evidence.Result(List.of(record), record.source().coverage(), result.outcome(), null, result.detail());
            });
        }
        var separator = id.indexOf('#');
        var entryId = separator < 0 ? id : id.substring(separator + 1);
        return query(new Evidence.Query(Evidence.Operation.GET, new Evidence.JsonObject(Map.of(
                "id", new Evidence.JsonString(entryId))), 1, null, null), request.scope())
                .thenApply(result -> {
                    if (!request.scope().equals(context.currentScope().get())) throw new Toolbox.Failure(
                            Toolbox.Code.STALE_SCOPE, "scope changed while reading Patchouli");
                    if (result.records().isEmpty()) throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE,
                            "the observed guide entry is no longer available");
                    if (result.coverage() == Evidence.Coverage.ERROR) throw new Toolbox.Failure(
                            Toolbox.Code.EXTRACTION_FAILED, "current Patchouli extraction failed");
                    return result;
                });
    }

    private CompletionStage<Evidence.Result> searchLiveDocument(PackSageTools.ReadRequest request, Toolbox.Context context,
                                                                 String query, String cursor, int limit) {
        var start = 0;
        var startPage = 0;
        var cursorOwner = cursor != null && cursor.split(":", -1).length == 3
                ? cursor.split(":", -1)[0] : null;
        if (cursor != null) {
            try {
                var position = cursor.split(":", -1);
                var ownerOffset = position.length == 3 ? 1 : 0;
                start = Integer.parseInt(position[ownerOffset]);
                startPage = position.length == ownerOffset + 2 ? Integer.parseInt(position[ownerOffset + 1]) : 0;
                if (position.length > ownerOffset + 2 || startPage < 0) throw new NumberFormatException();
            } catch (NumberFormatException failure) { return CompletableFuture.failedFuture(
                    new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "Patchouli source position is invalid; restart the search")); }
            if (start < 0) return CompletableFuture.failedFuture(
                    new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "Patchouli source position is invalid; restart the search"));
        }
        var offset = start;
        var initialPage = startPage;
        return CompletableFuture.supplyAsync(() -> {
            if (unavailable) throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "Patchouli runtime is unavailable");
            if (!owningThread.getAsBoolean()) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                    "Patchouli live traversal must run on the client thread");
            var scope = scopeSource.get();
            if (!request.scope().equals(scope) || !request.scope().equals(context.currentScope().get()))
                throw new Toolbox.Failure(Toolbox.Code.STALE_SCOPE, "repeat the lookup in the current scope");
            if (cursorOwner != null && !cursorOwner.equals(Integer.toString(System.identityHashCode(BookRegistry.INSTANCE))))
                throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                        "Patchouli cursor belongs to a replaced registry; restart the search");
            var budget = context.documentBudget();
            var records = new ArrayList<Evidence.Record>();
            var liveBooks = new java.util.LinkedHashMap<String, BookRecord>();
            var position = 0;
            var next = (String) null;
            var currentGeneration = lifecycleGeneration;
            outer: for (var book : BookRegistry.INSTANCE.books.values()) {
                var contents = book.getContents();
                if (contents == null || contents.isErrored()) continue;
                for (var entry : contents.entries.values()) {
                    var candidate = position++;
                    if (candidate < offset) {
                        if (!budget.document()) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                                "Patchouli source position exceeds the bounded seek; restart the search");
                        continue;
                    }
                    if (!budget.document()) { next = candidate + ":0"; break outer; }
                    if (entry.shouldHide() || entry.isLocked()) continue;
                    var id = entry.getId().toString();
                    var title = entry.getName().getString();
                    var pages = new ArrayList<PageRecord>();
                    var partial = false;
                    var pageIndex = 0;
                    for (var page : entry.getPages()) {
                        if (pageIndex < (candidate == offset ? initialPage : 0)) {
                            if (!budget.value()) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                                    "Patchouli page position exceeds the bounded seek; restart the search");
                            pageIndex++;
                            continue;
                        }
                        pageIndex++;
                        if (!page.isPageUnlocked()) { partial = true; continue; }
                        if (!budget.value()) { partial = true; next = candidate + ":" + (pageIndex - 1); break; }
                        var source = page.sourceObject;
                        var type = source != null && source.has("type") && source.get("type").isJsonPrimitive()
                                ? source.get("type").getAsString() : page.getClass().getSimpleName();
                        partial = true;
                        var text = (String) null;
                        if (page instanceof PageWithText && source != null && source.has("text")
                                && source.get("text").isJsonPrimitive()) {
                            var raw = source.get("text").getAsString();
                            if (budget.bytes(raw.length() * 2)) {
                                var translated = page.i18n(raw);
                                if (budget.bytes(translated.length() * 2)) text = translated;
                                else partial = true;
                            }
                            else partial = true;
                        }
                        if (!budget.bytes(type.length() * 2)) { partial = true; type = "unknown"; }
                        var supported = page instanceof PageText || page instanceof PageSpotlight || page instanceof PageCrafting
                                || page instanceof PageSmelting || page instanceof PageBlasting || page instanceof PageSmoking
                                || page instanceof PageCampfireCooking || page instanceof PageSmithing || page instanceof PageStonecutting;
                        pages.add(new PageRecord(pageIndex - 1, type, new Evidence.JsonObject(Map.of()), text,
                                List.of(), List.of(), true, supported ? Evidence.DecodingStatus.VERIFIED
                                : Evidence.DecodingStatus.OPAQUE));
                    }
                    if (candidate == offset && initialPage > 0) partial = true;
                    var found = new EntryRecord(book.id.toString(), id, title, pages, true, false, entry.isSecret(),
                            false, List.of(), List.of(), partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                            partial ? "bounded live guide fragment" : null);
                    if (query.isEmpty() || id.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))
                            || book.id.toString().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))
                            || title.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))
                            || pages.stream().anyMatch(value -> value.text() != null
                            && value.text().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))) {
                        var prior = liveBooks.get(book.id.toString());
                        var entries = new ArrayList<EntryRecord>(prior == null ? List.of() : prior.entries());
                        entries.add(found);
                        liveBooks.put(book.id.toString(), new BookRecord(book.id.toString(), book.name, entries,
                                List.of(), Evidence.Coverage.PARTIAL, "bounded live guide fragments"));
                        var snapshot = new Snapshot(scope, scope.requestGeneration(), new ArrayList<>(liveBooks.values()),
                                navigation -> PatchouliAPI.get().openBookEntry(ResourceLocation.parse(navigation.bookId()),
                                        ResourceLocation.parse(navigation.entryId()), navigation.page()));
                        current = snapshot;
                        currentLifecycleGeneration = currentGeneration;
                        records.add(toRecord(found, snapshot, matchingPages(found, Evidence.Operation.SEARCH,
                                query.toLowerCase(Locale.ROOT)), currentGeneration));
                    }
                    if (records.size() >= limit || next != null) {
                        if (next == null) next = Integer.toString(position);
                        break outer;
                    }
                }
            }
            if (!request.scope().equals(context.currentScope().get()) || !request.scope().equals(scopeSource.get()))
                throw new Toolbox.Failure(Toolbox.Code.STALE_SCOPE, "scope changed while reading Patchouli");
            var coverage = records.stream().anyMatch(record -> record.source().coverage() != Evidence.Coverage.COMPLETE)
                    ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE;
            var continuation = next == null ? null : next.contains(":") ? next : next + ":0";
            return result(records, coverage, continuation == null ? null : encodeDocumentCursor(query,
                            System.identityHashCode(BookRegistry.INSTANCE) + ":" + continuation, request.scope()),
                    budget.exhausted() ? "Patchouli live traversal reached the shared read budget" : null);
        }, clientExecutor);
    }

    private static String encodeDocumentCursor(String query, String position, Evidence.Scope scope) {
        var scopeValue = Evidence.serialize(new Evidence.JsonObject(Map.of(
                "world", new Evidence.JsonString(scope.worldSession()),
                "locale", new Evidence.JsonString(scope.resourcesLocale()),
                "team", new Evidence.JsonString(scope.playerTeam()),
                "runtime", new Evidence.JsonString(scope.providerRuntime()),
                "generation", new Evidence.JsonNumber(BigDecimal.valueOf(scope.requestGeneration())))));
        var value = new Evidence.JsonObject(Map.of("query", new Evidence.JsonString(query),
                "scope", new Evidence.JsonString(scopeValue), "position", new Evidence.JsonString(position)));
        return "patchouli:" + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Evidence.serialize(value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String decodeDocumentCursor(String cursor, String query, Evidence.Scope scope) {
        if (cursor == null) return null;
        try {
            if (!cursor.startsWith("patchouli:")) throw new IllegalArgumentException();
            var value = Evidence.deserializeObject(new String(java.util.Base64.getUrlDecoder().decode(cursor.substring(10)),
                    java.nio.charset.StandardCharsets.UTF_8));
            var scopeValue = Evidence.serialize(new Evidence.JsonObject(Map.of(
                    "world", new Evidence.JsonString(scope.worldSession()),
                    "locale", new Evidence.JsonString(scope.resourcesLocale()),
                    "team", new Evidence.JsonString(scope.playerTeam()),
                    "runtime", new Evidence.JsonString(scope.providerRuntime()),
                    "generation", new Evidence.JsonNumber(BigDecimal.valueOf(scope.requestGeneration())))));
            if (!(value.values().get("query") instanceof Evidence.JsonString savedQuery) || !query.equals(savedQuery.value())
                    || !(value.values().get("scope") instanceof Evidence.JsonString savedScope) || !scopeValue.equals(savedScope.value())
                    || !(value.values().get("position") instanceof Evidence.JsonString position)
                    || position.value().isBlank()) throw new IllegalArgumentException();
            return position.value();
        } catch (RuntimeException failure) {
            throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "Patchouli cursor is invalid for this query or scope; restart the search");
        }
    }

    public void refresh() {
        unavailable = false;
        failure = null;
        var generation = ++lifecycleGeneration;
        try {
            clientExecutor.execute(() -> {
                if (generation != lifecycleGeneration || unavailable) return;
                try {
                    var snapshot = snapshots.get();
                    if (generation != lifecycleGeneration || unavailable) return;
                    if (snapshot == null) {
                        current = null;
                        currentLifecycleGeneration = -1;
                        failure = "Patchouli runtime unavailable";
                    } else if (!scopeIsCurrent(snapshot.scope())) {
                        current = null;
                        currentLifecycleGeneration = -1;
                        failure = "Patchouli snapshot belongs to an inactive scope";
                    } else {
                        current = snapshot;
                        currentLifecycleGeneration = generation;
                        failure = snapshot.coverage() == Evidence.Coverage.ERROR ? snapshot.failure() : null;
                    }
                } catch (RuntimeException | LinkageError captureFailure) {
                    current = null;
                    currentLifecycleGeneration = -1;
                    failure = "Patchouli capture failed: " + captureFailure;
                }
            });
        } catch (RuntimeException schedulingFailure) {
            current = null;
            currentLifecycleGeneration = -1;
            failure = "Patchouli client-thread scheduling failed: " + schedulingFailure;
        }
    }

    public void invalidate() {
        ++lifecycleGeneration;
        failure = null;
    }

    public void unavailable() {
        unavailable = true;
        ++lifecycleGeneration;
        current = null;
        currentLifecycleGeneration = -1;
        failure = null;
    }

    @Override
    public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
        Objects.requireNonNull(query);
        Objects.requireNonNull(scope);
        if (query.operation() == Evidence.Operation.GET) {
            var id = stringArgument(query.arguments(), "id");
            if (id == null || id.isBlank()) return CompletableFuture.completedFuture(errorResult());
        }
        if (query.operation() == Evidence.Operation.SEARCH
                && stringArgument(query.arguments(), "text") == null) return CompletableFuture.completedFuture(errorResult());
        var start = query.cursor() == null ? 0 : parseCursor(query.cursor());
        if (start < 0) return CompletableFuture.completedFuture(errorResult());
        if (unavailable) return CompletableFuture.completedFuture(unavailableResult());
        var generation = lifecycleGeneration;
        try {
            return CompletableFuture.supplyAsync(snapshots, clientExecutor).handle((snapshot, captureFailure) -> {
                if (unavailable) return unavailableResult();
                if (generation != lifecycleGeneration) {
                    return new Evidence.Result(List.of(), Evidence.Coverage.PARTIAL, Evidence.Outcome.UNKNOWN, null,
                            "Patchouli snapshot was invalidated while the query was running");
                }
                if (captureFailure != null) {
                    failure = "Patchouli capture failed: " + captureFailure;
                    return new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null, failure);
                }
                if (snapshot == null) {
                    current = null;
                    currentLifecycleGeneration = -1;
                    failure = "Patchouli runtime unavailable";
                    return unavailableResult();
                }
                if (!sameScope(snapshot.scope(), scope) || !scopeIsCurrent(snapshot.scope())
                        || query.cursor() != null && query.cursorScope() != null
                        && !sameScope(query.cursorScope(), scope)) {
                    return new Evidence.Result(List.of(), Evidence.Coverage.PARTIAL, Evidence.Outcome.UNKNOWN, null,
                            "Patchouli snapshot scope does not match the requested active scope");
                }
                current = snapshot;
                currentLifecycleGeneration = generation;
                failure = snapshot.coverage() == Evidence.Coverage.ERROR ? snapshot.failure() : null;
                return querySnapshot(query, snapshot, start, generation);
            });
        } catch (RuntimeException schedulingFailure) {
            failure = "Patchouli client-thread scheduling failed: " + schedulingFailure;
            return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.ERROR,
                    Evidence.Outcome.ERROR, null, failure));
        }
    }

    @Override
    public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
        Objects.requireNonNull(target);
        Objects.requireNonNull(scope);
        var snapshot = current;
        if (snapshot == null || unavailable) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "Patchouli runtime unavailable");
        }
        if (currentLifecycleGeneration != lifecycleGeneration) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.STALE,
                    "Patchouli target belongs to an invalidated snapshot");
        }
        if (!ID.equals(target.providerId()) || !sameScope(snapshot.scope(), scope) || !sameScope(target.scope(), scope)
                || !scopeIsCurrent(snapshot.scope()))
            return new Evidence.NavigationResult(Evidence.NavigationStatus.STALE, "Patchouli target is outside the active runtime scope");
        var sourceHandle = target.sourceHandle();
        var parts = target.targetId().split("#", -1);
        if (parts.length != 3) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "malformed Patchouli page target");
        }
        var generationMarker = parts[2].indexOf("@lifecycle=");
        if (generationMarker <= 0 || !sourceHandle.equals(parts[0] + "#" + parts[1])
                || generationMarker + "@lifecycle=".length() >= parts[2].length()) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.STALE,
                    "Patchouli target identity does not match its source");
        }
        final long targetGeneration;
        try {
            targetGeneration = Long.parseLong(parts[2].substring(generationMarker + "@lifecycle=".length()));
        } catch (NumberFormatException malformedGeneration) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.STALE, "malformed Patchouli target generation");
        }
        final int page;
        try {
            page = Integer.parseInt(parts[2].substring(0, generationMarker));
        } catch (NumberFormatException malformedPage) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "malformed Patchouli page target");
        }
        if (!target.targetId().equals(parts[0] + "#" + parts[1] + "#" + page + "@lifecycle=" + targetGeneration)) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.STALE,
                    "Patchouli target identity is not canonical");
        }
        if (page < 0 || targetGeneration != lifecycleGeneration) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.STALE, "Patchouli target is stale");
        }
        var entry = snapshot.books().stream().filter(book -> book.id().equals(parts[0])).flatMap(book -> book.entries().stream())
                .filter(value -> value.id().equals(parts[1])).findFirst().orElse(null);
        var pageRecord = entry == null ? null : entry.pages().stream().filter(value -> value.index() == page).findFirst().orElse(null);
        if (entry == null || !entry.visible() || entry.locked() || entry.hidden() || pageRecord == null) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "Patchouli entry is unavailable or hidden");
        }
        if (entry.coverage() == Evidence.Coverage.ERROR) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.UNSUPPORTED, "Patchouli entry capture failed");
        }
        if (!pageRecord.visible()) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "Patchouli page is locked");
        }
        if (owningThread.getAsBoolean()) {
            try {
                snapshot.navigator().accept(new Navigation(parts[0], parts[1], page));
                return new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED, "Patchouli entry opened");
            } catch (RuntimeException navigationFailure) {
                return new Evidence.NavigationResult(Evidence.NavigationStatus.ERROR, navigationFailure.toString());
            }
        }
        var result = new CompletableFuture<Evidence.NavigationResult>();
        try {
            clientExecutor.execute(() -> {
                if (unavailable || current != snapshot || currentLifecycleGeneration != lifecycleGeneration
                        || !scopeIsCurrent(snapshot.scope())) {
                    result.complete(new Evidence.NavigationResult(Evidence.NavigationStatus.STALE,
                            "Patchouli snapshot changed before navigation"));
                    return;
                }
                try {
                    snapshot.navigator().accept(new Navigation(parts[0], parts[1], page));
                    result.complete(new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED,
                            "Patchouli entry opened"));
                } catch (RuntimeException navigationFailure) {
                    result.complete(new Evidence.NavigationResult(Evidence.NavigationStatus.ERROR,
                            navigationFailure.toString()));
                }
            });
        } catch (RuntimeException schedulingFailure) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.ERROR, schedulingFailure.toString());
        }
        try {
            return result.get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.ERROR, "Patchouli client thread did not respond");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Evidence.NavigationResult(Evidence.NavigationStatus.ERROR, "Patchouli navigation was interrupted");
        } catch (java.util.concurrent.ExecutionException executionFailure) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.ERROR, executionFailure.getCause().toString());
        }
    }

    private static Evidence.Result querySnapshot(Evidence.Query query, Snapshot snapshot, int start, long lifecycleGeneration) {
        var text = stringArgument(query.arguments(), "text");
        text = text == null ? "" : text.toLowerCase(Locale.ROOT);
        var id = stringArgument(query.arguments(), "id");
        var entries = new ArrayList<EntryRecord>();
        var skipped = 0;
        var scanCapped = false;
        outer:
        for (var book : snapshot.books()) {
            for (var entry : book.entries()) {
                if (!entry.visible() || entry.locked() || entry.hidden()) continue;
                if (skipped < start) {
                    skipped++;
                    continue;
                }
                if (entries.size() == MAX_QUERY_SCAN) {
                    scanCapped = true;
                    break outer;
                }
                entries.add(entry);
            }
        }
        if (skipped < start) return errorResult();
        var records = new ArrayList<Evidence.Record>();
        var scanned = 0;
        for (var entry : entries) {
            if (records.size() >= query.limit()) break;
            scanned++;
            if (query.operation() == Evidence.Operation.GET && !id.equals(entry.id())
                    && !id.equals(entry.bookId() + "#" + entry.id())) continue;
            if (query.operation() == Evidence.Operation.SEARCH && !matches(entry, text)) continue;
            records.add(toRecord(entry, snapshot, matchingPages(entry, query.operation(), text), lifecycleGeneration));
        }
        var coverage = snapshot.coverage();
        if (coverage == Evidence.Coverage.COMPLETE && records.stream()
                .anyMatch(record -> record.source().coverage() == Evidence.Coverage.ERROR)) coverage = Evidence.Coverage.ERROR;
        else if (coverage == Evidence.Coverage.COMPLETE && records.stream()
                .anyMatch(record -> record.source().coverage() == Evidence.Coverage.PARTIAL)) coverage = Evidence.Coverage.PARTIAL;
        else if (coverage == Evidence.Coverage.COMPLETE && records.stream()
                .anyMatch(record -> record.source().coverage() == Evidence.Coverage.OPAQUE)) coverage = Evidence.Coverage.OPAQUE;
        var next = scanned < entries.size() ? Integer.toString(start + scanned)
                : scanCapped ? Integer.toString(start + entries.size()) : null;
        return result(records, coverage, next, snapshot.failure());
    }

    private static Evidence.Record toRecord(EntryRecord entry, Snapshot snapshot, List<Integer> targetPages,
                                            long lifecycleGeneration) {
        var opaque = entry.pages().stream().anyMatch(page -> page.decoding() == Evidence.DecodingStatus.OPAQUE);
        var gated = entry.pages().stream().anyMatch(page -> !page.visible());
        var payloadValues = new java.util.LinkedHashMap<String, Evidence.JsonValue>();
        payloadValues.put("bookId", new Evidence.JsonString(entry.bookId()));
        payloadValues.put("entryId", new Evidence.JsonString(entry.id()));
        payloadValues.put("visible", new Evidence.JsonBoolean(entry.visible()));
        payloadValues.put("locked", new Evidence.JsonBoolean(entry.locked()));
        payloadValues.put("secret", new Evidence.JsonBoolean(entry.secret()));
        payloadValues.put("hidden", new Evidence.JsonBoolean(entry.hidden()));
        payloadValues.put("recipeMappings", new Evidence.JsonArray(entry.recipeMappings().stream().map(Evidence.JsonString::new).map(value -> (Evidence.JsonValue) value).toList()));
        payloadValues.put("provenance", new Evidence.JsonArray(entry.provenance().stream().map(Evidence.JsonString::new).map(value -> (Evidence.JsonValue) value).toList()));
        payloadValues.put("coverage", new Evidence.JsonString(entry.coverage().name()));
        payloadValues.put("failure", entry.failure() == null ? new Evidence.JsonNull() : new Evidence.JsonString(entry.failure()));
        payloadValues.put("pages", new Evidence.JsonArray(entry.pages().stream().map(PatchouliProvider::pagePayload)
                .map(value -> (Evidence.JsonValue) value).toList()));
        var coverage = entry.coverage();
        if (coverage == Evidence.Coverage.COMPLETE && gated) coverage = Evidence.Coverage.PARTIAL;
        if (coverage == Evidence.Coverage.COMPLETE && opaque) coverage = Evidence.Coverage.OPAQUE;
        var sourceHandle = entry.bookId() + "#" + entry.id();
        var provenance = new ArrayList<String>();
        provenance.add("patchouli:loaded-book:" + entry.bookId());
        provenance.add("patchouli:loaded-entry:" + entry.id());
        provenance.addAll(entry.provenance());
        var source = new Evidence.Source(snapshot.scope(), ID, VERSION, sourceHandle, snapshot.generation(), Instant.now(),
                Evidence.Activity.ACTIVE, coverage, Evidence.Authority.INSTALLED_DOCUMENTATION,
                provenance);
        var targets = targetPages.stream().filter(index -> entry.pages().stream().anyMatch(page -> page.index() == index))
                .map(index -> {
                    var pageHandle = sourceHandle + "#" + index;
                    return new Evidence.NavigationTarget(snapshot.scope(), ID,
                            sourceHandle, pageHandle + "@lifecycle=" + lifecycleGeneration);
                }).toList();
        return new Evidence.Record(source, Evidence.RecordKind.GUIDE, entry.title(), new Evidence.JsonObject(payloadValues), targets);
    }

    private static Evidence.JsonObject pagePayload(PageRecord page) {
        var values = new java.util.LinkedHashMap<String, Evidence.JsonValue>();
        values.put("index", new Evidence.JsonNumber(BigDecimal.valueOf(page.index())));
        values.put("type", new Evidence.JsonString(page.type()));
        values.put("visible", new Evidence.JsonBoolean(page.visible()));
        values.put("decoding", new Evidence.JsonString(page.decoding().name()));
        if (page.visible()) {
            values.put("source", page.payload());
            values.put("text", page.text() == null ? new Evidence.JsonNull() : new Evidence.JsonString(page.text()));
            values.put("itemLinks", new Evidence.JsonArray(page.itemLinks().stream().map(Evidence.JsonString::new)
                    .map(value -> (Evidence.JsonValue) value).toList()));
            values.put("recipeLinks", new Evidence.JsonArray(page.recipeLinks().stream().map(Evidence.JsonString::new)
                    .map(value -> (Evidence.JsonValue) value).toList()));
        } else {
            values.put("source", new Evidence.JsonObject(Map.of()));
            values.put("text", new Evidence.JsonNull());
            values.put("itemLinks", new Evidence.JsonArray(List.of()));
            values.put("recipeLinks", new Evidence.JsonArray(List.of()));
        }
        return new Evidence.JsonObject(values);
    }

    private static boolean matches(EntryRecord entry, String text) {
        return text.isEmpty() || entry.id().toLowerCase(Locale.ROOT).contains(text)
                || entry.bookId().toLowerCase(Locale.ROOT).contains(text)
                || entry.title().toLowerCase(Locale.ROOT).contains(text)
                || entry.pages().stream().anyMatch(page -> page.visible() && page.text() != null
                && page.text().toLowerCase(Locale.ROOT).contains(text));
    }

    private static List<Integer> matchingPages(EntryRecord entry, Evidence.Operation operation, String text) {
        var pages = new ArrayList<Integer>();
        for (var page : entry.pages()) {
            if (!page.visible()) continue;
            if (operation != Evidence.Operation.SEARCH || text.isEmpty()
                    || page.text() != null && page.text().toLowerCase(Locale.ROOT).contains(text)) pages.add(page.index());
        }
        if (pages.isEmpty() && operation == Evidence.Operation.SEARCH && (entry.id().toLowerCase(Locale.ROOT).contains(text)
                || entry.bookId().toLowerCase(Locale.ROOT).contains(text)
                || entry.title().toLowerCase(Locale.ROOT).contains(text))) {
            for (var page : entry.pages()) if (page.visible()) pages.add(page.index());
        }
        return pages;
    }

    private static String stringArgument(Evidence.JsonObject arguments, String key) {
        var value = arguments.values().get(key);
        return value instanceof Evidence.JsonString string ? string.value() : null;
    }

    private static int parseCursor(String cursor) {
        try { return Integer.parseInt(cursor); } catch (NumberFormatException ignored) { return -1; }
    }

    private static Evidence.Result result(List<Evidence.Record> records, Evidence.Coverage coverage, String next,
                                          String detail) {
        var outcome = coverage == Evidence.Coverage.UNAVAILABLE ? Evidence.Outcome.UNKNOWN
                : coverage == Evidence.Coverage.ERROR ? Evidence.Outcome.ERROR
                : coverage == Evidence.Coverage.COMPLETE ? Evidence.Outcome.COMPLETE : Evidence.Outcome.PARTIAL;
        return new Evidence.Result(records, coverage, outcome, next, detail);
    }

    private static Evidence.Result unavailableResult() {
        return new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null);
    }

    private static Evidence.Result errorResult() {
        return new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null,
                "Patchouli query arguments are invalid");
    }

    private boolean scopeIsCurrent(Evidence.Scope snapshotScope) {
        if (scopeSource == null) return true;
        try {
            return sameScope(snapshotScope, scopeSource.get());
        } catch (RuntimeException scopeFailure) {
            return false;
        }
    }

    private static boolean sameScope(Evidence.Scope left, Evidence.Scope right) {
        return left.worldSession().equals(right.worldSession()) && left.resourcesLocale().equals(right.resourcesLocale())
                && left.playerTeam().equals(right.playerTeam()) && left.providerRuntime().equals(right.providerRuntime())
                && left.requestGeneration() == right.requestGeneration();
    }
}
