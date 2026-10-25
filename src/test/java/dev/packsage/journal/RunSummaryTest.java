package dev.packsage.journal;

import dev.packsage.world.BlockPlace;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunSummaryTest {
    @Test
    void coarsenedOverflowDescribesUnknownActorsAndBlocks() {
        for (var type : List.of(EventType.BREAK, EventType.PLACE)) {
            assertTrue(RunSummary.describe(new JournalEntry.EventEntry(1, type, "*", "*", "minecraft:overworld",
                    20, 20, 1, 2, 0, 1_000, null, null, null, true, List.of(), 0), null)
                    .startsWith("an unknown player/actor " + (type == EventType.BREAK ? "broke" : "placed") + " 20 unknown blocks"));
        }
    }

    @Test
    void summarizesABreakRunAndADeath() {
        var start = Instant.parse("2026-10-25T10:00:00Z").toEpochMilli();
        var end = Instant.parse("2026-10-25T10:03:12Z").toEpochMilli();
        assertEquals("Steve broke 2,140 minecraft:sand in minecraft:overworld from 10,64,-3 (box 0,60,-20 to 30,64,5)"
                        + " between 2026-10-25T10:00:00Z and 2026-10-25T10:03:12Z",
                RunSummary.describe(new JournalEntry.EventEntry(1, EventType.BREAK, "player:steve", "minecraft:sand",
                        "minecraft:overworld", 2_140, 2_140, 1, 2, start, end, new BlockPlace("minecraft:overworld", 10, 64, -3),
                        new Box(0, 60, -20, 30, 64, 5), null, false, List.of(), 0), "Steve"));
        assertEquals("minecraft:villager \"Bob\" died in minecraft:overworld at 3,64,9 at 2026-10-25T10:00:00Z: Bob was slain by Zombie",
                RunSummary.describe(new JournalEntry.EventEntry(2, EventType.DEATH, "entity:minecraft:zombie", "minecraft:villager",
                        "minecraft:overworld", 1, 1, 1, 1, start, start, new BlockPlace("minecraft:overworld", 3, 64, 9),
                        Box.of(3, 64, 9), "{\"name\":\"Bob\",\"message\":\"Bob was slain by Zombie\"}", false, List.of(), 0), null));
    }

    @Test
    void summarizesEveryTypeAndCountOnlyRows() {
        var verbs = List.of("placed", "broke", "put 2,000 minecraft:sand into minecraft:chest", "took 2,000 minecraft:sand from minecraft:chest",
                "died", "killed", "travelled to", "earned", "changed profession to minecraft:farmer", "was cured",
                "turned into a zombie villager", "traded for", "raid started", "raid ended", "entered", "explosion");
        for (var type : EventType.values()) {
            var description = RunSummary.describe(new JournalEntry.EventEntry(1, type, "player:steve", "minecraft:sand",
                    "minecraft:overworld", 3, 2_000, 1, 2, 0, 1_000, null, null,
                    "{\"container\":\"minecraft:chest\",\"to\":\"minecraft:farmer\"}", true, List.of(), 0), "Steve");
            assertTrue(description.contains(verbs.get(type.ordinal())), description);
            assertTrue(description.endsWith("(positions not recorded)"), description);
        }
        assertTrue(RunSummary.describe(new JournalEntry.EventEntry(1, EventType.DEATH, "environment", "minecraft:villager",
                "minecraft:overworld", 1, 1, 0, 0, 0, 0, null, null, "{truncated", true, List.of(), 0), null)
                .contains("minecraft:villager died"));
    }
}
