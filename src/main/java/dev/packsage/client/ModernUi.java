package dev.packsage.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

final class ModernUi {
    static final int BACKGROUND = 0xa6121416;
    static final int SIDEBAR = 0x96181b1f;
    static final int SURFACE = 0xb220252a;
    static final int SURFACE_HOVER = 0xd0293039;
    static final int BORDER = 0xb0303840;
    static final int TEXT = 0xffedf1f3;
    static final int MUTED = 0xff8e9aa4;
    static final int ACCENT = 0xffb8d6c4;
    static final int ACCENT_DARK = 0xd0446251;
    static final int DANGER = 0xffe18b8b;
    static final int SIDEBAR_WIDTH = 180;
    static final int MAX_CONTENT_WIDTH = 624;
    static final int MESSAGE_LINE_HEIGHT = 12;
    static final ResourceLocation FONT = ResourceLocation.fromNamespaceAndPath("packsage", "ui");
    static final ResourceLocation BOLD_FONT = ResourceLocation.fromNamespaceAndPath("packsage", "ui_bold");

    private ModernUi() { }

    static void surface(GuiGraphics graphics, int left, int top, int right, int bottom, int color) {
        if (right <= left || bottom <= top) return;
        if (right - left < 8 || bottom - top < 8) {
            graphics.fill(left, top, right, bottom, color);
            return;
        }
        graphics.fill(left + 2, top, right - 2, bottom, color);
        graphics.fill(left, top + 2, left + 2, bottom - 2, color);
        graphics.fill(right - 2, top + 2, right, bottom - 2, color);
        graphics.fill(left + 2, top, right - 2, top + 1, BORDER);
        graphics.fill(left + 2, bottom - 1, right - 2, bottom, BORDER);
        graphics.fill(left, top + 2, left + 1, bottom - 2, BORDER);
        graphics.fill(right - 1, top + 2, right, bottom - 2, BORDER);
    }
}
