package dev.packsage.world;

import dev.packsage.knowledge.Evidence;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatisticListingTest {
    @Test
    void ordersByCategoryThenSubjectAndPages() {
        var mined = new ArrayList<Statistic>();
        for (var i = 69; i >= 0; i--) {
            mined.add(count("mined", String.format("minecraft:block_%02d", i)));
        }
        var other = List.of(count("custom", "minecraft:jump"), count("mod:foo", "mod:thing"), count("custom", "minecraft:deaths"),
                count("killed_by", "minecraft:zombie"), count("mined", "minecraft:block_00"));
        var lists = List.of(List.copyOf(mined), other);
        var page = StatisticListing.page(lists, 0, List.of());
        assertEquals(64, page.items().size());
        assertEquals("64", page.nextCursor());
        assertEquals(Evidence.Coverage.COMPLETE, page.coverage());
        assertNull(page.note());
        assertEquals("minecraft:block_00", page.items().get(0).subject());
        assertEquals("minecraft:block_63", page.items().get(63).subject());
        assertTrue(page.items().stream().allMatch(row -> row.category().equals("mined")));
        var next = StatisticListing.page(lists, 64, List.of("adapter x failed: boom"));
        assertNull(next.nextCursor());
        assertEquals(List.of("mined/minecraft:block_64", "mined/minecraft:block_65", "mined/minecraft:block_66", "mined/minecraft:block_67",
                "mined/minecraft:block_68", "mined/minecraft:block_69", "killed_by/minecraft:zombie", "custom/minecraft:deaths",
                "custom/minecraft:jump", "mod:foo/mod:thing"), next.items().stream().map(row -> row.category() + "/" + row.subject()).toList());
        assertEquals(Evidence.Coverage.PARTIAL, next.coverage());
        assertEquals("adapter x failed: boom", next.note());
    }

    @Test
    void emptyListingSaysUnlistedCountersAreZero() {
        var page = StatisticListing.page(List.of(List.of(), List.of()), 0, List.of());
        assertTrue(page.items().isEmpty());
        assertNull(page.nextCursor());
        assertEquals(Evidence.Coverage.COMPLETE, page.coverage());
        assertEquals("no counter matches; unlisted counters are zero", page.note());
    }

    private static Statistic count(String category, String subject) {
        return new Statistic("minecraft", category, subject, subject, 1, StatisticUnit.COUNT, 1);
    }
}
