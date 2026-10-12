package dev.packsage.chat;

import java.util.List;
import java.util.ArrayList;
import java.util.function.ToIntFunction;

public final class HistorySelector {
    private HistorySelector() {
    }

    public static ChatContracts.HistorySelection select(List<ChatContracts.Turn> turns,
                                                          ChatContracts.HistoryLimits limits,
                                                          ToIntFunction<ChatContracts.Turn> tokenCounter) {
        List<ChatContracts.Turn> selected = new ArrayList<>();
        int tokenCount = 0;
        int completed = 0;
        for (int index = turns.size() - 1; index >= 0 && completed < limits.completedTurns(); index--) {
            ChatContracts.Turn turn = turns.get(index);
            if (turn.outcome().status() != ChatContracts.OutcomeStatus.COMPLETED) continue;
            int tokens = tokenCounter.applyAsInt(turn);
            if (tokens < 0) throw new IllegalArgumentException("token counter returned a negative value");
            // Drop the oldest whole turns: once a turn no longer fits, no older turn is replayed either.
            if (tokenCount + tokens > limits.historicalTokens()) break;
            selected.add(0, turn);
            tokenCount += tokens;
            completed++;
        }
        return new ChatContracts.HistorySelection(selected, tokenCount);
    }
}
