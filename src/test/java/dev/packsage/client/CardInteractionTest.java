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
