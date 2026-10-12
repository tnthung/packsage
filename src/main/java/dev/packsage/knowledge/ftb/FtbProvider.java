package dev.packsage.knowledge.ftb;

import dev.ftb.mods.ftbquests.client.ClientQuestFile;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.DependencyRequirement;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.reward.ItemReward;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Client-visible FTB Quests records and a separately replaceable team overlay. */
public final class FtbProvider implements KnowledgeProvider {
    public static final String ID = "ftb_quests";
    public static final String VERSION = "2101.1.36";

    public record TaskRecord(String id, String type, Long progress, Long maximum, Boolean completed,
                             String itemId, Long itemCount, boolean supported) {
        public TaskRecord {
            Objects.requireNonNull(id);
            Objects.requireNonNull(type);
        }

        public TaskRecord(String id, String type, Long progress, Long maximum, Boolean completed) {
            this(id, type, progress, maximum, completed, null, null, supportedType(type));
        }

        public TaskRecord(String id, String type, long progress, Long maximum, boolean completed) {
            this(id, type, progress, maximum, completed, null, null, supportedType(type));
        }
    }

    public record RewardRecord(String id, String type, Boolean claimed, String itemId, Long count,
                               boolean supported) {
        public RewardRecord {
            Objects.requireNonNull(id);
            Objects.requireNonNull(type);
        }

        public RewardRecord(String id, String type, Boolean claimed) {
            this(id, type, claimed, null, null, supportedType(type));
        }

        public RewardRecord(String id, String type, boolean claimed) {
            this(id, type, claimed, null, null, supportedType(type));
        }
    }

    public record ChapterRecord(String id, String title, String group, String groupTitle,
                                List<String> quests, Boolean visible) {
        public ChapterRecord {
            Objects.requireNonNull(id);
            Objects.requireNonNull(title);
            Objects.requireNonNull(group);
            Objects.requireNonNull(groupTitle);
            quests = List.copyOf(quests);
        }

        public ChapterRecord(String id, String title, String group, List<String> quests, Boolean visible) {
            this(id, title, group, "", quests, visible);
        }
    }

    public record QuestRecord(String id, String title, String chapter, List<String> dependencies,
                              List<List<String>> dependencyBranches, int minimumDependencies,
                              List<TaskRecord> tasks, List<RewardRecord> rewards,
                              String dependencyRequirement, Integer maximumCompletableDependencies,
                              String rawTitle) {
        public QuestRecord {
            Objects.requireNonNull(id);
            Objects.requireNonNull(title);
            Objects.requireNonNull(chapter);
            dependencies = List.copyOf(dependencies);
            dependencyBranches = dependencyBranches.stream().map(List::copyOf).toList();
            if (minimumDependencies < 0) throw new IllegalArgumentException("minimumDependencies must not be negative");
            tasks = List.copyOf(tasks);
            rewards = List.copyOf(rewards);
        }

        public QuestRecord(String id, String title, String chapter, List<String> dependencies,
                           List<List<String>> dependencyBranches, int minimumDependencies,
                           List<TaskRecord> tasks, List<RewardRecord> rewards) {
            this(id, title, chapter, dependencies, dependencyBranches, minimumDependencies, tasks, rewards,
                    "all_completed", null, title);
        }
    }

    public record Progress(String questId, Boolean visible, Boolean dependenciesVisible, Boolean dependenciesComplete,
                           Boolean completed, Boolean rewardClaimed, List<TaskRecord> tasks, List<RewardRecord> rewards) {
        public Progress {
            Objects.requireNonNull(questId);
            tasks = List.copyOf(tasks);
            rewards = List.copyOf(rewards);
        }

        public Progress(String questId, boolean visible, boolean completed, boolean rewardClaimed,
                        List<TaskRecord> tasks, List<RewardRecord> rewards) {
            this(questId, visible, true, true, completed, rewardClaimed, tasks, rewards);
        }
    }

