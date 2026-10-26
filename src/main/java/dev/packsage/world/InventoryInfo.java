package dev.packsage.world;

/**
 * PendingLoot describes the loot table of a section whose loot is not generated yet, so it has no items yet: its id, seed
 * and whether it is undefined or empty. Otherwise null.
 */
public record InventoryInfo(InventoryId id, String label, int slots, SlotRole role, String pendingLoot) {
    public InventoryInfo(InventoryId id, String label, int slots, SlotRole role) {
        this(id, label, slots, role, null);
    }
}
