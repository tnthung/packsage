package dev.packsage.knowledge.curios;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class CuriosInventoryAdapter implements KnowledgeProvider {
    public static final String VERSION_RANGE = "[9.2.0,10.0.0)";
    private static final int MAX_TYPES = 32;
    private static final int MAX_SLOTS = 256;
    private final boolean loaded;
    private final boolean supported;
    private final String version;
    private volatile String failure;

    public CuriosInventoryAdapter(boolean loaded, boolean supported, String version) {
        this.loaded = loaded;
        this.supported = supported;
        this.version = version;
    }

    public boolean canAttempt() { return loaded && supported; }

    public boolean available() { return canAttempt() && failure == null; }

    public boolean loaded() { return loaded; }

    public String version() { return version; }

    public Capture capture(LivingEntity entity) {
        if (!loaded) return new Capture(List.of(), Evidence.Coverage.COMPLETE, "Curios is not loaded");
        if (!supported) return new Capture(List.of(), Evidence.Coverage.PARTIAL, "Curios equipment coverage is unknown");
        failure = null;
        var slots = new ArrayList<Slot>();
        var coverage = Evidence.Coverage.COMPLETE;
        try {
            ClassLoader loader = CuriosInventoryAdapter.class.getClassLoader();
            Object rawInventory = Class.forName("top.theillusivec4.curios.api.CuriosApi", true, loader)
                    .getMethod("getCuriosInventory", LivingEntity.class).invoke(null, entity);
            if (!(rawInventory instanceof Optional<?> optional) || optional.isEmpty()) {
                failure = "Curios player equipment is unavailable";
                return new Capture(List.of(), Evidence.Coverage.PARTIAL, "Curios player equipment is unavailable");
            }
            Class<?> inventoryType = Class.forName("top.theillusivec4.curios.api.type.capability.ICuriosItemHandler", true, loader);
            Object rawTypes = inventoryType.getMethod("getCurios").invoke(optional.get());
            if (!(rawTypes instanceof Map<?, ?> types)) {
                failure = "Curios functional equipment handler is unreadable";
                return new Capture(List.of(), Evidence.Coverage.PARTIAL, failure);
            }
            Class<?> typeHandler = Class.forName("top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler", true, loader);
            Class<?> stackHandler = Class.forName("top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler", true, loader);
            int visitedTypes = 0;
            int visitedSlots = 0;
            for (var entry : types.entrySet()) {
                if (visitedTypes == MAX_TYPES) {
                    coverage = Evidence.Coverage.PARTIAL;
                    break;
                }
                visitedTypes++;
                if (!(entry.getKey() instanceof String type) || type.isBlank() || entry.getValue() == null) {
                    coverage = Evidence.Coverage.PARTIAL;
                    continue;
                }
                Object rawStacks = typeHandler.getMethod("getStacks").invoke(entry.getValue());
                if (rawStacks == null) {
                    coverage = Evidence.Coverage.PARTIAL;
                    continue;
                }
                int declaredSlots = (int) typeHandler.getMethod("getSlots").invoke(entry.getValue());
                int stackSlots = (int) stackHandler.getMethod("getSlots").invoke(rawStacks);
                if (declaredSlots < 0 || stackSlots < 0) {
                    coverage = Evidence.Coverage.PARTIAL;
                    continue;
                }
                if (declaredSlots != stackSlots) coverage = Evidence.Coverage.PARTIAL;
                int availableSlots = Math.min(declaredSlots, stackSlots);
                if (availableSlots > MAX_SLOTS - visitedSlots) {
                    availableSlots = MAX_SLOTS - visitedSlots;
                    coverage = Evidence.Coverage.PARTIAL;
                }
                for (int index = 0; index < availableSlots; index++) {
                    visitedSlots++;
                    Object value = stackHandler.getMethod("getStackInSlot", int.class).invoke(rawStacks, index);
                    if (!(value instanceof ItemStack stack)) {
                        coverage = Evidence.Coverage.PARTIAL;
                    } else if (!stack.isEmpty()) slots.add(new Slot(type, index, stack.copy()));
                }
                if (visitedSlots == MAX_SLOTS && availableSlots < Math.min(declaredSlots, stackSlots)) {
                    coverage = Evidence.Coverage.PARTIAL;
                    break;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError problem) {
            failure = "Curios functional equipment could not be read";
            return new Capture(slots, Evidence.Coverage.PARTIAL, failure);
        }
        String detail = coverage == Evidence.Coverage.COMPLETE
                ? "Curios functional equipment read" : "Curios functional equipment coverage is partial";
        if (coverage != Evidence.Coverage.COMPLETE) failure = detail;
        return new Capture(slots, coverage, detail);
    }

    public ItemStack locate(LivingEntity entity, String type, int index) {
        if (index < 0 || !canAttempt()) return null;
        return capture(entity).slots().stream().filter(slot -> slot.type().equals(type) && slot.index() == index)
                .map(Slot::stack).findFirst().orElse(null);
    }

    @Override public String id() { return "curios"; }

    @Override public Metadata metadata() { return new Metadata(id(), List.of(), false, List.of(), Map.of()); }

    @Override public boolean enabled() { return loaded && supported; }

    @Override
    public ProviderStatus status() {
        var availability = !loaded ? Availability.UNAVAILABLE : available() ? Availability.AVAILABLE : Availability.PARTIAL;
        return new ProviderStatus(availability, version, !loaded ? "Curios is not loaded"
                : failure != null ? failure : supported ? "Curios functional equipment API is supported"
                : "Curios version is outside compatible range " + VERSION_RANGE);
    }

    @Override
    public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
        var status = status();
        var coverage = status.availability() == Availability.AVAILABLE ? Evidence.Coverage.COMPLETE
                : status.availability() == Availability.PARTIAL ? Evidence.Coverage.PARTIAL : Evidence.Coverage.UNAVAILABLE;
        var source = new Evidence.Source(scope, id(), version, "status:" + id(), scope.requestGeneration(), Instant.now(),
                Evidence.Activity.UNKNOWN, coverage, Evidence.Authority.UNKNOWN, List.of());
        var record = new Evidence.Record(source, Evidence.RecordKind.OTHER, status.detail(),
                new Evidence.JsonObject(Map.of("detail", new Evidence.JsonString(status.detail()))), List.of());
        return CompletableFuture.completedFuture(new Evidence.Result(List.of(record), coverage,
                coverage == Evidence.Coverage.COMPLETE ? Evidence.Outcome.COMPLETE
                        : coverage == Evidence.Coverage.PARTIAL ? Evidence.Outcome.PARTIAL : Evidence.Outcome.UNKNOWN,
                null, status.detail()));
    }

    @Override
    public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
        return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "Curios equipment has no navigation target");
    }

    public record Slot(String type, int index, ItemStack stack) {
        public Slot {
            stack = stack.copy();
        }
        @Override public ItemStack stack() { return stack.copy(); }
    }

    public record Capture(List<Slot> slots, Evidence.Coverage coverage, String detail) {
        public Capture { slots = List.copyOf(slots); }
    }
}
