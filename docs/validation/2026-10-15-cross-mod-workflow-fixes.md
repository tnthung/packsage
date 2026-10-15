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

First revised built/distributed/installed SHA-256: `6C63F9BBDBAD4037A47A726E0B4F4C22E3BFA235AA07FF4C69CEB7E6DCCFC406`. All three hashes matched after copying with Minecraft closed. The interim `DA4F2043...` artifact was superseded before entering the trial world and has no acceptance claim.

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

- `27fc747`, `2affdc4`: a regression reproduced missing arithmetic support, then serialization gained deterministic totals for unambiguous consumed input slots. Choices, unknown quantities and unsupported consumption/types omit the tally; component variants remain separate. Totals preserve partial source/decoding status and do not establish full recipe coverage.

The final automated suite/build passed: **487 tests, zero failures/errors, four skips**. Build, distribution and installed SHA-256 all match `27BF53DED7601529CF3D7033D89E07754B1016C3CF59586EC26AACF83D7C807F`; installation occurred with Minecraft closed.

Independent review accepted the observed seek fix and conservative tally semantics. It also identified an untested extreme-source limit: a booklet position beyond the per-call page/value traversal capacity can repeatedly return a partial cursor at the same position, because each call must seek from the beginning. The observed short AA entry does not reach that condition; do not claim all arbitrarily long booklet continuations advance. This remains a separate bounded-reader limitation.

### Installed retests: `27BF53DE...`

- **Carried-container crafting passed**: request `2ae47530-73b1-48ae-8751-c92680eeefbd`, conversation `3f389b48-4ea8-485d-8d1a-d6cd8bcdcbcd`. It inspected both bundles and the shulker by current inventory references, retrieved JEI input totals of four iron/four redstone/one casing, and correctly said one iron was missing. The answer retained the recipe's partial-decoding qualification. AA discovery returned eight records and a continuation under the exhausted seek instead of failing with an invalid cursor; the UI displayed `[guide]`.
- **Fresh follow-up passed**: request `ced6aef1-bdce-41f0-b8e1-5a146e23caa4` in the same conversation. Added one iron to the test bundle using an authorized trial-world command, then asked only “I changed what is in the bundle. Can I make it now?” It reread the player, all three containers and the JEI recipe, and correctly combined three loose iron with one bundled iron. No crafting was executed. The test bundle now contains one iron, four redstone and one casing; original carried containers remain unchanged and hotbar selection was restored to slot 9.
- **Guide discovery/citation rendering passed; equipment claims still failed**: request `d99d28f9-26d1-4e76-b809-6817b7b69b2d`, conversation `455838a0-7fea-4a07-81c8-03115428a83e`. Both guide sources were retrieved and rendered as `[guide]`. The answer still treated the documented fall-speed limit as general damage prevention. More concretely, `sections:["accessories"]` returned COMPLETE empty native inventory evidence, which the answer described as an empty checked accessory section. This exposed a reader-boundary bug, not merely missing prompt wording.
- **Broad cross-mod setup remains incomplete**: request `bc547b7b-40af-4f56-95c4-942ff187faf5`, conversation `33162148-42de-4095-b261-968c51483635`. AA returned partial text and a continuation correctly. The model changed queries while reusing cursors, guessed an unknown crystal ID and an inventory cursor, and did not obtain the requested Powah setup or quest progress. The answer acknowledged its gaps. This is not complete workflow acceptance despite the narrower crafting passes.

`28fa582` reproduced the unsupported-section false completeness. `6c970b4` fixes it within the native reader: unsupported-only sections return UNAVAILABLE/UNKNOWN, mixed requests retain known native matches as PARTIAL, and detail explicitly says unsupported sections were not inspected. The schema remains extensible for other adapters.
