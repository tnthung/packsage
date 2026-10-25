package dev.packsage.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.LinkedHashMap;
import java.util.Map;

/** Captures the existing timing log's exact values only while the debug bridge is running. */
public final class DebugRequestLog extends AbstractAppender implements AutoCloseable {
    private final Map<String, JsonArray> requests = new LinkedHashMap<>();

    public DebugRequestLog() {
        super("packsage-debug-timing", null, null, true, Property.EMPTY_ARRAY);
    }

    @Override
    public void start() {
        super.start();
        ((Logger) LogManager.getLogger("PackSage")).addAppender(this);
    }

    @Override
    public synchronized void append(LogEvent event) {
        String message = event.getMessage().getFormattedMessage();
        if (!message.startsWith("PackSage timing request=")) return;
        var fields = new JsonObject();
        for (String field : message.substring("PackSage timing ".length()).split(" ")) {
            int separator = field.indexOf('=');
            if (separator > 0) fields.addProperty(field.substring(0, separator), field.substring(separator + 1));
        }
        String request = fields.get("request").getAsString();
        if (!requests.containsKey(request) && requests.size() >= 256) requests.remove(requests.keySet().iterator().next());
        var entries = requests.computeIfAbsent(request, ignored -> new JsonArray());
        if (entries.size() < 2048) entries.add(fields);
    }

    public synchronized JsonArray read(String request) {
        var entries = requests.get(request);
        return entries == null ? new JsonArray() : entries.deepCopy();
    }

    @Override
    public synchronized void close() {
        ((Logger) LogManager.getLogger("PackSage")).removeAppender(this);
        stop();
        requests.clear();
    }
}
