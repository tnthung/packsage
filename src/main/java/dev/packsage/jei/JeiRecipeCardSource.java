package dev.packsage.jei;

import dev.packsage.PackSageLog;
import dev.packsage.client.ModernUi;
import dev.packsage.client.recipe.RecipeCard;
import dev.packsage.client.recipe.RecipeCardModel;
import dev.packsage.client.recipe.RecipeCardSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/** Recipe cards drawn by JEI's own recipe layouts, for JEI records and for native records JEI also shows. */
public final class JeiRecipeCardSource implements RecipeCardSource {
    static final double MIN_SCALE = 0.75;
    static final int CACHE_SIZE = 64;
    private static final int TICK_MS = 50;
    private static final ResourceLocation SLOT = ResourceLocation.fromNamespaceAndPath("packsage", "jei/slot");

    record Key(String handle, long generation) { }

    static final class Cache<V> extends LinkedHashMap<Key, V> {
        Cache() { super(16, 0.75f, true); }
        @Override protected boolean removeEldestEntry(Map.Entry<Key, V> eldest) { return size() > CACHE_SIZE; }
    }

    static final int REPORT_LIMIT = 32;
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private final Cache<Optional<Layout>> cache = new Cache<>();
    private final Set<String> reported;
    private JeiRuntimeIndex index;
    private long indexGeneration;

    public JeiRecipeCardSource() {
        this(REPORTED);
    }

    JeiRecipeCardSource(Set<String> reported) {
        this.reported = reported;
    }

    @Override
    public Optional<RecipeCard> card(RecipeCardModel model) {
        return card(model, Integer.MAX_VALUE);
    }

    @Override
    public Optional<RecipeCard> card(RecipeCardModel model, int maxWidth) {
        var snapshot = JeiRuntimeHolder.snapshot();
        var handle = handle(model);
        if (snapshot.runtime() == null || handle == null) return Optional.empty();
        long generation = snapshot.generation();
        var key = new Key(handle, generation);
        Optional<Layout> layout;
        try {
            if (index == null || indexGeneration != generation) {
                index = new JeiRuntimeIndex(snapshot);
                indexGeneration = generation;
                cache.clear();
            }
            layout = cache.get(key);
            if (layout == null) {
                var resolved = ref(index, model).flatMap(index::resolve);
                layout = resolved.flatMap(value -> drawable(snapshot.runtime(), value).map(drawable -> new Layout(drawable,
                        value.category().getRecipeType().getUid().toString(),
                        snapshot.runtime().getRecipeManager().createRecipeCatalystLookup(value.category().getRecipeType()).get().toList(),
                        snapshot.runtime().getIngredientManager())));
                cache.put(key, layout);
            }
        } catch (RuntimeException | LinkageError failure) {
            // Missing recipes return empty above; anything thrown is a broken category or API, so fall back and say so once.
            cache.put(key, Optional.empty());
            var category = JeiProjection.destination(handle).flatMap(JeiProjection::parseHandle).map(JeiProjection.Handle::categoryUid).orElse(handle);
            if (reported.size() < REPORT_LIMIT && reported.add(category))
                PackSageLog.LOGGER.warn("Could not draw JEI recipe card for category {} (recipe {}); using the fallback", category, handle, failure);
            return Optional.empty();
        }
        if (layout.isEmpty()) return Optional.empty();
        double scale = scale(layout.get().width, maxWidth);
        if (scale == 0) return Optional.empty();
        return Optional.of(new JeiCard(layout.get(), model.caption(), scale, generation));
    }

    @Override
    public List<Machine> machines(RecipeCardModel model) {
        card(model);
        var layout = cache.get(new Key(handle(model), generation()));
        return layout == null || layout.isEmpty() ? List.of() : layout.get().machines;
    }

    @Override
    public boolean open(RecipeCardModel model) {
        if (index == null || indexGeneration != generation() || JeiRuntimeHolder.snapshot().runtime() == null) return false;
        return ref(index, model).map(ref -> new JeiViewer(JeiRuntimeHolder.snapshot().runtime().getRecipesGui(), index::resolveVisible).showRecipe(ref)).orElse(false);
    }

