package dev.packsage.world;

import com.google.gson.JsonParser;
import dev.packsage.knowledge.Evidence;
import dev.packsage.model.ModelBackend;
import dev.packsage.model.PackSageTools;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class WorldEntityListingTest {
    @Test
    void longNamesKeepEveryEntityReachableThroughEncodedByteBoundedPages() throws Exception {
        var scope = new Evidence.Scope("world", "resources", "team", "runtime", 1);
        var world = DenseWorld.create(65);
        for (var entity : world.entities) entity.setCustomName(Component.literal("\"\n\u4e16".repeat(1000)));
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var server = (net.minecraft.client.server.IntegratedServer) ((sun.misc.Unsafe) field.get(null))
                .allocateInstance(net.minecraft.client.server.IntegratedServer.class);
        var levels = net.minecraft.server.MinecraftServer.class.getDeclaredField("levels");
        levels.setAccessible(true);
        levels.set(server, Map.of(net.minecraft.world.level.Level.OVERWORLD, world));
        var running = net.minecraft.server.MinecraftServer.class.getDeclaredField("running");
        running.setAccessible(true);
        running.set(server, true);
        var work = new ServerWork(System::nanoTime);
        var provider = new WorldTools(() -> server, new WorldRegistry(), () -> scope, Runnable::run, work,
                () -> CompletableFuture.completedFuture(ClientFocus.NONE), place -> false, id -> null, null);
        var tools = new PackSageTools(request -> provider.handle(request, null));
        var arguments = "{\"center\":{\"type\":\"block\",\"place\":\"minecraft:overworld|8,64,8\"},\"radius\":4,\"filter\":{}}";
        var ids = new HashSet<String>();
        int pages = 0;
        do {
            var response = tools.execute(new ModelBackend.ToolCall("entities", "list_entities", arguments), scope).toCompletableFuture();
            while (!response.isDone()) work.tick();
            assertFalse(response.get().result().error(), response.get().result().serializedResult());
            var encoded = response.get().result().serializedResult();
            assertTrue(encoded.getBytes(StandardCharsets.UTF_8).length <= 131_072);
            var json = JsonParser.parseString(encoded).getAsJsonObject();
            assertFalse(json.getAsJsonArray("records").isEmpty());
            for (var row : json.getAsJsonArray("records")) {
                var record = row.getAsJsonObject();
                var payload = record.getAsJsonObject("payload");
                assertTrue(ids.add(payload.get("id").getAsString()));
                assertNotNull(payload.get("nameTruncated"), "oversized names need an explicit truncation flag");
                assertTrue(payload.get("nameTruncated").getAsBoolean());
                assertTrue(payload.get("name").getAsString().length() <= 1024);
                assertTrue(record.get("title").getAsString().length() < 1200);
                assertEquals("PARTIAL", record.getAsJsonObject("source").get("coverage").getAsString());
            }
            assertEquals("PARTIAL", json.get("coverage").getAsString());
            assertTrue(++pages < 20);
            if (!json.has("nextCall")) break;
            arguments = json.getAsJsonObject("nextCall").get("arguments").toString();
        } while (true);
        assertEquals(65, ids.size(), "the outer encoder must not silently discard the rest of a page");
        assertTrue(pages > 2, "escaped names should trigger the encoded byte budget before the record-count limit");
    }
}
