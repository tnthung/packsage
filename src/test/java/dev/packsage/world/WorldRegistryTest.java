package dev.packsage.world;

import dev.packsage.knowledge.Evidence;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class WorldRegistryTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.bootStrap();
    }

    private record FakeReader(String adapterId, Evidence.JsonObject facts) implements InventoryReader {
        public List<InventoryInfo> inventories(LiveHolder holder) {
            return List.of();
        }

        public LiveInventory open(LiveHolder holder, String section) {
            return null;
        }

        public Evidence.JsonObject inspectItem(ItemStack stack) {
            if (facts == null) {
                throw new IllegalStateException("broken adapter");
            }
            return facts;
        }
    }

    @Test
    void mergedInspectionKeepsEachAdaptersSectionAndIsolatesAFailingAdapter() {
        var registry = new WorldRegistry();
        var facts = new Evidence.JsonObject(Map.of("fuel", new Evidence.JsonString("coal")));
        var good = new FakeReader("good", facts);
        registry.register(good);
        registry.register(new FakeReader("bad", null));
        var sections = registry.inspectItem(new ItemStack(Items.DIAMOND));
        assertEquals(List.of("good", "bad"), List.copyOf(sections.keySet()));
        assertEquals(Evidence.Coverage.COMPLETE, sections.get("good").coverage());
        assertEquals(facts, sections.get("good").facts());
        assertEquals(Evidence.Coverage.ERROR, sections.get("bad").coverage());
        assertEquals("broken adapter", sections.get("bad").note());
        assertSame(good, registry.inventoryReader("good"));
    }
}