    record Strip(int columns, int rows, int visible, int hidden, int height) {
        int width() { return columns * 18; }

        int at(double x, double y, double scale) { return at(x, y, scale, false); }

        int at(double x, double y, double scale, boolean rightward) {
            if (x < 0 || x >= width() * scale || y < 0 || y >= rows * 18 * scale) return -1;
            int column = (int) Math.floor(x / scale / 18);
            int index = (rightward ? column : columns - 1 - column) * rows + (int) Math.floor(y / scale / 18);
            return index < visible + (hidden > 0 ? 1 : 0) ? index : -1;
        }
    }

    record Panel(Strip strip, double left, int top, double scale, boolean rightward) {
        boolean contains(double x, double y) {
            return x >= left && x < left + strip.width() * scale && y >= top && y < top + strip.height() * scale;
        }
        int at(double x, double y) { return strip.at(x - left, y - top, scale, rightward); }
    }

    static Panel panel(Strip strip, int x, int y, double scale, int screenWidth) {
        double extraWidth = (strip.columns() - 1) * 18 * scale;
        if (x >= extraWidth) return new Panel(strip, x - extraWidth, y, scale, false);
        int columns = Math.max(1, (int) Math.floor((screenWidth - x) / (18 * scale)));
        int rows = Math.max(strip.rows(), (strip.visible() + columns - 1) / columns);
        return new Panel(new Strip((strip.visible() + rows - 1) / rows, rows, strip.visible(), 0, rows * 18), x, y, scale, true);
    }

    static Strip strip(String category, int count, int height) { return strip(category, count, height, false); }

    static Strip strip(String category, int count, int height, boolean expanded) {
        int rows = Math.max(1, height / 18);
        if (category.equals("minecraft:crafting") || count == 0) return new Strip(0, rows, 0, 0, height);
        int columns = expanded ? (count + rows - 1) / rows : 1;
        int visible = count > rows * columns ? rows * columns - 1 : count;
        return new Strip(columns, rows, visible, count - visible, Math.max(18, height));
    }

    @SuppressWarnings("removal")
    private static <T> List<Component> tooltip(IIngredientManager manager, ITypedIngredient<T> ingredient) {
        return manager.getIngredientRenderer(ingredient.getType()).getTooltip(ingredient.getIngredient(),
                Minecraft.getInstance().options.advancedItemTooltips ? TooltipFlag.ADVANCED : TooltipFlag.NORMAL);
    }

    /** The JEI runtime generation: cards made under another generation no longer draw. */
    @Override
    public long generation() {
        return JeiRuntimeHolder.generation();
    }

    Set<String> reportedCategories() {
        return reported;
    }

    /** The JEI reference for a model: viewer handles directly, native models through their vanilla category. */
    static Optional<JeiIndex.RecipeRef> ref(JeiIndex index, RecipeCardModel model) {
        if (model.category() == RecipeCardModel.Category.VIEWER) {
            return JeiProjection.destination(model.viewerHandle()).flatMap(JeiProjection::parseHandle).flatMap(handle -> handle.recipeId() != null
                    ? index.byRecipeId(handle.categoryUid(), handle.recipeId())
                    : handle.current(index) ? Optional.of(new JeiIndex.RecipeRef(handle.categoryUid(), handle.ordinal()))
                    : Optional.empty());
        }
        var category = nativeCategory(model.category());
        return category == null || model.recipeId() == null ? Optional.empty() : index.byRecipeId(category, model.recipeId());
    }

    /** The scale that fits {@code width} into {@code maxWidth}, or 0 when it would drop below {@link #MIN_SCALE}. */
    static double scale(int width, int maxWidth) {
        if (width <= maxWidth) return 1.0;
        double scale = maxWidth / (double) width;
        return scale >= MIN_SCALE ? scale : 0;
    }

