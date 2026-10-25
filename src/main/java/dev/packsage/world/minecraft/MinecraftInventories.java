package dev.packsage.world.minecraft;

import com.google.common.base.Suppliers;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.nativegame.NativeEnchantmentEligibility;
import dev.packsage.knowledge.nativegame.NativeItemFacts;
import dev.packsage.world.BlockPlace;
import dev.packsage.world.InventoryId;
import dev.packsage.world.InventoryInfo;
import dev.packsage.world.InventoryReader;
import dev.packsage.world.LiveHolder;
import dev.packsage.world.LiveInventory;
import dev.packsage.world.Section;
import dev.packsage.world.SlotRole;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Fox;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.horse.AbstractChestedHorse;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.animal.horse.Llama;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoubleBlockCombiner;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/** Vanilla inventories. Every read is side-effect free: no lazy creation, loot generation or component changes. */
public final class MinecraftInventories implements InventoryReader {
    public static final String ADAPTER = "minecraft";
    private static final int SHULKER_SLOTS = 27;
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD};
    private static final EquipmentSlot[] HANDS = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};

    /**
     * slots is the reported size (-1 = keyed); size bounds the entries actually readable. pendingLoot is the loot table
     * of a section whose loot is not generated yet, or null. keys are the entry names of positions 0 to size - 1, such
     * as menu slot indexes, or null when each position is its own entry.
     */
    record Part(String section, String label, SlotRole role, int slots, int size, IntFunction<ItemStack> slot, String pendingLoot,
                List<Integer> keys) {
        Part(String section, String label, SlotRole role, int slots, int size, IntFunction<ItemStack> slot, String pendingLoot) {
            this(section, label, role, slots, size, slot, pendingLoot, null);
        }

        Part(String section, String label, SlotRole role, int slots, int size, IntFunction<ItemStack> slot) {
            this(section, label, role, slots, size, slot, null);
        }

        InventoryInfo info() {
            return new InventoryInfo(new InventoryId(ADAPTER, section), label, slots, role, pendingLoot);
        }
    }

    private final Supplier<HolderLookup.Provider> registries;

    public MinecraftInventories(Supplier<HolderLookup.Provider> registries) {
        this.registries = registries;
    }

    @Override
    public String adapterId() {
        return ADAPTER;
    }

    @Override
    public List<InventoryInfo> inventories(LiveHolder holder) {
        return parts(holder).stream().map(Part::info).toList();
    }

    @Override
    public LiveInventory open(LiveHolder holder, String section) {
        for (var part : parts(holder)) {
            if (part.section().equals(section)) return new Opened(part);
        }
        return null;
    }

    private List<Part> parts(LiveHolder holder) {
        return switch (holder) {
            case LiveHolder.OfPlayer of -> player(of.player());
            case LiveHolder.OfBlock of -> block(of.level(), of.pos());
            case LiveHolder.OfEntity of -> entity(of.entity());
            case LiveHolder.OfItem of -> item(of.stack());
            case LiveHolder.OfMenu of -> menu(of.menu(), of.playerInventory());
        };
    }

    /** One part per menu section, keyed by menu slot index; reads only Slot.getItem. */
    private static List<Part> menu(AbstractContainerMenu menu, Container playerInventory) {
        var parts = new ArrayList<Part>();
        for (var section : MinecraftMenus.layout(menu, playerInventory).sections()) {
            var keys = section.menuSlots();
            parts.add(new Part(section.section(), section.label(), section.role(), keys.size(), keys.size(),
                    position -> menu.slots.get(keys.get(position)).getItem(), null, keys));
        }
        return parts;
    }

    private static List<Part> player(Player player) {
        var inventory = player.getInventory();
        return List.of(
                new Part("main", "Main inventory", SlotRole.STORAGE, 36, 36, inventory.items::get),
                new Part("armor", "Armor", SlotRole.EQUIPMENT, 4, 4, inventory.armor::get),
                new Part("offhand", "Offhand", SlotRole.EQUIPMENT, 1, 1, inventory.offhand::get),
                enderChest(player));
    }

    private static Part enderChest(Player player) {
        return container("ender_chest", "Ender chest", SlotRole.STORAGE, player.getEnderChestInventory(), 0, 27);
    }

    private static Part container(String section, String label, SlotRole role, Container container, int from, int count) {
        return new Part(section, label, role, count, count, index -> container.getItem(from + index));
    }

    private static Part fixed(String section, String label, SlotRole role, IntFunction<ItemStack> slot, int count) {
        return new Part(section, label, role, count, count, slot);
    }

    static Part unopenedLoot(String label, int slots, RandomizableContainer... halves) {
        for (var half : halves) {
            if (half.getLootTable() != null) {
                // Reading the slots would generate the loot, so report the pending table and no items.
                var table = half.getLootTable().location().toString();
                return new Part("container", label + ", loot not generated (" + table + ")",
                        SlotRole.STORAGE, slots, 0, index -> ItemStack.EMPTY, table);
            }
        }
        return null;
    }

    private static List<Part> block(ServerLevel level, BlockPos pos) {
        var chunk = loadedChunk(level, pos);
        if (chunk == null) return List.of();
        var state = chunk.getBlockState(pos);
        if (state.getBlock() instanceof EnderChestBlock) {
            var owner = owner(level);
            return owner == null ? List.of() : List.of(enderChest(owner));
        }
        var entity = chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
        if (entity == null) return List.of();
        if (state.getBlock() instanceof ChestBlock chest && entity instanceof Container single) {
            var combined = doubleChest(level, pos, state, chest, entity, single);
            if (combined != null) return List.of(combined);
        }
        if (entity instanceof AbstractFurnaceBlockEntity furnace) {
            return List.of(container("input", "Input", SlotRole.INPUT, furnace, 0, 1),
                    container("fuel", "Fuel", SlotRole.FUEL, furnace, 1, 1),
                    container("output", "Output", SlotRole.OUTPUT, furnace, 2, 1));
        }
        if (entity instanceof BrewingStandBlockEntity brewing) {
            return List.of(container("bottles", "Bottles", SlotRole.OUTPUT, brewing, 0, 3),
                    container("ingredient", "Ingredient", SlotRole.INPUT, brewing, 3, 1),
                    container("fuel", "Fuel", SlotRole.FUEL, brewing, 4, 1));
        }
        if (entity instanceof CampfireBlockEntity campfire) {
            var items = campfire.getItems();
            return List.of(fixed("cooking", "Cooking", SlotRole.INPUT, items::get, items.size()));
        }
        if (entity instanceof LecternBlockEntity lectern) {
            return List.of(fixed("container", "Book", SlotRole.DISPLAY, index -> lectern.getBook(), 1));
        }
        if (entity instanceof Container container) {
            var label = entity instanceof JukeboxBlockEntity ? "Record" : "Container";
            var role = entity instanceof JukeboxBlockEntity ? SlotRole.DISPLAY : SlotRole.STORAGE;
            if (entity instanceof RandomizableContainer loot) {
                var pending = unopenedLoot(label, container.getContainerSize(), loot);
                if (pending != null) return List.of(pending);
            }
            return List.of(container("container", label, role, container, 0, container.getContainerSize()));
        }
        return List.of();
    }

    /** Either half reads as the whole chest and names the other half; null when the chest is single. */
    private static Part doubleChest(ServerLevel level, BlockPos pos, BlockState state,
                                    ChestBlock chest, BlockEntity entity, Container single) {
        if (ChestBlock.getBlockType(state) == DoubleBlockCombiner.BlockType.SINGLE) return null;
        var otherPos = pos.relative(ChestBlock.getConnectedDirection(state));
        var otherChunk = loadedChunk(level, otherPos);
        if (otherChunk == null) return null;
        var other = otherChunk.getBlockEntity(otherPos, LevelChunk.EntityCreationType.CHECK);
        if (other == null) return null;
        var combined = ChestBlock.getContainer(chest, state, level, pos, true);
        if (combined == null || combined.getContainerSize() <= single.getContainerSize()) return null;
        var label = "Double chest, otherHalf " + new BlockPlace(level.dimension().location().toString(),
                otherPos.getX(), otherPos.getY(), otherPos.getZ()).text();
        if (entity instanceof RandomizableContainer self && other instanceof RandomizableContainer half) {
            var pending = unopenedLoot(label, combined.getContainerSize(), self, half);
            if (pending != null) return pending;
        }
        return container("container", label, SlotRole.STORAGE, combined, 0, combined.getContainerSize());
    }

    private static LevelChunk loadedChunk(ServerLevel level, BlockPos pos) {
        return level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    /** The ender chest block shows the singleplayer host's ender chest. */
    private static ServerPlayer owner(ServerLevel level) {
        var server = level.getServer();
        var profile = server.getSingleplayerProfile();
        var player = profile == null ? null : server.getPlayerList().getPlayer(profile.getId());
        if (player == null && !server.getPlayerList().getPlayers().isEmpty()) player = server.getPlayerList().getPlayers().getFirst();
        return player;
    }

    private static List<Part> entity(Entity entity) {
        if (entity instanceof Player player) return player(player);
        if (entity instanceof ItemFrame frame) return List.of(fixed("held", "Held item", SlotRole.DISPLAY, index -> frame.getItem(), 1));
        if (entity instanceof AbstractHorse horse) return horse(horse);
        if (entity instanceof Allay || entity instanceof Fox) {
            var living = (LivingEntity) entity;
            return List.of(fixed("held", "Held item", SlotRole.EQUIPMENT, index -> living.getItemBySlot(EquipmentSlot.MAINHAND), 1));
        }
        if (entity instanceof AbstractVillager villager) {
            return List.of(container("inventory", "Inventory", SlotRole.STORAGE, villager.getInventory(), 0, villager.getInventory().getContainerSize()));
        }
        if (entity instanceof ContainerEntity container) {
            if (container.getLootTable() != null) {
                var table = container.getLootTable().location().toString();
                return List.of(new Part("container", "Container, loot not generated (" + table + ")",
                        SlotRole.STORAGE, container.getContainerSize(), 0, index -> ItemStack.EMPTY, table));
            }
            return List.of(container("container", "Container", SlotRole.STORAGE, container, 0, container.getContainerSize()));
        }
        if (entity instanceof ArmorStand || entity instanceof Mob) {
            var living = (LivingEntity) entity;
            return List.of(fixed("armor", "Armor", SlotRole.EQUIPMENT, index -> living.getItemBySlot(ARMOR[index]), ARMOR.length),
                    fixed("hands", "Hands", SlotRole.EQUIPMENT, index -> living.getItemBySlot(HANDS[index]), HANDS.length));
        }
        return List.of();
    }

    private static List<Part> horse(AbstractHorse horse) {
        var parts = new ArrayList<Part>();
        if (horse instanceof Llama) {
            parts.add(fixed("carpet", "Carpet", SlotRole.EQUIPMENT, index -> horse.getBodyArmorItem(), 1));
        } else {
            parts.add(fixed("saddle", "Saddle", SlotRole.EQUIPMENT, index -> horse.getSlot(400).get(), 1));
        }
        if (horse instanceof Horse) {
            parts.add(fixed("body_armor", "Body armor", SlotRole.EQUIPMENT, index -> horse.getBodyArmorItem(), 1));
        }
        if (horse instanceof AbstractChestedHorse chested && chested.hasChest()) {
            // Entity.getSlot 500 + i addresses chest slot i without touching the protected inventory.
            parts.add(fixed("chest", "Chest", SlotRole.STORAGE, index -> horse.getSlot(500 + index).get(), chested.getInventoryColumns() * 3));
        }
        return parts;
    }

    private static List<Part> item(ItemStack stack) {
        var parts = new ArrayList<Part>();
        var shulker = stack.getItem() instanceof BlockItem block && block.getBlock() instanceof ShulkerBoxBlock;
        if (shulker || stack.has(DataComponents.CONTAINER)) {
            var contents = stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
            var slots = shulker ? Math.max(SHULKER_SLOTS, contents.getSlots()) : contents.getSlots();
            var live = Suppliers.memoize(() -> liveSlots(contents));
            parts.add(new Part("container", "Container", SlotRole.STORAGE, slots, slots,
                    index -> index < live.get().size() ? live.get().get(index) : ItemStack.EMPTY));
        }
        var bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
            parts.add(new Part("bundle", "Bundle", SlotRole.STORAGE, -1, bundle.size(), bundle::getItemUnsafe));
        }
        return parts;
    }

    /** The component's own stacks by slot; the public accessors only hand out copies or skip empty slots. */
    private static List<ItemStack> liveSlots(ItemContainerContents contents) {
        var live = contents.nonEmptyItems().iterator();
        var slots = new ArrayList<ItemStack>(contents.getSlots());
        contents.stream().forEach(copy -> slots.add(copy.isEmpty() ? ItemStack.EMPTY : live.next()));
        return slots;
    }

    /** Entries are decimal indexes, or the part's keys; entries() lists only occupied ones. */
    private record Opened(Part part) implements LiveInventory {
        @Override
        public ItemStack get(String entry) {
            var index = index(entry);
            var position = part.keys() == null || index < 0 ? index : part.keys().indexOf(index);
            return position < 0 || position >= part.size() ? null : part.slot().apply(position);
        }

        @Override
        public List<Map.Entry<String, ItemStack>> entries() {
            var entries = new ArrayList<Map.Entry<String, ItemStack>>();
            for (int position = 0; position < part.size(); position++) {
                var stack = part.slot().apply(position);
                var key = part.keys() == null ? position : part.keys().get(position);
                if (stack != null && !stack.isEmpty()) entries.add(Map.entry(Integer.toString(key), stack));
            }
            return entries;
        }

        private static int index(String entry) {
            if (entry == null || entry.isEmpty() || entry.length() > 9 || (entry.length() > 1 && entry.charAt(0) == '0')) return -1;
            for (int i = 0; i < entry.length(); i++) {
                if (entry.charAt(i) < '0' || entry.charAt(i) > '9') return -1;
            }
            return Integer.parseInt(entry);
        }
    }

    @Override
    public Evidence.JsonObject entryFacts(LiveHolder holder, String section, String entry) {
        var index = Opened.index(entry);
        if (index < 0) return null;
        if (holder instanceof LiveHolder.OfMenu) return new Evidence.JsonObject(Map.of("menuSlot", number(index)));
        return holder instanceof LiveHolder.OfPlayer of ? playerSlotFacts(section, index, of.player().getInventory().selected) : null;
    }

    /** The slot index of main and armor entries, the selected hotbar slot and the equipment slot; null for other sections. */
    static Evidence.JsonObject playerSlotFacts(String section, int index, int selected) {
        var values = new LinkedHashMap<String, Evidence.JsonValue>();
        switch (section) {
            case "main" -> {
                values.put("slot", number(index));
                if (index == selected) values.put("selected", new Evidence.JsonBoolean(true));
            }
            case "armor" -> {
                values.put("slot", number(index));
                if (index < ARMOR.length) values.put("equipmentSlot", new Evidence.JsonString(ARMOR[index].getName()));
            }
            case "offhand" -> values.put("equipmentSlot", new Evidence.JsonString(EquipmentSlot.OFFHAND.getName()));
            default -> {
                return null;
            }
        }
        return new Evidence.JsonObject(values);
    }

    @Override
    public Section inspectItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        var gaps = new ArrayList<String>();
        var values = new LinkedHashMap<String, Evidence.JsonValue>();
        values.put("id", new Evidence.JsonString(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()));
        values.put("name", new Evidence.JsonString(stack.getHoverName().getString()));
        var customName = stack.get(DataComponents.CUSTOM_NAME);
        if (customName != null) values.put("customName", new Evidence.JsonString(customName.getString()));
        values.put("count", number(stack.getCount()));
        values.put("maxStackSize", number(stack.getMaxStackSize()));
        if (stack.isDamageableItem()) {
            values.put("damage", number(stack.getDamageValue()));
            values.put("maxDamage", number(stack.getMaxDamage()));
        }
        putEnchantments(values, "enchantments", stack.get(DataComponents.ENCHANTMENTS));
        putEnchantments(values, "storedEnchantments", stack.get(DataComponents.STORED_ENCHANTMENTS));
        if (stack.has(DataComponents.POTION_CONTENTS)) {
            var potion = NativeItemFacts.potionContents(stack);
            values.put("potion", potion);
            if (potion.values().containsKey("effectsStatus")) gaps.add("potion effects are bounded");
        }
        var attributes = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (attributes != null && !attributes.modifiers().isEmpty()) {
            var modifiers = new ArrayList<Evidence.JsonValue>();
            for (var entry : attributes.modifiers()) {
                modifiers.add(new Evidence.JsonObject(Map.of(
                        "attribute", new Evidence.JsonString(entry.attribute().getRegisteredName()),
                        "id", new Evidence.JsonString(entry.modifier().id().toString()),
                        "amount", new Evidence.JsonNumber(BigDecimal.valueOf(entry.modifier().amount())),
                        "operation", new Evidence.JsonString(entry.modifier().operation().getSerializedName()),
                        "slot", new Evidence.JsonString(entry.slot().getSerializedName()))));
            }
            values.put("attributeModifiers", new Evidence.JsonArray(modifiers));
        }
        var eligibility = eligibility(stack);
        values.put("enchantmentEligibility", eligibility);
        if (!NativeEnchantmentEligibility.complete(eligibility)) {
            gaps.add("enchantment eligibility is " + ((Evidence.JsonString) eligibility.values().get("status")).value());
        }
        var container = stack.get(DataComponents.CONTAINER);
        if (container != null) values.put("containerSlots", number(container.nonEmptyStream().count()));
        var bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) values.put("bundleCount", number(bundle.size()));
        var provider = provider();
        var components = provider == null ? new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unknown")))
                : NativeItemFacts.components(stack, provider);
        values.put("components", components);
        if (components.values().get("status") instanceof Evidence.JsonString status) {
            gaps.add(status.value().equals("bounded") ? "raw components exceed " + NativeItemFacts.MAX_COMPONENT_BYTES + " bytes"
                    : "raw components could not be encoded");
        }
        var fingerprint = provider == null ? null : NativeItemFacts.componentFingerprint(stack, provider);
        if (fingerprint == null) {
            values.put("componentFingerprint", new Evidence.JsonNull());
            values.put("componentFingerprintStatus", new Evidence.JsonString("unknown"));
            gaps.add("the component fingerprint is unknown");
        } else {
            values.put("componentFingerprint", new Evidence.JsonString(fingerprint));
        }
        var facts = new Evidence.JsonObject(values);
        if (gaps.isEmpty()) return new Section(facts, Evidence.Coverage.COMPLETE, null);
        var note = String.join("; ", gaps);
        return new Section(facts, Evidence.Coverage.PARTIAL, Character.toUpperCase(note.charAt(0)) + note.substring(1));
    }

    /** Null when registry access fails; eligibility reports the failure itself. */
    private HolderLookup.Provider provider() {
        try {
            return registries.get();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Evidence.JsonObject eligibility(ItemStack stack) {
        HolderLookup.RegistryLookup<Enchantment> lookup;
        try {
            var provider = registries.get();
            lookup = provider == null ? null : provider.lookup(Registries.ENCHANTMENT).orElse(null);
        } catch (RuntimeException exception) {
            return unavailable(exception.getMessage() != null ? exception.getMessage() : exception.getClass().getName());
        }
        if (lookup == null) return unavailable("enchantment registry is not available");
        var result = NativeEnchantmentEligibility.read(stack, lookup);
        if (!(result.values().get("supported") instanceof Evidence.JsonArray array) || !array.values().isEmpty()) return result;
        var values = new LinkedHashMap<>(result.values());
        values.put("none", new Evidence.JsonString("No enchantment can be applied to this item"));
        return new Evidence.JsonObject(values);
    }

    private static Evidence.JsonObject unavailable(String detail) {
        return new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unavailable"), "detail", new Evidence.JsonString(detail)));
    }

    private static void putEnchantments(Map<String, Evidence.JsonValue> values, String key, ItemEnchantments enchantments) {
        if (enchantments == null || enchantments.isEmpty()) return;
        var levels = new LinkedHashMap<String, Evidence.JsonValue>();
        for (var entry : enchantments.entrySet()) {
            levels.put(entry.getKey().getRegisteredName(), number(entry.getIntValue()));
        }
        values.put(key, new Evidence.JsonObject(levels));
    }

    private static Evidence.JsonNumber number(long value) {
        return new Evidence.JsonNumber(BigDecimal.valueOf(value));
    }
}
