package dev.packsage.client;

import dev.packsage.chat.PackSageSettings;
import dev.packsage.model.ModelBackend;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.Objects;
import java.util.List;

public final class AssistantSettingsScreen extends Screen {
    private final PackSageSettings settings;
    private final Path path;
    private final List<ModelBackend.ModelInfo> models;
    private final String providerModelId;
    private final Runnable returnToAssistant;
    private EditBox turnsBox;
    private EditBox tokensBox;

    public AssistantSettingsScreen(PackSageSettings settings, Path path) {
        this(settings, path, Component.literal("PackSage Settings"));
    }

    public AssistantSettingsScreen(PackSageSettings settings, Path path, Component title) {
        this(settings, path, title, List.of(), null, () -> { });
    }

    public AssistantSettingsScreen(PackSageSettings settings, Path path, Component title,
                                   List<ModelBackend.ModelInfo> models, String providerModelId, Runnable returnToAssistant) {
        super(title);
        this.settings = Objects.requireNonNull(settings);
        this.path = Objects.requireNonNull(path);
        this.models = List.copyOf(models);
        this.providerModelId = providerModelId;
        this.returnToAssistant = returnToAssistant == null ? () -> { } : returnToAssistant;
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
        addRenderableWidget(Button.builder(Component.literal(modelLabel()), button -> {
            if (!models.isEmpty()) {
                int selected = 0;
                String current = settings.modelId() == null ? providerModelId : settings.modelId();
                for (int index = 0; index < models.size(); index++) {
                    if (models.get(index).id().equals(current)) selected = (index + 1) % models.size();
                }
                settings.setModelId(models.get(selected).id());
                button.setMessage(Component.literal(modelLabel()));
            }
        }).bounds(20, 85, 350, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Reasoning: " + settings.reasoningEffort().name()), button -> {
            ModelBackend.ReasoningEffort[] values = ModelBackend.ReasoningEffort.values();
            settings.setReasoningEffort(values[(settings.reasoningEffort().ordinal() + 1) % values.length]);
            button.setMessage(Component.literal("Reasoning: " + settings.reasoningEffort().name()));
        }).bounds(20, 110, 350, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), button -> finish()).bounds(20, 140, 70, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    public PackSageSettings settings() { return settings; }
    public void setOpenPaused(boolean value) { settings.setOpenPaused(value); }
    public void setAutomaticWebSearch(boolean value) { settings.setAutomaticWebSearch(value); }
    public boolean setHistoryTurns(int value) { return settings.setHistoryTurns(value); }
    public boolean setHistoricalTokens(int value) { return settings.setHistoricalTokens(value); }
    public void save() { settings.save(path); }
    public void close() { finish(); }

    @Override
    public void onClose() {
        finish();
    }

    private void applyFields() {
        if (turnsBox != null) {
            try { setHistoryTurns(Integer.parseInt(turnsBox.getValue())); } catch (NumberFormatException ignored) { }
        }
        if (tokensBox != null) {
            try { setHistoricalTokens(Integer.parseInt(tokensBox.getValue())); } catch (NumberFormatException ignored) { }
        }
    }

    private String modelLabel() {
        String id = settings.modelId() == null ? providerModelId : settings.modelId();
        return models.stream().filter(model -> model.id().equals(id)).findFirst()
                .map(model -> "Model: " + model.displayName()).orElse("Model: unavailable");
    }

    private void finish() {
        applyFields();
        save();
        returnToAssistant.run();
    }
}
