package dev.packsage.journal;

import com.google.gson.JsonParser;
import dev.packsage.world.BlockPlace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackersTest {
    private static final JournalEvents.Stamp STAMP = new JournalEvents.Stamp(200, 10_000, "minecraft:overworld");
    private static final ContainerSessions.Holder CHEST = new ContainerSessions.Holder("minecraft:chest", "minecraft:overworld", 1, 64, 2, null);

    @TempDir
    Path dir;

    @Test
    void containerCloseEmitsDepositsAndWithdrawalsByItem() {
        var sessions = new ContainerSessions();
        sessions.open("player:A", 7, CHEST, Map.of("minecraft:stone", 64L, "minecraft:dirt", 10L));
        var events = sessions.close("player:A", "Alex", 7, Map.of("minecraft:stone", 128L, "minecraft:iron_ingot", 5L), STAMP);
        assertEquals(List.of("minecraft:dirt", "minecraft:iron_ingot", "minecraft:stone"), events.stream().map(JournalEvent::subject).toList());
        assertEquals(List.of(EventType.WITHDRAW, EventType.DEPOSIT, EventType.DEPOSIT), events.stream().map(JournalEvent::type).toList());
        assertEquals(List.of(10L, 5L, 64L), events.stream().map(JournalEvent::amount).toList());
        for (var event : events) {
            assertEquals("player:A", event.actor());
            assertArrayEquals(new long[]{Positions.pack(1, 64, 2)}, event.positions());
        }
        assertTrue(sessions.openPlayers().isEmpty());
        assertTrue(sessions.close("player:A", "Alex", 7, Map.of(), STAMP).isEmpty());
    }

    @Test
    void closeWithoutOpenAndUnchangedContentsEmitNothing() {
        var sessions = new ContainerSessions();
        assertTrue(sessions.close("player:A", "Alex", 7, Map.of(), STAMP).isEmpty());
        sessions.open("player:A", 7, CHEST, Map.of("minecraft:stone", 64L));
        assertTrue(sessions.close("player:A", "Alex", 7, Map.of("minecraft:stone", 64L), STAMP).isEmpty());
        assertTrue(sessions.openPlayers().isEmpty());
    }

    @Test
    void snapshotsAreCopiedAndCloseMustMatchThePlayerAndMenu() {
        var sessions = new ContainerSessions();
        var before = new HashMap<>(Map.of("minecraft:stone", 64L));
        sessions.open("player:A", 7, CHEST, before);
        before.clear();
        assertTrue(sessions.close("player:B", "Steve", 7, Map.of(), STAMP).isEmpty());
        assertTrue(sessions.close("player:A", "Alex", 8, Map.of(), STAMP).isEmpty());
        assertEquals(Set.of("player:A"), sessions.openPlayers());
        var event = sessions.close("player:A", "Alex", 7, Map.of(), STAMP).getFirst();
        assertEquals(EventType.WITHDRAW, event.type());
        assertEquals(64, event.amount());
    }

    @Test
    void aNewMenuReplacesTheOldOneAndHolderDimensionIsPreservedAtClose() {
        var sessions = new ContainerSessions();
        sessions.open("player:A", 7, CHEST, Map.of("minecraft:stone", 64L));
        sessions.open("player:A", 8, new ContainerSessions.Holder("minecraft:chest_minecart", "minecraft:the_nether", 8, 70, 8, "cart-1"), Map.of());
        assertTrue(sessions.close("player:A", "Alex", 7, Map.of(), STAMP).isEmpty());
        var event = sessions.close("player:A", "Alex", 8, Map.of("minecraft:iron_ingot", 5L), STAMP).getFirst();
        assertEquals("minecraft:the_nether", event.dimension());
        assertEquals("cart-1", JsonParser.parseString(event.detail()).getAsJsonObject().get("entity").getAsString());
        assertArrayEquals(new long[]{Positions.pack(8, 70, 8)}, event.positions());
    }

    @Test
    void containerSessionsAndTheirItemSnapshotsAreBounded() {
        var sessions = new ContainerSessions();
        for (int i = 0; i < 65; i++) {
            sessions.open("player:" + i, 7, CHEST, Map.of());
        }
        assertEquals(64, sessions.openPlayers().size());
        assertFalse(sessions.openPlayers().contains("player:0"));
        var snapshot = sessions.openPlayers();
        sessions.close("player:1", "Alex", 7, Map.of(), STAMP);
        assertEquals(64, snapshot.size());
        var contents = new HashMap<String, Long>();
        for (int i = 0; i < 4_096; i++) {
            contents.put("mod:item_" + i, 1L);
        }
        sessions.open("player:large", 7, CHEST, contents);
        contents.put("mod:extra", 1L);
        assertThrows(IllegalArgumentException.class, () -> sessions.open("player:huge", 7, CHEST, contents));
        assertTrue(sessions.close("player:large", "Alex", 7, contents, STAMP).isEmpty());
        assertEquals(1, sessions.oversizedSnapshots());
        assertEquals(1, sessions.evictions());
        assertFalse(sessions.openPlayers().contains("player:large"));
    }

    @Test
    void portalPositionPairsWithTheNextDimensionChange() {
        var tracker = new PortalTracker();
        var portal = new BlockPlace("minecraft:overworld", 80, 64, 80);
        tracker.leaving("player:A", "minecraft:overworld", portal, 10);
        assertEquals(portal, tracker.arrived("player:A", "minecraft:overworld", 210).orElseThrow());
        assertTrue(tracker.arrived("player:A", "minecraft:overworld", 210).isEmpty());
    }

    @Test
    void aStaleDepartureIsIgnored() {
        var tracker = new PortalTracker();
        tracker.leaving("player:A", "minecraft:overworld", new BlockPlace("minecraft:overworld", 80, 64, 80), 10);
        assertTrue(tracker.arrived("player:A", "minecraft:overworld", 211).isEmpty());
        tracker.leaving("player:A", "minecraft:overworld", new BlockPlace("minecraft:overworld", 80, 64, 80), 10);
        assertTrue(tracker.arrived("player:A", "minecraft:overworld", 9).isEmpty());
        tracker.leaving("player:A", "minecraft:overworld", new BlockPlace("minecraft:overworld", 80, 64, 80), 10);
        assertTrue(tracker.arrived("player:A", "minecraft:overworld", 20).isPresent());
    }

    @Test
    void departuresRequireTheSamePlayerSourceDimensionAndForwardTimeAndAreBounded() {
        var tracker = new PortalTracker();
        var portal = new BlockPlace("minecraft:overworld", 80, 64, 80);
        tracker.leaving("player:A", "minecraft:overworld", portal, 10);
        assertTrue(tracker.arrived("player:B", "minecraft:overworld", 20).isEmpty());
        assertTrue(tracker.arrived("player:A", "minecraft:the_nether", 20).isEmpty());
        tracker.leaving("player:A", "minecraft:overworld", portal, 10);
        assertTrue(tracker.arrived("player:A", "minecraft:overworld", 9).isEmpty());
        for (int i = 0; i < 65; i++) {
            tracker.leaving("player:" + i, "minecraft:overworld", portal, 10);
        }
        assertTrue(tracker.arrived("player:0", "minecraft:overworld", 20).isEmpty());
        assertEquals(1, tracker.evictions());
        assertEquals(portal, tracker.arrived("player:64", "minecraft:overworld", 20).orElseThrow());
    }

    @Test
    void villagerFirstSightingIsSilentAndAChangeIsRecorded() {
        var watch = new VillagerWatch();
        assertTrue(watch.observe("v1", "minecraft:none", 1, 64, 2, STAMP).isEmpty());
        assertTrue(watch.observe("v1", "minecraft:none", 1, 64, 2, STAMP).isEmpty());
        var event = watch.observe("v1", "minecraft:farmer", 2, 64, 3, STAMP).getFirst();
        assertEquals(EventType.PROFESSION, event.type());
        assertEquals("v1", event.group());
        assertEquals("minecraft:none", JsonParser.parseString(event.detail()).getAsJsonObject().get("from").getAsString());
        assertEquals("minecraft:farmer", JsonParser.parseString(event.detail()).getAsJsonObject().get("to").getAsString());
        assertArrayEquals(new long[]{Positions.pack(2, 64, 3)}, event.positions());
        assertTrue(watch.observe("v1", "minecraft:farmer", 2, 64, 3, STAMP).isEmpty());
    }

    @Test
    void villagerEvictionKeepsRecentlySeenVillagers() {
        var watch = new VillagerWatch();
        for (int i = 0; i < 4_096; i++) {
            watch.observe("v" + i, "minecraft:none", 1, 64, 2, STAMP);
        }
        watch.observe("v0", "minecraft:none", 1, 64, 2, STAMP);
        watch.observe("extra", "minecraft:none", 1, 64, 2, STAMP);
        assertEquals(1, watch.evictions());
        assertEquals(1, watch.observe("v0", "minecraft:farmer", 1, 64, 2, STAMP).size());
        assertTrue(watch.observe("v1", "minecraft:farmer", 1, 64, 2, STAMP).isEmpty());
    }

    @Test
    void raidStartsOnceAndFinishesOnceWithItsOutcome() {
        var watch = new RaidWatch();
        var waiting = new RaidWatch.RaidState(false, null, 2, 0, 1, 64, 2);
        var ongoing = new RaidWatch.RaidState(true, null, 2, 1, 1, 64, 2);
        var victory = new RaidWatch.RaidState(true, "victory", 2, 5, 1, 64, 2);
        assertTrue(watch.observe(7, waiting, STAMP).isEmpty());
        assertEquals(EventType.RAID_STARTED, watch.observe(7, ongoing, STAMP).getFirst().type());
        assertTrue(watch.observe(7, ongoing, STAMP).isEmpty());
        var event = watch.observe(7, victory, STAMP).getFirst();
        assertEquals(EventType.RAID_FINISHED, event.type());
        assertEquals("victory", JsonParser.parseString(event.detail()).getAsJsonObject().get("outcome").getAsString());
        assertTrue(watch.observe(7, victory, STAMP).isEmpty());
        assertTrue(watch.missing(Set.of(), STAMP).isEmpty());
        assertTrue(watch.missing(Set.of(), STAMP).isEmpty());
    }

    @Test
    void aRaidThatDisappearsFinishesUnknown() {
        var watch = new RaidWatch();
        watch.observe(7, new RaidWatch.RaidState(true, null, 2, 3, 1, 64, 2), STAMP);
        assertTrue(watch.missing(Set.of(7), STAMP).isEmpty());
        var event = watch.missing(Set.of(), STAMP).getFirst();
        assertEquals(EventType.RAID_FINISHED, event.type());
        assertEquals("unknown", JsonParser.parseString(event.detail()).getAsJsonObject().get("outcome").getAsString());
        assertEquals(3, JsonParser.parseString(event.detail()).getAsJsonObject().get("waves").getAsInt());
        assertTrue(watch.missing(Set.of(), STAMP).isEmpty());
    }

    @Test
    void raidIdsAreScopedToTheirDimensionAndMemoryIsBounded() {
        var watch = new RaidWatch();
        var nether = new JournalEvents.Stamp(200, 10_000, "minecraft:the_nether");
        var ongoing = new RaidWatch.RaidState(true, null, 1, 1, 1, 64, 2);
        assertEquals(1, watch.observe(7, ongoing, STAMP).size());
        assertEquals(1, watch.observe(7, ongoing, nether).size());
        assertEquals(1, watch.missing(Set.of(), nether).size());
        assertEquals(1, watch.missing(Set.of(), STAMP).size());
        for (int i = 0; i < 1_025; i++) {
            watch.observe(i, ongoing, STAMP);
        }
        assertEquals(1_024, watch.missing(Set.of(), STAMP).size());
        assertEquals(1, watch.evictions());
    }

    @Test
    void firstObservationCanIncludeBothRaidStartAndFinish() {
        var watch = new RaidWatch();
        var state = new RaidWatch.RaidState(true, "loss", 1, 2, 1, 64, 2);
        assertEquals(List.of(EventType.RAID_STARTED, EventType.RAID_FINISHED),
                watch.observe(7, state, STAMP).stream().map(JournalEvent::type).toList());
        assertTrue(watch.observe(7, state, STAMP).isEmpty());
    }

    @Test
    void aRaidFirstSeenFinishedWithoutStartingHasOnlyAFinish() {
        var watch = new RaidWatch();
        var state = new RaidWatch.RaidState(false, "stopped", 1, 0, 1, 64, 2);
        assertEquals(List.of(EventType.RAID_FINISHED),
                watch.observe(7, state, STAMP).stream().map(JournalEvent::type).toList());
        assertTrue(watch.observe(7, new RaidWatch.RaidState(true, null, 1, 1, 1, 64, 2), STAMP).isEmpty());
        assertTrue(watch.missing(Set.of(), STAMP).isEmpty());
    }

    @Test
    void structureEntryIsRecordedOncePerStartPerSession() {
        var watch = new StructureWatch();
        var event = watch.inside("player:A", "minecraft:village_plains", -3, 8, -32, 64, 130, STAMP).getFirst();
        assertEquals(EventType.STRUCTURE, event.type());
        assertEquals("minecraft:village_plains", event.subject());
        assertEquals("-3,8", event.group());
        assertTrue(watch.inside("player:B", "minecraft:village_plains", -3, 8, -30, 64, 132, STAMP).isEmpty());
        assertEquals(1, watch.inside("player:A", "minecraft:village_plains", -2, 8, -30, 64, 132, STAMP).size());
        assertEquals(1, watch.inside("player:A", "minecraft:stronghold", -3, 8, -30, 64, 132, STAMP).size());
        assertEquals(1, watch.inside("player:A", "minecraft:village_plains", -3, 8, -30, 64, 132,
                new JournalEvents.Stamp(200, 10_000, "minecraft:the_nether")).size());
        assertEquals(1, new StructureWatch().inside("player:A", "minecraft:village_plains", -3, 8, -30, 64, 132, STAMP).size());
    }

    @Test
    void structureEvictionStillAdmitsNewStartsAndReentryStoresOnlyOneRow() throws Exception {
        var watch = new StructureWatch();
        var queue = new EventQueue(EventQueue.CAPACITY, EventQueue.OVERFLOW_KEYS);
        var writer = new JournalWriter(() -> JournalStore.open(dir.resolve("journal.db")), queue, new JournalStats(),
                () -> 200L, () -> 10_000L, 1_000);
        try (var store = writer.openNow()) {
            queue.offer(watch.inside("player:A", "minecraft:village_plains", 0, 0, 0, 64, 0, STAMP).getFirst());
            writer.drainOnce();
            for (int i = 1; i < 4_096; i++) {
                watch.inside("player:A", "minecraft:village_plains", i, 0, i * 16, 64, 0, STAMP);
            }
            assertTrue(watch.inside("player:A", "minecraft:village_plains", 1, 0, 16, 64, 0, STAMP).isEmpty());
            assertEquals(1, watch.inside("player:A", "minecraft:village_plains", 4_096, 0, 65_536, 64, 0, STAMP).size());
            assertEquals(1, watch.evictions());
            var reentry = watch.inside("player:B", "minecraft:village_plains", 0, 0, 0, 64, 0, STAMP);
            assertEquals(1, reentry.size());
            queue.offer(reentry.getFirst());
            writer.drainOnce();
            try (var statement = store.reader().createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM events WHERE type = 'structure'")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
            assertTrue(watch.inside("player:A", "minecraft:village_plains", 1, 0, 16, 64, 0, STAMP).isEmpty());
        }
    }
}
