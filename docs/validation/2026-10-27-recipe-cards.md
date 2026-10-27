# Recipe cards: live acceptance

Date: 2026-10-27. Tasks 8b and 9 of the [implementation plan](../plans/2026-10-27-recipe-cards.md). Player steps: [testing checklist](../testing/2026-10-27-recipe-cards.md).

Status: all eleven category comparisons pass; interaction acceptance remains partial. Prompt candidate 18 passed both nine-question presentation matrices, before promotion and on the installed default with the developer override cleared. Promoted in `4953ed32`; full build passes. Warm opening timings pass, with cold-opening exceptions recorded below. The remaining failures and unverified interactions are separate from the passes.

## Installation and method

- Main checkout `D:/Project/packsage`, branch `feat/packsage-v2`, starting HEAD `6f530233`.
- Installed `mods/packsage.jar`: SHA-256 `FEBE7743BAC9BCDDA4F480AB0700422337B8BFF209598E784B6EE21212CC87F8`.
- CurseForge Packsage instance, Minecraft 1.21.1, NeoForge 21.1.259, JEI 19.27.0.340. Launched with computer use and opened the existing `PackSage Adapter Filter Live Test` save (displayed as New World).
- Initial developer override was null. The effective installed prompt exactly matched candidate 14. Model: GPT-6.1-Sol, medium; screenshot enabled and automatic search enabled. Initial Answer detail: Normal.
- Used the real P key, Settings > Answer detail, native mouse/keyboard input and JEI. Questions were submitted once per fresh conversation through the authenticated loopback bridge, with calls, full returned records, replies and timings retained under [build/recipe-cards-live](../../build/recipe-cards-live/). The bridge token and authorization headers were neither printed nor saved as evidence.
- Screenshots are original game PNGs under [assets/2026-10-27-recipe-cards](assets/2026-10-27-recipe-cards/). Window client size 1024 by 768, GUI scale 2, except the explicit narrow-window check. Screenshots compare separate views of the same recipe; animation phase and cycling alternatives naturally differ.

## Category comparisons: passes

All eleven cases produced a Normal card from the cited recipe record. Recipe panels, standard and output slot sizes, positions, sprites, arrows and applicable flame/text placement visually matched JEI at the same GUI scale. Native captions and JEI side buttons differ by design. R on the JEI output opened each comparison; Sentry used U on its template, then the Smithing Table tab, to select that exact trim recipe.

| Case | Observed | Native screenshot | JEI screenshot |
|---|---|---|---|
| Shaped beacon | PASS: full grid, star in center, obsidian bottom row, large output | [Beacon](assets/2026-10-27-recipe-cards/normal-beacon.png) | [Beacon](assets/2026-10-27-recipe-cards/jei-beacon.png) |
| Small shaped crafting table | PASS: top-left 2 by 2 in a full nine-slot grid | [Crafting table](assets/2026-10-27-recipe-cards/normal-small-shapeless-cycle1.png) | [Crafting table](assets/2026-10-27-recipe-cards/jei-crafting-table.png) |
| Shapeless oak planks | PASS: centered input, shapeless marker and output count 4 | [Oak planks](assets/2026-10-27-recipe-cards/normal-small-shapeless-cycle2.png) | [Oak planks](assets/2026-10-27-recipe-cards/jei-oak-planks.png) |
| Smelting glass | PASS: empty fuel slot, flame, arrow, `10s`, `0.1 XP` | [Furnace](assets/2026-10-27-recipe-cards/normal-glass.png) | [Furnace](assets/2026-10-27-recipe-cards/jei-glass.png) |
| Blasting raw iron | PASS: `5s`, `0.7 XP`; JEI's third displayed recipe is the matching raw-iron recipe | [Blast furnace](assets/2026-10-27-recipe-cards/normal-blasting.png) | [Blast furnace](assets/2026-10-27-recipe-cards/jei-blasting.png) |
| Smoking beef | PASS: `5s`, `0.35 XP` | [Smoker](assets/2026-10-27-recipe-cards/normal-smoking-campfire.png) | [Smoker](assets/2026-10-27-recipe-cards/jei-smoking.png) |
| Campfire beef | PASS: `30s`, no fuel slot or XP text, shorter panel | [Campfire](assets/2026-10-27-recipe-cards/normal-smoking-campfire.png) | [Campfire](assets/2026-10-27-recipe-cards/jei-campfire.png) |
| Stonecutting stone bricks | PASS: input, arrow and large output | [Stonecutter](assets/2026-10-27-recipe-cards/normal-stonecutting.png) | [Stonecutter](assets/2026-10-27-recipe-cards/jei-stonecutting.png) |
| Smithing transform | PASS: three adjacent inputs, arrow, standard-size output | [Netherite sword](assets/2026-10-27-recipe-cards/normal-smithing-transform.png) | [Netherite sword](assets/2026-10-27-recipe-cards/jei-smithing-transform.png) |
| Smithing trim | PASS layout: three inputs and standard-size output. Native `?` versus JEI's dynamically assembled output is the specified native evidence limit | [Sentry trim](assets/2026-10-27-recipe-cards/normal-smithing.png) | [Sentry trim](assets/2026-10-27-recipe-cards/jei-smithing-trim.png) |
| Brewing Swiftness | PASS layout: sugar, three Awkward Potion inputs, component-correct output, heat bar, bubbles and downward arrow. Native omits JEI's `Steps: 2`, as explicitly required by the spec | [Brewing](assets/2026-10-27-recipe-cards/normal-swiftness.png) | [Brewing](assets/2026-10-27-recipe-cards/jei-swiftness.png) |

Evidence: [normal-categories.json](../../build/recipe-cards-live/normal-categories.json), [normal-small-shapeless.json](../../build/recipe-cards-live/normal-small-shapeless.json), and the Normal matrix files below. These are visual comparisons, not a claim of automated pixel equality or synchronized animation phases.

