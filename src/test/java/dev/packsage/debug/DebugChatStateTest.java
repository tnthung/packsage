package dev.packsage.debug;

import com.google.gson.JsonArray;
import dev.packsage.chat.ChatContracts;
import dev.packsage.knowledge.Evidence;
import dev.packsage.model.ModelBackend;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DebugChatStateTest {
    @Test
    void completedRepliesExposeOnlyTheirOwnTargetsAcrossTwoTurnsWithTheSameScope() {
        var scope = new Evidence.Scope("world", "resources", "player", "runtime", 1);
        var world = new ChatContracts.WorldIdentity("world", "World");
        var first = new Evidence.NavigationTarget(scope, "fixture", "first", "same-target");
        var second = new Evidence.NavigationTarget(scope, "fixture", "second", "same-target");
        var turns = List.of(first, second).stream().map(target -> new ChatContracts.Turn(
                new ChatContracts.RequestId(target.sourceHandle()), "question", "answer", List.of(),
                new ChatContracts.Context("{}", null, scope), List.of(), List.of(target),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1, Instant.EPOCH)).toList();
        var conversation = new ChatContracts.Conversation("conversation", world, turns, turns.getLast().outcome());
        var view = new ChatContracts.View(false, ChatContracts.SignInState.SIGNED_IN, false, conversation,
                turns.getLast().context(), List.of(), List.of(first, second), List.of(), "", "", world, false);

        for (var target : List.of(first, second)) {
            var result = DebugChatState.read(view, target.sourceHandle(), new JsonArray(), conversation);
            assertEquals(1, result.getAsJsonArray("navigationTargets").size());
            assertEquals(target.sourceHandle(), result.getAsJsonArray("navigationTargets").get(0).getAsJsonObject()
                    .get("sourceHandle").getAsString());
        }
        var unavailable = new ChatContracts.View(false, view.signIn(), false, conversation, view.context(),
                List.of(), List.of(), List.of(), "", "", world, false);
        assertEquals(1, DebugChatState.read(unavailable, "first", new JsonArray(), conversation)
                .getAsJsonArray("navigationTargets").size(), "inspection keeps the reply's historical target");
    }

    @Test
    void exposesSavedToolsEvidenceNavigationCitationsAndTiming() {
        var scope = new Evidence.Scope("world", "resources", "player", "runtime", 1);
        var source = new Evidence.Source(scope, "fixture", "v1", "source", 1, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.EXTERNAL, List.of());
        var target = new Evidence.NavigationTarget(scope, "fixture", "source", "target");
        var turn = new ChatContracts.Turn(new ChatContracts.RequestId("request"), "question", "answer", List.of(
                new ModelBackend.ToolCall("call", "read", "{\"id\":\"record\"}"),
                new ModelBackend.ToolResult("call", "{\"coverage\":\"partial\",\"detail\":\"more pages\",\"records\":[{},{}]}", false)),
                new ChatContracts.Context("{}", null, scope), List.of(source), List.of(target),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 10, Instant.EPOCH);
        var world = new ChatContracts.WorldIdentity("world", "World");
        var view = new ChatContracts.View(false, ChatContracts.SignInState.SIGNED_IN, false,
                new ChatContracts.Conversation("conversation", world, List.of(turn), turn.outcome()), turn.context(),
                turn.sources(), turn.navigationTargets(), List.of(), "", "", world, false);
        var timing = new JsonArray();
        timing.add("timing");
        var result = DebugChatState.read(view, "request", timing, view.conversation());
        assertEquals("completed", result.get("status").getAsString());
        assertEquals("answer", result.get("reply").getAsString());
        var tool = result.getAsJsonArray("toolCalls").get(0).getAsJsonObject();
        assertEquals("record", tool.getAsJsonObject("arguments").get("id").getAsString());
        assertEquals("partial", tool.get("coverage").getAsString());
        assertEquals("more pages", tool.get("detail").getAsString());
        assertEquals(2, tool.get("recordCount").getAsInt());
        assertEquals("target", result.getAsJsonArray("navigationTargets").get(0).getAsJsonObject().get("targetId").getAsString());
        assertEquals(1, result.getAsJsonArray("citations").size());
        assertEquals(timing, result.get("timing"));
        assertThrows(IllegalArgumentException.class, () -> DebugChatState.read(view, "missing", timing, view.conversation()));
    }

    @Test
    void failedToolArgumentsRemainInspectableEvenWhenTheyAreNotJson() {
        var scope = new Evidence.Scope("world", "resources", "player", "runtime", 1);
        var world = new ChatContracts.WorldIdentity("world", "World");
        var turn = new ChatContracts.Turn(new ChatContracts.RequestId("failed"), "question", "", List.of(
                new ModelBackend.ToolCall("call", "read", "{"), new ModelBackend.ToolResult("call", "unavailable", true)),
                new ChatContracts.Context("{}", null, scope), List.of(), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.FAILED, "failure"), 0, Instant.EPOCH);
        var view = new ChatContracts.View(false, ChatContracts.SignInState.SIGNED_IN, false,
                new ChatContracts.Conversation("conversation", world, List.of(turn), turn.outcome()), turn.context(),
                List.of(), List.of(), List.of(), "", "", world, false);
        var result = DebugChatState.read(view, "failed", new JsonArray(), view.conversation());
        assertEquals("failed", result.get("status").getAsString());
        var tool = result.getAsJsonArray("toolCalls").get(0).getAsJsonObject();
        assertEquals("{", tool.get("arguments").getAsString());
        assertTrue(tool.get("error").getAsBoolean());
    }
}
