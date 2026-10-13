package dev.packsage.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** A small custom editor whose display and measurements use the bundled UI font. */
final class MultilineEditor extends AbstractWidget {
    static final int MAX_LENGTH = 1000;
    static final int MAX_HEIGHT = 132;
    private final Font font;
    private final BooleanSupplier submit;
    private final int maxLength;
    private final boolean multiline;
    private final StringBuilder value = new StringBuilder();
    private int cursor;
    private int anchor;
    private int lineOffset;
    private boolean dragging;
    private boolean wrappedLineEnd;
    ResourceLocation leadingIcon;

    MultilineEditor(Font font, int x, int y, int width, int height, BooleanSupplier submit) {
        this(font, x, y, width, height, MAX_LENGTH, true, submit);
    }

    MultilineEditor(Font font, int x, int y, int width, int height, int maxLength, boolean multiline,
                    BooleanSupplier submit) {
        super(x, y, width, height, Component.literal("Ask PackSage"));
        this.font = Objects.requireNonNull(font);
        this.submit = Objects.requireNonNull(submit);
        if (maxLength < 1) throw new IllegalArgumentException("maxLength must be positive");
        this.maxLength = maxLength;
        this.multiline = multiline;
    }

    String value() { return value.toString(); }

    void setValue(String text) {
        Objects.requireNonNull(text);
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        value.setLength(0);
        StringBuilder cleaned = new StringBuilder();
        text.codePoints().filter(codePoint -> !Character.isISOControl(codePoint) || multiline && codePoint == '\n')
                .forEach(cleaned::appendCodePoint);
        int length = Math.min(maxLength, cleaned.length());
        if (length > 0 && length < cleaned.length() && Character.isHighSurrogate(cleaned.charAt(length - 1))) length--;
        value.append(cleaned, 0, length);
        cursor = value.length();
        anchor = cursor;
        wrappedLineEnd = false;
        lineOffset = 0;
    }

    int cursor() { return cursor; }

    int lineOffset() { return lineOffset; }

    int selectionStart() { return Math.min(anchor, cursor); }

    int selectionEnd() { return Math.max(anchor, cursor); }

    List<String> lines() { return wrappedLines().stream().map(WrappedLine::text).toList(); }

