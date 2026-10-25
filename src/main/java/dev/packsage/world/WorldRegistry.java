package dev.packsage.world;

import dev.packsage.knowledge.Evidence;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

public final class WorldRegistry {
    private final List<InventoryReader> readers = new CopyOnWriteArrayList<>();
    private final List<BlockInspector> blockInspectors = new CopyOnWriteArrayList<>();
    private final List<EntityInspector> entityInspectors = new CopyOnWriteArrayList<>();
    private final List<WorldAccessor> accessors = new CopyOnWriteArrayList<>();
    private final List<ProgressReader> progressReaders = new CopyOnWriteArrayList<>();
    private final List<StatisticReader> statisticReaders = new CopyOnWriteArrayList<>();

    public void register(Object adapter) {
        if (adapter instanceof InventoryReader reader) {
            readers.add(reader);
        }
        if (adapter instanceof BlockInspector inspector) {
            blockInspectors.add(inspector);
        }
        if (adapter instanceof EntityInspector inspector) {
            entityInspectors.add(inspector);
        }
        if (adapter instanceof WorldAccessor accessor) {
            accessors.add(accessor);
        }
        if (adapter instanceof ProgressReader reader) {
            progressReaders.add(reader);
        }
        if (adapter instanceof StatisticReader reader) {
            statisticReaders.add(reader);
        }
    }

    public Map<String, Section> inspectBlock(ServerLevel level, BlockPos pos) {
        var sections = new LinkedHashMap<String, Section>();
        for (var inspector : blockInspectors) {
            collect(sections, inspector.adapterId(), () -> inspector.inspectBlock(level, pos));
        }
        return sections;
    }

    public Map<String, Section> inspectEntity(Entity entity) {
        var sections = new LinkedHashMap<String, Section>();
        for (var inspector : entityInspectors) {
            collectSection(sections, inspector.adapterId(), () -> inspector.inspectEntity(entity));
        }
        return sections;
    }

    public Map<String, Section> inspectItem(ItemStack stack) {
        var sections = new LinkedHashMap<String, Section>();
        for (var reader : readers) {
            collectSection(sections, reader.adapterId(), () -> reader.inspectItem(stack));
        }
        return sections;
    }

    public Page<InventoryInfo> inventories(LiveHolder holder) {
        var result = new ArrayList<InventoryInfo>();
        var failures = new ArrayList<String>();
        for (var reader : readers) {
            try {
                result.addAll(reader.inventories(holder));
            } catch (RuntimeException exception) {
                failures.add(reader.adapterId() + ": " + describe(exception));
            }
        }
        if (failures.isEmpty()) {
            return new Page<>(result, null, Evidence.Coverage.COMPLETE, null);
        }
        return new Page<>(result, null, Evidence.Coverage.PARTIAL, "Inventory listing failed for " + String.join("; ", failures));
    }

    private static String describe(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    public InventoryReader inventoryReader(String adapterId) {
        return readers.stream().filter(reader -> reader.adapterId().equals(adapterId)).findFirst().orElse(null);
    }

    public List<WorldAccessor> accessors() {
        return List.copyOf(accessors);
    }

    public List<ProgressReader> progressReaders() {
        return List.copyOf(progressReaders);
    }

    public List<StatisticReader> statisticReaders() {
        return List.copyOf(statisticReaders);
    }

    /** Facts the other progress readers add to an entry {@code ownerAdapter} owns; server thread only. */
    public Map<String, Section> progressFacts(ServerPlayer player, ProgressId id, String ownerAdapter) {
        var sections = new LinkedHashMap<String, Section>();
        for (var reader : progressReaders) {
            if (!reader.adapterId().equals(ownerAdapter)) {
                collect(sections, reader.adapterId(), () -> reader.progressFacts(player, id));
            }
        }
        return sections;
    }

    private static void collect(Map<String, Section> sections, String id, Supplier<Evidence.JsonObject> inspection) {
        collectSection(sections, id, () -> {
            var facts = inspection.get();
            return facts == null ? null : new Section(facts, Evidence.Coverage.COMPLETE, null);
        });
    }

    private static void collectSection(Map<String, Section> sections, String id, Supplier<Section> inspection) {
        try {
            var section = inspection.get();
            if (section != null) {
                sections.put(id, section);
            }
        } catch (RuntimeException exception) {
            sections.put(id, new Section(new Evidence.JsonObject(Map.of()), Evidence.Coverage.ERROR, describe(exception)));
        }
    }
}
