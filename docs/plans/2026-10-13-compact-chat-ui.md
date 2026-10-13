# Compact chat implementation

1. Add guarded, serialized session deletion with persistence/runtime tests; integrate sidebar confirmation and commit the deletion slice.
2. Replace JSON byte counts with a bundled tokenizer-backed text estimate; verify packaging and quota/count tests, then commit the counting slice.
3. Compact shared typography and controls, simplify Settings, revise composer geometry/icons and option hover behavior; check geometry, wrapping and interaction tests, then commit the UI slice.
4. Run the automated suite/build and inspect the installed UI when available. Record observed checks and outstanding live acceptance separately.

Preserve the unrelated implementation-plan edit and untracked reference images. Do not run formatting tools.
