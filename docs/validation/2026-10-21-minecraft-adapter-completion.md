# Minecraft adapter completion validation

Scope: the [specification](../specs/2026-10-21-minecraft-adapter-completion.md), its [implementation plan](../plans/2026-10-21-minecraft-adapter-completion.md) and the [player checks](../testing/2026-10-21-minecraft-adapter-completion.md). Minecraft commands and every third-party adapter are outside this scope.

## Automated acceptance

Java 21 `gradlew.bat test build` on the final head succeeded with 822 tests: 0 failures, 0 errors and 4 existing skips. The suite had 818 tests before the final-review fix wave. The installed artifact has SHA-256 `8B5E0D4B8E66841727BAB9C737207E3DA8369D20215CC0698F8F3530EBB0DCBE`, built by `scripts/build.ps1` into the CurseForge Packsage instance.

## Delivered bounds

Every new field is read fresh on each search, continuation and `read_evidence`; nothing is cached between calls. New fields never replace the existing `minCost`, `maxCost`, `components` or `mechanicsStatus` keys. A bound that cuts output makes the record `PARTIAL` and names the cut in the field's `status`. Unknown types are reported with their ID and `status: "opaque"`, never dropped.

| Bound | Limit |
|---|---|
| Enchantment levels | 32 |
| Effect components | 64 |
| Effects per component | 32 |
| Encoded effects | 16,384 bytes |
| Enchantment holders | 512 |
| Declared-modifier amplifiers | 0 to 3 |
| Potion effects | 32 |
| Reagent names | 64 |

## Review outcome

Nine parallel lanes each received per-task specification and quality reviews. The final whole-branch review found two Important issues:
- bounded potion effects did not make the record `PARTIAL`;
- mod-code calls in `createModifiers`, eligibility and the effects encoder were not contained.

Commit `b50de3f` fixed both, and a re-review accepted the fix. One Minor issue is parked: the brewing title uses an unguarded output hover name, so a throwing name degrades that record to opaque.

## Installed acceptance

Run by an agent driving the real client through computer use (GPT-6-Luna, reasoning extra high) as the player, in world "New World" on the Packsage profile (Minecraft 1.21.1, NeoForge). PackSage's own assistant model was GPT-6-Sol at medium effort, with the developer prompt override empty. The installed JAR hash matched the artifact above before launch and again in round 2. Full per-check evidence and saved-conversation paths are in the working notes `.superpowers/sdd/2026-10-21-minecraft-adapter-completion/live-results.md`.

The testing document was corrected between rounds: the Cargo setup now fits the 256-character chat limit, and the `plan_recipe` criterion moved from check 8 to the new check 8b. Check 9 opened each animal's inventory, but the assistant read it through `targeted`, so it is not an `opened`-target pass.

Round 1 ran checks 1 to 13 (8 pass, 4 fail, 1 blocked by the 256-character chat limit on the setup command). After the testing document was corrected, round 2 ran checks 8b and 13.

