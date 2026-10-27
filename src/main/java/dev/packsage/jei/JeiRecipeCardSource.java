package dev.packsage.jei;

import dev.packsage.PackSageLog;
import dev.packsage.client.ModernUi;
import dev.packsage.client.recipe.RecipeCard;
import dev.packsage.client.recipe.RecipeCardModel;
import dev.packsage.client.recipe.RecipeCardSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Recipe cards drawn by JEI's own recipe layouts, for JEI records and for native records JEI also shows. */
public final class JeiRecipeCardSource implements RecipeCardSource {
    static final double MIN_SCALE = 0.75;
    static final int CACHE_SIZE = 64;
    private static final int TICK_MS = 50;

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
                layout = resolved.flatMap(value -> drawable(snapshot.runtime(), value)).map(Layout::new);
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
                    : handle.generation() == index.generation() ? Optional.of(new JeiIndex.RecipeRef(handle.categoryUid(), handle.ordinal()))
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
        long lastTick = System.currentTimeMillis();

        Layout(IRecipeLayoutDrawable<?> drawable) {
            // The border JEI draws around getRect() belongs to the card, matching the native card's padding.
            this(drawable, drawable.getRect().getX() - drawable.getRectWithBorder().getX(),
                    drawable.getRect().getY() - drawable.getRectWithBorder().getY(),
                    drawable.getRectWithBorder().getWidth(), drawable.getRectWithBorder().getHeight());
            drawable.setPosition(offsetX, offsetY);
        }

        Layout(IRecipeLayoutDrawable<?> drawable, int offsetX, int offsetY, int width, int height) {
            this.drawable = drawable;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.width = width;
            this.height = height;
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

        @Override
        public Optional<Hover> hoverAt(int x, int y, double mouseX, double mouseY) {
            var stack = itemAt(x, y, mouseX, mouseY);
            return stack.isEmpty() ? Optional.empty() : Optional.of(new Hover.Item(stack));
        }

        @Override
        public ItemStack itemAt(int x, int y, double mouseX, double mouseY) {
            if (!current() || !inside(x, y, mouseX, mouseY)) return ItemStack.EMPTY;
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
