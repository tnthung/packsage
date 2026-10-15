package dev.packsage.client;

import dev.packsage.chat.ChatActions;
import dev.packsage.chat.ChatContracts;
import dev.packsage.chat.PackSageSettings;
import dev.packsage.knowledge.Evidence;
import dev.packsage.model.GroundingInstructions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeveloperSettingsScreenTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void enterIsConsumedAndResizePreservesMultilineDraft() {
        var screen = new DeveloperSettingsScreen(PackSageSettings.defaults(), temporaryDirectory.resolve("settings.properties"), actions(), () -> { });
        screen.width = 512;
        screen.height = 240;
        ScreenTestHost.initialize(screen);
        var editor = (MultilineEditor) screen.children().getFirst();
        editor.setValue("line one\n世界 🌍");
        assertTrue(editor.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0));
        assertEquals("line one\n世界 🌍", editor.value());
        screen.width = 320;
        ScreenTestHost.initialize(screen);
        assertEquals("line one\n世界 🌍", ((MultilineEditor) screen.children().getFirst()).value());
    }

    @Test
    void restoreDefaultRequiresSaveAndSaveWorksAtNarrowWidth() {
        Path path = temporaryDirectory.resolve("settings.properties");
        PackSageSettings settings = PackSageSettings.defaults();
        settings.setDeveloperPrompt("old prompt");
        AtomicInteger returned = new AtomicInteger();
        var screen = new DeveloperSettingsScreen(settings, path, actions(), returned::incrementAndGet);
        screen.width = 320;
        screen.height = 240;
        ScreenTestHost.initialize(screen);
        int y = screen.height - 25;
        screen.mouseClicked(35, y, 0);
        assertEquals(GroundingInstructions.defaultInstructions(), ((MultilineEditor) screen.children().getFirst()).value());
        assertEquals("old prompt", settings.developerPrompt());
        screen.mouseClicked(278, y, 0);
        assertEquals(null, settings.developerPrompt());
        assertEquals(1, returned.get());
        assertEquals(null, PackSageSettings.load(path).developerPrompt());
    }

    @Test
    void cancelAndInvalidOrFailedSaveKeepTheSavedPrompt() {
        PackSageSettings settings = PackSageSettings.defaults();
        settings.setDeveloperPrompt("saved prompt");
        AtomicInteger blankReturned = new AtomicInteger();
        var blank = new DeveloperSettingsScreen(settings, temporaryDirectory.resolve("settings.properties"), actions(), blankReturned::incrementAndGet);
        blank.width = 320;
        blank.height = 240;
        ScreenTestHost.initialize(blank);
        ((MultilineEditor) blank.children().getFirst()).setValue(" \n");
        blank.mouseClicked(278, 215, 0);
        assertEquals("saved prompt", settings.developerPrompt());
        assertEquals(1, blank.children().size());
        assertEquals(0, blankReturned.get());
        blank.mouseClicked(208, 215, 0);
        assertEquals("saved prompt", settings.developerPrompt());

        AtomicInteger failedReturned = new AtomicInteger();
        var failed = new DeveloperSettingsScreen(settings, temporaryDirectory, actions(), failedReturned::incrementAndGet);
        failed.width = 320;
        failed.height = 240;
        ScreenTestHost.initialize(failed);
        ((MultilineEditor) failed.children().getFirst()).setValue("unsaved prompt");
        failed.mouseClicked(278, 215, 0);
        assertEquals("saved prompt", settings.developerPrompt());
        assertEquals(1, failed.children().size());
        assertEquals(0, failedReturned.get());

        AtomicInteger cancelledReturned = new AtomicInteger();
        var cancelled = new DeveloperSettingsScreen(settings, temporaryDirectory.resolve("cancel.properties"), actions(), cancelledReturned::incrementAndGet);
        cancelled.width = 320;
        cancelled.height = 240;
        ScreenTestHost.initialize(cancelled);
        ((MultilineEditor) cancelled.children().getFirst()).setValue("draft");
        cancelled.mouseClicked(208, 215, 0);
        assertEquals("saved prompt", settings.developerPrompt());
        assertEquals(1, cancelledReturned.get());
    }

    private static ChatActions actions() {
        return new ChatActions() {
            @Override public ChatContracts.View view() {
                var world = new ChatContracts.WorldIdentity("world", "World");
                return new ChatContracts.View(false, ChatContracts.SignInState.SIGNED_OUT, false,
                        new ChatContracts.Conversation("chat", world, List.of(),
                                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.ACTIVE, null)),
                        new ChatContracts.Context("", null, new Evidence.Scope("world", "resources", "team", "runtime", 0)), List.of(), List.of());
            }
            @Override public void signIn() { }
            @Override public void cancelSignIn() { }
            @Override public void signOut() { }
            @Override public void send(String question, boolean screenshot, boolean search) { }
            @Override public void stop() { }
            @Override public void newChat() { }
            @Override public void setPaused(boolean paused) { }
            @Override public void navigate(Evidence.NavigationTarget target) { }
        };
    }
}