## Interaction passes

- Ordinary slot hover shows the vanilla item name and highlight: [Sand tooltip](assets/2026-10-27-recipe-cards/slot-tooltip.png).
- F3+H was enabled from gameplay. Rehovering a slot shows the registry ID and component count: [advanced Red Sand tooltip](assets/2026-10-27-recipe-cards/slot-tooltip-advanced.png). Ingredient item chips also show advanced item details: [Glass chip](assets/2026-10-27-recipe-cards/item-chip-tooltip.png).
- All four crafting-table plank slots cycle together through the same plank alternative: [cycle sample 1](assets/2026-10-27-recipe-cards/normal-small-shapeless-cycle1.png), [cycle sample 2](assets/2026-10-27-recipe-cards/normal-small-shapeless-cycle2.png). Sand and red sand also cycle in the furnace card. Three-second Shift freeze/resume remains unverified below.
- Hovering a Concise result chip opens the full floating native card: [Beacon preview](assets/2026-10-27-recipe-cards/result-hover.png). Moving off to an ingredient chip closes it and shows that chip's own item tooltip.
- At a 298-pixel client width (GUI scale automatically becomes 1), both 124-GUI-pixel crafting cards become source chips; neither clips nor scales down: [narrow fallback](assets/2026-10-27-recipe-cards/narrow-fallback.png). Restored the original window width afterwards.
- A saved Normal answer still renders cards after changing the current setting to Concise. The legacy turn in conversation `7730f3ed-121c-49ff-8001-8c7b3f0ccd43` has no stored verbosity and retains its original text arrangement and source chip, with no added cards/chips: [legacy beacon](assets/2026-10-27-recipe-cards/legacy-beacon.png).
- Trim's unknown output shows `?` and a tooltip containing its recorded `unknown.output:...` identity: [unknown-output tooltip](assets/2026-10-27-recipe-cards/trim-unknown-tooltip.png). This does not exercise a custom ingredient.

## Candidate 14: initial nine-question matrix

Each row used the exact question from the plan in a separate conversation. All nine requests completed and were saved. Item links used IDs present in their returned tool results; none was invented. Native recipe records were `navigable:false` and all nine had zero navigation targets, so there was no supported in-game recipe link to omit. Detailed beacon retained returned external-source links, rendered as Open minecraft.wiki chips. Raw calls, coverage, reply and timing fields are in each linked JSON; [candidate14-summary.json](../../build/recipe-cards-live/candidate14-summary.json) indexes them.

| Answer detail / question | Request ID | Result |
|---|---|---|
| Normal beacon | `d9242050-8ba5-4244-8bad-46d6991d7782` | PASS cards/no text grid; [evidence](../../build/recipe-cards-live/normal-beacon.json) |
| Normal glass | `58eafa3d-c651-4a82-9c79-c4ad41dc7ce7` | PASS standalone furnace card/no text grid; [evidence](../../build/recipe-cards-live/normal-glass.json) |
| Normal Swiftness | `d3336a10-8ae1-4e73-81ed-a906c5b66de7` | PASS two standalone brewing cards/no text grid; [evidence](../../build/recipe-cards-live/normal-swiftness.json) |
| Concise beacon | `674ba20c-2da1-4aba-ac97-0f2e1be25acd` | FAIL duplicate output item chip plus result chip; [evidence](../../build/recipe-cards-live/concise-beacon.json), [screenshot](assets/2026-10-27-recipe-cards/concise-beacon-c14.png) |
| Concise glass | `27649453-933f-4ad5-a4d0-e62f16fb40fc` | FAIL duplicate Glass chips, repeated names and prose slot placement; [evidence](../../build/recipe-cards-live/concise-glass.json), [screenshot](assets/2026-10-27-recipe-cards/concise-glass-c14.png) |
| Concise Swiftness | `0527c2b1-286c-4cdd-895e-18644c92b385` | FAIL repeated potion outputs/citations and component-free potion item link; [evidence](../../build/recipe-cards-live/concise-swiftness.json), [screenshot](assets/2026-10-27-recipe-cards/concise-swiftness-c14.png) |
| Detailed beacon | `d29d4ca1-f33b-42b5-8a94-c63c26083d76` | PASS standalone cards/no text grid; [evidence](../../build/recipe-cards-live/detailed-beacon.json) |
| Detailed glass | `ec43d30d-2780-4aea-8b44-a1ff5edb3493` | PASS card/no text grid, but FAIL whole-policy prohibition on prose slot layout: it describes the input and fuel slots; [evidence](../../build/recipe-cards-live/detailed-glass.json), [screenshot](assets/2026-10-27-recipe-cards/detailed-glass-c14.png) |
| Detailed Swiftness | `ded2b131-c9c1-4340-88dd-3eec3610034d` | PASS standalone basic and upgrade brewing cards/no text grid; [evidence](../../build/recipe-cards-live/detailed-swiftness.json), [screenshot](assets/2026-10-27-recipe-cards/detailed-swiftness-c14.png) |

The Concise Swiftness status poll timed out while the native Windows sizing menu suspended the render thread. The same request was recovered after sizing ended; the question was not resubmitted. No gameplay command or fixture mutation was used.

## Prompt iteration

Candidate files are whole prompts under `build/prompt-candidates`. Each replacement was applied through the in-game editor opened by middle-clicking the settings gear. GET /prompt verified the saved text exactly matched its candidate before new questions were submitted. Minecraft stayed open between candidates.

