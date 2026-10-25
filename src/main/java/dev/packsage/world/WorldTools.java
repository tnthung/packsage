package dev.packsage.world;

import dev.packsage.journal.NoteBook;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.model.PackSageTools;
import dev.packsage.world.minecraft.MinecraftMenus;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoubleBlockCombiner;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The model-facing world tools under provider id {@code world}. Every world read runs on the integrated server thread
 * in {@link ServerWork} slices; without an integrated server every tool fails {@code UNAVAILABLE}. Cursors are opaque
 * tokens bound to the tool and its arguments, kept in a 256-entry LRU, that map to the slices' own continuations.
 */
public final class WorldTools implements KnowledgeProvider {
    public static final String ID = "world";
    static final String VERSION = "1";
    static final int DEFAULT_RADIUS = 16;
    static final int MAX_RADIUS = 256;
    static final int CURSORS = 256;
    private static final long TIMEOUT_SECONDS = 60;
    private static final int RESOLVE_PER_STEP = 64;

    private record Cursor(String tool, Evidence.JsonObject signature, Evidence.Scope scope, Object state) {
    }

    /** A resolved scan center, kept in list cursors so later pages scan the same sphere. */
    record Center(String dimension, Vec3 position) {
    }

    private record ListState(Center center, String scan) {
    }

    /**
     * Remaining roots of a find_items walk; ordinal resumes inside the first one. Gather is null once every chunk in
     * range was gathered, or the chunks still to gather holders from after these roots.
     */
    record FindState(List<PathRoot> roots, int ordinal, Gather gather) {
    }

    /** The find_items sphere and the index of the next chunk in range to gather holders from. */
    record Gather(String dimension, Vec3 center, int nextChunk) {
    }

    /**
     * Entries not shown yet, then the search continuation (null when the search is done or for list_items). Coverage
     * and note are sticky: a later page of the same listing or search is never more complete than an earlier one. Span
     * is the game-time span of the read the pending entries came from, or null when unknown.
     */
    record EntriesState(List<ItemEntry> pending, FindState search, Evidence.Coverage coverage, String note, ReadSpan span,
                        HolderSummary summary, Map<String, Evidence.JsonObject> slotFacts) {
        EntriesState(List<ItemEntry> pending, FindState search, Evidence.Coverage coverage, String note, ReadSpan span) {
            this(pending, search, coverage, note, span, null, Map.of());
        }

        EntriesState(List<ItemEntry> pending, FindState search, Evidence.Coverage coverage, String note) {
            this(pending, search, coverage, note, null);
        }
    }

    /**
     * The listed holder and its inventories, shown as the first record of a listing's first page. Facts are the
     * holder's own, such as the player's selected hotbar slot.
     */
    record HolderSummary(PathRoot root, List<InventorySummary> inventories, Evidence.JsonObject facts) {
    }

    /** EmptySlots is null for keyed sections, which have no fixed slots, and for sections that could not be read. */
    record InventorySummary(InventoryInfo info, Integer emptySlots) {
    }

    /** The game times a sliced read started and ended at; the world can change between its slices. */
    record ReadSpan(long fromGameTime, long toGameTime) {
    }

    record Spanned<T>(T value, ReadSpan span) {
    }

    private record AreaRead(Center center, Page<BlockSummary> blocks, Page<EntityHandle> entities) {
    }

    /**
     * Note is sticky for later pages; bound says why only this page was cut. Summary is null for searches and nested
     * holders; slot facts are keyed by location path text.
     */
    private record ItemsRead(List<ItemEntry> entries, FindState search, Evidence.Coverage coverage, String note, String bound,
                             HolderSummary summary, Map<String, Evidence.JsonObject> slotFacts) {
        ItemsRead(List<ItemEntry> entries, FindState search, Evidence.Coverage coverage, String note, String bound) {
            this(entries, search, coverage, note, bound, null, Map.of());
        }
    }

    /** A read behind a holder word, or why the word did not resolve. */
    private record Worded<T>(T value, String unresolved) {
    }

    private final Supplier<MinecraftServer> server;
    private final WorldRegistry registry;
    private final Supplier<Evidence.Scope> scope;
    private final AreaScan scan;
    private final Executor worker;
    private final ServerWork work;
    private final WorldNavigation navigation;
    private final Function<String, BlockPlace> notePlaces;
    private final Supplier<CompletionStage<ClientFocus>> focus;
    private final WorldKnowledgeTools knowledge;
    private final Map<String, Cursor> cursors = new LinkedHashMap<>(16, 0.75f, true);
    /**
     * The menu each opened id was issued for, on the server in openedOn. Ids restart for every new player and wrap after
     * 100, so an id alone can name a later menu.
     */
    private final Map<Integer, WeakReference<AbstractContainerMenu>> openedMenus = new HashMap<>();
    private WeakReference<MinecraftServer> openedOn = new WeakReference<>(null);

    /**
     * The worker runs everything after a server read, so record building never lands on the server thread. Reads queue
     * on {@code work}, which the server ticks drive. {@code focus} captures the client's crosshair and open screen.
     */
    public WorldTools(Minecraft client, WorldRegistry registry, Supplier<Evidence.Scope> scope, Executor worker, ServerWork work,
                      Supplier<CompletionStage<ClientFocus>> focus) {
        this(client, registry, scope, worker, work, focus, id -> null);
    }

    public WorldTools(Minecraft client, WorldRegistry registry, Supplier<Evidence.Scope> scope, Executor worker, ServerWork work,
                      Supplier<CompletionStage<ClientFocus>> focus, Function<String, BlockPlace> notePlaces) {
        this(client::getSingleplayerServer, registry, scope, worker, work, focus, WorldNavigation.blockLookup(client::getSingleplayerServer),
                WorldNavigation.entityLookup(client::getSingleplayerServer), WorldNavigation.client(client), notePlaces);
    }

    /** Highlights check {@code blockExists} and {@code entityPlace} before drawing through {@code client}. */
    WorldTools(Supplier<MinecraftServer> server, WorldRegistry registry, Supplier<Evidence.Scope> scope, Executor worker, ServerWork work,
               Supplier<CompletionStage<ClientFocus>> focus, Function<BlockPlace, Boolean> blockExists, Function<UUID, BlockPlace> entityPlace, WorldNavigation.Client client) {
        this(server, registry, scope, worker, work, focus, blockExists, entityPlace, client, id -> null);
    }

    WorldTools(Supplier<MinecraftServer> server, WorldRegistry registry, Supplier<Evidence.Scope> scope, Executor worker, ServerWork work,
               Supplier<CompletionStage<ClientFocus>> focus, Function<BlockPlace, Boolean> blockExists, Function<UUID, BlockPlace> entityPlace,
               WorldNavigation.Client client, Function<String, BlockPlace> notePlaces) {
        this.server = server;
        this.registry = registry;
        this.scope = scope;
        this.worker = worker;
        this.work = work;
        this.focus = focus;
        this.scan = new AreaScan(registry);
        this.navigation = new WorldNavigation(blockExists, entityPlace, client);
        this.notePlaces = notePlaces;
        this.knowledge = new WorldKnowledgeTools(this);
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
                ? new ProviderStatus(Availability.UNAVAILABLE, VERSION, "world tools need an integrated singleplayer server")
                : new ProviderStatus(Availability.AVAILABLE, VERSION, null);
    }

    @Override
    public CompletionStage<Evidence.Result> handle(PackSageTools.ReadRequest request, Context context) {
        try {
            return switch (request.tool()) {
                case "inspect_area" -> inspectArea(request);
                case "list_blocks" -> listBlocks(request);
                case "list_entities" -> listEntities(request);
                case "inspect_block" -> inspect(request, "block|" + place(requiredText(request, "place")).text());
                case "inspect_entity" -> inspect(request, "entity|" + uuid(requiredText(request, "id")));
                case "list_items" -> listItems(request);
                case "inspect_item" -> inspectItem(request);
                case "find_items" -> findItems(request);
                default -> WorldKnowledgeTools.TOOLS.contains(request.tool()) ? knowledge.handle(request)
                        : CompletableFuture.failedFuture(new Failure(Code.NOT_HANDLED, request.tool()));
            };
        } catch (Failure failure) {
            return CompletableFuture.failedFuture(failure);
        } catch (IllegalArgumentException failure) {
            return CompletableFuture.failedFuture(invalid(failure.getMessage()));
        }
    }

