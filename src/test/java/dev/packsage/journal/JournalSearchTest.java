package dev.packsage.journal;

import dev.packsage.knowledge.Evidence;
import dev.packsage.world.BlockPlace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JournalSearchTest {
    @TempDir
    Path dir;

    @Test
    void newestFirstSixtyFourPerPageWithACursorThatContinues() throws Exception {
        try (var store = JournalStore.open(dir.resolve("journal.db"))) {
            store.startSession(0, 0);
            var runs = new ArrayList<Run>();
            for (int i = 1; i <= 150; i++) {
                runs.add(new Run(new JournalEvent(EventType.DEATH, i, i, "minecraft:overworld", "environment",
                        "minecraft:villager", null, 1, new long[]{Positions.pack(0, 64, 0)}, null)));
            }
            store.writeBatch(runs, List.of());
            var search = new JournalSearch(store);
            var filter = new JournalFilter(null, null, null, null, Set.of(), List.of(), null, false);
            var first = search.search(filter, null, 200);
            var second = search.search(filter, first.nextCursor(), 200);
            var third = search.search(filter, second.nextCursor(), 200);
            assertEquals(64, first.items().size());
            assertEquals(150, first.items().getFirst().sortWall());
            assertEquals("87:e000000000087", first.nextCursor());
            assertEquals(64, second.items().size());
            assertEquals(22, third.items().size());
            assertEquals(Evidence.Coverage.PARTIAL, first.coverage());
            assertEquals(Evidence.Coverage.PARTIAL, second.coverage());
            assertEquals(Evidence.Coverage.COMPLETE, third.coverage());
            assertNull(third.nextCursor());
            var refs = new HashSet<String>();
            for (var page : List.of(first, second, third)) {
                for (var entry : page.items()) {
                    assertTrue(refs.add(entry.ref()));
                }
            }
            assertEquals(150, refs.size());
        }
    }

    @Test
    void aRunExtendedBetweenPagesIsNotRepeatedOrSkipped() throws Exception {
        try (var store = JournalStore.open(dir.resolve("journal.db"))) {
            store.startSession(0, 0);
            var runs = new ArrayList<Run>();
            for (int i = 1; i <= 100; i++) {
                runs.add(new Run(new JournalEvent(EventType.BREAK, i, i, "minecraft:overworld", "player:steve",
                        "minecraft:sand", null, 1, new long[]{Positions.pack(0, 64, 0)}, null)));
            }
            store.writeBatch(runs, List.of());
            var search = new JournalSearch(store);
            var filter = new JournalFilter(null, null, null, null, Set.of(), List.of(), null, false);
            var first = search.search(filter, null, 200);
            runs.get(30).add(new JournalEvent(EventType.BREAK, 101, 500, "minecraft:overworld", "player:steve",
                    "minecraft:sand", null, 1, new long[]{Positions.pack(1, 64, 0)}, null));
            store.writeBatch(List.of(runs.get(30)), List.of());
            var second = search.search(filter, first.nextCursor(), 600);
            var refs = new HashSet<String>();
            first.items().forEach(entry -> assertTrue(refs.add(entry.ref())));
            second.items().forEach(entry -> assertTrue(refs.add(entry.ref())));
            assertEquals(100, refs.size());
            assertEquals(2, ((JournalEntry.EventEntry) second.items().get(5)).count());
        }
    }

    @Test
    void areaUsesTheBoxThenExactPositions() throws Exception {
        try (var store = JournalStore.open(dir.resolve("journal.db"))) {
            store.startSession(0, 0);
            store.writeBatch(List.of(new Run(new JournalEvent(EventType.BREAK, 1, 1, "minecraft:overworld", "player:steve",
                    "minecraft:sand", null, 3, new long[]{Positions.pack(0, 64, 10), Positions.pack(10, 64, 10),
                    Positions.pack(10, 64, 0)}, null))), List.of());
            var search = new JournalSearch(store);
            assertTrue(search.search(new JournalFilter(null, null, new JournalFilter.Area("minecraft:overworld", 0, 64, 0, 8),
                    null, Set.of(), List.of(), null, false), null, 10).items().isEmpty());
            var result = search.search(new JournalFilter(null, null, new JournalFilter.Area("minecraft:overworld", 0, 64, 0, 12),
                    null, Set.of(), List.of(), null, false), null, 10);
            var entry = (JournalEntry.EventEntry) result.items().getFirst();
            assertEquals(2, entry.inAreaCount());
            assertEquals(List.of(new BlockPlace("minecraft:overworld", 0, 64, 10),
                    new BlockPlace("minecraft:overworld", 10, 64, 0)), entry.inArea());
        }
    }

    @Test
    void filtersByTypeSubjectDimensionAndText() throws Exception {
        try (var store = JournalStore.open(dir.resolve("journal.db"))) {
            store.startSession(0, 0);
            store.writeBatch(List.of(new Run(new JournalEvent(EventType.DEATH, 1, 1, "minecraft:overworld", "environment",
                    "minecraft:villager", null, 1, new long[]{Positions.pack(0, 64, 0)}, "minecraft:zombie")),
                    new Run(new JournalEvent(EventType.KILL, 2, 2, "minecraft:overworld", "player:steve",
                            "minecraft:villager", null, 1, new long[]{Positions.pack(0, 64, 0)}, "minecraft:zombie"))), List.of());
            var search = new JournalSearch(store);
            assertEquals(1, search.search(new JournalFilter(null, null, null, "minecraft:overworld", Set.of("death"),
                    List.of("minecraft:villager"), "ZOMBIE", false), null, 10).items().size());
            assertTrue(search.search(new JournalFilter(null, null, null, "minecraft:the_nether", Set.of("death"),
                    List.of("minecraft:villager"), "zombie", false), null, 10).items().isEmpty());
            assertTrue(search.search(new JournalFilter(null, null, null, null, Set.of("death"),
                    List.of("villager"), null, false), null, 10).items().isEmpty());
        }
    }

    @Test
    void notesAndEventsInterleaveByTime() throws Exception {
        try (var store = JournalStore.open(dir.resolve("journal.db"))) {
            store.startSession(0, 0);
            store.writeBatch(List.of(new Run(new JournalEvent(EventType.BREAK, 10, 10, "minecraft:overworld", "player:steve",
                    "minecraft:sand", null, 1, new long[]{Positions.pack(0, 64, 0)}, null))), List.of());
            try (var statement = store.writer().createStatement()) {
                statement.executeUpdate("INSERT INTO notes VALUES ('n00000001', 'farm', 'built a farm', 'build',"
                        + " 'minecraft:overworld', 0, 64, 0, 10, 10, 30, 'chat', 'request')");
                statement.executeUpdate("INSERT INTO notes VALUES ('n00000002', 'base', NULL, '',"
                        + " NULL, NULL, NULL, NULL, 5, 5, 40, NULL, NULL)");
            }
            var result = new JournalSearch(store).search(new JournalFilter(null, null, null, null,
                    Set.of(), List.of(), null, false), null, 100);
            assertEquals(List.of("nn00000001", "e000000000001", "nn00000002"),
                    result.items().stream().map(JournalEntry::ref).toList());
            var note = ((JournalEntry.NoteEntry) result.items().getFirst()).note();
            assertEquals(new Note("n00000001", "farm", "built a farm", List.of("build"),
                    new BlockPlace("minecraft:overworld", 0, 64, 0), 10, 10, 30, "chat", "request"), note);
        }
    }

    @Test
    void notesOnlyReturnsOnlyNotes() throws Exception {
        try (var store = JournalStore.open(dir.resolve("journal.db"))) {
            store.startSession(0, 0);
            store.writeBatch(List.of(new Run(new JournalEvent(EventType.BREAK, 1, 1, "minecraft:overworld", "player:steve",
                    "minecraft:sand", null, 1, new long[]{Positions.pack(0, 64, 0)}, null))), List.of());
            try (var statement = store.writer().createStatement()) {
                statement.executeUpdate("INSERT INTO notes(id, text, tags, created_wall, created_game_time, updated_wall)"
                        + " VALUES ('n00000001', 'farm', '', 2, 2, 2)");
            }
            var filter = new JournalFilter(null, null, null, null, Set.of("note"), List.of(), null, false);
            assertTrue(filter.notesOnly());
            var result = new JournalSearch(store).search(filter, null, 100);
            assertEquals(1, result.items().size());
            assertInstanceOf(JournalEntry.NoteEntry.class, result.items().getFirst());
        }
    }

    @Test
    void rangeBeforeRecordingOrOverAGapIsPartial() throws Exception {
        try (var store = JournalStore.open(dir.resolve("journal.db"))) {
            var session = store.startSession(1_000, 0);
            store.endSession(session, 2_000, 100);
            store.startSession(5_000, 10_100);
            var search = new JournalSearch(store);
            var gap = search.search(new JournalFilter(1_000L, 6_000L, null, null, Set.of(), List.of(), null, false), null, 6_000);
            assertEquals(Evidence.Coverage.PARTIAL, gap.coverage());
            assertTrue(gap.note().contains("the world ran without recording between 1970-01-01T00:00:02Z and 1970-01-01T00:00:05Z"));
            var early = search.search(new JournalFilter(0L, 1_500L, null, null, Set.of(), List.of(), null, false), null, 6_000);
            assertEquals(Evidence.Coverage.PARTIAL, early.coverage());
            assertTrue(early.note().contains("the journal began recording at 1970-01-01T00:00:01Z"));
            assertEquals(Evidence.Coverage.COMPLETE, search.search(new JournalFilter(5_000L, 6_000L, null,
                    null, Set.of(), List.of(), null, false), null, 6_000).coverage());
        }
    }

    @Test
    void anUnendedSessionUsesItsLastEventToDetectAGap() throws Exception {
        try (var store = JournalStore.open(dir.resolve("journal.db"))) {
            store.startSession(1_000, 0);
            store.writeBatch(List.of(new Run(new JournalEvent(EventType.BREAK, 100, 2_000, "minecraft:overworld", "player:steve",
                    "minecraft:sand", null, 1, new long[]{Positions.pack(0, 64, 0)}, null))), List.of());
            store.startSession(5_000, 10_100);
            var result = new JournalSearch(store).search(new JournalFilter(2_500L, 4_000L, null, null,
                    Set.of(), List.of(), null, false), null, 6_000);
            assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
            assertTrue(result.note().contains("between 1970-01-01T00:00:02Z and 1970-01-01T00:00:05Z"));
        }
    }

    @Test
    void countOnlyRowsMakeAreaSearchesPartial() throws Exception {
        try (var store = JournalStore.open(dir.resolve("journal.db"))) {
            store.startSession(0, 0);
            store.writeBatch(List.of(), List.of(new OverflowRow(EventType.BREAK, "minecraft:overworld", "player:steve",
                    "minecraft:sand", 20, 20, 1, 2, 1, 2)));
            var search = new JournalSearch(store);
            var result = search.search(new JournalFilter(null, null, new JournalFilter.Area("minecraft:overworld", 0, 64, 0, 8),
                    null, Set.of("break"), List.of("minecraft:sand"), null, false), null, 10);
            assertTrue(result.items().isEmpty());
            assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
            assertTrue(result.note().contains("1 count-only rows in this period have no positions; the area cannot rule them out"));
            var all = search.search(new JournalFilter(null, null, null, null, Set.of(), List.of(), null, false), null, 10);
            assertTrue(((JournalEntry.EventEntry) all.items().getFirst()).countOnly());
            assertNull(((JournalEntry.EventEntry) all.items().getFirst()).at());
            assertEquals(Evidence.Coverage.COMPLETE, all.coverage());
            assertEquals(Evidence.Coverage.COMPLETE, search.search(new JournalFilter(null, null,
                    new JournalFilter.Area("minecraft:the_nether", 0, 64, 0, 8), null, Set.of(), List.of(), null, false), null, 10).coverage());
        }
    }

    @Test
    void aFreshStoreAndFailedEventsExplainPartialCoverage() throws Exception {
        try (var store = JournalStore.open(dir.resolve("journal.db"))) {
            var filter = new JournalFilter(null, null, null, null, Set.of(), List.of(), null, false);
            var fresh = new JournalSearch(store).search(filter, null, 10);
            assertEquals(Evidence.Coverage.PARTIAL, fresh.coverage());
            assertTrue(fresh.note().contains("not recording yet"));
            store.startSession(0, 0);
            var stats = new JournalStats();
            stats.failedEvents.add(3);
            var failed = new JournalSearch(store, stats).search(filter, null, 10);
            assertEquals(Evidence.Coverage.PARTIAL, failed.coverage());
            assertTrue(failed.note().contains("3"));
            assertTrue(failed.note().contains("failed"));
            assertEquals(Evidence.Coverage.COMPLETE, new JournalSearch(store).search(filter, null, 10).coverage());
        }
    }

    @Test
    void coarsenedOverflowCannotRuleOutASpecificSubjectInAnArea() throws Exception {
        try (var store = JournalStore.open(dir.resolve("journal.db"))) {
            store.startSession(0, 0);
            store.writeBatch(List.of(), List.of(new OverflowRow(EventType.BREAK, "minecraft:overworld", "*", "*",
                    20, 20, 1, 2, 1, 2)));
            var result = new JournalSearch(store).search(new JournalFilter(null, null,
                    new JournalFilter.Area("minecraft:overworld", 0, 64, 0, 8), null, Set.of("break"),
                    List.of("minecraft:sand"), null, false), null, 10);
            assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
            assertTrue(result.note().contains("1 count-only rows"));
        }
    }

    @Test
    void candidateLimitAdvancesPastNonmatchingRuns() throws Exception {
        try (var store = JournalStore.open(dir.resolve("journal.db"))) {
            store.startSession(0, 0);
            var runs = new ArrayList<Run>();
            runs.add(new Run(new JournalEvent(EventType.BREAK, 1, 1, "minecraft:overworld", "player:steve",
                    "minecraft:sand", null, 1, new long[]{Positions.pack(0, 64, 0)}, null)));
            for (int i = 2; i <= 4_097; i++) {
                runs.add(new Run(new JournalEvent(EventType.BREAK, i, i, "minecraft:overworld", "player:steve",
                        "minecraft:sand", null, 2, new long[]{Positions.pack(0, 64, 10), Positions.pack(10, 64, 0)}, null)));
            }
            store.writeBatch(runs, List.of());
            var search = new JournalSearch(store);
            var filter = new JournalFilter(null, null, new JournalFilter.Area("minecraft:overworld", 0, 64, 0, 8),
                    null, Set.of(), List.of(), null, false);
            var first = search.search(filter, null, 5_000);
            assertTrue(first.items().isEmpty());
            assertEquals(Evidence.Coverage.PARTIAL, first.coverage());
            assertEquals("2:e000000000002", first.nextCursor());
            assertTrue(first.note().contains("examined 4,096 candidate runs; continue with the cursor"));
            var second = search.search(filter, first.nextCursor(), 5_000);
            assertEquals(1, second.items().size());
            assertEquals(Evidence.Coverage.COMPLETE, second.coverage());
        }
    }

    @Test
    void textEscapesWildcardsAndSearchesNoteWordsAndTags() throws Exception {
        try (var store = JournalStore.open(dir.resolve("journal.db"))) {
            store.startSession(0, 0);
            try (var statement = store.writer().createStatement()) {
                statement.executeUpdate("INSERT INTO notes(id, text, player_words, tags, created_wall, created_game_time, updated_wall)"
                        + " VALUES ('n00000001', 'farm', '100%_done', 'build', 1, 1, 1),"
                        + " ('n00000002', 'base', '100xxdone', 'home', 2, 2, 2)");
            }
            var search = new JournalSearch(store);
            for (var text : List.of("%_", "BUILD", "farm")) {
                var result = search.search(new JournalFilter(null, null, null, null, Set.of("note"), List.of(), text, true), null, 10);
                assertEquals(List.of("nn00000001"), result.items().stream().map(JournalEntry::ref).toList());
            }
        }
    }

    @Test
    void eventTimeOverlapsAndAreaPositionsAreCappedAtSixteen() throws Exception {
        try (var store = JournalStore.open(dir.resolve("journal.db"))) {
            store.startSession(0, 0);
            var run = new Run(new JournalEvent(EventType.BREAK, 1, 1, "minecraft:overworld", "player:steve",
                    "minecraft:sand", null, 1, new long[]{Positions.pack(0, 64, 0)}, null));
            for (int i = 1; i < 20; i++) {
                run.add(new JournalEvent(EventType.BREAK, i + 1, i + 1, "minecraft:overworld", "player:steve",
                        "minecraft:sand", null, 1, new long[]{Positions.pack(i, 64, 0)}, null));
            }
            store.writeBatch(List.of(run), List.of());
            var result = new JournalSearch(store).search(new JournalFilter(10L, 15L,
                    new JournalFilter.Area("minecraft:overworld", 0, 64, 0, 20), null, Set.of(), List.of(), null, false), null, 100);
            var entry = (JournalEntry.EventEntry) result.items().getFirst();
            assertEquals(20, entry.inAreaCount());
            assertEquals(16, entry.inArea().size());
        }
    }

    @Test
    void coarsenedOverflowMakesSubjectAndTextSearchesPartialWithoutAnArea() throws Exception {
        try (var store = JournalStore.open(dir.resolve("journal.db"))) {
            store.startSession(0, 0);
            store.writeBatch(List.of(), List.of(new OverflowRow(EventType.BREAK, "minecraft:overworld", "*", "*",
                    20, 20, 1, 2, 1, 2), new OverflowRow(EventType.BREAK, "minecraft:overworld", "*", "minecraft:stone",
                    10, 10, 1, 2, 1, 2)));
            var search = new JournalSearch(store);
            for (var filter : List.of(
                    new JournalFilter(null, null, null, "minecraft:overworld", Set.of("break"), List.of("minecraft:sand"), null, false),
                    new JournalFilter(null, null, null, "minecraft:overworld", Set.of("break"), List.of(), "Steve", false))) {
                var result = search.search(filter, null, 10);
                assertTrue(result.items().isEmpty());
                assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
                assertTrue(result.note().contains((filter.subjects().isEmpty() ? "2" : "1")
                        + " overflow rows without a recorded subject or actor in this period cannot be ruled out"));
                assertNull(result.nextCursor());
            }
            for (var filter : List.of(
                    new JournalFilter(3L, null, null, null, Set.of(), List.of("minecraft:sand"), null, false),
                    new JournalFilter(null, 0L, null, null, Set.of(), List.of(), "Steve", false),
                    new JournalFilter(null, null, null, "minecraft:the_nether", Set.of(), List.of("minecraft:sand"), null, false),
                    new JournalFilter(null, null, null, null, Set.of("death"), List.of(), "Steve", false),
                    new JournalFilter(null, null, null, null, Set.of("note"), List.of(), "Steve", true),
                    new JournalFilter(null, null, null, null, Set.of(), List.of(), null, false))) {
                assertEquals(Evidence.Coverage.COMPLETE, search.search(filter, null, 10).coverage());
            }
        }
    }

    @Test
    void actorOnlyOverflowMakesACombinedSubjectAndTextSearchPartial() throws Exception {
        try (var store = JournalStore.open(dir.resolve("journal.db"))) {
            store.startSession(0, 0);
            store.writeBatch(List.of(), List.of(new OverflowRow(EventType.BREAK, "minecraft:overworld", "*", "minecraft:sand",
                    10, 10, 1, 2, 1, 2)));
            var result = new JournalSearch(store).search(new JournalFilter(null, null, null, null,
                    Set.of("break"), List.of("minecraft:sand"), "Steve", false), null, 10);
            assertTrue(result.items().isEmpty());
            assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
            assertTrue(result.note().contains("1 overflow rows without a recorded subject or actor in this period cannot be ruled out"));
            assertNull(result.nextCursor());
        }
    }

    @Test
    void actorOnlyOverflowDoesNotMakeAnUnrelatedSubjectSearchPartial() throws Exception {
        try (var store = JournalStore.open(dir.resolve("journal.db"))) {
            store.startSession(0, 0);
            store.writeBatch(List.of(), List.of(new OverflowRow(EventType.BREAK, "minecraft:overworld", "*", "minecraft:stone",
                    10, 10, 1, 2, 1, 2)));
            var result = new JournalSearch(store).search(new JournalFilter(null, null, null, null,
                    Set.of("break"), List.of("minecraft:sand"), null, false), null, 10);
            assertTrue(result.items().isEmpty());
            assertEquals(Evidence.Coverage.COMPLETE, result.coverage());
            assertNull(result.note());
        }
    }

    @Test
    void anUnboundedRangeReportsHistoryBeforeRecordingAfterTheTickTolerance() throws Exception {
        for (var gameTime : List.of(200L, 201L)) {
            try (var store = JournalStore.open(dir.resolve("journal-" + gameTime + ".db"))) {
                store.startSession(1_000, gameTime);
                var search = new JournalSearch(store);
                var result = search.search(new JournalFilter(null, null, null, null, Set.of(), List.of(), null, false), null, 2_000);
                assertEquals(gameTime == 200 ? Evidence.Coverage.COMPLETE : Evidence.Coverage.PARTIAL, result.coverage());
                if (gameTime > 200) {
                    assertTrue(result.note().contains("the journal began recording at 1970-01-01T00:00:01Z"));
                }
                assertEquals(Evidence.Coverage.COMPLETE, search.search(new JournalFilter(1_000L, null, null, null,
                        Set.of(), List.of(), null, false), null, 2_000).coverage());
            }
        }
    }

    @Test
    void exhaustedStreamsAtTheCandidateLimitAreComplete() throws Exception {
        try (var store = JournalStore.open(dir.resolve("journal.db"))) {
            store.startSession(0, 0);
            var runs = new ArrayList<Run>();
            for (int i = 1; i <= 4_096; i++) {
                runs.add(new Run(new JournalEvent(EventType.BREAK, i, i, "minecraft:overworld", "player:steve",
                        "minecraft:sand", null, 2, new long[]{Positions.pack(0, 64, 10), Positions.pack(10, 64, 0)}, null)));
            }
            store.writeBatch(runs, List.of());
            var result = new JournalSearch(store).search(new JournalFilter(null, null,
                    new JournalFilter.Area("minecraft:overworld", 0, 64, 0, 8), null, Set.of(), List.of(), null, false), null, 5_000);
            assertTrue(result.items().isEmpty());
            assertEquals(Evidence.Coverage.COMPLETE, result.coverage());
            assertNull(result.nextCursor());
            assertNull(result.note());
        }
    }

    @Test
    void areaRejectsNegativeRadii() {
        assertThrows(IllegalArgumentException.class, () -> new JournalFilter.Area("minecraft:overworld", 0, 64, 0, -1));
    }

    @Test
    void areaIncludesPositionsExactlyAtTheRadius() throws Exception {
        try (var store = JournalStore.open(dir.resolve("journal.db"))) {
            store.startSession(0, 0);
            store.writeBatch(List.of(new Run(new JournalEvent(EventType.BREAK, 1, 1, "minecraft:overworld", "player:steve",
                    "minecraft:sand", null, 2, new long[]{Positions.pack(3, 64, 4), Positions.pack(3, 64, 5)}, null))), List.of());
            var result = new JournalSearch(store).search(new JournalFilter(null, null,
                    new JournalFilter.Area("minecraft:overworld", 0, 64, 0, 5), null, Set.of(), List.of(), null, false), null, 10);
            var entry = (JournalEntry.EventEntry) result.items().getFirst();
            assertEquals(1, entry.inAreaCount());
            assertEquals(List.of(new BlockPlace("minecraft:overworld", 3, 64, 4)), entry.inArea());
        }
    }

    @Test
    void equalTimeNotesAndEventsContinueAcrossEitherStreamBoundary() throws Exception {
        try (var store = JournalStore.open(dir.resolve("journal.db"))) {
            store.startSession(0, 0);
            var runs = new ArrayList<Run>();
            for (int i = 1; i <= 65; i++) {
                runs.add(new Run(new JournalEvent(EventType.DEATH, 1, 10, "minecraft:overworld", "environment",
                        "minecraft:villager", null, 1, new long[]{Positions.pack(0, 64, 0)}, null)));
            }
            store.writeBatch(runs, List.of());
            try (var statement = store.writer().createStatement()) {
                for (int i = 1; i <= 64; i++) {
                    statement.executeUpdate("INSERT INTO notes(id, text, tags, created_wall, created_game_time, updated_wall)"
                            + " VALUES ('n" + String.format("%08d", i) + "', 'farm', '', 10, 1, 10)");
                }
            }
            var search = new JournalSearch(store);
            var filter = new JournalFilter(null, null, null, null, Set.of(), List.of(), null, false);
            var first = search.search(filter, null, 20);
            var second = search.search(filter, first.nextCursor(), 20);
            var third = search.search(filter, second.nextCursor(), 20);
            assertEquals(64, first.items().size());
            assertTrue(first.items().stream().allMatch(JournalEntry.NoteEntry.class::isInstance));
            assertEquals("10:nn00000001", first.nextCursor());
            assertEquals(64, second.items().size());
            assertEquals("10:e000000000002", second.nextCursor());
            assertEquals(List.of("e000000000001"), third.items().stream().map(JournalEntry::ref).toList());
            assertEquals(Evidence.Coverage.COMPLETE, third.coverage());
            var refs = new HashSet<String>();
            for (var page : List.of(first, second, third)) {
                for (var entry : page.items()) {
                    assertTrue(refs.add(entry.ref()));
                }
            }
            assertEquals(129, refs.size());
        }
    }

    @Test
    void eventIdsOrderNumericallyBeyondTwelveDigitsWithTheExistingCursorFormat() throws Exception {
        try (var store = JournalStore.open(dir.resolve("journal.db"))) {
            store.startSession(0, 0);
            store.writeBatch(List.of(new Run(new JournalEvent(EventType.DEATH, 1, 10, "minecraft:overworld", "environment",
                    "minecraft:villager", null, 1, new long[]{Positions.pack(0, 64, 0)}, null)),
                    new Run(new JournalEvent(EventType.DEATH, 1, 10, "minecraft:overworld", "environment",
                            "minecraft:villager", null, 1, new long[]{Positions.pack(0, 64, 0)}, null))), List.of());
            try (var statement = store.writer().createStatement()) {
                statement.executeUpdate("UPDATE events SET id = 999999999999 WHERE id = 1");
                statement.executeUpdate("UPDATE events SET id = 1000000000000 WHERE id = 2");
            }
            var search = new JournalSearch(store);
            var filter = new JournalFilter(null, null, null, null, Set.of(), List.of(), null, false);
            assertEquals(List.of("e1000000000000", "e999999999999"),
                    search.search(filter, null, 20).items().stream().map(JournalEntry::ref).toList());
            assertEquals(List.of("e999999999999"),
                    search.search(filter, "10:e1000000000000", 20).items().stream().map(JournalEntry::ref).toList());
        }
    }

    @Test
    void eventCursorsKeepAsciiDigitsRegardlessOfTheDefaultLocale() throws Exception {
        var previous = Locale.getDefault();
        try (var store = JournalStore.open(dir.resolve("journal.db"))) {
            store.startSession(0, 0);
            var runs = new ArrayList<Run>();
            for (int i = 1; i <= 65; i++) {
                runs.add(new Run(new JournalEvent(EventType.DEATH, i, i, "minecraft:overworld", "environment",
                        "minecraft:villager", null, 1, new long[]{Positions.pack(0, 64, 0)}, null)));
            }
            store.writeBatch(runs, List.of());
            Locale.setDefault(Locale.forLanguageTag("ar"));
            var search = new JournalSearch(store);
            var filter = new JournalFilter(null, null, null, null, Set.of(), List.of(), null, false);
            var first = search.search(filter, null, 100);
            assertEquals("2:e000000000002", first.nextCursor());
            assertEquals(List.of("e000000000001"),
                    search.search(filter, first.nextCursor(), 100).items().stream().map(JournalEntry::ref).toList());
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void searchesOnAnotherThreadUsingTheReaderConnection() throws Exception {
        try (var store = JournalStore.open(dir.resolve("journal.db")); var worker = Executors.newSingleThreadExecutor()) {
            store.startSession(0, 0);
            try (var statement = store.writer().createStatement()) {
                store.writer().setAutoCommit(false);
                statement.executeUpdate("INSERT INTO notes(id, text, tags, created_wall, created_game_time, updated_wall)"
                        + " VALUES ('n00000001', 'uncommitted', '', 1, 1, 1)");
                var result = worker.submit(() -> new JournalSearch(store).search(new JournalFilter(null, null, null, null,
                        Set.of(), List.of(), null, false), null, 10)).get();
                assertTrue(result.items().isEmpty());
                store.writer().rollback();
                store.writer().setAutoCommit(true);
            }
            assertEquals(42, worker.submit(() -> store.read(connection -> {
                assertSame(store.reader(), connection);
                assertTrue(Thread.holdsLock(connection));
                return 42;
            })).get());
        }
    }
}
