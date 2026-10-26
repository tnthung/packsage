package dev.packsage.client.recipe;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlotStacksTest {
    private static RegistryAccess registries;

    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.bootStrap();
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    @Test
    void cycleIndexAdvancesOncePerInterval() {
        assertEquals(0, SlotStacks.cycleIndex(999, 3));
        assertEquals(1, SlotStacks.cycleIndex(1000, 3));
        assertEquals(0, SlotStacks.cycleIndex(3000, 3));
    }

    @Test
    void cycleIndexHandlesSingleStack() {
        assertEquals(0, SlotStacks.cycleIndex(123456, 1));
    }

    @Test
    void missingItemResolvesToBarrierMarker() {
        var result = SlotStacks.resolve(new RecipeCardModel.Stack(RecipeCardModel.Stack.Kind.ITEM,
                "missingmod:lost_item", "{}", 1, ""), registries);
        assertEquals(1, result.size());
        assertTrue(result.getFirst().is(Items.BARRIER));
        assertEquals("Missing: missingmod:lost_item", result.getFirst().get(DataComponents.CUSTOM_NAME).getString());
    }

    @Test
    void itemComponentsAndCountAreRestored() {
        var result = SlotStacks.resolve(new RecipeCardModel.Stack(RecipeCardModel.Stack.Kind.ITEM,
                "minecraft:stone", "{\"minecraft:custom_name\":\"\\\"Example\\\"\"}", 3, ""), registries);
        assertEquals(1, result.size());
        assertTrue(result.getFirst().is(Items.STONE));
        assertEquals(3, result.getFirst().getCount());
        assertEquals("Example", result.getFirst().getHoverName().getString());
    }

    @Test
    void customIngredientDoesNotInventAnItem() {
        assertTrue(SlotStacks.resolve(new RecipeCardModel.Stack(RecipeCardModel.Stack.Kind.CUSTOM,
                "custom", "{}", 1, "custom"), registries).isEmpty());
    }
}
