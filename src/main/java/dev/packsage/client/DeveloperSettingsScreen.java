package dev.packsage.client;

import dev.packsage.chat.PackSageSettings;
import dev.packsage.model.GroundingInstructions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.Objects;

public final class DeveloperSettingsScreen extends Screen {
    private final PackSageSettings settings;
    private final Path path;
    private final Runnable returnToAssistant;
    private MultilineEditor editor;
    private String draft;
    private String error;

    public DeveloperSettingsScreen(PackSageSettings settings, Path path, Runnable returnToAssistant) {
        super(Component.literal("Developer Settings"));
        this.settings = Objects.requireNonNull(settings);
        this.path = Objects.requireNonNull(path);
        this.returnToAssistant = Objects.requireNonNull(returnToAssistant);
        draft = settings.developerPrompt() == null ? GroundingInstructions.defaultInstructions() : settings.developerPrompt();
    }

    @Override
    protected void init() {
        if (editor != null) draft = editor.value();
        int panelWidth = Math.max(1, Math.min(720, width - 32));
        int left = (width - panelWidth) / 2;
        editor = addRenderableWidget(new MultilineEditor(font, left + 12, 58, panelWidth - 24, Math.max(40, height - 112),
                PackSageSettings.MAX_DEVELOPER_PROMPT_LENGTH + 1, true, () -> true));
        editor.setMessage(Component.literal("System prompt"));
        editor.setValue(draft);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) { }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, ModernUi.BACKGROUND);
        int panelWidth = Math.max(1, Math.min(720, width - 32));
        int left = (width - panelWidth) / 2;
        ModernUi.surface(graphics, left, 12, left + 52, 32, ModernUi.SURFACE);
        graphics.drawString(font, Component.literal("Back").withStyle(style -> style.withFont(ModernUi.FONT)), left + 10, 19, ModernUi.TEXT, false);
        graphics.drawString(font, Component.literal("Developer settings").withStyle(style -> style.withFont(ModernUi.BOLD_FONT)), left + 64, 19, ModernUi.TEXT, false);
        super.render(graphics, mouseX, mouseY, partialTick);
        int buttonsY = height - 36;
        String[] labels = {"Restore default", "Cancel", "Save"};
        for (int i = 0; i < labels.length; i++) {
            int x = left + 12 + i * 112;
            ModernUi.surface(graphics, x, buttonsY, x + 104, buttonsY + 22,
                    mouseX >= x && mouseX < x + 104 && mouseY >= buttonsY && mouseY < buttonsY + 22
                            ? ModernUi.SURFACE_HOVER : ModernUi.SURFACE);
            graphics.drawString(font, Component.literal(labels[i]).withStyle(style -> style.withFont(ModernUi.FONT)), x + 8, buttonsY + 7, ModernUi.TEXT, false);
        }
        if (error != null) graphics.drawString(font, Component.literal(error).withStyle(style -> style.withFont(ModernUi.FONT)), left + 12, height - 50, ModernUi.DANGER, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int panelWidth = Math.max(1, Math.min(720, width - 32));
            int left = (width - panelWidth) / 2;
            int buttonsY = height - 36;
            if (mouseX >= left && mouseX < left + 52 && mouseY >= 12 && mouseY < 32) { finish(); return true; }
            if (mouseY >= buttonsY && mouseY < buttonsY + 22) {
                if (mouseX >= left + 12 && mouseX < left + 116) {
                    draft = GroundingInstructions.defaultInstructions();
                    editor.setValue(draft);
                    error = null;
                    return true;
                }
                if (mouseX >= left + 124 && mouseX < left + 228) { finish(); return true; }
                if (mouseX >= left + 236 && mouseX < left + 340) {
                    draft = editor.value();
                    if (draft.isBlank()) { error = "Prompt cannot be blank"; return true; }
                    if (draft.length() > PackSageSettings.MAX_DEVELOPER_PROMPT_LENGTH) { error = "Prompt is too long"; return true; }
                    try {
                        PackSageSettings candidate = settings.withDeveloperPrompt(draft.equals(GroundingInstructions.defaultInstructions()) ? null : draft);
                        candidate.save(path);
                        settings.setDeveloperPrompt(candidate.developerPrompt());
                        finish();
                    } catch (RuntimeException failure) { error = "Could not save settings"; }
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        if (editor != null) draft = editor.value();
        super.resize(minecraft, width, height);
    }

    @Override public void onClose() { finish(); }

    private void finish() {
        draft = editor == null ? draft : editor.value();
        returnToAssistant.run();
    }
}
