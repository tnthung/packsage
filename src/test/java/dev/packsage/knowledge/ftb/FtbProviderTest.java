package dev.packsage.knowledge.ftb;

import dev.packsage.knowledge.Evidence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FtbProviderTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "ftb-1", 1);
    private static final String LARGE_ID = "7fffffffffffffff";

    @Test
    void preservesFullWidthQuestIdDependenciesAndSeparateProgressOverlay() throws Exception {
        var quest = new FtbProvider.QuestRecord(LARGE_ID, "Target", "chapter", List.of("1", "2"),
                List.of(List.of("1"), List.of("2")), 1,
                List.of(new FtbProvider.TaskRecord("task", "unknown:task", 2, 4L, false)),
                List.of(new FtbProvider.RewardRecord("reward", "unknown:reward", false)));
        var provider = new FtbProvider(() -> new FtbProvider.Snapshot(SCOPE, 1, List.of(quest),
                List.of(new FtbProvider.Progress(LARGE_ID, true, false, false, quest.tasks(), quest.rewards())), ignored -> { }), Runnable::run);
        provider.refresh();

        var result = provider.query(new Evidence.Query(Evidence.Operation.GET,
                new Evidence.JsonObject(Map.of("id", new Evidence.JsonString(LARGE_ID))), 10, null, null), SCOPE)
                .toCompletableFuture().get();

        assertEquals(LARGE_ID, result.records().get(0).source().sourceHandle());
        assertEquals(LARGE_ID, ((Evidence.JsonString) result.records().get(0).payload().values().get("questId")).value());
        assertEquals(Evidence.Coverage.COMPLETE, result.coverage());
        assertEquals(false, ((Evidence.JsonBoolean) result.records().get(0).payload().values().get("completed")).value());
    }

    @Test
    void staleScopeAndMissingProgressAreVisibleWithoutInventingCompletion() throws Exception {
        var quest = new FtbProvider.QuestRecord("abcd", "Opaque", "chapter", List.of(), List.of(), 0, List.of(), List.of());
        var provider = new FtbProvider(() -> new FtbProvider.Snapshot(SCOPE, 1, List.of(quest), List.of(), ignored -> { }), Runnable::run);
        provider.refresh();

        var result = provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("opaque"))), 10, null, null), SCOPE)
                .toCompletableFuture().get();
        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        assertTrue(result.records().get(0).payload().values().get("completed") instanceof Evidence.JsonNull);
        assertEquals(Evidence.NavigationStatus.STALE, provider.open(
                new Evidence.NavigationTarget(SCOPE, FtbProvider.ID, "abcd", "abcd"),
                new Evidence.Scope("other", "en_us", "team", "ftb-1", 1)).status());
    }

    @Test
    void navigationUsesQuestIdAndCurrentScope() {
        var opened = new AtomicReference<String>();
        var quest = new FtbProvider.QuestRecord("abcd", "Opaque", "chapter", List.of(), List.of(), 0, List.of(), List.of());
        var provider = new FtbProvider(() -> new FtbProvider.Snapshot(SCOPE, 1, List.of(quest), List.of(), opened::set), Runnable::run);
        provider.refresh();
        assertEquals(Evidence.NavigationStatus.OPENED, provider.open(
                new Evidence.NavigationTarget(SCOPE, FtbProvider.ID, "abcd", "abcd"), SCOPE).status());
        assertEquals("abcd", opened.get());
    }
}
