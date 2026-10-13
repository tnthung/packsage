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
    void narrowAndWideLayoutsKeepChoicesInsideThePanelAndIgnoreClippedClicks() {
        FakeActions actions = new FakeActions();
        PackSageSettings settings = PackSageSettings.defaults();
        var screen = new AssistantSettingsScreen(settings, temporaryDirectory.resolve("settings.properties"), Component.literal("Settings"), actions, () -> { });
        for (int width : List.of(320, 512, 1280)) {
            screen.width = width;
            screen.height = 240;
            var layout = screen.layout();
            assertTrue(layout.left() >= 0);
            assertTrue(layout.left() + layout.width() <= width);
            assertTrue(layout.width() <= 620);
            assertTrue(layout.contentHeight() > layout.bottom() - layout.top());
            List<AssistantSettingsScreen.Choice> choices = new ArrayList<>(layout.models());
            choices.addAll(layout.efforts());
            for (var choice : choices) {
                assertTrue(choice.width() > 0);
                assertTrue(choice.x() >= layout.left());
                assertTrue(choice.x() + choice.width() <= layout.left() + layout.width());
            }
            var high = layout.efforts().stream().filter(choice -> choice.value().equals("HIGH")).findFirst().orElseThrow();
            assertFalse(screen.mouseClicked(high.x() + 4, high.y() + 4, 0));
            assertEquals(ModelBackend.ReasoningEffort.DEFAULT, settings.reasoningEffort());
        }
    }

    @Test
    void modelAndEffortChoicesSelectDirectlyAndCatalogUpdatesWhileOpen() {
        FakeActions actions = new FakeActions();
        PackSageSettings settings = PackSageSettings.defaults();
        var screen = new AssistantSettingsScreen(settings, temporaryDirectory.resolve("settings.properties"), Component.literal("Settings"), actions, () -> { });
        screen.width = 512;
        screen.height = 240;
        var model = screen.layout().models().get(2);
        screen.mouseScrolled(256, 100, 0, -(model.y() - 100) / 28.0);
        model = screen.layout().models().get(2);
        assertTrue(screen.mouseClicked(model.x() + 10, model.y() + 12, 0));
        assertEquals("second", settings.modelId());
        var high = screen.layout().efforts().stream().filter(choice -> choice.value().equals("HIGH")).findFirst().orElseThrow();
        screen.mouseScrolled(256, 100, 0, -(high.y() - 100) / 28.0);
        high = screen.layout().efforts().stream().filter(choice -> choice.value().equals("HIGH")).findFirst().orElseThrow();
        assertTrue(screen.mouseClicked(high.x() + 10, high.y() + 12, 0));
        assertEquals(ModelBackend.ReasoningEffort.HIGH, settings.reasoningEffort());
        actions.models = List.of(new ModelBackend.ModelInfo("new", "New Model", true, true, 1_000, 100));
        assertEquals(List.of("", "second", "new"), screen.layout().models().stream().map(AssistantSettingsScreen.Choice::value).toList());
        assertEquals(1, screen.layout().models().stream().filter(AssistantSettingsScreen.Choice::selected).count());
        assertTrue(screen.layout().models().get(1).label().startsWith("Unavailable:"));
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
        assertEquals(layout.historyTop() + 20, turns.getY());
        assertEquals(fieldWidth, turns.getWidth());
        assertEquals(30, turns.getHeight());
        assertEquals(layout.left() + 16 + fieldWidth + 16, tokens.getX());
        assertEquals(layout.historyTop() + 20, tokens.getY());
        assertEquals(fieldWidth, tokens.getWidth());
        assertEquals(30, tokens.getHeight());
        assertEquals(layout.left() + 16, sessions.getX());
        assertEquals(layout.historyTop() + 86, sessions.getY());
        assertEquals(layout.width() - 32, sessions.getWidth());
        assertEquals(30, sessions.getHeight());

        int initialModelY = layout.models().getFirst().y();
        int track = layout.bottom() - layout.top();
        int maxScroll = layout.contentHeight() - track;
        int thumb = Math.min(track, Math.max(16, track * track / (track + maxScroll)));
        int scrollbarX = layout.left() + layout.width() - 2;
        assertTrue(maxScroll > 0);
        assertTrue(screen.mouseClicked(scrollbarX, layout.top() + thumb / 2, 0));
        assertTrue(screen.mouseDragged(scrollbarX, layout.bottom(), 0, 0, 0));
        assertTrue(screen.mouseReleased(scrollbarX, layout.bottom(), 0));
        assertTrue(screen.layout().models().getFirst().y() < initialModelY);
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
        int x = layout.left() + layout.width() - 120;
        int y = layout.accountTop() + 30;
        screen.mouseClicked(x, y, 0);
        screen.mouseClicked(x, y, 0);
        actions.state = ChatContracts.SignInState.SIGNED_IN;
        screen.mouseClicked(x, y, 0);
        assertEquals(List.of("signIn", "cancelSignIn", "signOut"), actions.calls);
        assertFalse(screen.mouseClicked(0, 20, 0));
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
            assertEquals(layout.models().getFirst().x(), layout.accountLeft());
            assertEquals(layout.models().getFirst().width(), layout.accountWidth());
            assertTrue(layout.accountLeft() + layout.accountWidth() <= layout.left() + layout.width() - 13);
            assertTrue(layout.accountHeight() <= (layout.width() >= 340 ? 56 : 90));
            int actionX = layout.width() >= 340 ? layout.accountLeft() + layout.accountWidth() - 112 : layout.accountLeft() + 12;
            int actionY = layout.accountTop() + (layout.width() >= 340 ? (layout.accountHeight() - 28) / 2 : 52);
            assertTrue(screen.mouseClicked(actionX + 50, actionY + 14, 0));
            assertEquals(List.of("signIn"), actions.calls);
            assertFalse(screen.mouseClicked(actionX + 105, actionY + 14, 0));
            assertEquals(List.of("signIn"), actions.calls);
        }
    }

    private static final class FakeActions implements ChatActions {
        private ChatContracts.SignInState state = ChatContracts.SignInState.SIGNED_OUT;
        private List<ModelBackend.ModelInfo> models = List.of(new ModelBackend.ModelInfo("first", "First", true, true, 1_000, 100),
                new ModelBackend.ModelInfo("second", "Second", true, true, 1_000, 100));
        private final List<String> calls = new ArrayList<>();

        @Override public ChatContracts.View view() {
            var world = new ChatContracts.WorldIdentity("world", "World");
            return new ChatContracts.View(false, state, false, new ChatContracts.Conversation("chat", world, List.of(),
                    new ChatContracts.Outcome(ChatContracts.OutcomeStatus.ACTIVE, null)),
                    new ChatContracts.Context("", null, new Evidence.Scope("world", "resources", "team", "runtime", 0)), List.of(), List.of());
        }
        @Override public List<ModelBackend.ModelInfo> modelCatalog() { return models; }
        @Override public String providerSelectedModelId() { return models.getFirst().id(); }
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
