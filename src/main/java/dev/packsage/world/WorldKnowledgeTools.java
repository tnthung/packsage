package dev.packsage.world;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox.Code;
import dev.packsage.knowledge.Toolbox.Failure;
import dev.packsage.model.PackSageTools;
import dev.packsage.world.minecraft.LocateBiome;
import dev.packsage.world.minecraft.MinecraftLoot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The world-knowledge tools: loot tables, the nearest biome or structure, and the command tree. {@link WorldTools}
 * delegates these tool names and the {@code loot|} and {@code command|} reread handles here, and lends its server
 * access, pacing, cursors and record helpers.
 */
final class WorldKnowledgeTools {
    static final Set<String> TOOLS = Set.of("read_loot", "locate", "search_commands", "read_command");
    static final String UNKNOWN_TABLE = "no loot table has this id in the loaded data packs";
    static final String EMPTY_TABLE = "this table drops nothing";
    static final String NO_PENDING_LOOT = "this container has no pending loot: it was opened already or never had a loot table;"
            + " its contents are read with list_items";

    /** What a loot read found on the server thread. */
    private sealed interface Loot permits LootFetch, NoPendingLoot, NotLiving {
    }

    /**
     * A resolved table as the server step looked it up, not encoded yet, with the registries to encode it against. Both
     * are immutable after a reload. Place and seed are set for a container's pending loot; readAt is the game time.
     */
    record LootFetch(String id, String resolvedFrom, Optional<LootTable> table, HolderLookup.Provider registries, BlockPlace place,
                     Long seed, long readAt) implements Loot {
    }

    /** A fetched table encoded on the worker, when it exists. */
    record LootRead(String id, String resolvedFrom, Optional<MinecraftLoot.Encoded> table, BlockPlace place, Long seed, long readAt) {
    }

    record NoPendingLoot(BlockPlace place, String block, long readAt) implements Loot {
    }

    /** An entity without a death loot table, named by its type and id. */
    private record NotLiving(String entity) implements Loot {
    }

    /** Later pages of one loot read, paged on the worker from the table encoded by the first. */
    private record LootState(LootRead read, int nextPool) {
    }

    /** Continuations keep the original center even if the player moves between calls. */
    record BiomeState(WorldTools.Center center, String query, int radius, int nextIndex) {
    }

    /** Server-owned inputs captured together; registry matching and spiral preparation run on the worker. */
    private record BiomeRead(WorldTools.Center center, Registry<Biome> registry, BiomeSource source, LocateBiome.Sampler sampler,
                             int minY, int maxY) {
    }

    private final WorldTools calls;

    WorldKnowledgeTools(WorldTools calls) {
        this.calls = calls;
    }

    CompletionStage<Evidence.Result> handle(PackSageTools.ReadRequest request) {
        return switch (request.tool()) {
            case "read_loot" -> readLoot(request);
            case "locate" -> locate(request);
            case "search_commands" -> searchCommands(request);
            case "read_command" -> readCommand(request);
            default -> CompletableFuture.failedFuture(new Failure(Code.NOT_HANDLED, request.tool()));
        };
    }

    /** Reads one loot or command handle fresh; a loot handle repeats read_loot by its table id. */
    CompletionStage<Evidence.Record> reread(String handle, Evidence.Scope scope) {
        if (!handle.startsWith("loot|") && !handle.startsWith("command|")) {
            throw new Failure(Code.INVALID_REFERENCE, "only loot and command records are reread here");
        }
        var server = calls.server();
        if (handle.startsWith("command|")) return pending();
        ResourceKey<LootTable> key;
        try {
            key = MinecraftLoot.table(handle.substring(5));
        } catch (IllegalArgumentException exception) {
            throw WorldTools.invalid(exception.getMessage());
        }
        return calls.run(server, WorldTools.once(() -> lootFetch(server, key, "table", null, null))).thenApply(fetched -> {
            var read = encoded(fetched);
            return lootRecord(scope, read, read.table().map(table -> MinecraftLoot.page(table, 0)).orElse(null), 0);
        });
    }

    // read_loot