Candidate 15 integrates result-name ownership into citation policy: an inline labelled recipe citation supplies the output name, with no extra item link or repeated citation; component-dependent items use their exact variant record. Its Concise beacon and Swiftness checks pass. Concise glass still fails: after the correct chips it says “Put the sand in the input slot and fuel below it,” repeating an ingredient and describing placement. Candidate 15 was not promoted. Evidence: [beacon](../../build/recipe-cards-live/c15-concise-beacon.json), [glass](../../build/recipe-cards-live/c15-concise-glass.json), [Swiftness](../../build/recipe-cards-live/c15-concise-swiftness.json); [beacon screenshot](assets/2026-10-27-recipe-cards/c15-concise-beacon.png), [glass screenshot](assets/2026-10-27-recipe-cards/c15-concise-glass-read-error.png), [Swiftness screenshot](assets/2026-10-27-recipe-cards/c15-concise-swiftness.png).

Candidate 16 strengthens the existing spatial-information rule for all recipes and integrates a Concise final check for repeated names/citations across explanation and caveat sentences. Concise glass passes, but Swiftness repeats the result citation on a final caveat, producing a second result chip. Beacon's recipe presentation passes, but it incorrectly tells the player to “Open the recipe chip” when the supported interaction is hover. Not promoted. [Glass](../../build/recipe-cards-live/c16-concise-glass.json), [beacon](../../build/recipe-cards-live/c16-concise-beacon.json), [Swiftness](../../build/recipe-cards-live/c16-concise-swiftness.json), [duplicate-chip screenshot](assets/2026-10-27-recipe-cards/c16-concise-swiftness.png).

Candidate 17 reconciles citation scope with Concise brevity: one recipe citation supports its quantities and caveats together and supplies its output name; the existing hover behavior is stated correctly. All three Concise and Normal questions pass. Detailed beacon/glass pass, but Detailed Swiftness explains Water Bottle + Sugar → Mundane Potion in a warning using only an inline source chip. That transformation does not get a card. Not promoted. [Failing reply](../../build/recipe-cards-live/c17-detailed-swiftness.json), [warning screenshot](assets/2026-10-27-recipe-cards/c17-detailed-swiftness-warning.png). All nine `c17-<detail>-<question>.json` responses remain in the evidence folder.

Candidate 18 makes the existing Normal/Detailed rule apply to every explained transformation, including alternatives and warnings. It passed the full matrix below. No prompt rules outside recipe presentation were intentionally changed. The whole prompt and all current tool descriptions were reviewed together; tool schemas/descriptions and verbosity-setting text were unchanged. Keeping the verbosity text preserves the exact configuration used for the live passing matrix.

### Candidate 18: passing matrix before promotion

All nine replies were saved byte-for-byte as returned, with the expected stored verbosity and exact requested question. Every item ID and local reference matched returned evidence; zero tool errors and zero in-game navigation targets. The absence of recipe navigation is explained by the native records' `navigable:false`. Returned external links remained visible where the answer used external guidance. All Normal/Detailed explained recipes have standalone citations/cards and no text grids or slot-placement prose. Concise uses one inline result citation per recipe and names ingredients/outputs once, with variant outputs represented by their recipe record. This is recipe-presentation acceptance; it does not supersede older unrelated A3/J8 answer-completeness findings.

| Detail / question | Request ID | Model-terminal time | Result and evidence |
|---|---|---|---|
| Normal beacon | `774560ec-660b-4805-9944-08c2003f199a` | 24,372 ms | PASS; [JSON](../../build/recipe-cards-live/c18-normal-beacon.json), [render](assets/2026-10-27-recipe-cards/c18-normal-beacon.png) |
| Normal glass | `c040167f-47e3-4b6c-b8c5-ae2c64c539cd` | 16,914 ms | PASS; [JSON](../../build/recipe-cards-live/c18-normal-glass.json), [render](assets/2026-10-27-recipe-cards/c18-normal-glass.png) |
| Normal Swiftness | `a27faffa-2780-4481-9bf9-bec1494ba90c` | 36,399 ms | PASS; [JSON](../../build/recipe-cards-live/c18-normal-swiftness.json), [render](assets/2026-10-27-recipe-cards/c18-normal-swiftness.png) |
| Detailed beacon | `9e214685-caee-40bd-8547-9c85216ea312` | 34,839 ms | PASS; [JSON](../../build/recipe-cards-live/c18-detailed-beacon.json), [render](assets/2026-10-27-recipe-cards/c18-detailed-beacon.png) |
| Detailed glass | `893f7a30-39e7-4778-9ee7-7612eae933cd` | 20,457 ms | PASS; [JSON](../../build/recipe-cards-live/c18-detailed-glass.json), [render](assets/2026-10-27-recipe-cards/c18-detailed-glass.png) |
| Detailed Swiftness | `8153b704-05f8-4445-8d8a-a84c2f544166` | 34,195 ms | PASS; [JSON](../../build/recipe-cards-live/c18-detailed-swiftness.json), [render](assets/2026-10-27-recipe-cards/c18-detailed-swiftness.png) |
| Concise beacon | `7d7f19b8-e3aa-4a4f-b6d7-3dd6dcb88fa6` | 15,960 ms | PASS; [JSON](../../build/recipe-cards-live/c18-concise-beacon.json), [render](assets/2026-10-27-recipe-cards/c18-concise-beacon.png) |
| Concise glass | `592620a5-e874-4f5c-9a44-35e354d4b802` | 17,229 ms | PASS; [JSON](../../build/recipe-cards-live/c18-concise-glass.json), [render](assets/2026-10-27-recipe-cards/c18-concise-glass.png) |
| Concise Swiftness | `fe254c74-7e2d-4591-bca3-bfbfe1a825e7` | 27,413 ms | PASS; [JSON](../../build/recipe-cards-live/c18-concise-swiftness.json), [render](assets/2026-10-27-recipe-cards/c18-concise-swiftness.png) |

