package dev.packsage.world;

import dev.packsage.knowledge.AdapterRegistry;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.model.PackSageTools;
import dev.packsage.world.minecraft.LocateBiome;
import dev.packsage.world.minecraft.MinecraftLoot;
import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class WorldKnowledgeToolsTest {
    @Test
    void savedVerdictsConfirmDropOrKeepPredictions() {
        var hits = java.util.stream.IntStream.range(0, 3).mapToObj(x -> new dev.packsage.world.minecraft.LocateStructure.Hit(
                "minecraft:test", x, 0, x * 16, 72, 0, dev.packsage.world.minecraft.LocateStructure.Kind.PREDICTED,
                "placement and biome checks")).toList();
        var confirmed = WorldKnowledgeTools.confirm(hits, chunk -> dev.packsage.world.minecraft.SavedStarts.Verdict.values()[chunk.x]);
        assertEquals(2, confirmed.size());
        assertEquals(dev.packsage.world.minecraft.LocateStructure.Kind.FOUND, confirmed.getFirst().kind());
        assertEquals("saved chunk", confirmed.getFirst().basis());
        assertEquals(hits.get(2), confirmed.getLast());
    }

    @Test
    void structureRecordsHaveEstimatedYHorizontalDistanceLinksAndPartialCoverage() {
        var request = new PackSageTools.ReadRequest("locate", Evidence.deserializeObject("{\"structure\":\"minecraft:test\"}"), SCOPE);
        var hits = List.of(new dev.packsage.world.minecraft.LocateStructure.Hit("minecraft:test", 6, 8, 96, 72, 128,
                dev.packsage.world.minecraft.LocateStructure.Kind.PREDICTED, "placement and biome checks"));
        var result = new WorldKnowledgeTools(tools()).structureResult(request,
                new WorldTools.Center("minecraft:overworld", new Vec3(0, 999, 0)), "minecraft:test", 100,
                new dev.packsage.world.minecraft.LocateStructure.Result(hits, 2, List.of("packsage:unsupported")), false);
        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        assertNotNull(result.nextCursor());
        assertTrue(result.detail().contains("packsage:unsupported"));
        var record = result.records().getFirst();
        assertEquals("minecraft:test (predicted) at 96, 128", record.title());
        assertEquals("world-place|minecraft:overworld|96,72,128", record.targets().getFirst().targetId());
        assertEquals(new Evidence.JsonBoolean(true), record.payload().values().get("yEstimated"));
        assertEquals(WorldTools.number(160), record.payload().values().get("distanceBlocks"));
    }

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

    private static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** A loot registry as the game reloads it, with the built-in empty table registered, plus the given tables. */
    private static HolderLookup.Provider reloadedLoot(Map<String, LootTable> tables) {
        var loot = new MappedRegistry<LootTable>(Registries.LOOT_TABLE, Lifecycle.stable());
        loot.register(BuiltInLootTables.EMPTY, LootTable.EMPTY, RegistrationInfo.BUILT_IN);
        tables.forEach((id, table) -> loot.register(MinecraftLoot.table(id), table, RegistrationInfo.BUILT_IN));
        return HolderLookup.Provider.create(Stream.of(loot.asLookup()));
    }

    private static PackSageTools.ReadRequest lootRequest() {
        return new PackSageTools.ReadRequest("read_loot", Evidence.deserializeObject("{\"table\":\"minecraft:chests/test\"}"), SCOPE);
    }

    private static MinecraftLoot.Encoded encoded(int pools, int poolBytes, boolean idsBounded) {
        var list = new java.util.ArrayList<Evidence.JsonObject>();
        for (int pool = 0; pool < pools; pool++) list.add(new Evidence.JsonObject(Map.of("filler", new Evidence.JsonString("x".repeat(poolBytes)))));
        return new MinecraftLoot.Encoded(new Evidence.JsonObject(Map.of()), list, List.of("minecraft:stone"), List.of(), idsBounded);
    }

    @Test
    void theServerStepLooksTheTableUpWithoutEncodingIt() {
        bootstrap();
        var table = LootTable.lootTable().withPool(LootPool.lootPool().add(LootItem.lootTableItem(Items.ROTTEN_FLESH))).build();
        var registries = reloadedLoot(Map.of("packsage:test", table));
        var fetched = WorldKnowledgeTools.fetch(registries, MinecraftLoot.table("packsage:test"), "table", null, null, 7);
        assertSame(table, fetched.table().orElseThrow());
        assertSame(registries, fetched.registries());
        var read = WorldKnowledgeTools.encoded(fetched);
        assertEquals(List.of("minecraft:rotten_flesh"), read.table().orElseThrow().itemIds());
        assertEquals(7, read.readAt());
        assertTrue(WorldKnowledgeTools.fetch(registries, MinecraftLoot.table("packsage:none"), "table", null, null, 7).table().isEmpty());
    }

    @Test
    void lootCoverageNotesAndTargetsFollowThePage() {
        var knowledge = new WorldKnowledgeTools(tools());
        var bounded = knowledge.lootResult(lootRequest(), new WorldKnowledgeTools.LootRead("minecraft:chests/test", "table",
                Optional.of(encoded(1, 10, true)), null, null, 5), 0);
        assertEquals(Evidence.Coverage.PARTIAL, bounded.coverage());
        assertNull(bounded.nextCursor());
        assertTrue(bounded.detail().contains("first " + MinecraftLoot.MAX_IDS), bounded.detail());
        assertEquals(Evidence.Coverage.PARTIAL, bounded.records().getFirst().source().coverage());
        assertTrue(bounded.records().getFirst().targets().isEmpty());

        var place = new BlockPlace("minecraft:overworld", 1, 2, 3);
        var pending = knowledge.lootResult(lootRequest(), new WorldKnowledgeTools.LootRead("minecraft:chests/test", "place:" + place.text(),
                Optional.of(encoded(1, 10, false)), place, 42L, 5), 0);
        assertEquals(Evidence.Coverage.COMPLETE, pending.coverage());
        assertTrue(pending.detail().contains("first opened"), pending.detail());
        var payload = pending.records().getFirst().payload().values();
        assertEquals(new Evidence.JsonBoolean(true), payload.get("pending"));
        assertEquals(WorldTools.number(42), payload.get("seed"));
        assertEquals(WorldNavigation.blockId(place), pending.records().getFirst().targets().getFirst().targetId());

        var paged = knowledge.lootResult(lootRequest(), new WorldKnowledgeTools.LootRead("minecraft:chests/test", "table",
                Optional.of(encoded(3, 10_000, false)), null, null, 5), 0);
        assertEquals(Evidence.Coverage.PARTIAL, paged.coverage());
        assertNotNull(paged.nextCursor());
        assertTrue(paged.detail().contains("follow"), paged.detail());

        var unknown = knowledge.lootResult(lootRequest(), new WorldKnowledgeTools.LootRead("minecraft:chests/test", "table",
                Optional.empty(), null, null, 5), 0);
        assertEquals(Evidence.Coverage.COMPLETE, unknown.coverage());
        assertEquals(WorldKnowledgeTools.UNKNOWN_TABLE, unknown.detail());
    }

    @Test
    void entityUuidsAreRecognizedInEitherCase() {
        var id = java.util.UUID.fromString("1b4e28ba-2fa1-11d2-883f-0016d3cca427");
        assertEquals(id, WorldKnowledgeTools.uuidOrNull("1B4E28BA-2FA1-11D2-883F-0016D3CCA427"));
        assertEquals(id, WorldKnowledgeTools.uuidOrNull("1b4e28ba-2fa1-11d2-883f-0016d3cca427"));
        assertNull(WorldKnowledgeTools.uuidOrNull("minecraft:zombie"));
    }

    @Test
    void aContainerWithoutPendingLootIsABlockRecord() {
        var place = new BlockPlace("minecraft:overworld", 1, 2, 3);
        var record = WorldKnowledgeTools.noPendingRecord(SCOPE, new WorldKnowledgeTools.NoPendingLoot(place, "minecraft:chest", 5));
        assertEquals(Evidence.RecordKind.BLOCK, record.kind());
        assertEquals("block|" + place.text(), record.source().sourceHandle());
        assertEquals(new Evidence.JsonBoolean(false), record.payload().values().get("pending"));
        assertEquals(new Evidence.JsonString(WorldKnowledgeTools.NO_PENDING_LOOT), record.payload().values().get("note"));
    }

    @Test
    void unknownEmptyAndNoDropTablesAreDistinct() {
        bootstrap();
        HolderLookup.Provider registries = VanillaRegistries.createLookup();
        var reloaded = reloadedLoot(Map.of());
        var unknown = WorldKnowledgeTools.lootPayload("packsage:nothing_here", Optional.empty(), null).values();
        assertEquals(new Evidence.JsonBoolean(false), unknown.get("exists"));
        assertEquals(new Evidence.JsonString("no loot table has this id in the loaded data packs"), unknown.get("note"));
        var noPools = MinecraftLoot.encode(LootTable.EMPTY, registries);
        var air = MinecraftLoot.lookup(reloaded, MinecraftLoot.blockTable("minecraft:air")).map(table -> MinecraftLoot.encode(table, registries));
        assertEquals(BuiltInLootTables.EMPTY, MinecraftLoot.blockTable("minecraft:air"));
        for (var table : List.of(Optional.of(noPools), air)) {
            var payload = WorldKnowledgeTools.lootPayload("minecraft:empty", table, MinecraftLoot.page(table.orElseThrow(), 0)).values();
            assertEquals(new Evidence.JsonBoolean(true), payload.get("exists"));
            assertEquals(new Evidence.JsonBoolean(true), payload.get("empty"));
            assertEquals(new Evidence.JsonString("this table drops nothing"), payload.get("note"));
        }
    }

    @Test
    void biomeIdsAndTagsResolveAgainstTheRegistryAndDimension() {
        bootstrap();
        var lookup = VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME);
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        var plains = registry.register(Biomes.PLAINS, lookup.getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        var desert = registry.register(Biomes.DESERT, lookup.getOrThrow(Biomes.DESERT).value(), RegistrationInfo.BUILT_IN);
        registry.bindTags(Map.of(TagKey.create(Registries.BIOME, ResourceLocation.parse("packsage:dry")), List.of(desert)));
        var possible = Set.<Holder<Biome>>of(plains, desert);

        assertEquals(Set.of("minecraft:desert"), WorldKnowledgeTools.biomeTargets(registry, possible, "minecraft:desert"));
        assertEquals(Set.of("minecraft:desert"), WorldKnowledgeTools.biomeTargets(registry, possible, "#packsage:dry"));
        assertTrue(WorldKnowledgeTools.biomeTargets(registry, Set.of(plains), "minecraft:desert").isEmpty());
        assertTrue(WorldKnowledgeTools.biomeTargets(registry, Set.of(plains), "#packsage:dry").isEmpty());
        for (var query : List.of("packsage:des", "#packsage:des", "MINECRAFT:DES", "#PACKSAGE:DES")) {
            var failure = assertThrows(Toolbox.Failure.class, () -> WorldKnowledgeTools.biomeTargets(registry, possible, query));
            assertEquals(Toolbox.Code.INVALID_ARGUMENTS, failure.code());
            assertTrue(failure.getMessage().contains("minecraft:desert"), failure.getMessage());
        }
    }

    @Test
    void biomeHitCarriesSamplingFactsAndAPersistentPlaceLink() {
        var request = new PackSageTools.ReadRequest("locate", Evidence.deserializeObject("{\"biome\":\"#packsage:dry\"}"), SCOPE);
        var result = new WorldKnowledgeTools(tools()).biomeResult(request, new WorldTools.Center("minecraft:overworld", new Vec3(0, 64, 0)),
                "#packsage:dry", 100, new LocateBiome.Result(new LocateBiome.Hit("minecraft:desert", 96, 128, 128), -1, 10));
        assertEquals(Evidence.Coverage.COMPLETE, result.coverage());
        assertNull(result.nextCursor());
        assertEquals(1, result.records().size());
        var record = result.records().getFirst();
        assertEquals(Evidence.RecordKind.OTHER, record.kind());
        assertEquals("locate|minecraft:overworld|minecraft:desert|96,128", record.source().sourceHandle());
        assertEquals("minecraft:desert at 96, 128, 128", record.title());
        assertEquals("world-place|minecraft:overworld|96,128,128", record.targets().getFirst().targetId());
        assertEquals(Evidence.deserializeObject("""
                {"kind":"sampled","biome":"minecraft:desert","query":"#packsage:dry","dimension":"minecraft:overworld",
                 "place":"minecraft:overworld|96,128,128","distanceBlocks":160,"resolution":{"horizontalBlocks":32,"verticalBlocks":64}}
                """), record.payload());
    }

    @Test
    void biomeEarlyStopKeepsTheCenterAndQueryInABoundCursor() {
        var tools = tools();
        var request = new PackSageTools.ReadRequest("locate", Evidence.deserializeObject("{\"biome\":\"minecraft:desert\",\"radius\":4}"), SCOPE);
        var center = new WorldTools.Center("minecraft:overworld", new Vec3(-12.5, 70, 19.5));
        var knowledge = new WorldKnowledgeTools(tools);
        var partial = knowledge.biomeResult(request, center, "minecraft:desert", 4, new LocateBiome.Result(null, 10, 10));
        assertEquals(Evidence.Coverage.PARTIAL, partial.coverage());
        assertTrue(partial.records().isEmpty());
        assertTrue(partial.detail().contains("500 ms"), partial.detail());
        assertTrue(partial.detail().contains("approximately 64 blocks"), partial.detail());
        var resumed = new PackSageTools.ReadRequest("locate", Evidence.deserializeObject("{\"biome\":\"minecraft:desert\",\"radius\":4,\"cursor\":\""
                + partial.nextCursor() + "\"}"), SCOPE);
        var state = WorldTools.state(tools.cursor(resumed), WorldKnowledgeTools.BiomeState.class);
        assertEquals(center, state.center());
        assertEquals("minecraft:desert", state.query());
        assertEquals(4, state.radius());
        assertEquals(10, state.nextIndex());
        var complete = knowledge.biomeResult(request, center, "minecraft:desert", 4, new LocateBiome.Result(null, -1, 15));
        assertEquals(Evidence.Coverage.COMPLETE, complete.coverage());
        assertTrue(complete.records().isEmpty());
        assertNull(complete.nextCursor());
        assertTrue(complete.detail().contains("no matching biome within 4 chunks"), complete.detail());
    }

    @Test
    void oddBiomeRadiiReportTheRoundedDownSamplingDistance() {
        var request = new PackSageTools.ReadRequest("locate", Evidence.deserializeObject("{\"biome\":\"minecraft:desert\",\"radius\":3}"), SCOPE);
        var result = new WorldKnowledgeTools(tools()).biomeResult(request, new WorldTools.Center("minecraft:overworld", new Vec3(0, 64, 0)),
                "minecraft:desert", 3, new LocateBiome.Result(null, -1, 9));
        assertEquals(Evidence.Coverage.COMPLETE, result.coverage());
        assertTrue(result.detail().contains("radius rounded down to 32-block rings"), result.detail());
        assertTrue(result.detail().contains("ring 1 at 32 blocks from the center"), result.detail());
    }

    /** A read-only server graph: unused chunk machinery stays uninitialized so accidental chunk reads fail. */
    private record BiomeWorld(WorldTools tools, ServerWork work) {
        static BiomeWorld create(boolean keylessSamples) throws ReflectiveOperationException {
            bootstrap();
            var lookup = VanillaRegistries.createLookup();
            var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
            var plains = registry.register(Biomes.PLAINS, lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
            var desert = registry.register(Biomes.DESERT, lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.DESERT).value(), RegistrationInfo.BUILT_IN);
            var source = new net.minecraft.world.level.biome.BiomeSource() {
                @Override
                protected com.mojang.serialization.MapCodec<? extends net.minecraft.world.level.biome.BiomeSource> codec() {
                    return net.minecraft.world.level.biome.FixedBiomeSource.CODEC;
                }

                @Override
                protected Stream<Holder<Biome>> collectPossibleBiomes() {
                    return keylessSamples ? Stream.of(plains, desert) : Stream.of(plains);
                }

                @Override
                public Holder<Biome> getNoiseBiome(int x, int y, int z, net.minecraft.world.level.biome.Climate.Sampler sampler) {
                    if (!keylessSamples) return fail("an impossible biome must finish without sampling");
                    return x == 8 && z == 0 ? desert : Holder.direct(plains.value());
                }
            };
            var unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            var unsafe = unsafeField.get(null);
            var allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
            var server = (net.minecraft.client.server.IntegratedServer) allocate.invoke(unsafe, net.minecraft.client.server.IntegratedServer.class);
            var level = (net.minecraft.server.level.ServerLevel) allocate.invoke(unsafe, net.minecraft.server.level.ServerLevel.class);
            var chunks = (net.minecraft.server.level.ServerChunkCache) allocate.invoke(unsafe, net.minecraft.server.level.ServerChunkCache.class);
            var chunkMap = (net.minecraft.server.level.ChunkMap) allocate.invoke(unsafe, net.minecraft.server.level.ChunkMap.class);
            var random = allocate.invoke(unsafe, net.minecraft.world.level.levelgen.RandomState.class);
            var generator = new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(source,
                    lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.OVERWORLD));
            for (var binding : List.of(
                    new Object[] { net.minecraft.server.MinecraftServer.class, server, "running", true },
                    new Object[] { net.minecraft.server.MinecraftServer.class, server, "levels", Map.of(net.minecraft.world.level.Level.OVERWORLD, level) },
                    new Object[] { net.minecraft.world.level.Level.class, level, "registryAccess", new net.minecraft.core.RegistryAccess.ImmutableRegistryAccess(List.of(registry)) },
                    new Object[] { net.minecraft.world.level.Level.class, level, "dimensionTypeRegistration",
                            lookup.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD) },
                    new Object[] { net.minecraft.server.level.ServerLevel.class, level, "chunkSource", chunks },
                    new Object[] { net.minecraft.server.level.ServerChunkCache.class, chunks, "chunkMap", chunkMap },
                    new Object[] { net.minecraft.server.level.ChunkMap.class, chunkMap, "randomState", random },
                    new Object[] { net.minecraft.server.level.ChunkMap.class, chunkMap, "worldGenContext",
                            new net.minecraft.world.level.chunk.status.WorldGenContext(level, generator, null, null, null) })) {
                var field = ((Class<?>) binding[0]).getDeclaredField((String) binding[2]);
                field.setAccessible(true);
                field.set(binding[1], binding[3]);
            }
            var work = new ServerWork(System::nanoTime);
            return new BiomeWorld(new WorldTools(() -> server, new WorldRegistry(), () -> SCOPE, Runnable::run, work,
                    () -> java.util.concurrent.CompletableFuture.completedFuture(ClientFocus.NONE), place -> false, id -> null, null), work);
        }
    }

    @Test
    void locateReportsWhenTheSourceCannotProduceTheBiome() throws Exception {
        var world = BiomeWorld.create(false);
        var request = new PackSageTools.ReadRequest("locate", Evidence.deserializeObject("""
                {"biome":"minecraft:desert","center":{"type":"block","place":"minecraft:overworld|0,64,0"}}
                """), SCOPE);
        var result = world.tools().handle(request, null).toCompletableFuture();
        while (!result.isDone()) world.work().tick();
        assertEquals(Evidence.Coverage.COMPLETE, result.get().coverage());
        assertTrue(result.get().records().isEmpty());
        assertNull(result.get().nextCursor());
        assertEquals("this dimension's biome source never produces it", result.get().detail());
    }

    @Test
    void locateSkipsKeylessSamplesAndNullRadiusUsesTheDefault() throws Exception {
        var world = BiomeWorld.create(true);
        var request = new PackSageTools.ReadRequest("locate", Evidence.deserializeObject("""
                {"biome":"minecraft:desert","radius":null,"center":{"type":"block","place":"minecraft:overworld|0,64,0"}}
                """), SCOPE);
        var result = world.tools().handle(request, null).toCompletableFuture();
        while (!result.isDone()) world.work().tick();
        assertEquals(Evidence.Coverage.COMPLETE, result.get().coverage());
        assertEquals("minecraft:desert at 32, 64, 0", result.get().records().getFirst().title());
    }

    @Test
    void locateRejectsInvalidRadiiAndMismatchedCursors() throws Exception {
        var world = BiomeWorld.create(false);
        for (var radius : List.of("0", "513", "1.5")) {
            assertEquals(Toolbox.Code.INVALID_ARGUMENTS, failure(world.tools(), "locate",
                    "{\"biome\":\"minecraft:desert\",\"radius\":" + radius + "}"), radius);
        }
        var request = new PackSageTools.ReadRequest("locate", Evidence.deserializeObject("{\"biome\":\"minecraft:desert\",\"radius\":4}"), SCOPE);
        var partial = new WorldKnowledgeTools(world.tools()).biomeResult(request,
                new WorldTools.Center("minecraft:overworld", new Vec3(0, 64, 0)), "minecraft:desert", 4, new LocateBiome.Result(null, 10, 10));
        assertEquals(Toolbox.Code.INVALID_CURSOR, failure(world.tools(), "locate",
                "{\"biome\":\"minecraft:plains\",\"radius\":4,\"cursor\":\"" + partial.nextCursor() + "\"}"));
    }
}
