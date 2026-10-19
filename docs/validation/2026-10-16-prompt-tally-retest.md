# In-game recipe-tally prompt experiment

Date: 2026-10-16. Continue the [natural-player workflow](../testing/2026-10-15-natural-player-conversations.md) using the [developer prompt editor](2026-10-15-developer-prompt-settings.md). The user declined an autonomous test runner; no runner or production code was added.

## Setup

- Installed ATM10 JAR SHA-256: `6F9BB52C020F864BF74C75B06D426C77ECE2B43B828A9ECC1433D9A613EE3787`, checked during this run. Repository HEAD was `f7e7b1e`.
- World: `OoO - PackSage Trial`; fresh chats, GPT-6-Luna, Low reasoning, screenshots enabled, automatic web search off. Earlier recorded failures used Medium reasoning, so this is not a controlled before/after comparison.
- The user opened developer settings because the desktop-control API cannot hold Ctrl during a mouse click. Restore default populated the draft; the candidate below was then entered and saved. Reading the saved settings confirmed an exact match: 10,681 characters, UTF-8 SHA-256 `9b1cc89af74232947af04b5821cb6a9ff8a6afae30fba4554c9da3f0442d4d9d`.
- The candidate was the shipped default with this paragraph inserted immediately before `Truncated ingredients,`:

> Before reporting recipe quantities, tally the returned recipe.inputs by groupId. Each non-empty group is a separate required input slot; alternatives within that group are OR choices. Choose one valid alternative per group, add its quantity to the selected material total, and count repeated groups separately. Reconcile the totals against every required input group and the output yield before answering. Use the fresh returned slots, not a remembered recipe or quantities from an earlier answer. This tally does not make incomplete ingredients complete.

The player agents received their private gameplay goals and actual assistant replies only. `luna_high_recipe_evidence` independently audited the saved tool results. No crafting, inventory mutation, machine repair or other physical outcome was performed by the agent.

## Storage conversation

Player agent: `luna_high_player_backpack`. Saved session file: `bc09d654-e071-48be-b7b6-0f899a38b539-aeabb11ae78ddf1b.jsonl` under the instance's `packsage/OoO_-_PackSage_Trial-814a1737fe55fba5` directory.

1. Request `43d3d972-a306-4724-8a5f-b87f65385d31`: “My inventory keeps filling up. What’s a cheap storage upgrade I could make with what I have?” The answer recommended using 55 owned empty chests or the existing shulker. Complete direct inventory and resolved storage reads support the chest count/emptiness and the shulker's five cobblestone plus two sticks. The reviewer flagged “the cheapest upgrade” as stronger wording than the bounded comparison established; using owned chests without crafting was supported.
2. Request `5567b334-4c5e-4f28-b2c1-c22ffe1b7f4c`: “I’d rather carry the extra storage with me. Do I have what I need to make a backpack?” The answer recommended a bundle and declined to confirm backpack materials. The complete bundle read supports one iron ingot, four redstone and one iron casing. Remaining capacity was an inference, not an explicit returned field. The recipe investigation stopped prematurely: broad JEI results contained other backpack upgrade outputs and a continuation call, but the assistant neither continued nor searched the exact backpack output. It had not established that the needed recipe was unavailable.

The player accepted the bundle suggestion. No ingredient totals were produced, so this conversation did not exercise the tally hypothesis or pass the backpack-crafting goal. The player's final acceptance was recorded as a judgment and was not submitted as another in-game question.

## Focused backpack conversation

Player agent: `luna_high_player_backpack_craft`. Saved session file: `42c728a2-e12d-40ec-9fb8-df2d593a4325-c3c6ae0e3479b55e.jsonl` in the same directory.

Request `2b4d4815-89c3-4ff0-89d5-d390cfe5a396`: “How do I craft a basic Sophisticated Backpacks backpack, and what ingredients do I need?” The answer claimed one chest, three leather “in the remaining middle-column spots,” and five string.

The exact output-item lookup returned canonical recipe `sophisticatedbackpacks:backpack`, reference `jei:jei/1/5/minecraft:crafting/recipe-sophisticatedbackpacks:backpack-1`. Its nine nonempty INPUT groups each require quantity one:

