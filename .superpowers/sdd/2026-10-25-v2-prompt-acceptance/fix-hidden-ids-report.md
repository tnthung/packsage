# P5 hidden advancement identifier fix

Status: implemented and committed in the main `D:/Project/packsage` checkout on `feat/packsage-v2`. Automated checks pass; live P5 reacceptance remains pending. No game, instance files, other worktrees or `build/prompt-candidates/` were accessed or modified. Nothing was installed, pushed or merged; no formatting tools were used.

## Root cause and fix

The progress-statistics ruling retained real ids for unearned hidden advancements. `MinecraftProgress.handle` redacted the title but retained the advancement id, which `ProgressTools` copied into payload ids, evidence source handles, requirements and unlocks. The real path itself disclosed the advancement even with no description or criteria. Hidden roots could also disclose their id and title through tree summaries and navigation targets.

`MinecraftProgress` now assigns each unearned hidden advancement a random `hidden:<UUID>` local id. A weakly keyed per-server-session map retains the association without retaining departed servers. All access stays on the server thread. The session key is independent of the advancement-manager generation, so aliases survive reloads while stale-holder slice checks remain intact.

- Listings, inspected handles, requirements and unlocks use the same opaque identity. Existing `ProgressTools` record/payload construction and offset/signature cursors propagate it without an additional mapping layer.
- Inspecting an opaque id resolves the original advancement internally and returns the existing `Progress.hidden` result while unearned. Saved `progress|minecraft/hidden:...` evidence handles reread through that same path.
- A hidden root has an opaque tree id and the generic title `Hidden progress tree`. Its tab is not offered as a navigation destination, including through visible descendants. Done/total counts are unchanged.
- Once earned, new listing and inspection results expose the real id, title and ordinary details. Previously issued opaque ids still resolve during the same server session. Opaque ids are session references and do not promise persistence after server restart; earned advancement navigation continues to use provider-owned real identities.
- The `ProgressHandle` contract, specification, plan, changelog and required roadmaps document the corrected behavior. The original context ruling is superseded by this P5 task. Tool descriptions were left unchanged because neither describes hidden ids.

## Regression coverage and verification

Commands ran in Git Bash from the main checkout, with `export JAVA_HOME="D:/Project/packsage/.tools/jdk/jdk-21.0.12.1"` (Java 21).

1. Test-first commit `04c8e3a` preceded production changes. `./gradlew test --tests dev.packsage.world.minecraft.MinecraftProgressTest --tests dev.packsage.world.ProgressToolsTest -q` ran 26 tests with four expected assertion failures in `MinecraftProgressTest`: ordinary listing retained the real hidden id; hidden inspection/listing lacked an opaque id; a hidden root exposed its namespace/path; and world-session aliases were absent. There were no fixture or compilation failures.
2. After the fix, the same focused command passed all 26 tests (10 reader, 16 tool), with no failures, errors or skips. Reader fixtures exercise actual advancement trees/progress, partial hidden criteria, visible-child hidden parents, root redaction, distinct ids, reload stability, world isolation, opaque inspection, earning and rereading the old opaque identity. Tool fixtures exercise paging, unchanged counts, ids in requires/unlocks, saved evidence rereads, generic record titles, absent hidden targets, and substring checks across serialized results.
3. `./gradlew build -q` passed after the fix and again after the final single-use-expression cleanup: exit 0. Final JUnit XML totals: **1,065 tests, 1,061 passed, 4 skipped, 0 failures, 0 errors** (baseline 1,062 tests; three added).
4. Manual style/scope review and `git diff --check` passed. The live acceptance artifact remains unchanged and P5 stays failed until a separate installed recheck establishes the corrected reply.

## Commits

- `04c8e3a` — `[Add] cover opaque hidden advancement references`
- `18e8983` — `[Fix] hide unearned advancement identities with world session aliases`
- `7d2f16b` — `[Chg] inline single-use hidden id expressions`
- `56e9cf7` — `[Chg] record opaque hidden advancement contract and pending acceptance`

The contract/checklist update and this report are separate documentation commits. Every message was generated from its staged index with the `commit-message` skill; commits include only this task's files and contain no attribution trailers.

## Fix round 1

