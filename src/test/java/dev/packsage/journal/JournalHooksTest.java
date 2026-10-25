package dev.packsage.journal;

import net.minecraft.SharedConstants;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
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

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JournalHooksTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
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
        assertEquals(17, listeners.size());
        for (var listener : listeners.values()) {
            assertFalse(listener.receiveCanceled());
            assertDoesNotThrow(() -> listener.action().accept(null));
        }
        assertEquals(17, journal.stats().hookFailures());
        journal.stats().endTick();
        assertEquals(0, journal.stats().maxHookNanosPerTick.get());
        for (var type : List.of(BlockEvent.BreakEvent.class, BlockEvent.EntityPlaceEvent.class,
                LivingDeathEvent.class, EntityTravelToDimensionEvent.class)) {
            assertEquals(EventPriority.LOWEST, listeners.get(type).priority());
        }
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