    /**
     * Resolves and looks up the table in one server-thread step; encoding and every page, including the first, run on
     * the worker. Reading never rolls the table or unpacks a container.
     */
    private CompletionStage<Evidence.Result> readLoot(PackSageTools.ReadRequest request) {
        exactlyOne(request, List.of("table", "block", "entity", "place"));
        var server = calls.server();
        var cursor = calls.cursor(request);
        if (cursor != null) {
            var state = WorldTools.state(cursor, LootState.class);
            return CompletableFuture.supplyAsync(() -> lootResult(request, state.read(), state.nextPool()), calls.worker());
        }
        ServerWork.Slice<Loot> read;
        if (WorldTools.argument(request, "table") != null) {
            var id = WorldTools.requiredText(request, "table");
            read = WorldTools.once(() -> lootFetch(server, MinecraftLoot.table(id), "table", null, null));
        } else if (WorldTools.argument(request, "block") != null) {
            var id = WorldTools.requiredText(request, "block");
            read = WorldTools.once(() -> lootFetch(server, MinecraftLoot.blockTable(id), "block:" + id, null, null));
        } else if (WorldTools.argument(request, "entity") != null) {
            var text = WorldTools.requiredText(request, "entity");
            var id = uuidOrNull(text);
            read = WorldTools.once(() -> id == null ? lootFetch(server, MinecraftLoot.entityTypeTable(text), "entity:" + text, null, null)
                    : entityLoot(server, id));
        } else {
            var place = WorldTools.place(WorldTools.requiredText(request, "place"));
            read = WorldTools.once(() -> containerLoot(server, place));
        }
        return calls.run(server, read).thenApply(loot -> switch (loot) {
            case LootFetch fetched -> lootResult(request, encoded(fetched), 0);
            case NoPendingLoot none -> WorldTools.result(List.of(noPendingRecord(request.scope(), none)), Evidence.Coverage.COMPLETE,
                    null, NO_PENDING_LOOT);
            case NotLiving entity -> WorldTools.result(List.of(), Evidence.Coverage.COMPLETE, null,
                    entity.entity() + " is not a living entity, so it has no death loot table");
        });
    }

    Evidence.Result lootResult(PackSageTools.ReadRequest request, LootRead read, int fromPool) {
        var page = read.table().map(table -> MinecraftLoot.page(table, fromPool)).orElse(null);
        var next = page == null || page.nextPool() == -1 ? null : calls.remember(request, new LootState(read, page.nextPool()));
        var record = lootRecord(request.scope(), read, page, fromPool);
        var notes = lootNotes(read, page, fromPool);
        return WorldTools.result(List.of(record), record.source().coverage(), next, notes.isEmpty() ? null : String.join("; ", notes));
    }

