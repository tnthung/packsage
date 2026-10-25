package dev.packsage.world.minecraft;

import dev.packsage.world.SlotRole;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.HorseInventoryMenu;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.MerchantResultSlot;
import net.minecraft.world.inventory.NonInteractiveResultSlot;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The sections of an open server-side menu: its own active slots, by menu slot index, without the player inventory and
 * without the output previews a menu shows before anything is taken.
 */
public final class MinecraftMenus {
    private static final String VANILLA_PACKAGE = "net.minecraft.world.inventory";

    public record MenuSection(String section, String label, SlotRole role, List<Integer> menuSlots) {
        public MenuSection {
            menuSlots = List.copyOf(menuSlots);
        }
    }

    /** Kind is recipe_preview or trade_preview. */
    public record ExcludedSlot(int slot, String kind) {
    }

    /** Vanilla is false for a menu class outside Minecraft, whose previews cannot be told apart. */
    public record MenuLayout(List<MenuSection> sections, List<ExcludedSlot> excluded, boolean vanilla) {
        public MenuLayout {
            sections = List.copyOf(sections);
            excluded = List.copyOf(excluded);
        }
    }

    private record Kind(String section, String label, SlotRole role) {
    }

    private MinecraftMenus() {
    }

    public static MenuLayout layout(AbstractContainerMenu menu, Container playerInventory) {
        return layout(menu.getClass(), menu.slots, playerInventory, menu instanceof ItemCombinerMenu combiner ? combiner.getResultSlot() : -1);
    }

    public static MenuLayout layout(Class<?> menuType, List<Slot> slots, Container playerInventory, int combinerResultSlot) {
        var vanilla = menuType.getPackageName().equals(VANILLA_PACKAGE);
        var kinds = new LinkedHashMap<Kind, List<Integer>>();
        var excluded = new ArrayList<ExcludedSlot>();
        for (int index = 0; index < slots.size(); index++) {
            var slot = slots.get(index);
            if (slot.container == playerInventory || !slot.isActive()) continue;
            var preview = previewKind(menuType, slot, index, combinerResultSlot);
            if (preview != null) {
                excluded.add(new ExcludedSlot(index, preview));
                continue;
            }
            kinds.computeIfAbsent(kind(menuType, slot, index, vanilla), key -> new ArrayList<>()).add(index);
        }
        var sections = new ArrayList<MenuSection>();
        kinds.forEach((kind, menuSlots) -> sections.add(new MenuSection(kind.section(), kind.label(), kind.role(), menuSlots)));
        return new MenuLayout(sections, excluded, vanilla);
    }

    /** The menu's registry key, or unknown when it has no registered type. */
    public static String menuType(AbstractContainerMenu menu) {
        try {
            var key = BuiltInRegistries.MENU.getKey(menu.getType());
            return key == null ? "unknown" : key.toString();
        } catch (RuntimeException exception) {
            return "unknown";
        }
    }

    private static String previewKind(Class<?> menuType, Slot slot, int index, int combinerResultSlot) {
        if (slot instanceof ResultSlot || slot instanceof NonInteractiveResultSlot) return "recipe_preview";
        if (slot instanceof MerchantResultSlot) return "trade_preview";
        if (menuType == GrindstoneMenu.class && index == 2 || menuType == StonecutterMenu.class && index == 1
                || menuType == LoomMenu.class && index == 3 || menuType == CartographyTableMenu.class && index == 2
                || (menuType == AnvilMenu.class || menuType == SmithingMenu.class) && index == combinerResultSlot) {
            return "recipe_preview";
        }
        return null;
    }

    private static Kind kind(Class<?> menuType, Slot slot, int index, boolean vanilla) {
        if (menuType == ChestMenu.class || menuType == ShulkerBoxMenu.class || menuType == HopperMenu.class
                || menuType == DispenserMenu.class || menuType == CrafterMenu.class) {
            return new Kind("container", "Container", SlotRole.STORAGE);
        }
        if (menuType == LecternMenu.class) return new Kind("container", "Book", SlotRole.DISPLAY);
        if (AbstractFurnaceMenu.class.isAssignableFrom(menuType) && index <= 2) {
            return switch (index) {
                case 0 -> new Kind("input", "Input", SlotRole.INPUT);
                case 1 -> new Kind("fuel", "Fuel", SlotRole.FUEL);
                default -> new Kind("output", "Output", SlotRole.OUTPUT);
            };
        }
        if (menuType == BrewingStandMenu.class && index <= 4) {
            return index <= 2 ? new Kind("bottles", "Bottles", SlotRole.OUTPUT)
                    : index == 3 ? new Kind("ingredient", "Ingredient", SlotRole.INPUT) : new Kind("fuel", "Fuel", SlotRole.FUEL);
        }
        if (menuType == HorseInventoryMenu.class) {
            if (index == 0) return new Kind("saddle", "Saddle", SlotRole.EQUIPMENT);
            if (index == 1) {
                return slot.getItem().is(ItemTags.WOOL_CARPETS) ? new Kind("carpet", "Carpet", SlotRole.EQUIPMENT)
                        : new Kind("body_armor", "Body armor", SlotRole.EQUIPMENT);
            }
            return new Kind("chest", "Chest", SlotRole.STORAGE);
        }
        return vanilla ? new Kind("menu", "Menu", SlotRole.INPUT) : new Kind("menu", "Menu", SlotRole.STORAGE);
    }
}
