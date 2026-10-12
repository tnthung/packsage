package dev.packsage.knowledge.ftb;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.ftb.mods.ftbquests.client.ClientQuestFile;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.Task;
import dev.ftb.mods.ftbquests.quest.reward.Reward;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Client-visible FTB Quests records and a separately replaceable team overlay. */
public final class FtbProvider implements KnowledgeProvider {
    public static final String ID = "ftb_quests";
    public static final String VERSION = "2101.1.36";

    public record TaskRecord(String id, String type, long progress, Long maximum, boolean completed) {
        public TaskRecord {
            Objects.requireNonNull(id);
            Objects.requireNonNull(type);
        }
    }

    public record RewardRecord(String id, String type, boolean claimed) {
        public RewardRecord {
            Objects.requireNonNull(id);
            Objects.requireNonNull(type);
        }
    }

    public record QuestRecord(String id, String title, String chapter, List<String> dependencies,
                              List<List<String>> dependencyBranches, int minimumDependencies,
                              List<TaskRecord> tasks, List<RewardRecord> rewards) {
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
    }

    public record Progress(String questId, boolean visible, boolean dependenciesVisible, boolean dependenciesComplete,
                           boolean completed, boolean rewardClaimed, List<TaskRecord> tasks, List<RewardRecord> rewards) {
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

    public record Snapshot(Evidence.Scope scope, long generation, List<QuestRecord> quests,
                           List<Progress> progress, Consumer<String> navigator) {
        public Snapshot {
            Objects.requireNonNull(scope);
            quests = List.copyOf(quests);
            progress = List.copyOf(progress);
            Objects.requireNonNull(navigator);
        }

        public static Snapshot capture(ClientQuestFile file, Evidence.Scope scope, UUID playerId) {
            var teamData = file.selfTeamData;
            var quests = new ArrayList<QuestRecord>();
            var progress = new ArrayList<Progress>();
            file.getAllChapters().stream().flatMap(chapter -> chapter.getQuests().stream()).forEach(quest -> {
                var dependencies = quest.streamDependencies().map(dependency -> dependency.getCodeString()).toList();
                var tasks = quest.getTasks().stream().map(task -> taskRecord(task, teamData)).toList();
                var rewards = quest.getRewards().stream().map(reward -> rewardRecord(reward, teamData, playerId)).toList();
                quests.add(new QuestRecord(quest.getCodeString(), quest.getRawTitle(),
                        quest.getQuestChapter() == null ? "" : quest.getQuestChapter().getCodeString(), dependencies,
                        List.of(), quest.getMinRequiredDependencies(), tasks, rewards));
                progress.add(new Progress(quest.getCodeString(), quest.isVisible(teamData), quest.areDependenciesVisible(teamData),
                        quest.areDependenciesComplete(teamData), quest.isCompletedRaw(teamData),
                        !quest.hasUnclaimedRewardsRaw(teamData, playerId), tasks, rewards));
            });
            return new Snapshot(scope, scope.requestGeneration(), quests, progress,
                    id -> {
                        var quest = file.getQuest(QuestObjectBase.parseCodeString(id));
                        if (quest != null) ClientQuestFile.openGui(quest, true);
                    });
        }

        private static TaskRecord taskRecord(Task task, TeamData teamData) {
            var progress = teamData.getProgress(task);
            var maximum = task.getMaxProgress();
            return new TaskRecord(task.getCodeString(), task.getType().getTypeId().toString(), progress,
                    maximum < 0 ? null : maximum, teamData.isCompleted(task));
        }

        private static RewardRecord rewardRecord(Reward reward, TeamData teamData, UUID playerId) {
            return new RewardRecord(reward.getCodeString(), reward.getType().getTypeId().toString(),
                    teamData.isRewardClaimed(playerId, reward));
        }
    }

    private final Supplier<Snapshot> snapshotSource;
    private final Executor clientExecutor;
    private volatile Snapshot current;

    public FtbProvider(Supplier<Snapshot> snapshotSource, Executor clientExecutor) {
        this.snapshotSource = Objects.requireNonNull(snapshotSource);
        this.clientExecutor = Objects.requireNonNull(clientExecutor);
    }

    public FtbProvider(ClientQuestFile file, Supplier<Evidence.Scope> scopeSource, UUID playerId, Executor clientExecutor) {
        this(() -> Snapshot.capture(file, scopeSource.get(), playerId), clientExecutor);
    }

    @Override
    public String id() { return ID; }

    @Override
    public ProviderStatus status() {
        var snapshot = current;
        if (snapshot == null) return new ProviderStatus(Availability.UNAVAILABLE, VERSION, "FTB Quests client data unavailable");
        return new ProviderStatus(snapshot.quests().isEmpty() ? Availability.PARTIAL : Availability.AVAILABLE,
                VERSION, "client quest file generation " + snapshot.generation());
    }

    public void refresh() {
        current = snapshotSource.get();
    }

    public void unavailable() {
        current = null;
    }

    @Override
    public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
        return CompletableFuture.supplyAsync(snapshotSource, clientExecutor).thenApply(snapshot -> {
            current = snapshot;
            if (!sameScope(snapshot.scope(), scope) || query.cursor() != null && query.cursorScope() != null
                    && !sameScope(query.cursorScope(), scope)) return new Evidence.Result(List.of(), Evidence.Coverage.PARTIAL,
                    Evidence.Outcome.UNKNOWN, null);
            var start = query.cursor() == null ? 0 : parseCursor(query.cursor());
            if (start < 0) return new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null);
            var text = argument(query.arguments(), "text").toLowerCase(Locale.ROOT);
            var id = argument(query.arguments(), "id");
            var records = new ArrayList<Evidence.Record>();
            for (var index = start; index < snapshot.quests().size() && records.size() < query.limit(); index++) {
                var quest = snapshot.quests().get(index);
                if (query.operation() == Evidence.Operation.GET && id != null && !id.equals(quest.id())) continue;
                if (query.operation() == Evidence.Operation.SEARCH && !matches(quest, text)) continue;
                records.add(toRecord(quest, snapshot.progress().stream().filter(value -> value.questId().equals(quest.id())).findFirst().orElse(null), snapshot));
            }
            var next = start + records.size() < snapshot.quests().size() ? Integer.toString(start + records.size()) : null;
            var coverage = records.stream().anyMatch(record -> record.source().coverage() == Evidence.Coverage.PARTIAL)
                    ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE;
            return new Evidence.Result(records, coverage, coverage == Evidence.Coverage.COMPLETE ? Evidence.Outcome.COMPLETE : Evidence.Outcome.PARTIAL, next);
        });
    }

    @Override
    public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
        var snapshot = current;
        if (snapshot == null) return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "FTB Quests client data unavailable");
        if (!ID.equals(target.providerId()) || !sameScope(snapshot.scope(), scope) || !sameScope(target.scope(), scope))
            return new Evidence.NavigationResult(Evidence.NavigationStatus.STALE, "quest target is outside the active scope");
        if (snapshot.quests().stream().noneMatch(quest -> quest.id().equals(target.targetId())))
            return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "quest is unavailable");
        try {
            snapshot.navigator().accept(target.targetId());
            return new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED, "quest screen opened");
        } catch (RuntimeException failure) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.ERROR, failure.toString());
        }
    }

    private static Evidence.Record toRecord(QuestRecord quest, Progress progress, Snapshot snapshot) {
        var visible = progress == null ? null : progress.visible();
        var dependenciesVisible = progress == null ? null : progress.dependenciesVisible();
        var dependenciesComplete = progress == null ? null : progress.dependenciesComplete();
        var completed = progress == null ? null : progress.completed();
        var claimed = progress == null ? null : progress.rewardClaimed();
        var tasks = progress == null ? quest.tasks() : progress.tasks();
        var rewards = progress == null ? quest.rewards() : progress.rewards();
        var payload = new Evidence.JsonObject(Map.ofEntries(
                Map.entry("questId", new Evidence.JsonString(quest.id())),
                Map.entry("chapter", new Evidence.JsonString(quest.chapter())),
                Map.entry("dependencies", new Evidence.JsonArray(quest.dependencies().stream().map(Evidence.JsonString::new).map(value -> (Evidence.JsonValue) value).toList())),
                Map.entry("minimumDependencies", new Evidence.JsonNumber(java.math.BigDecimal.valueOf(quest.minimumDependencies()))),
                Map.entry("dependencyBranches", new Evidence.JsonArray(quest.dependencyBranches().stream().map(branch -> new Evidence.JsonArray(branch.stream().map(Evidence.JsonString::new).map(value -> (Evidence.JsonValue) value).toList())).map(value -> (Evidence.JsonValue) value).toList())),
                Map.entry("dependenciesVisible", dependenciesVisible == null ? new Evidence.JsonNull() : new Evidence.JsonBoolean(dependenciesVisible)),
                Map.entry("dependenciesComplete", dependenciesComplete == null ? new Evidence.JsonNull() : new Evidence.JsonBoolean(dependenciesComplete)),
                Map.entry("visible", visible == null ? new Evidence.JsonNull() : new Evidence.JsonBoolean(visible)),
                Map.entry("completed", completed == null ? new Evidence.JsonNull() : new Evidence.JsonBoolean(completed)),
                Map.entry("rewardClaimed", claimed == null ? new Evidence.JsonNull() : new Evidence.JsonBoolean(claimed)),
                Map.entry("tasks", new Evidence.JsonArray(tasks.stream().map(task -> new Evidence.JsonObject(Map.ofEntries(
                        Map.entry("id", new Evidence.JsonString(task.id())), Map.entry("type", new Evidence.JsonString(task.type())),
                        Map.entry("progress", new Evidence.JsonNumber(java.math.BigDecimal.valueOf(task.progress()))), Map.entry("completed", new Evidence.JsonBoolean(task.completed()))))).map(value -> (Evidence.JsonValue) value).toList())),
                Map.entry("rewards", new Evidence.JsonArray(rewards.stream().map(reward -> new Evidence.JsonObject(Map.ofEntries(
                        Map.entry("id", new Evidence.JsonString(reward.id())), Map.entry("type", new Evidence.JsonString(reward.type())),
                        Map.entry("claimed", new Evidence.JsonBoolean(reward.claimed()))))).map(value -> (Evidence.JsonValue) value).toList()))));
        var opaque = tasks.stream().anyMatch(task -> task.type().startsWith("unknown:"))
                || rewards.stream().anyMatch(reward -> reward.type().startsWith("unknown:"));
        var coverage = progress == null || opaque ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE;
        var source = new Evidence.Source(snapshot.scope(), ID, VERSION, quest.id(), snapshot.generation(), Instant.now(), Evidence.Activity.ACTIVE,
                coverage, Evidence.Authority.LOADED, List.of("ftbquests:client-quest-file", "ftbquests:team-data"));
        return new Evidence.Record(source, Evidence.RecordKind.QUEST, quest.title(), payload,
                List.of(new Evidence.NavigationTarget(snapshot.scope(), ID, quest.id(), quest.id())));
    }

    private static boolean matches(QuestRecord quest, String text) {
        return text.isEmpty() || quest.id().toLowerCase(Locale.ROOT).contains(text) || quest.title().toLowerCase(Locale.ROOT).contains(text)
                || quest.chapter().toLowerCase(Locale.ROOT).contains(text);
    }

    private static String argument(Evidence.JsonObject arguments, String key) {
        var value = arguments.values().get(key);
        return value instanceof Evidence.JsonString string ? string.value() : "";
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