| Check | Observation (request ID) | Result |
|---|---|---|
| 1. Enchantment costs and mechanics | `search_registry` for Sharpness (`ef0b42be-6e87-4d54-976c-6ed4ba3fca1e`): `COMPLETE`, `levels[4]` minCost 45 / maxCost 65, `effectMechanics` damage add 3.0 at level 5, `effectsStatus="built_in_components_decoded"`. Page about 3,176 bytes. Reply gave 3 damage and the 45 to 65 range. | Pass |
| 2. Eligibility | Pickaxe turn (`d1bacab5-8c0a-4018-a45d-624665f5b355`) and sword repeat (`25811121-561e-415f-8480-885f1e8e5b20`): no `read_evidence` call, so `enchantmentEligibility` was never read. The reply listed enchantments from the registry page, and the repeat did not confirm the held sword. | Fail |
| 3. Declared modifiers | Speed (`b3bb78d0-333e-4b57-abbc-8603563b006f`): `mechanicsStatus="callbacks_not_interpreted"`, movement-speed `add_multiplied_total` amounts 0.2, 0.4, 0.6, 0.8 for amplifiers 0 to 3. Reply: Speed II is 40% faster. | Pass |
| 4. Active effects | Three fresh `active_effect` searches (`a7f36ba2-1b4c-48bf-baf3-be740a8729f0`, `195c8eed-80b9-4b3e-8e7e-15b0b56a1835`, `48fea13c-bfac-499c-a0d5-077637b21cc7`): Speed amplifier 1, `durationTicks=716`, then an empty `COMPLETE` page after expiry. The paused-world recheck was a setup issue, not a defect. | Pass |
| 5. Potion items | Three stacks (`a094dc75-54ad-4b8d-b58b-fadc88dd018e`): potion, lingering potion (`durationScale=0.25`) and tipped arrow (`durationScale=0.125`) decoded with effect, duration and amplifier. The reply scaled each correctly. No instant effect was available, so instant scaling was not exercised. | Pass |
| 6. Component-distinct variants | Alpha and Beta iron swords (`83829800-6634-4523-bf4d-dad31d1233aa`): different `componentFingerprint` values; the reply separated them and did not merge them into one stack. | Pass |
| 7. Recipe previews | Crafting table (`b6022633-1d82-4f42-9427-0b2906956499`) and stonecutter (`9c6f0fd6-62d0-4522-818b-5bbc89f6be90`): the assistant chose `target: targeted` while PackSage kept the menu open, got `UNAVAILABLE` with "target block inventory is unsupported", and never read the opened menu. | Fail |
| 8. Brewing by name | `Potion of Strength` (`9248b573-c1ee-4270-87cf-15b9825afdaa`): 24 `search_recipe` calls in about 2 min 20 s, no `plan_recipe`. Localized titles and `names` were returned and the brewing steps were correct. | Fail (plan part) |
| 8b. Brewing plan | Plan three Potions of Strength (`7a425403-74a3-4132-a1d2-3c44d1db7925`): 16 calls in 72 s, no `plan_recipe`. The reply stated "one three-bottle batch" without plan evidence. | Fail |
| 9. Donkey and mule, menu open, read via `targeted` | Donkey (`d9fee5fe-4c6e-4993-80f8-13ee8f3b460b`) and mule (`876b8d2c-ce67-4be9-9f3d-7f2ab1d8ec64`): `COMPLETE` with `saddle` and `cargo` sections (5 diamonds; 7 iron ingots). | Pass |
| 10. Targeted donkey and mule | Donkey (`1f010b67-1796-4ece-a7ae-903b821221cf`): targeted in context, but no tool call; the reply came from the screenshot. Mule (`0bf151c3-2643-486f-b67a-87e0b6ac4e7c`): `COMPLETE`, saddle and 7 iron ingots. | Fail (donkey), pass (mule) |
| 11. Ridden donkey and mule | Donkey (`128888b2-268e-40c1-987c-c63366307e78`) and mule (`09fd1aec-474d-4fa0-b1ea-b564b15e6c65`) after natural right-click mounting: `ridden` target `COMPLETE`, equipment and cargo sections. | Pass |
| 12. Native chest boat | Targeted (`5aebc435-8057-44b4-9900-e30cc9371b97`) and ridden (`9f939b19-6be1-4a7e-a272-4a39daa124f8`): `COMPLETE`, 3 diamonds and 4 emeralds. | Pass |
| 13. Mount switching | Round 1 was blocked by the chat-length limit. Round 2: first page of 5 entries with `hasMore` (`4effd82f-f099-4ede-95a0-63883552edfc`); after switching mounts, the old cursor was rejected as "storage cursor does not match this query or scope", then a fresh read identified the mule (`663db5e5-e5f1-46c1-8463-2463cb3e6a1e`). | Pass |

## Passes

Checks 1, 3, 4, 5, 6, 9, 11, 12 and 13 passed in full, and the mule case of check 10 passed.

Reader observations worth keeping:
- The enchantment search page for Sharpness was about 3,176 bytes with one matching record.
- Native brewing search progress text such as `scanned=0-512 of 6282 ... no matches in this range; continue with the cursor` was returned, and the model continued.
- Localized titles such as `Potion of Strength brewing transformation` and `names` fields were returned.
- No navigation targets were supplied for native records. This is expected, because the native adapter has no viewer navigation.

## Failures and classification

| Check | Classification | Reason |
|---|---|---|
| 2 | Assistant tool selection | No `read_evidence` call, so `enchantmentEligibility` was never read. |
| 7 | Assistant tool selection | `targeted` was chosen instead of `opened` while PackSage kept the crafting or stonecutter menu open. |
| 8 | Assistant tool selection, plus a test-design defect | The model called no `plan_recipe`. The plan criterion did not belong in a discovery check, so it was moved to 8b. The model also searched `strength`, not the localized phrase, so localized matching and empty-page progress remain unconfirmed. |
| 8b | Assistant tool selection | No `plan_recipe` call; the reply stated a three-bottle batch without plan evidence. |
| 10 (donkey) | Assistant tool selection | The model answered from the screenshot without a tool call for the targeted donkey. |

None of these is a reader defect: where the right call was made, the reader returned the expected evidence. Fixing them needs prompt and tool-description work, which this repository requires to be iterated in the in-game editor with live validation. It is tracked as a new item in the [tool roadmap](../roadmap/2026-10-13-todo.md).

## Acceptance limits and follow-up

- Accepted: enchantment per-level costs, loaded effect payloads, decoded built-in effect mechanics, declared attribute modifiers, active player effects (installed default prompt, override cleared), actual potion-item contents, targeted donkey and mule cargo and saddle fixtures, native chest-boat targeted and ridden contents, ridden donkey and mule fixtures, and the mount-switch lifecycle.
- Not accepted: applicable enchantments for an item (check 2), stored quantities excluding recipe previews (check 7), localized potion-name matching and empty-page progress (check 8), planner brewing batch presentation (check 8b) and the opened-path entity-menu checks, because the opened target was not exercised.
- Shared cross-adapter lines (result normalization preserving variants, and adapter query execution) stay open. The Minecraft part of variant preservation passed in check 6.
- The expired-handle `read_evidence` path for active effects and instant-effect scaling for potions were not directly exercised.
- Native brewing search remains slow: about 2 min 20 s with 24 calls (check 8) and 72 s with 16 calls (check 8b).
- Follow-up: prompt and tool-description work for the four tool-selection behaviours above, rerun live in the in-game editor, followed by the unparked Minor brewing-title guard.
