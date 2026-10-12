package dev.packsage.chat;

import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.function.ToIntFunction;

import dev.packsage.model.ModelBackend;

public final class HistorySelector {
    private HistorySelector() {
    }

    public static ChatContracts.HistorySelection select(List<ChatContracts.Turn> turns,
                                                          ChatContracts.HistoryLimits limits,
                                                          ToIntFunction<ChatContracts.Turn> tokenCounter) {
        List<ChatContracts.Turn> selected = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int tokenCount = 0;
        int completed = 0;
        for (int index = turns.size() - 1; index >= 0 && completed < limits.completedTurns(); index--) {
            ChatContracts.Turn turn = turns.get(index);
            if (turn.outcome().status() != ChatContracts.OutcomeStatus.COMPLETED) continue;
            Set<String> calls = new HashSet<>();
            Set<String> results = new HashSet<>();
            boolean validReplay = true;
            for (ModelBackend.InputItem item : turn.replayItems()) {
                if (item instanceof ModelBackend.ToolCall call && !calls.add(call.callId())) validReplay = false;
                if (item instanceof ModelBackend.ToolResult result
                        && (!calls.contains(result.callId()) || !results.add(result.callId()))) validReplay = false;
            }
            if (!validReplay || !calls.equals(results)) {
                errors.add(0, "excluded incomplete replay for request " + turn.requestId().value());
                continue;
            }
            int tokens = tokenCounter.applyAsInt(turn);
            if (tokens < 0) throw new IllegalArgumentException("token counter returned a negative value");
            if (tokenCount + tokens > limits.historicalTokens()) continue;
            selected.add(0, turn);
            tokenCount += tokens;
            completed++;
        }
        return new ChatContracts.HistorySelection(selected, tokenCount, errors);
    }
}
