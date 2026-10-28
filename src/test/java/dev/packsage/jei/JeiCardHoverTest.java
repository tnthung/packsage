package dev.packsage.jei;

import dev.packsage.client.recipe.RecipeCard;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.builder.IIngredientConsumer;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.gui.inputs.IJeiInputHandler;
import mezz.jei.api.gui.inputs.RecipeSlotUnderMouse;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JeiCardHoverTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.bootStrap();
    }

    /** One 18-pixel slot at the origin; JEI's API loads outside the game layer in unit tests, so these are compiled fakes. */
    @SuppressWarnings("removal")
    private static final class Slot implements IRecipeSlotDrawable {
        final List<Component> tooltip;
        Slot(List<Component> tooltip) { this.tooltip = tooltip; }
        @Override public List<Component> getTooltip() { return tooltip; }
        @Override public void getTooltip(ITooltipBuilder builder) { throw new UnsupportedOperationException(); }
        @Override public void draw(GuiGraphics graphics) { }
        @Override public void drawHoverOverlays(GuiGraphics graphics) { }
        @Override public void drawTooltip(GuiGraphics graphics, int mouseX, int mouseY) { }
        @Override public boolean isMouseOver(double mouseX, double mouseY) { return mouseX >= 0 && mouseX < 18 && mouseY >= 0 && mouseY < 18; }
        @Override public void setPosition(int x, int y) { }
        @Override public IIngredientConsumer createDisplayOverrides() { throw new UnsupportedOperationException(); }
        @Override public void clearDisplayOverrides() { }
        @Override public Rect2i getRect() { return new Rect2i(0, 0, 18, 18); }
        @Override public Rect2i getAreaIncludingBackground() { return getRect(); }
        @Override public Stream<ITypedIngredient<?>> getAllIngredients() { return Stream.empty(); }
        @Override public List<ITypedIngredient<?>> getAllIngredientsList() { return List.of(); }
        @Override public Optional<ITypedIngredient<?>> getDisplayedIngredient() { return Optional.empty(); }
        @Override public Stream<ITypedIngredient<?>> getDisplayedIngredients() { return Stream.empty(); }
        @Override public Optional<TagKey<?>> getTagKey() { return Optional.empty(); }
        @Override public RecipeIngredientRole getRole() { return RecipeIngredientRole.OUTPUT; }
        @Override public void drawHighlight(GuiGraphics graphics, int color) { }
        @Override public Optional<String> getSlotName() { return Optional.empty(); }
    }

    private static final class Drawable implements IRecipeLayoutDrawable<Object> {
        final Slot slot;
        Drawable(Slot slot) { this.slot = slot; }
        @Override public Optional<ItemStack> getItemStackUnderMouse(int mouseX, int mouseY) {
            return slot.isMouseOver(mouseX, mouseY) ? Optional.of(new ItemStack(Items.IRON_INGOT)) : Optional.empty();
        }
        @Override public Optional<IRecipeSlotDrawable> getRecipeSlotUnderMouse(double mouseX, double mouseY) {
            return slot.isMouseOver(mouseX, mouseY) ? Optional.of(slot) : Optional.empty();
        }
        @Override public void setPosition(int x, int y) { }
        @Override public void drawRecipe(GuiGraphics graphics, int mouseX, int mouseY) { }
        @Override public void drawOverlays(GuiGraphics graphics, int mouseX, int mouseY) { }
        @Override public boolean isMouseOver(double mouseX, double mouseY) { return true; }
        @Override public <T> Optional<T> getIngredientUnderMouse(int mouseX, int mouseY, IIngredientType<T> type) { return Optional.empty(); }
        @Override public Optional<RecipeSlotUnderMouse> getSlotUnderMouse(double mouseX, double mouseY) { return Optional.empty(); }
        @Override public Rect2i getRect() { return new Rect2i(0, 0, 100, 50); }
        @Override public Rect2i getRectWithBorder() { return getRect(); }
        @Override public Rect2i getSideButtonArea(int index) { return getRect(); }
        @Override public IRecipeSlotsView getRecipeSlotsView() { throw new UnsupportedOperationException(); }
        @Override public IRecipeCategory<Object> getRecipeCategory() { throw new UnsupportedOperationException(); }
        @Override public Object getRecipe() { throw new UnsupportedOperationException(); }
        @Override public IJeiInputHandler getInputHandler() { throw new UnsupportedOperationException(); }
        @Override public void tick() { }
    }

    private static JeiRecipeCardSource.JeiCard card(List<Component> tooltip) {
        return new JeiRecipeCardSource.JeiCard(new JeiRecipeCardSource.Layout(new Drawable(new Slot(tooltip)), 0, 0, 100, 50),
                "Grinder", 1.0, JeiRuntimeHolder.generation());
    }

    @Test
    void expandedCatalystItemsHitTestAtBothScales() {
        var layout = new JeiRecipeCardSource.Layout(new Drawable(new Slot(List.of())), 18, 0, 118, 54);
        layout.strip = JeiRecipeCardSource.strip("mod:altar", 25, 54);
        layout.expandedStrip = JeiRecipeCardSource.strip("mod:altar", 25, 54, true);
        layout.machines = java.util.stream.IntStream.range(0, 25).mapToObj(i -> new dev.packsage.client.recipe.RecipeCardSource.Machine(
                new ItemStack(Items.FURNACE), List.of(Component.literal("Machine " + i)))).toList();
        for (double scale : List.of(1.0, 0.75)) {
            var card = new JeiRecipeCardSource.JeiCard(layout, "Altar", scale, JeiRuntimeHolder.generation());
            card.updatePanel(10, 20, 10 + scale, 20 + scale, 400);
            assertEquals(Items.FURNACE, card.itemAt(10, 20, 10 + scale, 20 + scale).getItem());
            // The collapsed +N spot is a real catalyst in the expanded panel, including over the recipe area.
            assertEquals(Items.FURNACE, card.itemAt(10, 20, 10 + 55 * scale, 20 + 37 * scale).getItem());
            assertEquals(Items.FURNACE, card.itemAt(10, 20, 10 + 145 * scale, 20 + scale).getItem());
        }
        JeiRuntimeHolder.unavailable();
        var stale = new JeiRecipeCardSource.JeiCard(layout, "Altar", 1, JeiRuntimeHolder.generation() - 1);
        assertTrue(stale.itemAt(10, 20, 11, 57).isEmpty());
    }

    @Test
    void catalystPanelStaysOpenUntilThePointerLeavesIt() {
        var layout = new JeiRecipeCardSource.Layout(null, 18, 0, 118, 54);
        layout.strip = JeiRecipeCardSource.strip("mod:altar", 25, 54);
        layout.expandedStrip = JeiRecipeCardSource.strip("mod:altar", 25, 54, true);
        var card = new JeiRecipeCardSource.JeiCard(layout, "Altar", 0.75, JeiRuntimeHolder.generation());
        card.updatePanel(100, 20, 101, 21, 400);
        assertTrue(card.overlayContains(101, 21));
        // The same prepared card can appear at another citation; that draw must not move an open panel.
        card.updatePanel(300, 20, 101, 21, 400);
        assertTrue(card.overlayContains(101, 21));
        card.updatePanel(100, 20, 101, 21, 400);
        assertTrue(card.overlayContains(101, 21));
        card.updatePanel(100, 20, 300, 200, 400);
        assertFalse(card.overlayContains(101, 21));
    }

    @Test
    void slotHoverKeepsJeiLinesSuchAsOutputChance() {
        var lines = List.<Component>of(Component.literal("Iron Ingot"), Component.literal("25% chance"));
        var card = card(lines);

        assertEquals(Optional.of(new RecipeCard.Hover.Text(lines)), card.hoverAt(10, 10, 15, 15));
        assertEquals(Items.IRON_INGOT, card.itemAt(10, 10, 15, 15).getItem());
        assertTrue(card.hoverAt(10, 10, 60, 40).isEmpty());
    }

    @Test
    void slotWithoutJeiLinesFallsBackToTheItemTooltip() {
        var hover = card(List.of()).hoverAt(10, 10, 15, 15).orElseThrow();
        assertEquals(Items.IRON_INGOT, ((RecipeCard.Hover.Item) hover).stack().getItem());
    }
}
