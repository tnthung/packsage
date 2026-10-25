package dev.packsage.world.minecraft;

import dev.packsage.knowledge.Evidence;
import dev.packsage.world.Progress;
import dev.packsage.world.ProgressCriterion;
import dev.packsage.world.ProgressEntry;
import dev.packsage.world.ProgressHandle;
import dev.packsage.world.ProgressId;
import dev.packsage.world.ProgressReader;
import dev.packsage.world.ProgressSnapshot;
import dev.packsage.world.ProgressStatus;
import dev.packsage.world.ProgressTree;
import dev.packsage.world.Section;
import dev.packsage.world.ServerWork;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Vanilla advancements as progress: one tree per displayed root, entries depth-first. Progress is looked up only for
 * holders of the advancement set loaded when the read began, which the player's progress map already holds, so a read
 * never starts progress or changes a criterion.
 */
public final class MinecraftProgress implements ProgressReader {
    static final int STEP_NODES = 64;
    static final String RELOADED_NOTE = "advancements were reloaded during the read; repeat it";

    /**
     * What a read needs from the server. {@code generation} is an identity token for the loaded advancement set, which a
     * reload replaces; {@code criterionLabel} names a criterion for the player.
     */
    record Source(Supplier<Object> generation, Supplier<AdvancementTree> tree, Function<AdvancementHolder, AdvancementProgress> progress,
                  Function<String, String> criterionLabel) {
    }

    @Override
    public String adapterId() {
        return MinecraftInventories.ADAPTER;
    }

    @Override
    public ServerWork.Slice<ProgressSnapshot> snapshot(ServerPlayer player) {
        return snapshot(source(player));
    }

    @Override
    public Progress inspect(ServerPlayer player, String local) {
        return inspect(source(player), local);
    }

    private static Source source(ServerPlayer player) {
        var server = player.getServer();
        var registries = player.registryAccess();
        return new Source(server::getAdvancements, () -> server.getAdvancements().tree(), player.getAdvancements()::getOrStartProgress,
                name -> criterionLabel(registries, name));
    }

    /** A biome, entity type or item named by the criterion gives its translated name; anything else is the name itself. */
    private static String criterionLabel(RegistryAccess registries, String name) {
        var id = ResourceLocation.tryParse(name);
        if (id == null) return name;
        if (registries.registryOrThrow(Registries.BIOME).containsKey(id)) {
            return Component.translatable("biome." + id.getNamespace() + "." + id.getPath()).getString();
        }
        var entity = registries.registryOrThrow(Registries.ENTITY_TYPE).getOptional(id);
        if (entity.isPresent()) return entity.get().getDescription().getString();
        var item = registries.registryOrThrow(Registries.ITEM).getOptional(id);
        if (item.isPresent()) return item.get().getDescription().getString();
        return name;
    }

    ServerWork.Slice<ProgressSnapshot> snapshot(Source source) {
        return new Read(source);
    }

    /** {@code parentDone} is null for a root. */
    static ProgressStatus status(boolean done, boolean anyCriterionDone, boolean hidden, Boolean parentDone) {
        if (done) return ProgressStatus.DONE;
        if (hidden) return ProgressStatus.HIDDEN;
        if (anyCriterionDone) return ProgressStatus.IN_PROGRESS;
        return parentDone == null || parentDone ? ProgressStatus.AVAILABLE : ProgressStatus.LOCKED;
    }

    /** Null when {@code local} is not a displayed advancement of a displayed root in the current tree. */
    Progress inspect(Source source, String local) {
        var id = ResourceLocation.tryParse(local);
        if (id == null) return null;
        var node = source.tree().get().get(id);
        if (node == null || node.advancement().display().isEmpty() || node.root().advancement().display().isEmpty()) return null;
        var tree = id(node.root());
        var parent = displayedParent(node);
        var progress = source.progress().apply(node.holder());
        var handle = handle(node, tree, progress, parent == null ? null : source.progress().apply(parent.holder()).isDone());
        if (handle.status() == ProgressStatus.HIDDEN) return Progress.hidden(handle);

        var requires = new ArrayList<ProgressHandle>();
        if (parent != null) {
            var grandparent = displayedParent(parent);
            requires.add(handle(parent, tree, source.progress().apply(parent.holder()),
                    grandparent == null ? null : source.progress().apply(grandparent.holder()).isDone()));
        }
        var unlocks = new ArrayList<ProgressHandle>();
        for (var child : displayedChildren(node)) {
            unlocks.add(handle(child, tree, source.progress().apply(child.holder()), progress.isDone()));
        }

        var advancement = node.advancement();
        var requirements = advancement.requirements();
        var criteria = new ArrayList<ProgressCriterion>();
        Instant latest = null;
        for (var name : names(requirements)) {
            var criterion = progress.getCriterion(name);
            var obtained = criterion == null ? null : criterion.getObtained();
            criteria.add(new ProgressCriterion(name, source.criterionLabel().apply(name), criterion != null && criterion.isDone(), obtained));
            if (obtained != null && (latest == null || obtained.isAfter(latest))) latest = obtained;
        }

        var rewards = new ArrayList<String>();
        var granted = advancement.rewards();
        if (granted.experience() > 0) rewards.add("experience: " + granted.experience());
        for (var loot : granted.loot()) {
            rewards.add("loot: " + loot.location());
        }
        for (var recipe : granted.recipes()) {
            rewards.add("recipe: " + recipe);
        }
        granted.function().ifPresent(function -> rewards.add("function: " + function.getId()));

        var facts = new LinkedHashMap<String, Evidence.JsonValue>();
        facts.put("frame", new Evidence.JsonString(advancement.display().get().getType().getSerializedName()));
        facts.put("criteriaDone", number(requirements.count(name -> {
            var criterion = progress.getCriterion(name);
            return criterion != null && criterion.isDone();
        })));
        facts.put("criteriaTotal", number(requirements.size()));
        if (requirements.requirements().stream().anyMatch(group -> group.size() > 1)) {
            var groups = new ArrayList<Evidence.JsonValue>();
            for (var group : requirements.requirements()) {
                groups.add(new Evidence.JsonArray(group.stream().<Evidence.JsonValue>map(Evidence.JsonString::new).toList()));
            }
            facts.put("requirements", new Evidence.JsonArray(groups));
        }
        return new Progress(handle, description(advancement.display().get()), criteria, requires, unlocks, rewards,
                progress.isDone() ? latest : null,
                Map.of(MinecraftInventories.ADAPTER, new Section(new Evidence.JsonObject(facts), Evidence.Coverage.COMPLETE, null)));
    }

