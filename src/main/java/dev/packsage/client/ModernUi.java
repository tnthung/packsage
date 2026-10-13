package dev.packsage.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

final class ModernUi {
    static final int BACKGROUND = 0xff121416;
    static final int SIDEBAR = 0xff181b1f;
    static final int SURFACE = 0xff20252a;
    static final int SURFACE_HOVER = 0xff293039;
    static final int BORDER = 0xff303840;
    static final int TEXT = 0xffedf1f3;
    static final int MUTED = 0xff8e9aa4;
    static final int ACCENT = 0xffb8d6c4;
    static final int ACCENT_DARK = 0xff446251;
    static final int DANGER = 0xffe18b8b;
    static final int SIDEBAR_WIDTH = 224;
    static final int MAX_CONTENT_WIDTH = 720;
    static final ResourceLocation FONT = ResourceLocation.fromNamespaceAndPath("packsage", "ui");

    private ModernUi() { }

    static void surface(GuiGraphics graphics, int left, int top, int right, int bottom, int color) {
        if (right <= left || bottom <= top) return;
        if (right - left < 8 || bottom - top < 8) {
            graphics.fill(left, top, right, bottom, color);
            return;
        }
        graphics.fill(left + 3, top, right - 3, bottom, BORDER);
        graphics.fill(left + 1, top + 1, right - 1, bottom - 1, BORDER);
        graphics.fill(left, top + 3, right, bottom - 3, BORDER);
        graphics.fill(left + 3, top + 1, right - 3, bottom - 1, color);
        graphics.fill(left + 2, top + 2, right - 2, bottom - 2, color);
        graphics.fill(left + 1, top + 3, right - 1, bottom - 3, color);
    }
}
