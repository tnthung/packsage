package dev.packsage.client;

import dev.packsage.chat.ChatActions;
import dev.packsage.chat.ChatContracts;
import dev.packsage.chat.PackSageSettings;
import dev.packsage.model.ModelBackend;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.locale.Language;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class AssistantSettingsScreen extends Screen {
    record Choice(String value, String label, int x, int y, int width, boolean selected) { }
    record Layout(int left, int width, int top, int bottom, int accountTop, int accountHeight,
                  List<Choice> models, List<Choice> efforts, int behaviorTop, int historyTop,
                  boolean historyStacked, int contentHeight) { }

    private final PackSageSettings settings;
    private final Path path;
    private final List<ModelBackend.ModelInfo> models;
    private final String providerModelId;
    private final ChatContracts.AccountInfo account;
    private final ChatActions actions;
    private final Runnable returnToAssistant;
    private MultilineEditor turnsEditor;
    private MultilineEditor tokensEditor;
    private String turnsDraft;
    private String tokensDraft;
    private int scrollOffset;
    private boolean draggingScrollbar;
    private int scrollbarDragOffset;
    private boolean closed;

    public AssistantSettingsScreen(PackSageSettings settings, Path path) {
        this(settings, path, Component.literal("PackSage Settings"));
    }

    public AssistantSettingsScreen(PackSageSettings settings, Path path, Component title) {
        this(settings, path, title, List.of(), null, () -> { });
    }

    public AssistantSettingsScreen(PackSageSettings settings, Path path, Component title,
                                   List<ModelBackend.ModelInfo> models, String providerModelId, Runnable returnToAssistant) {
        this(settings, path, title, models, providerModelId,
                new ChatContracts.AccountInfo(new ModelBackend.ProviderInfo("unknown", "Provider"), ""), null, returnToAssistant);
    }

    public AssistantSettingsScreen(PackSageSettings settings, Path path, Component title,
                                   List<ModelBackend.ModelInfo> models, String providerModelId,
                                   ChatContracts.AccountInfo account, Runnable returnToAssistant) {
        this(settings, path, title, models, providerModelId, account, null, returnToAssistant);
    }

    public AssistantSettingsScreen(PackSageSettings settings, Path path, Component title,
                                   ChatActions actions, Runnable returnToAssistant) {
        this(settings, path, title, List.of(), null, actions.account(), actions, returnToAssistant);
    }

    private AssistantSettingsScreen(PackSageSettings settings, Path path, Component title,
                                    List<ModelBackend.ModelInfo> models, String providerModelId,
                                    ChatContracts.AccountInfo account, ChatActions actions, Runnable returnToAssistant) {
        super(title);
        this.settings = Objects.requireNonNull(settings);
        this.path = Objects.requireNonNull(path);
        this.models = List.copyOf(models);
        this.providerModelId = providerModelId;
        this.account = Objects.requireNonNull(account);
        this.actions = actions;
        this.returnToAssistant = returnToAssistant == null ? () -> { } : returnToAssistant;
        turnsDraft = Integer.toString(settings.historyTurns());
        tokensDraft = Integer.toString(settings.historicalTokens());
    }

    @Override
    protected void init() {
        if (turnsEditor != null) turnsDraft = turnsEditor.value();
        if (tokensEditor != null) tokensDraft = tokensEditor.value();
        turnsEditor = addRenderableWidget(new MultilineEditor(font, 0, 0, 100, 30, 3, false, () -> true));
        tokensEditor = addRenderableWidget(new MultilineEditor(font, 0, 0, 100, 30, 7, false, () -> true));
        turnsEditor.setMessage(Component.literal("History turns"));
        tokensEditor.setMessage(Component.literal("History tokens"));
        turnsEditor.setValue(turnsDraft);
        tokensEditor.setValue(tokensDraft);
    }

    Layout layout() {
        int panelWidth = Math.max(1, Math.min(620, width - 24));
        int left = (width - panelWidth) / 2;
        int rowWidth = Math.max(1, panelWidth - 32);
        int accountHeight = panelWidth >= 340 ? 80 : 114;
        int y = 58 - scrollOffset;
        int accountTop = y;
        y += accountHeight + 36;
        List<ModelBackend.ModelInfo> availableModels = actions == null ? models : actions.modelCatalog();
        String providerModelId = actions == null ? this.providerModelId : actions.providerSelectedModelId();
        List<Choice> modelChoices = new ArrayList<>();
        modelChoices.add(new Choice("", "Provider default" + (providerModelId == null ? "" : " · "
                + availableModels.stream().filter(model -> model.id().equals(providerModelId))
                .map(ModelBackend.ModelInfo::displayName).findFirst().orElse(providerModelId)),
                left + 16, y, rowWidth, settings.modelId() == null));
        y += 28;
        if (settings.modelId() != null && availableModels.stream().noneMatch(model -> model.id().equals(settings.modelId()))) {
            modelChoices.add(new Choice(settings.modelId(), "Unavailable: " + settings.modelId(), left + 16, y, rowWidth, true));
            y += 28;
        }
        for (ModelBackend.ModelInfo model : availableModels) {
            modelChoices.add(new Choice(model.id(), model.displayName(), left + 16, y, rowWidth, model.id().equals(settings.modelId())));
            y += 28;
        }
        y += 32;
        List<Choice> effortChoices = new ArrayList<>();
        int columns = Math.max(1, Math.min(4, (rowWidth + 8) / 120));
        int cellWidth = (rowWidth - (columns - 1) * 8) / columns;
        ModelBackend.ReasoningEffort[] efforts = ModelBackend.ReasoningEffort.values();
        for (int index = 0; index < efforts.length; index++) {
            String label = switch (efforts[index]) {
                case DEFAULT -> "Provider default";
                case XHIGH -> "Extra high";
                default -> efforts[index].name().charAt(0) + efforts[index].name().substring(1).toLowerCase(java.util.Locale.ROOT);
            };
            effortChoices.add(new Choice(efforts[index].name(), label, left + 16 + (index % columns) * (cellWidth + 8),
                    y + (index / columns) * 28, cellWidth, settings.reasoningEffort() == efforts[index]));
        }
        y += ((efforts.length + columns - 1) / columns) * 28 + 36;
        int behaviorTop = y;
        y += 76;
        int historyTop = y;
        boolean stacked = rowWidth < 280;
        y += stacked ? 146 : 82;
        Layout result = new Layout(left, panelWidth, 50, Math.max(51, height - 12), accountTop, accountHeight,
                List.copyOf(modelChoices), List.copyOf(effortChoices), behaviorTop, historyTop, stacked, y + scrollOffset - 50);
        if (turnsEditor != null) {
            int fieldWidth = result.historyStacked() ? rowWidth : (rowWidth - 16) / 2;
            int tokensX = result.historyStacked() ? left + 16 : left + 16 + fieldWidth + 16;
            int tokensY = result.historyTop() + (result.historyStacked() ? 66 : 0);
            turnsEditor.setRectangle(fieldWidth, 30, left + 16, result.historyTop() + 20);
            tokensEditor.setRectangle(fieldWidth, 30, tokensX, tokensY + 20);
            turnsEditor.visible = turnsEditor.getY() < result.bottom() && turnsEditor.getY() + 30 > result.top();
            tokensEditor.visible = tokensEditor.getY() < result.bottom() && tokensEditor.getY() + 30 > result.top();
        }
        return result;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The custom render path applies its glass backdrop before the content.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.flush();
        renderBlurredBackground(partialTick);
        graphics.fill(0, 0, width, height, ModernUi.BACKGROUND);
        Layout layout = layout();
        int maxScroll = Math.max(0, layout.contentHeight() - (layout.bottom() - layout.top()));
        if (scrollOffset > maxScroll) {
            scrollOffset = maxScroll;
            layout = layout();
        }
        ModernUi.surface(graphics, layout.left(), 12, layout.left() + 66, 40,
                mouseX >= layout.left() && mouseX < layout.left() + 66 && mouseY >= 12 && mouseY < 40
                        ? ModernUi.SURFACE_HOVER : ModernUi.SURFACE);
        graphics.blit(ResourceLocation.fromNamespaceAndPath("packsage", "textures/gui/icons/arrow-left.png"),
                layout.left() + 8, 18, 16, 16, 0f, 0f, 64, 64, 64, 64);
        graphics.drawString(font, Component.literal("Back").withStyle(style -> style.withFont(ModernUi.FONT)),
                layout.left() + 29, 12 + (28 - font.lineHeight) / 2 + 1, ModernUi.TEXT, false);
        graphics.drawString(font, Component.literal("Settings").withStyle(style -> style.withFont(ModernUi.BOLD_FONT)),
                layout.left() + 82, 21, ModernUi.TEXT, false);
        graphics.enableScissor(layout.left(), layout.top(), layout.left() + layout.width(), layout.bottom());
        int x = layout.left() + 16;
        int rowWidth = Math.max(1, layout.width() - 32);
        ModernUi.surface(graphics, layout.left(), layout.accountTop(), layout.left() + layout.width(),
                layout.accountTop() + layout.accountHeight(), ModernUi.SURFACE);
        ChatContracts.AccountInfo account = actions == null ? this.account : actions.account();
        ResourceLocation providerIcon = ResourceLocation.tryBuild("packsage", "textures/gui/icons/" + account.provider().id() + ".png");
        if (providerIcon == null || minecraft.getResourceManager().getResource(providerIcon).isEmpty()) {
            providerIcon = ResourceLocation.fromNamespaceAndPath("packsage", "textures/gui/icons/user.png");
        }
        graphics.blit(providerIcon,
                x, layout.accountTop() + 21, 28, 28, 0f, 0f, 64, 64, 64, 64);
        graphics.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(Component.literal(account.provider().displayName())
                        .withStyle(style -> style.withFont(ModernUi.BOLD_FONT)),
                        Math.max(1, rowWidth - 44 - (layout.width() >= 340 ? 124 : 0)))),
                x + 42, layout.accountTop() + 18, ModernUi.TEXT, false);
        ChatContracts.SignInState signIn = actions == null ? ChatContracts.SignInState.SIGNED_OUT : actions.view().signIn();
        String status = switch (signIn) {
            case SIGNED_IN -> account.hint().isBlank() ? "Connected" : account.hint();
            case SIGNING_IN -> "Connecting...";
            case FAILED -> "Connection failed";
            case UNKNOWN -> "Checking connection...";
            default -> "Not connected";
        };
        graphics.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(Component.literal(status).withStyle(style -> style.withFont(ModernUi.FONT)),
                        Math.max(1, rowWidth - 44 - (layout.width() >= 340 ? 124 : 0)))),
                x + 42, layout.accountTop() + 37, signIn == ChatContracts.SignInState.FAILED ? ModernUi.DANGER : ModernUi.MUTED, false);
        if (actions != null) {
            int buttonX = layout.width() >= 340 ? layout.left() + layout.width() - 128 : x;
            int buttonY = layout.accountTop() + (layout.width() >= 340 ? 24 : 72);
            ModernUi.surface(graphics, buttonX, buttonY, buttonX + 112, buttonY + 28,
                    mouseX >= buttonX && mouseX < buttonX + 112 && mouseY >= buttonY && mouseY < buttonY + 28
                            ? ModernUi.SURFACE_HOVER : ModernUi.BACKGROUND);
            graphics.drawString(font, Component.literal(switch (signIn) {
                        case SIGNED_IN -> "Sign out";
                        case SIGNING_IN -> "Cancel sign-in";
                        default -> "Sign in";
                    }).withStyle(style -> style.withFont(ModernUi.FONT)), buttonX + 12, buttonY + (28 - font.lineHeight) / 2 + 1, ModernUi.TEXT, false);
        }
        graphics.drawString(font, Component.literal("Model").withStyle(style -> style.withFont(ModernUi.BOLD_FONT)),
                x, layout.models().getFirst().y() - 20, ModernUi.TEXT, false);
        graphics.drawString(font, Component.literal("Reasoning effort").withStyle(style -> style.withFont(ModernUi.BOLD_FONT)),
                x, layout.efforts().getFirst().y() - 20, ModernUi.TEXT, false);
        List<Choice> choices = new ArrayList<>(layout.models());
        choices.addAll(layout.efforts());
        for (Choice choice : choices) {
            ModernUi.surface(graphics, choice.x(), choice.y(), choice.x() + choice.width(), choice.y() + 24,
                    choice.selected() ? ModernUi.ACCENT_DARK
                            : mouseX >= choice.x() && mouseX < choice.x() + choice.width() && mouseY >= choice.y() && mouseY < choice.y() + 24
                            ? ModernUi.SURFACE_HOVER : ModernUi.SURFACE);
            for (int dy = -5; dy <= 5; dy++) {
                int outer = (int) Math.sqrt(25 - dy * dy);
                int inner = Math.abs(dy) <= 3 ? (int) Math.sqrt(9 - dy * dy) : -1;
                graphics.fill(choice.x() + 12 - outer, choice.y() + 12 + dy, choice.x() + 12 - inner, choice.y() + 13 + dy, ModernUi.ACCENT);
                graphics.fill(choice.x() + 13 + inner, choice.y() + 12 + dy, choice.x() + 13 + outer, choice.y() + 13 + dy, ModernUi.ACCENT);
                if (choice.selected() && Math.abs(dy) <= 2) {
                    int selected = (int) Math.sqrt(4 - dy * dy);
                    graphics.fill(choice.x() + 12 - selected, choice.y() + 12 + dy, choice.x() + 13 + selected, choice.y() + 13 + dy, ModernUi.ACCENT);
                }
            }
            graphics.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(Component.literal(choice.label()).withStyle(style -> style.withFont(ModernUi.FONT)),
                    Math.max(1, choice.width() - 34))), choice.x() + 28, choice.y() + (24 - font.lineHeight) / 2 + 1, ModernUi.TEXT, false);
        }
        graphics.drawString(font, Component.literal("Behavior").withStyle(style -> style.withFont(ModernUi.BOLD_FONT)),
                x, layout.behaviorTop() - 20, ModernUi.TEXT, false);
        for (int index = 0; index < 2; index++) {
            boolean checked = index == 0 ? settings.openPaused() : settings.automaticWebSearch();
            int y = layout.behaviorTop() + index * 34;
            ModernUi.surface(graphics, x, y, x + rowWidth, y + 28,
                    mouseX >= x && mouseX < x + rowWidth && mouseY >= y && mouseY < y + 28 ? ModernUi.SURFACE_HOVER : ModernUi.SURFACE);
            ModernUi.surface(graphics, x + 9, y + 7, x + 23, y + 21, checked ? ModernUi.ACCENT_DARK : ModernUi.BACKGROUND);
            if (checked) graphics.blit(ResourceLocation.fromNamespaceAndPath("packsage", "textures/gui/icons/check.png"),
                    x + 10, y + 8, 12, 12, 0f, 0f, 64, 64, 64, 64);
            graphics.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(Component.literal(index == 0 ? "Open paused" : "Automatic web search")
                            .withStyle(style -> style.withFont(ModernUi.FONT)), Math.max(1, rowWidth - 42))),
                    x + 34, y + (28 - font.lineHeight) / 2 + 1, ModernUi.TEXT, false);
        }
        int fieldWidth = layout.historyStacked() ? rowWidth : (rowWidth - 16) / 2;
        int tokensX = layout.historyStacked() ? x : x + fieldWidth + 16;
        int tokensY = layout.historyTop() + (layout.historyStacked() ? 66 : 0);
        graphics.drawString(font, Component.literal("History turns").withStyle(style -> style.withFont(ModernUi.FONT)),
                x, layout.historyTop(), ModernUi.MUTED, false);
        graphics.drawString(font, Component.literal("History tokens").withStyle(style -> style.withFont(ModernUi.FONT)),
                tokensX, tokensY, ModernUi.MUTED, false);
        if (turnsEditor != null) {
            super.render(graphics, mouseX, mouseY, partialTick);
        }
        graphics.disableScissor();
        if (maxScroll > 0) {
            int track = layout.bottom() - layout.top();
            int thumb = Math.min(track, Math.max(16, track * track / (track + maxScroll)));
            int y = layout.top() + scrollOffset * (track - thumb) / maxScroll;
            graphics.fill(layout.left() + layout.width() - 3, y, layout.left() + layout.width(), y + thumb, ModernUi.BORDER);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        Layout layout = layout();
        if (mouseX >= layout.left() && mouseX < layout.left() + 66 && mouseY >= 12 && mouseY < 40) {
            finish();
            return true;
        }
        int maxScroll = Math.max(0, layout.contentHeight() - (layout.bottom() - layout.top()));
        if (maxScroll > 0) {
            int track = layout.bottom() - layout.top();
            int thumb = Math.min(track, Math.max(16, track * track / (track + maxScroll)));
            int thumbTop = layout.top() + scrollOffset * (track - thumb) / maxScroll;
            int scrollbarLeft = layout.left() + layout.width() - 8;
            if (mouseX >= scrollbarLeft && mouseX < layout.left() + layout.width()
                    && mouseY >= thumbTop && mouseY < thumbTop + thumb) {
                draggingScrollbar = true;
                scrollbarDragOffset = (int) mouseY - thumbTop;
                setFocused(null);
                return true;
            }
        }
        if (mouseX < layout.left() || mouseX >= layout.left() + layout.width() || mouseY < layout.top() || mouseY >= layout.bottom()) {
            setFocused(null);
            return false;
        }
        setFocused(null);
        int buttonX = layout.width() >= 340 ? layout.left() + layout.width() - 128 : layout.left() + 16;
        int buttonY = layout.accountTop() + (layout.width() >= 340 ? 24 : 72);
        if (actions != null && mouseX >= buttonX && mouseX < buttonX + 112 && mouseY >= buttonY && mouseY < buttonY + 28) {
            switch (actions.view().signIn()) {
                case SIGNED_IN -> actions.signOut();
                case SIGNING_IN -> actions.cancelSignIn();
                default -> actions.signIn();
            }
            return true;
        }
        for (Choice choice : layout.models()) {
            if (mouseX >= choice.x() && mouseX < choice.x() + choice.width() && mouseY >= choice.y() && mouseY < choice.y() + 24) {
                settings.setModelId(choice.value());
                return true;
            }
        }
        for (Choice choice : layout.efforts()) {
            if (mouseX >= choice.x() && mouseX < choice.x() + choice.width() && mouseY >= choice.y() && mouseY < choice.y() + 24) {
                settings.setReasoningEffort(ModelBackend.ReasoningEffort.valueOf(choice.value()));
                return true;
            }
        }
        if (mouseX >= layout.left() + 16 && mouseX < layout.left() + layout.width() - 16) {
            if (mouseY >= layout.behaviorTop() && mouseY < layout.behaviorTop() + 28) {
                settings.setOpenPaused(!settings.openPaused());
                return true;
            }
            if (mouseY >= layout.behaviorTop() + 34 && mouseY < layout.behaviorTop() + 62) {
                settings.setAutomaticWebSearch(!settings.automaticWebSearch());
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!draggingScrollbar || button != 0) return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        Layout layout = layout();
        int maxScroll = Math.max(0, layout.contentHeight() - (layout.bottom() - layout.top()));
        int track = layout.bottom() - layout.top();
        int thumb = Math.min(track, Math.max(16, track * track / (track + maxScroll)));
        int available = track - thumb;
        if (maxScroll > 0 && available > 0) {
            int thumbTop = Math.max(layout.top(), Math.min(layout.top() + available,
                    (int) mouseY - scrollbarDragOffset));
            scrollOffset = (thumbTop - layout.top()) * maxScroll / available;
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && draggingScrollbar) {
            draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        Layout layout = layout();
        if (mouseX < layout.left() || mouseX >= layout.left() + layout.width() || mouseY < layout.top() || mouseY >= layout.bottom()) return false;
        scrollOffset = Math.max(0, Math.min(Math.max(0, layout.contentHeight() - (layout.bottom() - layout.top())), scrollOffset - (int) (scrollY * 28)));
        return true;
    }

    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        if (turnsEditor != null) turnsDraft = turnsEditor.value();
        if (tokensEditor != null) tokensDraft = tokensEditor.value();
        super.resize(minecraft, width, height);
    }

    @Override
    public boolean isPauseScreen() {
        if (actions == null || minecraft == null) return false;
        var server = minecraft.getSingleplayerServer();
        return actions.view().paused() && minecraft.isSingleplayer() && server != null && !server.isPublished();
    }

    public PackSageSettings settings() { return settings; }
    public void setOpenPaused(boolean value) { settings.setOpenPaused(value); }
    public void setAutomaticWebSearch(boolean value) { settings.setAutomaticWebSearch(value); }
    public boolean setHistoryTurns(int value) { return settings.setHistoryTurns(value); }
    public boolean setHistoricalTokens(int value) { return settings.setHistoricalTokens(value); }
    public void save() { settings.save(path); }
    public void close() { finish(); }
    @Override public void onClose() { finish(); }

    private void finish() {
        if (closed) return;
        closed = true;
        if (turnsEditor != null) {
            try { settings.setHistoryTurns(Integer.parseInt(turnsEditor.value())); } catch (NumberFormatException ignored) { }
        }
        if (tokensEditor != null) {
            try { settings.setHistoricalTokens(Integer.parseInt(tokensEditor.value())); } catch (NumberFormatException ignored) { }
        }
        settings.save(path);
        returnToAssistant.run();
    }
}
