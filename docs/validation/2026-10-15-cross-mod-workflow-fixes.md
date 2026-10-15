# Cross-mod workflow fix validation

Follow-up to [the initial workflow report](2026-10-15-cross-mod-workflows.md) and [correction plan](../plans/2026-10-15-cross-mod-workflow-fixes.md).

## Changes and automated evidence

- `5965b53`, `072d489`, `b4363b4`: revised grounding instructions and aligned assertions. Search instructions distinguish one literal phrase, complete misses and partial continuations; separate cursors from record handles; prefer exact-output recipe discovery without accidental text restrictions; require per-slot recipe counts and current inventory/recipe checks; inspect each requested carried container; limit equipment claims to observed sections.
- `f0a49e2`: regression first failed because the Patchouli 2 KiB reserve check did not mark the shared byte budget exhausted. The same test confirms a textless crafting page normally advances to following text.
- `bce0578`: marks the insufficient reserve as exhaustion and preserves underlying `Toolbox.Failure` through the AA reflection boundary. Existing provider logic can then issue a continuation for a fresh call. This corrects the initial report's speculative textless-page diagnosis.
- `f6d9d04`: Relics and the verified Reliquified Artifacts path now include the current localized item name in document titles, preserving stable IDs. Previously a query for an item name could not match ability-only titles or descriptions that omitted the item name.
- `108f7f4` and subsequent step commits catch up the changelog and record changes as written.

`gradlew test build` passed after the final source change: **482 tests, zero failures, zero errors, four skips**. The initial prompt test failure was stale assertion wording and was corrected separately. Existing Gradle loopback protocol warnings did not prevent task completion. No formatter was run.

Independent review found no release blocker in the reserve fix, prompt recovery order or localized titles. One bounded limitation remains: a Relics evidence read whose title byte reservation fails can still return its capped title and attempt one fragment lookup; the text remains empty and explicitly partial. This is not complete content evidence.

Final built/distributed/installed SHA-256: `6C63F9BBDBAD4037A47A726E0B4F4C22E3BFA235AA07FF4C69CEB7E6DCCFC406`. All three hashes matched after copying with Minecraft closed. The interim `DA4F2043...` artifact was superseded before entering the trial world and has no acceptance claim.

## Live acceptance

Pending natural-player retests in `OoO - PackSage Trial`, with fresh chats, GPT-6-Luna medium and existing search/screenshot settings. Do not prescribe tool names, arguments, providers, cursors or references in acceptance questions. Inspect actual saved call/result pairs and answer claims. Record pass, partial and failed results separately.
