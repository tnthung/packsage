package dev.packsage.world;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record ItemPath(PathRoot root, List<PathStep> steps) {
    public static final int MAX_STEPS = 6;

    public ItemPath {
        steps = List.copyOf(steps);
        if (steps.size() > MAX_STEPS) {
            throw new IllegalArgumentException("Item path exceeds " + MAX_STEPS + " steps");
        }
    }

    public ItemPath child(PathStep step) {
        var next = new ArrayList<>(steps);
        next.add(step);
        return new ItemPath(root, next);
    }

    public String text() {
        var text = new StringBuilder();
        switch (root) {
            case PathRoot.Player player -> text.append("player:").append(player.id());
            case PathRoot.Entity entity -> text.append("entity:").append(entity.id());
            case PathRoot.Block block -> text.append("block:").append(block.place().text());
        }
        for (var step : steps) {
            text.append('>').append(step.inventory().text()).append('#').append(step.entry()).append('@').append(step.kind());
        }
        return text.toString();
    }

    public static ItemPath parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Item path is missing");
        }
        var parts = text.split(">", -1);
        var steps = new ArrayList<PathStep>();
        for (var i = 1; i < parts.length; i++) {
            var hash = parts[i].indexOf('#');
            var at = parts[i].lastIndexOf('@');
            if (hash < 0 || at < hash) {
                throw new IllegalArgumentException("Invalid path step: " + parts[i]);
            }
            steps.add(new PathStep(InventoryId.parse(parts[i].substring(0, hash)), parts[i].substring(hash + 1, at), parts[i].substring(at + 1)));
        }
        return new ItemPath(parseRoot(parts[0]), steps);
    }

    private static PathRoot parseRoot(String text) {
        if (text.startsWith("player:")) {
            return new PathRoot.Player(parseUuid(text.substring(7)));
        }
        if (text.startsWith("entity:")) {
            return new PathRoot.Entity(parseUuid(text.substring(7)));
        }
        if (text.startsWith("block:")) {
            return new PathRoot.Block(BlockPlace.parse(text.substring(6)));
        }
        throw new IllegalArgumentException("Invalid item path root: " + text);
    }

    private static UUID parseUuid(String text) {
        var id = UUID.fromString(text);
        if (!id.toString().equals(text)) {
            throw new IllegalArgumentException("Invalid UUID: " + text);
        }
        return id;
    }
}