[candidate18-summary.json](../../build/recipe-cards-live/candidate18-summary.json) includes the calls, arguments, coverage, record counts, exact replies, saved-reply comparisons, stored verbosity and reference/ID checks. Timing spans acceptance to model termination, including capture/tool/model work, and excludes persistence. Independent questions in each detail group ran in separate chats concurrently; this is separate from the model's own tool-call grouping. The prompt changes did not change query or fan-out policy. Brewing used plan_recipe and, where needed, a later multi-handle read_evidence for returned variant references.

The existing bridge request-discoverability issue also appeared on the first Normal and Concise beacon polls in candidate 18 and on two candidate 17 beacon polls: `No saved request` immediately after acceptance. The same accepted request IDs were recovered after completion, without resubmitting the question. Candidate 18 first-poll responses are retained as `c18-*-beacon-first-poll.json`; this is not a recipe-card rendering failure.

## Build and reinstallation

Promoted the exact candidate 18 text to `GroundingInstructions.java`, updated its existing policy anchors and candidate equality test, and committed immediately as `4953ed32` before automated verification. No other production code changed.

With `JAVA_HOME=D:/Project/packsage/.tools/jdk/jdk-21.0.12.1`, `gradlew.bat build -q` exited 0. `node build/tools/count.cjs` reports **1,340 tests, zero failures/errors, five skipped** (1,335 passed). [Build log](../../build/recipe-cards-live/build.log). This verifies prompt equality and existing regressions; it does not establish model compliance or the unrun Shift interactions.

Cleared the developer override, restored Normal and advanced tooltips off, saved/quit normally, and verified zero instance Java processes using the canonical instance path. Removed temporary JEI from `mods` into `build/recipe-cards-live/temporary-mods/` after the comparison. Ran `powershell -ExecutionPolicy Bypass -File scripts/build.ps1`: exit 0, BUILD SUCCESSFUL, with build/test tasks up-to-date. Installed JAR and dist JAR both have SHA-256 `D7E6C8E8ECEE1F4F9F9064282843C341E49659635E9B953A959E41B9B7B72915`. [Install log](../../build/recipe-cards-live/install.log), [hash metadata](../../build/recipe-cards-live/installed-promoted.json).

### Installed default: passing matrix after restart

Relaunched through CurseForge, reopened the same save, and verified that the developer override was null and the effective prompt exactly matched candidate 18. Changed Answer detail through Settings for each group. Each exact question was submitted once in a fresh chat. All nine completed and saved, with matching persisted replies and expected stored verbosity. All linked item IDs and evidence references occur in the returned tool results; zero tool errors. Normal/Detailed render standalone cards without text grids or slot prose. Concise renders ingredient chips and one result chip per recipe, with no repeated ingredient/output names. Native records remain `navigable:false`, with zero navigation targets; Detailed beacon retains the returned external links. This does not establish destination-opening behavior for adapters absent from this Minecraft-only instance.

| Detail / question | Request ID | Model-terminal time | Result and evidence |
|---|---|---|---|
| Normal beacon | `577b9916-772b-4f6e-a2c5-a2f4e2cf4a84` | 27,421 ms | PASS; [JSON](../../build/recipe-cards-live/installed-normal-beacon.json), [crafting](assets/2026-10-27-recipe-cards/installed-normal-beacon-top.png), [furnace](assets/2026-10-27-recipe-cards/installed-normal-beacon.png) |
| Normal glass | `53153453-85a3-4076-8cb1-5accd4ef26c6` | 16,232 ms | PASS; [JSON](../../build/recipe-cards-live/installed-normal-glass.json), [render](assets/2026-10-27-recipe-cards/installed-normal-glass.png) |
| Normal Swiftness | `fd2032f8-23cd-456d-b35b-ca50da872967` | 19,408 ms | PASS; [JSON](../../build/recipe-cards-live/installed-normal-swiftness.json), [render](assets/2026-10-27-recipe-cards/installed-normal-swiftness.png) |
| Detailed beacon | `b846e6ea-fbdb-470e-89d5-7e59f2953fb7` | 38,294 ms | PASS; [JSON](../../build/recipe-cards-live/installed-detailed-beacon.json), [crafting](assets/2026-10-27-recipe-cards/installed-detailed-beacon-top.png), [external links](assets/2026-10-27-recipe-cards/installed-detailed-beacon.png) |
| Detailed glass | `3387f328-4480-4661-96fd-a5c68939b442` | 27,925 ms | PASS; [JSON](../../build/recipe-cards-live/installed-detailed-glass.json), [render](assets/2026-10-27-recipe-cards/installed-detailed-glass.png) |
| Detailed Swiftness | `84531a26-fcc8-4d42-abca-32b2707e7356` | 36,606 ms | PASS; [JSON](../../build/recipe-cards-live/installed-detailed-swiftness.json), [first step](assets/2026-10-27-recipe-cards/installed-detailed-swiftness-top.png), [upgrade](assets/2026-10-27-recipe-cards/installed-detailed-swiftness.png) |
| Concise beacon | `b21a9718-ad2d-48d3-a73d-b5070cf174ad` | 16,066 ms | PASS; [JSON](../../build/recipe-cards-live/installed-concise-beacon.json), [render](assets/2026-10-27-recipe-cards/installed-concise-beacon.png) |
| Concise glass | `848a7a6b-c77d-4fb6-ac48-3426d6bbdca6` | 16,038 ms | PASS; [JSON](../../build/recipe-cards-live/installed-concise-glass.json), [render](assets/2026-10-27-recipe-cards/installed-concise-glass.png) |
| Concise Swiftness | `9e3ad4bd-9b96-418a-a6aa-2a06ab0c7253` | 27,813 ms | PASS; [JSON](../../build/recipe-cards-live/installed-concise-swiftness.json), [render](assets/2026-10-27-recipe-cards/installed-concise-swiftness.png) |

