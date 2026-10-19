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

Automated behavior and packaging checks are complete within the baseline failure limits above. Actual Minecraft rendering, hover travel into the popup, scrolling at different GUI scales and saved-setting behavior in a running client remain unverified. The branch and worktree are retained for the player's review and merge decision.
