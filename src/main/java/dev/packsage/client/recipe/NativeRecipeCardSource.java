package dev.packsage.client.recipe;

import dev.packsage.client.ModernUi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

public final class NativeRecipeCardSource implements RecipeCardSource {
    private static final ResourceLocation PANEL = ResourceLocation.fromNamespaceAndPath("packsage", "jei/single_recipe_background");
    private static final ResourceLocation BREWING_BACKGROUND = ResourceLocation.fromNamespaceAndPath("packsage", "jei/brewing_stand_background");
    private static final ResourceLocation OUTPUT_SLOT = ResourceLocation.fromNamespaceAndPath("packsage", "jei/output_slot");
    private static final ResourceLocation SLOT = ResourceLocation.fromNamespaceAndPath("packsage", "jei/slot");
    private static final ResourceLocation BREWING_HEAT = ResourceLocation.fromNamespaceAndPath("packsage", "jei/brewing_stand_blaze_heat");
    private static final ResourceLocation BREWING_BUBBLES = ResourceLocation.fromNamespaceAndPath("packsage", "jei/brewing_stand_bubbles");
    private static final ResourceLocation BREWING_ARROW = ResourceLocation.fromNamespaceAndPath("packsage", "jei/brewing_stand_arrow");
    private static final ResourceLocation RECIPE_ARROW = ResourceLocation.fromNamespaceAndPath("packsage", "jei/recipe_arrow");
    private static final ResourceLocation RECIPE_ARROW_FILLED = ResourceLocation.fromNamespaceAndPath("packsage", "jei/recipe_arrow_filled");
    private static final ResourceLocation FLAME_EMPTY = ResourceLocation.fromNamespaceAndPath("packsage", "jei/icons/flame_empty");
    private static final ResourceLocation FLAME = ResourceLocation.fromNamespaceAndPath("packsage", "jei/icons/flame");
    private static final ResourceLocation SHAPELESS_ICON = ResourceLocation.fromNamespaceAndPath("packsage", "jei/icons/shapeless_icon");

    @Override
    public Optional<RecipeCard> card(RecipeCardModel model) {
        var level = Minecraft.getInstance().level;
        if (level == null) return Optional.empty();
        return Optional.of(new NativeCard(model, level.registryAccess()));
    }

    private record DisplayedStack(ItemStack icon, RecipeCard.Hover hover) { }

    static final class NativeCard implements RecipeCard {
        private final RecipeCardModel model;
        private final Component caption;
        private final List<List<DisplayedStack>> slots;
        private final long animationStart = System.currentTimeMillis();
        private long lastTime = animationStart;
        private long cycleTime = ThreadLocalRandom.current().nextInt(100000) * (long) SlotStacks.CYCLE_MS;

        NativeCard(RecipeCardModel model, HolderLookup.Provider registries) {
            this.model = model;
            this.caption = Component.literal(model.caption()).withStyle(style -> style.withFont(ModernUi.FONT));
            var slots = new ArrayList<List<DisplayedStack>>();
            for (var slot : model.slots()) {
                var stacks = new ArrayList<DisplayedStack>();
                for (var alternative : slot.stacks()) {
                    var resolved = SlotStacks.resolve(alternative, registries);
                    if (resolved.missing()) {
                        var identity = alternative.identity();
                        if (alternative.kind() == RecipeCardModel.Stack.Kind.TAG && !identity.startsWith("#")) identity = "#" + identity;
                        stacks.add(new DisplayedStack(new ItemStack(Items.BARRIER), new Hover.Text(List.of(Component.literal("Missing: " + identity)))));
                    } else if (resolved.stacks().isEmpty()) {
                        stacks.add(new DisplayedStack(null, new Hover.Text(List.of(Component.literal(alternative.description())))));
                    } else {
                        for (var stack : resolved.stacks()) stacks.add(new DisplayedStack(stack, new Hover.Item(stack)));
                    }
                }
                slots.add(List.copyOf(stacks));
            }
            this.slots = List.copyOf(slots);
        }

        @Override public int width() { return model.width() + 8; }
        @Override public int height() { return model.height() + 8 + 4 + Minecraft.getInstance().font.lineHeight; }

