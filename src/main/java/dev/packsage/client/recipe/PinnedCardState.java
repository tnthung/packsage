package dev.packsage.client.recipe;

public final class PinnedCardState {
    public enum Visibility { HIDDEN, HOVER, PINNED }

    public record Rect(int left, int top, int right, int bottom) { }

    private Visibility visibility = Visibility.HIDDEN;
    private Rect anchor;
    private boolean releasedInsidePanel;

    public Visibility update(double mouseX, double mouseY, boolean shiftDown, Rect chip, Rect panel) {
        boolean onChip = chip != null && mouseX >= chip.left() && mouseX < chip.right()
                && mouseY >= chip.top() && mouseY < chip.bottom();
        boolean onPanel = panel != null && mouseX >= panel.left() && mouseX < panel.right()
                && mouseY >= panel.top() && mouseY < panel.bottom();
        if (visibility == Visibility.PINNED && shiftDown) return visibility;
        if (visibility == Visibility.PINNED) releasedInsidePanel = onPanel;
        if (onChip && shiftDown) {
            anchor = chip;
            releasedInsidePanel = false;
            return visibility = Visibility.PINNED;
        }
        anchor = null;
        if (onChip || (releasedInsidePanel && onPanel)) return visibility = Visibility.HOVER;
        releasedInsidePanel = false;
        return visibility = Visibility.HIDDEN;
    }

    public Rect anchor() {
        return anchor;
    }
}
