package dev.packsage.client.recipe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PinnedCardStateTest {
    private final PinnedCardState.Rect chip = new PinnedCardState.Rect(10, 10, 30, 20);
    private final PinnedCardState.Rect panel = new PinnedCardState.Rect(10, 25, 110, 85);
    private final PinnedCardState state = new PinnedCardState();

    @Test
    void hoverChipShowsPanel() {
        assertEquals(PinnedCardState.Visibility.HOVER, state.update(15, 15, false, chip, null));
        assertNull(state.anchor());
    }

    @Test
    void leavingChipWithoutShiftHides() {
        state.update(15, 15, false, chip, null);
        assertEquals(PinnedCardState.Visibility.HIDDEN, state.update(15, 30, false, chip, panel));
    }

    @Test
    void shiftWhileHoverPins() {
        state.update(15, 15, false, chip, null);
        assertEquals(PinnedCardState.Visibility.PINNED, state.update(15, 15, true, chip, panel));
        assertEquals(chip, state.anchor());
        assertEquals(PinnedCardState.Visibility.PINNED, state.update(50, 50, true, null, panel));
        assertEquals(chip, state.anchor());
    }

    @Test
    void releasingShiftOutsideChipAndPanelHides() {
        state.update(15, 15, true, chip, null);
        assertEquals(PinnedCardState.Visibility.HIDDEN, state.update(200, 200, false, chip, panel));
        assertNull(state.anchor());
    }

    @Test
    void releasingShiftInsidePanelKeepsHoverUntilMouseLeaves() {
        state.update(15, 15, true, chip, null);
        assertEquals(PinnedCardState.Visibility.HOVER, state.update(50, 50, false, chip, panel));
        assertNull(state.anchor());
        assertEquals(PinnedCardState.Visibility.HOVER, state.update(60, 60, false, chip, panel));
        assertEquals(PinnedCardState.Visibility.HIDDEN, state.update(200, 200, false, chip, panel));
    }

    @Test
    void shiftAloneWithoutHoverDoesNothing() {
        assertEquals(PinnedCardState.Visibility.HIDDEN, state.update(200, 200, true, chip, null));
        assertNull(state.anchor());
    }
}