    private static String handle(RecipeCardModel model) {
        if (model.category() == RecipeCardModel.Category.VIEWER) return model.viewerHandle();
        var category = nativeCategory(model.category());
        return category == null || model.recipeId() == null ? null : category + "|" + model.recipeId();
    }

    private static String nativeCategory(RecipeCardModel.Category category) {
        return switch (category) {
            case CRAFTING_SHAPED, CRAFTING_SHAPELESS -> "minecraft:crafting";
            case SMELTING -> "minecraft:smelting";
            case BLASTING -> "minecraft:blasting";
            case SMOKING -> "minecraft:smoking";
            case CAMPFIRE -> "minecraft:campfire_cooking";
            case STONECUTTING -> "minecraft:stonecutting";
            case SMITHING -> "minecraft:smithing";
            case BREWING -> "minecraft:brewing";
            case VIEWER -> null;
        };
    }

    private static <T> Optional<IRecipeLayoutDrawable<?>> drawable(IJeiRuntime runtime, JeiRuntimeIndex.Resolved<T> resolved) {
        return runtime.getRecipeManager().createRecipeLayoutDrawable(resolved.category(), resolved.recipe(),
                runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup()).map(value -> value);
    }

    /** A JEI layout positioned so its border starts at the local origin; shared by every card of the same recipe. */
    static final class Layout {
        final IRecipeLayoutDrawable<?> drawable;
        final int offsetX;
        final int offsetY;
        final int width;
        final int height;
        List<ITypedIngredient<?>> catalysts = List.of();
        List<Machine> machines = List.of();
        IIngredientManager ingredients;
        Strip strip;
        Strip expandedStrip;
        long lastTick = System.currentTimeMillis();

        Layout(IRecipeLayoutDrawable<?> drawable, int offsetX, int offsetY, int width, int height) {
            this.drawable = drawable;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.width = width;
            this.height = height;
            this.strip = new Strip(0, 1, 0, 0, height);
        }

        Layout(IRecipeLayoutDrawable<?> drawable, String category, List<ITypedIngredient<?>> catalysts,
               IIngredientManager ingredients) {
            this.drawable = drawable;
            this.strip = strip(category, catalysts.size(), drawable.getRect().getHeight());
            this.expandedStrip = strip(category, catalysts.size(), drawable.getRect().getHeight(), true);
            this.offsetX = drawable.getRect().getX() - drawable.getRectWithBorder().getX() + strip.width();
            this.offsetY = drawable.getRect().getY() - drawable.getRectWithBorder().getY();
            this.width = drawable.getRectWithBorder().getWidth() + strip.width();
            this.height = Math.max(drawable.getRectWithBorder().getHeight(), strip.height());
            this.catalysts = strip.width() == 0 ? List.of() : List.copyOf(catalysts);
            this.ingredients = ingredients;
            this.machines = this.catalysts.stream().map(ingredient -> new Machine(ingredient.getItemStack().orElse(ItemStack.EMPTY), tooltip(ingredients, ingredient))).toList();
            drawable.setPosition(offsetX, offsetY);
        }

        void tick() {
            long now = System.currentTimeMillis();
            long ticks = Math.max(0, (now - lastTick) / TICK_MS);
            for (long i = 0; i < Math.min(ticks, 20); i++) drawable.tick();
            lastTick += ticks * TICK_MS;
        }
    }

    static final class JeiCard implements RecipeCard {
        private final Layout layout;
        private final Component caption;
        private final double scale;
        private final long generation;
        private final int areaWidth;
        private final int areaHeight;
        private Panel panel;
        private int panelX;
        private int panelY;

        JeiCard(Layout layout, String caption, double scale, long generation) {
            this.layout = layout;
            this.caption = Component.literal(caption).withStyle(style -> style.withFont(ModernUi.FONT));
            this.scale = scale;
            this.generation = generation;
            this.areaWidth = (int) Math.floor(layout.width * scale);
            this.areaHeight = (int) Math.floor(layout.height * scale);
        }

