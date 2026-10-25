package dev.packsage.world;

import java.util.Locale;

public enum ProgressStatus {
    DONE, IN_PROGRESS, AVAILABLE, LOCKED, HIDDEN;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ProgressStatus fromWire(String wire) {
        for (var status : values()) {
            if (status.wire().equals(wire)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown progress status: " + wire);
    }
}
