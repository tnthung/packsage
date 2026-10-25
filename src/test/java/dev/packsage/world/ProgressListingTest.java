package dev.packsage.world;

import dev.packsage.knowledge.Evidence;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressListingTest {
    private static final ProgressListing.Filter ALL = new ProgressListing.Filter(null, Set.of(), "");

    @Test
    void treesComeFirstThenEntriesInOrderAcross64RecordPages() {
        var first = snapshot("a", 3, 60, null);
        var second = snapshot("b", 2, 40, null);
        var page = ProgressListing.page(List.of(first, second), ALL, 0);
        assertEquals(64, page.items().size());
        assertEquals("64", page.nextCursor());
        assertEquals(Evidence.Coverage.COMPLETE, page.coverage());
        var trees = page.items().subList(0, 5).stream().map(item -> item.tree().id().text()).toList();
        assertEquals(List.of("a/t0", "a/t1", "a/t2", "b/t0", "b/t1"), trees);
        assertTrue(page.items().subList(0, 5).stream().allMatch(item -> item.entry() == null));
        assertEquals(first.entries().subList(0, 59), page.items().subList(5, 64).stream().map(ProgressListing.Item::entry).toList());
        var next = ProgressListing.page(List.of(first, second), ALL, 64);
        assertEquals(41, next.items().size());
        assertNull(next.nextCursor());
        var expected = new ArrayList<>(first.entries().subList(59, 60));
        expected.addAll(second.entries());
        assertEquals(expected, next.items().stream().map(ProgressListing.Item::entry).toList());
    }

    @Test
    void textNeverMatchesHiddenEntries() {
        var tree = new ProgressId("minecraft", "minecraft:adventure/root");
        var hidden = new ProgressEntry(new ProgressHandle(new ProgressId("minecraft", "minecraft:adventure/levitate"), tree, null, ProgressStatus.HIDDEN, true), null);
        var visible = entry(new ProgressId("minecraft", "minecraft:adventure/sleep_in_bed"), tree, "Sweet Dreams", ProgressStatus.DONE);
        var snapshots = List.of(new ProgressSnapshot(List.of(new ProgressTree(tree, "Adventure", 1, 2, true)), List.of(visible, hidden), null));
        var byText = ProgressListing.page(snapshots, new ProgressListing.Filter(null, Set.of(), "LEVITATE"), 0);
        assertTrue(byText.items().isEmpty());
        assertEquals(Evidence.Coverage.COMPLETE, byText.coverage());
        assertEquals("no progress entry matches", byText.note());
        var byStatus = ProgressListing.page(snapshots, new ProgressListing.Filter(null, Set.of(ProgressStatus.HIDDEN), ""), 0);
        var entries = byStatus.items().stream().map(ProgressListing.Item::entry).filter(e -> e != null).toList();
        assertEquals(List.of(hidden), entries);
        assertNull(entries.get(0).handle().title());
        var visibleByText = ProgressListing.page(snapshots, new ProgressListing.Filter(null, Set.of(), "dreams"), 0);
        assertEquals(List.of(visible), visibleByText.items().stream().map(ProgressListing.Item::entry).filter(e -> e != null).toList());
    }

    @Test
    void treeFilterAcceptsABareLocalIdAndListsOnlyThatTree() {
        var story = new ProgressId("minecraft", "minecraft:story/root");
        var nether = new ProgressId("minecraft", "minecraft:nether/root");
        var mineStone = entry(new ProgressId("minecraft", "minecraft:story/mine_stone"), story, "Stone Age", ProgressStatus.DONE);
        var hotStuff = entry(new ProgressId("minecraft", "minecraft:nether/return_to_sender"), nether, "Return to Sender", ProgressStatus.LOCKED);
        var snapshots = List.of(new ProgressSnapshot(List.of(new ProgressTree(story, "Minecraft", 1, 1, true), new ProgressTree(nether, "Nether", 0, 1, true)),
                List.of(mineStone, hotStuff), null));
        var page = ProgressListing.page(snapshots, new ProgressListing.Filter(ProgressId.parse("minecraft:story/root"), Set.of(), null), 0);
        assertEquals(2, page.items().size());
        assertEquals(story, page.items().get(0).tree().id());
        assertEquals(mineStone, page.items().get(1).entry());
        var none = ProgressListing.page(snapshots, new ProgressListing.Filter(new ProgressId("other", "minecraft:story/root"), Set.of(), null), 0);
        assertTrue(none.items().isEmpty());
        assertEquals(Evidence.Coverage.COMPLETE, none.coverage());
        assertEquals("no progress entry matches", none.note());
    }

    @Test
    void aPartialSnapshotMakesThePagePartialWithItsNote() {
        var page = ProgressListing.page(List.of(snapshot("a", 1, 2, "the advancements were reloaded"), snapshot("b", 1, 2, null),
                snapshot("c", 1, 2, "quests are still loading")), ALL, 0);
        assertEquals(9, page.items().size());
        assertEquals(Evidence.Coverage.PARTIAL, page.coverage());
        assertEquals("the advancements were reloaded; quests are still loading", page.note());
    }

    @Test
    void anEmptyPartialListingKeepsTheSnapshotNote() {
        var page = ProgressListing.page(List.of(new ProgressSnapshot(List.of(), List.of(), "the advancements were reloaded")), ALL, 0);
        assertTrue(page.items().isEmpty());
        assertEquals(Evidence.Coverage.PARTIAL, page.coverage());
        assertEquals("no progress entry matches; the advancements were reloaded", page.note());
    }

    private static ProgressSnapshot snapshot(String adapter, int trees, int entries, String note) {
        var treeList = new ArrayList<ProgressTree>();
        for (var i = 0; i < trees; i++) {
            treeList.add(new ProgressTree(new ProgressId(adapter, "t" + i), "Tree " + i, 0, entries, true));
        }
        var entryList = new ArrayList<ProgressEntry>();
        for (var i = 0; i < entries; i++) {
            entryList.add(entry(new ProgressId(adapter, "e" + i), treeList.get(i % trees).id(), "Entry " + i, ProgressStatus.AVAILABLE));
        }
        return new ProgressSnapshot(treeList, entryList, note);
    }

    private static ProgressEntry entry(ProgressId id, ProgressId tree, String title, ProgressStatus status) {
        return new ProgressEntry(new ProgressHandle(id, tree, title, status, true), "About " + title);
    }
}
