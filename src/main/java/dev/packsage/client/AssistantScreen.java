package dev.packsage.client;

import dev.packsage.chat.ChatActions;
import dev.packsage.chat.ChatContracts;
import dev.packsage.knowledge.Evidence;
import dev.packsage.model.ModelBackend;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.locale.Language;

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
    private ChatContracts.View view;
    private boolean awaitingRequestView;
    private String input = "";
    private boolean explicitWebSearch;
    private boolean includeScreenshot = true;
    private MultilineEditor inputEditor;
    private MultilineEditor sessionSearchEditor;
    private String sessionSearch = "";
    private Runnable settingsOpener = () -> { };
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

    public AssistantScreen(ChatActions actions) { this(actions, Component.literal("PackSage Assistant")); }

    public AssistantScreen(ChatActions actions, Component title) {
        super(title);
        this.actions = Objects.requireNonNull(actions);
        this.view = actions.view();
    }

    @Override
    protected void init() {
        if (inputEditor != null) input = inputEditor.value();
        if (sessionSearchEditor != null) sessionSearch = sessionSearchEditor.value();
        sessionSearchEditor = new MultilineEditor(font, 12, 32, Math.max(1, ModernUi.SIDEBAR_WIDTH - 24), 28,
                80, false, () -> false);
        sessionSearchEditor.setValue(sessionSearch);
        sessionSearchEditor.setMessage(Component.literal("Search sessions"));
        sessionSearchEditor.leadingIcon = ResourceLocation.fromNamespaceAndPath("packsage", "textures/gui/icons/magnifying-glass.png");
        inputEditor = new MultilineEditor(font, 0, 0, 1, 28, QUESTION_MAX_LENGTH, true, () -> {
            submit();
            return true;
        });
        inputEditor.setValue(input);
        addRenderableWidget(sessionSearchEditor);
        addRenderableWidget(inputEditor);
        followOnCompletion = true;
        layout();
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
        graphics.fill(0, 0, width, height, ModernUi.BACKGROUND);
        graphics.fill(0, 0, sidebarWidth, height, ModernUi.SIDEBAR);
        drawSidebar(graphics, mouseX, mouseY);
        int contentTop = 48;
        int contentBottom = Math.max(contentTop + 1, toggleY - 8);
        graphics.enableScissor(readableLeft, contentTop, readableLeft + readableWidth, contentBottom);
        int contentOrigin = contentTop + 8;
        int y = contentOrigin - scrollOffset;
        y = drawConversation(graphics, y, contentBottom);
        graphics.disableScissor();
        updateContentMetrics(y + scrollOffset - contentOrigin + 4, contentBottom - contentTop);
        drawComposer(graphics, mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
        drawSendIcon(graphics);
    }

    void layout() {
        sidebarWidth = Math.min(ModernUi.SIDEBAR_WIDTH, Math.max(120, width / 3));
        int mainWidth = Math.max(1, width - sidebarWidth);
        readableWidth = Math.max(1, Math.min(ModernUi.MAX_CONTENT_WIDTH, mainWidth - 32));
        readableLeft = sidebarWidth + (mainWidth - readableWidth) / 2;
        if (sessionSearchEditor != null) {
            sessionSearchEditor.setSize(Math.max(1, sidebarWidth - 24), 28);
            sessionSearchEditor.setPosition(12, 32);
        }
        if (inputEditor != null) {
            int editorWidth = Math.max(1, readableWidth - 46);
            inputEditor.setSize(editorWidth, 28);
            composerHeight = Math.min(Math.min(MultilineEditor.MAX_HEIGHT, Math.max(28, height / 3)), inputEditor.preferredHeight());
            composerTop = Math.max(48, height - composerHeight - 28);
            inputEditor.setSize(editorWidth, composerHeight);
            inputEditor.setPosition(readableLeft, composerTop);
            inputEditor.active = !view.readOnly();
        } else {
            composerHeight = 28;
            composerTop = Math.max(48, height - composerHeight - 28);
        }
        toggleY = Math.max(0, composerTop - 26);
        sendX = readableLeft + readableWidth - 36;
        sendY = composerTop + composerHeight - 28;
    }

    private void drawSidebar(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, Component.literal("PackSage").withStyle(style ->
                style.withFont(ModernUi.FONT).withColor(ModernUi.TEXT)), 16, 14, ModernUi.TEXT, false);
        ModernUi.surface(graphics, 12, 68, sidebarWidth - 12, 96, ModernUi.ACCENT_DARK);
        graphics.blit(ResourceLocation.fromNamespaceAndPath("packsage", "textures/gui/icons/plus.png"),
                20, 76, 16, 16, 0.0F, 0.0F, 64, 64, 64, 64);
        graphics.drawString(font, Component.literal("New chat").withStyle(style ->
                style.withFont(ModernUi.FONT).withColor(ModernUi.TEXT)), 42, 68 + (28 - font.lineHeight) / 2 + 1, ModernUi.TEXT, false);
        drawSessionGroups(graphics, mouseX, mouseY);
        int controlY = Math.max(0, height - 44);
        ModernUi.surface(graphics, 12, controlY, 44, Math.min(height, controlY + 32),
                mouseX >= 12 && mouseX < 44 && mouseY >= controlY && mouseY < controlY + 32 ? ModernUi.SURFACE_HOVER : ModernUi.SURFACE);
        graphics.blit(ResourceLocation.fromNamespaceAndPath("packsage", "textures/gui/icons/gear.png"),
                20, controlY + 8, 16, 16, 0.0F, 0.0F, 64, 64, 64, 64);
        RuntimeState state = actualRuntimeState();
        if (state.privateSingleplayer() && state.integratedServer() && !state.published()) {
            ModernUi.surface(graphics, 52, controlY, 84, Math.min(height, controlY + 32),
                    mouseX >= 52 && mouseX < 84 && mouseY >= controlY && mouseY < controlY + 32 ? ModernUi.SURFACE_HOVER : ModernUi.SURFACE);
            graphics.blit(ResourceLocation.fromNamespaceAndPath("packsage", effectivePause(state)
                    ? "textures/gui/icons/play.png" : "textures/gui/icons/pause.png"),
                    60, controlY + 8, 16, 16, 0.0F, 0.0F, 64, 64, 64, 64);
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
            int count = expanded ? group.getValue().size() : Math.min(2, group.getValue().size());
            for (int index = 0; index < count; index++) {
                ChatContracts.SessionSummary session = group.getValue().get(index);
                rows.add(new SessionRow(world, session, session.title(), y, 38, false));
                y += 38;
            }
            if (group.getValue().size() > 2 && query.isEmpty()) {
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
                boolean selected = row.session().id().equals(view.conversation().id())
                        && row.world().worldOrServer().equals(view.conversation().world().worldOrServer());
                if (selected || mouseX >= 12 && mouseX < sidebarWidth - 12 && mouseY >= row.top() && mouseY < row.top() + 34) {
                    ModernUi.surface(graphics, 12, row.top(), sidebarWidth - 12, row.top() + 34, ModernUi.SURFACE_HOVER);
                }
                graphics.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(Component.literal(row.label().replaceAll("\\s+", " "))
                                .withStyle(style -> style.withFont(ModernUi.FONT)), Math.max(1, sidebarWidth - 48))),
                        24, row.top() + (34 - (row.session().lastMessage().isBlank() ? font.lineHeight
                                : ModernUi.MESSAGE_LINE_HEIGHT + font.lineHeight)) / 2 + 1, selected ? ModernUi.TEXT : ModernUi.MUTED, false);
                graphics.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(Component.literal(row.session().lastMessage().replaceAll("\\s+", " "))
                                .withStyle(style -> style.withFont(ModernUi.FONT)), Math.max(1, sidebarWidth - 48))),
                        24, row.top() + (34 - (ModernUi.MESSAGE_LINE_HEIGHT + font.lineHeight)) / 2 + 1 + ModernUi.MESSAGE_LINE_HEIGHT, ModernUi.MUTED, false);
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

    private int drawConversation(GuiGraphics graphics, int y, int contentBottom) {
        graphics.drawString(font, Component.literal(view.conversation().world().readableLabel()).withStyle(style ->
                style.withFont(ModernUi.BOLD_FONT).withColor(ModernUi.TEXT)), readableLeft, y, ModernUi.TEXT, false);
        y += 14;
        if (view.readOnly()) {
            graphics.drawString(font, Component.literal(view.activity().isEmpty() ? "Read-only history" : view.activity())
                    .withStyle(style -> style.withFont(ModernUi.FONT)), readableLeft, y, ModernUi.MUTED, false);
            y += ModernUi.MESSAGE_LINE_HEIGHT;
        }
        if (!view.readOnly()) {
            graphics.drawString(font, Component.literal(ScreenText.contextSummary(view.context())).withStyle(style ->
                    style.withFont(ModernUi.FONT).withColor(ModernUi.MUTED)), readableLeft, y, ModernUi.MUTED, false);
            y += 16;
        }
        chipHits.clear();
        sourceHits.clear();
        for (ChatContracts.Turn turn : view.conversation().turns()) {
            y = drawUserMessage(graphics, turn.question(), y);
            y += 7;
            y = drawAssistantMessage(graphics, turn.answer(), turn, y);
            String outcome = ScreenText.outcomeLine(turn.outcome());
            if (outcome != null) {
                graphics.drawString(font, Component.literal(outcome).withStyle(style ->
                        style.withFont(ModernUi.FONT).withColor(ModernUi.DANGER)), readableLeft + 32, y, ModernUi.DANGER, false);
                y += ModernUi.MESSAGE_LINE_HEIGHT;
            }
            y += 10;
        }
        if (view.active() && !view.activeQuestion().isEmpty()) y = drawUserMessage(graphics, view.activeQuestion(), y) + 7;
        if (view.active() || awaitingRequestView) {
            drawAssistantAvatar(graphics, y);
            int responseTop = y;
            if (!view.streamingAnswer().isEmpty()) y = drawInlineLines(graphics, ScreenText.inlineLines(view.streamingAnswer(), List.of()), y,
                    readableLeft + 32, readableWidth - 32, null);
            graphics.drawString(font, Component.literal(view.activity().isEmpty() ? "Working..." : view.activity())
                    .withStyle(style -> style.withFont(ModernUi.FONT)), readableLeft + 32, y + 2, ModernUi.ACCENT, false);
            y = Math.max(responseTop + 24, y + ModernUi.MESSAGE_LINE_HEIGHT);
        }
        if (!view.errors().isEmpty()) {
            y += 4;
            for (var line : font.split(Component.literal(view.errors().getLast()).withStyle(style ->
                    style.withFont(ModernUi.FONT).withColor(ModernUi.DANGER)), Math.max(1, readableWidth - 32))) {
                graphics.drawString(font, line, readableLeft + 32, y, ModernUi.DANGER, false);
                y += ModernUi.MESSAGE_LINE_HEIGHT;
            }
        }
        return Math.max(y, contentBottom - scrollOffset);
    }

    private int drawUserMessage(GuiGraphics graphics, String text, int y) {
        int bubbleWidth = Math.max(1, Math.min(360, readableWidth - 34));
        Component content = Component.literal(text).withStyle(style -> style.withFont(ModernUi.FONT).withColor(ModernUi.TEXT));
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(content, Math.max(1, bubbleWidth - 20));
        int bubbleHeight = Math.max(28, lines.size() * ModernUi.MESSAGE_LINE_HEIGHT + 14);
        int bubbleX = readableLeft + readableWidth - bubbleWidth - 28;
        ModernUi.surface(graphics, bubbleX, y, bubbleX + bubbleWidth, y + bubbleHeight, ModernUi.SURFACE_HOVER);
        int lineY = y + (bubbleHeight - ((lines.size() - 1) * ModernUi.MESSAGE_LINE_HEIGHT + font.lineHeight)) / 2 + 1;
        for (net.minecraft.util.FormattedCharSequence line : lines) {
            graphics.drawString(font, line, bubbleX + 10, lineY, ModernUi.TEXT, false);
            lineY += ModernUi.MESSAGE_LINE_HEIGHT;
        }
        int avatarX = readableLeft + readableWidth - 24;
        if (minecraft != null && minecraft.player != null) {
            PlayerFaceRenderer.draw(graphics, minecraft.getSkinManager().getInsecureSkin(minecraft.player.getGameProfile()),
                    avatarX, y + Math.max(0, bubbleHeight - 24), 24);
        } else {
            ModernUi.surface(graphics, avatarX, y + Math.max(0, bubbleHeight - 24), avatarX + 24,
                    y + Math.max(0, bubbleHeight - 24) + 24, ModernUi.ACCENT_DARK);
            graphics.drawString(font, Component.literal("You").withStyle(style ->
                    style.withFont(ModernUi.FONT).withColor(ModernUi.TEXT)), avatarX + 3,
                    y + Math.max(0, bubbleHeight - 24) + (24 - font.lineHeight) / 2 + 1, ModernUi.TEXT, false);
        }
        return y + bubbleHeight;
    }

    private int drawAssistantMessage(GuiGraphics graphics, String answer, ChatContracts.Turn turn, int y) {
        drawAssistantAvatar(graphics, y);
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
        return Math.max(y + 24, drawInlineLines(graphics, ScreenText.inlineLines(answer, targets, citations, turn.sources()), y,
                readableLeft + 32, readableWidth - 32, turn));
    }

    private void drawAssistantAvatar(GuiGraphics graphics, int y) {
        ModernUi.surface(graphics, readableLeft, y, readableLeft + 24, y + 24, ModernUi.ACCENT_DARK);
        graphics.drawString(font, Component.literal("Ps").withStyle(style ->
                style.withFont(ModernUi.BOLD_FONT).withColor(ModernUi.TEXT)), readableLeft + 4, y + (24 - font.lineHeight) / 2 + 1, ModernUi.TEXT, false);
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
        int toggleWidth = Math.max(1, Math.min(128, (readableWidth - 6) / 2));
        ModernUi.surface(graphics, readableLeft, toggleY, readableLeft + toggleWidth, toggleY + 20,
                explicitWebSearch ? ModernUi.ACCENT_DARK : ModernUi.SURFACE);
        graphics.blit(ResourceLocation.fromNamespaceAndPath("packsage", "textures/gui/icons/globe.png"),
                readableLeft + 7, toggleY + (20 - 13) / 2, 13, 13, 0.0F, 0.0F, 64, 64, 64, 64);
        if (toggleWidth >= 96) graphics.drawString(font, Component.literal("Search").withStyle(style ->
                style.withFont(ModernUi.FONT).withColor(ModernUi.TEXT)), readableLeft + 27, toggleY + (20 - font.lineHeight) / 2 + 1, ModernUi.TEXT, false);
        int screenshotLeft = readableLeft + toggleWidth + 6;
        ModernUi.surface(graphics, screenshotLeft, toggleY, screenshotLeft + toggleWidth, toggleY + 20,
                includeScreenshot ? ModernUi.ACCENT_DARK : ModernUi.SURFACE);
        graphics.blit(ResourceLocation.fromNamespaceAndPath("packsage", "textures/gui/icons/camera.png"),
                screenshotLeft + 7, toggleY + (20 - 13) / 2, 13, 13, 0.0F, 0.0F, 64, 64, 64, 64);
        if (toggleWidth >= 112) graphics.drawString(font, Component.literal("Screenshot").withStyle(style ->
                style.withFont(ModernUi.FONT).withColor(ModernUi.TEXT)), screenshotLeft + 27, toggleY + (20 - font.lineHeight) / 2 + 1, ModernUi.TEXT, false);
        ModernUi.surface(graphics, sendX, sendY, sendX + 28, sendY + 28,
                awaitingRequestView || view.active() ? ModernUi.ACCENT_DARK : view.readOnly() ? ModernUi.SURFACE : ModernUi.SURFACE_HOVER);
        if (view.readOnly()) {
            graphics.drawString(font, Component.literal("Read-only history").withStyle(style ->
                    style.withFont(ModernUi.FONT).withColor(ModernUi.MUTED)), readableLeft, toggleY + 27, ModernUi.MUTED, false);
        }
    }

    private void drawSendIcon(GuiGraphics graphics) {
        graphics.blit(ResourceLocation.fromNamespaceAndPath("packsage", "textures/gui/icons/"
                        + (awaitingRequestView || view.active() ? "stop" : "paper-plane-right") + ".png"),
                sendX + 6, sendY + 6, 16, 16, 0.0F, 0.0F, 64, 64, 64, 64);
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
        if (height > 0 && (mouseY < 48 || mouseY >= toggleY - 8)) return false;
        scrollOffset = Math.max(0, Math.min(maxScroll(), scrollOffset - (int) (scrollY * 20)));
        followBottom = scrollOffset >= maxScroll();
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
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
            if (mouseX >= 12 && mouseX < sidebarWidth - 12 && mouseY >= 68 && mouseY < 96) { newChat(); return true; }
            if (mouseX >= 12 && mouseX < sidebarWidth - 12 && mouseY >= 104 && mouseY < height - 56) {
                for (SessionRow row : sessionRows()) {
                    if (mouseY < row.top() || mouseY >= row.top() + row.height()) continue;
                    if (row.expand()) {
                        if (!expandedWorlds.add(row.world().worldOrServer())) expandedWorlds.remove(row.world().worldOrServer());
                        return true;
                    }
                    if (row.session() != null) {
                        setFocused(null);
                        actions.openSession(row.session());
                        scrollToBottom();
                        return true;
                    }
                }
            }
            int controlY = Math.max(0, height - 44);
            if (mouseX >= 12 && mouseX < 44 && mouseY >= controlY && mouseY < controlY + 32) {
                settingsOpener.run();
                return true;
            }
            RuntimeState state = actualRuntimeState();
            if (state.privateSingleplayer() && state.integratedServer() && !state.published()
                    && mouseX >= 52 && mouseX < 84 && mouseY >= controlY && mouseY < controlY + 32) {
                setPaused(!effectivePause(state));
                return true;
            }
        }
        if (!view.readOnly() && mouseY >= toggleY && mouseY < toggleY + 20) {
            int toggleWidth = Math.max(1, Math.min(128, (readableWidth - 6) / 2));
            if (mouseX >= readableLeft && mouseX < readableLeft + toggleWidth) { explicitWebSearch = !explicitWebSearch; return true; }
            int screenshotLeft = readableLeft + toggleWidth + 6;
            if (mouseX >= screenshotLeft && mouseX < screenshotLeft + toggleWidth) { includeScreenshot = !includeScreenshot; return true; }
        }
        if (mouseX >= sendX && mouseX < sendX + 28 && mouseY >= sendY && mouseY < sendY + 28) {
            if (awaitingRequestView || view.active()) stop();
            else submit();
            return true;
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
        awaitingRequestView = false;
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
    public void setRuntimeState(RuntimeState state) { runtimeState = Objects.requireNonNull(state); }
    public RuntimeState runtimeState() { return actualRuntimeState(); }
    public int scrollOffset() { return scrollOffset; }

    public void submit() {
        input = input();
        if (input.isBlank() || view.readOnly() || view.active() || awaitingRequestView) return;
        String question = input;
        setInput("");
        awaitingRequestView = true;
        actions.send(question, includeScreenshot, explicitWebSearch);
        scrollToBottom();
    }

    public void close() { }

    @Override
    public void onClose() {
        if (minecraft != null) super.onClose();
    }

    public void stop() { actions.stop(); }
    public void newChat() { sidebarScroll = 0; actions.newChat(); scrollToBottom(); }
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
}
