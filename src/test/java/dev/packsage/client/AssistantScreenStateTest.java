package dev.packsage.client;

import dev.packsage.chat.ChatActions;
import dev.packsage.chat.ChatContracts;
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
            screen.mouseClicked(editor.getX() + editor.getWidth() + 12, editor.getY() + editor.getHeight() - 20, 0);
        }
        assertEquals(List.of("stop", "stop", "stop", "stop"), actions.calls);
        screen.refresh(actions.view);
        var editor = (MultilineEditor) screen.children().getLast();
        screen.mouseClicked(editor.getX() + editor.getWidth() + 12, editor.getY() + editor.getHeight() - 20, 0);
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
        int x = editor.getX() + editor.getWidth() + 12;
        int y = editor.getY() + editor.getHeight() - 20;
        screen.mouseClicked(x, y, 0);
        screen.mouseClicked(x, y, 0);
        assertEquals(List.of("send", "stop"), actions.calls);
        screen.refresh(actions.view);
        screen.mouseClicked(x, y, 0);
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

    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "runtime", 1);
    private static final Evidence.NavigationTarget TARGET = new Evidence.NavigationTarget(SCOPE, "provider", "source", "target");
    private static final Evidence.NavigationTarget TARGET_TWO = new Evidence.NavigationTarget(SCOPE, "provider", "source2", "target2");
    private static final Evidence.NavigationTarget TARGET_THREE = new Evidence.NavigationTarget(SCOPE, "provider", "source3", "target3");
    private static final Evidence.Source SOURCE = new Evidence.Source(SCOPE, "provider", "1", "source", 1,
            Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of());

    private static final class FakeActions implements ChatActions {
        private final List<String> calls = new ArrayList<>();
        private CompletionStage<List<ChatContracts.SessionSummary>> sessions = CompletableFuture.completedFuture(List.of());
        private final ChatContracts.View view = new ChatContracts.View(false, ChatContracts.SignInState.SIGNED_OUT,
                true, new ChatContracts.Conversation("conversation", new ChatContracts.WorldIdentity("world", "World"),
                List.of(new ChatContracts.Turn(new ChatContracts.RequestId("request"), "question", "answer", List.of(),
                        new ChatContracts.Context("{}", null, SCOPE), List.of(SOURCE),
                        new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1)),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null)),
                new ChatContracts.Context("{}", null, SCOPE), List.of(SOURCE), List.of(TARGET), List.of());

        @Override public ChatContracts.View view() { return view; }
        @Override public CompletionStage<List<ChatContracts.SessionSummary>> sessions() { return sessions; }
        @Override public void openSession(ChatContracts.SessionSummary session) { calls.add("open:" + session.id()); }
        @Override public void signIn() { calls.add("signIn"); }
        @Override public void cancelSignIn() { calls.add("cancelSignIn"); }
        @Override public void signOut() { calls.add("signOut"); }
        @Override public void send(String question, boolean includeScreenshot, boolean explicitWebSearch) { calls.add("send"); }
        @Override public void stop() { calls.add("stop"); }
        @Override public void newChat() { calls.add("newChat"); }
        @Override public void setPaused(boolean paused) { calls.add("pause:" + paused); }
        @Override public void navigate(Evidence.NavigationTarget target) { calls.add("navigate"); }
    }
}
