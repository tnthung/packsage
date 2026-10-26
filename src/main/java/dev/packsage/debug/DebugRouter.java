package dev.packsage.debug;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.internal.Streams;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public final class DebugRouter {
    public enum Operation {
        STATE, COMMAND, LOOK, BREAK, OPEN, MENU_MOVE, MENU_CLOSE, CHAT, CHAT_STATUS, PROMPT_GET, PROMPT_SET,
        NAVIGATE, SCREENSHOT, CHAT_SCREEN, WORLD_LEAVE, WORLD_OPEN, SERVER_CONNECT, SCREEN_OPEN, RESOURCEPACKS, GAME_QUIT,
        COMMANDS_PENDING, COMMANDS_DECIDE, SETTINGS
    }

    @FunctionalInterface
    public interface Game {
        JsonObject execute(Operation operation, JsonObject body) throws Exception;
        default void afterResponse(Operation operation) { }
    }

    private final byte[] authorization;
    private final Game game;

    public DebugRouter(String token, Game game) {
        authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        this.game = game;
    }

    public DebugHttp.Response route(DebugHttp.Request request) {
        if (!MessageDigest.isEqual(authorization, request.headers().getOrDefault("authorization", "").getBytes(StandardCharsets.UTF_8))) {
            return DebugHttp.error(401, "Bearer token required");
        }
        try {
            if (request.body().getBytes(StandardCharsets.UTF_8).length > DebugHttp.MAX_BODY_BYTES) {
                return DebugHttp.error(413, "Request body too large");
            }
            Operation operation = switch (request.path()) {
                case "/state" -> Operation.STATE;
                case "/command" -> Operation.COMMAND;
                case "/player/look" -> Operation.LOOK;
                case "/player/break" -> Operation.BREAK;
                case "/player/open" -> Operation.OPEN;
                case "/player/menu/move" -> Operation.MENU_MOVE;
                case "/player/menu/close" -> Operation.MENU_CLOSE;
                case "/chat" -> Operation.CHAT;
                case "/prompt" -> request.method().equals("GET") ? Operation.PROMPT_GET : Operation.PROMPT_SET;
                case "/navigate" -> Operation.NAVIGATE;
                case "/screenshot" -> Operation.SCREENSHOT;
                case "/chat-screen" -> Operation.CHAT_SCREEN;
                case "/world/leave" -> Operation.WORLD_LEAVE;
                case "/world/open" -> Operation.WORLD_OPEN;
                case "/server/connect" -> Operation.SERVER_CONNECT;
                case "/screen/open" -> Operation.SCREEN_OPEN;
                case "/resourcepacks" -> Operation.RESOURCEPACKS;
                case "/game/quit" -> Operation.GAME_QUIT;
                case "/commands/pending" -> Operation.COMMANDS_PENDING;
                case "/commands/decide" -> Operation.COMMANDS_DECIDE;
                case "/settings" -> Operation.SETTINGS;
                default -> request.path().matches("/chat/[A-Za-z0-9_-]+") ? Operation.CHAT_STATUS : null;
            };
            if (operation == null) return DebugHttp.error(404, "Unknown endpoint");
            String method = operation == Operation.STATE || operation == Operation.CHAT_STATUS || operation == Operation.PROMPT_GET
                    || operation == Operation.COMMANDS_PENDING ? "GET" : "POST";
            if (!method.equals(request.method())) return DebugHttp.error(405, "Method not allowed");
            JsonObject body = new JsonObject();
            if (!request.body().isBlank()) {
                try (var reader = new JsonReader(new StringReader(request.body()))) {
                    reader.setLenient(false);
                    JsonElement parsed = Streams.parse(reader);
                    if (!parsed.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Expected a JSON object");
                    body = parsed.getAsJsonObject();
                }
            }
            switch (operation) {
                case COMMAND -> require(body, "command", "string");
                case LOOK -> {
                    if (body.has("yaw") || body.has("pitch")) {
                        require(body, "yaw", "number");
                        require(body, "pitch", "number");
                        if (Math.abs(body.get("pitch").getAsDouble()) > 90) throw new IllegalArgumentException("pitch must be between -90 and 90");
                    } else {
                        require(body, "x", "number"); require(body, "y", "number"); require(body, "z", "number");
                    }
                }
                case BREAK, OPEN -> {
                    if (operation == Operation.OPEN && body.has("openInventory") && !body.has("entity")) {
                        throw new IllegalArgumentException("openInventory requires an entity");
                    }
                    if (operation == Operation.OPEN && body.has("entity")) {
                        require(body, "entity", "string");
                        if (body.has("openInventory")) require(body, "openInventory", "boolean");
                    }
                    else { require(body, "x", "integer"); require(body, "y", "integer"); require(body, "z", "integer"); }
                }
                case MENU_MOVE -> {
                    require(body, "slot", "integer");
                    if (body.get("slot").getAsInt() < 0) throw new IllegalArgumentException("slot cannot be negative");
                    if (body.has("shift")) require(body, "shift", "boolean");
                }
                case CHAT -> {
                    require(body, "question", "string");
                    if (body.has("conversation")) require(body, "conversation", "string");
                    if (body.has("screenshot")) require(body, "screenshot", "boolean");
                    if (body.has("webSearch")) require(body, "webSearch", "boolean");
                }
                case CHAT_STATUS -> body.addProperty("requestId", request.path().substring(6));
                case PROMPT_SET -> {
                    if (!body.has("text")) throw new IllegalArgumentException("text is required (null clears it)");
                    if (!body.get("text").isJsonNull()) require(body, "text", "string");
                }
                case NAVIGATE -> { require(body, "requestId", "string"); require(body, "targetId", "string"); }
                case SCREENSHOT -> { if (body.has("hideGui")) require(body, "hideGui", "boolean"); }
                case CHAT_SCREEN -> {
                    require(body, "open", "boolean");
                    if (body.has("conversationId")) {
                        require(body, "conversationId", "string");
                        if (!body.get("open").getAsBoolean()) throw new IllegalArgumentException("conversationId requires open=true");
                    }
                }
                case WORLD_OPEN -> {
                    require(body, "save", "string");
                    String save = body.get("save").getAsString();
                    if (save.equals(".") || save.equals("..") || save.contains("/") || save.contains("\\")) {
                        throw new IllegalArgumentException("save must be a save folder or level name");
                    }
                }
                case SERVER_CONNECT -> require(body, "address", "string");
                case SCREEN_OPEN -> {
                    require(body, "screen", "string");
                    String screen = body.get("screen").getAsString();
                    if (!screen.equals("adapters") && !screen.equals("settings") && !screen.equals("chat")) {
                        throw new IllegalArgumentException("screen must be adapters, settings, or chat");
                    }
                    if (body.has("conversationId")) {
                        require(body, "conversationId", "string");
                        if (!screen.equals("chat")) throw new IllegalArgumentException("conversationId is only valid for the chat screen");
                    }
                }
                case RESOURCEPACKS -> {
                    if (!body.has("enable") || !body.get("enable").isJsonArray()) throw new IllegalArgumentException("enable must be an array of file pack ids");
                    if (!body.has("disable") || !body.get("disable").isJsonArray()) throw new IllegalArgumentException("disable must be an array of file pack ids");
                    var enable = new java.util.HashSet<String>();
                    var disable = new java.util.HashSet<String>();
                    for (var id : body.getAsJsonArray("enable")) {
                        if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString() || !id.getAsString().startsWith("file/")
                                || id.getAsString().length() <= 5 || !enable.add(id.getAsString())) {
                            throw new IllegalArgumentException("enable must contain unique file/<name> ids");
                        }
                    }
                    for (var id : body.getAsJsonArray("disable")) {
                        if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString() || !id.getAsString().startsWith("file/")
                                || id.getAsString().length() <= 5 || !disable.add(id.getAsString())) {
                            throw new IllegalArgumentException("disable must contain unique file/<name> ids");
                        }
                    }
                    if (enable.stream().anyMatch(disable::contains)) throw new IllegalArgumentException("a pack cannot be enabled and disabled in one request");
                }
                case COMMANDS_DECIDE -> { require(body, "id", "string"); require(body, "run", "boolean"); }
                case SETTINGS -> {
                    if (body.has("commandConfirmation")) {
                        require(body, "commandConfirmation", "string");
                        if (!java.util.Set.of("always", "risky", "direct").contains(body.get("commandConfirmation").getAsString())) {
                            throw new IllegalArgumentException("commandConfirmation must be always, risky or direct");
                        }
                    }
                    if (body.has("verbosity")) {
                        require(body, "verbosity", "string");
                        if (!java.util.Set.of("concise", "normal", "detailed").contains(body.get("verbosity").getAsString())) {
                            throw new IllegalArgumentException("verbosity must be concise, normal or detailed");
                        }
                    }
                    if (body.has("showChatsFromOtherSessions")) require(body, "showChatsFromOtherSessions", "boolean");
                }
                default -> { }
            }
            return new DebugHttp.Response(operation == Operation.GAME_QUIT ? 202 : 200, game.execute(operation, body),
                    operation == Operation.GAME_QUIT ? () -> game.afterResponse(operation) : () -> { });
        } catch (Exception exception) {
            Throwable failure = exception;
            while ((failure instanceof ExecutionException || failure instanceof java.util.concurrent.CompletionException) && failure.getCause() != null) failure = failure.getCause();
            int status = failure instanceof TimeoutException ? 504 : failure instanceof IllegalStateException ? 409
                    : failure instanceof IllegalArgumentException || failure instanceof com.google.gson.JsonParseException
                    || failure instanceof java.io.IOException ? 400 : 500;
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return DebugHttp.error(status, failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        }
    }

    private static void require(JsonObject body, String field, String type) {
        var value = body.get(field);
        if (value == null || !value.isJsonPrimitive()) throw new IllegalArgumentException(field + " must be " + type);
        var primitive = value.getAsJsonPrimitive();
        boolean valid = switch (type) {
            case "string" -> primitive.isString() && !primitive.getAsString().isBlank();
            case "boolean" -> primitive.isBoolean();
            case "number" -> primitive.isNumber() && Double.isFinite(primitive.getAsDouble()) && Float.isFinite(primitive.getAsFloat());
            case "integer" -> primitive.isNumber() && primitive.getAsBigDecimal().stripTrailingZeros().scale() <= 0
                    && primitive.getAsBigDecimal().compareTo(java.math.BigDecimal.valueOf(Integer.MIN_VALUE)) >= 0
                    && primitive.getAsBigDecimal().compareTo(java.math.BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0;
            default -> false;
        };
        if (!valid) throw new IllegalArgumentException(field + " must be " + type);
    }
}
