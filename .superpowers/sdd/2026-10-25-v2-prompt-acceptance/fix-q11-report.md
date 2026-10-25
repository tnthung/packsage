# Q11 advancement tab navigation fix

Status: implemented and automated checks pass on `feat/packsage-v2` in the main checkout. Live recheck remains pending; the installed game and `build/prompt-candidates/` were not touched.

## Root cause

`ProgressNavigation.targetId(ProgressHandle)` previously excluded only hidden entries. Progress status describes completion and prerequisites, so an `available` root could receive a target even when vanilla had not sent its tab to the client. `ProgressTools` also created tree summary targets from a synthetic available handle without checking tab visibility.

Verified against `build/moddev/artifacts/neoforge-21.1.202-sources.jar`:

- `net/minecraft/server/PlayerAdvancements.java:56,253-280,308-327`: the server owns a `visible` set; `updateTreeVisibility` uses vanilla's evaluator to maintain it, and `flushDirty` sends additions/removals and visible progress.
- `net/minecraft/server/advancements/AdvancementVisibilityEvaluator.java:14-70`: visibility depends on completed descendants and nearby completed ancestors, with hidden/displayless rules; being available does not establish visibility.
- `net/minecraft/client/multiplayer/ClientAdvancements.java:41-48`: the client removes and adds advancements from the packet rather than loading the server's whole tree.

The original click-time check correctly refused the missing tab. The defect was offering that target while building evidence.

## Fix

- `src/main/java/dev/packsage/world/minecraft/MinecraftProgress.java:79`: read `PlayerAdvancements.visible` directly on the server thread through an access transformer, without mutating the set, evaluating/flushing dirty progress or sending packets. A removed or inaccessible field fails compilation rather than guessing linkability.
- Copy root-tab visibility into `ProgressTree` and `ProgressHandle`, including inspected prerequisites and unlocks. The snapshot samples it once per tree and preserves the existing sliced traversal.
- `src/main/java/dev/packsage/world/ProgressNavigation.java:51` and `ProgressTools.java:196`: omit both tree and entry targets when their tab is absent. Entries remain readable. Hidden entries still never receive targets, even with a visible tab. A non-visible entry in a visible tab can still link to that tab.
- `open()`, current-server-tree resolution, stable target IDs and client-thread dispatch are unchanged.
- Updated `CHANGELOG.md` and both required roadmaps; acceptance checkboxes remain unchecked.

## Tests

All commands ran in Git Bash with `JAVA_HOME="D:/Project/packsage/.tools/jdk/jdk-21.0.12.1"` (Java 21).

- RED: `./gradlew test --tests dev.packsage.world.ProgressToolsTest -q` failed with 15 tests, 1 failure. `availableProgressWithoutAVisibleTabHasNoLinks` caught the Adventure tree target: expected empty targets, received a target.
- GREEN: the same Gradle test task filtered to `ProgressToolsTest`, `ProgressNavigationTest`, `ProgressListingTest`, `ProgressTypesTest` and `minecraft.MinecraftProgressTest` passed all 39 tests. Coverage includes absent-tab list/inspect records, visible targets retained, hidden targets suppressed, and deep entries linked through a visible root. The adapter fixture derives visibility using vanilla's evaluator.
- `./gradlew build -q`: exit 0; 1,021 tests, 1,017 passed, 4 skipped, zero failures/errors (baseline 1,019; two tests added).
- Manual style review and `git diff --check` passed. No formatting tools, push or merge.

## Commits

- `af62287` — `[Add] reproduce advancement links to an unavailable tab`
- `f5904de` — `[Fix] omit advancement links when the player cannot open their tab`
- `c62566e` — `[Chg] record Q11 navigation fix and pending live acceptance`

This report is committed separately. No installed/live behavior is claimed from the automated checks.

## Follow-up

Replaced per-read reflection with direct access using `public net.minecraft.server.PlayerAdvancements visible` in `src/main/resources/META-INF/accesstransformer.cfg`, following the existing `Raids.raidMap` entry. Corrected the source reference above to line 79. Gradle picked up the transformer without a build configuration change and recompiled the transformed Minecraft sources and the direct field read.

Added `MinecraftProgressTest.vanillaTabVisibilityFieldIsPublicAndAvailable`, which requires the public `visible` field to exist with type `Set`. Before the transformer, the focused test failed with `NoSuchFieldException: visible`. After the change, the requested Git Bash Java 21 `./gradlew build -q` exited 0: 1,022 tests, 1,018 passed, 4 skipped, zero failures/errors. All 8 `MinecraftProgressTest` cases passed. Manual style review and `git diff --check` passed; live Q11 acceptance remains pending.
