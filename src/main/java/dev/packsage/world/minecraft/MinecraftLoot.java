package dev.packsage.world.minecraft;

import com.mojang.serialization.JsonOps;
import dev.packsage.knowledge.Evidence;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Loot tables as the game defines them: which table a block, entity or container uses, the table encoded with its own
 * codec, and pages of whole pools. Nothing here rolls a table or unpacks a container's pending loot.
 */
public final class MinecraftLoot {
    public static final int PAGE_BYTES = 24_576;
    public static final int MAX_IDS = 256;

    /** A container's loot that is generated when it is first opened, with its seed (0 means a random seed). */
    public record Pending(ResourceKey<LootTable> table, long seed) {
    }

    /** The table without its pools, its pools, and the item and tag ids its entries name. */
    public record Encoded(Evidence.JsonObject header, List<Evidence.JsonObject> pools, List<String> itemIds, List<String> tagIds,
                          boolean idsBounded) {
        public Encoded {
            pools = List.copyOf(pools);
            itemIds = List.copyOf(itemIds);
            tagIds = List.copyOf(tagIds);
        }
    }

    /** NextPool is -1 when no pools remain; poolTrimmed when the page's only pool had its entries cut. */
    public record LootPage(List<Evidence.JsonObject> pools, int nextPool, boolean poolTrimmed) {
        public LootPage {
            pools = List.copyOf(pools);
        }
    }

    private MinecraftLoot() {
    }

    public static ResourceKey<LootTable> table(String id) {
        return ResourceKey.create(Registries.LOOT_TABLE, location(id, "loot table"));
    }

    public static ResourceKey<LootTable> blockTable(String blockId) {
        var block = BuiltInRegistries.BLOCK.getOptional(location(blockId, "block"));
        if (block.isEmpty()) throw new IllegalArgumentException("unknown block " + blockId);
        return block.get().getLootTable();
    }

    public static ResourceKey<LootTable> entityTypeTable(String typeId) {
        var type = BuiltInRegistries.ENTITY_TYPE.getOptional(location(typeId, "entity type"));
        if (type.isEmpty()) throw new IllegalArgumentException("unknown entity type " + typeId);
        return type.get().getDefaultLootTable();
    }

    /** The entity's own death table, such as a sheep's colour table; null for an entity that is not living. */
    public static ResourceKey<LootTable> entityTable(Entity entity) {
        if (entity instanceof Mob mob) return mob.getLootTable();
        if (entity instanceof LivingEntity living) return living.getLootTable();
        return null;
    }

    /**
     * The container's pending loot, or null when it has none. Reads only the table and seed: the container's slot
     * accessors generate the loot.
     */
    public static Pending pending(BlockEntity entity) {
        if (!(entity instanceof RandomizableContainer container) || container.getLootTable() == null) return null;
        return new Pending(container.getLootTable(), container.getLootTableSeed());
    }

    /**
     * The table registered under the key in the loaded data packs; empty when none is. The game registers
     * {@code minecraft:empty}, the table that drops nothing, on every reload; it is also answered for a registry
     * without it, so it never reads as unknown.
     */
    public static Optional<LootTable> lookup(HolderLookup.Provider registries, ResourceKey<LootTable> key) {
        var table = registries.lookup(Registries.LOOT_TABLE).flatMap(lookup -> lookup.get(key)).map(Holder::value);
        if (table.isEmpty() && key.equals(BuiltInLootTables.EMPTY)) return Optional.of(LootTable.EMPTY);
        return table;
    }