[Installed summary](../../build/recipe-cards-live/installed-summary.json) retains calls, arguments, coverage, exact replies and persistence/reference checks. Every group used separate chats concurrently, as before promotion. This is observed compliance on these nine samples, not a guarantee for every future answer. No new candidate was needed after installation.

### Final game state

Minecraft remains running in the same test world, unpaused, with chat/settings closed (`screen:null`). Position `[1583.394432834578, 108.51250108506484, 184.70977029085697]`, yaw `-156.01227`, pitch `6.299982`, creative mode, health 20, selected slot 0 and no mount are unchanged. No gameplay commands, movement or recipe fixtures were used. Normal is restored, advanced tooltips are off, the override is null, and the effective default still equals candidate 18. Final installed hash matches the verified hash above. Temporary JEI is absent from mods. [Final state](../../build/recipe-cards-live/state-final.json), [verification](../../build/recipe-cards-live/final-verification.json), [world screenshot](assets/2026-10-27-recipe-cards/final-world.png).

## Chat-opening timings

Used P from gameplay, with the completed six-card conversation `2a562715-dbe3-4598-8273-e1290f4146a7` selected. The five warm samples are consecutive logged opening requests at 08:26:41, 08:26:44, 08:26:46, 08:26:49 and 08:26:51. From `client_open_request.start_ns` to `first_frame_rendered.event_ns`: **34.6892, 45.0522, 36.2778, 37.4595 and 32.9329 ms**. All have `loading=false`. PASS against the prior approximately 11–50 ms range for these warm samples. First-frame logging measures render-call completion, not GPU presentation or time before the common key handler.

The first real P opening after launch was **55.5631 ms** (08:19:36); its screen-construction-to-frame portion was 52.5105 ms. This cold sample is outside the earlier 50.383 ms maximum and is retained as a timing acceptance exception, not hidden by the warm results. Bridge-selected openings are excluded from the five key samples. Raw stage lines: [opening-stages.log](../../build/recipe-cards-live/opening-stages.log).

After installing candidate 18, five real P openings on the same six-card conversation measured **35.3310, 33.3311, 37.6480, 33.9087 and 34.0422 ms** at 09:15:02 through 09:15:05, all `loading=false`. These warm samples also pass. The first real P opening after this restart measured **94.0028 ms** (09:09:09), another cold-opening exception. The bridge-selected opening used to select the six-card chat was `loading=true` and is excluded from the five warm samples. [Installed stage lines](../../build/recipe-cards-live/opening-stages-installed.log), [derived summary](../../build/recipe-cards-live/installed-opening-summary.json).

## Remaining failures and unverified checks

### UI-1: wheel scrolling leaves a hovered result preview visible

FAIL. Reproduce with the candidate 14 Concise beacon answer above: hover its green result chip, then scroll one wheel step while the pointer remains on the chip. Expected: scrolling dismisses the floating card. Actual: the card remains visible after the wheel event and in a later stationary capture. This short chat has no overflowing history, so the content does not move. Evidence: [before](assets/2026-10-27-recipe-cards/result-hover.png), [after](assets/2026-10-27-recipe-cards/result-after-scroll.png). No rendering code was changed.

### UI-2: read-marking failure exposes a Java exception and local file path

FAIL, observed once on candidate 15's Concise glass completion, request `4c839feb-8810-423c-99e1-941201198bb0`, conversation `20ba7cef-eadc-49f9-895d-a4cf59538b0a`. Steps: ask the glass question in a fresh Concise chat and view the completed answer. Expected: completion is marked read, or a bounded player-facing error is shown. Actual: a red multi-line “Completion state could not be marked as read” message exposes `java.nio.file.AccessDeniedException` and the temporary/target `.state.json` paths. The answer itself is completed and saved. [Screenshot](assets/2026-10-27-recipe-cards/c15-concise-glass-read-error.png). Later questions completed without the message; the filesystem failure was not deliberately reproduced or fixed.

### Unverified interactions

- NOT RUN: sustained Shift for three seconds, freeze without a release jump, pinned panel slot tooltips, release outside chip/panel, and pinned panel click-through protection. The available computer-use API exposes complete key chords but no key-down/key-up or timed hold operation. An ordinary Shift tap cannot establish these behaviors.
- NOT RUN: missing-item barrier and `Missing:` tooltip. No naturally missing registry item was present in these recorded recipes.
- NOT RUN: custom-ingredient `?` tooltip. The trim unknown-output placeholder was exercised, but it is a different case. No loaded recognized native recipe with a custom ingredient was found in the selected checks; unsupported Nature's Aura recipes remain source-only by design.
- Candidates 14–17 failures are retained above; candidate 18 passed both before promotion and on the installed default. Full feature acceptance remains incomplete because UI-1/UI-2, the cold timing exceptions and the unverified interactions above remain open.

## Follow-up live recheck: compact chips and source visibility

Date: 2026-10-27. This section appends new observations; earlier results remain historical. **Acceptance remains partial:** checks 1, 2, 4 and 5 pass; check 3 fails its no-empty-lines clause; check 6 has cold and warm timing exceptions; check 7 is NOT RUN. UI-1 still reproduces, and UI-3 below is a new spacing failure. UI-2 did not recur in these eleven completions, which does not establish that it is fixed.

### Build and method

