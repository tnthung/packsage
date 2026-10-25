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
