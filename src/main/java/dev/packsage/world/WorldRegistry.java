package dev.packsage.world;

import dev.packsage.knowledge.Evidence;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
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
            collect(sections, inspector.adapterId(), () -> inspector.inspectEntity(entity));
        }
        return sections;
    }

    public Map<String, Section> inspectItem(ItemStack stack) {
        var sections = new LinkedHashMap<String, Section>();
        for (var reader : readers) {
            collect(sections, reader.adapterId(), () -> reader.inspectItem(stack));
        }
        return sections;
    }

    public List<InventoryInfo> inventories(LiveHolder holder) {
        var result = new ArrayList<InventoryInfo>();
        for (var reader : readers) {
            try {
                result.addAll(reader.inventories(holder));
            } catch (RuntimeException ignored) {
                // A failing adapter lists no inventories; the others still answer.
            }
        }
        return result;
    }

    public InventoryReader inventoryReader(String adapterId) {
        return readers.stream().filter(reader -> reader.adapterId().equals(adapterId)).findFirst().orElse(null);
    }

    public List<WorldAccessor> accessors() {
        return List.copyOf(accessors);
    }

    private static void collect(Map<String, Section> sections, String id, Supplier<Evidence.JsonObject> inspection) {
        try {
            var facts = inspection.get();
            if (facts != null) {
                sections.put(id, new Section(facts, Evidence.Coverage.COMPLETE, null));
            }
        } catch (RuntimeException exception) {
            var message = exception.getMessage();
            sections.put(id, new Section(new Evidence.JsonObject(Map.of()), Evidence.Coverage.ERROR,
                    message == null ? exception.getClass().getSimpleName() : message));
        }
    }
}
