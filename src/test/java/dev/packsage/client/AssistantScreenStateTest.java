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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantScreenStateTest {
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
    void statusLineShowsTheActivityWhileActiveAndIdleOtherwise() {
        FakeActions actions = new FakeActions();
        AssistantScreen screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;

        screen.refresh(active(actions, "", "Reading quest progress (step 2)"));
        assertEquals("Reading quest progress (step 2)", screen.statusLine());
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
    void navigationRequiresTheRegisteredTargetTuple() {
        FakeActions actions = new FakeActions();
        AssistantScreen screen = new AssistantScreen(actions);
        screen.width = 640;
        screen.height = 480;

        screen.navigate(TARGET);

        assertEquals(List.of("navigate"), actions.calls);
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
        for (String phase : List.of("Capturing context...", "Waiting Model", "Thinking...", "Reading quest progress")) {
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
        screen.refresh(active(actions, "", "Thinking..."));
        screen.mouseClicked(deleteX, row.top() + 17, 0);
        screen.mouseClicked(35, row.top() + 22, 0);
        assertEquals(1, actions.calls.stream().filter(call -> call.equals("delete:saved")).count());
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
    void sidebarGroupsCurrentWorldFirstExpandsAndScrollsToEverySession() {
        FakeActions actions = new FakeActions();
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
        assertEquals(3, screen.sessionRows().stream().filter(row -> row.session() != null).count());
        actions.settings.setSessionsBeforeCollapse(4);
        assertEquals(5, screen.sessionRows().stream().filter(row -> row.session() != null).count());
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
    void messageWrappingKeepsWordsWholeAndChipsWithinTheirRows() {
        var screen = new AssistantScreen(new FakeActions());
        screen.width = 640;
        screen.height = 480;
        ScreenTestHost.initialize(screen);
        var words = screen.messageLayout(ScreenText.inlineLines("alpha beta gamma", List.of()), 55);
        assertEquals(0, words.runs().stream().filter(run -> run.span().text().equals("beta")).findFirst().orElseThrow().y());
        assertEquals(12, words.runs().stream().filter(run -> run.span().text().equals("gamma")).findFirst().orElseThrow().y());
        var quest = new Evidence.NavigationTarget(SCOPE, "ftb_quests", "source", "quest");
        var chips = screen.messageLayout(ScreenText.inlineLines("[ftb_quests:source] [ftb_quests:source]", List.of(quest)), 70);
        var targets = chips.runs().stream().filter(run -> run.span().target() != null).toList();
        assertEquals(2, targets.size());
        assertEquals(12, targets.get(1).y() - targets.get(0).y());
        assertTrue(chips.runs().stream().allMatch(run -> run.x() + run.width() <= 70));
        var longToken = screen.messageLayout(ScreenText.inlineLines("abcdefgh", List.of()), 15);
        assertEquals("abcdefgh", longToken.runs().stream().map(run -> run.span().text()).reduce("", String::concat));
        assertEquals(36, longToken.height());
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
        private final List<String> calls = new ArrayList<>();
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

        @Override public ChatContracts.View view() { return view; }
        @Override public PackSageSettings settings() { return settings; }
        @Override public List<ModelBackend.ModelInfo> modelCatalog() { return List.of(
                new ModelBackend.ModelInfo("model-one", "Model one", true, true, 50000, 1000),
                new ModelBackend.ModelInfo("model-two", "Model two", true, true, 100000, 1000)); }
        @Override public void resend(ChatContracts.RequestId latest, String question, boolean screenshot, boolean search) {
            calls.add("resend:" + latest.value()); sentQuestion = question;
        }
        @Override public CompletionStage<List<ChatContracts.SessionSummary>> sessions() { return sessions; }
        @Override public void openSession(ChatContracts.SessionSummary session) { calls.add("open:" + session.id()); }
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
