package dev.packsage.world;

import dev.packsage.journal.NoteBook;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.model.PackSageTools;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
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
    /** Encoded-size budgets inside one inspect_area record, well under the 128 KiB tool output limit. */
    static final int AREA_NOTABLE_BYTES = 32 * 1024;
    static final int AREA_COUNT_BYTES = 16 * 1024;

    /** A resolved scan center, kept in list cursors so later pages scan the same sphere. */
    record Center(String dimension, Vec3 position) {
    }

    record ListState(Center center, String scan, Page<BlockHandle> pending, WorldPaging.ReadSpan span) {
        ListState(Center center, String scan) {
            this(center, scan, null, null);
        }
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
    record EntriesState(List<ItemEntry> pending, FindState search, Evidence.Coverage coverage, String note, WorldPaging.ReadSpan span,
                        HolderSummary summary, Map<String, Evidence.JsonObject> slotFacts) {
        EntriesState(List<ItemEntry> pending, FindState search, Evidence.Coverage coverage, String note, WorldPaging.ReadSpan span) {
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
    /**
     * Note is sticky for later pages; bound says why only this page was cut. Summary is null for searches and nested
     * holders; slot facts are keyed by location path text.
     */
    record ItemsRead(List<ItemEntry> entries, FindState search, Evidence.Coverage coverage, String note, String bound,
                     HolderSummary summary, Map<String, Evidence.JsonObject> slotFacts) {
        ItemsRead(List<ItemEntry> entries, FindState search, Evidence.Coverage coverage, String note, String bound) {
            this(entries, search, coverage, note, bound, null, Map.of());
        }
    }

    /** A read behind a holder word, or why the word did not resolve. */
    record Worded<T>(T value, String unresolved) {
    }

    private final Supplier<MinecraftServer> server;
    final WorldRegistry registry;
    private final Supplier<Evidence.Scope> scope;
    final AreaScan scan;
    private final Executor worker;
    private final ServerWork work;
    private final WorldNavigation navigation;
    private final Function<String, BlockPlace> notePlaces;
    final Supplier<CompletionStage<ClientFocus>> focus;
    private final WorldKnowledgeTools knowledge;
    final WorldAreaTools areaTools;
    final WorldInspectionTools inspectionTools;
    final WorldItemTools itemTools;
    final WorldPaging paging = new WorldPaging();
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
        this(client::getSingleplayerServer, registry, scope, worker, work, focus,
                new WorldNavigation(client::getSingleplayerServer, WorldNavigation.client(client)), notePlaces);
    }

    /** Highlights check {@code blockExists} and {@code entityPlace} before drawing through {@code client}. */
    WorldTools(Supplier<MinecraftServer> server, WorldRegistry registry, Supplier<Evidence.Scope> scope, Executor worker, ServerWork work,
               Supplier<CompletionStage<ClientFocus>> focus, Function<BlockPlace, Boolean> blockExists, Function<UUID, BlockPlace> entityPlace, WorldNavigation.Client client) {
        this(server, registry, scope, worker, work, focus, blockExists, entityPlace, client, id -> null);
    }

    WorldTools(Supplier<MinecraftServer> server, WorldRegistry registry, Supplier<Evidence.Scope> scope, Executor worker, ServerWork work,
               Supplier<CompletionStage<ClientFocus>> focus, Function<BlockPlace, Boolean> blockExists, Function<UUID, BlockPlace> entityPlace,
               WorldNavigation.Client client, Function<String, BlockPlace> notePlaces) {
        this(server, registry, scope, worker, work, focus, new WorldNavigation(blockExists, entityPlace, client), notePlaces);
    }

    private WorldTools(Supplier<MinecraftServer> server, WorldRegistry registry, Supplier<Evidence.Scope> scope, Executor worker, ServerWork work,
                       Supplier<CompletionStage<ClientFocus>> focus, WorldNavigation navigation, Function<String, BlockPlace> notePlaces) {
        this.server = server;
        this.registry = registry;
        this.scope = scope;
        this.worker = worker;
        this.work = work;
        this.focus = focus;
        this.scan = new AreaScan(registry);
        this.navigation = navigation;
        this.notePlaces = notePlaces;
        this.knowledge = new WorldKnowledgeTools(this);
        this.areaTools = new WorldAreaTools(this);
        this.inspectionTools = new WorldInspectionTools(this);
        this.itemTools = new WorldItemTools(this);
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
                case "inspect_area" -> areaTools.inspectArea(request);
                case "list_blocks" -> areaTools.listBlocks(request);
                case "list_entities" -> areaTools.listEntities(request);
                case "inspect_block" -> inspectionTools.inspect(request, "block|" + place(requiredText(request, "place")).text());
                case "inspect_entity" -> inspectionTools.inspect(request, "entity|" + uuid(requiredText(request, "id")));
                case "list_items" -> itemTools.listItems(request);
                case "inspect_item" -> itemTools.inspectItem(request);
                case "find_items" -> itemTools.findItems(request);
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
            return reread(reference.sourceHandle(), request.scope()).thenApply(WorldPaging::single);
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
            return reread(handle.value(), scope).thenApply(WorldPaging::single).exceptionally(failure -> failed(failure));
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
        if (!scope.equals(this.scope.get()) || !scope.equals(target.scope())) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.STALE, "observation scope changed");
        }
        return navigation.open(target);
    }

    private static Evidence.Result failed(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        return new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null, failure.getMessage());
    }

    /** Reads one block, entity, item, holder, loot or command handle fresh. */
    CompletionStage<Evidence.Record> reread(String handle, Evidence.Scope scope) {
        if (handle.startsWith("block|")) {
            var place = place(handle.substring(6));
            var server = server();
            return run(server, WorldPaging.once(() -> inspectionTools.inspectBlock(server, place, scope)));
        }
        if (handle.startsWith("entity|")) {
            var id = uuid(handle.substring(7));
            var server = server();
            return run(server, WorldPaging.once(() -> inspectionTools.inspectEntity(server, id, scope))).thenApply(record -> {
                // This continuation runs on the worker. Leave room for source/target metadata and the tool envelope.
                if (Evidence.serialize(record.payload()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        + record.title().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 65_536) return record;
                var payload = new LinkedHashMap<>(record.payload().values());
                var sections = new LinkedHashMap<>(((Evidence.JsonObject) payload.get("sections")).values());
                record Detail(String adapter, String key, int bytes) {
                }
                var details = new ArrayList<Detail>();
                sections.forEach((adapter, value) -> ((Evidence.JsonObject) ((Evidence.JsonObject) value).values().get("facts"))
                        .values().forEach((key, fact) -> details.add(new Detail(adapter, key,
                                Evidence.serialize(fact).getBytes(java.nio.charset.StandardCharsets.UTF_8).length))));
                details.sort(Comparator.comparingInt(Detail::bytes).reversed());
                payload.put("note", text("Entity inspection details exceed the 64 KiB byte budget; the largest facts were omitted, coverage is partial"));
                for (var detail : details) {
                    if (Evidence.serialize(new Evidence.JsonObject(payload)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 60_000) break;
                    var section = new LinkedHashMap<>(((Evidence.JsonObject) sections.get(detail.adapter())).values());
                    var facts = new LinkedHashMap<>(((Evidence.JsonObject) section.get("facts")).values());
                    facts.remove(detail.key());
                    section.put("facts", new Evidence.JsonObject(facts));
                    section.put("coverage", text("PARTIAL"));
                    section.put("note", text((section.get("note") instanceof Evidence.JsonString previous ? previous.value() + "; " : "")
                            + detail.key() + " omitted for the byte budget"));
                    sections.put(detail.adapter(), new Evidence.JsonObject(section));
                    payload.put("sections", new Evidence.JsonObject(sections));
                }
                if (Evidence.serialize(new Evidence.JsonObject(payload)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 60_000) {
                    // Oversized metadata can still require the compact identity fallback after trimming all facts.
                    payload.keySet().retainAll(List.of("entity", "id", "dimension", "x", "y", "z", "holder", "gameTime", "dayTime", "note"));
                    payload.put("sections", new Evidence.JsonObject(Map.of()));
                }
                return evidence(scope, record.source().sourceHandle(), record.kind(), "Entity " + id, payload,
                        Evidence.Coverage.PARTIAL, WorldNavigation.entityId(id));
            });
        }
        if (handle.startsWith("item|")) {
            var path = itemPath(handle.substring(5));
            if (path.steps().isEmpty()) throw invalid(path.text() + " names a holder, not an item; list its items with list_items");
            var server = server();
            return run(server, WorldPaging.once(() -> itemTools.inspectItem(server, path, scope)));
        }
        if (handle.startsWith("loot|") || handle.startsWith("command|")) return knowledge.reread(handle, scope);
        if (handle.startsWith("holder|")) {
            return itemTools.listItems(new PackSageTools.ReadRequest("list_items", new Evidence.JsonObject(Map.of(
                    "holder", text(handle.substring(7)))), scope)).thenApply(result -> {
                if (result.nextCursor() != null) {
                    paging.discard(result.nextCursor());
                }
                return result.records().stream().filter(record -> record.source().sourceHandle().equals(handle)).findFirst()
                        .orElseThrow(() -> new Failure(Code.INVALID_REFERENCE, "holder summary is no longer available; repeat list_items"));
            });
        }
        throw new Failure(Code.INVALID_REFERENCE, "only block, entity, item, holder, loot and command records can be reread; repeat the lookup that produced it");
    }

    // Server-thread reads

    public CompletionStage<BlockPlace> resolveAnchor(Anchor anchor) {
        try {
            var server = server();
            return run(server, WorldPaging.once(() -> {
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

    LiveHolder root(MinecraftServer server, PathRoot root) {
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

    PathResolver resolver(MinecraftServer server) {
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

    static ItemPath itemPath(String text) {
        ItemPath path;
        try {
            path = ItemPath.parse(text);
        } catch (IllegalArgumentException exception) {
            throw invalid("invalid item path: " + text);
        }
        return path;
    }

    /** The holder word the text starts with, or null when it starts with none. */
    static HolderWords.Phrase phrase(String text) {
        try {
            return HolderWords.parse(text);
        } catch (IllegalArgumentException exception) {
            throw invalid("invalid item path: " + text);
        }
    }

    static InventoryId inventoryId(String text) {
        try {
            return InventoryId.parse(text);
        } catch (IllegalArgumentException exception) {
            throw invalid("inventory must be <adapter>/<section>, such as minecraft/container: " + text);
        }
    }

    static int radius(Evidence.JsonValue value) {
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
    static KindFilter filter(Evidence.JsonValue value, boolean items) {
        if (!(value instanceof Evidence.JsonObject object)) throw invalid("filter must be an object");
        var text = object.values().get("text");
        if (text != null && !(text instanceof Evidence.JsonNull) && !(text instanceof Evidence.JsonString)) throw invalid("filter.text must be a string");
        var tooltip = object.values().get("tooltip");
        if (!items && tooltip != null && !(tooltip instanceof Evidence.JsonNull)) throw invalid("filter.tooltip applies only to list_items and find_items");
        return new KindFilter(strings(object.values().get("ids"), "filter.ids"), strings(object.values().get("tags"), "filter.tags"),
                text instanceof Evidence.JsonString string ? string.value() : null, strings(tooltip, "filter.tooltip"));
    }

    static List<String> strings(Evidence.JsonValue value, String name) {
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

    /** The listed holder's inventories with their empty slots, and the holder's own facts; only blocks and entities link. */
    static Evidence.JsonObject spanPayload(WorldPaging.ReadSpan span) {
        var values = new LinkedHashMap<String, Evidence.JsonValue>();
        values.put("fromGameTime", number(span.fromGameTime()));
        values.put("toGameTime", number(span.toGameTime()));
        return new Evidence.JsonObject(values);
    }

    /**
     * The game time the record was read at, in ticks (20 per second), so game times in its facts can be aged, and the
     * day time (24000 ticks per day, 0 at sunrise).
     */
    static void putTime(Map<String, Evidence.JsonValue> payload, net.minecraft.world.level.Level level) {
        payload.put("gameTime", number(level.getGameTime()));
        payload.put("dayTime", number(level.getDayTime()));
    }

    static void putSpan(Map<String, Evidence.JsonValue> payload, WorldPaging.ReadSpan span) {
        if (span != null) payload.put("readSpan", spanPayload(span));
    }

    /** The highlight target of a path's holder; the player and an open menu have none. */
    static Evidence.Record evidence(Evidence.Scope scope, String handle, Evidence.RecordKind kind, String title,
                                          Map<String, Evidence.JsonValue> payload, Evidence.Coverage coverage, String targetId) {
        var source = new Evidence.Source(scope, ID, VERSION, handle, scope.requestGeneration(), Instant.now(),
                Evidence.Activity.ACTIVE, coverage, Evidence.Authority.SERVER_READ, List.of());
        var targets = targetId == null ? List.<Evidence.NavigationTarget>of() : List.of(new Evidence.NavigationTarget(scope, ID, handle, targetId));
        return new Evidence.Record(source, kind, title, new Evidence.JsonObject(payload), targets);
    }

    static Evidence.JsonObject sections(Map<String, Section> sections) {
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

    static Evidence.JsonArray inventories(Page<InventoryInfo> inventories) {
        return new Evidence.JsonArray(inventories.items().stream().map(info -> {
            var value = new LinkedHashMap<String, Evidence.JsonValue>();
            value.put("inventory", text(info.id().text()));
            value.put("label", text(info.label()));
            value.put("slots", number(info.slots()));
            value.put("role", text(info.role().name().toLowerCase(java.util.Locale.ROOT)));
            return (Evidence.JsonValue) new Evidence.JsonObject(value);
        }).toList());
    }

    static Evidence.Coverage coverage(Map<String, Section> sections, Page<InventoryInfo> inventories) {
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

    static String describe(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
