package dev.packsage.world;

/** One advancement tab or quest chapter, with how many of its entries are done and whether its tab can be opened. */
public record ProgressTree(ProgressId id, String title, int done, int total, boolean tabVisible) {
}
