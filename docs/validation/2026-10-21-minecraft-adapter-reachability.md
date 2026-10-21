# Minecraft adapter reachability validation

Scope: the [specification](../specs/2026-10-21-minecraft-adapter-reachability.md), its [implementation plan](../plans/2026-10-21-minecraft-adapter-reachability.md) and the [player checks](../testing/2026-10-21-minecraft-adapter-reachability.md). This record re-tests the failed checks 2, 7, 8, 8b and 10 of the [completion validation record](2026-10-21-minecraft-adapter-completion.md) and adds checks 14, 15 and 16. Minecraft commands and every third-party adapter are outside this scope.

## Delivered

- The `item` target returns the selected item's own record (section `item`, with `enchantmentEligibility`) before any contents. A non-container item reports storage type and contents status `none`.
- Each `enchantmentEligibility` entry carries `exclusiveWith`: the supported enchantment IDs it conflicts with, capped at 32, with a bounded status.
- The gameplay context reports `openedMenu` (with an optional plain title of at most 64 characters), and a targeted-unsupported result points to `target: opened`.
- A `player_attribute` selector reports base, value and modifiers (`id`, `amount`, `operation`, sorted, at most 64) and whether the attribute is syncable. Non-finite numbers are reported as `non_finite`, and one bad attribute cannot fail a page.
- Effect records carry `description` and `descriptionSource` from `<descriptionId>.description` language keys; otherwise `descriptionStatus` is `not_provided`, `bounded` or `unknown`.
- Native shaped recipes report `shape=WxH` and `slot-index=row*width+column` constraints plus a payload `shape`; shapeless recipes report `shape=shapeless`.
- Tool descriptions were updated, and a new default system prompt (14,669 characters) was iterated live as candidates 1 to 3, then promoted (`dca295a`). The specification was updated for the rulings below (`ee7de39`).

## Race fix

During the first candidate round, the first send after joining a world failed with "Cannot send without an active conversation" and no request was made. The cause was `AssistantRuntime.invalidate` cancelling the pending world conversation selection. It predates this work and comes from the parallel-chat change. Commit `26036cc` added the failing test and `e8891d6` fixed it, so evidence invalidation no longer cancels the pending selection. The error did not recur in the candidate-3, installed-default or recheck rounds. Those rounds exercised only fresh-chat and same-chat sends, so they show the exercised paths, not every join sequence.

## Automated evidence

The final `gradlew test build` on head `860e4c1` succeeded with 851 tests: 0 failures, 0 errors and 4 existing skips. The installed final artifact has SHA-256 `05155D8FD688E7E72B3952A2C4B52BA8233B5DFC35B14339CD0065470AB49CD8`.

## Review outcome

Each task received a specification and a quality review. The whole-branch review found two defects, both fixed:
- non-finite attribute and modifier numbers (fixed in `d7b71f7`, reported as `non_finite`);
- a non-container selected item that did not report `none` for storage type and contents status (fixed in `9ec9759`).

Scoped re-reviews approved both fixes. The later exclusivity and shape commits (`5957336`, `74d0b3a`) were followed by a guard fix (`860e4c1`): shape reads are guarded against throwing recipes, and bounded exclusivity makes eligibility status bounded.

## Live rounds

All live runs used a computer-use tester agent acting as the player, in world "New World" on the Packsage profile (Minecraft 1.21.1, NeoForge). PackSage's own assistant model was GPT-6-Sol at medium effort in every round, with the effort left unchanged. The evidence files are in the working notes `.superpowers/sdd/2026-10-21-minecraft-adapter-reachability/`.

### Candidates (developer prompt override)

Candidates are recorded separately from the installed default; nothing here is installed-default acceptance.

| Round | JAR SHA-256 (prefix) | Outcome |
|---|---|---|
| Candidate 1 | `E70FACEA` | Blocked: the tester had no desktop control, so the candidate was never applied and no request was made. |
| Candidate 2 | `AEF579CD` | Partial: the round ended after the crafting-menu part of check 7 (stonecutter and check 10 were not run). |
| Candidate 3 | `0EAEBD5E` | Ran 8b, 16, 7, 10, 2 and the piston regression. |

Candidate 2 results:

| Check | Request ID | Result |
|---|---|---|
| 2 pickaxe | `70606ac6-d3c8-464f-9f2a-0659e63c6e66` | Pass |
| 2 sword repeat | `7934b778-f65d-4745-954b-6f3c57ad39ff` | Fail: the reply declined to answer for the held sword |
| 4 active effects | `6517dc0a-da1d-4155-88da-f113a254d776`, then `b6641316-c4df-4076-8d15-82a724b4d592` after expiry | Pass |
| 8 brewing by name | `a13eadf4-712f-474b-94c1-f5a11a5bb0a9` | Pass (4 calls, 2 recipe searches) |
| 8b brewing plan | `e9971700-84bd-4709-a82a-5d77eaf43fcb` | Fail: fixed three-bottle grouping stated without the up-to-three and one-reagent-per-operation semantics |
| Piston regression | `dc0d6408-8dd5-41c7-b6f6-043e54bfc507` | Pass for ingredients; grid positions were not available |
| 16 held sword | `0e19e818-767b-4420-a419-450bdb427966` | Pass |
| 16 empty hand | `306f1fc0-6d17-41b2-92cc-f49d47e87702` | Fail: an unselected sword was substituted and the empty hand was never stated |
| 14 Luck | `bea5cd21-4cf9-4772-a818-cd13fef2b575`, then `2f66bb2d-cc59-44c3-918b-2e328d0f2fc5` after clearing the effect | Pass |
| 15 effect description | `4cc5317a-97db-4171-a572-b997f67298ea` | Pass (description-absent case) |
| 7 crafting menu | `f0bb4c07-afcb-4f44-bdcb-d874641ca41c` | Pass |

