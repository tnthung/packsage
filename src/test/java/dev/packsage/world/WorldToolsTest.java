package dev.packsage.world;

import dev.packsage.knowledge.AdapterRegistry;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.model.PackSageTools;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class WorldToolsTest {
    @Test
    void blockHoldersBeyondTheOldChunkCapAreAllGatheredInBoundedSteps() throws Exception {
        var world = DenseWorld.create(0);
        var chunk = (DenseWorld.Chunk) world.chunks.chunk;
        for (int i = 0; i < 4097; i++) {
            var at = new BlockPos(i % 16, 48 + i / 256, i / 16 % 16);
            chunk.blockEntities.put(at, new BarrelBlockEntity(at, Blocks.BARREL.defaultBlockState()));
        }
        var checked = new HashSet<BlockPos>();
        var gathering = new java.util.concurrent.atomic.AtomicBoolean(true);
        var registry = new WorldRegistry();
        registry.register(new InventoryReader() {
            @Override public String adapterId() { return "test"; }
            @Override public List<InventoryInfo> inventories(LiveHolder holder) {
                if (gathering.get()) assertTrue(checked.add(((LiveHolder.OfBlock) holder).pos()));
                return List.of(new InventoryInfo(new InventoryId("test", "storage"), "Storage", 1, SlotRole.STORAGE));
            }
            @Override public LiveInventory open(LiveHolder holder, String section) {
                return new LiveInventory() {
                    @Override public ItemStack get(String entry) { return new ItemStack(net.minecraft.world.item.Items.STONE); }
                    @Override public List<Map.Entry<String, ItemStack>> entries() { return List.of(Map.entry("0", get("0"))); }
                };
            }
            @Override public Section inspectItem(ItemStack stack) { return null; }
        });
        var tools = new WorldTools(() -> null, registry, () -> SCOPE, Runnable::run, new ServerWork(System::nanoTime),
                () -> CompletableFuture.completedFuture(ClientFocus.NONE), place -> false, id -> null, null);
        var type = Class.forName("dev.packsage.world.WorldTools$FindSlice");
        var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var slice = constructor.newInstance(tools, null, new Anchor.Player(), 32, KindFilter.ANY, false, null);
        for (var entry : Map.<String, Object>of("level", world, "center", new Vec3(8, 64, 8),
                "chunks", List.of(new ChunkPos(0, 0)), "startChunk", -1).entrySet()) {
            var field = type.getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(slice, entry.getValue());
        }
        var collect = type.getDeclaredMethod("collect", ChunkPos.class);
        collect.setAccessible(true);
        int steps = 0;
        boolean done;
        do {
            var before = checked.size();
            done = (boolean) collect.invoke(slice, new ChunkPos(0, 0));
            assertTrue(checked.size() - before <= 64);
            if (!done) assertFalse(((ServerWork.Slice<?>) slice).stopEarly(), "a dense chunk continuation must not replay gathered holders");
            assertTrue(++steps < 2000);
        } while (!done);
        assertEquals(4097, checked.size());
        gathering.set(false);
        var holders = type.getDeclaredField("holders");
        holders.setAccessible(true);
        String cursor = null;
        long total = 0;
        int pages = 0;
        do {
            var search = new ItemSearch(registry, (List<Map.Entry<PathRoot, LiveHolder>>) holders.get(slice), KindFilter.ANY, false, cursor);
            while (search.step()) {
            }
            for (var entry : search.result().items()) total += entry.count();
            cursor = search.result().nextCursor();
            assertTrue(++pages <= 9);
        } while (cursor != null);
        assertEquals(4097, total);
        assertEquals(9, pages);
    }

    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "resources", "team", "runtime", 1);
    private static final String ENTITY = "3f1c2a4e-0000-4000-8000-000000000001";

    @Test
    void entityTitlesUseTheBlockTheEntityStandsIn() {
        var entity = new EntityHandle("minecraft:mule", java.util.UUID.fromString(ENTITY), "minecraft:overworld", 1003.5, 250, -0.5, "Mule");
        assertEquals("Mule (minecraft:mule) at minecraft:overworld|1003,250,-1", WorldTools.entityTitle(entity));
    }

    @ParameterizedTest
    @ValueSource(strings = {"stable", "next_tick", "frozen_tick", "missing_block", "missing_entity", "unloaded", "unloaded_at_snapshot", "unloaded_after_snapshot"})
    void holderCoverageReflectsTickSpansAndCountsEachUnloadedChunkOnce(String scenario) throws Exception {
        var world = DenseWorld.create(1);
        world.entitiesUnloaded = scenario.equals("unloaded");
        var pos = new BlockPos(8, 64, 8);
        ((DenseWorld.Chunk) world.chunks.chunk).blockEntities.put(pos, new BarrelBlockEntity(pos, Blocks.BARREL.defaultBlockState()));
        var registry = new WorldRegistry();
        var tools = new WorldTools(() -> null, registry, () -> SCOPE, Runnable::run, new ServerWork(System::nanoTime),
                () -> CompletableFuture.completedFuture(ClientFocus.NONE), place -> false, id -> null, null);
        var type = Class.forName("dev.packsage.world.WorldTools$FindSlice");
        var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var slice = (ServerWork.Slice<?>) constructor.newInstance(tools, null, new Anchor.Player(), 4, KindFilter.ANY, false, null);
        for (var entry : Map.<String, Object>of("level", world, "center", new Vec3(8, 64, 8),
                "chunks", List.of(new ChunkPos(0, 0))).entrySet()) {
            var field = type.getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(slice, entry.getValue());
        }
        java.lang.reflect.Field tickCount = null;
        if (scenario.equals("frozen_tick")) {
            var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            world.server = (net.minecraft.client.server.IntegratedServer) ((sun.misc.Unsafe) field.get(null))
                    .allocateInstance(net.minecraft.client.server.IntegratedServer.class);
            tickCount = net.minecraft.server.MinecraftServer.class.getDeclaredField("tickCount");
            tickCount.setAccessible(true);
        }
        assertTrue(slice.step(), "copy block positions");
        if (scenario.equals("missing_block")) ((DenseWorld.Chunk) world.chunks.chunk).blockEntities.clear();
        assertTrue(slice.step(), "revalidate block positions");
        if (scenario.equals("next_tick")) world.gameTime++;
        if (tickCount != null) tickCount.setInt(world.server, 1);
        if (scenario.equals("unloaded_at_snapshot")) world.entitiesUnloaded = true;
        assertTrue(slice.step(), "decide whether entity snapshots are available");
        if (scenario.equals("unloaded_at_snapshot")) world.entitiesUnloaded = false;
        if (scenario.equals("unloaded_after_snapshot")) world.entitiesUnloaded = true;
        if (scenario.equals("missing_entity")) world.entities.clear();
        var next = type.getDeclaredField("next");
        next.setAccessible(true);
        var steps = 0;
        while (next.getInt(slice) == 0) {
            assertTrue(slice.step());
            assertTrue(++steps < 10);
        }
        // Finish the actual item search without the client-tooltip setup, which requires a running host.
        var search = type.getDeclaredField("search");
        search.setAccessible(true);
        search.set(slice, new ItemSearch(registry, List.of(), KindFilter.ANY, false, null));
        assertFalse(slice.step());
        var result = slice.result();
        var coverage = result.getClass().getDeclaredMethod("coverage");
        coverage.setAccessible(true);
        var note = result.getClass().getDeclaredMethod("note");
        note.setAccessible(true);
        if (scenario.equals("stable")) {
            assertEquals(Evidence.Coverage.COMPLETE, coverage.invoke(result));
            assertEquals(null, note.invoke(result));
        } else if (scenario.equals("next_tick") || scenario.equals("frozen_tick")) {
            assertEquals(Evidence.Coverage.COMPLETE, coverage.invoke(result));
            assertTrue(note.invoke(result).toString().contains(scenario.equals("next_tick") ? "400..401" : "400..400"), note.invoke(result).toString());
        } else {
            assertEquals(Evidence.Coverage.PARTIAL, coverage.invoke(result));
            if (scenario.equals("missing_block")) assertTrue(note.invoke(result).toString().contains("block entity from a chunk snapshot disappeared"));
            else if (scenario.equals("missing_entity")) assertTrue(note.invoke(result).toString().contains("entity from a chunk snapshot disappeared"));
            else assertEquals("1 chunks in range did not have their entities loaded yet, so entities there were not searched", note.invoke(result));
        }
    }

    @Test
    void findingHoldersInADenseChunkBoundsEnumerationAndInventoryChecks() throws Exception {
        var world = DenseWorld.create(4000);
        var checked = new AtomicInteger();
        var registry = new WorldRegistry();
        registry.register(new InventoryReader() {
            @Override public String adapterId() { return "test"; }
            @Override public List<InventoryInfo> inventories(LiveHolder holder) { checked.incrementAndGet(); return List.of(); }
            @Override public LiveInventory open(LiveHolder holder, String section) { return null; }
            @Override public Section inspectItem(ItemStack stack) { return null; }
        });
        var tools = new WorldTools(() -> null, registry, () -> SCOPE, Runnable::run, new ServerWork(System::nanoTime),
                () -> CompletableFuture.completedFuture(ClientFocus.NONE), place -> false, id -> null, null);
        var type = Class.forName("dev.packsage.world.WorldTools$FindSlice");
        var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var slice = (ServerWork.Slice<?>) constructor.newInstance(tools, null, new Anchor.Player(), 4, KindFilter.ANY, false, null);
        for (var entry : Map.<String, Object>of("level", world, "center", new Vec3(8, 64, 8),
                "chunks", List.of(new ChunkPos(0, 0))).entrySet()) {
            var field = type.getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(slice, entry.getValue());
        }
        var steps = 0;
        while (world.visited < 4000) {
            var before = world.visited;
            var checks = checked.get();
            assertTrue(slice.step());
            assertTrue(world.visited - before <= 64, "enumerated " + (world.visited - before));
            assertTrue(checked.get() - checks <= 64, "checked " + (checked.get() - checks));
            assertTrue(++steps < 200);
        }
        assertTrue(steps > 60);
        assertEquals(4000, checked.get());
    }

    @Test
    void holderSnapshotsSurviveEntityAndBlockEntityMutationsBetweenSteps() throws Exception {
        var world = DenseWorld.create(130);
        world.forbidGlobal = true;
        var chunk = (DenseWorld.Chunk) world.chunks.chunk;
        for (var i = 0; i < 130; i++) {
            var pos = new BlockPos(i % 16, 60 + i / 16, 8);
            chunk.blockEntities.put(pos, new BarrelBlockEntity(pos, Blocks.BARREL.defaultBlockState()));
        }
        var checked = new HashSet<Object>();
        var registry = new WorldRegistry();
        registry.register(new InventoryReader() {
            @Override public String adapterId() { return "test"; }
            @Override public List<InventoryInfo> inventories(LiveHolder holder) {
                var key = holder instanceof LiveHolder.OfEntity entity ? entity.entity().getUUID() : ((LiveHolder.OfBlock) holder).pos();
                assertTrue(checked.add(key), "duplicate holder " + key);
                return List.of();
            }
            @Override public LiveInventory open(LiveHolder holder, String section) { return null; }
            @Override public Section inspectItem(ItemStack stack) { return null; }
        });
        var tools = new WorldTools(() -> null, registry, () -> SCOPE, Runnable::run, new ServerWork(System::nanoTime),
                () -> CompletableFuture.completedFuture(ClientFocus.NONE), place -> false, id -> null, null);
        var type = Class.forName("dev.packsage.world.WorldTools$FindSlice");
        var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var slice = constructor.newInstance(tools, null, new Anchor.Player(), 16, KindFilter.ANY, false, null);
        for (var entry : Map.<String, Object>of("level", world, "center", new Vec3(8, 64, 8)).entrySet()) {
            var field = type.getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(slice, entry.getValue());
        }
        var collect = type.getDeclaredMethod("collect", ChunkPos.class);
        collect.setAccessible(true);
        assertFalse((boolean) collect.invoke(slice, new ChunkPos(0, 0)));
        // Force removal, shiftKeys and a rehash after the first step has obtained its traversal state.
        chunk.blockEntities.clear();
        var added = new BlockPos(8, 64, 8);
        chunk.blockEntities.put(added, new BarrelBlockEntity(added, Blocks.BARREL.defaultBlockState()));
        var steps = 0;
        while (!(boolean) collect.invoke(slice, new ChunkPos(0, 0))) {
            if (world.visited > 0 && world.entities.size() == 130) {
                world.entities.remove(129);
                world.entities.add(new ItemEntity(EntityType.ITEM, world));
                world.entities.remove(128);
            }
            assertTrue(++steps < 20);
        }
        var notes = type.getDeclaredField("notes");
        notes.setAccessible(true);
        assertTrue(((Set<?>) notes.get(slice)).stream().anyMatch(note -> note.toString().contains("snapshot")));
        assertTrue(world.visited >= 129);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"air", "cave_air", "void_air", "stone"})
    void inspectedAirDoesNotAdvertiseABlockHighlight(String kind) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        class Chunks extends net.minecraft.server.level.ServerChunkCache {
            net.minecraft.world.level.chunk.LevelChunk chunk;

            Chunks() { super(null, null, null, null, null, null, 0, 0, false, null, null, null); }
            @Override public net.minecraft.world.level.chunk.LevelChunk getChunkNow(int x, int z) { return chunk; }
        }
        class Level extends net.minecraft.server.level.ServerLevel {
            Chunks chunks;
            net.minecraft.world.level.block.state.BlockState state;

            Level() { super(null, null, null, null, null, null, null, false, 0, java.util.List.of(), false, null); }
            @Override public net.minecraft.server.level.ServerChunkCache getChunkSource() { return chunks; }
            @Override public net.minecraft.world.level.block.state.BlockState getBlockState(net.minecraft.core.BlockPos pos) { return state; }
            @Override public long getGameTime() { return 400; }
            @Override public long getDayTime() { return 600; }
        }
        var unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = unsafeField.get(null);
        var allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
        var chunks = (Chunks) allocate.invoke(unsafe, Chunks.class);
        chunks.chunk = (net.minecraft.world.level.chunk.LevelChunk) allocate.invoke(unsafe, net.minecraft.world.level.chunk.LevelChunk.class);
        var level = (Level) allocate.invoke(unsafe, Level.class);
        level.chunks = chunks;
        level.state = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(kind)).defaultBlockState();
        var server = (net.minecraft.client.server.IntegratedServer) allocate.invoke(unsafe, net.minecraft.client.server.IntegratedServer.class);
        var levels = net.minecraft.server.MinecraftServer.class.getDeclaredField("levels");
        levels.setAccessible(true);
        levels.set(server, Map.of(net.minecraft.world.level.Level.OVERWORLD, level));
        var inspect = WorldTools.class.getDeclaredMethod("inspectBlock", net.minecraft.server.MinecraftServer.class, BlockPlace.class, Evidence.Scope.class);
        inspect.setAccessible(true);
        var record = (Evidence.Record) inspect.invoke(new WorldTools(() -> server, new WorldRegistry(), () -> SCOPE,
                Runnable::run, new ServerWork(System::nanoTime), () -> java.util.concurrent.CompletableFuture.completedFuture(ClientFocus.NONE),
                place -> false, id -> null, null), server, new BlockPlace("minecraft:overworld", 1, 64, 2), SCOPE);
        assertEquals(new Evidence.JsonString("minecraft:" + kind), record.payload().values().get("block"));
        if (kind.equals("stone")) {
            assertEquals(1, record.targets().size());
            assertEquals("world-block|minecraft:overworld|1,64,2", record.targets().getFirst().targetId());
        } else {
            assertTrue(record.targets().isEmpty(), "air cannot resolve through block navigation");
        }
    }

    private static WorldTools tools() {
        return tools(() -> java.util.concurrent.CompletableFuture.completedFuture(ClientFocus.NONE));
    }

    private static WorldTools tools(java.util.function.Supplier<java.util.concurrent.CompletionStage<ClientFocus>> focus) {
        return new WorldTools(() -> null, new WorldRegistry(), () -> SCOPE, Runnable::run, new ServerWork(System::nanoTime), focus, place -> false, id -> null, null);
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
    void noteAnchorsResolveToTheNotesPlaceAndUnknownNotesFail(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = unsafeField.get(null);
        var server = (net.minecraft.client.server.IntegratedServer) unsafe.getClass().getMethod("allocateInstance", Class.class)
                .invoke(unsafe, net.minecraft.client.server.IntegratedServer.class);
        var running = net.minecraft.server.MinecraftServer.class.getDeclaredField("running");
        running.setAccessible(true);
        running.set(server, true);
        var level = (net.minecraft.server.level.ServerLevel) unsafe.getClass().getMethod("allocateInstance", Class.class)
                .invoke(unsafe, net.minecraft.server.level.ServerLevel.class);
        var levels = net.minecraft.server.MinecraftServer.class.getDeclaredField("levels");
        levels.setAccessible(true);
        levels.set(server, Map.of(net.minecraft.world.level.Level.OVERWORLD, level));
        var place = new BlockPlace("minecraft:overworld", 40, 70, -8);
        var work = new ServerWork(System::nanoTime);
        var tools = new WorldTools(() -> server, new WorldRegistry(), () -> SCOPE, Runnable::run, work,
                () -> java.util.concurrent.CompletableFuture.completedFuture(ClientFocus.NONE), block -> false, id -> null, null,
                id -> id.equals("n0000abcd") ? place : null);
        var anchor = WorldTools.anchor(Evidence.deserializeObject("{\"type\":\"note\",\"id\":\"n0000abcd\"}"));
        assertEquals(new Anchor.OfNote("n0000abcd"), anchor);
        var result = tools.resolveAnchor(anchor).toCompletableFuture();
        while (!result.isDone()) work.tick();
        assertEquals(place, result.get());
        var journal = new dev.packsage.journal.Journal(Runnable::run);
        journal.open(directory.resolve("journal.db"), () -> 400);
        journal.write(store -> null).toCompletableFuture().get();
        try {
            var journalTools = new dev.packsage.journal.JournalTools(journal, () -> SCOPE, Runnable::run, () -> server, tools::resolveAnchor);
            var remembered = journalTools.handle(new PackSageTools.ReadRequest("remember", Evidence.deserializeObject(
                    "{\"text\":\"iron farm\",\"at\":{\"type\":\"block\",\"place\":\"minecraft:overworld|40,70,-8\"}}"), SCOPE),
                    new Toolbox.Context(() -> SCOPE, Map.of(), new AdapterRegistry(), new Toolbox.DocumentBudget(), null)).toCompletableFuture();
            while (work.hasWork()) work.tick();
            assertEquals(place.text(), ((Evidence.JsonString) remembered.get(10, java.util.concurrent.TimeUnit.SECONDS)
                    .records().getFirst().payload().values().get("place")).value());
            assertEquals(place, journal.notes().all().getFirst().place());
        } finally {
            journal.close().get();
        }
        var unknown = new WorldTools(() -> server, new WorldRegistry(), () -> SCOPE, Runnable::run, work,
                () -> java.util.concurrent.CompletableFuture.completedFuture(ClientFocus.NONE), block -> false, id -> null, null, id -> null);
        var missing = unknown.resolveAnchor(anchor).toCompletableFuture();
        while (!missing.isDone()) work.tick();
        var failure = (Toolbox.Failure) assertThrows(ExecutionException.class, missing::get).getCause();
        assertEquals(Toolbox.Code.UNAVAILABLE, failure.code());
        assertTrue(failure.getMessage().contains("note n0000abcd has no saved place"));
        for (var json : java.util.List.of("{\"type\":\"note\",\"id\":\"bad\"}", "{\"type\":\"note\"}",
                "{\"type\":\"note\",\"id\":\"n0000abcd\",\"place\":\"minecraft:overworld|1,2,3\"}")) {
            assertEquals(Toolbox.Code.INVALID_ARGUMENTS, assertThrows(Toolbox.Failure.class,
                    () -> WorldTools.anchor(Evidence.deserializeObject(json))).code());
        }
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
                "list_items", "{\"holder\":\"player\",\"filter\":{\"tooltip\":[\"Luck\"]}}",
                "inspect_item", "{\"path\":\"player:" + ENTITY + ">minecraft/main#0@minecraft:stone\"}",
                "find_items", "{\"filter\":{\"ids\":[\"minecraft:stone\"],\"tooltip\":[\"Luck\"]}}");
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
                "{\"holder\":\"player\",\"inventory\":\"main\"}", "list_items",
                "{\"filter\":{\"tooltip\":[\"Luck\"]}}", "list_blocks",
                "{\"filter\":{\"text\":\"villager\",\"tooltip\":[\"Luck\"]}}", "list_entities",
                "{\"filter\":{\"tooltip\":\"Luck\"}}", "find_items").entrySet()) {
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
    void runMapsFailuresLikeServerReads() throws Exception {
        var tools = tools();
        var field = WorldTools.class.getDeclaredField("work");
        field.setAccessible(true);
        var work = (ServerWork) field.get(tools);
        for (var failure : new RuntimeException[] { new IllegalArgumentException("bad"), new IllegalStateException("x") }) {
            var result = tools.run(() -> true, WorldTools.once(() -> { throw failure; })).toCompletableFuture();
            while (work.hasWork()) work.tick();
            var mapped = (Toolbox.Failure) assertThrows(ExecutionException.class, result::get).getCause();
            assertEquals(failure instanceof IllegalArgumentException ? Toolbox.Code.INVALID_ARGUMENTS : Toolbox.Code.EXTRACTION_FAILED,
                    mapped.code());
            assertEquals(failure instanceof IllegalArgumentException ? "INVALID_ARGUMENTS: bad" : "EXTRACTION_FAILED: x",
                    mapped.getMessage());
        }
    }

    @Test
    void resultContinuationsRunOnTheWorkerNotTheServerThread() throws Exception {
        var work = new ServerWork(System::nanoTime);
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor(task -> new Thread(task, "world-worker"));
        try {
            var tools = new WorldTools(() -> null, new WorldRegistry(), () -> SCOPE, worker, work, () -> java.util.concurrent.CompletableFuture.completedFuture(ClientFocus.NONE), place -> false, id -> null, null);
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

    /** Counts steps; stops early only when allowed. */
    private static final class Steps implements ServerWork.Slice<String> {
        final String name;
        final int total;
        final boolean stoppable;
        int steps;
        boolean stopped;

        Steps(String name, int total, boolean stoppable) {
            this.name = name;
            this.total = total;
            this.stoppable = stoppable;
        }

        @Override
        public boolean step() {
            return ++steps < total;
        }

        @Override
        public String result() {
            return name + steps + (stopped ? " stopped" : "");
        }

        @Override
        public boolean stopEarly() {
            stopped = stoppable;
            return stoppable;
        }
    }

    @Test
    void earlyStopsReachTheSlicesInsideCombinators() {
        var first = new Steps("a", 10, true);
        var second = new Steps("b", 10, true);
        var pair = WorldTools.both(first, second);
        pair.step();
        assertTrue(pair.stopEarly());
        assertEquals(java.util.Map.entry("a1 stopped", "b0 stopped"), pair.result());

        var started = WorldTools.then(() -> "x", value -> WorldTools.mapped(new Steps(value, 10, true), text -> text + "!"));
        assertFalse(started.stopEarly(), "the first step must run before the slice it starts can stop");
        started.step();
        started.step();
        assertTrue(started.stopEarly());
        assertEquals("x1 stopped!", started.result());

        var stubborn = WorldTools.mapped(new Steps("c", 10, false), text -> text);
        stubborn.step();
        assertFalse(stubborn.stopEarly());
    }

    @Test
    void gatheringStopsOnlyAfterAtLeastOneNewChunk() {
        assertFalse(WorldTools.gatherCanStop(10, 4, 4));
        assertTrue(WorldTools.gatherCanStop(10, 5, 4));
        assertFalse(WorldTools.gatherCanStop(10, 10, 0));
    }

    @Test
    void slicedReadsReportTheGameTimeSpanTheyCovered() {
        var time = new long[] {100};
        var inner = new Steps("a", 3, true);
        var read = WorldTools.spanned(() -> time[0], inner);
        read.step();
        time[0] = 101;
        read.step();
        time[0] = 105;
        read.step();
        time[0] = 200;
        var result = read.result();
        assertEquals("a3", result.value());
        assertEquals(new WorldTools.ReadSpan(100, 105), result.span());
        assertTrue(read.stopEarly());
        assertTrue(inner.stopped);

        var area = WorldTools.spanPayload(new WorldTools.ReadSpan(100, 105));
        assertEquals(Evidence.deserializeObject("{\"fromGameTime\":100,\"toGameTime\":105}"), area);
    }

    @Test
    void everyBlockOrEntityHolderOfAnEntryGetsALinkableRecord() {
        var tools = tools();
        var request = new PackSageTools.ReadRequest("find_items", Evidence.deserializeObject("{\"filter\":{\"ids\":[\"minecraft:stone\"]}}"), SCOPE);
        var chestA = "block:minecraft:overworld|1,64,2";
        var chestB = "block:minecraft:overworld|5,64,2";
        var mule = "entity:3f1c2a4e-0000-4000-8000-000000000002";
        var stone = new ItemEntry("minecraft:stone", "Stone", 96, 1, java.util.List.of(
                ItemPath.parse("player:" + ENTITY + ">minecraft/main#0@minecraft:stone"),
                ItemPath.parse(chestA + ">minecraft/container#3@minecraft:stone"),
                ItemPath.parse(chestB + ">minecraft/container#0@minecraft:shulker_box>minecraft/container#1@minecraft:stone"),
                ItemPath.parse(mule + ">minecraft/chest#2@minecraft:stone")), false);
        var dirt = new ItemEntry("minecraft:dirt", "Dirt", 3, 1, java.util.List.of(
                ItemPath.parse(chestA + ">minecraft/container#4@minecraft:dirt")), false);

        var page = tools.entries(request, new WorldTools.EntriesState(java.util.List.of(stone, dirt), null, Evidence.Coverage.COMPLETE, null, new WorldTools.ReadSpan(100, 105)),
                Evidence.Coverage.COMPLETE, null);

        assertEquals(Evidence.Coverage.COMPLETE, page.coverage());
        var linked = new java.util.HashMap<String, String>();
        for (var record : page.records()) {
            assertEquals(Evidence.deserializeObject("{\"fromGameTime\":100,\"toGameTime\":105}"), record.payload().values().get("readSpan"));
            for (var target : record.targets()) {
                assertEquals(record.source().sourceHandle(), target.sourceHandle(), "a link opens only from a record with its handle");
                linked.put(target.sourceHandle(), target.targetId());
            }
        }
        assertEquals("world-block|minecraft:overworld|1,64,2", linked.get("block|minecraft:overworld|1,64,2"));
        assertEquals("world-block|minecraft:overworld|5,64,2", linked.get("block|minecraft:overworld|5,64,2"));
        assertEquals("world-entity|3f1c2a4e-0000-4000-8000-000000000002", linked.get("entity|3f1c2a4e-0000-4000-8000-000000000002"));
        assertEquals(1, page.records().stream().filter(record -> record.source().sourceHandle().equals("block|minecraft:overworld|1,64,2")).count(),
                "a holder shared by entries gets one record");
    }

    @Test
    void entriesAndHolderRecordsTogetherStayWithinTheToolOutputLimitAndTheRestContinues() {
        var tools = tools();
        var arguments = Evidence.deserializeObject("{\"filter\":{\"text\":\"\"}}");
        var request = new PackSageTools.ReadRequest("find_items", arguments, SCOPE);
        var entries = new java.util.ArrayList<ItemEntry>();
        for (var i = 0; i < 72; i++) {
            var location = ItemPath.parse("block:minecraft:overworld|" + i + ",64,0>minecraft/container#0@minecraft:item_" + i);
            entries.add(new ItemEntry("minecraft:item_" + i, "Item " + i, 1, 1, java.util.List.of(location), false));
        }
        var seen = new java.util.HashSet<String>();
        var page = tools.entries(request, new WorldTools.EntriesState(entries, null, Evidence.Coverage.COMPLETE, null),
                Evidence.Coverage.COMPLETE, null);
        var pages = 0;
        while (true) {
            pages++;
            assertTrue(page.records().size() <= 64, "page " + pages + " has " + page.records().size() + " records");
            var handles = page.records().stream().map(record -> record.source().sourceHandle()).collect(java.util.stream.Collectors.toSet());
            for (var record : page.records()) {
                if (!record.source().sourceHandle().startsWith("item|")) continue;
                seen.add(record.source().sourceHandle());
                var holder = record.source().sourceHandle().substring(5).split(">")[0].replace("block:", "block|");
                assertTrue(handles.contains(holder), "the holder of " + record.source().sourceHandle() + " has a record on its page");
            }
            if (page.nextCursor() == null) break;
            var continued = new PackSageTools.ReadRequest("find_items", Evidence.deserializeObject(
                    "{\"filter\":{\"text\":\"\"},\"cursor\":\"" + page.nextCursor() + "\"}"), SCOPE);
            page = tools.entries(continued, (WorldTools.EntriesState) tools.cursor(continued), Evidence.Coverage.COMPLETE, null);
        }
        assertEquals(72, seen.size(), "every entry appears on some page");
        assertEquals(Evidence.Coverage.COMPLETE, page.coverage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"list_items", "find_items"})
    void longItemNamesKeepEveryEntryReachableThroughTheRealEncoder(String tool) throws Exception {
        var provider = tools();
        var entries = new java.util.ArrayList<ItemEntry>();
        for (var i = 0; i < 65; i++) {
            entries.add(new ItemEntry("minecraft:item_" + i, "\"\n世".repeat(i == 0 ? 100_000 : 1000), 1, 1,
                    List.of(ItemPath.parse("block:minecraft:overworld|" + i % 3 + ",64,0>minecraft/container#" + i + "@minecraft:item_" + i)), false));
        }
        var arguments = tool.equals("list_items") ? "{\"holder\":\"player\"}" : "{\"filter\":{}}";
        var tools = new PackSageTools(request -> CompletableFuture.completedFuture(provider.entries(request,
                request.arguments().values().containsKey("cursor") ? (WorldTools.EntriesState) provider.cursor(request)
                        : new WorldTools.EntriesState(entries, null, Evidence.Coverage.COMPLETE, null), Evidence.Coverage.COMPLETE, null)));
        var seen = new HashSet<String>();
        int pages = 0;
        do {
            var response = tools.execute(new dev.packsage.model.ModelBackend.ToolCall("items", tool, arguments), SCOPE).toCompletableFuture().get();
            assertFalse(response.result().error(), response.result().serializedResult());
            var encoded = response.result().serializedResult();
            assertTrue(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 131_072);
            var json = com.google.gson.JsonParser.parseString(encoded).getAsJsonObject();
            for (var value : json.getAsJsonArray("records")) {
                var record = value.getAsJsonObject();
                var payload = record.getAsJsonObject("payload");
                if (!payload.has("item")) continue;
                assertTrue(seen.add(payload.get("item").getAsString()), "entries must be delivered exactly once");
                assertTrue(payload.get("name").getAsString().length() <= 1024);
                assertTrue(payload.get("nameTruncated").getAsBoolean());
                assertEquals("PARTIAL", record.getAsJsonObject("source").get("coverage").getAsString());
            }
            assertEquals("PARTIAL", json.get("coverage").getAsString());
            assertTrue(json.get("detail").getAsString().contains("truncated"));
            assertTrue(++pages < 20);
            if (!json.has("nextCall")) break;
            arguments = json.getAsJsonObject("nextCall").get("arguments").toString();
        } while (true);
        assertEquals(65, seen.size(), "the outer encoder must not discard entries or their continuation");
        assertTrue(pages > 2, "encoded bytes must cut pages before their record-count bound");
    }

    @Test
    void anEntryWithMoreHoldersThanFitLinksTheFirstOnesAndSaysHowManyAreLeft() {
        var tools = tools();
        var request = new PackSageTools.ReadRequest("find_items", Evidence.deserializeObject("{\"filter\":{\"ids\":[\"minecraft:stone\"]}}"), SCOPE);
        var locations = new java.util.ArrayList<ItemPath>();
        for (var i = 0; i < 70; i++) locations.add(ItemPath.parse("block:minecraft:overworld|" + i + ",64,0>minecraft/container#0@minecraft:stone"));
        var stone = new ItemEntry("minecraft:stone", "Stone", 70, 1, locations, false);
        var dirt = new ItemEntry("minecraft:dirt", "Dirt", 1, 1, java.util.List.of(
                ItemPath.parse("block:minecraft:overworld|0,64,0>minecraft/container#1@minecraft:dirt")), false);

        var page = tools.entries(request, new WorldTools.EntriesState(java.util.List.of(stone, dirt), null, Evidence.Coverage.COMPLETE, null),
                Evidence.Coverage.COMPLETE, null);

        assertEquals(64, page.records().size());
        assertEquals("item|" + locations.getFirst().text(), page.records().getFirst().source().sourceHandle());
        assertTrue(page.detail().contains("63 of 70"), page.detail());
        assertTrue(page.nextCursor() != null, "dirt continues on the next page");
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
        assertTrue(first.detail().contains("64 entries, the page limit"), first.detail());
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

        var search = new WorldTools.FindState(java.util.List.of(), 0, null);
        var merged = tools.entries(request, new WorldTools.EntriesState(java.util.List.of(), search, Evidence.Coverage.PARTIAL, note),
                Evidence.Coverage.COMPLETE, "Inventory minecraft/container could not be opened");
        assertEquals(Evidence.Coverage.PARTIAL, merged.coverage());
        assertEquals(note + "; Inventory minecraft/container could not be opened", merged.detail());
        var carried = (WorldTools.EntriesState) tools.cursor(new PackSageTools.ReadRequest("find_items", Evidence.deserializeObject(
                "{\"filter\":{\"ids\":[\"minecraft:stone\"]},\"cursor\":\"" + merged.nextCursor() + "\"}"), SCOPE));
        assertEquals(Evidence.Coverage.PARTIAL, carried.coverage());
        assertEquals(merged.detail(), carried.note());
    }

    @Test
    void unresolvedWordsAnswerWithACompleteResultAndADetail() {
        var result = WorldTools.unresolved("Your main hand is empty");
        assertTrue(result.records().isEmpty());
        assertEquals(Evidence.Coverage.COMPLETE, result.coverage());
        assertEquals(Evidence.Outcome.COMPLETE, result.outcome());
        assertEquals(null, result.nextCursor());
        assertEquals("Your main hand is empty", result.detail());

        var tools = tools();
        assertEquals(Toolbox.Code.UNAVAILABLE, failure(tools, "list_items", "{\"holder\":\"held\"}"));
        assertEquals(Toolbox.Code.UNAVAILABLE, failure(tools, "list_items", "{\"holder\":\"targeted>minecraft/container#0@minecraft:shulker_box\"}"));
        assertEquals(Toolbox.Code.UNAVAILABLE, failure(tools, "inspect_item", "{\"path\":\"offhand\"}"));
        assertEquals(Toolbox.Code.INVALID_ARGUMENTS, failure(tools, "inspect_item", "{\"path\":\"targeted\"}"), "targeted names a holder");
        assertEquals(Toolbox.Code.INVALID_ARGUMENTS, failure(tools, "list_items", "{\"holder\":\"held>bad\"}"));
    }

    @Test
    void theHolderSummaryLeadsTheFirstPageAndCountsTowardTheRecordLimit() {
        var tools = tools();
        var arguments = Evidence.deserializeObject("{\"holder\":\"player\"}");
        var request = new PackSageTools.ReadRequest("list_items", arguments, SCOPE);
        var entries = new java.util.ArrayList<ItemEntry>();
        var slotFacts = new java.util.HashMap<String, Evidence.JsonObject>();
        for (var i = 0; i < 70; i++) {
            var location = ItemPath.parse("player:" + ENTITY + ">minecraft/main#" + i + "@minecraft:item_" + i);
            entries.add(new ItemEntry("minecraft:item_" + i, "Item " + i, 1, 1, java.util.List.of(location), false));
            slotFacts.put(location.text(), Evidence.deserializeObject(i == 3 ? "{\"slot\":3,\"selected\":true}" : "{\"slot\":" + i + "}"));
        }
        var root = new PathRoot.Player(java.util.UUID.fromString(ENTITY));
        var main = new InventoryInfo(new InventoryId("minecraft", "main"), "Main inventory", 36, SlotRole.STORAGE);
        var bundle = new InventoryInfo(new InventoryId("minecraft", "bundle"), "Bundle", -1, SlotRole.STORAGE);
        var summary = new WorldTools.HolderSummary(root, java.util.List.of(new WorldTools.InventorySummary(main, 0),
                new WorldTools.InventorySummary(bundle, null)), Evidence.deserializeObject("{\"selectedSlot\":3}"));

        var first = tools.entries(request, new WorldTools.EntriesState(entries, null, Evidence.Coverage.COMPLETE, null, null, summary, slotFacts),
                Evidence.Coverage.COMPLETE, null);

        assertEquals(64, first.records().size());
        var lead = first.records().getFirst();
        assertEquals("holder|player:" + ENTITY, lead.source().sourceHandle());
        assertTrue(lead.targets().isEmpty(), "the player summary has no link");
        var payload = lead.payload().values();
        assertEquals(new Evidence.JsonString("player:" + ENTITY), payload.get("holder"));
        assertEquals(Evidence.deserializeObject("{\"selectedSlot\":3}").values().get("selectedSlot"), payload.get("selectedSlot"));
        var inventories = ((Evidence.JsonArray) payload.get("inventories")).values();
        assertEquals(Evidence.deserializeObject("{\"inventory\":\"minecraft/main\",\"label\":\"Main inventory\",\"slots\":36,\"role\":\"storage\",\"emptySlots\":0}"),
                inventories.getFirst());
        assertTrue(((Evidence.JsonObject) inventories.get(1)).values().get("emptySlots") instanceof Evidence.JsonNull);
        var selected = first.records().stream().filter(record -> record.source().sourceHandle().contains("#3@")).findFirst().orElseThrow();
        var slots = ((Evidence.JsonArray) selected.payload().values().get("slots")).values();
        assertEquals(Evidence.deserializeObject("{\"path\":\"player:" + ENTITY + ">minecraft/main#3@minecraft:item_3\",\"slot\":3,\"selected\":true}"),
                slots.getFirst());

        var continued = new PackSageTools.ReadRequest("list_items", Evidence.deserializeObject(
                "{\"holder\":\"player\",\"cursor\":\"" + first.nextCursor() + "\"}"), SCOPE);
        var second = tools.entries(continued, (WorldTools.EntriesState) tools.cursor(continued), Evidence.Coverage.COMPLETE, null);
        assertEquals(7, second.records().size());
        assertTrue(second.records().stream().noneMatch(record -> record.source().sourceHandle().startsWith("holder|")));
        assertTrue(second.records().getFirst().payload().values().containsKey("slots"), "slot facts carry over to later pages");

        var place = "minecraft:overworld|1,64,2";
        var chest = ItemPath.parse("block:" + place + ">minecraft/container#0@minecraft:stone");
        var blockSummary = new WorldTools.HolderSummary(chest.root(), java.util.List.of(), new Evidence.JsonObject(Map.of()));
        var block = tools.entries(new PackSageTools.ReadRequest("list_items", Evidence.deserializeObject("{\"holder\":\"block:" + place + "\"}"), SCOPE),
                new WorldTools.EntriesState(java.util.List.of(new ItemEntry("minecraft:stone", "Stone", 1, 1, java.util.List.of(chest), false)),
                        null, Evidence.Coverage.COMPLETE, null, null, blockSummary, Map.of()), Evidence.Coverage.COMPLETE, null);
        assertEquals(java.util.List.of("block|" + place, "item|" + chest.text()),
                block.records().stream().map(record -> record.source().sourceHandle()).toList(), "the summary's root is not repeated as a holder");
        assertEquals("world-block|" + place, block.records().getFirst().targets().getFirst().targetId());
    }

    @Test
    void aSectionWhoseLootIsNotGeneratedClaimsNoEmptySlotsAndSaysWhy() {
        var notes = new java.util.LinkedHashSet<String>();
        var pending = new InventoryInfo(new InventoryId("minecraft", "container"), "Container", 27, SlotRole.STORAGE, "minecraft:chests/simple_dungeon");
        assertEquals(new WorldTools.InventorySummary(pending, null), WorldTools.inventorySummary(pending, 0, notes));
        assertEquals(java.util.List.of("minecraft/container's loot has not been generated yet (table minecraft:chests/simple_dungeon); "
                + "its contents are decided when it is first opened"), java.util.List.copyOf(notes));

        notes.clear();
        var main = new InventoryInfo(new InventoryId("minecraft", "main"), "Main inventory", 36, SlotRole.STORAGE);
        assertEquals(new WorldTools.InventorySummary(main, 30), WorldTools.inventorySummary(main, 6, notes));
        var bundle = new InventoryInfo(new InventoryId("minecraft", "bundle"), "Bundle", -1, SlotRole.STORAGE);
        assertEquals(new WorldTools.InventorySummary(bundle, null), WorldTools.inventorySummary(bundle, 2, notes));
        assertTrue(notes.isEmpty(), notes.toString());
    }

    @Test
    void savedHolderEvidenceRereadsTheFreshListItemsSummary() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        class Players extends net.minecraft.server.players.PlayerList {
            net.minecraft.server.level.ServerPlayer player;
            Players() { super(null, null, null, 0); }
            @Override public net.minecraft.server.level.ServerPlayer getPlayer(java.util.UUID id) { return player; }
        }
        class Server extends net.minecraft.client.server.IntegratedServer {
            Players players;
            Server() { super(null, null, null, null, null, null, null); }
            @Override public com.mojang.authlib.GameProfile getSingleplayerProfile() {
                return new com.mojang.authlib.GameProfile(java.util.UUID.fromString(ENTITY), "Host");
            }
            @Override public net.minecraft.server.players.PlayerList getPlayerList() { return players; }
        }
        class Player extends net.minecraft.server.level.ServerPlayer {
            net.minecraft.world.entity.player.Inventory inventory;
            Player() { super(null, null, null, null); }
            @Override public net.minecraft.world.entity.player.Inventory getInventory() { return inventory; }
            @Override public java.util.UUID getUUID() { return java.util.UUID.fromString(ENTITY); }
        }
        var field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = field.get(null);
        var allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
        var player = (Player) allocate.invoke(unsafe, Player.class);
        player.inventory = new net.minecraft.world.entity.player.Inventory(player);
        var server = (Server) allocate.invoke(unsafe, Server.class);
        server.players = (Players) allocate.invoke(unsafe, Players.class);
        server.players.player = player;
        var running = net.minecraft.server.MinecraftServer.class.getDeclaredField("running");
        running.setAccessible(true);
        running.setBoolean(server, true);
        var level = (net.minecraft.server.level.ServerLevel) allocate.invoke(unsafe, net.minecraft.server.level.ServerLevel.class);
        var levelData = net.minecraft.world.level.Level.class.getDeclaredField("levelData");
        levelData.setAccessible(true);
        levelData.set(level, java.lang.reflect.Proxy.newProxyInstance(net.minecraft.world.level.storage.WritableLevelData.class.getClassLoader(),
                new Class<?>[]{net.minecraft.world.level.storage.WritableLevelData.class}, (proxy, method, arguments) -> 400L));
        var levels = net.minecraft.server.MinecraftServer.class.getDeclaredField("levels");
        levels.setAccessible(true);
        levels.set(server, Map.of(net.minecraft.world.level.Level.OVERWORLD, level));
        var work = new ServerWork(System::nanoTime);
        var registry = new WorldRegistry();
        var tools = new WorldTools(() -> server, registry, () -> SCOPE, Runnable::run, work,
                () -> java.util.concurrent.CompletableFuture.completedFuture(ClientFocus.NONE), place -> false, id -> null, null);
        var listed = tools.handle(new PackSageTools.ReadRequest("list_items", Evidence.deserializeObject("{\"holder\":\"player\"}"), SCOPE), null);
        for (int tick = 0; !listed.toCompletableFuture().isDone() && tick < 100; tick++) work.tick();
        var saved = listed.toCompletableFuture().join().records().getFirst();
        player.inventory.selected = 4;
        var context = new Toolbox.Context(() -> SCOPE, Map.of("saved", new Toolbox.Reference("world",
                saved.source().sourceHandle(), saved.kind(), SCOPE)), new AdapterRegistry(), new Toolbox.DocumentBudget(), null);
        var reread = tools.readEvidence(new PackSageTools.ReadRequest("read_evidence", Evidence.deserializeObject("{\"handles\":[\"saved\"]}"), SCOPE), context);
        for (int tick = 0; !reread.toCompletableFuture().isDone() && tick < 100; tick++) work.tick();
        var result = reread.toCompletableFuture().join();
        assertEquals(Evidence.Coverage.COMPLETE, result.coverage());
        assertEquals(1, result.records().size());
        assertEquals(saved.source().sourceHandle(), result.records().getFirst().source().sourceHandle());
        assertEquals(new Evidence.JsonNumber(java.math.BigDecimal.valueOf(4)), result.records().getFirst().payload().values().get("selectedSlot"));
        assertEquals(new Evidence.JsonArray(java.util.List.of()), result.records().getFirst().payload().values().get("inventories"));
        registry.register(new InventoryReader() {
            @Override public String adapterId() { return "fixture"; }
            @Override public java.util.List<InventoryInfo> inventories(LiveHolder holder) {
                return holder instanceof LiveHolder.OfPlayer ? java.util.List.of(new InventoryInfo(
                        new InventoryId("fixture", "storage"), "Storage", 65, SlotRole.STORAGE)) : java.util.List.of();
            }
            @Override public LiveInventory open(LiveHolder holder, String section) {
                return new LiveInventory() {
                    @Override public net.minecraft.world.item.ItemStack get(String entry) { return null; }
                    @Override public java.util.List<Map.Entry<String, net.minecraft.world.item.ItemStack>> entries() {
                        return net.minecraft.core.registries.BuiltInRegistries.ITEM.stream()
                                .filter(item -> item != net.minecraft.world.item.Items.AIR).limit(65)
                                .map(item -> Map.entry(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString(),
                                        new net.minecraft.world.item.ItemStack(item))).toList();
                    }
                };
            }
            @Override public Section inspectItem(net.minecraft.world.item.ItemStack stack) { return null; }
        });
        var large = tools.readEvidence(new PackSageTools.ReadRequest("read_evidence", Evidence.deserializeObject("{\"handles\":[\"saved\"]}"), SCOPE), context);
        for (int tick = 0; !large.toCompletableFuture().isDone() && tick < 100; tick++) work.tick();
        assertEquals(1, large.toCompletableFuture().join().records().size());
        var cursors = WorldTools.class.getDeclaredField("cursors");
        cursors.setAccessible(true);
        assertTrue(((Map<?, ?>) cursors.get(tools)).isEmpty(), "a holder reread must not retain an unused item page");
        registry.register(new InventoryReader() {
            @Override public String adapterId() { return "broken"; }
            @Override public java.util.List<InventoryInfo> inventories(LiveHolder holder) { throw new IllegalStateException("inventory unavailable"); }
            @Override public LiveInventory open(LiveHolder holder, String section) { return null; }
            @Override public Section inspectItem(net.minecraft.world.item.ItemStack stack) { return null; }
        });
        var partial = tools.readEvidence(new PackSageTools.ReadRequest("read_evidence", Evidence.deserializeObject("{\"handles\":[\"saved\"]}"), SCOPE), context);
        for (int tick = 0; !partial.toCompletableFuture().isDone() && tick < 100; tick++) work.tick();
        assertEquals(Evidence.Coverage.PARTIAL, partial.toCompletableFuture().join().coverage());
    }

    @Test
    void inspectAreaKeepsTheEntityPageNoteWhenItsListIsCut() {
        var blocks = new Page<>(java.util.List.of(new BlockSummary(Map.of(), java.util.List.of(), 0)), null, Evidence.Coverage.COMPLETE, null);
        var entities = AreaScan.page(java.util.List.<EntityHandle>of(), "5:0", 0, 2, java.util.List.of(AreaScan.PAGE_LIMIT_NOTE));
        assertTrue(entities.note().contains("continue with the cursor"), entities.note());

        var notes = WorldTools.areaNotes(blocks, entities);

        assertEquals(2, notes.size(), notes.toString());
        assertTrue(notes.getFirst().contains("list_entities"), notes.getFirst());
        assertEquals("2 chunks in range did not have their entities loaded yet, so entities there were not read", notes.get(1));
        assertTrue(notes.stream().noneMatch(note -> note.contains("cursor")), notes.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"An entity from a chunk snapshot disappeared during the read",
            "An entity section changed repeatedly during acquisition; this chunk snapshot is incomplete",
            "An entity section became unavailable during acquisition; this chunk snapshot is incomplete"})
    void inspectAreaKeepsConcreteEntityGapsWithoutCursorOrSpanInstructions(String gap) {
        var blocks = new Page<>(List.of(new BlockSummary(Map.of(), List.of(), 0)), null, Evidence.Coverage.COMPLETE, null);
        for (var cut : List.of(false, true)) {
            var entities = new Page<EntityHandle>(List.of(), cut ? "5:0" : null, Evidence.Coverage.PARTIAL,
                    gap + (cut ? "; " + AreaScan.PAGE_LIMIT_NOTE : "") + "; " + AreaScan.SNAPSHOT_NOTE.formatted(1, 2));
            var notes = WorldTools.areaNotes(blocks, entities);
            assertTrue(String.join("; ", notes).contains(gap), notes.toString());
            assertTrue(notes.stream().noneMatch(note -> note.contains("cursor") || note.contains("game-time span")), notes.toString());
        }
    }

    @Test
    void inspectAreaListsTheBlockNoteBeforeTheNotableCap() {
        var chest = new NotableBlock(new BlockHandle("minecraft:chest", new BlockPlace("minecraft:overworld", 0, 64, 0)), "container");
        var blocks = new Page<>(java.util.List.of(new BlockSummary(Map.of(), java.util.Collections.nCopies(AreaScan.SUMMARY_NOTABLE, chest), 0)),
                null, Evidence.Coverage.PARTIAL, "3 chunks in range were not loaded and were not fully read");
        var entities = new Page<EntityHandle>(java.util.List.of(), null, Evidence.Coverage.COMPLETE, null);

        var notes = WorldTools.areaNotes(blocks, entities);

        assertEquals(2, notes.size(), notes.toString());
        assertEquals("3 chunks in range were not loaded and were not fully read", notes.getFirst());
        assertTrue(notes.get(1).startsWith("Notable blocks reached the " + AreaScan.SUMMARY_NOTABLE + " cap"), notes.get(1));
    }

    @Test
    void openedPathsFailOnceTheMenuChanges() throws ReflectiveOperationException {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = unsafeField.get(null);
        var allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
        var host = (net.minecraft.server.level.ServerPlayer) allocate.invoke(unsafe, net.minecraft.server.level.ServerPlayer.class);
        var inventory = new net.minecraft.world.entity.player.Inventory(host);
        var inventoryField = net.minecraft.world.entity.player.Player.class.getDeclaredField("inventory");
        inventoryField.setAccessible(true);
        inventoryField.set(host, inventory);
        var inventoryMenu = allocate.invoke(unsafe, net.minecraft.world.inventory.InventoryMenu.class);
        var inventoryMenuField = net.minecraft.world.entity.player.Player.class.getDeclaredField("inventoryMenu");
        inventoryMenuField.setAccessible(true);
        inventoryMenuField.set(host, inventoryMenu);

        var tools = tools();
        var chest = new TestMenu(7);
        host.containerMenu = chest;
        assertEquals(null, tools.openedRoot(null, host, 7), "an id never issued");
        tools.issueOpened(null, chest);
        var opened = (LiveHolder.OfMenu) tools.openedRoot(null, host, 7);
        assertSame(chest, opened.menu());
        assertSame(inventory, opened.playerInventory());

        host.containerMenu = new TestMenu(8);
        assertEquals(null, tools.openedRoot(null, host, 7), "a different menu opened");
        var reused = new TestMenu(7);
        host.containerMenu = reused;
        assertEquals(null, tools.openedRoot(null, host, 7), "a later menu that reuses the id, as after a rejoin or respawn");
        tools.issueOpened(null, reused);
        assertSame(reused, ((LiveHolder.OfMenu) tools.openedRoot(null, host, 7)).menu());
        host.containerMenu = chest;
        assertEquals(null, tools.openedRoot(null, host, 7), "the earlier menu with the same id");
        host.containerMenu = host.inventoryMenu;
        assertEquals(null, tools.openedRoot(null, host, 7), "the menu closed");
        assertEquals(null, tools.openedRoot(null, host, 0), "the player inventory is not an opened menu");
        assertEquals(null, tools.openedRoot(null, null, 7), "the player left");

        var gone = WorldTools.pathFailure(ItemPath.parse("opened:7>minecraft/menu#3@minecraft:oak_planks"),
                new PathResolver.Resolution(null, null, 0, PathResolver.Failure.ROOT_GONE));
        assertEquals(Toolbox.Code.UNAVAILABLE, gone.code());
        assertEquals("UNAVAILABLE: the menu is no longer open", gone.getMessage());
    }

    @Test
    void listingAnOpenedMenuGoesOnWithoutTheTitleWhenTheFocusCaptureFails() {
        var rejected = tools(() -> {
            throw new IllegalStateException("client is closing");
        });
        var failed = tools(() -> java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("no screen")));
        for (var tools : java.util.List.of(rejected, failed)) {
            assertSame(ClientFocus.NONE, tools.focus(null, true).toCompletableFuture().join());
            assertSame(ClientFocus.NONE, tools.focus(HolderWords.parse("opened"), true).toCompletableFuture().join());
            var targeted = tools.focus(HolderWords.parse("targeted"), false).toCompletableFuture();
            var failure = org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.CompletionException.class, targeted::join);
            assertEquals(Toolbox.Code.UNAVAILABLE, ((Toolbox.Failure) failure.getCause()).code());
        }
    }

    @Test
    void menuFactsNameTheMenuItsTitleAndPreviewsAndFlagAModdedMenu() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var menu = new TestMenu(7);
        var storage = new net.minecraft.world.SimpleContainer(2);
        menu.slots.add(new net.minecraft.world.inventory.Slot(storage, 0, 0, 0));
        menu.slots.add(new net.minecraft.world.inventory.NonInteractiveResultSlot(storage, 1, 0, 0));
        var holder = new LiveHolder.OfMenu(menu, new net.minecraft.world.SimpleContainer(36));
        var notes = new java.util.LinkedHashSet<String>();

        var facts = WorldTools.menuFacts(holder, new ClientFocus(null, 7, "Storage"), notes).values();

        assertEquals(new Evidence.JsonString("unknown"), facts.get("menuType"));
        assertEquals(new Evidence.JsonString("Storage"), facts.get("title"));
        assertEquals(new Evidence.JsonArray(java.util.List.of(new Evidence.JsonObject(Map.of(
                "slot", new Evidence.JsonNumber(java.math.BigDecimal.ONE), "kind", new Evidence.JsonString("recipe_preview"))))), facts.get("excludedSlots"));
        assertEquals(java.util.Set.of("unknown is not a vanilla menu; some listed slots may be previews"), notes);
        assertFalse(WorldTools.menuFacts(holder, new ClientFocus(null, 8, "Other"), notes).values().containsKey("title"),
                "the client shows a different menu");

        var broken = new TestMenu(7);
        broken.slots.add(new net.minecraft.world.inventory.Slot(storage, 0, 0, 0) {
            @Override
            public boolean isActive() {
                throw new IllegalStateException("slot broke");
            }
        });
        var brokenNotes = new java.util.LinkedHashSet<String>();
        var brokenFacts = WorldTools.menuFacts(new LiveHolder.OfMenu(broken, new net.minecraft.world.SimpleContainer(36)), ClientFocus.NONE, brokenNotes).values();
        assertEquals(new Evidence.JsonNull(), brokenFacts.get("excludedSlots"));
        assertEquals(java.util.Set.of("The preview slots of unknown could not be read: slot broke"), brokenNotes);
    }

    @Test
    void worldToolsAcceptEveryHolderSearchInventoryCouldRead() throws Exception {
        var requests = new java.util.ArrayList<PackSageTools.ReadRequest>();
        var tools = new PackSageTools(request -> {
            requests.add(request);
            return java.util.concurrent.CompletableFuture.completedFuture(new Evidence.Result(java.util.List.of(),
                    Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null));
        });
        var calls = new java.util.LinkedHashMap<String, String>();
        for (var holder : java.util.List.of("player", "held", "offhand", "targeted", "ridden", "opened")) {
            calls.put("{\"holder\":\"" + holder + "\"}", "list_items");
        }
        calls.put("{\"holder\":\"player\",\"filter\":{\"ids\":[\"minecraft:diamond\"],\"tags\":[\"c:gems\"],\"text\":\"diamond\",\"tooltip\":[\"Unbreakable\"]}}", "list_items");
        calls.put("{\"path\":\"held\"}", "inspect_item");
        calls.put("{\"path\":\"offhand\"}", "inspect_item");
        calls.put("{\"path\":\"player:00000000-0000-0000-0000-000000000001>minecraft/main#3@minecraft:diamond\"}", "inspect_item");
        calls.put("{\"path\":\"held>minecraft/container#0@minecraft:diamond\"}", "inspect_item");
        calls.put("{\"holder\":\"player\",\"inventory\":\"minecraft/main\"}", "list_items");

        for (var call : calls.entrySet()) {
            requests.clear();
            var response = tools.execute(new dev.packsage.model.ModelBackend.ToolCall("coverage", call.getValue(), call.getKey()), SCOPE)
                    .toCompletableFuture().get();
            assertFalse(response.result().error(), call.getValue() + " " + call.getKey() + ": " + response.result().serializedResult());
            assertEquals(1, requests.size(), call.getKey());
            assertEquals(call.getValue(), requests.getFirst().tool());
            assertEquals(Evidence.deserializeObject(call.getKey()), requests.getFirst().arguments(), call.getKey());
        }
    }

    /** A menu that fails the test when anything moves, clicks or syncs its slots. */
    private static final class TestMenu extends net.minecraft.world.inventory.AbstractContainerMenu {
        private TestMenu(int containerId) {
            super(null, containerId);
        }

        @Override
        public net.minecraft.world.item.ItemStack quickMoveStack(net.minecraft.world.entity.player.Player player, int slotIndex) {
            return fail("quickMoveStack");
        }

        @Override
        public void broadcastChanges() {
            fail("broadcastChanges");
        }

        @Override
        public void slotsChanged(net.minecraft.world.Container container) {
            fail("slotsChanged");
        }

        @Override
        public void clicked(int slot, int button, net.minecraft.world.inventory.ClickType type, net.minecraft.world.entity.player.Player player) {
            fail("clicked");
        }

        @Override
        public boolean stillValid(net.minecraft.world.entity.player.Player player) {
            return true;
        }
    }
}
