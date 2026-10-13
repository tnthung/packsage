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
    record Layout(int left, int width, int top, int bottom, int accountTop, int accountHeight, int accountLeft, int accountWidth,
                  int behaviorTop, int shortcutTop, int historyTop,
                  boolean historyStacked, int contentHeight) { }

    private final PackSageSettings settings;
    private final Path path;
    private final ChatContracts.AccountInfo account;
    private final ChatActions actions;
    private final Runnable returnToAssistant;
    private MultilineEditor turnsEditor;
    private MultilineEditor tokensEditor;
    private MultilineEditor sessionsEditor;
    private String turnsDraft;
    private String tokensDraft;
    private String sessionsDraft;
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
        this(settings, path, title, new ChatContracts.AccountInfo(new ModelBackend.ProviderInfo("unknown", "Provider"), ""),
                null, returnToAssistant);
    }

    public AssistantSettingsScreen(PackSageSettings settings, Path path, Component title,
                                   List<ModelBackend.ModelInfo> models, String providerModelId,
                                   ChatContracts.AccountInfo account, Runnable returnToAssistant) {
        this(settings, path, title, account, null, returnToAssistant);
    }

    public AssistantSettingsScreen(PackSageSettings settings, Path path, Component title,
                                   ChatActions actions, Runnable returnToAssistant) {
        this(settings, path, title, actions.account(), actions, returnToAssistant);
    }

    private AssistantSettingsScreen(PackSageSettings settings, Path path, Component title, ChatContracts.AccountInfo account,
                                    ChatActions actions, Runnable returnToAssistant) {
        super(title);
        this.settings = Objects.requireNonNull(settings);
        this.path = Objects.requireNonNull(path);
        this.account = Objects.requireNonNull(account);
        this.actions = actions;
        this.returnToAssistant = returnToAssistant == null ? () -> { } : returnToAssistant;
        turnsDraft = Integer.toString(settings.historyTurns());
        tokensDraft = Integer.toString(settings.historicalTokens());
        sessionsDraft = Integer.toString(settings.sessionsBeforeCollapse());
    }

    @Override
    protected void init() {
        if (turnsEditor != null) turnsDraft = turnsEditor.value();
        if (tokensEditor != null) tokensDraft = tokensEditor.value();
        if (sessionsEditor != null) sessionsDraft = sessionsEditor.value();
        turnsEditor = addRenderableWidget(new MultilineEditor(font, 0, 0, 100, 20, 3, false, () -> true));
        tokensEditor = addRenderableWidget(new MultilineEditor(font, 0, 0, 100, 20, 7, false, () -> true));
        sessionsEditor = addRenderableWidget(new MultilineEditor(font, 0, 0, 100, 20, 4, false, () -> true));
        turnsEditor.setMessage(Component.literal("History turns"));
        tokensEditor.setMessage(Component.literal("History tokens"));
        sessionsEditor.setMessage(Component.literal("Sessions before Show more"));
        turnsEditor.setValue(turnsDraft);
        tokensEditor.setValue(tokensDraft);
        sessionsEditor.setValue(sessionsDraft);
    }

    Layout layout() {
        int panelWidth = Math.max(1, Math.min(512, width - 24));
        int left = (width - panelWidth) / 2;
        int rowWidth = Math.max(1, panelWidth - 32);
        int accountHeight = panelWidth >= 340 ? 40 : 68;
        int y = 58 - scrollOffset;
        int accountTop = y;
        y += accountHeight + 28;
        int behaviorTop = y;
        y += 46 + 26;
        int shortcutTop = y;
        y += 46 + 6;
        int historyTop = y;
        boolean stacked = rowWidth < 280;
        y += stacked ? 112 : 68;
        Layout result = new Layout(left, panelWidth, 50, Math.max(51, height - 12), accountTop, accountHeight, left + 16, rowWidth,
                behaviorTop, shortcutTop, historyTop, stacked, y + scrollOffset - 50);
        if (turnsEditor != null) {
            int fieldWidth = result.historyStacked() ? rowWidth : (rowWidth - 16) / 2;
            int tokensX = result.historyStacked() ? left + 16 : left + 16 + fieldWidth + 16;
            int tokensY = result.historyTop() + (result.historyStacked() ? 40 : 0);
            turnsEditor.setRectangle(fieldWidth, 20, left + 16, result.historyTop() + 12);
            tokensEditor.setRectangle(fieldWidth, 20, tokensX, tokensY + 12);
            sessionsEditor.setRectangle(rowWidth, 20, left + 16, result.historyTop() + (result.historyStacked() ? 80 : 36) + 12);
            turnsEditor.visible = turnsEditor.getY() < result.bottom() && turnsEditor.getY() + 20 > result.top();
            tokensEditor.visible = tokensEditor.getY() < result.bottom() && tokensEditor.getY() + 20 > result.top();
            sessionsEditor.visible = sessionsEditor.getY() < result.bottom() && sessionsEditor.getY() + 20 > result.top();
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
        ModernUi.surface(graphics, layout.left(), 12, layout.left() + 52, 32,
                mouseX >= layout.left() && mouseX < layout.left() + 52 && mouseY >= 12 && mouseY < 32
                        ? ModernUi.SURFACE_HOVER : ModernUi.SURFACE);
        graphics.blit(ResourceLocation.fromNamespaceAndPath("packsage", "textures/gui/icons/arrow-left.png"),
                layout.left() + 4, 14, 14, 14, 0f, 0f, 64, 64, 64, 64);
        graphics.drawString(font, Component.literal("Back").withStyle(style -> style.withFont(ModernUi.FONT)),
                layout.left() + 23, 12 + (20 - font.lineHeight) / 2 + 1, ModernUi.TEXT, false);
        graphics.drawString(font, Component.literal("Settings").withStyle(style -> style.withFont(ModernUi.BOLD_FONT)),
                layout.left() + 62, 12 + (20 - font.lineHeight) / 2 + 1, ModernUi.TEXT, false);
        graphics.enableScissor(layout.left(), layout.top(), layout.left() + layout.width(), layout.bottom());
        int x = layout.left() + 16;
        int rowWidth = Math.max(1, layout.width() - 32);
        ModernUi.surface(graphics, layout.accountLeft(), layout.accountTop(), layout.accountLeft() + layout.accountWidth(),
                layout.accountTop() + layout.accountHeight(), ModernUi.SURFACE);
        ChatContracts.AccountInfo account = actions == null ? this.account : actions.account();
        ResourceLocation providerIcon = ResourceLocation.tryBuild("packsage", "textures/gui/icons/" + account.provider().id() + ".png");
        if (providerIcon == null || minecraft.getResourceManager().getResource(providerIcon).isEmpty()) {
            providerIcon = ResourceLocation.fromNamespaceAndPath("packsage", "textures/gui/icons/user.png");
        }
        int accountContentHeight = 40;
        int accountTextTop = layout.accountTop() + (accountContentHeight - (ModernUi.MESSAGE_LINE_HEIGHT + font.lineHeight)) / 2;
        graphics.blit(providerIcon,
                layout.accountLeft() + 12, layout.accountTop() + (accountContentHeight - 20) / 2, 20, 20, 0f, 0f, 64, 64, 64, 64);
        graphics.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(Component.literal(account.provider().displayName())
                        .withStyle(style -> style.withFont(ModernUi.BOLD_FONT)),
                        Math.max(1, layout.accountWidth() - 56 - (layout.width() >= 340 ? 92 : 0)))),
                layout.accountLeft() + 48, accountTextTop, ModernUi.TEXT, false);
        ChatContracts.SignInState signIn = actions == null ? ChatContracts.SignInState.SIGNED_OUT : actions.view().signIn();
        String status = switch (signIn) {
            case SIGNED_IN -> account.hint().isBlank() ? "Connected" : account.hint();
            case SIGNING_IN -> "Connecting...";
            case FAILED -> "Connection failed";
            case UNKNOWN -> "Checking connection...";
            default -> "Not connected";
        };
        graphics.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(Component.literal(status).withStyle(style -> style.withFont(ModernUi.FONT)),
                        Math.max(1, layout.accountWidth() - 56 - (layout.width() >= 340 ? 92 : 0)))),
                layout.accountLeft() + 48, accountTextTop + ModernUi.MESSAGE_LINE_HEIGHT, signIn == ChatContracts.SignInState.FAILED ? ModernUi.DANGER : ModernUi.MUTED, false);
        if (actions != null) {
            int buttonX = layout.width() >= 340 ? layout.accountLeft() + layout.accountWidth() - 92 : layout.accountLeft() + 12;
            int buttonY = layout.accountTop() + (layout.width() >= 340 ? (layout.accountHeight() - 20) / 2 : 44);
            ModernUi.surface(graphics, buttonX, buttonY, buttonX + 80, buttonY + 20,
                    mouseX >= buttonX && mouseX < buttonX + 80 && mouseY >= buttonY && mouseY < buttonY + 20
                            ? ModernUi.SURFACE_HOVER : ModernUi.BACKGROUND);
            graphics.drawString(font, Component.literal(switch (signIn) {
                        case SIGNED_IN -> "Sign out";
                        case SIGNING_IN -> "Cancel sign-in";
                        default -> "Sign in";
                    }).withStyle(style -> style.withFont(ModernUi.FONT)), buttonX + 8, buttonY + (20 - font.lineHeight) / 2 + 1, ModernUi.TEXT, false);
        }
        graphics.drawString(font, Component.literal("Behavior").withStyle(style -> style.withFont(ModernUi.BOLD_FONT)),
                x, layout.behaviorTop() - 20, ModernUi.TEXT, false);
        for (int index = 0; index < 2; index++) {
            boolean checked = index == 0 ? settings.openPaused() : settings.automaticWebSearch();
            int y = layout.behaviorTop() + index * 26;
            ModernUi.surface(graphics, x, y, x + rowWidth, y + 20,
                    mouseX >= x && mouseX < x + rowWidth && mouseY >= y && mouseY < y + 20 ? ModernUi.SURFACE_HOVER : ModernUi.SURFACE);
            ModernUi.surface(graphics, x + 7, y + 3, x + 21, y + 17, checked ? ModernUi.ACCENT_DARK : ModernUi.BACKGROUND);
            if (checked) graphics.blit(ResourceLocation.fromNamespaceAndPath("packsage", "textures/gui/icons/check.png"),
                    x + 8, y + 4, 12, 12, 0f, 0f, 64, 64, 64, 64);
            graphics.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(Component.literal(index == 0 ? "Open paused" : "Automatic web search")
                            .withStyle(style -> style.withFont(ModernUi.FONT)), Math.max(1, rowWidth - 42))),
                    x + 30, y + (20 - font.lineHeight) / 2 + 1, ModernUi.TEXT, false);
        }
        graphics.drawString(font, Component.literal("Send shortcut").withStyle(style -> style.withFont(ModernUi.BOLD_FONT)),
                x, layout.shortcutTop() - 20, ModernUi.TEXT, false);
        for (int index = 0; index < 2; index++) {
            PackSageSettings.SendShortcut shortcut = index == 0 ? PackSageSettings.SendShortcut.ENTER : PackSageSettings.SendShortcut.CTRL_ENTER;
            int y = layout.shortcutTop() + index * 26;
            boolean selected = settings.sendShortcut() == shortcut;
            ModernUi.surface(graphics, x, y, x + rowWidth, y + 20,
                    selected ? ModernUi.ACCENT_DARK
                            : mouseX >= x && mouseX < x + rowWidth && mouseY >= y && mouseY < y + 20
                            ? ModernUi.SURFACE_HOVER : ModernUi.SURFACE);
            graphics.blit(ResourceLocation.fromNamespaceAndPath("packsage", "textures/gui/icons/"
                            + (selected ? "radio-button" : "circle") + ".png"),
                    x + 5, y + (20 - 12) / 2, 12, 12, 0f, 0f, 64, 64, 64, 64);
            graphics.drawString(font, Component.literal(index == 0 ? "Enter" : "Ctrl+Enter")
                            .withStyle(style -> style.withFont(ModernUi.FONT)),
                    x + 24, y + (20 - font.lineHeight) / 2 + 1, ModernUi.TEXT, false);
        }
        int fieldWidth = layout.historyStacked() ? rowWidth : (rowWidth - 16) / 2;
        int tokensX = layout.historyStacked() ? x : x + fieldWidth + 16;
        int tokensY = layout.historyTop() + (layout.historyStacked() ? 40 : 0);
        graphics.drawString(font, Component.literal("History turns").withStyle(style -> style.withFont(ModernUi.FONT)),
                x, layout.historyTop(), ModernUi.MUTED, false);
        graphics.drawString(font, Component.literal("History tokens").withStyle(style -> style.withFont(ModernUi.FONT)),
                tokensX, tokensY, ModernUi.MUTED, false);
        int sessionsY = layout.historyTop() + (layout.historyStacked() ? 80 : 36);
        graphics.drawString(font, Component.literal("Sessions before Show more").withStyle(style -> style.withFont(ModernUi.FONT)),
                x, sessionsY, ModernUi.MUTED, false);
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
        if (mouseX >= layout.left() && mouseX < layout.left() + 52 && mouseY >= 12 && mouseY < 32) {
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
        int buttonX = layout.width() >= 340 ? layout.accountLeft() + layout.accountWidth() - 92 : layout.accountLeft() + 12;
        int buttonY = layout.accountTop() + (layout.width() >= 340 ? (layout.accountHeight() - 20) / 2 : 44);
        if (actions != null && mouseX >= buttonX && mouseX < buttonX + 80 && mouseY >= buttonY && mouseY < buttonY + 20) {
            switch (actions.view().signIn()) {
                case SIGNED_IN -> actions.signOut();
                case SIGNING_IN -> actions.cancelSignIn();
                default -> actions.signIn();
            }
            return true;
        }
        if (mouseX >= layout.left() + 16 && mouseX < layout.left() + layout.width() - 16) {
            if (mouseY >= layout.behaviorTop() && mouseY < layout.behaviorTop() + 20) {
                settings.setOpenPaused(!settings.openPaused());
                return true;
            }
            if (mouseY >= layout.behaviorTop() + 26 && mouseY < layout.behaviorTop() + 46) {
                settings.setAutomaticWebSearch(!settings.automaticWebSearch());
                return true;
            }
            if (mouseY >= layout.shortcutTop() && mouseY < layout.shortcutTop() + 20) {
                settings.setSendShortcut(PackSageSettings.SendShortcut.ENTER);
                return true;
            }
            if (mouseY >= layout.shortcutTop() + 26 && mouseY < layout.shortcutTop() + 46) {
                settings.setSendShortcut(PackSageSettings.SendShortcut.CTRL_ENTER);
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
        if (sessionsEditor != null) sessionsDraft = sessionsEditor.value();
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
    public boolean setSessionsBeforeCollapse(int value) { return settings.setSessionsBeforeCollapse(value); }
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
        if (sessionsEditor != null) {
            try { settings.setSessionsBeforeCollapse(Integer.parseInt(sessionsEditor.value())); } catch (NumberFormatException ignored) { }
        }
        settings.save(path);
        returnToAssistant.run();
    }
}
