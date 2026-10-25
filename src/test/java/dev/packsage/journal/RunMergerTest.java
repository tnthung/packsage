package dev.packsage.journal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunMergerTest {
    private static final String OVERWORLD = "minecraft:overworld";

    @Test
    void aFastTunnelOfSandBecomesOneRun() {
        var merger = new RunMerger();
        var expected = new long[2000];
        for (int i = 0; i < 2000; i++) {
            expected[i] = Positions.pack(i, 64, 0);
            merger.add(sand(i, i, 64, 0));
        }
        var runs = merger.takeChanged();
        assertEquals(1, runs.size());
        var run = runs.get(0);
        assertEquals(2000, run.count());
        assertEquals(2000, run.amount());
        assertEquals(new Box(0, 64, 0, 1999, 64, 0), run.box());
        assertEquals(0, run.firstGameTime());
        assertEquals(1999, run.lastGameTime());
        assertEquals(Positions.pack(0, 64, 0), run.first());
        assertEquals(-1, run.rowId());
        var decoded = Positions.decode(Positions.encode(run.positions(), run.count()));
        assertArrayEquals(expected, decoded);
    }

    @Test
    void ninetyNineTicksJoinsAndOneHundredStartsANewRun() {
        var merger = new RunMerger();
        merger.add(sand(0, 0, 64, 0));
        merger.add(sand(99, 1, 64, 0));
        merger.add(sand(199, 2, 64, 0));
        var runs = merger.takeChanged();
        assertEquals(2, runs.size());
        assertEquals(2, runs.get(0).count());
        assertEquals(99, runs.get(0).lastGameTime());
        assertEquals(1, runs.get(1).count());
        assertEquals(199, runs.get(1).firstGameTime());
    }

    @Test
    void sixteenBlocksFromTheBoxJoinsAndSeventeenStartsANewRun() {
        var merger = new RunMerger();
        merger.add(sand(0, 0, 64, 0));
        merger.add(sand(1, 16, 64, 0));
        merger.add(sand(2, 33, 64, 0));
        var runs = merger.takeChanged();
        assertEquals(2, runs.size());
        assertEquals(new Box(0, 64, 0, 16, 64, 0), runs.get(0).box());
        assertEquals(Box.of(33, 64, 0), runs.get(1).box());
    }

    @Test
    void differentActorSubjectDimensionOrGroupNeverMerge() {
        var merger = new RunMerger();
        merger.add(event(EventType.BREAK, 0, OVERWORLD, "player:A", "minecraft:sand", null, at(0, 64, 0)));
        merger.add(event(EventType.BREAK, 1, OVERWORLD, "player:B", "minecraft:sand", null, at(0, 64, 0)));
        merger.add(event(EventType.BREAK, 2, OVERWORLD, "player:A", "minecraft:gravel", null, at(0, 64, 0)));
        merger.add(event(EventType.BREAK, 3, "minecraft:the_nether", "player:A", "minecraft:sand", null, at(0, 64, 0)));
        merger.add(event(EventType.BREAK, 4, OVERWORLD, "player:A", "minecraft:sand", "chest", at(0, 64, 0)));
        merger.add(event(EventType.PLACE, 5, OVERWORLD, "player:A", "minecraft:sand", null, at(0, 64, 0)));
        var runs = merger.takeChanged();
        assertEquals(6, runs.size());
        assertEquals(6, merger.openRuns());
        for (var run : runs) {
            assertEquals(1, run.count());
        }
    }

    @Test
    void interleavedSandAndGravelKeepTheirOwnRuns() {
        var merger = new RunMerger();
        for (int i = 0; i < 50; i++) {
            merger.add(sand(2L * i, i, 64, 0));
            merger.add(event(EventType.BREAK, 2L * i + 1, OVERWORLD, "player:A", "minecraft:gravel", null, at(i, 64, 1)));
        }
        var runs = merger.takeChanged();
        assertEquals(2, runs.size());
        assertEquals("minecraft:sand", runs.get(0).key().subject());
        assertEquals(50, runs.get(0).count());
        assertEquals("minecraft:gravel", runs.get(1).key().subject());
        assertEquals(50, runs.get(1).count());
    }

    @Test
    void deathsNeverMerge() {
        var merger = new RunMerger();
        merger.add(event(EventType.DEATH, 10, OVERWORLD, "entity:minecraft:zombie", "minecraft:villager", null, at(5, 64, 5)));
        merger.add(event(EventType.DEATH, 10, OVERWORLD, "entity:minecraft:zombie", "minecraft:villager", null, at(5, 64, 5)));
        assertEquals(2, merger.takeChanged().size());
        assertEquals(0, merger.openRuns());
    }

    @Test
    void runsStopAt4096Positions() {
        var merger = new RunMerger();
        for (int i = 0; i < 5000; i++) {
            merger.add(sand(i / 10, i, 64, 0));
        }
        var runs = merger.takeChanged();
        assertEquals(2, runs.size());
        assertEquals(4096, runs.get(0).count());
        assertEquals(904, runs.get(1).count());
        assertEquals(Positions.pack(4096, 64, 0), runs.get(1).first());
    }

    @Test
    void explosionBreaksWithManyPositionsJoinTheBreakRun() {
        var merger = new RunMerger();
        for (int k = 0; k < 3; k++) {
            var positions = new long[40];
            for (int j = 0; j < 40; j++) {
                positions[j] = Positions.pack(8 * k + j % 5, 64 - j / 5, 0);
            }
            merger.add(event(EventType.BREAK, 10L * k, OVERWORLD, "explosion", "minecraft:stone", null, positions));
        }
        var runs = merger.takeChanged();
        assertEquals(1, runs.size());
        assertEquals(120, runs.get(0).count());
        assertEquals(120, runs.get(0).amount());
        assertEquals(new Box(0, 57, 0, 20, 64, 0), runs.get(0).box());
    }

    @Test
    void takeChangedReportsNewRunsThenExtendedOnes() {
        var merger = new RunMerger();
        merger.add(sand(0, 0, 64, 0));
        merger.add(event(EventType.BREAK, 1, OVERWORLD, "player:A", "minecraft:gravel", null, at(0, 64, 1)));
        var first = merger.takeChanged();
        assertEquals(2, first.size());
        var sand = first.get(0);
        var gravel = first.get(1);
        sand.rowId(1);
        gravel.rowId(2);
        assertEquals(List.of(), merger.takeChanged());

        merger.add(event(EventType.BREAK, 2, OVERWORLD, "player:A", "minecraft:gravel", null, at(1, 64, 1)));
        merger.add(sand(3, 1, 64, 0));
        merger.add(event(EventType.PLACE, 4, OVERWORLD, "player:A", "minecraft:torch", null, at(1, 65, 0)));
        var second = merger.takeChanged();
        assertEquals(3, second.size());
        assertSame(sand, second.get(0));
        assertEquals(1, second.get(0).rowId());
        assertEquals(2, second.get(0).count());
        assertSame(gravel, second.get(1));
        assertEquals(2, second.get(1).rowId());
        assertEquals(2, second.get(1).count());
        assertEquals(-1, second.get(2).rowId());
        assertEquals(EventType.PLACE, second.get(2).key().type());
    }

    @Test
    void closeIdleForgetsRunsAfterOneHundredTicks() {
        var merger = new RunMerger();
        merger.add(sand(0, 0, 64, 0));
        merger.add(event(EventType.BREAK, 50, OVERWORLD, "player:A", "minecraft:gravel", null, at(0, 64, 1)));
        merger.closeIdle(99);
        assertEquals(2, merger.openRuns());
        merger.closeIdle(100);
        assertEquals(1, merger.openRuns());
        merger.closeIdle(150);
        assertEquals(0, merger.openRuns());
        assertEquals(2, merger.takeChanged().size());
    }

    @Test
    void aRunKeepsTheFirstDetailAndTheWallSpan() {
        var merger = new RunMerger();
        merger.add(new JournalEvent(EventType.BREAK, 0, 1_000, OVERWORLD, "player:A", "minecraft:sand", null, 1,
                at(0, 64, 0), "{\"tool\":\"shovel\"}"));
        merger.add(new JournalEvent(EventType.BREAK, 1, 1_050, OVERWORLD, "player:A", "minecraft:sand", null, 1,
                at(1, 64, 0), "{\"tool\":\"hand\"}"));
        var run = merger.takeChanged().get(0);
        assertEquals("{\"tool\":\"shovel\"}", run.detail());
        assertEquals(1_000, run.firstWall());
        assertEquals(1_050, run.lastWall());
        assertTrue(run.accepts(sand(2, 2, 64, 0)));
    }

    private static JournalEvent sand(long tick, int x, int y, int z) {
        return event(EventType.BREAK, tick, OVERWORLD, "player:A", "minecraft:sand", null, at(x, y, z));
    }

    private static long[] at(int x, int y, int z) {
        return new long[]{Positions.pack(x, y, z)};
    }

    private static JournalEvent event(EventType type, long tick, String dimension, String actor, String subject, String group,
                                      long[] positions) {
        return new JournalEvent(type, tick, 1_000L + tick * 50, dimension, actor, subject, group, positions.length, positions, null);
    }
}
