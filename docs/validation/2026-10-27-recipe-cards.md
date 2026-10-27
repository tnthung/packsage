# Recipe cards: live acceptance

Date: 2026-10-27. Tasks 8b and 9 of the [implementation plan](../plans/2026-10-27-recipe-cards.md). Player steps: [testing checklist](../testing/2026-10-27-recipe-cards.md).

Status: in progress. Category comparisons and the candidate 14 matrix are recorded below. Candidate 15 is being tested; it has not been promoted. The remaining failures and unverified interactions are separate from the passes.

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

## Chat-opening timings

Used P from gameplay, with the completed six-card conversation `2a562715-dbe3-4598-8273-e1290f4146a7` selected. The five warm samples are consecutive logged opening requests at 08:26:41, 08:26:44, 08:26:46, 08:26:49 and 08:26:51. From `client_open_request.start_ns` to `first_frame_rendered.event_ns`: **34.6892, 45.0522, 36.2778, 37.4595 and 32.9329 ms**. All have `loading=false`. PASS against the prior approximately 11–50 ms range for these warm samples. First-frame logging measures render-call completion, not GPU presentation or time before the common key handler.

The first real P opening after launch was **55.5631 ms** (08:19:36); its screen-construction-to-frame portion was 52.5105 ms. This cold sample is outside the earlier 50.383 ms maximum and is retained as a timing acceptance exception, not hidden by the warm results. Bridge-selected openings are excluded from the five key samples. Raw stage lines: [opening-stages.log](../../build/recipe-cards-live/opening-stages.log).

## Remaining failures and unverified checks

### UI-1: wheel scrolling leaves a hovered result preview visible

FAIL. Reproduce with the candidate 14 Concise beacon answer above: hover its green result chip, then scroll one wheel step while the pointer remains on the chip. Expected: scrolling dismisses the floating card. Actual: the card remains visible after the wheel event and in a later stationary capture. This short chat has no overflowing history, so the content does not move. Evidence: [before](assets/2026-10-27-recipe-cards/result-hover.png), [after](assets/2026-10-27-recipe-cards/result-after-scroll.png). No rendering code was changed.

### Unverified interactions

- NOT RUN: sustained Shift for three seconds, freeze without a release jump, pinned panel slot tooltips, release outside chip/panel, and pinned panel click-through protection. The available computer-use API exposes complete key chords but no key-down/key-up or timed hold operation. An ordinary Shift tap cannot establish these behaviors.
- NOT RUN: missing-item barrier and `Missing:` tooltip. No naturally missing registry item was present in these recorded recipes.
- NOT RUN: custom-ingredient `?` tooltip. The trim unknown-output placeholder was exercised, but it is a different case. No loaded recognized native recipe with a custom ingredient was found in the selected checks; unsupported Nature's Aura recipes remain source-only by design.
- Candidate 14 prompt failures are retained above. Candidate 15 and installed-default acceptance are pending.
