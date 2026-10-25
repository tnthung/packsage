package dev.packsage.debug;

import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class DebugHttp {
    public static final int MAX_BODY_BYTES = 1_048_576;

    private DebugHttp() { }

    public record Request(String method, String path, Map<String, String> headers, String body) { }
    public record Response(int status, JsonObject body) { }

    public static final class Failure extends IOException {
        final int status;

        public Failure(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public static Request parse(InputStream input) throws IOException {
        var header = new ByteArrayOutputStream();
        int previous = -1;
        int lineLength = 0;
        while (true) {
            int value = input.read();
            if (value < 0) throw new Failure(400, "Truncated headers");
            header.write(value);
            if (header.size() > 16_384) throw new Failure(431, "Headers too large");
            if (value == '\n') {
                if (previous != '\r') throw new Failure(400, "Expected CRLF");
                if (lineLength == 1) break;
                lineLength = 0;
            } else {
                if (previous == '\r') throw new Failure(400, "Expected CRLF");
                lineLength++;
            }
            previous = value;
        }
        String[] lines = header.toString(StandardCharsets.US_ASCII).split("\r\n");
        if (lines.length == 0) throw new Failure(400, "Missing request line");
        String[] start = lines[0].split(" ", -1);
        if (start.length != 3 || !start[2].equals("HTTP/1.1") || !start[0].matches("[A-Z]+")
                || !start[1].matches("/[^\\s?#]*")) throw new Failure(400, "Invalid HTTP request line");
        if (lines.length > 65) throw new Failure(431, "Too many headers");
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon < 1 || !lines[i].substring(0, colon).matches("[A-Za-z0-9!#$%&'*+.^_`|~-]+")) {
                throw new Failure(400, "Invalid header");
            }
            String name = lines[i].substring(0, colon).toLowerCase(Locale.ROOT);
            if (headers.putIfAbsent(name, lines[i].substring(colon + 1).trim()) != null) {
                throw new Failure(400, "Duplicate header");
            }
        }
        if (headers.containsKey("transfer-encoding")) throw new Failure(400, "Transfer encoding is unsupported");
        String length = headers.getOrDefault("content-length", "0");
        if (!length.matches("[0-9]{1,10}")) throw new Failure(400, "Invalid content length");
        long size = Long.parseLong(length);
        if (size > MAX_BODY_BYTES) throw new Failure(413, "Request body too large");
        byte[] body = input.readNBytes((int) size);
        if (body.length != size) throw new Failure(400, "Truncated body");
        try {
            return new Request(start[0], start[1], Map.copyOf(headers), StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString());
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new Failure(400, "Invalid UTF-8");
        }
    }

    public static Response error(int status, String message) {
        var body = new JsonObject();
        body.addProperty("error", message);
        return new Response(status, body);
    }
}
