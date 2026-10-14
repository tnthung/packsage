package dev.packsage.client;

import net.minecraft.client.gui.screens.Screen;

/** Initializes widgets for headless state checks; it does not exercise Minecraft rendering. */
final class ScreenTestHost {
    private ScreenTestHost() { }

    static void initialize(Screen screen) {
        try {
            var font = Screen.class.getDeclaredField("font");
            font.setAccessible(true);
            font.set(screen, new MultilineEditorTest.FakeFont());
            var clear = Screen.class.getDeclaredMethod("clearWidgets");
            clear.setAccessible(true);
            clear.invoke(screen);
            var init = Screen.class.getDeclaredMethod("init");
            init.setAccessible(true);
            init.invoke(screen);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
