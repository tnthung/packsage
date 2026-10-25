package dev.packsage.world;

import java.util.UUID;

public sealed interface PathRoot {
    record Player(UUID id) implements PathRoot {
    }

    record Block(BlockPlace place) implements PathRoot {
    }

    record Entity(UUID id) implements PathRoot {
    }

    /** The player's open menu by its container id; valid only while that menu stays open. */
    record Opened(int containerId) implements PathRoot {
        public Opened {
            if (containerId < 0) throw new IllegalArgumentException("Invalid container id: " + containerId);
        }
    }
}
