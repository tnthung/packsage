package dev.packsage.world;

import dev.packsage.knowledge.AdapterRegistry;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.model.PackSageTools;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class WorldKnowledgeToolsTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "resources", "team", "runtime", 1);

    private static WorldTools tools() {
        return new WorldTools(() -> null, new WorldRegistry(), () -> SCOPE, Runnable::run, new ServerWork(System::nanoTime),
                () -> java.util.concurrent.CompletableFuture.completedFuture(ClientFocus.NONE), place -> false, id -> null, null);
    }

    private static Toolbox.Code failure(WorldTools tools, String tool, String arguments) {
        var context = new Toolbox.Context(() -> SCOPE, Map.of(), new AdapterRegistry(), new Toolbox.DocumentBudget(), null);
        try {
            tools.handle(new PackSageTools.ReadRequest(tool, Evidence.deserializeObject(arguments), SCOPE), context).toCompletableFuture().get();
        } catch (Toolbox.Failure failure) {
            return failure.code();
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof Toolbox.Failure failure) return failure.code();
            fail(exception);
        } catch (InterruptedException exception) {
            fail(exception);
        }
        return fail(tool + " succeeded");
    }

    @Test
    void toolsAreUnavailableWithoutAnIntegratedServer() {
        var tools = tools();
        var calls = Map.of(
                "read_loot", "{\"table\":\"minecraft:entities/zombie\"}",
                "locate", "{\"structure\":\"#minecraft:village\"}",
                "search_commands", "{}",
                "read_command", "{\"name\":\"fill\"}");
        calls.forEach((tool, arguments) -> assertEquals(Toolbox.Code.UNAVAILABLE, failure(tools, tool, arguments), tool));
    }

    @Test
    void argumentsNeedExactlyOneSource() {
        var tools = tools();
        var calls = List.of(
                List.of("read_loot", "{}"),
                List.of("read_loot", "{\"table\":\"a:b\",\"block\":\"minecraft:stone\"}"),
                List.of("locate", "{}"),
                List.of("locate", "{\"biome\":\"minecraft:plains\",\"structure\":\"minecraft:village_plains\"}"),
                List.of("read_command", "{\"name\":\"  \"}"));
        for (var call : calls) assertEquals(Toolbox.Code.INVALID_ARGUMENTS, failure(tools, call.get(0), call.get(1)), call.toString());
    }

    private static Evidence.Result reread(String handle) throws Exception {
        var query = new Evidence.Query(Evidence.Operation.GET, Evidence.deserializeObject("{\"id\":\"" + handle + "\"}"), 1, null, null);
        return tools().query(query, SCOPE).toCompletableFuture().get();
    }

    @Test
    void lootAndCommandHandlesAreRereadThroughTheKnowledgeTools() throws Exception {
        for (var handle : List.of("loot|minecraft:entities/zombie", "command|fill")) {
            var result = reread(handle);
            assertEquals(Evidence.Coverage.ERROR, result.coverage(), handle);
            assertTrue(result.detail().contains("integrated singleplayer server"), handle + ": " + result.detail());
        }
    }

    @Test
    void unknownHandlesNameEveryRereadableKind() throws Exception {
        var detail = reread("chunk|0,0").detail();
        for (var kind : List.of("block", "entity", "item", "loot", "command")) assertTrue(detail.contains(kind), detail);
    }

    @Test
    void unknownEmptyAndNoDropTablesAreDistinct() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider registries = VanillaRegistries.createLookup();
        var unknown = WorldKnowledgeTools.lootPayload("packsage:nothing_here", Optional.empty(), null).values();
        assertEquals(new Evidence.JsonBoolean(false), unknown.get("exists"));
        assertEquals(new Evidence.JsonString("no loot table has this id in the loaded data packs"), unknown.get("note"));
        var noPools = MinecraftLoot.encode(LootTable.EMPTY, registries);
        var air = MinecraftLoot.lookup(registries, MinecraftLoot.blockTable("minecraft:air")).map(table -> MinecraftLoot.encode(table, registries));
        assertEquals(BuiltInLootTables.EMPTY, MinecraftLoot.blockTable("minecraft:air"));
        for (var table : List.of(Optional.of(noPools), air)) {
            var payload = WorldKnowledgeTools.lootPayload("minecraft:empty", table, MinecraftLoot.page(table.orElseThrow(), 0)).values();
            assertEquals(new Evidence.JsonBoolean(true), payload.get("exists"));
            assertEquals(new Evidence.JsonBoolean(true), payload.get("empty"));
            assertEquals(new Evidence.JsonString("this table drops nothing"), payload.get("note"));
        }
    }
}