- String-family alternatives: slots 1, 3, 4 and 6.
- Leather/ersatz-leather alternatives: slots 2, 7, 8 and 9.
- Chest-family alternatives: slot 5.

The returned tally is four string, four leather and one chest. The answer's totals contradict that evidence; its placement explanation is also inconsistent. The record remains marked PARTIAL, but it has no payload error and all nine groups are present. Neither partial search coverage nor other opaque matches explains this miscount.

The player accepted the incorrect totals and said they would gather them. No gathering happened, and that acceptance was not submitted as another in-game question. Factual acceptance fails independently of player satisfaction.

## Outcome

The tally-only prompt experiment failed to prevent the known counting error. Do not promote it to the shipped prompt. The storage conversation also exposed an unresolved early-stop behavior in recipe discovery.

After the user reopened developer settings, Restore default and Save completed successfully. Reading the persisted settings confirmed that the `developerPrompt` property was absent, clearing the experimental override. No production source or tool schema changed. No build or automated test suite was run for this settings-only live experiment. The broader gameplay regression batch remains incomplete.

## Visible-tally revision

The user requested continued iteration. The next candidate replaced the first addition with:

> When giving a recipe ingredient list, show a compact tally in the answer with columns Ingredient, Quantities from input slots, and Total. Build each row from the actual returned input groups of the selected recipe: choose one valid alternative per group and include the quantity of every group that uses that ingredient. For example, four separate quantity-one groups contribute 1 + 1 + 1 + 1 = 4. Alternatives inside one group are choices, not additional required slots. Derive the final material list from this visible tally, not from a remembered recipe or an earlier answer. The tally does not make incomplete ingredients complete. Give crafting-grid positions only when the returned evidence establishes the layout; group IDs alone are not coordinates.

The saved override exactly matched the candidate: 10,883 characters, UTF-8 SHA-256 `d7c8d6fe4218c8d12f34f6ea922e7741c1bec192c9948b4f07320b8d58d1adf4`. `luna_high_backpack_visible_tally` supplied the opening: “What materials do I need to gather to craft a basic Sophisticated Backpacks backpack?” The same opening was replayed unchanged in fresh chats for the reasoning comparisons; those repeats were controlled probes, not new adaptive player conversations. Screenshots remained enabled and automatic search remained off.

- **Low, failed count:** request `b1ac020a-71f5-4045-82fb-10e3a9682e09`, session `019b491c-a70d-4a35-8158-05916b9b21d6-c20ffc97d2e43027.jsonl`. The answer visibly added five string quantities, four leather quantities and one chest. The exact recipe and its subsequent exact-reference reread both contain four string groups, four leather groups and one chest group. The player accepted the incorrect answer; acceptance was not submitted as another in-game question.
- **Medium, correct count:** request `ba370439-88f5-4ef8-8b83-2e3465c332eb`, session `c36f9494-07f2-4043-adf2-57c0cc4e9c2a-66f5259270547997.jsonl`. The answer gave four string, four leather and one chest, matching all nine returned groups, and qualified unknown alternatives/layout. Independent evidence review confirmed the counts. This one success did not establish reliability.
- **Medium repeat, failed discovery:** request `574faaa2-6852-45cb-b14a-29faa26e172a`, session `c44eb97b-4d3e-4381-90eb-d483c6662759-a09a5b14e0953f90.jsonl`. The answer said the recipe could not be found and asked the player to search the viewer. The native exact-output query was PARTIAL with a continuation, and JEI was queried first without an output filter and then with the item ID in `text`; both JEI results were also PARTIAL with continuations. No exact-output JEI query was made. The absence conclusion was not established.
- **High, failed count:** request `dbab6afc-d8ae-4d7c-827d-53aee4817aae`, session `35e0325e-f7d0-4355-91fe-22c685a3abc1-174d06307f7fa2df.jsonl`. The answer gave six string, three leather and one chest. The fresh target recipe still has string groups 1/3/4/6, leather groups 2/7/8/9 and chest group 5, quantity one each. Higher effort did not prevent invented tally contributions.

The visible grouped table did not provide a reliable fix. No shipped prompt change is justified by these results. A per-input-row transcription experiment is being prepared; the visible-tally override and High reasoning are still active at this point in the record.

## Per-input checklist revision

