# UI alignment correction plan

Date: 2026-10-13

Follow the [alignment requirements](../specs/2026-10-13-live-ui-corrections.md). Keep this edit to typography and presentation; preserve unrelated edits/sketches and never run formatting tools.

1. Measure the bundled fonts with the pinned native FreeType settings and confirm the draw-Y/visible-glyph contract. Apply one shared font shift, center multiline message/editor blocks from their line box, and use the same centering formula for boxed labels. Shrink Search/Screenshot icons to 13 logical pixels.
2. Replace raster-step radio drawing with antialiased assets. Compact and inset the account card, align its icon/text/action, and keep render/click geometry identical.
3. Run focused editor/settings checks, commit functional changes separately using staged-index commit messages, then build/install committed source. Record automated/artifact evidence and focused Minecraft visual checks without claiming live rendering proof.