    /**
     * Walks each displayed root depth-first, at most {@link #STEP_NODES} nodes a step. A node without a display is not
     * listed, but its displayed descendants are, under its nearest displayed ancestor.
     */
    private static final class Read implements ServerWork.Slice<ProgressSnapshot> {
        /** {@code parentDone} is whether the nearest displayed ancestor is done; null for a root. */
        private record Pending(AdvancementNode node, Boolean parentDone) {
        }

        private final Source source;
        private final Deque<Pending> pending = new ArrayDeque<>();
        private final List<ProgressTree> trees = new ArrayList<>();
        private final List<ProgressEntry> entries = new ArrayList<>();
        private Object generation;
        private List<AdvancementNode> roots;
        private int nextRoot;
        private AdvancementNode root;
        private int done;
        private int total;
        private String note;

        Read(Source source) {
            this.source = source;
        }

        @Override
        public boolean step() {
            if (generation == null) {
                generation = source.generation().get();
                roots = new ArrayList<>();
                for (var node : source.tree().get().roots()) {
                    if (node.advancement().display().isPresent()) roots.add(node);
                }
            } else if (source.generation().get() != generation) {
                note = RELOADED_NOTE;
                return false;
            }
            for (var handled = 0; handled < STEP_NODES; handled++) {
                if (pending.isEmpty()) {
                    if (root != null) {
                        trees.add(new ProgressTree(id(root), title(root), done, total));
                        root = null;
                    }
                    if (nextRoot == roots.size()) return false;
                    root = roots.get(nextRoot++);
                    done = 0;
                    total = 0;
                    pending.push(new Pending(root, null));
                }
                visit(pending.pop());
            }
            return true;
        }

        private void visit(Pending next) {
            var node = next.node();
            var display = node.advancement().display();
            var parentDone = next.parentDone();
            if (display.isPresent()) {
                var progress = source.progress().apply(node.holder());
                var handle = handle(node, id(root), progress, parentDone);
                entries.add(new ProgressEntry(handle, handle.status() == ProgressStatus.HIDDEN ? null : description(display.get())));
                total++;
                if (progress.isDone()) done++;
                parentDone = progress.isDone();
            }
            var children = new ArrayList<AdvancementNode>();
            node.children().forEach(children::add);
            for (var i = children.size() - 1; i >= 0; i--) {
                pending.push(new Pending(children.get(i), parentDone));
            }
        }

        @Override
        public ProgressSnapshot result() {
            return new ProgressSnapshot(trees, entries, note);
        }
    }

    private static ProgressHandle handle(AdvancementNode node, ProgressId tree, AdvancementProgress progress, Boolean parentDone) {
        var display = node.advancement().display().orElseThrow();
        var status = status(progress.isDone(), progress.hasProgress(), display.isHidden(), parentDone);
        return new ProgressHandle(id(node), tree, status == ProgressStatus.HIDDEN ? null : title(node), status);
    }

    private static ProgressId id(AdvancementNode node) {
        return new ProgressId(MinecraftInventories.ADAPTER, node.holder().id().toString());
    }

    /** The display title, or the advancement id when the title is blank. */
    private static String title(AdvancementNode node) {
        var title = node.advancement().display().orElseThrow().getTitle().getString();
        return title.isBlank() ? node.holder().id().toString() : title;
    }

    private static String description(DisplayInfo display) {
        return display.getDescription().getString();
    }

    private static AdvancementNode displayedParent(AdvancementNode node) {
        var parent = node.parent();
        while (parent != null && parent.advancement().display().isEmpty()) {
            parent = parent.parent();
        }
        return parent;
    }

    private static List<AdvancementNode> displayedChildren(AdvancementNode node) {
        var children = new ArrayList<AdvancementNode>();
        for (var child : node.children()) {
            if (child.advancement().display().isPresent()) {
                children.add(child);
            } else {
                children.addAll(displayedChildren(child));
            }
        }
        return children;
    }

    /** Criterion names in requirement order. */
    private static LinkedHashSet<String> names(AdvancementRequirements requirements) {
        var names = new LinkedHashSet<String>();
        requirements.requirements().forEach(names::addAll);
        return names;
    }

    private static Evidence.JsonNumber number(long value) {
        return new Evidence.JsonNumber(BigDecimal.valueOf(value));
    }
}
