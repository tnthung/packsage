package dev.packsage.knowledge.ftb;

import dev.ftb.mods.ftbquests.client.ClientQuestFile;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.translation.TranslationKey;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import dev.ftb.mods.ftbquests.quest.task.TaskType;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.knowledge.AdapterRegistry;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.model.PackSageTools;
import org.junit.jupiter.api.Test;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FtbProviderTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "ftb-1", 1);
    private static final String LARGE_ID = "FFFFFFFFFFFFFFFF";

    @Test
    void documentAdapterMatchesItsQualifiedTypeAndReadsCurrentSnapshot() {
        var generation = new AtomicInteger();
        var provider = new FtbProvider(() -> {
            var title = generation.incrementAndGet() == 1 ? "Old quest" : "Current quest";
            var quest = new FtbProvider.QuestRecord("current-quest", title, "chapter", List.of(), List.of(), 0,
                    List.of(), List.of());
            return new FtbProvider.Snapshot(SCOPE, generation.get(), List.of(quest),
                    List.of(progress(quest)), ignored -> { });
        }, Runnable::run);
        var request = documentRequest("ftb_quests", "quest");
        var registry = new AdapterRegistry();
        registry.register(provider);

        var first = provider.searchDocument(request, context(registry, Map.of())).toCompletableFuture().join();
        var second = provider.searchDocument(request, context(registry, Map.of())).toCompletableFuture().join();

        assertEquals("Old quest", first.records().getFirst().title());
        assertEquals("Current quest", second.records().getFirst().title());
        assertEquals("ftb_quests", provider.metadata().id());
        assertEquals(List.of("quest"), provider.metadata().documentTypes());
        assertEquals(Toolbox.Code.NOT_HANDLED, failureCode(provider.searchDocument(
                documentRequest("patchouli", "quest"), context(registry, Map.of()))));
        assertEquals(Toolbox.Code.NOT_HANDLED, failureCode(provider.searchDocument(
                documentRequest("ftb_quests", "guide"), context(registry, Map.of()))));
    }

    @Test
    void liveWalkerReadsCurrentTeamProgressAndResumesWithinTheSameChapterOwner() throws Exception {
        var unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = unsafeField.get(null);
        var fakeMinecraft = (Minecraft) unsafe.getClass().getMethod("allocateInstance", Class.class)
                .invoke(unsafe, Minecraft.class);
        var minecraftInstance = Minecraft.class.getDeclaredField("instance");
        minecraftInstance.setAccessible(true);
        var previousMinecraft = minecraftInstance.get(null);
        minecraftInstance.set(null, fakeMinecraft);
        try {
            var file = new ClientQuestFile();
            var group = file.getDefaultChapterGroup();
            var translationsField = QuestObjectBase.class.getDeclaredField("protoTranslations");
            translationsField.setAccessible(true);
            var cachedTitleField = QuestObjectBase.class.getDeclaredField("cachedTitle");
            cachedTitleField.setAccessible(true);
            var groupTranslations = new EnumMap<TranslationKey, String>(TranslationKey.class);
            groupTranslations.put(TranslationKey.TITLE, "");
            translationsField.set(group, groupTranslations);
            cachedTitleField.set(group, Component.literal(""));
            var chapter = new Chapter(0x100L, file, group);
            var chapterTranslations = new EnumMap<TranslationKey, String>(TranslationKey.class);
            chapterTranslations.put(TranslationKey.TITLE, "");
            translationsField.set(chapter, chapterTranslations);
            cachedTitleField.set(chapter, Component.literal(""));
            chapter.onCreated();
            var firstQuestId = 0x1000L;
            for (var index = 0; index < 800; index++) {
                var quest = new Quest(firstQuestId + index, chapter);
                var questTitle = "Live quest " + index;
                var questTranslations = new EnumMap<TranslationKey, String>(TranslationKey.class);
                questTranslations.put(TranslationKey.TITLE, questTitle);
                translationsField.set(quest, questTranslations);
                cachedTitleField.set(quest, Component.literal(questTitle));
                quest.onCreated();
            }
            var team = new TeamData(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"), file);
            file.selfTeamData = team;
            team.setCompleted(firstQuestId, new java.util.Date(1));
            var provider = new FtbProvider(() -> { throw new AssertionError("production query captured a full snapshot"); },
                    Runnable::run, () -> SCOPE, () -> file, () -> true, true);
            var registry = new AdapterRegistry();
            registry.register(provider);
            var filter = new Evidence.JsonObject(Map.of("provider", new Evidence.JsonString(FtbProvider.ID),
                    "types", new Evidence.JsonArray(List.of(new Evidence.JsonString("quest")))));
            var arguments = new java.util.LinkedHashMap<String, Evidence.JsonValue>();
            arguments.put("query", new Evidence.JsonString(""));
            arguments.put("filter", filter);
            arguments.put("limit", new Evidence.JsonNumber(java.math.BigDecimal.ONE));
            var request = new PackSageTools.ReadRequest("search_document", new Evidence.JsonObject(arguments), SCOPE);

            var first = provider.searchDocument(request, context(registry, Map.of())).toCompletableFuture().get();
            assertEquals(1, first.records().size());
            assertEquals(Evidence.Coverage.PARTIAL, first.coverage());
            assertTrue(first.nextCursor().startsWith("ftb:"));
            var firstChapter = first.records().getFirst();
            assertEquals(Evidence.Coverage.PARTIAL, firstChapter.source().coverage());
            assertEquals(0, ((Evidence.JsonNumber) firstChapter.payload().values().get("questOffset")).value().intValue());
            var firstQuestRange = ((Evidence.JsonArray) firstChapter.payload().values().get("quests")).values();
            assertTrue(firstQuestRange.size() < chapter.getQuests().size());
            var firstQuestHandle = chapter.getQuests().getFirst().getCodeString();
            var progress = provider.query(new Evidence.Query(Evidence.Operation.GET,
                    new Evidence.JsonObject(Map.of("id", new Evidence.JsonString(firstQuestHandle))),
                    1, null, null), SCOPE).toCompletableFuture().get();
            assertEquals(true, ((Evidence.JsonBoolean) progress.records().getFirst().payload().values().get("completed")).value());
            var progressReference = new Toolbox.Reference(FtbProvider.ID, firstQuestHandle,
                    Evidence.RecordKind.QUEST, SCOPE);
            var progressRequest = new PackSageTools.ReadRequest("read_progress", new Evidence.JsonObject(Map.of(
                    "handles", new Evidence.JsonArray(List.of(new Evidence.JsonString(firstQuestHandle))))), SCOPE);
            var freshProgress = provider.readProgress(progressRequest, context(registry,
                    Map.of(firstQuestHandle, progressReference))).toCompletableFuture().get();
            assertEquals(true, ((Evidence.JsonBoolean) freshProgress.records().getFirst().payload().values().get("completed")).value());

            arguments.put("cursor", new Evidence.JsonString(first.nextCursor()));
            var second = provider.searchDocument(new PackSageTools.ReadRequest("search_document",
                    new Evidence.JsonObject(arguments), SCOPE), context(registry, Map.of())).toCompletableFuture().get();
            assertEquals(1, second.records().size());
            var secondChapter = second.records().getFirst();
            var secondOffset = ((Evidence.JsonNumber) secondChapter.payload().values().get("questOffset")).value().intValue();
            var secondQuestRange = ((Evidence.JsonArray) secondChapter.payload().values().get("quests")).values();
            assertEquals(firstQuestRange.size(), secondOffset);
            assertTrue(secondQuestRange.size() < chapter.getQuests().size() - secondOffset);
            assertFalse(((Evidence.JsonString) firstQuestRange.getLast()).value().equals(
                    ((Evidence.JsonString) secondQuestRange.getFirst()).value()));

            group.removeChapter(chapter);
            var replacement = new Chapter(0x101L, file, group);
            var replacementTranslations = new EnumMap<TranslationKey, String>(TranslationKey.class);
            replacementTranslations.put(TranslationKey.TITLE, "Replacement chapter");
            translationsField.set(replacement, replacementTranslations);
            cachedTitleField.set(replacement, Component.literal("Replacement chapter"));
            replacement.onCreated();
            var changedOwner = provider.searchDocument(new PackSageTools.ReadRequest("search_document",
                    new Evidence.JsonObject(arguments), SCOPE), context(registry, Map.of())).toCompletableFuture();
            try {
                changedOwner.join();
                throw new AssertionError("changed chapter owner should invalidate the continuation");
            } catch (java.util.concurrent.CompletionException failure) {
                assertEquals(Toolbox.Code.INVALID_CURSOR, ((Toolbox.Failure) failure.getCause()).code());
            }
        } finally {
            minecraftInstance.set(null, previousMinecraft);
        }
    }

    @Test
    void liveWalkerEmitsLargeQuestTaskRangesBeforeContinuingAndSkipsUnknownCallbacks() throws Exception {
        var unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = unsafeField.get(null);
        var fakeMinecraft = (Minecraft) unsafe.getClass().getMethod("allocateInstance", Class.class)
                .invoke(unsafe, Minecraft.class);
        var minecraftInstance = Minecraft.class.getDeclaredField("instance");
        minecraftInstance.setAccessible(true);
        var previousMinecraft = minecraftInstance.get(null);
        minecraftInstance.set(null, fakeMinecraft);
        try {
            var file = new ClientQuestFile();
            var group = file.getDefaultChapterGroup();
            var translationsField = QuestObjectBase.class.getDeclaredField("protoTranslations");
            translationsField.setAccessible(true);
            var cachedTitleField = QuestObjectBase.class.getDeclaredField("cachedTitle");
            cachedTitleField.setAccessible(true);
            var groupTranslations = new EnumMap<TranslationKey, String>(TranslationKey.class);
            groupTranslations.put(TranslationKey.TITLE, "");
            translationsField.set(group, groupTranslations);
            cachedTitleField.set(group, Component.literal(""));
            var chapter = new Chapter(0x200L, file, group);
            var chapterTranslations = new EnumMap<TranslationKey, String>(TranslationKey.class);
            chapterTranslations.put(TranslationKey.TITLE, "");
            translationsField.set(chapter, chapterTranslations);
            cachedTitleField.set(chapter, Component.literal(""));
            chapter.onCreated();
            var quest = new Quest(0x2000L, chapter);
            quest.onCreated();
            for (var index = 0; index < 180; index++) {
                quest.addTask(new ItemTask(0x3000L + index, quest)
                        .setStackAndCount(new ItemStack(Items.STONE), 1));
            }
            quest.addTask(new Task(0x4000L, quest) {
                @Override public TaskType getType() { throw new AssertionError("unknown task type callback"); }
                @Override public long getMaxProgress() { throw new AssertionError("unknown task progress callback"); }
            });
            var provider = new FtbProvider(() -> { throw new AssertionError("production query captured a full snapshot"); },
                    Runnable::run, () -> SCOPE, () -> file, () -> true, true);
            var registry = new AdapterRegistry();
            registry.register(provider);
            var queryText = "é".repeat(512);
            var questTranslations = new EnumMap<TranslationKey, String>(TranslationKey.class);
            questTranslations.put(TranslationKey.TITLE, queryText);
            translationsField.set(quest, questTranslations);
            cachedTitleField.set(quest, Component.literal(queryText));
            var arguments = new java.util.LinkedHashMap<String, Evidence.JsonValue>();
            arguments.put("query", new Evidence.JsonString(queryText));
            arguments.put("filter", new Evidence.JsonObject(Map.of("provider", new Evidence.JsonString(FtbProvider.ID),
                    "types", new Evidence.JsonArray(List.of(new Evidence.JsonString("quest"))))));
            arguments.put("limit", new Evidence.JsonNumber(java.math.BigDecimal.ONE));
            var request = new PackSageTools.ReadRequest("search_document", new Evidence.JsonObject(arguments), SCOPE);
            var observedTaskIds = new java.util.ArrayList<String>();
            var cursor = (String) null;
            for (var pageIndex = 0; pageIndex < 8; pageIndex++) {
                if (cursor != null) arguments.put("cursor", new Evidence.JsonString(cursor));
                var page = provider.searchDocument(new PackSageTools.ReadRequest("search_document",
                        new Evidence.JsonObject(arguments), SCOPE), context(registry, Map.of())).toCompletableFuture().get();
                assertEquals(1, page.records().size());
                var payload = page.records().getFirst().payload().values();
                var taskOffset = ((Evidence.JsonNumber) payload.get("taskOffset")).value().intValue();
                var taskValues = ((Evidence.JsonArray) payload.get("tasks")).values();
                assertEquals(observedTaskIds.size(), taskOffset);
                for (var taskValue : taskValues) observedTaskIds.add(((Evidence.JsonString)
                        ((Evidence.JsonObject) taskValue).values().get("id")).value());
                cursor = page.nextCursor();
                if (pageIndex == 0) {
                    assertTrue(taskValues.size() > 0);
                    assertTrue(cursor.length() <= 1024);
                    var changedArguments = new java.util.LinkedHashMap<>(arguments);
                    changedArguments.put("query", new Evidence.JsonString("different query"));
                    var invalidCursor = provider.searchDocument(new PackSageTools.ReadRequest("search_document",
                            new Evidence.JsonObject(changedArguments), SCOPE), context(registry, Map.of())).toCompletableFuture();
                    try {
                        invalidCursor.join();
                        throw new AssertionError("cursor should be bound to its original query");
                    } catch (java.util.concurrent.CompletionException failure) {
                        assertEquals(Toolbox.Code.INVALID_CURSOR, ((Toolbox.Failure) failure.getCause()).code());
                    }
                }
                if (cursor == null) break;
            }
            assertEquals(181, observedTaskIds.size());
            assertEquals(181, new java.util.HashSet<>(observedTaskIds).size());
        } finally {
            minecraftInstance.set(null, previousMinecraft);
        }
    }

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
        assertFalse(provider.enabled());
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

    private static PackSageTools.ReadRequest documentRequest(String provider, String type) {
        return new PackSageTools.ReadRequest("search_document", new Evidence.JsonObject(Map.of(
                "query", new Evidence.JsonString(""), "filter", new Evidence.JsonObject(Map.of(
                        "provider", new Evidence.JsonString(provider),
                        "types", new Evidence.JsonArray(List.of(new Evidence.JsonString(type))))))), SCOPE);
    }

    private static Toolbox.Context context(AdapterRegistry registry, Map<String, Toolbox.Reference> references) {
        return new Toolbox.Context(() -> SCOPE, references, registry, new Toolbox.DocumentBudget(), "test-call");
    }

    private static Toolbox.Code failureCode(java.util.concurrent.CompletionStage<Evidence.Result> stage) {
        try {
            stage.toCompletableFuture().join();
            return null;
        } catch (java.util.concurrent.CompletionException failure) {
            return ((Toolbox.Failure) failure.getCause()).code();
        }
    }
}
