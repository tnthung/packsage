package dev.packsage.client;

import dev.packsage.chat.ChatActions;
import dev.packsage.chat.ChatContracts;
import dev.packsage.chat.PackSageSettings;
import dev.packsage.knowledge.Evidence;
import dev.packsage.model.ModelBackend;
import dev.packsage.model.GroundingInstructions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.locale.Language;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

public final class AssistantScreen extends Screen {
    public record RuntimeState(boolean privateSingleplayer, boolean integratedServer, boolean published) { }
    public static final int QUESTION_MAX_LENGTH = 1000;
    private final ChatActions actions;
    private final PackSageSettings settings;
    private ChatContracts.View view;
    private boolean awaitingRequestView;
    String pendingQuestion = "";
    private Instant pendingSentAt;
    private String input = "";
    private boolean explicitWebSearch;
    private boolean includeScreenshot = true;
    private MultilineEditor inputEditor;
    private MultilineEditor sessionSearchEditor;
    private String sessionSearch = "";
    private Runnable settingsOpener = () -> { };
    private Runnable settingsSaver = () -> { };
    private RuntimeState runtimeState = new RuntimeState(true, true, false);
    private int scrollOffset;
    private int contentHeight;
    private int viewportHeight = 1;
    private boolean followBottom = true;
    private boolean followOnCompletion;
    private List<ChatContracts.SessionSummary> sessions = List.of();
    private final List<ChipHit> chipHits = new ArrayList<>();
    private final List<SourceHit> sourceHits = new ArrayList<>();
    private final Set<String> expandedWorlds = new HashSet<>();
    private int sidebarScroll;
    private int sidebarContentHeight;
    private long catalogGeneration;
    private int sidebarWidth;
    private int readableLeft;
    private int readableWidth;
    private int composerTop;
    private int composerHeight;
    private int sendX;
    private int sendY;
    private int toggleY;
    private ChatContracts.RequestId editingRequest;
    private ChatContracts.RequestId resendingRequest;
    private String picker;
    private int pickerScroll;
    private String hoveredPickerChoice;
    private long pickerHoverStarted;
    private ChatContracts.SessionSummary pendingDelete;
    private boolean deletingSession;
    private ChatContracts.HistoryPreview history;
    private final List<MessageAction> messageActions = new ArrayList<>();

    public AssistantScreen(ChatActions actions) { this(actions, Component.literal("PackSage Assistant")); }

    public AssistantScreen(ChatActions actions, Component title) {
        super(title);
        this.actions = Objects.requireNonNull(actions);
        this.settings = actions.settings();
        this.view = actions.view();
    }

    @Override
    protected void init() {
        if (inputEditor != null) input = inputEditor.value();
        if (sessionSearchEditor != null) sessionSearch = sessionSearchEditor.value();
        sessionSearchEditor = new MultilineEditor(font, 12, 32, Math.max(1, ModernUi.SIDEBAR_WIDTH - 24), 20,
                80, false, () -> false);
        sessionSearchEditor.setValue(sessionSearch);
        sessionSearchEditor.setMessage(Component.literal("Search sessions"));
        sessionSearchEditor.leadingIcon = ResourceLocation.fromNamespaceAndPath("packsage", "textures/gui/icons/magnifying-glass.png");
        inputEditor = new MultilineEditor(font, 0, 0, 1, 20, QUESTION_MAX_LENGTH, true, () -> {
            submit();
            return true;
        });
        inputEditor.setValue(input);
        inputEditor.controlEnter = () -> settings.sendShortcut() == PackSageSettings.SendShortcut.CTRL_ENTER;
        addRenderableWidget(sessionSearchEditor);
        addRenderableWidget(inputEditor);
        followOnCompletion = true;
        layout();
        history = actions.historyPreview(editingRequest == null ? resendingRequest : editingRequest);
        loadSessions();
    }