Candidate 2 found two issues that candidate 3 addressed: the empty-hand substitution and the unqualified three-bottle grouping.

Candidate 3 results:

| Check | Request ID | Result |
|---|---|---|
| 8b brewing plan | `e25ec69b-b4fd-4260-99f8-d9d90126256f` | Pass: two operations stated under a stated three-bottle grouping |
| 16 held sword | `1f605cfc-a35d-4b62-aff9-2f2758e272e0` | Pass |
| 16 empty hand | `fb82e189-ad40-4a63-80f5-1568e9814bf8` | Failed the strict wording criterion; ruled a pass (see Rulings) |
| 7 crafting menu | `064ea195-c9a3-4658-8856-44e7f81bf31b` | Pass |
| 7 stonecutter | `989975e4-e706-4ea3-ba8d-8c16f31bde27` | Pass |
| 10 donkey | `209a023c-a425-41d1-9e65-d8e1c897c67d` | Pass |
| 10 mule | `ae934625-dda6-456f-9c34-4e1e560f8a67` | Pass |
| 2 pickaxe | `35b7e7ed-7730-4ee0-ab05-f3dd591a54b1` | Pass |
| 2 sword repeat | `0d336fd7-22f2-470c-a5bf-bb4e2b2f5136` | Pass under the ruling: the held sword was read fresh and the mismatch flagged |
| Piston regression | `d283f9ae-75f4-492b-ae4f-8020032f2a58` | Pass for ingredients (13 calls, 86.8 s); the JEI viewer was found and opened the correct recipe |

### Installed default

The candidate was promoted to the code default, the build was installed, and the developer override was cleared (Restore default, then Save; `developerPrompt` absent from `config/packsage.properties`). Two rounds ran on this state.

Round 1 used JAR `F7CD4890AC95E0B3EABB9B21200773DF55771D34DA5B5F24EC2419BB85E493B4`. It passed 2, 4, 7, 8, 8b, 10, 14 and 15, and failed two checks:

| Check | Request ID | Failure |
|---|---|---|
| 16 held sword | `f8e37ad1-9c0c-4ef9-8f1f-3bfb76d45596` | The reply listed the supported enchantments but never said Sharpness, Smite and Bane of Arthropods are mutually exclusive: no exclusivity evidence was read |
| Piston regression | `1280a941-0841-4149-b666-7f50e81a5072` | The reply gave ingredients but said grid positions were not in the record and sent the player to JEI; no placement evidence existed, and the JEI scan was unfinished |

These two failures led to `exclusiveWith` (`5957336`) and the shape constraints (`74d0b3a`).

Round 2 (recheck) used the final JAR `05155D8F...49CD8` with the override cleared, and re-ran 16 held, 16 empty hand, the piston regression and 8.

## Final installed-default results

Each row cites the final passing request on the installed default with the override cleared.

| Check | Request ID | JAR | Result |
|---|---|---|---|
| 2 pickaxe | `c2d8816b-c379-4554-9238-e5ebc92d4ddb` | `F7CD4890` | Pass: 1 call; `enchantmentEligibility` read through the item target |
| 2 sword repeat | `2106e69a-4f73-4294-8532-3964fe33908a` | `F7CD4890` | Pass under the ruling |
| 4 active effects | `97be8a43-1539-439b-bfc0-e32f75ace64e`, expiry `303cbe39-631d-4679-9f49-4eea7de0ca14` | `F7CD4890` | Pass |
| 7 crafting menu | `857884f7-0aae-42fe-b89f-8537299c73e4` | `F7CD4890` | Pass: `openedMenu=minecraft:crafting`, `excludedProjections` `recipe_preview` |
| 7 stonecutter | `1e53a02f-aefb-40a4-a729-449172fb9289` | `F7CD4890` | Pass: `openedMenu=minecraft:stonecutter` |
| 8 brewing by name | `fa66cdd4-e67f-4979-9131-4707f36ed681` (round 1: `a9065717-f1cf-421f-bd24-33707e2f6d50`) | `05155D8F` | Pass: 2 recipe searches, against 24 in the completion run |
| 8b brewing plan | `cb3e9143-7492-49f1-a867-b277e7a3da39` | `F7CD4890` | Pass: `plan_recipe` called; grouping stated |
| 10 donkey | `7c6005e3-c94f-43f3-8bb8-d4f96c0dabd2` | `F7CD4890` | Pass |
| 10 mule | `7257a1bc-1187-44ce-833d-78b0be3a6b55` | `F7CD4890` | Pass |
| 14 Luck | `ea637508-eacb-4887-9ca4-eaa9e6e3dd47`, effect cleared `b9300e9b-2db0-4775-88c1-2e2b9bd756de` | `F7CD4890` | Pass: effect +3 and helmet modifier `packsage:test_helmet_luck` +3; value 6, then 3 |
| 15 effect description | `2c770231-9f92-49fd-b505-0bc6ef1a6a69` | `F7CD4890` | Pass (description-absent case) |
| 16 held sword | `ecfcf4b6-7347-41b6-960c-203ce7c21e47` | `05155D8F` | Pass: the reply states the mutual exclusion from `exclusiveWith` |
| 16 empty hand | `bfc2bd47-10db-439b-a4e7-3e15b1ff6df2` | `05155D8F` | Pass: "not holding a sword right now" |
| Piston regression | `0f56b8b8-5841-412c-9945-1c5c34608bb9` | `05155D8F` | Pass: 3 calls; the 3x3 placement came from the native shape |