        /** Whether the JEI runtime that built this card's layout is still the current one; any availability change advances it. */
        boolean current() {
            return JeiRuntimeHolder.generation() == generation;
        }

        @Override public int width() { return areaWidth; }
        @Override public int height() { return areaHeight + 4 + Minecraft.getInstance().font.lineHeight; }

        // drawHoverOverlays is deprecated in later JEI versions, but it is the 19.27 way to draw only the slot highlight.
        @SuppressWarnings("removal")
        @Override
        public void draw(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
            // A stale layout belongs to a replaced runtime; the screen rebuilds its cards on the generation change.
            if (!current()) return;
            layout.tick();
            var drawable = layout.drawable;
            boolean inside = inside(x, y, mouseX, mouseY);
            int localX = inside ? local(mouseX, x) : -1000;
            int localY = inside ? local(mouseY, y) : -1000;
            graphics.pose().pushPose();
            graphics.pose().translate(x, y, 0);
            graphics.pose().scale((float) scale, (float) scale, 1);
            drawStrip(graphics, layout.strip, true, localX, localY);
            drawable.setPosition(layout.offsetX, layout.offsetY);
            drawable.drawRecipe(graphics, localX, localY);
            if (drawable.getItemStackUnderMouse(localX, localY).isPresent()) {
                // The screen draws item tooltips itself above every layer; only draw JEI's slot highlight here.
                drawable.getSlotUnderMouse(localX, localY).ifPresent(slot -> {
                    graphics.pose().pushPose();
                    graphics.pose().translate(slot.offset().x(), slot.offset().y(), 0);
                    slot.slot().drawHoverOverlays(graphics);
                    graphics.pose().popPose();
                });
            } else drawable.drawOverlays(graphics, localX, localY);
            graphics.pose().popPose();
            graphics.drawString(Minecraft.getInstance().font, caption, x, y + areaHeight + 4, ModernUi.MUTED, false);
        }

        private void drawStrip(GuiGraphics graphics, Strip strip, boolean rightward, int mouseX, int mouseY) {
            if (strip.width() == 0) return;
            graphics.fill(0, 0, strip.width(), strip.height(), 0xffc6c6c6);
            for (int i = 0; i < strip.visible() + (strip.hidden() > 0 ? 1 : 0); i++) {
                int x = (rightward ? i / strip.rows() : strip.columns() - 1 - i / strip.rows()) * 18;
                int y = i % strip.rows() * 18;
                graphics.blitSprite(SLOT, x, y, 18, 18);
                if (i < strip.visible()) {
                    graphics.pose().pushPose();
                    graphics.pose().translate(x + 1, y + 1, 0);
                    drawIngredient(graphics, layout.ingredients, layout.catalysts.get(i));
                    graphics.pose().popPose();
                } else {
                    String label = "+" + strip.hidden();
                    float textScale = Math.min(1, 16f / Minecraft.getInstance().font.width(label));
                    graphics.pose().pushPose();
                    graphics.pose().translate(x + 1, y + 5, 0);
                    graphics.pose().scale(textScale, textScale, 1);
                    graphics.drawString(Minecraft.getInstance().font, label, 0, 0, 0xff404040, false);
                    graphics.pose().popPose();
                }
                if (strip.at(mouseX, mouseY, 1, rightward) == i) {
                    graphics.pose().pushPose();
                    graphics.pose().translate(0, 0, 200);
                    graphics.fill(x + 1, y + 1, x + 17, y + 17, 0x80ffffff);
                    graphics.pose().popPose();
                }
            }
        }

        void updatePanel(int x, int y, double mouseX, double mouseY, int screenWidth) {
            if (!current() || layout.expandedStrip == null || layout.strip.width() == 0) {
                panel = null;
                return;
            }
            if (layout.strip.at(mouseX - x, mouseY - y, scale) >= 0) {
                panelX = x;
                panelY = y;
                panel = panel(layout.expandedStrip, x, y, scale, screenWidth);
            } else if (panel != null && panelX == x && panelY == y) {
                panel = panel.contains(mouseX, mouseY) ? panel(layout.expandedStrip, x, y, scale, screenWidth) : null;
            }
        }

