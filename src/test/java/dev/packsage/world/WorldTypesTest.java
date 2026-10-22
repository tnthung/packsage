package dev.packsage.world;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldTypesTest {
    @Test
    void blockPlaceAndInventoryIdRoundTripTheirTextForms() {
        var place = new BlockPlace("minecraft:overworld", 10, 64, -3);
        assertEquals("minecraft:overworld|10,64,-3", place.text());
        assertEquals(place, BlockPlace.parse(place.text()));
        assertThrows(IllegalArgumentException.class, () -> BlockPlace.parse("minecraft:overworld|1,2"));
        assertThrows(IllegalArgumentException.class, () -> BlockPlace.parse("nonsense"));
        var id = new InventoryId("minecraft", "fuel");
        assertEquals("minecraft/fuel", id.text());
        assertEquals(id, InventoryId.parse("minecraft/fuel"));
        assertThrows(IllegalArgumentException.class, () -> InventoryId.parse("minecraft"));
    }

    @Test
    void itemPathRoundTripsAndRejectsASeventhStep() {
        var entity = UUID.randomUUID();
        var path = new ItemPath(new PathRoot.Entity(entity), List.of())
                .child(new PathStep(new InventoryId("minecraft", "chest"), "5", "sophisticatedbackpacks:backpack"))
                .child(new PathStep(new InventoryId("minecraft", "container"), "3", "minecraft:bundle"));
        assertEquals("entity:" + entity + ">minecraft/chest#5@sophisticatedbackpacks:backpack>minecraft/container#3@minecraft:bundle", path.text());
        assertEquals(path, ItemPath.parse(path.text()));
        var block = new ItemPath(new PathRoot.Block(new BlockPlace("minecraft:the_nether", 1, 2, 3)), List.of());
        assertEquals(block, ItemPath.parse(block.text()));
        assertEquals(new ItemPath(new PathRoot.Player(entity), List.of()), ItemPath.parse("player:" + entity));
        assertThrows(IllegalArgumentException.class, () -> ItemPath.parse("chest:" + entity));
        assertThrows(IllegalArgumentException.class, () -> ItemPath.parse("player:" + entity + ">minecraft/chest"));
        var full = new ItemPath(new PathRoot.Player(entity), List.of());
        for (var i = 0; i < ItemPath.MAX_STEPS; i++) {
            full = full.child(new PathStep(new InventoryId("minecraft", "container"), String.valueOf(i), "minecraft:bundle"));
        }
        var last = full;
        assertEquals(ItemPath.MAX_STEPS, last.steps().size());
        assertThrows(IllegalArgumentException.class, () -> last.child(new PathStep(new InventoryId("minecraft", "container"), "9", "minecraft:bundle")));
    }

    @Test
    void kindFilterCombinesFieldsWithAndAlternativesWithOr() {
        var filter = new KindFilter(List.of("minecraft:iron_ingot", "minecraft:gold_ingot"), List.of("c:ingots"), "");
        assertTrue(filter.matches("minecraft:gold_ingot", List.of("c:ingots"), "Gold Ingot"));
        assertFalse(filter.matches("minecraft:gold_ingot", List.of("c:gems"), "Gold Ingot"));
        assertFalse(filter.matches("minecraft:copper_ingot", List.of("c:ingots"), "Copper Ingot"));
        var text = new KindFilter(List.of(), List.of(), "GOLD");
        assertTrue(text.matches("minecraft:gold_ingot", List.of(), null));
        assertTrue(text.matches("minecraft:apple", List.of(), "Golden Apple"));
        assertFalse(text.matches("minecraft:apple", List.of(), "Apple"));
        assertTrue(KindFilter.ANY.matches("minecraft:apple", List.of(), "Apple"));
    }
}
