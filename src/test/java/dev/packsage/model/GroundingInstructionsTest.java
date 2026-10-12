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
    void preservesSelectedModelSearchAndTypedReplayAndAttachesRealImageBytes() {
        var replay = List.<ModelBackend.InputItem>of(new ModelBackend.ToolCall("old", "lookup", "{}"),
                new ModelBackend.ToolResult("old", "{\"count\":7}", false));
        var oldTurn = new ChatContracts.Turn(new ChatContracts.RequestId("old"), "old question", "old answer", replay,
                new ChatContracts.Context("{\"time\":1}", null, SCOPE), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 5);
        var submission = new ChatContracts.Submission(new ChatContracts.RequestId("current"),
                new ChatContracts.WorldIdentity("world", "World"), SCOPE, "current question", true,
                new ChatContracts.HistoryLimits(10, 12_000), new ModelBackend.ModelInfo("catalog-model", null, null, null, null),
                ModelBackend.SearchMode.EXPLICIT_ONLY, 2048);
        var captured = new ChatContracts.CapturedContext(new ChatContracts.Context("{\"dimension\":\"overworld\"}",
                new ChatContracts.Screenshot("current.png", "image/png", 3, Instant.EPOCH), SCOPE), new byte[]{1, 2, 3});

        var request = GroundingInstructions.build(new ChatContracts.PreparedRequest(submission,
                new ChatContracts.HistorySelection(List.of(oldTurn), 5), captured,
                new ChatContracts.EvidenceSelection(List.of(), List.of())));

        assertEquals("catalog-model", request.modelId());
        assertEquals(ModelBackend.SearchMode.EXPLICIT_ONLY, request.searchMode());
        assertEquals("current", request.requestId());
        assertEquals(2048, request.outputBudget());
        assertTrue(request.input().containsAll(replay));
        var current = (ModelBackend.Message) request.input().getLast();
        assertTrue(current.content().stream().anyMatch(content -> content instanceof ModelBackend.ImageContent image
                && image.dataUrl().equals("data:image/png;base64,AQID")));
        assertTrue(current.content().stream().anyMatch(content -> content instanceof ModelBackend.TextContent text
                && text.text().contains("current question") && text.text().contains("overworld")));
        assertEquals(8, request.tools().size());
    }

    @Test
    void explicitlyRejectedImagesAreOmittedAndTextBudgetCountsMultibyteContent() {
        var submission = new ChatContracts.Submission(new ChatContracts.RequestId("current"),
                new ChatContracts.WorldIdentity("world", "World"), SCOPE, "question", true,
                new ChatContracts.HistoryLimits(10, 12_000), new ModelBackend.ModelInfo("catalog-model", false, null, null, null),
                ModelBackend.SearchMode.AUTOMATIC, 2048);
        var captured = new ChatContracts.CapturedContext(new ChatContracts.Context("{}",
                new ChatContracts.Screenshot("current.png", "image/png", 1, Instant.EPOCH), SCOPE), new byte[]{1});
        var request = GroundingInstructions.build(new ChatContracts.PreparedRequest(submission,
                new ChatContracts.HistorySelection(List.of(), 0), captured, new ChatContracts.EvidenceSelection(List.of(), List.of())));
        assertTrue(((ModelBackend.Message) request.input().getLast()).content().stream().noneMatch(ModelBackend.ImageContent.class::isInstance));
        assertTrue(((ModelBackend.Message) request.input().getLast()).content().stream().anyMatch(content -> content instanceof ModelBackend.TextContent text
                && text.text().contains("image") && text.text().contains("omitted")));
        var ascii = new ChatContracts.Turn(new ChatContracts.RequestId("r"), "a", "b", List.of(),
                new ChatContracts.Context("{}", null, SCOPE), List.of(), new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 0);
        var unicode = new ChatContracts.Turn(ascii.requestId(), "龍", "b", List.of(), ascii.context(), ascii.sources(), ascii.outcome(), 0);
        assertTrue(GroundingInstructions.historyTextBudget(unicode) > GroundingInstructions.historyTextBudget(ascii));
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
        assertEquals(GroundingInstructions.historyTextBudget(withSources), request.input().subList(0, request.input().size() - 1).stream()
                .mapToInt(item -> new com.google.gson.GsonBuilder().serializeNulls().create().toJson(item)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum());
    }
}
