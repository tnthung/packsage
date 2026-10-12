package dev.packsage.client;

import dev.packsage.chat.PackSageSettings;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.Objects;

public final class AssistantSettingsScreen extends Screen {
    private final PackSageSettings settings;
    private final Path path;
    private EditBox turnsBox;
    private EditBox tokensBox;

    public AssistantSettingsScreen(PackSageSettings settings, Path path) {
        this(settings, path, Component.literal("PackSage Settings"));
    }

    public AssistantSettingsScreen(PackSageSettings settings, Path path, Component title) {
        super(title);
        this.settings = Objects.requireNonNull(settings);
        this.path = Objects.requireNonNull(path);
    }

    @Override
    protected void init() {
        turnsBox = new EditBox(font, 20, 30, 100, 20, Component.literal("Historical turns"));
        turnsBox.setValue(Integer.toString(settings.historyTurns()));
        tokensBox = new EditBox(font, 20, 55, 100, 20, Component.literal("Historical tokens"));
        tokensBox.setValue(Integer.toString(settings.historicalTokens()));
        addRenderableWidget(turnsBox);
        addRenderableWidget(tokensBox);
        addRenderableWidget(Button.builder(Component.literal("Save"), button -> {
            applyFields();
            save();
        }).bounds(130, 30, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Open paused: " + settings.openPaused()), button -> {
            setOpenPaused(!settings.openPaused());
            button.setMessage(Component.literal("Open paused: " + settings.openPaused()));
        }).bounds(210, 30, 130, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Automatic search: " + settings.automaticWebSearch()), button -> {
            setAutomaticWebSearch(!settings.automaticWebSearch());
            button.setMessage(Component.literal("Automatic search: " + settings.automaticWebSearch()));
        }).bounds(210, 55, 160, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    public PackSageSettings settings() { return settings; }
    public void setOpenPaused(boolean value) { settings.setOpenPaused(value); }
    public void setAutomaticWebSearch(boolean value) { settings.setAutomaticWebSearch(value); }
    public boolean setHistoryTurns(int value) { return settings.setHistoryTurns(value); }
    public boolean setHistoricalTokens(int value) { return settings.setHistoricalTokens(value); }
    public void save() { settings.save(path); }
    public void close() { applyFields(); save(); }

    @Override
    public void onClose() {
        applyFields();
        save();
        if (minecraft != null) super.onClose();
    }

    private void applyFields() {
        if (turnsBox != null) {
            try { setHistoryTurns(Integer.parseInt(turnsBox.getValue())); } catch (NumberFormatException ignored) { }
        }
        if (tokensBox != null) {
            try { setHistoricalTokens(Integer.parseInt(tokensBox.getValue())); } catch (NumberFormatException ignored) { }
        }
    }
}