    public record Snapshot(Evidence.Scope scope, long generation, List<ChapterRecord> chapters,
                           List<QuestRecord> quests, List<Progress> progress, Consumer<String> navigator,
                           WeakReference<ClientQuestFile> fileReference, List<String> errors) {
        public Snapshot {
            Objects.requireNonNull(scope);
            chapters = List.copyOf(chapters);
            quests = List.copyOf(quests);
            progress = List.copyOf(progress);
            Objects.requireNonNull(navigator);
            errors = List.copyOf(errors);
        }

        public Snapshot(Evidence.Scope scope, long generation, List<QuestRecord> quests,
                        List<Progress> progress, Consumer<String> navigator) {
            this(scope, generation, List.of(), quests, progress, navigator, null, List.of());
        }

        /** Captures copied quest and team data on the client thread that owns the file. */
        public static Snapshot capture(ClientQuestFile file, Evidence.Scope scope, UUID playerId) {
            Objects.requireNonNull(file);
            Objects.requireNonNull(scope);
            var teamData = file.selfTeamData;
            var usableTeam = teamData != null && !teamData.isLocked();
            var chapters = new ArrayList<ChapterRecord>();
            var quests = new ArrayList<QuestRecord>();
            var progress = new ArrayList<Progress>();
            var errors = new ArrayList<String>();
            try {
                for (var chapter : file.getAllChapters()) {
                    try {
                        var chapterQuests = new ArrayList<String>();
                        for (var quest : chapter.getQuests()) {
                            try {
                                var captured = captureQuest(quest, chapter, usableTeam ? teamData : null, playerId, errors);
                                quests.add(captured.quest());
                                progress.add(captured.progress());
                                chapterQuests.add(captured.quest().id());
                            } catch (RuntimeException failure) {
                                errors.add("quest capture failed: " + failure);
                            }
                        }
                        var group = chapter.getGroup();
                        var groupId = group == null ? "" : group.getCodeString();
                        var groupTitle = group == null ? "" : displayTitle(group);
                        var chapterId = chapter.getCodeString();
                        var visible = usableTeam ? safeBoolean(() -> chapter.isVisible(teamData), errors, "chapter " + chapterId) : null;
                        chapters.add(new ChapterRecord(chapterId, displayTitle(chapter), groupId, groupTitle, chapterQuests, visible));
                    } catch (RuntimeException failure) {
                        errors.add("chapter capture failed: " + failure);
                    }
                }
            } catch (RuntimeException failure) {
                errors.add("quest file capture failed: " + failure);
            }
            if (chapters.isEmpty()) errors.add("quest file contains no chapters");
            return new Snapshot(scope, scope.requestGeneration(), chapters, quests, progress, ignored -> { },
                    new WeakReference<>(file), errors);
        }

        private static QuestCapture captureQuest(Quest quest, Chapter chapter, TeamData teamData, UUID playerId,
                                                 List<String> errors) {
            var questId = quest.getCodeString();
            var rawTitle = quest.getRawTitle();
            var title = displayTitle(quest);
            var dependencies = new ArrayList<String>();
            try {
                quest.streamDependencies().forEach(dependency -> dependencies.add(dependency.getCodeString()));
            } catch (RuntimeException failure) {
                errors.add("dependencies unavailable for " + questId + ": " + failure);
            }
            var dependencyRequirement = dependencyRequirement(quest);
            var oneDependency = "one_completed".equals(dependencyRequirement) || "one_started".equals(dependencyRequirement);
            List<List<String>> branches;
            if (oneDependency) branches = dependencies.stream().map(List::of).toList();
            else branches = dependencies.isEmpty() ? List.of() : List.of(List.copyOf(dependencies));
            Integer maximumCompletableDependencies = maximumCompletableDependencies(quest);
            var staticTasks = new ArrayList<TaskRecord>();
            var staticRewards = new ArrayList<RewardRecord>();
            var overlayTasks = new ArrayList<TaskRecord>();
            var overlayRewards = new ArrayList<RewardRecord>();
            try {
                for (var task : quest.getTasks()) {
                    staticTasks.add(taskRecord(task, null, errors));
                    overlayTasks.add(taskRecord(task, teamData, errors));
                }
            } catch (RuntimeException failure) {
                errors.add("tasks unavailable for " + questId + ": " + failure);
            }
            try {
                for (var reward : quest.getRewards()) {
                    staticRewards.add(rewardRecord(reward, null, null, errors));
                    overlayRewards.add(rewardRecord(reward, teamData, playerId, errors));
                }
            } catch (RuntimeException failure) {
                errors.add("rewards unavailable for " + questId + ": " + failure);
            }
            var record = new QuestRecord(questId, title, chapter.getCodeString(), dependencies, branches,
                    quest.getMinRequiredDependencies(), staticTasks, staticRewards, dependencyRequirement,
                    maximumCompletableDependencies, rawTitle);
            Boolean visible = null;
            Boolean dependenciesVisible = null;
            Boolean dependenciesComplete = null;
            Boolean completed = null;
            Boolean rewardClaimed = null;
            if (teamData != null) {
                visible = safeBoolean(() -> quest.isVisible(teamData), errors, "quest " + questId + " visibility");
                dependenciesVisible = safeBoolean(() -> quest.areDependenciesVisible(teamData), errors,
                        "quest " + questId + " dependency visibility");
                dependenciesComplete = safeBoolean(() -> quest.areDependenciesComplete(teamData), errors,
                        "quest " + questId + " dependency completion");
                completed = safeBoolean(() -> quest.isCompletedRaw(teamData), errors, "quest " + questId + " completion");
                if (playerId != null) {
                    rewardClaimed = safeBoolean(() -> !quest.hasUnclaimedRewardsRaw(teamData, playerId), errors,
                            "quest " + questId + " rewards");
                }
            }
            return new QuestCapture(record, new Progress(questId, visible, dependenciesVisible, dependenciesComplete,
                    completed, rewardClaimed, overlayTasks, overlayRewards));
        }

        private static TaskRecord taskRecord(Task task, TeamData teamData, List<String> errors) {
            var id = safeId(task);
            try {
                var type = task.getType().getTypeId().toString();
                var maximum = task.getMaxProgress();
                var progress = teamData == null ? null : teamData.getProgress(task);
                var completed = teamData == null ? null : teamData.isCompleted(task);
                String itemId = null;
                Long itemCount = null;
                if (task instanceof ItemTask itemTask) {
                    var stack = itemTask.getItemStack();
                    if (stack != null && !stack.isEmpty()) itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                    if (maximum >= 0) itemCount = maximum;
                }
                return new TaskRecord(id, type, progress, maximum < 0 ? null : maximum, completed,
                        itemId, itemCount, task instanceof ItemTask);
            } catch (RuntimeException failure) {
                errors.add("task " + id + " capture failed: " + failure);
                return new TaskRecord(id, "unsupported:" + task.getClass().getName(), null, null, null,
                        null, null, false);
            }
        }

        private static RewardRecord rewardRecord(Reward reward, TeamData teamData, UUID playerId,
                                                 List<String> errors) {
            var id = safeId(reward);
            try {
                var type = reward.getType().getTypeId().toString();
                var claimed = teamData == null || playerId == null ? null : teamData.isRewardClaimed(playerId, reward);
                String itemId = null;
                Long count = null;
                if (reward instanceof ItemReward itemReward) {
                    var item = itemReward.getItem();
                    if (item != null && !item.isEmpty()) itemId = BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
                    count = (long) itemReward.getCount();
                }
                return new RewardRecord(id, type, claimed, itemId, count, reward instanceof ItemReward);
            } catch (RuntimeException failure) {
                errors.add("reward " + id + " capture failed: " + failure);
                return new RewardRecord(id, "unsupported:" + reward.getClass().getName(), null, null, null, false);
            }
        }

        private static String safeId(QuestObjectBase object) {
            try { return object.getCodeString(); } catch (RuntimeException ignored) { return object.getClass().getName(); }
        }

        private static String displayTitle(QuestObjectBase object) {
            try { return object.getTitle().getString(); } catch (RuntimeException ignored) { return object.getRawTitle(); }
        }

        private static Boolean safeBoolean(Supplier<Boolean> value, List<String> errors, String description) {
            try { return value.get(); } catch (RuntimeException failure) {
                errors.add(description + " failed: " + failure);
                return null;
            }
        }

        private static String dependencyRequirement(Quest quest) {
            try {
                Field field = Quest.class.getDeclaredField("dependencyRequirement");
                if (!field.trySetAccessible()) return null;
                var value = field.get(quest);
                return value instanceof DependencyRequirement requirement ? requirement.getId() : null;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }

        private static Integer maximumCompletableDependencies(Quest quest) {
            try {
                Field field = Quest.class.getDeclaredField("maxCompletableDeps");
                if (!field.trySetAccessible()) return null;
                var value = field.get(quest);
                return value instanceof Integer maximum && maximum > 0 ? maximum : null;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }

        private record QuestCapture(QuestRecord quest, Progress progress) { }
    }

    private final Supplier<Snapshot> snapshotSource;
    private final Executor clientExecutor;
    private final Supplier<Evidence.Scope> scopeSource;
    private final Supplier<ClientQuestFile> fileSource;
    private final BooleanSupplier clientThread;
    private final boolean liveFileNavigation;
    private volatile Snapshot current;
    private volatile String failure;
    private volatile boolean disabled;
    private volatile long lifecycleGeneration;

    public FtbProvider(Supplier<Snapshot> snapshotSource, Executor clientExecutor) {
        this(snapshotSource, clientExecutor, null, null, () -> true, false);
    }

    private FtbProvider(Supplier<Snapshot> snapshotSource, Executor clientExecutor,
                        Supplier<Evidence.Scope> scopeSource, Supplier<ClientQuestFile> fileSource,
                        BooleanSupplier clientThread, boolean liveFileNavigation) {
        this.snapshotSource = Objects.requireNonNull(snapshotSource);
        this.clientExecutor = Objects.requireNonNull(clientExecutor);
        this.scopeSource = scopeSource;
        this.fileSource = fileSource;
        this.clientThread = Objects.requireNonNull(clientThread);
        this.liveFileNavigation = liveFileNavigation;
    }

    public FtbProvider(ClientQuestFile file, Supplier<Evidence.Scope> scopeSource, UUID playerId,
                       Executor clientExecutor) {
        this(() -> Snapshot.capture(file, scopeSource.get(), playerId), clientExecutor, scopeSource,
                () -> file, () -> true, false);
    }

    /** Builds a provider that resolves the current global FTB file for every client-thread capture. */
    public static FtbProvider forMinecraft(Supplier<Evidence.Scope> scopeSource, Executor clientExecutor) {
        Objects.requireNonNull(scopeSource);
        return new FtbProvider(() -> {
            var file = ClientQuestFile.INSTANCE;
            if (file == null) return null;
            var player = Minecraft.getInstance().player;
            return Snapshot.capture(file, scopeSource.get(), player == null ? null : player.getUUID());
        }, clientExecutor, scopeSource, () -> ClientQuestFile.INSTANCE,
                () -> Minecraft.getInstance().isSameThread(), true);
    }

    public static boolean isRuntimeAvailable() { return ClientQuestFile.INSTANCE != null; }

    @Override
    public String id() { return ID; }

    @Override
    public ProviderStatus status() {
        var snapshot = current;
        if (failure != null) return new ProviderStatus(Availability.ERROR, VERSION, failure);
        if (snapshot == null) return new ProviderStatus(Availability.UNAVAILABLE, VERSION, "FTB Quests client data unavailable");
        if (!scopeIsCurrent(snapshot.scope())) return new ProviderStatus(Availability.PARTIAL, VERSION,
                "FTB Quests client data belongs to an inactive scope");
        if (incomplete(snapshot)) return new ProviderStatus(Availability.PARTIAL, VERSION,
                "client quest file generation " + snapshot.generation() + " has partial records");
        return new ProviderStatus(Availability.AVAILABLE, VERSION, "client quest file generation " + snapshot.generation());
    }

    public void refresh() {
        disabled = false;
        failure = null;
        var generation = ++lifecycleGeneration;
        try {
            clientExecutor.execute(() -> {
                if (generation != lifecycleGeneration || disabled) return;
                try {
                    var snapshot = snapshotSource.get();
                    if (generation != lifecycleGeneration || disabled) return;
                    if (snapshot == null) {
                        current = null;
                        failure = "FTB Quests client data unavailable";
                    } else if (!scopeIsCurrent(snapshot.scope())) {
                        current = null;
                        failure = "FTB Quests client data belongs to an inactive scope";
                    } else {
                        current = snapshot;
                        failure = null;
                    }
                } catch (RuntimeException captureFailure) {
                    current = null;
                    failure = "FTB Quests capture failed: " + captureFailure;
                }
            });
        } catch (RuntimeException schedulingFailure) {
            current = null;
            failure = "FTB Quests client-thread scheduling failed: " + schedulingFailure;
        }
    }

    public void unavailable() {
        disabled = true;
        ++lifecycleGeneration;
        current = null;
        failure = null;
    }

    @Override
    public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
        Objects.requireNonNull(query);
        Objects.requireNonNull(scope);
        var generation = lifecycleGeneration;
        try {
            return CompletableFuture.supplyAsync(() -> {
                if (disabled || generation != lifecycleGeneration)
                    return unavailableResult("FTB Quests client data unavailable");
                Snapshot snapshot;
                try {
                    snapshot = snapshotSource.get();
                } catch (RuntimeException captureFailure) {
                    current = null;
                    failure = "FTB Quests capture failed: " + captureFailure;
                    return new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null);
                }
                if (snapshot == null) {
                    current = null;
                    failure = "FTB Quests client data unavailable";
                    return unavailableResult(failure);
                }
                if (disabled || generation != lifecycleGeneration)
                    return unavailableResult("FTB Quests client data unavailable");
                if (!sameScope(snapshot.scope(), scope) || !scopeIsCurrent(snapshot.scope())
                        || query.cursor() != null && query.cursorScope() != null
                        && !sameScope(query.cursorScope(), scope)) {
                    return new Evidence.Result(List.of(), Evidence.Coverage.PARTIAL, Evidence.Outcome.UNKNOWN, null);
                }
                current = snapshot;
                return querySnapshot(query, scope, snapshot);
            }, clientExecutor);
        } catch (RuntimeException schedulingFailure) {
            return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.ERROR,
                    Evidence.Outcome.ERROR, null));
        }
    }

    @Override
    public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
        var snapshot = current;
        if (snapshot == null) return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE,
                "FTB Quests client data unavailable");
        if (!ID.equals(target.providerId()) || !sameScope(snapshot.scope(), scope) || !sameScope(target.scope(), scope)
                || !scopeIsCurrent(snapshot.scope())) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.STALE, "quest target is outside the active scope");
        }
        if (!target.sourceHandle().equals(target.targetId()))
            return new Evidence.NavigationResult(Evidence.NavigationStatus.STALE, "quest target identity does not match its source");
        if (snapshot.quests().stream().noneMatch(quest -> quest.id().equals(target.targetId())))
            return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "quest is unavailable");
        try {
            if (!clientThread.getAsBoolean())
                return new Evidence.NavigationResult(Evidence.NavigationStatus.UNSUPPORTED,
                        "FTB Quests navigation must run on the client thread");
            if (liveFileNavigation) {
                var file = fileSource.get();
                var capturedFile = snapshot.fileReference() == null ? null : snapshot.fileReference().get();
                if (file == null) return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE,
                        "FTB Quests client data unavailable");
                if (capturedFile == null || capturedFile != file || ClientQuestFile.INSTANCE != file)
                    return new Evidence.NavigationResult(Evidence.NavigationStatus.STALE,
                            "quest target belongs to a replaced quest file");
                var parsedId = parseQuestId(target.targetId());
                if (parsedId.isEmpty()) return new Evidence.NavigationResult(Evidence.NavigationStatus.ERROR,
                        "malformed quest id");
                var quest = file.getQuest(parsedId.getAsLong());
                if (quest == null || !quest.getCodeString().equals(target.targetId()))
                    return new Evidence.NavigationResult(Evidence.NavigationStatus.STALE,
                            "quest target is absent from the current quest file");
                ClientQuestFile.openGui(quest, true);
            } else {
                snapshot.navigator().accept(target.targetId());
            }
            return new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED, "quest screen opened");
        } catch (RuntimeException navigationFailure) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.ERROR, navigationFailure.toString());
        }
    }

    private static Evidence.Result querySnapshot(Evidence.Query query, Evidence.Scope scope, Snapshot snapshot) {
        var id = query.operation() == Evidence.Operation.GET || query.operation() == Evidence.Operation.RELATED
                ? stringArgument(query.arguments(), "id") : null;
        if ((query.operation() == Evidence.Operation.GET || query.operation() == Evidence.Operation.RELATED) && id == null)
            return malformed("GET and RELATED require a string id");
        if ((query.operation() == Evidence.Operation.GET || query.operation() == Evidence.Operation.RELATED) && id.isBlank())
            return malformed("GET and RELATED require a non-empty id");
        var text = query.operation() == Evidence.Operation.SEARCH ? stringArgument(query.arguments(), "text") : "";
        if (query.operation() == Evidence.Operation.SEARCH && text == null) return malformed("SEARCH requires a string text");
        if (query.operation() == Evidence.Operation.RELATED && id == null) return malformed("RELATED requires a string id");
        var candidates = new ArrayList<Object>();
        if (query.operation() != Evidence.Operation.RELATED) candidates.addAll(snapshot.chapters());
        candidates.addAll(snapshot.quests());
        var start = query.cursor() == null ? 0 : parseCursor(query.cursor());
        if (start < 0 || start > candidates.size()) return malformed("malformed cursor");
        var records = new ArrayList<Evidence.Record>();
        var nextIndex = start;
        for (; nextIndex < candidates.size() && records.size() < query.limit(); nextIndex++) {
            var candidate = candidates.get(nextIndex);
            if (query.operation() == Evidence.Operation.GET && !candidateId(candidate).equals(id)) continue;
            if (query.operation() == Evidence.Operation.SEARCH && !matches(candidate, text.toLowerCase(Locale.ROOT))) continue;
            if (query.operation() == Evidence.Operation.RELATED && !related((QuestRecord) candidate, id)) continue;
            if (candidate instanceof ChapterRecord chapter) records.add(toRecord(chapter, snapshot));
            else records.add(toRecord((QuestRecord) candidate, snapshot));
        }
        var partial = incomplete(snapshot) || records.stream().anyMatch(record -> record.source().coverage() != Evidence.Coverage.COMPLETE);
        var coverage = partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE;
        var outcome = coverage == Evidence.Coverage.COMPLETE ? Evidence.Outcome.COMPLETE
                : records.isEmpty() ? Evidence.Outcome.UNKNOWN : Evidence.Outcome.PARTIAL;
        return new Evidence.Result(records, coverage, outcome, nextIndex < candidates.size() ? Integer.toString(nextIndex) : null);
    }

    private static Evidence.Result malformed(String detail) {
        return new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null);
    }

    private static Evidence.Result unavailableResult(String detail) {
        return new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null);
    }

    private static Evidence.Record toRecord(ChapterRecord chapter, Snapshot snapshot) {
        var values = new java.util.LinkedHashMap<String, Evidence.JsonValue>();
        values.put("chapterId", new Evidence.JsonString(chapter.id()));
        values.put("group", new Evidence.JsonString(chapter.group()));
        values.put("groupTitle", new Evidence.JsonString(chapter.groupTitle()));
        values.put("visible", nullableBoolean(chapter.visible()));
        values.put("quests", strings(chapter.quests()));
        var coverage = chapter.visible() == null ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE;
        var source = new Evidence.Source(snapshot.scope(), ID, VERSION, chapter.id(), snapshot.generation(), Instant.now(),
                Evidence.Activity.ACTIVE, coverage, Evidence.Authority.LOADED, List.of("ftbquests:client-quest-file"));
        return new Evidence.Record(source, Evidence.RecordKind.QUEST, chapter.title(), new Evidence.JsonObject(values),
                List.of(new Evidence.NavigationTarget(snapshot.scope(), ID, chapter.id(), chapter.id())));
    }

    private static Evidence.Record toRecord(QuestRecord quest, Snapshot snapshot) {
        var progress = snapshot.progress().stream().filter(value -> value.questId().equals(quest.id())).findFirst().orElse(null);
        var tasks = progress == null ? quest.tasks() : progress.tasks();
        var rewards = progress == null ? quest.rewards() : progress.rewards();
        var values = new java.util.LinkedHashMap<String, Evidence.JsonValue>();
        values.put("questId", new Evidence.JsonString(quest.id()));
        values.put("rawTitle", new Evidence.JsonString(quest.rawTitle() == null ? quest.title() : quest.rawTitle()));
        values.put("chapter", new Evidence.JsonString(quest.chapter()));
        values.put("dependencies", strings(quest.dependencies()));
        values.put("dependencyBranches", new Evidence.JsonArray(quest.dependencyBranches().stream()
                .map(FtbProvider::strings).map(value -> (Evidence.JsonValue) value).toList()));
        values.put("dependencyRequirement", quest.dependencyRequirement() == null ? new Evidence.JsonNull()
                : new Evidence.JsonString(quest.dependencyRequirement()));
        values.put("minimumDependencies", number(quest.minimumDependencies()));
        var oneDependency = "one_completed".equals(quest.dependencyRequirement()) || "one_started".equals(quest.dependencyRequirement());
        values.put("evaluatedMinimumDependencies", number(oneDependency && !quest.dependencies().isEmpty() ? 1 : quest.minimumDependencies()));
        values.put("maximumCompletableDependencies", quest.maximumCompletableDependencies() == null
                ? new Evidence.JsonNull() : number(quest.maximumCompletableDependencies()));
        values.put("exclusive", new Evidence.JsonBoolean(quest.maximumCompletableDependencies() != null));
        values.put("dependenciesVisible", progress == null ? new Evidence.JsonNull() : nullableBoolean(progress.dependenciesVisible()));
        values.put("dependenciesComplete", progress == null ? new Evidence.JsonNull() : nullableBoolean(progress.dependenciesComplete()));
        values.put("visible", progress == null ? new Evidence.JsonNull() : nullableBoolean(progress.visible()));
        values.put("completed", progress == null ? new Evidence.JsonNull() : nullableBoolean(progress.completed()));
        values.put("rewardClaimed", progress == null ? new Evidence.JsonNull() : nullableBoolean(progress.rewardClaimed()));
        values.put("progressAvailable", progress != null && progress.dependenciesVisible() != null
                && progress.dependenciesComplete() != null && progress.visible() != null && progress.completed() != null
                ? new Evidence.JsonBoolean(true) : new Evidence.JsonBoolean(false));
        values.put("tasks", new Evidence.JsonArray(tasks.stream().map(FtbProvider::taskJson)
                .map(value -> (Evidence.JsonValue) value).toList()));
        values.put("rewards", new Evidence.JsonArray(rewards.stream().map(FtbProvider::rewardJson)
                .map(value -> (Evidence.JsonValue) value).toList()));
        var opaque = tasks.stream().anyMatch(task -> !task.supported()) || rewards.stream().anyMatch(reward -> !reward.supported());
        var progressUnknown = progress == null || progress.dependenciesVisible() == null
                || progress.dependenciesComplete() == null || progress.visible() == null
                || progress.completed() == null || progress.rewardClaimed() == null;
        var coverage = opaque || progressUnknown || quest.dependencyRequirement() == null
                ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE;
        var source = new Evidence.Source(snapshot.scope(), ID, VERSION, quest.id(), snapshot.generation(), Instant.now(),
                Evidence.Activity.ACTIVE, coverage, Evidence.Authority.LOADED,
                List.of("ftbquests:client-quest-file", "ftbquests:team-data"));
        return new Evidence.Record(source, Evidence.RecordKind.QUEST, quest.title(), new Evidence.JsonObject(values),
                List.of(new Evidence.NavigationTarget(snapshot.scope(), ID, quest.id(), quest.id())));
    }

    private static Evidence.JsonObject taskJson(TaskRecord task) {
        var values = new java.util.LinkedHashMap<String, Evidence.JsonValue>();
        values.put("id", new Evidence.JsonString(task.id()));
        values.put("type", new Evidence.JsonString(task.type()));
        values.put("progress", task.progress() == null ? new Evidence.JsonNull() : number(task.progress()));
        values.put("maximum", task.maximum() == null ? new Evidence.JsonNull() : number(task.maximum()));
        values.put("completed", nullableBoolean(task.completed()));
        values.put("item", task.itemId() == null ? new Evidence.JsonNull() : new Evidence.JsonString(task.itemId()));
        values.put("count", task.itemCount() == null ? new Evidence.JsonNull() : number(task.itemCount()));
        values.put("supported", new Evidence.JsonBoolean(task.supported()));
        return new Evidence.JsonObject(values);
    }

    private static Evidence.JsonObject rewardJson(RewardRecord reward) {
        var values = new java.util.LinkedHashMap<String, Evidence.JsonValue>();
        values.put("id", new Evidence.JsonString(reward.id()));
        values.put("type", new Evidence.JsonString(reward.type()));
        values.put("claimed", nullableBoolean(reward.claimed()));
        values.put("item", reward.itemId() == null ? new Evidence.JsonNull() : new Evidence.JsonString(reward.itemId()));
        values.put("count", reward.count() == null ? new Evidence.JsonNull() : number(reward.count()));
        values.put("supported", new Evidence.JsonBoolean(reward.supported()));
        return new Evidence.JsonObject(values);
    }

    private static Evidence.JsonValue nullableBoolean(Boolean value) {
        return value == null ? new Evidence.JsonNull() : new Evidence.JsonBoolean(value);
    }

    private static Evidence.JsonNumber number(long value) {
        return new Evidence.JsonNumber(java.math.BigDecimal.valueOf(value));
    }

    private static Evidence.JsonArray strings(List<String> values) {
        return new Evidence.JsonArray(values.stream().map(Evidence.JsonString::new).map(value -> (Evidence.JsonValue) value).toList());
    }

    private static String candidateId(Object candidate) {
        return candidate instanceof ChapterRecord chapter ? chapter.id() : ((QuestRecord) candidate).id();
    }

    private static boolean matches(Object candidate, String text) {
        if (candidate instanceof ChapterRecord chapter) {
            return text.isEmpty() || contains(chapter.id(), text) || contains(chapter.title(), text)
                    || contains(chapter.group(), text) || contains(chapter.groupTitle(), text)
                    || chapter.quests().stream().anyMatch(value -> contains(value, text));
        }
        var quest = (QuestRecord) candidate;
        return text.isEmpty() || contains(quest.id(), text) || contains(quest.title(), text)
                || contains(quest.rawTitle(), text) || contains(quest.chapter(), text)
                || quest.dependencies().stream().anyMatch(value -> contains(value, text))
                || quest.tasks().stream().anyMatch(task -> contains(task.id(), text) || contains(task.type(), text)
                || contains(task.itemId(), text))
                || quest.rewards().stream().anyMatch(reward -> contains(reward.id(), text) || contains(reward.type(), text)
                || contains(reward.itemId(), text));
    }

    private static boolean contains(String value, String text) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(text);
    }

    private static boolean related(QuestRecord quest, String id) {
        return quest.id().equals(id) || quest.dependencies().contains(id) || quest.chapter().equals(id);
    }

    private static boolean supportedType(String type) {
        return !type.startsWith("unknown:") && !type.startsWith("unsupported:") && !type.endsWith(":custom");
    }

    private static boolean incomplete(Snapshot snapshot) {
        return !snapshot.errors().isEmpty() || snapshot.quests().isEmpty() && snapshot.chapters().isEmpty()
                || snapshot.chapters().stream().anyMatch(chapter -> chapter.visible() == null)
                || snapshot.quests().stream().anyMatch(quest -> snapshot.progress().stream()
                .noneMatch(progress -> progress.questId().equals(quest.id())))
                || snapshot.progress().stream().anyMatch(progress -> progress.dependenciesVisible() == null
                || progress.dependenciesComplete() == null || progress.visible() == null
                || progress.completed() == null || progress.rewardClaimed() == null)
                || snapshot.quests().stream().anyMatch(quest -> quest.dependencyRequirement() == null)
                || snapshot.quests().stream().anyMatch(quest -> quest.tasks().stream().anyMatch(task -> !task.supported())
                || quest.rewards().stream().anyMatch(reward -> !reward.supported()))
                || snapshot.progress().stream().anyMatch(progress -> progress.tasks().stream().anyMatch(task -> !task.supported())
                || progress.rewards().stream().anyMatch(reward -> !reward.supported()));
    }

    private boolean scopeIsCurrent(Evidence.Scope captured) {
        if (scopeSource == null) return true;
        try {
            var currentScope = scopeSource.get();
            return currentScope != null && sameScope(currentScope, captured);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static OptionalLong parseQuestId(String id) {
        var value = id.startsWith("#") ? id.substring(1) : id;
        if (value.isEmpty() || value.length() > 16 || value.chars().anyMatch(character -> Character.digit(character, 16) < 0))
            return OptionalLong.empty();
        try { return OptionalLong.of(Long.parseUnsignedLong(value, 16)); }
        catch (NumberFormatException ignored) { return OptionalLong.empty(); }
    }

    private static String stringArgument(Evidence.JsonObject arguments, String key) {
        var value = arguments.values().get(key);
        return value instanceof Evidence.JsonString string ? string.value() : null;
    }

    private static int parseCursor(String cursor) {
        try { return Integer.parseInt(cursor); } catch (NumberFormatException ignored) { return -1; }
    }

    private static boolean sameScope(Evidence.Scope left, Evidence.Scope right) {
        return left.worldSession().equals(right.worldSession()) && left.resourcesLocale().equals(right.resourcesLocale())
                && left.playerTeam().equals(right.playerTeam()) && left.providerRuntime().equals(right.providerRuntime())
                && left.requestGeneration() == right.requestGeneration();
    }
}
