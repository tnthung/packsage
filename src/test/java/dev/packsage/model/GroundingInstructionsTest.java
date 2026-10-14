package dev.packsage.model;

import dev.packsage.chat.ChatContracts;
import dev.packsage.knowledge.Evidence;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GroundingInstructionsTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "resources", "team", "runtime", 2);

    @Test
    void visualGroundingDistinguishesMissedTargetsAndThirdPersonViews() {
        var submission = new ChatContracts.Submission(new ChatContracts.RequestId("missed-target"),
                new ChatContracts.WorldIdentity("world", "World"), SCOPE, "check again", false,
                new ChatContracts.HistoryLimits(10, 12_000), new ModelBackend.ModelInfo("model", null, null, null, null),
                ModelBackend.SearchMode.DISABLED, 512);
        var request = GroundingInstructions.build(new ChatContracts.PreparedRequest(submission,
                new ChatContracts.HistorySelection(List.of(), 0),
                new ChatContracts.CapturedContext(new ChatContracts.Context(
                        "{\"focusKind\":\"miss\",\"cameraPerspective\":\"third_person_back\"}", null, SCOPE), new byte[0]),
                new ChatContracts.EvidenceSelection(List.of(), List.of())));

        assertTrue(request.instructions().contains("interaction range"));
        assertTrue(request.instructions().contains("cameraPerspective"));
        assertTrue(request.instructions().contains("visible crosshair"));
        assertTrue(((ModelBackend.Message) request.input().get(request.input().size() - 2)).content().stream().anyMatch(
                content -> content instanceof ModelBackend.TextContent text
                        && text.text().contains("third_person_back") && text.text().contains("\"focusKind\":\"miss\"")));
    }

    @Test
    void keepsCurrentQuestionSeparateFromHistoryAndGameplayAndAttachesRealImageBytes() {
        var replay = List.<ModelBackend.InputItem>of(new ModelBackend.ToolCall("old", "lookup", "{}"),
                new ModelBackend.ToolResult("old", "{\"count\":7}", false));
        var oldTurn = new ChatContracts.Turn(new ChatContracts.RequestId("old"), "How do I enchant my pickaxe?",
                "Use Fortune V on the pickaxe.", replay,
                new ChatContracts.Context("{\"time\":1}", null, SCOPE), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 5);
        var submission = new ChatContracts.Submission(new ChatContracts.RequestId("current"),
                new ChatContracts.WorldIdentity("world", "World"), SCOPE, "hi", true,
                new ChatContracts.HistoryLimits(10, 12_000), new ModelBackend.ModelInfo("catalog-model", null, null, null, null),
                ModelBackend.SearchMode.EXPLICIT_ONLY, 2048, ModelBackend.ReasoningEffort.NONE);
        var gameplay = "{\"dimension\":\"overworld\",\"heldItem\":\"minecraft:iron_pickaxe\","
                + "\"target\":{\"kind\":\"block\",\"id\":\"minecraft:ancient_debris\"},"
                + "\"biome\":\"minecraft:nether_wastes\"}";
        var captured = new ChatContracts.CapturedContext(new ChatContracts.Context(gameplay,
                new ChatContracts.Screenshot("current.png", "image/png", 3, Instant.EPOCH), SCOPE), new byte[]{1, 2, 3});

        var request = GroundingInstructions.build(new ChatContracts.PreparedRequest(submission,
                new ChatContracts.HistorySelection(List.of(oldTurn), 5), captured,
                new ChatContracts.EvidenceSelection(List.of(), List.of())));

        assertEquals("catalog-model", request.modelId());
        assertEquals(ModelBackend.SearchMode.EXPLICIT_ONLY, request.searchMode());
        assertEquals("current", request.requestId());
        assertEquals(2048, request.outputBudget());
        assertEquals(ModelBackend.ReasoningEffort.NONE, request.reasoningEffort());
        assertTrue(request.instructions().contains("navigable: true"));
        assertTrue(request.instructions().contains("outputItem"));
        assertTrue(request.instructions().contains("search_history"));
        assertTrue(request.instructions().contains("read_history"));
        assertTrue(request.instructions().contains("search_document"));
        assertTrue(request.instructions().contains("search_recipe"));
        assertTrue(request.instructions().contains("search_registry"));
        assertTrue(request.instructions().contains("search_inventory"));
        assertFalse(request.instructions().contains("read_storage"));
        assertFalse(request.instructions().contains("read_inventory"));
        assertTrue(request.instructions().contains("historical conversation"));
        assertTrue(request.instructions().contains("The final user message is the only current request."));
        assertTrue(request.instructions().contains("Do not summarize gameplay metadata, continue an earlier topic, or retrieve unrelated evidence."));
        assertTrue(request.instructions().contains("Retrieve pack evidence before asserting recipes"));
        assertTrue(request.instructions().contains("Continue an earlier topic only when the latest request asks for it or depends on it."));
        assertTrue(request.instructions().contains("short, direct reply without unrelated evidence lookup"));
        assertTrue(request.instructions().contains("retrieve fresh evidence before making current pack claims"));
        assertTrue(request.instructions().contains("one case-insensitive literal phrase, not a keyword list"));
        assertTrue(request.instructions().contains("search distinct topics separately"));
        assertTrue(request.instructions().contains("try an empty query with a provider filter when appropriate"));
        assertTrue(request.instructions().contains("omit text unless it is an additional intended filter"));
        assertTrue(request.instructions().contains("unchanged query, filters and limit"));
        assertTrue(request.instructions().contains("Cursors continue searches; references identify records for reads."));
        assertTrue(request.instructions().contains("count each required input slot and its quantity once"));
        assertTrue(request.instructions().contains("vanilla armor coverage does not establish accessory-slot coverage"));
        assertTrue(request.instructions().contains("reread current inventory and recipe evidence"));
        assertTrue(request.instructions().contains("use its returned slot reference as an item target"));
        assertEquals(4, request.input().size());
        assertTrue(request.input().get(0) instanceof ModelBackend.Message);
        assertTrue(request.input().get(1) instanceof ModelBackend.AssistantOutput);
        assertEquals("How do I enchant my pickaxe?", ((ModelBackend.TextContent)
                ((ModelBackend.Message) request.input().get(0)).content().getFirst()).text());
        assertEquals("Use Fortune V on the pickaxe.", ((ModelBackend.OutputText)
                ((ModelBackend.AssistantOutput) request.input().get(1)).content().getFirst()).text());
        var background = (ModelBackend.Message) request.input().get(2);
        assertEquals(ModelBackend.Role.USER, background.role());
        assertEquals(2, background.content().size());
        assertTrue(background.content().stream().anyMatch(content -> content instanceof ModelBackend.TextContent text
                && text.text().equals("Fresh gameplay observation (background context): " + gameplay)));
        assertTrue(background.content().stream().anyMatch(content -> content instanceof ModelBackend.ImageContent image
                && image.dataUrl().equals("data:image/png;base64,AQID")));
        var current = (ModelBackend.Message) request.input().getLast();
        assertEquals(ModelBackend.Role.USER, current.role());
        assertEquals(1, current.content().size());
        assertEquals("hi", ((ModelBackend.TextContent) current.content().getFirst()).text());
        assertEquals(12, request.tools().size());
    }

    @Test
    void explicitReasoningEffortTransfersToModelRequest() {
        var submission = new ChatContracts.Submission(new ChatContracts.RequestId("reasoning"),
                new ChatContracts.WorldIdentity("world", "World"), SCOPE, "question", false,
                new ChatContracts.HistoryLimits(10, 12_000), new ModelBackend.ModelInfo("catalog-model", null, null, null, null),
                ModelBackend.SearchMode.DISABLED, 2048, ModelBackend.ReasoningEffort.HIGH);
        var request = GroundingInstructions.build(new ChatContracts.PreparedRequest(submission,
                new ChatContracts.HistorySelection(List.of(), 0),
                new ChatContracts.CapturedContext(new ChatContracts.Context("{}", null, SCOPE), new byte[0]),
                new ChatContracts.EvidenceSelection(List.of(), List.of())));
        assertEquals(ModelBackend.ReasoningEffort.HIGH, request.reasoningEffort());
    }

    @Test
    void historicalAssistantMetadataUsesLastCompletedFinalOutputAndLegacyFallback() {
        var turn = new ChatContracts.Turn(new ChatContracts.RequestId("legacy-id"), "question", "answer",
                List.of(new ModelBackend.AssistantOutput("final-id", ModelBackend.OutputStatus.COMPLETED,
                                ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText("old", List.of()))),
                        new ModelBackend.AssistantOutput("commentary-id", ModelBackend.OutputStatus.COMPLETED,
                                ModelBackend.OutputPhase.COMMENTARY, List.of(new ModelBackend.OutputText("later", List.of())))),
                new ChatContracts.Context("{}", null, SCOPE), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 0);
        var legacy = new ChatContracts.Turn(new ChatContracts.RequestId("legacy-fallback"), "legacy question", "legacy answer",
                List.of(), turn.context(), List.of(), turn.outcome(), 0);
        assertEquals("final-id", ((ModelBackend.AssistantOutput) GroundingInstructions.build(new ChatContracts.PreparedRequest(
                new ChatContracts.Submission(new ChatContracts.RequestId("request"), new ChatContracts.WorldIdentity("world", "World"), SCOPE,
                        "current", false, new ChatContracts.HistoryLimits(10, 12_000), new ModelBackend.ModelInfo("model", null, null, null, null),
                        ModelBackend.SearchMode.DISABLED, 512), new ChatContracts.HistorySelection(List.of(turn), 1),
                new ChatContracts.CapturedContext(new ChatContracts.Context("{}", null, SCOPE), new byte[0]),
                new ChatContracts.EvidenceSelection(List.of(), List.of()))).input().get(1)).id());
        assertEquals("msg_legacy-fallback", ((ModelBackend.AssistantOutput) GroundingInstructions.build(new ChatContracts.PreparedRequest(
                new ChatContracts.Submission(new ChatContracts.RequestId("request-legacy"), new ChatContracts.WorldIdentity("world", "World"), SCOPE,
                        "current", false, new ChatContracts.HistoryLimits(10, 12_000), new ModelBackend.ModelInfo("model", null, null, null, null),
                        ModelBackend.SearchMode.DISABLED, 512), new ChatContracts.HistorySelection(List.of(legacy), 1),
                new ChatContracts.CapturedContext(new ChatContracts.Context("{}", null, SCOPE), new byte[0]),
                new ChatContracts.EvidenceSelection(List.of(), List.of()))).input().get(1)).id());
    }

    @Test
    void explicitlyRejectedImagesAreOmittedAndTextBudgetCountsMultilingualContent() {
        var submission = new ChatContracts.Submission(new ChatContracts.RequestId("current"),
                new ChatContracts.WorldIdentity("world", "World"), SCOPE, "question", true,
                new ChatContracts.HistoryLimits(10, 12_000), new ModelBackend.ModelInfo("catalog-model", false, null, null, null),
                ModelBackend.SearchMode.AUTOMATIC, 2048);
        var captured = new ChatContracts.CapturedContext(new ChatContracts.Context("{}",
                new ChatContracts.Screenshot("current.png", "image/png", 1, Instant.EPOCH), SCOPE), new byte[]{1});
        var request = GroundingInstructions.build(new ChatContracts.PreparedRequest(submission,
                new ChatContracts.HistorySelection(List.of(), 0), captured, new ChatContracts.EvidenceSelection(List.of(), List.of())));
        assertTrue(((ModelBackend.Message) request.input().get(request.input().size() - 2)).content().stream().noneMatch(ModelBackend.ImageContent.class::isInstance));
        assertTrue(((ModelBackend.Message) request.input().get(request.input().size() - 2)).content().stream().anyMatch(content -> content instanceof ModelBackend.TextContent text
                && text.text().contains("image") && text.text().contains("omitted")));
        var ascii = new ChatContracts.Turn(new ChatContracts.RequestId("r"), "a", "b", List.of(),
                new ChatContracts.Context("{}", null, SCOPE), List.of(), new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 0);
        var unicode = new ChatContracts.Turn(ascii.requestId(), "龍", "b", List.of(), ascii.context(), ascii.sources(), ascii.outcome(), 0);
        assertTrue(GroundingInstructions.historyTextBudget(unicode) > 0);
    }

    @Test
    void historyBudgetCountsOnlyTheReplayedTurnContent() {
        var replay = List.<ModelBackend.InputItem>of(new ModelBackend.ToolCall("call", "search_pack", "{\"text\":\"lantern\"}"),
                new ModelBackend.ToolResult("call", "{\"records\":[1,2,3]}", false));
        var bare = new ChatContracts.Turn(new ChatContracts.RequestId("history"), "question", "answer", replay,
                new ChatContracts.Context("{\"time\":1}", new ChatContracts.Screenshot("old.png", "image/png", 3, Instant.EPOCH), SCOPE),
                List.of(), new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 0);
        var sources = new java.util.ArrayList<Evidence.Source>();
        for (int index = 0; index < 50; index++) {
            sources.add(new Evidence.Source(SCOPE, "native", "1", "recipe:" + index, 2, Instant.EPOCH,
                    Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of("snippet " + index)));
        }
        var withSources = new ChatContracts.Turn(bare.requestId(), bare.question(), bare.answer(), bare.replayItems(),
                bare.context(), sources, bare.outcome(), 0);

        assertEquals(GroundingInstructions.historyTextBudget(bare), GroundingInstructions.historyTextBudget(withSources));
        var request = GroundingInstructions.build(new ChatContracts.PreparedRequest(new ChatContracts.Submission(
                new ChatContracts.RequestId("current"), new ChatContracts.WorldIdentity("world", "World"), SCOPE, "current", false,
                new ChatContracts.HistoryLimits(10, 12_000), new ModelBackend.ModelInfo("catalog-model", null, null, null, null),
                ModelBackend.SearchMode.DISABLED, 2048), new ChatContracts.HistorySelection(List.of(withSources), 0),
                new ChatContracts.CapturedContext(new ChatContracts.Context("{}", null, SCOPE), new byte[0]),
                new ChatContracts.EvidenceSelection(List.of(), List.of())));
        assertEquals(GroundingInstructions.historyTextBudget(withSources), GroundingInstructions.historyTokenCounts(withSources).total());
    }

    @Test
    void historyTokenCountsUseOrdinaryTextForQuestionAndAnswer() {
        assertEquals(3, GroundingInstructions.historyTextBudget(new ChatContracts.Turn(new ChatContracts.RequestId("ui-check"),
                "UI_CHECK_B", "", List.of(), new ChatContracts.Context("", null, SCOPE), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 0)));
        assertEquals(2, GroundingInstructions.historyTextBudget(new ChatContracts.Turn(new ChatContracts.RequestId("hello-world"),
                "hello world", "", List.of(), new ChatContracts.Context("", null, SCOPE), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 0)));
        assertEquals(4, GroundingInstructions.historyTextBudget(new ChatContracts.Turn(new ChatContracts.RequestId("hello-punctuation"),
                "Hello, world!", "", List.of(), new ChatContracts.Context("", null, SCOPE), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 0)));
        assertEquals(0, GroundingInstructions.historyTextBudget(new ChatContracts.Turn(new ChatContracts.RequestId("empty"),
                "", "", List.of(), new ChatContracts.Context("", null, SCOPE), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 0)));
        ChatContracts.Turn turn = new ChatContracts.Turn(new ChatContracts.RequestId("counted"),
                "hello world", "Hello, world!", List.of(), new ChatContracts.Context("context", null, SCOPE), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1);

        ChatContracts.TokenCounts counts = GroundingInstructions.historyTokenCounts(turn);

        assertEquals(2, counts.user());
        assertEquals(4, counts.assistant());
        assertEquals(GroundingInstructions.historyTextBudget(turn), counts.total());
    }

    @Test
    void largeHistoricalToolPayloadsReplayOnlyQuestionAndAnswer() {
        var replay = List.<ModelBackend.InputItem>of(new ModelBackend.ToolCall("old", "search_pack", "{}"),
                new ModelBackend.ToolResult("old", "x".repeat(40_000), false));
        var turn = new ChatContracts.Turn(new ChatContracts.RequestId("history-large"), "old question", "old answer", replay,
                new ChatContracts.Context("{\"old\":true}", null, SCOPE), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 0);
        var submission = new ChatContracts.Submission(new ChatContracts.RequestId("current-large"),
                new ChatContracts.WorldIdentity("world", "World"), SCOPE, "current question", false,
                new ChatContracts.HistoryLimits(10, 12_000), new ModelBackend.ModelInfo("catalog-model", null, null, null, null),
                ModelBackend.SearchMode.DISABLED, 2048);
        var request = GroundingInstructions.build(new ChatContracts.PreparedRequest(submission,
                new ChatContracts.HistorySelection(List.of(turn), GroundingInstructions.historyTextBudget(turn)),
                new ChatContracts.CapturedContext(new ChatContracts.Context("{}", null, SCOPE), new byte[0]),
                new ChatContracts.EvidenceSelection(List.of(), List.of())));

        assertEquals(4, request.input().size());
        assertTrue(request.input().get(0) instanceof ModelBackend.Message);
        assertTrue(request.input().get(1) instanceof ModelBackend.AssistantOutput);
        assertTrue(((ModelBackend.Message) request.input().get(0)).content().getFirst() instanceof ModelBackend.TextContent);
        assertTrue(((ModelBackend.AssistantOutput) request.input().get(1)).content().getFirst() instanceof ModelBackend.OutputText);
        assertTrue(request.input().stream().noneMatch(ModelBackend.ToolResult.class::isInstance));
        assertTrue(GroundingInstructions.historyTextBudget(turn) < 12_000);
    }
}
