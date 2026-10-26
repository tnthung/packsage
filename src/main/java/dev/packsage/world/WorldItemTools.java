package dev.packsage.world;

import dev.packsage.knowledge.Evidence;
import dev.packsage.model.PackSageTools;
import dev.packsage.world.minecraft.MinecraftMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoubleBlockCombiner;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static dev.packsage.world.WorldPaging.*;
import static dev.packsage.world.WorldTools.*;

final class WorldItemTools {
    private static final long TIMEOUT_SECONDS = 60;
    private static final int RESOLVE_PER_STEP = 64;

    private final WorldTools world;

    WorldItemTools(WorldTools world) {
        this.world = world;
    }

    CompletionStage<Evidence.Result> listItems(PackSageTools.ReadRequest request) {
        var holderText = requiredText(request, "holder");
        var phrase = holderText.equals("player") ? null : phrase(holderText);
        var holder = holderText.equals("player") || phrase != null ? null : itemPath(holderText);
        if (holder != null) listable(holder);
        var inventory = argument(request, "inventory") == null ? null : inventoryId(requiredText(request, "inventory"));
        var filter = argument(request, "filter") == null ? KindFilter.ANY : filter(argument(request, "filter"), true);
        var cursor = world.paging.cursor(request);
        var server = world.server();
        if (cursor != null) return CompletableFuture.completedFuture(entries(request, state(cursor, EntriesState.class), Evidence.Coverage.COMPLETE, null));
        var opened = phrase != null ? phrase.word() == HolderWords.Word.OPENED : holder != null && holder.root() instanceof PathRoot.Opened;
        return focus(phrase, opened).thenCompose(focus -> world.run(server, spanned(gameTime(server), once(() -> {
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
    CompletionStage<Evidence.Result> inspectItem(PackSageTools.ReadRequest request) {
        var text = requiredText(request, "path");
        var phrase = phrase(text);
        if (phrase == null) return world.inspectionTools.inspect(request, "item|" + itemPath(text).text());
        if (phrase.steps().isEmpty() && phrase.word() != HolderWords.Word.HELD && phrase.word() != HolderWords.Word.OFFHAND) {
            throw invalid(text + " names a holder, not an item; list its items with list_items");
        }
        var server = world.server();
        return focus(phrase, false).thenCompose(focus -> world.run(server, once(() -> {
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
            captured = world.focus.get().toCompletableFuture();
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
        if (phrase.word() == HolderWords.Word.OPENED && menu >= 0) world.issueOpened(server, host.containerMenu);
        return HolderWords.resolve(phrase, new HolderWords.Situation(host.getUUID(), host.getInventory().selected, kind(host.getMainHandItem()),
                kind(host.getOffhandItem()), focus.targeted(), vehicle == null ? null : vehicle.getUUID(), menu));
    }

    private static String kind(net.minecraft.world.item.ItemStack stack) {
        return stack.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    CompletionStage<Evidence.Result> findItems(PackSageTools.ReadRequest request) {
        var filter = filter(argument(request, "filter"), true);
        var anchor = anchor(argument(request, "center"));
        var radius = radius(argument(request, "radius"));
        var includeNested = !(argument(request, "includeNested") instanceof Evidence.JsonBoolean nested) || nested.value();
        var cursor = world.paging.cursor(request);
        var server = world.server();
        var state = cursor == null ? null : state(cursor, EntriesState.class);
        if (state != null && !state.pending().isEmpty()) {
            return CompletableFuture.completedFuture(entries(request, state, Evidence.Coverage.COMPLETE, null));
        }
        return world.run(server, spanned(gameTime(server), new FindSlice(server, anchor, radius, filter, includeNested, state == null ? null : state.search())))
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
        // Measure entries and their holder links before advancing the pending entry cursor. Leave room for the
        // result envelope beneath the outer encoder's 128 KiB cap, as list_entities does.
        // The holder summary leads the first page and counts toward it; its root already has its record there.
        var summary = state.summary();
        var lead = summary == null ? 0 : 1;
        var roots = new LinkedHashSet<PathRoot>();
        var records = new ArrayList<Evidence.Record>();
        var holders = new ArrayList<Evidence.Record>();
        var bytes = 0;
        if (summary != null) {
            var record = summaryRecord(request.scope(), summary, state.span(), sticky);
            records.add(record);
            bytes += PackSageTools.encodeRecord(record).toString().getBytes(StandardCharsets.UTF_8).length + 1;
        }
        var shownCount = 0;
        for (var entry : state.pending()) {
            var added = new LinkedHashSet<PathRoot>();
            for (var location : entry.locations()) {
                var root = location.root();
                if (!(root instanceof PathRoot.Player) && !(root instanceof PathRoot.Opened) && !roots.contains(root) && (summary == null || !summary.root().equals(root))) added.add(root);
            }
            var record = entryRecord(request.scope(), entry, state.slotFacts(), state.span());
            var addedHolders = new ArrayList<Evidence.Record>();
            var addedBytes = PackSageTools.encodeRecord(record).toString().getBytes(StandardCharsets.UTF_8).length + 1;
            for (var root : added) {
                var holder = holderRecord(request.scope(), root, state.span());
                var holderBytes = PackSageTools.encodeRecord(holder).toString().getBytes(StandardCharsets.UTF_8).length + 1;
                if (lead + shownCount + 1 + holders.size() + addedHolders.size() >= AreaScan.LIST_PAGE
                        || bytes + addedBytes + holderBytes > 64 * 1024) break;
                addedHolders.add(holder);
                addedBytes += holderBytes;
            }
            if (shownCount > 0 && (lead + shownCount + 1 + roots.size() + added.size() > AreaScan.LIST_PAGE
                    || bytes + addedBytes > 64 * 1024 || addedHolders.size() < added.size())) break;
            if (bytes + addedBytes > 64 * 1024) throw new Failure(Code.EXTRACTION_FAILED, "one item record exceeds the page byte budget");
            records.add(record);
            holders.addAll(addedHolders);
            bytes += addedBytes;
            roots.addAll(added);
            shownCount++;
            if (record.source().coverage() == Evidence.Coverage.PARTIAL) {
                sticky = Evidence.Coverage.PARTIAL;
                notes.add("Item names longer than 1,024 characters were truncated; nameTruncated marks the affected records");
            }
        }
        var rest = List.copyOf(state.pending().subList(shownCount, state.pending().size()));
        var merged = notes.isEmpty() ? null : String.join("; ", notes);
        var next = rest.isEmpty() && state.search() == null ? null
                : world.paging.remember(request, new EntriesState(rest, state.search(), sticky, merged, state.span(), null, state.slotFacts()));
        if (bound != null) notes.add(bound);
        if (!rest.isEmpty()) notes.add("This page shows " + shownCount + " entries, the page limit with their holders and encoded byte budget; continue with the cursor");
        // One record per block or entity holder on the page, so each holder has its own highlight link.
        records.addAll(holders);
        var linked = holders.size();
        if (linked < roots.size()) {
            notes.add("Highlight links are shown for " + linked + " of " + roots.size() + " holders of the first entry"
                    + "; list or inspect the others by their item paths");
        }
        return result(records, sticky, next, notes.isEmpty() ? null : String.join("; ", notes));
    }

    /** Reads one item by its concrete path; server thread only. */
    Evidence.Record inspectItem(MinecraftServer server, ItemPath path, Evidence.Scope scope) {
        var resolution = world.resolver(server).resolve(path);
        if (resolution.failure() != PathResolver.Failure.NONE) throw pathFailure(path, resolution);
        var stack = resolution.stack();
        var inventories = world.registry.inventories(new LiveHolder.OfItem(stack));
        var sections = world.registry.inspectItem(stack);
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
        var resolution = world.resolver(server).resolve(path);
        if (resolution.failure() != PathResolver.Failure.NONE) throw pathFailure(path, resolution);
        var notes = new LinkedHashSet<String>();
        var listed = world.registry.inventories(resolution.holder());
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
            var reader = world.registry.inventoryReader(info.id().adapter());
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
                var nested = world.registry.inventories(new LiveHolder.OfItem(stack));
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
        private List<BlockPos> blocks;
        private AreaScan.EntitySnapshot snapshot;
        private NavigableSet<UUID> entities;
        private final Set<UUID> seenEntities = new HashSet<>();
        private int blockIndex;
        private int blockCursor;
        private boolean denseBlocks;
        private Long startedAt;
        private long fromGameTime;

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
            if (level != null && startedAt == null) {
                startedAt = AreaScan.serverTick(level);
                fromGameTime = level.getGameTime();
            }
            if (search != null) return search.step();
            if (resume != null && chunks == null) {
                resolveRoots();
            } else if (chunks == null) {
                host = host(server);
                var start = world.areaTools.center(server, anchor);
                level = level(server, start.dimension());
                center = start.position();
                holders.add(Map.entry(new PathRoot.Player(host.getUUID()), new LiveHolder.OfPlayer(host)));
                chunks = AreaScan.chunksInRange(center, radius);
            } else if (next < chunks.size()) {
                if (collect(chunks.get(next))) next++;
            } else {
                search = new ItemSearch(world.registry, holders, filter, ItemTooltips.of(host(server))::lines, includeNested,
                        resumedCursor(resume, firstRootFound));
            }
            return true;
        }

        /** Re-resolves the continuation's roots, then picks up gathering where the earlier page stopped, if it did. */
        private void resolveRoots() {
            var end = Math.min(resume.roots().size(), resolved + RESOLVE_PER_STEP);
            for (; resolved < end; resolved++) {
                var root = resume.roots().get(resolved);
                var holder = world.root(server, root);
                if (holder == null) {
                    gone++;
                    continue;
                }
                if (resolved == 0) firstRootFound = true;
                if (root instanceof PathRoot.Entity entity) seenEntities.add(entity.id());
                if (root instanceof PathRoot.Player player) seenEntities.add(player.id());
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
            if (chunks == null) return false;
            if (blocks != null || denseBlocks || snapshot != null) return false;
            if (!gatherCanStop(chunks.size(), next, startChunk)) return false;
            stopped = true;
            return true;
        }

        /** Copies bounded plain keys once per chunk, then revalidates at most 64 holders per step. */
        private boolean collect(ChunkPos pos) {
            var chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk == null) {
                unloaded++;
                blocks = null;
                entities = null;
                snapshot = null;
                blockCursor = 0;
                denseBlocks = false;
                return true;
            }
            if (blocks == null) {
                blocks = new ArrayList<>();
                if (blockCursor == 0) denseBlocks = chunk.getBlockEntities().size() > AreaScan.SNAPSHOT_LIMIT;
                if (denseBlocks) {
                    // Dense chunks use a deterministic position anchor; no mutable map iterator survives a step.
                    var end = Math.min(level.getHeight() * 256, blockCursor + AreaScan.SNAPSHOT_LIMIT);
                    for (; blockCursor < end; blockCursor++) {
                        var at = new BlockPos(pos.getMinBlockX() + blockCursor % 16, level.getMinBuildHeight() + blockCursor / 256,
                                pos.getMinBlockZ() + blockCursor / 16 % 16);
                        if (chunk.getBlockEntities().containsKey(at)) blocks.add(at);
                    }
                } else {
                    for (var at : chunk.getBlockEntities().keySet()) blocks.add(at.immutable());
                }
                blockIndex = 0;
                return false;
            }
            if (blockIndex < blocks.size()) {
                var dimension = level.dimension().location().toString();
                for (int read = 0; read < RESOLVE_PER_STEP && blockIndex < blocks.size(); read++) {
                    var at = blocks.get(blockIndex++);
                    // getBlockEntity can promote pending NBT even with CHECK; read only the existing map.
                    var blockEntity = chunk.getBlockEntities().get(at);
                    if (blockEntity == null || blockEntity.isRemoved()) {
                        notes.add("A block entity from a chunk snapshot disappeared during the read");
                        continue;
                    }
                    if (Vec3.atCenterOf(at).distanceToSqr(center) > (double) radius * radius) continue;
                    var state = chunk.getBlockState(at);
                    if (state.getBlock() instanceof EnderChestBlock) continue;
                    var doubleChest = state.getBlock() instanceof ChestBlock && ChestBlock.getBlockType(state) != DoubleBlockCombiner.BlockType.SINGLE;
                    if (doubleChest && chestHalves.contains(at.relative(ChestBlock.getConnectedDirection(state)))) continue;
                    var holder = new LiveHolder.OfBlock(level, at);
                    if (!holds(holder)) continue;
                    if (doubleChest) chestHalves.add(at);
                    holders.add(Map.entry(new PathRoot.Block(new BlockPlace(dimension, at.getX(), at.getY(), at.getZ())), holder));
                }
                return false;
            }
            if (denseBlocks && blockCursor < level.getHeight() * 256) {
                blocks = null;
                return false;
            }
            if (entities == null) {
                if (!level.areEntitiesLoaded(pos.toLong())) {
                    entitiesUnloaded++;
                    blocks = null;
                    snapshot = null;
                    blockCursor = 0;
                    denseBlocks = false;
                    return true;
                }
                if (snapshot == null) snapshot = new AreaScan.EntitySnapshot();
                if (snapshot.step(level, pos, notes)) return false;
                entities = snapshot.ids;
                return false;
            }
            if (level.areEntitiesLoaded(pos.toLong())) {
                for (int read = 0; read < RESOLVE_PER_STEP && !entities.isEmpty(); read++) {
                    var id = entities.pollFirst();
                    var entity = level.getEntity(id);
                    if (entity == null || entity.isRemoved()) {
                        notes.add("An entity from a chunk snapshot disappeared during the read");
                        continue;
                    }
                    if (!seenEntities.add(id) || entity == host || entity.position().distanceToSqr(center) > (double) radius * radius) continue;
                    var x = SectionPos.blockToSectionCoord(entity.getBlockX());
                    var z = SectionPos.blockToSectionCoord(entity.getBlockZ());
                    if (level.getChunkSource().getChunkNow(x, z) == null || !level.areEntitiesLoaded(ChunkPos.asLong(x, z))) {
                        notes.add("An entity moved outside loaded chunks during the read");
                        continue;
                    }
                    LiveHolder holder = entity instanceof ServerPlayer player ? new LiveHolder.OfPlayer(player) : new LiveHolder.OfEntity(entity);
                    if (!holds(holder)) continue;
                    holders.add(Map.entry(entity instanceof ServerPlayer ? new PathRoot.Player(id) : new PathRoot.Entity(id), holder));
                }
                if (!entities.isEmpty()) return false;
            } else {
                entitiesUnloaded++;
            }
            blocks = null;
            entities = null;
            snapshot = null;
            blockCursor = 0;
            denseBlocks = false;
            return true;
        }

        private boolean holds(LiveHolder holder) {
            var inventories = world.registry.inventories(holder);
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
            if (startedAt != null && AreaScan.serverTick(level) != startedAt) all.add(AreaScan.SNAPSHOT_NOTE.formatted(fromGameTime, level.getGameTime()));
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

    private static Evidence.Record entryRecord(Evidence.Scope scope, ItemEntry entry, Map<String, Evidence.JsonObject> slotFacts, ReadSpan span) {
        var truncated = entry.name().length() > 1024;
        var name = truncated ? entry.name().substring(0, Character.isHighSurrogate(entry.name().charAt(1023)) ? 1023 : 1024) : entry.name();
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("item", text(entry.item()));
        payload.put("name", text(name));
        payload.put("nameTruncated", new Evidence.JsonBoolean(truncated));
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
        return evidence(scope, "item|" + first.text(), Evidence.RecordKind.ITEM, name + " x" + entry.count(), payload,
                truncated ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE, rootTarget(first.root()));
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

    /** The highlight target of a path's holder; the player and an open menu have none. */
    private static String rootTarget(PathRoot root) {
        return switch (root) {
            case PathRoot.Player player -> null;
            case PathRoot.Opened opened -> null;
            case PathRoot.Entity of -> WorldNavigation.entityId(of.id());
            case PathRoot.Block of -> WorldNavigation.blockId(of.place());
        };
    }
}