        @Override
        public boolean overlayContains(double mouseX, double mouseY) {
            return current() && panel != null && panel.contains(mouseX, mouseY);
        }

        @Override
        public boolean drawOverlay(GuiGraphics graphics, int x, int y, int screenWidth, int mouseX, int mouseY) {
            updatePanel(x, y, mouseX, mouseY, screenWidth);
            if (panel == null || panelX != x || panelY != y) return false;
            graphics.pose().pushPose();
            graphics.pose().translate(panel.left(), panel.top(), 0);
            graphics.pose().scale((float) scale, (float) scale, 1);
            drawStrip(graphics, panel.strip(), panel.rightward(), (int) Math.floor((mouseX - panel.left()) / scale), local(mouseY, y));
            graphics.pose().popPose();
            return overlayContains(mouseX, mouseY);
        }

        private static <T> void drawIngredient(GuiGraphics graphics, IIngredientManager manager, ITypedIngredient<T> ingredient) {
            manager.getIngredientRenderer(ingredient.getType()).render(graphics, ingredient.getIngredient());
        }

        // IRecipeSlotDrawable.getTooltip() is the 19.27 way to read a slot's complete tooltip.
        @SuppressWarnings({"deprecation", "removal"})
        @Override
        public Optional<Hover> hoverAt(int x, int y, double mouseX, double mouseY) {
            if (!current() || !inside(x, y, mouseX, mouseY) && !overlayContains(mouseX, mouseY)) return Optional.empty();
            var strip = overlayContains(mouseX, mouseY) ? panel.strip() : layout.strip;
            int catalyst = overlayContains(mouseX, mouseY) ? panel.at(mouseX, mouseY) : strip.at(mouseX - x, mouseY - y, scale);
            if (catalyst >= 0) return catalyst < strip.visible()
                    ? Optional.of(new Hover.Text(tooltip(layout.ingredients, layout.catalysts.get(catalyst)))) : Optional.empty();
            var stack = itemAt(x, y, mouseX, mouseY);
            if (stack.isEmpty()) return Optional.empty();
            // The slot's whole JEI tooltip keeps lines the plain item tooltip lacks, such as an output's chance; the screen draws it above chat.
            var lines = layout.drawable.getRecipeSlotUnderMouse(local(mouseX, x), local(mouseY, y)).map(slot -> slot.getTooltip()).orElse(List.of());
            return Optional.of(lines.isEmpty() ? new Hover.Item(stack) : new Hover.Text(lines));
        }

        @Override
        public ItemStack itemAt(int x, int y, double mouseX, double mouseY) {
            if (!current() || !inside(x, y, mouseX, mouseY) && !overlayContains(mouseX, mouseY)) return ItemStack.EMPTY;
            var strip = overlayContains(mouseX, mouseY) ? panel.strip() : layout.strip;
            int catalyst = overlayContains(mouseX, mouseY) ? panel.at(mouseX, mouseY) : strip.at(mouseX - x, mouseY - y, scale);
            if (catalyst >= 0) return catalyst < strip.visible() ? layout.machines.get(catalyst).stack() : ItemStack.EMPTY;
            if (overlayContains(mouseX, mouseY)) return ItemStack.EMPTY;
            layout.drawable.setPosition(layout.offsetX, layout.offsetY);
            return layout.drawable.getItemStackUnderMouse(local(mouseX, x), local(mouseY, y)).orElse(ItemStack.EMPTY);
        }

        private boolean inside(int x, int y, double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + areaWidth && mouseY >= y && mouseY < y + areaHeight;
        }

        private int local(double mouse, int origin) {
            return (int) Math.floor((mouse - origin) / scale);
        }
    }
}
