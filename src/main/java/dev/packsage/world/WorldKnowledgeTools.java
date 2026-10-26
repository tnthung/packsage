package dev.packsage.world;

import com.mojang.brigadier.arguments.ArgumentType;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox.Code;
import dev.packsage.knowledge.Toolbox.Failure;
import dev.packsage.model.PackSageTools;
import dev.packsage.world.minecraft.CommandReader;
import dev.packsage.world.minecraft.LocateBiome;
import dev.packsage.world.minecraft.LocateStructure;
import dev.packsage.world.minecraft.MinecraftLoot;
import dev.packsage.world.minecraft.ReadOnlyRegions;
import dev.packsage.world.minecraft.SavedStarts;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.loot.LootTable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

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
    record BiomeState(WorldAreaTools.Center center, String query, int radius, int nextIndex) {
    }

    /** Server-owned inputs captured together; registry matching and spiral preparation run on the worker. */
    private record BiomeRead(WorldAreaTools.Center center, Registry<Biome> registry, BiomeSource source, LocateBiome.Sampler sampler,
                             int minY, int maxY) {
    }

    /** A server-thread copy can spend its call budget before finishing; the cursor retains the remaining work. */
    private record CommandCapture(ServerWork.Slice<CommandReader<CommandSourceStack>> snapshot, int permission) {
    }

    private record CommandSearchState(List<CommandReader.Summary> summaries, int permission, int offset) {
    }

    record CommandReadState(CommandReader<?> reader, int permission, int usageOffset, int argumentOffset) {
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
        if (handle.startsWith("command|")) {
            return readCommand(new PackSageTools.ReadRequest("read_command",
                    new Evidence.JsonObject(Map.of("name", WorldTools.text(handle.substring(8)))), scope))
                    .thenApply(result -> commandRereadRecord(scope, handle, result));
        }
        ResourceKey<LootTable> key;
        try {
            key = MinecraftLoot.table(handle.substring(5));
        } catch (IllegalArgumentException exception) {
            throw WorldTools.invalid(exception.getMessage());
        }
        return calls.run(server, WorldPaging.once(() -> lootFetch(server, key, "table", null, null))).thenApply(fetched -> {
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
        var cursor = calls.paging.cursor(request);
        if (cursor != null) {
            var state = WorldPaging.state(cursor, LootState.class);
            return CompletableFuture.supplyAsync(() -> lootResult(request, state.read(), state.nextPool()), calls.worker());
        }
        ServerWork.Slice<Loot> read;
        if (WorldTools.argument(request, "table") != null) {
            var id = WorldTools.requiredText(request, "table");
            read = WorldPaging.once(() -> lootFetch(server, MinecraftLoot.table(id), "table", null, null));
        } else if (WorldTools.argument(request, "block") != null) {
            var id = WorldTools.requiredText(request, "block");
            read = WorldPaging.once(() -> lootFetch(server, MinecraftLoot.blockTable(id), "block:" + id, null, null));
        } else if (WorldTools.argument(request, "entity") != null) {
            var text = WorldTools.requiredText(request, "entity");
            var id = uuidOrNull(text);
            read = WorldPaging.once(() -> id == null ? lootFetch(server, MinecraftLoot.entityTypeTable(text), "entity:" + text, null, null)
                    : entityLoot(server, id));
        } else {
            var place = WorldTools.place(WorldTools.requiredText(request, "place"));
            read = WorldPaging.once(() -> containerLoot(server, place));
        }
        return calls.run(server, read).thenApply(loot -> switch (loot) {
            case LootFetch fetched -> lootResult(request, encoded(fetched), 0);
            case NoPendingLoot none -> WorldPaging.result(List.of(noPendingRecord(request.scope(), none)), Evidence.Coverage.COMPLETE,
                    null, NO_PENDING_LOOT);
            case NotLiving entity -> WorldPaging.result(List.of(), Evidence.Coverage.COMPLETE, null,
                    entity.entity() + " is not a living entity, so it has no death loot table");
        });
    }

    Evidence.Result lootResult(PackSageTools.ReadRequest request, LootRead read, int fromPool) {
        var page = read.table().map(table -> MinecraftLoot.page(table, fromPool)).orElse(null);
        var next = page == null || page.nextPool() == -1 ? null : calls.paging.remember(request, new LootState(read, page.nextPool()));
        var record = lootRecord(request.scope(), read, page, fromPool);
        var notes = lootNotes(read, page, fromPool);
        return WorldPaging.result(List.of(record), record.source().coverage(), next, notes.isEmpty() ? null : String.join("; ", notes));
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
        var cursor = calls.paging.cursor(request);
        if (WorldTools.argument(request, "structure") != null) return locateStructure(request, server, anchor, radius, cursor);
        var state = cursor == null ? null : WorldPaging.state(cursor, BiomeState.class);
        var searchRadius = state == null ? radius : state.radius();
        var searchQuery = state == null ? WorldTools.requiredText(request, "biome") : state.query();
        return calls.run(server, WorldPaging.then(() -> state == null ? calls.areaTools.center(server, anchor) : state.center(), center -> WorldPaging.once(() -> {
            var level = WorldTools.level(server, center.dimension());
            var source = level.getChunkSource().getGenerator().getBiomeSource();
            var climate = level.getChunkSource().randomState().sampler();
            return new BiomeRead(center, level.registryAccess().registryOrThrow(Registries.BIOME), source,
                    (x, y, z) -> source.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), climate)
                            .unwrapKey().map(key -> key.location().toString()).orElse(""), level.getMinBuildHeight(), level.getMaxBuildHeight());
        }))).thenCompose(read -> {
            var targets = biomeTargets(read.registry(), read.source().possibleBiomes(), searchQuery);
            if (targets.isEmpty()) {
                return CompletableFuture.completedFuture(WorldPaging.result(List.of(), Evidence.Coverage.COMPLETE, null,
                        "this dimension's biome source never produces it"));
            }
            var origin = BlockPos.containing(read.center().position());
            return calls.run(server, new LocateBiome(targets::contains, read.sampler(), origin.getX(), origin.getY(), origin.getZ(), searchRadius,
                    read.minY(), read.maxY(), state == null ? 0 : state.nextIndex()))
                    .thenApply(result -> biomeResult(request, read.center(), searchQuery, searchRadius, result));
        });
    }

    record StructureState(WorldAreaTools.Center center, String query, int radius, int ring, int entry, int candidate) {
    }

    private record StructureRead(WorldAreaTools.Center center, LocateStructure.World world, List<LocateStructure.Target> targets,
                                 long seed, Map<ConcentricRingsStructurePlacement, List<ChunkPos>> rings, Path folder) {
    }

    private CompletionStage<Evidence.Result> locateStructure(PackSageTools.ReadRequest request, MinecraftServer server,
                                                           Anchor anchor, int radius, Object cursor) {
        var state = cursor == null ? null : WorldPaging.state(cursor, StructureState.class);
        var query = state == null ? WorldTools.requiredText(request, "structure") : state.query();
        var searchRadius = state == null ? radius : state.radius();
        return calls.run(server, WorldPaging.then(() -> state == null ? calls.areaTools.center(server, anchor) : state.center(), center -> WorldPaging.once(() -> {
            var level = WorldTools.level(server, center.dimension());
            var chunks = level.getChunkSource();
            var generator = chunks.getGenerator();
            var random = chunks.randomState();
            var generatorState = chunks.getGeneratorState();
            var targets = structureTargets(level.registryAccess().registryOrThrow(Registries.STRUCTURE), query).stream().map(holder -> new LocateStructure.Target(
                    holder.unwrapKey().orElseThrow().location().toString(), holder.value(), generatorState.getPlacementsForStructure(holder))).toList();
            var rings = new HashMap<ConcentricRingsStructurePlacement, List<ChunkPos>>();
            for (var target : targets) for (var placement : target.placements()) {
                if (placement instanceof ConcentricRingsStructurePlacement concentric) {
                    var positions = generatorState.getRingPositionsFor(concentric);
                    rings.put(concentric, positions == null ? List.of() : List.copyOf(positions));
                }
            }
            var world = new LocateStructure.World() {
                @Override
                public boolean loaded(int x, int z) {
                    return chunks.getChunkNow(x, z) != null;
                }

                @Override
                public StructureStart loadedStart(int x, int z, Structure structure) {
                    var chunk = chunks.getChunkNow(x, z);
                    if (chunk == null) return null;
                    var start = chunk.getStartForStructure(structure);
                    return start != null && start.isValid() ? start : null;
                }

                @Override
                public boolean placementPasses(StructurePlacement placement, int x, int z) {
                    return placement.isStructureChunk(generatorState, x, z);
                }

                @Override
                public boolean biomePasses(Structure structure, BlockPos pos) {
                    return LocateStructure.biomePasses(structure, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1,
                            y -> generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(pos.getX()), QuartPos.fromBlock(y),
                                    QuartPos.fromBlock(pos.getZ()), random.sampler()));
                }

                @Override
                public int surfaceY(int x, int z) {
                    return generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, random);
                }
            };
            return new StructureRead(center, world, targets, generatorState.getLevelSeed(), rings,
                    DimensionType.getStorageFolder(level.dimension(), server.getWorldPath(LevelResource.ROOT)));
        }))).thenCompose(read -> calls.run(server, new LocateStructure(read.world(), read.targets(), read.seed(), read.rings()::get,
                SectionPos.blockToSectionCoord(read.center().position().x), SectionPos.blockToSectionCoord(read.center().position().z),
                searchRadius, state == null ? 0 : state.ring(), state == null ? 0 : state.entry(), state == null ? 0 : state.candidate())).thenCompose(result -> {
                    var ordered = result.hits().stream().sorted(Comparator.comparingDouble(hit ->
                            Math.hypot(hit.x() - read.center().position().x, hit.z() - read.center().position().z))).toList();
                    return CompletableFuture.supplyAsync(() -> confirm(ordered,
                                    hit -> ReadOnlyRegions.read(read.folder(), new ChunkPos(hit.chunkX(), hit.chunkZ()), hit.structure())), calls.worker())
                            .orTimeout(10, TimeUnit.SECONDS).handleAsync((hits, failure) -> structureResult(request, read.center(), query,
                                    searchRadius, new LocateStructure.Result(failure == null ? hits : ordered, result.nextRing(), result.nextEntry(),
                                            result.nextCandidate(), result.unsupported(), result.notes(), result.budget()),
                                    failure != null), calls.worker());
                }));
    }

    static List<Holder<Structure>> structureTargets(Registry<Structure> registry, String query) {
        var tag = query.startsWith("#");
        var id = ResourceLocation.tryParse(tag ? query.substring(1) : query);
        if (id != null && tag) {
            var members = registry.getTag(TagKey.create(Registries.STRUCTURE, id));
            if (members.isPresent()) return members.get().stream().toList();
        } else if (id != null && registry.containsKey(id)) {
            return List.of(registry.getHolder(ResourceKey.create(Registries.STRUCTURE, id)).orElseThrow());
        }
        var path = query.substring(query.indexOf(':') + 1).replaceFirst("^#", "").toLowerCase(Locale.ROOT);
        throw WorldTools.invalid((id == null ? "invalid" : "unknown") + " structure " + (tag ? "tag " : "id ") + query
                + (id == null ? "; try a lowercase id or tag" : "") + "; registry ids containing " + path + ": "
                + registry.keySet().stream().filter(key -> key.getPath().contains(path)).sorted().limit(16).toList());
    }

    /** Worker-only saved verdicts. ABSENT rules out this saved candidate, never other predictions. */
    static List<LocateStructure.Hit> confirm(List<LocateStructure.Hit> hits, Function<LocateStructure.Hit, SavedStarts.Verdict> verdicts) {
        var confirmed = new ArrayList<LocateStructure.Hit>();
        var scans = 0;
        for (var hit : hits) {
            if (hit.kind() == LocateStructure.Kind.FOUND || scans >= LocateStructure.SCANS) {
                confirmed.add(hit);
                continue;
            }
            scans++;
            var verdict = verdicts.apply(hit);
            if (verdict == SavedStarts.Verdict.FOUND) {
                confirmed.add(new LocateStructure.Hit(hit.structure(), hit.chunkX(), hit.chunkZ(), hit.x(), hit.y(), hit.z(),
                        LocateStructure.Kind.FOUND, "saved chunk"));
            } else if (verdict != SavedStarts.Verdict.ABSENT) {
                confirmed.add(hit);
            }
        }
        return List.copyOf(confirmed);
    }

    Evidence.Result structureResult(PackSageTools.ReadRequest request, WorldAreaTools.Center center, String query, int radius,
                                    LocateStructure.Result result, boolean timeout) {
        var partial = result.nextRing() != -1 || !result.unsupported().isEmpty() || timeout;
        var coverage = partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE;
        var records = new ArrayList<Evidence.Record>();
        for (var hit : result.hits().stream().limit(LocateStructure.RESULTS).toList()) {
            var kind = hit.kind() == LocateStructure.Kind.FOUND ? "found" : "predicted";
            var place = new BlockPlace(center.dimension(), hit.x(), hit.y(), hit.z());
            var payload = new LinkedHashMap<String, Evidence.JsonValue>();
            payload.put("kind", WorldTools.text(kind));
            payload.put("structure", WorldTools.text(hit.structure()));
            payload.put("query", WorldTools.text(query));
            payload.put("dimension", WorldTools.text(center.dimension()));
            payload.put("place", WorldTools.text(place.text()));
            payload.put("yEstimated", new Evidence.JsonBoolean(!hit.basis().equals("loaded chunk")));
            payload.put("distanceBlocks", WorldTools.number(Math.round(Math.hypot(hit.x() - center.position().x, hit.z() - center.position().z))));
            payload.put("basis", WorldTools.text(hit.basis()));
            if (hit.kind() == LocateStructure.Kind.PREDICTED) payload.put("note", WorldTools.text("should generate here from the world seed; terrain can still prevent it"));
            records.add(WorldTools.evidence(request.scope(), "locate|" + center.dimension() + "|" + hit.structure() + "|" + hit.x() + "," + hit.z(),
                    Evidence.RecordKind.OTHER, hit.structure() + " (" + kind + ") at " + hit.x() + ", " + hit.z(), payload, coverage,
                    WorldNavigation.placeId(place)));
        }
        var notes = new ArrayList<String>();
        notes.add("searched within " + radius + " chunks; unloaded candidates use placement and biome checks, not terrain validation");
        notes.add("saved reads are limited to " + LocateStructure.SCANS + " candidates and may miss unsaved changes; absent means only not found in saved data");
        if (!result.unsupported().isEmpty()) notes.add("unsupported placements: " + String.join(", ", result.unsupported()));
        if (timeout) notes.add("saved-chunk reading exceeded 10 seconds or failed; candidates remain predicted");
        notes.addAll(result.notes());
        if (result.hits().size() > LocateStructure.RESULTS) notes.add((result.hits().size() - LocateStructure.RESULTS)
                + " more hits found on this page; showing the nearest " + LocateStructure.RESULTS);
        if (result.nextRing() != -1) notes.add("search paused at ring " + result.nextRing()
                + ", entry " + result.nextEntry() + ", candidate " + result.nextCandidate()
                + " after " + (result.budget() ? "the 500 ms server budget" : "a result page") + "; continue with the cursor");
        if (records.isEmpty()) notes.add(result.nextRing() == -1 ? "no matching starts found in loaded or saved data and no remaining predictions"
                : "candidates in range were ruled out by saved chunks or the search was bounded; continue to search further");
        return WorldPaging.result(records, coverage, result.nextRing() == -1 ? null
                : calls.paging.remember(request, new StructureState(center, query, radius, result.nextRing(), result.nextEntry(), result.nextCandidate())), String.join("; ", notes));
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
    Evidence.Result biomeResult(PackSageTools.ReadRequest request, WorldAreaTools.Center center, String query, int radius, LocateBiome.Result result) {
        if (result.hit() == null) {
            if (result.nextIndex() != -1) {
                return WorldPaging.result(List.of(), Evidence.Coverage.PARTIAL,
                        calls.paging.remember(request, new BiomeState(center, query, radius, result.nextIndex())),
                        "biome sampling reached the 500 ms server work budget after " + result.columns() + " columns; sampled out to approximately "
                                + (int) Math.ceil((Math.sqrt(result.nextIndex()) - 1) / 2) * LocateBiome.HORIZONTAL_STEP
                                + " blocks from the center along each horizontal axis (outer ring may be incomplete); continue with the cursor");
            }
            return WorldPaging.result(List.of(), Evidence.Coverage.COMPLETE, null, "no matching biome within " + radius
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
        payload.put("resolution", new Evidence.JsonObject(Map.of("horizontalBlocks", WorldTools.number(LocateBiome.HORIZONTAL_STEP),
                "verticalBlocks", WorldTools.number(LocateBiome.VERTICAL_STEP))));
        return WorldPaging.single(WorldTools.evidence(request.scope(), "locate|" + center.dimension() + "|" + hit.biome() + "|" + hit.x() + "," + hit.z(),
                Evidence.RecordKind.OTHER, hit.biome() + " at " + hit.x() + ", " + hit.y() + ", " + hit.z(), payload, Evidence.Coverage.COMPLETE,
                WorldNavigation.placeId(place)));
    }

    private CompletionStage<Evidence.Result> searchCommands(PackSageTools.ReadRequest request) {
        var server = calls.server();
        var cursor = calls.paging.cursor(request);
        if (cursor instanceof CommandCapture capture) return captureCommands(request, server, capture);
        if (cursor != null) {
            var state = WorldPaging.state(cursor, CommandSearchState.class);
            return CompletableFuture.supplyAsync(() -> commandSearchResult(request, state.summaries(), state.permission(), state.offset()), calls.worker());
        }
        return commandCapture(server).thenCompose(capture -> captureCommands(request, server, capture));
    }

    private CompletionStage<Evidence.Result> readCommand(PackSageTools.ReadRequest request) {
        var name = WorldTools.requiredText(request, "name");
        if (name.isBlank()) throw WorldTools.invalid("name must not be blank");
        var server = calls.server();
        var cursor = calls.paging.cursor(request);
        if (cursor instanceof CommandCapture capture) return captureCommands(request, server, capture);
        if (cursor != null) {
            var state = WorldPaging.state(cursor, CommandReadState.class);
            return CompletableFuture.supplyAsync(() -> commandResult(request, state.reader(), state.permission(), name,
                    state.usageOffset(), state.argumentOffset()), calls.worker());
        }
        return commandCapture(server).thenCompose(capture -> captureCommands(request, server, capture));
    }

    /** Captures the player's own source, never the server's privileged command source. */
    private CompletionStage<CommandCapture> commandCapture(MinecraftServer server) {
        return calls.run(server, WorldPaging.once(() -> {
            var source = WorldTools.host(server).createCommandSourceStack();
            int permission = 4;
            while (permission > 0 && !source.hasPermission(permission)) permission--;
            return new CommandCapture(new CommandReader<>(server.getCommands().getDispatcher(), source,
                    WorldKnowledgeTools::argumentType).snapshot(), permission);
        }));
    }

    /** No live tree, argument metadata or permission predicate is read by the subsequent worker stages. */
    private CompletionStage<Evidence.Result> captureCommands(PackSageTools.ReadRequest request, MinecraftServer server, CommandCapture capture) {
        return calls.run(server, capture.snapshot()).thenApply(reader -> {
            if (reader == null) return WorldPaging.result(List.of(), Evidence.Coverage.PARTIAL, calls.paging.remember(request, capture),
                    "command tree copy reached the 500 ms server work budget; continue with the cursor; playerPermissionLevel: " + capture.permission());
            if (request.tool().equals("read_command")) return commandResult(request, reader, capture.permission(), WorldTools.requiredText(request, "name"), 0, 0);
            return commandSearchResult(request, reader.search(WorldTools.argument(request, "text") == null ? null
                    : WorldTools.requiredText(request, "text")), capture.permission(), 0);
        });
    }

    Evidence.Result commandSearchResult(PackSageTools.ReadRequest request, List<CommandReader.Summary> summaries, int permission, int offset) {
        int end = Math.min(offset + CommandReader.PAGE, summaries.size());
        var records = new ArrayList<Evidence.Record>();
        for (var summary : summaries.subList(offset, end)) {
            var payload = new LinkedHashMap<String, Evidence.JsonValue>();
            payload.put("name", WorldTools.text(summary.name()));
            payload.put("canUse", new Evidence.JsonBoolean(summary.canUse()));
            payload.put("canRun", new Evidence.JsonBoolean(summary.canUse()));
            payload.put("usage", strings(summary.usage()));
            payload.put("redirect", summary.redirect() == null ? new Evidence.JsonNull() : WorldTools.text(summary.redirect()));
            payload.put("note", WorldTools.text("PackSage does not run commands"));
            records.add(WorldTools.evidence(request.scope(), "command|" + summary.name(), Evidence.RecordKind.OTHER, "/" + summary.name(),
                    payload, Evidence.Coverage.COMPLETE, null));
        }
        boolean more = end < summaries.size();
        return WorldPaging.result(records, more ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                more ? calls.paging.remember(request, new CommandSearchState(summaries, permission, end)) : null,
                "playerPermissionLevel: " + permission + (more ? "; command page reached " + CommandReader.PAGE + " records; continue with the cursor" : ""));
    }

    Evidence.Result commandResult(PackSageTools.ReadRequest request, CommandReader<?> reader, int permission, String name, int usageOffset, int argumentOffset) {
        var detail = reader.read(name, argumentOffset);
        if (detail == null) return WorldPaging.result(List.of(), Evidence.Coverage.COMPLETE, null,
                "no command " + name + "; search_commands lists them");
        int usageEnd = Math.min(usageOffset + CommandReader.MAX_USAGE, detail.usage().size());
        int playerEnd = Math.min(usageOffset + CommandReader.MAX_USAGE, detail.playerUsage().size());
        boolean more = usageEnd < detail.usage().size() || playerEnd < detail.playerUsage().size() || detail.argumentsBounded();
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("path", WorldTools.text(detail.path()));
        payload.put("canUse", new Evidence.JsonBoolean(detail.canUse()));
        payload.put("canRun", new Evidence.JsonBoolean(detail.canUse()));
        payload.put("usage", strings(detail.usage().subList(Math.min(usageOffset, usageEnd), usageEnd)));
        payload.put("playerUsage", strings(detail.playerUsage().subList(Math.min(usageOffset, playerEnd), playerEnd)));
        payload.put("arguments", new Evidence.JsonArray(detail.arguments().stream().map(argument -> (Evidence.JsonValue) new Evidence.JsonObject(Map.of(
                "path", WorldTools.text(argument.path()), "name", WorldTools.text(argument.name()), "type", WorldTools.text(argument.type()),
                "examples", strings(argument.examples()), "canUse", new Evidence.JsonBoolean(argument.canUse())))).toList()));
        payload.put("argumentsBounded", new Evidence.JsonBoolean(detail.argumentsBounded()));
        payload.put("redirect", detail.redirect() == null ? new Evidence.JsonNull() : WorldTools.text(detail.redirect()));
        payload.put("playerPermissionLevel", WorldTools.number(permission));
        payload.put("note", WorldTools.text("PackSage does not run commands"));
        var coverage = more ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE;
        return WorldPaging.result(List.of(WorldTools.evidence(request.scope(), "command|" + detail.path(), Evidence.RecordKind.OTHER,
                "/" + detail.path(), payload, coverage, null)), coverage,
                more ? calls.paging.remember(request, new CommandReadState(reader, permission, usageOffset + CommandReader.MAX_USAGE,
                        argumentOffset + detail.arguments().size())) : null,
                more ? "command usage or arguments reached the page bound; continue with the cursor" : null);
    }

    private static String argumentType(ArgumentType<?> type) {
        try {
            var id = BuiltInRegistries.COMMAND_ARGUMENT_TYPE.getKey(ArgumentTypeInfos.byClass(type));
            return id == null ? type.getClass().getSimpleName() : id.toString();
        } catch (RuntimeException exception) {
            return type.getClass().getSimpleName();
        }
    }

    /** read_evidence returns a record, so retain a pending copy's cursor in its payload for read_command. */
    static Evidence.Record commandRereadRecord(Evidence.Scope scope, String handle, Evidence.Result result) {
        if (!result.records().isEmpty() && result.nextCursor() == null) return result.records().getFirst();
        if (result.coverage() != Evidence.Coverage.PARTIAL) throw new Failure(Code.UNAVAILABLE, result.detail());
        var payload = result.records().isEmpty() ? new LinkedHashMap<String, Evidence.JsonValue>()
                : new LinkedHashMap<>(result.records().getFirst().payload().values());
        payload.put("path", WorldTools.text(handle.substring(8)));
        payload.put("cursor", WorldTools.text(result.nextCursor()));
        payload.put("note", WorldTools.text("PackSage does not run commands"));
        payload.put("detail", WorldTools.text(result.detail() + "; continue with read_command using this path and cursor"));
        return WorldTools.evidence(scope, handle, Evidence.RecordKind.OTHER, "/" + handle.substring(8), payload, Evidence.Coverage.PARTIAL, null);
    }

    private static void exactlyOne(PackSageTools.ReadRequest request, List<String> names) {
        if (names.stream().filter(name -> WorldTools.argument(request, name) != null).count() != 1) {
            throw WorldTools.invalid("give exactly one of " + String.join(", ", names));
        }
    }
}
