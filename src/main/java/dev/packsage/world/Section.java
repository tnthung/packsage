package dev.packsage.world;

import dev.packsage.knowledge.Evidence;

public record Section(Evidence.JsonObject facts, Evidence.Coverage coverage, String note) {
}