- Started in the main checkout on `feat/packsage-v2` at `03f2ee6ef56a9c03d37c6c72794ae44e5de942e9`. Installed `packsage.jar` was and remains SHA-256 `9DAF91D98C33586DAD4EA8D151AC64CEEAA4BE039CF38A9AAED792F686994BDC`.
- Launched the existing CurseForge **Packsage** instance with computer use and opened **PackSage Adapter Filter Live Test**, displayed as New World in the world list. JEI `jei-1.21.1-neoforge-19.27.0.340.jar` stayed in the instance's `mods` directory throughout. No mod was moved, removed or replaced.
- Read the current spec at the start of the run, including the 12-GUI-pixel chip/icon rule and source visibility rules. During testing, another work step advanced the checkout to `e9ea9e4f` and changed the specification and icon inset. **That later change is not installed or covered by this recheck.** The tested artifact is identified by the hash above.
- Model GPT-6.1-Sol, medium; screenshot and automatic web search enabled. The developer prompt override remained null, and the effective prompt matched its initial value at the end. No code or prompt was changed by this test run.
- Changed Answer detail before each new request, using Settings for the initial Concise case and the authenticated bridge for the other detail groups. Each question was submitted once in a new conversation. All eleven completed, were saved with the intended verbosity, and have saved replies matching the bridge responses. [Durable request/call/reply evidence](assets/2026-10-27-recipe-cards/recheck-evidence.json) records IDs, tool calls, returned references and final state; full responses remain under `build/recipe-cards-recheck`. The bridge token and authorization headers were never printed or saved in evidence.
- Original game PNGs use a 1024 by 768 client at GUI scale 2, except the explicit 290 by 768 narrow-window views at scale 1 and the labelled wide view. Restored the original client size afterwards. No formatting tools, build, unit tests or gameplay fixture mutations were used for this installed visual recheck.

### Results by requested check

| Check | Result | Observation and screenshots |
|---|---|---|
| 1. JEI loads and R opens recipes | PASS | The JEI item list is visible in the inventory. Searched for Beacon, defocused the search field, hovered its result and pressed R; JEI opened the beacon crafting recipe. [Item list](assets/2026-10-27-recipe-cards/recheck-jei-list.png), [R opens recipe](assets/2026-10-27-recipe-cards/recheck-jei-r-beacon.png). |
| 2. Concise compact chips and hover | PASS for the requested installed build | The fresh exact beacon question produces one green result chip and three filled neutral ingredient chips. Chips occupy one 12-GUI-pixel text line, with 12-pixel item renders (24 image pixels at scale 2), rounded corners, a visibly lighter ingredient fill and a rounded result outline. Glass hover shows its item tooltip. Beacon hover shows the floating crafting card, whose slots retain the full 16-pixel item renders. [Concise answer](assets/2026-10-27-recipe-cards/recheck-concise-beacon.png), [item tooltip](assets/2026-10-27-recipe-cards/recheck-item-tooltip.png), [floating card](assets/2026-10-27-recipe-cards/recheck-result-hover.png). This does not accept the later `e9ea9e4f` icon-inset change. |
| 3. Sources by verbosity, links and legacy | FAIL: spacing subcase; other observed subcases PASS | Normal and Concise attribute answers hide bare source citations and citation groups without visible source chips or empty bullets. Concise Nature's Aura renders the labelled `Sand` citation as ordinary text and retains its web link. Detailed retains Source chips and source hover details. Normal advancement links remain Open chips; clicking Open advancement for Take Aim opens the Adventure advancements tab. Legacy beacon retains its Source chip while current detail is Normal. The supplemental Normal standalone-citation case leaves a trailing blank row: UI-3 below. [Normal](assets/2026-10-27-recipe-cards/recheck-source-normal.png), [Concise](assets/2026-10-27-recipe-cards/recheck-source-concise.png), [plain label/web link](assets/2026-10-27-recipe-cards/recheck-concise-plain-label-web.png), [Detailed](assets/2026-10-27-recipe-cards/recheck-source-detailed.png), [source hover](assets/2026-10-27-recipe-cards/recheck-source-detailed-hover.png), [Open chips](assets/2026-10-27-recipe-cards/recheck-navigation-normal.png), [opened tab](assets/2026-10-27-recipe-cards/recheck-navigation-opened.png), [legacy](assets/2026-10-27-recipe-cards/recheck-legacy-normal-setting.png). |
| 4. Narrow window | PASS | At a 290-pixel client width, the Normal beacon card becomes a green result chip, and hover opens its floating full card. The same-width Detailed answer falls back to a Source chip. Neither beacon card is squeezed to fit. [Normal](assets/2026-10-27-recipe-cards/recheck-narrow-normal.png), [Normal hover](assets/2026-10-27-recipe-cards/recheck-narrow-normal-hover.png), [Detailed](assets/2026-10-27-recipe-cards/recheck-narrow-detailed.png). |
| 5. Normal and Detailed card spot-checks | PASS | Fresh beacon and glass answers show their cards: beacon's five glass, centered star and bottom obsidian row; glass's input, empty fuel slot, flame, arrow, output, `10s` and `0.1 XP`. Cards retain the previous layout and full-size slot items. [Normal beacon](assets/2026-10-27-recipe-cards/recheck-normal-beacon.png), [Normal glass](assets/2026-10-27-recipe-cards/recheck-normal-glass.png), [Detailed beacon](assets/2026-10-27-recipe-cards/recheck-detailed-beacon.png), [Detailed glass](assets/2026-10-27-recipe-cards/recheck-detailed-glass.png), [both Detailed cards in wide view](assets/2026-10-27-recipe-cards/recheck-detailed-beacon-glass-wide.png). |
| 6. Real-key opening timing | FAIL against the prior maximum | First P opening: 72.2324 ms. Five warm P openings: 51.0822, 34.9051, 36.8649, 36.2240 and 37.5099 ms. Four warm samples pass; the first warm sample is 0.6992 ms above the previous 50.383 ms maximum. Details below. |
| 7. Sustained Shift pinning | NOT RUN | The supported computer-use API still exposes complete key chords only, with no key-down/key-up or timed hold. Could not establish sustained Shift, movement into the pinned card, slot tooltips while pinned, release outside, click-through protection or pinned-panel scroll dismissal. No pass is inferred from a Shift tap. Ordinary unpinned wheel dismissal was run and still fails: UI-1 below. |