        @Override
        public void draw(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
            long now = System.currentTimeMillis();
            if (!Screen.hasShiftDown()) cycleTime += Math.max(0, now - lastTime);
            lastTime = now;
            long animation = Math.max(0, now - animationStart);
            var font = Minecraft.getInstance().font;
            int[] widths = { 16, width() - 32, 16 };
            int[] heights = { 16, model.height() + 8 - 32, 16 };
            int[] source = { 0, 16, 48 };
            int px = x;
            for (int col = 0; col < 3; col++) {
                int py = y;
                for (int row = 0; row < 3; row++) {
                    int tileWidth = col == 1 ? 32 : 16;
                    int tileHeight = row == 1 ? 32 : 16;
                    for (int dx = 0; dx < widths[col]; dx += tileWidth) {
                        for (int bottom = heights[row]; bottom > 0; bottom -= tileHeight) {
                            int w = Math.min(tileWidth, widths[col] - dx);
                            int h = Math.min(tileHeight, bottom);
                            graphics.blitSprite(PANEL, 64, 64, source[col], source[row] + tileHeight - h,
                                    px + dx, py + bottom - h, w, h);
                        }
                    }
                    py += heights[row];
                }
                px += widths[col];
            }
            int rx = x + 4, ry = y + 4;
            var category = model.category();
            if (category == RecipeCardModel.Category.BREWING) {
                graphics.blitSprite(BREWING_BACKGROUND, rx + RecipeCardLayouts.BREWING_BACKGROUND_X, ry + RecipeCardLayouts.BREWING_BACKGROUND_Y, RecipeCardLayouts.BREWING_BACKGROUND_WIDTH, RecipeCardLayouts.BREWING_BACKGROUND_HEIGHT);
            }
            for (var slot : model.slots()) {
                if (category == RecipeCardModel.Category.BREWING && !slot.output()) continue;
                boolean large = slot.output() && category != RecipeCardModel.Category.SMITHING && category != RecipeCardModel.Category.BREWING;
                graphics.blitSprite(large ? OUTPUT_SLOT : SLOT,
                        rx + slot.x() + (large ? RecipeCardLayouts.OUTPUT_SLOT_OFFSET : RecipeCardLayouts.SLOT_OFFSET),
                        ry + slot.y() + (large ? RecipeCardLayouts.OUTPUT_SLOT_OFFSET : RecipeCardLayouts.SLOT_OFFSET),
                        large ? RecipeCardLayouts.OUTPUT_SLOT_SIZE : RecipeCardLayouts.SLOT_SIZE,
                        large ? RecipeCardLayouts.OUTPUT_SLOT_SIZE : RecipeCardLayouts.SLOT_SIZE);
            }
            if (category == RecipeCardModel.Category.BREWING) {
                graphics.blitSprite(BREWING_HEAT, rx + RecipeCardLayouts.BREWING_HEAT_X, ry + RecipeCardLayouts.BREWING_HEAT_Y, RecipeCardLayouts.BREWING_HEAT_WIDTH, RecipeCardLayouts.BREWING_HEAT_HEIGHT);
                int[] masks = { 29, 23, 18, 13, 9, 5, 0 };
                int mask = masks[(int) ((animation % 700) / 100)];
                if (mask < RecipeCardLayouts.BREWING_BUBBLES_HEIGHT) graphics.blitSprite(BREWING_BUBBLES, RecipeCardLayouts.BREWING_BUBBLES_WIDTH, RecipeCardLayouts.BREWING_BUBBLES_HEIGHT, 0, mask, rx + RecipeCardLayouts.BREWING_BUBBLES_X, ry + RecipeCardLayouts.BREWING_BUBBLES_Y + mask, RecipeCardLayouts.BREWING_BUBBLES_WIDTH, RecipeCardLayouts.BREWING_BUBBLES_HEIGHT - mask);
                int arrow = (int) ((animation % 20000) * 28 / 20000);
                if (arrow > 0) graphics.blitSprite(BREWING_ARROW, RecipeCardLayouts.BREWING_ARROW_WIDTH, RecipeCardLayouts.BREWING_ARROW_HEIGHT, 0, 0, rx + RecipeCardLayouts.BREWING_ARROW_X, ry + RecipeCardLayouts.BREWING_ARROW_Y, RecipeCardLayouts.BREWING_ARROW_WIDTH, arrow);
            } else {
                boolean cooking = category == RecipeCardModel.Category.SMELTING || category == RecipeCardModel.Category.BLASTING || category == RecipeCardModel.Category.SMOKING || category == RecipeCardModel.Category.CAMPFIRE;
                int ax = category == RecipeCardModel.Category.CRAFTING_SHAPED || category == RecipeCardModel.Category.CRAFTING_SHAPELESS ? RecipeCardLayouts.CRAFTING_ARROW_X : category == RecipeCardModel.Category.SMITHING ? RecipeCardLayouts.SMITHING_ARROW_X : RecipeCardLayouts.COOKING_ARROW_X;
                int ay = switch (category) { case CRAFTING_SHAPED, CRAFTING_SHAPELESS -> RecipeCardLayouts.CRAFTING_ARROW_Y; case SMITHING -> RecipeCardLayouts.SMITHING_ARROW_Y; case CAMPFIRE -> RecipeCardLayouts.CAMPFIRE_ARROW_Y; case STONECUTTING -> RecipeCardLayouts.STONECUTTING_ARROW_Y; default -> RecipeCardLayouts.COOKING_ARROW_Y; };
                graphics.blitSprite(RECIPE_ARROW, rx + ax, ry + ay, RecipeCardLayouts.ARROW_WIDTH, RecipeCardLayouts.ARROW_HEIGHT);
                if (cooking) {
                    int ticks = model.cookingTime() > 0 ? model.cookingTime() : category == RecipeCardModel.Category.CAMPFIRE ? 400 : category == RecipeCardModel.Category.SMELTING ? 200 : 100;
                    long duration = ticks * 50L;
                    int filled = (int) ((animation % duration) * 23 / duration);
                    if (filled > 0) graphics.blitSprite(RECIPE_ARROW_FILLED, RecipeCardLayouts.ARROW_WIDTH, RecipeCardLayouts.ARROW_HEIGHT, 0, 0, rx + ax, ry + ay, filled, RecipeCardLayouts.ARROW_HEIGHT);
                    graphics.blitSprite(FLAME_EMPTY, rx + RecipeCardLayouts.FLAME_X, ry + RecipeCardLayouts.FLAME_Y, RecipeCardLayouts.FLAME_SIZE, RecipeCardLayouts.FLAME_SIZE);
                    int mask = (int) ((animation % 15000) * 15 / 15000);
                    if (mask < RecipeCardLayouts.FLAME_SIZE) graphics.blitSprite(FLAME, RecipeCardLayouts.FLAME_SIZE, RecipeCardLayouts.FLAME_SIZE, 0, mask, rx + RecipeCardLayouts.FLAME_X, ry + RecipeCardLayouts.FLAME_Y + mask, RecipeCardLayouts.FLAME_SIZE, RecipeCardLayouts.FLAME_SIZE - mask);
                }
                if (category == RecipeCardModel.Category.CRAFTING_SHAPELESS) {
                    graphics.pose().pushPose();
                    graphics.pose().translate(rx + RecipeCardLayouts.SHAPELESS_X, ry + RecipeCardLayouts.SHAPELESS_Y, 0);
                    graphics.pose().scale(0.25f, 0.25f, 1);
                    graphics.blitSprite(SHAPELESS_ICON, 36, 36, 1, 1, 1, 1, 33, 34);
                    graphics.pose().popPose();
                }
            }
            for (int i = 0; i < model.slots().size(); i++) {
                var slot = model.slots().get(i);
                var alternatives = slots.get(i);
                if (alternatives.isEmpty()) continue;
                var displayed = alternatives.get(SlotStacks.cycleIndex(cycleTime, alternatives.size()));
                if (displayed.icon() == null) graphics.drawString(font, "?", rx + slot.x() + 5, ry + slot.y() + 4, 0xff404040, false);
                else {
                    graphics.renderItem(displayed.icon(), rx + slot.x(), ry + slot.y());
                    graphics.renderItemDecorations(font, displayed.icon(), rx + slot.x(), ry + slot.y());
                }
            }
            for (var text : model.texts()) graphics.drawString(font, text.text(), rx + text.x() - (text.rightAligned() ? font.width(text.text()) : 0), ry + text.y(), 0xff808080, false);
            for (int i = 0; i < model.slots().size(); i++) {
                var slot = model.slots().get(i);
                if (!slots.get(i).isEmpty() && mouseX >= rx + slot.x() && mouseX < rx + slot.x() + 16 && mouseY >= ry + slot.y() && mouseY < ry + slot.y() + 16) graphics.fillGradient(RenderType.guiOverlay(), rx + slot.x(), ry + slot.y(), rx + slot.x() + 16, ry + slot.y() + 16, 0x80ffffff, 0x80ffffff, 0);
            }
            graphics.drawString(font, caption, x, y + model.height() + 12, ModernUi.MUTED, false);
        }

        @Override
        public Optional<Hover> hoverAt(int x, int y, double mouseX, double mouseY) {
            for (int i = 0; i < model.slots().size(); i++) {
                var slot = model.slots().get(i);
                var alternatives = slots.get(i);
                if (!alternatives.isEmpty() && mouseX >= x + 4 + slot.x() && mouseX < x + 20 + slot.x()
                        && mouseY >= y + 4 + slot.y() && mouseY < y + 20 + slot.y())
                    return Optional.of(alternatives.get(SlotStacks.cycleIndex(cycleTime, alternatives.size())).hover());
            }
            return Optional.empty();
        }
    }
}
