package dev.packsage.client;

import dev.packsage.chat.ChatActions;
import dev.packsage.chat.ChatContracts;
import dev.packsage.chat.PackSageSettings;
import dev.packsage.knowledge.Evidence;
import dev.packsage.model.ModelBackend;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantSettingsScreenTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void closeSavesSettingsAndReturnsToAssistantOnce() throws Exception {
        PackSageSettings settings = PackSageSettings.defaults();
        AtomicInteger returned = new AtomicInteger();
        AssistantSettingsScreen screen = new AssistantSettingsScreen(settings, temporaryDirectory.resolve("settings.properties"),
                Component.literal("Settings"),
                List.of(new ModelBackend.ModelInfo("model", "Model", true, true, 1_000, 100)), "model", returned::incrementAndGet);
        settings.setModelId("model");
        settings.setReasoningEffort(ModelBackend.ReasoningEffort.HIGH);

        assertTrue(screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0));
        screen.onClose();

        assertEquals(1, returned.get());
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("settings.properties")));
        assertEquals(ModelBackend.ReasoningEffort.HIGH, PackSageSettings.load(temporaryDirectory.resolve("settings.properties")).reasoningEffort());
    }

    @Test
    void narrowAndWideLayoutsKeepAccountAndCompactControlsInsideThePanel() {
        FakeActions actions = new FakeActions();
        PackSageSettings settings = PackSageSettings.defaults();
        var screen = new AssistantSettingsScreen(settings, temporaryDirectory.resolve("settings.properties"), Component.literal("Settings"), actions, () -> { });
        for (int width : List.of(320, 512, 1280)) {
            screen.width = width;
            screen.height = 240;
            var layout = screen.layout();
            assertTrue(layout.left() >= 0);
            assertTrue(layout.left() + layout.width() <= width);
            assertTrue(layout.width() <= 512);
            assertTrue(layout.contentHeight() > layout.bottom() - layout.top());
            assertEquals(layout.left() + 16, layout.accountLeft());
            assertEquals(layout.width() - 32, layout.accountWidth());
            assertEquals(layout.width() >= 340 ? 40 : 68, layout.accountHeight());
            assertEquals(26, layout.shortcutTop() - layout.behaviorTop() - 46);
            assertFalse(screen.mouseClicked(layout.left() + 20, layout.behaviorTop() + 22, 0));
            assertEquals(ModelBackend.ReasoningEffort.DEFAULT, settings.reasoningEffort());
        }
    }

    @Test
    void behaviorControlsUseCompactRowsAndToggleSettings() {
        PackSageSettings settings = PackSageSettings.defaults();
        var screen = new AssistantSettingsScreen(settings, temporaryDirectory.resolve("settings.properties"), Component.literal("Settings"),
                List.of(new ModelBackend.ModelInfo("legacy", "Legacy", true, true, 1_000, 100)), "legacy", () -> { });
        screen.width = 512;
        screen.height = 240;
        var layout = screen.layout();
        assertTrue(screen.mouseClicked(layout.left() + 20, layout.behaviorTop() + 10, 0));
        assertFalse(settings.openPaused());
        assertTrue(screen.mouseClicked(layout.left() + 20, layout.behaviorTop() + 36, 0));
        assertFalse(settings.automaticWebSearch());
        assertTrue(screen.mouseClicked(layout.left() + 20, layout.behaviorTop() + 62, 0));
        assertFalse(settings.hideInactiveAdapters());
    }

    @Test
    void rebuildingSettingsPreservesNumericDraftsAndCloseSavesValidValues() {
        var path = temporaryDirectory.resolve("settings.properties");
        var screen = new AssistantSettingsScreen(PackSageSettings.defaults(), path, Component.literal("Settings"),
                List.of(), null, () -> { });
        screen.width = 512;
        screen.height = 240;
        ScreenTestHost.initialize(screen);
        ((MultilineEditor) screen.children().getFirst()).setValue("37");
        ((MultilineEditor) screen.children().get(1)).setValue("23456");
        ((MultilineEditor) screen.children().get(2)).setValue("5");
        screen.width = 320;
        ScreenTestHost.initialize(screen);
        assertEquals("37", ((MultilineEditor) screen.children().getFirst()).value());
        assertEquals("23456", ((MultilineEditor) screen.children().get(1)).value());
        assertEquals("5", ((MultilineEditor) screen.children().get(2)).value());
        var layout = screen.layout();
        assertTrue(layout.historyStacked());
        assertEquals(layout.historyTop() + 52, ((MultilineEditor) screen.children().get(1)).getY());
        assertEquals(layout.historyTop() + 92, ((MultilineEditor) screen.children().get(2)).getY());
        screen.close();
        assertEquals(37, PackSageSettings.load(path).historyTurns());
        assertEquals(23456, PackSageSettings.load(path).historicalTokens());
        assertEquals(5, PackSageSettings.load(path).sessionsBeforeCollapse());
    }

    @Test
    void numericEditorsUseLogicalBoundsAndScrollbarDragMovesThePage() {
        var screen = new AssistantSettingsScreen(PackSageSettings.defaults(), temporaryDirectory.resolve("settings.properties"),
                Component.literal("Settings"), List.of(), null, () -> { });
        screen.width = 512;
        screen.height = 240;
        ScreenTestHost.initialize(screen);

        var layout = screen.layout();
        var turns = (MultilineEditor) screen.children().getFirst();
        var tokens = (MultilineEditor) screen.children().get(1);
        var sessions = (MultilineEditor) screen.children().get(2);
        int fieldWidth = (layout.width() - 32 - 16) / 2;
        assertEquals(layout.left() + 16, turns.getX());
        assertEquals(layout.historyTop() + 12, turns.getY());
        assertEquals(fieldWidth, turns.getWidth());
        assertEquals(20, turns.getHeight());
        assertEquals(layout.left() + 16 + fieldWidth + 16, tokens.getX());
        assertEquals(layout.historyTop() + 12, tokens.getY());
        assertEquals(fieldWidth, tokens.getWidth());
        assertEquals(20, tokens.getHeight());
        assertEquals(layout.left() + 16, sessions.getX());
        assertEquals(layout.historyTop() + 48, sessions.getY());
        assertEquals(layout.width() - 32, sessions.getWidth());
        assertEquals(20, sessions.getHeight());

        int initialHistoryY = layout.historyTop();
        int track = layout.bottom() - layout.top();
        int maxScroll = layout.contentHeight() - track;
        int thumb = Math.min(track, Math.max(16, track * track / (track + maxScroll)));
        int scrollbarX = layout.left() + layout.width() - 2;
        assertTrue(maxScroll > 0);
        assertTrue(screen.mouseClicked(scrollbarX, layout.top() + thumb / 2, 0));
        assertTrue(screen.mouseDragged(scrollbarX, layout.bottom(), 0, 0, 0));
        assertTrue(screen.mouseReleased(scrollbarX, layout.bottom(), 0));
        assertTrue(screen.layout().historyTop() < initialHistoryY);
    }

    @Test
    void clickingOutsideEditorClearsFocus() {
        var screen = new AssistantSettingsScreen(PackSageSettings.defaults(), temporaryDirectory.resolve("settings.properties"),
                Component.literal("Settings"), List.of(), null, () -> { });
        screen.width = 512;
        screen.height = 240;
        ScreenTestHost.initialize(screen);
        var editor = (MultilineEditor) screen.children().getFirst();
        editor.setFocused(true);
        screen.setFocused(editor);
        assertTrue(editor.isFocused());
        assertFalse(screen.mouseClicked(0, 20, 0));
        assertFalse(editor.isFocused());
    }

    @Test
    void sendShortcutChoiceUsesDedicatedRowsAndPersists() {
        PackSageSettings settings = PackSageSettings.defaults();
        var screen = new AssistantSettingsScreen(settings, temporaryDirectory.resolve("settings.properties"),
                Component.literal("Settings"), List.of(), null, () -> { });
        screen.width = 512;
        screen.height = 1_000;
        var layout = screen.layout();
        assertEquals(PackSageSettings.SendShortcut.ENTER, settings.sendShortcut());
        assertTrue(screen.mouseClicked(layout.left() + 16, layout.shortcutTop() + 40, 0));
        assertEquals(PackSageSettings.SendShortcut.CTRL_ENTER, settings.sendShortcut());
        assertTrue(screen.mouseClicked(layout.left() + 16, layout.shortcutTop() + 10, 0));
        assertEquals(PackSageSettings.SendShortcut.ENTER, settings.sendShortcut());
    }

    @Test
    void accountControlUsesTheCurrentAuthenticationStateAndBackHasBoundedHitArea() {
        FakeActions actions = new FakeActions();
        AtomicInteger returned = new AtomicInteger();
        var screen = new AssistantSettingsScreen(PackSageSettings.defaults(), temporaryDirectory.resolve("settings.properties"),
                Component.literal("Settings"), actions, returned::incrementAndGet);
        screen.width = 512;
        screen.height = 240;
        var layout = screen.layout();
        int x = layout.accountLeft() + layout.accountWidth() - 92 + 12;
        int y = layout.accountTop() + 10;
        screen.mouseClicked(x, y, 0);
        screen.mouseClicked(x, y, 0);
        actions.state = ChatContracts.SignInState.SIGNED_IN;
        screen.mouseClicked(x, y, 0);
        assertEquals(List.of("signIn", "cancelSignIn", "signOut"), actions.calls);
        assertFalse(screen.mouseClicked(0, 20, 0));
        assertFalse(screen.mouseClicked(layout.left() + 52, 20, 0));
        assertEquals(0, returned.get());
        assertTrue(screen.mouseClicked(layout.left() + 10, 20, 0));
        assertEquals(1, returned.get());
    }

    @Test
    void compactAccountCardStaysClearOfScrollbarAndItsActionMatchesBothLayouts() {
        for (int width : List.of(320, 512, 1280)) {
            FakeActions actions = new FakeActions();
            var screen = new AssistantSettingsScreen(PackSageSettings.defaults(), temporaryDirectory.resolve("settings.properties"),
                    Component.literal("Settings"), actions, () -> { });
            screen.width = width;
            screen.height = 240;
            var layout = screen.layout();
            assertEquals(layout.left() + 16, layout.accountLeft());
            assertEquals(layout.width() - 32, layout.accountWidth());
            assertTrue(layout.accountLeft() + layout.accountWidth() <= layout.left() + layout.width() - 13);
            assertEquals(layout.width() >= 340 ? 40 : 68, layout.accountHeight());
            int actionX = layout.width() >= 340 ? layout.accountLeft() + layout.accountWidth() - 92 : layout.accountLeft() + 12;
            int actionY = layout.accountTop() + (layout.width() >= 340 ? 10 : 44);
            assertTrue(screen.mouseClicked(actionX + 40, actionY + 10, 0));
            assertEquals(List.of("signIn"), actions.calls);
            assertFalse(screen.mouseClicked(actionX + 85, actionY + 10, 0));
            assertEquals(List.of("signIn"), actions.calls);
        }
    }

    private static final class FakeActions implements ChatActions {
        private ChatContracts.SignInState state = ChatContracts.SignInState.SIGNED_OUT;
        private final List<String> calls = new ArrayList<>();

        @Override public ChatContracts.View view() {
            var world = new ChatContracts.WorldIdentity("world", "World");
            return new ChatContracts.View(false, state, false, new ChatContracts.Conversation("chat", world, List.of(),
                    new ChatContracts.Outcome(ChatContracts.OutcomeStatus.ACTIVE, null)),
                    new ChatContracts.Context("", null, new Evidence.Scope("world", "resources", "team", "runtime", 0)), List.of(), List.of());
        }
        @Override public List<ModelBackend.ModelInfo> modelCatalog() { return List.of(); }
        @Override public String providerSelectedModelId() { return ""; }
        @Override public ChatContracts.AccountInfo account() {
            return new ChatContracts.AccountInfo(new ModelBackend.ProviderInfo("openai", "OpenAI"), "Connected");
        }
        @Override public void signIn() { calls.add("signIn"); state = ChatContracts.SignInState.SIGNING_IN; }
        @Override public void cancelSignIn() { calls.add("cancelSignIn"); state = ChatContracts.SignInState.SIGNED_OUT; }
        @Override public void signOut() { calls.add("signOut"); state = ChatContracts.SignInState.SIGNED_OUT; }
        @Override public void send(String question, boolean screenshot, boolean search) { }
        @Override public void stop() { }
        @Override public void newChat() { }
        @Override public void setPaused(boolean paused) { }
        @Override public void navigate(Evidence.NavigationTarget target) { }
    }
}
