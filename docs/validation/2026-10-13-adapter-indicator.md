# Adapter indicator validation

Contract: [compact chat controls](../specs/2026-10-13-compact-chat-ui.md). Implementation: [compact chat plan](../plans/2026-10-13-compact-chat-ui.md).

## Automated checks and artifact

Implementation commit: `662139d6911917c4eb643fdbd54553ba0132e723`.

`./gradlew.bat test build --console=plain` passed with 377 tests: 373 passed, four Windows symlink skips, no failures or errors. The build succeeded. The corrected sidebar regression failed against the original screen and passed with the new screen, checking that the adapter indicator, Settings and Pause have distinct actions at a narrow width.

Tests also cover immutable logical-ID availability, independence from partial extraction coverage, delegate failure/disable states, concrete FTB/JEI/Patchouli unavailable transitions, world entry/disconnection and provider-factory failure. Independent read-only review caught the concrete lifecycle flag gap; the provider overrides and existing lifecycle assertions address it. No formatting tools were used; `git diff --check` passed.

The distribution and installed ATM10 `mods/packsage.jar` were hash-checked while Minecraft was closed. SHA-256: `5748C37999DFAC5D9AF7D7D8D4173AC8820F2E528B221F4DDA111DDEF331A7F0`. Baseline build: Minecraft 1.21.1, NeoForge 21.1.202, Java 21.0.12.1. The active ATM10 profile uses NeoForge 21.1.251.

## Live checks

The installed candidate reached ATM10's 535-mod title screen and opened only the existing backed-up `OoO - PackSage Trial` world. At the profile's unchanged GUI scale 2, F8 opened the chat with three distinct footer squares: adapter information, Settings and Pause/Resume.

The adapter icon is left of Settings. Hovering/clicking the inert icon displayed a compact, unclipped tooltip with exactly six rows: Minecraft, JEI, FTB Quests, NeoForge configs, Patchouli and Chat history. All six bullets were green in this profile; the labels retained the normal text color. The list appeared before any new model/evidence query, confirming that missing captured data does not make compatible adapters red.

The shifted Settings icon opened the populated Settings page, and Back returned to the same chat. The shifted Pause/Resume button changed from Play to Pause on resuming, then back to Play when paused again. This observes the UI/requested-state transition; automated tests cover the pause action and state wiring. The trial world was saved/quit, and Minecraft was left at its title screen.

No new model request, movement, block interaction, item transfer, quest submission or reward claim was performed. The original OoO world was not opened. Missing/unsupported adapters and runtime-disable red states were covered by automated checks rather than changing ATM10's supplied mod set. Future version-specific implementations use the same logical mod IDs; actual new version implementations were not added in this UI change.
