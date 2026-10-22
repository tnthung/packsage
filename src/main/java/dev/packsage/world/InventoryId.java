package dev.packsage.world;

public record InventoryId(String adapter, String section) {
    public InventoryId {
        if (adapter == null || adapter.isEmpty() || adapter.indexOf('/') >= 0 || section == null || section.isEmpty()
                || "#@>".chars().anyMatch(c -> section.indexOf(c) >= 0 || adapter.indexOf(c) >= 0)) {
            throw new IllegalArgumentException("Invalid inventory id: " + adapter + "/" + section);
        }
    }

    public String text() {
        return adapter + "/" + section;
    }

    public static InventoryId parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Inventory id is missing");
        }
        var slash = text.indexOf('/');
        if (slash <= 0 || slash == text.length() - 1) {
            throw new IllegalArgumentException("Invalid inventory id: " + text);
        }
        return new InventoryId(text.substring(0, slash), text.substring(slash + 1));
    }
}