## Passes

- Checks 2, 4, 7 (crafting and stonecutter), 8, 8b, 10 (donkey and mule), 14, 15 (description-absent case) and 16 (held and empty hand) pass on the installed default.
- The piston regression passes on the installed default after the shape constraints: ingredients and a complete 3x3 layout without invented positions.
- Check 14 distinguishes the Luck effect (+3 through `minecraft:effect.luck`) from the equipped helmet (+3 through `packsage:test_helmet_luck`), and reports +3 from the helmet alone after the effect is cleared. The assistant stated that Minecraft does not record what applied the effect.
- Check 8 needed 2 recipe searches (24 in the completion run). Check 8b called `plan_recipe`.
- No supported gameplay link was supplied for item, attribute, menu and animal records; every source pill that was clicked opened the matching record.

## Failures

Recorded separately; none is a pass in disguise.

| Where | Request ID | Failure | Resolution |
|---|---|---|---|
| Candidate 2, check 2 repeat | `7934b778-f65d-4745-954b-6f3c57ad39ff` | Declined to answer for the held sword | Fixed in candidate 3 |
| Candidate 2, check 8b | `e9971700-84bd-4709-a82a-5d77eaf43fcb` | Grouping stated without the batch semantics | Fixed in candidate 3 |
| Candidate 2, check 16 empty hand | `306f1fc0-6d17-41b2-92cc-f49d47e87702` | Substituted an unselected sword | Fixed in candidate 3 |
| Candidate 3, check 16 empty hand | `fb82e189-ad40-4a63-80f5-1568e9814bf8` | Strict wording only (see Rulings) | Ruled a pass |
| Default round 1, check 16 | `f8e37ad1-9c0c-4ef9-8f1f-3bfb76d45596` | No exclusivity evidence read | Fixed by `exclusiveWith`; passes in the recheck |
| Default round 1, piston regression | `1280a941-0841-4149-b666-7f50e81a5072` | No placement evidence | Fixed by native shape; passes in the recheck |

Candidate 1 was blocked (no desktop control), not failed.

## Rulings

- A repeated eligibility question after the held item changes counts as a pass when the held item is read fresh and the mismatch is flagged before advice about another item (check 2 sword repeat).
- An empty-hand question counts as a pass when the reply says "not holding a sword" and the carried-sword advice is read fresh and attributed to the carried sword (check 16 empty hand).
- Brewing totals may be derived from `operationSemantics` when the grouping is stated (check 8b); the planner's batch count is not presented as an operation count. The specification was updated accordingly.

## Acceptance limits

- Effect `description` is implemented and covered by automated tests only. The minimal pack's effects ship no `<descriptionId>.description` text, so only the description-absent case passed live. A pack with description text (for example ATM10) is needed for the present case. The replies did not announce the missing description; they sourced the general explanation externally and kept local claims to returned modifiers.
- The expired-handle `read_evidence` branch for active effects was not exercised; the fresh-search path was observed.
- Opened-path entity menus (donkey and mule screens) are still not accepted: the model used the targeted path in these runs.
- Modded menus reported in `openedMenu` are unsupported by `target: opened`.
- Minecraft does not record what applied a status effect, so the assistant narrows the source through `player_attribute`, the ambient flag and tooltip search.
- Prompt wording for the "refreshing short duration" effect-source cue needs two reads and remains a tracked follow-up.
- The citation rendering problem ("Open ref: ..." in the ATM10 report) is a separate existing roadmap item.
- Native brewing and JEI piston discovery stay bounded and can take many calls (candidate 3 piston: 13 calls, 86.8 s); an unfinished JEI scan is not proof a recipe is absent.
- Shared cross-adapter lines and Minecraft commands remain open.
