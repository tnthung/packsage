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

Retests use `OoO - PackSage Trial`, fresh chats, GPT-6-Luna medium, screenshots enabled and explicit-only web search. Questions prescribe no tool names, arguments, providers, cursors or references. Saved call/result pairs were checked against answer claims.

### First revised build: `6C63F9BB...`

- Fall comparison, request `0586a7b3-22db-4eab-b259-31e6dee7514a`, conversation `171950b7-dcc3-491e-8027-ae7f4af099d4`: **partial**. Both item-name guide searches returned complete documentation, and the answer correctly distinguished the umbrella's held fall-speed limit from the mantle's leaf-landing protection. It still overstated absence of an equipped mantle from vanilla slots, which do not cover Curios. Grouped guide citations appeared as raw private-use markup. These findings led to explicit unknown-accessory wording and the citation parser fix.
- Cross-mod construction, request `933bd0c9-3134-4835-91c4-6031a1d0325f`, conversation `7a851b80-4539-4211-a38a-0a97ab466a65`: **incomplete**. AA crystal documentation was retrieved, but some AA searches failed while seeking later internal fragments after their shared byte budget ran low. Regression isolated this second path: an exhausted seek incorrectly reported `INVALID_CURSOR`. The model also changed queries/limits while reusing cursors and guessed a progress handle. It did not establish the requested recipe, inventory gap or quest progress. Method-specific tool descriptions were added; they do not guarantee model compliance.
- Carried-container crafting, request `23eaf384-d766-48c3-af81-4cbaa83f4e60`, conversation `a8269dd8-ab32-4cf3-9cb0-7d0e6ee6e826`: **failed**. It read the original bundle and shulker by slot reference and recovered from a mistyped reference, but omitted the second bundle in the hotbar. The JEI input slots specify four iron, four redstone and one casing; the answer incorrectly said five iron and therefore overstated missing materials. Current source reads worked; answer completeness and arithmetic did not.

All three are natural-player tests, not tool-coached diagnostics. No physical crafting, power transfer or fall-damage experiment was performed.

### Follow-up fixes

- `67de832`: require explicit uncertainty for unobserved accessory slots and distinguish documented effects from inferred outcomes.
- `5e6bfef`, `dc66eae`: citation regression reproduced the raw grouped markup, then all six guide/manual providers gained compact tags without invented navigation targets.
- `208eabc`: operation-specific tool descriptions reinforce literal searches, valid continuations, exact-output recipe lookup, nested-container access and observed reference requirements.
- `2306c96`, `2aa4476`: later-page regression reproduced `INVALID_CURSOR` under byte exhaustion; exhausted seeks now preserve resumability while an ample-budget out-of-range page remains invalid.

The full automated suite/build passed after these follow-up fixes. Final installed-build acceptance is recorded separately below when run.
