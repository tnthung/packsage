package dev.packsage.world.minecraft;

import com.mojang.serialization.Lifecycle;
import dev.packsage.knowledge.Evidence;
import dev.packsage.world.LiveHolder;
import dev.packsage.world.SlotRole;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.HorseInventoryMenu;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class MinecraftMenusTest {
    private static MinecraftInventories inventories;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var enchantments = new MappedRegistry<Enchantment>(Registries.ENCHANTMENT, Lifecycle.stable());
        var provider = HolderLookup.Provider.create(Stream.<HolderLookup.RegistryLookup<?>>of(enchantments.asLookup()));
        inventories = new MinecraftInventories(() -> provider);
    }

    private static Map<String, List<Integer>> sections(MinecraftMenus.MenuLayout layout) {
        var sections = new java.util.LinkedHashMap<String, List<Integer>>();
        for (var section : layout.sections()) sections.put(section.section(), section.menuSlots());
        return sections;
    }

    private static List<Slot> slots(Container container, int count) {
        var slots = new ArrayList<Slot>();
        for (int i = 0; i < count; i++) slots.add(new Slot(container, i, 0, 0));
        return slots;
    }

    @Test
    void craftingGridIsListedAndItsOutputPreviewExcluded() {
        var result = new SimpleContainer(1);
        result.setItem(0, new ItemStack(Items.CRAFTING_TABLE));
        var grid = new TransientCraftingContainer(new TestMenu(), 3, 3);
        var slots = new ArrayList<Slot>();
        slots.add(new ResultSlot(null, grid, result, 0, 0, 0));
        slots.addAll(slots(grid, 9));
        var layout = MinecraftMenus.layout(CraftingMenu.class, slots, new SimpleContainer(36), -1);

        assertEquals(Map.of("menu", List.of(1, 2, 3, 4, 5, 6, 7, 8, 9)), sections(layout));
        assertEquals(List.of(new MinecraftMenus.ExcludedSlot(0, "recipe_preview")), layout.excluded());
        assertTrue(layout.vanilla());
    }

    @Test
    void furnaceBrewingAndHorseMenusUseTheirBlockAndEntitySections() {
        var player = new SimpleContainer(36);
        var furnace = MinecraftMenus.layout(FurnaceMenu.class, slots(new SimpleContainer(3), 3), player, -1);
        assertEquals(List.of("input", "fuel", "output"), furnace.sections().stream().map(MinecraftMenus.MenuSection::section).toList());
        assertEquals(List.of(List.of(0), List.of(1), List.of(2)), furnace.sections().stream().map(MinecraftMenus.MenuSection::menuSlots).toList());
        assertEquals(SlotRole.FUEL, furnace.sections().get(1).role());
        assertTrue(furnace.excluded().isEmpty());

        var brewing = MinecraftMenus.layout(BrewingStandMenu.class, slots(new SimpleContainer(5), 5), player, -1);
        assertEquals(Map.of("bottles", List.of(0, 1, 2), "ingredient", List.of(3), "fuel", List.of(4)), sections(brewing));

        var horse = new SimpleContainer(4);
        var armor = new SimpleContainer(1);
        armor.setItem(0, new ItemStack(Items.DIAMOND_HORSE_ARMOR));
        var horseSlots = new ArrayList<Slot>();
        horseSlots.add(new Slot(horse, 0, 0, 0));
        horseSlots.add(new Slot(armor, 0, 0, 0));
        horseSlots.add(new Slot(horse, 1, 0, 0));
        horseSlots.add(new Slot(horse, 2, 0, 0));
        assertEquals(Map.of("saddle", List.of(0), "body_armor", List.of(1), "chest", List.of(2, 3)),
                sections(MinecraftMenus.layout(HorseInventoryMenu.class, horseSlots, player, -1)));

        armor.setItem(0, new ItemStack(Items.WHITE_CARPET));
        var carpet = Items.WHITE_CARPET.builtInRegistryHolder();
        var tags = carpet.tags().toList();
        bindTags(carpet, List.of(ItemTags.WOOL_CARPETS));
        try {
            assertEquals(Map.of("saddle", List.of(0), "carpet", List.of(1), "chest", List.of(2, 3)),
                    sections(MinecraftMenus.layout(HorseInventoryMenu.class, horseSlots, player, -1)));
        } finally {
            bindTags(carpet, tags);
        }
    }

    @Test
    void playerInventorySlotsAndInactiveSlotsAreLeftOut() {
        var player = new SimpleContainer(36);
        var horse = new SimpleContainer(4);
        var slots = new ArrayList<Slot>();
        slots.add(new Slot(horse, 0, 0, 0));
        // A donkey's body armor slot is inactive.
        slots.add(new Slot(new SimpleContainer(1), 0, 0, 0) {
            @Override
            public boolean isActive() {
                return false;
            }
        });
        slots.add(new Slot(horse, 1, 0, 0));
        slots.add(new Slot(player, 0, 0, 0));
        slots.add(new Slot(player, 1, 0, 0));
        var layout = MinecraftMenus.layout(HorseInventoryMenu.class, slots, player, -1);
        assertEquals(Map.of("saddle", List.of(0), "chest", List.of(2)), sections(layout));
        assertTrue(layout.excluded().isEmpty());
    }

    @Test
    void aModdedMenuListsItsSlotsUnderMenuAndIsNotVanilla() {
        var player = new SimpleContainer(36);
        var slots = new ArrayList<Slot>(slots(new SimpleContainer(2), 2));
        slots.add(new Slot(player, 0, 0, 0));
        var layout = MinecraftMenus.layout(TestMenu.class, slots, player, -1);
        assertEquals(Map.of("menu", List.of(0, 1)), sections(layout));
        assertFalse(layout.vanilla());
        assertEquals("unknown", MinecraftMenus.menuType(new TestMenu()));
    }

    @Test
    void readingAnOpenedMenuLeavesItsSlotsUntouched() {
        var storage = new SimpleContainer(3);
        storage.setItem(0, new ItemStack(Items.OAK_PLANKS, 4));
        storage.setItem(2, new ItemStack(Items.SHULKER_BOX));
        var player = new SimpleContainer(36);
        player.setItem(0, new ItemStack(Items.STONE));
        var menu = new TestMenu();
        menu.slots.add(new ReadOnlySlot(player, 0));
        for (int i = 0; i < 3; i++) menu.slots.add(new ReadOnlySlot(storage, i));
        var stacks = new ArrayList<ItemStack>();
        var copies = new ArrayList<ItemStack>();
        for (var slot : menu.slots) {
            stacks.add(slot.getItem());
            copies.add(slot.getItem().copy());
        }
        var holder = new LiveHolder.OfMenu(menu, player);

        var infos = inventories.inventories(holder);
        assertEquals(List.of("minecraft/menu"), infos.stream().map(info -> info.id().text()).toList());
        assertEquals(3, infos.getFirst().slots());
        var opened = inventories.open(holder, "menu");
        assertNotNull(opened);
        assertEquals(List.of("1", "3"), opened.entries().stream().map(Map.Entry::getKey).toList());
        assertTrue(opened.get("1").is(Items.OAK_PLANKS));
        assertTrue(opened.get("2").isEmpty());
        assertNull(opened.get("0"), "the player inventory slot is not part of the menu section");
        assertNull(opened.get("4"));
        assertNotNull(inventories.inspectItem(opened.get("1")));
        assertNotNull(inventories.inspectItem(opened.get("3")));
        assertEquals(new Evidence.JsonObject(Map.of("menuSlot", new Evidence.JsonNumber(java.math.BigDecimal.valueOf(3)))),
                inventories.entryFacts(holder, "menu", "3"));
        for (int i = 0; i < menu.slots.size(); i++) {
            var now = menu.slots.get(i).getItem();
            assertSame(stacks.get(i), now, "slot " + i + " holds the same stack");
            assertTrue(ItemStack.matches(copies.get(i), now), "slot " + i + " keeps its item, count and components");
        }
    }

    @SuppressWarnings("unchecked")
    private static void bindTags(Holder.Reference<Item> holder, Collection<TagKey<Item>> tags) {
        try {
            var bind = Holder.Reference.class.getDeclaredMethod("bindTags", Collection.class);
            bind.setAccessible(true);
            bind.invoke(holder, tags);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    /** A slot that fails the test when anything writes to it or takes from it. */
    private static final class ReadOnlySlot extends Slot {
        ReadOnlySlot(Container container, int index) {
            super(container, index, 0, 0);
        }

        @Override
        public void set(ItemStack stack) {
            fail("set");
        }

        @Override
        public void setChanged() {
            fail("setChanged");
        }

        @Override
        public void setByPlayer(ItemStack stack) {
            fail("setByPlayer");
        }

        @Override
        public void setByPlayer(ItemStack stack, ItemStack previous) {
            fail("setByPlayer");
        }

        @Override
        public void onTake(Player player, ItemStack stack) {
            fail("onTake");
        }

        @Override
        public ItemStack remove(int amount) {
            return fail("remove");
        }

        @Override
        public ItemStack safeTake(int count, int decrement, Player player) {
            return fail("safeTake");
        }
    }

    /** A menu that fails the test when anything moves, clicks or syncs its slots. */
    private static final class TestMenu extends AbstractContainerMenu {
        private TestMenu() {
            super(null, 0);
        }

        @Override
        public ItemStack quickMoveStack(Player player, int slotIndex) {
            return fail("quickMoveStack");
        }

        @Override
        public void broadcastChanges() {
            fail("broadcastChanges");
        }

        @Override
        public void slotsChanged(Container container) {
            fail("slotsChanged");
        }

        @Override
        public void clicked(int slot, int button, net.minecraft.world.inventory.ClickType type, Player player) {
            fail("clicked");
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }
}