    private void loadSessions() {
        long generation = ++catalogGeneration;
        try {
            actions.sessions().thenAccept(values -> {
                Runnable update = () -> {
                    if (generation == catalogGeneration) sessions = List.copyOf(values);
                };
                if (minecraft == null) update.run();
                else minecraft.execute(update);
            });
        } catch (RuntimeException failure) {
            sessions = List.of();
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The custom render path applies its backdrop before the content.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.flush();
        renderBlurredBackground(partialTick);
        layout();
        history = actions.historyPreview(editingRequest == null ? resendingRequest : editingRequest);
        graphics.fill(0, 0, width, height, ModernUi.BACKGROUND);
        graphics.fill(0, 0, sidebarWidth, height, ModernUi.SIDEBAR);
        drawSidebar(graphics, mouseX, mouseY);
        int contentTop = 40;
        int contentBottom = Math.max(contentTop + 1, toggleY - 8);
        graphics.enableScissor(readableLeft, contentTop, readableLeft + readableWidth, contentBottom);
        int contentOrigin = contentTop + 8;
        int y = contentOrigin - scrollOffset;
        y = drawConversation(graphics, y, contentBottom, mouseX, mouseY);
        graphics.disableScissor();
        updateContentMetrics(y + scrollOffset - contentOrigin + 4, contentBottom - contentTop);
        drawComposer(graphics, mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
        drawSendIcon(graphics);
        drawPicker(graphics, mouseX, mouseY);
        drawToolbarTooltip(graphics, mouseX, mouseY);
    }

    void layout() {
        sidebarWidth = Math.min(ModernUi.SIDEBAR_WIDTH, Math.max(100, width / 3));
        int mainWidth = Math.max(1, width - sidebarWidth);
        readableWidth = Math.max(1, Math.min(ModernUi.MAX_CONTENT_WIDTH, mainWidth - 32));
        readableLeft = sidebarWidth + (mainWidth - readableWidth) / 2;
        int toolbarHeight = toolbarItems().stream().filter(item -> !item.kind().equals("context"))
                .mapToInt(ToolbarItem::y).max().orElse(toggleY) - toggleY + 26;
        if (sessionSearchEditor != null) {
            sessionSearchEditor.setSize(Math.max(1, sidebarWidth - 24), 20);
            sessionSearchEditor.setPosition(12, 32);
        }
        if (inputEditor != null) {
            int meterWidth = Math.max(2, Math.min(86, readableWidth)) / 2;
            int editorWidth = Math.max(1, readableWidth - 58 - meterWidth);
            inputEditor.setSize(editorWidth, 20);
            composerHeight = Math.min(Math.min(MultilineEditor.MAX_HEIGHT,
                    Math.min(Math.max(20, height / 3), Math.max(20, height - toolbarHeight - 68))), inputEditor.preferredHeight());
            composerTop = Math.max(40, height - composerHeight - 20);
            inputEditor.setSize(editorWidth, composerHeight);
            inputEditor.setPosition(readableLeft + 32 + meterWidth, composerTop);
            inputEditor.active = !view.readOnly();
        } else {
            composerHeight = 20;
            composerTop = Math.max(40, height - composerHeight - 20);
        }
        toggleY = Math.max(0, composerTop - toolbarHeight);
        sendX = readableLeft + readableWidth - 20;
        sendY = composerTop + composerHeight - 20;
    }

    private void drawSidebar(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, Component.literal("PackSage").withStyle(style ->
                style.withFont(ModernUi.FONT).withColor(ModernUi.TEXT)), 16, 14, ModernUi.TEXT, false);
        ModernUi.surface(graphics, 12, 60, sidebarWidth - 12, 80, ModernUi.ACCENT_DARK);
        graphics.blit(ResourceLocation.fromNamespaceAndPath("packsage", "textures/gui/icons/plus.png"),
                18, 60 + (20 - 12) / 2, 12, 12, 0.0F, 0.0F, 64, 64, 64, 64);
        graphics.drawString(font, Component.literal("New chat").withStyle(style ->
                style.withFont(ModernUi.FONT).withColor(ModernUi.TEXT)), 36, 60 + (20 - font.lineHeight) / 2 + 1, ModernUi.TEXT, false);
        drawSessionGroups(graphics, mouseX, mouseY);
        int controlY = Math.max(0, height - 32);
        ModernUi.surface(graphics, 12, controlY, 32, Math.min(height, controlY + 20),
                mouseX >= 12 && mouseX < 32 && mouseY >= controlY && mouseY < controlY + 20 ? ModernUi.SURFACE_HOVER : ModernUi.SURFACE);
        graphics.blit(ResourceLocation.fromNamespaceAndPath("packsage", "textures/gui/icons/gear.png"),
                16, controlY + 4, 12, 12, 0.0F, 0.0F, 64, 64, 64, 64);
        RuntimeState state = actualRuntimeState();
        if (state.privateSingleplayer() && state.integratedServer() && !state.published()) {
            ModernUi.surface(graphics, 40, controlY, 60, Math.min(height, controlY + 20),
                    mouseX >= 40 && mouseX < 60 && mouseY >= controlY && mouseY < controlY + 20 ? ModernUi.SURFACE_HOVER : ModernUi.SURFACE);
            graphics.blit(ResourceLocation.fromNamespaceAndPath("packsage", effectivePause(state)
                    ? "textures/gui/icons/play.png" : "textures/gui/icons/pause.png"),
                    44, controlY + 4, 12, 12, 0.0F, 0.0F, 64, 64, 64, 64);
        }
    }

    record SessionRow(ChatContracts.WorldIdentity world, ChatContracts.SessionSummary session, String label,
                      int top, int height, boolean expand) { }

    List<SessionRow> sessionRows() {
        String query = (sessionSearchEditor == null ? sessionSearch : sessionSearchEditor.value()).trim().toLowerCase(java.util.Locale.ROOT);
        Map<String, List<ChatContracts.SessionSummary>> groups = new LinkedHashMap<>();
        for (ChatContracts.SessionSummary session : sessions) {
            if (!query.isEmpty() && !session.title().toLowerCase(java.util.Locale.ROOT).contains(query)
                    && !session.lastMessage().toLowerCase(java.util.Locale.ROOT).contains(query)
                    && !session.world().readableLabel().toLowerCase(java.util.Locale.ROOT).contains(query)) continue;
            groups.computeIfAbsent(session.world().worldOrServer(), ignored -> new ArrayList<>()).add(session);
        }
        List<Map.Entry<String, List<ChatContracts.SessionSummary>>> ordered = new ArrayList<>(groups.entrySet());
        ordered.sort((first, second) -> Boolean.compare(!first.getKey().equals(view.activeWorld().worldOrServer()),
                !second.getKey().equals(view.activeWorld().worldOrServer())));
        int y = 108 - sidebarScroll;
        List<SessionRow> rows = new ArrayList<>();
        for (Map.Entry<String, List<ChatContracts.SessionSummary>> group : ordered) {
            ChatContracts.WorldIdentity world = group.getKey().equals(view.activeWorld().worldOrServer())
                    ? view.activeWorld() : group.getValue().getFirst().world();
            rows.add(new SessionRow(world, null, world.readableLabel(), y, 24, false));
            y += 24;
            boolean expanded = expandedWorlds.contains(group.getKey()) || !query.isEmpty();
            int count = expanded ? group.getValue().size() : Math.min(settings.sessionsBeforeCollapse(), group.getValue().size());
            for (int index = 0; index < count; index++) {
                ChatContracts.SessionSummary session = group.getValue().get(index);
                rows.add(new SessionRow(world, session, session.title(), y, 38, false));
                y += 38;
            }
            if (group.getValue().size() > settings.sessionsBeforeCollapse() && query.isEmpty()) {
                rows.add(new SessionRow(world, null, expanded ? "Show less" : "Show more", y, 24, true));
                y += 24;
            }
            y += 8;
        }
        sidebarContentHeight = y + sidebarScroll - 108;
        return rows;
    }

    private void drawSessionGroups(GuiGraphics graphics, int mouseX, int mouseY) {
        List<SessionRow> rows = sessionRows();
        int bottom = Math.max(105, height - 56);
        int maxScroll = Math.max(0, sidebarContentHeight - (bottom - 104));
        if (sidebarScroll > maxScroll) {
            sidebarScroll = maxScroll;
            rows = sessionRows();
        }
        graphics.enableScissor(0, 104, sidebarWidth, bottom);
        for (SessionRow row : rows) {
            if (row.top() + row.height() <= 104 || row.top() >= bottom) continue;
            if (row.session() == null) {
                if (!row.expand()) graphics.blit(ResourceLocation.fromNamespaceAndPath("packsage", "textures/gui/icons/globe.png"),
                        16, row.top() + (row.height() - 14) / 2, 14, 14, 0.0F, 0.0F, 64, 64, 64, 64);
                graphics.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(Component.literal(row.label())
                                .withStyle(style -> style.withFont(ModernUi.FONT)), Math.max(1, sidebarWidth - (row.expand() ? 40 : 52)))),
                        row.expand() ? 24 : 36, row.top() + (row.height() - font.lineHeight) / 2 + 1,
                        row.expand() || row.world().equals(view.activeWorld()) ? ModernUi.ACCENT : ModernUi.MUTED, false);
            } else {
                if (row.session().equals(pendingDelete)) {
                    graphics.drawString(font, Component.literal("Delete session?").withStyle(style -> style.withFont(ModernUi.FONT)),
                            24, row.top() + 1, ModernUi.TEXT, false);
                    for (int index = 0; index < 2; index++) {
                        int buttonWidth = Math.max(1, Math.min(54, (sidebarWidth - 42) / 2));
                        int x = 18 + index * (buttonWidth + 6);
                        ModernUi.surface(graphics, x, row.top() + 15, x + buttonWidth, row.top() + 32,
                                mouseX >= x && mouseX < x + buttonWidth && mouseY >= row.top() + 15 && mouseY < row.top() + 32
                                        ? ModernUi.SURFACE_HOVER : ModernUi.SURFACE);
                        Component label = Component.literal(index == 0 ? "Delete" : "Cancel").withStyle(style -> style.withFont(ModernUi.FONT));
                        graphics.drawString(font, label, x + (buttonWidth - font.width(label)) / 2,
                                row.top() + 15 + (17 - font.lineHeight) / 2 + 1, index == 0 ? ModernUi.DANGER : ModernUi.TEXT, false);
                    }
                    continue;
                }
                boolean selected = row.session().id().equals(view.conversation().id())
                        && row.world().worldOrServer().equals(view.conversation().world().worldOrServer());
                if (selected || mouseX >= 12 && mouseX < sidebarWidth - 12 && mouseY >= row.top() && mouseY < row.top() + 34) {
                    ModernUi.surface(graphics, 12, row.top(), sidebarWidth - 12, row.top() + 34, ModernUi.SURFACE_HOVER);
                }
                graphics.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(Component.literal(row.label().replaceAll("\\s+", " "))
                                .withStyle(style -> style.withFont(ModernUi.FONT)), Math.max(1, sidebarWidth - 72))),
                        24, row.top() + (34 - (row.session().lastMessage().isBlank() ? font.lineHeight
                                : ModernUi.MESSAGE_LINE_HEIGHT + font.lineHeight)) / 2 + 1, selected ? ModernUi.TEXT : ModernUi.MUTED, false);
                graphics.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(Component.literal(row.session().lastMessage().replaceAll("\\s+", " "))
                                .withStyle(style -> style.withFont(ModernUi.FONT)), Math.max(1, sidebarWidth - 72))),
                        24, row.top() + (34 - (ModernUi.MESSAGE_LINE_HEIGHT + font.lineHeight)) / 2 + 1 + ModernUi.MESSAGE_LINE_HEIGHT, ModernUi.MUTED, false);
                if (!view.active() && !awaitingRequestView && !deletingSession) {
                    int x = sidebarWidth - 36;
                    ModernUi.surface(graphics, x, row.top() + 7, x + 20, row.top() + 27,
                            mouseX >= x && mouseX < x + 20 && mouseY >= row.top() + 7 && mouseY < row.top() + 27
                                    ? ModernUi.SURFACE_HOVER : ModernUi.SURFACE);
                    graphics.blit(ResourceLocation.fromNamespaceAndPath("packsage", "textures/gui/icons/trash.png"),
                            x + 4, row.top() + 11, 12, 12, 0f, 0f, 64, 64, 64, 64);
                }
            }
        }
        graphics.disableScissor();
        if (maxScroll > 0) {
            int track = bottom - 104;
            int thumb = Math.max(12, track * track / (track + maxScroll));
            int y = 104 + sidebarScroll * (track - thumb) / maxScroll;
            graphics.fill(sidebarWidth - 3, y, sidebarWidth - 1, y + thumb, ModernUi.BORDER);
        }
    }

    private int drawConversation(GuiGraphics graphics, int y, int contentBottom, int mouseX, int mouseY) {
        graphics.drawString(font, Component.literal(view.conversation().world().readableLabel()).withStyle(style ->
                style.withFont(ModernUi.BOLD_FONT).withColor(ModernUi.TEXT)), readableLeft, y, ModernUi.TEXT, false);
        y += 14;
        if (view.readOnly()) {
            graphics.drawString(font, Component.literal(view.activity().isEmpty() ? "Read-only history" : view.activity())
                    .withStyle(style -> style.withFont(ModernUi.FONT)), readableLeft, y, ModernUi.MUTED, false);
            y += ModernUi.MESSAGE_LINE_HEIGHT;
        }
        chipHits.clear();
        sourceHits.clear();
        messageActions.clear();
        if (history == null) history = actions.historyPreview(editingRequest == null ? resendingRequest : editingRequest);
        for (ChatContracts.Turn turn : view.conversation().turns()) {
            if (view.active() && turn.requestId().equals(resendingRequest)) continue;
            boolean included = !view.readOnly() && history.selection().turns().stream()
                    .anyMatch(selected -> selected.requestId().equals(turn.requestId()));
            ChatContracts.TokenCounts tokens = GroundingInstructions.historyTokenCounts(turn);
            y = drawUserMessage(graphics, turn.question(), y, included);
            y = drawMessageActions(graphics, turn, true, tokens.user(), y, mouseX, mouseY);
            y += 7;
            y = drawAssistantMessage(graphics, turn.answer(), turn, y, included);
            y = drawMessageActions(graphics, turn, false, tokens.assistant(), y, mouseX, mouseY);
            String outcome = ScreenText.outcomeLine(turn.outcome());
            if (outcome != null) {
                graphics.drawString(font, Component.literal(outcome).withStyle(style ->
                        style.withFont(ModernUi.FONT).withColor(ModernUi.DANGER)), readableLeft + 26, y, ModernUi.DANGER, false);
                y += ModernUi.MESSAGE_LINE_HEIGHT;
            }
            y += 10;
        }
        String liveQuestion = view.active() ? view.activeQuestion() : awaitingRequestView ? pendingQuestion : "";
        ChatContracts.Turn liveTurn = new ChatContracts.Turn(new ChatContracts.RequestId("active-preview"),
                liveQuestion, view.streamingAnswer(), List.of(), view.context(), List.of(), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.ACTIVE, null), 0,
                view.activeSentAt() == null ? pendingSentAt : view.activeSentAt());
        if (!liveQuestion.isEmpty()) {
            y = drawUserMessage(graphics, liveQuestion, y, false);
            y = drawMessageActions(graphics, liveTurn, true, GroundingInstructions.historyTokenCounts(liveTurn).user(), y, mouseX, mouseY) + 7;
        }
        if (view.active() || awaitingRequestView) {
            drawAssistantAvatar(graphics, y);
            int responseTop = y;
            if (!view.streamingAnswer().isEmpty()) y = drawInlineLines(graphics, ScreenText.inlineLines(view.streamingAnswer(), List.of()), y,
                    readableLeft + 26, readableWidth - 26, null);
            graphics.drawString(font, Component.literal(view.activity().isEmpty() ? "Working..." : view.activity())
                    .withStyle(style -> style.withFont(ModernUi.FONT)), readableLeft + 26, y + 2, ModernUi.ACCENT, false);
            y = Math.max(responseTop + 18, y + ModernUi.MESSAGE_LINE_HEIGHT);
            if (!view.streamingAnswer().isEmpty()) y = drawMessageActions(graphics, liveTurn, false,
                    GroundingInstructions.historyTokenCounts(liveTurn).assistant(), y, mouseX, mouseY);
        }
        if (!view.errors().isEmpty()) {
            y += 4;
            for (var line : font.split(Component.literal(view.errors().getLast()).withStyle(style ->
                    style.withFont(ModernUi.FONT).withColor(ModernUi.DANGER)), Math.max(1, readableWidth - 26))) {
                graphics.drawString(font, line, readableLeft + 26, y, ModernUi.DANGER, false);
                y += ModernUi.MESSAGE_LINE_HEIGHT;
            }
        }
        return Math.max(y, contentBottom - scrollOffset);
    }

    private int drawUserMessage(GuiGraphics graphics, String text, int y, boolean included) {
        int bubbleWidth = Math.max(1, Math.min(360, readableWidth - 26));
        Component content = Component.literal(text).withStyle(style -> style.withFont(ModernUi.FONT).withColor(ModernUi.TEXT));
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(content, Math.max(1, bubbleWidth - 20));
        int bubbleHeight = Math.max(20, lines.size() * ModernUi.MESSAGE_LINE_HEIGHT + 8);
        int bubbleX = readableLeft + readableWidth - bubbleWidth - 24;
        ModernUi.surface(graphics, bubbleX, y, bubbleX + bubbleWidth, y + bubbleHeight, ModernUi.SURFACE_HOVER);
        int lineY = y + (bubbleHeight - ((lines.size() - 1) * ModernUi.MESSAGE_LINE_HEIGHT + font.lineHeight)) / 2 + 1;
        for (net.minecraft.util.FormattedCharSequence line : lines) {
            graphics.drawString(font, line, bubbleX + 10, lineY, ModernUi.TEXT, false);
            lineY += ModernUi.MESSAGE_LINE_HEIGHT;
        }
        int avatarX = readableLeft + readableWidth - 18;
        if (minecraft != null && minecraft.player != null) {
            PlayerFaceRenderer.draw(graphics, minecraft.getSkinManager().getInsecureSkin(minecraft.player.getGameProfile()),
                    avatarX, y + Math.max(0, bubbleHeight - 18), 18);
        } else {
            ModernUi.surface(graphics, avatarX, y + Math.max(0, bubbleHeight - 18), avatarX + 18,
                    y + Math.max(0, bubbleHeight - 18) + 18, ModernUi.ACCENT_DARK);
            graphics.drawString(font, Component.literal("You").withStyle(style ->
                    style.withFont(ModernUi.FONT).withColor(ModernUi.TEXT)), avatarX + 3,
                    y + Math.max(0, bubbleHeight - 18) + (18 - font.lineHeight) / 2 + 1, ModernUi.TEXT, false);
        }
        graphics.fill(avatarX + 13, y + Math.max(0, bubbleHeight - 18) + 13, avatarX + 18,
                y + Math.max(0, bubbleHeight - 18) + 18, included ? 0xff74bf8c : ModernUi.DANGER);
        return y + bubbleHeight;
    }

    private int drawAssistantMessage(GuiGraphics graphics, String answer, ChatContracts.Turn turn, int y, boolean included) {
        drawAssistantAvatar(graphics, y);
        graphics.fill(readableLeft + 13, y + 13, readableLeft + 18, y + 18,
                included ? 0xff74bf8c : ModernUi.DANGER);
        List<Evidence.NavigationTarget> targets = turnTargets(turn);
        List<ModelBackend.UrlCitation> citations = new ArrayList<>();
        int searchFrom = 0;
        for (ModelBackend.InputItem item : turn.replayItems()) {
            if (item instanceof ModelBackend.AssistantOutput output && output.status() == ModelBackend.OutputStatus.COMPLETED) {
                for (ModelBackend.OutputContent content : output.content()) {
                    if (content instanceof ModelBackend.OutputText text) {
                        int offset = answer.indexOf(text.text(), searchFrom);
                        if (offset < 0) continue;
                        for (ModelBackend.UrlCitation citation : text.citations()) {
                            if (citation.startIndex() >= 0 && citation.endIndex() <= text.text().length()) {
                                citations.add(new ModelBackend.UrlCitation(offset + citation.startIndex(), offset + citation.endIndex(),
                                        citation.title(), citation.url()));
                            }
                        }
                        searchFrom = offset + text.text().length();
                    }
                }
            }
        }
        return Math.max(y + 18, drawInlineLines(graphics, ScreenText.inlineLines(answer, targets, citations, turn.sources()), y,
                readableLeft + 26, readableWidth - 26, turn));
    }

    private int drawMessageActions(GuiGraphics graphics, ChatContracts.Turn turn, boolean user, int tokens, int y, int mouseX, int mouseY) {
        Component count = Component.literal((user && turn.sentAt() != null ? DateTimeFormatter.ofPattern("MMM d, HH:mm", java.util.Locale.getDefault())
                .withZone(ZoneId.systemDefault()).format(turn.sentAt()) + " · " : "")
                + "~" + tokens + " tokens").withStyle(style -> style.withFont(ModernUi.FONT));
        boolean editable = user && !view.active() && !awaitingRequestView && !view.readOnly()
                && turn == view.conversation().turns().getLast();
        boolean stacked = user && font.width(count) + 42 + (editable ? 38 : 0) > readableWidth;
        int left = user ? Math.max(readableLeft, readableLeft + readableWidth - font.width(count) - 42 - (editable ? 38 : 0)) : readableLeft + 26;
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(count, Math.max(1, stacked ? readableWidth : font.width(count)));
        for (int index = 0; index < lines.size(); index++) graphics.drawString(font, lines.get(index), left,
                y + 6 + index * ModernUi.MESSAGE_LINE_HEIGHT, ModernUi.MUTED, false);
        int actionLeft = stacked ? readableLeft + readableWidth - 34 - (editable ? 38 : 0) : left + font.width(count) + 8;
        int actionY = y + 2 + (stacked ? lines.size() * ModernUi.MESSAGE_LINE_HEIGHT + 4 : 0);
        ModernUi.surface(graphics, actionLeft, actionY, actionLeft + 34, actionY + 16,
                mouseX >= actionLeft && mouseX < actionLeft + 34 && mouseY >= actionY && mouseY < actionY + 16 ? ModernUi.SURFACE_HOVER : ModernUi.SURFACE);
        graphics.drawString(font, Component.literal("Copy").withStyle(style -> style.withFont(ModernUi.FONT)),
                actionLeft + 5, actionY + (16 - font.lineHeight) / 2 + 1, ModernUi.TEXT, false);
        messageActions.add(new MessageAction(turn, user, false, actionLeft, actionY, 34));
        if (editable) {
            ModernUi.surface(graphics, actionLeft + 40, actionY, actionLeft + 72, actionY + 16,
                    mouseX >= actionLeft + 40 && mouseX < actionLeft + 72 && mouseY >= actionY && mouseY < actionY + 16 ? ModernUi.SURFACE_HOVER : ModernUi.SURFACE);
            graphics.drawString(font, Component.literal("Edit").withStyle(style -> style.withFont(ModernUi.FONT)),
                    actionLeft + 45, actionY + (16 - font.lineHeight) / 2 + 1, ModernUi.TEXT, false);
            messageActions.add(new MessageAction(turn, true, true, actionLeft + 40, actionY, 32));
        }
        return actionY + 20;
    }

    private void drawAssistantAvatar(GuiGraphics graphics, int y) {
        ModernUi.surface(graphics, readableLeft, y, readableLeft + 18, y + 18, ModernUi.ACCENT_DARK);
        graphics.drawString(font, Component.literal("Ps").withStyle(style ->
                style.withFont(ModernUi.BOLD_FONT).withColor(ModernUi.TEXT)), readableLeft + 4, y + (18 - font.lineHeight) / 2 + 1, ModernUi.TEXT, false);
        graphics.fill(readableLeft + 13, y + 13, readableLeft + 18, y + 18, ModernUi.DANGER);
    }

    private int drawInlineLines(GuiGraphics graphics, List<ScreenText.InlineLine> lines, int y, int left,
                                int maxWidth, ChatContracts.Turn turn) {
        MessageLayout layout = messageLayout(lines, maxWidth);
        int quoteY = -1;
        for (MessageRun run : layout.runs()) {
            int x = left + run.x();
            int top = y + run.y();
            if (run.quoted() && quoteY != run.y()) {
                graphics.fill(left, top, left + 2, top + ModernUi.MESSAGE_LINE_HEIGHT, ModernUi.MUTED);
                quoteY = run.y();
            }
            ScreenText.InlineSpan span = run.span();
            if (span.target() != null || span.source() != null) {
                ModernUi.surface(graphics, x, top, x + run.width(), top + ModernUi.MESSAGE_LINE_HEIGHT, ModernUi.SURFACE_HOVER);
                if (turn != null && span.target() != null) chipHits.add(new ChipHit(span.target(), x, top, x + run.width(), top + ModernUi.MESSAGE_LINE_HEIGHT));
                if (turn != null && span.source() != null) sourceHits.add(new SourceHit(span.source(), turn, x, top, x + run.width(), top + ModernUi.MESSAGE_LINE_HEIGHT));
            }
            graphics.drawString(font, Component.literal(span.text()).withStyle(style -> style
                            .withFont(span.bold() || span.target() != null || span.source() != null ? ModernUi.BOLD_FONT : ModernUi.FONT).withColor(span.color())),
                    x + (span.target() == null && span.source() == null ? 0 : 6),
                    top + (ModernUi.MESSAGE_LINE_HEIGHT - font.lineHeight) / 2 + 1, span.color(), false);
        }
        return y + layout.height();
    }

    record MessageRun(ScreenText.InlineSpan span, int x, int y, int width, boolean quoted) { }
    record MessageLayout(List<MessageRun> runs, int height) { }

    MessageLayout messageLayout(List<ScreenText.InlineLine> lines, int maxWidth) {
        int width = Math.max(1, maxWidth);
        List<MessageRun> runs = new ArrayList<>();
        int y = 0;
        for (ScreenText.InlineLine line : lines) {
            int indent = line.quote() ? 10 : 0;
            int x = indent;
            if (line.spans().isEmpty() && line.quote()) runs.add(new MessageRun(
                    new ScreenText.InlineSpan("", null, ModernUi.TEXT, false, false), x, y, 0, true));
            for (ScreenText.InlineSpan span : line.spans()) {
                if (span.target() != null || span.source() != null) {
                    Component label = Component.literal(span.text()).withStyle(style -> style.withFont(ModernUi.BOLD_FONT));
                    int chipWidth = Math.min(Math.max(1, width - indent), font.width(label) + 12);
                    if (x > indent && x + chipWidth > width) { x = indent; y += ModernUi.MESSAGE_LINE_HEIGHT; }
                    String clipped = font.substrByWidth(label, Math.max(1, chipWidth - 12)).getString();
                    runs.add(new MessageRun(new ScreenText.InlineSpan(clipped, span.target(), ModernUi.TEXT, true, false, span.source()),
                            x, y, chipWidth, line.quote()));
                    x += chipWidth + 4;
                    continue;
                }
                for (String token : span.text().split("(?<=\\s)(?=\\S)|(?<=\\S)(?=\\s)")) {
                    if (token.isEmpty()) continue;
                    boolean space = token.codePoints().allMatch(Character::isWhitespace);
                    if (space && x == indent) continue;
                    int tokenWidth = font.width(Component.literal(token).withStyle(style ->
                            style.withFont(span.bold() ? ModernUi.BOLD_FONT : ModernUi.FONT)));
                    if (x > indent && x + tokenWidth > width) {
                        x = indent;
                        y += ModernUi.MESSAGE_LINE_HEIGHT;
                        if (space) continue;
                    }
                    while (!token.isEmpty()) {
                        int take = prefixWidth(token, Math.max(1, width - x), span.bold());
                        if (take <= 0) take = Character.charCount(token.codePointAt(0));
                        String piece = token.substring(0, take);
                        int pieceWidth = font.width(Component.literal(piece).withStyle(style ->
                                style.withFont(span.bold() ? ModernUi.BOLD_FONT : ModernUi.FONT)));
                        runs.add(new MessageRun(new ScreenText.InlineSpan(piece, null, span.color(), span.bold(), span.code()),
                                x, y, pieceWidth, line.quote()));
                        x += pieceWidth;
                        token = token.substring(take);
                        if (!token.isEmpty()) { x = indent; y += ModernUi.MESSAGE_LINE_HEIGHT; }
                    }
                }
            }
            y += ModernUi.MESSAGE_LINE_HEIGHT;
        }
        return new MessageLayout(List.copyOf(runs), y);
    }

    private int prefixWidth(String value, int available, boolean bold) {
        int end = 0;
        while (end < value.length()) {
            int next = end + Character.charCount(value.codePointAt(end));
            Component candidate = Component.literal(value.substring(0, next)).withStyle(style ->
                    style.withFont(bold ? ModernUi.BOLD_FONT : ModernUi.FONT));
            if (font.width(candidate) > available) break;
            end = next;
        }
        return end;
    }

    private List<Evidence.NavigationTarget> turnTargets(ChatContracts.Turn turn) {
        List<Evidence.Source> sources = new ArrayList<>(turn.sources());
        view.sources().forEach(source -> {
            if (source.scope().equals(turn.context().scope()) && !sources.contains(source)) sources.add(source);
        });
        List<Evidence.NavigationTarget> targets = new ArrayList<>();
        turn.navigationTargets().forEach(target -> {
            if (sources.stream().anyMatch(source -> source.scope().equals(target.scope())
                    && source.providerId().equals(target.providerId()) && source.sourceHandle().equals(target.sourceHandle()))
                    && !targets.contains(target)) targets.add(target);
        });
        view.navigationTargets().forEach(target -> {
            if (target.scope().equals(turn.context().scope()) && sources.stream().anyMatch(source -> source.scope().equals(target.scope())
                    && source.providerId().equals(target.providerId()) && source.sourceHandle().equals(target.sourceHandle()))
                    && !targets.contains(target)) {
                targets.add(target);
            }
        });
        return targets;
    }

    private void drawComposer(GuiGraphics graphics, int mouseX, int mouseY) {
        for (ToolbarItem item : toolbarItems()) {
            boolean hovered = mouseX >= item.x() && mouseX < item.x() + item.width()
                    && mouseY >= item.y() && mouseY < item.y() + 20;
            boolean selected = item.kind().equals("search") && explicitWebSearch
                    || item.kind().equals("screenshot") && includeScreenshot;
            ModernUi.surface(graphics, item.x(), item.y(), item.x() + item.width(), item.y() + 20,
                    selected ? hovered ? 0xe052765f : ModernUi.ACCENT_DARK
                            : hovered ? ModernUi.SURFACE_HOVER : ModernUi.SURFACE);
            if (item.kind().equals("context")) {
                graphics.fill(item.x() + 2, item.y() + 2, item.x() + item.width() - 2, item.y() + 18, ModernUi.BORDER);
                if (history != null && history.effectiveTokens() > 0) graphics.fill(item.x() + 2, item.y() + 2,
                        item.x() + 2 + (int) ((item.width() - 4L) * Math.min(history.selection().tokenCount(), history.effectiveTokens())
                                / history.effectiveTokens()), item.y() + 18, ModernUi.ACCENT);
                continue;
            }
            String label;
            int textLeft = item.x() + 7;
            if (item.kind().equals("search") || item.kind().equals("screenshot")) {
                graphics.blit(ResourceLocation.fromNamespaceAndPath("packsage", "textures/gui/icons/"
                                + (item.kind().equals("search") ? "globe" : "camera") + ".png"),
                        item.x() + 6, item.y() + (20 - 13) / 2, 13, 13, 0.0F, 0.0F, 64, 64, 64, 64);
                textLeft = item.x() + 24;
                label = item.kind().equals("search") ? "Search" : "Screenshot";
            } else if (item.kind().equals("model")) {
                String selectedId = settings.modelId() == null ? actions.providerSelectedModelId() : settings.modelId();
                label = actions.modelCatalog().stream().filter(model -> model.id().equals(selectedId))
                        .findFirst().map(ModelBackend.ModelInfo::displayName).orElse("Model");
            } else if (item.kind().equals("effort")) {
                graphics.blit(ResourceLocation.fromNamespaceAndPath("packsage", "textures/gui/icons/brain.png"),
                        item.x() + 6, item.y() + 4, 12, 12, 0f, 0f, 64, 64, 64, 64);
                textLeft = item.x() + 22;
                label = settings.reasoningEffort() == ModelBackend.ReasoningEffort.DEFAULT ? "Default"
                        : settings.reasoningEffort() == ModelBackend.ReasoningEffort.XHIGH ? "Extra high"
                        : settings.reasoningEffort().name().toLowerCase(java.util.Locale.ROOT);
            } else label = "";
            boolean dropdown = item.kind().equals("model") || item.kind().equals("effort");
            if (dropdown) graphics.blit(ResourceLocation.fromNamespaceAndPath("packsage", "textures/gui/icons/caret-up.png"),
                    item.x() + item.width() - 15, item.y() + 5, 10, 10, 0.0F, 0.0F, 64, 64, 64, 64);
            graphics.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(Component.literal(label)
                            .withStyle(style -> style.withFont(ModernUi.FONT)), Math.max(1, item.width() - (textLeft - item.x()) - (dropdown ? 19 : 7)))),
                    textLeft, item.y() + (20 - font.lineHeight) / 2 + 1, ModernUi.TEXT, false);
        }
        ModernUi.surface(graphics, readableLeft, sendY, readableLeft + 20, sendY + 20,
                mouseX >= readableLeft && mouseX < readableLeft + 20 && mouseY >= sendY && mouseY < sendY + 20
                        ? ModernUi.SURFACE_HOVER : ModernUi.SURFACE);
        graphics.blit(ResourceLocation.fromNamespaceAndPath("packsage", "textures/gui/icons/info.png"),
                readableLeft + 4, sendY + 4, 12, 12, 0f, 0f, 64, 64, 64, 64);
        ModernUi.surface(graphics, sendX, sendY, sendX + 20, sendY + 20,
                awaitingRequestView || view.active() ? ModernUi.ACCENT_DARK : view.readOnly() ? ModernUi.SURFACE : ModernUi.SURFACE_HOVER);
        if (view.readOnly()) {
            graphics.drawString(font, Component.literal("Read-only history").withStyle(style ->
                    style.withFont(ModernUi.FONT).withColor(ModernUi.MUTED)), readableLeft, composerTop + composerHeight + 5, ModernUi.MUTED, false);
        }
        if (editingRequest != null) {
            graphics.drawString(font, Component.literal("Editing latest message · Escape to cancel").withStyle(style ->
                    style.withFont(ModernUi.FONT)), readableLeft, composerTop + composerHeight + 7, ModernUi.ACCENT, false);
        }
    }

    record ToolbarItem(String kind, int x, int y, int width) { }

    List<ToolbarItem> toolbarItems() {
        int buttonWidth = Math.max(2, Math.min(86, readableWidth));
        List<ToolbarItem> items = new ArrayList<>();
        int x = readableLeft;
        int y = toggleY;
        for (String kind : List.of("model", "effort", "search", "screenshot")) {
            int itemWidth = buttonWidth;
            if (x > readableLeft && x + itemWidth > readableLeft + readableWidth) {
                x = readableLeft;
                y += 26;
            }
            items.add(new ToolbarItem(kind, x, y, itemWidth));
            x += itemWidth + 6;
        }
        items.add(new ToolbarItem("context", readableLeft + 26, sendY, buttonWidth / 2));
        return items;
    }

    record PickerChoice(String value, String label) { }
    record PickerRow(PickerChoice choice, int x, int y, int width) { }

    List<PickerChoice> pickerChoices() {
        List<PickerChoice> choices = new ArrayList<>();
        if ("model".equals(picker)) {
            choices.add(new PickerChoice("", "Provider default"));
            for (ModelBackend.ModelInfo model : actions.modelCatalog()) choices.add(new PickerChoice(model.id(), model.displayName()));
        } else if ("effort".equals(picker)) {
            for (ModelBackend.ReasoningEffort effort : ModelBackend.ReasoningEffort.values()) choices.add(new PickerChoice(effort.name(),
                    effort == ModelBackend.ReasoningEffort.DEFAULT ? "Provider default" : effort.name().toLowerCase(java.util.Locale.ROOT)));
        }
        return choices;
    }

    List<PickerRow> pickerRows() {
        if (picker == null) return List.of();
        List<PickerChoice> choices = pickerChoices();
        ToolbarItem anchor = toolbarItems().stream().filter(item -> item.kind().equals(picker)).findFirst().orElseThrow();
        int visible = Math.max(1, Math.min(choices.size(), (anchor.y() - 46) / 22));
        pickerScroll = Math.max(0, Math.min(pickerScroll, Math.max(0, choices.size() - visible)));
        int popupWidth = anchor.width();
        int left = anchor.x();
        int top = Math.max(40, anchor.y() - visible * 22 - 6);
        List<PickerRow> rows = new ArrayList<>();
        for (int index = 0; index < visible; index++) rows.add(new PickerRow(choices.get(pickerScroll + index), left, top + index * 22, popupWidth));
        return rows;
    }

    private void drawPicker(GuiGraphics graphics, int mouseX, int mouseY) {
        List<PickerRow> rows = pickerRows();
        String hovered = rows.stream().filter(row -> mouseX >= row.x() && mouseX < row.x() + row.width()
                        && mouseY >= row.y() && mouseY < row.y() + 22).map(row -> row.choice().value()).findFirst().orElse(null);
        if (!Objects.equals(hoveredPickerChoice, hovered)) {
            hoveredPickerChoice = hovered;
            pickerHoverStarted = System.currentTimeMillis();
        }
        for (PickerRow row : rows) {
            boolean selected = "model".equals(picker) ? Objects.equals(settings.modelId() == null ? "" : settings.modelId(), row.choice().value())
                    : settings.reasoningEffort().name().equals(row.choice().value());
            ModernUi.surface(graphics, row.x(), row.y(), row.x() + row.width(), row.y() + 22,
                    selected ? ModernUi.ACCENT_DARK : mouseX >= row.x() && mouseX < row.x() + row.width()
                            && mouseY >= row.y() && mouseY < row.y() + 22 ? ModernUi.SURFACE_HOVER : 0xff20252a);
            Component label = Component.literal(row.choice().label()).withStyle(style -> style.withFont(ModernUi.FONT));
            int overflow = Math.max(0, font.width(label) - Math.max(1, row.width() - 16));
            int offset = 0;
            if (overflow > 0 && row.choice().value().equals(hovered)) {
                long elapsed = Math.max(0, System.currentTimeMillis() - pickerHoverStarted - 700);
                long travel = overflow * 35L;
                long phase = elapsed % (travel * 2 + 1400);
                offset = (int) (phase < travel ? phase / 35 : phase < travel + 700 ? overflow
                        : phase < travel * 2 + 700 ? overflow - (phase - travel - 700) / 35 : 0);
            }
            graphics.enableScissor(row.x() + 8, row.y() + 1, row.x() + row.width() - 8, row.y() + 21);
            graphics.drawString(font, label, row.x() + 8 - offset, row.y() + (22 - font.lineHeight) / 2 + 1, ModernUi.TEXT, false);
            graphics.disableScissor();
        }
        int count = pickerChoices().size();
        if (!rows.isEmpty() && count > rows.size()) {
            PickerRow first = rows.getFirst();
            int track = rows.size() * 22;
            int thumb = Math.max(8, track * rows.size() / count);
            int top = first.y() + pickerScroll * (track - thumb) / (count - rows.size());
            graphics.fill(first.x() + first.width() - 3, top, first.x() + first.width() - 1, top + thumb, ModernUi.MUTED);
        }
    }

    private void drawToolbarTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (picker != null) return;
        List<ToolbarItem> tooltipItems = new ArrayList<>(toolbarItems());
        tooltipItems.add(new ToolbarItem("extra", readableLeft, sendY, 20));
        for (ToolbarItem item : tooltipItems) {
            if (mouseX < item.x() || mouseX >= item.x() + item.width() || mouseY < item.y() || mouseY >= item.y() + 20) continue;
            List<Component> lines = new ArrayList<>();
            List<ScreenText.TooltipField> fields = List.of();
            String title = "";
            String footer = "";
            if (item.kind().equals("context")) {
                title = "Chat context";
                fields = ScreenText.historyContextFields(history);
                footer = view.readOnly() ? "Read-only until you join this world/server." : "Estimated tokens for included messages.";
            } else if (item.kind().equals("extra")) {
                title = "Current gameplay";
                try {
                    fields = ScreenText.gameplayContextFields(actions.gameplayContext());
                } catch (RuntimeException ignored) {
                    lines.add(Component.literal("Gameplay observation is unavailable."));
                }
                footer = "Attached with your next message.";
            } else if (item.kind().equals("model")) {
                String selectedId = settings.modelId() == null ? actions.providerSelectedModelId() : settings.modelId();
                lines.add(Component.literal(actions.modelCatalog().stream().filter(model -> model.id().equals(selectedId))
                        .findFirst().map(ModelBackend.ModelInfo::displayName).orElse("Select a model")));
            } else if (item.kind().equals("effort")) lines.add(Component.literal("Reasoning effort: " + settings.reasoningEffort().name().toLowerCase(java.util.Locale.ROOT)));
            else lines.add(Component.literal(item.kind().equals("search") ? "Web search" : "Attach screenshot"));
            List<net.minecraft.util.FormattedCharSequence> wrapped = new ArrayList<>();
            int tooltipWidth = Math.max(1, Math.min(286, width - 24));
            if (title.isEmpty()) tooltipWidth = Math.min(tooltipWidth, 20 + lines.stream()
                    .mapToInt(line -> font.width(line.copy().withStyle(style -> style.withFont(ModernUi.FONT)))).max().orElse(0));
            int labelWidth = fields.stream().mapToInt(field -> font.width(Component.literal(field.label())
                    .withStyle(style -> style.withFont(ModernUi.FONT))) + 14).max().orElse(0);
            List<List<net.minecraft.util.FormattedCharSequence>> values = new ArrayList<>();
            for (ScreenText.TooltipField field : fields) values.add(font.split(Component.literal(field.value())
                    .withStyle(style -> style.withFont(ModernUi.FONT)), Math.max(1, tooltipWidth - 20 - labelWidth)));
            for (Component line : lines) wrapped.addAll(font.split(line.copy().withStyle(style -> style.withFont(ModernUi.FONT)), tooltipWidth - 20));
            List<net.minecraft.util.FormattedCharSequence> footerLines = footer.isEmpty() ? List.of()
                    : font.split(Component.literal(footer).withStyle(style -> style.withFont(ModernUi.FONT)), tooltipWidth - 20);
            int tooltipHeight = Math.min(height - 8, 20 + (title.isEmpty() ? 0 : 16) + wrapped.size() * 14
                    + values.stream().mapToInt(value -> Math.max(1, value.size()) * 14).sum()
                    + (footerLines.isEmpty() ? 0 : 6 + footerLines.size() * 14));
            int left = Math.max(4, Math.min(mouseX + 10, width - tooltipWidth - 4));
            int top = Math.max(4, item.y() - tooltipHeight - 6);
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 400);
            ModernUi.surface(graphics, left, top, left + tooltipWidth, top + tooltipHeight, 0xff20252a);
            graphics.enableScissor(left + 4, top + 4, left + tooltipWidth - 4, top + tooltipHeight - 4);
            int y = top + 10;
            if (!title.isEmpty()) {
                graphics.drawString(font, Component.literal(title).withStyle(style -> style.withFont(ModernUi.BOLD_FONT)),
                        left + 10, y, ModernUi.TEXT, false);
                y += 16;
            }
            for (int index = 0; index < fields.size(); index++) {
                graphics.drawString(font, Component.literal(fields.get(index).label()).withStyle(style -> style.withFont(ModernUi.FONT)),
                        left + 10, y, ModernUi.MUTED, false);
                for (int line = 0; line < values.get(index).size(); line++) graphics.drawString(font, values.get(index).get(line),
                        left + 10 + labelWidth, y + line * 14, ModernUi.TEXT, false);
                y += Math.max(1, values.get(index).size()) * 14;
            }
            for (var line : wrapped) {
                graphics.drawString(font, line, left + 10, y, ModernUi.TEXT, false);
                y += 14;
            }
            if (!footerLines.isEmpty()) y += 6;
            for (var line : footerLines) {
                graphics.drawString(font, line, left + 10, y, ModernUi.MUTED, false);
                y += 14;
            }
            graphics.disableScissor();
            graphics.pose().popPose();
        }
    }

    private void drawSendIcon(GuiGraphics graphics) {
        graphics.blit(ResourceLocation.fromNamespaceAndPath("packsage", "textures/gui/icons/"
                        + (awaitingRequestView || view.active() ? "stop" : "paper-plane-right") + ".png"),
                sendX + 4, sendY + 4, 12, 12, 0.0F, 0.0F, 64, 64, 64, 64);
    }

    void updateContentMetrics(int contentHeight, int viewportHeight) {
        this.contentHeight = Math.max(0, contentHeight);
        this.viewportHeight = Math.max(1, viewportHeight);
        if (followBottom && (view.active() || followOnCompletion)) scrollOffset = maxScroll();
        else scrollOffset = Math.max(0, Math.min(maxScroll(), scrollOffset));
        followOnCompletion = false;
        if (scrollOffset >= maxScroll()) followBottom = true;
    }

    int maxScroll() { return Math.max(0, contentHeight - viewportHeight); }

    boolean followingBottom() { return followBottom; }

    private void scrollToBottom() { followBottom = true; scrollOffset = maxScroll(); }

    String statusLine() { return !view.active() ? "Request: idle" : view.activity().isEmpty() ? "Working..." : view.activity(); }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (picker != null) {
            pickerScroll -= (int) Math.signum(scrollY);
            pickerRows();
            return true;
        }
        if (inputEditor != null && inputEditor.isMouseOver(mouseX, mouseY)) return inputEditor.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        if (sessionSearchEditor != null && sessionSearchEditor.isMouseOver(mouseX, mouseY)) return sessionSearchEditor.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        if (mouseX < sidebarWidth) {
            if (mouseY >= 104 && mouseY < height - 56) {
                sessionRows();
                sidebarScroll = Math.max(0, Math.min(Math.max(0, sidebarContentHeight - (height - 160)), sidebarScroll - (int) (scrollY * 28)));
                return true;
            }
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        if (height > 0 && (mouseY < 40 || mouseY >= toggleY - 8)) return false;
        scrollOffset = Math.max(0, Math.min(maxScroll(), scrollOffset - (int) (scrollY * 20)));
        followBottom = scrollOffset >= maxScroll();
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        if (picker != null) {
            for (PickerRow row : pickerRows()) {
                if (mouseX >= row.x() && mouseX < row.x() + row.width() && mouseY >= row.y() && mouseY < row.y() + 22) {
                    if (picker.equals("model")) settings.setModelId(row.choice().value());
                    else settings.setReasoningEffort(ModelBackend.ReasoningEffort.valueOf(row.choice().value()));
                    settingsSaver.run();
                    break;
                }
            }
            picker = null;
            setFocused(null);
            return true;
        }
        if (inputEditor != null && inputEditor.isMouseOver(mouseX, mouseY)) {
            setFocused(inputEditor);
            return inputEditor.mouseClicked(mouseX, mouseY, button);
        }
        if (sessionSearchEditor != null && sessionSearchEditor.isMouseOver(mouseX, mouseY)) {
            setFocused(sessionSearchEditor);
            return sessionSearchEditor.mouseClicked(mouseX, mouseY, button);
        }
        setFocused(null);
        if (mouseX < sidebarWidth) {
            if (mouseX >= 12 && mouseX < sidebarWidth - 12 && mouseY >= 60 && mouseY < 80) { newChat(); return true; }
            if (mouseX >= 12 && mouseX < sidebarWidth - 12 && mouseY >= 104 && mouseY < height - 56) {
                for (SessionRow row : sessionRows()) {
                    if (mouseY < row.top() || mouseY >= row.top() + row.height()) continue;
                    if (row.expand()) {
                        if (!expandedWorlds.add(row.world().worldOrServer())) expandedWorlds.remove(row.world().worldOrServer());
                        return true;
                    }
                    if (row.session() != null) {
                        if (row.session().equals(pendingDelete)) {
                            if (mouseY >= row.top() + 15 && mouseY < row.top() + 32) {
                                int confirmWidth = Math.max(1, Math.min(54, (sidebarWidth - 42) / 2));
                                if (mouseX >= 18 && mouseX < 18 + confirmWidth && !view.active() && !awaitingRequestView && !deletingSession) {
                                    deletingSession = true;
                                    actions.deleteSession(row.session()).whenComplete((ignored, failure) -> {
                                        Runnable update = () -> {
                                            deletingSession = false;
                                            pendingDelete = null;
                                            loadSessions();
                                        };
                                        if (minecraft == null) update.run();
                                        else minecraft.execute(update);
                                    });
                                } else if (mouseX >= 24 + confirmWidth && mouseX < 24 + 2 * confirmWidth && !deletingSession) pendingDelete = null;
                            }
                            return true;
                        }
                        if (mouseX >= sidebarWidth - 36 && mouseX < sidebarWidth - 16
                                && mouseY >= row.top() + 7 && mouseY < row.top() + 27) {
                            if (!view.active() && !awaitingRequestView && !deletingSession) pendingDelete = row.session();
                            return true;
                        }
                        pendingDelete = null;
                        setFocused(null);
                        actions.openSession(row.session());
                        scrollToBottom();
                        return true;
                    }
                }
            }
            int controlY = Math.max(0, height - 32);
            if (mouseX >= 12 && mouseX < 32 && mouseY >= controlY && mouseY < controlY + 20) {
                settingsOpener.run();
                return true;
            }
            RuntimeState state = actualRuntimeState();
            if (state.privateSingleplayer() && state.integratedServer() && !state.published()
                    && mouseX >= 40 && mouseX < 60 && mouseY >= controlY && mouseY < controlY + 20) {
                setPaused(!effectivePause(state));
                return true;
            }
        }
        if (!view.readOnly()) {
            for (ToolbarItem item : toolbarItems()) {
                if (mouseX < item.x() || mouseX >= item.x() + item.width() || mouseY < item.y() || mouseY >= item.y() + 20) continue;
                if (item.kind().equals("search")) explicitWebSearch = !explicitWebSearch;
                else if (item.kind().equals("screenshot")) includeScreenshot = !includeScreenshot;
                else if (item.kind().equals("model") || item.kind().equals("effort")) {
                    picker = item.kind();
                    pickerScroll = 0;
                    hoveredPickerChoice = null;
                }
                return true;
            }
        }
        if (mouseX >= sendX && mouseX < sendX + 20 && mouseY >= sendY && mouseY < sendY + 20) {
            if (awaitingRequestView || view.active()) stop();
            else submit();
            return true;
        }
        for (MessageAction action : messageActions) {
            if (mouseY >= 48 && mouseY < toggleY - 8 && mouseX >= action.x() && mouseX < action.x() + action.width()
                    && mouseY >= action.y() && mouseY < action.y() + 16) {
                if (action.edit()) editLatest(action.turn().requestId());
                else if (minecraft != null) minecraft.keyboardHandler.setClipboard(action.user() ? action.turn().question() : action.turn().answer());
                return true;
            }
        }
        for (ChipHit hit : chipHits) {
            if (mouseY >= 48 && mouseY < toggleY - 8 && mouseX >= readableLeft && mouseX < readableLeft + readableWidth
                    && mouseX >= hit.left() && mouseX < hit.right() && mouseY >= hit.top() && mouseY < hit.bottom()) {
                navigate(hit.target());
                return true;
            }
        }
        for (SourceHit hit : sourceHits) {
            if (mouseY >= 48 && mouseY < toggleY - 8 && mouseX >= readableLeft && mouseX < readableLeft + readableWidth
                    && mouseX >= hit.left() && mouseX < hit.right() && mouseY >= hit.top() && mouseY < hit.bottom()) {
                if (minecraft != null) minecraft.setScreen(new SourceDetailsScreen(hit.source(), hit.turn(),
                        () -> minecraft.setScreen(this), this::isPauseScreen));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (inputEditor != null && inputEditor.isFocused()) return inputEditor.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        if (sessionSearchEditor != null && sessionSearchEditor.isFocused()) return sessionSearchEditor.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (inputEditor != null && inputEditor.isFocused()) return inputEditor.mouseReleased(mouseX, mouseY, button);
        if (sessionSearchEditor != null && sessionSearchEditor.isFocused()) return sessionSearchEditor.mouseReleased(mouseX, mouseY, button);
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && (picker != null || editingRequest != null)) {
            picker = null;
            if (editingRequest != null) { editingRequest = null; setInput(""); }
            return true;
        }
        if (inputEditor != null && inputEditor.isFocused() && inputEditor.keyPressed(keyCode, scanCode, modifiers)) return true;
        if (sessionSearchEditor != null && sessionSearchEditor.isFocused() && sessionSearchEditor.keyPressed(keyCode, scanCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (inputEditor != null && inputEditor.isFocused() && inputEditor.charTyped(codePoint, modifiers)) return true;
        return sessionSearchEditor != null && sessionSearchEditor.isFocused() && sessionSearchEditor.charTyped(codePoint, modifiers)
                || super.charTyped(codePoint, modifiers);
    }

    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        if (inputEditor != null) input = inputEditor.value();
        if (sessionSearchEditor != null) sessionSearch = sessionSearchEditor.value();
        super.resize(minecraft, width, height);
        layout();
    }

    public void refresh(ChatContracts.View next) {
        if (inputEditor != null) input = inputEditor.value();
        boolean completed = view.active() && !next.active();
        boolean sessionsChanged = !view.conversation().id().equals(next.conversation().id()) || completed;
        boolean historyLoaded = !view.conversation().id().equals(next.conversation().id())
                || view.activity().equals("Loading session...") && !next.activity().equals("Loading session...");
        view = Objects.requireNonNull(next);
        history = null;
        if (!next.active()) resendingRequest = null;
        if (editingRequest != null && (next.active() || next.readOnly() || next.conversation().turns().isEmpty()
                || !next.conversation().turns().getLast().requestId().equals(editingRequest))) editingRequest = null;
        awaitingRequestView = false;
        pendingQuestion = "";
        if (!next.active()) pendingSentAt = null;
        if (completed) followOnCompletion = followBottom;
        if (historyLoaded) followOnCompletion = followBottom;
        layout();
        if (sessionsChanged) loadSessions();
    }

    public ChatContracts.View view() { return view; }

    public String input() { return inputEditor == null ? input : inputEditor.value(); }

    public void setInput(String value) {
        input = Objects.requireNonNull(value);
        if (input.length() > QUESTION_MAX_LENGTH) {
            int end = QUESTION_MAX_LENGTH;
            if (Character.isHighSurrogate(input.charAt(end - 1)) && Character.isLowSurrogate(input.charAt(end))) end--;
            input = input.substring(0, end);
        }
        if (inputEditor != null) inputEditor.setValue(input);
    }

    public void setExplicitWebSearch(boolean value) { explicitWebSearch = value; }
    public void setIncludeScreenshot(boolean value) { includeScreenshot = value; }
    public boolean explicitWebSearch() { return explicitWebSearch; }
    public boolean includeScreenshot() { return includeScreenshot; }
    public void setSettingsOpener(Runnable opener) { settingsOpener = Objects.requireNonNull(opener); }
    public void setSettingsSaver(Runnable saver) { settingsSaver = Objects.requireNonNull(saver); }
    public void setRuntimeState(RuntimeState state) { runtimeState = Objects.requireNonNull(state); }
    public RuntimeState runtimeState() { return actualRuntimeState(); }
    public int scrollOffset() { return scrollOffset; }

    public void submit() {
        input = input();
        if (input.isBlank() || view.readOnly() || view.active() || awaitingRequestView) return;
        String question = input;
        pendingQuestion = question;
        pendingSentAt = Instant.now();
        setInput("");
        awaitingRequestView = true;
        if (editingRequest == null) actions.send(question, includeScreenshot, explicitWebSearch);
        else {
            ChatContracts.RequestId replacing = editingRequest;
            resendingRequest = replacing;
            editingRequest = null;
            actions.resend(replacing, question, includeScreenshot, explicitWebSearch);
        }
        scrollToBottom();
    }

    boolean editLatest(ChatContracts.RequestId requestId) {
        if (view.active() || awaitingRequestView || view.readOnly() || view.conversation().turns().isEmpty()
                || !view.conversation().turns().getLast().requestId().equals(requestId)) return false;
        editingRequest = requestId;
        setInput(view.conversation().turns().getLast().question());
        if (inputEditor != null) setFocused(inputEditor);
        history = null;
        return true;
    }

    public void close() { }

    @Override
    public void onClose() {
        if (minecraft != null) super.onClose();
    }

    public void stop() { actions.stop(); }
    public void newChat() { sidebarScroll = 0; editingRequest = null; picker = null; actions.newChat(); scrollToBottom(); }
    public void signIn() { actions.signIn(); }
    public void cancelSignIn() { actions.cancelSignIn(); }
    public void signOut() { actions.signOut(); }

    public void setPaused(boolean paused) {
        RuntimeState state = actualRuntimeState();
        if (state.privateSingleplayer() && state.integratedServer() && !state.published()) actions.setPaused(paused);
    }

    @Override
    public boolean isPauseScreen() { return effectivePause(actualRuntimeState()); }

    public boolean effectivePause(RuntimeState state) {
        return actions.view().paused() && state != null && state.privateSingleplayer() && state.integratedServer() && !state.published();
    }

    public void navigate(Evidence.NavigationTarget target) {
        if (target == null
                || view.navigationTargets().stream().noneMatch(target::equals)
                || view.sources().stream().noneMatch(source -> source.scope().equals(target.scope())
                && source.providerId().equals(target.providerId()) && source.sourceHandle().equals(target.sourceHandle()))) return;
        actions.navigate(target);
    }

    public List<Evidence.Source> sourceReferences() { return view.sources(); }

    public String answer() {
        if (view.active() && !view.streamingAnswer().isEmpty()) return view.streamingAnswer();
        List<ChatContracts.Turn> turns = view.conversation().turns();
        return turns.isEmpty() ? "" : turns.getLast().answer();
    }

    private RuntimeState actualRuntimeState() {
        if (minecraft == null) return runtimeState;
        var integratedServer = minecraft.getSingleplayerServer();
        return new RuntimeState(minecraft.isSingleplayer() && integratedServer != null, integratedServer != null,
                integratedServer != null && integratedServer.isPublished());
    }

    private record ChipHit(Evidence.NavigationTarget target, int left, int top, int right, int bottom) { }
    private record SourceHit(Evidence.Source source, ChatContracts.Turn turn, int left, int top, int right, int bottom) { }
    private record MessageAction(ChatContracts.Turn turn, boolean user, boolean edit, int x, int y, int width) { }
}
