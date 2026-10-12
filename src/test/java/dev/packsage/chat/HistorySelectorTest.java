package dev.packsage.chat;

import dev.packsage.knowledge.Evidence;
import dev.packsage.model.ModelBackend;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistorySelectorTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "provider", 1);

    @Test
    void selectsLatestTenCompletedTurnsAndKeepsWholeToolExchanges() {
        List<ChatContracts.Turn> turns = new ArrayList<>();
        for (int index = 0; index < 11; index++) turns.add(turn(index, 1));

        ChatContracts.HistorySelection selection = HistorySelector.select(turns,
                new ChatContracts.HistoryLimits(10, 12_000), ignored -> 1);

        assertEquals(10, selection.turns().size());
        assertEquals("request-1", selection.turns().get(0).requestId().value());
        assertEquals(List.of(new ModelBackend.ToolCall("call-1", "lookup", "{}"),
                new ModelBackend.ToolResult("call-1", "{}", false)), selection.turns().get(0).replayItems());
    }

    @Test
    void dropsOversizedTurnsWholeAndUsesVerifiedCounter() {
        List<ChatContracts.Turn> turns = List.of(turn(1, 20), turn(2, 1), turn(3, 1));

        ChatContracts.HistorySelection selection = HistorySelector.select(turns,
                new ChatContracts.HistoryLimits(10, 8), turn -> turn.tokenCount() * 3);

        assertEquals(List.of("request-2", "request-3"), selection.turns().stream()
                .map(turn -> turn.requestId().value()).toList());
        assertEquals(6, selection.tokenCount());
    }

    @Test
    void activeExchangeAndFreshContextAreSeparateFromHistoricalSelection() {
        ChatContracts.HistorySelection selection = HistorySelector.select(List.of(turn(1, 3)),
                new ChatContracts.HistoryLimits(10, 12_000), turn -> 100);

        assertEquals(100, selection.tokenCount());
        assertTrue(selection.turns().get(0).context().serializedGameplayContext().contains("fresh-at-turn-1"));
    }

    @Test
    void excludesIncompleteToolReplayAndReportsRecovery() {
        ChatContracts.Turn incomplete = new ChatContracts.Turn(new ChatContracts.RequestId("request-incomplete"),
                "question", "answer", List.of(new ModelBackend.ToolCall("call-1", "lookup", "{}")),
                new ChatContracts.Context("fresh", null, SCOPE), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1);

        ChatContracts.HistorySelection selection = HistorySelector.select(List.of(incomplete),
                new ChatContracts.HistoryLimits(10, 12_000), ignored -> 1);

        assertTrue(selection.turns().isEmpty());
        assertEquals(List.of("excluded incomplete replay for request request-incomplete"), selection.errors());
    }

    private static ChatContracts.Turn turn(int index, int tokenCount) {
        ChatContracts.Context context = new ChatContracts.Context("fresh-at-turn-" + index, null, SCOPE);
        return new ChatContracts.Turn(new ChatContracts.RequestId("request-" + index), "question-" + index,
                "answer-" + index, List.of(new ModelBackend.ToolCall("call-" + index, "lookup", "{}"),
                        new ModelBackend.ToolResult("call-" + index, "{}", false)), context, List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), tokenCount);
    }
}
