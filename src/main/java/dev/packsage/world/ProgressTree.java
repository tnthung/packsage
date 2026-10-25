package dev.packsage.world;

/** One advancement tab or quest chapter, with how many of its entries are done. */
public record ProgressTree(ProgressId id, String title, int done, int total) {
}
