package dev.packsage.client.recipe;

import dev.packsage.client.ModernUi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

public final class NativeRecipeCardSource implements RecipeCardSource {
    @Override
    public Optional<RecipeCard> card(RecipeCardModel model) {
        var level = Minecraft.getInstance().level;
        if (level == null) return Optional.empty();
        var slots = new ArrayList<List<DisplayedStack>>();
        for (var slot : model.slots()) {
            var stacks = new ArrayList<DisplayedStack>();
            for (var alternative : slot.stacks()) {
                if (alternative.kind() == RecipeCardModel.Stack.Kind.CUSTOM || alternative.kind() == RecipeCardModel.Stack.Kind.UNKNOWN) {
                    var marker = new ItemStack(Items.BARRIER);
                    marker.set(DataComponents.CUSTOM_NAME, Component.literal(alternative.description().isBlank() ? "Unknown ingredient" : alternative.description()));
                    stacks.add(new DisplayedStack(marker, true));
                } else {
                    for (var stack : SlotStacks.resolve(alternative, level.registryAccess())) stacks.add(new DisplayedStack(stack, false));
                }
            }
            slots.add(List.copyOf(stacks));
        }
        return Optional.of(new NativeCard(model, List.copyOf(slots)));
    }

    private record DisplayedStack(ItemStack stack, boolean question) { }

    private static final class NativeCard implements RecipeCard {
        private final RecipeCardModel model;
        private final List<List<DisplayedStack>> slots;
        private final long animationStart = System.currentTimeMillis();
        private long lastTime = animationStart;
        private long cycleTime = ThreadLocalRandom.current().nextInt(100000) * (long) SlotStacks.CYCLE_MS;

