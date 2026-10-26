package dev.packsage.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.packsage.knowledge.Evidence;
import dev.packsage.model.ModelBackend;
import dev.packsage.model.PackSageTools;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.*;

class GameStateReaderTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "resources", "team", "runtime", 1);
    private static final JsonObject SNAPSHOT = JsonParser.parseString("""
            {
              "performance": {"fps": 60, "frameTimeMs": 16.6, "integratedServerTps": 20.0, "integratedServerMspt": 8.5, "renderDistance": 12, "simulationDistance": 10, "loadedChunks": 441, "renderedChunkSections": 180, "totalChunkSections": 676, "memory": {"usedBytes": 1000, "maxBytes": 4000, "allocatedBytes": 2000}},
              "entities_and_chunks": {"renderedEntities": 3, "totalEntities": 7, "playerChunk": {"x": 2, "z": -4}, "chunkSection": {"x": 2, "y": 4, "z": -4}, "region": {"localX": 2, "localZ": 28, "x": 0, "z": -1, "file": "r.0.-1.mca"}},
              "light_and_environment": {"light": {"block": 3, "sky": 15, "combined": 15}, "biome": "minecraft:plains", "localDifficulty": {"effective": 1.0, "specialMultiplier": 0.0}, "gameTime": 24000, "dayTime": 24000, "day": 1, "moonPhase": 1},
              "position": {"xyz": {"x": 32.5, "y": 68.0, "z": -63.25}, "block": {"x": 32, "y": 68, "z": -64}, "facing": {"direction": "SOUTH", "yaw": 0.0, "pitch": 0.0}, "dimension": "minecraft:overworld"},
              "looking_at": {"block": {"status": "none"}, "fluid": {"status": "none"}, "entity": {"status": "none"}}
            }
            """).getAsJsonObject();

    @Test
    void encodesOneStructuredF3RecordThroughTheSharedToolEncoder() {
        var capture = new FakeCapture(new GameStateReader.Snapshot(false, SNAPSHOT));
        var reader = new GameStateReader(capture);
        var tools = new PackSageTools(request -> reader.handle(request, null));
        var response = tools.execute(new ModelBackend.ToolCall("f3", "read_game_state", "{}"), SCOPE).toCompletableFuture().join();

        assertFalse(response.result().error());
        assertEquals(1, response.records().size());
        var record = response.records().getFirst();
        assertEquals("game_state", record.source().providerId());
        assertEquals(Evidence.RecordKind.OTHER, record.kind());
        assertEquals(Set.of("performance", "entities_and_chunks", "light_and_environment", "position", "looking_at"), record.payload().values().keySet());
        var encoded = JsonParser.parseString(response.result().serializedResult()).getAsJsonObject().getAsJsonArray("records").get(0).getAsJsonObject();
        assertEquals("OTHER", encoded.get("kind").getAsString());
        assertEquals(60, encoded.getAsJsonObject("payload").getAsJsonObject("performance").get("fps").getAsInt());
        assertEquals("minecraft:overworld", encoded.getAsJsonObject("payload").getAsJsonObject("position").get("dimension").getAsString());
        assertEquals(Set.of("performance", "entities_and_chunks", "light_and_environment", "position", "looking_at"), capture.sections);
    }

    @Test
    void reportsNullValuesAndUnreadableSectionsAsUnavailable() {
        var data = SNAPSHOT.deepCopy();
        data.getAsJsonObject("performance").add("frameTimeMs", com.google.gson.JsonNull.INSTANCE);
        data.remove("looking_at");
        var reader = new GameStateReader(new FakeCapture(new GameStateReader.Snapshot(false, data)));
        var result = reader.handle(new PackSageTools.ReadRequest("read_game_state", Evidence.deserializeObject("{}"), SCOPE), null)
                .toCompletableFuture().join();
        var payload = JsonParser.parseString(Evidence.serialize(result.records().getFirst().payload())).getAsJsonObject();

        assertEquals("unavailable", payload.getAsJsonObject("performance").get("frameTimeMs").getAsString());
        assertEquals("unavailable", payload.get("looking_at").getAsString());
    }

    @Test
    void capturesAndReturnsOnlyRequestedSections() {
        var capture = new FakeCapture(new GameStateReader.Snapshot(false, SNAPSHOT));
        var sections = new JsonArray();
        sections.add("position");
        sections.add("looking_at");
        var reader = new GameStateReader(capture);
        var result = reader.handle(new PackSageTools.ReadRequest("read_game_state",
                Evidence.deserializeObject("{\"sections\":" + sections + "}"), SCOPE), null).toCompletableFuture().join();
        var payload = JsonParser.parseString(Evidence.serialize(result.records().getFirst().payload())).getAsJsonObject();

        assertEquals(Set.of("position", "looking_at"), capture.sections);
        assertEquals(Set.of("position", "looking_at"), payload.keySet());
    }

    @Test
    void marksIntegratedServerPerformanceUnavailableInMultiplayer() {
        var reader = new GameStateReader(new FakeCapture(new GameStateReader.Snapshot(true, SNAPSHOT)));
        var result = reader.handle(new PackSageTools.ReadRequest("read_game_state", Evidence.deserializeObject("{}"), SCOPE), null)
                .toCompletableFuture().join();
        var performance = JsonParser.parseString(Evidence.serialize(result.records().getFirst().payload())).getAsJsonObject()
                .getAsJsonObject("performance");

        assertEquals("unavailable", performance.get("integratedServerTps").getAsString());
        assertEquals("unavailable", performance.get("integratedServerMspt").getAsString());
    }

    private static final class FakeCapture implements GameStateReader.Capture {
        private final GameStateReader.Snapshot snapshot;
        private Set<String> sections;

        private FakeCapture(GameStateReader.Snapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public CompletionStage<GameStateReader.Snapshot> capture(Set<String> sections) {
            this.sections = Set.copyOf(sections);
            return CompletableFuture.completedFuture(snapshot);
        }
    }
}
