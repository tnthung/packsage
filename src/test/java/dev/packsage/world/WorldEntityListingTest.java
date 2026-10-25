package dev.packsage.world;

import com.google.gson.JsonParser;
import dev.packsage.knowledge.Evidence;
import dev.packsage.model.ModelBackend;
import dev.packsage.model.PackSageTools;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class WorldEntityListingTest {
    @ParameterizedTest
    @ValueSource(strings = {"matches", "empty", "frozen_matches", "frozen_empty"})
    void stableMultiTickListingsReportTheirSpanWithoutPartialCoverage(String scenario) throws Exception {
        var scope = new Evidence.Scope("world", "resources", "team", "runtime", 1);
        var world = DenseWorld.create(scenario.endsWith("empty") ? 0 : 3);
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var server = (net.minecraft.client.server.IntegratedServer) ((sun.misc.Unsafe) field.get(null))
                .allocateInstance(net.minecraft.client.server.IntegratedServer.class);
        world.server = server;
        var levels = net.minecraft.server.MinecraftServer.class.getDeclaredField("levels");
        levels.setAccessible(true);
        levels.set(server, Map.of(net.minecraft.world.level.Level.OVERWORLD, world));
        var running = net.minecraft.server.MinecraftServer.class.getDeclaredField("running");
        running.setAccessible(true);
        running.set(server, true);
        var tickCount = net.minecraft.server.MinecraftServer.class.getDeclaredField("tickCount");
        tickCount.setAccessible(true);
        var clock = new java.util.concurrent.atomic.AtomicLong();
        var work = new ServerWork(() -> clock.getAndAdd(1_000_000));
        var provider = new WorldTools(() -> server, new WorldRegistry(), () -> scope, Runnable::run, work,
                () -> CompletableFuture.completedFuture(ClientFocus.NONE), place -> false, id -> null, null);
        var tools = new PackSageTools(request -> provider.handle(request, null));
        var response = tools.execute(new ModelBackend.ToolCall("entities", "list_entities",
                "{\"center\":{\"type\":\"block\",\"place\":\"minecraft:overworld|8,64,8\"},\"radius\":4,\"filter\":{}}"), scope).toCompletableFuture();
        int ticks = 0;
        do {
            tickCount.setInt(server, ++ticks);
            work.tick();
            if (!response.isDone() && !scenario.startsWith("frozen")) world.gameTime++;
            assertTrue(ticks < 20);
        } while (!response.isDone());
        var json = JsonParser.parseString(response.get().result().serializedResult()).getAsJsonObject();
        assertEquals("COMPLETE", json.get("coverage").getAsString(), json.toString());
        assertEquals("COMPLETE", json.get("outcome").getAsString(), json.toString());
        assertTrue(ticks > 1);
        assertTrue(json.get("detail").getAsString().contains("400.." + world.gameTime), json.toString());
        assertEquals(scenario.endsWith("empty") ? 0 : 3, json.getAsJsonArray("records").size());
        for (var row : json.getAsJsonArray("records")) {
            var span = row.getAsJsonObject().getAsJsonObject("payload").getAsJsonObject("readSpan");
            assertEquals(400, span.get("fromGameTime").getAsLong());
            assertEquals(world.gameTime, span.get("toGameTime").getAsLong());
        }
    }

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
