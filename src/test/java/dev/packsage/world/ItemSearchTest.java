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
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        var page = run(new ItemSearch(registry(reader), holders, IRON, true, null));

        assertEquals(Evidence.Coverage.COMPLETE, page.coverage());
        assertNull(page.nextCursor());
        assertEquals(1, page.items().size());
        var entry = page.items().get(0);
        assertEquals(5, entry.count());
        assertEquals(List.of(
                "entity:" + ENTITY + ">fake/box#0@minecraft:iron_ingot",
                "entity:" + ENTITY + ">fake/box#1@minecraft:shulker_box>fake/box#1@minecraft:iron_ingot"),
                entry.locations().stream().map(ItemPath::text).toList());

        var shallow = run(new ItemSearch(registry(reader), holders, IRON, false, null));
        assertEquals(2, shallow.items().get(0).count());

        var boxes = run(new ItemSearch(registry(reader), holders, new KindFilter(List.of("minecraft:shulker_box"), List.of(), ""), true, null));
        assertTrue(boxes.items().get(0).holdsItems());
    }

    @Test
    void stopsAt512ContainersWithACursorThatContinues() {
        var reader = new FakeReader();
        var holders = new ArrayList<Map.Entry<PathRoot, LiveHolder>>();
        for (var i = 0; i < 600; i++) {
            holders.add(holder(i, reader.box(new ItemStack(Items.IRON_INGOT))));
        }

        var first = run(new ItemSearch(registry(reader), holders, IRON, true, null));

        assertEquals(Evidence.Coverage.PARTIAL, first.coverage());
        assertEquals("512", first.nextCursor());
        assertEquals(512, first.items().get(0).count());

        var second = run(new ItemSearch(registry(reader), holders, IRON, true, first.nextCursor()));

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

        var first = run(new ItemSearch(registry(reader), holders, IRON, true, null));

        assertEquals("256", first.nextCursor());
        assertEquals(256, first.items().get(0).count());
    }

    @Test
    void aPageEndingInsideAHolderResumesAtItsNextContainer() {
        var reader = new FakeReader();
        var holders = new ArrayList<Map.Entry<PathRoot, LiveHolder>>();
        for (var i = 0; i < 511; i++) {
            holders.add(holder(i, reader.box(new ItemStack(Items.IRON_INGOT))));
        }
        holders.add(holder(511, reader.box(reader.box(new ItemStack(Items.DIAMOND)), new ItemStack(Items.IRON_INGOT))));
        var diamond = new KindFilter(List.of("minecraft:diamond"), List.of(), "");

        var first = run(new ItemSearch(registry(reader), holders, diamond, true, null));

        assertEquals(Evidence.Coverage.PARTIAL, first.coverage());
        assertEquals("511.1", first.nextCursor());
        assertTrue(first.items().isEmpty());

        var second = run(new ItemSearch(registry(reader), holders, diamond, true, first.nextCursor()));

        assertEquals(Evidence.Coverage.COMPLETE, second.coverage());
        assertNull(second.nextCursor());
        assertEquals(List.of(new UUID(0, 511) + ">fake/box#0@minecraft:shulker_box>fake/box#0@minecraft:diamond"),
                second.items().get(0).locations().stream().map(path -> path.text().substring("entity:".length())).toList());

        var resumedIron = run(new ItemSearch(registry(reader), holders, IRON, true, first.nextCursor()));
        assertTrue(resumedIron.items().isEmpty(), "containers before the cursor's ordinal belong to the earlier page");
    }

    @Test
    void eachContainerIsOneStep() {
        var reader = new FakeReader();
        var holders = List.of(holder(0, reader.box(reader.box(), reader.box(), reader.box())));
        var search = new ItemSearch(registry(reader), holders, IRON, true, null);

        var steps = 1;
        while (search.step()) {
            steps++;
        }

        assertEquals(4, steps);
    }

    @Test
    void malformedCursorsAreRejected() {
        var registry = registry(new FakeReader());
        for (var cursor : List.of("", "x", "1.", ".1", "-1", "1.-2", "1.2.3")) {
            assertThrows(IllegalArgumentException.class, () -> new ItemSearch(registry, List.of(), IRON, true, cursor), cursor);
        }
    }

    @Test
    void nestingStopsAtSixSteps() {
        var reader = new FakeReader();
        var box = reader.box(new ItemStack(Items.IRON_INGOT));
        for (var i = 0; i < 6; i++) {
            box = reader.box(box);
        }
        var holders = List.of(holder(0, reader.box(box)));

        var irons = run(new ItemSearch(registry(reader), holders, IRON, true, null));
        assertTrue(irons.items().isEmpty());
        assertEquals(Evidence.Coverage.PARTIAL, irons.coverage());

        var boxes = run(new ItemSearch(registry(reader), holders, new KindFilter(List.of("minecraft:shulker_box"), List.of(), ""), true, null));
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

        var page = run(new ItemSearch(registry, holders, IRON, true, null));

        assertEquals(Evidence.Coverage.PARTIAL, page.coverage());
        assertEquals(1, page.items().get(0).count());
        assertTrue(page.note().contains("boom"));
    }
}
