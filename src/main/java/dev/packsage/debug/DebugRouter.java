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
        NAVIGATE, SCREENSHOT, CHAT_SCREEN
    }

    @FunctionalInterface
    public interface Game {
        JsonObject execute(Operation operation, JsonObject body) throws Exception;
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
                default -> request.path().matches("/chat/[A-Za-z0-9_-]+") ? Operation.CHAT_STATUS : null;
            };
            if (operation == null) return DebugHttp.error(404, "Unknown endpoint");
            String method = operation == Operation.STATE || operation == Operation.CHAT_STATUS || operation == Operation.PROMPT_GET ? "GET" : "POST";
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
                    if (operation == Operation.OPEN && body.has("entity")) require(body, "entity", "string");
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
                }
                case CHAT_STATUS -> body.addProperty("requestId", request.path().substring(6));
                case PROMPT_SET -> {
                    if (!body.has("text")) throw new IllegalArgumentException("text is required (null clears it)");
                    if (!body.get("text").isJsonNull()) require(body, "text", "string");
                }
                case NAVIGATE -> { require(body, "requestId", "string"); require(body, "targetId", "string"); }
                case SCREENSHOT -> { if (body.has("hideGui")) require(body, "hideGui", "boolean"); }
                case CHAT_SCREEN -> require(body, "open", "boolean");
                default -> { }
            }
            return new DebugHttp.Response(200, game.execute(operation, body));
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