The navigation observations establish retained chips and the opened advancement tab; they are not a new full navigation/restart matrix. Web links remain visible in the Concise Nature's Aura and Detailed beacon answers ([Detailed sources and web links](assets/2026-10-27-recipe-cards/recheck-detailed-source-web.png)); external browser launch was not part of this recheck. No source-only empty-bullet fixture was fabricated. The natural list and grouped-citation cases inspected above are clean, while the final standalone source paragraph exposes UI-3.

Fresh recipe requests:

| Detail / exact question | Request ID | Model-terminal time |
|---|---|---|
| Concise / `how do I make a beacon?` | `41ca4daf-69e4-46f6-a175-2439a67210c8` | 17,607 ms |
| Normal / `how do I make a beacon?` | `47465a5e-9f57-4f82-b49d-f360a28b7169` | 31,182 ms |
| Normal / `how do I smelt glass?` | `7f5cd3fa-6f16-47b6-87db-5fbc05d8dde0` | 15,586 ms |
| Detailed / `how do I make a beacon?` | `e413dec8-a0d1-46ba-bd12-ac401551876d` | 47,439 ms |
| Detailed / `how do I smelt glass?` | `c0821a18-1034-482b-aca6-4e17eb104e91` | 23,949 ms |

Supplemental source probes used `what effects do I have and where do my bonuses come from?` in Normal, Concise and Detailed, `what should I do next for advancements?` in Normal, and `how do I make sand using Nature's Aura?` in Concise and Normal. Their request IDs and exact replies are in the durable evidence JSON. These are renderer/link observations, not a separate acceptance of every gameplay claim in those replies.

### Opening timings

Measured from `client_open_request.start_ns` to `first_frame_rendered.event_ns`, using the real P key. All samples have `loading=false`. The first opening was taken before any bridge-selected chat opening after launch, with the previously selected Concise glass conversation `431286e5-79b1-4651-9468-a4d6257aa6de`. The five warm samples use the same completed six-card conversation as the earlier report, `2a562715-dbe3-4598-8273-e1290f4146a7`, at the restored 1024 by 768 size. Bridge-selected openings and sizing operations are excluded from these samples.

| Sample | Local log time | Request to first frame | Assessment |
|---|---|---|---|
| First P after launch | 09:29:35 | 72.2324 ms | Cold exception |
| Warm 1 | 09:40:44.086 | 51.0822 ms | Slightly above the prior maximum |
| Warm 2 | 09:40:44.689 | 34.9051 ms | PASS |
| Warm 3 | 09:40:46.339 | 36.8649 ms | PASS |
| Warm 4 | 09:40:48.339 | 36.2240 ms | PASS |
| Warm 5 | 09:40:50.635 | 37.5099 ms | PASS |

[Raw timing lines](assets/2026-10-27-recipe-cards/recheck-opening-stages.log), [derived timing pairs](assets/2026-10-27-recipe-cards/recheck-opening-summary.json), [selected six-card chat](assets/2026-10-27-recipe-cards/recheck-warm-six-card-chat.png). As before, this measures render-call completion, not GPU presentation. The exceptions are retained; this run does not establish that recipe cards caused the timing variation.

### Bugs retained or newly observed

**UI-1 still FAILS: wheel scrolling leaves an ordinary hovered preview visible.** In the fresh Concise beacon conversation `9d11abff-4d77-4fa3-b996-9bf1d6f5f036`, hover the Beacon result chip, then scroll one wheel step with the pointer still on it. Expected: the floating card closes. Actual: it remains visible immediately and in a later stationary capture. The short conversation does not overflow. [Before](assets/2026-10-27-recipe-cards/recheck-result-hover.png), [after](assets/2026-10-27-recipe-cards/recheck-result-after-scroll.png). This is the unpinned case; pinned dismissal remains NOT RUN.

**UI-3 FAILS: a hidden source-only paragraph leaves a trailing blank row.** Set Answer detail to Normal, start a new conversation and ask `how do I make sand using Nature's Aura?`. Request `b3e0df2e-94be-4f72-98b6-34339d56a76a`, conversation `a712fa5f-1cb8-484b-b848-a72fef640641`. The saved answer ends with its final prose paragraph, a blank line, then `[native:naturesaura:altar/sand]` alone. Expected: removing that source-only paragraph leaves no empty line in the bubble. Actual: the Source chip is hidden, but one blank text row remains below `settings.` before the bubble's bottom padding, approximately 12 GUI pixels / 24 image pixels. [Screenshot](assets/2026-10-27-recipe-cards/recheck-normal-hidden-source-web.png). The inspected Normal/Concise trailing inline citations do not show this gap; the standalone final paragraph is the reproducing case. No code fix was made.

UI-2 was not observed in these eleven saved completions. Missing/custom ingredient and sustained Shift checks from the earlier report remain open. The later icon-inset change is also outside this installed-build acceptance.

### Final state

Minecraft is left running in **PackSage Adapter Filter Live Test**, with chat and settings closed, Answer detail **Normal**, the original 1024 by 768 client size restored, and the developer override still null. [Normal selected](assets/2026-10-27-recipe-cards/recheck-final-settings-normal.png), [world with chat closed](assets/2026-10-27-recipe-cards/recheck-final-world.png). The installed PackSage hash is unchanged and JEI remains installed. No push or merge was performed.

## Player confirmation on the final installed build

After the follow-up fixes, the installed build (`017dd4ba`, packsage.jar SHA-256 starting `94E116536E2C`) contains:

