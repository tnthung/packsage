package dev.packsage.client;

import dev.packsage.chat.ChatActions;
import dev.packsage.world.CommandRunner;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/** The pending command cards in the chat; all layout, drawing and button hit testing stay here. */
final class CommandConfirmationPanel {
    record Row(CommandRunner.Confirmation confirmation, List<FormattedCharSequence> lines, int left, int top, int width, int buttonsY) { }

    private final ChatActions actions;
    private List<Row> rows = List.of();
    private int clipTop;
    private int clipBottom;

    CommandConfirmationPanel(ChatActions actions) { this.actions = actions; }

    List<Row> layout(Font font, int left, int top, int width, int clipTop, int clipBottom) {
        this.clipTop = clipTop;
        this.clipBottom = clipBottom;
        var next = new ArrayList<Row>();
        for (var confirmation : actions.commandConfirmations()) {
            var lines = font.split(Component.literal(confirmation.command()).withStyle(style -> style.withFont(ModernUi.FONT)), Math.max(1, width - 16));
            int buttonsY = top + 36 + lines.size() * ModernUi.MESSAGE_LINE_HEIGHT;
            next.add(new Row(confirmation, lines, left, top, width, buttonsY));
            top = buttonsY + 32;
        }
        rows = List.copyOf(next);
        return rows;
    }

    int render(GuiGraphics graphics, Font font, int left, int top, int width, int clipTop, int clipBottom) {
        layout(font, left, top, width, clipTop, clipBottom);
        for (var row : rows) {
            ModernUi.surface(graphics, row.left(), row.top(), row.left() + row.width(), row.buttonsY() + 26, ModernUi.SURFACE);
            graphics.drawString(font, Component.literal("Run this command? (expires in 60 seconds)")
                    .withStyle(style -> style.withFont(ModernUi.BOLD_FONT)), row.left() + 8, row.top() + 7, ModernUi.ACCENT, false);
            graphics.drawString(font, Component.literal("Unpause the world before Run.").withStyle(style -> style.withFont(ModernUi.FONT)),
                    row.left() + 8, row.top() + 21, ModernUi.MUTED, false);
            for (int i = 0; i < row.lines().size(); i++) graphics.drawString(font, row.lines().get(i), row.left() + 8,
                    row.top() + 36 + i * ModernUi.MESSAGE_LINE_HEIGHT, ModernUi.TEXT, false);
            ModernUi.surface(graphics, row.left() + 8, row.buttonsY(), row.left() + 64, row.buttonsY() + 20, ModernUi.ACCENT_DARK);
            ModernUi.surface(graphics, row.left() + 72, row.buttonsY(), row.left() + 136, row.buttonsY() + 20, ModernUi.SURFACE_HOVER);
            graphics.drawString(font, Component.literal("Run").withStyle(style -> style.withFont(ModernUi.FONT)),
                    row.left() + 22, row.buttonsY() + 6, ModernUi.TEXT, false);
            graphics.drawString(font, Component.literal("Cancel").withStyle(style -> style.withFont(ModernUi.FONT)),
                    row.left() + 82, row.buttonsY() + 6, ModernUi.TEXT, false);
        }
        return rows.isEmpty() ? top : rows.getLast().buttonsY() + 32;
    }

    boolean mouseClicked(double x, double y, int button) {
        if (button != 0 || y < clipTop || y >= clipBottom) return false;
        for (var row : rows) {
            if (y < row.buttonsY() || y >= row.buttonsY() + 20) continue;
            if (x >= row.left() + 8 && x < row.left() + 64) {
                actions.confirmCommand(row.confirmation().id(), true);
                return true;
            }
            if (x >= row.left() + 72 && x < row.left() + 136) {
                actions.confirmCommand(row.confirmation().id(), false);
                return true;
            }
        }
        return false;
    }
}
