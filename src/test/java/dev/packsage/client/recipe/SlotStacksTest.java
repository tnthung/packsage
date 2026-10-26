package dev.packsage.client.recipe;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
    void missingItemAndUnknownTagAreReportedMissing() {
        var item = SlotStacks.resolve(new RecipeCardModel.Stack(RecipeCardModel.Stack.Kind.ITEM,
                "missingmod:lost_item", "{}", 1, ""), registries);
        var tag = SlotStacks.resolve(new RecipeCardModel.Stack(RecipeCardModel.Stack.Kind.TAG,
                "#missingmod:lost_tag", "{}", 1, ""), registries);
        assertTrue(item.missing());
        assertTrue(item.stacks().isEmpty());
        assertTrue(tag.missing());
        assertTrue(tag.stacks().isEmpty());
    }

    @Test
    void invalidRegistryAndComponentsAreReportedMissing() {
        var stack = new RecipeCardModel.Stack(RecipeCardModel.Stack.Kind.ITEM, "minecraft:stone", "invalid", 1, "");
        assertTrue(SlotStacks.resolve(stack, registries).missing());
        assertTrue(SlotStacks.resolve(stack, null).missing());
    }

    @Test
    void hoverReturnsItemsOrTextForEachSlot() {
        var model = new RecipeCardModel(RecipeCardModel.Category.CRAFTING_SHAPED, 100, 40, List.of(
                new RecipeCardModel.Slot("item", 0, 0, false, List.of(new RecipeCardModel.Stack(
                        RecipeCardModel.Stack.Kind.ITEM, "minecraft:stone", "{}", 1, ""))),
                new RecipeCardModel.Slot("missing", 20, 0, false, List.of(new RecipeCardModel.Stack(
                        RecipeCardModel.Stack.Kind.ITEM, "missingmod:lost_item", "{}", 1, ""))),
                new RecipeCardModel.Slot("tag", 40, 0, false, List.of(new RecipeCardModel.Stack(
                        RecipeCardModel.Stack.Kind.TAG, "#missingmod:lost_tag", "{}", 1, ""))),
                new RecipeCardModel.Slot("custom", 60, 0, false, List.of(new RecipeCardModel.Stack(
                        RecipeCardModel.Stack.Kind.CUSTOM, "custom", "{}", 1, "Any special ingredient")))
        ), "Crafting table", List.of());
        var card = new NativeRecipeCardSource.NativeCard(model, registries);
        var item = assertInstanceOf(RecipeCard.Hover.Item.class, card.hoverAt(10, 20, 14, 24).orElseThrow());
        assertTrue(item.stack().is(Items.STONE));
        var missing = assertInstanceOf(RecipeCard.Hover.Text.class, card.hoverAt(10, 20, 34, 24).orElseThrow());
        assertEquals("Missing: missingmod:lost_item", missing.lines().getFirst().getString());
        var tag = assertInstanceOf(RecipeCard.Hover.Text.class, card.hoverAt(10, 20, 54, 24).orElseThrow());
        assertEquals("Missing: #missingmod:lost_tag", tag.lines().getFirst().getString());
        var custom = assertInstanceOf(RecipeCard.Hover.Text.class, card.hoverAt(10, 20, 74, 24).orElseThrow());
        assertEquals("Any special ingredient", custom.lines().getFirst().getString());
        assertTrue(card.hoverAt(10, 20, 13, 24).isEmpty());
        assertTrue(card.hoverAt(10, 20, 30, 24).isEmpty());
    }

    @Test
    void itemComponentsAndCountAreRestored() {
        var result = SlotStacks.resolve(new RecipeCardModel.Stack(RecipeCardModel.Stack.Kind.ITEM,
                "minecraft:stone", "{\"minecraft:custom_name\":\"\\\"Example\\\"\"}", 3, ""), registries);
        assertFalse(result.missing());
        assertEquals(1, result.stacks().size());
        assertTrue(result.stacks().getFirst().is(Items.STONE));
        assertEquals(3, result.stacks().getFirst().getCount());
        assertEquals("Example", result.stacks().getFirst().getHoverName().getString());
    }

    @Test
    void itemCountsAreClampedToStackLimits() {
        var zero = SlotStacks.resolve(new RecipeCardModel.Stack(RecipeCardModel.Stack.Kind.ITEM,
                "minecraft:stone", "{}", 0, ""), registries).stacks().getFirst();
        var negative = SlotStacks.resolve(new RecipeCardModel.Stack(RecipeCardModel.Stack.Kind.ITEM,
                "minecraft:stone", "{}", -3, ""), registries).stacks().getFirst();
        var oversized = SlotStacks.resolve(new RecipeCardModel.Stack(RecipeCardModel.Stack.Kind.ITEM,
                "minecraft:ender_pearl", "{}", 100, ""), registries).stacks().getFirst();
        assertEquals(1, zero.getCount());
        assertEquals(1, negative.getCount());
        assertEquals(16, oversized.getCount());
    }

    @Test
    void customIngredientDoesNotInventAnItem() {
        assertTrue(SlotStacks.resolve(new RecipeCardModel.Stack(RecipeCardModel.Stack.Kind.CUSTOM,
                "custom", "{}", 1, "custom"), registries).stacks().isEmpty());
    }
}
