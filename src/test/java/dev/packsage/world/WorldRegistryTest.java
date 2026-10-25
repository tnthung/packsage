package dev.packsage.world;

import dev.packsage.knowledge.Evidence;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        public Section inspectItem(ItemStack stack) {
            if (facts == null) {
                throw new IllegalStateException("broken adapter");
            }
            return new Section(facts, Evidence.Coverage.COMPLETE, null);
        }
    }

    private record ListingReader(String adapterId, List<InventoryInfo> infos) implements InventoryReader {
        public List<InventoryInfo> inventories(LiveHolder holder) {
            if (infos == null) {
                throw new IllegalStateException("cannot list");
            }
            return infos;
        }

        public LiveInventory open(LiveHolder holder, String section) {
            return null;
        }

        public Section inspectItem(ItemStack stack) {
            return null;
        }
    }

    @Test
    void inventoriesReportAFailingReaderAsPartial() {
        var registry = new WorldRegistry();
        var info = new InventoryInfo(new InventoryId("good", "main"), "Main", 27, SlotRole.STORAGE);
        registry.register(new ListingReader("good", List.of(info)));
        registry.register(new ListingReader("bad", null));
        var holder = new LiveHolder.OfItem(new ItemStack(Items.DIAMOND));
        var page = registry.inventories(holder);
        assertEquals(List.of(info), page.items());
        assertEquals(Evidence.Coverage.PARTIAL, page.coverage());
        assertNull(page.nextCursor());
        assertTrue(page.note().contains("bad") && page.note().contains("cannot list"));
        var healthy = new WorldRegistry();
        healthy.register(new ListingReader("good", List.of(info)));
        assertEquals(Evidence.Coverage.COMPLETE, healthy.inventories(holder).coverage());
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

    private record FakeProgressAndStatistics(String adapterId, Evidence.JsonObject facts) implements ProgressReader, StatisticReader {
        public ServerWork.Slice<ProgressSnapshot> snapshot(ServerPlayer player) {
            return null;
        }

        public Progress inspect(ServerPlayer player, String local) {
            return null;
        }

        public Evidence.JsonObject progressFacts(ServerPlayer player, ProgressId id) {
            if (facts == null) {
                throw new IllegalStateException("facts failed");
            }
            return facts;
        }

        public List<String> categories() {
            return List.of();
        }

        public ServerWork.Slice<List<Statistic>> statistics(ServerPlayer player, List<String> categories, KindFilter subjects) {
            return null;
        }
    }

    @Test
    void registersProgressAndStatisticReadersAndIsolatesAFailingFactsAdapter() {
        var registry = new WorldRegistry();
        var facts = new Evidence.JsonObject(Map.of("quest", new Evidence.JsonString("linked")));
        var owner = new FakeProgressAndStatistics("a", facts);
        registry.register(owner);
        registry.register(new FakeProgressAndStatistics("b", facts));
        registry.register(new FakeProgressAndStatistics("c", null));
        assertEquals(List.of("a", "b", "c"), registry.progressReaders().stream().map(ProgressReader::adapterId).toList());
        assertEquals(List.of("a", "b", "c"), registry.statisticReaders().stream().map(StatisticReader::adapterId).toList());
        assertSame(owner, registry.progressReaders().get(0));
        assertSame(owner, registry.statisticReaders().get(0));
        var sections = registry.progressFacts(null, new ProgressId("a", "minecraft:story/root"), "a");
        assertEquals(List.of("b", "c"), List.copyOf(sections.keySet()));
        assertFalse(sections.containsKey("a"));
        assertEquals(Evidence.Coverage.COMPLETE, sections.get("b").coverage());
        assertEquals(facts, sections.get("b").facts());
        assertEquals(Evidence.Coverage.ERROR, sections.get("c").coverage());
        assertEquals("facts failed", sections.get("c").note());
    }
}
