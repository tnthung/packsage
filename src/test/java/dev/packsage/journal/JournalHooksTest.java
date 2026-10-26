package dev.packsage.journal;

import net.minecraft.SharedConstants;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.WritableLevelData;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JournalHooksTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @ParameterizedTest
    @ValueSource(strings = {"before", "during", "after"})
    void registrationAroundServerStartOpensOneJournalSession(String timing, @TempDir Path dir) throws Exception {
        class Server extends IntegratedServer {
            Path root;
            List<Runnable> tasks;

            Server() { super(null, null, null, null, null, null, null); }

            @Override public Path getWorldPath(LevelResource resource) { return root; }
            @Override public void execute(Runnable task) { tasks.add(task); }
            @Override public Iterable<ServerLevel> getAllLevels() { return List.of(); }
        }
        var field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = field.get(null);
        var server = (Server) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, Server.class);
        server.root = dir;
        server.tasks = new ArrayList<>();
        var serverThread = MinecraftServer.class.getDeclaredField("serverThread");
        serverThread.setAccessible(true);
        serverThread.set(server, Thread.currentThread());
        var running = MinecraftServer.class.getDeclaredField("running");
        running.setAccessible(true);
        running.set(server, true);
        var ready = MinecraftServer.class.getDeclaredField("isReady");
        ready.setAccessible(true);
        ready.set(server, timing.equals("after"));
        var level = (ServerLevel) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, ServerLevel.class);
        var levelData = net.minecraft.world.level.Level.class.getDeclaredField("levelData");
        levelData.setAccessible(true);
        levelData.set(level, Proxy.newProxyInstance(WritableLevelData.class.getClassLoader(), new Class<?>[]{WritableLevelData.class},
                (proxy, method, args) -> 400L));
        var levels = MinecraftServer.class.getDeclaredField("levels");
        levels.setAccessible(true);
        levels.set(server, Map.of(net.minecraft.world.level.Level.OVERWORLD, level));
        var integrated = new AtomicReference<MinecraftServer>(timing.equals("before") ? null : server);
        var bus = BusBuilder.builder().build();
        if (!timing.equals("before")) bus.post(new ServerStartedEvent(server));
        try (var worker = Executors.newSingleThreadExecutor()) {
            var journal = new Journal(worker);
            try {
                new JournalHooks(journal, integrated::get).register(bus);
                // Queued server work runs even when a late-joined world is paused and emits no ticks.
                server.tasks.forEach(Runnable::run);
                if (timing.equals("before")) {
                    integrated.set(server);
                    bus.post(new ServerStartedEvent(server));
                } else if (timing.equals("during")) {
                    // The event was missed, but the server has not published isReady yet.
                    bus.post(new ServerTickEvent.Post(() -> true, server));
                }
                assertTrue(journal.recording(), "journal must open for registration " + timing + " server start");
                journal.write(store -> null).toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertTrue(journal.ready());
                assertTrue(java.nio.file.Files.exists(dir.resolve("packsage/journal.db")));
                var stats = journal.stats();
                journal.record(JournalEvents.broken(new JournalEvents.Stamp(400, 100, "minecraft:overworld"),
                        "player:A", "Alex", "minecraft:sand", 1, 64, 2));
                bus.post(new ServerStartedEvent(server));
                bus.post(new ServerTickEvent.Post(() -> true, server));
                assertSame(stats, journal.stats(), "catch-up and start events must not reopen the same world");
                assertEquals(1, journal.stats().offered.sum());
                bus.post(new ServerStoppedEvent(server));
                assertFalse(journal.recording());
            } finally {
                journal.close().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyRegisteredHandlerContainsRuntimeExceptionsWithoutTimingNonServerWork() {
        record Listener(EventPriority priority, boolean receiveCanceled, Consumer<Event> action) {
        }
        var listeners = new HashMap<Class<?>, Listener>();
        var bus = (IEventBus) Proxy.newProxyInstance(IEventBus.class.getClassLoader(), new Class<?>[]{IEventBus.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("addListener")) {
                        listeners.put((Class<?>) args[2], new Listener((EventPriority) args[0], (boolean) args[1],
                                (Consumer<Event>) args[3]));
                    }
                    return null;
                });
        var journal = new Journal(Runnable::run);
        new JournalHooks(journal, () -> {
            throw new IllegalStateException("a failed integration lookup");
        }).register(bus);
        assertEquals(19, listeners.size());
        for (var listener : listeners.values()) {
            assertFalse(listener.receiveCanceled());
            assertDoesNotThrow(() -> listener.action().accept(null));
        }
        assertEquals(18, journal.stats().hookFailures());
        journal.stats().endTick();
        assertEquals(0, journal.stats().maxHookNanosPerTick.get());
        for (var type : List.of(BlockEvent.BreakEvent.class, BlockEvent.EntityPlaceEvent.class,
                net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Post.class,
                net.neoforged.neoforge.event.entity.item.ItemTossEvent.class,
                LivingDeathEvent.class, EntityTravelToDimensionEvent.class)) {
            assertEquals(EventPriority.LOWEST, listeners.get(type).priority());
        }
    }

    @Test
    void pickupAndTossHooksRecordActualCountsAndIgnoreZeroCanceledAndOffThreadEvents(@TempDir Path dir) throws Exception {
        class Server extends IntegratedServer {
            Thread serverThread;

            Server() { super(null, null, null, null, null, null, null); }

            @Override public boolean isSameThread() { return Thread.currentThread() == serverThread; }
        }
        class Level extends ServerLevel {
            MinecraftServer server;

            Level() { super(null, null, null, null, net.minecraft.world.level.Level.OVERWORLD, null, null, false, 0L,
                    List.of(), false, null); }

            @Override public MinecraftServer getServer() { return server; }
        }
        class Player extends ServerPlayer {
            Level level;
            MinecraftServer server;

            Player() { super(null, null, null, null); }

            @Override public net.minecraft.world.level.Level level() { return level; }
            @Override public MinecraftServer getServer() { return server; }
            @Override public BlockPos blockPosition() { return new BlockPos(10, 64, -3); }
            @Override public java.util.UUID getUUID() { return java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"); }
            @Override public String getScoreboardName() { return "Alex"; }
        }
        class Item extends net.minecraft.world.entity.item.ItemEntity {
            Level level;
            net.minecraft.world.item.ItemStack stack;

            Item() { super((net.minecraft.world.level.Level) null, 0, 0, 0, net.minecraft.world.item.ItemStack.EMPTY); }

            @Override public net.minecraft.world.level.Level level() { return level; }
            @Override public BlockPos blockPosition() { return new BlockPos(10, 64, -3); }
            @Override public net.minecraft.world.item.ItemStack getItem() { return stack; }
        }
        var unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = unsafeField.get(null);
        var server = (Server) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, Server.class);
        server.serverThread = Thread.currentThread();
        var level = (Level) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, Level.class);
        level.server = server;
        var dimension = net.minecraft.world.level.Level.class.getDeclaredField("dimension");
        dimension.setAccessible(true);
        dimension.set(level, net.minecraft.world.level.Level.OVERWORLD);
        var levelData = net.minecraft.world.level.Level.class.getDeclaredField("levelData");
        levelData.setAccessible(true);
        levelData.set(level, java.lang.reflect.Proxy.newProxyInstance(net.minecraft.world.level.storage.WritableLevelData.class.getClassLoader(),
                new Class<?>[]{net.minecraft.world.level.storage.WritableLevelData.class}, (proxy, method, args) -> 400L));
        var levels = MinecraftServer.class.getDeclaredField("levels");
        levels.setAccessible(true);
        levels.set(server, Map.of(net.minecraft.world.level.Level.OVERWORLD, level));
        var player = (Player) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, Player.class);
        player.level = level;
        player.server = server;
        var partial = (Item) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, Item.class);
        partial.level = level;
        partial.stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 3);
        var full = (Item) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, Item.class);
        full.level = level;
        full.stack = net.minecraft.world.item.ItemStack.EMPTY;
        var zero = (Item) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, Item.class);
        zero.level = level;
        zero.stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 2);
        var toss = (Item) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, Item.class);
        toss.level = level;
        toss.stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 4);

        try (var worker = Executors.newSingleThreadExecutor()) {
            var journal = new Journal(worker);
            var bus = BusBuilder.builder().build();
            new JournalHooks(journal, () -> server).register(bus);
            journal.open(dir.resolve("journal.db"), () -> 400L);
            try {
                bus.post(new net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Post(player, partial,
                        new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 8)));
                bus.post(new net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Post(player, full,
                        new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 7)));
                bus.post(new net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Post(player, zero,
                        new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 2)));
                var canceled = new net.neoforged.neoforge.event.entity.item.ItemTossEvent(toss, player);
                canceled.setCanceled(true);
                bus.post(canceled);
                bus.post(new net.neoforged.neoforge.event.entity.item.ItemTossEvent(toss, player));
                var offThread = new Thread(() -> bus.post(new net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Post(player, partial,
                        new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 6))));
                offThread.start();
                offThread.join();

                assertEquals(3, journal.stats().offered.sum());
            } finally {
                journal.close().get(10, TimeUnit.SECONDS);
            }
            try (var store = JournalStore.open(dir.resolve("journal.db"))) {
                var pickup = new JournalSearch(store).search(new JournalFilter(null, null, null, "minecraft:overworld",
                        java.util.Set.of("pickup"), List.of("minecraft:diamond"), null, false), null, System.currentTimeMillis()).items();
                assertEquals(1, pickup.size());
                assertEquals(2, ((JournalEntry.EventEntry) pickup.getFirst()).count());
                assertEquals(12, ((JournalEntry.EventEntry) pickup.getFirst()).amount());
                assertEquals("player:00000000-0000-0000-0000-000000000001", ((JournalEntry.EventEntry) pickup.getFirst()).actor());
                assertEquals(400, ((JournalEntry.EventEntry) pickup.getFirst()).firstGameTime());
                assertEquals(new dev.packsage.world.BlockPlace("minecraft:overworld", 10, 64, -3),
                        ((JournalEntry.EventEntry) pickup.getFirst()).at());
                var drop = new JournalSearch(store).search(new JournalFilter(null, null, null, "minecraft:overworld",
                        java.util.Set.of("drop"), List.of("minecraft:diamond"), null, false), null, System.currentTimeMillis()).items();
                assertEquals(1, drop.size());
                assertEquals(4, ((JournalEntry.EventEntry) drop.getFirst()).amount());
                assertEquals(400, ((JournalEntry.EventEntry) drop.getFirst()).firstGameTime());
            }
        }
    }

    @Test
    void worldStartAndStopStayOutOfTheGameplayTickFigureAndTheSlowestHookIsNamed() {
        var stats = new JournalStats();
        stats.worldStartStopNanos(5_000_000);
        stats.hookNanos("BreakEvent", 200_000);
        stats.hookNanos("Detonate", 400_000);
        stats.endTick();
        assertEquals(600_000, stats.maxHookNanosPerTick.get());
        var summary = stats.summary();
        assertTrue(summary.contains("slowest tick 600 us"), summary);
        assertTrue(summary.contains("slowest hook Detonate 400 us (gameplay hook 2 of 2)"), summary);
        assertTrue(summary.contains("ticks at or over 1 ms 0 of 1 (0 after the first 200)"), summary);
        assertTrue(summary.contains("world start and stop 5 ms"), summary);
        assertTrue(summary.contains("hooks 5 ms total"), summary);
    }

    @Test
    void summariesLogAtStopAndAtMostOnceAMinuteWhileOverflowGrows(@TempDir Path dir) throws Exception {
        var field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = field.get(null);
        var server = (IntegratedServer) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, IntegratedServer.class);
        var serverThread = MinecraftServer.class.getDeclaredField("serverThread");
        serverThread.setAccessible(true);
        serverThread.set(server, Thread.currentThread());
        var levels = MinecraftServer.class.getDeclaredField("levels");
        levels.setAccessible(true);
        levels.set(server, Map.of());
        var summaries = new ArrayList<String>();
        var callingThread = Thread.currentThread().getName();
        var logger = (Logger) LogManager.getLogger("PackSage");
        var previousLevel = logger.getLevel();
        var appender = new AbstractAppender("journal-hook-summaries", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                var message = event.getMessage().getFormattedMessage();
                if (event.getThreadName().equals(callingThread) && message.startsWith("PackSage journal:")) summaries.add(message);
            }
        };
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var journal = new Journal(worker);
            var hooks = new JournalHooks(journal, () -> server);
            var session = hooks.newSession();
            session.server = server;
            var bus = BusBuilder.builder().build();
            hooks.register(bus);
            journal.open(dir.resolve("journal.db"), () -> 0);
            try {
                journal.write(store -> null).toCompletableFuture().get(10, TimeUnit.SECONDS);
                bus.post(new ServerTickEvent.Post(() -> true, server));
                assertTrue(summaries.isEmpty());
                journal.stats().overflowed.increment();
                session.lastOverflowLogNanos = System.nanoTime() - TimeUnit.SECONDS.toNanos(59);
                bus.post(new ServerTickEvent.Post(() -> true, server));
                assertTrue(summaries.isEmpty(), "wait a full minute before reporting overflow");
                session.lastOverflowLogNanos = System.nanoTime() - TimeUnit.SECONDS.toNanos(61);
                bus.post(new ServerTickEvent.Post(() -> true, server));
                assertEquals(1, summaries.size());
                assertTrue(summaries.getFirst().contains("overflowed 1"));
                journal.stats().overflowed.increment();
                bus.post(new ServerTickEvent.Post(() -> true, server));
                assertEquals(1, summaries.size(), "growth must not bypass the rate limit");
                session.lastOverflowLogNanos = System.nanoTime() - TimeUnit.SECONDS.toNanos(61);
                bus.post(new ServerTickEvent.Post(() -> true, server));
                assertEquals(2, summaries.size());
                assertTrue(summaries.getLast().contains("overflowed 2"));
                session.lastOverflowLogNanos = System.nanoTime() - TimeUnit.SECONDS.toNanos(61);
                bus.post(new ServerTickEvent.Post(() -> true, server));
                assertEquals(2, summaries.size(), "unchanged overflow must stay quiet");
                bus.post(new ServerStoppedEvent(server));
                assertEquals(3, summaries.size(), "world stop must log a summary even without new overflow");
                assertEquals(0, journal.stats().hookFailures());
                assertFalse(journal.recording());
            } finally {
                journal.close().get(10, TimeUnit.SECONDS);
            }
        } finally {
            logger.removeAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void explosionsCapNonAirPositionsAndCountOnlyDroppedNonAir() {
        var stats = new JournalStats();
        var reads = new AtomicInteger();
        var positions = IntStream.range(0, 5_000).mapToObj(x -> new BlockPos(x, 64, 0)).toList();
        var grouped = JournalHooks.explosionBlocks(positions, pos -> {
            reads.incrementAndGet();
            return pos.getX() == 0 ? null : pos.getX() % 2 == 0 ? "minecraft:stone" : "minecraft:sand";
        }, stats);
        assertEquals(5_000, reads.get());
        assertEquals(2_048, grouped.get("minecraft:stone").length);
        assertEquals(2_048, grouped.get("minecraft:sand").length);
        assertEquals(903, stats.droppedExplosionPositions());
        assertEquals(Positions.pack(4_095, 64, 0), grouped.get("minecraft:sand")[2_047]);
    }

    @Test
    void explosionStateReadsAreBoundedEvenWhenEveryPositionIsAir() {
        var stats = new JournalStats();
        var reads = new AtomicInteger();
        assertTrue(JournalHooks.explosionBlocks(IntStream.range(0, 10_000)
                .mapToObj(x -> new BlockPos(x, 64, 0)).toList(), pos -> {
                    reads.incrementAndGet();
                    return null;
                }, stats).isEmpty());
        assertEquals(8_192, reads.get());
        assertEquals(0, stats.droppedExplosionPositions());
        assertEquals(0, stats.hookFailures());
        assertEquals(1_808, stats.unreadExplosionPositions(), "unknown blocks are neither confirmed drops nor hook failures");
    }

    @Test
    void trackerStateAndItsCountersAreRecreatedForEveryWorld() {
        var hooks = new JournalHooks(new Journal(Runnable::run), () -> null);
        var first = hooks.newSession();
        first.containers.open("player:A", 1, new ContainerSessions.Holder("minecraft:chest", "minecraft:overworld", 0, 64, 0, null), Map.of());
        first.portals.leaving("player:A", "minecraft:overworld", new dev.packsage.world.BlockPlace("minecraft:overworld", 0, 64, 0), 0);
        var stamp = new JournalEvents.Stamp(0, 0, "minecraft:overworld");
        first.villagers.observe("v", "minecraft:none", 0, 64, 0, stamp);
        first.raids.observe(7, new RaidWatch.RaidState(true, null, 1, 1, 0, 64, 0), stamp);
        first.structures.inside("player:A", "minecraft:village_plains", 0, 0, 0, 64, 0, stamp);
        var next = hooks.newSession();
        assertTrue(next.containers.openPlayers().isEmpty());
        assertTrue(next.portals.arrived("player:A", "minecraft:overworld", 1).isEmpty());
        assertTrue(next.villagers.observe("v", "minecraft:farmer", 0, 64, 0, stamp).isEmpty());
        assertEquals(1, next.raids.observe(7, new RaidWatch.RaidState(true, null, 1, 1, 0, 64, 0), stamp).size());
        assertEquals(1, next.structures.inside("player:A", "minecraft:village_plains", 0, 0, 0, 64, 0, stamp).size());
    }

    @Test
    void sessionTrackerLossesReachStatsOnce() {
        var journal = new Journal(Runnable::run);
        var hooks = new JournalHooks(journal, () -> null);
        var session = hooks.newSession();
        for (int i = 0; i < 65; i++) {
            session.containers.open("player:" + i, 1,
                    new ContainerSessions.Holder("minecraft:chest", "minecraft:overworld", 0, 64, 0, null), Map.of());
        }
        var large = new HashMap<String, Long>();
        for (int i = 0; i < 4_097; i++) {
            large.put("mod:item_" + i, 1L);
        }
        session.containers.close("player:1", null, 1, large, new JournalEvents.Stamp(0, 0, "minecraft:overworld"));
        session.updateStats(journal.stats());
        session.updateStats(journal.stats());
        assertEquals(1, journal.stats().trackerEvictions());
        assertEquals(1, journal.stats().oversizedSnapshots());
    }

    @Test
    void structureAndInteractionHintEvictionsDoNotReportLostHistory() {
        var session = new JournalHooks(new Journal(Runnable::run), () -> null).newSession();
        var stats = new JournalStats();
        for (int i = 0; i <= StructureWatch.MAX_STARTS; i++) {
            session.structures.inside("player:A", "minecraft:village_plains", i, 0, 0, 64, 0,
                    new JournalEvents.Stamp(0, 0, "minecraft:overworld"));
        }
        assertEquals(1, session.structures.evictions());
        session.updateStats(stats);
        session.updateStats(stats);
        assertEquals(0, stats.trackerEvictions());
    }

    @Test
    void closingAnUntrackedMenuDoesNotReadThePlayerOrContents() throws ReflectiveOperationException {
        var unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = unsafeField.get(null);
        var player = (net.minecraft.server.level.ServerPlayer) unsafe.getClass().getMethod("allocateInstance", Class.class)
                .invoke(unsafe, net.minecraft.server.level.ServerPlayer.class);
        var hooks = new JournalHooks(new Journal(Runnable::run), () -> null);
        var close = JournalHooks.class.getDeclaredMethod("closeContainer", net.minecraft.server.level.ServerPlayer.class,
                net.minecraft.world.inventory.AbstractContainerMenu.class);
        close.setAccessible(true);
        // An uninitialized player and absent menu fail if extraction gets past the session guard.
        assertDoesNotThrow(() -> close.invoke(hooks, player, null));
    }

    @Test
    void callbacksWithoutAnIntegratedServerDoNotTouchSessionTrackerCounters() {
        var journal = new Journal(Runnable::run);
        var hooks = new JournalHooks(journal, () -> null);
        var session = hooks.newSession();
        for (int i = 0; i < 65; i++) {
            session.containers.open("player:" + i, 1,
                    new ContainerSessions.Holder("minecraft:chest", "minecraft:overworld", 0, 64, 0, null), Map.of());
        }
        var bus = BusBuilder.builder().build();
        hooks.register(bus);
        bus.post(new BlockEvent.BreakEvent(null, BlockPos.ZERO, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), null));
        assertEquals(0, journal.stats().trackerEvictions());
        journal.stats().endTick();
        assertEquals(0, journal.stats().maxHookNanosPerTick.get());
    }
}
