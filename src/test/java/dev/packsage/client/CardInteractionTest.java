package dev.packsage.client;

import dev.packsage.chat.ChatActions;
import dev.packsage.chat.ChatContracts;
import dev.packsage.client.recipe.ItemViewer;
import dev.packsage.client.recipe.PinnedCardState;
import dev.packsage.client.recipe.RecipeCard;
import dev.packsage.knowledge.Evidence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardInteractionTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "runtime", 1);
    private static final ItemStack GLASS = new ItemStack(Items.GLASS);

    private static final class FakeViewer implements ItemViewer {
        final List<String> calls = new ArrayList<>();
        @Override public boolean showRecipes(ItemStack stack) { calls.add("recipes:" + stack.getItem()); return true; }
        @Override public boolean showUses(ItemStack stack) { calls.add("uses:" + stack.getItem()); return true; }
        @Override public boolean toggleBookmark(ItemStack stack) { calls.add("bookmark:" + stack.getItem()); return true; }
        @Override public boolean matchesShowRecipes(int keyCode, int scanCode) { return keyCode == GLFW.GLFW_KEY_R; }
        @Override public boolean matchesShowUses(int keyCode, int scanCode) { return keyCode == GLFW.GLFW_KEY_U; }
        @Override public boolean matchesBookmark(int keyCode, int scanCode) { return keyCode == GLFW.GLFW_KEY_A; }
    }

    private static final class SlotCard implements RecipeCard {
        @Override public int width() { return 40; }
        @Override public int height() { return 30; }
        @Override public void draw(net.minecraft.client.gui.GuiGraphics graphics, int x, int y, int mouseX, int mouseY) { }
        @Override public Optional<Hover> hoverAt(int x, int y, double mouseX, double mouseY) {
            return mouseX >= x + 4 && mouseX < x + 20 && mouseY >= y + 4 && mouseY < y + 20
                    ? Optional.of(new Hover.Item(GLASS)) : Optional.empty();
        }
    }

    private static final class FakeActions implements ChatActions {
        final List<String> navigated = new ArrayList<>();
        final ChatContracts.View view;
        FakeActions(ChatContracts.View view) { this.view = view; }
        @Override public ChatContracts.View view() { return view; }
        @Override public void signIn() { }
        @Override public void cancelSignIn() { }
        @Override public void signOut() { }
        @Override public void send(String question, boolean includeScreenshot, boolean explicitWebSearch) { }
        @Override public void stop() { }
        @Override public void newChat() { }
        @Override public void setPaused(boolean paused) { }
        @Override public void navigate(Evidence.NavigationTarget target) { navigated.add("navigate"); }
    }

    private static final class Harness {
        final FakeViewer viewer = new FakeViewer();
        final FakeActions actions;
        final AssistantScreen screen;

        Harness(boolean withViewer) throws Exception {
            this(withViewer, false);
        }

        Harness(boolean withViewer, boolean foreignHistory) throws Exception {
            var source = new Evidence.Source(SCOPE, "provider", "1", "source", 1, java.time.Instant.EPOCH,
                    Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of());
            var target = new Evidence.NavigationTarget(SCOPE, "provider", "source", "target");
            var context = new ChatContracts.Context("{}", null, SCOPE);
            var view = new ChatContracts.View(false, ChatContracts.SignInState.SIGNED_OUT, true,
                    new ChatContracts.Conversation("conversation", new ChatContracts.WorldIdentity("world", "World"),
                            List.of(new ChatContracts.Turn(new ChatContracts.RequestId("request"), "question", "answer", List.of(),
                                    context, List.of(source), new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1)),
                            new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null)),
                    context, List.of(source), List.of(target), List.of(), "", "",
                    foreignHistory ? new ChatContracts.WorldIdentity("other", "Other") : new ChatContracts.WorldIdentity("world", "World"), foreignHistory);
            actions = new FakeActions(view);
            screen = new AssistantScreen(actions);
            screen.width = 640;
            screen.height = 480;
            ScreenTestHost.initialize(screen);
            screen.refresh(view);
            if (withViewer) screen.setItemViewer(viewer);
            screen.setFocused(null);
            set("readableLeft", 20);
            set("readableWidth", 400);
            set("toggleY", 400);
        }

        void set(String name, int value) throws Exception {
            var field = AssistantScreen.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setInt(screen, value);
        }

        @SuppressWarnings("unchecked")
        void hit(String list, String record, Object... args) throws Exception {
            var field = AssistantScreen.class.getDeclaredField(list);
            field.setAccessible(true);
            Class<?> type = null;
            for (var c : AssistantScreen.class.getDeclaredClasses()) if (c.getSimpleName().equals(record)) type = c;
            Constructor<?> constructor = type.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            ((List<Object>) field.get(screen)).add(constructor.newInstance(args));
        }

        void card() throws Exception { hit("cardHits", "CardHit", new SlotCard(), null, new PinnedCardState.Rect(50, 100, 90, 130)); }
        void itemChip() throws Exception { hit("itemHits", "ItemHit", GLASS, new PinnedCardState.Rect(50, 200, 90, 212)); }
        void resultChip() throws Exception { hit("cardHits", "CardHit", new SlotCard(), GLASS, new PinnedCardState.Rect(50, 250, 90, 262)); }
        void sourceChip() throws Exception { hit("chipHits", "ChipHit", new Evidence.NavigationTarget(SCOPE, "provider", "source", "target"), 320, 60, 360, 72); }

        void mouse(int x, int y) throws Exception { set("drawMouseX", x); set("drawMouseY", y); }
        boolean key(int code) { return screen.keyPressed(code, 0, 0); }

        void focus(String editor) throws Exception {
            var field = AssistantScreen.class.getDeclaredField(editor);
            field.setAccessible(true);
            screen.setFocused((net.minecraft.client.gui.components.events.GuiEventListener) field.get(screen));
        }
    }

    @Test
    void expandedPanelTakesPriorityOverCoveredItemsAndNavigation() throws Exception {
        for (boolean foreign : List.of(false, true)) for (boolean item : List.of(false, true)) {
            var h = new Harness(true, foreign);
            h.sourceChip();
            h.hit("itemHits", "ItemHit", GLASS, new PinnedCardState.Rect(320, 60, 360, 72));
            var coveredMouse = new int[2];
            var card = new RecipeCard() {
                @Override public int width() { return 40; }
                @Override public int height() { return 30; }
                @Override public void draw(net.minecraft.client.gui.GuiGraphics graphics, int x, int y, int mouseX, int mouseY) { coveredMouse[0] = mouseX; coveredMouse[1] = mouseY; }
                @Override public Optional<Hover> hoverAt(int x, int y, double mouseX, double mouseY) { return Optional.empty(); }
                @Override public boolean overlayContains(double mouseX, double mouseY) { return mouseX >= 320 && mouseX < 360 && mouseY >= 60 && mouseY < 72; }
                @Override public ItemStack itemAt(int x, int y, double mouseX, double mouseY) { return item ? new ItemStack(Items.FURNACE) : ItemStack.EMPTY; }
            };
            var field = AssistantScreen.class.getDeclaredField("catalystPanel");
            field.setAccessible(true);
            var constructor = field.getType().getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            field.set(h.screen, constructor.newInstance(card, null, new PinnedCardState.Rect(50, 60, 90, 90)));
            h.mouse(330, 66);
            var draw = AssistantScreen.class.getDeclaredMethod("drawInlineLines", net.minecraft.client.gui.GuiGraphics.class,
                    AssistantScreen.MessageLayout.class, int.class, int.class, ChatContracts.Turn.class);
            draw.setAccessible(true);
            draw.invoke(h.screen, null, new AssistantScreen.MessageLayout(List.of(new AssistantScreen.MessageRun(
                    null, 0, 0, 40, false, card, null, 30)), 30), 60, 320, null);
            assertEquals(-1, coveredMouse[0]);
            assertEquals(-1, coveredMouse[1]);
            var tooltip = AssistantScreen.class.getDeclaredMethod("drawSourceTooltip", net.minecraft.client.gui.GuiGraphics.class, int.class, int.class);
            tooltip.setAccessible(true);
            // A null graphics sentinel proves the covered navigation tooltip is not rendered.
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> tooltip.invoke(h.screen, null, 330, 66));
            assertTrue(h.screen.mouseClicked(330, 66, 0));
            assertTrue(h.screen.mouseClicked(330, 66, 1));
            assertTrue(h.screen.mouseDragged(330, 66, 0, 4, 4));
            assertTrue(h.screen.mouseReleased(330, 66, 0));
            h.set("scrollOffset", 100);
            assertTrue(h.screen.mouseScrolled(330, 66, 0, -1));
            var offset = AssistantScreen.class.getDeclaredField("scrollOffset");
            offset.setAccessible(true);
            assertEquals(100, offset.getInt(h.screen));
            h.mouse(330, 66);
            for (int key : new int[] {GLFW.GLFW_KEY_R, GLFW.GLFW_KEY_U, GLFW.GLFW_KEY_A}) h.key(key);
            assertEquals(List.of(), h.actions.navigated);
            assertEquals(!foreign && item ? List.of("recipes:" + Items.FURNACE, "uses:" + Items.FURNACE,
                    "recipes:" + Items.FURNACE, "uses:" + Items.FURNACE, "bookmark:" + Items.FURNACE) : List.of(), h.viewer.calls);
        }
    }

    @Test
    void clippedStripsOnlyReceiveThePointerInsideTheViewport() throws Exception {
        var h = new Harness(true);
        var pointer = new int[2];
        var open = new boolean[1];
        var card = new RecipeCard() {
            @Override public int width() { return 40; }
            @Override public int height() { return 54; }
            @Override public void draw(net.minecraft.client.gui.GuiGraphics graphics, int x, int y, int mouseX, int mouseY) { }
            @Override public Optional<Hover> hoverAt(int x, int y, double mouseX, double mouseY) { return Optional.empty(); }
            @Override public boolean drawOverlay(net.minecraft.client.gui.GuiGraphics graphics, int x, int y, int width, int mouseX, int mouseY) {
                pointer[0] = mouseX; pointer[1] = mouseY;
                open[0] = mouseX >= 0;
                return open[0];
            }
            @Override public boolean overlayContains(double mouseX, double mouseY) { return open[0]; }
        };
        h.hit("cardHits", "CardHit", card, null, new PinnedCardState.Rect(50, 20, 90, 74));
        h.hit("cardHits", "CardHit", card, null, new PinnedCardState.Rect(50, 380, 90, 434));
        var method = AssistantScreen.class.getDeclaredMethod("drawRecipeTooltips", net.minecraft.client.gui.GuiGraphics.class, int.class, int.class);
        method.setAccessible(true);
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var graphics = (net.minecraft.client.gui.GuiGraphics) ((sun.misc.Unsafe) unsafeField.get(null))
                .allocateInstance(net.minecraft.client.gui.GuiGraphics.class);
        var pose = net.minecraft.client.gui.GuiGraphics.class.getDeclaredField("pose");
        pose.setAccessible(true);
        pose.set(graphics, new com.mojang.blaze3d.vertex.PoseStack());
        for (int[] mouse : new int[][] {{55, 30}, {55, 400}, {10, 60}, {430, 60}}) {
            method.invoke(h.screen, graphics, mouse[0], mouse[1]);
            assertEquals(-1000, pointer[0]);
            assertEquals(-1000, pointer[1]);
        }
        method.invoke(h.screen, graphics, 55, 60);
        assertEquals(55, pointer[0]);
        assertEquals(60, pointer[1]);
        var hits = AssistantScreen.class.getDeclaredField("cardHits");
        hits.setAccessible(true);
        ((List<?>) hits.get(h.screen)).clear();
        method.invoke(h.screen, graphics, 55, 60);
        assertFalse(card.overlayContains(55, 60));
    }

    private static final class TextGraphics extends net.minecraft.client.gui.GuiGraphics {
        int textX;
        private TextGraphics() { super(null, null); }
        @Override public int drawString(net.minecraft.client.gui.Font font, net.minecraft.network.chat.Component text, int x, int y, int color, boolean shadow) {
            textX = x;
            return x;
        }
    }

    @Test
    void machineLabelWithoutAnIconHasLeftPadding() throws Exception {
        var h = new Harness(true);
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var graphics = (TextGraphics) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(TextGraphics.class);
        var method = AssistantScreen.class.getDeclaredMethod("drawInlineLines", net.minecraft.client.gui.GuiGraphics.class,
                AssistantScreen.MessageLayout.class, int.class, int.class, ChatContracts.Turn.class);
        method.setAccessible(true);
        // A zero-width clipped chip skips the surface draw; text placement is independent of chip width.
        method.invoke(h.screen, graphics, new AssistantScreen.MessageLayout(List.of(new AssistantScreen.MessageRun(
                new ScreenText.InlineSpan("+4", null, 0, false, false), 0, 0, 0, false, null, null, 14,
                new dev.packsage.client.recipe.RecipeCardSource.Machine(null, List.of(), "+4"), null)), 14), 60, 100, null);
        assertEquals(104, graphics.textX);
    }

    @Test
    void nonItemMachineChipOpensItsRecipe() throws Exception {
        for (ItemStack stack : new ItemStack[] {null, ItemStack.EMPTY}) {
            var h = new Harness(true);
            var opened = new ArrayList<dev.packsage.client.recipe.RecipeCardModel>();
            var model = new dev.packsage.client.recipe.RecipeCardModel(dev.packsage.client.recipe.RecipeCardModel.Category.VIEWER,
                    0, 0, List.of(), "Altar", List.of(), 0, null, "mod:altar|mod:recipe");
            h.screen.setRecipeCardSource(new dev.packsage.client.recipe.RecipeCardSource() {
                @Override public Optional<RecipeCard> card(dev.packsage.client.recipe.RecipeCardModel value) { return Optional.empty(); }
                @Override public boolean open(dev.packsage.client.recipe.RecipeCardModel value) { opened.add(value); return true; }
            });
            h.hit("machineHits", "MachineHit", new dev.packsage.client.recipe.RecipeCardSource.Machine(stack, List.of(), "Altar"),
                    model, new PinnedCardState.Rect(320, 100, 360, 112));
            assertTrue(h.screen.mouseClicked(330, 106, 0));
            assertEquals(List.of(model), opened);
        }
    }

    @Test
    void rOverCardSlotShowsRecipes() throws Exception {
        var h = new Harness(true);
        h.card();
        h.mouse(60, 110);
        assertTrue(h.key(GLFW.GLFW_KEY_R));
        assertEquals(List.of("recipes:" + Items.GLASS), h.viewer.calls);
    }

    @Test
    void uOverChipShowsUses() throws Exception {
        var h = new Harness(true);
        h.itemChip();
        h.mouse(60, 205);
        assertTrue(h.key(GLFW.GLFW_KEY_U));
        assertEquals(List.of("uses:" + Items.GLASS), h.viewer.calls);
    }

    @Test
    void aOverCardSlotTogglesBookmark() throws Exception {
        var h = new Harness(true);
        h.card();
        h.mouse(60, 110);
        assertTrue(h.key(GLFW.GLFW_KEY_A));
        assertEquals(List.of("bookmark:" + Items.GLASS), h.viewer.calls);
    }

    @Test
    void keysAwayFromItemsDoNothing() throws Exception {
        var h = new Harness(true);
        h.card();
        h.mouse(300, 110);
        h.key(GLFW.GLFW_KEY_R);
        assertEquals(List.of(), h.viewer.calls);
    }

    @Test
    void leftClickCardSlotShowsRecipes() throws Exception {
        var h = new Harness(true);
        h.card();
        assertTrue(h.screen.mouseClicked(60, 110, 0));
        assertEquals(List.of("recipes:" + Items.GLASS), h.viewer.calls);
    }

    @Test
    void rightClickCardSlotShowsUses() throws Exception {
        var h = new Harness(true);
        h.card();
        assertTrue(h.screen.mouseClicked(60, 110, 1));
        assertEquals(List.of("uses:" + Items.GLASS), h.viewer.calls);
    }

    @Test
    void resultChipClickShowsRecipes() throws Exception {
        var h = new Harness(true);
        h.resultChip();
        assertTrue(h.screen.mouseClicked(60, 255, 0));
        assertEquals(List.of("recipes:" + Items.GLASS), h.viewer.calls);
    }

    @Test
    void sourceChipClickStillNavigates() throws Exception {
        var h = new Harness(true);
        h.sourceChip();
        assertTrue(h.screen.mouseClicked(330, 66, 0));
        assertEquals(List.of("navigate"), h.actions.navigated);
        assertEquals(List.of(), h.viewer.calls);
    }

    @Test
    void shortcutsIgnoredWhileATextEditorIsFocused() throws Exception {
        for (String editor : List.of("inputEditor", "sessionSearchEditor")) {
            var h = new Harness(true);
            h.card();
            h.mouse(60, 110);
            h.focus(editor);
            for (int key : new int[]{GLFW.GLFW_KEY_R, GLFW.GLFW_KEY_U, GLFW.GLFW_KEY_A}) {
                h.key(key);
                h.screen.charTyped((char) (key + 32), 0);
            }
            assertEquals(List.of(), h.viewer.calls, editor);
            var field = AssistantScreen.class.getDeclaredField(editor);
            field.setAccessible(true);
            assertEquals("rua", ((MultilineEditor) field.get(h.screen)).value(), editor);
        }
    }

    @Test
    void foreignWorldHistoryKeepsClicksAndKeysInert() throws Exception {
        var h = new Harness(true, true);
        h.card();
        h.itemChip();
        h.resultChip();
        assertFalse(h.screen.mouseClicked(60, 110, 0));
        assertFalse(h.screen.mouseClicked(60, 110, 1));
        assertFalse(h.screen.mouseClicked(60, 205, 0));
        assertFalse(h.screen.mouseClicked(60, 255, 0));
        for (int[] position : new int[][] {{60, 110}, {60, 205}, {60, 255}}) {
            h.mouse(position[0], position[1]);
            for (int key : new int[] {GLFW.GLFW_KEY_R, GLFW.GLFW_KEY_U, GLFW.GLFW_KEY_A}) h.key(key);
        }
        assertEquals(List.of(), h.viewer.calls);
    }

    @Test
    void sameWorldSavedHistoryStillOpensRecipes() throws Exception {
        var h = new Harness(true, false);
        h.card();
        assertTrue(h.screen.mouseClicked(60, 110, 0));
        h.mouse(60, 110);
        assertTrue(h.key(GLFW.GLFW_KEY_A));
        assertEquals(List.of("recipes:" + Items.GLASS, "bookmark:" + Items.GLASS), h.viewer.calls);
    }

    @Test
    void noViewerKeepsClicksInert() throws Exception {
        var h = new Harness(false);
        h.card();
        h.itemChip();
        h.resultChip();
        h.mouse(60, 110);
        assertFalse(h.screen.mouseClicked(60, 110, 0));
        assertFalse(h.screen.mouseClicked(60, 205, 0));
        assertFalse(h.screen.mouseClicked(60, 255, 0));
        assertFalse(h.key(GLFW.GLFW_KEY_R));
        assertEquals(List.of(), h.viewer.calls);
    }
}
