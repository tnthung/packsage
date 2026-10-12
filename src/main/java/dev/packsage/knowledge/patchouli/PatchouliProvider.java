package dev.packsage.knowledge.patchouli;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import net.minecraft.resources.ResourceLocation;
import vazkii.patchouli.api.PatchouliAPI;
import vazkii.patchouli.client.book.BookContents;
import vazkii.patchouli.client.book.BookEntry;
import vazkii.patchouli.client.book.BookPage;
import vazkii.patchouli.client.book.page.PageText;
import vazkii.patchouli.client.book.page.abstr.PageWithText;
import vazkii.patchouli.common.book.Book;
import vazkii.patchouli.common.book.BookRegistry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
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
                              List<String> recipeMappings, List<String> provenance) {
        public EntryRecord {
            Objects.requireNonNull(bookId);
            Objects.requireNonNull(id);
            Objects.requireNonNull(title);
            pages = List.copyOf(pages);
            recipeMappings = List.copyOf(recipeMappings);
            provenance = List.copyOf(provenance);
        }

        public EntryRecord(String bookId, String id, String title, List<PageRecord> pages,
                           boolean visible, boolean locked, boolean secret, boolean hidden,
                           List<String> recipeMappings) {
            this(bookId, id, title, pages, visible, locked, secret, hidden, recipeMappings, List.of());
        }
    }

    public record BookRecord(String id, String title, List<EntryRecord> entries, List<String> provenance) {
        public BookRecord {
            Objects.requireNonNull(id);
            Objects.requireNonNull(title);
            entries = List.copyOf(entries);
            provenance = List.copyOf(provenance);
        }
    }

    public record Navigation(String bookId, String entryId, int page) {
        public Navigation {
            Objects.requireNonNull(bookId);
            Objects.requireNonNull(entryId);
            if (page < 0) throw new IllegalArgumentException("page must not be negative");
        }
    }

    public record Snapshot(Evidence.Scope scope, long generation, List<BookRecord> books,
                           Consumer<Navigation> navigator) {
        public Snapshot {
            Objects.requireNonNull(scope);
            books = List.copyOf(books);
            Objects.requireNonNull(navigator);
        }

        /** Captures the currently loaded Patchouli registry on its owning client thread. */
        public static Snapshot capture(Evidence.Scope scope) {
            var books = new ArrayList<BookRecord>();
            for (var book : BookRegistry.INSTANCE.books.values()) {
                books.add(captureBook(book));
            }
            return new Snapshot(scope, scope.requestGeneration(), books, navigation ->
                    PatchouliAPI.get().openBookEntry(ResourceLocation.parse(navigation.bookId()),
                            ResourceLocation.parse(navigation.entryId()), navigation.page()));
        }

        private static BookRecord captureBook(Book book) {
            var entries = new ArrayList<EntryRecord>();
            try {
                BookContents contents = book.getContents();
                for (var entry : contents.entries.values()) entries.add(captureEntry(book, entry));
            } catch (RuntimeException ignored) {
                // The loaded registry can contain a book whose contents failed to reload. Keep its identity.
            }
            var provenance = new ArrayList<String>();
            provenance.add("patchouli:book:" + book.id);
            if (book.owner != null) provenance.add("patchouli:owner:" + book.owner.getId());
            return new BookRecord(book.id.toString(), book.name, entries, provenance);
        }

        private static EntryRecord captureEntry(Book book, BookEntry entry) {
            var pages = new ArrayList<PageRecord>();
            var index = 0;
            for (var page : entry.getPages()) pages.add(capturePage(page, index++));
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
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // An inaccessible implementation detail is partial evidence, never a reason to fail the provider.
            }
            var provenance = entry.getAddedBy().isBlank() ? List.<String>of()
                    : List.of("patchouli:resource:" + entry.getAddedBy());
            return new EntryRecord(book.id.toString(), entry.getId().toString(), entry.getName().getString(), pages,
                    !entry.shouldHide(), entry.isLocked(), entry.isSecret(), entry.shouldHide(), mappings, provenance);
        }

        private static PageRecord capturePage(BookPage page, int index) {
            var source = page.sourceObject == null ? new JsonObject() : page.sourceObject.deepCopy();
            var type = source.has("type") ? source.get("type").getAsString() : page.getClass().getSimpleName();
            var text = page instanceof PageWithText && source.has("text") ? page.i18n(source.get("text").getAsString()) : null;
            var items = values(source, "item", "items");
            var recipes = values(source, "recipe", "recipes", "recipe_id", "recipeId");
            var supported = page instanceof PageText || page instanceof PageWithText;
            return new PageRecord(index, type, fromGson(source), text, items, recipes,
                    page.isPageUnlocked(), supported ? Evidence.DecodingStatus.VERIFIED : Evidence.DecodingStatus.OPAQUE);
        }

        private static List<String> values(JsonObject source, String... keys) {
            var result = new ArrayList<String>();
            for (var key : keys) {
                var value = source.get(key);
                if (value == null || value.isJsonNull()) continue;
                if (value.isJsonArray()) value.getAsJsonArray().forEach(item -> result.add(item.toString()));
                else result.add(value.toString());
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

    private final Supplier<Snapshot> snapshots;
    private final Executor clientExecutor;
    private volatile Snapshot current;

    public PatchouliProvider(Supplier<Snapshot> snapshots, Executor clientExecutor) {
        this.snapshots = Objects.requireNonNull(snapshots);
        this.clientExecutor = Objects.requireNonNull(clientExecutor);
    }

    public static PatchouliProvider forMinecraft(Supplier<Evidence.Scope> scopeSource, Executor clientExecutor) {
        Objects.requireNonNull(scopeSource);
        return new PatchouliProvider(() -> Snapshot.capture(scopeSource.get()), clientExecutor);
    }

    public static boolean isRuntimeAvailable() {
        try {
            Class.forName("vazkii.patchouli.common.book.BookRegistry", false, PatchouliProvider.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    @Override
    public String id() { return ID; }

    @Override
    public ProviderStatus status() {
        var snapshot = current;
        if (snapshot == null) return new ProviderStatus(Availability.UNAVAILABLE, VERSION, "Patchouli runtime unavailable");
        var opaque = snapshot.books().stream().flatMap(book -> book.entries().stream())
                .flatMap(entry -> entry.pages().stream()).anyMatch(page -> page.decoding() == Evidence.DecodingStatus.OPAQUE);
        return new ProviderStatus(opaque ? Availability.PARTIAL : Availability.AVAILABLE, VERSION,
                "loaded Patchouli books generation " + snapshot.generation());
    }

    public void refresh() { current = snapshots.get(); }

    public void unavailable() { current = null; }

    @Override
    public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
        return CompletableFuture.supplyAsync(snapshots, clientExecutor).thenApply(snapshot -> {
            current = snapshot;
            if (!sameScope(snapshot.scope(), scope) || query.cursor() != null && query.cursorScope() != null
                    && !sameScope(query.cursorScope(), scope)) {
                return new Evidence.Result(List.of(), Evidence.Coverage.PARTIAL, Evidence.Outcome.UNKNOWN, null);
            }
            var start = query.cursor() == null ? 0 : parseCursor(query.cursor());
            if (start < 0) return new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null);
            var text = stringArgument(query.arguments(), "text").toLowerCase(Locale.ROOT);
            var id = stringArgument(query.arguments(), "id");
            var entries = snapshot.books().stream().flatMap(book -> book.entries().stream()).toList();
            var records = new ArrayList<Evidence.Record>();
            for (var index = start; index < entries.size() && records.size() < query.limit(); index++) {
                var entry = entries.get(index);
                if (query.operation() == Evidence.Operation.GET && id != null && !id.equals(entry.id())
                        && !id.equals(entry.bookId() + "#" + entry.id())) continue;
                if (query.operation() == Evidence.Operation.SEARCH && !matches(entry, text)) continue;
                records.add(toRecord(entry, snapshot));
            }
            var next = start + records.size() < entries.size() ? Integer.toString(start + records.size()) : null;
            var partial = records.stream().anyMatch(record -> record.source().coverage() != Evidence.Coverage.COMPLETE);
            return new Evidence.Result(records, partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                    partial ? Evidence.Outcome.PARTIAL : Evidence.Outcome.COMPLETE, next);
        });
    }

    @Override
    public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
        var snapshot = current;
        if (snapshot == null) return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "Patchouli runtime unavailable");
        if (!ID.equals(target.providerId()) || !sameScope(snapshot.scope(), scope) || !sameScope(target.scope(), scope))
            return new Evidence.NavigationResult(Evidence.NavigationStatus.STALE, "Patchouli target is outside the active runtime scope");
        var parts = target.targetId().split("#", 2);
        if (parts.length != 2 || snapshot.books().stream().noneMatch(book -> book.id().equals(parts[0])
                && book.entries().stream().anyMatch(entry -> entry.id().equals(parts[1]) && entry.visible())))
            return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "Patchouli entry is unavailable or hidden");
        var page = parsePage(target.sourceHandle());
        try {
            snapshot.navigator().accept(new Navigation(parts[0], parts[1], page));
            return new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED, "Patchouli entry opened");
        } catch (RuntimeException failure) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.ERROR, failure.toString());
        }
    }

    private static Evidence.Record toRecord(EntryRecord entry, Snapshot snapshot) {
        var opaque = entry.pages().stream().anyMatch(page -> page.decoding() == Evidence.DecodingStatus.OPAQUE);
        var payloadValues = new java.util.LinkedHashMap<String, Evidence.JsonValue>();
        payloadValues.put("bookId", new Evidence.JsonString(entry.bookId()));
        payloadValues.put("entryId", new Evidence.JsonString(entry.id()));
        payloadValues.put("visible", new Evidence.JsonBoolean(entry.visible()));
        payloadValues.put("locked", new Evidence.JsonBoolean(entry.locked()));
        payloadValues.put("secret", new Evidence.JsonBoolean(entry.secret()));
        payloadValues.put("hidden", new Evidence.JsonBoolean(entry.hidden()));
        payloadValues.put("recipeMappings", new Evidence.JsonArray(entry.recipeMappings().stream().map(Evidence.JsonString::new).map(value -> (Evidence.JsonValue) value).toList()));
        payloadValues.put("provenance", new Evidence.JsonArray(entry.provenance().stream().map(Evidence.JsonString::new).map(value -> (Evidence.JsonValue) value).toList()));
        payloadValues.put("pages", new Evidence.JsonArray(entry.pages().stream().map(page -> page.payload()).map(value -> (Evidence.JsonValue) value).toList()));
        var coverage = opaque ? Evidence.Coverage.OPAQUE : Evidence.Coverage.COMPLETE;
        var sourceHandle = entry.bookId() + "#" + entry.id();
        var provenance = new ArrayList<String>();
        provenance.add("patchouli:loaded-book:" + entry.bookId());
        provenance.add("patchouli:loaded-entry:" + entry.id());
        provenance.addAll(entry.provenance());
        var source = new Evidence.Source(snapshot.scope(), ID, VERSION, sourceHandle, snapshot.generation(), Instant.now(),
                Evidence.Activity.ACTIVE, coverage, Evidence.Authority.INSTALLED_DOCUMENTATION,
                provenance);
        var target = new Evidence.NavigationTarget(snapshot.scope(), ID, sourceHandle + "#0", sourceHandle);
        return new Evidence.Record(source, Evidence.RecordKind.GUIDE, entry.title(), new Evidence.JsonObject(payloadValues), List.of(target));
    }

    private static boolean matches(EntryRecord entry, String text) {
        return text.isEmpty() || entry.id().toLowerCase(Locale.ROOT).contains(text)
                || entry.bookId().toLowerCase(Locale.ROOT).contains(text)
                || entry.title().toLowerCase(Locale.ROOT).contains(text)
                || entry.pages().stream().anyMatch(page -> page.text() != null && page.text().toLowerCase(Locale.ROOT).contains(text));
    }

    private static String stringArgument(Evidence.JsonObject arguments, String key) {
        var value = arguments.values().get(key);
        return value instanceof Evidence.JsonString string ? string.value() : "";
    }

    private static int parseCursor(String cursor) {
        try { return Integer.parseInt(cursor); } catch (NumberFormatException ignored) { return -1; }
    }

    private static int parsePage(String sourceHandle) {
        var marker = sourceHandle.lastIndexOf('#');
        if (marker < 0) return 0;
        try { return Integer.parseInt(sourceHandle.substring(marker + 1)); } catch (NumberFormatException ignored) { return 0; }
    }

    private static boolean sameScope(Evidence.Scope left, Evidence.Scope right) {
        return left.worldSession().equals(right.worldSession()) && left.resourcesLocale().equals(right.resourcesLocale())
                && left.playerTeam().equals(right.playerTeam()) && left.providerRuntime().equals(right.providerRuntime())
                && left.requestGeneration() == right.requestGeneration();
    }
}
