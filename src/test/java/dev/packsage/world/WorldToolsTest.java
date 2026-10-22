package dev.packsage.world;

import dev.packsage.knowledge.AdapterRegistry;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.model.PackSageTools;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

class WorldToolsTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "resources", "team", "runtime", 1);
    private static final String ENTITY = "3f1c2a4e-0000-4000-8000-000000000001";

    private static WorldTools tools() {
        return new WorldTools(() -> null, new WorldRegistry(), () -> SCOPE, Runnable::run, new ServerWork(System::nanoTime), place -> false, id -> null, null);
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
                "inspect_area", "{}",
                "list_blocks", "{\"filter\":{\"ids\":[\"minecraft:chest\"]}}",
                "list_entities", "{\"filter\":{\"text\":\"villager\"},\"radius\":32}",
                "inspect_block", "{\"place\":\"minecraft:overworld|1,2,3\"}",
                "inspect_entity", "{\"id\":\"" + ENTITY + "\"}",
                "list_items", "{\"holder\":\"player\"}",
                "inspect_item", "{\"path\":\"player:" + ENTITY + ">minecraft/main#0@minecraft:stone\"}",
                "find_items", "{\"filter\":{\"ids\":[\"minecraft:stone\"]}}");
        calls.forEach((tool, arguments) -> assertEquals(Toolbox.Code.UNAVAILABLE, failure(tools, tool, arguments), tool));
        assertEquals(KnowledgeProvider.Availability.UNAVAILABLE, tools.status().availability());
        assertFalse(tools.enabled());
        assertEquals("world", tools.id());
        assertEquals(Toolbox.Code.NOT_HANDLED, failure(tools, "search_recipe", "{}"));
    }

    @Test
    void anchorAndPlaceArgumentsParseAndRejectMalformedValues() {
        var tools = tools();
        for (var call : Map.of(
                "{\"place\":\"minecraft:overworld|1,2\"}", "inspect_block",
                "{\"place\":\"Not A Dimension|1,2,3\"}", "inspect_block",
                "{\"id\":\"villager\"}", "inspect_entity",
                "{\"path\":\"player:" + ENTITY + "\"}", "inspect_item",
                "{\"path\":\"chest>minecraft/container#1@minecraft:stone\"}", "inspect_item",
                "{\"holder\":\"chest\"}", "list_items",
                "{\"holder\":\"player\",\"inventory\":\"main\"}", "list_items").entrySet()) {
            assertEquals(Toolbox.Code.INVALID_ARGUMENTS, failure(tools, call.getValue(), call.getKey()), call.getKey());
        }
        for (var center : new String[] {
                "{\"type\":\"block\",\"place\":\"minecraft:overworld|1,2\"}",
                "{\"type\":\"block\"}",
                "{\"type\":\"entity\",\"id\":\"not-a-uuid\"}",
                "{\"type\":\"entity\"}",
                "{\"type\":\"player\",\"id\":\"" + ENTITY + "\"}",
                "{\"type\":\"moon\"}"}) {
            assertEquals(Toolbox.Code.INVALID_ARGUMENTS, failure(tools, "inspect_area", "{\"center\":" + center + "}"), center);
        }
        assertEquals(Toolbox.Code.INVALID_ARGUMENTS, failure(tools, "inspect_area", "{\"radius\":0}"));
        assertEquals(Toolbox.Code.INVALID_ARGUMENTS, failure(tools, "inspect_area", "{\"radius\":257}"));
        for (var center : new String[] {
                "{\"type\":\"player\"}",
                "{\"type\":\"entity\",\"id\":\"" + ENTITY + "\"}",
                "{\"type\":\"block\",\"place\":\"minecraft:the_nether|-4,70,12\"}"}) {
            assertEquals(Toolbox.Code.UNAVAILABLE, failure(tools, "inspect_area", "{\"center\":" + center + ",\"radius\":8}"), center);
        }
        assertEquals(Toolbox.Code.UNAVAILABLE, failure(tools, "list_items",
                "{\"holder\":\"block:minecraft:overworld|1,2,3\",\"inventory\":\"minecraft/container\"}"));
    }

    @Test
    void cursorsBindToTheirToolAndArguments() {
        var tools = tools();
        var stone = Evidence.deserializeObject("{\"filter\":{\"ids\":[\"minecraft:stone\"]},\"radius\":24}");
        var token = tools.remember("find_items", stone, SCOPE, "state");
        assertEquals(Toolbox.Code.INVALID_CURSOR, failure(tools, "find_items",
                "{\"filter\":{\"ids\":[\"minecraft:dirt\"]},\"radius\":24,\"cursor\":\"" + token + "\"}"));
        assertEquals(Toolbox.Code.INVALID_CURSOR, failure(tools, "find_items",
                "{\"filter\":{\"ids\":[\"minecraft:stone\"]},\"radius\":32,\"cursor\":\"" + token + "\"}"));
        assertEquals(Toolbox.Code.INVALID_CURSOR, failure(tools, "list_blocks",
                "{\"filter\":{\"ids\":[\"minecraft:stone\"]},\"radius\":24,\"cursor\":\"" + token + "\"}"));
        assertEquals(Toolbox.Code.INVALID_CURSOR, failure(tools, "find_items",
                "{\"filter\":{\"ids\":[\"minecraft:stone\"]},\"radius\":24,\"cursor\":\"made-up\"}"));
        assertEquals(Toolbox.Code.UNAVAILABLE, failure(tools, "find_items",
                "{\"filter\":{\"ids\":[\"minecraft:stone\"]},\"radius\":24,\"center\":null,\"cursor\":\"" + token + "\"}"));
        var later = new Evidence.Scope("world", "resources", "team", "runtime", 2);
        var stale = tools.remember("find_items", stone, later, "state");
        assertEquals(Toolbox.Code.STALE_SCOPE, failure(tools, "find_items",
                "{\"filter\":{\"ids\":[\"minecraft:stone\"]},\"radius\":24,\"cursor\":\"" + stale + "\"}"));
        String first = null;
        for (var i = 0; i < 257; i++) {
            var minted = tools.remember("find_items", stone, SCOPE, "state");
            if (first == null) first = minted;
        }
        assertEquals(Toolbox.Code.INVALID_CURSOR, failure(tools, "find_items",
                "{\"filter\":{\"ids\":[\"minecraft:stone\"]},\"radius\":24,\"cursor\":\"" + first + "\"}"));
    }

    @Test
    void resultContinuationsRunOnTheWorkerNotTheServerThread() throws Exception {
        var work = new ServerWork(System::nanoTime);
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor(task -> new Thread(task, "world-worker"));
        try {
            var tools = new WorldTools(() -> null, new WorldRegistry(), () -> SCOPE, worker, work, place -> false, id -> null, null);
            ServerWork.Slice<String> slice = new ServerWork.Slice<>() {
                @Override
                public boolean step() {
                    return false;
                }

                @Override
                public String result() {
                    return "read";
                }
            };
            var mapped = tools.run(() -> true, slice).thenApply(value -> Thread.currentThread().getName());
            while (work.hasWork()) work.tick();
            assertEquals("world-worker", mapped.toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void continuationPagesKeepTheFirstPagesPartialCoverageAndNotes() {
        var tools = tools();
        var arguments = Evidence.deserializeObject("{\"filter\":{\"ids\":[\"minecraft:stone\"]}}");
        var request = new PackSageTools.ReadRequest("find_items", arguments, SCOPE);
        var entries = new java.util.ArrayList<ItemEntry>();
        for (var i = 0; i < 70; i++) {
            var location = ItemPath.parse("player:" + ENTITY + ">minecraft/main#" + i + "@minecraft:item_" + i);
            entries.add(new ItemEntry("minecraft:item_" + i, "Item " + i, 1, 1, java.util.List.of(location), false));
        }
        var note = "3 chunks in range were not loaded and were not searched";
        var first = tools.entries(request, new WorldTools.EntriesState(entries, null, Evidence.Coverage.COMPLETE, null),
                Evidence.Coverage.PARTIAL, note);
        assertEquals(64, first.records().size());
        assertEquals(Evidence.Coverage.PARTIAL, first.coverage());
        var continued = new PackSageTools.ReadRequest("find_items", Evidence.deserializeObject(
                "{\"filter\":{\"ids\":[\"minecraft:stone\"]},\"cursor\":\"" + first.nextCursor() + "\"}"), SCOPE);
        var state = (WorldTools.EntriesState) tools.cursor(continued);
        var last = tools.entries(continued, state, Evidence.Coverage.COMPLETE, null);
        assertEquals(6, last.records().size());
        assertEquals(null, last.nextCursor());
        assertEquals(Evidence.Coverage.PARTIAL, last.coverage());
        assertEquals(Evidence.Outcome.PARTIAL, last.outcome());
        assertEquals(note, last.detail());

        var search = new WorldTools.FindState(java.util.List.of(), 0);
        var merged = tools.entries(request, new WorldTools.EntriesState(java.util.List.of(), search, Evidence.Coverage.PARTIAL, note),
                Evidence.Coverage.COMPLETE, "Inventory minecraft/container could not be opened");
        assertEquals(Evidence.Coverage.PARTIAL, merged.coverage());
        assertEquals(note + "; Inventory minecraft/container could not be opened", merged.detail());
        var carried = (WorldTools.EntriesState) tools.cursor(new PackSageTools.ReadRequest("find_items", Evidence.deserializeObject(
                "{\"filter\":{\"ids\":[\"minecraft:stone\"]},\"cursor\":\"" + merged.nextCursor() + "\"}"), SCOPE));
        assertEquals(Evidence.Coverage.PARTIAL, carried.coverage());
        assertEquals(merged.detail(), carried.note());
    }
}
