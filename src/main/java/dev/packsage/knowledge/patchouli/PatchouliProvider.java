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

/** Fresh, bounded evidence from client-visible loaded Patchouli books. */
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

    private record LiveCursor(int offset, String owner, int ownerToken, boolean after,
                              PatchouliLiveReader.Position position) { }
    private record DocumentCursor(String query, Evidence.Scope scope, String position) { }

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
    private final Supplier<Map<ResourceLocation, Book>> liveBooks;
    private final Map<String, DocumentCursor> documentCursors = new java.util.LinkedHashMap<>();
    private volatile Evidence.Scope lastScope;
    private volatile Evidence.Coverage latestCoverage = Evidence.Coverage.UNAVAILABLE;
    private volatile boolean opaque;
    private volatile long lifecycleGeneration;
    private volatile long currentLifecycleGeneration = -1;
    private volatile String failure;
    private volatile boolean unavailable;

    public PatchouliProvider(Supplier<Snapshot> snapshots, Executor clientExecutor) {
        this(snapshots, clientExecutor, null, () -> false);
    }

    private PatchouliProvider(Supplier<Snapshot> snapshots, Executor clientExecutor,
                              Supplier<Evidence.Scope> scopeSource, BooleanSupplier owningThread) {
        this(snapshots, clientExecutor, scopeSource, owningThread, () -> BookRegistry.INSTANCE.books);
    }

    PatchouliProvider(Supplier<Snapshot> snapshots, Executor clientExecutor,
                      Supplier<Evidence.Scope> scopeSource, BooleanSupplier owningThread,
                      Supplier<Map<ResourceLocation, Book>> liveBooks) {
        this.snapshots = Objects.requireNonNull(snapshots);
        this.clientExecutor = Objects.requireNonNull(clientExecutor);
        this.scopeSource = scopeSource;
        this.owningThread = Objects.requireNonNull(owningThread);
        this.liveBooks = Objects.requireNonNull(liveBooks);
    }

    public static PatchouliProvider forMinecraft(Supplier<Evidence.Scope> scopeSource, Executor clientExecutor) {
        Objects.requireNonNull(scopeSource);
        return new PatchouliProvider(() -> null, clientExecutor, scopeSource,
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
        if (failure != null) return new ProviderStatus(Availability.ERROR, VERSION, failure);
        if (unavailable || scopeSource == null && lastScope == null)
            return new ProviderStatus(Availability.UNAVAILABLE, VERSION, "Patchouli runtime unavailable");
        if (scopeSource != null) return new ProviderStatus(Availability.AVAILABLE, VERSION, "loaded Patchouli registry is available");
        if (currentLifecycleGeneration != lifecycleGeneration)
            return new ProviderStatus(Availability.PARTIAL, VERSION, "Patchouli observation was invalidated");
        if (latestCoverage == Evidence.Coverage.ERROR) return new ProviderStatus(Availability.ERROR, VERSION, "current guide extraction failed");
        return new ProviderStatus(latestCoverage != Evidence.Coverage.COMPLETE || opaque ? Availability.PARTIAL : Availability.AVAILABLE,
                VERSION, "current loaded guide status");
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
        if (scopeSource != null) return searchLiveDocument(request, context, query.value(), sourceCursor, limit, null);
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
            return searchLiveDocument(request, context, "", null, 1, id).thenApply(result -> {
                var record = result.records().stream().filter(value -> value.source().sourceHandle().equals(id))
                        .findFirst().orElseThrow(() -> new Toolbox.Failure(Toolbox.Code.UNAVAILABLE,
                                "the observed guide entry is no longer available"));
                return new Evidence.Result(List.of(record), record.source().coverage(), result.outcome(), null, result.detail());
            });
        }
        return query(new Evidence.Query(Evidence.Operation.GET, new Evidence.JsonObject(Map.of(
                "id", new Evidence.JsonString(id))), 1, null, null), request.scope())
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
                                                                 String query, String cursor, int limit, String exactHandle) {
        long generation = lifecycleGeneration;
        LiveCursor resume = null;
        if (cursor != null) {
            try {
                var value = com.google.gson.JsonParser.parseString(cursor).getAsJsonObject();
                if (value.get("lifecycle").getAsLong() != generation) throw new IllegalArgumentException();
                resume = new LiveCursor(value.get("offset").getAsInt(), value.get("owner").getAsString(),
                        value.get("ownerToken").getAsInt(), value.get("after").getAsBoolean(),
                        new PatchouliLiveReader.Position(value.get("page").getAsInt(), value.get("node").getAsInt(),
                                value.get("textOffset").getAsInt(), value.get("mapping").getAsInt()));
                if (resume.offset() < 0 || resume.position().page() < 0 || resume.position().node() < 0
                        || resume.position().textOffset() < 0 || resume.position().mapping() < 0) throw new IllegalArgumentException();
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                        "Patchouli continuation is invalid or its loaded registry was reloaded"));
            }
        }
        var selected = resume;
        return CompletableFuture.supplyAsync(() -> {
            if (unavailable) throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "Patchouli runtime is unavailable");
            if (generation != lifecycleGeneration) throw new Toolbox.Failure(Toolbox.Code.STALE_SCOPE, "Patchouli reloaded before the live read");
            if (!owningThread.getAsBoolean()) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                    "Patchouli live traversal must run on the client thread");
            if (!request.scope().equals(scopeSource.get()) || !request.scope().equals(context.currentScope().get()))
                throw new Toolbox.Failure(Toolbox.Code.STALE_SCOPE, "repeat the lookup in the current scope");
            var books = liveBooks.get();
            var records = new ArrayList<Evidence.Record>();
            LiveCursor next = null;
            LiveCursor lastVisited = null;
            boolean partial = false;
            int index = 0;
            var bookIterator = books.values().iterator();
            if (exactHandle != null) {
                var parts = exactHandle.split("#", -1);
                if (parts.length != 2) throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE, "guide reference is not book-qualified");
                var key = ResourceLocation.tryParse(parts[0]);
                var book = key == null ? null : books.get(key);
                if (book == null) throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "the loaded guide book is unavailable");
                bookIterator = List.of(book).iterator();
            }
            outer: while (bookIterator.hasNext()) {
                if (!context.documentBudget().value()) {
                    if (selected != null && (lastVisited == null || lastVisited.offset() <= selected.offset()))
                        throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "Patchouli book seek exceeds the bounded continuation range");
                    if (lastVisited != null) { next = lastVisited; partial = true; break; }
                    throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "Patchouli book seek exceeds the shared read budget");
                }
                var book = bookIterator.next();
                var contents = book.getContents();
                if (contents == null || contents.isErrored()) { partial = true; continue; }
                var iterator = contents.entries.values().iterator();
                if (exactHandle != null) {
                    var entryKey = ResourceLocation.tryParse(exactHandle.substring(exactHandle.indexOf('#') + 1));
                    var entry = entryKey == null ? null : contents.entries.get(entryKey);
                    if (entry == null) throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "the loaded guide entry is unavailable");
                    iterator = List.of(entry).iterator();
                }
                while (iterator.hasNext()) {
                    boolean seeking = selected != null && (index < selected.offset() || index == selected.offset() && selected.after());
                    if (!context.documentBudget().value() || !seeking && !context.documentBudget().document()) {
                        if (seeking) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "Patchouli entry seek exceeds the bounded continuation range");
                        if (lastVisited != null) { next = lastVisited; partial = true; break outer; }
                        throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "Patchouli entry seek exceeds the shared read budget");
                    }
                    var entry = iterator.next();
                    String owner = book.id + "#" + entry.getId();
                    int token = System.identityHashCode(entry);
                    int candidate = index++;
                    lastVisited = new LiveCursor(candidate, owner, token, true, new PatchouliLiveReader.Position(0, 0, 0, 0));
                    if (selected != null && candidate < selected.offset()) continue;
                    if (selected != null && candidate == selected.offset()) {
                        if (!owner.equals(selected.owner()) || token != selected.ownerToken()) throw new Toolbox.Failure(
                                Toolbox.Code.INVALID_CURSOR, "Patchouli continuation owner changed; restart the search");
                        if (selected.after()) continue;
                    }
                    if (entry.shouldHide() || entry.isLocked()) {
                        if (exactHandle != null) throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "the observed guide is hidden or locked");
                        continue;
                    }
                    if (context.documentBudget().remainingBytes() < 4096) {
                        next = new LiveCursor(candidate, owner, token, false, new PatchouliLiveReader.Position(0, 0, 0, 0));
                        partial = true;
                        break outer;
                    }
                    var fragment = PatchouliLiveReader.entry(book, entry,
                            selected != null && candidate == selected.offset() && !selected.after() ? selected.position() : null,
                            query.length() * 2,
                            context.documentBudget());
                    var found = fragment.record();
                    partial |= found.coverage() != Evidence.Coverage.COMPLETE;
                    if (exactHandle != null || matches(found, query.toLowerCase(Locale.ROOT))
                            || found.pages().stream().anyMatch(page -> Evidence.serialize(page.payload()).toLowerCase(Locale.ROOT)
                            .contains(query.toLowerCase(Locale.ROOT)))) {
                        var observation = new Snapshot(request.scope(), request.scope().requestGeneration(), List.of(), ignored -> { });
                        records.add(toRecord(found, observation, matchingPages(found, Evidence.Operation.SEARCH,
                                query.toLowerCase(Locale.ROOT))));
                    }
                    if (fragment.next() != null) {
                        next = new LiveCursor(candidate, owner, token, false, fragment.next());
                        partial = true;
                        break outer;
                    }
                    if (records.size() >= limit) {
                        if (iterator.hasNext() || bookIterator.hasNext()) next = new LiveCursor(candidate, owner, token, true,
                                new PatchouliLiveReader.Position(0, 0, 0, 0));
                        break outer;
                    }
                }
            }
            if (selected != null && index <= selected.offset()) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                    "Patchouli continuation owner is no longer present");
            if (generation != lifecycleGeneration || !request.scope().equals(context.currentScope().get()) || !request.scope().equals(scopeSource.get()))
                throw new Toolbox.Failure(Toolbox.Code.STALE_SCOPE, "scope changed while reading Patchouli");
            String continuation = null;
            if (next != null && exactHandle == null) {
                var value = new JsonObject();
                value.addProperty("lifecycle", generation);
                value.addProperty("offset", next.offset());
                value.addProperty("owner", next.owner());
                value.addProperty("ownerToken", next.ownerToken());
                value.addProperty("after", next.after());
                value.addProperty("page", next.position().page());
                value.addProperty("node", next.position().node());
                value.addProperty("textOffset", next.position().textOffset());
                value.addProperty("mapping", next.position().mapping());
                continuation = encodeDocumentCursor(query, value.toString(), request.scope());
            }
            return result(records, partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE, continuation,
                    next != null || partial ? "bounded current guide evidence; narrow the query or continue discovery" : null);
        }, clientExecutor);
    }

    private String encodeDocumentCursor(String query, String position, Evidence.Scope scope) {
        var token = "patchouli:" + java.util.UUID.randomUUID();
        synchronized (documentCursors) {
            documentCursors.put(token, new DocumentCursor(query, scope, position));
            while (documentCursors.size() > 128) documentCursors.remove(documentCursors.keySet().iterator().next());
        }
        return token;
    }

    private String decodeDocumentCursor(String cursor, String query, Evidence.Scope scope) {
        if (cursor == null) return null;
        synchronized (documentCursors) {
            var saved = documentCursors.get(cursor);
            if (saved == null || !query.equals(saved.query()) || !scope.equals(saved.scope())) throw new Toolbox.Failure(
                    Toolbox.Code.INVALID_CURSOR, "Patchouli cursor is invalid for this query or scope; restart the search");
            return saved.position();
        }
    }

    public void refresh() {
        unavailable = false;
        failure = null;
        var generation = ++lifecycleGeneration;
        if (scopeSource != null) {
            lastScope = null;
            currentLifecycleGeneration = -1;
            return;
        }
        try {
            clientExecutor.execute(() -> {
                if (generation != lifecycleGeneration || unavailable) return;
                try {
                    var snapshot = snapshots.get();
                    if (generation != lifecycleGeneration || unavailable) return;
                    if (snapshot == null) {
                        lastScope = null;
                        currentLifecycleGeneration = -1;
                        failure = "Patchouli runtime unavailable";
                    } else if (!scopeIsCurrent(snapshot.scope())) {
                        lastScope = null;
                        currentLifecycleGeneration = -1;
                        failure = "Patchouli snapshot belongs to an inactive scope";
                    } else {
                        lastScope = snapshot.scope();
                        latestCoverage = snapshot.coverage();
                        opaque = snapshot.books().stream().flatMap(book -> book.entries().stream()).flatMap(entry -> entry.pages().stream())
                                .anyMatch(page -> page.decoding() == Evidence.DecodingStatus.OPAQUE);
                        currentLifecycleGeneration = generation;
                        failure = snapshot.coverage() == Evidence.Coverage.ERROR ? snapshot.failure() : null;
                    }
                } catch (RuntimeException | LinkageError captureFailure) {
                    lastScope = null;
                    currentLifecycleGeneration = -1;
                    failure = "Patchouli capture failed: " + captureFailure;
                }
            });
        } catch (RuntimeException schedulingFailure) {
            lastScope = null;
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
        lastScope = null;
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
        if (scopeSource != null) {
            var context = new Toolbox.Context(scopeSource, Map.of(), new dev.packsage.knowledge.AdapterRegistry(),
                    new Toolbox.DocumentBudget(), null);
            var request = new PackSageTools.ReadRequest("search_document", query.arguments(), scope);
            return searchLiveDocument(request, context, query.operation() == Evidence.Operation.SEARCH
                    ? stringArgument(query.arguments(), "text") : "", query.cursor(), query.limit(),
                    query.operation() == Evidence.Operation.GET ? stringArgument(query.arguments(), "id") : null);
        }
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
                    lastScope = null;
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
                if (scopeSource == null) {
                    lastScope = snapshot.scope();
                    latestCoverage = snapshot.coverage();
                    opaque = snapshot.books().stream().flatMap(book -> book.entries().stream()).flatMap(entry -> entry.pages().stream())
                            .anyMatch(page -> page.decoding() == Evidence.DecodingStatus.OPAQUE);
                    currentLifecycleGeneration = generation;
                }
                failure = snapshot.coverage() == Evidence.Coverage.ERROR ? snapshot.failure() : null;
                return querySnapshot(query, snapshot, start);
            });
        } catch (RuntimeException schedulingFailure) {
            failure = "Patchouli client-thread scheduling failed: " + schedulingFailure;
            return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.ERROR,
                    Evidence.Outcome.ERROR, null, failure));
        }
    }

    @Override
    public CompletionStage<Evidence.NavigationTarget> refreshNavigation(Evidence.NavigationTarget target, Evidence.Scope scope) {
        if (!ID.equals(target.providerId()) || unavailable || !scopeIsCurrent(scope)) return CompletableFuture.completedFuture(null);
        var parts = target.targetId().split("#", -1);
        if (parts.length != 3) return CompletableFuture.completedFuture(null);
        for (var id : List.of(parts[0], parts[1])) {
            var parsed = ResourceLocation.tryParse(id);
            if (parsed == null || !parsed.toString().equals(id)) return CompletableFuture.completedFuture(null);
        }
        final int page;
        try {
            var position = parts[2].split("@lifecycle=", -1);
            page = Integer.parseInt(position[0]);
            if (page < 0 || !Integer.toString(page).equals(position[0]) || position.length > 2
                    || position.length == 2 && (Long.parseLong(position[1]) < 0
                    || !Long.toString(Long.parseLong(position[1])).equals(position[1]))) return CompletableFuture.completedFuture(null);
        } catch (RuntimeException malformed) {
            return CompletableFuture.completedFuture(null);
        }
        var generation = lifecycleGeneration;
        return query(new Evidence.Query(Evidence.Operation.GET, new Evidence.JsonObject(Map.of(
                "id", new Evidence.JsonString(parts[0] + "#" + parts[1]))), 1, null, null), scope).thenApply(result -> {
            if (generation != lifecycleGeneration || !scopeIsCurrent(scope) || result == null
                    || result.outcome() == Evidence.Outcome.ERROR || result.records().size() != 1) return null;
            var record = result.records().getFirst();
            if (!record.source().scope().equals(scope) || !ID.equals(record.source().providerId())
                    || !record.source().sourceHandle().equals(parts[0] + "#" + parts[1])) return null;
            return record.targets().stream().filter(value -> value.targetId().equals(parts[0] + "#" + parts[1] + "#" + page))
                    .findFirst().orElse(null);
        });
    }

    @Override
    public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
        Objects.requireNonNull(target);
        Objects.requireNonNull(scope);
        if (unavailable) return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "Patchouli runtime unavailable");
        if (!ID.equals(target.providerId()) || !sameScope(target.scope(), scope) || !scopeIsCurrent(scope))
            return new Evidence.NavigationResult(Evidence.NavigationStatus.STALE, "guide target is outside the active scope");
        var parts = target.targetId().split("#", -1);
        if (parts.length != 3 || !target.sourceHandle().equals(parts[0] + "#" + parts[1]))
            return new Evidence.NavigationResult(Evidence.NavigationStatus.STALE, "guide target identity does not match its source");
        final int page;
        final long generation;
        try {
            var marker = parts[2].indexOf("@lifecycle=");
            page = Integer.parseInt(marker < 0 ? parts[2] : parts[2].substring(0, marker));
            generation = marker < 0 ? lifecycleGeneration : Long.parseLong(parts[2].substring(marker + "@lifecycle=".length()));
            if (page < 0 || generation != lifecycleGeneration || !target.targetId().equals(parts[0] + "#" + parts[1]
                    + "#" + page + (marker < 0 ? "" : "@lifecycle=" + generation))) throw new IllegalArgumentException();
        } catch (RuntimeException malformed) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.STALE, "guide target page or lifecycle is invalid");
        }
        var result = new CompletableFuture<Evidence.NavigationResult>();
        Runnable action = () -> {
            if (unavailable || generation != lifecycleGeneration || !scopeIsCurrent(scope)) {
                result.complete(new Evidence.NavigationResult(Evidence.NavigationStatus.STALE, "guide scope changed before navigation"));
                return;
            }
            try {
                if (scopeSource != null) {
                    var bookKey = ResourceLocation.tryParse(parts[0]);
                    var book = bookKey == null ? null : liveBooks.get().get(bookKey);
                    var contents = book == null ? null : book.getContents();
                    var entryKey = ResourceLocation.tryParse(parts[1]);
                    var entry = contents == null || contents.isErrored() || entryKey == null ? null : contents.entries.get(entryKey);
                    if (entry == null || entry.shouldHide() || entry.isLocked()) {
                        result.complete(new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "guide entry is currently unavailable or hidden"));
                        return;
                    }
                    var field = BookEntry.class.getDeclaredField("realPages");
                    field.setAccessible(true);
                    var loadedPages = (List<?>) field.get(entry);
                    int displayIndex = 0;
                    boolean found = false;
                    var budget = new Toolbox.DocumentBudget();
                    for (int index = 0; index < loadedPages.size() && budget.value(); index++) {
                        var loadedPage = (BookPage) loadedPages.get(index);
                        if (book.advancementsEnabled() && !loadedPage.isPageUnlocked()) continue;
                        if (displayIndex++ == page) { found = loadedPage.isPageUnlocked(); break; }
                    }
                    if (!found) {
                        result.complete(new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "guide page is locked, missing or beyond the navigation bound"));
                        return;
                    }
                    PatchouliAPI.get().openBookEntry(bookKey, entryKey, page);
                } else {
                    var observation = snapshots.get();
                    if (observation == null || !sameScope(observation.scope(), scope)) {
                        result.complete(new Evidence.NavigationResult(Evidence.NavigationStatus.STALE, "guide runtime is unavailable or changed scope"));
                        return;
                    }
                    var entry = observation.books().stream().filter(book -> book.id().equals(parts[0])).flatMap(book -> book.entries().stream())
                            .filter(value -> value.id().equals(parts[1])).findFirst().orElse(null);
                    var currentPage = entry == null ? null : entry.pages().stream().filter(value -> value.index() == page).findFirst().orElse(null);
                    if (entry == null || !entry.visible() || entry.locked() || entry.hidden() || currentPage == null || !currentPage.visible()) {
                        result.complete(new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "guide entry or page is currently unavailable"));
                        return;
                    }
                    if (entry.coverage() == Evidence.Coverage.ERROR) {
                        result.complete(new Evidence.NavigationResult(Evidence.NavigationStatus.UNSUPPORTED, "current guide extraction failed"));
                        return;
                    }
                    observation.navigator().accept(new Navigation(parts[0], parts[1], page));
                }
                result.complete(new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED, "current guide entry opened"));
            } catch (RuntimeException | ReflectiveOperationException navigationFailure) {
                result.complete(new Evidence.NavigationResult(Evidence.NavigationStatus.ERROR, navigationFailure.toString()));
            }
        };
        if (owningThread.getAsBoolean()) action.run();
        else clientExecutor.execute(action);
        try {
            return result.get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.ERROR, "Patchouli client thread did not respond");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Evidence.NavigationResult(Evidence.NavigationStatus.ERROR, "Patchouli navigation was interrupted");
        } catch (java.util.concurrent.ExecutionException failure) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.ERROR, failure.getCause().toString());
        }
    }

    private static Evidence.Result querySnapshot(Evidence.Query query, Snapshot snapshot, int start) {
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
            records.add(toRecord(entry, snapshot, matchingPages(entry, query.operation(), text)));
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

    private static Evidence.Record toRecord(EntryRecord entry, Snapshot snapshot, List<Integer> targetPages) {
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
                .map(index -> new Evidence.NavigationTarget(snapshot.scope(), ID, sourceHandle, sourceHandle + "#" + index)).toList();
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
