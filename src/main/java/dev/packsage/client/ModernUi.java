package dev.packsage.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
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
    private static final ResourceLocation SURFACE_FILL = ResourceLocation.fromNamespaceAndPath("packsage", "surface-fill");
    private static final ResourceLocation SURFACE_OUTLINE = ResourceLocation.fromNamespaceAndPath("packsage", "surface-outline");

    private ModernUi() { }

    /** A text button: the surface with its label centered both ways. Hit testing stays with the caller. */
    static void button(GuiGraphics graphics, Font font, String label, int left, int top, int width, int height, int fill, int textColor) {
        surface(graphics, left, top, left + width, top + height, fill);
        Component text = Component.literal(label).withStyle(style -> style.withFont(FONT));
        graphics.drawString(font, text, left + (width - font.width(text)) / 2, top + (height - font.lineHeight) / 2 + 1, textColor, false);
    }

    static boolean hovered(int mouseX, int mouseY, int left, int top, int width, int height) {
        return mouseX >= left && mouseX < left + width && mouseY >= top && mouseY < top + height;
    }

    static void surface(GuiGraphics graphics, int left, int top, int right, int bottom, int color) {
        if (right <= left || bottom <= top) return;
        float[] previousColor = RenderSystem.getShaderColor().clone();
        try {
            graphics.setColor(((color >> 16) & 0xff) / 255f, ((color >> 8) & 0xff) / 255f,
                    (color & 0xff) / 255f, ((color >>> 24) & 0xff) / 255f);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            graphics.blitSprite(SURFACE_FILL, left, top, right - left, bottom - top);
            graphics.setColor(((BORDER >> 16) & 0xff) / 255f, ((BORDER >> 8) & 0xff) / 255f,
                    (BORDER & 0xff) / 255f, ((BORDER >>> 24) & 0xff) / 255f);
            RenderSystem.enableBlend();
            graphics.blitSprite(SURFACE_OUTLINE, left, top, right - left, bottom - top);
        } finally {
            graphics.setColor(previousColor[0], previousColor[1], previousColor[2], previousColor[3]);
            RenderSystem.disableBlend();
        }
    }
}
