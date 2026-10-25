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
            case PathRoot.Opened opened -> text.append("opened:").append(opened.containerId());
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
        var step = text.indexOf('>');
        var root = step < 0 ? text : text.substring(0, step);
        return new ItemPath(parseRoot(root), parseSteps(text.substring(root.length())));
    }

    /** Parses the steps after a root: empty, or one {@code >adapter/section#entry@kind} token per step. */
    static List<PathStep> parseSteps(String text) {
        var steps = new ArrayList<PathStep>();
        if (text.isEmpty()) return steps;
        if (text.charAt(0) != '>') throw new IllegalArgumentException("Invalid path steps: " + text);
        for (var part : text.substring(1).split(">", -1)) {
            var hash = part.indexOf('#');
            var at = part.lastIndexOf('@');
            if (hash < 0 || at < hash) {
                throw new IllegalArgumentException("Invalid path step: " + part);
            }
            steps.add(new PathStep(InventoryId.parse(part.substring(0, hash)), part.substring(hash + 1, at), part.substring(at + 1)));
        }
        return steps;
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
        if (text.startsWith("opened:")) {
            return new PathRoot.Opened(parseContainerId(text.substring(7)));
        }
        throw new IllegalArgumentException("Invalid item path root: " + text);
    }

    /** A non-negative decimal without leading zeros. */
    private static int parseContainerId(String text) {
        if (text.isEmpty() || text.length() > 9 || text.length() > 1 && text.charAt(0) == '0' || !text.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException("Invalid container id: " + text);
        }
        return Integer.parseInt(text);
    }

    private static UUID parseUuid(String text) {
        var id = UUID.fromString(text);
        if (!id.toString().equals(text)) {
            throw new IllegalArgumentException("Invalid UUID: " + text);
        }
        return id;
    }
}
