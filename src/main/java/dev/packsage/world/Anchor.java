package dev.packsage.world;

import java.util.UUID;

public sealed interface Anchor {
    record Player() implements Anchor {
    }

    record OfEntity(UUID id) implements Anchor {
    }

    record OfBlock(BlockPlace place) implements Anchor {
    }
}
