package dev.packsage.client;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultilineEditorTest {
    private static final Font FONT = new FakeFont();

    @Test
    void wrappingAndTrailingNewlinesHaveStableRows() {
        MultilineEditor editor = new MultilineEditor(FONT, 0, 0, 40, 42, () -> true);
        editor.setValue("abcd\n");
        assertEquals(List.of("abcd", ""), editor.lines());
        editor.setValue("a\n\n");
        assertEquals(List.of("a", "", ""), editor.lines());
    }

    @Test
    void limitsPasteAndSetValueAtCodePointBoundaries() {
        MultilineEditor editor = new MultilineEditor(FONT, 0, 0, 100, 42, 3, true, () -> true);
        editor.setValue("ab😀");
        assertEquals("ab", editor.value());
        editor.insert("😀c");
        assertEquals("ab", editor.value());
    }

    @Test
    void singleLineInputStripsNewlinesAndControls() {
        MultilineEditor editor = new MultilineEditor(FONT, 0, 0, 100, 42, 20, false, () -> true);
        editor.setValue("a\nb\u0001c");
        assertEquals("abc", editor.value());
        editor.insert("d\ne");
        assertEquals("abcde", editor.value());
    }

    @Test
    void selectionNavigationCollapsesBeforeMoving() {
        MultilineEditor editor = new MultilineEditor(FONT, 0, 0, 100, 32, () -> true);
        editor.setValue("one two three four five six");
        editor.setFocused(true);
        editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_A, 0, org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL);
        editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT, 0, 0);
        assertEquals(0, editor.cursor());
        editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_A, 0, org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL);
        editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT, 0, 0);
        assertEquals(editor.value().length(), editor.cursor());
    }

    @Test
    void wrappedMouseAndVerticalKeyboardPositionsMatchMeasuredRows() {
        MultilineEditor editor = new MultilineEditor(FONT, 0, 0, 40, 42, () -> true);
        editor.setValue("abcdefghij");
        assertEquals(List.of("abcd", "efgh", "ij"), editor.lines());
        editor.onClick(21, 27, 0);
        assertEquals(6, editor.cursor());
        editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN, 0, 0);
        assertEquals(10, editor.cursor());
        editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_UP, 0, 0);
        assertEquals(6, editor.cursor());
        editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_END, 0, 0);
        assertEquals(8, editor.cursor());
        editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_HOME, 0, org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT);
        editor.insert("Z");
        assertEquals("abcdZij", editor.value());
    }

    @Test
    void wrappedBoundaryClicksAndEndKeepTheirVisualRow() {
        MultilineEditor editor = new MultilineEditor(FONT, 0, 0, 40, 60, () -> true);
        editor.setValue("abcdefghij");
        editor.onClick(10, 27, 0);
        assertEquals(4, editor.cursor());
        editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN, 0, 0);
        assertEquals(8, editor.cursor());
        editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_UP, 0, 0);
        assertEquals(4, editor.cursor());
        editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_END, 0, 0);
        assertEquals(8, editor.cursor());
        editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_HOME, 0, 0);
        assertEquals(4, editor.cursor());
    }

    @Test
    void growthIsBoundedAndManualScrollChangesTheVisibleClickTarget() {
        MultilineEditor editor = new MultilineEditor(FONT, 0, 0, 40, 32, () -> true);
        editor.setValue("x\n".repeat(40));
        assertEquals(132, editor.preferredHeight());
        editor.mouseScrolled(10, 7, 0, -5);
        assertEquals(5, editor.lineOffset());
        editor.onClick(10, 7, 0);
        assertEquals(10, editor.cursor());
        assertEquals(5, editor.lineOffset());
    }

    @Test
    void enterSubmitsAndShiftEnterInsertsANewline() {
        java.util.concurrent.atomic.AtomicInteger submitted = new java.util.concurrent.atomic.AtomicInteger();
        MultilineEditor editor = new MultilineEditor(FONT, 0, 0, 100, 42, () -> { submitted.incrementAndGet(); return true; });
        editor.setFocused(true);
        editor.charTyped('a', 0);
        editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT);
        editor.charTyped('b', 0);
        assertEquals("a\nb", editor.value());
        assertEquals(0, submitted.get());
        editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0);
        assertEquals(1, submitted.get());
    }

    @Test
    void controlEnterModeKeepsPlainEnterAndShiftEnterAsNewlines() {
        java.util.concurrent.atomic.AtomicInteger submitted = new java.util.concurrent.atomic.AtomicInteger();
        MultilineEditor editor = new MultilineEditor(FONT, 0, 0, 100, 42, () -> { submitted.incrementAndGet(); return true; });
        editor.controlEnter = () -> true;
        editor.setFocused(true);
        editor.charTyped('a', 0);
        editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0);
        editor.charTyped('b', 0);
        editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT);
        assertEquals("a\nb\n", editor.value());
        assertEquals(0, submitted.get());
        editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL);
        assertEquals(1, submitted.get());
    }

    @Test
    void leadingIconReservesSpaceForTextAndCaretAndOneLineFitsTheSendHeight() {
        MultilineEditor editor = new MultilineEditor(FONT, 0, 0, 60, 28, 80, false, () -> true);
        editor.leadingIcon = ResourceLocation.fromNamespaceAndPath("packsage", "textures/gui/icons/magnifying-glass.png");
        editor.setValue("abcdefgh");
        assertEquals(List.of("abcd", "efgh"), editor.lines());
        editor.onClick(30, 10, 0);
        assertEquals(0, editor.cursor());
        editor.onClick(45, 10, 0);
        assertEquals(3, editor.cursor());
        editor.setValue("");
        assertEquals(28, editor.preferredHeight());
    }

    static final class FakeFont extends Font {
        FakeFont() {
            super(resource -> null, false);
        }

        @Override
        public int width(FormattedText text) {
            final int[] width = {0};
            text.visit((style, value) -> {
                assertTrue(ModernUi.FONT.equals(style.getFont()) || ModernUi.BOLD_FONT.equals(style.getFont()));
                assertFalse(style.isBold());
                width[0] += value.codePoints().count() * 5;
                return java.util.Optional.empty();
            }, Style.EMPTY);
            return width[0];
        }

        @Override
        public FormattedText substrByWidth(FormattedText text, int width) {
            String value = text.getString();
            return FormattedText.of(value.substring(0, value.offsetByCodePoints(0, Math.min(value.codePointCount(0, value.length()), Math.max(0, width / 5)))));
        }
    }
}
