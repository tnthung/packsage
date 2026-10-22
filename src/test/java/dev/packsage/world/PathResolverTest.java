package dev.packsage.world;

import dev.packsage.knowledge.Evidence;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PathResolverTest {
    private static final UUID ENTITY = UUID.fromString("3f1c0000-0000-0000-0000-000000000001");

    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.bootStrap();
    }

    private record FakeReader(String adapterId, String section, ItemStack content) implements InventoryReader {
        public List<InventoryInfo> inventories(LiveHolder holder) {
            return List.of();
        }

        public LiveInventory open(LiveHolder holder, String requested) {
            if (!section.equals(requested)) {
                return null;
            }
            return new LiveInventory() {
                public ItemStack get(String entry) {
                    return entry.equals("0") ? content : null;
                }

                public List<Map.Entry<String, ItemStack>> entries() {
                    return List.of(Map.entry("0", content));
                }
            };
        }

        public Evidence.JsonObject inspectItem(ItemStack stack) {
            return null;
        }
    }

    private static final Function<PathRoot, LiveHolder> NO_ROOT = root -> null;

    private static ItemPath twoSteps() {
        return new ItemPath(new PathRoot.Entity(ENTITY), List.of(
                new PathStep(new InventoryId("a", "box"), "0", "minecraft:shulker_box"),
                new PathStep(new InventoryId("b", "bag"), "0", "minecraft:iron_ingot")));
    }

    private static PathResolver resolver(ItemStack second, boolean withB, LiveHolder root) {
        var registry = new WorldRegistry();
        registry.register(new FakeReader("a", "box", new ItemStack(Items.SHULKER_BOX)));
        if (withB) {
            registry.register(new FakeReader("b", "bag", second));
        }
        return new PathResolver(registry, path -> root);
    }

    @Test
    void resolvesAcrossTwoAdapters() {
        var result = resolver(new ItemStack(Items.IRON_INGOT), true, new LiveHolder.OfItem(ItemStack.EMPTY)).resolve(twoSteps());

        assertEquals(PathResolver.Failure.NONE, result.failure());
        assertEquals(Items.IRON_INGOT, result.stack().getItem());
    }

    @Test
    void missingAdapterFailsUnavailableAtItsStep() {
        var result = resolver(new ItemStack(Items.IRON_INGOT), false, new LiveHolder.OfItem(ItemStack.EMPTY)).resolve(twoSteps());

        assertEquals(PathResolver.Failure.UNAVAILABLE, result.failure());
        assertEquals(1, result.failedStep());
    }

    @Test
    void swappedItemFailsStaleAtItsStepAndKeepsEarlierSteps() {
        var result = resolver(new ItemStack(Items.DIRT), true, new LiveHolder.OfItem(ItemStack.EMPTY)).resolve(twoSteps());

        assertEquals(PathResolver.Failure.STALE, result.failure());
        assertEquals(1, result.failedStep());
        assertEquals(Items.SHULKER_BOX, result.stack().getItem());
        assertSame(result.stack(), ((LiveHolder.OfItem) result.holder()).stack());
    }

    @Test
    void emptyEntryFailsStale() {
        var result = resolver(ItemStack.EMPTY, true, new LiveHolder.OfItem(ItemStack.EMPTY)).resolve(twoSteps());

        assertEquals(PathResolver.Failure.STALE, result.failure());
        assertEquals(1, result.failedStep());
    }

    @Test
    void goneRootFailsRootGone() {
        var result = new PathResolver(new WorldRegistry(), NO_ROOT).resolve(twoSteps());

        assertEquals(PathResolver.Failure.ROOT_GONE, result.failure());
        assertNull(result.holder());
        assertNull(result.stack());
    }
}
