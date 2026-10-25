package dev.packsage.world;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox.Code;
import dev.packsage.knowledge.Toolbox.Failure;
import dev.packsage.model.PackSageTools;
import dev.packsage.world.minecraft.MinecraftLoot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private sealed interface Loot permits LootRead, NoPendingLoot, NotLiving {
    }

    /**
     * A resolved table, encoded when it exists. Place and seed are set for a container's pending loot; readAt is the
     * game time of the read.
     */
    private record LootRead(String id, String resolvedFrom, Optional<MinecraftLoot.Encoded> table, BlockPlace place, Long seed,
                            long readAt) implements Loot {
    }

    private record NoPendingLoot(BlockPlace place, String block, long readAt) implements Loot {
    }

    /** An entity without a death loot table, named by its type and id. */
    private record NotLiving(String entity) implements Loot {
    }

    /** Later pages of one loot read, paged on the worker from the table encoded by the first. */
    private record LootState(LootRead read, int nextPool) {
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
        return calls.run(server, WorldTools.once(() -> lootRead(server, key, "table", null, null)))
                .thenApply(read -> lootRecord(scope, read, read.table().map(table -> MinecraftLoot.page(table, 0)).orElse(null), 0));
    }

    // read_loot

    /**
     * Resolves the table and encodes it in one server-thread step; every page, including the first, is built on the
     * worker. Reading never rolls the table or unpacks a container.
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
            read = WorldTools.once(() -> lootRead(server, MinecraftLoot.table(id), "table", null, null));
        } else if (WorldTools.argument(request, "block") != null) {
            var id = WorldTools.requiredText(request, "block");
            read = WorldTools.once(() -> lootRead(server, MinecraftLoot.blockTable(id), "block:" + id, null, null));
        } else if (WorldTools.argument(request, "entity") != null) {
            var text = WorldTools.requiredText(request, "entity");
            var id = uuidOrNull(text);
            read = WorldTools.once(() -> id == null ? lootRead(server, MinecraftLoot.entityTypeTable(text), "entity:" + text, null, null)
                    : entityLoot(server, id));
        } else {
            var place = WorldTools.place(WorldTools.requiredText(request, "place"));
            read = WorldTools.once(() -> containerLoot(server, place));
        }
        return calls.run(server, read).thenApply(loot -> switch (loot) {
            case LootRead found -> lootResult(request, found, 0);
            case NoPendingLoot none -> WorldTools.result(List.of(noPendingRecord(request.scope(), none)), Evidence.Coverage.COMPLETE,
                    null, NO_PENDING_LOOT);
            case NotLiving entity -> WorldTools.result(List.of(), Evidence.Coverage.COMPLETE, null,
                    entity.entity() + " is not a living entity, so it has no death loot table");
        });
    }

    private Evidence.Result lootResult(PackSageTools.ReadRequest request, LootRead read, int fromPool) {
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
        return lootRead(server, key, "entity:" + id, null, null);
    }

    /** Server thread only. Reads the container's pending table and seed, never its slots, which would unpack it. */
    private static Loot containerLoot(MinecraftServer server, BlockPlace place) {
        var level = WorldTools.level(server, place.dimension());
        var pos = new BlockPos(place.x(), place.y(), place.z());
        if (!WorldTools.loaded(level, pos)) throw new Failure(Code.UNAVAILABLE, "the chunk at " + place.text() + " is not loaded");
        var chunk = level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
        var pending = MinecraftLoot.pending(chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK));
        if (pending == null) {
            return new NoPendingLoot(place, BuiltInRegistries.BLOCK.getKey(chunk.getBlockState(pos).getBlock()).toString(),
                    server.overworld().getGameTime());
        }
        return lootRead(server, pending.table(), "place:" + place.text(), place, pending.seed());
    }

    /** Server thread only. Tables come from the reloadable registries, so data-pack overrides apply. */
    private static LootRead lootRead(MinecraftServer server, ResourceKey<LootTable> key, String resolvedFrom, BlockPlace place, Long seed) {
        var registries = server.reloadableRegistries().get();
        var table = MinecraftLoot.lookup(registries, key).map(found -> MinecraftLoot.encode(found, registries));
        return new LootRead(key.location().toString(), resolvedFrom, table, place, seed, server.overworld().getGameTime());
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

    private static Evidence.Record noPendingRecord(Evidence.Scope scope, NoPendingLoot none) {
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("resolvedFrom", WorldTools.text("place:" + none.place().text()));
        payload.put("place", WorldTools.text(none.place().text()));
        payload.put("block", WorldTools.text(none.block()));
        payload.put("pending", new Evidence.JsonBoolean(false));
        payload.put("note", WorldTools.text(NO_PENDING_LOOT));
        payload.put("readAt", WorldTools.number(none.readAt()));
        return WorldTools.evidence(scope, "block|" + none.place().text(), Evidence.RecordKind.OTHER, "No pending loot at " + none.place().text(),
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

    /** The entity input names a loaded entity when it is a UUID, and an entity type otherwise. */
    private static UUID uuidOrNull(String text) {
        try {
            var id = UUID.fromString(text);
            return id.toString().equals(text) ? id : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    // locate, search_commands and read_command

    private CompletionStage<Evidence.Result> locate(PackSageTools.ReadRequest request) {
        exactlyOne(request, List.of("biome", "structure"));
        calls.server();
        return pending();
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
