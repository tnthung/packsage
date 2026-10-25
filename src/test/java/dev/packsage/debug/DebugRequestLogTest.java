package dev.packsage.debug;

import dev.packsage.PackSageLog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DebugRequestLogTest {
    @Test
    void capturesActualTimingValuesByRequestAndDetachesOnClose() {
        var log = new DebugRequestLog();
        log.start();
        try {
            PackSageLog.LOGGER.info("PackSage timing request={} phase=tool_summary toolCalls={} toolWallMs={} event_ns={}", "debug-test", 2, 17, 12345);
            PackSageLog.LOGGER.info("PackSage timing request={} phase=accepted start_ns={}", "other", 999);
            assertEquals(1, log.read("debug-test").size());
            assertEquals("tool_summary", log.read("debug-test").get(0).getAsJsonObject().get("phase").getAsString());
            assertEquals("17", log.read("debug-test").get(0).getAsJsonObject().get("toolWallMs").getAsString());
            assertEquals(0, log.read("missing").size());
        } finally {
            log.close();
        }
        PackSageLog.LOGGER.info("PackSage timing request={} phase=accepted start_ns={}", "debug-test", 111);
        assertEquals(0, log.read("debug-test").size());
    }
}
