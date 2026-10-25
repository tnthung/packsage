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
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
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

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Vanilla advancements as progress: one tree per displayed root, entries depth-first. Progress is looked up only for
 * holders of the advancement set loaded when the read began, which the player's progress map already holds, so a read
 * never starts progress or changes a criterion.
 */
public final class MinecraftProgress implements ProgressReader {
    static final int STEP_NODES = 64;
    static final String RELOADED_NOTE = "advancements were reloaded during the read; repeat it";
    private final Map<Object, HiddenIds> hiddenIds = new WeakHashMap<>();

    private record HiddenIds(Map<String, String> aliases, Map<String, String> originals) {
    }

    /**
     * What a read needs from the server. {@code generation} is an identity token for the loaded advancement set, which a
     * reload replaces; {@code world} keeps opaque identities stable across reloads in one server session;
     * {@code criterionLabel} names a criterion for the player.
     */
    record Source(Object world, Supplier<Object> generation, Supplier<AdvancementTree> tree, Function<AdvancementHolder, AdvancementProgress> progress,
                  Predicate<AdvancementHolder> visible, Function<String, String> criterionLabel) {
        /** Keep progress lookups shared by ordering, handles and ids within one read. */
        private Source cached() {
            var values = new HashMap<AdvancementHolder, AdvancementProgress>();
            return new Source(world, generation, tree, holder -> values.computeIfAbsent(holder, progress), visible, criterionLabel);
        }
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
        // Read vanilla's packet visibility set on the server thread, without evaluating or flushing dirty progress.
        return new Source(server, server::getAdvancements, () -> server.getAdvancements().tree(), player.getAdvancements()::getOrStartProgress,
                player.getAdvancements().visible::contains, name -> criterionLabel(registries, name));
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
        return new Read(source.cached());
    }

    /** {@code parentDone} is null for a root. */
    static ProgressStatus status(boolean done, boolean anyCriterionDone, boolean hidden, Boolean parentDone) {
        if (done) return ProgressStatus.DONE;
        if (hidden) return ProgressStatus.HIDDEN;
        if (anyCriterionDone) return ProgressStatus.IN_PROGRESS;
        return parentDone == null || parentDone ? ProgressStatus.AVAILABLE : ProgressStatus.LOCKED;
    }