    /** Server thread only. A loaded entity's own table, such as a sheep's colour table. */
    private static Loot entityLoot(MinecraftServer server, UUID id) {
        var entity = WorldTools.entity(server, id);
        if (entity == null) throw new Failure(Code.UNAVAILABLE, "no loaded entity has id " + id);
        var key = MinecraftLoot.entityTable(entity);
        if (key == null) return new NotLiving(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()) + " " + id);
        return lootFetch(server, key, "entity:" + id, null, null);
    }

    /** Server thread only. Reads the container's pending table and seed, never its slots, which would unpack it. */
    private static Loot containerLoot(MinecraftServer server, BlockPlace place) {
        var level = WorldTools.level(server, place.dimension());
        var pos = new BlockPos(place.x(), place.y(), place.z());
        var chunk = level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
        if (chunk == null) throw new Failure(Code.UNAVAILABLE, "the chunk at " + place.text() + " is not loaded");
        var pending = MinecraftLoot.pending(chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK));
        if (pending == null) {
            return new NoPendingLoot(place, BuiltInRegistries.BLOCK.getKey(chunk.getBlockState(pos).getBlock()).toString(),
                    server.overworld().getGameTime());
        }
        return lootFetch(server, pending.table(), "place:" + place.text(), place, pending.seed());
    }

    /** Server thread only. Tables come from the reloadable registries, so data-pack overrides apply. */
    private static LootFetch lootFetch(MinecraftServer server, ResourceKey<LootTable> key, String resolvedFrom, BlockPlace place, Long seed) {
        return fetch(server.reloadableRegistries().get(), key, resolvedFrom, place, seed, server.overworld().getGameTime());
    }

    /** Looks the table up only; encoding it is left to the worker. */
    static LootFetch fetch(HolderLookup.Provider registries, ResourceKey<LootTable> key, String resolvedFrom, BlockPlace place, Long seed,
                           long readAt) {
        return new LootFetch(key.location().toString(), resolvedFrom, MinecraftLoot.lookup(registries, key), registries, place, seed, readAt);
    }

    /**
     * Worker only: encodes the fetched table with its codec, which has no side effects. A table the codec cannot encode
     * fails as an extraction failure, as it would inside a server read.
     */
    static LootRead encoded(LootFetch fetched) {
        Optional<MinecraftLoot.Encoded> table;
        try {
            table = fetched.table().map(found -> MinecraftLoot.encode(found, fetched.registries()));
        } catch (RuntimeException exception) {
            throw new Failure(Code.EXTRACTION_FAILED, "loot table " + fetched.id() + " could not be encoded: "
                    + (exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()));
        }
        return new LootRead(fetched.id(), fetched.resolvedFrom(), table, fetched.place(), fetched.seed(), fetched.readAt());
    }

    /** The table's facts on one page; page is null when the table does not exist. */
    static Evidence.JsonObject lootPayload(String id, Optional<MinecraftLoot.Encoded> table, MinecraftLoot.LootPage page) {
        var values = new LinkedHashMap<String, Evidence.JsonValue>();
        values.put("table", WorldTools.text(id));
        values.put("exists", new Evidence.JsonBoolean(table.isPresent()));
        if (table.isEmpty()) {
            values.put("note", WorldTools.text(UNKNOWN_TABLE));
            return new Evidence.JsonObject(values);
        }
        var encoded = table.get();
        var empty = encoded.pools().isEmpty();
        values.put("empty", new Evidence.JsonBoolean(empty));
        values.put("header", encoded.header());
        values.put("pools", new Evidence.JsonArray(List.copyOf(page.pools())));
        values.put("poolCount", WorldTools.number(encoded.pools().size()));
        values.put("poolsShown", WorldTools.number(page.pools().size()));
        values.put("itemIds", strings(encoded.itemIds()));
        values.put("tagIds", strings(encoded.tagIds()));
        if (empty) values.put("note", WorldTools.text(EMPTY_TABLE));
        return new Evidence.JsonObject(values);
    }

    /** One page of the table; a container's pending loot links to the container, other tables have no viewer. */
    private static Evidence.Record lootRecord(Evidence.Scope scope, LootRead read, MinecraftLoot.LootPage page, int fromPool) {
        var payload = new LinkedHashMap<>(lootPayload(read.id(), read.table(), page).values());
        payload.put("resolvedFrom", WorldTools.text(read.resolvedFrom()));
        if (page != null && fromPool > 0) payload.put("firstPool", WorldTools.number(fromPool));
        if (read.place() != null) {
            payload.put("place", WorldTools.text(read.place().text()));
            payload.put("pending", new Evidence.JsonBoolean(true));
            payload.put("seed", WorldTools.number(read.seed()));
        }
        payload.put("readAt", WorldTools.number(read.readAt()));
        var partial = page != null && (page.nextPool() != -1 || page.poolTrimmed() || read.table().get().idsBounded());
        return WorldTools.evidence(scope, "loot|" + read.id(), Evidence.RecordKind.OTHER, "Loot table " + read.id(), payload,
                partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE, read.place() == null ? null : WorldNavigation.blockId(read.place()));
    }

    static Evidence.Record noPendingRecord(Evidence.Scope scope, NoPendingLoot none) {
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("resolvedFrom", WorldTools.text("place:" + none.place().text()));
        payload.put("place", WorldTools.text(none.place().text()));
        payload.put("block", WorldTools.text(none.block()));
        payload.put("pending", new Evidence.JsonBoolean(false));
        payload.put("note", WorldTools.text(NO_PENDING_LOOT));
        payload.put("readAt", WorldTools.number(none.readAt()));
        return WorldTools.evidence(scope, "block|" + none.place().text(), Evidence.RecordKind.BLOCK, "No pending loot at " + none.place().text(),
                payload, Evidence.Coverage.COMPLETE, WorldNavigation.blockId(none.place()));
    }

    private static List<String> lootNotes(LootRead read, MinecraftLoot.LootPage page, int fromPool) {
        var notes = new ArrayList<String>();
        if (page == null) {
            notes.add(UNKNOWN_TABLE);
            return notes;
        }
        var encoded = read.table().get();
        if (encoded.pools().isEmpty()) notes.add(EMPTY_TABLE);
        if (page.poolTrimmed()) notes.add("pool " + fromPool + " has more entries than one page holds; only its leading entries are shown");
        if (page.nextPool() != -1) notes.add("pools " + page.nextPool() + " to " + (encoded.pools().size() - 1) + " follow; continue with the cursor");
        if (encoded.idsBounded()) notes.add("itemIds and tagIds list only the first " + MinecraftLoot.MAX_IDS + " of each");
        if (read.place() != null) notes.add("the container generates this loot when it is first opened; seed 0 means a random seed");
        return notes;
    }

    private static Evidence.JsonArray strings(List<String> values) {
        return new Evidence.JsonArray(values.stream().map(value -> (Evidence.JsonValue) WorldTools.text(value)).toList());
    }

    /** The entity input names a loaded entity when it is a UUID, in either case, and an entity type otherwise. */
    static UUID uuidOrNull(String text) {
        try {
            var id = UUID.fromString(text);
            return id.toString().equalsIgnoreCase(text) ? id : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    // locate, search_commands and read_command

    private CompletionStage<Evidence.Result> locate(PackSageTools.ReadRequest request) {
        exactlyOne(request, List.of("biome", "structure"));
        var server = calls.server();
        if (WorldTools.argument(request, "biome") == null) return pending();
        var anchor = WorldTools.anchor(WorldTools.argument(request, "center"));
        var radius = 100;
        if (WorldTools.argument(request, "radius") != null) {
            try {
                if (!(WorldTools.argument(request, "radius") instanceof Evidence.JsonNumber number)) throw WorldTools.invalid("radius must be an integer from 1 to 512");
                radius = number.value().intValueExact();
                if (radius < 1 || radius > 512) throw WorldTools.invalid("radius must be an integer from 1 to 512");
            } catch (ArithmeticException exception) {
                throw WorldTools.invalid("radius must be an integer from 1 to 512");
            }
        }
        var cursor = calls.cursor(request);
        var state = cursor == null ? null : WorldTools.state(cursor, BiomeState.class);
        var searchRadius = state == null ? radius : state.radius();
        var searchQuery = state == null ? WorldTools.requiredText(request, "biome") : state.query();
        return calls.run(server, WorldTools.then(() -> state == null ? calls.center(server, anchor) : state.center(), center -> WorldTools.once(() -> {
            var level = WorldTools.level(server, center.dimension());
            var source = level.getChunkSource().getGenerator().getBiomeSource();
            var climate = level.getChunkSource().randomState().sampler();
            return new BiomeRead(center, level.registryAccess().registryOrThrow(Registries.BIOME), source,
                    (x, y, z) -> source.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), climate)
                            .unwrapKey().map(key -> key.location().toString()).orElse(""), level.getMinBuildHeight(), level.getMaxBuildHeight());
        }))).thenCompose(read -> {
            var targets = biomeTargets(read.registry(), read.source().possibleBiomes(), searchQuery);
            if (targets.isEmpty()) {
                return CompletableFuture.completedFuture(WorldTools.result(List.of(), Evidence.Coverage.COMPLETE, null,
                        "this dimension's biome source never produces it"));
            }
            var origin = BlockPos.containing(read.center().position());
            return calls.run(server, new LocateBiome(targets::contains, read.sampler(), origin.getX(), origin.getY(), origin.getZ(), searchRadius,
                    read.minY(), read.maxY(), state == null ? 0 : state.nextIndex()))
                    .thenApply(result -> biomeResult(request, read.center(), searchQuery, searchRadius, result));
        });
    }

    /** Worker only: resolve an id or tag, then restrict it to biomes this source can produce. */
    static Set<String> biomeTargets(Registry<Biome> registry, Set<Holder<Biome>> possible, String query) {
        var tag = query.startsWith("#");
        var id = ResourceLocation.tryParse(tag ? query.substring(1) : query);
        var targets = new HashSet<String>();
        var known = false;
        if (id != null && tag) {
            var members = registry.getTag(TagKey.create(Registries.BIOME, id));
            known = members.isPresent();
            if (members.isPresent()) members.get().forEach(holder -> targets.add(holder.unwrapKey().orElseThrow().location().toString()));
        } else if (id != null) {
            known = registry.containsKey(id);
            if (known) targets.add(id.toString());
        }
        if (!known) {
            var path = query.substring(query.indexOf(':') + 1).replaceFirst("^#", "").toLowerCase(Locale.ROOT);
            throw WorldTools.invalid((id == null ? "invalid" : "unknown") + " biome " + (tag ? "tag " : "id ") + query
                    + (id == null ? "; try a lowercase id or tag" : "") + "; registry ids containing " + path + ": "
                    + registry.keySet().stream().filter(key -> key.getPath().contains(path)).sorted().limit(16).toList());
        }
        var producible = new HashSet<String>();
        for (var holder : possible) holder.unwrapKey().ifPresent(key -> producible.add(key.location().toString()));
        targets.retainAll(producible);
        return Set.copyOf(targets);
    }

    /** Builds the page on the worker after the sampling slice has finished or spent its call budget. */
    Evidence.Result biomeResult(PackSageTools.ReadRequest request, WorldTools.Center center, String query, int radius, LocateBiome.Result result) {
        if (result.hit() == null) {
            if (result.nextIndex() != -1) {
                return WorldTools.result(List.of(), Evidence.Coverage.PARTIAL,
                        calls.remember(request, new BiomeState(center, query, radius, result.nextIndex())),
                        "biome sampling reached the 500 ms server work budget after " + result.columns() + " columns; sampled out to approximately "
                                + (int) Math.ceil((Math.sqrt(result.nextIndex()) - 1) / 2) * LocateBiome.HORIZONTAL_STEP
                                + " blocks from the center along each horizontal axis (outer ring may be incomplete); continue with the cursor");
            }
            return WorldTools.result(List.of(), Evidence.Coverage.COMPLETE, null, "no matching biome within " + radius
                    + " chunks; sampled through ring " + radius * 16 / LocateBiome.HORIZONTAL_STEP + " at "
                    + radius * 16 / LocateBiome.HORIZONTAL_STEP * LocateBiome.HORIZONTAL_STEP
                    + " blocks from the center along each horizontal axis (radius rounded down to 32-block rings)");
        }
        var hit = result.hit();
        var place = new BlockPlace(center.dimension(), hit.x(), hit.y(), hit.z());
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("kind", WorldTools.text("sampled"));
        payload.put("biome", WorldTools.text(hit.biome()));
        payload.put("query", WorldTools.text(query));
        payload.put("dimension", WorldTools.text(center.dimension()));
        payload.put("place", WorldTools.text(place.text()));
        payload.put("distanceBlocks", WorldTools.number(Math.round(Math.hypot(hit.x() - center.position().x, hit.z() - center.position().z))));
        payload.put("resolution", new Evidence.JsonObject(java.util.Map.of("horizontalBlocks", WorldTools.number(LocateBiome.HORIZONTAL_STEP),
                "verticalBlocks", WorldTools.number(LocateBiome.VERTICAL_STEP))));
        return WorldTools.single(WorldTools.evidence(request.scope(), "locate|" + center.dimension() + "|" + hit.biome() + "|" + hit.x() + "," + hit.z(),
                Evidence.RecordKind.OTHER, hit.biome() + " at " + hit.x() + ", " + hit.y() + ", " + hit.z(), payload, Evidence.Coverage.COMPLETE,
                WorldNavigation.placeId(place)));
    }

    private CompletionStage<Evidence.Result> searchCommands(PackSageTools.ReadRequest request) {
        calls.server();
        return pending();
    }

    private CompletionStage<Evidence.Result> readCommand(PackSageTools.ReadRequest request) {
        if (WorldTools.requiredText(request, "name").isBlank()) throw WorldTools.invalid("name must not be blank");
        calls.server();
        return pending();
    }

    private static void exactlyOne(PackSageTools.ReadRequest request, List<String> names) {
        if (names.stream().filter(name -> WorldTools.argument(request, name) != null).count() != 1) {
            throw WorldTools.invalid("give exactly one of " + String.join(", ", names));
        }
    }

    private static <T> CompletionStage<T> pending() {
        return CompletableFuture.failedFuture(new Failure(Code.UNAVAILABLE, "not implemented yet"));
    }
}
