package dev.packsage.world;

public record BlockPlace(String dimension, int x, int y, int z) {
    public BlockPlace {
        if (dimension == null || dimension.isEmpty() || dimension.indexOf('|') >= 0 || dimension.indexOf('>') >= 0) {
            throw new IllegalArgumentException("Invalid dimension: " + dimension);
        }
    }

    public String text() {
        return dimension + "|" + x + "," + y + "," + z;
    }

    public static BlockPlace parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Block place is missing");
        }
        var bar = text.indexOf('|');
        if (bar <= 0) {
            throw new IllegalArgumentException("Invalid block place: " + text);
        }
        var parts = text.substring(bar + 1).split(",", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid block place: " + text);
        }
        try {
            return new BlockPlace(text.substring(0, bar), Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid block place: " + text, exception);
        }
    }
}