        private NativeCard(RecipeCardModel model, List<List<DisplayedStack>> slots) {
            this.model = model;
            this.slots = slots;
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
            var panel = ResourceLocation.fromNamespaceAndPath("packsage", "jei/single_recipe_background");
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
                            graphics.blitSprite(panel, 64, 64, source[col], source[row] + tileHeight - h,
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
                graphics.blitSprite(ResourceLocation.fromNamespaceAndPath("packsage", "jei/brewing_stand_background"), rx + RecipeCardLayouts.BREWING_BACKGROUND_X, ry + RecipeCardLayouts.BREWING_BACKGROUND_Y, RecipeCardLayouts.BREWING_BACKGROUND_WIDTH, RecipeCardLayouts.BREWING_BACKGROUND_HEIGHT);
            }
            for (var slot : model.slots()) {
                if (category == RecipeCardModel.Category.BREWING && !slot.output()) continue;
                boolean large = slot.output() && category != RecipeCardModel.Category.SMITHING && category != RecipeCardModel.Category.BREWING;
                graphics.blitSprite(ResourceLocation.fromNamespaceAndPath("packsage", large ? "jei/output_slot" : "jei/slot"),
                        rx + slot.x() + (large ? RecipeCardLayouts.OUTPUT_SLOT_OFFSET : RecipeCardLayouts.SLOT_OFFSET),
                        ry + slot.y() + (large ? RecipeCardLayouts.OUTPUT_SLOT_OFFSET : RecipeCardLayouts.SLOT_OFFSET),
                        large ? RecipeCardLayouts.OUTPUT_SLOT_SIZE : RecipeCardLayouts.SLOT_SIZE,
                        large ? RecipeCardLayouts.OUTPUT_SLOT_SIZE : RecipeCardLayouts.SLOT_SIZE);
            }
            if (category == RecipeCardModel.Category.BREWING) {
                graphics.blitSprite(ResourceLocation.fromNamespaceAndPath("packsage", "jei/brewing_stand_blaze_heat"), rx + RecipeCardLayouts.BREWING_HEAT_X, ry + RecipeCardLayouts.BREWING_HEAT_Y, RecipeCardLayouts.BREWING_HEAT_WIDTH, RecipeCardLayouts.BREWING_HEAT_HEIGHT);
                int[] masks = { 29, 23, 18, 13, 9, 5, 0 };
                int mask = masks[(int) ((animation % 700) / 100)];
                if (mask < RecipeCardLayouts.BREWING_BUBBLES_HEIGHT) graphics.blitSprite(ResourceLocation.fromNamespaceAndPath("packsage", "jei/brewing_stand_bubbles"), RecipeCardLayouts.BREWING_BUBBLES_WIDTH, RecipeCardLayouts.BREWING_BUBBLES_HEIGHT, 0, mask, rx + RecipeCardLayouts.BREWING_BUBBLES_X, ry + RecipeCardLayouts.BREWING_BUBBLES_Y + mask, RecipeCardLayouts.BREWING_BUBBLES_WIDTH, RecipeCardLayouts.BREWING_BUBBLES_HEIGHT - mask);
                int arrow = (int) ((animation % 20000) * 28 / 20000);
                if (arrow > 0) graphics.blitSprite(ResourceLocation.fromNamespaceAndPath("packsage", "jei/brewing_stand_arrow"), RecipeCardLayouts.BREWING_ARROW_WIDTH, RecipeCardLayouts.BREWING_ARROW_HEIGHT, 0, 0, rx + RecipeCardLayouts.BREWING_ARROW_X, ry + RecipeCardLayouts.BREWING_ARROW_Y, RecipeCardLayouts.BREWING_ARROW_WIDTH, arrow);
            } else {
                boolean cooking = category == RecipeCardModel.Category.SMELTING || category == RecipeCardModel.Category.BLASTING || category == RecipeCardModel.Category.SMOKING || category == RecipeCardModel.Category.CAMPFIRE;
                int ax = category == RecipeCardModel.Category.CRAFTING_SHAPED || category == RecipeCardModel.Category.CRAFTING_SHAPELESS ? RecipeCardLayouts.CRAFTING_ARROW_X : category == RecipeCardModel.Category.SMITHING ? RecipeCardLayouts.SMITHING_ARROW_X : RecipeCardLayouts.COOKING_ARROW_X;
                int ay = switch (category) { case CRAFTING_SHAPED, CRAFTING_SHAPELESS -> RecipeCardLayouts.CRAFTING_ARROW_Y; case SMITHING -> RecipeCardLayouts.SMITHING_ARROW_Y; case CAMPFIRE -> RecipeCardLayouts.CAMPFIRE_ARROW_Y; case STONECUTTING -> RecipeCardLayouts.STONECUTTING_ARROW_Y; default -> RecipeCardLayouts.COOKING_ARROW_Y; };
                graphics.blitSprite(ResourceLocation.fromNamespaceAndPath("packsage", "jei/recipe_arrow"), rx + ax, ry + ay, RecipeCardLayouts.ARROW_WIDTH, RecipeCardLayouts.ARROW_HEIGHT);
                if (cooking) {
                    int ticks = model.cookingTime() > 0 ? model.cookingTime() : category == RecipeCardModel.Category.CAMPFIRE ? 400 : category == RecipeCardModel.Category.SMELTING ? 200 : 100;
                    long duration = ticks * 50L;
                    int filled = (int) ((animation % duration) * 23 / duration);
                    if (filled > 0) graphics.blitSprite(ResourceLocation.fromNamespaceAndPath("packsage", "jei/recipe_arrow_filled"), RecipeCardLayouts.ARROW_WIDTH, RecipeCardLayouts.ARROW_HEIGHT, 0, 0, rx + ax, ry + ay, filled, RecipeCardLayouts.ARROW_HEIGHT);
                    graphics.blitSprite(ResourceLocation.fromNamespaceAndPath("packsage", "jei/icons/flame_empty"), rx + RecipeCardLayouts.FLAME_X, ry + RecipeCardLayouts.FLAME_Y, RecipeCardLayouts.FLAME_SIZE, RecipeCardLayouts.FLAME_SIZE);
                    int mask = (int) ((animation % 15000) * 15 / 15000);
                    if (mask < RecipeCardLayouts.FLAME_SIZE) graphics.blitSprite(ResourceLocation.fromNamespaceAndPath("packsage", "jei/icons/flame"), RecipeCardLayouts.FLAME_SIZE, RecipeCardLayouts.FLAME_SIZE, 0, mask, rx + RecipeCardLayouts.FLAME_X, ry + RecipeCardLayouts.FLAME_Y + mask, RecipeCardLayouts.FLAME_SIZE, RecipeCardLayouts.FLAME_SIZE - mask);
                }
                if (category == RecipeCardModel.Category.CRAFTING_SHAPELESS) {
                    graphics.pose().pushPose();
                    graphics.pose().translate(rx + RecipeCardLayouts.SHAPELESS_X, ry + RecipeCardLayouts.SHAPELESS_Y, 0);
                    graphics.pose().scale(0.25f, 0.25f, 1);
                    graphics.blitSprite(ResourceLocation.fromNamespaceAndPath("packsage", "jei/icons/shapeless_icon"), 36, 36, 1, 1, 1, 1, 33, 34);
                    graphics.pose().popPose();
                }
            }
            for (int i = 0; i < model.slots().size(); i++) {
                var slot = model.slots().get(i);
                var alternatives = slots.get(i);
                if (alternatives.isEmpty()) continue;
                var displayed = alternatives.get(SlotStacks.cycleIndex(cycleTime, alternatives.size()));
                if (displayed.question()) graphics.drawString(font, "?", rx + slot.x() + 5, ry + slot.y() + 4, 0xff404040, false);
                else {
                    graphics.renderItem(displayed.stack(), rx + slot.x(), ry + slot.y());
                    graphics.renderItemDecorations(font, displayed.stack(), rx + slot.x(), ry + slot.y());
                }
            }
            for (var text : model.texts()) graphics.drawString(font, text.text(), rx + text.x() - (text.rightAligned() ? font.width(text.text()) : 0), ry + text.y(), 0xff808080, false);
            for (int i = 0; i < model.slots().size(); i++) {
                var slot = model.slots().get(i);
                if (!slots.get(i).isEmpty() && mouseX >= rx + slot.x() && mouseX < rx + slot.x() + 16 && mouseY >= ry + slot.y() && mouseY < ry + slot.y() + 16) graphics.fill(rx + slot.x(), ry + slot.y(), rx + slot.x() + 16, ry + slot.y() + 16, 0x80ffffff);
            }
            graphics.drawString(font, Component.literal(model.caption()).withStyle(style -> style.withFont(ModernUi.FONT)), x, y + model.height() + 12, ModernUi.MUTED, false);
        }

        @Override
        public Optional<ItemStack> stackAt(int x, int y, double mouseX, double mouseY) {
            for (int i = 0; i < model.slots().size(); i++) {
                var slot = model.slots().get(i);
                var alternatives = slots.get(i);
                if (!alternatives.isEmpty() && mouseX >= x + 4 + slot.x() && mouseX < x + 20 + slot.x()
                        && mouseY >= y + 4 + slot.y() && mouseY < y + 20 + slot.y())
                    return Optional.of(alternatives.get(SlotStacks.cycleIndex(cycleTime, alternatives.size())).stack());
            }
            return Optional.empty();
        }
    }
}