- **Read state:** the completion read-state retry; the message it shows no longer contains file paths (`03f2ee6e`).
- **Chip icons:** the 10 px chip icon inset (`e9ea9e4f`).
- **Blank row:** hiding a source-only paragraph no longer leaves an empty row (`0e230028`).
- **Answer layout:** answers are drawn without a bubble and keep their sent time; result chips use the standard chip border (`017dd4ba`).

The player used this build in game and reported that everything works as intended.

**UI-1 ruling.** The hovered preview staying visible after a wheel step in a chat too short to scroll is intended behavior. The content did not move and the pointer stayed on the chip, so the chip was still hovered. Pinned panels close on scroll.

**Still open:**

- **Missing and custom ingredient tooltips.** These have no live evidence; only unit tests cover them.
- **Cold-opening timings.** Cold openings remain recorded exceptions to the warm 11–50 ms target.

## 2026-10-27 sidebar and popup retest

**Sidebar stability: PASS. Popup layering: PASS for the observed cases.** Tested main checkout `feat/packsage-v2` at `cb8d068ac8eaa114710783991c72ebb7a0df636c`, installed JAR SHA-256 `EF7A93C49913B32491435525FC4F35B80C0CCFFB00A340740A6BF093893A8B8A`. Same CurseForge instance/test world, Minecraft 1.21.1 / NeoForge 21.1.259, JEI 19.27.0.340 retained. No code/prompt/mod changes. GPT-6.1-Sol medium; Answer detail Normal; saved Detailed chats retain their own presentation. [Metadata](assets/2026-10-27-v2-retest/run-metadata.json).

### Sidebar — PASS

The five-row list exposes **Show more**. Click it, scroll the sidebar, then select three revealed chats in succession: the dense-world chat, POI/statistics chat and J8 chat. Wait at least three seconds on each. Row order, expansion and sidebar thumb/scroll position stay put; selection/highlight changes. The original relative visible order stays item probe → frozen probe → long-cow probe → dense probe → POI probe → J8.

[Collapsed](assets/2026-10-27-v2-retest/sidebar-collapsed.png), [expanded](assets/2026-10-27-v2-retest/sidebar-expanded.png), [scrolled baseline](assets/2026-10-27-v2-retest/sidebar-scrolled-baseline.png), [dense after wait](assets/2026-10-27-v2-retest/sidebar-selected-dense-delayed.png), [POI after wait](assets/2026-10-27-v2-retest/sidebar-selected-poi-delayed.png), [J8 after wait](assets/2026-10-27-v2-retest/sidebar-selected-j8-delayed.png).

Send **thanks** in that revealed J8 chat. Request `cad0cd96-d997-49e8-beba-0fe7f22e7275`, completed/saved in 8,055 ms; reply “You’re welcome!”. After completion, scrolling to the top confirms only J8 was promoted; other chats retain their relative order and revealed rows remain available. Promotion was already visible while the request was active; this is the final-order verdict, not a claim that promotion waits for completion.

[Active request](assets/2026-10-27-v2-retest/sidebar-send-active.png), [completed at retained scroll](assets/2026-10-27-v2-retest/sidebar-send-completed-scrolled.png), [completed top order](assets/2026-10-27-v2-retest/sidebar-send-completed-top.png), [saved request](assets/2026-10-27-v2-retest/sidebar-send.json).

### Popup layering — PASS for observed cases

Used saved Normal beacon/glass chat `e5dab5e5-3156-4efc-ad84-914278dccb84` and Detailed beacon chat `262c501b-abc6-48fc-8c4e-94c693e5b120`. Normal/medium selections were retained; pickers were opened and dismissed without selecting another value.

- **Model picker:** drawn over ingredient chips and the beacon's input item sprites. [Baseline chips/card](assets/2026-10-27-v2-retest/layer-chips-and-card.png), [picker](assets/2026-10-27-v2-retest/layer-model-chips-card.png). The separate glass-card view also overlaps the model rows: [screenshot](assets/2026-10-27-v2-retest/layer-model-picker.png).
- **Effort picker:** rows cover the glass output and beacon output region above the underlying items; no item renders in front of the popup. [Glass](assets/2026-10-27-v2-retest/layer-effort-picker.png), [chips/beacon](assets/2026-10-27-v2-retest/layer-effort-chips-card.png).
- **Toolbar tooltip:** moved the card under Current gameplay, then hovered the toolbar icon using a drag from empty content, without toggling controls. The tooltip occludes the glass card's item region. [Before](assets/2026-10-27-v2-retest/layer-tooltip-card-position.png), [tooltip](assets/2026-10-27-v2-retest/layer-toolbar-tooltip.png).
- **Source tooltip:** hovered the saved Detailed Beacon source chip; the source panel draws over the lower glass-card region with clean text and surface. [Before](assets/2026-10-27-v2-retest/layer-source-baseline.png), [hover](assets/2026-10-27-v2-retest/layer-source-tooltip.png). This source capture overlaps the lower card/fuel-slot region; the toolbar capture above supplies direct sprite occlusion evidence.

No layering or selection-stability bug was observed. This does not reopen or close cold-opening timing exceptions or earlier unrelated checks. Main captures use the original 1024×768 client / GUI scale 2. A later maximized view was inspected and restored; an attempted outward resize was rejected by the computer-use API before a resize, and is not acceptance evidence.

### Final state

Original window restored. Minecraft running/unpaused in PackSage Adapter Filter Live Test, chat/settings closed, Answer detail **Normal**, developer override null and effective prompt unchanged. [Settings](assets/2026-10-27-v2-retest/final-settings-normal.png), [world](assets/2026-10-27-v2-retest/final-world.png). No mod changes, formatter, push or merge.
