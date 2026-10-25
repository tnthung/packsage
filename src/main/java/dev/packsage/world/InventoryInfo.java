package dev.packsage.world;

/** PendingLoot is the loot table id of a section whose loot is not generated yet, so it has no items yet; otherwise null. */
public record InventoryInfo(InventoryId id, String label, int slots, SlotRole role, String pendingLoot) {
    public InventoryInfo(InventoryId id, String label, int slots, SlotRole role) {
        this(id, label, slots, role, null);
    }
}
