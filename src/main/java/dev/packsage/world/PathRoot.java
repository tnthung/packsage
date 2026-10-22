package dev.packsage.world;

import java.util.UUID;

public sealed interface PathRoot {
    record Player(UUID id) implements PathRoot {
    }

    record Block(BlockPlace place) implements PathRoot {
    }

    record Entity(UUID id) implements PathRoot {
    }
}
