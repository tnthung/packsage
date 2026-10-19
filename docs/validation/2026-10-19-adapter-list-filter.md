# Adapter list filter and scrolling validation

Contract: [compact chat UI](../specs/2026-10-13-compact-chat-ui.md). Work: [compact chat plan, step 7](../plans/2026-10-13-compact-chat-ui.md).

Implemented on `feat/adapter-list-filter`, based on `f6f3106`, in a separate worktree. No merge, push, installation or live Minecraft check was performed.

## Automated checks

Java 21.0.12.1 was used for Gradle. The system Java 24 runtime could not configure Gradle's test reporting; selecting the project's existing portable Java 21 resolved that environment issue.

- The focused settings and screen suite passed all 62 tests. Checks cover the default-on filter, saved false value, Settings click/layout bounds, active-only versus all-adapter rows, eight-row and short-window limits, clamped offsets, and actual wheel input over the popup and icon.
- `./gradlew.bat test build --continue --console=plain` ran 636 tests: 625 passed, seven failed and four were skipped. JAR packaging completed. The seven failures are the same `NativeBrewingReaderTest` failures observed before this feature, on the clean 634-test baseline.
- `./gradlew.bat assemble --console=plain` passed and produced `build/libs/packsage-0.1.0.jar`.
- A manual style review and `git diff --check` passed. No formatting tools were used.

The pre-existing brewing reader at the feature's base returns an empty candidate page and `null` for rereads. Its failing tests are `potionMixesKeepDistinctPotionComponentsAndOutputFilterSelectsItemType`, `containerConversionPreservesPotionComponentsAcrossItemChange`, `exactDeclarativeRecipePreservesOutputComponentsAndCount`, `opaqueRecipeIsReportedWithoutCallingExecutableMethods`, `continuationReadsTheNextRuleWithoutReplayingTheFirstPage`, `rereadingHandleTracksTheSameTransformationAfterReorderAndReturnsNullAfterRemoval`, and `outputFilteredSearchBoundsBrewingRuleTraversal`. Brewing source and tests were not changed by this feature.

Review corrections fixed reversed wheel direction, added icon wheel routing, and clipped adapter labels using the same font as rendering so wrapped labels cannot hide later entries. Filtering remains part of the nontrivial list-layout calculation.

## Acceptance status

The preceding checks describe the feature branch before the player's merge. The merged build subsequently completed 639 automated tests without failures (635 passed, four existing skips), including the repaired brewing tests; see [brewing validation](2026-10-19-brewing.md).

## Installed checks after merge

On 2026-10-19 the player installed the merged JAR in the CurseForge `Packsage` minimal profile, Minecraft 1.21.1 / NeoForge 21.1.259. Installed `mods/packsage.jar` matched the built artifact's SHA-256 `3F7D95FE728758CE1826F893776BC35564D7B6ED9664C543CDDBE884CA446E22`. Checks used the native Minecraft UI at a 1024 by 768 client area.

- With no `hideInactiveAdapters` key, Settings showed the checkbox on. The full fixture showed fourteen green logical adapters, eight rows at a time.
- Moving from the icon into the popup and scrolling over the popup reached later adapters. Scrolling over the icon also moved the popup, without scrolling the chat or session list. Further downward scrolling at the final `Chat history` row left the list clamped at its end.
- Turning the filter off visibly cleared the checkbox and saved `hideInactiveAdapters=false`. After completely closing and restarting Minecraft, Settings still showed it off.
- With Minecraft closed, Immersive Engineering was temporarily disabled through CurseForge. The inactive check used a separate copy of `New World`, in `saves/PackSage Adapter Filter Live Test`; the original save was not opened with the mod disabled. With the filter off, the ninth row was red `Immersive Engineering`. Turning the filter on removed that row: the next row after `GuideME` became green `Actually Additions`.
- At GUI scales 2 and 3 the popup displayed eight readable rows without wrapped labels hiding later entries. Scale 2 was restored. The 70-percent cap for unusually short viewports and resize-time offset clamping retain automated coverage; the attempted native-window border drag did not resize the client, so it supplies no live resize evidence.

The filter was left on, matching its initial behavior, and `hideInactiveAdapters=true` was verified on disk. Immersive Engineering was re-enabled with Minecraft closed; its enabled JAR and the full client's restored 34-mod title screen were verified before reopening the original world. No developer prompt or authentication setting was changed.

Installed rendering, active/inactive filtering, saved false across a process restart, pointer travel, independent scrolling and the tested GUI scales passed. No source change was needed. The disposable save copy remains available to identify the isolated inactive-mod fixture.
