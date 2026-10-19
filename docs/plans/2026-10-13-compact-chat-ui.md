# Compact chat implementation

1. Add guarded, serialized session deletion with persistence/runtime tests; integrate sidebar confirmation and commit the deletion slice.
2. Replace JSON byte counts with a bundled tokenizer-backed text estimate; verify packaging and quota/count tests, then commit the counting slice.
3. Compact shared typography and controls, simplify Settings, revise composer geometry/icons and option hover behavior; check geometry, wrapping and interaction tests, then commit the UI slice.
4. Run the automated suite/build and inspect the installed UI when available. Record observed checks and outstanding live acceptance separately.
5. Refine toggle hover states, composer-row meter placement, readable tooltips and account label alignment. Add persisted submission timestamps and verify their lifecycle/round-trip behavior independently of the UI changes. Commit these slices separately. Keep desktop interaction stopped while intermittent black flashes remain unexplained.
6. Expose adapter enablement through the existing chat-action seam and render the approved sidebar information tooltip. Keep optional compatibility/runtime failures separate from partial extraction coverage and group version implementations under a stable logical mod identity. Verify lifecycle states and shifted Settings/Pause interactions, then record build and authorized live observations separately.
7. Add the default-on persisted inactive-adapter filter to Settings, preserving it through developer-prompt copies. Add a third Behavior row with matching layout, hover and click bounds. Bound the existing adapter popup by 70% viewport height and eight rows, with independent wheel scrolling, reachable hover bounds and clamped offsets. Check settings persistence, filtering, geometry and input routing; record pre-existing suite failures separately and leave the feature worktree unmerged for player review.

Preserve the unrelated implementation-plan edit and untracked reference images. Do not run formatting tools.