    /** Null for absent entries and real ids of unearned hidden advancements; only their session aliases resolve. */
    Progress inspect(Source source, String local) {
        var aliases = hiddenIds.get(source.world());
        var original = aliases == null ? null : aliases.originals().get(local);
        var id = ResourceLocation.tryParse(original == null ? local : original);
        if (id == null) return null;
        var node = source.tree().get().get(id);
        if (node == null || node.advancement().display().isEmpty() || node.root().advancement().display().isEmpty()) return null;
        source = source.cached();
        var progress = source.progress().apply(node.holder());
        if (node.advancement().display().get().isHidden() && !progress.isDone() && original == null) return null;
        var tree = id(source, node.root(), source.progress().apply(node.root().holder()));
        var tabVisible = tree.equals(id(node.root())) && source.visible().test(node.root().holder());
        var parent = displayedParent(node);
        var parentProgress = parent == null ? null : source.progress().apply(parent.holder());
        var handle = handle(source, node, tree, progress, parentProgress == null ? null : parentProgress.isDone(), tabVisible);
        if (handle.status() == ProgressStatus.HIDDEN) return Progress.hidden(handle);

        var requires = new ArrayList<ProgressHandle>();
        if (parent != null) {
            var grandparent = displayedParent(parent);
            requires.add(handle(source, parent, tree, parentProgress,
                    grandparent == null ? null : source.progress().apply(grandparent.holder()).isDone(), tabVisible));
        }
        var unlocks = new ArrayList<ProgressHandle>();
        for (var child : displayedChildren(source, node)) {
            unlocks.add(handle(source, child, tree, source.progress().apply(child.holder()), progress.isDone(), tabVisible));
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
     * Acquires at most {@link #STEP_NODES} index buckets a step into owned ordered sibling maps, then walks at most
     * that many nodes or finished sibling frames a step. Visible roots and siblings use id order; unearned hidden
     * ones follow them in alias order. No engine iterator, table or collection view survives a step.
     * A node without a display is not listed, but its displayed descendants are, under its nearest displayed ancestor.
     * A partial read lists only the trees it finished counting.
     */
    private final class Read implements ServerWork.Slice<ProgressSnapshot> {
        private static final Field NODES;
        private static final Field VALUES;

        static {
            Field nodes = null;
            Field values = null;
            try {
                nodes = AdvancementTree.class.getDeclaredField("nodes");
                values = Object2ObjectOpenHashMap.class.getDeclaredField("value");
                nodes.setAccessible(true);
                values.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                nodes = null;
                values = null;
            }
            NODES = nodes;
            VALUES = values;
        }

        /** {@code parentDone} is whether the nearest displayed ancestor is done; null for a root. */
        private record Pending(NavigableMap<String, AdvancementNode> siblings, Boolean parentDone) {
        }

        private final Source source;
        private final Deque<Pending> pending = new ArrayDeque<>();
        private final List<ProgressTree> trees = new ArrayList<>();
        private final List<ProgressEntry> entries = new ArrayList<>();
        private Object generation;
        private final NavigableMap<String, AdvancementNode> roots = new TreeMap<>();
        private final Map<AdvancementNode, NavigableMap<String, AdvancementNode>> children = new HashMap<>();
        private int nextNode;
        private boolean acquired;
        private AdvancementNode root;
        private ProgressId tree;
        private boolean tabVisible;
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
            } else if (source.generation().get() != generation) {
                note = RELOADED_NOTE;
                return false;
            }
            if (!acquired) {
                // The loaded manager owns a fixed fastutil node table until reload. Index its buckets directly:
                // re-creating/skipping an iterator or copying the table would hide unbounded work in one step.
                try {
                    if (NODES == null || VALUES == null) throw new IllegalStateException("node index fields unavailable");
                    var values = (Object[]) VALUES.get(NODES.get(source.tree().get()));
                    for (var handled = 0; handled < STEP_NODES && nextNode < values.length; handled++) {
                        var node = (AdvancementNode) values[nextNode++];
                        if (node == null || node.parent() == null && node.advancement().display().isEmpty()) continue;
                        var key = node.advancement().display().isPresent() && node.advancement().display().get().isHidden()
                                && !source.progress().apply(node.holder()).isDone()
                                ? "1" + id(source, node, source.progress().apply(node.holder())).local() : "0" + node.holder().id();
                        if (node.parent() == null) roots.put(key, node);
                        else children.computeIfAbsent(node.parent(), ignored -> new TreeMap<>()).put(key, node);
                    }
                    acquired = nextNode == values.length;
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    note = "The advancement index could not be read; progress coverage is incomplete";
                    return false;
                }
                return true;
            }
            for (var handled = 0; handled < STEP_NODES; handled++) {
                if (pending.isEmpty()) {
                    if (root != null) {
                        trees.add(new ProgressTree(tree, tree.equals(id(root)) ? title(root) : "Hidden progress tree", done, total, tabVisible));
                        root = null;
                    }
                    if (roots.isEmpty()) return false;
                    root = roots.pollFirstEntry().getValue();
                    tree = id(source, root, source.progress().apply(root.holder()));
                    tabVisible = tree.equals(id(root)) && source.visible().test(root.holder());
                    done = 0;
                    total = 0;
                    visit(root, null);
                } else {
                    var frame = pending.peek();
                    if (frame.siblings().isEmpty()) pending.pop();
                    else visit(frame.siblings().pollFirstEntry().getValue(), frame.parentDone());
                }
            }
            return true;
        }

        private void visit(AdvancementNode node, Boolean parentDone) {
            var display = node.advancement().display();
            if (display.isPresent()) {
                var progress = source.progress().apply(node.holder());
                var handle = handle(source, node, tree, progress, parentDone, tabVisible);
                entries.add(new ProgressEntry(handle, handle.status() == ProgressStatus.HIDDEN ? null : description(display.get())));
                total++;
                if (progress.isDone()) done++;
                parentDone = progress.isDone();
            }
            var siblings = children.remove(node);
            if (siblings != null) pending.push(new Pending(siblings, parentDone));
        }

        @Override
        public ProgressSnapshot result() {
            return new ProgressSnapshot(trees, entries, note);
        }
    }

    private ProgressHandle handle(Source source, AdvancementNode node, ProgressId tree, AdvancementProgress progress, Boolean parentDone, boolean tabVisible) {
        var display = node.advancement().display().orElseThrow();
        var status = status(progress.isDone(), progress.hasProgress(), display.isHidden(), parentDone);
        return new ProgressHandle(id(source, node, progress), tree, status == ProgressStatus.HIDDEN ? null : title(node), status, tabVisible);
    }

    /** Random aliases reveal no namespace or path; only this world's server session can resolve them. */
    private ProgressId id(Source source, AdvancementNode node, AdvancementProgress progress) {
        if (node.advancement().display().orElseThrow().isHidden() && !progress.isDone()) {
            var aliases = hiddenIds.computeIfAbsent(source.world(), ignored -> new HiddenIds(new LinkedHashMap<>(), new LinkedHashMap<>()));
            return new ProgressId(MinecraftInventories.ADAPTER, aliases.aliases().computeIfAbsent(node.holder().id().toString(), original -> {
                var alias = "hidden:" + UUID.randomUUID();
                aliases.originals().put(alias, original);
                return alias;
            }));
        }
        return id(node);
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

    private List<AdvancementNode> displayedChildren(Source source, AdvancementNode node) {
        var children = new ArrayList<AdvancementNode>();
        for (var child : sorted(source, node.children())) {
            if (child.advancement().display().isPresent()) {
                children.add(child);
            } else {
                children.addAll(displayedChildren(source, child));
            }
        }
        return children;
    }

    /** Keep visible content order stable without exposing the real ids through hidden sibling positions. */
    private List<AdvancementNode> sorted(Source source, Iterable<AdvancementNode> nodes) {
        var list = new ArrayList<AdvancementNode>();
        nodes.forEach(list::add);
        var hidden = new HashMap<AdvancementNode, String>();
        for (var node : list) {
            if (node.advancement().display().isPresent() && node.advancement().display().get().isHidden()) {
                var progress = source.progress().apply(node.holder());
                if (!progress.isDone()) hidden.put(node, id(source, node, progress).local());
            }
        }
        list.sort(Comparator.comparing((AdvancementNode node) -> hidden.containsKey(node))
                .thenComparing(node -> hidden.getOrDefault(node, node.holder().id().toString())));
        return list;
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
