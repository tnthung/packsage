package dev.packsage.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
        super(Component.literal(source.providerId().equals("native") ? "Minecraft source"
                : source.providerId().equals("config") ? "Config source" : "Recorded source"));
        details = details(source, turn);
        this.returnToChat = Objects.requireNonNull(returnToChat);
        this.pauseState = Objects.requireNonNull(pauseState);
    }

    static List<String> details(Evidence.Source source, ChatContracts.Turn turn) {
        if (!turn.sources().contains(source)) {
            throw new IllegalArgumentException("source must belong to this answer");
        }
        List<String> lines = new ArrayList<>();
        lines.add("Recorded with this answer; current values may differ.");
        if (source.coverage() == Evidence.Coverage.PARTIAL) lines.add("Coverage is partial; this snapshot may omit information.");
        else if (source.coverage() == Evidence.Coverage.OPAQUE || source.coverage() == Evidence.Coverage.UNAVAILABLE
                || source.coverage() == Evidence.Coverage.ERROR) lines.add("A readable value snapshot is not available for this source.");
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
                    List<String> facts = new ArrayList<>();
                    if (!source.providerId().equals("config") && !source.providerId().equals("native")) {
                        if (record.has("title")) facts.add(record.get("title").getAsString());
                    } else if (payload == null) continue;
                    else if (source.providerId().equals("native")) {
                        if (source.sourceHandle().startsWith("inventory:")) {
                            String itemName = payload.has("name") ? payload.get("name").getAsString()
                                    : payload.has("id") ? ScreenText.displayName(payload.get("id").getAsString()) : null;
                            if (itemName != null) facts.add("Item: " + itemName);
                            if (payload.has("count")) facts.add("Count: " + payload.get("count").getAsString());
                            String section = payload.has("section") ? payload.get("section").getAsString() : null;
                            String slot = payload.has("slot") ? payload.get("slot").getAsString() : null;
                            if (section != null || slot != null) facts.add("Location: "
                                    + (section == null ? "inventory" : ScreenText.displayName(section))
                                    + (slot == null ? "" : " slot " + ("hotbar".equals(section)
                                    ? Integer.parseInt(slot) + 1 + " (recorded index " + slot + ")" : slot)));
                        } else {
                            for (String key : List.of("outputs", "inputs")) {
                                JsonElement entries = payload.get(key);
                                if ((entries == null || !entries.isJsonArray()) && record.has("recipe")
                                        && record.get("recipe").isJsonObject()) entries = record.getAsJsonObject("recipe").get(key);
                                if (entries == null || !entries.isJsonArray()) continue;
                                facts.add(key.equals("outputs") ? "Outputs:" : "Inputs:");
                                int shown = 0;
                                for (JsonElement entryValue : entries.getAsJsonArray()) {
                                    if (shown++ == 8) { facts.add("… more ingredients"); break; }
                                    JsonObject entry = entryValue.getAsJsonObject();
                                    if (key.equals("inputs") && entry.has("alternatives") && entry.get("alternatives").isJsonArray()) {
                                        JsonArray alternatives = entry.getAsJsonArray("alternatives");
                                        List<String> names = new ArrayList<>();
                                        for (int alternative = 0; alternative < alternatives.size() && alternative < 3; alternative++) {
                                            JsonObject option = alternatives.get(alternative).getAsJsonObject();
                                            if (option.has("comparisonIdentity")) names.add(ScreenText.displayName(option.get("comparisonIdentity").getAsString())
                                                    + " ×" + (option.has("quantity") ? option.get("quantity").getAsString() : "?"));
                                        }
                                        if (!names.isEmpty()) facts.add("  " + String.join(" / ", names)
                                                + (alternatives.size() > 3 ? " +" + (alternatives.size() - 3) + " more alternatives" : ""));
                                    } else {
                                        String identity = entry.has("comparisonIdentity") ? entry.get("comparisonIdentity").getAsString()
                                                : entry.has("type") ? entry.get("type").getAsString() : null;
                                        if (identity != null) facts.add("  " + ScreenText.displayName(identity) + " ×"
                                                + (entry.has("quantity") ? entry.get("quantity").getAsString() : "?"));
                                    }
                                }
                            }
                        }
                    } else {
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
                        facts.add(label + ": " + (field.isJsonPrimitive() ? field.getAsString() : field.toString()));
                    }
                    }
                    lines.addAll(facts);
                    lines.add("Observed: " + source.observedAt());
                    lines.add("Authority: " + source.authority() + " · Coverage: " + source.coverage());
                    lines.add("Source: " + source.sourceHandle());
                    source.provenance().forEach(value -> lines.add("Provenance: " + value));
                    return List.copyOf(lines);
                }
            } catch (RuntimeException ignored) {
                // Older or malformed replay items do not supply a trustworthy value snapshot.
            }
        }
        lines.add("Value snapshot unavailable in this session.");
        lines.add("Observed: " + source.observedAt());
        lines.add("Authority: " + source.authority() + " · Coverage: " + source.coverage());
        lines.add("Source: " + source.sourceHandle());
        source.provenance().forEach(value -> lines.add("Provenance: " + value));
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
                left + 82, 12 + (28 - font.lineHeight) / 2 + 1, ModernUi.TEXT, false);
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