    @Override
    public CompletionStage<Evidence.Result> readEvidence(PackSageTools.ReadRequest request, Context context) {
        Reference reference;
        try {
            reference = context.reference(request);
        } catch (Failure failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (!reference.providerId().equals(ID)) return CompletableFuture.failedFuture(new Failure(Code.NOT_HANDLED, "different evidence owner"));
        try {
            return reread(reference.sourceHandle(), request.scope()).thenApply(WorldTools::single);
        } catch (Failure failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override
    public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
        if (query.operation() != Evidence.Operation.GET || !(query.arguments().values().get("id") instanceof Evidence.JsonString handle)) {
            return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null,
                    "world evidence is read through the world tools"));
        }
        if (!scope.equals(this.scope.get())) {
            return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null,
                    "observation scope changed"));
        }
        try {
            return reread(handle.value(), scope).thenApply(WorldTools::single).exceptionally(failure -> failed(failure));
        } catch (Failure failure) {
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
        return navigation.open(target);
    }

    static Evidence.Result single(Evidence.Record record) {
        var complete = record.source().coverage() == Evidence.Coverage.COMPLETE;
        return new Evidence.Result(List.of(record), complete ? Evidence.Coverage.COMPLETE : Evidence.Coverage.PARTIAL,
                complete ? Evidence.Outcome.COMPLETE : Evidence.Outcome.PARTIAL, null);
    }

    private static Evidence.Result failed(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        return new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null, failure.getMessage());
    }

    // Tools

    private CompletionStage<Evidence.Result> inspectArea(PackSageTools.ReadRequest request) {
        var anchor = anchor(argument(request, "center"));
        var radius = radius(argument(request, "radius"));
        var server = server();
        return run(server, spanned(gameTime(server), then(() -> center(server, anchor), center -> {
            var level = level(server, center.dimension());
            return mapped(both(scan.summary(level, center.position(), radius),
                            scan.listEntities(level, center.position(), radius, KindFilter.ANY, null)),
                    pages -> new AreaRead(center, pages.getKey(), pages.getValue()));
        }))).thenApply(read -> area(request.scope(), anchor, radius, read.value(), read.span()));
    }

    private CompletionStage<Evidence.Result> listBlocks(PackSageTools.ReadRequest request) {
        return list(request, (level, center, radius, filter, cursor) -> scan.listBlocks(level, center, radius, filter, cursor), this::blockRecord);
    }

    private CompletionStage<Evidence.Result> listEntities(PackSageTools.ReadRequest request) {
        return list(request, (level, center, radius, filter, cursor) -> scan.listEntities(level, center, radius, filter, cursor), this::entityRecord);
    }

    private interface ListScan<T> {
        ServerWork.Slice<Page<T>> scan(ServerLevel level, Vec3 center, int radius, KindFilter filter, String cursor);
    }

    private interface RecordOf<T> {
        Evidence.Record record(Evidence.Scope scope, T handle, ReadSpan span);
    }

    private <T> CompletionStage<Evidence.Result> list(PackSageTools.ReadRequest request, ListScan<T> listing, RecordOf<T> records) {
        var anchor = anchor(argument(request, "center"));
        var radius = radius(argument(request, "radius"));
        var filter = filter(argument(request, "filter"), false);
        var cursor = cursor(request);
        var server = server();
        var state = cursor == null ? null : state(cursor, ListState.class);
        return run(server, spanned(gameTime(server), then(() -> state == null ? center(server, anchor) : state.center(), center -> mapped(
                listing.scan(level(server, center.dimension()), center.position(), radius, filter, state == null ? null : state.scan()),
                page -> Map.entry(center, page))))).thenApply(read -> {
            var page = read.value().getValue();
            var next = page.nextCursor() == null ? null : remember(request, new ListState(read.value().getKey(), page.nextCursor()));
            return result(page.items().stream().map(handle -> records.record(request.scope(), handle, read.span())).toList(), page.coverage(), next, page.note());
        });
    }

    private CompletionStage<Evidence.Result> inspect(PackSageTools.ReadRequest request, String handle) {
        return reread(handle, request.scope()).thenApply(WorldTools::single);
    }

    private CompletionStage<Evidence.Result> listItems(PackSageTools.ReadRequest request) {
        var holderText = requiredText(request, "holder");
        var phrase = holderText.equals("player") ? null : phrase(holderText);
        var holder = holderText.equals("player") || phrase != null ? null : itemPath(holderText);
        if (holder != null) listable(holder);
        var inventory = argument(request, "inventory") == null ? null : inventoryId(requiredText(request, "inventory"));
        var filter = argument(request, "filter") == null ? KindFilter.ANY : filter(argument(request, "filter"), true);
        var cursor = cursor(request);
        var server = server();
        if (cursor != null) return CompletableFuture.completedFuture(entries(request, state(cursor, EntriesState.class), Evidence.Coverage.COMPLETE, null));
        var opened = phrase != null ? phrase.word() == HolderWords.Word.OPENED : holder != null && holder.root() instanceof PathRoot.Opened;
        return focus(phrase, opened).thenCompose(focus -> run(server, spanned(gameTime(server), once(() -> {
            var path = holder;
            if (phrase != null) {
                var resolved = resolveWord(server, phrase, focus);
                if (resolved instanceof HolderWords.Resolved.Unresolved unresolved) return new Worded<ItemsRead>(null, unresolved.reason());
                path = listable(((HolderWords.Resolved.Path) resolved).path());
            }
            return new Worded<>(items(server, path, inventory, filter, focus), null);
        })))).thenApply(read -> {
            var worded = read.value();
            if (worded.unresolved() != null) return unresolved(worded.unresolved());
            var items = worded.value();
            return entries(request, new EntriesState(items.entries(), null, Evidence.Coverage.COMPLETE, null, read.span(), items.summary(), items.slotFacts()),
                    items.coverage(), items.note());
        });
    }

    private static ItemPath listable(ItemPath holder) {
        if (holder.steps().size() >= ItemPath.MAX_STEPS) {
            throw invalid("items nested deeper than " + ItemPath.MAX_STEPS + " steps cannot be listed");
        }
        return holder;
    }

    /** Reads one item by a concrete path or a word such as held; a word that names a holder is not an item. */
    private CompletionStage<Evidence.Result> inspectItem(PackSageTools.ReadRequest request) {
        var text = requiredText(request, "path");
        var phrase = phrase(text);
        if (phrase == null) return inspect(request, "item|" + itemPath(text).text());
        if (phrase.steps().isEmpty() && phrase.word() != HolderWords.Word.HELD && phrase.word() != HolderWords.Word.OFFHAND) {
            throw invalid(text + " names a holder, not an item; list its items with list_items");
        }
        var server = server();
        return focus(phrase, false).thenCompose(focus -> run(server, once(() -> {
            var resolved = resolveWord(server, phrase, focus);
            if (resolved instanceof HolderWords.Resolved.Unresolved unresolved) return new Worded<Evidence.Record>(null, unresolved.reason());
            return new Worded<>(inspectItem(server, ((HolderWords.Resolved.Path) resolved).path(), request.scope()), null);
        }))).thenApply(read -> read.unresolved() != null ? unresolved(read.unresolved()) : single(read.value()));
    }

    /** A word that did not resolve, such as held with an empty hand: no records, complete, and the reason. */
    static Evidence.Result unresolved(String reason) {
        return new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null, reason);
    }

    /**
     * The client's focus, awaited only for the targeted word, which needs it, and for listing an open menu, which takes
     * only its optional screen title from it and goes on without the title when the capture fails.
     */
    CompletionStage<ClientFocus> focus(HolderWords.Phrase phrase, boolean opened) {
        var required = phrase != null && phrase.word() == HolderWords.Word.TARGETED;
        if (!required && !opened) return CompletableFuture.completedFuture(ClientFocus.NONE);
        var unavailable = "the game did not report what is under the crosshair or open";
        CompletableFuture<ClientFocus> captured;
        try {
            captured = focus.get().toCompletableFuture();
        } catch (RuntimeException exception) {
            // The client can reject the capture, for example while it shuts down.
            return required ? CompletableFuture.failedFuture(new Failure(Code.UNAVAILABLE, unavailable)) : CompletableFuture.completedFuture(ClientFocus.NONE);
        }
        return captured.orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).exceptionally(failure -> {
            if (!required) return ClientFocus.NONE;
            throw new Failure(Code.UNAVAILABLE, unavailable);
        });
    }

    /** Resolves a word against the host player as it is now, issuing the opened id for the open menu; server thread only. */
    private HolderWords.Resolved resolveWord(MinecraftServer server, HolderWords.Phrase phrase, ClientFocus focus) {
        var host = host(server);
        var vehicle = host.getVehicle();
        var menu = host.containerMenu == host.inventoryMenu ? -1 : host.containerMenu.containerId;
        if (phrase.word() == HolderWords.Word.OPENED && menu >= 0) issueOpened(server, host.containerMenu);
        return HolderWords.resolve(phrase, new HolderWords.Situation(host.getUUID(), host.getInventory().selected, kind(host.getMainHandItem()),
                kind(host.getOffhandItem()), focus.targeted(), vehicle == null ? null : vehicle.getUUID(), menu));
    }

    private static String kind(net.minecraft.world.item.ItemStack stack) {
        return stack.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private CompletionStage<Evidence.Result> findItems(PackSageTools.ReadRequest request) {
        var filter = filter(argument(request, "filter"), true);
        var anchor = anchor(argument(request, "center"));
        var radius = radius(argument(request, "radius"));
        var includeNested = !(argument(request, "includeNested") instanceof Evidence.JsonBoolean nested) || nested.value();
        var cursor = cursor(request);
        var server = server();
        var state = cursor == null ? null : state(cursor, EntriesState.class);
        if (state != null && !state.pending().isEmpty()) {
            return CompletableFuture.completedFuture(entries(request, state, Evidence.Coverage.COMPLETE, null));
        }
        return run(server, spanned(gameTime(server), new FindSlice(server, anchor, radius, filter, includeNested, state == null ? null : state.search())))
                .thenApply(finished -> {
                    var read = finished.value();
                    return entries(request, new EntriesState(read.entries(), read.search(),
                            state == null ? Evidence.Coverage.COMPLETE : state.coverage(), state == null ? null : state.note(), finished.span()),
                            read.coverage(), read.note(), read.bound());
                });
    }

    Evidence.Result entries(PackSageTools.ReadRequest request, EntriesState state, Evidence.Coverage coverage, String note) {
        return entries(request, state, coverage, note, null);
    }

    /**
     * Shows up to one list page of entries; the rest, and any search continuation, go behind the next cursor. This
     * page's coverage and note merge into the state's sticky ones, which every later page reports too; bound and the
     * entry split explain only this page's cut.
     */
    Evidence.Result entries(PackSageTools.ReadRequest request, EntriesState state, Evidence.Coverage coverage, String note, String bound) {
        var partial = state.coverage() != Evidence.Coverage.COMPLETE || coverage != Evidence.Coverage.COMPLETE;
        var sticky = partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE;
        var notes = new LinkedHashSet<String>();
        if (state.note() != null) notes.add(state.note());
        if (note != null) notes.add(note);
        var merged = notes.isEmpty() ? null : String.join("; ", notes);
        // Entries and their holder records share one page of LIST_PAGE records, the tool output limit, so nothing on
        // the page is cut after it is built. A page always shows at least one entry.
        // The holder summary leads the first page and counts toward it; its root already has its record there.
        var summary = state.summary();
        var lead = summary == null ? 0 : 1;
        var roots = new LinkedHashSet<PathRoot>();
        var shownCount = 0;
        for (var entry : state.pending()) {
            var added = new LinkedHashSet<PathRoot>();
            for (var location : entry.locations()) {
                var root = location.root();
                if (!(root instanceof PathRoot.Player) && !(root instanceof PathRoot.Opened) && !roots.contains(root) && (summary == null || !summary.root().equals(root))) added.add(root);
            }
            if (shownCount > 0 && lead + shownCount + 1 + roots.size() + added.size() > AreaScan.LIST_PAGE) break;
            roots.addAll(added);
            shownCount++;
        }
        var shown = state.pending().subList(0, shownCount);
        var rest = List.copyOf(state.pending().subList(shown.size(), state.pending().size()));
        var next = rest.isEmpty() && state.search() == null ? null
                : remember(request, new EntriesState(rest, state.search(), sticky, merged, state.span(), null, state.slotFacts()));
        if (bound != null) notes.add(bound);
        if (!rest.isEmpty()) notes.add("This page shows " + shown.size() + " entries, the page limit with their holders; continue with the cursor");
        var records = new ArrayList<Evidence.Record>();
        if (summary != null) records.add(summaryRecord(request.scope(), summary, state.span(), sticky));
        for (var entry : shown) records.add(entryRecord(request.scope(), entry, state.slotFacts(), state.span()));
        // One record per block or entity holder on the page, so each holder has its own highlight link.
        var linked = 0;
        for (var root : roots) {
            if (records.size() >= AreaScan.LIST_PAGE) break;
            var holder = holderRecord(request.scope(), root, state.span());
            if (holder != null) records.add(holder);
            linked++;
        }
        if (linked < roots.size()) {
            notes.add("Highlight links are shown for " + linked + " of " + roots.size() + " holders of " + shown.getFirst().name()
                    + "; list or inspect the others by their item paths");
        }
        return result(records, sticky, next, notes.isEmpty() ? null : String.join("; ", notes));
    }

    static Evidence.Result result(List<Evidence.Record> records, Evidence.Coverage coverage, String next, String note) {
        var complete = next == null && coverage == Evidence.Coverage.COMPLETE;
        return new Evidence.Result(records, complete ? Evidence.Coverage.COMPLETE : Evidence.Coverage.PARTIAL,
                complete ? Evidence.Outcome.COMPLETE : Evidence.Outcome.PARTIAL, next, note);
    }

    /** Reads one block, entity, item, holder, loot or command handle fresh. */
    private CompletionStage<Evidence.Record> reread(String handle, Evidence.Scope scope) {
        if (handle.startsWith("block|")) {
            var place = place(handle.substring(6));
            var server = server();
            return run(server, once(() -> inspectBlock(server, place, scope)));
        }
        if (handle.startsWith("entity|")) {
            var id = uuid(handle.substring(7));
            var server = server();
            return run(server, once(() -> inspectEntity(server, id, scope)));
        }
        if (handle.startsWith("item|")) {
            var path = itemPath(handle.substring(5));
            if (path.steps().isEmpty()) throw invalid(path.text() + " names a holder, not an item; list its items with list_items");
            var server = server();
            return run(server, once(() -> inspectItem(server, path, scope)));
        }
        if (handle.startsWith("loot|") || handle.startsWith("command|")) return knowledge.reread(handle, scope);
        if (handle.startsWith("holder|")) {
            return listItems(new PackSageTools.ReadRequest("list_items", new Evidence.JsonObject(Map.of(
                    "holder", text(handle.substring(7)))), scope)).thenApply(result -> {
                if (result.nextCursor() != null) {
                    synchronized (cursors) {
                        cursors.remove(result.nextCursor());
                    }
                }
                return result.records().stream().filter(record -> record.source().sourceHandle().equals(handle)).findFirst()
                        .orElseThrow(() -> new Failure(Code.INVALID_REFERENCE, "holder summary is no longer available; repeat list_items"));
            });
        }
        throw new Failure(Code.INVALID_REFERENCE, "only block, entity, item, holder, loot and command records can be reread; repeat the lookup that produced it");
    }

    // Server-thread reads

    private Evidence.Record inspectBlock(MinecraftServer server, BlockPlace place, Evidence.Scope scope) {
        var level = level(server, place.dimension());
        var pos = new BlockPos(place.x(), place.y(), place.z());
        if (!loaded(level, pos)) throw new Failure(Code.UNAVAILABLE, "the chunk at " + place.text() + " is not loaded");
        var state = level.getBlockState(pos);
        var inventories = registry.inventories(new LiveHolder.OfBlock(level, pos));
        var sections = registry.inspectBlock(level, pos);
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        var kind = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        payload.put("block", text(kind));
        payload.put("name", text(state.getBlock().getName().getString()));
        payload.put("place", text(place.text()));
        payload.put("holder", text(new ItemPath(new PathRoot.Block(place), List.of()).text()));
        payload.put("sections", sections(sections));
        payload.put("inventories", inventories(inventories));
        putTime(payload, level);
        return evidence(scope, "block|" + place.text(), Evidence.RecordKind.BLOCK, kind + " at " + place.text(), payload,
                coverage(sections, inventories), state.isAir() ? null : WorldNavigation.blockId(place));
    }

    private Evidence.Record inspectEntity(MinecraftServer server, UUID id, Evidence.Scope scope) {
        var entity = entity(server, id);
        if (entity == null) throw new Failure(Code.UNAVAILABLE, "no loaded entity has id " + id);
        var holder = entity instanceof ServerPlayer player ? new LiveHolder.OfPlayer(player) : new LiveHolder.OfEntity(entity);
        var root = entity instanceof ServerPlayer ? new PathRoot.Player(id) : new PathRoot.Entity(id);
        var inventories = registry.inventories(holder);
        var sections = registry.inspectEntity(entity);
        var handle = handle(entity);
        var payload = new LinkedHashMap<String, Evidence.JsonValue>(entityFacts(handle));
        payload.put("holder", text(new ItemPath(root, List.of()).text()));
        payload.put("sections", sections(sections));
        payload.put("inventories", inventories(inventories));
        putTime(payload, entity.level());
        return evidence(scope, "entity|" + id, Evidence.RecordKind.OTHER, entityTitle(handle), payload,
                coverage(sections, inventories), WorldNavigation.entityId(id));
    }

    private Evidence.Record inspectItem(MinecraftServer server, ItemPath path, Evidence.Scope scope) {
        var resolution = resolver(server).resolve(path);
        if (resolution.failure() != PathResolver.Failure.NONE) throw pathFailure(path, resolution);
        var stack = resolution.stack();
        var inventories = registry.inventories(new LiveHolder.OfItem(stack));
        var sections = registry.inspectItem(stack);
        var kind = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("item", text(kind));
        payload.put("name", text(stack.getHoverName().getString()));
        payload.put("count", number(stack.getCount()));
        payload.put("path", text(path.text()));
        var tooltip = ItemTooltips.of(host(server)).lines(stack);
        payload.put("tooltip", new Evidence.JsonArray(tooltip.lines().stream().map(line -> (Evidence.JsonValue) text(line)).toList()));
        payload.put("tooltipStatus", text(tooltip.status()));
        payload.put("sections", sections(sections));
        payload.put("inventories", inventories(inventories));
        var coverage = tooltip.status().equals("complete") ? coverage(sections, inventories) : Evidence.Coverage.PARTIAL;
        return evidence(scope, "item|" + path.text(), Evidence.RecordKind.ITEM, stack.getHoverName().getString() + " at " + path.text(), payload,
                coverage, rootTarget(path.root()));
    }

    private ItemsRead items(MinecraftServer server, ItemPath holderPath, InventoryId inventory, KindFilter filter, ClientFocus focus) {
        var path = holderPath == null ? new ItemPath(new PathRoot.Player(host(server).getUUID()), List.of()) : holderPath;
        var resolution = resolver(server).resolve(path);
        if (resolution.failure() != PathResolver.Failure.NONE) throw pathFailure(path, resolution);
        var notes = new LinkedHashSet<String>();
        var listed = registry.inventories(resolution.holder());
        if (listed.note() != null) notes.add(listed.note());
        var selected = listed.items().stream().filter(info -> inventory == null || info.id().equals(inventory)).toList();
        if (inventory != null && selected.isEmpty()) {
            throw invalid(path.text() + " has no inventory " + inventory.text() + "; it has "
                    + String.join(", ", listed.items().stream().map(info -> info.id().text()).toList()));
        }
        var grouper = new ItemGrouper();
        var tooltips = ItemTooltips.of(host(server));
        var unreadTooltips = new int[1];
        var summaries = new ArrayList<InventorySummary>();
        var slotFacts = new LinkedHashMap<String, Evidence.JsonObject>();
        for (var info : selected) {
            var reader = registry.inventoryReader(info.id().adapter());
            LiveInventory live;
            try {
                live = reader == null ? null : reader.open(resolution.holder(), info.id().section());
            } catch (RuntimeException exception) {
                notes.add("Reading " + info.id().text() + " failed: " + describe(exception));
                summaries.add(new InventorySummary(info, null));
                continue;
            }
            if (live == null) {
                notes.add("Inventory " + info.id().text() + " could not be opened");
                summaries.add(new InventorySummary(info, null));
                continue;
            }
            var occupied = 0;
            for (var entry : live.entries()) {
                var stack = entry.getValue();
                if (stack == null || stack.isEmpty()) continue;
                occupied++;
                var kind = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                if (!filter.matches(kind, stack.getTags().map(tag -> tag.location().toString()).toList(), stack.getHoverName().getString(), () -> {
                    var tooltip = tooltips.lines(stack);
                    if (!tooltip.status().equals("complete")) unreadTooltips[0]++;
                    return tooltip;
                })) continue;
                var nested = registry.inventories(new LiveHolder.OfItem(stack));
                if (nested.note() != null) notes.add(nested.note());
                var location = path.child(new PathStep(info.id(), entry.getKey(), kind));
                grouper.add(location, stack, !nested.items().isEmpty());
                try {
                    var facts = reader.entryFacts(resolution.holder(), info.id().section(), entry.getKey());
                    if (facts != null) slotFacts.put(location.text(), facts);
                } catch (RuntimeException exception) {
                    notes.add("Slot facts of " + info.id().text() + " failed: " + describe(exception));
                }
            }
            summaries.add(inventorySummary(info, occupied, notes));
        }
        if (unreadTooltips[0] > 0) notes.add(ItemSearch.unreadTooltipsNote(unreadTooltips[0]));
        // Only a block, entity, player or menu holder has a summary record; an item's inventories show on inspect_item.
        var summary = !path.steps().isEmpty() ? null : new HolderSummary(path.root(), summaries, switch (resolution.holder()) {
            case LiveHolder.OfPlayer of -> new Evidence.JsonObject(Map.of("selectedSlot", number(of.player().getInventory().selected)));
            case LiveHolder.OfMenu of -> menuFacts(of, focus, notes);
            default -> new Evidence.JsonObject(Map.of());
        });
        return new ItemsRead(grouper.entries(), null, notes.isEmpty() ? Evidence.Coverage.COMPLETE : Evidence.Coverage.PARTIAL,
                notes.isEmpty() ? null : String.join("; ", notes), null, summary, slotFacts);
    }

    /**
     * The open menu's type, the title its screen shows when the client shows this menu, and the preview slots left out
     * of its contents; a menu from outside Minecraft adds a note, since its previews cannot be told apart.
     */
    static Evidence.JsonObject menuFacts(LiveHolder.OfMenu holder, ClientFocus focus, Set<String> notes) {
        var menuType = MinecraftMenus.menuType(holder.menu());
        var values = new LinkedHashMap<String, Evidence.JsonValue>();
        values.put("menuType", text(menuType));
        if (focus.openedContainerId() == holder.menu().containerId && focus.openedTitle() != null) values.put("title", text(focus.openedTitle()));
        MinecraftMenus.MenuLayout layout;
        try {
            layout = MinecraftMenus.layout(holder.menu(), holder.playerInventory());
        } catch (RuntimeException exception) {
            values.put("excludedSlots", new Evidence.JsonNull());
            notes.add("The preview slots of " + menuType + " could not be read: " + describe(exception));
            return new Evidence.JsonObject(values);
        }
        values.put("excludedSlots", new Evidence.JsonArray(layout.excluded().stream().map(slot -> (Evidence.JsonValue) new Evidence.JsonObject(
                Map.of("slot", number(slot.slot()), "kind", text(slot.kind())))).toList()));
        if (!layout.vanilla()) notes.add(menuType + " is not a vanilla menu; some listed slots may be previews");
        return new Evidence.JsonObject(values);
    }

    /**
     * One listed inventory with its empty slots: null for keyed sections, and for a section whose loot is not generated
     * yet, which gets a note instead because its contents are not decided.
     */
    static InventorySummary inventorySummary(InventoryInfo info, int occupied, Set<String> notes) {
        if (info.pendingLoot() != null) {
            notes.add(info.id().text() + "'s loot has not been generated yet (table " + info.pendingLoot()
                    + "); its contents are decided when it is first opened");
            return new InventorySummary(info, null);
        }
        return new InventorySummary(info, info.slots() >= 0 ? info.slots() - occupied : null);
    }

    /**
     * Gathers the find_items holders one chunk per step (the player first, then each loaded chunk's existing block
     * entities and entities in range that hold inventories), after re-resolving a continuation's remaining roots, then
     * runs {@link ItemSearch} over them. Stopped early while gathering, it returns no entries and a continuation over
     * the holders gathered so far and the chunks left.
     */
    private final class FindSlice implements ServerWork.Slice<ItemsRead> {
        private final MinecraftServer server;
        private final Anchor anchor;
        private final int radius;
        private final KindFilter filter;
        private final boolean includeNested;
        private final FindState resume;
        private final List<Map.Entry<PathRoot, LiveHolder>> holders = new ArrayList<>();
        private final Set<BlockPos> chestHalves = new HashSet<>();
        private final Set<String> notes = new LinkedHashSet<>();
        private ServerPlayer host;
        private ServerLevel level;
        private Vec3 center;
        private List<ChunkPos> chunks;
        private int next;
        private int startChunk;
        private int resolved;
        private int unloaded;
        private int entitiesUnloaded;
        private int gone;
        private boolean firstRootFound;
        private boolean stopped;
        private ItemSearch search;

        FindSlice(MinecraftServer server, Anchor anchor, int radius, KindFilter filter, boolean includeNested, FindState resume) {
            this.server = server;
            this.anchor = anchor;
            this.radius = radius;
            this.filter = filter;
            this.includeNested = includeNested;
            this.resume = resume;
        }

        @Override
        public boolean step() {
            if (stopped) return false;
            if (search != null) return search.step();
            if (resume != null && chunks == null) {
                resolveRoots();
            } else if (chunks == null) {
                host = host(server);
                var start = center(server, anchor);
                level = level(server, start.dimension());
                center = start.position();
                holders.add(Map.entry(new PathRoot.Player(host.getUUID()), new LiveHolder.OfPlayer(host)));
                chunks = AreaScan.chunksInRange(center, radius);
            } else if (next < chunks.size()) {
                collect(chunks.get(next++));
            } else {
                search = new ItemSearch(registry, holders, filter, ItemTooltips.of(host(server))::lines, includeNested, resumedCursor(resume, firstRootFound));
            }
            return true;
        }

        /** Re-resolves the continuation's roots, then picks up gathering where the earlier page stopped, if it did. */
        private void resolveRoots() {
            var end = Math.min(resume.roots().size(), resolved + RESOLVE_PER_STEP);
            for (; resolved < end; resolved++) {
                var root = resume.roots().get(resolved);
                var holder = root(server, root);
                if (holder == null) {
                    gone++;
                    continue;
                }
                if (resolved == 0) firstRootFound = true;
                // Gathering later chunks must not add the other half of a double chest already among the roots.
                if (root instanceof PathRoot.Block block) chestHalves.add(new BlockPos(block.place().x(), block.place().y(), block.place().z()));
                holders.add(Map.entry(root, holder));
            }
            if (resolved < resume.roots().size()) return;
            var gather = resume.gather();
            if (gather == null) {
                chunks = List.of();
                return;
            }
            host = host(server);
            level = level(server, gather.dimension());
            center = gather.center();
            chunks = AreaScan.chunksInRange(center, radius);
            next = gather.nextChunk();
            startChunk = next;
        }

        /**
         * Stops inside the search, or between chunks while gathering once this page has gathered at least one chunk;
         * resolving roots runs to its end first.
         */
        @Override
        public boolean stopEarly() {
            if (search != null) return search.stopEarly();
            if (chunks == null || !gatherCanStop(chunks.size(), next, startChunk)) return false;
            stopped = true;
            return true;
        }

        private void collect(ChunkPos pos) {
            var chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk == null) {
                unloaded++;
                return;
            }
            var radiusSquared = (double) radius * radius;
            var dimension = level.dimension().location().toString();
            var blocks = new ArrayList<>(chunk.getBlockEntities().keySet());
            blocks.sort(Comparator.comparingLong(BlockPos::asLong));
            for (var at : blocks) {
                if (Vec3.atCenterOf(at).distanceToSqr(center) > radiusSquared) continue;
                var state = chunk.getBlockState(at);
                // The player's ender chest is searched once, under the player.
                if (state.getBlock() instanceof EnderChestBlock) continue;
                var doubleChest = state.getBlock() instanceof ChestBlock && ChestBlock.getBlockType(state) != DoubleBlockCombiner.BlockType.SINGLE;
                // Either half of a double chest reads the whole chest, so only one half is searched.
                if (doubleChest && chestHalves.contains(at.relative(ChestBlock.getConnectedDirection(state)))) continue;
                var holder = new LiveHolder.OfBlock(level, at.immutable());
                if (!holds(holder)) continue;
                if (doubleChest) chestHalves.add(at.immutable());
                holders.add(Map.entry(new PathRoot.Block(new BlockPlace(dimension, at.getX(), at.getY(), at.getZ())), holder));
            }
            // A loaded chunk can still be waiting for its entities; count it rather than report them absent.
            if (!level.areEntitiesLoaded(pos.toLong())) {
                entitiesUnloaded++;
                return;
            }
            var box = new AABB(pos.getMinBlockX(), center.y - radius, pos.getMinBlockZ(),
                    pos.getMinBlockX() + 16, center.y + radius, pos.getMinBlockZ() + 16);
            var entities = new ArrayList<>(level.getEntities((Entity) null, box, entity -> entity != host
                    && SectionPos.blockToSectionCoord(entity.getBlockX()) == pos.x && SectionPos.blockToSectionCoord(entity.getBlockZ()) == pos.z
                    && entity.position().distanceToSqr(center) <= radiusSquared));
            entities.sort(Comparator.comparing(Entity::getUUID));
            for (var entity : entities) {
                LiveHolder holder = entity instanceof ServerPlayer player ? new LiveHolder.OfPlayer(player) : new LiveHolder.OfEntity(entity);
                if (!holds(holder)) continue;
                holders.add(Map.entry(entity instanceof ServerPlayer ? new PathRoot.Player(entity.getUUID()) : new PathRoot.Entity(entity.getUUID()), holder));
            }
        }

        private boolean holds(LiveHolder holder) {
            var inventories = registry.inventories(holder);
            if (inventories.note() != null) notes.add(inventories.note());
            return !inventories.items().isEmpty();
        }

        @Override
        public ItemsRead result() {
            var all = new ArrayList<String>();
            if (unloaded > 0) all.add(unloaded + " chunks in range were not loaded and were not searched");
            if (entitiesUnloaded > 0) all.add(entitiesUnloaded + " chunks in range did not have their entities loaded yet, so entities there were not searched");
            if (gone > 0) all.add(gone + " holders from earlier pages are gone or no longer loaded");
            all.addAll(notes);
            List<ItemEntry> entries = List.of();
            FindState continuation = null;
            String bound;
            Evidence.Coverage coverage;
            if (search == null) {
                // Stopped while gathering: nothing was searched yet, so every holder so far carries over.
                continuation = new FindState(holders.stream().map(Map.Entry::getKey).toList(), firstRootFound ? resume.ordinal() : 0,
                        new Gather(level.dimension().location().toString(), center, next));
                bound = "Stopped at the per-call work limit while gathering holders, after " + next + " of " + chunks.size()
                        + " chunks; continue with the cursor";
                coverage = Evidence.Coverage.PARTIAL;
            } else {
                var page = search.result();
                entries = page.items();
                if (page.nextCursor() != null) continuation = continuation(holders.stream().map(Map.Entry::getKey).toList(), page.nextCursor());
                if (search.gapNote() != null) all.add(search.gapNote());
                bound = search.boundNote();
                coverage = page.coverage();
            }
            if (continuation != null || resume != null) {
                all.add("Counts cover only the containers searched for each page; an item can appear again on another page");
            }
            if (!all.isEmpty()) coverage = Evidence.Coverage.PARTIAL;
            return new ItemsRead(entries, continuation, coverage, all.isEmpty() ? null : String.join("; ", all), bound);
        }
    }

    /**
     * The find_items continuation of an {@link ItemSearch} page over these roots: the roots from the cursor's holder on,
     * with the ordinal to resume at inside the first one.
     */
    static FindState continuation(List<PathRoot> roots, String searchCursor) {
        var parts = searchCursor.split("[.]");
        var from = Integer.parseInt(parts[0]);
        return new FindState(List.copyOf(roots.subList(from, roots.size())), parts.length == 2 ? Integer.parseInt(parts[1]) : 0, null);
    }

    /**
     * Whether find_items gathering may stop before chunk {@code next}: only after this page gathered at least one chunk
     * past {@code startChunk}, where it began, and while chunks remain.
     */
    static boolean gatherCanStop(int chunkCount, int next, int startChunk) {
        return next > startChunk && next < chunkCount;
    }

    /**
     * The {@link ItemSearch} cursor that resumes a continuation over its re-resolved roots: inside the first root when it
     * is still there, otherwise from the start of the roots that remain.
     */
    static String resumedCursor(FindState resume, boolean firstRootFound) {
        return resume != null && firstRootFound && resume.ordinal() > 0 ? "0." + resume.ordinal() : null;
    }

    // Live lookups; server thread only

    public CompletionStage<BlockPlace> resolveAnchor(Anchor anchor) {
        try {
            var server = server();
            return run(server, once(() -> {
                var center = center(server, anchor);
                var pos = BlockPos.containing(center.position());
                return new BlockPlace(center.dimension(), pos.getX(), pos.getY(), pos.getZ());
            }));
        } catch (Failure failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    Center center(MinecraftServer server, Anchor anchor) {
        return switch (anchor) {
            case Anchor.Player player -> {
                var host = host(server);
                yield new Center(host.serverLevel().dimension().location().toString(), host.position());
            }
            case Anchor.OfEntity of -> {
                var entity = entity(server, of.id());
                if (entity == null) throw new Failure(Code.UNAVAILABLE, "no loaded entity has id " + of.id());
                yield new Center(entity.level().dimension().location().toString(), entity.position());
            }
            case Anchor.OfBlock of -> {
                level(server, of.place().dimension());
                yield new Center(of.place().dimension(), Vec3.atCenterOf(new BlockPos(of.place().x(), of.place().y(), of.place().z())));
            }
            case Anchor.OfNote of -> {
                var place = notePlaces.apply(of.id());
                if (place == null) throw new Failure(Code.UNAVAILABLE, "note " + of.id() + " has no saved place");
                level(server, place.dimension());
                yield new Center(place.dimension(), Vec3.atCenterOf(new BlockPos(place.x(), place.y(), place.z())));
            }
        };
    }

    static ServerPlayer host(MinecraftServer server) {
        var player = hostOrNull(server);
        if (player == null) throw new Failure(Code.UNAVAILABLE, "the player is not in the world");
        return player;
    }

    private static ServerPlayer hostOrNull(MinecraftServer server) {
        var profile = server.getSingleplayerProfile();
        return profile == null ? null : server.getPlayerList().getPlayer(profile.getId());
    }

    private static ServerLevel levelOrNull(MinecraftServer server, String dimension) {
        var location = ResourceLocation.tryParse(dimension);
        return location == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, location));
    }

    static ServerLevel level(MinecraftServer server, String dimension) {
        var level = levelOrNull(server, dimension);
        if (level == null) throw new Failure(Code.UNAVAILABLE, "dimension " + dimension + " is not loaded");
        return level;
    }

    static boolean loaded(ServerLevel level, BlockPos pos) {
        return level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())) != null;
    }

    static Entity entity(MinecraftServer server, UUID id) {
        for (var level : server.getAllLevels()) {
            var entity = level.getEntity(id);
            if (entity != null && !entity.isRemoved()) return entity;
        }
        return null;
    }

    private LiveHolder root(MinecraftServer server, PathRoot root) {
        return switch (root) {
            case PathRoot.Player player -> {
                var found = server.getPlayerList().getPlayer(player.id());
                yield found == null ? null : new LiveHolder.OfPlayer(found);
            }
            case PathRoot.Entity of -> {
                var entity = entity(server, of.id());
                yield entity == null ? null : new LiveHolder.OfEntity(entity);
            }
            case PathRoot.Block block -> {
                var level = levelOrNull(server, block.place().dimension());
                var pos = new BlockPos(block.place().x(), block.place().y(), block.place().z());
                yield level == null || !loaded(level, pos) ? null : new LiveHolder.OfBlock(level, pos);
            }
            case PathRoot.Opened of -> openedRoot(server, hostOrNull(server), of.containerId());
        };
    }

    /** Remembers that the opened id of this menu names this menu instance. */
    synchronized void issueOpened(MinecraftServer server, AbstractContainerMenu menu) {
        forServer(server);
        openedMenus.put(menu.containerId, new WeakReference<>(menu));
    }

    /**
     * The host's open menu while it is still the instance this id was issued for; null for an id never issued on this
     * server, and once the menu closed or any other menu opened, even one that reuses the id.
     */
    synchronized LiveHolder openedRoot(MinecraftServer server, Player host, int containerId) {
        forServer(server);
        var issued = openedMenus.get(containerId);
        if (host == null || issued == null || host.containerMenu == host.inventoryMenu || host.containerMenu != issued.get()
                || host.containerMenu.containerId != containerId) {
            return null;
        }
        return new LiveHolder.OfMenu(host.containerMenu, host.getInventory());
    }

    /** Forgets every issued id once another server, such as a newly opened world, is asked about. */
    private void forServer(MinecraftServer server) {
        if (openedOn.get() == server) return;
        openedMenus.clear();
        openedOn = new WeakReference<>(server);
    }

    private PathResolver resolver(MinecraftServer server) {
        return new PathResolver(registry, root -> root(server, root));
    }

    static Failure pathFailure(ItemPath path, PathResolver.Resolution resolution) {
        var root = new ItemPath(path.root(), List.of()).text();
        return switch (resolution.failure()) {
            case ROOT_GONE -> new Failure(Code.UNAVAILABLE, path.root() instanceof PathRoot.Opened ? "the menu is no longer open"
                    : root + " is not loaded or no longer exists");
            case UNAVAILABLE -> new Failure(Code.UNAVAILABLE, "step " + (resolution.failedStep() + 1) + " of " + path.text() + " cannot be opened");
            default -> new Failure(Code.UNAVAILABLE, "the item at step " + (resolution.failedStep() + 1) + " of " + path.text()
                    + " moved or changed; list its holder again");
        };
    }

    private static EntityHandle handle(Entity entity) {
        return new EntityHandle(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(), entity.getUUID(),
                entity.level().dimension().location().toString(), entity.getX(), entity.getY(), entity.getZ(), entity.getName().getString());
    }

    // Running slices

    Executor worker() {
        return worker;
    }

    MinecraftServer server() {
        var current = server.get();
        if (current == null) throw new Failure(Code.UNAVAILABLE, "world tools need an integrated singleplayer server");
        return current;
    }

    /** Queues the slice on the shared {@link ServerWork}, which steps it within each server tick's budget. */
    <T> CompletionStage<T> run(MinecraftServer server, ServerWork.Slice<T> slice) {
        return run(() -> this.server.get() == server && server.isRunning(), slice);
    }

    <T> CompletionStage<T> run(BooleanSupplier worldOpen, ServerWork.Slice<T> slice) {
        return ServerReads.run(work, worker, worldOpen, slice);
    }

    static <T> ServerWork.Slice<T> once(Supplier<T> read) {
        return then(read, value -> mapped(done(), ignored -> value));
    }

    private static ServerWork.Slice<Boolean> done() {
        return new ServerWork.Slice<>() {
            @Override
            public boolean step() {
                return false;
            }

            @Override
            public Boolean result() {
                return Boolean.TRUE;
            }
        };
    }

    /** Records the game time before the slice's first step and after its last one. */
    static <T> ServerWork.Slice<Spanned<T>> spanned(LongSupplier gameTime, ServerWork.Slice<T> slice) {
        return new ServerWork.Slice<>() {
            private boolean started;
            private long from;
            private long to;

            @Override
            public boolean step() {
                if (!started) {
                    started = true;
                    from = gameTime.getAsLong();
                }
                var more = slice.step();
                to = gameTime.getAsLong();
                return more;
            }

            @Override
            public Spanned<T> result() {
                return new Spanned<>(slice.result(), new ReadSpan(from, to));
            }

            @Override
            public boolean stopEarly() {
                return slice.stopEarly();
            }
        };
    }

    /** The overworld's game time, which every dimension shares; server thread only. */
    private static LongSupplier gameTime(MinecraftServer server) {
        return () -> server.overworld().getGameTime();
    }

    /** Runs first as one step, then the slice it starts. */
    static <A, T> ServerWork.Slice<T> then(Supplier<A> first, Function<A, ServerWork.Slice<T>> next) {
        return new ServerWork.Slice<>() {
            private ServerWork.Slice<T> inner;

            @Override
            public boolean step() {
                if (inner == null) {
                    inner = next.apply(first.get());
                    return true;
                }
                return inner.step();
            }

            @Override
            public T result() {
                return inner.result();
            }

            @Override
            public boolean stopEarly() {
                return inner != null && inner.stopEarly();
            }
        };
    }

    static <A, T> ServerWork.Slice<T> mapped(ServerWork.Slice<A> slice, Function<A, T> map) {
        return new ServerWork.Slice<>() {
            @Override
            public boolean step() {
                return slice.step();
            }

            @Override
            public T result() {
                return map.apply(slice.result());
            }

            @Override
            public boolean stopEarly() {
                return slice.stopEarly();
            }
        };
    }

    static <A, B> ServerWork.Slice<Map.Entry<A, B>> both(ServerWork.Slice<A> first, ServerWork.Slice<B> second) {
        return new ServerWork.Slice<>() {
            private boolean firstDone;

            @Override
            public boolean step() {
                if (!firstDone) {
                    firstDone = !first.step();
                    return true;
                }
                return second.step();
            }

            @Override
            public Map.Entry<A, B> result() {
                return Map.entry(first.result(), second.result());
            }

            /** Stopping inside the first slice stops the second before it starts, so both report what they missed. */
            @Override
            public boolean stopEarly() {
                if (firstDone) return second.stopEarly();
                return first.stopEarly() & second.stopEarly();
            }
        };
    }

    // Cursors

    String remember(String tool, Evidence.JsonObject arguments, Evidence.Scope scope, Object state) {
        var token = UUID.randomUUID().toString();
        synchronized (cursors) {
            cursors.put(token, new Cursor(tool, signature(arguments), scope, state));
            while (cursors.size() > CURSORS) cursors.remove(cursors.keySet().iterator().next());
        }
        return token;
    }

    String remember(PackSageTools.ReadRequest request, Object state) {
        return remember(request.tool(), request.arguments(), request.scope(), state);
    }

    /** The state behind the request's cursor, or null without one. */
    Object cursor(PackSageTools.ReadRequest request) {
        if (!(argument(request, "cursor") instanceof Evidence.JsonString token)) return null;
        Cursor cursor;
        synchronized (cursors) {
            cursor = cursors.get(token.value());
        }
        if (cursor == null || !cursor.tool().equals(request.tool()) || !cursor.signature().equals(signature(request.arguments()))) {
            throw new Failure(Code.INVALID_CURSOR, "cursor does not match this query; restart without a cursor");
        }
        if (!cursor.scope().equals(request.scope())) throw new Failure(Code.STALE_SCOPE, "world cursor scope changed");
        return cursor.state();
    }

    static <T> T state(Object state, Class<T> type) {
        if (!type.isInstance(state)) throw new Failure(Code.INVALID_CURSOR, "cursor does not match this query; restart without a cursor");
        return type.cast(state);
    }

    /** The arguments a cursor is bound to: everything but cursor and limit, with null values dropped. */
    private static Evidence.JsonObject signature(Evidence.JsonObject arguments) {
        var values = new LinkedHashMap<String, Evidence.JsonValue>();
        arguments.values().forEach((key, value) -> {
            if (!key.equals("cursor") && !key.equals("limit") && !(value instanceof Evidence.JsonNull)) values.put(key, withoutNulls(value));
        });
        return new Evidence.JsonObject(values);
    }

    private static Evidence.JsonValue withoutNulls(Evidence.JsonValue value) {
        if (!(value instanceof Evidence.JsonObject object)) return value;
        var values = new LinkedHashMap<String, Evidence.JsonValue>();
        object.values().forEach((key, nested) -> {
            if (!(nested instanceof Evidence.JsonNull)) values.put(key, withoutNulls(nested));
        });
        return new Evidence.JsonObject(values);
    }

    // Arguments

    static Evidence.JsonValue argument(PackSageTools.ReadRequest request, String name) {
        var value = request.arguments().values().get(name);
        return value instanceof Evidence.JsonNull ? null : value;
    }

    static String requiredText(PackSageTools.ReadRequest request, String name) {
        if (!(argument(request, name) instanceof Evidence.JsonString value)) throw invalid(name + " must be a string");
        return value.value();
    }

    static Failure invalid(String message) {
        return new Failure(Code.INVALID_ARGUMENTS, message);
    }

    public static Anchor parseAnchor(Evidence.JsonValue value) {
        return anchor(value);
    }

    static Anchor anchor(Evidence.JsonValue value) {
        if (value == null) return new Anchor.Player();
        if (!(value instanceof Evidence.JsonObject object) || !(object.values().get("type") instanceof Evidence.JsonString type)) {
            throw invalid("center must be an anchor object with a type");
        }
        var keys = new HashSet<String>();
        object.values().forEach((key, field) -> {
            if (!(field instanceof Evidence.JsonNull)) keys.add(key);
        });
        return switch (type.value()) {
            case "player" -> {
                if (!keys.equals(Set.of("type"))) throw invalid("a player anchor takes no id or place");
                yield new Anchor.Player();
            }
            case "entity" -> {
                if (!keys.equals(Set.of("type", "id")) || !(object.values().get("id") instanceof Evidence.JsonString id)) {
                    throw invalid("an entity anchor takes exactly an id");
                }
                yield new Anchor.OfEntity(uuid(id.value()));
            }
            case "block" -> {
                if (!keys.equals(Set.of("type", "place")) || !(object.values().get("place") instanceof Evidence.JsonString place)) {
                    throw invalid("a block anchor takes exactly a place");
                }
                yield new Anchor.OfBlock(place(place.value()));
            }
            case "note" -> {
                if (!keys.equals(Set.of("type", "id")) || !(object.values().get("id") instanceof Evidence.JsonString id)
                        || !NoteBook.isId(id.value())) throw invalid("a note anchor takes exactly a saved note id");
                yield new Anchor.OfNote(id.value());
            }
            default -> throw invalid("anchor type must be player, entity, block or note");
        };
    }

    static BlockPlace place(String text) {
        BlockPlace place;
        try {
            place = BlockPlace.parse(text);
        } catch (IllegalArgumentException exception) {
            throw invalid("place must be <dimension>|x,y,z, such as minecraft:overworld|10,64,-3: " + text);
        }
        var dimension = ResourceLocation.tryParse(place.dimension());
        if (dimension == null || !dimension.toString().equals(place.dimension())) throw invalid("invalid dimension: " + place.dimension());
        return place;
    }

    static UUID uuid(String text) {
        try {
            var id = UUID.fromString(text);
            if (id.toString().equals(text)) return id;
        } catch (IllegalArgumentException ignored) {
        }
        throw invalid("invalid entity id: " + text);
    }

    private static ItemPath itemPath(String text) {
        ItemPath path;
        try {
            path = ItemPath.parse(text);
        } catch (IllegalArgumentException exception) {
            throw invalid("invalid item path: " + text);
        }
        return path;
    }

    /** The holder word the text starts with, or null when it starts with none. */
    private static HolderWords.Phrase phrase(String text) {
        try {
            return HolderWords.parse(text);
        } catch (IllegalArgumentException exception) {
            throw invalid("invalid item path: " + text);
        }
    }

    private static InventoryId inventoryId(String text) {
        try {
            return InventoryId.parse(text);
        } catch (IllegalArgumentException exception) {
            throw invalid("inventory must be <adapter>/<section>, such as minecraft/container: " + text);
        }
    }

    private static int radius(Evidence.JsonValue value) {
        if (value == null) return DEFAULT_RADIUS;
        if (value instanceof Evidence.JsonNumber number) {
            try {
                var radius = number.value().intValueExact();
                if (radius >= 1 && radius <= MAX_RADIUS) return radius;
            } catch (ArithmeticException ignored) {
            }
        }
        throw invalid("radius must be an integer from 1 to " + MAX_RADIUS);
    }

    /** Parses a filter; only item filters may carry tooltip fragments. */
    private static KindFilter filter(Evidence.JsonValue value, boolean items) {
        if (!(value instanceof Evidence.JsonObject object)) throw invalid("filter must be an object");
        var text = object.values().get("text");
        if (text != null && !(text instanceof Evidence.JsonNull) && !(text instanceof Evidence.JsonString)) throw invalid("filter.text must be a string");
        var tooltip = object.values().get("tooltip");
        if (!items && tooltip != null && !(tooltip instanceof Evidence.JsonNull)) throw invalid("filter.tooltip applies only to list_items and find_items");
        return new KindFilter(strings(object.values().get("ids"), "filter.ids"), strings(object.values().get("tags"), "filter.tags"),
                text instanceof Evidence.JsonString string ? string.value() : null, strings(tooltip, "filter.tooltip"));
    }

    private static List<String> strings(Evidence.JsonValue value, String name) {
        if (value == null || value instanceof Evidence.JsonNull) return List.of();
        if (!(value instanceof Evidence.JsonArray array)) throw invalid(name + " must be an array of strings");
        var result = new ArrayList<String>();
        for (var item : array.values()) {
            if (!(item instanceof Evidence.JsonString string)) throw invalid(name + " must be an array of strings");
            result.add(string.value());
        }
        return result;
    }

    // Records

    /** The block page's note, the notable cap, a cut entity list's list_entities pointer, then the entity page's load notes. */
    static List<String> areaNotes(Page<BlockSummary> blocks, Page<EntityHandle> entities) {
        var notes = new LinkedHashSet<String>();
        if (blocks.note() != null) notes.add(blocks.note());
        if (blocks.items().getFirst().notable().size() >= AreaScan.SUMMARY_NOTABLE) {
            notes.add("Notable blocks reached the " + AreaScan.SUMMARY_NOTABLE + " cap; use list_blocks with a filter for the rest");
        }
        // inspect_area returns no cursor, so a cut entity page points at list_entities and keeps only its load notes,
        // never its cursor instructions.
        if (entities.nextCursor() != null) {
            notes.add(entities.items().size() >= AreaScan.LIST_PAGE
                    ? "More than " + AreaScan.LIST_PAGE + " entities are in range; use list_entities with a filter for the rest"
                    : "The entity scan stopped at the per-call work limit; use list_entities for the rest");
        }
        if (entities.loadNote() != null) notes.add(entities.loadNote());
        return List.copyOf(notes);
    }

    private Evidence.Result area(Evidence.Scope scope, Anchor anchor, int radius, AreaRead read, ReadSpan span) {
        var summary = read.blocks().items().getFirst();
        var notes = areaNotes(read.blocks(), read.entities());
        var notable = new TreeMap<String, Map<String, List<Evidence.JsonValue>>>();
        for (var block : summary.notable()) {
            var place = block.handle().place();
            notable.computeIfAbsent(block.role(), key -> new TreeMap<>()).computeIfAbsent(block.handle().kind(), key -> new ArrayList<>())
                    .add(text(place.x() + "," + place.y() + "," + place.z()));
        }
        var roles = new LinkedHashMap<String, Evidence.JsonValue>();
        notable.forEach((role, kinds) -> {
            var byKind = new LinkedHashMap<String, Evidence.JsonValue>();
            kinds.forEach((kind, places) -> byKind.put(kind, new Evidence.JsonArray(places)));
            roles.put(role, new Evidence.JsonObject(byKind));
        });
        var counts = new LinkedHashMap<String, Evidence.JsonValue>();
        summary.counts().forEach((kind, count) -> counts.put(kind, number(count)));
        var center = new LinkedHashMap<String, Evidence.JsonValue>();
        center.put("dimension", text(read.center().dimension()));
        center.put("x", decimal(read.center().position().x));
        center.put("y", decimal(read.center().position().y));
        center.put("z", decimal(read.center().position().z));
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("center", new Evidence.JsonObject(center));
        payload.put("radius", number(radius));
        payload.put("blockCounts", new Evidence.JsonObject(counts));
        payload.put("notable", new Evidence.JsonObject(roles));
        payload.put("notableCount", number(summary.notable().size()));
        payload.put("unloadedChunks", number(summary.unloadedChunks()));
        putSpan(payload, span);
        payload.put("entities", new Evidence.JsonArray(read.entities().items().stream()
                .map(entity -> (Evidence.JsonValue) new Evidence.JsonObject(entityFacts(entity))).toList()));
        var coverage = notes.isEmpty() && read.blocks().coverage() == Evidence.Coverage.COMPLETE
                && read.entities().coverage() == Evidence.Coverage.COMPLETE ? Evidence.Coverage.COMPLETE : Evidence.Coverage.PARTIAL;
        var handle = "area|" + anchorText(anchor) + "|" + radius;
        var record = evidence(scope, handle, Evidence.RecordKind.OTHER, "Area within " + radius + " blocks of " + anchorText(anchor),
                payload, coverage, null);
        return result(List.of(record), coverage, null, notes.isEmpty() ? null : String.join("; ", notes));
    }

    private Evidence.Record blockRecord(Evidence.Scope scope, BlockHandle block, ReadSpan span) {
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("block", text(block.kind()));
        payload.put("place", text(block.place().text()));
        putSpan(payload, span);
        return evidence(scope, "block|" + block.place().text(), Evidence.RecordKind.BLOCK, block.kind() + " at " + block.place().text(),
                payload, Evidence.Coverage.COMPLETE, WorldNavigation.blockId(block.place()));
    }

    private Evidence.Record entityRecord(Evidence.Scope scope, EntityHandle entity, ReadSpan span) {
        var payload = new LinkedHashMap<>(entityFacts(entity));
        putSpan(payload, span);
        return evidence(scope, "entity|" + entity.id(), Evidence.RecordKind.OTHER, entityTitle(entity), payload,
                Evidence.Coverage.COMPLETE, WorldNavigation.entityId(entity.id()));
    }

    /** The listed holder's inventories with their empty slots, and the holder's own facts; only blocks and entities link. */
    private static Evidence.Record summaryRecord(Evidence.Scope scope, HolderSummary summary, ReadSpan span, Evidence.Coverage coverage) {
        var holder = new ItemPath(summary.root(), List.of()).text();
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("holder", text(holder));
        payload.put("inventories", new Evidence.JsonArray(summary.inventories().stream().map(inventory -> {
            var value = new LinkedHashMap<String, Evidence.JsonValue>();
            value.put("inventory", text(inventory.info().id().text()));
            value.put("label", text(inventory.info().label()));
            value.put("slots", number(inventory.info().slots()));
            value.put("role", text(inventory.info().role().name().toLowerCase(java.util.Locale.ROOT)));
            value.put("emptySlots", inventory.emptySlots() == null ? new Evidence.JsonNull() : number(inventory.emptySlots()));
            return (Evidence.JsonValue) new Evidence.JsonObject(value);
        }).toList()));
        payload.putAll(summary.facts().values());
        putSpan(payload, span);
        var title = "Inventories of " + holder;
        return switch (summary.root()) {
            case PathRoot.Block of -> evidence(scope, "block|" + of.place().text(), Evidence.RecordKind.BLOCK, title, payload,
                    coverage, WorldNavigation.blockId(of.place()));
            case PathRoot.Entity of -> evidence(scope, "entity|" + of.id(), Evidence.RecordKind.OTHER, title, payload,
                    coverage, WorldNavigation.entityId(of.id()));
            case PathRoot.Player player -> evidence(scope, "holder|" + holder, Evidence.RecordKind.OTHER, title, payload,
                    coverage, null);
            case PathRoot.Opened opened -> evidence(scope, "holder|" + holder, Evidence.RecordKind.OTHER, title, payload,
                    coverage, null);
        };
    }

    private Evidence.Record entryRecord(Evidence.Scope scope, ItemEntry entry, Map<String, Evidence.JsonObject> slotFacts, ReadSpan span) {
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("item", text(entry.item()));
        payload.put("name", text(entry.name()));
        payload.put("count", number(entry.count()));
        payload.put("variants", number(entry.variants()));
        payload.put("holdsItems", new Evidence.JsonBoolean(entry.holdsItems()));
        payload.put("locations", new Evidence.JsonArray(entry.locations().stream().map(path -> (Evidence.JsonValue) text(path.text())).toList()));
        var slots = new ArrayList<Evidence.JsonValue>();
        for (var location : entry.locations()) {
            var facts = slotFacts.get(location.text());
            if (facts == null) continue;
            var slot = new LinkedHashMap<String, Evidence.JsonValue>();
            slot.put("path", text(location.text()));
            slot.putAll(facts.values());
            slots.add(new Evidence.JsonObject(slot));
        }
        if (!slots.isEmpty()) payload.put("slots", new Evidence.JsonArray(slots));
        putSpan(payload, span);
        var first = entry.locations().getFirst();
        return evidence(scope, "item|" + first.text(), Evidence.RecordKind.ITEM, entry.name() + " x" + entry.count(), payload,
                Evidence.Coverage.COMPLETE, rootTarget(first.root()));
    }

    /** A block or entity holder of listed items, with its highlight target; the player and an open menu have none. */
    private static Evidence.Record holderRecord(Evidence.Scope scope, PathRoot root, ReadSpan span) {
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("holder", text(new ItemPath(root, List.of()).text()));
        return switch (root) {
            case PathRoot.Player player -> null;
            case PathRoot.Opened opened -> null;
            case PathRoot.Block of -> {
                payload.put("place", text(of.place().text()));
                putSpan(payload, span);
                yield evidence(scope, "block|" + of.place().text(), Evidence.RecordKind.BLOCK, "Item holder at " + of.place().text(),
                        payload, Evidence.Coverage.COMPLETE, WorldNavigation.blockId(of.place()));
            }
            case PathRoot.Entity of -> {
                payload.put("id", text(of.id().toString()));
                putSpan(payload, span);
                yield evidence(scope, "entity|" + of.id(), Evidence.RecordKind.OTHER, "Item holder entity " + of.id(),
                        payload, Evidence.Coverage.COMPLETE, WorldNavigation.entityId(of.id()));
            }
        };
    }

    /** Game times in ticks between the read's first and last slice; the world can change in between. */
    static Evidence.JsonObject spanPayload(ReadSpan span) {
        var values = new LinkedHashMap<String, Evidence.JsonValue>();
        values.put("fromGameTime", number(span.fromGameTime()));
        values.put("toGameTime", number(span.toGameTime()));
        return new Evidence.JsonObject(values);
    }

    /**
     * The game time the record was read at, in ticks (20 per second), so game times in its facts can be aged, and the
     * day time (24000 ticks per day, 0 at sunrise).
     */
    private static void putTime(Map<String, Evidence.JsonValue> payload, net.minecraft.world.level.Level level) {
        payload.put("gameTime", number(level.getGameTime()));
        payload.put("dayTime", number(level.getDayTime()));
    }

    private static void putSpan(Map<String, Evidence.JsonValue> payload, ReadSpan span) {
        if (span != null) payload.put("readSpan", spanPayload(span));
    }

    private static Map<String, Evidence.JsonValue> entityFacts(EntityHandle entity) {
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("entity", text(entity.kind()));
        payload.put("id", text(entity.id().toString()));
        payload.put("name", text(entity.name()));
        payload.put("dimension", text(entity.dimension()));
        payload.put("x", decimal(entity.x()));
        payload.put("y", decimal(entity.y()));
        payload.put("z", decimal(entity.z()));
        return payload;
    }

    private static String entityTitle(EntityHandle entity) {
        return entity.name() + " (" + entity.kind() + ") at " + entity.dimension() + "|"
                + Math.round(entity.x()) + "," + Math.round(entity.y()) + "," + Math.round(entity.z());
    }

    private static String anchorText(Anchor anchor) {
        return switch (anchor) {
            case Anchor.Player player -> "player";
            case Anchor.OfEntity of -> "entity:" + of.id();
            case Anchor.OfBlock of -> "block:" + of.place().text();
            case Anchor.OfNote of -> "note:" + of.id();
        };
    }

    /** The highlight target of a path's holder; the player and an open menu have none. */
    private static String rootTarget(PathRoot root) {
        return switch (root) {
            case PathRoot.Player player -> null;
            case PathRoot.Opened opened -> null;
            case PathRoot.Entity of -> WorldNavigation.entityId(of.id());
            case PathRoot.Block of -> WorldNavigation.blockId(of.place());
        };
    }

    static Evidence.Record evidence(Evidence.Scope scope, String handle, Evidence.RecordKind kind, String title,
                                          Map<String, Evidence.JsonValue> payload, Evidence.Coverage coverage, String targetId) {
        var source = new Evidence.Source(scope, ID, VERSION, handle, scope.requestGeneration(), Instant.now(),
                Evidence.Activity.ACTIVE, coverage, Evidence.Authority.SERVER_READ, List.of());
        var targets = targetId == null ? List.<Evidence.NavigationTarget>of() : List.of(new Evidence.NavigationTarget(scope, ID, handle, targetId));
        return new Evidence.Record(source, kind, title, new Evidence.JsonObject(payload), targets);
    }

    private static Evidence.JsonObject sections(Map<String, Section> sections) {
        var values = new LinkedHashMap<String, Evidence.JsonValue>();
        sections.forEach((adapter, section) -> {
            var value = new LinkedHashMap<String, Evidence.JsonValue>();
            value.put("coverage", text(section.coverage().name()));
            value.put("facts", section.facts());
            if (section.note() != null) value.put("note", text(section.note()));
            values.put(adapter, new Evidence.JsonObject(value));
        });
        return new Evidence.JsonObject(values);
    }

    private static Evidence.JsonArray inventories(Page<InventoryInfo> inventories) {
        return new Evidence.JsonArray(inventories.items().stream().map(info -> {
            var value = new LinkedHashMap<String, Evidence.JsonValue>();
            value.put("inventory", text(info.id().text()));
            value.put("label", text(info.label()));
            value.put("slots", number(info.slots()));
            value.put("role", text(info.role().name().toLowerCase(java.util.Locale.ROOT)));
            return (Evidence.JsonValue) new Evidence.JsonObject(value);
        }).toList());
    }

    private static Evidence.Coverage coverage(Map<String, Section> sections, Page<InventoryInfo> inventories) {
        return inventories.coverage() == Evidence.Coverage.COMPLETE
                && sections.values().stream().allMatch(section -> section.coverage() == Evidence.Coverage.COMPLETE)
                ? Evidence.Coverage.COMPLETE : Evidence.Coverage.PARTIAL;
    }

    static Evidence.JsonString text(String value) {
        return new Evidence.JsonString(value);
    }

    static Evidence.JsonNumber number(long value) {
        return new Evidence.JsonNumber(BigDecimal.valueOf(value));
    }

    private static Evidence.JsonNumber decimal(double value) {
        return new Evidence.JsonNumber(BigDecimal.valueOf(Math.round(value * 10) / 10.0));
    }

    private static String describe(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