    /** Encodes with the table's own codec, whose encoder has no side effects. */
    public static Encoded encode(LootTable table, HolderLookup.Provider registries) {
        var json = LootTable.DIRECT_CODEC.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), table).getOrThrow();
        var encoded = Evidence.deserializeObject(json.toString());
        var header = new LinkedHashMap<>(encoded.values());
        var pools = new ArrayList<Evidence.JsonObject>();
        if (header.remove("pools") instanceof Evidence.JsonArray array) {
            for (var pool : array.values()) {
                if (pool instanceof Evidence.JsonObject object) pools.add(object);
            }
        }
        var items = new TreeSet<String>();
        var tags = new TreeSet<String>();
        collectIds(encoded, items, tags);
        var bounded = items.size() > MAX_IDS || tags.size() > MAX_IDS;
        return new Encoded(new Evidence.JsonObject(header), pools, items.stream().limit(MAX_IDS).toList(),
                tags.stream().limit(MAX_IDS).toList(), bounded);
    }

    private static void collectIds(Evidence.JsonValue value, TreeSet<String> items, TreeSet<String> tags) {
        if (value instanceof Evidence.JsonArray array) {
            array.values().forEach(nested -> collectIds(nested, items, tags));
            return;
        }
        if (!(value instanceof Evidence.JsonObject object)) return;
        if (object.values().get("type") instanceof Evidence.JsonString type && object.values().get("name") instanceof Evidence.JsonString name) {
            if (type.value().equals("minecraft:item")) items.add(name.value());
            if (type.value().equals("minecraft:tag")) tags.add("#" + name.value());
        }
        object.values().values().forEach(nested -> collectIds(nested, items, tags));
    }

    /**
     * Whole pools from fromPool on, while their array stays within {@link #PAGE_BYTES} UTF-8 bytes, and always at least
     * one. A first pool larger than a page keeps the leading entries that fit.
     */
    public static LootPage page(Encoded encoded, int fromPool) {
        var pools = encoded.pools();
        if (fromPool >= pools.size()) return new LootPage(List.of(), -1, false);
        var shown = new ArrayList<Evidence.JsonObject>();
        var used = 2;
        var index = fromPool;
        for (; index < pools.size(); index++) {
            var size = bytes(pools.get(index)) + (shown.isEmpty() ? 0 : 1);
            if (used + size > PAGE_BYTES) break;
            shown.add(pools.get(index));
            used += size;
        }
        var trimmed = false;
        if (shown.isEmpty()) {
            shown.add(trim(pools.get(index)));
            trimmed = true;
            index++;
        }
        return new LootPage(shown, index < pools.size() ? index : -1, trimmed);
    }

    /** The pool with the leading entries whose page stays within the bound, and how many it shows of how many. */
    private static Evidence.JsonObject trim(Evidence.JsonObject pool) {
        var entries = pool.values().get("entries") instanceof Evidence.JsonArray array ? array.values() : List.<Evidence.JsonValue>of();
        // Measured with the largest count in both numbers, so the real page is never larger.
        var used = 2 + bytes(trimmed(pool, List.of(), entries.size(), entries.size()));
        var count = 0;
        for (var entry : entries) {
            var size = bytes(entry) + (count == 0 ? 0 : 1);
            if (used + size > PAGE_BYTES) break;
            used += size;
            count++;
        }
        return trimmed(pool, entries.subList(0, count), count, entries.size());
    }

    private static Evidence.JsonObject trimmed(Evidence.JsonObject pool, List<Evidence.JsonValue> entries, int shown, int count) {
        var values = new LinkedHashMap<>(pool.values());
        values.put("entries", new Evidence.JsonArray(entries));
        values.put("entriesShown", new Evidence.JsonNumber(BigDecimal.valueOf(shown)));
        values.put("entryCount", new Evidence.JsonNumber(BigDecimal.valueOf(count)));
        values.put("poolTrimmed", new Evidence.JsonBoolean(true));
        return new Evidence.JsonObject(values);
    }

    private static int bytes(Evidence.JsonValue value) {
        return Evidence.serialize(value).getBytes(StandardCharsets.UTF_8).length;
    }

    private static ResourceLocation location(String id, String what) {
        var location = ResourceLocation.tryParse(id);
        if (location == null) throw new IllegalArgumentException("invalid " + what + " id: " + id);
        return location;
    }
}
