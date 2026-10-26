package dev.packsage.client;

import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;

/** Real Minecraft text splitting with deterministic glyph advances, without font atlases or a GPU. */
final class ScreenPreparationFont extends Font {
    long measuredGlyphs;

    ScreenPreparationFont() {
        super(resource -> null, false);
        try {
            var field = Font.class.getDeclaredField("splitter");
            field.setAccessible(true);
            field.set(this, new StringSplitter((codepoint, style) -> {
                measuredGlyphs++;
                return codepoint == ' ' ? 3 : "il.,:!".indexOf(codepoint) >= 0 ? 2 : 6;
            }));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
