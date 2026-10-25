package dev.packsage.journal;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    void everyRegisteredHandlerContainsRuntimeExceptionsAndAccountsItsTime() {
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
        assertTrue(journal.stats().maxHookNanosPerTick.get() > 0);
        for (var type : List.of(BlockEvent.BreakEvent.class, BlockEvent.EntityPlaceEvent.class,
                LivingDeathEvent.class, EntityTravelToDimensionEvent.class)) {
            assertEquals(EventPriority.LOWEST, listeners.get(type).priority());
        }
    }

    @Test
    void explosionsCapPositionsBeforeReadingStatesAndGroupingAndCountWhatWasDropped() {
        var stats = new JournalStats();
        var reads = new AtomicInteger();
        var positions = IntStream.range(0, 5_000).mapToObj(x -> new BlockPos(x, 64, 0)).toList();
        var grouped = JournalHooks.explosionBlocks(positions, pos -> {
            reads.incrementAndGet();
            return pos.getX() == 0 ? null : pos.getX() % 2 == 0 ? "minecraft:stone" : "minecraft:sand";
        }, stats);
        assertEquals(4_096, reads.get());
        assertEquals(2_047, grouped.get("minecraft:stone").length);
        assertEquals(2_048, grouped.get("minecraft:sand").length);
        assertEquals(904, stats.droppedExplosionPositions());
        assertEquals(Positions.pack(4_095, 64, 0), grouped.get("minecraft:sand")[2_047]);
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
    }
}
