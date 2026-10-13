# UI alignment validation

Date: 2026-10-13

Source: `002d605da013cfec3ffac6a02d9c40e16591a052`. Requirements: [alignment follow-up](../specs/2026-10-13-live-ui-corrections.md). Plan: [alignment corrections](../plans/2026-10-13-ui-alignment.md). Player checks: [Minecraft visual checks](../testing/2026-10-13-ui-alignment.md).

## Changes and measurement

Both bundled font definitions now shift down one logical pixel. Message bubbles, editor rows, sidebar rows, inline chips and page headers use consistent centered line geometry. The editor's pointer-to-row calculation uses the same origin as rendering. Search/Screenshot icons shrink from 16 to 13 logical pixels, about 81% of their prior size.

The account card shares the model rows' inset bounds, leaving 16 logical pixels before the page's right edge. Its height decreases from 80 to 56 pixels in the wide layout and from 114 to 90 in the narrow layout. Provider icon, status block and account action are centered within their respective groups. Radio controls use smooth 64-pixel assets drawn at 14 logical pixels, with linear filtering and a filled selected dot.

A native measurement used the pinned LWJGL 3.3.3 FreeType path, bundled Inter fonts, size 11 and oversample 2. Minecraft's fixed line box is nine pixels. Before the shift, Search/Screenshot glyph centers were three pixels below the draw origin in both faces; afterward they were four. Combined with the boxed-label formula, their measured center is 10 pixels in a 20-pixel control and 14 in a 28-pixel control. This confirms glyph geometry, not a rendered Minecraft frame.

## Automated evidence

- Focused client suite: 72 tests passed, no failures, errors or skips.
- A fresh `git archive` export of committed source passed `scripts/build.ps1` (`test build`): 305 tests, 303 passed, no failures or errors, two skipped Windows symlink checks.
- The bounded independent review found no material regression in editor geometry, settings layout, hit testing or the existing source viewer behavior.
- `git diff --check` passed. No formatting tools were used.
- JAR inspection confirmed both one-pixel font shifts, all 18 icons and both radio filtering metadata files.

## Installed artifact and acceptance

Minecraft was closed before installation. The exported artifact, repository `dist/packsage.jar` and installed `C:\Users\hung1\curseforge\minecraft\Instances\Packsage\mods\packsage.jar` match SHA-256 `4EFDA0D7C5E470013E1FA9E4F98522FC87FEC2F25022842861FB71B102A37F37`. Repository build metadata was copied from the verified build.

Automated checks, packaging and installation are complete. Actual centering, radio appearance and account layout at different GUI scales remain pending the linked Minecraft visual checks.
