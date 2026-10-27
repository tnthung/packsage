package dev.packsage.client;

import dev.packsage.chat.ChatActions;
import dev.packsage.chat.ChatContracts;
import dev.packsage.chat.PackSageSettings;
import dev.packsage.model.ModelBackend;
import dev.packsage.knowledge.Evidence;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantScreenStateTest {
    @Test
    void cancelingEditRecomputesHistoryInclusion() throws Exception {
        var screen = new AssistantScreen(new FakeActions());
        screen.width = 960;
        screen.height = 600;
        ScreenTestHost.initialize(screen);
        var font = net.minecraft.client.gui.screens.Screen.class.getDeclaredField("font");
        font.setAccessible(true);
        font.set(screen, new ScreenPreparationFont());
        assertTrue(screen.editLatest(screen.view().conversation().turns().getLast().requestId()));
        screen.prepareConversation();
        var history = AssistantScreen.class.getDeclaredField("history");
        history.setAccessible(true);
        assertTrue(((ChatContracts.HistoryPreview) history.get(screen)).selection().turns().isEmpty());
        assertTrue(screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0));
        screen.prepareConversation();
        assertEquals(screen.view().conversation().turns(), ((ChatContracts.HistoryPreview) history.get(screen)).selection().turns());
    }

    @Test
    void expandedToolFieldsUseMutedLabelsAndNormalValues() throws Exception {
        var screen = new AssistantScreen(new FakeActions());
        var step = new ChatContracts.ActivityStep("tool:result", "Reading", "Failed",
                ChatContracts.ActivityStatus.FAILED, "{}", "read_evidence", "Coverage: COMPLETE", 600L, "Result text");
        var method = AssistantScreen.class.getDeclaredMethod("activityBodyLines", ChatContracts.ActivityStep.class, List.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        var lines = (List<ScreenText.InlineLine>) method.invoke(screen, step, List.of());
        assertEquals(List.of("Tool:", "Parameter:", "Error:", "Elapsed:"),
                lines.stream().map(line -> line.spans().getFirst().text()).toList());
        for (var line : lines) {
            assertEquals(ModernUi.MUTED, line.spans().getFirst().color());
            assertEquals(ModernUi.TEXT, line.spans().getLast().color());
        }
        var completed = new ChatContracts.ActivityStep("tool:result", "Reading", "Completed",
                ChatContracts.ActivityStatus.COMPLETED, "{}", "read_evidence", "Coverage: COMPLETE", 600L, "Result text");
        @SuppressWarnings("unchecked")
        var result = (List<ScreenText.InlineLine>) method.invoke(screen, completed, List.of());
        assertEquals("Result:", result.get(2).spans().getFirst().text());
        assertEquals(ModernUi.TEXT, result.get(2).spans().getLast().color());
    }

    @Test
    void sidebarListStartsFourPixelsAfterNewChat() {
        var actions = new FakeActions();
        actions.sessions = CompletableFuture.completedFuture(List.of(new ChatContracts.SessionSummary(
                actions.view.activeWorld(), "chat", "Question", "", Instant.EPOCH)));
        var screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);
        assertEquals(84, screen.sessionRows().getFirst().top());
    }

    @Test
    void repeatedScreenPreparationReusesLayoutAndRefreshInvalidatesIt() throws Exception {
        var actions = new FakeActions();
        var screen = new AssistantScreen(actions);
        screen.width = 960;
        screen.height = 600;
        ScreenTestHost.initialize(screen);
        var font = new ScreenPreparationFont();
        var field = net.minecraft.client.gui.screens.Screen.class.getDeclaredField("font");
        field.setAccessible(true);
        field.set(screen, font);
        var prepare = AssistantScreen.class.getDeclaredMethod("prepareConversation");
        prepare.setAccessible(true);
        prepare.invoke(screen);
        long measured = font.measuredGlyphs;
        assertTrue(measured > 0);
        prepare.invoke(screen);
        assertEquals(measured, font.measuredGlyphs);
        int previews = actions.historyPreviews;
        prepare.invoke(screen);
        assertEquals(previews, actions.historyPreviews);
        screen.width = 480;
        screen.layout();
        prepare.invoke(screen);
        assertTrue(font.measuredGlyphs > measured, "resize must rebuild layout");
        measured = font.measuredGlyphs;
        var original = actions.view;
        var turn = original.conversation().turns().getFirst();
        actions.view = new ChatContracts.View(false, original.signIn(), false,
                new ChatContracts.Conversation(original.conversation().id(), original.conversation().world(),
                        List.of(new ChatContracts.Turn(turn.requestId(), turn.question(), "Updated answer", List.of(),
                                turn.context(), turn.sources(), turn.outcome(), 1)), original.conversation().outcome()),
                original.context(), original.sources(), original.navigationTargets(), List.of());
        screen.refresh(actions.view);
        prepare.invoke(screen);
        assertTrue(font.measuredGlyphs > measured, "edited/reloaded turns must rebuild layout");
    }

    @Test
    void idleTicksDoNotPrepareOlderHistoryAndScrollingAtTheTopRequestsIt() throws Exception {
        var actions = new FakeActions();
        var original = actions.view;
        actions.view = new ChatContracts.View(false, original.signIn(), false,
                new ChatContracts.Conversation(original.conversation().id(), original.conversation().world(),
                        java.util.Collections.nCopies(20, original.conversation().turns().getFirst()), original.conversation().outcome()),
                original.context(), original.sources(), original.navigationTargets(), List.of());
        var screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);
        var count = AssistantScreen.class.getDeclaredField("visibleTurnCount");
        count.setAccessible(true);
        int initial = count.getInt(screen);
        for (int index = 0; index < 30; index++) screen.tick();
        assertEquals(initial, count.getInt(screen));
        screen.updateContentMetrics(1_000, 100);
        screen.mouseScrolled(400, 200, 0, 100);
        assertEquals(initial + 4, count.getInt(screen));
        screen.updateContentMetrics(2_000, 100);
        assertEquals(1_000, screen.scrollOffset(), "prepending must keep the scrolled message anchored");
    }

    @Test
    void fittingWordLayoutMeasuresGlyphsLinearly() throws Exception {
        var screen = new AssistantScreen(new FakeActions());
        var font = new ScreenPreparationFont();
        var field = net.minecraft.client.gui.screens.Screen.class.getDeclaredField("font");
        field.setAccessible(true);
        field.set(screen, font);
        String text = "abcdefghij ".repeat(100);
        assertFalse(screen.messageLayout(ScreenText.inlineLines(text, List.of()), 340).runs().isEmpty());
        assertTrue(font.measuredGlyphs < text.length() * 4L, "glyph visits=" + font.measuredGlyphs);
    }

    @Test
    void refreshRequestedDuringShowMoreRunsAfterThePageFinishes() {
        var actions = new FakeActions();
        actions.settings.setSessionsBeforeCollapse(3);
        var sessions = new ArrayList<ChatContracts.SessionSummary>();
        for (int index = 0; index < 20; index++) sessions.add(new ChatContracts.SessionSummary(
                actions.view.activeWorld(), "chat-" + index, "Question " + index, "", Instant.EPOCH));
        actions.sessions = CompletableFuture.completedFuture(sessions);
        var screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 900;
        ScreenTestHost.initialize(screen);
        var pendingPage = new CompletableFuture<List<ChatContracts.SessionSummary>>();
        actions.sessions = pendingPage;
        var more = screen.sessionRows().stream().filter(AssistantScreen.SessionRow::expand).findFirst().orElseThrow();
        screen.mouseClicked(24, more.top() + 5, 0);
        actions.sessions = CompletableFuture.completedFuture(sessions.subList(1, sessions.size()));
        screen.loadSessions();
        pendingPage.complete(sessions);
        assertTrue(screen.sessionRows().stream().filter(row -> row.session() != null)
                .noneMatch(row -> row.session().id().equals("chat-0")));
    }

    @Test
    void changingOtherSessionsSettingDiscardsAnOlderPendingPage() {
        var actions = new FakeActions();
        var oldPage = new CompletableFuture<List<ChatContracts.SessionSummary>>();
        actions.sessions = oldPage;
        var screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);
        actions.settings.setShowChatsFromOtherSessions(true);
        actions.sessions = CompletableFuture.completedFuture(List.of(new ChatContracts.SessionSummary(
                new ChatContracts.WorldIdentity("other", "Other"), "new", "Other world", "", Instant.EPOCH)));
        screen.loadSessions();
        oldPage.complete(List.of(new ChatContracts.SessionSummary(actions.view.activeWorld(), "old", "Old", "", Instant.EPOCH)));
        assertEquals(List.of("new"), screen.sessionRows().stream().filter(row -> row.session() != null)
                .map(row -> row.session().id()).toList());
    }

    @Test
    void sidebarLoadsConfiguredCountThenTenMorePerClick() {
        var actions = new FakeActions();
        actions.settings.setSessionsBeforeCollapse(3);
        var sessions = new ArrayList<ChatContracts.SessionSummary>();
        for (int index = 0; index < 26; index++) sessions.add(new ChatContracts.SessionSummary(
                actions.view.activeWorld(), "chat-" + index, "Question " + index, "preview", Instant.EPOCH));
        actions.sessions = CompletableFuture.completedFuture(sessions);
        var screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 900;
        ScreenTestHost.initialize(screen);
        assertEquals(3, screen.sessionRows().stream().filter(row -> row.session() != null).count());
        for (int expected : List.of(13, 23, 26)) {
            var more = screen.sessionRows().stream().filter(AssistantScreen.SessionRow::expand).findFirst().orElseThrow();
            screen.mouseScrolled(50, 200, 0, -100);
            more = screen.sessionRows().stream().filter(AssistantScreen.SessionRow::expand).findFirst().orElseThrow();
            screen.mouseClicked(24, more.top() + 5, 0);
            assertEquals(expected, screen.sessionRows().stream().filter(row -> row.session() != null).count());
        }
        assertTrue(screen.sessionRows().stream().noneMatch(AssistantScreen.SessionRow::expand));
    }

    @Test
    void messageMetadataUsesEachMessagesLocalSentTimeAndOmitsMissingResponseTime() {
        var previousZone = java.util.TimeZone.getDefault();
        var previousLocale = java.util.Locale.getDefault();
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Taipei"));
        java.util.Locale.setDefault(java.util.Locale.US);
        try {
            var actions = new FakeActions();
            var screen = new AssistantScreen(actions);
            var original = actions.view.conversation().turns().getFirst();
            var turn = new ChatContracts.Turn(original.requestId(), "Question", "Answer", List.of(), original.context(),
                    List.of(), List.of(), original.outcome(), 7, Instant.parse("2026-10-26T04:01:00Z"), List.of(),
                    List.of(), 125_000L, Instant.parse("2026-10-26T04:03:05Z"));
            assertEquals("Oct 26, 12:01 · ~3 tokens", screen.messageMetadata(turn, true, 3));
            assertEquals("Oct 26, 12:03 · ~4 tokens", screen.messageMetadata(turn, false, 4));
            var legacy = new ChatContracts.Turn(original.requestId(), "Question", "Answer", List.of(), original.context(),
                    List.of(), List.of(), original.outcome(), 7, Instant.parse("2026-10-26T04:01:00Z"));
            assertEquals("~4 tokens", screen.messageMetadata(legacy, false, 4));
            assertEquals("~3 tokens", screen.messageMetadata(original, true, 3));
        } finally {
            java.util.TimeZone.setDefault(previousZone);
            java.util.Locale.setDefault(previousLocale);
        }
    }

    @Test
    void expandedToolShowsSavedResultAndLegacyToolsOmitIt() {
        var screen = new AssistantScreen(new FakeActions());
        var step = new ChatContracts.ActivityStep("tool:result", "Reading", "Completed",
                ChatContracts.ActivityStatus.COMPLETED, "{}", "read_evidence", "Coverage: COMPLETE; records: 1",
                600L, "Loaded fixture\nLantern\nitem: minecraft:lantern");
        assertEquals("Tool: read_evidence\nParameter: {}\nResult: Loaded fixture\nLantern\nitem: minecraft:lantern\nElapsed: 600 ms",
                screen.activityBody(step, List.of()));
        assertFalse(screen.activityBody(new ChatContracts.ActivityStep("tool:old", "Reading", "Completed",
                ChatContracts.ActivityStatus.COMPLETED), List.of()).contains("Result:"));
    }

    @Test
    void initializationShowsTheScreenBeforeSelectingHistoryForRendering() {
        FakeActions actions = new FakeActions();
        var screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);

        assertEquals(0, actions.historyPreviews);
        assertFalse(screen.children().isEmpty());
        assertEquals(actions.view, screen.view());
    }

    @Test
    void fillingOlderHistoryKeepsTheScrolledMessageInPlace() {
        FakeActions actions = new FakeActions();
        var original = actions.view;
        actions.view = new ChatContracts.View(false, original.signIn(), false,
                new ChatContracts.Conversation(original.conversation().id(), original.conversation().world(),
                        java.util.Collections.nCopies(20, original.conversation().turns().getFirst()), original.conversation().outcome()),
                original.context(), original.sources(), original.navigationTargets(), List.of());
        var screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);
        screen.updateContentMetrics(1_000, 100);
        screen.mouseScrolled(400, 200, 0, 100);
        assertEquals(0, screen.scrollOffset());

        screen.tick();
        screen.updateContentMetrics(2_000, 100);

        assertEquals(1_000, screen.scrollOffset());
        assertFalse(screen.followingBottom());
    }
    @Test
    void journalFooterRequiresAVisibleUnavailableRowAndRelevantWorldOrEvidence() {
        FakeActions actions = new FakeActions();
        actions.adapterDetails = java.util.Map.of("journal", "database refused");
        AssistantScreen screen = new AssistantScreen(actions);
        var availability = java.util.Map.of("world", true, "journal", false);
        var font = new MultilineEditorTest.FakeFont();
        assertEquals("database refused", screen.adapterFooter(availability,
                AssistantScreen.adapterTooltipRows(font, availability, false, 0, 640, 480)));
        assertEquals("", screen.adapterFooter(availability,
                AssistantScreen.adapterTooltipRows(font, availability, true, 0, 640, 480)));
        assertEquals("", screen.adapterFooter(availability,
                AssistantScreen.adapterTooltipRows(font, availability, false, 0, 640, 100)));
        assertEquals("", screen.adapterFooter(java.util.Map.of("journal", true),
                AssistantScreen.adapterTooltipRows(font, java.util.Map.of("journal", true), false, 0, 640, 480)));
        assertEquals("", screen.adapterFooter(java.util.Map.of(),
                AssistantScreen.adapterTooltipRows(font, java.util.Map.of(), false, 0, 640, 480)));
        screen.refresh(new ChatContracts.View(false, ChatContracts.SignInState.SIGNED_OUT, false, actions.view.conversation(),
                actions.view.context(), List.of(new Evidence.Source(SCOPE, "journal", "1", "note:n0000abcd", 1, Instant.EPOCH,
                Evidence.Activity.INACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.PLAYER_REPORTED, List.of())), List.of(), List.of()));
        assertEquals("database refused", screen.adapterFooter(java.util.Map.of(),
                AssistantScreen.adapterTooltipRows(font, java.util.Map.of(), false, 0, 640, 480)));
    }

    @Test
    void adapterRowsFilterUnavailableEntriesAndTooltipWindowIsCappedAndClamped() {
        var availability = java.util.Map.of("native", true, "config", false);
        var first = AssistantScreen.adapterTooltipRows(new MultilineEditorTest.FakeFont(), availability, true, 0, 640, 480);
        assertEquals(List.of("native"), first.rows().stream().map(java.util.Map.Entry::getKey).toList());
        var firstAll = AssistantScreen.adapterTooltipRows(new MultilineEditorTest.FakeFont(), availability, false, 0, 640, 480);
        assertEquals(List.of("native", "config", "world", "progress", "journal", "history"), firstAll.rows().stream().map(java.util.Map.Entry::getKey).toList());
        assertEquals(0, firstAll.offset());
        assertEquals(120, firstAll.height());
        var last = AssistantScreen.adapterTooltipRows(new MultilineEditorTest.FakeFont(), availability, false, 100, 640, 100);
        assertEquals(4, last.offset());
        assertEquals(List.of("journal", "history"), last.rows().stream().map(java.util.Map.Entry::getKey).toList());
        assertEquals(2, last.visibleRows());
        assertEquals(1, AssistantScreen.adapterTooltipRows(new MultilineEditorTest.FakeFont(), availability, false, 0, 640, 82).visibleRows());
    }

    @Test
    void adapterTooltipWidthUsesLongestFilteredEntryAndScrollbarTracksScrollPosition() {
        var availability = java.util.Map.<String, Boolean>of();
        var first = AssistantScreen.adapterTooltipRows(new MultilineEditorTest.FakeFont(), availability, false, 0, 640, 100);
        var last = AssistantScreen.adapterTooltipRows(new MultilineEditorTest.FakeFont(), availability, false, 100, 640, 100);

        assertEquals(first.tooltipWidth(), last.tooltipWidth());
        assertTrue(first.tooltipWidth() >= 20 + 10 + 5 * "NeoForge configs".length() + 8);
        assertEquals(28, first.scrollTrackHeight());
        assertEquals(9, first.scrollThumbHeight());
        assertEquals(0, first.scrollThumbOffset());
        assertEquals(19, last.scrollThumbOffset());
        assertEquals(0, AssistantScreen.adapterTooltipRows(new MultilineEditorTest.FakeFont(),
                java.util.Map.of("native", true), true, 0, 640, 480).scrollTrackHeight());
    }

    @Test
    void adapterTooltipAndIconConsumeWheelAndMoveDownThroughRows() throws Exception {
        FakeActions actions = new FakeActions();
        actions.settings.setHideInactiveAdapters(false);
        actions.availableAdapters = java.util.Map.of("native", true);
        AssistantScreen screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 82;
        ScreenTestHost.initialize(screen);
        var visibleField = AssistantScreen.class.getDeclaredField("adapterPopupVisible");
        visibleField.setAccessible(true);
        visibleField.setBoolean(screen, true);
        for (var bounds : List.of("adapterPopupLeft", "adapterPopupTop", "adapterPopupWidth", "adapterPopupHeight")) {
            var field = AssistantScreen.class.getDeclaredField(bounds);
            field.setAccessible(true);
            field.setInt(screen, switch (bounds) {
                case "adapterPopupLeft" -> 200;
                case "adapterPopupTop" -> 200;
                case "adapterPopupWidth" -> 180;
                default -> 148;
            });
        }

        var offset = AssistantScreen.class.getDeclaredField("adapterScroll");
        offset.setAccessible(true);
        assertTrue(screen.mouseScrolled(210, 210, 0, -1));
        assertEquals(1, offset.getInt(screen));
        assertTrue(screen.mouseScrolled(20, 60, 0, -1));
        assertEquals(2, offset.getInt(screen));
    }

    @Test
    void closeDoesNotStopButStopAndNewChatDo() {
        FakeActions actions = new FakeActions();
        AssistantScreen screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;

        screen.close();
        assertEquals(List.of(), actions.calls);
        screen.stop();
        screen.newChat();
        assertEquals(List.of("stop", "newChat"), actions.calls);
    }

    @Test
    void staleNavigationLeavesAnswerVisibleAndUnavailableTargetsAreIgnored() {
        FakeActions actions = new FakeActions();
        AssistantScreen screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        screen.refresh(actions.view);

        screen.navigate(new Evidence.NavigationTarget(SCOPE, "provider", "stale", "target"));
        assertEquals("answer", screen.answer());
        assertEquals(List.of(), actions.calls);
    }

    @Test
    void activeStreamingAnswerIsVisibleAndCompletedAnswerDoesNotDuplicateIt() {
        FakeActions actions = new FakeActions();
        AssistantScreen screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        ChatContracts.View streaming = new ChatContracts.View(true, ChatContracts.SignInState.SIGNED_OUT,
                true, actions.view.conversation(), actions.view.context(), actions.view.sources(),
                actions.view.navigationTargets(), actions.view.errors(), "partial");

        screen.refresh(streaming);
        assertEquals("partial", screen.answer());

        screen.refresh(actions.view);
        assertEquals("answer", screen.answer());
    }

    private static ChatContracts.View active(FakeActions actions, String streaming, String activity) {
        return new ChatContracts.View(true, ChatContracts.SignInState.SIGNED_OUT, false, actions.view.conversation(),
                actions.view.context(), actions.view.sources(), actions.view.navigationTargets(), actions.view.errors(),
                streaming, activity);
    }

    @Test
    void refreshKeepsTheScrollOffsetWhenThePlayerScrolledUp() {
        FakeActions actions = new FakeActions();
        AssistantScreen screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        screen.refresh(actions.view);
        screen.updateContentMetrics(1000, 200);
        screen.mouseScrolled(400, 200, 0, -5);
        screen.mouseScrolled(400, 200, 0, 2);
        int offset = screen.scrollOffset();
        assertEquals(60, offset);

        screen.refresh(active(actions, "partial", "Writing answer…"));
        screen.refresh(actions.view);
        screen.updateContentMetrics(1000, 200);

        assertEquals(offset, screen.scrollOffset());
        assertFalse(screen.followingBottom());
    }

    @Test
    void streamingFollowsTheBottomUntilThePlayerScrollsUpAndResumesAtTheBottom() {
        FakeActions actions = new FakeActions();
        AssistantScreen screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        screen.refresh(active(actions, "a", "Thinking…"));
        screen.updateContentMetrics(1000, 200);
        assertEquals(800, screen.maxScroll());
        assertEquals(800, screen.scrollOffset());

        screen.refresh(active(actions, "ab", "Writing answer…"));
        screen.updateContentMetrics(1100, 200);
        assertEquals(900, screen.scrollOffset());

        screen.mouseScrolled(400, 200, 0, 1);
        assertEquals(880, screen.scrollOffset());
        assertFalse(screen.followingBottom());
        screen.updateContentMetrics(1300, 200);
        assertEquals(880, screen.scrollOffset());

        screen.mouseScrolled(400, 200, 0, -100);
        assertEquals(1100, screen.scrollOffset());
        assertTrue(screen.followingBottom());
        screen.updateContentMetrics(1400, 200);
        assertEquals(1200, screen.scrollOffset());
    }

    @Test
    void maxScrollUsesTheMeasuredContentHeightAndShrinkingContentClampsTheOffset() {
        FakeActions actions = new FakeActions();
        AssistantScreen screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        screen.refresh(actions.view);
        screen.updateContentMetrics(5000, 100);
        screen.mouseScrolled(400, 200, 0, -1000);
        assertEquals(4900, screen.scrollOffset());

        screen.updateContentMetrics(300, 100);
        assertEquals(200, screen.maxScroll());
        assertEquals(200, screen.scrollOffset());
        screen.updateContentMetrics(50, 100);
        assertEquals(0, screen.maxScroll());
        assertEquals(0, screen.scrollOffset());
    }

    @Test
    void submittingAQuestionScrollsToTheBottomAndFollows() {
        FakeActions actions = new FakeActions();
        AssistantScreen screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        screen.refresh(actions.view);
        screen.updateContentMetrics(1000, 200);
        screen.mouseScrolled(400, 200, 0, 10);
        assertFalse(screen.followingBottom());

        screen.setInput("how do I craft a lantern?");
        screen.submit();

        assertEquals(List.of("send"), actions.calls);
        assertEquals(800, screen.scrollOffset());
        assertTrue(screen.followingBottom());
    }

    @Test
    void activeComposerKeepsRejectedDraftAndSeparatesSendFromStop() {
        FakeActions actions = new FakeActions();
        AssistantScreen screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);
        screen.refresh(active(actions, "partial", "Writing answer…"));
        screen.setInput("please clarify");
        actions.steeringAccepted = false;
        screen.submit();

        assertEquals("please clarify", screen.input());
        assertEquals(List.of("steer"), actions.calls);
        actions.steeringAccepted = true;
        screen.submit();
        assertEquals("", screen.input());
        assertEquals("please clarify", actions.sentQuestion);
        assertEquals(List.of("steer", "steer"), actions.calls);

        screen.setInput("one more thing");
        var editor = (MultilineEditor) screen.children().getLast();
        int buttonY = editor.getY() + editor.getHeight() - 10;
        screen.mouseClicked(editor.getX() + editor.getWidth() + 8, buttonY, 0);
        assertEquals("stop", actions.calls.getLast());
        screen.mouseClicked(editor.getX() + editor.getWidth() + 34, buttonY, 0);
        assertEquals("steer", actions.calls.getLast());
    }

    @Test
    void answerSegmentsPlaceSteeringBetweenAssistantText() {
        assertEquals(List.of(new AssistantScreen.MessageSegment("AB", false, 0),
                        new AssistantScreen.MessageSegment("clarify", true, 2),
                        new AssistantScreen.MessageSegment("CD", false, 2)),
                AssistantScreen.answerSegments("ABCD", List.of(new ChatContracts.SteeringMessage("clarify", 2))));
    }

    @Test
    void activeStopControlDoesNotOverlapComposerAtNarrowWidths() {
        for (int width : List.of(240, 320)) {
            AssistantScreen screen = new AssistantScreen(new FakeActions());
            screen.width = width;
            screen.height = 480;
            ScreenTestHost.initialize(screen);
            screen.refresh(active(new FakeActions(), "partial", "Writing answer…"));
            var editor = (MultilineEditor) screen.children().getLast();
            int sidebarWidth = Math.min(ModernUi.SIDEBAR_WIDTH, Math.max(100, width / 3));
            int mainWidth = Math.max(1, width - sidebarWidth);
            int readableWidth = Math.max(1, Math.min(ModernUi.MAX_CONTENT_WIDTH, mainWidth - 32));
            int readableLeft = sidebarWidth + (mainWidth - readableWidth) / 2;
            int stopX = readableLeft + readableWidth - 46;

            assertTrue(editor.getX() + editor.getWidth() <= stopX, "composer overlaps Stop at width " + width);
        }
    }

    private static ChatContracts.View withTargets(ChatContracts.View base, boolean active, String streaming,
                                                  List<Evidence.NavigationTarget> targets) {
        return new ChatContracts.View(active, ChatContracts.SignInState.SIGNED_OUT, false, base.conversation(),
                base.context(), base.sources(), targets, base.errors(), streaming, active ? "Writing answer…" : "");
    }

    @Test
    void followingContinuesThroughCompletionSoTheFinishedAnswerStaysVisible() {
        FakeActions actions = new FakeActions();
        AssistantScreen screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        screen.refresh(active(actions, "partial", "Writing answer…"));
        screen.updateContentMetrics(1000, 200);
        assertEquals(800, screen.scrollOffset());

        screen.refresh(withTargets(actions.view, false, "", List.of(TARGET, TARGET_TWO)));
        screen.updateContentMetrics(1200, 200);
        assertEquals(1000, screen.scrollOffset());

        screen.refresh(actions.view);
        screen.updateContentMetrics(1400, 200);
        assertEquals(1000, screen.scrollOffset());
    }

    @Test
    void completionDoesNotPullAPlayerWhoScrolledUp() {
        FakeActions actions = new FakeActions();
        AssistantScreen screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        screen.refresh(active(actions, "partial", "Writing answer…"));
        screen.updateContentMetrics(1000, 200);
        screen.mouseScrolled(400, 200, 0, 5);
        assertEquals(700, screen.scrollOffset());

        screen.refresh(actions.view);
        screen.updateContentMetrics(1200, 200);

        assertEquals(700, screen.scrollOffset());
    }

    @Test
    void navigationFeedbackStaysVisibleAtTheBottomWithoutPullingEarlierHistory() {
        FakeActions actions = new FakeActions();
        AssistantScreen screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        screen.refresh(active(actions, "partial", "Writing answer…"));
        screen.updateContentMetrics(1000, 200);
        screen.refresh(actions.view);
        screen.updateContentMetrics(1000, 200);

        screen.showNavigationError("Target is unavailable");
        screen.updateContentMetrics(1040, 200);
        assertEquals(840, screen.scrollOffset());

        screen.mouseScrolled(400, 200, 0, 5);
        screen.showNavigationError("Another target is unavailable");
        screen.updateContentMetrics(1080, 200);
        assertEquals(740, screen.scrollOffset());
    }

    @Test
    void statusLineShowsTheActivityWhileActiveAndIdleOtherwise() {
        FakeActions actions = new FakeActions();
        AssistantScreen screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;

        screen.refresh(active(actions, "", "Reading evidence (step 2)"));
        assertEquals("Reading evidence (step 2)", screen.statusLine());
        screen.refresh(active(actions, "", ""));
        assertEquals("Working...", screen.statusLine());
        screen.refresh(actions.view);
        assertEquals("Request: idle", screen.statusLine());
    }

    @Test
    void activityExpansionDefaultsOpenCollapsesOnceAndPreservesManualChoiceAcrossTurnTransfer() {
        AssistantScreen screen = new AssistantScreen(new FakeActions());
        String key = "request:request:tool:call-1";

        screen.updateActivityExpansion(key, ChatContracts.ActivityStatus.RUNNING);
        assertTrue(screen.activityExpanded(key));
        screen.toggleActivity(key);
        assertFalse(screen.activityExpanded(key));
        screen.updateActivityExpansion(key, ChatContracts.ActivityStatus.RUNNING);
        assertFalse(screen.activityExpanded(key));

        screen.updateActivityExpansion(key, ChatContracts.ActivityStatus.COMPLETED);
        assertFalse(screen.activityExpanded(key));
        screen.toggleActivity(key);
        screen.updateActivityExpansion(key, ChatContracts.ActivityStatus.COMPLETED);
        assertTrue(screen.activityExpanded(key));

        screen.updateActivityExpansion(key, ChatContracts.ActivityStatus.COMPLETED);
        assertTrue(screen.activityExpanded(key));
    }

    @Test
    void completedStepsRenderAsOneCollapsedGroupHeader() {
        AssistantScreen screen = new AssistantScreen(new FakeActions());
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);
        String key = "request:request";
        List<ChatContracts.ActivityStep> steps = List.of(
                new ChatContracts.ActivityStep("tool:one", "First tool", "Completed", ChatContracts.ActivityStatus.COMPLETED),
                new ChatContracts.ActivityStep("tool:two", "Second tool", "Completed", ChatContracts.ActivityStatus.COMPLETED),
                new ChatContracts.ActivityStep("tool:three", "Third tool", "Completed", ChatContracts.ActivityStatus.COMPLETED),
                new ChatContracts.ActivityStep("tool:four", "Fourth tool", "Completed", ChatContracts.ActivityStatus.COMPLETED));

        AssistantScreen.MessageLayout header = screen.activityGroupHeader(key, steps.size(),
                ChatContracts.ActivityStatus.COMPLETED, 100);

        assertFalse(screen.activityExpanded(key));
        assertTrue(header.height() <= ModernUi.MESSAGE_LINE_HEIGHT);
        assertTrue(header.runs().stream().map(run -> run.span().text()).collect(java.util.stream.Collectors.joining())
                .contains("4 steps"));
    }

    @Test
    void responseGroupStaysOpenBetweenCompletedStepsThenCollapsesAndCanBeReopened() {
        FakeActions actions = new FakeActions();
        AssistantScreen screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);
        String key = "request:request";
        List<ChatContracts.ActivityStep> steps = List.of(
                new ChatContracts.ActivityStep("tool:one", "First tool", "Completed", ChatContracts.ActivityStatus.COMPLETED),
                new ChatContracts.ActivityStep("tool:two", "Second tool", "Completed", ChatContracts.ActivityStatus.COMPLETED),
                new ChatContracts.ActivityStep("tool:three", "Third tool", "Completed", ChatContracts.ActivityStatus.COMPLETED),
                new ChatContracts.ActivityStep("tool:four", "Fourth tool", "Completed", ChatContracts.ActivityStatus.COMPLETED));
        ChatContracts.View running = new ChatContracts.View(true, actions.view.signIn(), false,
                actions.view.conversation(), actions.view.context(), actions.view.sources(), actions.view.navigationTargets(),
                actions.view.errors(), "partial", "Working...", actions.view.activeWorld(), false,
                "question", Instant.EPOCH, steps, "request");
        ChatContracts.Turn priorTurn = actions.view.conversation().turns().getLast();
        ChatContracts.Turn savedTurn = new ChatContracts.Turn(priorTurn.requestId(), priorTurn.question(), priorTurn.answer(),
                priorTurn.replayItems(), priorTurn.context(), priorTurn.sources(), priorTurn.navigationTargets(),
                priorTurn.outcome(), priorTurn.tokenCount(), priorTurn.sentAt(), steps);
        ChatContracts.Conversation savedConversation = new ChatContracts.Conversation(actions.view.conversation().id(),
                actions.view.conversation().world(), List.of(savedTurn), actions.view.conversation().outcome());
        ChatContracts.View saved = new ChatContracts.View(false, actions.view.signIn(), false, savedConversation,
                actions.view.context(), actions.view.sources(), actions.view.navigationTargets(), actions.view.errors(), "", "",
                actions.view.activeWorld(), true, "", null, List.of());

        screen.refresh(running);
        screen.activityGroupHeader("request:" + screen.view().activeRequestId(), screen.view().activities().size(),
                ChatContracts.ActivityStatus.RUNNING, 100);
        assertTrue(screen.activityExpanded(key));

        screen.refresh(saved);
        AssistantScreen.MessageLayout completed = screen.activityGroupHeader(
                "request:" + screen.view().conversation().turns().getLast().requestId().value(), steps.size(),
                ChatContracts.ActivityStatus.COMPLETED, 100);
        assertEquals(key, "request:" + screen.view().conversation().turns().getLast().requestId().value());
        assertFalse(screen.activityExpanded(key));
        assertTrue(screen.mouseClicked(260, 110, 0));
        screen.activityGroupHeader(key, steps.size(), ChatContracts.ActivityStatus.COMPLETED, 100);
        assertTrue(screen.activityExpanded(key));
        assertTrue(completed.height() <= ModernUi.MESSAGE_LINE_HEIGHT);
    }

    @Test
    void groupManualToggleSurvivesStepCountUpdatesAndTerminalRefreshes() {
        AssistantScreen screen = new AssistantScreen(new FakeActions());
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);
        String key = "request:request";

        screen.activityGroupHeader(key, 4, ChatContracts.ActivityStatus.RUNNING, 100);
        assertTrue(screen.mouseClicked(260, 110, 0));
        screen.activityGroupHeader(key, 5, ChatContracts.ActivityStatus.RUNNING, 100);
        assertFalse(screen.activityExpanded(key));

        screen.activityGroupHeader(key, 5, ChatContracts.ActivityStatus.COMPLETED, 100);
        assertTrue(screen.mouseClicked(260, 110, 0));
        screen.activityGroupHeader(key, 5, ChatContracts.ActivityStatus.COMPLETED, 100);
        assertTrue(screen.activityExpanded(key));
        screen.activityGroupHeader(key, 5, ChatContracts.ActivityStatus.COMPLETED, 100);
        assertTrue(screen.activityExpanded(key));
    }

    @Test
    void failedAndCancelledResponsesAlsoCollapseTheGroup() {
        AssistantScreen screen = new AssistantScreen(new FakeActions());
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);

        for (ChatContracts.ActivityStatus status : List.of(ChatContracts.ActivityStatus.FAILED,
                ChatContracts.ActivityStatus.CANCELLED)) {
            String key = "request:" + status.name().toLowerCase(java.util.Locale.ROOT);
            screen.activityGroupHeader(key, 4, ChatContracts.ActivityStatus.RUNNING, 100);
            assertTrue(screen.activityExpanded(key));
            AssistantScreen.MessageLayout header = screen.activityGroupHeader(key, 4, status, 100);
            assertFalse(screen.activityExpanded(key));
            assertTrue(header.height() <= ModernUi.MESSAGE_LINE_HEIGHT);
        }
    }

    @Test
    void activityHeadersWrapWithinNarrowContentWidth() {
        AssistantScreen screen = new AssistantScreen(new FakeActions());
        screen.width = 320;
        screen.height = 480;
        ScreenTestHost.initialize(screen);
        ChatContracts.ActivityStep step = new ChatContracts.ActivityStep("tool:one",
                "Searching pack: " + "long_identifier_".repeat(5), "", ChatContracts.ActivityStatus.RUNNING);
        AssistantScreen.MessageLayout layout = screen.activityHeader("request:request:tool:one", step, 100);

        assertTrue(layout.height() >= ModernUi.MESSAGE_LINE_HEIGHT * 2);
        assertTrue(layout.runs().stream().allMatch(run -> run.x() + run.width() <= 156));
    }

    @Test
    void activityHeaderClickTogglesAndViewportClippingRejectsOutsideClick() {
        AssistantScreen screen = new AssistantScreen(new FakeActions());
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);
        String key = "request:request:tool:call-1";
        ChatContracts.ActivityStep running = new ChatContracts.ActivityStep("tool:call-1", "Reading history", "Running tool…",
                ChatContracts.ActivityStatus.RUNNING);
        screen.activityHeader(key, running, 100);

        assertTrue(screen.mouseClicked(260, 110, 0));
        assertFalse(screen.activityExpanded(key));
        screen.activityHeader(key, new ChatContracts.ActivityStep("tool:call-1", "Reading history", "Completed",
                ChatContracts.ActivityStatus.COMPLETED), 100);
        assertFalse(screen.activityExpanded(key));
        assertTrue(screen.mouseClicked(260, 110, 0));
        screen.activityHeader(key, new ChatContracts.ActivityStep("tool:call-1", "Reading history", "Completed",
                ChatContracts.ActivityStatus.COMPLETED), 100);
        assertTrue(screen.activityExpanded(key));
        screen.activityHeader(key, running, 20);
        assertFalse(screen.mouseClicked(260, 25, 0));
        assertTrue(screen.activityExpanded(key));
        screen.activityHeader(key, running, 36);
        assertTrue(screen.mouseClicked(260, 44, 0));
        assertFalse(screen.activityExpanded(key));
    }

    @Test
    void legacyExpandedToolActivityResolvesArgumentsByExactCallId() {
        AssistantScreen screen = new AssistantScreen(new FakeActions());
        String body = screen.activityBody(new ChatContracts.ActivityStep("tool:second", "Reading evidence", "",
                ChatContracts.ActivityStatus.COMPLETED), List.of(
                new ModelBackend.ToolCall("first", "packsage", "read_evidence", "{\"selected\":\"wrong call\"}"),
                new ModelBackend.ToolCall("second", "packsage", "read_evidence",
                        "{\"selected\":null,\"path\":[{\"child\":true}]}")));

        assertTrue(body.contains("selected"));
        assertTrue(body.contains("null"));
        assertTrue(body.contains("child"));
        assertFalse(body.contains("wrong call"));

        String bounded = screen.activityBody(new ChatContracts.ActivityStep("tool:large", "Reading evidence", "",
                        ChatContracts.ActivityStatus.RUNNING), List.of(new ModelBackend.ToolCall("large", "packsage",
                        "read_evidence", "{\"payload\":\"" + "x".repeat(6000) + "\"}")));
        assertFalse(bounded.isEmpty());
        assertTrue(bounded.length() <= 2048, "expanded tool arguments should have an explicit display bound");
        assertTrue(bounded.contains("[truncated]"));
    }

    @Test
    void toolActivityBodyPrefersCapturedArgumentsAndHidesMalformedOrUnavailablePayloads() {
        AssistantScreen screen = new AssistantScreen(new FakeActions());
        String captured = screen.activityBody(new ChatContracts.ActivityStep("tool:call-1", "Lookup", "Completed",
                ChatContracts.ActivityStatus.COMPLETED, "{\"current\":null}"), List.of(
                new ModelBackend.ToolCall("call-1", "packsage", "lookup", "{\"legacy\":true}")));
        assertTrue(captured.contains("current"));
        assertTrue(captured.contains("null"));
        assertFalse(captured.contains("legacy"));

        assertTrue(screen.activityBody(new ChatContracts.ActivityStep("tool:empty", "Lookup", "Completed",
                ChatContracts.ActivityStatus.COMPLETED, "{}"), List.of()).contains("Parameter: {}"));

        String malformed = screen.activityBody(new ChatContracts.ActivityStep("tool:broken", "Lookup", "Completed",
                ChatContracts.ActivityStatus.FAILED, "{broken SECRET"), List.of());
        assertTrue(malformed.contains("malformed JSON"));
        assertFalse(malformed.contains("SECRET"));

        assertTrue(screen.activityBody(new ChatContracts.ActivityStep("tool:missing", "Lookup", "Completed",
                ChatContracts.ActivityStatus.COMPLETED), List.of()).contains("Arguments unavailable"));
    }

    @Test
    void navigationRequiresTheRegisteredTargetTuple() {
        FakeActions actions = new FakeActions();
        AssistantScreen screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;

        screen.navigate(TARGET);

        assertEquals(List.of("navigate"), actions.calls);
    }

    @Test
    void navigationFeedbackExpiresAndASecondNavigationReplacesIt() {
        AssistantScreen screen = new AssistantScreen(new FakeActions());

        screen.showNavigationError("First target is unavailable");
        assertEquals("First target is unavailable", screen.navigationError(0));
        long replacementStarted = System.currentTimeMillis();
        screen.showNavigationError("Second target is unavailable");
        assertEquals("Second target is unavailable", screen.navigationError(replacementStarted + 4_999));
        assertNull(screen.navigationError(System.currentTimeMillis() + 5_000));
    }

    @Test
    void navigationFeedbackClearsOnNextNavigationAndSessionOrWorldChange() {
        FakeActions actions = new FakeActions();
        AssistantScreen screen = new AssistantScreen(actions);
        screen.showNavigationError("Target is unavailable");

        screen.navigate(TARGET);

        assertNull(screen.navigationError(0));
        screen.showNavigationError("Target is unavailable");
        ChatContracts.Conversation current = actions.view.conversation();
        ChatContracts.Conversation nextConversation = new ChatContracts.Conversation("conversation-next", current.world(),
                current.turns(), current.outcome());
        screen.refresh(new ChatContracts.View(false, ChatContracts.SignInState.SIGNED_OUT, true, nextConversation,
                actions.view.context(), actions.view.sources(), actions.view.navigationTargets(), actions.view.errors(),
                actions.view.streamingAnswer(), actions.view.activity(), actions.view.activeWorld(), false));
        assertNull(screen.navigationError(0));

        screen.showNavigationError("Target is unavailable");
        screen.refresh(new ChatContracts.View(false, ChatContracts.SignInState.SIGNED_OUT, true, nextConversation,
                actions.view.context(), actions.view.sources(), actions.view.navigationTargets(), actions.view.errors(),
                actions.view.streamingAnswer(), actions.view.activity(), new ChatContracts.WorldIdentity("world-2", "World 2"), false));
        assertNull(screen.navigationError(0));
    }

    @Test
    void questionFieldAcceptsQuestionsLongerThanTheVanillaDefault() {
        FakeActions actions = new FakeActions();
        AssistantScreen screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        String question = "Use installed recipes: how many torches does the coal-or-charcoal recipe produce? "
                + "Show its ingredients and source.";
        ScreenTestHost.initialize(screen);

        screen.setInput(question);
        screen.refresh(actions.view);

        assertEquals(question, screen.input());
        screen.setInput("x".repeat(AssistantScreen.QUESTION_MAX_LENGTH + 1));
        screen.refresh(actions.view);
        assertEquals(AssistantScreen.QUESTION_MAX_LENGTH, screen.input().length());
    }

    @Test
    void pauseControlUsesEffectivePrivateSingleplayerState() {
        FakeActions actions = new FakeActions();
        AssistantScreen screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;

        screen.setRuntimeState(new AssistantScreen.RuntimeState(false, false, false));
        assertFalse(screen.isPauseScreen());
        screen.setPaused(true);
        assertEquals(List.of(), actions.calls);

        screen.setRuntimeState(new AssistantScreen.RuntimeState(true, true, false));
        assertTrue(screen.isPauseScreen());
        screen.setPaused(false);
        assertEquals(List.of("pause:false"), actions.calls);
    }

    @Test
    void sendControlStopsThroughoutCaptureModelAndToolPhasesThenSendsAgainAfterCompletion() {
        FakeActions actions = new FakeActions();
        var screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);
        screen.setInput("next question");
        for (String phase : List.of("Capturing context...", "Waiting Model", "Thinking...", "Reading evidence")) {
            screen.refresh(active(actions, "", phase));
            var editor = (MultilineEditor) screen.children().getLast();
            screen.mouseClicked(editor.getX() + editor.getWidth() + 12, editor.getY() + editor.getHeight() - 10, 0);
        }
        assertEquals(List.of("stop", "stop", "stop", "stop"), actions.calls);
        screen.refresh(actions.view);
        var editor = (MultilineEditor) screen.children().getLast();
        screen.mouseClicked(editor.getX() + editor.getWidth() + 12, editor.getY() + editor.getHeight() - 10, 0);
        assertEquals("send", actions.calls.getLast());
    }

    @Test
    void rapidSecondClickStopsBeforeTheFirstActiveViewArrives() {
        FakeActions actions = new FakeActions();
        var screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);
        screen.setInput("question");
        var editor = (MultilineEditor) screen.children().getLast();
        int y = editor.getY() + editor.getHeight() - 10;
        screen.mouseClicked(editor.getX() + editor.getWidth() + 12, y, 0);
        ScreenTestHost.initialize(screen);
        editor = (MultilineEditor) screen.children().getLast();
        y = editor.getY() + editor.getHeight() - 10;
        screen.mouseClicked(editor.getX() + editor.getWidth() + 12, y, 0);
        assertEquals(List.of("send", "stop"), actions.calls);
        screen.refresh(actions.view);
        editor = (MultilineEditor) screen.children().getLast();
        screen.setInput("next question");
        screen.mouseClicked(editor.getX() + editor.getWidth() + 12,
                editor.getY() + editor.getHeight() - 10, 0);
        assertEquals(List.of("send", "stop", "send"), actions.calls);
    }

    @Test
    void differentLabelsForOneWorldShareOneSidebarGroup() {
        FakeActions actions = new FakeActions();
        var renamed = new ChatContracts.WorldIdentity(actions.view.activeWorld().worldOrServer(), "Old Name");
        actions.sessions = CompletableFuture.completedFuture(List.of(
                new ChatContracts.SessionSummary(renamed, "old", "Old chat", "", Instant.EPOCH),
                new ChatContracts.SessionSummary(actions.view.activeWorld(), "new", "New chat", "", Instant.EPOCH)));
        var screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);
        assertEquals(1, screen.sessionRows().stream().filter(row -> row.session() == null && !row.expand()).count());
        assertEquals("World", screen.sessionRows().getFirst().label());
        assertEquals(2, screen.sessionRows().stream().filter(row -> row.session() != null).count());
    }

    @Test
    void sessionDeletionRequiresConfirmationAndIsBlockedWhileActive() {
        FakeActions actions = new FakeActions();
        var session = new ChatContracts.SessionSummary(actions.view.activeWorld(), "saved", "Saved chat", "", Instant.EPOCH);
        actions.sessions = CompletableFuture.completedFuture(List.of(session));
        var screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);
        var row = screen.sessionRows().stream().filter(value -> value.session() != null).findFirst().orElseThrow();
        int deleteX = Math.min(ModernUi.SIDEBAR_WIDTH, 640 / 3) - 26;
        screen.mouseClicked(deleteX, row.top() + 17, 0);
        assertTrue(actions.calls.isEmpty());
        screen.mouseClicked(95, row.top() + 22, 0);
        assertTrue(actions.calls.isEmpty());
        screen.width = 320;
        screen.layout();
        screen.mouseClicked(320 / 3 - 26, row.top() + 17, 0);
        screen.mouseClicked(80, row.top() + 22, 0);
        screen.mouseClicked(35, row.top() + 22, 0);
        assertEquals(List.of("open:saved"), actions.calls);
        actions.calls.clear();
        screen.width = 640;
        screen.layout();
        screen.mouseClicked(deleteX, row.top() + 17, 0);
        screen.mouseClicked(35, row.top() + 22, 0);
        assertEquals(List.of("delete:saved"), actions.calls);
        actions.calls.clear();
        var activeSaved = new ChatContracts.SessionSummary(actions.view.activeWorld(), "saved", "Saved chat", "",
                Instant.EPOCH, true, null);
        actions.sessions = CompletableFuture.completedFuture(List.of(activeSaved));
        screen.refreshSessions(List.of(activeSaved));
        screen.refresh(active(actions, "", "Thinking..."));
        screen.mouseClicked(deleteX, row.top() + 17, 0);
        assertFalse(actions.calls.contains("delete:saved"));
    }

    @Test
    void sessionListPushRejectsAnOlderLoadWithoutReplacingTheSelectedView() throws Exception {
        FakeActions actions = new FakeActions();
        CompletableFuture<List<ChatContracts.SessionSummary>> olderLoad = new CompletableFuture<>();
        actions.sessions = olderLoad;
        AssistantScreen screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);
        ChatContracts.View selected = screen.view();
        var pushed = new ChatContracts.SessionSummary(actions.view.activeWorld(), "live", "Live title", "Latest answer",
                Instant.EPOCH, true, null);

        AssistantScreen.class.getMethod("refreshSessions", List.class).invoke(screen, List.of(pushed));
        olderLoad.complete(List.of(new ChatContracts.SessionSummary(actions.view.activeWorld(), "stale", "Stale title", "",
                Instant.EPOCH)));

        assertSame(selected, screen.view());
        assertEquals(List.of("live"), screen.sessionRows().stream().filter(row -> row.session() != null)
                .map(row -> row.session().id()).toList());
    }

    @Test
    void inactiveSessionCanBeDeletedWhileAnotherRowIsActiveAndNewChatRemainsAvailable() {
        FakeActions actions = new FakeActions();
        var active = new ChatContracts.SessionSummary(actions.view.activeWorld(), "conversation", "Active", "Working",
                Instant.EPOCH, true, null);
        var inactive = new ChatContracts.SessionSummary(actions.view.activeWorld(), "saved", "Saved", "Done",
                Instant.EPOCH, false, null);
        actions.sessions = CompletableFuture.completedFuture(List.of(active, inactive));
        AssistantScreen screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);
        screen.refresh(active(actions, "partial", "Writing answer…"));

        screen.mouseClicked(20, 70, 0);
        assertEquals(List.of("newChat"), actions.calls);
        actions.calls.clear();
        var savedRow = screen.sessionRows().stream().filter(row -> row.session() != null
                && row.session().id().equals("saved")).findFirst().orElseThrow();
        int deleteX = Math.min(ModernUi.SIDEBAR_WIDTH, 640 / 3) - 26;
        screen.mouseClicked(deleteX, savedRow.top() + 17, 0);
        screen.mouseClicked(35, savedRow.top() + 22, 0);
        assertEquals(List.of("delete:saved"), actions.calls);

        actions.calls.clear();
        var activeRow = screen.sessionRows().stream().filter(row -> row.session() != null
                && row.session().id().equals("conversation")).findFirst().orElseThrow();
        screen.mouseClicked(deleteX, activeRow.top() + 17, 0);
        screen.mouseClicked(35, activeRow.top() + 22, 0);
        assertFalse(actions.calls.contains("delete:conversation"));
    }

    @Test
    void conversationSwitchesRestoreSeparateDraftEditScrollFollowAndActivityState() throws Exception {
        FakeActions actions = new FakeActions();
        AssistantScreen screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);
        assertTrue(screen.editLatest(new ChatContracts.RequestId("request")));
        screen.setInput("A edited draft");
        screen.updateContentMetrics(1000, 200);
        screen.mouseScrolled(400, 200, 0, -100);
        screen.mouseScrolled(400, 200, 0, 10);
        int aOffset = screen.scrollOffset();
        screen.updateActivityExpansion("request:request", ChatContracts.ActivityStatus.COMPLETED);
        screen.toggleActivity("request:request");

        ChatContracts.View b = new ChatContracts.View(false, ChatContracts.SignInState.SIGNED_OUT, false,
                new ChatContracts.Conversation("b", actions.view.conversation().world(), List.of(),
                        new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null)),
                actions.view.context(), List.of(), List.of(), List.of(), "", "");
        screen.refresh(b);
        assertEquals("", screen.input());
        screen.setInput("B draft");
        screen.updateContentMetrics(1200, 200);
        screen.mouseScrolled(400, 200, 0, -100);
        screen.mouseScrolled(400, 200, 0, 5);
        int bOffset = screen.scrollOffset();
        screen.updateActivityExpansion("request:request", ChatContracts.ActivityStatus.RUNNING);
        screen.toggleActivity("request:request");
        assertFalse(screen.activityExpanded("request:request"));

        AssistantScreen.class.getMethod("refreshSessions", List.class).invoke(screen, List.of(
                new ChatContracts.SessionSummary(actions.view.activeWorld(), "conversation", "A", "Completed",
                        Instant.EPOCH, true, null)));
        assertSame(b, screen.view());
        assertEquals("B draft", screen.input());
        assertEquals(bOffset, screen.scrollOffset());
        assertFalse(screen.followingBottom());
        assertFalse(screen.activityExpanded("request:request"));

        screen.refresh(actions.view);
        assertEquals("A edited draft", screen.input());
        assertEquals(aOffset, screen.scrollOffset());
        assertFalse(screen.followingBottom());
        assertTrue(screen.activityExpanded("request:request"));
        screen.submit();
        assertEquals("resend:request", actions.calls.getLast());
        assertEquals("A edited draft", actions.sentQuestion);

        screen.refresh(b);
        assertEquals("B draft", screen.input());
        assertEquals(bOffset, screen.scrollOffset());
        assertFalse(screen.activityExpanded("request:request"));
    }

    @Test
    void conversationSwitchRestoresBackfilledHistoryWindowAndScrollPosition() {
        FakeActions actions = new FakeActions();
        ChatContracts.View original = actions.view;
        actions.view = new ChatContracts.View(false, original.signIn(), false,
                new ChatContracts.Conversation(original.conversation().id(), original.conversation().world(),
                        java.util.Collections.nCopies(12, original.conversation().turns().getFirst()), original.conversation().outcome()),
                original.context(), original.sources(), original.navigationTargets(), List.of());
        AssistantScreen screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);
        screen.tick();
        screen.tick();
        screen.updateContentMetrics(2_000, 200);
        screen.mouseScrolled(400, 200, 0, 5);
        int offset = screen.scrollOffset();

        ChatContracts.View other = new ChatContracts.View(false, ChatContracts.SignInState.SIGNED_OUT, false,
                new ChatContracts.Conversation("other", original.conversation().world(), List.of(),
                        new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null)),
                original.context(), List.of(), List.of(), List.of(), "", "");
        screen.refresh(other);
        screen.refresh(actions.view);
        screen.tick();
        screen.updateContentMetrics(2_400, 200);

        assertEquals(offset, screen.scrollOffset());
    }

    @Test
    void loadingReopenPreservesEditTargetUntilTheLoadedViewCanResend() {
        FakeActions actions = new FakeActions();
        AssistantScreen screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);
        assertTrue(screen.editLatest(new ChatContracts.RequestId("request")));
        screen.setInput("Edited question while reopening");

        screen.refresh(new ChatContracts.View(false, actions.view.signIn(), actions.view.paused(),
                new ChatContracts.Conversation("other", actions.view.conversation().world(), List.of(),
                        new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null)),
                actions.view.context(), List.of(), List.of(), List.of(), "", "", actions.view.activeWorld(), false));
        screen.refresh(new ChatContracts.View(false, actions.view.signIn(), actions.view.paused(),
                actions.view.conversation(), actions.view.context(), actions.view.sources(), actions.view.navigationTargets(),
                actions.view.errors(), "", "Loading session...", actions.view.activeWorld(), true, "", null, List.of(), null, List.of()));

        assertEquals("Edited question while reopening", screen.input());
        screen.refresh(actions.view);
        screen.submit();

        assertEquals("resend:request", actions.calls.getLast());
        assertEquals("Edited question while reopening", actions.sentQuestion);
    }

    @Test
    void aConversationThatGrowsWhileAwayRestoresItsFollowBottomChoiceWithoutChangingOtherScrollState() {
        FakeActions actions = new FakeActions();
        AssistantScreen screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);
        screen.updateContentMetrics(1000, 200);

        ChatContracts.View b = new ChatContracts.View(false, ChatContracts.SignInState.SIGNED_OUT, false,
                new ChatContracts.Conversation("b", actions.view.conversation().world(), List.of(),
                        new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null)),
                actions.view.context(), List.of(), List.of(), List.of(), "", "");
        screen.refresh(b);
        screen.updateContentMetrics(1000, 200);
        screen.mouseScrolled(400, 200, 0, 2);
        int bOffset = screen.scrollOffset();
        assertFalse(screen.followingBottom());

        ChatContracts.Turn nextTurn = new ChatContracts.Turn(new ChatContracts.RequestId("next-request"), "next question",
                "next answer", List.of(), actions.view.context(), actions.view.sources(), actions.view.navigationTargets(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 2, Instant.EPOCH);
        screen.refresh(new ChatContracts.View(false, actions.view.signIn(), false,
                new ChatContracts.Conversation("conversation", actions.view.conversation().world(),
                        List.of(actions.view.conversation().turns().getFirst(), nextTurn),
                        new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null)),
                actions.view.context(), actions.view.sources(), actions.view.navigationTargets(), List.of(), "", ""));
        screen.updateContentMetrics(1400, 200);
        assertEquals(1200, screen.scrollOffset());
        assertTrue(screen.followingBottom());

        screen.refresh(b);
        screen.updateContentMetrics(1000, 200);
        assertEquals(bOffset, screen.scrollOffset());
        assertFalse(screen.followingBottom());
    }

    @Test
    void composerAndStopControlsFollowTheSelectedConversationView() {
        FakeActions actions = new FakeActions();
        AssistantScreen screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);
        ChatContracts.View b = new ChatContracts.View(false, ChatContracts.SignInState.SIGNED_OUT, false,
                new ChatContracts.Conversation("b", actions.view.conversation().world(), List.of(),
                        new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null)),
                actions.view.context(), List.of(), List.of(), List.of(), "", "");
        screen.refresh(b);
        screen.setInput("B question");
        screen.submit();
        assertEquals(List.of("send"), actions.calls);
        assertEquals("B question", actions.sentQuestion);

        screen.refresh(new ChatContracts.View(true, ChatContracts.SignInState.SIGNED_OUT, false, b.conversation(),
                b.context(), b.sources(), b.navigationTargets(), List.of(), "partial", "Writing answer…"));
        screen.setInput("B follow-up");
        screen.submit();
        screen.stop();
        assertEquals(List.of("send", "steer", "stop"), actions.calls);
        assertEquals("B follow-up", actions.sentQuestion);
    }

    @Test
    void unreadAcknowledgementUsesTheExpectedRequestOnlyForDisplayedCompletedSelection() throws Exception {
        FakeActions actions = new FakeActions();
        var unread = new ChatContracts.SessionSummary(actions.view.activeWorld(), "conversation", "Finished title",
                "Finished answer", Instant.EPOCH, false, new ChatContracts.RequestId("request"));
        actions.sessions = CompletableFuture.completedFuture(List.of(unread));
        AssistantScreen screen = new AssistantScreen(actions);
        screen.width = 240;
        screen.height = 240;
        ScreenTestHost.initialize(screen);
        AssistantScreen.class.getMethod("refreshSessions", List.class).invoke(screen, List.of(unread));
        var unreadRow = screen.sessionRows().stream().filter(row -> row.session() != null).findFirst().orElseThrow();
        assertEquals("Finished title", unreadRow.label());
        assertTrue(screen.getNarrationMessage().getString().contains("Finished title"));
        assertTrue(screen.getNarrationMessage().getString().toLowerCase(java.util.Locale.ROOT).contains("unread"));
        var acknowledge = AssistantScreen.class.getDeclaredMethod("acknowledgeRenderedUnread", boolean.class);
        acknowledge.setAccessible(true);

        acknowledge.invoke(screen, false);
        assertEquals(List.of(), actions.readCalls);
        screen.refresh(new ChatContracts.View(true, actions.view.signIn(), false, actions.view.conversation(),
                actions.view.context(), actions.view.sources(), actions.view.navigationTargets(), List.of(), "partial", "Working"));
        acknowledge.invoke(screen, true);
        assertEquals(List.of(), actions.readCalls);
        screen.refresh(new ChatContracts.View(false, actions.view.signIn(), false, actions.view.conversation(),
                actions.view.context(), actions.view.sources(), actions.view.navigationTargets(), List.of(), "", "Loading session...",
                actions.view.activeWorld(), false, ""));
        acknowledge.invoke(screen, true);
        assertEquals(List.of(), actions.readCalls);
        screen.refresh(actions.view);
        acknowledge.invoke(screen, true);
        acknowledge.invoke(screen, true);
        assertTrue(actions.readCalls.contains("conversation:request"));

        var nextUnread = new ChatContracts.SessionSummary(unread.world(), unread.id(), unread.title(), unread.lastMessage(),
                unread.updatedAt(), false, new ChatContracts.RequestId("next-request"));
        AssistantScreen.class.getMethod("refreshSessions", List.class).invoke(screen, List.of(nextUnread));
        acknowledge.invoke(screen, true);
        assertFalse(actions.readCalls.contains("conversation:next-request"));

        ChatContracts.Turn nextTurn = new ChatContracts.Turn(new ChatContracts.RequestId("next-request"), "next question",
                "next answer", List.of(), actions.view.context(), actions.view.sources(), actions.view.navigationTargets(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1, Instant.EPOCH);
        screen.refresh(new ChatContracts.View(false, actions.view.signIn(), false,
                new ChatContracts.Conversation("conversation", actions.view.conversation().world(), List.of(nextTurn),
                        new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null)),
                actions.view.context(), actions.view.sources(), actions.view.navigationTargets(), List.of(), "", ""));
        acknowledge.invoke(screen, true);
        assertTrue(actions.readCalls.contains("conversation:next-request"));

        actions.sessions = CompletableFuture.completedFuture(List.of(nextUnread));
        ChatContracts.View other = new ChatContracts.View(false, actions.view.signIn(), false,
                new ChatContracts.Conversation("other", actions.view.conversation().world(), List.of(),
                        new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null)),
                actions.view.context(), List.of(), List.of(), List.of(), "", "");
        screen.refresh(other);
        AssistantScreen.class.getMethod("refreshSessions", List.class).invoke(screen, List.of(nextUnread));
        acknowledge.invoke(screen, true);
        assertFalse(actions.readCalls.contains("other:next-request"));
    }

    @Test
    void composerGrowsWithinSmallScreensAndRebuildingPreservesTheDraft() {
        var screen = new AssistantScreen(new FakeActions());
        screen.width = 320;
        screen.height = 240;
        ScreenTestHost.initialize(screen);
        var editor = (MultilineEditor) screen.children().getLast();
        int originalY = editor.getY();
        screen.setInput("wrapped words\n".repeat(30));
        screen.layout();
        assertTrue(editor.getY() < originalY);
        assertTrue(editor.getX() >= 0 && editor.getX() + editor.getWidth() <= 320);
        assertTrue(editor.getHeight() <= 80);
        assertTrue(editor.getY() >= 104);
        String draft = editor.value();
        screen.width = 1280;
        ScreenTestHost.initialize(screen);
        assertEquals(draft, screen.input());
        editor = (MultilineEditor) screen.children().getLast();
        assertTrue(editor.getWidth() <= 720);
        assertTrue(editor.getX() > 224);
    }

    @Test
    void sidebarGroupsCurrentWorldFirstPagesAndScrollsToEverySession() {
        FakeActions actions = new FakeActions();
        actions.settings.setShowChatsFromOtherSessions(true);
        var activeWorld = actions.view.activeWorld();
        var other = new ChatContracts.WorldIdentity("other", "Other");
        List<ChatContracts.SessionSummary> sessions = new ArrayList<>();
        sessions.add(new ChatContracts.SessionSummary(other, "other-chat", "Elsewhere", "Other preview", Instant.EPOCH));
        for (int index = 0; index < 8; index++) sessions.add(new ChatContracts.SessionSummary(activeWorld,
                "chat-" + index, "Question " + index, "Preview", Instant.EPOCH));
        actions.sessions = CompletableFuture.completedFuture(sessions);
        var screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);
        assertEquals(activeWorld, screen.sessionRows().getFirst().world());
        assertEquals(2, screen.sessionRows().stream().filter(row -> row.session() != null).count());
        actions.settings.setSessionsBeforeCollapse(4);
        screen.loadSessions();
        assertEquals(4, screen.sessionRows().stream().filter(row -> row.session() != null).count());
        actions.settings.setSessionsBeforeCollapse(2);
        var expand = screen.sessionRows().stream().filter(AssistantScreen.SessionRow::expand).findFirst().orElseThrow();
        screen.mouseClicked(24, expand.top() + 5, 0);
        assertEquals(9, screen.sessionRows().stream().filter(row -> row.session() != null).count());
        screen.mouseScrolled(50, 200, 0, -100);
        var row = screen.sessionRows().stream().filter(value -> value.session() != null && value.session().id().equals("other-chat")).findFirst().orElseThrow();
        assertTrue(row.top() >= 104 && row.top() < 424);
        screen.mouseClicked(24, row.top() + 5, 0);
        assertEquals("open:other-chat", actions.calls.getLast());
        ((MultilineEditor) screen.children().getFirst()).setValue("Question 7");
        assertEquals(List.of("chat-7"), screen.sessionRows().stream().filter(value -> value.session() != null).map(value -> value.session().id()).toList());
    }

    @Test
    void sidebarDoesNotToggleOtherSessions() {
        FakeActions actions = new FakeActions();
        actions.sessions = CompletableFuture.completedFuture(List.of(
                new ChatContracts.SessionSummary(actions.view.activeWorld(), "here", "Here", "", Instant.EPOCH),
                new ChatContracts.SessionSummary(new ChatContracts.WorldIdentity("elsewhere", "Elsewhere"),
                        "there", "There", "", Instant.EPOCH)));
        var screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);

        assertEquals(List.of("here"), screen.sessionRows().stream().filter(row -> row.session() != null)
                .map(row -> row.session().id()).toList());
        assertFalse(screen.mouseClicked(24, 90, 0));
        assertFalse(actions.settings.showChatsFromOtherSessions());
        assertEquals(1, screen.sessionRows().stream().filter(row -> row.session() != null).count());
    }

    @Test
    void expandedToolBodyUsesOnlyTheRequestedLinesAndCompactJson() {
        var screen = new AssistantScreen(new FakeActions());
        String body = screen.activityBody(new ChatContracts.ActivityStep("tool:one", "Reading", "Completed",
                ChatContracts.ActivityStatus.COMPLETED, "{\n  \"identity\": \"minecraft:torch\", \"filter\": null\n}", "plan_recipe",
                "Coverage: PARTIAL; records: 2\nRoute limit reached", 125L,
                "Route limit reached\nTorch\nitem: minecraft:torch\nand 3 more"), List.of());

        assertEquals("Tool: plan_recipe\nParameter: {\"identity\":\"minecraft:torch\",\"filter\":null}\n"
                + "Result: Route limit reached\nTorch\nitem: minecraft:torch\nand 3 more\nElapsed: 125 ms", body);
        assertTrue(screen.activityBody(new ChatContracts.ActivityStep("tool:two", "Reading", "Completed",
                ChatContracts.ActivityStatus.COMPLETED, null, "search", null, 4_200L), List.of()).endsWith("Elapsed: 4.2 s"));
        assertTrue(screen.activityBody(new ChatContracts.ActivityStep("tool:three", "Reading", "Completed",
                ChatContracts.ActivityStatus.COMPLETED, null, "search", null, 99L), List.of()).endsWith("Elapsed: 99 ms"));
    }

    @Test
    void expandedFailedToolShowsErrorInsteadOfResult() {
        var screen = new AssistantScreen(new FakeActions());
        assertEquals("Tool: lookup\nParameter: {}\nError: Not available (missing)\nElapsed: 6 ms",
                screen.activityBody(new ChatContracts.ActivityStep("tool:failed", "Lookup", "Not available (missing)",
                        ChatContracts.ActivityStatus.FAILED, "{}", "lookup", "Coverage: NONE", 6L, "Not available"), List.of()));
    }

    @Test
    void toolElapsedKeepsSubMillisecondPrecisionAndChangesUnitsAtTheBoundary() {
        var screen = new AssistantScreen(new FakeActions());
        for (var example : java.util.Map.of(0L, "0 µs", 480_000L, "480 µs", 999_999L, "999 µs",
                1_000_000L, "1 ms", 6_000_000L, "6 ms", 999_999_999L, "999 ms",
                1_000_000_000L, "1.0 s", 1_400_000_000L, "1.4 s").entrySet()) {
            var step = new com.google.gson.Gson().fromJson("{\"id\":\"tool:fast\",\"title\":\"Lookup\","
                    + "\"status\":\"COMPLETED\",\"toolName\":\"lookup\",\"serializedArguments\":\"{}\","
                    + "\"durationMillis\":0,\"durationNanos\":" + example.getKey() + "}", ChatContracts.ActivityStep.class);
            assertTrue(screen.activityBody(step, List.of()).endsWith("Elapsed: " + example.getValue()), example.toString());
        }
    }

    @Test
    void submittingClearsTheComposerAndOutsideClicksClearEitherEditorFocus() {
        FakeActions actions = new FakeActions();
        var screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);
        var search = (MultilineEditor) screen.children().getFirst();
        var input = (MultilineEditor) screen.children().getLast();
        screen.mouseClicked(search.getX() + 35, search.getY() + 12, 0);
        assertTrue(search.isFocused());
        var meter = screen.toolbarItems().getLast();
        screen.mouseClicked(meter.x() + 5, meter.y() + 5, 0);
        assertFalse(search.isFocused());
        screen.mouseClicked(input.getX() + 10, input.getY() + 10, 0);
        assertTrue(input.isFocused());
        screen.mouseClicked(300, 100, 0);
        assertFalse(input.isFocused());
        assertEquals(20, input.getHeight());
        screen.setInput("submitted question");
        screen.submit();
        assertEquals("", screen.input());
        assertEquals("submitted question", actions.sentQuestion);
        assertEquals("submitted question", screen.pendingQuestion);
        screen.refresh(actions.view);
        assertEquals("", screen.pendingQuestion);
    }

    @Test
    void pauseUsesTheLiveRequestedStateBeforeAnyScreenRefresh() {
        FakeActions actions = new FakeActions();
        var screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        actions.setPaused(false);
        actions.calls.clear();
        screen.layout();
        assertFalse(screen.isPauseScreen());
        screen.mouseClicked(78, 458, 0);
        assertTrue(screen.isPauseScreen());
        screen.mouseClicked(78, 458, 0);
        assertFalse(screen.isPauseScreen());
        assertEquals(List.of("pause:true", "pause:false"), actions.calls);
    }

    @Test
    void sidebarAdapterSettingsAndPauseHitboxesStayDistinctAtNarrowWidth() {
        FakeActions actions = new FakeActions();
        var screen = new AssistantScreen(actions);
        screen.width = 240;
        screen.height = 240;
        var settingsOpens = new java.util.concurrent.atomic.AtomicInteger();
        screen.setSettingsOpener(settingsOpens::incrementAndGet);
        screen.setRuntimeState(new AssistantScreen.RuntimeState(true, true, false));
        screen.layout();

        screen.mouseClicked(22, 220, 0);
        assertEquals(0, settingsOpens.get());
        assertEquals(List.of(), actions.calls);
        screen.mouseClicked(50, 220, 0);
        assertEquals(1, settingsOpens.get());
        screen.mouseClicked(78, 220, 0);
        assertEquals(List.of("pause:false"), actions.calls);
    }

    @Test
    void gearUsesLeftClickForSettingsAndMiddleClickForAdvancedSettings() {
        var screen = new AssistantScreen(new FakeActions());
        screen.width = 240;
        screen.height = 240;
        screen.setRuntimeState(new AssistantScreen.RuntimeState(true, true, false));
        var settingsOpens = new java.util.concurrent.atomic.AtomicInteger();
        var advancedSettingsOpens = new java.util.concurrent.atomic.AtomicInteger();
        screen.setSettingsOpener(settingsOpens::incrementAndGet);
        screen.setAdvancedSettingsOpener(advancedSettingsOpens::incrementAndGet);
        screen.layout();

        screen.mouseClicked(50, 220, 0);
        assertEquals(1, settingsOpens.get());
        assertEquals(0, advancedSettingsOpens.get());
        screen.mouseClicked(50, 220, 2);
        assertEquals(1, settingsOpens.get());
        assertEquals(1, advancedSettingsOpens.get());
        screen.mouseClicked(50, 220, 1);
        screen.mouseClicked(120, 220, 2);
        assertEquals(1, settingsOpens.get());
        assertEquals(1, advancedSettingsOpens.get());
    }

    @Test
    void recipeAndItemRowsUseTheirRequiredHeights() {
        var model = new dev.packsage.client.recipe.RecipeCardModel(
                dev.packsage.client.recipe.RecipeCardModel.Category.SMELTING, 80, 50, List.of(), "Furnace", List.of());
        var plain = new ScreenText.InlineLine(List.of(new ScreenText.InlineSpan("plain", null, ModernUi.TEXT, false, false)));
        var item = new ScreenText.InlineLine(List.of(new ScreenText.InlineSpan("Glass", null, ModernUi.TEXT, false, false,
                null, null, null, "minecraft:glass")));
        var result = new ScreenText.InlineLine(List.of(new ScreenText.InlineSpan("Glass", null, ModernUi.TEXT, false, false,
                SOURCE, null, model, null)));
        assertEquals(12, AssistantScreen.lineHeight(plain));
        assertEquals(18, AssistantScreen.lineHeight(item));
        assertEquals(18, AssistantScreen.lineHeight(result));
        assertEquals(79, AssistantScreen.lineHeight(new ScreenText.InlineLine(result.spans(), false, model)));
    }

    @Test
    void cardWiderThanBubbleFallsBackToSourceChip() {
        var screen = new AssistantScreen(new FakeActions());
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);
        var model = new dev.packsage.client.recipe.RecipeCardModel(
                dev.packsage.client.recipe.RecipeCardModel.Category.SMELTING, 80, 50, List.of(), "Furnace", List.of());
        var card = new dev.packsage.client.recipe.RecipeCard() {
            @Override public int width() { return 88; }
            @Override public int height() { return 71; }
            @Override public void draw(net.minecraft.client.gui.GuiGraphics graphics, int x, int y, int mouseX, int mouseY) { }
            @Override public java.util.Optional<Hover> hoverAt(int x, int y, double mouseX, double mouseY) { return java.util.Optional.empty(); }
        };
        var line = new ScreenText.InlineLine(List.of(new ScreenText.InlineSpan("Glass", null, ModernUi.TEXT, false, false, SOURCE)), false, model);
        var narrow = screen.messageLayout(List.of(line), 87, java.util.Map.of(model, card), java.util.Map.of());
        assertEquals("Source: Glass", narrow.runs().getFirst().span().text());
        assertNull(narrow.runs().getFirst().card());
        assertEquals(12, narrow.height());
        var wide = screen.messageLayout(List.of(line), 88, java.util.Map.of(model, card), java.util.Map.of());
        assertSame(card, wide.runs().getFirst().card());
        assertEquals(79, wide.height());
    }

    @Test
    void copyTextUsesLabelsAcrossSteeredAnswerSegments() throws Exception {
        var actions = new FakeActions();
        var screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);
        var font = net.minecraft.client.gui.screens.Screen.class.getDeclaredField("font");
        font.setAccessible(true);
        font.set(screen, new ScreenPreparationFont());
        var prior = actions.view.conversation().turns().getFirst();
        var answer = "[Glass](item:minecraft:glass) and [Recipe](native:source)";
        var turn = new ChatContracts.Turn(prior.requestId(), prior.question(), answer, List.of(), prior.context(),
                List.of(new Evidence.Source(SCOPE, "native", "1", "source", 1, Instant.EPOCH,
                        Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of())),
                List.of(), prior.outcome(), 1, null, List.of(),
                List.of(new ChatContracts.SteeringMessage("clarify", answer.indexOf("[Recipe]"))), null, null,
                PackSageSettings.Verbosity.CONCISE);
        var prepare = AssistantScreen.class.getDeclaredMethod("prepareTurn", ChatContracts.Turn.class);
        prepare.setAccessible(true);
        var prepared = prepare.invoke(screen, turn);
        var copy = prepared.getClass().getDeclaredMethod("copy");
        copy.setAccessible(true);
        assertEquals("Glass and Recipe", copy.invoke(prepared));
    }

    @Test
    void legacyTurnLayoutUnchanged() {
        var screen = new AssistantScreen(new FakeActions());
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);
        var lines = ScreenText.inlineLines("alpha beta gamma\n[Glass](item:minecraft:glass)", List.of());
        var layout = screen.messageLayout(lines, 55);
        assertEquals(12, layout.runs().stream().filter(run -> run.span().text().equals("gamma")).findFirst().orElseThrow().y());
        assertTrue(layout.runs().stream().noneMatch(run -> run.card() != null || run.stack() != null));
        assertEquals("[Glass](item:minecraft:glass)", layout.runs().stream().filter(run -> run.y() >= 24)
                .map(run -> run.span().text()).reduce("", String::concat));
    }

    @Test
    void resolvedItemChipOnlyExpandsItsWrappedRow() {
        var screen = new AssistantScreen(new FakeActions());
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);
        var item = new ScreenText.InlineSpan("Glass", null, ModernUi.TEXT, false, false, null, null, null, "minecraft:glass");
        var line = new ScreenText.InlineLine(List.of(new ScreenText.InlineSpan("hello ", null, ModernUi.TEXT, false, false), item,
                new ScreenText.InlineSpan(" world", null, ModernUi.TEXT, false, false)));
        var layout = screen.messageLayout(List.of(line), 55, java.util.Map.of(),
                java.util.Map.of("minecraft:glass", new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GLASS)));
        var chip = layout.runs().stream().filter(run -> run.stack() != null).findFirst().orElseThrow();
        assertEquals(12, chip.y());
        assertEquals(18, chip.height());
        assertEquals(30, layout.runs().stream().filter(run -> run.span().text().equals("world")).findFirst().orElseThrow().y());
    }

    @Test
    void messageWrappingKeepsWordsWholeAndChipsWithinTheirRows() {
        var screen = new AssistantScreen(new FakeActions());
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);
        var words = screen.messageLayout(ScreenText.inlineLines("alpha beta gamma", List.of()), 55);
        assertEquals(0, words.runs().stream().filter(run -> run.span().text().equals("beta")).findFirst().orElseThrow().y());
        assertEquals(12, words.runs().stream().filter(run -> run.span().text().equals("gamma")).findFirst().orElseThrow().y());
        var setting = new Evidence.NavigationTarget(SCOPE, "config", "source", "setting");
        var chips = screen.messageLayout(ScreenText.inlineLines("[config:source] [config:source]", List.of(setting)), 70);
        var targets = chips.runs().stream().filter(run -> run.span().target() != null).toList();
        assertEquals(2, targets.size());
        assertEquals(12, targets.get(1).y() - targets.get(0).y());
        assertTrue(chips.runs().stream().allMatch(run -> run.x() + run.width() <= 70));
        var longToken = screen.messageLayout(ScreenText.inlineLines("abcdefgh", List.of()), 15);
        assertEquals("abcdefgh", longToken.runs().stream().map(run -> run.span().text()).reduce("", String::concat));
        assertEquals(36, longToken.height());
    }

    @Test
    void inlineReferencesDistinguishNavigationFromRecordedSourcesBeforeLayout() {
        var screen = new AssistantScreen(new FakeActions());
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);

        var navigation = screen.messageLayout(List.of(new ScreenText.InlineLine(List.of(
                new ScreenText.InlineSpan("Lantern recipe", TARGET, ModernUi.TEXT, false, false, SOURCE)), false)), 150)
                .runs().getFirst();
        assertEquals("Open Lantern recipe", navigation.span().text());
        assertSame(TARGET, navigation.span().target());
        assertSame(SOURCE, navigation.span().source());
        assertEquals(ModernUi.ACCENT, navigation.span().color());
        assertTrue(navigation.span().bold());

        var source = screen.messageLayout(List.of(new ScreenText.InlineLine(List.of(
                new ScreenText.InlineSpan("Lantern recipe", null, ModernUi.TEXT, false, false, SOURCE)), false)), 150)
                .runs().getFirst();
        assertEquals("Source: Lantern recipe", source.span().text());
        assertNull(source.span().target());
        assertSame(SOURCE, source.span().source());
        assertEquals(ModernUi.TEXT, source.span().color());
        assertFalse(source.span().bold());

        var targetOnly = screen.messageLayout(List.of(new ScreenText.InlineLine(List.of(
                new ScreenText.InlineSpan("Lantern recipe", TARGET, ModernUi.TEXT, false, false)), false)), 150);
        assertEquals("Open Lantern recipe", targetOnly.runs().getFirst().span().text());
        assertSame(TARGET, targetOnly.runs().getFirst().span().target());

        assertEquals("Open Lantern recipe", screen.messageLayout(List.of(new ScreenText.InlineLine(List.of(
                new ScreenText.InlineSpan("Open Lantern recipe", TARGET, ModernUi.TEXT, false, false)), false)), 150)
                .runs().getFirst().span().text());
    }

    @Test
    void inlineReferencePresentationUsesItsFinalLabelForWrappingAndClipping() {
        var screen = new AssistantScreen(new FakeActions());
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);

        var run = screen.messageLayout(List.of(new ScreenText.InlineLine(List.of(
                new ScreenText.InlineSpan("Recipe", TARGET, ModernUi.TEXT, false, false)), false)), 35).runs().getFirst();
        assertEquals("Open", run.span().text());
        assertSame(TARGET, run.span().target());
        assertTrue(run.width() <= 35);

        var world = new Evidence.NavigationTarget(SCOPE, "world", "block|minecraft:overworld|1,64,2",
                "world-block|minecraft:overworld|1,64,2");
        var shown = screen.messageLayout(List.of(new ScreenText.InlineLine(List.of(
                new ScreenText.InlineSpan("in world", world, ModernUi.TEXT, false, false),
                new ScreenText.InlineSpan("highlight", world, ModernUi.TEXT, false, false),
                new ScreenText.InlineSpan("Show chest", world, ModernUi.TEXT, false, false)), false)), 600).runs();
        assertEquals(List.of("Show in world", "Show highlight", "Show chest"),
                shown.stream().map(value -> value.span().text()).toList());

        var plainAndCode = screen.messageLayout(List.of(new ScreenText.InlineLine(List.of(
                new ScreenText.InlineSpan("plain", null, ModernUi.TEXT, false, false),
                new ScreenText.InlineSpan("code", null, ScreenText.CODE_COLOR, false, true)), false)), 100);
        assertEquals(List.of("plain", "code"), plainAndCode.runs().stream().map(value -> value.span().text()).toList());
        assertEquals(List.of(ModernUi.TEXT, ScreenText.CODE_COLOR), plainAndCode.runs().stream()
                .map(value -> value.span().color()).toList());
    }

    @Test
    void ordinaryWebLinksUseOpenControlsAndKeepTheirDestinationThroughWrappingAndClipping() {
        var screen = new AssistantScreen(new FakeActions());
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);

        for (int width : List.of(45, 160, 300)) {
            var layout = screen.messageLayout(ScreenText.inlineLines("[Minecraft Wiki homepage](https://minecraft.wiki/) "
                    + "[Open Wiki](https://minecraft.wiki/)", List.of()), width);
            var links = layout.runs().stream().filter(run -> run.span().externalUrl() != null).toList();
            assertEquals(2, links.size());
            assertTrue(links.stream().allMatch(run -> run.span().externalUrl().equals("https://minecraft.wiki/")
                    && run.span().target() == null && run.span().source() == null && run.span().bold()
                    && run.span().color() == ModernUi.ACCENT && run.x() + run.width() <= width));
            if (width == 300) assertEquals(List.of("Open Minecraft Wiki homepage", "Open Wiki"),
                    links.stream().map(run -> run.span().text()).toList());
        }
    }

    @Test
    void composerToolbarHasEqualCompactWidthsAndWrapsWithinTheMainArea() {
        var screen = new AssistantScreen(new FakeActions());
        screen.width = 1100;
        screen.height = 480;
        screen.layout();
        var wide = screen.toolbarItems();
        assertEquals(5, wide.size());
        assertEquals(1, wide.stream().filter(item -> !item.kind().equals("context")).map(AssistantScreen.ToolbarItem::width).distinct().count());
        assertEquals(wide.getFirst().width() / 2, wide.getLast().width());
        assertEquals(1, wide.stream().filter(item -> !item.kind().equals("context")).map(AssistantScreen.ToolbarItem::y).distinct().count());
        assertTrue(wide.getLast().y() > wide.getFirst().y());
        assertEquals(wide.getFirst().x() + 26, wide.getLast().x());
        assertTrue(wide.stream().allMatch(item -> item.width() <= 100));
        screen.width = 480;
        screen.layout();
        var narrow = screen.toolbarItems();
        assertEquals(1, narrow.stream().filter(item -> !item.kind().equals("context")).map(AssistantScreen.ToolbarItem::width).distinct().count());
        assertTrue(narrow.stream().map(AssistantScreen.ToolbarItem::y).distinct().count() > 1);
        assertTrue(narrow.stream().allMatch(item -> item.x() >= 160 && item.x() + item.width() <= 480));
        assertTrue(narrow.stream().filter(item -> !item.kind().equals("context")).mapToInt(AssistantScreen.ToolbarItem::y).max().orElseThrow() < 424);
        screen.width = 320;
        screen.height = 240;
        ScreenTestHost.initialize(screen);
        screen.setInput("long draft ".repeat(70));
        screen.layout();
        var input = (MultilineEditor) screen.children().getLast();
        assertTrue(screen.toolbarItems().stream().filter(item -> !item.kind().equals("context")).allMatch(item -> item.y() + 20 <= input.getY()));
        assertTrue(screen.toolbarItems().stream().allMatch(item -> item.x() + item.width() <= 320));
        var meter = screen.toolbarItems().getLast();
        assertEquals(meter.x() + meter.width() + 6, input.getX());
        assertEquals(input.getY() + input.getHeight() - 20, meter.y());
    }

    @Test
    void pickerConsumesClicksBeforeTheCommandConfirmationUnderneath() throws Exception {
        var actions = new FakeActions();
        actions.confirmations = List.of(new dev.packsage.world.CommandRunner.Confirmation("command", "request", "give @s diamond"));
        var screen = new AssistantScreen(actions);
        screen.width = 900;
        screen.height = 480;
        ScreenTestHost.initialize(screen);
        var model = screen.toolbarItems().stream().filter(item -> item.kind().equals("model")).findFirst().orElseThrow();
        screen.mouseClicked(model.x() + 5, model.y() + 5, 0);
        var option = screen.pickerRows().stream().filter(row -> row.choice().value().equals("model-two")).findFirst().orElseThrow();
        var panelField = AssistantScreen.class.getDeclaredField("commandConfirmation");
        panelField.setAccessible(true);
        ((CommandConfirmationPanel) panelField.get(screen)).layout(new net.minecraft.client.gui.Font(ignored -> null, false) {
            @Override public List<net.minecraft.util.FormattedCharSequence> split(net.minecraft.network.chat.FormattedText text, int width) {
                return List.of(net.minecraft.util.FormattedCharSequence.forward(text.getString(), net.minecraft.network.chat.Style.EMPTY));
            }
        }, option.x(), option.y() - 48, 180, 40, 480);
        screen.mouseClicked(option.x() + 20, option.y() + 5, 0);
        assertEquals("model-two", actions.settings.modelId());
        assertFalse(actions.calls.contains("command:command"));
    }

    @Test
    void modelAndEffortDropdownsSelectValuesAndOutsideClicksDismissThem() {
        FakeActions actions = new FakeActions();
        var screen = new AssistantScreen(actions);
        screen.width = 900;
        screen.height = 480;
        ScreenTestHost.initialize(screen);
        var model = screen.toolbarItems().stream().filter(item -> item.kind().equals("model")).findFirst().orElseThrow();
        screen.mouseClicked(model.x() + 5, model.y() + 5, 0);
        assertTrue(screen.pickerRows().stream().allMatch(row -> row.width() == model.width() && row.y() + 22 < model.y()));
        var option = screen.pickerRows().stream().filter(row -> row.choice().value().equals("model-two")).findFirst().orElseThrow();
        screen.mouseClicked(option.x() + 5, option.y() + 5, 0);
        assertEquals("model-two", actions.settings.modelId());
        assertTrue(screen.pickerRows().isEmpty());
        var effort = screen.toolbarItems().stream().filter(item -> item.kind().equals("effort")).findFirst().orElseThrow();
        screen.mouseClicked(effort.x() + 5, effort.y() + 5, 0);
        option = screen.pickerRows().stream().filter(row -> row.choice().value().equals("HIGH")).findFirst().orElseThrow();
        screen.mouseClicked(option.x() + 5, option.y() + 5, 0);
        assertEquals(ModelBackend.ReasoningEffort.HIGH, actions.settings.reasoningEffort());
        screen.mouseClicked(model.x() + 5, model.y() + 5, 0);
        screen.mouseClicked(300, 100, 0);
        assertTrue(screen.pickerRows().isEmpty());
    }

    @Test
    void editingLatestResendsWithTheExpectedIdentityAndHonorsActiveGuard() {
        FakeActions actions = new FakeActions();
        var screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);
        assertFalse(screen.editLatest(new ChatContracts.RequestId("older")));
        assertTrue(screen.editLatest(new ChatContracts.RequestId("request")));
        assertEquals("question", screen.input());
        screen.setInput("edited question");
        screen.submit();
        assertEquals(List.of("resend:request"), actions.calls);
        assertEquals("edited question", actions.sentQuestion);
        assertEquals("", screen.input());
        assertFalse(screen.editLatest(new ChatContracts.RequestId("request")));
        screen.refresh(new ChatContracts.View(false, actions.view.signIn(), false, actions.view.conversation(),
                actions.view.context(), actions.view.sources(), actions.view.navigationTargets(), List.of(), "", "",
                actions.view.activeWorld(), true, ""));
        assertFalse(screen.editLatest(new ChatContracts.RequestId("request")));
    }

    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "runtime", 1);
    private static final Evidence.NavigationTarget TARGET = new Evidence.NavigationTarget(SCOPE, "provider", "source", "target");
    private static final Evidence.NavigationTarget TARGET_TWO = new Evidence.NavigationTarget(SCOPE, "provider", "source2", "target2");
    private static final Evidence.NavigationTarget TARGET_THREE = new Evidence.NavigationTarget(SCOPE, "provider", "source3", "target3");
    private static final Evidence.Source SOURCE = new Evidence.Source(SCOPE, "provider", "1", "source", 1,
            Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of());

    private static final class FakeActions implements ChatActions {
        private final PackSageSettings settings = PackSageSettings.defaults();
        private java.util.Map<String, Boolean> availableAdapters = java.util.Map.of();
        private java.util.Map<String, String> adapterDetails = java.util.Map.of();
        private final List<String> calls = new ArrayList<>();
        private List<dev.packsage.world.CommandRunner.Confirmation> confirmations = List.of();
        private final List<String> readCalls = new ArrayList<>();
        private String sentQuestion;
        private boolean steeringAccepted = true;
        private CompletionStage<List<ChatContracts.SessionSummary>> sessions = CompletableFuture.completedFuture(List.of());
        private ChatContracts.View view = new ChatContracts.View(false, ChatContracts.SignInState.SIGNED_OUT,
                true, new ChatContracts.Conversation("conversation", new ChatContracts.WorldIdentity("world", "World"),
                List.of(new ChatContracts.Turn(new ChatContracts.RequestId("request"), "question", "answer", List.of(),
                        new ChatContracts.Context("{}", null, SCOPE), List.of(SOURCE),
                        new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1)),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null)),
                new ChatContracts.Context("{}", null, SCOPE), List.of(SOURCE), List.of(TARGET), List.of());

        int historyPreviews;
        @Override public ChatContracts.HistoryPreview historyPreview() {
            historyPreviews++;
            return ChatActions.super.historyPreview();
        }
        @Override public ChatContracts.HistoryPreview historyPreview(ChatContracts.RequestId replacing) {
            historyPreviews++;
            return new ChatContracts.HistoryPreview(dev.packsage.chat.HistorySelector.select(view.conversation().turns(),
                    new ChatContracts.HistoryLimits(settings.historyTurns(), settings.historicalTokens()), turn -> 1, replacing),
                    settings.historicalTokens(), settings.historicalTokens(), null, settings.historyTurns());
        }
        @Override public ChatContracts.View view() { return view; }
        @Override public PackSageSettings settings() { return settings; }
        @Override public List<dev.packsage.world.CommandRunner.Confirmation> commandConfirmations() { return confirmations; }
        @Override public void confirmCommand(String id, boolean run) { calls.add("command:" + id); }
        @Override public java.util.Map<String, Boolean> adapterAvailability() { return availableAdapters; }
        @Override public java.util.Map<String, String> adapterDetails() { return adapterDetails; }
        @Override public List<ModelBackend.ModelInfo> modelCatalog() { return List.of(
                new ModelBackend.ModelInfo("model-one", "Model one", true, true, 50000, 1000),
                new ModelBackend.ModelInfo("model-two", "Model two", true, true, 100000, 1000)); }
        @Override public void resend(ChatContracts.RequestId latest, String question, boolean screenshot, boolean search) {
            calls.add("resend:" + latest.value()); sentQuestion = question;
        }
        @Override public CompletionStage<List<ChatContracts.SessionSummary>> sessions() { return sessions; }
        @Override public void openSession(ChatContracts.SessionSummary session) { calls.add("open:" + session.id()); }
        @Override public void markRead(ChatContracts.SessionSummary session, ChatContracts.RequestId expectedCompletion) {
            readCalls.add(session.id() + ":" + expectedCompletion.value());
        }
        @Override public CompletionStage<Void> deleteSession(ChatContracts.SessionSummary session) {
            calls.add("delete:" + session.id());
            return CompletableFuture.completedFuture(null);
        }
        @Override public void signIn() { calls.add("signIn"); }
        @Override public void cancelSignIn() { calls.add("cancelSignIn"); }
        @Override public void signOut() { calls.add("signOut"); }
        @Override public void send(String question, boolean includeScreenshot, boolean explicitWebSearch) { calls.add("send"); sentQuestion = question; }
        @Override public boolean steer(String text) { calls.add("steer"); sentQuestion = text; return steeringAccepted; }
        @Override public void stop() { calls.add("stop"); }
        @Override public void newChat() { calls.add("newChat"); }
        @Override public void setPaused(boolean paused) {
            calls.add("pause:" + paused);
            view = new ChatContracts.View(view.active(), view.signIn(), paused, view.conversation(), view.context(), view.sources(),
                    view.navigationTargets(), view.errors(), view.streamingAnswer(), view.activity(), view.activeWorld(), view.readOnly(), view.activeQuestion());
        }
        @Override public void navigate(Evidence.NavigationTarget target) { calls.add("navigate"); }
    }
}
