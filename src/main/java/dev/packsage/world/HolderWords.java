package dev.packsage.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The words {@code held}, {@code offhand}, {@code targeted}, {@code ridden} and {@code opened} that list_items and
 * inspect_item accept as a path root. A word resolves once, at call time, to a concrete path.
 */
public final class HolderWords {
    private static final InventoryId MAIN = new InventoryId("minecraft", "main");
    private static final InventoryId OFFHAND = new InventoryId("minecraft", "offhand");

    enum Word { HELD, OFFHAND, TARGETED, RIDDEN, OPENED }

    record Phrase(Word word, List<PathStep> steps) {
        Phrase {
            steps = List.copyOf(steps);
        }
    }

    /** Kinds are null for empty slots; openedContainerId is -1 when no menu is open. */
    record Situation(UUID player, int selectedSlot, String mainKind, String offhandKind, PathRoot targeted, UUID ridden, int openedContainerId) {
    }

    sealed interface Resolved {
        record Path(ItemPath path) implements Resolved {
        }

        record Unresolved(String reason) implements Resolved {
        }
    }

    private HolderWords() {
    }

    /** Null when the text does not start with a word followed by its end or a step; malformed steps throw. */
    static Phrase parse(String text) {
        if (text == null) return null;
        for (var word : Word.values()) {
            var name = word.name().toLowerCase(Locale.ROOT);
            if (text.startsWith(name) && (text.length() == name.length() || text.charAt(name.length()) == '>')) {
                return new Phrase(word, ItemPath.parseSteps(text.substring(name.length())));
            }
        }
        return null;
    }

    /** Paths longer than {@link ItemPath#MAX_STEPS} throw. */
    static Resolved resolve(Phrase phrase, Situation situation) {
        return switch (phrase.word()) {
            case HELD -> situation.mainKind() == null ? new Resolved.Unresolved("Your main hand is empty")
                    : path(new PathRoot.Player(situation.player()), new PathStep(MAIN, Integer.toString(situation.selectedSlot()), situation.mainKind()), phrase);
            case OFFHAND -> situation.offhandKind() == null ? new Resolved.Unresolved("Your offhand is empty")
                    : path(new PathRoot.Player(situation.player()), new PathStep(OFFHAND, "0", situation.offhandKind()), phrase);
            case TARGETED -> situation.targeted() == null ? new Resolved.Unresolved("Nothing is under the crosshair")
                    : path(situation.targeted(), null, phrase);
            case RIDDEN -> situation.ridden() == null ? new Resolved.Unresolved("You are not riding anything")
                    : path(new PathRoot.Entity(situation.ridden()), null, phrase);
            // Opened menus become a path root of their own once menus can be read.
            case OPENED -> new Resolved.Unresolved("No menu is open");
        };
    }

    private static Resolved path(PathRoot root, PathStep first, Phrase phrase) {
        var steps = new ArrayList<PathStep>();
        if (first != null) steps.add(first);
        steps.addAll(phrase.steps());
        return new Resolved.Path(new ItemPath(root, steps));
    }
}
