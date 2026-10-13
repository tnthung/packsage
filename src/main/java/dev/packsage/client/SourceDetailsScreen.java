package dev.packsage.client;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import dev.packsage.chat.ChatContracts;
import dev.packsage.knowledge.Evidence;
import dev.packsage.model.ModelBackend;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Read-only evidence recorded with an answer; opening it never reads or changes live config. */
final class SourceDetailsScreen extends Screen {
    private final List<String> details;
    private final Runnable returnToChat;
    private final BooleanSupplier pauseState;
    private int scrollOffset;
    private int contentHeight;

    SourceDetailsScreen(Evidence.Source source, ChatContracts.Turn turn, Runnable returnToChat, BooleanSupplier pauseState) {
        super(Component.literal("Config source"));
        details = details(source, turn);
        this.returnToChat = Objects.requireNonNull(returnToChat);
        this.pauseState = Objects.requireNonNull(pauseState);
    }

    static List<String> details(Evidence.Source source, ChatContracts.Turn turn) {
        if (!source.providerId().equals("config") || !turn.sources().contains(source)) {
            throw new IllegalArgumentException("config source must belong to this answer");
        }
        List<String> lines = new ArrayList<>();
        lines.add("Recorded with this answer; current values may differ.");
        lines.add("Observed: " + source.observedAt());
        lines.add("Authority: " + source.authority() + " · Coverage: " + source.coverage());
        lines.add("Source: " + source.sourceHandle());
        source.provenance().forEach(value -> lines.add("Provenance: " + value));
        for (ModelBackend.InputItem item : turn.replayItems()) {
            if (!(item instanceof ModelBackend.ToolResult result) || result.error()) continue;
            try {
                var records = JsonParser.parseString(result.serializedResult()).getAsJsonObject().getAsJsonArray("records");
                if (records == null) continue;
                for (var value : records) {
                    var record = value.getAsJsonObject();
                    var observed = record.getAsJsonObject("source");
                    if (observed == null || !source.providerId().equals(observed.get("providerId").getAsString())
                            || !source.sourceHandle().equals(observed.get("sourceHandle").getAsString())
                            || !source.observedAt().toString().equals(observed.get("observedAt").getAsString())
                            || source.observedGeneration() != observed.get("observedGeneration").getAsLong()
                            || !new Gson().toJsonTree(source.scope()).equals(observed.get("scope"))) continue;
                    var payload = record.getAsJsonObject("payload");
                    if (payload == null) continue;
                    lines.add("");
                    for (String key : List.of("fileName", "key", "savedValue", "savedState", "loadedValue", "loadedState",
                            "comment", "default", "restart", "synchronizedServer", "filePath", "savedError")) {
                        var field = payload.get(key);
                        if (field == null) continue;
                        String label = switch (key) {
                            case "fileName" -> "File";
                            case "savedValue" -> "Saved value";
                            case "savedState" -> "Saved state";
                            case "loadedValue" -> "Loaded value";
                            case "loadedState" -> "Loaded state";
                            case "synchronizedServer" -> "Synchronized server";
                            case "filePath" -> "File path";
                            case "savedError" -> "Saved read error";
                            default -> Character.toUpperCase(key.charAt(0)) + key.substring(1);
                        };
                        lines.add(label + ": " + (field.isJsonPrimitive() ? field.getAsString() : field.toString()));
                    }
                    return List.copyOf(lines);
                }
            } catch (RuntimeException ignored) {
                // Older or malformed replay items do not supply a trustworthy value snapshot.
            }
        }
        lines.add("Value snapshot unavailable in this session.");
        return List.copyOf(lines);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The custom backdrop is drawn before the content.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.flush();
        renderBlurredBackground(partialTick);
        graphics.fill(0, 0, width, height, ModernUi.BACKGROUND);
        int panelWidth = Math.max(1, Math.min(620, width - 24));
        int left = (width - panelWidth) / 2;
        ModernUi.surface(graphics, left, 12, left + 66, 40,
                mouseX >= left && mouseX < left + 66 && mouseY >= 12 && mouseY < 40 ? ModernUi.SURFACE_HOVER : ModernUi.SURFACE);
        graphics.blit(ResourceLocation.fromNamespaceAndPath("packsage", "textures/gui/icons/arrow-left.png"),
                left + 8, 18, 16, 16, 0f, 0f, 64, 64, 64, 64);
        graphics.drawString(font, Component.literal("Back").withStyle(style -> style.withFont(ModernUi.FONT)),
                left + 29, 12 + (28 - font.lineHeight) / 2 + 1, ModernUi.TEXT, false);
        graphics.drawString(font, getTitle().copy().withStyle(style -> style.withFont(ModernUi.BOLD_FONT)),
                left + 82, 22, ModernUi.TEXT, false);
        int bottom = Math.max(51, height - 12);
        graphics.enableScissor(left, 50, left + panelWidth, bottom);
        int y = 58 - scrollOffset;
        for (String detail : details) {
            for (var line : font.split(Component.literal(detail).withStyle(style -> style.withFont(ModernUi.FONT)), Math.max(1, panelWidth - 32))) {
                graphics.drawString(font, line, left + 16, y, ModernUi.TEXT, false);
                y += ModernUi.MESSAGE_LINE_HEIGHT;
            }
            y += 5;
        }
        contentHeight = y + scrollOffset - 50;
        graphics.disableScissor();
        scrollOffset = Math.min(scrollOffset, Math.max(0, contentHeight - (bottom - 50)));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int left = (width - Math.max(1, Math.min(620, width - 24))) / 2;
        if (button == 0 && mouseX >= left && mouseX < left + 66 && mouseY >= 12 && mouseY < 40) {
            onClose();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scrollOffset = Math.max(0, Math.min(Math.max(0, contentHeight - Math.max(1, height - 62)), scrollOffset - (int) (scrollY * 28)));
        return true;
    }

    @Override
    public boolean isPauseScreen() { return pauseState.getAsBoolean(); }

    @Override
    public void onClose() { returnToChat.run(); }
}
