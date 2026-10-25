package dev.packsage.world;

import dev.packsage.knowledge.AdapterRegistry;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.model.PackSageTools;
import net.minecraft.SharedConstants;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressToolsTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "resources", "team", "runtime", 1);
    private static final ProgressId ROOT = new ProgressId("minecraft", "minecraft:story/root");
    private static final ProgressHandle STONE = new ProgressHandle(new ProgressId("minecraft", "minecraft:story/mine_stone"), ROOT, "Stone Age", ProgressStatus.DONE);
    private static final ProgressHandle HIDDEN = new ProgressHandle(new ProgressId("minecraft", "minecraft:story/hidden"), ROOT, null, ProgressStatus.HIDDEN);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final class Fixture {
        final ServerWork work;
        final WorldRegistry registry = new WorldRegistry();
        final MinecraftServer server;
        final ServerPlayer player;
        final ProgressTools tools;

        Fixture() throws Exception {
            this(new ServerWork(System::nanoTime));
        }

        Fixture(ServerWork work) throws Exception {
            this.work = work;
            var field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            field.setAccessible(true);
            var unsafe = field.get(null);
            var allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
            server = (MinecraftServer) allocate.invoke(unsafe, IntegratedServer.class);
            player = (ServerPlayer) allocate.invoke(unsafe, ServerPlayer.class);
            var running = MinecraftServer.class.getDeclaredField("running");
            running.setAccessible(true);
            running.setBoolean(server, true);
            tools = new ProgressTools(() -> server, ignored -> player, registry, () -> SCOPE, Runnable::run, work, new ProgressNavigation(null));
        }

        Evidence.Result finish(CompletionStage<Evidence.Result> read) {
            for (var ticks = 0; !read.toCompletableFuture().isDone() && ticks < 1000; ticks++) work.tick();
            assertTrue(read.toCompletableFuture().isDone(), "read did not finish after 1000 ticks");
            return read.toCompletableFuture().join();
        }

        Evidence.Result call(String tool, String json) {
            return finish(tools.handle(new PackSageTools.ReadRequest(tool, Evidence.deserializeObject(json), SCOPE), null));
        }
    }

    private static final class Reader implements ProgressReader {
        final String adapter;
        ProgressSnapshot snapshot;
        Progress progress;
        Evidence.JsonObject facts;

        Reader(String adapter, ProgressSnapshot snapshot, Progress progress) {
            this.adapter = adapter;
            this.snapshot = snapshot;
            this.progress = progress;
        }

        @Override
        public String adapterId() { return adapter; }

        @Override
        public ServerWork.Slice<ProgressSnapshot> snapshot(ServerPlayer player) { return ServerReads.once(() -> snapshot); }

        @Override
        public Progress inspect(ServerPlayer player, String local) {
            return progress != null && progress.handle().id().local().equals(local) ? progress : null;
        }

        @Override
        public Evidence.JsonObject progressFacts(ServerPlayer player, ProgressId id) { return facts; }
    }

    @Test
    void toolsAreUnavailableWithoutAnIntegratedServer() {
        var tools = new ProgressTools(() -> null, ignored -> null, new WorldRegistry(), () -> SCOPE, Runnable::run, new ServerWork(System::nanoTime), new ProgressNavigation(null));
        for (var call : Map.of("list_progress", "{}", "inspect_progress", "{\"id\":\"minecraft:story/root\"}", "list_statistics", "{}").entrySet()) {
            var failure = assertThrows(CompletionException.class, () -> tools.handle(new PackSageTools.ReadRequest(call.getKey(), Evidence.deserializeObject(call.getValue()), SCOPE), null).toCompletableFuture().join());
            assertEquals(Toolbox.Code.UNAVAILABLE, ((Toolbox.Failure) failure.getCause()).code());
        }
        assertFalse(tools.enabled());
        assertEquals(KnowledgeProvider.Availability.UNAVAILABLE, tools.status().availability());
        var failure = assertThrows(CompletionException.class, () -> tools.handle(new PackSageTools.ReadRequest("other", new Evidence.JsonObject(Map.of()), SCOPE), null).toCompletableFuture().join());
        assertEquals(Toolbox.Code.NOT_HANDLED, ((Toolbox.Failure) failure.getCause()).code());
    }

    @Test
    void listProgressCombinesAdaptersTreesFirstWithLinksExceptHidden() throws Exception {
        var fixture = new Fixture();
        fixture.registry.register(new Reader("minecraft", new ProgressSnapshot(List.of(new ProgressTree(ROOT, "Story", 1, 2)), List.of(new ProgressEntry(STONE, "Mine stone"), new ProgressEntry(HIDDEN, null)), null), null));
        var quest = new ProgressHandle(new ProgressId("quests", "first"), new ProgressId("quests", "chapter"), "First quest", ProgressStatus.AVAILABLE);
        fixture.registry.register(new Reader("quests", new ProgressSnapshot(List.of(new ProgressTree(quest.tree(), "Chapter", 0, 1)), List.of(new ProgressEntry(quest, "Begin")), null), null));

        var result = fixture.call("list_progress", "{}");

        assertEquals(Evidence.Coverage.COMPLETE, result.coverage());
        assertEquals(List.of("tree|minecraft/minecraft:story/root", "tree|quests/chapter", "progress|minecraft/minecraft:story/mine_stone", "progress|minecraft/minecraft:story/hidden", "progress|quests/first"), result.records().stream().map(record -> record.source().sourceHandle()).toList());
        assertEquals("advancement|minecraft:story/root|minecraft:story/root", result.records().get(0).targets().getFirst().targetId());
        assertTrue(result.records().get(1).targets().isEmpty());
        assertEquals("advancement|minecraft:story/root|minecraft:story/mine_stone", result.records().get(2).targets().getFirst().targetId());
        assertTrue(result.records().get(3).targets().isEmpty());
        assertEquals(new Evidence.JsonNull(), result.records().get(3).payload().values().get("title"));
        assertFalse(result.records().get(3).payload().values().containsKey("description"));
        assertTrue(result.records().get(4).targets().isEmpty());
        for (var record : result.records()) {
            assertEquals("progress", record.source().providerId());
            assertEquals(Evidence.Authority.SERVER_READ, record.source().authority());
        }
    }

    @Test
    void listProgressCursorContinuesAndRejectsAChangedFilter() throws Exception {
        var fixture = new Fixture();
        var entries = new ArrayList<ProgressEntry>();
        for (var i = 0; i < 70; i++) entries.add(new ProgressEntry(new ProgressHandle(new ProgressId("minecraft", "minecraft:entry_" + i), ROOT, "Entry " + i, ProgressStatus.AVAILABLE), "Read"));
        fixture.registry.register(new Reader("minecraft", new ProgressSnapshot(List.of(new ProgressTree(ROOT, "Story", 0, 70)), entries, null), null));

        var first = fixture.call("list_progress", "{}");
        assertEquals(64, first.records().size());
        assertEquals(Evidence.Outcome.PARTIAL, first.outcome());
        assertTrue(first.nextCursor().startsWith("64."));
        var second = fixture.call("list_progress", "{\"cursor\":\"" + first.nextCursor() + "\",\"text\":null}");
        assertEquals(7, second.records().size());
        assertNull(second.nextCursor());
        assertEquals("Entry 63", second.records().getFirst().title());
        var crossTool = assertThrows(CompletionException.class, () -> fixture.call("list_statistics", "{\"cursor\":\"" + first.nextCursor() + "\"}"));
        assertEquals(Toolbox.Code.INVALID_CURSOR, ((Toolbox.Failure) crossTool.getCause()).code());
        for (var cursor : List.of(first.nextCursor(), "broken", "-1.0", "2147483648.0", "1.0.extra")) {
            var failure = assertThrows(CompletionException.class, () -> fixture.call("list_progress", "{\"text\":\"changed\",\"cursor\":\"" + cursor + "\"}"));
            assertEquals(Toolbox.Code.INVALID_CURSOR, ((Toolbox.Failure) failure.getCause()).code());
        }
    }

    @Test
    void snapshotNotesKeepOrphanEntriesAndPartialCoverage() throws Exception {
        var fixture = new Fixture();
        fixture.registry.register(new Reader("minecraft", new ProgressSnapshot(List.of(), List.of(new ProgressEntry(STONE, "Mine stone")), "advancements were reloaded during the read; repeat it"), null));
        var result = fixture.call("list_progress", "{}");
        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        assertTrue(result.detail().contains("reloaded"));
        assertEquals(1, result.records().size());
        assertEquals(Evidence.Coverage.PARTIAL, result.records().getFirst().source().coverage());
    }

    @Test
    void inspectProgressMergesOtherAdaptersFactsAndRejectsUnknownIds() throws Exception {
        var fixture = new Fixture();
        var done = Instant.parse("2026-10-25T01:02:03Z");
        fixture.registry.register(new Reader("minecraft", new ProgressSnapshot(List.of(), List.of(), null), new Progress(STONE, "Mine stone", List.of(new ProgressCriterion("stone", "Stone", true, done)), List.of(STONE), List.of(HIDDEN, STONE), List.of("10 experience"), done, Map.of("minecraft", new Section(Evidence.deserializeObject("{\"own\":true}"), Evidence.Coverage.COMPLETE, null)))));
        var other = new Reader("quests", new ProgressSnapshot(List.of(), List.of(), null), null);
        other.facts = Evidence.deserializeObject("{\"chapter\":\"Start\"}");
        fixture.registry.register(other);

        for (var id : List.of("minecraft/minecraft:story/mine_stone", "minecraft:story/mine_stone")) {
            var result = fixture.call("inspect_progress", "{\"id\":\"" + id + "\"}");
            var payload = result.records().getFirst().payload().values();
            assertEquals(Evidence.deserializeObject("{\"minecraft\":{\"coverage\":\"COMPLETE\",\"facts\":{\"own\":true}},\"quests\":{\"coverage\":\"COMPLETE\",\"facts\":{\"chapter\":\"Start\"}}}"), payload.get("sections"));
            assertEquals(new Evidence.JsonString("2026-10-25T01:02:03Z"), payload.get("doneAt"));
            assertEquals(Evidence.deserializeObject("{\"id\":\"stone\",\"label\":\"Stone\",\"done\":true,\"doneAt\":\"2026-10-25T01:02:03Z\"}"), ((Evidence.JsonArray) payload.get("criteria")).values().getFirst());
            var hidden = (Evidence.JsonObject) ((Evidence.JsonArray) payload.get("unlocks")).values().getFirst();
            assertEquals(new Evidence.JsonNull(), hidden.values().get("title"));
            for (var field : List.of("requires", "unlocks")) {
                for (var handle : ((Evidence.JsonArray) payload.get(field)).values()) {
                    assertFalse(((Evidence.JsonObject) handle).values().containsKey("description"));
                }
            }
        }
        var failure = assertThrows(CompletionException.class, () -> fixture.call("inspect_progress", "{\"id\":\"missing\"}"));
        assertEquals(Toolbox.Code.INVALID_ARGUMENTS, ((Toolbox.Failure) failure.getCause()).code());
        assertTrue(failure.getCause().getMessage().contains("no progress entry has id missing; find ids with list_progress"));
    }

    @Test
    void hiddenInspectionDoesNotMergeFactsOrExposeDetails() throws Exception {
        var fixture = new Fixture();
        fixture.registry.register(new Reader("minecraft", new ProgressSnapshot(List.of(), List.of(), null), Progress.hidden(HIDDEN)));
        var other = new Reader("quests", new ProgressSnapshot(List.of(), List.of(), null), null);
        other.facts = Evidence.deserializeObject("{\"secret\":\"Spoiler\"}");
        fixture.registry.register(other);
        var record = fixture.call("inspect_progress", "{\"id\":\"minecraft/minecraft:story/hidden\"}").records().getFirst();
        assertEquals(java.util.Set.of("id", "adapter", "tree", "title", "status"), record.payload().values().keySet());
        assertTrue(record.targets().isEmpty());
    }

    @Test
    void listStatisticsKeepsOtherAdaptersWhenOneFails() throws Exception {
        var fixture = new Fixture();
        fixture.registry.register(new StatisticReader() {
            @Override
            public String adapterId() { return "broken"; }
            @Override
            public List<String> categories() { return List.of("mined"); }
            @Override
            public ServerWork.Slice<List<Statistic>> statistics(ServerPlayer player, List<String> categories, KindFilter subjects) {
                return ServerReads.once(() -> { throw new IllegalStateException("counter cannot be reflected"); });
            }
        });
        fixture.registry.register(new StatisticReader() {
            @Override
            public String adapterId() { return "minecraft"; }
            @Override
            public List<String> categories() { return List.of("mined"); }
            @Override
            public ServerWork.Slice<List<Statistic>> statistics(ServerPlayer player, List<String> categories, KindFilter subjects) {
                return ServerReads.once(() -> List.of(new Statistic("minecraft", "mined", "minecraft:diamond_ore", "Times Mined: Diamond Ore", 12, StatisticUnit.COUNT, 12)));
            }
        });
        var result = fixture.call("list_statistics", "{}");
        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        assertTrue(result.detail().contains("broken"));
        assertTrue(result.detail().contains("counter cannot be reflected"));
        assertEquals("Times Mined: Diamond Ore = 12", result.records().getFirst().title());
        assertEquals(Evidence.deserializeObject("{\"adapter\":\"minecraft\",\"category\":\"mined\",\"subject\":\"minecraft:diamond_ore\",\"label\":\"Times Mined: Diamond Ore\",\"value\":12.0,\"unit\":\"count\",\"raw\":12}"), result.records().getFirst().payload());
        assertTrue(result.records().getFirst().targets().isEmpty());
    }

    @Test
    void statisticAndProgressHandlesRereadThroughReadEvidence() throws Exception {
        var fixture = new Fixture();
        var reader = new Reader("minecraft", new ProgressSnapshot(List.of(), List.of(new ProgressEntry(STONE, "Mine stone")), null), new Progress(STONE, "Mine stone", List.of(), List.of(), List.of(), List.of(), null, Map.of()));
        fixture.registry.register(reader);
        fixture.registry.register(new StatisticReader() {
            @Override
            public String adapterId() { return "minecraft"; }
            @Override
            public List<String> categories() { return List.of("mined"); }
            @Override
            public ServerWork.Slice<List<Statistic>> statistics(ServerPlayer player, List<String> categories, KindFilter subjects) {
                assertEquals(List.of("mined"), categories);
                assertEquals(List.of("minecraft:diamond_ore"), subjects.ids());
                return ServerReads.once(() -> List.of(new Statistic("minecraft", "mined", "minecraft:diamond_ore", "Times Mined: Diamond Ore", 0, StatisticUnit.COUNT, 0)));
            }
        });
        for (var handle : List.of("progress|minecraft/minecraft:story/mine_stone", "stat|minecraft|mined|minecraft:diamond_ore")) {
            var context = new Toolbox.Context(() -> SCOPE, Map.of("observed", new Toolbox.Reference("progress", handle, Evidence.RecordKind.OTHER, SCOPE)), new AdapterRegistry(), new Toolbox.DocumentBudget(), null);
            var result = fixture.finish(fixture.tools.readEvidence(new PackSageTools.ReadRequest("read_evidence", Evidence.deserializeObject("{\"handles\":[\"observed\"]}"), SCOPE), context));
            assertEquals(handle, result.records().getFirst().source().sourceHandle());
            assertEquals(Evidence.Coverage.COMPLETE, result.coverage());
            var query = fixture.finish(fixture.tools.query(new Evidence.Query(Evidence.Operation.GET, new Evidence.JsonObject(Map.of("id", new Evidence.JsonString(handle))), 1, null, null), SCOPE));
            assertEquals(handle, query.records().getFirst().source().sourceHandle());
        }
        var tree = fixture.finish(fixture.tools.query(new Evidence.Query(Evidence.Operation.GET, Evidence.deserializeObject("{\"id\":\"tree|minecraft/minecraft:story/root\"}"), 1, null, null), SCOPE));
        assertEquals(Evidence.Outcome.ERROR, tree.outcome());
        assertTrue(tree.detail().contains("INVALID_REFERENCE: repeat list_progress"));
    }

    @Test
    void serverReadsMapsFailuresAndContinuesOnWorker() throws Exception {
        var work = new ServerWork(System::nanoTime);
        var queued = new ArrayList<Runnable>();
        var mapped = ServerReads.run(work, queued::add, () -> true, ServerReads.once(() -> "read")).thenApply(value -> value + " on worker");
        work.tick();
        assertFalse(mapped.toCompletableFuture().isDone());
        queued.removeFirst().run();
        assertEquals("read on worker", mapped.toCompletableFuture().join());
        for (var failure : List.of(new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE, "gone"), new IllegalArgumentException("bad"), new IllegalStateException("broken"), new CompletionException(new java.util.concurrent.TimeoutException()))) {
            var read = ServerReads.run(work, Runnable::run, () -> true, ServerReads.once(() -> { throw failure; }));
            work.tick();
            var caught = assertThrows(CompletionException.class, () -> read.toCompletableFuture().join());
            assertEquals(failure instanceof Toolbox.Failure ? Toolbox.Code.INVALID_REFERENCE : failure instanceof IllegalArgumentException ? Toolbox.Code.INVALID_ARGUMENTS : failure instanceof CompletionException ? Toolbox.Code.UNAVAILABLE : Toolbox.Code.EXTRACTION_FAILED, ((Toolbox.Failure) caught.getCause()).code());
            if (failure instanceof Toolbox.Failure) assertSame(failure, caught.getCause());
        }
    }

    @Test
    void missingHostFailsStatisticsInsteadOfClaimingAPartialRead() throws Exception {
        var fixture = new Fixture();
        fixture.registry.register(new StatisticReader() {
            @Override
            public String adapterId() { return "minecraft"; }
            @Override
            public List<String> categories() { return List.of("mined"); }
            @Override
            public ServerWork.Slice<List<Statistic>> statistics(ServerPlayer player, List<String> categories, KindFilter subjects) {
                throw new AssertionError("no adapter read should start without the host");
            }
        });
        var tools = new ProgressTools(() -> fixture.server, ignored -> { throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "the singleplayer host is not in the world"); },
                fixture.registry, () -> SCOPE, Runnable::run, fixture.work, new ProgressNavigation(null));
        var read = tools.handle(new PackSageTools.ReadRequest("list_statistics", new Evidence.JsonObject(Map.of()), SCOPE), null);
        var failure = assertThrows(CompletionException.class, () -> fixture.finish(read));
        assertEquals(Toolbox.Code.UNAVAILABLE, ((Toolbox.Failure) failure.getCause()).code());
    }

    @Test
    void statisticRereadPreservesAdapterFailuresAndDistinguishesMissingRows() throws Exception {
        var fixture = new Fixture();
        fixture.registry.register(new StatisticReader() {
            @Override
            public String adapterId() { return "broken"; }
            @Override
            public List<String> categories() { return List.of("mined"); }
            @Override
            public ServerWork.Slice<List<Statistic>> statistics(ServerPlayer player, List<String> categories, KindFilter subjects) {
                throw new IllegalStateException("counter cannot be reflected");
            }
        });
        for (var adapter : List.of("broken", "missing")) {
            var handle = "stat|" + adapter + "|mined|minecraft:diamond_ore";
            var context = new Toolbox.Context(() -> SCOPE, Map.of("observed", new Toolbox.Reference("progress", handle, Evidence.RecordKind.OTHER, SCOPE)), new AdapterRegistry(), new Toolbox.DocumentBudget(), null);
            var failure = assertThrows(CompletionException.class, () -> fixture.finish(fixture.tools.readEvidence(new PackSageTools.ReadRequest("read_evidence", Evidence.deserializeObject("{\"handles\":[\"observed\"]}"), SCOPE), context)));
            assertEquals(adapter.equals("broken") ? Toolbox.Code.EXTRACTION_FAILED : Toolbox.Code.INVALID_REFERENCE, ((Toolbox.Failure) failure.getCause()).code());
            if (adapter.equals("broken")) assertTrue(failure.getCause().getMessage().contains("broken: counter cannot be reflected"));
        }
    }

    @Test
    void compositeSlicesKeepPartialRowsWhenTheirInnerSliceStopsEarly() throws Exception {
        for (var tool : List.of("list_progress", "list_statistics")) {
            var clock = new java.util.concurrent.atomic.AtomicLong();
            var fixture = new Fixture(new ServerWork(clock::get));
            class BoundedReader implements ProgressReader, StatisticReader {
                @Override
                public String adapterId() { return "minecraft"; }
                @Override
                public List<String> categories() { return List.of("mined"); }
                @Override
                public Progress inspect(ServerPlayer player, String local) { return null; }
                @Override
                public ServerWork.Slice<ProgressSnapshot> snapshot(ServerPlayer player) {
                    return new ServerWork.Slice<>() {
                        boolean stepped;
                        boolean stopped;
                        @Override
                        public boolean step() {
                            if (stepped) throw new IllegalStateException("slice was stepped after its budget");
                            stepped = true;
                            clock.addAndGet(ServerWork.CALL_BUDGET_NANOS);
                            return true;
                        }
                        @Override
                        public boolean stopEarly() { stopped = true; return true; }
                        @Override
                        public ProgressSnapshot result() {
                            assertTrue(stopped);
                            return new ProgressSnapshot(List.of(), List.of(new ProgressEntry(STONE, "Mine stone")), "snapshot work budget reached");
                        }
                    };
                }
                @Override
                public ServerWork.Slice<List<Statistic>> statistics(ServerPlayer player, List<String> categories, KindFilter subjects) {
                    return new ServerWork.Slice<>() {
                        boolean stepped;
                        boolean stopped;
                        @Override
                        public boolean step() {
                            if (stepped) throw new IllegalStateException("slice was stepped after its budget");
                            stepped = true;
                            clock.addAndGet(ServerWork.CALL_BUDGET_NANOS);
                            return true;
                        }
                        @Override
                        public boolean stopEarly() { stopped = true; return true; }
                        @Override
                        public List<Statistic> result() {
                            assertTrue(stopped);
                            return List.of(new Statistic("minecraft", "mined", "minecraft:diamond_ore", "Times Mined: Diamond Ore", 12, StatisticUnit.COUNT, 12));
                        }
                    };
                }
            }
            fixture.registry.register(new BoundedReader());
            var result = fixture.call(tool, "{}");
            assertEquals(1, result.records().size());
            assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
            assertTrue(result.detail().contains("budget"));
        }
    }

    @Test
    void workerRecordBuildingFailuresAreTypedExtractionFailures() throws Exception {
        var fixture = new Fixture();
        fixture.registry.register(new Reader("minecraft", new ProgressSnapshot(List.of(), List.of(new ProgressEntry(new ProgressHandle(STONE.id(), null, STONE.title(), STONE.status()), "Mine stone")), null),
                new Progress(STONE, "Mine stone", List.of(new ProgressCriterion("stone", null, true, null)), List.of(), List.of(), List.of(), null, Map.of())));
        fixture.registry.register(new StatisticReader() {
            @Override
            public String adapterId() { return "minecraft"; }
            @Override
            public List<String> categories() { return List.of("mined"); }
            @Override
            public ServerWork.Slice<List<Statistic>> statistics(ServerPlayer player, List<String> categories, KindFilter subjects) {
                return ServerReads.once(() -> List.of(new Statistic("minecraft", "mined", "minecraft:diamond_ore", "Times Mined: Diamond Ore", Double.NaN, StatisticUnit.COUNT, 0)));
            }
        });
        for (var request : Map.of("list_progress", "{}", "inspect_progress", "{\"id\":\"minecraft/minecraft:story/mine_stone\"}", "list_statistics", "{}").entrySet()) {
            var failure = assertThrows(CompletionException.class, () -> fixture.call(request.getKey(), request.getValue()));
            assertTrue(failure.getCause() instanceof Toolbox.Failure, request.getKey() + ": " + failure.getCause());
            assertEquals(Toolbox.Code.EXTRACTION_FAILED, ((Toolbox.Failure) failure.getCause()).code());
        }
    }
}
