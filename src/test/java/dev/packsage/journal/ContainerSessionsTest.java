package dev.packsage.journal;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerSessionsTest {
    @Test
    void hasTracksOpenAndClosedPlayers() {
        var sessions = new ContainerSessions();
        assertFalse(sessions.has("player:A"));
        sessions.open("player:A", 1, new ContainerSessions.Holder("minecraft:chest", "minecraft:overworld", 0, 64, 0, null), Map.of());
        assertTrue(sessions.has("player:A"));
        sessions.close("player:A", null, 1, Map.of(), new JournalEvents.Stamp(0, 0, "minecraft:overworld"));
        assertFalse(sessions.has("player:A"));
    }
}
