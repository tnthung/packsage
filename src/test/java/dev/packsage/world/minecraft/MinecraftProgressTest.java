package dev.packsage.world.minecraft;

import dev.packsage.knowledge.AdapterRegistry;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.model.PackSageTools;
import dev.packsage.world.Progress;
import dev.packsage.world.ProgressCriterion;
import dev.packsage.world.ProgressEntry;
import dev.packsage.world.ProgressHandle;
import dev.packsage.world.ProgressId;
import dev.packsage.world.ProgressReader;
import dev.packsage.world.ProgressSnapshot;
import dev.packsage.world.ProgressStatus;
import dev.packsage.world.ProgressTools;
import dev.packsage.world.ProgressTree;
import dev.packsage.world.ServerWork;
import dev.packsage.world.WorldRegistry;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.advancements.critereon.ImpossibleTrigger;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.advancements.AdvancementVisibilityEvaluator;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class MinecraftProgressTest {
    private static final String RELOADED = "advancements were reloaded during the read; repeat it";

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void vanillaTabVisibilityFieldIsPublicAndAvailable() throws NoSuchFieldException {
        assertEquals(Set.class, PlayerAdvancements.class.getField("visible").getType());
    }

    @Test
    void statusCoversDoneInProgressAvailableLockedAndHidden() {
        assertEquals(ProgressStatus.DONE, MinecraftProgress.status(true, true, true, false));
        assertEquals(ProgressStatus.HIDDEN, MinecraftProgress.status(false, true, true, true));
        assertEquals(ProgressStatus.IN_PROGRESS, MinecraftProgress.status(false, true, false, false));
        assertEquals(ProgressStatus.AVAILABLE, MinecraftProgress.status(false, false, false, null));
        assertEquals(ProgressStatus.AVAILABLE, MinecraftProgress.status(false, false, false, true));
        assertEquals(ProgressStatus.LOCKED, MinecraftProgress.status(false, false, false, false));
    }

    @Test
    void snapshotListsDisplayedTreesAndEntriesAndSkipsDisplaylessAdvancements() {
        var world = new World();
        var other = world.add(displayed("test:b/root", null, false, "c"));
        for (var i = 0; i < 143; i++) {
            world.add(displayed("test:b/child_" + i, other, false, "c"));
        }
        var recipes = world.add(displayless("test:recipes/root", null));
        world.add(displayless("test:recipes/torch", recipes));
        var root = world.add(displayed("test:a/root", null, false, "c"), "c");
        world.add(displayed("test:a/second", root, false, "c"));
        var middle = world.add(displayless("test:a/recipe", root));
        world.add(displayed("test:a/recipe_child", middle, false, "c"));
        world.add(displayed("test:a/hidden", root, true, "c"));
        var first = world.add(displayed("test:a/first", root, false, "c"), "c");
        world.add(displayed("test:a/first_child", first, false, "c"));

        var steps = new int[1];
        var snapshot = run(new MinecraftProgress().snapshot(world.source()), steps);

        assertNull(snapshot.note());
        assertEquals(List.of(new ProgressTree(id("test:a/root"), "test:a/root title", 2, 6, true),
                new ProgressTree(id("test:b/root"), "test:b/root title", 0, 144, false)), snapshot.trees());
        var locals = snapshot.entries().stream().map(entry -> entry.handle().id().local()).toList();
        var hiddenId = snapshot.entries().get(5).handle().id().local();
        assertTrue(hiddenId.startsWith("hidden:"));
        var expected = new ArrayList<>(List.of("test:a/root", "test:a/first", "test:a/first_child", "test:a/recipe_child",
                "test:a/second", hiddenId, "test:b/root"));
        IntStream.range(0, 143).mapToObj(i -> "test:b/child_" + i).sorted().forEach(expected::add);
        assertEquals(expected, locals);
        assertTrue(steps[0] >= 3, "steps: " + steps[0]);
        var firstEntry = snapshot.entries().get(locals.indexOf("test:a/first"));
        assertEquals(new ProgressEntry(new ProgressHandle(id("test:a/first"), id("test:a/root"), "test:a/first title", ProgressStatus.DONE, true),
                "test:a/first description"), firstEntry);
        assertEquals(ProgressStatus.AVAILABLE, snapshot.entries().get(locals.indexOf("test:a/first_child")).handle().status());
        assertEquals(ProgressStatus.AVAILABLE, snapshot.entries().get(locals.indexOf("test:a/recipe_child")).handle().status());
        assertEquals(ProgressStatus.AVAILABLE, snapshot.entries().get(6).handle().status());
        assertEquals(ProgressStatus.LOCKED, snapshot.entries().get(7).handle().status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"siblings", "roots"})
    void veryWideTreesCopyReferencesFirstThenBoundPreparationAndVisitsPerStep(String shape) {
        var world = new World();
        var root = displayed("test:root", null, false, "c");
        var holders = new ArrayList<AdvancementHolder>();
        if (shape.equals("siblings")) holders.add(root);
        for (var i = 0; i < 20_000; i++) holders.add(displayed("test:hidden_" + i, shape.equals("siblings") ? root : null, true, "c"));
        world.tree.addAll(holders);
        for (var holder : holders) {
            var progress = new AdvancementProgress();
            progress.update(holder.value().requirements());
            world.progress.put(holder, progress);
        }
        var reader = new MinecraftProgress();
        var slice = reader.snapshot(world.source());
        assertTrue(slice.step(), "the first step copies node references without preparing progress");
        assertEquals(0, world.lookups);
        assertTrue(slice.result().entries().isEmpty());
        var steps = 1;
        boolean more;
        do {
            var before = world.lookups;
            more = slice.step();
            assertTrue(world.lookups - before <= MinecraftProgress.STEP_NODES,
                    "step " + steps + " prepared " + (world.lookups - before) + " nodes");
            assertTrue(++steps < 5000);
        } while (more);
        var snapshot = slice.result();
        assertNull(snapshot.note());
        assertEquals(holders.size(), snapshot.entries().size());
        assertEquals(shape.equals("siblings") ? 1 : 20_000, snapshot.trees().size());
        var hidden = snapshot.entries().stream().filter(entry -> entry.handle().status() == ProgressStatus.HIDDEN)
                .map(entry -> entry.handle().id().local()).toList();
        assertEquals(20_000, new HashSet<>(hidden).size());
        assertEquals(hidden.stream().sorted().toList(), hidden);
        assertTrue(steps > holders.size() / MinecraftProgress.STEP_NODES);
    }

    @Test
    void tabVisibilityIsIndependentOfAvailableStatusAndSharedByTheWholeTree() {
        var world = new World();
        var root = world.add(displayed("test:adventure/root", null, false, "c"));
        var first = world.add(displayed("test:adventure/first", root, false, "c"));
        var second = world.add(displayed("test:adventure/second", first, false, "c"));
        var deep = world.add(displayed("test:adventure/deep", second, false, "c"));
        var reader = new MinecraftProgress();

        var unavailable = run(reader.snapshot(world.source()), new int[1]);

        assertEquals(ProgressStatus.AVAILABLE, entry(unavailable, "test:adventure/root").handle().status());
        assertFalse(unavailable.trees().getFirst().tabVisible());
        for (var entry : unavailable.entries()) assertFalse(entry.handle().tabVisible());
        assertFalse(reader.inspect(world.source(), "test:adventure/root").handle().tabVisible());

        world.progress.get(root).grantProgress("c");
        var visible = run(reader.snapshot(world.source()), new int[1]);

        assertTrue(visible.trees().getFirst().tabVisible());
        for (var entry : visible.entries()) assertTrue(entry.handle().tabVisible());
        assertEquals(ProgressStatus.AVAILABLE, entry(visible, "test:adventure/first").handle().status());
        // This entry itself is beyond vanilla's visibility depth, but its root tab still opens.
        assertTrue(reader.inspect(world.source(), deep.id().toString()).handle().tabVisible());
        assertTrue(reader.inspect(world.source(), "test:adventure/first").requires().getFirst().tabVisible());
        assertTrue(reader.inspect(world.source(), "test:adventure/first").unlocks().getFirst().tabVisible());
    }

    @Test
    void hiddenUnearnedEntriesCarryNoTitleDescriptionOrCriteria() {
        var world = new World();
        var root = world.add(displayed("test:a/root", null, false, "c"), "c");
        var hidden = world.add(displayed("test:a/secret", root, true, "x", "y"), "x");
        world.add(displayed("test:a/after_secret", hidden, false, "c"));
        var reader = new MinecraftProgress();

        var snapshot = run(reader.snapshot(world.source()), new int[1]);
        var entry = snapshot.entries().stream().filter(value -> value.handle().status() == ProgressStatus.HIDDEN).findFirst().orElseThrow();
        var handle = entry.handle();
        assertTrue(handle.id().local().startsWith("hidden:"));
        assertFalse(new com.google.gson.Gson().toJson(snapshot).contains("test:a/secret"));
        assertEquals(new ProgressEntry(handle, null), entry);
        assertEquals(Progress.hidden(handle), reader.inspect(world.source(), handle.id().local()));
        assertNull(reader.inspect(world.source(), "test:a/secret"));
        assertNull(reader.inspect(world.source(), "test:a/nonexistent"));
        assertEquals(List.of(handle), reader.inspect(world.source(), "test:a/root").unlocks());
        assertEquals(List.of(handle), reader.inspect(world.source(), "test:a/after_secret").requires());
        world.generation = new Object();
        assertEquals(handle, run(reader.snapshot(world.source()), new int[1]).entries().stream()
                .filter(value -> value.handle().status() == ProgressStatus.HIDDEN).findFirst().orElseThrow().handle());

        world.progress.get(hidden).grantProgress("y");
        var done = entry(run(reader.snapshot(world.source()), new int[1]), "test:a/secret");
        assertEquals(ProgressStatus.DONE, done.handle().status());
        assertEquals("test:a/secret title", done.handle().title());
        assertEquals("test:a/secret description", done.description());
        assertEquals(2, reader.inspect(world.source(), "test:a/secret").criteria().size());
        assertEquals(done.handle(), reader.inspect(world.source(), handle.id().local()).handle());
    }

    @Test
    void unearnedHiddenSiblingsFollowVisibleSiblingsInListingsAndUnlocks() {
        var world = new World();
        var root = world.add(displayed("test:root", null, false, "c"), "c");
        var hidden = world.add(displayed("test:a_secret", root, true, "c"));
        world.add(displayed("test:b_earned", root, true, "c"), "c");
        world.add(displayed("test:z_visible", root, false, "c"));
        world.add(displayed("test:c_secret", root, true, "c"));
        var reader = new MinecraftProgress();

        var snapshot = run(reader.snapshot(world.source()), new int[1]);
        var ids = snapshot.entries().stream().map(entry -> entry.handle().id().local()).toList();
        assertEquals(List.of("test:root", "test:b_earned", "test:z_visible"), ids.subList(0, 3));
        assertTrue(ids.get(3).startsWith("hidden:"));
        assertTrue(ids.get(4).startsWith("hidden:"));
        assertEquals(ids.subList(3, 5).stream().sorted().toList(), ids.subList(3, 5));
        assertEquals(ids.subList(1, 5), reader.inspect(world.source(), "test:root").unlocks().stream().map(handle -> handle.id().local()).toList());
        assertEquals(ids, run(reader.snapshot(world.source()), new int[1]).entries().stream().map(entry -> entry.handle().id().local()).toList());

        world.progress.get(hidden).grantProgress("c");
        assertEquals(List.of("test:root", "test:a_secret", "test:b_earned", "test:z_visible"),
                run(reader.snapshot(world.source()), new int[1]).entries().stream().map(entry -> entry.handle().id().local()).limit(4).toList());
    }

    @Test
    void realHiddenIdsNeverReachSerializedProgressToolResults() throws Exception {
        var world = new World();
        var root = world.add(displayed("test:root", null, false, "c"), "c");
        world.add(displayed("test:child", world.add(displayed("minecraft:nether/all_effects", root, true, "c")), false, "c"));
        for (var i = 0; i < 70; i++) world.add(displayed("test:entry_" + i, root, false, "c"));
        var reader = new MinecraftProgress();
        var registry = new WorldRegistry();
        registry.register(new ProgressReader() {
            @Override
            public String adapterId() { return reader.adapterId(); }

            @Override
            public ServerWork.Slice<ProgressSnapshot> snapshot(ServerPlayer player) { return reader.snapshot(world.source()); }

            @Override
            public Progress inspect(ServerPlayer player, String local) { return reader.inspect(world.source(), local); }
        });
        var field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = field.get(null);
        var server = (MinecraftServer) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, IntegratedServer.class);
        var running = MinecraftServer.class.getDeclaredField("running");
        running.setAccessible(true);
        running.setBoolean(server, true);
        var work = new ServerWork(System::nanoTime);
        var scope = new Evidence.Scope("world", "resources", "team", "runtime", 1);
        var constructor = ProgressTools.class.getDeclaredConstructor(Supplier.class, Function.class, WorldRegistry.class, Supplier.class,
                Executor.class, ServerWork.class, Class.forName("dev.packsage.world.ProgressNavigation"));
        constructor.setAccessible(true);
        var tools = constructor.newInstance((Supplier<MinecraftServer>) () -> server, (Function<MinecraftServer, ServerPlayer>) ignored -> null,
                registry, (Supplier<Evidence.Scope>) () -> scope, (Executor) Runnable::run, work, null);
        var results = new ArrayList<Evidence.Result>();
        String cursor = null;
        do {
            var read = tools.handle(new PackSageTools.ReadRequest("list_progress", Evidence.deserializeObject(cursor == null ? "{}" : "{\"cursor\":\"" + cursor + "\"}"), scope), null);
            while (!read.toCompletableFuture().isDone()) work.tick();
            results.add(read.toCompletableFuture().join());
            cursor = results.getLast().nextCursor();
        } while (cursor != null);
        assertEquals(2, results.size());
        assertNotNull(results.getFirst().nextCursor());
        var record = results.stream().flatMap(result -> result.records().stream()).filter(value -> value.title().equals("Hidden progress entry")).findFirst().orElseThrow();
        for (var local : List.of(((Evidence.JsonString) record.payload().values().get("id")).value(), "test:root", "test:child")) {
            var read = tools.handle(new PackSageTools.ReadRequest("inspect_progress", Evidence.deserializeObject("{\"id\":\"" + local + "\"}"), scope), null);
            while (!read.toCompletableFuture().isDone()) work.tick();
            results.add(read.toCompletableFuture().join());
        }
        var context = new Toolbox.Context(() -> scope, Map.of("saved", new Toolbox.Reference("progress", record.source().sourceHandle(),
                Evidence.RecordKind.QUEST, scope)), new AdapterRegistry(), new Toolbox.DocumentBudget(), null);
        var reread = tools.readEvidence(new PackSageTools.ReadRequest("read_evidence", Evidence.deserializeObject("{\"handles\":[\"saved\"]}"), scope), context);
        while (!reread.toCompletableFuture().isDone()) work.tick();
        results.add(reread.toCompletableFuture().join());
        var json = new com.google.gson.GsonBuilder().serializeNulls()
                .registerTypeAdapter(Instant.class, (com.google.gson.JsonSerializer<Instant>) (value, type, serialization) -> new com.google.gson.JsonPrimitive(value.toString()))
                .create();
        for (var result : results) assertFalse(json.toJson(result).contains("all_effects"));

        for (var local : List.of("minecraft:nether/all_effects", "minecraft:nether/nonexistent")) {
            var read = tools.handle(new PackSageTools.ReadRequest("inspect_progress", Evidence.deserializeObject("{\"id\":\"" + local + "\"}"), scope), null);
            while (!read.toCompletableFuture().isDone()) work.tick();
            var failure = (Toolbox.Failure) assertThrows(CompletionException.class, () -> read.toCompletableFuture().join()).getCause();
            assertEquals(Toolbox.Code.INVALID_ARGUMENTS, failure.code());
            assertEquals("INVALID_ARGUMENTS: no progress entry has id " + local + "; find ids with list_progress", failure.getMessage());
        }
    }

    @Test
    void hiddenRootRedactsTreeIdentityTitleAndNavigationUntilEarned() {
        var world = new World();
        var root = world.add(displayed("spoiler:secret_root", null, true, "c"));
        world.add(displayed("test:child", root, false, "c"), "c");
        var reader = new MinecraftProgress();

        var snapshot = run(reader.snapshot(world.source()), new int[1]);

        assertFalse(new com.google.gson.Gson().toJson(snapshot).contains("spoiler"));
        assertFalse(new com.google.gson.Gson().toJson(snapshot).contains("secret_root"));
        assertEquals(1, snapshot.trees().getFirst().done());
        assertEquals(2, snapshot.trees().getFirst().total());
        assertEquals("Hidden progress tree", snapshot.trees().getFirst().title());
        assertEquals(snapshot.entries().getFirst().handle().id(), snapshot.trees().getFirst().id());
        for (var entry : snapshot.entries()) {
            assertEquals(snapshot.trees().getFirst().id(), entry.handle().tree());
            assertFalse(entry.handle().tabVisible());
        }
        var child = reader.inspect(world.source(), "test:child");
        assertEquals(snapshot.trees().getFirst().id(), child.handle().tree());
        assertEquals(List.of(snapshot.entries().getFirst().handle()), child.requires());

        world.progress.get(root).grantProgress("c");
        var earned = run(reader.snapshot(world.source()), new int[1]);
        assertEquals(id("spoiler:secret_root"), earned.trees().getFirst().id());
        assertEquals("spoiler:secret_root title", earned.trees().getFirst().title());
        assertTrue(earned.trees().getFirst().tabVisible());
    }

    @Test
    void hiddenIdsAreDistinctAndBelongToOneWorldSession() {
        var reader = new MinecraftProgress();
        var world = new World();
        var root = world.add(displayed("test:root", null, false, "c"));
        world.add(displayed("spoiler:first", root, true, "c"));
        world.add(displayed("spoiler:second", root, true, "c"));
        var snapshot = run(reader.snapshot(world.source()), new int[1]);
        var first = snapshot.entries().get(1).handle().id().local();
        assertFalse(first.equals(snapshot.entries().get(2).handle().id().local()));
        assertFalse(new com.google.gson.Gson().toJson(snapshot).contains("spoiler"));

        var other = new World();
        other.add(displayed("spoiler:first", other.add(displayed("test:root", null, false, "c")), true, "c"));
        assertFalse(first.equals(run(reader.snapshot(other.source()), new int[1]).entries().get(1).handle().id().local()));
        assertNull(reader.inspect(other.source(), first));
        assertNotNull(reader.inspect(world.source(), first));
    }

    @Test
    void inspectReportsCriteriaWithDatesRequirementsUnlocksAndRewards() {
        var world = new World();
        var root = world.add(displayed("test:adventure/root", null, false, "c"), "c");
        var adventuring = world.add(Advancement.Builder.advancement().parent(root)
                .display(display("Adventuring Time", "Discover every biome", AdvancementType.CHALLENGE, false))
                .rewards(AdvancementRewards.Builder.experience(100).addRecipe(ResourceLocation.withDefaultNamespace("torch")))
                .addCriterion("minecraft:plains", impossible()).addCriterion("minecraft:desert", impossible())
                .build(ResourceLocation.parse("test:adventure/adventuring_time")), "minecraft:plains");
        world.add(displayed("test:adventure/next", adventuring, false, "c"));
        var middle = world.add(displayless("test:adventure/recipe", adventuring));
        world.add(displayed("test:adventure/recipe_child", middle, false, "c"));
        var alternatives = world.add(Advancement.Builder.advancement().parent(root)
                .display(display("Either", "Any one of two", AdvancementType.GOAL, false))
                .addCriterion("a", impossible()).addCriterion("b", impossible()).addCriterion("c", impossible())
                .requirements(new AdvancementRequirements(List.of(List.of("a", "b"), List.of("c"))))
                .build(ResourceLocation.parse("test:adventure/either")), "b", "c");
        var reader = new MinecraftProgress();

        var progress = reader.inspect(world.source(), "test:adventure/adventuring_time");

        var tree = id("test:adventure/root");
        assertEquals(new ProgressHandle(id("test:adventure/adventuring_time"), tree, "Adventuring Time", ProgressStatus.IN_PROGRESS, true), progress.handle());
        assertEquals("Discover every biome", progress.description());
        var plains = world.progress.get(adventuring).getCriterion("minecraft:plains").getObtained();
        assertNotNull(plains);
        assertEquals(List.of(new ProgressCriterion("minecraft:plains", "label minecraft:plains", true, plains),
                new ProgressCriterion("minecraft:desert", "label minecraft:desert", false, null)), progress.criteria());
        assertEquals(List.of(new ProgressHandle(tree, tree, "test:adventure/root title", ProgressStatus.DONE, true)), progress.requires());
        assertEquals(List.of(new ProgressHandle(id("test:adventure/next"), tree, "test:adventure/next title", ProgressStatus.LOCKED, true),
                new ProgressHandle(id("test:adventure/recipe_child"), tree, "test:adventure/recipe_child title", ProgressStatus.LOCKED, true)),
                progress.unlocks());
        assertEquals(List.of(progress.handle()), reader.inspect(world.source(), "test:adventure/recipe_child").requires());
        assertEquals(List.of("experience: 100", "recipe: minecraft:torch"), progress.rewards());
        assertNull(progress.doneAt());
        var facts = progress.facts().get("minecraft");
        assertEquals(Evidence.Coverage.COMPLETE, facts.coverage());
        var values = facts.facts().values();
        assertEquals(new Evidence.JsonString("challenge"), values.get("frame"));
        assertEquals(1, ((Evidence.JsonNumber) values.get("criteriaDone")).value().intValue());
        assertEquals(2, ((Evidence.JsonNumber) values.get("criteriaTotal")).value().intValue());
        assertFalse(values.containsKey("requirements"));

        var either = reader.inspect(world.source(), "test:adventure/either");
        assertEquals(ProgressStatus.DONE, either.handle().status());
        var latest = world.progress.get(alternatives).getCriterion("b").getObtained();
        var c = world.progress.get(alternatives).getCriterion("c").getObtained();
        assertEquals(latest.isAfter(c) ? latest : c, either.doneAt());
        assertEquals(List.of("a", "b", "c"), either.criteria().stream().map(ProgressCriterion::id).toList());
        assertEquals(new Evidence.JsonArray(List.of(
                new Evidence.JsonArray(List.of(new Evidence.JsonString("a"), new Evidence.JsonString("b"))),
                new Evidence.JsonArray(List.of(new Evidence.JsonString("c"))))), either.facts().get("minecraft").facts().values().get("requirements"));
        assertEquals(2, ((Evidence.JsonNumber) either.facts().get("minecraft").facts().values().get("criteriaDone")).value().intValue());

        assertNull(reader.inspect(world.source(), "test:adventure/recipe"));
        assertNull(reader.inspect(world.source(), "test:adventure/missing"));
        assertNull(reader.inspect(world.source(), "not a valid id"));
    }

    @Test
    void readingNeverStartsProgressOrChangesCriteria() {
        var world = new World();
        var root = world.add(displayed("test:a/root", null, false, "c"), "c");
        var child = world.add(displayed("test:a/child", root, false, "x", "y"), "x");
        world.add(displayed("test:a/secret", child, true, "z"));
        world.add(displayless("test:a/recipe", root));
        var keys = new HashSet<>(world.progress.keySet());
        var before = criteria(world);
        var reader = new MinecraftProgress();

        run(reader.snapshot(world.source()), new int[1]);
        assertEquals(3, world.lookups);
        world.lookups = 0;
        reader.inspect(world.source(), "test:a/child");
        assertEquals(3, world.lookups);
        for (var local : List.of("test:a/root", "test:a/child", "test:a/secret", "test:a/recipe")) {
            reader.inspect(world.source(), local);
        }

        assertEquals(keys, world.progress.keySet());
        assertEquals(before, criteria(world));
    }

    @Test
    void reloadBetweenSlicesStopsThePartialSnapshotWithoutTouchingStaleHolders() {
        var world = new World();
        var root = world.add(displayed("test:a/root", null, false, "c"));
        for (var i = 0; i < 149; i++) {
            world.add(displayed("test:a/child_" + i, root, false, "c"));
        }
        var slice = new MinecraftProgress().snapshot(world.source());

        while (slice.result().entries().isEmpty()) assertTrue(slice.step());
        var lookups = world.lookups;
        assertTrue(lookups > 0);
        world.generation = new Object();
        while (slice.step()) {
            // Runs until the slice notices the reload.
        }

        assertEquals(lookups, world.lookups);
        var snapshot = slice.result();
        assertEquals(RELOADED, snapshot.note());
        assertFalse(snapshot.entries().isEmpty());
        assertTrue(snapshot.entries().size() <= 64);
        assertTrue(snapshot.trees().isEmpty());
    }

    private static ProgressSnapshot run(ServerWork.Slice<ProgressSnapshot> slice, int[] steps) {
        do {
            steps[0]++;
        } while (slice.step());
        return slice.result();
    }

    private static ProgressEntry entry(ProgressSnapshot snapshot, String local) {
        return snapshot.entries().stream().filter(entry -> entry.handle().id().local().equals(local)).findFirst().orElseThrow();
    }

    private static Map<String, Map<String, Optional<Instant>>> criteria(World world) {
        var states = new LinkedHashMap<String, Map<String, Optional<Instant>>>();
        world.progress.entrySet().stream().sorted(Comparator.comparing(entry -> entry.getKey().id().toString())).forEach(entry -> {
            var criteria = new LinkedHashMap<String, Optional<Instant>>();
            for (var name : entry.getKey().value().requirements().names()) {
                var criterion = entry.getValue().getCriterion(name);
                assertEquals(criterion.isDone(), criterion.getObtained() != null);
                criteria.put(name, Optional.ofNullable(criterion.getObtained()));
            }
            states.put(entry.getKey().id().toString(), criteria);
        });
        return states;
    }

    private static ProgressId id(String local) {
        return new ProgressId("minecraft", local);
    }

    private static AdvancementHolder displayed(String id, AdvancementHolder parent, boolean hidden, String... criteria) {
        var builder = Advancement.Builder.advancement().display(display(id + " title", id + " description", AdvancementType.TASK, hidden));
        if (parent != null) builder.parent(parent);
        for (var criterion : criteria) {
            builder.addCriterion(criterion, impossible());
        }
        return builder.build(ResourceLocation.parse(id));
    }

    private static AdvancementHolder displayless(String id, AdvancementHolder parent) {
        var builder = Advancement.Builder.recipeAdvancement().addCriterion("c", impossible());
        if (parent != null) builder.parent(parent);
        return builder.build(ResourceLocation.parse(id));
    }

    private static DisplayInfo display(String title, String description, AdvancementType type, boolean hidden) {
        return new DisplayInfo(new ItemStack(Items.STONE), Component.literal(title), Component.literal(description), Optional.empty(),
                type, true, true, hidden);
    }

    private static net.minecraft.advancements.Criterion<ImpossibleTrigger.TriggerInstance> impossible() {
        return CriteriaTriggers.IMPOSSIBLE.createCriterion(new ImpossibleTrigger.TriggerInstance());
    }

    /** A loaded advancement set with the player's progress; looking up a holder outside the map fails the test. */
    private static final class World {
        final AdvancementTree tree = new AdvancementTree();
        final Map<AdvancementHolder, AdvancementProgress> progress = new HashMap<>();
        Object generation = new Object();
        int lookups;

        AdvancementHolder add(AdvancementHolder holder, String... granted) {
            tree.addAll(List.of(holder));
            var entry = new AdvancementProgress();
            entry.update(holder.value().requirements());
            for (var criterion : granted) {
                entry.grantProgress(criterion);
            }
            progress.put(holder, entry);
            return holder;
        }

        MinecraftProgress.Source source() {
            var visible = new HashSet<AdvancementHolder>();
            for (var root : tree.roots()) {
                AdvancementVisibilityEvaluator.evaluateVisibility(root, node -> progress.get(node.holder()).isDone(), (node, shown) -> {
                    if (shown) visible.add(node.holder());
                });
            }
            return new MinecraftProgress.Source(this, () -> generation, () -> tree, holder -> {
                lookups++;
                var entry = progress.get(holder);
                if (entry == null) fail("progress looked up for an unknown holder " + holder.id());
                return entry;
            }, visible::contains, name -> "label " + name);
        }
    }
}
