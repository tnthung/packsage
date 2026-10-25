package dev.packsage.world;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemTooltipsTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void tooltipLinesAreBoundedAndComputedOnACopy() {
        var original = new ItemStack(Items.DIAMOND_SWORD);
        var before = original.getComponents().toString();
        var received = new ArrayList<ItemStack>();
        var tooltips = new ItemTooltips(stack -> {
            received.add(stack);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("changed"));
            return IntStream.range(0, 40).mapToObj(index -> (Component) Component.literal(index == 0 ? "x".repeat(300) : "line " + index)).toList();
        });

        var tooltip = tooltips.lines(original);

        assertEquals(1, received.size());
        assertNotSame(original, received.getFirst());
        assertEquals(before, original.getComponents().toString());
        assertNull(original.get(DataComponents.CUSTOM_NAME));
        assertEquals(ItemTooltips.MAX_LINES, tooltip.lines().size());
        assertEquals(ItemTooltips.MAX_LINE_LENGTH, tooltip.lines().getFirst().length());
        assertEquals("line 1", tooltip.lines().get(1));
        assertEquals("bounded", tooltip.status());
    }

    @Test
    void shortTooltipsAreComplete() {
        var tooltip = new ItemTooltips(stack -> List.of(Component.literal("Stone"), Component.literal("A rock")))
                .lines(new ItemStack(Items.STONE));
        assertEquals(List.of("Stone", "A rock"), tooltip.lines());
        assertEquals("complete", tooltip.status());
    }

    @Test
    void aThrowingTooltipMarksStatusUnknownInsteadOfFailing() {
        var thrown = new ItemTooltips(stack -> { throw new IllegalStateException("broken mod tooltip"); }).lines(new ItemStack(Items.STONE));
        assertTrue(thrown.lines().isEmpty());
        assertEquals("unknown", thrown.status());

        var errored = new ItemTooltips(stack -> { throw new NoClassDefFoundError("client only"); }).lines(new ItemStack(Items.STONE));
        assertTrue(errored.lines().isEmpty());
        assertEquals("unknown", errored.status());
    }
}
