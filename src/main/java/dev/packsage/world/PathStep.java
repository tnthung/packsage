package dev.packsage.world;

public record PathStep(InventoryId inventory, String entry, String kind) {
    public PathStep {
        if (inventory == null || entry == null || entry.isEmpty() || kind == null || kind.isEmpty()
                || entry.indexOf('>') >= 0 || entry.indexOf('@') >= 0 || kind.indexOf('>') >= 0 || kind.indexOf('@') >= 0) {
            throw new IllegalArgumentException("Invalid path step: " + inventory + " " + entry + " " + kind);
        }
    }
}
