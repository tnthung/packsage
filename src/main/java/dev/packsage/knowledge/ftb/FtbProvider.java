package dev.packsage.knowledge.ftb;

import dev.ftb.mods.ftbquests.client.ClientQuestFile;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.DependencyRequirement;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.reward.AdvancementReward;
import dev.ftb.mods.ftbquests.quest.reward.AllTableReward;
import dev.ftb.mods.ftbquests.quest.reward.ChoiceReward;
import dev.ftb.mods.ftbquests.quest.reward.CommandReward;
import dev.ftb.mods.ftbquests.quest.reward.CurrencyReward;
import dev.ftb.mods.ftbquests.quest.reward.ItemReward;
import dev.ftb.mods.ftbquests.quest.reward.LootReward;
import dev.ftb.mods.ftbquests.quest.reward.RandomReward;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import dev.ftb.mods.ftbquests.quest.reward.StageReward;
import dev.ftb.mods.ftbquests.quest.reward.ToastReward;
import dev.ftb.mods.ftbquests.quest.reward.XPLevelsReward;
import dev.ftb.mods.ftbquests.quest.reward.XPReward;
import dev.ftb.mods.ftbquests.quest.task.AdvancementTask;
import dev.ftb.mods.ftbquests.quest.task.BiomeTask;
import dev.ftb.mods.ftbquests.quest.task.CheckmarkTask;
import dev.ftb.mods.ftbquests.quest.task.DimensionTask;
import dev.ftb.mods.ftbquests.quest.task.EnergyTask;
import dev.ftb.mods.ftbquests.quest.task.FluidTask;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbquests.quest.task.KillTask;
import dev.ftb.mods.ftbquests.quest.task.LocationTask;
import dev.ftb.mods.ftbquests.quest.task.ObservationTask;
import dev.ftb.mods.ftbquests.quest.task.StageTask;
import dev.ftb.mods.ftbquests.quest.task.StatTask;
import dev.ftb.mods.ftbquests.quest.task.StructureTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import dev.ftb.mods.ftbquests.quest.task.XPTask;
import dev.ftb.mods.ftbquests.quest.task.neoforge.ForgeEnergyTask;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.model.PackSageTools;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    public record ChapterChildProgress(String questId, Boolean visible, Boolean completed) {
        public ChapterChildProgress { Objects.requireNonNull(questId); }
    }

    public record ChapterRecord(String id, String title, String group, String groupTitle, int questOffset,
                                List<String> quests, List<ChapterChildProgress> questProgress,
                                boolean childRangeComplete, Boolean visible, boolean titleComplete) {
        public ChapterRecord {
            Objects.requireNonNull(id);
            Objects.requireNonNull(title);
            Objects.requireNonNull(group);
            Objects.requireNonNull(groupTitle);
            if (questOffset < 0) throw new IllegalArgumentException("questOffset must not be negative");
            quests = List.copyOf(quests);
            questProgress = List.copyOf(questProgress);
        }

        public ChapterRecord(String id, String title, String group, String groupTitle,
                             int questOffset, List<String> quests, List<ChapterChildProgress> questProgress,
                             boolean childRangeComplete, Boolean visible) {
            this(id, title, group, groupTitle, questOffset, quests, questProgress, childRangeComplete, visible, true);
        }

        public ChapterRecord(String id, String title, String group, String groupTitle,
                             List<String> quests, Boolean visible) {
            this(id, title, group, groupTitle, 0, quests, List.of(), true, visible, true);
        }

        public ChapterRecord(String id, String title, String group, List<String> quests, Boolean visible) {
            this(id, title, group, "", 0, quests, List.of(), true, visible, true);
        }
    }

    public record QuestRecord(String id, String title, String chapter, List<String> dependencies,
                              List<List<String>> dependencyBranches, int minimumDependencies,
                              List<TaskRecord> tasks, List<RewardRecord> rewards,
                              String dependencyRequirement, Integer maximumCompletableDependencies,
                              String rawTitle, int dependencyOffset, boolean dependencyRangeComplete,
                              int taskOffset, boolean taskRangeComplete, int rewardOffset,
                              boolean rewardRangeComplete, boolean titleComplete) {
        public QuestRecord {
            Objects.requireNonNull(id);
            Objects.requireNonNull(title);
            Objects.requireNonNull(chapter);
            dependencies = List.copyOf(dependencies);
            dependencyBranches = dependencyBranches.stream().map(List::copyOf).toList();
            if (minimumDependencies < 0) throw new IllegalArgumentException("minimumDependencies must not be negative");
            tasks = List.copyOf(tasks);
            rewards = List.copyOf(rewards);
            if (dependencyOffset < 0 || taskOffset < 0 || rewardOffset < 0)
                throw new IllegalArgumentException("nested source offsets must not be negative");
        }

        public QuestRecord(String id, String title, String chapter, List<String> dependencies,
                           List<List<String>> dependencyBranches, int minimumDependencies,
                           List<TaskRecord> tasks, List<RewardRecord> rewards,
                           String dependencyRequirement, Integer maximumCompletableDependencies,
                           String rawTitle, int dependencyOffset, boolean dependencyRangeComplete,
                           int taskOffset, boolean taskRangeComplete, int rewardOffset,
                           boolean rewardRangeComplete) {
            this(id, title, chapter, dependencies, dependencyBranches, minimumDependencies, tasks, rewards,
                    dependencyRequirement, maximumCompletableDependencies, rawTitle, dependencyOffset,
                    dependencyRangeComplete, taskOffset, taskRangeComplete, rewardOffset, rewardRangeComplete, true);
        }

        public QuestRecord(String id, String title, String chapter, List<String> dependencies,
                           List<List<String>> dependencyBranches, int minimumDependencies,
                           List<TaskRecord> tasks, List<RewardRecord> rewards,
                           String dependencyRequirement, Integer maximumCompletableDependencies,
                           String rawTitle) {
            this(id, title, chapter, dependencies, dependencyBranches, minimumDependencies, tasks, rewards,
                    dependencyRequirement, maximumCompletableDependencies, rawTitle, 0, true, 0, true, 0, true);
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
                        itemId, itemCount, supportedType(type));
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
                return new RewardRecord(id, type, claimed, itemId, count, supportedType(type));
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
    private final Map<String, DocumentCursorState> documentCursors = new LinkedHashMap<>(128, 0.75f, true);
    private volatile Snapshot current;
    private volatile String failure;
    private volatile boolean disabled;
    private volatile long lifecycleGeneration;

    private record DocumentCursorState(String query, String position, Evidence.Scope scope) { }

    public FtbProvider(Supplier<Snapshot> snapshotSource, Executor clientExecutor) {
        this(snapshotSource, clientExecutor, null, null, () -> true, false);
    }

    FtbProvider(Supplier<Snapshot> snapshotSource, Executor clientExecutor,
                Supplier<Evidence.Scope> scopeSource, Supplier<ClientQuestFile> fileSource,
                BooleanSupplier clientThread, boolean liveFileNavigation) {
        this.snapshotSource = Objects.requireNonNull(snapshotSource);
        this.clientExecutor = Objects.requireNonNull(clientExecutor);
        this.scopeSource = scopeSource;
        this.fileSource = fileSource;
        this.clientThread = Objects.requireNonNull(clientThread);
        this.liveFileNavigation = liveFileNavigation;
    }

    /** Builds a provider that resolves the current global FTB file for every client-thread capture. */
    public static FtbProvider forMinecraft(Supplier<Evidence.Scope> scopeSource, Executor clientExecutor) {
        Objects.requireNonNull(scopeSource);
        return new FtbProvider(() -> null, clientExecutor, scopeSource, () -> ClientQuestFile.INSTANCE,
                () -> Minecraft.getInstance().isSameThread(), true);
    }

    /** Returns a copied client-thread token for the loaded file and team overlay. */
    public static String scopeToken() {
        var minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) throw new IllegalStateException("FTB Quests scope must be read on the client thread");
        var file = ClientQuestFile.INSTANCE;
        if (file == null) return "ftbquests:unavailable";
        var teamData = file.selfTeamData;
        var teamId = teamData == null ? "none" : String.valueOf(teamData.getTeamId());
        var locked = teamData == null ? "unknown" : Boolean.toString(teamData.isLocked());
        var folder = file.getFolder();
        return "ftbquests:" + Integer.toUnsignedString(System.identityHashCode(file)) + ":"
                + (folder == null ? "" : folder.toString()) + ":" + teamId + ":" + locked;
    }

    public static boolean isRuntimeAvailable() { return ClientQuestFile.INSTANCE != null; }

    @Override
    public String id() { return ID; }

    @Override
    public boolean enabled() { return !disabled; }

    @Override
    public ProviderStatus status() {
        if (liveFileNavigation) {
            if (failure != null) return new ProviderStatus(Availability.ERROR, VERSION, failure);
            if (disabled || liveFileNavigation && fileSource.get() == null
                    || !liveFileNavigation && ClientQuestFile.INSTANCE == null) return new ProviderStatus(Availability.UNAVAILABLE, VERSION,
                    "FTB Quests client data unavailable");
            return new ProviderStatus(Availability.AVAILABLE, VERSION, "FTB Quests live client file is available");
        }
        var snapshot = current;
        if (failure != null) return new ProviderStatus(Availability.ERROR, VERSION, failure);
        if (snapshot == null) return new ProviderStatus(Availability.UNAVAILABLE, VERSION, "FTB Quests client data unavailable");
        if (!scopeIsCurrent(snapshot.scope())) return new ProviderStatus(Availability.PARTIAL, VERSION,
                "FTB Quests client data belongs to an inactive scope");
        if (incomplete(snapshot)) return new ProviderStatus(Availability.PARTIAL, VERSION,
                "client quest file generation " + snapshot.generation() + " has partial records");
        return new ProviderStatus(Availability.AVAILABLE, VERSION, "client quest file generation " + snapshot.generation());
    }

    @Override
    public Toolbox.Metadata metadata() {
        return new Toolbox.Metadata(ID, List.of("quest"), false, List.of(), java.util.Map.of());
    }

    @Override
    public CompletionStage<Evidence.Result> searchDocument(PackSageTools.ReadRequest request, Toolbox.Context context) {
        var filter = request.arguments().values().get("filter");
        if (!(filter instanceof Evidence.JsonObject selected)
                || !(selected.values().get("provider") instanceof Evidence.JsonString provider)
                || !ID.equals(provider.value())) return CompletableFuture.failedFuture(
                new Toolbox.Failure(Toolbox.Code.NOT_HANDLED, "FTB Quests only handles provider-qualified document searches"));
        if (selected.values().get("types") instanceof Evidence.JsonArray types && !types.values().isEmpty()
                && types.values().stream().noneMatch(type -> type instanceof Evidence.JsonString string
                && "quest".equals(string.value()))) {
            return CompletableFuture.failedFuture(new Toolbox.Failure(Toolbox.Code.NOT_HANDLED,
                    "FTB Quests does not contribute the requested document type"));
        }
        if (!(request.arguments().values().get("query") instanceof Evidence.JsonString query)) {
            return CompletableFuture.failedFuture(new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS,
                    "search_document requires a query"));
        }
        if (!request.scope().equals(context.currentScope().get())) return CompletableFuture.failedFuture(
                new Toolbox.Failure(Toolbox.Code.STALE_SCOPE, "repeat the lookup in the current scope"));
        var arguments = new java.util.LinkedHashMap<String, Evidence.JsonValue>();
        arguments.put("text", query);
        var cursor = request.arguments().values().get("cursor") instanceof Evidence.JsonString value ? value.value() : null;
        String sourceCursor;
        try {
            if (cursor != null && !context.documentBudget().bytes(cursor.length() * 2))
                throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "FTB cursor exceeded the shared read budget");
            sourceCursor = decodeDocumentCursor(cursor, query.value(), request.scope());
        } catch (Toolbox.Failure failure) {
            return CompletableFuture.failedFuture(failure);
        }
        var limit = request.arguments().values().get("limit") instanceof Evidence.JsonNumber value
                ? value.value().intValue() : 16;
        if (liveFileNavigation) return searchLiveDocument(request, context, query.value(), sourceCursor, limit, null);
        try {
            return query(new Evidence.Query(Evidence.Operation.SEARCH, new Evidence.JsonObject(arguments), limit,
                    sourceCursor == null ? null : request.scope(), sourceCursor), request.scope()).thenApply(result -> {
                if (!request.scope().equals(context.currentScope().get())) throw new Toolbox.Failure(Toolbox.Code.STALE_SCOPE,
                        "scope changed while reading FTB Quests");
                if (result.coverage() == Evidence.Coverage.ERROR) throw new Toolbox.Failure(
                        result.detail() != null && result.detail().contains("cursor")
                                ? Toolbox.Code.INVALID_CURSOR : Toolbox.Code.EXTRACTION_FAILED,
                        result.detail() == null ? "FTB Quests extraction failed" : result.detail());
                return result.nextCursor() == null ? result : new Evidence.Result(result.records(), result.coverage(),
                        result.outcome(), encodeDocumentCursor(query.value(), result.nextCursor(), request.scope()), result.detail());
            });
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override
    public CompletionStage<Evidence.Result> readEvidence(PackSageTools.ReadRequest request, Toolbox.Context context) {
        var reference = context.reference(request);
        if (!ID.equals(reference.providerId())) return CompletableFuture.failedFuture(
                new Toolbox.Failure(Toolbox.Code.NOT_HANDLED, "FTB Quests does not own this reference"));
        if (reference.kind() != Evidence.RecordKind.QUEST) return CompletableFuture.failedFuture(
                new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE, "expected an observed FTB quest or chapter reference"));
        if (liveFileNavigation) {
            var arguments = new java.util.LinkedHashMap<String, Evidence.JsonValue>();
            arguments.put("query", new Evidence.JsonString(reference.sourceHandle()));
            arguments.put("filter", new Evidence.JsonObject(java.util.Map.of("provider", new Evidence.JsonString(ID),
                    "types", new Evidence.JsonArray(List.of(new Evidence.JsonString("quest"))))));
            arguments.put("limit", number(256));
            return searchLiveDocument(new PackSageTools.ReadRequest("search_document",
                    new Evidence.JsonObject(arguments), request.scope()), context, reference.sourceHandle(), null, 1,
                    reference.sourceHandle())
                    .thenApply(result -> {
                        if (result.records().isEmpty() && context.documentBudget().exhausted())
                            throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                                    "FTB live read budget ended before the observed record could be captured");
                        var record = result.records().stream().filter(value -> value.source().sourceHandle()
                                .equals(reference.sourceHandle())).findFirst().orElseThrow(() -> new Toolbox.Failure(
                                Toolbox.Code.UNAVAILABLE, "the observed quest is no longer available"));
                        return new Evidence.Result(List.of(record), record.source().coverage(), result.outcome(), result.nextCursor(),
                                result.detail());
                    });
        }
        return query(new Evidence.Query(Evidence.Operation.GET, new Evidence.JsonObject(java.util.Map.of(
                "id", new Evidence.JsonString(reference.sourceHandle()))), 1, null, null), request.scope())
                .thenApply(result -> {
                    if (!request.scope().equals(context.currentScope().get())) throw new Toolbox.Failure(
                            Toolbox.Code.STALE_SCOPE, "scope changed while reading FTB Quests");
                    if (result.records().isEmpty()) throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE,
                            "the observed quest or chapter is no longer available");
                    if (result.coverage() == Evidence.Coverage.ERROR) throw new Toolbox.Failure(
                            Toolbox.Code.EXTRACTION_FAILED, "current FTB Quests extraction failed");
                    return result;
                });
    }

    @Override
    public CompletionStage<Evidence.Result> readProgress(PackSageTools.ReadRequest request, Toolbox.Context context) {
        return readEvidence(request, context);
    }

    private CompletionStage<Evidence.Result> searchLiveDocument(PackSageTools.ReadRequest request, Toolbox.Context context,
                                                                  String query, String cursor, int limit, String exactSourceId) {
        if (cursor != null && !context.documentBudget().bytes(cursor.length() * 2)) return CompletableFuture.failedFuture(
                new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "FTB cursor exceeded the shared read budget"));
        var cursorParts = cursor == null ? new String[0] : cursor.split("\\|", -1);
        var cursorFile = cursorParts.length == 5 ? cursorParts[0] : null;
        var expectedOwner = cursorParts.length == 5 ? cursorParts[2] : "";
        var start = cursor == null ? 0 : cursorParts.length == 5 ? parseCursor(cursorParts[1]) : -1;
        var startPhase = -1;
        var startIndex = 0;
        try {
            if (cursor != null && cursorParts.length != 5) throw new NumberFormatException();
            if (cursor != null) {
                startPhase = Integer.parseInt(cursorParts[3]);
                startIndex = Integer.parseInt(cursorParts[4]);
                if (startPhase < -1 || startPhase > 4 || startIndex < 0
                        || startPhase == -1 && startIndex != 0) throw new NumberFormatException();
            }
        } catch (NumberFormatException failure) {
            return CompletableFuture.failedFuture(new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                    "FTB nested source position is invalid; restart the search"));
        }
        if (start < 0) return CompletableFuture.failedFuture(
                new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "FTB source position is invalid; restart the search"));
        int initialPhase = startPhase;
        int initialIndex = startIndex;
        return CompletableFuture.supplyAsync(() -> {
            var file = fileSource.get();
            var scope = scopeSource.get();
            if (disabled || file == null || fileSource.get() != file)
                throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "FTB Quests client file is unavailable");
            var fileIdentity = Integer.toString(System.identityHashCode(file));
            if (cursorFile != null && !cursorFile.equals(fileIdentity))
                throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "FTB cursor belongs to a replaced quest file; restart the search");
            if (!request.scope().equals(scope) || !request.scope().equals(context.currentScope().get()))
                throw new Toolbox.Failure(Toolbox.Code.STALE_SCOPE, "repeat the lookup in the current scope");
            var budget = context.documentBudget();
            Chapter exactChapter = null;
            Quest exactQuest = null;
            if (exactSourceId != null) {
                if (!budget.bytes(exactSourceId.length() * 2)) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                        "FTB source handle exceeded the shared read budget");
                var parsedId = parseQuestId(exactSourceId);
                if (parsedId.isEmpty()) throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE,
                        "the observed quest or chapter is no longer available");
                if (!budget.value()) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                        "FTB live read budget ended before exact source resolution");
                exactQuest = file.getQuest(parsedId.getAsLong());
                if (exactQuest != null && !budget.value()) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                        "FTB live read budget ended before exact source validation");
                if (exactQuest != null && !exactSourceId.equals(exactQuest.getCodeString())) exactQuest = null;
                if (!budget.value()) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                        "FTB live read budget ended before exact chapter resolution");
                exactChapter = file.getChapter(parsedId.getAsLong());
                if (exactChapter != null && !budget.value()) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                        "FTB live read budget ended before exact chapter validation");
                if (exactChapter != null && !exactSourceId.equals(exactChapter.getCodeString())) exactChapter = null;
                if (exactQuest == null && exactChapter == null) throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE,
                        "the observed quest or chapter is no longer available");
                if (exactQuest != null) {
                    if (!budget.value()) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                            "FTB live read budget ended before exact chapter validation");
                    exactChapter = exactQuest.getQuestChapter();
                }
            }
            var traversalStart = exactQuest == null ? start : 1;
            var traversalPhase = exactQuest == null ? initialPhase : -1;
            var traversalIndex = exactQuest == null ? initialIndex : 0;
            if (exactQuest != null && !budget.value()) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                    "FTB live read budget ended before exact chapter identity validation");
            var traversalExpectedOwner = exactQuest == null ? expectedOwner : exactChapter.getCodeString();
            var partial = false;
            if (!budget.value()) return new Evidence.Result(List.of(), Evidence.Coverage.PARTIAL,
                    Evidence.Outcome.UNKNOWN, null, "FTB live traversal reached the shared read budget");
            var player = Minecraft.getInstance().player;
            var playerId = player == null || !budget.value() ? null : player.getUUID();
            if (player != null && playerId == null) partial = true;
            if (!budget.value()) return new Evidence.Result(List.of(), Evidence.Coverage.PARTIAL,
                    Evidence.Outcome.UNKNOWN, null, "FTB live traversal reached the shared read budget");
            var team = file.selfTeamData;
            if (team != null && (!budget.value() || team.isLocked())) {
                if (budget.exhausted()) partial = true;
                team = null;
            }
            var currentTeam = team;
            var records = new ArrayList<Evidence.Record>();
            var position = 0;
            var next = (String) null;
            var previousOwner = "";
            if (exactChapter == null && !budget.value()) return new Evidence.Result(List.of(), Evidence.Coverage.PARTIAL,
                    Evidence.Outcome.UNKNOWN, null, "FTB live traversal reached the shared read budget");
            var chapterGroups = exactChapter == null ? file.getChapterGroups().iterator() : null;
            var chapterIterator = exactChapter == null ? List.<Chapter>of().iterator() : List.of(exactChapter).iterator();
            outer: while (true) {
                if (!chapterIterator.hasNext()) {
                    if (chapterGroups == null || !chapterGroups.hasNext()) break;
                    if (!budget.value()) {
                        if (position < traversalStart) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                                "FTB cursor cannot reach the next chapter group within the read budget; restart the search");
                        next = fileIdentity + "|" + position + "|" + previousOwner + "|-1|0";
                        partial = true;
                        break;
                    }
                    chapterIterator = chapterGroups.next().getChapters().iterator();
                    continue;
                }
                var candidate = position++;
                var seekingChapter = candidate < traversalStart;
                if (seekingChapter && (!budget.value() || !budget.bytes(64))) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                        "FTB cursor cannot reach its chapter within the read budget; restart the search");
                if (!seekingChapter && !budget.document()) {
                    next = fileIdentity + "|" + candidate + "|" + previousOwner + "|-1|0";
                    partial = true;
                    break;
                }
                if (!seekingChapter && (!budget.value() || !budget.bytes(64))) {
                    next = fileIdentity + "|" + candidate + "|" + previousOwner + "|-1|0";
                    partial = true;
                    break;
                }
                var chapter = chapterIterator.next();
                var chapterId = chapter.getCodeString();
                if (candidate == traversalStart - 1 && traversalPhase == -1 && !traversalExpectedOwner.equals(chapterId))
                    throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "FTB cursor owner changed; restart the search");
                if (candidate == traversalStart && traversalPhase >= 0
                        && ((traversalPhase != 3 && traversalPhase != 4) || !expectedOwner.equals(chapterId)))
                    throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "FTB cursor no longer identifies the same source; restart the search");
                previousOwner = chapterId;
                var emitChapter = candidate >= traversalStart
                        && (traversalPhase == -1 || traversalPhase == 3 || traversalPhase == 4);
                if (emitChapter && (!budget.bytes(64)
                        || !budget.value() || !budget.value() || !budget.value() || !budget.value() || !budget.value()
                        || !budget.value() || currentTeam != null && !budget.value())) {
                    next = fileIdentity + "|" + candidate + "|" + chapterId + "|4|0";
                    partial = true;
                    break;
                }
                var group = emitChapter ? chapter.getGroup() : null;
                var groupId = group == null ? "" : group.getCodeString();
                var chapterTitle = chapterId;
                var groupTitle = "";
                var chapterTitleComplete = false;
                if (emitChapter) {
                    try {
                        var rawChapterTitle = chapter.getRawTitle();
                        if (!budget.bytes(Math.min(rawChapterTitle.length(), 512) * 2)) {
                            next = fileIdentity + "|" + candidate + "|" + chapterId + "|4|0";
                            partial = true;
                            break outer;
                        }
                        chapterTitle = rawChapterTitle.length() > 512 ? rawChapterTitle.substring(0, 512) : rawChapterTitle;
                        chapterTitleComplete = rawChapterTitle.length() <= 512;
                        if (rawChapterTitle.isEmpty()) {
                            if (!budget.bytes(513 * 2)) {
                                next = fileIdentity + "|" + candidate + "|" + chapterId + "|4|0";
                                partial = true;
                                break outer;
                            }
                            chapterTitle = chapter.getTitle().getString(513);
                            if (chapterTitle.length() > 512) {
                                chapterTitle = chapterTitle.substring(0, 512);
                                chapterTitleComplete = false;
                            }
                        }
                    } catch (RuntimeException failure) {
                        chapterTitle = chapterId;
                        chapterTitleComplete = false;
                    }
                    if (group != null) {
                        try {
                            var rawGroupTitle = group.getRawTitle();
                            if (!budget.bytes(Math.min(rawGroupTitle.length(), 512) * 2)) {
                                next = fileIdentity + "|" + candidate + "|" + chapterId + "|4|0";
                                partial = true;
                                break outer;
                            }
                            groupTitle = rawGroupTitle.length() > 512 ? rawGroupTitle.substring(0, 512) : rawGroupTitle;
                            chapterTitleComplete &= rawGroupTitle.length() <= 512;
                            if (rawGroupTitle.isEmpty()) {
                                if (!budget.bytes(513 * 2)) {
                                    next = fileIdentity + "|" + candidate + "|" + chapterId + "|4|0";
                                    partial = true;
                                    break outer;
                                }
                                groupTitle = group.getTitle().getString(513);
                                if (groupTitle.length() > 512) {
                                    groupTitle = groupTitle.substring(0, 512);
                                    chapterTitleComplete = false;
                                }
                            }
                        } catch (RuntimeException failure) {
                            groupTitle = groupId;
                            chapterTitleComplete = false;
                        }
                    }
                }
                if (candidate < traversalStart && !budget.value()) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                        "FTB cursor cannot read the current chapter within the read budget; restart the search");
                var chapterQuests = chapter.getQuests();
                if (emitChapter) {
                    var questIds = new ArrayList<String>();
                    var questProgress = new ArrayList<ChapterChildProgress>();
                    var initialQuestIndex = candidate == traversalStart && traversalPhase == 3 ? traversalIndex : 0;
                    var questIndex = initialQuestIndex;
                    var chapterPartial = initialQuestIndex > 0;
                    if (candidate == traversalStart && traversalPhase == 3 && initialQuestIndex >= chapterQuests.size())
                        throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                                "FTB chapter child position no longer exists; restart the search");
                    while (questIndex < chapterQuests.size()) {
                        if (!budget.value() || currentTeam != null && !budget.value()
                                || !budget.bytes(128)) {
                            next = fileIdentity + "|" + candidate + "|" + chapterId + "|3|" + questIndex;
                            partial = true;
                            chapterPartial = true;
                            break;
                        }
                        var quest = chapterQuests.get(questIndex);
                        var questId = quest.getCodeString();
                        questIds.add(questId);
                        questProgress.add(new ChapterChildProgress(questId, null,
                                currentTeam == null ? null : currentTeam.isCompleted(quest)));
                        questIndex++;
                    }
                    var chapterRecord = new ChapterRecord(chapterId, chapterTitle, groupId, groupTitle, initialQuestIndex,
                            questIds, questProgress, !chapterPartial, null, chapterTitleComplete);
                    var snapshot = new Snapshot(scope, scope.requestGeneration(), List.of(chapterRecord), List.of(), List.of(),
                            ignored -> { }, new WeakReference<>(file), List.of());
                    java.util.function.Predicate<String> matches = value -> {
                        if (query.isEmpty()) return true;
                        for (var index = 0; index <= value.length() - query.length(); index++)
                            if (value.regionMatches(true, index, query, 0, query.length())) return true;
                        return false;
                    };
                    if (matches.test(chapterId) || matches.test(chapterTitle) || matches.test(groupId)
                            || matches.test(groupTitle) || questIds.stream().anyMatch(matches))
                        records.add(toRecord(chapterRecord, snapshot));
                    if (next != null || records.size() >= limit) {
                        if (next == null) next = fileIdentity + "|" + (candidate + 1) + "|" + chapterId + "|-1|0";
                        break;
                    }
                }
                var questList = exactQuest != null ? List.of(exactQuest) : chapterQuests;
                var skippedQuests = 0;
                if (candidate < traversalStart && exactQuest == null) {
                    if (!budget.value() || !budget.bytes(16)) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                            "FTB cursor cannot read the chapter quest count within the read budget; restart the search");
                    skippedQuests = Math.min(questList.size(), traversalStart - position);
                    if (skippedQuests > 0) {
                        if (!budget.value()) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                                "FTB cursor cannot read the preceding quest within the read budget; restart the search");
                        previousOwner = questList.get(skippedQuests - 1).getCodeString();
                        if (!budget.bytes(previousOwner.length() * 2)) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                                "FTB cursor cannot read the preceding quest identity within the read budget; restart the search");
                        position += skippedQuests;
                        if (position == traversalStart && traversalPhase == -1 && !expectedOwner.equals(previousOwner))
                            throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "FTB cursor owner changed; restart the search");
                    }
                }
                if (position == traversalStart && traversalPhase == -1 && skippedQuests == 0
                        && !expectedOwner.equals(chapterId))
                    throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "FTB cursor owner changed; restart the search");
                var questIterator = questList.listIterator(skippedQuests);
                var questIndex = skippedQuests;
                while (questIterator.hasNext()) {
                    var questCandidate = position++;
                    var seekingQuest = questCandidate < traversalStart;
                    if (seekingQuest && (!budget.value() || !budget.bytes(64))) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                            "FTB cursor cannot reach its quest within the read budget; restart the search");
                    if (!seekingQuest && !budget.document()) {
                        next = fileIdentity + "|" + questCandidate + "|" + previousOwner + "|-1|0";
                        partial = true;
                        break outer;
                    }
                    if (!seekingQuest && (!budget.value() || !budget.bytes(64))) {
                        next = fileIdentity + "|" + questCandidate + "|" + previousOwner + "|-1|0";
                        partial = true;
                        break outer;
                    }
                    var quest = questIterator.next();
                    var id = quest.getCodeString();
                    if (questCandidate == traversalStart - 1 && traversalPhase == -1 && !traversalExpectedOwner.equals(id))
                        throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "FTB cursor owner changed; restart the search");
                    if (questCandidate < traversalStart) {
                        previousOwner = id;
                        questIndex++;
                        continue;
                    }
                    var phase = questCandidate == traversalStart ? traversalPhase : 0;
                    if (questCandidate == traversalStart && traversalPhase >= 0
                            && !traversalExpectedOwner.equals(id))
                        throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "FTB cursor no longer identifies the same source; restart the search");
                    if (phase == 3) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                            "FTB cursor phase does not match its source; restart the search");
                    if (!budget.bytes(64)) {
                        next = fileIdentity + "|" + questCandidate + "|" + id + "|0|0";
                        partial = true;
                        break outer;
                    }
                    var nestedStart = questCandidate == traversalStart && phase >= 0 ? traversalIndex : 0;
                    var dependencies = new ArrayList<String>();
                    var tasks = new ArrayList<TaskRecord>();
                    var rewards = new ArrayList<RewardRecord>();
                    var progressTasks = new ArrayList<TaskRecord>();
                    var progressRewards = new ArrayList<RewardRecord>();
                    if (!budget.value() || !budget.value() || !budget.value() || !budget.value() || !budget.value()
                            || currentTeam != null && (!budget.value() || !budget.value() || !budget.value()
                            || !budget.value() || !budget.value()) || !budget.value() || !budget.value()
                            || !budget.value()) {
                        next = fileIdentity + "|" + questCandidate + "|" + id + "|" + (phase < 0 ? 0 : phase)
                                + "|" + nestedStart;
                        partial = true;
                        break outer;
                    }
                    var questTasks = quest.getTasksAsList();
                    var rawTitle = quest.getRawTitle();
                    if (!budget.bytes(Math.min(rawTitle.length(), 512) * 2)) {
                        next = fileIdentity + "|" + questCandidate + "|" + id + "|" + (phase < 0 ? 0 : phase)
                                + "|" + nestedStart;
                        partial = true;
                        break outer;
                    }
                    var titleTruncated = rawTitle.length() > 512;
                    if (titleTruncated) rawTitle = rawTitle.substring(0, 512);
                    var titleComplete = !titleTruncated;
                    String questTitle;
                    if (titleTruncated) {
                        questTitle = rawTitle;
                    } else {
                        try {
                            if (!rawTitle.isEmpty()) {
                                if (!budget.bytes(513 * 2)) {
                                    next = fileIdentity + "|" + questCandidate + "|" + id + "|" + (phase < 0 ? 0 : phase)
                                            + "|" + nestedStart;
                                    partial = true;
                                    break outer;
                                }
                                questTitle = quest.getTitle().getString(513);
                            } else {
                                var firstTask = questTasks.isEmpty() ? null : questTasks.getFirst();
                                var firstTaskHasKnownTitle = firstTask == null
                                        || firstTask.getClass() == AdvancementTask.class || firstTask.getClass() == BiomeTask.class
                                        || firstTask.getClass() == CheckmarkTask.class || firstTask.getClass() == DimensionTask.class
                                        || firstTask.getClass() == EnergyTask.class || firstTask.getClass() == FluidTask.class
                                        || firstTask.getClass() == ItemTask.class || firstTask.getClass() == KillTask.class
                                        || firstTask.getClass() == LocationTask.class || firstTask.getClass() == ObservationTask.class
                                        || firstTask.getClass() == StageTask.class || firstTask.getClass() == StatTask.class
                                        || firstTask.getClass() == StructureTask.class || firstTask.getClass() == XPTask.class
                                        || firstTask.getClass() == ForgeEnergyTask.class;
                                if (!firstTaskHasKnownTitle) {
                                    questTitle = id;
                                    titleComplete = false;
                                } else if (firstTask != null && firstTask.getRawTitle().length() > 512) {
                                    if (!budget.bytes(512 * 2)) {
                                        next = fileIdentity + "|" + questCandidate + "|" + id + "|" + (phase < 0 ? 0 : phase)
                                                + "|" + nestedStart;
                                        partial = true;
                                        break outer;
                                    }
                                    questTitle = firstTask.getRawTitle().substring(0, 512);
                                    titleComplete = false;
                                } else {
                                    if (!budget.bytes(513 * 2)) {
                                        next = fileIdentity + "|" + questCandidate + "|" + id + "|" + (phase < 0 ? 0 : phase)
                                                + "|" + nestedStart;
                                        partial = true;
                                        break outer;
                                    }
                                    questTitle = quest.getTitle().getString(513);
                                }
                            }
                        } catch (RuntimeException failure) {
                            questTitle = rawTitle.isEmpty() ? id : rawTitle;
                            titleComplete = false;
                            partial = true;
                        }
                    }
                    if (questTitle.length() > 512) {
                        questTitle = questTitle.substring(0, 512);
                        titleComplete = false;
                    }
                    if (questTitle.isEmpty()) {
                        questTitle = id;
                        titleComplete = false;
                    }
                    var dependencyIndex = 0;
                    if (phase <= 0) {
                        var dependencyIterator = quest.streamDependencies().iterator();
                        while (dependencyIterator.hasNext()) {
                            if (!budget.value()) {
                                if (phase == 0 && nestedStart > 0 && dependencyIndex <= nestedStart)
                                    throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                                            "FTB dependency cursor cannot be reached within the read budget; restart or narrow the search");
                                next = fileIdentity + "|" + questCandidate + "|" + id + "|0|"
                                        + Math.max(dependencyIndex, phase == 0 ? nestedStart : 0);
                                partial = true;
                                break;
                            }
                            var sourceIndex = dependencyIndex;
                            var dependency = dependencyIterator.next();
                            dependencyIndex++;
                            if (sourceIndex >= (phase == 0 ? nestedStart : 0)) {
                                if (!budget.bytes(128)) {
                                    next = fileIdentity + "|" + questCandidate + "|" + id + "|0|" + sourceIndex;
                                    partial = true;
                                    break;
                                }
                                dependencies.add(dependency.getCodeString());
                            }
                        }
                    }
                    if (next == null && phase == 0 && nestedStart > 0 && nestedStart >= dependencyIndex)
                        throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                                "FTB dependency position no longer exists; restart the search");
                    var dependencyRangeComplete = false;
                    if (phase <= 0 && next == null) dependencyRangeComplete = nestedStart == 0;
                    var selectedPhase = phase < 0 ? 0 : phase;
                    if (next == null && selectedPhase <= 0) selectedPhase = 1;
                    if (next == null && selectedPhase <= 1 && !budget.value()) {
                        next = fileIdentity + "|" + questCandidate + "|" + id + "|1|0";
                        partial = true;
                    }
                    var taskStart = selectedPhase == 1 && phase == 1 ? nestedStart : 0;
                    var taskIndex = taskStart;
                    if (taskStart > 0 && taskStart >= questTasks.size()) throw new Toolbox.Failure(
                            Toolbox.Code.INVALID_CURSOR, "FTB task position no longer exists; restart the search");
                    while (next == null && selectedPhase == 1 && taskIndex < questTasks.size()) {
                        if (!budget.value()) {
                            next = fileIdentity + "|" + questCandidate + "|" + id + "|1|" + taskIndex;
                            partial = true;
                            break;
                        }
                        if (!budget.value() || !budget.value() || !budget.value()
                                || !budget.value() || !budget.value() || !budget.value() || !budget.value()
                                || !budget.value() || !budget.value() || !budget.value() || !budget.value()
                                || !budget.value() || !budget.value() || !budget.value() || !budget.bytes(1024)) {
                            next = fileIdentity + "|" + questCandidate + "|" + id + "|1|" + taskIndex;
                            partial = true;
                            break;
                        }
                        var task = questTasks.get(taskIndex++);
                        var nativeTask = task.getClass() == AdvancementTask.class || task.getClass() == BiomeTask.class
                                || task.getClass() == CheckmarkTask.class || task.getClass() == DimensionTask.class
                                || task.getClass() == EnergyTask.class || task.getClass() == FluidTask.class
                                || task.getClass() == ItemTask.class || task.getClass() == KillTask.class
                                || task.getClass() == LocationTask.class || task.getClass() == ObservationTask.class
                                || task.getClass() == StageTask.class || task.getClass() == StatTask.class
                                || task.getClass() == StructureTask.class || task.getClass() == XPTask.class
                                || task.getClass() == ForgeEnergyTask.class;
                        var taskId = task.getCodeString();
                        var className = task.getClass().getName();
                        var type = nativeTask ? task.getType().getTypeId().toString()
                                : "unsupported:" + (className.length() > 128 ? className.substring(0, 128) : className);
                        var maximum = nativeTask ? task.getMaxProgress() : -1;
                        var progress = currentTeam == null ? null : currentTeam.getProgress(task);
                        var completed = currentTeam == null ? null : currentTeam.isCompleted(task);
                        String itemId = null;
                        Long itemCount = null;
                        if (task.getClass() == ItemTask.class) {
                            var itemStack = ((ItemTask) task).getItemStack();
                            if (itemStack != null && !itemStack.isEmpty())
                                itemId = BuiltInRegistries.ITEM.getKey(itemStack.getItem()).toString();
                            if (maximum >= 0) itemCount = maximum;
                        }
                        tasks.add(new TaskRecord(taskId, type, null, maximum < 0 ? null : maximum, null,
                                itemId, itemCount, nativeTask && supportedType(type)));
                        progressTasks.add(new TaskRecord(taskId, type, progress, maximum < 0 ? null : maximum,
                                completed, itemId, itemCount, nativeTask && supportedType(type)));
                    }
                    var taskRangeComplete = selectedPhase == 1 && next == null && taskStart == 0;
                    if (next == null && selectedPhase == 1) selectedPhase = 2;
                    if (next == null && selectedPhase <= 2 && !budget.value()) {
                        next = fileIdentity + "|" + questCandidate + "|" + id + "|2|0";
                        partial = true;
                    }
                    var questRewards = next == null && selectedPhase == 2 ? quest.getRewards() : List.<Reward>of();
                    var rewardStart = selectedPhase == 2 && phase == 2 ? nestedStart : 0;
                    var rewardIndex = rewardStart;
                    if (rewardStart > 0 && rewardStart >= questRewards.size()) throw new Toolbox.Failure(
                            Toolbox.Code.INVALID_CURSOR, "FTB reward position no longer exists; restart the search");
                    while (next == null && selectedPhase == 2 && rewardIndex < questRewards.size()) {
                        if (!budget.value()) {
                            next = fileIdentity + "|" + questCandidate + "|" + id + "|2|" + rewardIndex;
                            partial = true;
                            break;
                        }
                        if (!budget.value() || !budget.value() || !budget.value()
                                || !budget.value() || !budget.value() || !budget.value() || !budget.value()
                                || !budget.value() || !budget.value() || !budget.value() || !budget.value()
                                || !budget.value() || !budget.bytes(1024)) {
                            next = fileIdentity + "|" + questCandidate + "|" + id + "|2|" + rewardIndex;
                            partial = true;
                            break;
                        }
                        var reward = (Reward) ((List<?>) questRewards).get(rewardIndex++);
                        var nativeReward = reward.getClass() == AdvancementReward.class
                                || reward.getClass() == AllTableReward.class || reward.getClass() == ChoiceReward.class
                                || reward.getClass() == CommandReward.class || reward.getClass() == CurrencyReward.class
                                || reward.getClass() == ItemReward.class || reward.getClass() == LootReward.class
                                || reward.getClass() == RandomReward.class || reward.getClass() == StageReward.class
                                || reward.getClass() == ToastReward.class || reward.getClass() == XPLevelsReward.class
                                || reward.getClass() == XPReward.class;
                        var rewardId = reward.getCodeString();
                        var className = reward.getClass().getName();
                        var type = nativeReward ? reward.getType().getTypeId().toString()
                                : "unsupported:" + (className.length() > 128 ? className.substring(0, 128) : className);
                        var claimed = currentTeam == null || playerId == null ? null
                                : currentTeam.isRewardClaimed(playerId, reward);
                        String itemId = null;
                        Long count = null;
                        if (reward.getClass() == ItemReward.class) {
                            var itemStack = ((ItemReward) reward).getItem();
                            if (itemStack != null && !itemStack.isEmpty())
                                itemId = BuiltInRegistries.ITEM.getKey(itemStack.getItem()).toString();
                            count = (long) ((ItemReward) reward).getCount();
                        }
                        rewards.add(new RewardRecord(rewardId, type, null, itemId, count,
                                nativeReward && supportedType(type)));
                        progressRewards.add(new RewardRecord(rewardId, type, claimed, itemId, count,
                                nativeReward && supportedType(type)));
                    }
                    var rewardRangeComplete = selectedPhase == 2 && next == null && rewardStart == 0;
                    var completedQuest = next == null;
                    previousOwner = id;
                    var requirement = Snapshot.dependencyRequirement(quest);
                    var oneDependency = "one_completed".equals(requirement) || "one_started".equals(requirement);
                    var questRecord = new QuestRecord(id, questTitle, chapterId, dependencies,
                            oneDependency ? dependencies.stream().map(List::of).toList()
                                    : dependencies.isEmpty() ? List.of() : List.of(List.copyOf(dependencies)),
                            quest.getMinRequiredDependencies(), tasks, rewards, requirement,
                            Snapshot.maximumCompletableDependencies(quest), rawTitle,
                            phase == 0 ? nestedStart : 0, dependencyRangeComplete,
                            taskStart, taskRangeComplete, rewardStart, rewardRangeComplete, titleComplete);
                    var progress = new Progress(id,
                            null, null, null, currentTeam == null ? null : currentTeam.isCompleted(quest), null,
                            progressTasks, progressRewards);
                    var snapshot = new Snapshot(scope, scope.requestGeneration(), List.of(), List.of(questRecord),
                            List.of(progress), ignored -> { }, new WeakReference<>(file), List.of());
                    java.util.function.Predicate<String> matches = value -> {
                        if (query.isEmpty()) return true;
                        for (var index = 0; index <= value.length() - query.length(); index++)
                            if (value.regionMatches(true, index, query, 0, query.length())) return true;
                        return false;
                    };
                    if (matches.test(id) || matches.test(rawTitle) || matches.test(questTitle)
                            || matches.test(questRecord.chapter()) || questRecord.dependencies().stream().anyMatch(matches)
                            || questRecord.tasks().stream().anyMatch(value -> matches.test(value.id())
                            || matches.test(value.type()) || value.itemId() != null && matches.test(value.itemId()))
                            || questRecord.rewards().stream().anyMatch(value -> matches.test(value.id())
                            || matches.test(value.type()) || value.itemId() != null && matches.test(value.itemId())))
                        records.add(toRecord(questRecord, snapshot));
                    questIndex++;
                    if (records.size() >= limit || next != null) {
                        if (records.size() >= limit && completedQuest && next == null)
                            next = fileIdentity + "|" + position + "|" + id + "|-1|0";
                        break outer;
                    }
                }
            }
            if (!request.scope().equals(context.currentScope().get()) || !request.scope().equals(scopeSource.get())
                     || fileSource.get() != file)
                throw new Toolbox.Failure(Toolbox.Code.STALE_SCOPE, "scope or FTB file changed while reading quests");
            var coverage = partial || records.stream().anyMatch(record -> record.source().coverage() != Evidence.Coverage.COMPLETE)
                    ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE;
            return new Evidence.Result(records, coverage, coverage == Evidence.Coverage.COMPLETE
                    ? Evidence.Outcome.COMPLETE : Evidence.Outcome.PARTIAL,
                    next == null ? null : encodeDocumentCursor(query, next, request.scope()),
                    budget.exhausted() ? "FTB live traversal reached the shared read budget" : null);
        }, clientExecutor);
    }

    private String encodeDocumentCursor(String query, String position, Evidence.Scope scope) {
        var cursor = "ftb:" + UUID.randomUUID();
        synchronized (documentCursors) {
            documentCursors.put(cursor, new DocumentCursorState(query, position, scope));
            if (documentCursors.size() > 128) documentCursors.remove(documentCursors.keySet().iterator().next());
        }
        return cursor;
    }

    private String decodeDocumentCursor(String cursor, String query, Evidence.Scope scope) {
        if (cursor == null) return null;
        try {
            if (cursor.length() > 1024 || !cursor.startsWith("ftb:")) throw new IllegalArgumentException();
            DocumentCursorState saved;
            synchronized (documentCursors) {
                saved = documentCursors.get(cursor);
            }
            if (saved == null || !query.equals(saved.query()) || !sameScope(scope, saved.scope())
                    || saved.position().isBlank()) throw new IllegalArgumentException();
            return saved.position();
        } catch (RuntimeException failure) {
            throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "FTB Quests cursor is invalid for this query or scope; restart the search");
        }
    }

    public void refresh() {
        disabled = false;
        failure = null;
        var generation = ++lifecycleGeneration;
        if (liveFileNavigation) {
            current = null;
            return;
        }
        try {
            clientExecutor.execute(() -> {
                if (generation != lifecycleGeneration || disabled) return;
                try {
                    var snapshot = snapshotSource.get();
                    if (generation != lifecycleGeneration || disabled) return;
                    if (snapshot == null) {
                        current = null;
                        failure = null;
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
        if (liveFileNavigation) {
            var id = query.operation() == Evidence.Operation.GET ? stringArgument(query.arguments(), "id") : null;
            var text = query.operation() == Evidence.Operation.SEARCH ? stringArgument(query.arguments(), "text") : "";
            if (query.operation() == Evidence.Operation.GET && id == null || query.operation() == Evidence.Operation.SEARCH && text == null)
                return CompletableFuture.completedFuture(malformed("FTB query is missing a required identifier or search text"));
            if (query.operation() != Evidence.Operation.GET && query.operation() != Evidence.Operation.SEARCH)
                return CompletableFuture.completedFuture(malformed("unsupported live FTB query operation"));
            if (query.cursorScope() != null && !sameScope(query.cursorScope(), scope))
                return CompletableFuture.completedFuture(malformed("FTB cursor belongs to another scope"));
            try {
                var context = new Toolbox.Context(scopeSource, java.util.Map.of(), new dev.packsage.knowledge.AdapterRegistry(),
                        new Toolbox.DocumentBudget(), null);
                if (query.cursor() != null && query.cursor().startsWith("ftb:")
                        && !context.documentBudget().bytes(query.cursor().length() * 2))
                    throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "FTB cursor exceeded the shared read budget");
                var sourceCursor = query.cursor() != null && query.cursor().startsWith("ftb:")
                        ? decodeDocumentCursor(query.cursor(), text, scope) : query.cursor();
                return searchLiveDocument(new PackSageTools.ReadRequest("search_document", query.arguments(), scope),
                        context, text, sourceCursor, query.limit(), id).thenApply(result -> {
                    if (result.nextCursor() == null) return result;
                    var position = decodeDocumentCursor(result.nextCursor(), text, scope);
                    return new Evidence.Result(result.records(), result.coverage(), result.outcome(), position, result.detail());
                });
            } catch (RuntimeException failure) {
                return CompletableFuture.completedFuture(malformed(failure.getMessage()));
            }
        }
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
                    failure = null;
                    return unavailableResult("FTB Quests client data unavailable");
                }
                if (disabled || generation != lifecycleGeneration)
                    return unavailableResult("FTB Quests client data unavailable");
                if (!sameScope(snapshot.scope(), scope) || !scopeIsCurrent(snapshot.scope())
                        || query.cursor() != null && query.cursorScope() != null
                        && !sameScope(query.cursorScope(), scope)) {
                    return new Evidence.Result(List.of(), Evidence.Coverage.PARTIAL, Evidence.Outcome.UNKNOWN, null);
                }
                if (!liveFileNavigation) current = snapshot;
                return querySnapshot(query, scope, snapshot);
            }, clientExecutor);
        } catch (RuntimeException schedulingFailure) {
            return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.ERROR,
                    Evidence.Outcome.ERROR, null));
        }
    }

    @Override
    public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
        if (liveFileNavigation) {
            if (disabled) return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE,
                    "FTB Quests client data unavailable");
            if (!ID.equals(target.providerId()) || !target.scope().equals(scope) || !scopeIsCurrent(scope))
                return new Evidence.NavigationResult(Evidence.NavigationStatus.STALE,
                        "quest target is outside the active scope");
            if (!target.sourceHandle().equals(target.targetId()))
                return new Evidence.NavigationResult(Evidence.NavigationStatus.STALE,
                        "quest target identity does not match its source");
            if (!clientThread.getAsBoolean()) return new Evidence.NavigationResult(Evidence.NavigationStatus.UNSUPPORTED,
                    "FTB Quests navigation must run on the client thread");
            try {
                var file = fileSource.get();
                if (file == null || fileSource.get() != file) return new Evidence.NavigationResult(
                        Evidence.NavigationStatus.UNAVAILABLE, "FTB Quests client data unavailable");
                var parsedId = parseQuestId(target.targetId());
                if (parsedId.isEmpty()) return new Evidence.NavigationResult(Evidence.NavigationStatus.ERROR,
                        "malformed quest id");
                var quest = file.getQuest(parsedId.getAsLong());
                if (quest == null || !quest.getCodeString().equals(target.targetId())) return new Evidence.NavigationResult(
                        Evidence.NavigationStatus.STALE, "quest target is absent from the current quest file");
                ClientQuestFile.openGui(quest, true);
                return new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED, "quest screen opened");
            } catch (RuntimeException navigationFailure) {
                return new Evidence.NavigationResult(Evidence.NavigationStatus.ERROR, navigationFailure.toString());
            }
        }
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
            snapshot.navigator().accept(target.targetId());
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
        values.put("titleComplete", new Evidence.JsonBoolean(chapter.titleComplete()));
        values.put("visible", nullableBoolean(chapter.visible()));
        values.put("questOffset", number(chapter.questOffset()));
        values.put("questRangeComplete", new Evidence.JsonBoolean(chapter.childRangeComplete()));
        values.put("quests", strings(chapter.quests()));
        values.put("questProgress", new Evidence.JsonArray(chapter.questProgress().stream().map(value ->
                (Evidence.JsonValue) new Evidence.JsonObject(java.util.Map.of("questId", new Evidence.JsonString(value.questId()),
                        "visible", nullableBoolean(value.visible()), "completed", nullableBoolean(value.completed())))).toList()));
        var coverage = chapter.visible() == null || !chapter.childRangeComplete() || !chapter.titleComplete()
                || chapter.questProgress().stream().anyMatch(value -> value.visible() == null || value.completed() == null)
                ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE;
        var source = new Evidence.Source(snapshot.scope(), ID, VERSION, chapter.id(), snapshot.generation(), Instant.now(),
                Evidence.Activity.ACTIVE, coverage, Evidence.Authority.LOADED, List.of("ftbquests:client-quest-file"));
        return new Evidence.Record(source, Evidence.RecordKind.QUEST, chapter.title(), new Evidence.JsonObject(values),
                List.of());
    }

    private static Evidence.Record toRecord(QuestRecord quest, Snapshot snapshot) {
        var progress = snapshot.progress().stream().filter(value -> value.questId().equals(quest.id())).findFirst().orElse(null);
        var tasks = progress == null ? quest.tasks() : progress.tasks();
        var rewards = progress == null ? quest.rewards() : progress.rewards();
        var values = new java.util.LinkedHashMap<String, Evidence.JsonValue>();
        values.put("questId", new Evidence.JsonString(quest.id()));
        values.put("rawTitle", new Evidence.JsonString(quest.rawTitle() == null ? quest.title() : quest.rawTitle()));
        values.put("titleComplete", new Evidence.JsonBoolean(quest.titleComplete()));
        values.put("chapter", new Evidence.JsonString(quest.chapter()));
        values.put("dependencyOffset", number(quest.dependencyOffset()));
        values.put("dependencyRangeComplete", new Evidence.JsonBoolean(quest.dependencyRangeComplete()));
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
        values.put("taskOffset", number(quest.taskOffset()));
        values.put("taskRangeComplete", new Evidence.JsonBoolean(quest.taskRangeComplete()));
        values.put("rewards", new Evidence.JsonArray(rewards.stream().map(FtbProvider::rewardJson)
                .map(value -> (Evidence.JsonValue) value).toList()));
        values.put("rewardOffset", number(quest.rewardOffset()));
        values.put("rewardRangeComplete", new Evidence.JsonBoolean(quest.rewardRangeComplete()));
        var opaque = tasks.stream().anyMatch(task -> !task.supported() || !taskMetadataComplete(task))
                || rewards.stream().anyMatch(reward -> !reward.supported() || !rewardMetadataComplete(reward));
        var progressUnknown = progress == null || progress.dependenciesVisible() == null
                || progress.dependenciesComplete() == null || progress.visible() == null
                || progress.completed() == null || progress.rewardClaimed() == null;
        var coverage = opaque || progressUnknown || quest.dependencyRequirement() == null
                || !quest.dependencyRangeComplete() || !quest.taskRangeComplete() || !quest.rewardRangeComplete()
                || !quest.titleComplete()
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
        values.put("metadataComplete", new Evidence.JsonBoolean(taskMetadataComplete(task)));
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
        values.put("metadataComplete", new Evidence.JsonBoolean(rewardMetadataComplete(reward)));
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

    private static boolean taskMetadataComplete(TaskRecord task) {
        return task.supported() && ("ftbquests:checkmark".equals(task.type())
                || "ftbquests:item".equals(task.type()) && task.itemId() != null && task.itemCount() != null);
    }

    private static boolean rewardMetadataComplete(RewardRecord reward) {
        return reward.supported() && "ftbquests:item".equals(reward.type())
                && reward.itemId() != null && reward.count() != null;
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
                || snapshot.quests().stream().anyMatch(quest -> quest.tasks().stream()
                .anyMatch(task -> !task.supported() || !taskMetadataComplete(task))
                || quest.rewards().stream().anyMatch(reward -> !reward.supported() || !rewardMetadataComplete(reward)))
                || snapshot.progress().stream().anyMatch(progress -> progress.tasks().stream()
                .anyMatch(task -> !task.supported() || !taskMetadataComplete(task))
                || progress.rewards().stream().anyMatch(reward -> !reward.supported()
                || !rewardMetadataComplete(reward)));
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