The third candidate replaces the earlier addition with:

> When answering a recipe quantity question, first show an ingredient checklist with one row for each input group of one selected recipe. Copy each groupId exactly once, with one chosen alternative's item name and returned quantity. Do not merge rows at this stage. Alternatives within a group are choices; do not create extra rows for alternatives, outputs, or catalysts. Then give a shopping list by adding only the quantities in those displayed rows, grouped by the chosen ingredient. Check that every returned input group appears exactly once and that each shopping-list total agrees with its checklist rows. If input details are missing, keep the ingredient list incomplete. Input group IDs identify evidence rows, not crafting-grid coordinates; give grid positions only when explicit layout evidence supports them.

The saved override exactly matched the candidate: 10,945 characters, UTF-8 SHA-256 `23cc37a1e28a577780511982835deed9af87de20f80ff44bea2b61da4864ad1e`. Reasoning remains High, with the same player-authored backpack question in fresh chats.

- **First High trial, correct:** request `255ce952-48f7-4add-a8fc-10a68a7081bc`, session `4d0c1309-59c2-4bda-85a5-d8fca99e8253-90b5b3d15fa76d11.jsonl`. The answer enumerates all nine input groups individually, with string in 1/3/4/6, leather in 2/7/8/9, and chest in 5, quantity one each; its shopping list correctly totals 4 string, 4 leather and 1 chest. Independent audit confirms every row against exact reference `jei:jei/1/14/minecraft:crafting/recipe-sophisticatedbackpacks:backpack-1`, one backpack output, and supported substitute wording. The recipe remains PARTIAL; no exhaustive alternative or layout claim was made.
- **Second High trial, count correct but citation invalid:** request `b65c2da1-714b-440e-b015-94464a462a91`, session `f3247598-d365-41dc-9604-8c66b99c1b02-110ff557f0d2ffb0.jsonl`. All nine rows and 4/4/1 totals again match the returned recipe and its exact-reference reread. The answer instead cites `[jei:sophisticatedbackpacks:backpack]`; the actual returned reference is `jei:jei/1/15/minecraft:crafting/recipe-sophisticatedbackpacks:backpack-1`. Independent review found the shorthand does not resolve because the client matches the exact source handle. The UI displays a plain provider citation instead of the working Recipe chip seen in the first trial.

### Furnace regression

Player agent `luna_high_furnace_count_player` supplied: “How many regular furnaces can I make with what I’m carrying?” Request `634c6860-e7fc-42a0-af61-b497dff9205c`, session `c2b908e6-82c9-494f-a854-18f0d327f0e6-3a1594cc5494223a.jsonl`, used the same checklist override and High reasoning.

The answer gives eight furnaces from 71 cobblestone, using 64 and leaving seven. Fresh complete inventory evidence gives 61 cobblestone in main slot 1; complete storage reads give five in the Bundle in main slot 0 and five in the Shulker Box in main slot 4. The other Bundle has no cobblestone. Distinct owner slots rule out double counting. Exact JEI reference `jei:jei/1/17/minecraft:crafting/recipe-minecraft:furnace-1` returns eight consumed cobblestone inputs of quantity one and one furnace output; slot-5 is an empty group. Both root inspection and independent review confirm the arithmetic and returned references. The player accepted the answer; this acceptance was not submitted as another game turn.

Remaining issues: the answer scales and compresses all eight IDs into a sentence rather than transcribing one returned-quantity row per group. Each Markdown slot link renders as a repeated Recipe chip, hiding the slot labels and cluttering the answer. “You can make 8” also assumes access to a crafting station; ingredient capacity is supported, but station access was not established.

### Current disposition

The per-input checklist produced correct backpack counts in two fresh High-reasoning trials and correct furnace material arithmetic in one inventory trial. This is a promising local result, not a reliability guarantee or a full acceptance pass: one backpack citation failed to resolve, the furnace checklist presentation is poor, and crafting-station availability was unverified. The prompt wording also needs an explicit empty-group rule before general adoption.

The checklist override remains active in-game with High reasoning, screenshots enabled, and automatic search off. No shipped source, tool schema, build artifact, or Git index was changed in this round. Validation notes remain uncommitted. No build or unit suite was run for these settings-only live trials.
