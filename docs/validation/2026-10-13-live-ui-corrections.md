# Live UI correction validation

Date: 2026-10-13

Source: `65a7c3eaad5eba26e52e49bf79b5015c95c9344e`. Requirements: [live feedback](../specs/2026-10-13-live-ui-corrections.md). Player checks: [Minecraft checks](../testing/2026-10-13-live-ui-corrections.md).

## Findings and changes

The blank settings body came from calling the pinned native `AbstractWidget.setRectangle(width, height, x, y)` method with the wrong argument order. Correct editor geometry now updates before rendering/hit testing; one page scrollbar supports dragging. The existing Back placement is retained.

Chat uses native logical GUI dimensions, a 28-pixel initial composer aligned with Send, focus clearing on outside/control clicks, a globe for web search, a magnifier in session search and hover for gear/pause. Submitted input clears, immediate Stop remains, and activity appears beside the active assistant response. Pause uses the live requested state rather than a delayed screen snapshot.

Message layout wraps words with long-token fallback, uses 13-pixel rows and contained inline chips, and renders quote metadata. A pinned real Inter SemiBold face replaces synthetic bold. The translucent surfaces use non-overlapping interior fills; world blur runs before UI content.

Config citations are separate read-only source controls. Their panel shows recorded metadata and matches persisted tool payloads by provider, handle, timestamp, generation and scope. Repeated config handles use the last recorded source. Opening the panel does not access live config files or change world/scope/config state. Back returns the same chat and preserves its pause policy. Legacy gameplay links without stored targets remain unchanged as authorized.

## Automated evidence

- Focused client suite: 71 tests passed; the final repeated-source parser assertion also passed its targeted rerun.
- Independent bounded reviews found no material defects in chat/settings/editor behavior or the config source binding/viewer.
- A fresh `git archive` export of the committed source passed `scripts/build.ps1` (`test build`). The 29 suites report 304 tests: 302 passed, no failures or errors, two skipped Windows symlink checks.
- `git diff --check` and staged checks passed. No formatting tools were used.
- JAR inspection confirmed both font assets, the SemiBold definition, all 16 icons and the source viewer class. The SemiBold TTF header/hash match the pinned asset record.

## Installed artifact and acceptance

Minecraft was closed before installation. The exported build artifact, repository `dist/packsage.jar` and installed `C:\Users\hung1\curseforge\minecraft\Instances\Packsage\mods\packsage.jar` all have SHA-256 `AAE145E19C660AD3B2B54EA974167445A7109BFF995B9100B5D49913FEC697A5`. Repository build metadata was copied from that verified build.

Compilation, interaction/state checks, packaging and installation are complete. Actual GUI-scale rendering, glass/font appearance, settings visibility and game pause/navigation acceptance remain pending the linked Minecraft checks. Automated checks are not live visual proof.
