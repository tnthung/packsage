package dev.packsage.debug;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.packsage.chat.PackSageSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DebugBridgeTest {
    @TempDir Path directory;

    @Test
    void parsesUtf8BodyAndCaseInsensitiveHeaders() throws Exception {
        String body = "{\"question\":\"hello 世界\"}";
        var request = DebugHttp.parse(new ByteArrayInputStream(("POST /chat HTTP/1.1\r\nHost: localhost\r\nauthorization: Bearer secret\r\nContent-Length: "
                + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body).getBytes(StandardCharsets.UTF_8)));
        assertEquals("POST", request.method());
        assertEquals("/chat", request.path());
        assertEquals(body, request.body());
        assertEquals("Bearer secret", request.headers().get("authorization"));
    }

    @Test
    void emptyHeadersStillProduceAControlledClientError() {
        assertThrows(DebugHttp.Failure.class, () -> DebugHttp.parse(new ByteArrayInputStream("\r\n".getBytes(StandardCharsets.US_ASCII))));
    }

    @Test
    void stoppedListenerCannotLeaveAnAcceptedConnectionQueuedForever() throws Exception {
        var logs = new ArrayList<String>();
        var bridge = new DebugBridge(directory, 0, (operation, body) -> body, logs::add);
        assertTrue(bridge.start());
        var token = JsonParser.parseString(Files.readString(directory.resolve("packsage/debug-bridge.json"))).getAsJsonObject();
        try (var socket = new Socket("127.0.0.1", token.get("port").getAsInt())) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write("GET /state HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII));
            bridge.close();
            assertEquals(-1, socket.getInputStream().read());
        } finally {
            bridge.close();
        }
        assertFalse(Files.exists(directory.resolve("packsage/debug-bridge.json")));
    }

    @Test
    void rejectsAmbiguousOversizedAndTruncatedRequests() {
        for (String wire : new String[] {
                "POST /chat HTTP/1.1\r\nContent-Length: 1048577\r\n\r\n",
                "POST /chat HTTP/1.1\r\nContent-Length: -1\r\n\r\n",
                "POST /chat HTTP/1.1\r\nContent-Length: 4\r\nContent-Length: 4\r\n\r\n{}",
                "POST /chat HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n",
                "POST /chat HTTP/1.1\r\nContent-Length: 4\r\n\r\n{}",
                "GET /state HTTP/1.1\r\n" + "X: a\r\n".repeat(65) + "\r\n",
                "GET /state HTTP/1.1\r\nX: " + "a".repeat(17000) + "\r\n\r\n",
                "GET /state HTTP/1.0\r\n\r\n" }) {
            assertThrows(DebugHttp.Failure.class, () -> DebugHttp.parse(new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8))), wire.substring(0, Math.min(25, wire.length())));
        }
    }

    @Test
    void authenticatesBeforeRouting() {
        var calls = new ArrayList<DebugRouter.Operation>();
        var router = new DebugRouter("secret", (operation, body) -> { calls.add(operation); return new JsonObject(); });
        assertEquals(401, router.route(new DebugHttp.Request("GET", "/state", Map.of(), "")).status());
        assertEquals(401, router.route(new DebugHttp.Request("GET", "/state", Map.of("authorization", "Bearer wrong"), "")).status());
        assertEquals(401, router.route(new DebugHttp.Request("GET", "/state", Map.of("authorization", "bearer secret"), "")).status());
        assertEquals(200, router.route(new DebugHttp.Request("GET", "/state", Map.of("authorization", "Bearer secret"), "")).status());
        assertEquals(java.util.List.of(DebugRouter.Operation.STATE), calls);
    }

    @Test
    void routesEveryHandlerToTheAdapterWithItsArguments() {
        var seen = new ArrayList<DebugRouter.Operation>();
        var router = new DebugRouter("secret", (operation, body) -> {
            seen.add(operation);
            if (operation == DebugRouter.Operation.CHAT_STATUS) assertEquals("req-1", body.get("requestId").getAsString());
            return body;
        });
        String[][] requests = {
                {"GET", "/state", ""}, {"POST", "/command", "{\"command\":\"time set day\"}"},
                {"POST", "/player/look", "{\"yaw\":30,\"pitch\":10}"},
                {"POST", "/player/break", "{\"x\":1,\"y\":2,\"z\":3}"},
                {"POST", "/player/open", "{\"entity\":\"uuid\",\"openInventory\":true}"},
                {"POST", "/player/menu/move", "{\"slot\":3,\"shift\":true}"},
                {"POST", "/player/menu/close", ""}, {"POST", "/chat", "{\"question\":\"hello\"}"},
                {"GET", "/chat/req-1", ""}, {"GET", "/prompt", ""}, {"POST", "/prompt", "{\"text\":null}"},
                {"POST", "/navigate", "{\"requestId\":\"req-1\",\"targetId\":\"target-1\"}"},
                {"POST", "/screenshot", "{\"hideGui\":true}"}, {"POST", "/chat-screen", "{\"open\":true}"},
                {"POST", "/world/leave", ""}, {"POST", "/world/open", "{\"save\":\"Fixture World\"}"},
                {"POST", "/server/connect", "{\"address\":\"127.0.0.1:25565\"}"},
                {"POST", "/screen/open", "{\"screen\":\"adapters\"}"},
                {"POST", "/resourcepacks", "{\"enable\":[\"file/Example\"],\"disable\":[]}"},
                {"POST", "/game/quit", ""},
                {"GET", "/commands/pending", ""},
                {"POST", "/commands/decide", "{\"id\":\"c-1\",\"run\":false}"},
                {"POST", "/settings", "{\"commandConfirmation\":\"risky\",\"verbosity\":\"concise\",\"showChatsFromOtherSessions\":true}"}
        };
        for (String[] request : requests) {
            var response = router.route(new DebugHttp.Request(request[0], request[1], Map.of("authorization", "Bearer secret"), request[2]));
            assertEquals(request[1].equals("/game/quit") ? 202 : 200, response.status(), request[1]);
            if (!request[2].isEmpty()) assertEquals(JsonParser.parseString(request[2]), response.body());
        }
        assertEquals(java.util.List.of(DebugRouter.Operation.values()), seen);
    }

    @Test
    void rejectsBadMethodsPathsJsonAndArgumentsWithoutCallingGame() {
        var router = new DebugRouter("secret", (operation, body) -> { fail("must not call adapter"); return null; });
        assertEquals(405, router.route(new DebugHttp.Request("DELETE", "/state", Map.of("authorization", "Bearer secret"), "")).status());
        assertEquals(404, router.route(new DebugHttp.Request("GET", "/missing", Map.of("authorization", "Bearer secret"), "")).status());
        for (String[] request : new String[][] {
                {"/chat", "[1]"}, {"/chat", "{"}, {"/chat", "{}"}, {"/chat", "{\"question\":false}"},
                {"/chat", "{\"question\":\"   \"}"}, {"/prompt", "{}"}, {"/prompt", "{\"text\":3}"},
                {"/chat", "{\"question\":\"hello\",\"screenshot\":1}"}, {"/chat", "{\"question\":\"hello\",\"webSearch\":null}"},
                {"/player/look", "{\"yaw\":3}"}, {"/player/break", "{\"x\":1.5,\"y\":2,\"z\":3}"},
                {"/player/menu/move", "{\"slot\":-1}"}, {"/screenshot", "{\"hideGui\":1}"},
                {"/chat-screen", "{\"open\":\"true\"}"},
                {"/player/open", "{\"entity\":\"uuid\",\"openInventory\":\"true\"}"},
                {"/player/open", "{\"x\":1,\"y\":2,\"z\":3,\"openInventory\":true}"},
                {"/world/open", "{}"}, {"/world/open", "{\"save\":\"../outside\"}"},
                {"/server/connect", "{\"address\":false}"},
                {"/screen/open", "{}"}, {"/screen/open", "{\"screen\":\"unknown\"}"},
                {"/screen/open", "{\"screen\":\"settings\",\"conversationId\":\"saved\"}"},
                {"/resourcepacks", "{}"},
                {"/resourcepacks", "{\"enable\":[\"other/Example\"],\"disable\":[]}"},
                {"/resourcepacks", "{\"enable\":[\"file/Example\"],\"disable\":[\"file/Example\"]}"},
                {"/commands/decide", "{\"id\":\"c-1\"}"}, {"/settings", "{\"verbosity\":\"loud\"}"},
                {"/settings", "{\"commandConfirmation\":true}"} }) {
            assertEquals(400, router.route(new DebugHttp.Request("POST", request[0], Map.of("authorization", "Bearer secret"), request[1])).status(), request[0] + request[1]);
        }
        assertEquals(405, router.route(new DebugHttp.Request("GET", "/world/leave", Map.of("authorization", "Bearer secret"), "")).status());
        assertEquals(405, router.route(new DebugHttp.Request("GET", "/game/quit", Map.of("authorization", "Bearer secret"), "")).status());
        assertEquals(413, router.route(new DebugHttp.Request("POST", "/chat", Map.of("authorization", "Bearer secret"), "x".repeat(1048577))).status());
    }

    @Test
    void savedChatScreenAcceptsAnOptionalConversationId() {
        var router = new DebugRouter("secret", (operation, body) -> body);
        var response = router.route(new DebugHttp.Request("POST", "/chat-screen", Map.of("authorization", "Bearer secret"),
                "{\"open\":true,\"conversationId\":\"saved\"}"));
        assertEquals(200, response.status());
        assertEquals("saved", response.body().get("conversationId").getAsString());
        response = router.route(new DebugHttp.Request("POST", "/screen/open", Map.of("authorization", "Bearer secret"),
                "{\"screen\":\"chat\",\"conversationId\":\"saved\"}"));
        assertEquals(200, response.status());
        assertEquals("saved", response.body().get("conversationId").getAsString());
    }

    @Test
    void gameQuitSchedulesShutdownOnlyAfterAcceptedResponseIsSent() {
        var stopped = new java.util.concurrent.atomic.AtomicBoolean();
        var router = new DebugRouter("secret", new DebugRouter.Game() {
            @Override public JsonObject execute(DebugRouter.Operation operation, JsonObject body) { return new JsonObject(); }
            @Override public void afterResponse(DebugRouter.Operation operation) { stopped.set(true); }
        });
        var response = router.route(new DebugHttp.Request("POST", "/game/quit", Map.of("authorization", "Bearer secret"), ""));
        assertEquals(202, response.status());
        assertFalse(stopped.get());
        response.afterSend().run();
        assertTrue(stopped.get());
    }

    @Test
    void mapsGameFailuresAndTimeoutsToJsonErrors() {
        for (Exception failure : new Exception[] { new IllegalStateException("no world"), new java.util.concurrent.TimeoutException("game timeout") }) {
            var router = new DebugRouter("secret", (operation, body) -> { throw failure; });
            var response = router.route(new DebugHttp.Request("GET", "/state", Map.of("authorization", "Bearer secret"), ""));
            assertEquals(failure instanceof java.util.concurrent.TimeoutException ? 504 : 409, response.status());
            assertTrue(response.body().has("error"));
        }
    }

    @Test
    void configIsOffByDefaultAndSurvivesPromptSaves() throws Exception {
        Path path = directory.resolve("packsage.properties");
        assertFalse(PackSageSettings.load(path).debugBridge());
        assertEquals(25599, PackSageSettings.load(path).debugBridgePort());
        Files.writeString(path, "debugBridge=true\ndebugBridgePort=25601\n");
        var settings = PackSageSettings.load(path);
        assertTrue(settings.debugBridge());
        settings.saveDeveloperPrompt(path, "test prompt").join();
        assertTrue(PackSageSettings.load(path).debugBridge());
        assertEquals(25601, PackSageSettings.load(path).debugBridgePort());
        Files.writeString(path, "debugBridgePort=-1\n");
        assertEquals(25599, PackSageSettings.load(path).debugBridgePort());
        String previous = System.getProperty("packsage.debugBridge");
        try {
            System.setProperty("packsage.debugBridge", "true");
            assertTrue(DebugBridge.enabled(PackSageSettings.defaults()));
        } finally {
            if (previous == null) System.clearProperty("packsage.debugBridge");
            else System.setProperty("packsage.debugBridge", previous);
        }
    }

    @Test
    void chatScreenshotAndSearchOverridesReachTheGameWithoutInventingDefaults() {
        var router = new DebugRouter("secret", (operation, body) -> body);
        for (String body : new String[] { "{\"question\":\"hello\"}",
                "{\"question\":\"hello\",\"screenshot\":false,\"webSearch\":true}" }) {
            var response = router.route(new DebugHttp.Request("POST", "/chat", Map.of("authorization", "Bearer secret"), body));
            assertEquals(200, response.status());
            assertEquals(JsonParser.parseString(body), response.body());
        }
    }

    @Test
    void ordinarySettingsSavesDoNotAddDebugBridgeProperties() throws Exception {
        Path path = directory.resolve("ordinary.properties");
        PackSageSettings.defaults().save(path).join();
        var properties = new java.util.Properties();
        try (var input = Files.newInputStream(path)) { properties.load(input); }
        assertFalse(properties.containsKey("debugBridge"));
        assertFalse(properties.containsKey("debugBridgePort"));
        Files.writeString(path, "debugBridge=false\ndebugBridgePort=25599\n");
        PackSageSettings.load(path).saveDeveloperPrompt(path, "test").join();
        try (var input = Files.newInputStream(path)) { properties.load(input); }
        assertEquals("false", properties.getProperty("debugBridge"));
        assertEquals("25599", properties.getProperty("debugBridgePort"));
    }

    @Test
    void listenerPublishesFreshTokenServesRequestsAndDeletesFileOnStop() throws Exception {
        var logs = new ArrayList<String>();
        Path tokenFile = directory.resolve("packsage/debug-bridge.json");
        String firstToken;
        try (var bridge = new DebugBridge(directory, 0, (operation, body) -> body, logs::add)) {
            assertTrue(bridge.start());
            var token = JsonParser.parseString(Files.readString(tokenFile)).getAsJsonObject();
            firstToken = token.get("token").getAsString();
            assertTrue(firstToken.matches("[0-9a-f]{64}"));
            assertEquals(ProcessHandle.current().pid(), token.get("pid").getAsLong());
            try (var socket = new Socket("127.0.0.1", token.get("port").getAsInt())) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write(("GET /state HTTP/1.1\r\nAuthorization: Bearer " + firstToken + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(response.startsWith("HTTP/1.1 200"));
                assertTrue(response.contains("Connection: close"));
            }
            try (var refused = new DebugBridge(directory.resolve("other"), token.get("port").getAsInt(), (operation, body) -> body, logs::add)) {
                assertFalse(refused.start());
                assertFalse(Files.exists(directory.resolve("other/packsage/debug-bridge.json")));
            }
        }
        assertFalse(Files.exists(tokenFile));
        try (var bridge = new DebugBridge(directory, 0, (operation, body) -> body, logs::add)) {
            assertTrue(bridge.start());
            assertNotEquals(firstToken, JsonParser.parseString(Files.readString(tokenFile)).getAsJsonObject().get("token").getAsString());
        }
        assertTrue(logs.stream().noneMatch(line -> line.contains(firstToken)));
        assertTrue(logs.stream().anyMatch(line -> line.contains("refused")));
        assertTrue(logs.stream().anyMatch(line -> line.contains("stopped")));
    }
}
