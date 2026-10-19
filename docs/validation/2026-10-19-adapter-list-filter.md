# Adapter list filter and scrolling validation

Contract: [compact chat UI](../specs/2026-10-13-compact-chat-ui.md). Work: [compact chat plan, step 7](../plans/2026-10-13-compact-chat-ui.md).

Implemented on `feat/adapter-list-filter`, based on `f6f3106`, in a separate worktree. The assistant performed no merge, push, installation or live Minecraft check.

## Automated checks

Java 21.0.12.1 was used for Gradle. The system Java 24 runtime could not configure Gradle's test reporting; selecting the project's existing portable Java 21 resolved that environment issue.

- All 63 settings and screen tests pass: seven settings tests, nine settings-screen tests and 47 assistant-screen tests. Checks cover the default-on filter, saved false value, Settings click/layout bounds, active-only versus all-adapter rows, eight-row and short-window limits, clamped offsets, actual wheel input over the popup and icon, stable full-list width and overflow scrollbar geometry at the first, middle and last scroll positions.
- `./gradlew.bat test build --continue --console=plain` ran 637 tests: 626 passed, seven failed and four were skipped. JAR packaging completed. The seven failures are the same `NativeBrewingReaderTest` failures observed before this feature, on the clean 634-test baseline.
- `./gradlew.bat assemble --console=plain` passed and produced `build/libs/packsage-0.1.0.jar`.
- A manual style review and `git diff --check` passed. No formatting tools were used.

The pre-existing brewing reader at the feature's base returns an empty candidate page and `null` for rereads. Its failing tests are `potionMixesKeepDistinctPotionComponentsAndOutputFilterSelectsItemType`, `containerConversionPreservesPotionComponentsAcrossItemChange`, `exactDeclarativeRecipePreservesOutputComponentsAndCount`, `opaqueRecipeIsReportedWithoutCallingExecutableMethods`, `continuationReadsTheNextRuleWithoutReplayingTheFirstPage`, `rereadingHandleTracksTheSameTransformationAfterReorderAndReturnsNullAfterRemoval`, and `outputFilteredSearchBoundsBrewingRuleTraversal`. Brewing source and tests were not changed by this feature.

Review corrections fixed reversed wheel direction, added icon wheel routing, and clipped adapter labels using the same font as rendering so wrapped labels cannot hide later entries. Filtering remains part of the nontrivial list-layout calculation.

The player tested the initial candidate and reported changing popup width while scrolling and no visible scrollbar. The follow-up measures every filtered adapter label before selecting the viewport rows, reserves scrollbar space and renders an overflow track with a proportional position thumb. Sparse lists keep their natural height. Automated follow-up checks pass; the corrected appearance awaits player rechecking.

## Acceptance status

Automated behavior and packaging checks are complete within the baseline failure limits above. The player's two initial visual findings are recorded above; live acceptance of the corrected width and scrollbar, hover travel, other GUI scales and saved-setting behavior remains pending. The branch and worktree are retained for the player's review and merge decision.
