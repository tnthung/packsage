package dev.packsage.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import dev.packsage.chat.ChatActions;
import dev.packsage.chat.ChatContracts;
import dev.packsage.chat.PackSageSettings;
import dev.packsage.client.recipe.NativeRecipeCardSource;
import dev.packsage.client.recipe.PinnedCardState;
import dev.packsage.client.recipe.RecipeCard;
import dev.packsage.client.recipe.RecipeCardModel;
import dev.packsage.client.recipe.SlotStacks;
import dev.packsage.knowledge.Evidence;
import dev.packsage.model.GroundingInstructions;
import dev.packsage.model.ModelBackend;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.Registries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class AssistantScreen extends Screen {
    public record RuntimeState(boolean privateSingleplayer, boolean integratedServer, boolean published) { }
    public static final int QUESTION_MAX_LENGTH = 1000;
    private static final int SESSION_LIST_TOP = 84;
    private static final int ACTIVITY_ARGUMENTS_INPUT_LIMIT = 16_384;
    private static final int ACTIVITY_ARGUMENTS_DISPLAY_LIMIT = 2_048;
    private static final Gson ACTIVITY_ARGUMENTS_JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private final ChatActions actions;
    private final long openingStartNs = System.nanoTime();
    private boolean firstFrameRendered;
    private String preparedConversationId;
    private final CommandConfirmationPanel commandConfirmation;
    private final PackSageSettings settings;
    private ChatContracts.View view;
    private boolean awaitingRequestView;
    String pendingQuestion = "";
    private Instant pendingSentAt;
    private String input = "";
    private String navigationError;
    private long navigationErrorExpiresAt;
    private boolean navigationNotice;
    private boolean explicitWebSearch;
    private boolean includeScreenshot = true;
    private MultilineEditor inputEditor;
    private MultilineEditor sessionSearchEditor;
    private String sessionSearch = "";
    private Runnable settingsOpener = () -> { };
    private Runnable advancedSettingsOpener = () -> { };
    private Runnable settingsSaver = () -> { };
    private net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> containerScreen;
    private RuntimeState runtimeState = new RuntimeState(true, true, false);
    private int scrollOffset;
    private int contentHeight;
    private int viewportHeight = 1;
    private boolean followBottom = true;
    private boolean followOnCompletion;
    private List<ChatContracts.SessionSummary> sessions = List.of();
    private boolean moreSessions;
    private boolean sessionsLoading;
    private boolean sessionRefreshPending;
    private int sessionOffset;
    private ChatContracts.WorldIdentity catalogWorld;
    private boolean catalogOtherWorlds;
    private final List<ChipHit> chipHits = new ArrayList<>();
    private final List<SourceHit> sourceHits = new ArrayList<>();
    private final List<ItemHit> itemHits = new ArrayList<>();
    private final List<CardHit> cardHits = new ArrayList<>();
    private PinnedCardState pinnedCard = new PinnedCardState();
    private int drawMouseX;
    private int drawMouseY;
    private CardHit panelCard;
    private PinnedCardState.Rect panelRect;
    private final List<WebLinkHit> webLinkHits = new ArrayList<>();
    private Evidence.Source tooltipSource;
    private ChatContracts.Turn tooltipTurn;
    private List<String> sourceTooltip = List.of();
    private final List<ActivityHit> activityHits = new ArrayList<>();
    private final Map<String, ActivityExpansion> activityExpansion = new LinkedHashMap<>();
    private final Map<ConversationKey, ConversationUiState> conversationStates = new HashMap<>();
    private final Set<ConversationKey> deletedConversationKeys = new HashSet<>();
    private final Map<ChatContracts.RequestId, CachedSourceLabels> sourceLabelCache = new HashMap<>();
    private final Map<ChatContracts.RequestId, PreparedTurn> preparedTurns = new HashMap<>();
    private final Map<String, UserLines> preparedUserLines = new LinkedHashMap<>();
    private int sidebarScroll;
    private int sidebarContentHeight;
    private long catalogGeneration;
    private int sidebarWidth;
    private int readableLeft;
    private int readableWidth;
    private int composerTop;
    private int composerHeight;
    private int sendX;
    private int stopX;
    private int sendY;
    private int toggleY;
    private ChatContracts.RequestId editingRequest;
    private ChatContracts.RequestId resendingRequest;
    private String picker;
    private int pickerScroll;
    private int adapterScroll;
    private int adapterPopupLeft;
    private int adapterPopupTop;
    private int adapterPopupWidth;
    private int adapterPopupHeight;
    private boolean adapterPopupVisible;
    private boolean adapterPopupRequested;
    private String adapterRowsKey = "";
    private String hoveredPickerChoice;
    private long pickerHoverStarted;
    private ChatContracts.SessionSummary pendingDelete;
    private boolean deletingSession;
    private ChatContracts.HistoryPreview history;
    private int historyTurns;
    private int historicalTokens;
    private String historyModel;
    private String historyProviderModel;
    private int visibleTurnCount = 4;
    private boolean historyPrepended;
    private final List<MessageAction> messageActions = new ArrayList<>();

    public AssistantScreen(ChatActions actions) { this(actions, Component.literal("PackSage Assistant")); }

    public AssistantScreen(ChatActions actions, Component title) {
        super(title);
        this.actions = Objects.requireNonNull(actions);
        commandConfirmation = new CommandConfirmationPanel(actions);
        this.settings = actions.settings();
        this.view = actions.view();
        long eventNs = System.nanoTime();
        dev.packsage.PackSageLog.LOGGER.info("PackSage timing opening={} phase=screen_constructed conversation={} start_ns={} event_ns={} elapsed_ms={}",
                openingStartNs, view.conversation().id(), openingStartNs, eventNs, (eventNs - openingStartNs) / 1_000_000.0);
    }

    @Override
    protected void init() {
        preparedTurns.clear();
        preparedUserLines.clear();
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
        loadSessions();
        long eventNs = System.nanoTime();
        dev.packsage.PackSageLog.LOGGER.info("PackSage timing opening={} phase=screen_init_done conversation={} start_ns={} event_ns={} elapsed_ms={}",
                openingStartNs, view.conversation().id(), openingStartNs, eventNs, (eventNs - openingStartNs) / 1_000_000.0);
    }

    public void loadSessions() {
        loadSessionPage(false);
    }

    private void loadSessionPage(boolean more) {
        if (!Objects.equals(catalogWorld, view.activeWorld()) || catalogOtherWorlds != settings.showChatsFromOtherSessions()) {
            catalogGeneration++;
            catalogWorld = view.activeWorld();
            catalogOtherWorlds = settings.showChatsFromOtherSessions();
            sessionsLoading = false;
            sessionRefreshPending = false;
            sessions = List.of();
            sessionOffset = 0;
            moreSessions = false;
        }
        if (sessionsLoading) {
            if (!more) sessionRefreshPending = true;
            return;
        }
        sessionsLoading = true;
        long generation = ++catalogGeneration;
        long pageStartNs = System.nanoTime();
        try {
            actions.sessionPage(more ? sessionOffset : 0, more ? 10 : Math.max(settings.sessionsBeforeCollapse(), sessionOffset))
                    .toCompletableFuture().orTimeout(5, java.util.concurrent.TimeUnit.SECONDS).whenComplete((page, failure) -> {
                Runnable update = () -> {
                    if (generation != catalogGeneration) return;
                    sessionsLoading = false;
                    long eventNs = System.nanoTime();
                    dev.packsage.PackSageLog.LOGGER.info("PackSage timing opening={} phase=sidebar_page_done start_ns={} event_ns={} elapsed_ms={} success={}",
                            openingStartNs, pageStartNs, eventNs, (eventNs - pageStartNs) / 1_000_000.0, failure == null);
                    if (failure != null) {
                        showNavigationError("Chat history could not be loaded: " + (failure.getMessage() == null
                                ? failure.getClass().getSimpleName() : failure.getMessage()));
                    } else {
                        List<ChatContracts.SessionSummary> next = new ArrayList<>(more ? sessions : List.of());
                        for (var session : page.sessions()) {
                            next.removeIf(existing -> existing.id().equals(session.id())
                                    && existing.world().worldOrServer().equals(session.world().worldOrServer()));
                            next.add(session);
                        }
                        sessions = List.copyOf(next);
                        sessionOffset = page.nextOffset();
                        moreSessions = page.hasMore();
                    }
                    if (sessionRefreshPending) {
                        sessionRefreshPending = false;
                        loadSessions();
                    }
                };
                if (minecraft == null) update.run();
                else minecraft.execute(update);
            });
        } catch (RuntimeException failure) {
            if (generation == catalogGeneration) {
                sessionsLoading = false;
                showNavigationError("Chat history could not be loaded: " + failure.getMessage());
            }
        }
    }

    public void refreshSessions(List<ChatContracts.SessionSummary> next) {
        catalogGeneration++;
        sessionsLoading = false;
        sessionRefreshPending = false;
        sessions = List.copyOf(next);
        sessionOffset = sessions.size();
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The custom render path applies its backdrop before the content.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        drawMouseX = mouseX;
        drawMouseY = mouseY;
        graphics.flush();
        renderBlurredBackground(partialTick);
        layout();
        prepareConversation();
        graphics.fill(0, 0, width, height, ModernUi.BACKGROUND);
        graphics.fill(0, 0, sidebarWidth, height, ModernUi.SIDEBAR);
        drawSidebar(graphics, mouseX, mouseY);
        int contentTop = 40;
        int contentBottom = Math.max(contentTop + 1, toggleY - 8);
        graphics.enableScissor(readableLeft, contentTop, readableLeft + readableWidth, contentBottom);
        int contentOrigin = contentTop + 8;
        int y = contentOrigin - scrollOffset;
        y = drawConversation(graphics, y, contentBottom, mouseX, mouseY);
        y = commandConfirmation.render(graphics, font, readableLeft + 26, y, Math.max(1, readableWidth - 26), contentTop, contentBottom);
        graphics.disableScissor();
        updateContentMetrics(y + scrollOffset - contentTop + 4, contentBottom - contentTop);
        drawComposer(graphics, mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
        drawSendIcon(graphics);
        if (view.active() || awaitingRequestView) graphics.blit(
                ResourceLocation.fromNamespaceAndPath("packsage", "textures/gui/icons/stop.png"),
                stopX + 4, sendY + 4, 12, 12, 0.0F, 0.0F, 64, 64, 64, 64);
        drawPicker(graphics, mouseX, mouseY);
        drawToolbarTooltip(graphics, mouseX, mouseY);
        drawSourceTooltip(graphics, mouseX, mouseY);
        drawRecipeTooltips(graphics, mouseX, mouseY);
        acknowledgeRenderedUnread(minecraft != null && minecraft.screen == this);
        if (!firstFrameRendered) {
            firstFrameRendered = true;
            long eventNs = System.nanoTime();
            dev.packsage.PackSageLog.LOGGER.info("PackSage timing opening={} phase=first_frame_rendered conversation={} start_ns={} event_ns={} elapsed_ms={} loading={}",
                    openingStartNs, view.conversation().id(), openingStartNs, eventNs, (eventNs - openingStartNs) / 1_000_000.0,
                    view.activity().equals("Loading session..."));
        }
    }

    @Override
    public Component getNarrationMessage() {
        List<String> unread = sessionRows().stream().filter(row -> row.session() != null
                        && row.session().unreadRequestId() != null && row.top() + row.height() > SESSION_LIST_TOP
                        && row.top() < Math.max(SESSION_LIST_TOP + 1, height - 56))
                .map(row -> "Unread completed response in " + row.label()).toList();
        if (unread.isEmpty()) return super.getNarrationMessage();
        return Component.literal(super.getNarrationMessage().getString() + ". " + String.join(". ", unread));
    }

    private void acknowledgeRenderedUnread(boolean currentScreen) {
        if (!currentScreen || view.active() || view.activity().equals("Loading session...")) return;
        for (ChatContracts.SessionSummary session : sessions) {
            ChatContracts.RequestId unreadRequestId = session.unreadRequestId();
            if (unreadRequestId != null && !session.active()
                    && session.world().worldOrServer().equals(view.conversation().world().worldOrServer())
                    && session.id().equals(view.conversation().id())
                    && view.conversation().turns().stream().anyMatch(turn -> turn.requestId().equals(unreadRequestId)
                    && turn.outcome().status() == ChatContracts.OutcomeStatus.COMPLETED)) {
                actions.markRead(session, unreadRequestId);
            }
        }
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
            int meterWidth = view.active() || awaitingRequestView
                    ? Math.max(1, Math.min(43, (readableWidth - 84) / 2))
                    : Math.max(2, Math.min(86, readableWidth)) / 2;
            int editorWidth = Math.max(1, readableWidth - (view.active() || awaitingRequestView ? 84 : 58) - meterWidth);
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
        stopX = sendX - 26;
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
        graphics.blit(ResourceLocation.fromNamespaceAndPath("packsage", "textures/gui/icons/info.png"),
                16, controlY + 4, 12, 12, 0.0F, 0.0F, 64, 64, 64, 64);
        ModernUi.surface(graphics, 40, controlY, 60, Math.min(height, controlY + 20),
                mouseX >= 40 && mouseX < 60 && mouseY >= controlY && mouseY < controlY + 20 ? ModernUi.SURFACE_HOVER : ModernUi.SURFACE);
        graphics.blit(ResourceLocation.fromNamespaceAndPath("packsage", "textures/gui/icons/gear.png"),
                44, controlY + 4, 12, 12, 0.0F, 0.0F, 64, 64, 64, 64);
        RuntimeState state = actualRuntimeState();
        if (state.privateSingleplayer() && state.integratedServer() && !state.published()) {
            ModernUi.surface(graphics, 68, controlY, 88, Math.min(height, controlY + 20),
                    mouseX >= 68 && mouseX < 88 && mouseY >= controlY && mouseY < controlY + 20 ? ModernUi.SURFACE_HOVER : ModernUi.SURFACE);
            graphics.blit(ResourceLocation.fromNamespaceAndPath("packsage", effectivePause(state)
                    ? "textures/gui/icons/play.png" : "textures/gui/icons/pause.png"),
                    72, controlY + 4, 12, 12, 0.0F, 0.0F, 64, 64, 64, 64);
        }
    }

    record SessionRow(ChatContracts.WorldIdentity world, ChatContracts.SessionSummary session, String label,
                      int top, int height, boolean expand) { }

    List<SessionRow> sessionRows() {
        String query = (sessionSearchEditor == null ? sessionSearch : sessionSearchEditor.value()).trim().toLowerCase(java.util.Locale.ROOT);
        Map<String, List<ChatContracts.SessionSummary>> groups = new LinkedHashMap<>();
        for (ChatContracts.SessionSummary session : sessions) {
            if (!settings.showChatsFromOtherSessions()
                    && !session.world().worldOrServer().equals(view.activeWorld().worldOrServer())) continue;
            if (!query.isEmpty() && !session.title().toLowerCase(java.util.Locale.ROOT).contains(query)
                    && !session.lastMessage().toLowerCase(java.util.Locale.ROOT).contains(query)
                    && !session.world().readableLabel().toLowerCase(java.util.Locale.ROOT).contains(query)) continue;
            groups.computeIfAbsent(session.world().worldOrServer(), ignored -> new ArrayList<>()).add(session);
        }
        List<Map.Entry<String, List<ChatContracts.SessionSummary>>> ordered = new ArrayList<>(groups.entrySet());
        ordered.sort((first, second) -> Boolean.compare(!first.getKey().equals(view.activeWorld().worldOrServer()),
                !second.getKey().equals(view.activeWorld().worldOrServer())));
        int y = SESSION_LIST_TOP - sidebarScroll;
        List<SessionRow> rows = new ArrayList<>();
        for (Map.Entry<String, List<ChatContracts.SessionSummary>> group : ordered) {
            ChatContracts.WorldIdentity world = group.getKey().equals(view.activeWorld().worldOrServer())
                    ? view.activeWorld() : group.getValue().getFirst().world();
            rows.add(new SessionRow(world, null, world.readableLabel(), y, 24, false));
            y += 24;
            for (int index = 0; index < group.getValue().size(); index++) {
                ChatContracts.SessionSummary session = group.getValue().get(index);
                rows.add(new SessionRow(world, session, session.title(), y, 38, false));
                y += 38;
            }
            y += 8;
        }
        if (moreSessions) {
            rows.add(new SessionRow(view.activeWorld(), null, sessionsLoading ? "Loading chats..." : "Show more", y, 24, true));
            y += 24;
        }
        sidebarContentHeight = y + sidebarScroll - SESSION_LIST_TOP;
        return rows;
    }

    private void drawSessionGroups(GuiGraphics graphics, int mouseX, int mouseY) {
        List<SessionRow> rows = sessionRows();
        int bottom = Math.max(SESSION_LIST_TOP + 1, height - 56);
        int maxScroll = Math.max(0, sidebarContentHeight - (bottom - SESSION_LIST_TOP));
        if (sidebarScroll > maxScroll) {
            sidebarScroll = maxScroll;
            rows = sessionRows();
        }
        graphics.enableScissor(0, SESSION_LIST_TOP, sidebarWidth, bottom);
        for (SessionRow row : rows) {
            if (row.top() + row.height() <= SESSION_LIST_TOP || row.top() >= bottom) continue;
            if (row.session() == null) {
                if (!row.expand()) graphics.blit(ResourceLocation.fromNamespaceAndPath("packsage", "textures/gui/icons/globe.png"),
                        16, row.top() + (row.height() - 14) / 2, 14, 14, 0.0F, 0.0F, 64, 64, 64, 64);
                graphics.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(Component.literal(row.label())
                                .withStyle(style -> style.withFont(ModernUi.FONT)), Math.max(1, sidebarWidth - (row.expand() ? 40 : 52)))),
                        row.expand() ? 24 : 36, row.top() + (row.height() - font.lineHeight) / 2 + 1,
                        row.expand() || row.world().equals(view.activeWorld()) ? ModernUi.ACCENT : ModernUi.MUTED, false);
            } else {
                if (row.session().equals(pendingDelete) && !row.session().active()) {
                    graphics.drawString(font, Component.literal("Delete session?").withStyle(style -> style.withFont(ModernUi.FONT)),
                            24, row.top() + 1, ModernUi.TEXT, false);
                    for (int index = 0; index < 2; index++) {
                        int buttonWidth = Math.max(1, Math.min(54, (sidebarWidth - 42) / 2));
                        int x = 18 + index * (buttonWidth + 6);
                        ModernUi.button(graphics, font, index == 0 ? "Delete" : "Cancel", x, row.top() + 15, buttonWidth, 17,
                                ModernUi.hovered(mouseX, mouseY, x, row.top() + 15, buttonWidth, 17) ? ModernUi.SURFACE_HOVER : ModernUi.SURFACE,
                                index == 0 ? ModernUi.DANGER : ModernUi.TEXT);
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
                if (row.session().unreadRequestId() != null) {
                    int titleY = row.top() + (34 - (row.session().lastMessage().isBlank() ? font.lineHeight
                            : ModernUi.MESSAGE_LINE_HEIGHT + font.lineHeight)) / 2 + 1;
                    graphics.fill(16, titleY + (font.lineHeight - 4) / 2, 20,
                            titleY + (font.lineHeight - 4) / 2 + 4, ModernUi.ACCENT);
                }
                if (!row.session().active() && !deletingSession) {
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
            int track = bottom - SESSION_LIST_TOP;
            int thumb = Math.max(12, track * track / (track + maxScroll));
            int y = SESSION_LIST_TOP + sidebarScroll * (track - thumb) / maxScroll;
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
        itemHits.clear();
        cardHits.clear();
        webLinkHits.clear();
        activityHits.clear();
        messageActions.clear();
        if (history == null) prepareConversation();
        for (ChatContracts.Turn turn : view.conversation().turns().subList(
                Math.max(0, view.conversation().turns().size() - visibleTurnCount), view.conversation().turns().size())) {
            if (view.active() && turn.requestId().equals(resendingRequest)) continue;
            boolean included = !view.readOnly() && history.selection().turns().stream()
                    .anyMatch(selected -> selected.requestId().equals(turn.requestId()));
            ChatContracts.TokenCounts tokens = prepareTurn(turn).tokens();
            y = drawUserMessage(graphics, turn.question(), y, included);
            y = drawMessageActions(graphics, turn, true, tokens.user(), y, mouseX, mouseY);
            y += 7;
            y = drawActivityRows(graphics, turn.activities(), turn.replayItems(), "request:" + turn.requestId().value(), switch (turn.outcome().status()) {
                case ACTIVE -> ChatContracts.ActivityStatus.RUNNING;
                case COMPLETED -> ChatContracts.ActivityStatus.COMPLETED;
                case CANCELLED -> ChatContracts.ActivityStatus.CANCELLED;
                case FAILED -> ChatContracts.ActivityStatus.FAILED;
            }, y);
            y = drawAnswerSequence(graphics, turn.answer(), turn.steeringMessages(), turn, y, included);
            y = drawMessageActions(graphics, turn, false, tokens.assistant(), y, mouseX, mouseY);
            String outcome = ScreenText.outcomeLine(turn.outcome());
            if (outcome != null) {
                for (var line : font.split(Component.literal(outcome).withStyle(style ->
                        style.withFont(ModernUi.FONT).withColor(ModernUi.DANGER)), Math.max(1, readableWidth - 26))) {
                    graphics.drawString(font, line, readableLeft + 26, y, ModernUi.DANGER, false);
                    y += ModernUi.MESSAGE_LINE_HEIGHT;
                }
            }
            y += 10;
        }
        String liveQuestion = view.active() ? view.activeQuestion() : awaitingRequestView ? pendingQuestion : "";
        ChatContracts.Turn liveTurn = new ChatContracts.Turn(new ChatContracts.RequestId("active-preview"),
                liveQuestion, view.streamingAnswer(), List.of(), view.context(), List.of(), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.ACTIVE, null), 0,
                view.activeSentAt() == null ? pendingSentAt : view.activeSentAt(), List.of(),
                view.active() ? view.activeSteeringMessages() : List.of());
        if (!liveQuestion.isEmpty()) {
            y = drawUserMessage(graphics, liveQuestion, y, false);
            y = drawMessageActions(graphics, liveTurn, true, GroundingInstructions.historyTokenCounts(liveTurn).user(), y, mouseX, mouseY) + 7;
        }
        if (view.active() || awaitingRequestView) {
            drawAssistantAvatar(graphics, y);
            int responseTop = y;
            y = drawActivityRows(graphics, view.activities(), List.of(), "request:" + (view.activeRequestId() == null
                    ? view.activeQuestion() + ":" + view.activeSentAt() : view.activeRequestId()),
                    ChatContracts.ActivityStatus.RUNNING, y);
            y = drawAnswerSequence(graphics, view.streamingAnswer(), view.activeSteeringMessages(), liveTurn, y, false);
            graphics.drawString(font, Component.literal(view.activity().isEmpty() ? "Working..." : view.activity())
                    .withStyle(style -> style.withFont(ModernUi.FONT)), readableLeft + 26, y + 2, ModernUi.ACCENT, false);
            y = Math.max(responseTop + 18, y + ModernUi.MESSAGE_LINE_HEIGHT);
            if (!view.streamingAnswer().isEmpty()) y = drawMessageActions(graphics, liveTurn, false,
                    GroundingInstructions.historyTokenCounts(liveTurn).assistant(), y, mouseX, mouseY);
        }
        String visibleError = navigationError(System.currentTimeMillis());
        int errorColor = visibleError != null && navigationNotice ? ModernUi.MUTED : ModernUi.DANGER;
        if (visibleError == null && !view.errors().isEmpty()) visibleError = view.errors().getLast();
        if (visibleError != null) {
            y += 4;
            for (var line : font.split(Component.literal(visibleError).withStyle(style ->
                    style.withFont(ModernUi.FONT).withColor(errorColor)), Math.max(1, readableWidth - 26))) {
                graphics.drawString(font, line, readableLeft + 26, y, errorColor, false);
                y += ModernUi.MESSAGE_LINE_HEIGHT;
            }
        }
        return Math.max(y, contentBottom - scrollOffset);
    }

    private int drawActivityRows(GuiGraphics graphics, List<ChatContracts.ActivityStep> steps,
                                 List<ModelBackend.InputItem> replayItems, String prefix,
                                 ChatContracts.ActivityStatus status, int y) {
        if (steps.isEmpty()) return y;
        MessageLayout group = activityGroupHeader(prefix, steps.size(), status, y);
        int left = readableLeft + 26;
        for (MessageRun run : group.runs()) {
            ScreenText.InlineSpan span = run.span();
            graphics.drawString(font, Component.literal(span.text()).withStyle(style -> style
                            .withFont(span.bold() ? ModernUi.BOLD_FONT : ModernUi.FONT).withColor(span.color())),
                    left + run.x(), y + run.y(), span.color(), false);
        }
        y += group.height();
        if (!activityExpanded(prefix)) return y;
        for (ChatContracts.ActivityStep step : steps) {
            String key = prefix + ":" + step.id();
            MessageLayout header = activityHeader(key, step, y);
            for (MessageRun run : header.runs()) {
                ScreenText.InlineSpan span = run.span();
                graphics.drawString(font, Component.literal(span.text()).withStyle(style -> style
                                .withFont(span.bold() ? ModernUi.BOLD_FONT : ModernUi.FONT).withColor(span.color())),
                        left + run.x(), y + run.y(), span.color(), false);
            }
            y += header.height();
            if (activityExpanded(key)) {
                y = drawInlineLines(graphics, messageLayout(activityBodyLines(step, replayItems),
                        Math.max(1, readableWidth - 38)), y, left + 10, null);
            }
        }
        return y;
    }

    List<ScreenText.InlineLine> activityBodyLines(ChatContracts.ActivityStep step, List<ModelBackend.InputItem> replayItems) {
        List<ScreenText.InlineLine> lines = new ArrayList<>();
        for (String line : activityBody(step, replayItems).split("\n", -1)) {
            int colon = line.indexOf(':');
            if (colon >= 0 && List.of("Tool", "Parameter", "Result", "Error", "Elapsed").contains(line.substring(0, colon))) {
                lines.add(new ScreenText.InlineLine(List.of(
                        new ScreenText.InlineSpan(line.substring(0, colon + 1), null, ModernUi.MUTED, false, false),
                        new ScreenText.InlineSpan(line.substring(colon + 1), null, ModernUi.TEXT, false, false))));
            } else lines.add(new ScreenText.InlineLine(List.of(new ScreenText.InlineSpan(line, null, ModernUi.TEXT, false, false))));
        }
        return lines;
    }

    String activityBody(ChatContracts.ActivityStep step, List<ModelBackend.InputItem> replayItems) {
        String detail = step.detail().isBlank() ? switch (step.status()) {
            case RUNNING -> step.id().startsWith("tool:") ? "Running tool…"
                    : step.id().startsWith("search:") ? "Searching the web…" : "Waiting for a summary…";
            case COMPLETED -> step.id().startsWith("reasoning:") ? "No summary was provided."
                    : step.id().startsWith("search:") ? "Search completed." : "Completed.";
            case FAILED -> "Step failed.";
            case CANCELLED -> "Step cancelled.";
        } : step.detail();
        if (!step.id().startsWith("tool:")) return detail;
        String toolName = step.toolName();
        String arguments = step.serializedArguments();
        if (arguments == null || toolName == null) {
            for (ModelBackend.InputItem item : replayItems) {
                if (item instanceof ModelBackend.ToolCall call && call.callId().equals(step.id().substring("tool:".length()))) {
                    if (arguments == null) arguments = call.serializedArguments();
                    if (toolName == null) toolName = call.name();
                    break;
                }
            }
        }
        String parameters;
        if (arguments == null) parameters = "Arguments unavailable.";
        else if (arguments.length() > ACTIVITY_ARGUMENTS_INPUT_LIMIT) parameters = "Arguments unavailable (input exceeds display limit).";
        else {
            int nesting = 0;
            boolean quoted = false;
            boolean escaped = false;
            boolean tooDeep = false;
            for (int index = 0; index < arguments.length(); index++) {
                char character = arguments.charAt(index);
                if (quoted) {
                    if (escaped) escaped = false;
                    else if (character == '\\') escaped = true;
                    else if (character == '"') quoted = false;
                } else if (character == '"') quoted = true;
                else if (character == '{' || character == '[') {
                    nesting++;
                    if (nesting > 32) tooDeep = true;
                } else if (character == '}' || character == ']') nesting--;
            }
            if (tooDeep) parameters = "Arguments unavailable (nesting exceeds display limit).";
            else {
                try {
                    var parsed = JsonParser.parseString(arguments);
                    parameters = parsed.isJsonObject() ? ACTIVITY_ARGUMENTS_JSON.toJson(parsed)
                            : "Arguments unavailable (malformed JSON object).";
                } catch (RuntimeException failure) {
                    parameters = "Arguments unavailable (malformed JSON).";
                }
            }
        }
        String body = "Tool: " + (toolName == null ? "Unknown" : toolName) + "\nParameter: " + parameters;
        if (body.length() > ACTIVITY_ARGUMENTS_DISPLAY_LIMIT) {
            String marker = "… [truncated]";
            int end = ACTIVITY_ARGUMENTS_DISPLAY_LIMIT - marker.length();
            if (Character.isHighSurrogate(body.charAt(end - 1)) && Character.isLowSurrogate(body.charAt(end))) end--;
            body = body.substring(0, end) + marker;
        }
        if (step.status() == ChatContracts.ActivityStatus.FAILED) body += "\nError: " + detail;
        else if (step.resultDetail() != null && !step.resultDetail().isBlank()) body += "\nResult: " + step.resultDetail();
        if (step.durationNanos() != null) {
            body += "\nElapsed: " + (step.durationNanos() < 1_000_000 ? step.durationNanos() / 1_000 + " µs"
                    : step.durationNanos() < 1_000_000_000 ? step.durationNanos() / 1_000_000 + " ms"
                    : String.format(Locale.ROOT, "%.1f s", step.durationNanos() / 1_000_000_000.0));
        } else if (step.durationMillis() != null) {
            body += "\nElapsed: " + (step.durationMillis() < 1_000 ? step.durationMillis() + " ms"
                    : String.format(Locale.ROOT, "%.1f s", step.durationMillis() / 1000.0));
        }
        return body;
    }

    void toggleActivity(String key) {
        ActivityExpansion state = activityExpansion.get(key);
        if (state != null) activityExpansion.put(key, new ActivityExpansion(state.status(), !state.expanded()));
    }

    void updateActivityExpansion(String key, ChatContracts.ActivityStatus status) {
        ActivityExpansion previous = activityExpansion.get(key);
        boolean terminal = status != ChatContracts.ActivityStatus.RUNNING;
        boolean expanded = previous == null ? !terminal : previous.status() == ChatContracts.ActivityStatus.RUNNING
                && terminal ? false : previous.expanded();
        activityExpansion.put(key, new ActivityExpansion(status, expanded));
    }

    MessageLayout activityHeader(String key, ChatContracts.ActivityStep step, int y) {
        updateActivityExpansion(key, step.status());
        List<ScreenText.InlineSpan> spans = new ArrayList<>();
        spans.add(new ScreenText.InlineSpan(activityExpanded(key) ? "v " : "> ", null, ModernUi.MUTED, false, false));
        spans.add(new ScreenText.InlineSpan(step.title(), null,
                step.status() == ChatContracts.ActivityStatus.FAILED ? ModernUi.DANGER : ModernUi.TEXT, false, false));
        String suffix = switch (step.status()) {
            case RUNNING -> "";
            case COMPLETED -> " (done)";
            case FAILED -> " (failed)";
            case CANCELLED -> " (cancelled)";
        };
        if (!suffix.isEmpty()) spans.add(new ScreenText.InlineSpan(suffix, null, ModernUi.MUTED, false, false));
        MessageLayout layout = messageLayout(List.of(new ScreenText.InlineLine(spans)), Math.max(1, readableWidth - 26));
        activityHits.removeIf(hit -> hit.key().equals(key));
        activityHits.add(new ActivityHit(key, readableLeft + 26, y, readableLeft + readableWidth, y + layout.height()));
        return layout;
    }

    MessageLayout activityGroupHeader(String key, int stepCount, ChatContracts.ActivityStatus status, int y) {
        updateActivityExpansion(key, status);
        List<ScreenText.InlineSpan> spans = new ArrayList<>();
        spans.add(new ScreenText.InlineSpan(activityExpanded(key) ? "v " : "> ", null, ModernUi.MUTED, false, false));
        spans.add(new ScreenText.InlineSpan(stepCount + (stepCount == 1 ? " step" : " steps"), null,
                status == ChatContracts.ActivityStatus.FAILED ? ModernUi.DANGER : ModernUi.TEXT, false, false));
        String suffix = switch (status) {
            case RUNNING -> "";
            case COMPLETED -> " (done)";
            case FAILED -> " (failed)";
            case CANCELLED -> " (cancelled)";
        };
        if (!suffix.isEmpty()) spans.add(new ScreenText.InlineSpan(suffix, null, ModernUi.MUTED, false, false));
        MessageLayout layout = messageLayout(List.of(new ScreenText.InlineLine(spans)), Math.max(1, readableWidth - 26));
        activityHits.removeIf(hit -> hit.key().equals(key) || hit.key().startsWith(key + ":"));
        activityHits.add(new ActivityHit(key, readableLeft + 26, y, readableLeft + readableWidth, y + layout.height()));
        return layout;
    }

    boolean activityExpanded(String key) {
        ActivityExpansion state = activityExpansion.get(key);
        return state != null && state.expanded();
    }

    private int drawUserMessage(GuiGraphics graphics, String text, int y, boolean included) {
        int bubbleWidth = Math.max(1, Math.min(360, readableWidth - 26));
        List<net.minecraft.util.FormattedCharSequence> lines = userLines(text, Math.max(1, bubbleWidth - 20));
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
        graphics.fill(avatarX + 15, y + Math.max(0, bubbleHeight - 18) + 15, avatarX + 18,
                y + Math.max(0, bubbleHeight - 18) + 18, included ? 0xff74bf8c : ModernUi.DANGER);
        return y + bubbleHeight;
    }

    private int drawAnswerSequence(GuiGraphics graphics, String answer, List<ChatContracts.SteeringMessage> steeringMessages,
                                   ChatContracts.Turn turn, int y, boolean included) {
        for (MessageSegment segment : answerSegments(answer, steeringMessages)) {
            if (segment.player()) y = drawUserMessage(graphics, segment.text(), y, false);
            else y = drawAssistantSegment(graphics, answer, segment, turn, y, included);
        }
        return y;
    }

    record MessageSegment(String text, boolean player, int answerOffset) { }

    static List<MessageSegment> answerSegments(String answer, List<ChatContracts.SteeringMessage> steeringMessages) {
        List<MessageSegment> segments = new ArrayList<>();
        int answerOffset = 0;
        for (ChatContracts.SteeringMessage message : steeringMessages) {
            if (message.answerOffset() > answerOffset) {
                segments.add(new MessageSegment(answer.substring(answerOffset, message.answerOffset()), false, answerOffset));
            }
            segments.add(new MessageSegment(message.text(), true, message.answerOffset()));
            answerOffset = message.answerOffset();
        }
        if (answerOffset < answer.length()) segments.add(new MessageSegment(answer.substring(answerOffset), false, answerOffset));
        return List.copyOf(segments);
    }

    private int drawAssistantSegment(GuiGraphics graphics, String answer, MessageSegment segment,
                                     ChatContracts.Turn turn, int y, boolean included) {
        MessageLayout prepared = prepareTurn(turn).answers().get(segment);
        int bubbleWidth = Math.max(1, Math.min(360, readableWidth - 26));
        int bubbleHeight = Math.max(20, prepared.height() + 8);
        ModernUi.surface(graphics, readableLeft + 24, y, readableLeft + 24 + bubbleWidth, y + bubbleHeight, ModernUi.SURFACE_HOVER);
        drawInlineLines(graphics, prepared, y + 4, readableLeft + 34, turn);
        drawAssistantAvatar(graphics, y + Math.max(0, bubbleHeight - 18));
        graphics.fill(readableLeft + 15, y + bubbleHeight - 3, readableLeft + 18, y + bubbleHeight,
                included ? 0xff74bf8c : ModernUi.DANGER);
        return y + bubbleHeight;
    }

    private MessageLayout assistantLayout(String answer, MessageSegment segment, ChatContracts.Turn turn,
                                          List<Evidence.NavigationTarget> targets, int width, Map<Evidence.Source, RecipeCardModel> models,
                                          Map<RecipeCardModel, RecipeCard> cards, Map<RecipeCardModel, ItemStack> outputs,
                                          Map<String, ItemStack> items, StringBuilder copy) {
        List<ModelBackend.UrlCitation> citations = new ArrayList<>();
        int searchFrom = 0;
        int segmentEnd = segment.answerOffset() + segment.text().length();
        for (ModelBackend.InputItem item : turn.replayItems()) {
            if (item instanceof ModelBackend.AssistantOutput output && output.status() == ModelBackend.OutputStatus.COMPLETED) {
                for (ModelBackend.OutputContent content : output.content()) {
                    if (content instanceof ModelBackend.OutputText text) {
                        int offset = answer.indexOf(text.text(), searchFrom);
                        if (offset < 0) continue;
                        for (ModelBackend.UrlCitation citation : text.citations()) {
                            if (citation.startIndex() >= 0 && citation.endIndex() <= text.text().length()) {
                                int citationStart = Math.max(segment.answerOffset(), offset + citation.startIndex());
                                int citationEnd = Math.min(segmentEnd, offset + citation.endIndex());
                                if (citationStart < citationEnd) citations.add(new ModelBackend.UrlCitation(
                                        citationStart - segment.answerOffset(), citationEnd - segment.answerOffset(),
                                        citation.title(), citation.url()));
                            }
                        }
                        searchFrom = offset + text.text().length();
                    }
                }
            }
        }
        List<ScreenText.InlineLine> lines = turn.verbosity() == null
                ? ScreenText.inlineLines(segment.text(), targets, citations, turn.sources(), sourceLabels(turn))
                : ScreenText.inlineLines(segment.text(), targets, citations, turn.sources(), sourceLabels(turn), turn.verbosity(), models);
        if (turn.verbosity() != null) {
            for (int index = 0; index < lines.size(); index++) {
                if (index > 0) copy.append('\n');
                for (var span : lines.get(index).spans()) copy.append(span.text());
                for (var span : lines.get(index).spans()) {
                    if (span.itemId() == null || items.containsKey(span.itemId()) || minecraft == null || minecraft.level == null) continue;
                    var id = ResourceLocation.tryParse(span.itemId());
                    var item = id == null ? null : minecraft.level.registryAccess().lookupOrThrow(Registries.ITEM)
                            .get(ResourceKey.create(Registries.ITEM, id)).orElse(null);
                    items.put(span.itemId(), item == null ? null : new ItemStack(item, 1));
                }
            }
        }
        return messageLayout(lines, width, cards, outputs, items);
    }

    /** Shared by rendering and the headless profile; visits only the requested history window, newest first. */
    void prepareConversation() {
        long prepareStartNs = System.nanoTime();
        String providerModel = actions.providerSelectedModelId();
        if (history == null || historyTurns != settings.historyTurns() || historicalTokens != settings.historicalTokens()
                || !Objects.equals(historyModel, settings.modelId()) || !Objects.equals(historyProviderModel, providerModel)) {
            history = actions.historyPreview(editingRequest == null ? resendingRequest : editingRequest);
            historyTurns = settings.historyTurns();
            historicalTokens = settings.historicalTokens();
            historyModel = settings.modelId();
            historyProviderModel = providerModel;
        }
        List<ChatContracts.Turn> turns = view.conversation().turns();
        for (int index = turns.size() - 1; index >= Math.max(0, turns.size() - visibleTurnCount); index--) {
            prepareTurn(turns.get(index));
        }
        if (!view.activity().equals("Loading session...") && !view.conversation().id().equals(preparedConversationId)) {
            preparedConversationId = view.conversation().id();
            long eventNs = System.nanoTime();
            dev.packsage.PackSageLog.LOGGER.info("PackSage timing opening={} phase=screen_prepare_done conversation={} start_ns={} event_ns={} elapsed_ms={} opening_ms={} turns={}",
                    openingStartNs, preparedConversationId, prepareStartNs, eventNs, (eventNs - prepareStartNs) / 1_000_000.0,
                    (eventNs - openingStartNs) / 1_000_000.0, Math.min(visibleTurnCount, turns.size()));
        }
    }

    private PreparedTurn prepareTurn(ChatContracts.Turn turn) {
        int width = Math.max(1, Math.max(1, Math.min(360, readableWidth - 26)) - 20);
        PreparedTurn cached = preparedTurns.get(turn.requestId());
        if (cached != null && cached.turn() == turn && cached.width() == width) return cached;
        userLines(turn.question(), width);
        List<Evidence.NavigationTarget> targets = turnTargets(turn);
        Map<MessageSegment, MessageLayout> answers = new HashMap<>();
        Map<Evidence.Source, RecipeCardModel> models = Map.of();
        Map<RecipeCardModel, RecipeCard> cards = Map.of();
        Map<RecipeCardModel, ItemStack> outputs = Map.of();
        Map<String, ItemStack> items = Map.of();
        StringBuilder copy = null;
        if (turn.verbosity() != null) {
            models = ScreenText.recipeCards(turn);
            cards = new HashMap<>();
            outputs = new HashMap<>();
            items = new HashMap<>();
            copy = new StringBuilder();
            if (!models.isEmpty()) {
                var source = new NativeRecipeCardSource();
                for (var model : new HashSet<>(models.values())) {
                    var card = source.card(model).orElse(null);
                    if (card == null) continue;
                    cards.put(model, card);
                    if (turn.verbosity() == PackSageSettings.Verbosity.CONCISE && minecraft != null && minecraft.level != null) for (var slot : model.slots()) {
                        if (!slot.output() || slot.stacks().isEmpty()) continue;
                        var resolved = SlotStacks.resolve(slot.stacks().getFirst(), minecraft.level.registryAccess());
                        outputs.put(model, resolved.stacks().isEmpty() ? new ItemStack(Items.BARRIER) : resolved.stacks().getFirst());
                        break;
                    }
                }
            }
        }
        for (MessageSegment segment : answerSegments(turn.answer(), turn.steeringMessages())) {
            if (segment.player()) userLines(segment.text(), width);
            else answers.put(segment, assistantLayout(turn.answer(), segment, turn, targets, width, models, cards, outputs, items, copy));
        }
        PreparedTurn prepared = new PreparedTurn(turn, width, GroundingInstructions.historyTokenCounts(turn), Map.copyOf(answers),
                turn.verbosity() == null ? turn.answer() : copy.toString());
        if (turn.verbosity() == null || minecraft != null && minecraft.level != null) preparedTurns.put(turn.requestId(), prepared);
        return prepared;
    }

    private List<net.minecraft.util.FormattedCharSequence> userLines(String text, int width) {
        UserLines cached = preparedUserLines.get(text);
        if (cached != null && cached.width() == width) return cached.lines();
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.literal(text).withStyle(style ->
                style.withFont(ModernUi.FONT).withColor(ModernUi.TEXT)), width);
        if (preparedUserLines.size() >= 128) preparedUserLines.remove(preparedUserLines.keySet().iterator().next());
        preparedUserLines.put(text, new UserLines(width, lines));
        return lines;
    }

    private record PreparedTurn(ChatContracts.Turn turn, int width, ChatContracts.TokenCounts tokens,
                                Map<MessageSegment, MessageLayout> answers, String copy) { }
    private record UserLines(int width, List<net.minecraft.util.FormattedCharSequence> lines) { }

    private Map<Evidence.Source, String> sourceLabels(ChatContracts.Turn turn) {
        CachedSourceLabels cached = sourceLabelCache.get(turn.requestId());
        if (cached != null && cached.turn() == turn) return cached.labels();
        Map<Evidence.Source, String> labels = ScreenText.sourceLabels(turn);
        sourceLabelCache.put(turn.requestId(), new CachedSourceLabels(turn, labels));
        return labels;
    }

    String messageMetadata(ChatContracts.Turn turn, boolean user, int tokens) {
        Instant sentAt = user ? turn.sentAt() : turn.assistantSentAt();
        return (sentAt != null ? DateTimeFormatter.ofPattern("MMM d, HH:mm", java.util.Locale.getDefault())
                .withZone(ZoneId.systemDefault()).format(sentAt) + " · " : "")
                + "~" + tokens + " tokens";
    }

    private int drawMessageActions(GuiGraphics graphics, ChatContracts.Turn turn, boolean user, int tokens, int y, int mouseX, int mouseY) {
        Component count = Component.literal(messageMetadata(turn, user, tokens)).withStyle(style -> style.withFont(ModernUi.FONT));
        boolean editable = user && !view.active() && !awaitingRequestView && !view.readOnly()
                && turn == view.conversation().turns().getLast();
        boolean stacked = font.width(count) + 42 + (editable ? 38 : 0) > readableWidth - (user ? 0 : 26);
        int left = user ? Math.max(readableLeft, readableLeft + readableWidth - font.width(count) - 42 - (editable ? 38 : 0)) : readableLeft + 26;
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(count, Math.max(1, stacked ? readableWidth - (user ? 0 : 26) : font.width(count)));
        for (int index = 0; index < lines.size(); index++) graphics.drawString(font, lines.get(index), left,
                y + 6 + index * ModernUi.MESSAGE_LINE_HEIGHT, ModernUi.MUTED, false);
        int actionLeft = stacked ? readableLeft + readableWidth - 34 - (editable ? 38 : 0) : left + font.width(count) + 8;
        int actionY = y + 2 + (stacked ? lines.size() * ModernUi.MESSAGE_LINE_HEIGHT + 4 : 0);
        ModernUi.button(graphics, font, "Copy", actionLeft, actionY, 34, 16,
                ModernUi.hovered(mouseX, mouseY, actionLeft, actionY, 34, 16) ? ModernUi.SURFACE_HOVER : ModernUi.SURFACE, ModernUi.TEXT);
        messageActions.add(new MessageAction(turn, user, false, actionLeft, actionY, 34));
        if (editable) {
            ModernUi.button(graphics, font, "Edit", actionLeft + 40, actionY, 32, 16,
                    ModernUi.hovered(mouseX, mouseY, actionLeft + 40, actionY, 32, 16) ? ModernUi.SURFACE_HOVER : ModernUi.SURFACE, ModernUi.TEXT);
            messageActions.add(new MessageAction(turn, true, true, actionLeft + 40, actionY, 32));
        }
        return actionY + 20;
    }

    private void drawAssistantAvatar(GuiGraphics graphics, int y) {
        ModernUi.surface(graphics, readableLeft, y, readableLeft + 18, y + 18, ModernUi.ACCENT_DARK);
        Component label = Component.literal("Ps").withStyle(style -> style.withFont(ModernUi.BOLD_FONT).withColor(ModernUi.TEXT));
        graphics.drawString(font, label, readableLeft + (18 - font.width(label)) / 2,
                y + (18 - font.lineHeight) / 2 + 1, ModernUi.TEXT, false);
        graphics.fill(readableLeft + 15, y + 15, readableLeft + 18, y + 18, ModernUi.DANGER);
    }

    private int drawInlineLines(GuiGraphics graphics, MessageLayout layout, int y, int left, ChatContracts.Turn turn) {
        int quoteY = -1;
        for (MessageRun run : layout.runs()) {
            int x = left + run.x();
            int top = y + run.y();
            if (top + run.height() <= 40 || top >= toggleY - 8) continue;
            if (run.quoted() && quoteY != run.y()) {
                graphics.fill(left, top, left + 2, top + run.height(), ModernUi.MUTED);
                quoteY = run.y();
            }
            if (run.card() != null && run.stack() == null) {
                run.card().draw(graphics, x, top + 4, drawMouseY >= 40 && drawMouseY < toggleY - 8 ? drawMouseX : -1,
                        drawMouseY >= 40 && drawMouseY < toggleY - 8 ? drawMouseY : -1);
                cardHits.add(new CardHit(run.card(), null, new PinnedCardState.Rect(x, top + 4,
                        x + run.card().width(), top + 4 + run.card().height())));
                continue;
            }
            ScreenText.InlineSpan span = run.span();
            boolean item = run.stack() != null;
            boolean reference = span.target() != null || span.source() != null || span.externalUrl() != null;
            if (item) {
                int chipTop = top + (run.height() - 18) / 2;
                if (run.card() != null) {
                    graphics.fill(x, chipTop, x + run.width(), chipTop + 18, ModernUi.ACCENT);
                    graphics.fill(x + 1, chipTop + 1, x + run.width() - 1, chipTop + 17, ModernUi.ACCENT_DARK);
                } else ModernUi.surface(graphics, x, chipTop, x + run.width(), chipTop + 18, ModernUi.SURFACE_HOVER);
                graphics.renderItem(run.stack(), x + 1, chipTop + 1);
                graphics.renderItemDecorations(font, run.stack(), x + 1, chipTop + 1);
                var rect = new PinnedCardState.Rect(x, chipTop, x + run.width(), chipTop + 18);
                if (run.card() != null) cardHits.add(new CardHit(run.card(), run.stack(), rect));
                else itemHits.add(new ItemHit(run.stack(), rect));
            } else if (reference) {
                int chipTop = top + (run.height() - ModernUi.MESSAGE_LINE_HEIGHT) / 2;
                ModernUi.surface(graphics, x, chipTop, x + run.width(), chipTop + ModernUi.MESSAGE_LINE_HEIGHT,
                        span.target() != null || span.externalUrl() != null ? ModernUi.ACCENT_DARK : ModernUi.SURFACE_HOVER);
                if (turn != null && span.target() != null) chipHits.add(new ChipHit(span.target(), x, chipTop, x + run.width(), chipTop + ModernUi.MESSAGE_LINE_HEIGHT));
                if (turn != null && span.source() != null) sourceHits.add(new SourceHit(span.source(), turn, x, chipTop, x + run.width(), chipTop + ModernUi.MESSAGE_LINE_HEIGHT));
                if (turn != null && span.externalUrl() != null) webLinkHits.add(new WebLinkHit(span.externalUrl(), x, chipTop,
                        x + run.width(), chipTop + ModernUi.MESSAGE_LINE_HEIGHT));
            }
            graphics.drawString(font, Component.literal(span.text()).withStyle(style -> style
                            .withFont(span.bold() || span.target() != null || span.externalUrl() != null ? ModernUi.BOLD_FONT : ModernUi.FONT).withColor(span.color())),
                    x + (item ? 20 : reference ? 6 : 0), top + (run.height() - font.lineHeight) / 2 + 1, span.color(), false);
        }
        return y + layout.height();
    }

    record MessageRun(ScreenText.InlineSpan span, int x, int y, int width, boolean quoted,
                      RecipeCard card, ItemStack stack, int height) { }
    record MessageLayout(List<MessageRun> runs, int height) { }

    static int lineHeight(RecipeCard card, boolean item) {
        return card != null ? card.height() + 8 : item ? 18 : ModernUi.MESSAGE_LINE_HEIGHT;
    }

    MessageLayout messageLayout(List<ScreenText.InlineLine> lines, int maxWidth) {
        return messageLayout(lines, maxWidth, Map.of(), Map.of(), Map.of());
    }

    MessageLayout messageLayout(List<ScreenText.InlineLine> lines, int maxWidth,
                                Map<RecipeCardModel, RecipeCard> cards, Map<RecipeCardModel, ItemStack> outputs,
                                Map<String, ItemStack> items) {
        int width = Math.max(1, maxWidth);
        List<MessageRun> runs = new ArrayList<>();
        int y = 0;
        for (ScreenText.InlineLine line : lines) {
            int indent = line.quote() ? 10 : 0;
            RecipeCard block = line.card() == null ? null : cards.get(line.card());
            if (block != null && block.width() <= width - indent) {
                runs.add(new MessageRun(line.spans().getFirst(), indent, y, block.width(), line.quote(), block, null, lineHeight(block, false)));
                y += lineHeight(block, false);
                continue;
            }
            int x = indent;
            int rowHeight = ModernUi.MESSAGE_LINE_HEIGHT;
            int rowStart = runs.size();
            if (line.spans().isEmpty() && line.quote()) runs.add(new MessageRun(
                    new ScreenText.InlineSpan("", null, ModernUi.TEXT, false, false), x, y, 0, true, null, null, rowHeight));
            for (ScreenText.InlineSpan span : line.spans()) {
                RecipeCard result = span.resultCard() == null ? null : cards.get(span.resultCard());
                ItemStack stack = span.itemId() == null ? null : items.get(span.itemId());
                if (result != null) stack = outputs.get(span.resultCard());
                boolean item = stack != null;
                if (item || span.target() != null || span.source() != null || span.externalUrl() != null) {
                    boolean navigation = !item && line.card() == null && (span.target() != null || span.externalUrl() != null);
                    String labelText = item && result != null ? stack.getHoverName().getString() : span.text();
                    String verb = span.target() == null ? "Open" : ScreenText.navigationVerb(span.target());
                    if (navigation && !labelText.regionMatches(true, 0, verb + " ", 0, verb.length() + 1)) labelText = verb + " " + labelText;
                    else if (!item && !navigation && !labelText.regionMatches(true, 0, "Source: ", 0, 8)) labelText = "Source: " + labelText;
                    boolean bold = navigation;
                    Component label = Component.literal(labelText).withStyle(style -> style.withFont(bold ? ModernUi.BOLD_FONT : ModernUi.FONT));
                    int padding = item ? 24 : 12;
                    int chipWidth = Math.min(Math.max(1, width - indent), font.width(label) + padding);
                    if (x > indent && x + chipWidth > width) {
                        if (rowHeight != ModernUi.MESSAGE_LINE_HEIGHT) for (int i = rowStart; i < runs.size(); i++) {
                            var r = runs.get(i);
                            runs.set(i, new MessageRun(r.span(), r.x(), r.y(), r.width(), r.quoted(), r.card(), r.stack(), rowHeight));
                        }
                        x = indent; y += rowHeight; rowHeight = ModernUi.MESSAGE_LINE_HEIGHT; rowStart = runs.size();
                    }
                    String clipped = font.substrByWidth(label, Math.max(1, chipWidth - padding)).getString();
                    runs.add(new MessageRun(new ScreenText.InlineSpan(clipped, item || line.card() != null ? null : span.target(),
                            navigation ? ModernUi.ACCENT : ModernUi.TEXT, bold, false, item ? null : span.source(),
                            item || line.card() != null ? null : span.externalUrl()), x, y, chipWidth, line.quote(), item ? result : null, stack, item ? lineHeight(null, true) : rowHeight));
                    if (item) rowHeight = lineHeight(null, true);
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
                        if (rowHeight != ModernUi.MESSAGE_LINE_HEIGHT) for (int i = rowStart; i < runs.size(); i++) {
                            var r = runs.get(i);
                            runs.set(i, new MessageRun(r.span(), r.x(), r.y(), r.width(), r.quoted(), r.card(), r.stack(), rowHeight));
                        }
                        x = indent; y += rowHeight; rowHeight = ModernUi.MESSAGE_LINE_HEIGHT; rowStart = runs.size();
                        if (space) continue;
                    }
                    while (!token.isEmpty()) {
                        int take = font.substrByWidth(Component.literal(token).withStyle(style ->
                                style.withFont(span.bold() ? ModernUi.BOLD_FONT : ModernUi.FONT)), Math.max(1, width - x)).getString().length();
                        if (take <= 0) take = Character.charCount(token.codePointAt(0));
                        String piece = token.substring(0, take);
                        int pieceWidth = font.width(Component.literal(piece).withStyle(style ->
                                style.withFont(span.bold() ? ModernUi.BOLD_FONT : ModernUi.FONT)));
                        runs.add(new MessageRun(new ScreenText.InlineSpan(piece, null, span.color(), span.bold(), span.code()),
                                x, y, pieceWidth, line.quote(), null, null, rowHeight));
                        x += pieceWidth;
                        token = token.substring(take);
                        if (!token.isEmpty()) {
                            if (rowHeight != ModernUi.MESSAGE_LINE_HEIGHT) for (int i = rowStart; i < runs.size(); i++) {
                                var r = runs.get(i);
                                runs.set(i, new MessageRun(r.span(), r.x(), r.y(), r.width(), r.quoted(), r.card(), r.stack(), rowHeight));
                            }
                            x = indent; y += rowHeight; rowHeight = ModernUi.MESSAGE_LINE_HEIGHT; rowStart = runs.size();
                        }
                    }
                }
            }
            if (rowHeight != ModernUi.MESSAGE_LINE_HEIGHT) for (int i = rowStart; i < runs.size(); i++) {
                var r = runs.get(i);
                runs.set(i, new MessageRun(r.span(), r.x(), r.y(), r.width(), r.quoted(), r.card(), r.stack(), rowHeight));
            }
            y += rowHeight;
        }
        return new MessageLayout(List.copyOf(runs), y);
    }

    private List<Evidence.NavigationTarget> turnTargets(ChatContracts.Turn turn) {
        Set<Evidence.Source> sources = new HashSet<>(turn.sources());
        view.sources().forEach(source -> {
            if (source.scope().equals(turn.context().scope())) sources.add(source);
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
                view.readOnly() ? ModernUi.SURFACE : mouseX >= sendX && mouseX < sendX + 20
                        && mouseY >= sendY && mouseY < sendY + 20 ? ModernUi.SURFACE_HOVER : ModernUi.ACCENT_DARK);
        if (view.active() || awaitingRequestView) ModernUi.surface(graphics, stopX, sendY, stopX + 20, sendY + 20,
                mouseX >= stopX && mouseX < stopX + 20 && mouseY >= sendY && mouseY < sendY + 20
                        ? ModernUi.SURFACE_HOVER : ModernUi.DANGER);
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
    public record AdapterPopupLayout(int width, boolean scrollbar) { }
    record AdapterTooltipRows(List<Map.Entry<String, String>> rows, int offset, int visibleRows, int height,
                              int tooltipWidth, int labelWidth, int scrollTrackHeight, int scrollThumbHeight,
                              int scrollThumbOffset) { }

    private static final List<Map.Entry<String, String>> ADAPTERS = List.of(Map.entry("native", "Minecraft"),
            Map.entry("config", "NeoForge configs"), Map.entry("world", "World"), Map.entry("progress", "Progress"), Map.entry("journal", "Journal"), Map.entry("history", "Chat history"));

    static AdapterTooltipRows adapterTooltipRows(Font font, Map<String, Boolean> availability, boolean hideInactive,
                                                 int offset, int screenWidth, int screenHeight) {
        List<Map.Entry<String, String>> rows = hideInactive
                ? ADAPTERS.stream().filter(adapter -> availability.getOrDefault(adapter.getKey(), false)).toList() : ADAPTERS;
        int maxHeight = Math.min(Math.max(1, screenHeight - 8), Math.max(1, screenHeight * 7 / 10));
        int visibleRows = Math.min(rows.size(), Math.min(8, Math.max(0, (maxHeight - 36) / 14)));
        int clampedOffset = Math.max(0, Math.min(Math.max(0, rows.size() - visibleRows), offset));
        int trackHeight = visibleRows * 14;
        boolean overflowing = rows.size() > visibleRows;
        int thumbHeight = overflowing ? Math.min(trackHeight, Math.max(8, trackHeight * visibleRows / rows.size())) : 0;
        int bulletWidth = font.width(Component.literal("• ").withStyle(style -> style.withFont(ModernUi.FONT)));
        int tooltipWidth = Math.min(Math.max(1, Math.min(286, screenWidth - 24)), 20 + Math.max(
                font.width(Component.literal("Adapters").withStyle(style -> style.withFont(ModernUi.BOLD_FONT))),
                rows.stream().mapToInt(row -> bulletWidth + font.width(Component.literal(row.getValue())
                        .withStyle(style -> style.withFont(ModernUi.FONT)))).max().orElse(0)) + (overflowing ? 8 : 0));
        return new AdapterTooltipRows(rows.subList(clampedOffset, Math.min(rows.size(), clampedOffset + visibleRows)),
                clampedOffset, visibleRows, Math.min(maxHeight, 36 + visibleRows * 14), tooltipWidth,
                Math.max(1, tooltipWidth - 20 - (overflowing ? 8 : 0) - bulletWidth), overflowing ? trackHeight : 0,
                thumbHeight, overflowing ? clampedOffset * (trackHeight - thumbHeight) / (rows.size() - visibleRows) : 0);
    }

    String adapterFooter(Map<String, Boolean> availability, AdapterTooltipRows layout) {
        if (availability.getOrDefault("journal", false) || layout.rows().stream().noneMatch(row -> row.getKey().equals("journal"))) return "";
        if (!availability.getOrDefault("world", false) && view.sources().stream().noneMatch(source -> source.providerId().equals("journal"))
                && view.conversation().turns().stream().flatMap(turn -> turn.sources().stream()).noneMatch(source -> source.providerId().equals("journal"))) return "";
        return actions.adapterDetails().getOrDefault("journal", "");
    }

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
        items.add(new ToolbarItem("context", readableLeft + 26, sendY, view.active() || awaitingRequestView
                ? Math.max(1, Math.min(43, (readableWidth - 84) / 2)) : buttonWidth / 2));
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
        tooltipItems.add(new ToolbarItem("adapters", 12, Math.max(0, height - 32), 20));
        tooltipItems.add(new ToolbarItem("settings", 40, Math.max(0, height - 32), 20));
        boolean adapterDrawn = false;
        for (ToolbarItem item : tooltipItems) {
            boolean adapterIconHovered = item.kind().equals("adapters") && (adapterPopupRequested
                    || mouseX >= item.x() && mouseX < item.x() + item.width() && mouseY >= item.y() && mouseY < item.y() + 20);
            boolean adapterPopupHovered = item.kind().equals("adapters") && adapterPopupVisible
                    && mouseX >= adapterPopupLeft - 10 && mouseX < adapterPopupLeft + adapterPopupWidth + 10
                    && mouseY >= adapterPopupTop - 6 && mouseY < adapterPopupTop + adapterPopupHeight + 6;
            if (item.kind().equals("adapters") ? !adapterIconHovered && !adapterPopupHovered
                    : mouseX < item.x() || mouseX >= item.x() + item.width() || mouseY < item.y() || mouseY >= item.y() + 20) continue;
            List<Component> lines = new ArrayList<>();
            List<ScreenText.TooltipField> fields = List.of();
            String title = "";
            String footer = "";
            AdapterTooltipRows adapterLayout = null;
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
            } else if (item.kind().equals("adapters")) {
                title = "Adapters";
                Map<String, Boolean> availability = actions.adapterAvailability();
                String key = availability + ":" + settings.hideInactiveAdapters() + ":" + width + ":" + height;
                if (!key.equals(adapterRowsKey)) {
                    adapterRowsKey = key;
                    adapterScroll = 0;
                }
                adapterLayout = adapterTooltipRows(font, availability, settings.hideInactiveAdapters(), adapterScroll, width, height);
                footer = adapterFooter(availability, adapterLayout);
                adapterScroll = adapterLayout.offset();
                for (var adapter : adapterLayout.rows()) {
                    lines.add(Component.literal("• ").withStyle(style -> style.withColor(
                                    availability.getOrDefault(adapter.getKey(), false) ? 0xff6fd48a : 0xffe36b6b))
                            .append(Component.literal(font.substrByWidth(Component.literal(adapter.getValue())
                                            .withStyle(style -> style.withFont(ModernUi.FONT).withColor(ModernUi.TEXT)), adapterLayout.labelWidth()).getString())
                                    .withStyle(style -> style.withFont(ModernUi.FONT).withColor(ModernUi.TEXT))));
                }
            } else if (item.kind().equals("settings")) lines.add(Component.literal("Settings (middle-click for Advanced settings)"));
            else if (item.kind().equals("model")) {
                String selectedId = settings.modelId() == null ? actions.providerSelectedModelId() : settings.modelId();
                lines.add(Component.literal(actions.modelCatalog().stream().filter(model -> model.id().equals(selectedId))
                        .findFirst().map(ModelBackend.ModelInfo::displayName).orElse("Select a model")));
            } else if (item.kind().equals("effort")) lines.add(Component.literal("Reasoning effort: " + settings.reasoningEffort().name().toLowerCase(java.util.Locale.ROOT)));
            else lines.add(Component.literal(item.kind().equals("search") ? "Web search" : "Attach screenshot"));
            List<net.minecraft.util.FormattedCharSequence> wrapped = new ArrayList<>();
            int tooltipWidth = item.kind().equals("adapters") ? adapterLayout.tooltipWidth() : Math.max(1, Math.min(286, width - 24));
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
            int tooltipHeight = item.kind().equals("adapters") ? Math.min(height - 8,
                    adapterLayout.height() + (footerLines.isEmpty() ? 0 : 6 + footerLines.size() * 14)) : Math.min(height - 8, title.isEmpty()
                    ? 12 + Math.max(0, wrapped.size() - 1) * 14 + font.lineHeight
                    : 36 + wrapped.size() * 14
                    + values.stream().mapToInt(value -> Math.max(1, value.size()) * 14).sum()
                    + (footerLines.isEmpty() ? 0 : 6 + footerLines.size() * 14));
            int left = item.kind().equals("adapters") && !adapterIconHovered ? adapterPopupLeft
                    : Math.max(4, Math.min(adapterPopupRequested ? 22 : mouseX + 10, width - tooltipWidth - 4));
            int top = Math.max(4, item.y() - tooltipHeight - 6);
            if (item.kind().equals("adapters")) {
                adapterDrawn = true;
                adapterPopupVisible = true;
                adapterPopupLeft = left;
                adapterPopupTop = top;
                adapterPopupWidth = tooltipWidth;
                adapterPopupHeight = tooltipHeight;
            }
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 400);
            ModernUi.surface(graphics, left, top, left + tooltipWidth, top + tooltipHeight, 0xff20252a);
            graphics.enableScissor(left + 4, top + 4, left + tooltipWidth - 4, top + tooltipHeight - 4);
            int y = top + (title.isEmpty() ? 6 : 10);
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
            if (item.kind().equals("adapters") && adapterLayout.scrollTrackHeight() > 0) {
                int trackTop = top + 26;
                graphics.fill(left + tooltipWidth - 6, trackTop, left + tooltipWidth - 4,
                        trackTop + adapterLayout.scrollTrackHeight(), ModernUi.BORDER);
                int thumbTop = trackTop + adapterLayout.scrollThumbOffset();
                graphics.fill(left + tooltipWidth - 6, thumbTop, left + tooltipWidth - 4,
                        thumbTop + adapterLayout.scrollThumbHeight(), ModernUi.MUTED);
            }
            graphics.disableScissor();
            graphics.pose().popPose();
        }
        adapterPopupVisible = adapterDrawn;
    }

    private void drawSourceTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (panelRect != null && mouseX >= panelRect.left() && mouseX < panelRect.right()
                && mouseY >= panelRect.top() && mouseY < panelRect.bottom()) return;
        if (picker != null || mouseY < 40 || mouseY >= toggleY - 8 || mouseX < readableLeft
                || mouseX >= readableLeft + readableWidth) return;
        SourceHit hovered = sourceHits.stream().filter(hit -> mouseX >= hit.left() && mouseX < hit.right()
                && mouseY >= hit.top() && mouseY < hit.bottom()).findFirst().orElse(null);
        ChipHit navigation = chipHits.stream().filter(hit -> mouseX >= hit.left() && mouseX < hit.right()
                && mouseY >= hit.top() && mouseY < hit.bottom()).findFirst().orElse(null);
        WebLinkHit webLink = webLinkHits.stream().filter(hit -> mouseX >= hit.left() && mouseX < hit.right()
                && mouseY >= hit.top() && mouseY < hit.bottom()).findFirst().orElse(null);
        if (hovered == null && navigation == null && webLink == null) return;
        if (hovered != null && (!hovered.source().equals(tooltipSource) || hovered.turn() != tooltipTurn)) {
            tooltipSource = hovered.source();
            tooltipTurn = hovered.turn();
            sourceTooltip = SourceDetailsScreen.details(tooltipSource, tooltipTurn);
        }
        List<net.minecraft.util.FormattedCharSequence> lines = new ArrayList<>();
        lines.addAll(font.split(Component.literal(webLink != null ? "Click: open web link"
                : navigation != null ? "Click: " + ScreenText.navigationLabel(navigation.target())
                : "Click: view recorded source details").withStyle(style -> style.withFont(ModernUi.BOLD_FONT)),
                Math.max(1, Math.min(280, width - 24) - 20)));
        if (webLink != null) lines.addAll(font.split(Component.literal(webLink.url())
                .withStyle(style -> style.withFont(ModernUi.FONT)), Math.max(1, Math.min(280, width - 24) - 20)));
        if (hovered != null) for (String detail : sourceTooltip.stream().filter(detail -> !detail.isBlank()
                && !detail.startsWith("Observed:") && !detail.startsWith("Authority:")
                && !detail.startsWith("Source:") && !detail.startsWith("Provenance:"))
                .limit(8).toList()) lines.addAll(font.split(Component.literal(detail)
                .withStyle(style -> style.withFont(ModernUi.FONT)), Math.max(1, Math.min(280, width - 24) - 20)));
        int tooltipWidth = Math.max(1, Math.min(300, width - 16));
        int tooltipHeight = Math.min(height - 8, 12 + lines.size() * 14);
        int left = Math.max(4, Math.min(mouseX + 10, width - tooltipWidth - 4));
        int top = Math.max(4, Math.min((hovered != null ? hovered.top() : navigation != null ? navigation.top() : webLink.top()) - tooltipHeight - 4,
                height - tooltipHeight - 4));
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 400);
        ModernUi.surface(graphics, left, top, left + tooltipWidth, top + tooltipHeight, 0xff20252a);
        graphics.enableScissor(left + 6, top + 5, left + tooltipWidth - 6, top + tooltipHeight - 5);
        for (int index = 0; index < lines.size() && top + 6 + index * 14 < height - 4; index++)
            graphics.drawString(font, lines.get(index), left + 10, top + 6 + index * 14, ModernUi.TEXT, false);
        graphics.disableScissor();
        graphics.pose().popPose();
    }

    private void drawRecipeTooltips(GuiGraphics graphics, int mouseX, int mouseY) {
        if (picker != null) {
            pinnedCard = new PinnedCardState();
            panelCard = null;
            panelRect = null;
            return;
        }
        if (panelCard != null) {
            boolean visible = false;
            for (var hit : cardHits) {
                if (hit.stack() != null && hit.card() == panelCard.card() && hit.rect().equals(panelCard.rect())
                        && hit.rect().bottom() > 40 && hit.rect().top() < toggleY - 8
                        && hit.rect().right() > readableLeft && hit.rect().left() < readableLeft + readableWidth) {
                    visible = true;
                    break;
                }
            }
            if (!visible) {
                // A chip leaving the viewport closes its pinned panel as well.
                pinnedCard = new PinnedCardState();
                panelCard = null;
                panelRect = null;
            }
        }
        CardHit hovered = null;
        if ((panelRect == null || mouseX < panelRect.left() || mouseX >= panelRect.right()
                || mouseY < panelRect.top() || mouseY >= panelRect.bottom())
                && mouseX >= readableLeft && mouseX < readableLeft + readableWidth && mouseY >= 40 && mouseY < toggleY - 8) {
            for (var hit : cardHits) {
                if (hit.stack() != null && mouseX >= hit.rect().left() && mouseX < hit.rect().right()
                        && mouseY >= hit.rect().top() && mouseY < hit.rect().bottom()) {
                    hovered = hit;
                    break;
                }
            }
        }
        if (pinnedCard.anchor() == null && hovered != null && (panelCard == null || !panelCard.rect().equals(hovered.rect()))) {
            panelCard = hovered;
            panelRect = null;
        }
        var visibility = pinnedCard.update(mouseX, mouseY, hasShiftDown(), panelCard == null ? null : panelCard.rect(), panelRect);
        RecipeCard.Hover tooltip = null;
        if (visibility != PinnedCardState.Visibility.HIDDEN && panelCard != null) {
            if (panelRect == null) {
                int panelWidth = panelCard.card().width() + 16;
                int panelHeight = panelCard.card().height() + 16;
                int left = Math.max(0, Math.min(panelCard.rect().left(), width - panelWidth));
                int top = panelCard.rect().bottom();
                if (top + panelHeight > height) top = Math.max(0, panelCard.rect().top() - panelHeight);
                panelRect = new PinnedCardState.Rect(left, top, left + panelWidth, top + panelHeight);
            }
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 400);
            ModernUi.surface(graphics, panelRect.left(), panelRect.top(), panelRect.right(), panelRect.bottom(), ModernUi.SURFACE);
            panelCard.card().draw(graphics, panelRect.left() + 8, panelRect.top() + 8, mouseX, mouseY);
            tooltip = panelCard.card().hoverAt(panelRect.left() + 8, panelRect.top() + 8, mouseX, mouseY).orElse(null);
            graphics.pose().popPose();
        } else {
            panelCard = null;
            panelRect = null;
            if (mouseX >= readableLeft && mouseX < readableLeft + readableWidth && mouseY >= 40 && mouseY < toggleY - 8) {
                for (var hit : cardHits) if (hit.stack() == null && mouseX >= hit.rect().left() && mouseX < hit.rect().right()
                        && mouseY >= hit.rect().top() && mouseY < hit.rect().bottom()) {
                    tooltip = hit.card().hoverAt(hit.rect().left(), hit.rect().top(), mouseX, mouseY).orElse(null);
                    if (tooltip != null) break;
                }
                if (tooltip == null) for (var hit : itemHits) if (mouseX >= hit.rect().left() && mouseX < hit.rect().right()
                        && mouseY >= hit.rect().top() && mouseY < hit.rect().bottom()) {
                    tooltip = new RecipeCard.Hover.Item(hit.stack());
                    break;
                }
            }
        }
        if (tooltip != null) {
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 500);
            if (tooltip instanceof RecipeCard.Hover.Item item) graphics.renderTooltip(font, item.stack(), mouseX, mouseY);
            else if (tooltip instanceof RecipeCard.Hover.Text text) graphics.renderComponentTooltip(font, text.lines(), mouseX, mouseY);
            graphics.pose().popPose();
        }
    }

    private void drawSendIcon(GuiGraphics graphics) {
        graphics.blit(ResourceLocation.fromNamespaceAndPath("packsage", "textures/gui/icons/paper-plane-right.png"),
                sendX + 4, sendY + 4, 12, 12, 0.0F, 0.0F, 64, 64, 64, 64);
    }

    void updateContentMetrics(int contentHeight, int viewportHeight) {
        if (historyPrepended && !followBottom) scrollOffset += Math.max(0, contentHeight - this.contentHeight);
        historyPrepended = false;
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
        if (panelRect != null && mouseX >= panelRect.left() && mouseX < panelRect.right()
                && mouseY >= panelRect.top() && mouseY < panelRect.bottom()) return true;
        if (picker != null) {
            pickerScroll -= (int) Math.signum(scrollY);
            pickerRows();
            return true;
        }
        if (adapterPopupVisible && mouseX >= adapterPopupLeft && mouseX < adapterPopupLeft + adapterPopupWidth
                && mouseY >= adapterPopupTop && mouseY < adapterPopupTop + adapterPopupHeight) {
            adapterScroll = adapterTooltipRows(font, actions.adapterAvailability(), settings.hideInactiveAdapters(),
                    adapterScroll - (int) Math.signum(scrollY), width, height).offset();
            return true;
        }
        if (mouseX >= 12 && mouseX < 32 && mouseY >= Math.max(0, height - 32) && mouseY < Math.max(0, height - 12)) {
            adapterScroll = adapterTooltipRows(font, actions.adapterAvailability(), settings.hideInactiveAdapters(),
                    adapterScroll - (int) Math.signum(scrollY), width, height).offset();
            return true;
        }
        if (inputEditor != null && inputEditor.isMouseOver(mouseX, mouseY)) return inputEditor.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        if (sessionSearchEditor != null && sessionSearchEditor.isMouseOver(mouseX, mouseY)) return sessionSearchEditor.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        if (mouseX < sidebarWidth) {
            if (mouseY >= SESSION_LIST_TOP && mouseY < height - 56) {
                sessionRows();
                sidebarScroll = Math.max(0, Math.min(Math.max(0, sidebarContentHeight - (height - 56 - SESSION_LIST_TOP)), sidebarScroll - (int) (scrollY * 28)));
                return true;
            }
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        if (height > 0 && (mouseY < 40 || mouseY >= toggleY - 8)) return false;
        // A chat scroll closes the panel so a pinned card never detaches from its chip.
        pinnedCard = new PinnedCardState();
        panelCard = null;
        panelRect = null;
        scrollOffset = Math.max(0, Math.min(maxScroll(), scrollOffset - (int) (scrollY * 20)));
        followBottom = scrollOffset >= maxScroll();
        if (scrollY > 0 && scrollOffset == 0 && visibleTurnCount < view.conversation().turns().size()) {
            visibleTurnCount = Math.min(view.conversation().turns().size(), visibleTurnCount + 4);
            historyPrepended = true;
            followBottom = false;
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (panelRect != null && mouseX >= panelRect.left() && mouseX < panelRect.right()
                && mouseY >= panelRect.top() && mouseY < panelRect.bottom()) return true;
        if (picker == null && commandConfirmation.mouseClicked(mouseX, mouseY, button)) return true;
        if (button == 2 && picker == null) {
            int controlY = Math.max(0, height - 32);
            if (mouseX >= 40 && mouseX < 60 && mouseY >= controlY && mouseY < controlY + 20) {
                setFocused(null);
                advancedSettingsOpener.run();
                return true;
            }
        }
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
            if (mouseX >= 12 && mouseX < sidebarWidth - 12 && mouseY >= SESSION_LIST_TOP && mouseY < height - 56) {
                for (SessionRow row : sessionRows()) {
                    if (mouseY < row.top() || mouseY >= row.top() + row.height()) continue;
                    if (row.expand()) {
                        loadSessionPage(true);
                        return true;
                    }
                    if (row.session() != null) {
                        if (row.session().equals(pendingDelete)) {
                            if (mouseY >= row.top() + 15 && mouseY < row.top() + 32) {
                                int confirmWidth = Math.max(1, Math.min(54, (sidebarWidth - 42) / 2));
                                if (mouseX >= 18 && mouseX < 18 + confirmWidth && !row.session().active() && !deletingSession) {
                                    deletingSession = true;
                                    ConversationKey deletedKey = new ConversationKey(row.session().world().worldOrServer(), row.session().id());
                                    actions.deleteSession(row.session()).whenComplete((ignored, failure) -> {
                                        Runnable update = () -> {
                                            deletingSession = false;
                                            pendingDelete = null;
                                            if (failure == null) {
                                                conversationStates.remove(deletedKey);
                                                deletedConversationKeys.add(deletedKey);
                                            }
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
                            if (!row.session().active() && !deletingSession) pendingDelete = row.session();
                            return true;
                        }
                        pendingDelete = null;
                        setFocused(null);
                        actions.openSession(row.session());
                        return true;
                    }
                }
            }
            int controlY = Math.max(0, height - 32);
            if (mouseX >= 12 && mouseX < 32 && mouseY >= controlY && mouseY < controlY + 20) {
                setFocused(null);
                return true;
            }
            if (mouseX >= 40 && mouseX < 60 && mouseY >= controlY && mouseY < controlY + 20) {
                settingsOpener.run();
                return true;
            }
            RuntimeState state = actualRuntimeState();
            if (state.privateSingleplayer() && state.integratedServer() && !state.published()
                    && mouseX >= 68 && mouseX < 88 && mouseY >= controlY && mouseY < controlY + 20) {
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
        if ((view.active() || awaitingRequestView) && mouseX >= stopX && mouseX < stopX + 20
                && mouseY >= sendY && mouseY < sendY + 20) {
            stop();
            return true;
        }
        if (mouseX >= sendX && mouseX < sendX + 20 && mouseY >= sendY && mouseY < sendY + 20) {
            submit();
            return true;
        }
        for (MessageAction action : messageActions) {
            if (mouseY >= 48 && mouseY < toggleY - 8 && mouseX >= action.x() && mouseX < action.x() + action.width()
                    && mouseY >= action.y() && mouseY < action.y() + 16) {
                if (action.edit()) editLatest(action.turn().requestId());
                else if (minecraft != null) minecraft.keyboardHandler.setClipboard(action.user() ? action.turn().question() : prepareTurn(action.turn()).copy());
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
        for (WebLinkHit hit : webLinkHits) {
            if (mouseY >= 48 && mouseY < toggleY - 8 && mouseX >= readableLeft && mouseX < readableLeft + readableWidth
                    && mouseX >= hit.left() && mouseX < hit.right() && mouseY >= hit.top() && mouseY < hit.bottom()) {
                if (minecraft != null) ConfirmLinkScreen.confirmLinkNow(this, URI.create(hit.url()), true);
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
        for (ActivityHit hit : activityHits) {
            if (mouseY >= 40 && mouseY < toggleY - 8 && mouseX >= readableLeft
                    && mouseX < readableLeft + readableWidth && mouseX >= hit.left() && mouseX < hit.right()
                    && mouseY >= hit.top() && mouseY < hit.bottom()) {
                toggleActivity(hit.key());
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (panelRect != null && mouseX >= panelRect.left() && mouseX < panelRect.right()
                && mouseY >= panelRect.top() && mouseY < panelRect.bottom()) return true;
        if (inputEditor != null && inputEditor.isFocused()) return inputEditor.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        if (sessionSearchEditor != null && sessionSearchEditor.isFocused()) return sessionSearchEditor.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (panelRect != null && mouseX >= panelRect.left() && mouseX < panelRect.right()
                && mouseY >= panelRect.top() && mouseY < panelRect.bottom()) return true;
        if (inputEditor != null && inputEditor.isFocused()) return inputEditor.mouseReleased(mouseX, mouseY, button);
        if (sessionSearchEditor != null && sessionSearchEditor.isFocused()) return sessionSearchEditor.mouseReleased(mouseX, mouseY, button);
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && (picker != null || editingRequest != null)) {
            picker = null;
            if (editingRequest != null) { editingRequest = null; history = null; setInput(""); }
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

    private void saveConversationState(ConversationKey key) {
        if (inputEditor != null) input = inputEditor.value();
        if (!deletedConversationKeys.contains(key)) conversationStates.put(key, new ConversationUiState(input, editingRequest,
                resendingRequest, scrollOffset, visibleTurnCount, followBottom, followOnCompletion, Map.copyOf(activityExpansion),
                awaitingRequestView, pendingQuestion, pendingSentAt));
    }

    private void restoreConversationState(ConversationKey key) {
        ConversationUiState state = conversationStates.get(key);
        if (state == null) {
            input = "";
            editingRequest = null;
            resendingRequest = null;
            scrollOffset = 0;
            visibleTurnCount = 4;
            followBottom = true;
            followOnCompletion = true;
            activityExpansion.clear();
            awaitingRequestView = false;
            pendingQuestion = "";
            pendingSentAt = null;
        } else {
            input = state.input();
            editingRequest = state.editingRequest();
            resendingRequest = state.resendingRequest();
            scrollOffset = state.scrollOffset();
            visibleTurnCount = state.visibleTurnCount();
            followBottom = state.followBottom();
            followOnCompletion = state.followOnCompletion();
            activityExpansion.clear();
            activityExpansion.putAll(state.activityExpansion());
            awaitingRequestView = state.awaitingRequestView();
            pendingQuestion = state.pendingQuestion();
            pendingSentAt = state.pendingSentAt();
        }
        if (inputEditor != null) inputEditor.setValue(input);
    }

    public void refresh(ChatContracts.View next) {
        Objects.requireNonNull(next);
        if (inputEditor != null) input = inputEditor.value();
        ConversationKey previousKey = new ConversationKey(view.conversation().world().worldOrServer(), view.conversation().id());
        ConversationKey nextKey = new ConversationKey(next.conversation().world().worldOrServer(), next.conversation().id());
        boolean selectionChanged = !previousKey.equals(nextKey);
        if (selectionChanged || view.readOnly() != next.readOnly()
                || !view.activeWorld().equals(next.activeWorld()) || !view.context().scope().equals(next.context().scope())) {
            showNavigationError(null);
        }
        if (selectionChanged) {
            saveConversationState(previousKey);
            restoreConversationState(nextKey);
            historyPrepended = false;
        }
        boolean completed = !selectionChanged && view.active() && !next.active();
        boolean sessionsChanged = !view.activeWorld().equals(next.activeWorld()) || completed;
        boolean historyLoaded = selectionChanged
                || view.activity().equals("Loading session...") && !next.activity().equals("Loading session...");
        if (selectionChanged) sourceLabelCache.clear();
        if (selectionChanged || view.sources() != next.sources() || view.navigationTargets() != next.navigationTargets()) preparedTurns.clear();
        view = next;
        history = null;
        boolean loadingSession = next.activity().equals("Loading session...");
        if (!next.active() && !loadingSession) resendingRequest = null;
        if (editingRequest != null && (next.active() || !loadingSession
                && (next.readOnly() || next.conversation().turns().isEmpty()
                || !next.conversation().turns().getLast().requestId().equals(editingRequest)))) editingRequest = null;
        if (!loadingSession) {
            awaitingRequestView = false;
            pendingQuestion = "";
            if (!next.active()) pendingSentAt = null;
        }
        if (completed || historyLoaded) followOnCompletion = followBottom;
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
    public void setAdvancedSettingsOpener(Runnable opener) { advancedSettingsOpener = Objects.requireNonNull(opener); }
    public void setSettingsSaver(Runnable saver) { settingsSaver = Objects.requireNonNull(saver); }
    public void setRuntimeState(RuntimeState state) { runtimeState = Objects.requireNonNull(state); }
    public RuntimeState runtimeState() { return actualRuntimeState(); }
    public int scrollOffset() { return scrollOffset; }

    public AdapterPopupLayout openAdapterPopup() {
        adapterPopupRequested = true;
        var layout = adapterTooltipRows(font, actions.adapterAvailability(), settings.hideInactiveAdapters(), adapterScroll, width, height);
        return new AdapterPopupLayout(layout.tooltipWidth(), layout.scrollTrackHeight() > 0);
    }

    public void showNavigationError(String error) {
        if (error != null) followOnCompletion = followBottom;
        navigationError = error;
        navigationNotice = false;
        navigationErrorExpiresAt = error == null ? 0 : System.currentTimeMillis() + 5_000;
    }

    /** Shows information from a successful navigation in the error's place, without error styling. */
    public void showNavigationNotice(String notice) {
        showNavigationError(notice);
        navigationNotice = notice != null;
    }

    String navigationError(long nowMillis) {
        if (navigationError != null && nowMillis >= navigationErrorExpiresAt) showNavigationError(null);
        return navigationError;
    }

    /** Whether the navigation message is information from a successful open rather than an error. */
    boolean navigationNotice() {
        return navigationNotice;
    }

    public void submit() {
        input = input();
        if (input.isBlank() || view.readOnly()) return;
        String question = input;
        if (view.active()) {
            if (actions.steer(question)) setInput("");
            return;
        }
        if (awaitingRequestView) return;
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

    public void setContainerScreen(net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen) { containerScreen = screen; }

    net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> containerScreen() { return containerScreen; }

    @Override
    public void onClose() {
        if (minecraft != null && containerScreen != null && minecraft.player != null
                && minecraft.player.containerMenu == containerScreen.getMenu()) minecraft.setScreen(containerScreen);
        else if (minecraft != null) super.onClose();
    }

    public void stop() { actions.stop(); }
    public void newChat() { sidebarScroll = 0; picker = null; actions.newChat(); }
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
        showNavigationError(null);
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

    private record ItemHit(ItemStack stack, PinnedCardState.Rect rect) { }
    private record CardHit(RecipeCard card, ItemStack stack, PinnedCardState.Rect rect) { }
    private record ChipHit(Evidence.NavigationTarget target, int left, int top, int right, int bottom) { }
    private record SourceHit(Evidence.Source source, ChatContracts.Turn turn, int left, int top, int right, int bottom) { }
    private record CachedSourceLabels(ChatContracts.Turn turn, Map<Evidence.Source, String> labels) { }
    private record WebLinkHit(String url, int left, int top, int right, int bottom) { }
    private record ActivityHit(String key, int left, int top, int right, int bottom) { }
    private record ActivityExpansion(ChatContracts.ActivityStatus status, boolean expanded) { }
    private record ConversationKey(String worldOrServer, String id) { }
    private record ConversationUiState(String input, ChatContracts.RequestId editingRequest,
                                       ChatContracts.RequestId resendingRequest, int scrollOffset, int visibleTurnCount,
                                       boolean followBottom, boolean followOnCompletion,
                                       Map<String, ActivityExpansion> activityExpansion,
                                       boolean awaitingRequestView, String pendingQuestion, Instant pendingSentAt) { }
    private record MessageAction(ChatContracts.Turn turn, boolean user, boolean edit, int x, int y, int width) { }
}