    int preferredHeight() {
        return Math.min(MAX_HEIGHT, Math.max(28, wrappedLines().size() * ModernUi.MESSAGE_LINE_HEIGHT + 14));
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ModernUi.surface(graphics, getX(), getY(), getX() + getWidth(), getY() + getHeight(), ModernUi.SURFACE);
        List<WrappedLine> lines = wrappedLines();
        int visible = Math.max(1, (getHeight() - 14) / ModernUi.MESSAGE_LINE_HEIGHT);
        lineOffset = Math.min(Math.max(0, lineOffset), Math.max(0, lines.size() - visible));
        graphics.enableScissor(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1);
        int firstY = getY() + (multiline ? 7 : (getHeight() - font.lineHeight) / 2 + 1);
        if (leadingIcon != null) graphics.blit(leadingIcon, getX() + 8, getY() + (getHeight() - 16) / 2,
                16, 16, 0f, 0f, 64, 64, 64, 64);
        if (value.isEmpty()) graphics.drawString(font, getMessage().copy().withStyle(style -> style.withFont(ModernUi.FONT)),
                getX() + (leadingIcon == null ? 10 : 30), firstY, ModernUi.MUTED, false);
        for (int lineIndex = lineOffset; lineIndex < lines.size() && lineIndex < lineOffset + visible; lineIndex++) {
            WrappedLine line = lines.get(lineIndex);
            int y = firstY + (lineIndex - lineOffset) * ModernUi.MESSAGE_LINE_HEIGHT;
            int start = Math.max(selectionStart(), line.start()) - line.start();
            int end = Math.min(selectionEnd(), line.end()) - line.start();
            if (start < end) {
                int selectionLeft = getX() + (leadingIcon == null ? 10 : 30) + font.width(Component.literal(line.text().substring(0, start))
                        .withStyle(style -> style.withFont(ModernUi.FONT)));
                int selectionRight = getX() + (leadingIcon == null ? 10 : 30) + font.width(Component.literal(line.text().substring(0, end))
                        .withStyle(style -> style.withFont(ModernUi.FONT)));
                graphics.fill(selectionLeft, y - 1, Math.max(selectionLeft + 1, selectionRight), y + 10, 0x665f8294);
            }
            graphics.drawString(font, Component.literal(line.text()).withStyle(style ->
                    style.withFont(ModernUi.FONT).withColor(ModernUi.TEXT)), getX() + (leadingIcon == null ? 10 : 30), y, ModernUi.TEXT, false);
        }
        if (isFocused() && ((System.currentTimeMillis() / 500) & 1) == 0) {
            int lineIndex = cursorLine(lines);
            if (lineIndex >= lineOffset && lineIndex < lineOffset + visible) {
                WrappedLine line = lines.get(lineIndex);
                int column = Math.max(0, Math.min(cursor, line.end()) - line.start());
                int cursorX = getX() + (leadingIcon == null ? 10 : 30) + font.width(Component.literal(line.text().substring(0, column))
                        .withStyle(style -> style.withFont(ModernUi.FONT)));
                int cursorY = firstY + (lineIndex - lineOffset) * ModernUi.MESSAGE_LINE_HEIGHT;
                graphics.fill(cursorX, cursorY - 1, cursorX + 1, cursorY + 10, ModernUi.ACCENT);
            }
        }
        graphics.disableScissor();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!active || !isFocused()) return false;
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        boolean control = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        if (control && keyCode == GLFW.GLFW_KEY_A) {
            anchor = 0;
            cursor = value.length();
            wrappedLineEnd = false;
            ensureCursorVisible();
            return true;
        }
        if (control && keyCode == GLFW.GLFW_KEY_C) {
            if (selectionStart() != selectionEnd()) Minecraft.getInstance().keyboardHandler.setClipboard(
                    value.substring(selectionStart(), selectionEnd()));
            return true;
        }
        if (control && keyCode == GLFW.GLFW_KEY_X) {
            if (selectionStart() != selectionEnd()) {
                Minecraft.getInstance().keyboardHandler.setClipboard(value.substring(selectionStart(), selectionEnd()));
                deleteSelection();
                ensureCursorVisible();
            }
            return true;
        }
        if (control && keyCode == GLFW.GLFW_KEY_V) {
            insert(Minecraft.getInstance().keyboardHandler.getClipboard());
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (!multiline) return true;
            if (shift) {
                insert("\n");
                return true;
            }
            return submit.getAsBoolean();
        }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            if (selectionStart() != selectionEnd()) deleteSelection();
            else if (cursor > 0) {
                int previous = cursor - Character.charCount(value.codePointBefore(cursor));
                value.delete(previous, cursor);
                cursor = previous;
                anchor = cursor;
            }
            wrappedLineEnd = false;
            ensureCursorVisible();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DELETE) {
            if (selectionStart() != selectionEnd()) deleteSelection();
            else if (cursor < value.length()) value.delete(cursor, cursor + Character.charCount(value.codePointAt(cursor)));
            wrappedLineEnd = false;
            ensureCursorVisible();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_LEFT) {
            cursor = !shift && selectionStart() != selectionEnd() ? selectionStart()
                    : control ? previousWord(cursor) : cursor > 0 ? cursor - Character.charCount(value.codePointBefore(cursor)) : 0;
            wrappedLineEnd = false;
            if (!shift) anchor = cursor;
            ensureCursorVisible();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT) {
            cursor = !shift && selectionStart() != selectionEnd() ? selectionEnd()
                    : control ? nextWord(cursor) : cursor < value.length() ? cursor + Character.charCount(value.codePointAt(cursor)) : value.length();
            wrappedLineEnd = false;
            if (!shift) anchor = cursor;
            ensureCursorVisible();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_HOME) {
            if (control) cursor = 0;
            else {
                List<WrappedLine> lines = wrappedLines();
                WrappedLine line = lines.get(cursorLine(lines));
                cursor = line.start();
            }
            wrappedLineEnd = false;
            if (!shift) anchor = cursor;
            ensureCursorVisible();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_END) {
            if (control) {
                cursor = value.length();
                wrappedLineEnd = false;
            }
            else {
                List<WrappedLine> lines = wrappedLines();
                int lineIndex = cursorLine(lines);
                WrappedLine line = lines.get(lineIndex);
                cursor = line.end();
                wrappedLineEnd = lineIndex + 1 < lines.size() && lines.get(lineIndex + 1).start() == cursor;
            }
            if (!shift) anchor = cursor;
            ensureCursorVisible();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) {
            List<WrappedLine> lines = wrappedLines();
            int lineIndex = cursorLine(lines);
            int target = Math.max(0, Math.min(lines.size() - 1, lineIndex + (keyCode == GLFW.GLFW_KEY_UP ? -1 : 1)));
            WrappedLine current = lines.get(lineIndex);
            int column = Math.max(0, cursor - current.start());
            int x = font.width(Component.literal(current.text().substring(0, Math.min(column, current.text().length())))
                    .withStyle(style -> style.withFont(ModernUi.FONT)));
            cursor = indexAtWidth(lines.get(target), x);
            wrappedLineEnd = cursor == lines.get(target).end() && target + 1 < lines.size() && lines.get(target + 1).start() == cursor;
            if (!shift) anchor = cursor;
            ensureCursorVisible();
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (!active || !isFocused() || Character.isISOControl(codePoint)) return false;
        insert(Character.toString(codePoint));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!active || !visible || button != 0 || !isMouseOver(mouseX, mouseY)) return false;
        onClick(mouseX, mouseY, button);
        return true;
    }

    @Override
    public void onClick(double mouseX, double mouseY, int button) {
        setFocused(true);
        dragging = true;
        cursor = indexAtPoint(mouseX, mouseY);
        anchor = cursor;
        ensureCursorVisible();
    }

    @Override
    protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
        if (!dragging) return;
        cursor = indexAtPoint(mouseX, mouseY);
        ensureCursorVisible();
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int visible = Math.max(1, (getHeight() - 14) / ModernUi.MESSAGE_LINE_HEIGHT);
        int amount = (int) scrollY;
        if (amount == 0 && scrollY != 0) amount = (int) Math.signum(scrollY);
        lineOffset = Math.max(0, Math.min(Math.max(0, wrappedLines().size() - visible),
                lineOffset - amount));
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }

    void insert(String text) {
        if (text == null || text.isEmpty()) return;
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder cleaned = new StringBuilder();
        text.codePoints().filter(codePoint -> !Character.isISOControl(codePoint) || multiline && codePoint == '\n')
                .forEach(cleaned::appendCodePoint);
        text = cleaned.toString();
        if (text.isEmpty()) return;
        deleteSelection();
        int available = maxLength - value.length();
        if (available <= 0) return;
        int length = Math.min(available, text.length());
        if (length < text.length() && length > 0 && Character.isHighSurrogate(text.charAt(length - 1))) length--;
        String inserted = text.substring(0, length);
        value.insert(cursor, inserted);
        cursor += inserted.length();
        anchor = cursor;
        wrappedLineEnd = false;
        ensureCursorVisible();
    }

    private void deleteSelection() {
        int start = selectionStart();
        int end = selectionEnd();
        if (start == end) return;
        value.delete(start, end);
        cursor = start;
        anchor = start;
        wrappedLineEnd = false;
    }

    private List<WrappedLine> wrappedLines() {
        List<WrappedLine> lines = new ArrayList<>();
        int start = 0;
        while (true) {
            int newline = value.indexOf("\n", start);
            int hardEnd = newline < 0 ? value.length() : newline;
            if (start == hardEnd) lines.add(new WrappedLine("", start, hardEnd));
            else {
                int offset = start;
                while (offset < hardEnd) {
                    int end = fitEnd(offset, hardEnd);
                    lines.add(new WrappedLine(value.substring(offset, end), offset, end));
                    offset = end;
                }
            }
            if (newline < 0) break;
            start = newline + 1;
        }
        return lines.isEmpty() ? List.of(new WrappedLine("", 0, 0)) : lines;
    }

    private int fitEnd(int start, int hardEnd) {
        int width = Math.max(1, getWidth() - (leadingIcon == null ? 20 : 40));
        int end = start;
        int lastSpace = -1;
        while (end < hardEnd) {
            int next = end + Character.charCount(value.codePointAt(end));
            String candidate = value.substring(start, next);
            if (font.width(Component.literal(candidate).withStyle(style -> style.withFont(ModernUi.FONT))) > width) break;
            if (Character.isWhitespace(value.charAt(end))) lastSpace = next;
            end = next;
        }
        if (end == start) return start + Character.charCount(value.codePointAt(start));
        if (end < hardEnd && lastSpace > start) return lastSpace;
        return end;
    }

    private int cursorLine(List<WrappedLine> lines) {
        for (int index = 0; index < lines.size(); index++) {
            WrappedLine line = lines.get(index);
            if (cursor >= line.start() && cursor <= line.end()) {
                if (cursor == line.end() && !wrappedLineEnd && index + 1 < lines.size() && lines.get(index + 1).start() == cursor) continue;
                return index;
            }
        }
        return Math.max(0, lines.size() - 1);
    }

    private int indexAtPoint(double mouseX, double mouseY) {
        List<WrappedLine> lines = wrappedLines();
        int lineIndex = Math.max(0, Math.min(lines.size() - 1,
                lineOffset + (int) ((mouseY - getY() - (multiline ? 7 : (getHeight() - font.lineHeight) / 2 + 1)) / ModernUi.MESSAGE_LINE_HEIGHT)));
        WrappedLine line = lines.get(lineIndex);
        int index = indexAtWidth(line, Math.max(0, (int) mouseX - getX() - (leadingIcon == null ? 10 : 30)));
        wrappedLineEnd = index == line.end() && lineIndex + 1 < lines.size() && lines.get(lineIndex + 1).start() == index;
        return index;
    }

    private int indexAtWidth(WrappedLine line, int width) {
        int offset = line.start();
        int best = offset;
        while (offset < line.end()) {
            int next = offset + Character.charCount(value.codePointAt(offset));
            int nextWidth = font.width(Component.literal(value.substring(line.start(), next))
                    .withStyle(style -> style.withFont(ModernUi.FONT)));
            if (nextWidth > width) {
                int previousWidth = font.width(Component.literal(value.substring(line.start(), offset))
                        .withStyle(style -> style.withFont(ModernUi.FONT)));
                return width - previousWidth > nextWidth - width ? next : offset;
            }
            best = next;
            offset = next;
        }
        return best;
    }

    private void ensureCursorVisible() {
        int visible = Math.max(1, (getHeight() - 14) / ModernUi.MESSAGE_LINE_HEIGHT);
        List<WrappedLine> lines = wrappedLines();
        int line = cursorLine(lines);
        if (line < lineOffset) lineOffset = line;
        if (line >= lineOffset + visible) lineOffset = line - visible + 1;
        lineOffset = Math.max(0, Math.min(lineOffset, Math.max(0, lines.size() - visible)));
    }

    private int previousWord(int index) {
        int result = index;
        while (result > 0 && Character.isWhitespace(value.charAt(result - 1))) result -= Character.charCount(value.codePointBefore(result));
        while (result > 0 && !Character.isWhitespace(value.charAt(result - 1))) result -= Character.charCount(value.codePointBefore(result));
        return result;
    }

    private int nextWord(int index) {
        int result = index;
        while (result < value.length() && !Character.isWhitespace(value.charAt(result))) result += Character.charCount(value.codePointAt(result));
        while (result < value.length() && Character.isWhitespace(value.charAt(result))) result += Character.charCount(value.codePointAt(result));
        return result;
    }

    private record WrappedLine(String text, int start, int end) { }
}
