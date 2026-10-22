package dev.packsage.world;

import java.util.UUID;

public record EntityHandle(String kind, UUID id, String dimension, double x, double y, double z, String name) {
}
