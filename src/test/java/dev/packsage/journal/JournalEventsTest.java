package dev.packsage.journal;

import com.google.gson.JsonParser;
import dev.packsage.world.BlockPlace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JournalEventsTest {
    private static final JournalEvents.Stamp STAMP = new JournalEvents.Stamp(200, 10_000, "minecraft:overworld");

    @TempDir
    Path dir;

    @Test
    void aVillagerKilledByAZombieIsRecordedWithItsCause() throws Exception {
        var facts = new JournalEvents.DeathFacts("minecraft:villager", "villager-1", null, false, true, false,
                "minecraft:farmer", "minecraft:mob_attack", "entity:minecraft:zombie", "minecraft:zombie",
                "Villager was slain by Zombie", 12, 64, -8);
        var event = JournalEvents.death(STAMP, facts).orElseThrow();
        assertEquals(EventType.DEATH, event.type());
        assertEquals("entity:minecraft:zombie", event.actor());
        assertEquals("minecraft:villager", event.subject());
        assertArrayEquals(new long[]{Positions.pack(12, 64, -8)}, event.positions());
        var detail = JsonParser.parseString(event.detail()).getAsJsonObject();
        assertEquals("villager-1", detail.get("victimId").getAsString());
        assertFalse(detail.has("name"));
        assertEquals("minecraft:farmer", detail.get("profession").getAsString());
        assertEquals("minecraft:mob_attack", detail.get("damageType").getAsString());
        assertEquals("entity:minecraft:zombie", detail.get("killer").getAsString());
        assertEquals("minecraft:zombie", detail.get("direct").getAsString());
        assertEquals("Villager was slain by Zombie", detail.get("message").getAsString());
        assertTrue(JournalEvents.kill(STAMP, facts).isEmpty());
        try (var store = JournalStore.open(dir.resolve("journal.db"))) {
            store.startSession(0, 0);
            store.writeBatch(List.of(new Run(event)), List.of());
            var page = new JournalSearch(store).search(new JournalFilter(null, null, null, "minecraft:overworld",
                    Set.of("death"), List.of("minecraft:villager"), "zombie", false), null, 10_100);
            assertEquals(1, page.items().size());
            assertEquals("entity:minecraft:zombie", ((JournalEntry.EventEntry) page.items().getFirst()).actor());
        }
    }

    @Test
    void unnamedWildMobDeathsAreNotRecordedButPlayerKillsAre() {
        var facts = new JournalEvents.DeathFacts("minecraft:zombie", "zombie-1", null, false, false, false,
                null, "minecraft:player_attack", "player:A", "minecraft:player", "Zombie was slain by Alex", 0, 64, 0);
        assertTrue(JournalEvents.death(STAMP, facts).isEmpty());
        var kill = JournalEvents.kill(STAMP, facts).orElseThrow();
        assertEquals(EventType.KILL, kill.type());
        assertEquals("player:A", kill.actor());
        assertEquals("minecraft:zombie", kill.subject());
        assertEquals(1, kill.amount());
    }

    @Test
    void playersNamedMobsAndTamedAnimalsAreRecordedAndNamedVillagersCanGiveBothEvents() {
        for (int i = 0; i < 4; i++) {
            var facts = new JournalEvents.DeathFacts(i == 3 ? "minecraft:villager" : "minecraft:wolf", "victim",
                    i >= 2 ? "A \"name\"" : null, i == 0, i == 3, i == 1, null,
                    "minecraft:fall", "player:A", null, "fell", 0, 64, 0);
            var event = JournalEvents.death(STAMP, facts).orElseThrow();
            assertEquals(EventType.DEATH, event.type());
            if (i >= 2) {
                assertEquals("A \"name\"", JsonParser.parseString(event.detail()).getAsJsonObject().get("name").getAsString());
            }
            assertEquals(EventType.KILL, JournalEvents.kill(STAMP, facts).orElseThrow().type());
        }
    }

    @Test
    void absentDeathMessagesAreOmitted() {
        var facts = new JournalEvents.DeathFacts("minecraft:player", "A", null, true, false, false,
                null, "minecraft:fall", null, null, null, 0, 64, 0);
        assertFalse(JsonParser.parseString(JournalEvents.death(STAMP, facts).orElseThrow().detail()).getAsJsonObject().has("message"));
    }

    @Test
    void environmentalDeathsHaveNoInventedAttacker() {
        var facts = new JournalEvents.DeathFacts("minecraft:player", "A", null, true, false, false,
                null, "minecraft:fall", null, null, "Alex fell from a high place", 0, 64, 0);
        var event = JournalEvents.death(STAMP, facts).orElseThrow();
        assertEquals("environment", event.actor());
        var detail = JsonParser.parseString(event.detail()).getAsJsonObject();
        assertFalse(detail.has("killer"));
        assertFalse(detail.has("direct"));
        assertFalse(detail.has("profession"));
        assertTrue(JournalEvents.kill(STAMP, facts).isEmpty());
    }

    @Test
    void explosionSplitsIntoOneExplosionAndOneBreakPerBlockKind() {
        var sand = IntStream.range(0, 30).mapToLong(x -> Positions.pack(x, 64, 0)).toArray();
        var stone = IntStream.range(0, 10).mapToLong(x -> Positions.pack(x, 63, 0)).toArray();
        var events = JournalEvents.explosion(STAMP, "player:A", "minecraft:tnt", 1.6, 64.2, -1.6,
                Map.of("minecraft:stone", stone, "minecraft:sand", sand));
        assertEquals(3, events.size());
        var explosion = events.getFirst();
        assertEquals(EventType.EXPLOSION, explosion.type());
        assertEquals("minecraft:tnt", explosion.subject());
        assertEquals("player:A", explosion.actor());
        assertEquals(40, explosion.amount());
        assertArrayEquals(new long[]{Positions.pack(2, 64, -2)}, explosion.positions());
        assertEquals(List.of("minecraft:sand", "minecraft:stone"), events.subList(1, 3).stream().map(JournalEvent::subject).toList());
        assertArrayEquals(sand, events.get(1).positions());
        assertArrayEquals(stone, events.get(2).positions());
        for (var event : events.subList(1, 3)) {
            assertEquals(EventType.BREAK, event.type());
            assertEquals("explosion", event.actor());
            assertEquals(event.positions().length, event.amount());
        }
    }

    @Test
    void explosionEnforcesTheHookCapAndStillRecordsAnEmptyBlast() {
        var full = IntStream.range(0, 4_096).mapToLong(x -> Positions.pack(x, 64, 0)).toArray();
        assertEquals(4_096, JournalEvents.explosion(STAMP, "environment", "minecraft:creeper", 0, 64, 0,
                Map.of("minecraft:stone", full)).get(1).positions().length);
        assertThrows(IllegalArgumentException.class, () -> JournalEvents.explosion(STAMP, "environment", "unknown", 0, 64, 0,
                Map.of("minecraft:stone", full, "minecraft:dirt", new long[]{Positions.pack(0, 63, 0)})));
        var empty = JournalEvents.explosion(STAMP, "environment", null, 0, 64, 0, Map.of("minecraft:air", new long[0]));
        assertEquals(1, empty.size());
        assertEquals(0, empty.getFirst().amount());
        assertEquals("unknown", empty.getFirst().subject());
    }

    @Test
    void blockActionsKeepTheirStampPlayerAndPosition() {
        var placed = JournalEvents.place(STAMP, "player:A", "Alex", "minecraft:stone", 1, 64, 2);
        var broken = JournalEvents.broken(STAMP, "player:A", "Alex", "minecraft:stone", 1, 64, 2);
        assertEquals(EventType.PLACE, placed.type());
        assertEquals(EventType.BREAK, broken.type());
        for (var event : List.of(placed, broken)) {
            assertEquals(200, event.gameTime());
            assertEquals(10_000, event.wallMillis());
            assertEquals("minecraft:overworld", event.dimension());
            assertEquals("player:A", event.actor());
            assertEquals("minecraft:stone", event.subject());
            assertEquals(1, event.amount());
            assertArrayEquals(new long[]{Positions.pack(1, 64, 2)}, event.positions());
            assertEquals("Alex", JsonParser.parseString(event.detail()).getAsJsonObject().get("actorName").getAsString());
        }
    }

    @Test
    void blockActionsWithoutAnActorNameHaveNoDetail() {
        assertNull(JournalEvents.place(STAMP, "player:A", null, "minecraft:stone", 1, 64, 2).detail());
        assertNull(JournalEvents.broken(STAMP, "player:A", null, "minecraft:stone", 1, 64, 2).detail());
    }

    @Test
    void itemEventFactoryKeepsThePlayerItemCountStampAndPosition() throws Exception {
        var create = JournalEvents.class.getMethod("item", JournalEvents.Stamp.class, EventType.class, String.class,
                String.class, String.class, long.class, int.class, int.class, int.class);
        for (var type : List.of("pickup", "drop")) {
            var amount = type.equals("pickup") ? 9L : 4L;
            var event = (JournalEvent) create.invoke(null, STAMP, EventType.byId(type), "player:A", "Alex", "minecraft:diamond",
                    amount, 1, 64, 2);
            assertEquals(EventType.byId(type), event.type());
            assertEquals(200, event.gameTime());
            assertEquals(10_000, event.wallMillis());
            assertEquals("minecraft:overworld", event.dimension());
            assertEquals("player:A", event.actor());
            assertEquals("minecraft:diamond", event.subject());
            assertEquals(amount, event.amount());
            assertArrayEquals(new long[]{Positions.pack(1, 64, 2)}, event.positions());
            assertEquals("Alex", JsonParser.parseString(event.detail()).getAsJsonObject().get("actorName").getAsString());
        }
    }

    @Test
    void transferKeepsContainerIdentityAndRejectsZeroOrUnrepresentableAmounts() {
        var deposit = JournalEvents.transfer(STAMP, "player:A", "Alex", "minecraft:stone", 5,
                "minecraft:chest", 1, 64, 2, null);
        var withdraw = JournalEvents.transfer(STAMP, "player:A", "Alex", "minecraft:stone", -7,
                "minecraft:chest_minecart", 1, 64, 2, "cart-1");
        assertEquals(EventType.DEPOSIT, deposit.type());
        assertEquals(5, deposit.amount());
        assertEquals(EventType.WITHDRAW, withdraw.type());
        assertEquals(7, withdraw.amount());
        assertEquals("minecraft:chest", JsonParser.parseString(deposit.detail()).getAsJsonObject().get("container").getAsString());
        assertFalse(JsonParser.parseString(deposit.detail()).getAsJsonObject().has("entity"));
        assertEquals("cart-1", JsonParser.parseString(withdraw.detail()).getAsJsonObject().get("entity").getAsString());
        assertThrows(IllegalArgumentException.class, () -> JournalEvents.transfer(STAMP, "player:A", "Alex", "minecraft:stone",
                0, "minecraft:chest", 1, 64, 2, null));
        assertThrows(IllegalArgumentException.class, () -> JournalEvents.transfer(STAMP, "player:A", "Alex", "minecraft:stone",
                Long.MIN_VALUE, "minecraft:chest", 1, 64, 2, null));
    }

    @Test
    void dimensionKeepsSourcePortalAndDestinationArrival() {
        var portal = new BlockPlace("minecraft:overworld", 80, 64, 80);
        var arrival = new BlockPlace("minecraft:the_nether", 10, 70, 10);
        var event = JournalEvents.dimension(STAMP, "player:A", "minecraft:the_nether", portal, arrival);
        assertEquals(EventType.DIMENSION, event.type());
        assertEquals("minecraft:overworld", event.dimension());
        assertEquals("minecraft:the_nether", event.subject());
        assertEquals("player:A", event.actor());
        assertArrayEquals(new long[]{Positions.pack(80, 64, 80)}, event.positions());
        var detail = JsonParser.parseString(event.detail()).getAsJsonObject();
        assertEquals(portal.text(), detail.get("portal").getAsString());
        assertEquals(arrival.text(), detail.get("arrival").getAsString());
    }

    @Test
    void unknownPortalUsesTheExplicitLastSourcePosition() {
        var last = new BlockPlace("minecraft:overworld", -80, 65, 90);
        var arrival = new BlockPlace("minecraft:the_nether", -10, 70, 11);
        var event = JournalEvents.dimension(STAMP, "player:A", "minecraft:the_nether", null, arrival, last);
        assertArrayEquals(new long[]{Positions.pack(-80, 65, 90)}, event.positions());
        assertEquals("minecraft:overworld", event.dimension());
        var detail = JsonParser.parseString(event.detail()).getAsJsonObject();
        assertFalse(detail.has("portal"));
        assertEquals(arrival.text(), detail.get("arrival").getAsString());
        assertThrows(IllegalArgumentException.class,
                () -> JournalEvents.dimension(STAMP, "player:A", "minecraft:the_nether", null, arrival));
    }

    @Test
    void advancementProfessionConversionsAndTradeKeepTheirFacts() {
        var advancement = JournalEvents.advancement(STAMP, "player:A", "minecraft:story/mine_stone", "Stone Age", 1, 64, 2);
        assertEquals(EventType.ADVANCEMENT, advancement.type());
        assertEquals("minecraft:story/mine_stone", advancement.subject());
        assertEquals("Stone Age", JsonParser.parseString(advancement.detail()).getAsJsonObject().get("title").getAsString());
        var profession = JournalEvents.profession(STAMP, "villager-1", "minecraft:none", "minecraft:farmer", 1, 64, 2);
        assertEquals(EventType.PROFESSION, profession.type());
        assertEquals("environment", profession.actor());
        assertEquals("minecraft:villager", profession.subject());
        assertEquals("villager-1", profession.group());
        var detail = JsonParser.parseString(profession.detail()).getAsJsonObject();
        assertEquals("minecraft:none", detail.get("from").getAsString());
        assertEquals("minecraft:farmer", detail.get("to").getAsString());
        var cured = JournalEvents.cured(STAMP, "villager-1", 1, 64, 2);
        var converted = JournalEvents.converted(STAMP, "villager-1", 1, 64, 2);
        assertEquals(EventType.CURED, cured.type());
        assertEquals(EventType.CONVERTED, converted.type());
        for (var event : List.of(cured, converted)) {
            assertEquals("environment", event.actor());
            assertEquals("minecraft:villager", event.subject());
            assertEquals("villager-1", event.group());
        }
        var trade = JournalEvents.trade(STAMP, "player:A", "villager-1", "minecraft:farmer", "minecraft:bread", 6,
                "minecraft:emerald", null, 1, 64, 2);
        assertEquals(EventType.TRADE, trade.type());
        assertEquals("player:A", trade.actor());
        assertEquals("villager-1", trade.group());
        assertEquals("minecraft:bread", trade.subject());
        assertEquals(6, trade.amount());
        var tradeDetail = JsonParser.parseString(trade.detail()).getAsJsonObject();
        assertEquals("minecraft:farmer", tradeDetail.get("profession").getAsString());
        assertEquals("minecraft:emerald", tradeDetail.get("costA").getAsString());
        assertFalse(tradeDetail.has("costB"));
        assertEquals("minecraft:wheat", JsonParser.parseString(JournalEvents.trade(STAMP, "player:A", "villager-1",
                "minecraft:farmer", "minecraft:bread", 1, "minecraft:emerald", "minecraft:wheat", 1, 64, 2).detail())
                .getAsJsonObject().get("costB").getAsString());
    }

    @Test
    void raidsAndStructuresHaveTheirStableDiscriminators() {
        var started = JournalEvents.raidStarted(STAMP, 7, 3, 0, 1, 64, 2);
        assertEquals(EventType.RAID_STARTED, started.type());
        assertEquals("7", started.group());
        assertEquals(3, JsonParser.parseString(started.detail()).getAsJsonObject().get("omen").getAsInt());
        for (var outcome : List.of("victory", "loss", "stopped", "unknown")) {
            var finished = JournalEvents.raidFinished(STAMP, 7, outcome, 5, 1, 64, 2);
            assertEquals(EventType.RAID_FINISHED, finished.type());
            assertEquals("7", finished.group());
            assertEquals("environment", finished.actor());
            var detail = JsonParser.parseString(finished.detail()).getAsJsonObject();
            assertEquals(outcome, detail.get("outcome").getAsString());
            assertEquals(5, detail.get("waves").getAsInt());
        }
        var structure = JournalEvents.structure(STAMP, "player:A", "minecraft:village_plains", -3, 8, -32, 64, 130);
        assertEquals(EventType.STRUCTURE, structure.type());
        assertEquals("minecraft:village_plains", structure.subject());
        assertEquals("-3,8", structure.group());
        assertNull(structure.detail());
    }
}