Status: all four review findings addressed on 2026-10-26 in the main `D:/Project/packsage` checkout, branch `feat/packsage-v2`, after baseline `6733f13`. Automated verification passes. Live P5 reacceptance remains pending; the game, instance files, other worktrees and `build/prompt-candidates/` were not accessed or modified. Nothing was installed, pushed or merged, and no formatting tools were used.

### Changes

1. **Reject real-id probes while hidden and unearned.** `MinecraftProgress.inspect` now returns null for a real-id lookup before creating a handle or resolving a tree alias. Only an existing world-session alias can resolve such an entry. `ProgressTools` therefore produces the same `INVALID_ARGUMENTS` not-found response shape and message template as for a nonexistent id. Earned entries still accept their real ids, and earlier aliases still resolve within the session. The former test expecting a redacted record from a real-id probe now asserts not-found.
2. **Hide real-id ordering.** Visible roots and siblings retain real-id order, followed by unearned hidden entries sorted by opaque alias. Snapshot traversal and inspected unlocks share this ordering. Tests cover two hidden siblings, stable repeated reads, an already-earned hidden sibling, and the return to visible ordering on earning. The Progress contract, implementation plan, changelog and both required roadmaps document the behavior without marking live acceptance complete.
3. **Exercise the real reader through the real tools.** The added integration test loads a real advancement tree containing `minecraft:nether/all_effects`, feeds actual `MinecraftProgress` reads into `ProgressTools`, and serializes whole results from two listing pages, alias inspection, visible-parent and visible-child inspection, and a saved-evidence reread. It asserts `all_effects` is absent everywhere in those results, including records, source labels, handles and cursors. It also compares real-id and nonexistent-id inspection against the same not-found template. The fake-reader substring checks were removed; its separate paging and payload assertions remain.
4. **Reuse progress and reverse aliases.** Each read caches advancement progress by holder, passes the already-read value to `id`, and resolves aliases through a reverse map instead of a linear scan. The no-mutation test additionally asserts exactly three underlying progress lookups for its three displayed nodes, both for a snapshot and a child inspection that also reads its root and hidden child. Alias state remains weakly keyed by world session; per-read caches do not survive their read.

### Test-first evidence and final verification

All commands ran in Git Bash from the main checkout with `export JAVA_HOME="D:/Project/packsage/.tools/jdk/jdk-21.0.12.1"`.

- The first test-only commit preceded production changes. Its initial compile exposed a fixture reference to package-private `ProgressNavigation`; a separate test-only correction used reflection for the constructor signature without changing production visibility.
- `./gradlew test --tests dev.packsage.world.minecraft.MinecraftProgressTest --tests dev.packsage.world.ProgressToolsTest -q` then ran 28 tests with four expected assertion failures: real-id inspection returned a hidden record, hidden siblings occupied real-id positions in two tests, and the tool accepted the real-id probe instead of failing.
- After adding lookup-count assertions, `./gradlew test --tests dev.packsage.world.minecraft.MinecraftProgressTest -q` ran 12 tests with five expected failures. The additional failure was four underlying progress lookups where three were required. No fixture or compilation errors remained.
- After the production fix, only the integration test's expected error text failed because it omitted `Toolbox.Failure`'s standard `INVALID_ARGUMENTS:` prefix. That assertion was corrected in a separate test-only commit. The focused two-class command then passed all 28 tests with no failures, errors or skips.
- After the final single-use-expression cleanup, `./gradlew build -q` passed with exit 0. JUnit XML totals: **1,067 tests, 1,063 passed, 4 skipped, 0 failures, 0 errors**; two tests added to the 1,065-test baseline.
- Manual review of every changed file found no out-of-scope changes. `git diff 6733f13 --check` and `git diff --check` passed. No new or changed documentation links required path updates.

### Commits

- `ab8ce43` — `[Add] cover hidden advancement lookup and sibling ordering leaks`
- `d0af641` — `[Fix] keep progress tool integration fixture within package access boundaries`
- `f91e597` — `[Add] assert each advancement progress is read once per request`
- `ad63be4` — `[Fix] reject hidden real ids and order hidden siblings by session alias`
- `fc8bdf6` — `[Fix] include failure code prefix in hidden lookup regression assertion`
- `1160dbf` — `[Chg] document alias-only hidden inspection and sibling ordering`
- `061e0e5` — `[Chg] inline single-use progress integration fixture expressions`

This Fix round 1 report is committed separately. Each commit message was generated from its staged index with the `commit-message` skill. Only task-owned files were committed, with no attribution trailers.
