package dev.packsage.chat;

import dev.packsage.knowledge.Evidence;
import dev.packsage.model.ModelBackend;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatContractsTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world-a", "en_us", "team-a", "provider-1", 2);

    @Test
    void capturedContextOwnsScreenshotBytesAndRequiresMatchingMetadata() {
        byte[] bytes = {1, 2, 3};
        ChatContracts.CapturedContext captured = new ChatContracts.CapturedContext(new ChatContracts.Context("fresh",
                new ChatContracts.Screenshot("request-1", "image/png", 3, Instant.EPOCH), SCOPE), bytes);

        bytes[0] = 9;
        byte[] returned = captured.screenshotBytes();
        returned[1] = 9;

        assertArrayEquals(new byte[]{1, 2, 3}, captured.screenshotBytes());
        assertThrows(IllegalArgumentException.class, () -> new ChatContracts.CapturedContext(new ChatContracts.Context("fresh",
                new ChatContracts.Screenshot("request-1", "image/png", 2, Instant.EPOCH), SCOPE), new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> new ChatContracts.CapturedContext(
                new ChatContracts.Context("fresh", null, SCOPE), new byte[]{1}));
    }

    @Test
    void typedReplayRetainsReasoningIdentitySummaryAndToolPair() {
        List<ModelBackend.InputItem> replay = List.of(
                new ModelBackend.EncryptedReasoning("reasoning-1", "encrypted", "[]"),
                new ModelBackend.ToolCall("call-1", "lookup", "{}"),
                new ModelBackend.ToolResult("call-1", "{\"ok\":true}", false),
                new ModelBackend.WebSearchCall("search-1", ModelBackend.WebSearchStatus.COMPLETED,
                        new ModelBackend.SearchAction("packsage", List.of("packsage"),
                                List.of(new ModelBackend.WebSearchSource("https://example.test")))),
                new ModelBackend.AssistantOutput("message-1", ModelBackend.OutputStatus.COMPLETED,
                        ModelBackend.OutputPhase.FINAL_ANSWER, List.of(new ModelBackend.OutputText("answer",
                        List.of(new ModelBackend.UrlCitation(0, 6, "Example", "https://example.test"))))));
        ChatContracts.Turn turn = new ChatContracts.Turn(new ChatContracts.RequestId("request-1"), "question", "answer",
                replay, new ChatContracts.Context("fresh", null, SCOPE), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 3);

        assertEquals(replay, turn.replayItems());
    }

    @Test
    void preparedRequestRejectsCaptureOrEvidenceFromAnotherScope() {
        ChatContracts.Submission submission = new ChatContracts.Submission(new ChatContracts.RequestId("request-1"),
                new ChatContracts.WorldIdentity("stable-world", "World"), SCOPE, "question", false,
                new ChatContracts.HistoryLimits(10, 12_000),
                new ModelBackend.ModelInfo("model", "Model", true, true, 128_000, 4_096),
                ModelBackend.SearchMode.AUTOMATIC, 4_096);
        Evidence.Scope otherScope = new Evidence.Scope("world-b", "en_us", "team-a", "provider-1", 2);

        assertThrows(IllegalArgumentException.class, () -> new ChatContracts.PreparedRequest(submission,
                new ChatContracts.HistorySelection(List.of(), 0),
                new ChatContracts.CapturedContext(new ChatContracts.Context("fresh", null, otherScope), new byte[0]),
                new ChatContracts.EvidenceSelection(List.of(), List.of())));
        assertThrows(IllegalArgumentException.class, () -> new ChatContracts.PreparedRequest(submission,
                new ChatContracts.HistorySelection(List.of(), 0),
                new ChatContracts.CapturedContext(new ChatContracts.Context("fresh",
                        new ChatContracts.Screenshot("request-1", "image/png", 1, Instant.EPOCH), SCOPE), new byte[]{1}),
                new ChatContracts.EvidenceSelection(List.of(), List.of())));
    }

    @Test
    void modelCatalogSelectionAndProviderOptionalFieldsRemainConsistent() {
        ModelBackend.ModelInfo selected = new ModelBackend.ModelInfo("model", "Model", true, false, 128_000, 4_096);

        assertThrows(IllegalArgumentException.class, () -> new ModelBackend.Capabilities(List.of(selected), "missing",
                true, false, 128_000, 4_096));
        assertThrows(IllegalArgumentException.class, () -> new ModelBackend.Capabilities(List.of(selected), "model",
                false, false, 128_000, 4_096));
        assertEquals("packsage", new ModelBackend.ToolCall("call-1", "packsage", "lookup", "{}").namespace());
        assertEquals(null, new ModelBackend.ToolCall("call-1", "lookup", "{}").namespace());
        assertEquals(List.of(), new ModelBackend.SearchAction(null, List.of(), List.of()).queries());
        assertEquals(null, new ModelBackend.Capabilities(List.of(selected), null,
                null, null, null, null).selectedModelId());
    }
}
