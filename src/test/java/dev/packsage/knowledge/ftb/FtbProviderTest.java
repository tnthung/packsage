package dev.packsage.knowledge.ftb;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FtbProviderTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "ftb-1", 1);
    private static final String LARGE_ID = "FFFFFFFFFFFFFFFF";

    @Test
    void preservesFullWidthQuestIdDependenciesAndSeparateProgressOverlay() throws Exception {
        var quest = new FtbProvider.QuestRecord(LARGE_ID, "Target", "chapter", List.of("1", "2"),
                List.of(List.of("1"), List.of("2")), 1,
                List.of(new FtbProvider.TaskRecord("task", "unknown:task", 2, 4L, false)),
                List.of(new FtbProvider.RewardRecord("reward", "unknown:reward", false)));
        var opened = new AtomicReference<String>();
        var provider = new FtbProvider(() -> new FtbProvider.Snapshot(SCOPE, 1, List.of(quest),
                List.of(new FtbProvider.Progress(LARGE_ID, true, false, false, quest.tasks(), quest.rewards())),
                opened::set), Runnable::run);
        provider.refresh();

        var result = provider.query(new Evidence.Query(Evidence.Operation.GET,
                new Evidence.JsonObject(Map.of("id", new Evidence.JsonString(LARGE_ID))), 10, null, null), SCOPE)
                .toCompletableFuture().get();

        assertEquals(LARGE_ID, result.records().get(0).source().sourceHandle());
        assertEquals(LARGE_ID, ((Evidence.JsonString) result.records().get(0).payload().values().get("questId")).value());
        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        assertEquals(false, ((Evidence.JsonBoolean) result.records().get(0).payload().values().get("completed")).value());
        assertEquals(Evidence.NavigationStatus.OPENED, provider.open(
                new Evidence.NavigationTarget(SCOPE, FtbProvider.ID, LARGE_ID, LARGE_ID), SCOPE).status());
        assertEquals(LARGE_ID, opened.get());
    }

    @Test
    void lockedOrMissingProgressIsUnknown() throws Exception {
        var quest = quest("locked", "Locked", List.of());
        var task = new FtbProvider.TaskRecord("task", "ftbquests:item", null, 4L, null, "minecraft:diamond", 4L, true);
        var reward = new FtbProvider.RewardRecord("reward", "ftbquests:item", null, "minecraft:emerald", 2L, true);
        quest = new FtbProvider.QuestRecord(quest.id(), quest.title(), quest.chapter(), quest.dependencies(),
                quest.dependencyBranches(), quest.minimumDependencies(), List.of(task), List.of(reward),
                quest.dependencyRequirement(), quest.maximumCompletableDependencies(), quest.rawTitle());
        var provider = provider(new FtbProvider.Snapshot(SCOPE, 1, List.of(quest),
                List.of(new FtbProvider.Progress(quest.id(), null, null, null, null, null,
                        List.of(task), List.of(reward))), ignored -> { }));

        var result = provider.query(search("locked", 10, null), SCOPE).toCompletableFuture().get();
        var payload = result.records().get(0).payload().values();
        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        assertTrue(payload.get("completed") instanceof Evidence.JsonNull);
        assertTrue(payload.get("rewardClaimed") instanceof Evidence.JsonNull);
        var taskPayload = (Evidence.JsonObject) ((Evidence.JsonArray) payload.get("tasks")).values().get(0);
        assertTrue(taskPayload.values().get("progress") instanceof Evidence.JsonNull);
        assertTrue(taskPayload.values().get("completed") instanceof Evidence.JsonNull);
        assertEquals(KnowledgeProvider.Availability.PARTIAL, provider.status().availability());
    }

    @Test
    void captureFailureIsAnErrorAndUnavailableDoesNotRecapture() throws Exception {
        var calls = new AtomicInteger();
        var provider = new FtbProvider(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("capture failed");
        }, Runnable::run);

        var error = provider.query(search("", 10, null), SCOPE).toCompletableFuture().get();
        assertEquals(Evidence.Coverage.ERROR, error.coverage());
        assertEquals(Evidence.Outcome.ERROR, error.outcome());
        assertEquals(KnowledgeProvider.Availability.ERROR, provider.status().availability());

        provider.unavailable();
        var unavailable = provider.query(search("", 10, null), SCOPE).toCompletableFuture().get();
        assertEquals(Evidence.Coverage.UNAVAILABLE, unavailable.coverage());
        assertEquals(1, calls.get());
    }

    @Test
    void missingClientFileIsUnavailableRatherThanAnError() throws Exception {
        var provider = new FtbProvider(() -> null, Runnable::run);
        var result = provider.query(search("", 10, null), SCOPE).toCompletableFuture().get();
        assertEquals(Evidence.Coverage.UNAVAILABLE, result.coverage());
        assertEquals(KnowledgeProvider.Availability.UNAVAILABLE, provider.status().availability());
    }

    @Test
    void oneBadRecordDoesNotAbortOtherRecords() throws Exception {
        var bad = new FtbProvider.QuestRecord("bad", "Bad", "chapter", List.of(), List.of(), 0,
                List.of(new FtbProvider.TaskRecord("task", "unknown:task", null, null, null)), List.of());
        var good = quest("good", "Good target", List.of());
        var provider = provider(new FtbProvider.Snapshot(SCOPE, 1, List.of(bad, good),
                List.of(progress(bad), progress(good)), ignored -> { }));

        var result = provider.query(search("good", 10, null), SCOPE).toCompletableFuture().get();
        assertEquals(1, result.records().size());
        assertEquals("good", result.records().get(0).source().sourceHandle());
        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
    }

    @Test
    void recognizedNonItemProgressKeepsConservativePartialCoverage() throws Exception {
        var task = new FtbProvider.TaskRecord("kill-task", "ftbquests:kill", 1L, 2L, false,
                null, null, true);
        var quest = new FtbProvider.QuestRecord("kill", "Kill", "chapter", List.of(), List.of(), 0,
                List.of(task), List.of());
        var provider = provider(new FtbProvider.Snapshot(SCOPE, 1, List.of(quest),
                List.of(new FtbProvider.Progress(quest.id(), true, true, true, false, false,
                        List.of(task), List.of())), ignored -> { }));

        var result = provider.query(search("kill", 10, null), SCOPE).toCompletableFuture().get();
        var payload = (Evidence.JsonObject) ((Evidence.JsonArray) result.records().get(0).payload().values()
                .get("tasks")).values().get(0);
        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        assertEquals(true, ((Evidence.JsonBoolean) payload.values().get("supported")).value());
        assertEquals(false, ((Evidence.JsonBoolean) payload.values().get("metadataComplete")).value());
    }

    @Test
    void chapterAndItemMetadataAreSearchable() throws Exception {
        var task = new FtbProvider.TaskRecord("item-task", "ftbquests:item", null, 8L, null,
                "minecraft:diamond", 8L, true);
        var reward = new FtbProvider.RewardRecord("item-reward", "ftbquests:item", null,
                "minecraft:emerald", 3L, true);
        var quest = new FtbProvider.QuestRecord("quest", "Quest", "chapter", List.of("dep-a", "dep-b"),
                List.of(List.of("dep-a"), List.of("dep-b")), 0, List.of(task), List.of(reward),
                "one_started", null, "Quest raw");
        var chapter = new FtbProvider.ChapterRecord("chapter", "Chapter title", "group", "Group title",
                List.of("quest"), true);
        var snapshot = new FtbProvider.Snapshot(SCOPE, 1, List.of(chapter), List.of(quest),
                List.of(progress(quest)), ignored -> { }, null, List.of());
        var provider = provider(snapshot);

        var chapterResult = provider.query(search("group title", 10, null), SCOPE).toCompletableFuture().get();
        assertEquals(1, chapterResult.records().size());
        assertEquals("chapter", chapterResult.records().get(0).source().sourceHandle());
        assertTrue(chapterResult.records().get(0).targets().isEmpty());

        var itemResult = provider.query(search("diamond", 10, null), SCOPE).toCompletableFuture().get();
        var payload = itemResult.records().get(0).payload().values();
        assertEquals("one_started", ((Evidence.JsonString) payload.get("dependencyRequirement")).value());
        assertEquals(1, ((Evidence.JsonNumber) payload.get("evaluatedMinimumDependencies")).value().intValue());
        var taskPayload = (Evidence.JsonObject) ((Evidence.JsonArray) payload.get("tasks")).values().get(0);
        var rewardPayload = (Evidence.JsonObject) ((Evidence.JsonArray) payload.get("rewards")).values().get(0);
        assertEquals("minecraft:diamond", ((Evidence.JsonString) taskPayload.values().get("item")).value());
        assertEquals(8, ((Evidence.JsonNumber) taskPayload.values().get("count")).value().intValue());
        assertEquals("minecraft:emerald", ((Evidence.JsonString) rewardPayload.values().get("item")).value());
        assertEquals(3, ((Evidence.JsonNumber) rewardPayload.values().get("count")).value().intValue());
    }

    @Test
    void filteredPaginationAdvancesPastSkippedEntries() throws Exception {
        var first = quest("first", "no match", List.of());
        var second = quest("second", "target", List.of());
        var third = quest("third", "target again", List.of());
        var provider = provider(new FtbProvider.Snapshot(SCOPE, 1, List.of(first, second, third),
                List.of(progress(first), progress(second), progress(third)), ignored -> { }));

        var firstPage = provider.query(search("target", 1, null), SCOPE).toCompletableFuture().get();
        assertEquals("second", firstPage.records().get(0).source().sourceHandle());
        assertEquals("2", firstPage.nextCursor());
        var secondPage = provider.query(search("target", 1, firstPage.nextCursor()), SCOPE).toCompletableFuture().get();
        assertEquals("third", secondPage.records().get(0).source().sourceHandle());
        assertNull(secondPage.nextCursor());

        var none = provider.query(search("missing", 1, null), SCOPE).toCompletableFuture().get();
        assertTrue(none.records().isEmpty());
        assertNull(none.nextCursor());
    }

    @Test
    void malformedReadsDoNotBroadenSearchOrRepeatCursor() throws Exception {
        var provider = provider(new FtbProvider.Snapshot(SCOPE, 1, List.of(quest("quest", "Quest", List.of())),
                List.of(progress(quest("quest", "Quest", List.of()))), ignored -> { }));
        var malformedGet = provider.query(new Evidence.Query(Evidence.Operation.GET,
                new Evidence.JsonObject(Map.of("id", new Evidence.JsonNumber(java.math.BigDecimal.ONE))), 10, null, null), SCOPE)
                .toCompletableFuture().get();
        assertEquals(Evidence.Coverage.ERROR, malformedGet.coverage());

        var malformedSearch = provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonBoolean(true))), 10, null, null), SCOPE)
                .toCompletableFuture().get();
        assertEquals(Evidence.Coverage.ERROR, malformedSearch.coverage());

        var malformedCursor = provider.query(search("quest", 1, "not-a-number"), SCOPE).toCompletableFuture().get();
        assertEquals(Evidence.Coverage.ERROR, malformedCursor.coverage());
    }

    @Test
    void staleScopeAndMissingProgressAreVisibleWithoutInventingCompletion() throws Exception {
        var quest = quest("abcd", "Opaque", List.of());
        var provider = provider(new FtbProvider.Snapshot(SCOPE, 1, List.of(quest), List.of(), ignored -> { }));

        var result = provider.query(search("opaque", 10, null), SCOPE).toCompletableFuture().get();
        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        assertTrue(result.records().get(0).payload().values().get("completed") instanceof Evidence.JsonNull);
        assertEquals(Evidence.NavigationStatus.STALE, provider.open(
                new Evidence.NavigationTarget(SCOPE, FtbProvider.ID, "abcd", "abcd"),
                new Evidence.Scope("other", "en_us", "team", "ftb-1", 1)).status());
    }

    @Test
    void navigationUsesQuestIdAndCurrentScope() {
        var opened = new AtomicReference<String>();
        var quest = quest("abcd", "Opaque", List.of());
        var provider = provider(new FtbProvider.Snapshot(SCOPE, 1, List.of(quest), List.of(progress(quest)), opened::set));
        assertEquals(Evidence.NavigationStatus.OPENED, provider.open(
                new Evidence.NavigationTarget(SCOPE, FtbProvider.ID, "abcd", "abcd"), SCOPE).status());
        assertEquals("abcd", opened.get());
        assertEquals(Evidence.NavigationStatus.STALE, provider.open(
                new Evidence.NavigationTarget(SCOPE, FtbProvider.ID, "wrong", "abcd"), SCOPE).status());
    }

    @Test
    void pinnedFixturesLoadIntoPartialLockedQuestEvidence() throws Exception {
        var questFixture = new String(FtbProviderTest.class
                .getResourceAsStream("/ftb/quest-file.snbt").readAllBytes(), StandardCharsets.UTF_8);
        var teamFixture = new String(FtbProviderTest.class
                .getResourceAsStream("/ftb/team-data-locked.snbt").readAllBytes(), StandardCharsets.UTF_8);
        var questLines = questFixture.lines().map(String::trim).toList();
        var teamLines = teamFixture.lines().map(String::trim).toList();
        var questIdLine = questLines.stream().filter(line -> line.equals("id: \"FFFFFFFFFFFFFFFF\"")).findFirst().orElseThrow();
        var questId = questIdLine.substring(questIdLine.indexOf('"') + 1, questIdLine.lastIndexOf('"'));
        var titleLine = questLines.stream().filter(line -> line.equals("title: \"High bit quest\"")).findFirst().orElseThrow();
        var title = titleLine.substring(titleLine.indexOf('"') + 1, titleLine.lastIndexOf('"'));
        var dependencyRequirementLine = questLines.stream()
                .filter(line -> line.startsWith("dependency_requirement:")).findFirst().orElseThrow();
        var dependencyRequirement = dependencyRequirementLine.substring(dependencyRequirementLine.indexOf('"') + 1,
                dependencyRequirementLine.lastIndexOf('"'));
        var maximumLine = questLines.stream().filter(line -> line.startsWith("max_completable_dependents:"))
                .findFirst().orElseThrow();
        var maximum = Integer.parseInt(maximumLine.substring(maximumLine.indexOf(':') + 1).trim());
        var dependenciesLine = questLines.stream().filter(line -> line.startsWith("dependencies:"))
                .findFirst().orElseThrow();
        var dependencies = List.of(dependenciesLine.substring(dependenciesLine.indexOf('[') + 1,
                dependenciesLine.lastIndexOf(']')).replace("\"", "").replace(" ", "").split(","));
        var taskIdLine = questLines.stream().filter(line -> line.equals("id: \"2000000000000001\""))
                .findFirst().orElseThrow();
        var taskId = taskIdLine.substring(taskIdLine.indexOf('"') + 1, taskIdLine.lastIndexOf('"'));
        var taskItemLine = questLines.stream().filter(line -> line.contains("id: \"minecraft:diamond\""))
                .findFirst().orElseThrow();
        var taskItem = taskItemLine.substring(taskItemLine.indexOf('"') + 1, taskItemLine.lastIndexOf('"'));
        var taskCountLine = questLines.stream().filter(line -> line.equals("count: 8L")).findFirst().orElseThrow();
        var taskCount = Long.parseLong(taskCountLine.substring(taskCountLine.indexOf(':') + 1, taskCountLine.length() - 1).trim());
        var rewardIdLine = questLines.stream().filter(line -> line.equals("id: \"3000000000000001\""))
                .findFirst().orElseThrow();
        var rewardId = rewardIdLine.substring(rewardIdLine.indexOf('"') + 1, rewardIdLine.lastIndexOf('"'));
        var rewardItemLine = questLines.stream().filter(line -> line.contains("id: \"minecraft:emerald\""))
                .findFirst().orElseThrow();
        var rewardItem = rewardItemLine.substring(rewardItemLine.indexOf('"') + 1, rewardItemLine.lastIndexOf('"'));
        var rewardCountLine = questLines.stream().filter(line -> line.equals("count: 3L")).findFirst().orElseThrow();
        var rewardCount = Long.parseLong(rewardCountLine.substring(rewardCountLine.indexOf(':') + 1,
                rewardCountLine.length() - 1).trim());
        var locked = teamLines.stream().anyMatch(line -> line.equals("locked: true"));
        var task = new FtbProvider.TaskRecord(taskId, "ftbquests:item", null,
                taskCount, null, taskItem, taskCount, true);
        var reward = new FtbProvider.RewardRecord(rewardId, "ftbquests:item", null,
                rewardItem, rewardCount, true);
        var quest = new FtbProvider.QuestRecord(questId, title, "chapter", dependencies,
                dependencies.stream().map(List::of).toList(), 0,
                List.of(task), List.of(reward), dependencyRequirement, maximum, title);
        var provider = provider(new FtbProvider.Snapshot(SCOPE, 1, List.of(quest),
                List.of(new FtbProvider.Progress(quest.id(), null, null, null, null, null, List.of(task), List.of(reward))),
                ignored -> { }));

        assertEquals("FFFFFFFFFFFFFFFF", quest.id());
        assertTrue(locked);
        var result = provider.query(new Evidence.Query(Evidence.Operation.GET,
                new Evidence.JsonObject(Map.of("id", new Evidence.JsonString(quest.id()))), 10, null, null), SCOPE)
                .toCompletableFuture().get();
        var payload = result.records().get(0).payload().values();
        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        assertTrue(payload.get("completed") instanceof Evidence.JsonNull);
        assertEquals("one_started", ((Evidence.JsonString) payload.get("dependencyRequirement")).value());
        assertEquals(2, ((Evidence.JsonArray) payload.get("dependencyBranches")).values().size());
        var taskPayload = (Evidence.JsonObject) ((Evidence.JsonArray) payload.get("tasks")).values().get(0);
        var rewardPayload = (Evidence.JsonObject) ((Evidence.JsonArray) payload.get("rewards")).values().get(0);
        assertEquals("minecraft:diamond", ((Evidence.JsonString) taskPayload.values().get("item")).value());
        assertEquals(8, ((Evidence.JsonNumber) taskPayload.values().get("count")).value().intValue());
        assertEquals("minecraft:emerald", ((Evidence.JsonString) rewardPayload.values().get("item")).value());
        assertEquals(3, ((Evidence.JsonNumber) rewardPayload.values().get("count")).value().intValue());
    }

    private static FtbProvider provider(FtbProvider.Snapshot snapshot) {
        var provider = new FtbProvider(() -> snapshot, Runnable::run);
        provider.refresh();
        return provider;
    }

    private static FtbProvider.QuestRecord quest(String id, String title, List<String> dependencies) {
        return new FtbProvider.QuestRecord(id, title, "chapter", dependencies,
                dependencies.stream().map(List::of).toList(), dependencies.isEmpty() ? 0 : 1, List.of(), List.of());
    }

    private static FtbProvider.Progress progress(FtbProvider.QuestRecord quest) {
        return new FtbProvider.Progress(quest.id(), true, true, true, false, false, quest.tasks(), quest.rewards());
    }

    private static Evidence.Query search(String text, int limit, String cursor) {
        return new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString(text))), limit,
                cursor == null ? null : SCOPE, cursor);
    }
}
