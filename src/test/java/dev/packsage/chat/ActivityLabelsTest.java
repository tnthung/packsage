package dev.packsage.chat;

import dev.packsage.model.ModelBackend;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActivityLabelsTest {
    @Test
    void noteWritesUseOnlyTheGenericThinkingLabel() {
        for (var name : List.of("remember", "update_note", "forget_note")) {
            assertEquals(ActivityLabels.THINKING, ActivityLabels.tool(new ModelBackend.ToolCall("note", name, "{}"), 2));
        }
    }
}
