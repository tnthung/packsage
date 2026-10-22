package dev.packsage.world;

import dev.packsage.knowledge.Evidence;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemSearchTest {
    private static final UUID ENTITY = UUID.fromString("3f1c0000-0000-0000-0000-000000000001");
    private static final InventoryId BOX = new InventoryId("fake", "box");
    private static final KindFilter IRON = new KindFilter(List.of("minecraft:iron_ingot"), List.of(), "");

    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.bootStrap();
    }

    /** Reads the contents registered for each item stack, by identity. */
    private static final class FakeReader implements InventoryReader {
        final Map<ItemStack, List<ItemStack>> contents = new IdentityHashMap<>();

        ItemStack box(ItemStack... items) {
            var box = new ItemStack(Items.SHULKER_BOX);
            contents.put(box, List.of(items));
            return box;
        }

        public String adapterId() {
            return BOX.adapter();
        }

        public List<InventoryInfo> inventories(LiveHolder holder) {
            if (holder instanceof LiveHolder.OfItem item && contents.containsKey(item.stack())) {
                return List.of(new InventoryInfo(BOX, "Box", 27, SlotRole.STORAGE));
            }
            return List.of();
        }

        public LiveInventory open(LiveHolder holder, String section) {
            if (!BOX.section().equals(section) || !(holder instanceof LiveHolder.OfItem item) || !contents.containsKey(item.stack())) {
                return null;
            }
            var items = contents.get(item.stack());
            return new LiveInventory() {
                public ItemStack get(String entry) {
                    return items.get(Integer.parseInt(entry));
                }

                public List<Map.Entry<String, ItemStack>> entries() {
                    var entries = new ArrayList<Map.Entry<String, ItemStack>>();
                    for (var i = 0; i < items.size(); i++) {
                        entries.add(Map.entry(String.valueOf(i), items.get(i)));
                    }
                    return entries;
                }
            };
        }

        public Evidence.JsonObject inspectItem(ItemStack stack) {
            return null;
        }
    }

    private static WorldRegistry registry(FakeReader reader) {
        var registry = new WorldRegistry();
        registry.register(reader);
        return registry;
    }

    private static Map.Entry<PathRoot, LiveHolder> holder(int index, ItemStack box) {
        return Map.entry(new PathRoot.Entity(new UUID(0, index)), new LiveHolder.OfItem(box));
    }

    private static <T> T run(ServerWork.Slice<T> slice) {
        while (slice.step()) {
        }
        return slice.result();
    }

    @Test
    void findsNestedMatchesAndReportsTheirPaths() {
        var reader = new FakeReader();
        var inner = reader.box(ItemStack.EMPTY, new ItemStack(Items.IRON_INGOT, 3));
        var outer = reader.box(new ItemStack(Items.IRON_INGOT, 2), inner);
        var holders = List.of(Map.<PathRoot, LiveHolder>entry(new PathRoot.Entity(ENTITY), new LiveHolder.OfItem(outer)));

        var page = run(new ItemSearch(registry(reader), holders, IRON, true, 0));

        assertEquals(Evidence.Coverage.COMPLETE, page.coverage());
        assertNull(page.nextCursor());
        assertEquals(1, page.items().size());
        var entry = page.items().get(0);
        assertEquals(5, entry.count());
        assertEquals(List.of(
                "entity:" + ENTITY + ">fake/box#0@minecraft:iron_ingot",
                "entity:" + ENTITY + ">fake/box#1@minecraft:shulker_box>fake/box#1@minecraft:iron_ingot"),
                entry.locations().stream().map(ItemPath::text).toList());

        var shallow = run(new ItemSearch(registry(reader), holders, IRON, false, 0));
        assertEquals(2, shallow.items().get(0).count());

        var boxes = run(new ItemSearch(registry(reader), holders, new KindFilter(List.of("minecraft:shulker_box"), List.of(), ""), true, 0));
        assertTrue(boxes.items().get(0).holdsItems());
    }

    @Test
    void stopsAt512ContainersWithACursorThatContinues() {
        var reader = new FakeReader();
        var holders = new ArrayList<Map.Entry<PathRoot, LiveHolder>>();
        for (var i = 0; i < 600; i++) {
            holders.add(holder(i, reader.box(new ItemStack(Items.IRON_INGOT))));
        }

        var first = run(new ItemSearch(registry(reader), holders, IRON, true, 0));

        assertEquals(Evidence.Coverage.PARTIAL, first.coverage());
        assertEquals("512", first.nextCursor());
        assertEquals(512, first.items().get(0).count());

        var second = run(new ItemSearch(registry(reader), holders, IRON, true, Integer.parseInt(first.nextCursor())));

        assertEquals(Evidence.Coverage.COMPLETE, second.coverage());
        assertNull(second.nextCursor());
        assertEquals(88, second.items().get(0).count());
    }

    @Test
    void nestedContainersCountTowardThePageLimit() {
        var reader = new FakeReader();
        var holders = new ArrayList<Map.Entry<PathRoot, LiveHolder>>();
        for (var i = 0; i < 300; i++) {
            holders.add(holder(i, reader.box(reader.box(new ItemStack(Items.IRON_INGOT)))));
        }

        var first = run(new ItemSearch(registry(reader), holders, IRON, true, 0));

        assertEquals("256", first.nextCursor());
        assertEquals(256, first.items().get(0).count());
    }

    @Test
    void nestingStopsAtSixSteps() {
        var reader = new FakeReader();
        var box = reader.box(new ItemStack(Items.IRON_INGOT));
        for (var i = 0; i < 6; i++) {
            box = reader.box(box);
        }
        var holders = List.of(holder(0, reader.box(box)));

        var irons = run(new ItemSearch(registry(reader), holders, IRON, true, 0));
        assertTrue(irons.items().isEmpty());
        assertEquals(Evidence.Coverage.PARTIAL, irons.coverage());

        var boxes = run(new ItemSearch(registry(reader), holders, new KindFilter(List.of("minecraft:shulker_box"), List.of(), ""), true, 0));
        assertEquals(6, boxes.items().get(0).count());
        assertEquals(ItemPath.MAX_STEPS, boxes.items().get(0).locations().stream().mapToInt(path -> path.steps().size()).max().orElse(0));
    }

    @Test
    void readerFailuresMakeThePagePartial() {
        var reader = new FakeReader();
        var registry = registry(reader);
        registry.register(new InventoryReader() {
            public String adapterId() {
                return "broken";
            }

            public List<InventoryInfo> inventories(LiveHolder holder) {
                throw new IllegalStateException("boom");
            }

            public LiveInventory open(LiveHolder holder, String section) {
                return null;
            }

            public Evidence.JsonObject inspectItem(ItemStack stack) {
                return null;
            }
        });
        var holders = List.of(holder(0, reader.box(new ItemStack(Items.IRON_INGOT))));

        var page = run(new ItemSearch(registry, holders, IRON, true, 0));

        assertEquals(Evidence.Coverage.PARTIAL, page.coverage());
        assertEquals(1, page.items().get(0).count());
        assertTrue(page.note().contains("boom"));
    }
}
