package dev.packsage.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.packsage.chat.ChatContracts;
import dev.packsage.knowledge.Evidence;
import dev.packsage.model.ModelBackend;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceDetailsScreenTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "resources", "player", "runtime", 1);
    private static final Evidence.Source SOURCE = new Evidence.Source(SCOPE, "config", "1", "common#general.range", 1,
            Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.PARTIAL, Evidence.Authority.LOADED,
            List.of("config:fixture", "view:saved-and-loaded"));

    @Test
    void recordedValuesRequireTheExactSourceScopeAndObservation() {
        JsonObject source = new JsonObject();
        source.addProperty("providerId", SOURCE.providerId());
        source.addProperty("sourceHandle", SOURCE.sourceHandle());
        source.addProperty("observedAt", SOURCE.observedAt().toString());
        source.addProperty("observedGeneration", SOURCE.observedGeneration());
        source.add("scope", new Gson().toJsonTree(SCOPE));
        JsonObject payload = new JsonObject();
        payload.addProperty("fileName", "fixture-common.toml");
        payload.addProperty("key", "general.range");
        payload.addProperty("savedValue", 24);
        payload.addProperty("savedState", "observed");
        payload.addProperty("loadedValue", 25);
        payload.addProperty("loadedState", "observed");
        JsonObject record = new JsonObject();
        record.add("source", source);
        record.add("payload", payload);
        JsonArray records = new JsonArray();
        records.add(record);
        JsonObject result = new JsonObject();
        result.add("records", records);
        var turn = new ChatContracts.Turn(new ChatContracts.RequestId("request"), "question", "answer",
                List.of(new ModelBackend.ToolResult("call", result.toString(), false)), new ChatContracts.Context("{}", null, SCOPE),
                List.of(SOURCE), new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1);
        var details = SourceDetailsScreen.details(SOURCE, turn);
        assertTrue(details.contains("File: fixture-common.toml"));
        assertTrue(details.contains("Saved value: 24"));
        assertTrue(details.contains("Loaded value: 25"));

        source.add("scope", new Gson().toJsonTree(new Evidence.Scope("other", "resources", "player", "runtime", 1)));
        var unrelated = new ChatContracts.Turn(turn.requestId(), turn.question(), turn.answer(),
                List.of(new ModelBackend.ToolResult("call", result.toString(), false)), turn.context(), turn.sources(), turn.outcome(), 1);
        assertTrue(SourceDetailsScreen.details(SOURCE, unrelated).contains("Value snapshot unavailable in this session."));
        assertThrows(IllegalArgumentException.class, () -> SourceDetailsScreen.details(
                new Evidence.Source(SCOPE, "config", "1", "unregistered", 1, Instant.EPOCH, Evidence.Activity.ACTIVE,
                        Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()), turn));
    }

    @Test
    void missingReplayKeepsMetadataAndBackPreservesTheLivePausePolicy() {
        var turn = new ChatContracts.Turn(new ChatContracts.RequestId("request"), "question", "answer", List.of(),
                new ChatContracts.Context("{}", null, SCOPE), List.of(SOURCE),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1);
        AtomicBoolean pause = new AtomicBoolean(true);
        AtomicInteger returned = new AtomicInteger();
        var screen = new SourceDetailsScreen(SOURCE, turn, returned::incrementAndGet, pause::get);
        assertTrue(screen.isPauseScreen());
        pause.set(false);
        assertFalse(screen.isPauseScreen());
        assertTrue(SourceDetailsScreen.details(SOURCE, turn).contains("Source: common#general.range"));
        screen.onClose();
        assertEquals(1, returned.get());
    }
}
