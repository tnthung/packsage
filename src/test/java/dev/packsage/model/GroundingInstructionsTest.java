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
    void defaultInstructionsRouteCurrentStateEffectsAndPlans() {
        var text = GroundingInstructions.defaultInstructions();
        assertTrue(text.contains("Use the screenshot and gameplay observation only when relevant."));
        assertTrue(text.contains("read its own record with search_inventory target item"));
        assertTrue(text.contains("shows nothing held or selected, say so first, before answering about a matching carried item"));
        assertTrue(text.contains("openedMenu is not none, read it with target opened"));
        assertTrue(text.contains("say that Minecraft does not record what applied an effect"));
        assertTrue(text.contains("call plan_recipe with that ID and quantity"));
        assertTrue(text.contains("Do not state planner batches as operation counts."));
        assertTrue(text.contains("derive operations and input totals from those semantics and state the grouping you assumed"));
        assertTrue(text.contains("Request independent reads together in one turn"));
        assertTrue(text.contains("By default, include relevant navigation"));
        assertTrue(text.contains("search_recipe reads Minecraft's loaded recipes."));
        var removedSources = java.util.regex.Pattern.compile("\\b(JEI|search_document|read_progress|Ponder|Curios|[Qq]uests?|[Gg]uides?)\\b")
                .matcher(text);
        assertFalse(removedSources.find(), () -> removedSources.group());
    }

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

        assertFalse(request.instructions().isBlank());
        assertEquals("catalog-model", request.modelId());
        assertEquals(ModelBackend.SearchMode.EXPLICIT_ONLY, request.searchMode());
        assertEquals("current", request.requestId());
        assertEquals(2048, request.outputBudget());
        assertEquals(ModelBackend.ReasoningEffort.NONE, request.reasoningEffort());
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
        assertEquals(10, request.tools().size());
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

    @Test
    void historicalSteeringSplitsAssistantTextAndCountsUserTextOnce() {
        var turn = new ChatContracts.Turn(new ChatContracts.RequestId("steered-history"), "question", "ABCD",
                List.of(new ModelBackend.EncryptedReasoning("secret-reasoning", "encrypted", "[]"),
                        new ModelBackend.ToolCall("secret-call", "lookup", "{}"),
                        new ModelBackend.ToolResult("secret-call", "SECRET-RESULT", false)),
                new ChatContracts.Context("SECRET-CONTEXT", null, SCOPE), List.of(), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 0, Instant.EPOCH, List.of(),
                List.of(new ChatContracts.SteeringMessage("first hint", 2),
                        new ChatContracts.SteeringMessage("second hint", 2)));
        var request = GroundingInstructions.build(new ChatContracts.PreparedRequest(new ChatContracts.Submission(
                new ChatContracts.RequestId("current"), new ChatContracts.WorldIdentity("world", "World"), SCOPE,
                "current", false, new ChatContracts.HistoryLimits(10, 12_000),
                new ModelBackend.ModelInfo("model", null, null, null, null), ModelBackend.SearchMode.DISABLED, 512),
                new ChatContracts.HistorySelection(List.of(turn), GroundingInstructions.historyTextBudget(turn)),
                new ChatContracts.CapturedContext(new ChatContracts.Context("{}", null, SCOPE), new byte[0]),
                new ChatContracts.EvidenceSelection(List.of(), List.of())));

        assertEquals(7, request.input().size());
        assertEquals("question", ((ModelBackend.TextContent) ((ModelBackend.Message) request.input().get(0))
                .content().getFirst()).text());
        assertEquals("AB", ((ModelBackend.OutputText) ((ModelBackend.AssistantOutput) request.input().get(1))
                .content().getFirst()).text());
        assertEquals("first hint", ((ModelBackend.TextContent) ((ModelBackend.Message) request.input().get(2))
                .content().getFirst()).text());
        assertEquals("second hint", ((ModelBackend.TextContent) ((ModelBackend.Message) request.input().get(3))
                .content().getFirst()).text());
        assertEquals("CD", ((ModelBackend.OutputText) ((ModelBackend.AssistantOutput) request.input().get(4))
                .content().getFirst()).text());
        assertTrue(request.input().stream().noneMatch(item -> item instanceof ModelBackend.ToolCall
                || item instanceof ModelBackend.ToolResult || item instanceof ModelBackend.EncryptedReasoning));
        assertEquals(GroundingInstructions.historyTextBudget(turn), GroundingInstructions.historyTokenCounts(turn).total());
        assertTrue(GroundingInstructions.historyTokenCounts(turn).user() > 1);
    }
}
