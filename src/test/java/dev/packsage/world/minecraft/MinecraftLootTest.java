package dev.packsage.world.minecraft;

import com.mojang.serialization.Lifecycle;
import dev.packsage.knowledge.Evidence;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.entries.TagEntry;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.providers.number.UniformGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftLootTest {
    private static HolderLookup.Provider registries;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = VanillaRegistries.createLookup();
    }

    /** A loot registry as the game reloads it: no data-pack tables, and the built-in empty table registered. */
    static HolderLookup.Provider reloadedLoot() {
        var loot = new MappedRegistry<LootTable>(Registries.LOOT_TABLE, Lifecycle.stable());
        loot.register(BuiltInLootTables.EMPTY, LootTable.EMPTY, RegistrationInfo.BUILT_IN);
        return HolderLookup.Provider.create(Stream.of(loot.asLookup()));
    }

    private static LootTable zombieLike() {
        return LootTable.lootTable().withPool(LootPool.lootPool()
                .add(LootItem.lootTableItem(Items.ROTTEN_FLESH).apply(SetItemCountFunction.setCount(UniformGenerator.between(0, 2))))
                .add(TagEntry.expandTag(ItemTags.LOGS))).build();
    }

    /** Pools of item entries cycling through the item registry, so entries differ in size like real tables. */
    private static LootTable table(int pools, int entries) {
        var items = BuiltInRegistries.ITEM.stream().toList();
        var builder = LootTable.lootTable();
        var next = 0;
        for (int pool = 0; pool < pools; pool++) {
            var poolBuilder = LootPool.lootPool();
            for (int entry = 0; entry < entries; entry++) poolBuilder.add(LootItem.lootTableItem(items.get(next++ % items.size())));
            builder.withPool(poolBuilder);
        }
        return builder.build();
    }

    private static int bytes(List<Evidence.JsonObject> pools) {
        return Evidence.serialize(new Evidence.JsonArray(List.copyOf(pools))).getBytes(StandardCharsets.UTF_8).length;
    }

    private static int poolCount(LootTable table) throws ReflectiveOperationException {
        var field = LootTable.class.getDeclaredField("pools");
        field.setAccessible(true);
        return ((List<?>) field.get(table)).size();
    }

    @Test
    void blockAndEntityInputsResolveToTheirTables() {
        assertEquals("minecraft:blocks/stone", MinecraftLoot.blockTable("minecraft:stone").location().toString());
        assertEquals("minecraft:entities/zombie", MinecraftLoot.entityTypeTable("minecraft:zombie").location().toString());
        assertEquals("minecraft:entities/zombie", MinecraftLoot.table("minecraft:entities/zombie").location().toString());
        assertThrows(IllegalArgumentException.class, () -> MinecraftLoot.blockTable("minecraft:nope"));
        assertThrows(IllegalArgumentException.class, () -> MinecraftLoot.entityTypeTable("minecraft:nope"));
        assertThrows(IllegalArgumentException.class, () -> MinecraftLoot.table("Not An Id"));
    }

    @Test
    void encodingKeepsPoolsEntriesFunctionsAndReadableIds() {
        var encoded = MinecraftLoot.encode(zombieLike(), registries);
        assertEquals(List.of("minecraft:rotten_flesh"), encoded.itemIds());
        assertEquals(List.of("#minecraft:logs"), encoded.tagIds());
        assertEquals(1, encoded.pools().size());
        var pool = Evidence.serialize(encoded.pools().getFirst());
        assertTrue(pool.contains("\"minecraft:set_count\""), pool);
        assertTrue(pool.contains("\"minecraft:rotten_flesh\""), pool);
        assertTrue(!encoded.header().values().containsKey("pools"), encoded.header().toString());
        assertTrue(!encoded.idsBounded());
    }

    @Test
    void encodingTwiceIsIdenticalAndLeavesTheTableUnchanged() throws ReflectiveOperationException {
        var table = zombieLike();
        var before = poolCount(table);
        assertEquals(MinecraftLoot.encode(table, registries), MinecraftLoot.encode(table, registries));
        assertEquals(before, poolCount(table));
    }

    @Test
    void largeTablesPageByWholePoolsWithinTheByteBound() {
        var encoded = MinecraftLoot.encode(table(300, 10), registries);
        assertEquals(300, encoded.pools().size());
        var seen = new ArrayList<Evidence.JsonObject>();
        var from = 0;
        var pages = 0;
        MinecraftLoot.LootPage page;
        do {
            page = MinecraftLoot.page(encoded, from);
            pages++;
            assertTrue(!page.pools().isEmpty());
            assertTrue(!page.poolTrimmed());
            assertTrue(bytes(page.pools()) <= MinecraftLoot.PAGE_BYTES, "page " + pages + " has " + bytes(page.pools()) + " bytes");
            seen.addAll(page.pools());
            from = page.nextPool();
        } while (from != -1);
        assertTrue(pages > 1, "pages " + pages);
        assertEquals(encoded.pools(), seen);
        assertEquals(-1, page.nextPool());
    }

    @Test
    void anOversizedPoolIsTrimmedAndMarked() {
        var encoded = MinecraftLoot.encode(table(1, 2000), registries);
        var page = MinecraftLoot.page(encoded, 0);
        assertTrue(page.poolTrimmed());
        assertEquals(-1, page.nextPool());
        assertEquals(1, page.pools().size());
        var pool = page.pools().getFirst().values();
        var shown = ((Evidence.JsonNumber) pool.get("entriesShown")).value().intValueExact();
        assertEquals(new BigDecimal(2000), ((Evidence.JsonNumber) pool.get("entryCount")).value());
        assertEquals(new Evidence.JsonBoolean(true), pool.get("poolTrimmed"));
        assertTrue(shown > 0 && shown < 2000, "shown " + shown);
        assertEquals(shown, ((Evidence.JsonArray) pool.get("entries")).values().size());
        assertTrue(bytes(page.pools()) <= MinecraftLoot.PAGE_BYTES, "page has " + bytes(page.pools()) + " bytes");
    }

    @Test
    void unknownEmptyAndNoDropTablesAreDistinct() {
        var empty = HolderLookup.Provider.create(Stream.of(new MappedRegistry<>(Registries.LOOT_TABLE, Lifecycle.stable()).asLookup()));
        var unknown = ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.parse("packsage:nothing_here"));
        assertTrue(MinecraftLoot.lookup(empty, unknown).isEmpty());
        assertTrue(MinecraftLoot.lookup(reloadedLoot(), unknown).isEmpty());
        assertTrue(MinecraftLoot.encode(LootTable.EMPTY, registries).pools().isEmpty());
        var air = MinecraftLoot.blockTable("minecraft:air");
        assertEquals("minecraft:empty", air.location().toString());
        var airTable = MinecraftLoot.lookup(reloadedLoot(), air);
        assertTrue(airTable.isPresent());
        assertTrue(MinecraftLoot.encode(airTable.get(), registries).pools().isEmpty());
        var page = MinecraftLoot.page(MinecraftLoot.encode(LootTable.EMPTY, registries), 0);
        assertTrue(page.pools().isEmpty());
        assertEquals(-1, page.nextPool());
    }
}
