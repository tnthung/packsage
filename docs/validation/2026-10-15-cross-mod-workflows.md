# Cross-mod player workflow checks

## Scope and verdict

Tested through the Minecraft UI in `OoO - PackSage Trial`, ATM10 8.2, using the installed guide/manual artifact SHA-256 `30BE0F097A213A92266289BBCE4671C1EFFCA17FEEC0458C0BD4D12CBCDAE05C`. Model remained GPT-6-Luna, medium reasoning, screenshots enabled, search EXPLICIT_ONLY. No web calls were requested or observed. No code or model settings changed.

**End-to-end acceptance is not complete.** Five ordinary player turns across three scenarios exposed retrieval and answer-grounding failures. A held-bundle crafting follow-up successfully combined current Minecraft inventory, nested storage and an Actually Additions recipe from JEI. Two additional tool-coached diagnostic turns are excluded from player-facing acceptance: regular players must not need to name tools, providers, cursors or handles.

This extends the narrower [guide/manual source checks](2026-10-15-guide-manual-adapters.md). Those checks establish individual source reads, not reliable multi-source answers. Actual machine construction, energy transfer, crystal conversion and fall damage were not exercised; these are assistant workflow tests, not proof of physical mod interoperability.

## Reproduction and observations

### A. Actually Additions + Powah + recipes + inventory + quests

Start a fresh chat and ask:

> I want to make Actually Additions crystals with an Atomic Reconstructor powered by Powah. Using this installed pack, check both mods guides, the loaded recipes for a minimal setup, my current inventory for missing ingredients, and any related quest progress. Explain what is verified versus only suggested; do not use the web.

The recorded prompt had a `CROSS_MOD_A:` test label. It did not prescribe tool calls.

Expected: retrieve relevant instructions and recipes, compare current quantities, read relevant quest progress if discoverable, and distinguish proposed power compatibility from demonstrated behavior.

Observed request `b90ec1d7-2d8f-4c66-b4b0-52927e0c9147`: **incomplete**. The assistant searched literal keyword lists such as `Actually Additions Atomic Reconstructor laser crystal empower recipes Powah power setup`. It abandoned partial guide, registry and recipe searches with continuations. It reused an FTB cursor after changing the query, correctly receiving `INVALID_CURSOR`. AA traversal returned an explicit extraction failure. The inventory read succeeded. The final answer acknowledged missing guide/recipe/progress evidence and did not claim verified power compatibility, but did not deliver the requested setup.

### B. Artifacts + Reliquified Artifacts + Relics + equipment

Start another fresh chat, without diagnostic history:

> Which is better for surviving falls, an Artifacts Umbrella or a Relics Leafy Mantle? How do they work in this pack, and does anything I am wearing already protect me?

Expected: compare installed ability descriptions, including the mantle's protection when landing on leaves; distinguish documentation from rolled item stats; constrain equipment claims to inspected slots.

Observed request `951e0125-d06e-4352-a56b-85eb3b6307c3`: **incorrect answer**. The guide queries were literal phrases `Artifacts Umbrella fall damage` and `Relics Leafy Mantle fall damage`; both returned zero records. The assistant did not simplify either query. It then inferred behavior from registry ability names, saying the mantle's camouflage/revival were not fall-related. Previously observed installed camouflage text explicitly says it prevents fall damage when landing on tree leaves. The answer also attributed fall-speed/bounce stats to a registry record that only listed ability identities.

Vanilla armor inspection succeeded and showed Unobtainium armor with no enchantment levels listed. This does not establish protection or absence of protection from Curios accessories: the reported inventory source is vanilla inventory/equipment, and no Curios slot read occurred. The comparison is not accepted.

### C. Actually Additions recipe + carried storage + changes between turns

Add three iron ingots in the trial world using `/give @s minecraft:iron_ingot 3`. Start a fresh chat:

> Can I make an Atomic Reconstructor with what I am carrying, including the stuff inside my bundle and shulker box? Tell me exactly what I still need, and what I could use it for once I build it.

Observed request `76d17c00-4ff3-46c3-9100-d399db6ada7b`: **partially fulfilled**. It correctly counted the JEI recipe as four iron ingots, four redstone and one iron casing, and reported the current three carried iron. However, it tried opened-container access instead of inspecting carried storage items, so the requested bundle/shulker contents remained unexamined. It disclosed that limitation. Guide retrieval also remained unsuccessful; the general laser/energy explanation was not verified by a successful guide read in this turn.

Create a new controlled bundle, leaving existing storage unchanged:

```text
/give @s minecraft:bundle[minecraft:bundle_contents=[{id:"minecraft:iron_ingot",count:1},{id:"minecraft:redstone",count:4},{id:"actuallyadditions:iron_casing",count:1}]] 1
```

Hold the new bundle and ask:

> I picked up another bundle and I am holding it now. Does that give me enough to make it?

Observed request `a9408aef-12e2-4df6-8b05-da8759576a3a`: **pass for ingredient sufficiency**. Without tool coaching, the assistant freshly read the held item contents, player inventory and JEI recipe. It correctly combined three carried iron with one bundled iron, four bundled redstone and one bundled casing. It said the ingredients were sufficient and retained the recipe-decoding caveat. The other storage contents were unnecessary to establish sufficiency. This did not actually craft the machine.

While still holding only the new fixture bundle, replace its contents:

```text
/item replace entity @s weapon.mainhand with minecraft:bundle[minecraft:bundle_contents=[{id:"minecraft:redstone",count:4},{id:"actuallyadditions:iron_casing",count:1}]] 1
```

Ask:

> I changed what is in the bundle. Can I still make it?

Observed request `01f662bf-8927-4c0a-871c-387466a1637a`: **inventory freshness passes; recipe refresh incomplete**. Current held storage contained only redstone and casing; current player inventory still had three iron. The answer stopped claiming sufficiency and correctly reported one iron short relative to the previously established recipe. This turn's recipe queries returned no current record, which the answer disclosed. It did not recover the successful exact-output JEI query from the prior turn. Do not count this as fully refreshed recipe verification.

## Diagnostic-only findings

The two coached turns in scenario A are retained as debugging evidence, not acceptance:

- `cfb5c928-27fa-43ec-a792-5b9257f6fde8`: explicit literal queries and exact recipe IDs recovered AA guide text and both JEI recipes. The assistant miscounted the Reconstructor as **3 iron + 5 redstone + 1 casing**. The nine returned input slots actually show iron at slots 1, 3, 7, 9 and redstone at 2, 4, 6, 8, each quantity one. Powah Furnator totals were correctly counted as five dielectric paste, two tiny basic capacitors, one dielectric casing and one furnace. The assistant also passed a pagination cursor to `read_progress`, correctly receiving `INVALID_REFERENCE`.
- `586cecc3-4398-4d6e-9d0a-ea1add615fb4`: an old guide handle was rejected with `INVALID_REFERENCE`, followed by successful fresh discovery/read. Current inventory correctly showed the newly added three iron, but the assistant repeated the wrong recipe totals and incorrectly said the iron requirement was satisfied. Freshness worked; arithmetic did not.

Independent replay/source review traced the AA terminal extraction error to textless Patchouli crafting pages. `ActuallyAdditionsGuideReader` requests an entry fragment without source fields. For a page with recipe fields but no text, `PatchouliLiveReader` can construct an empty traversal root and fail its no-progress guard. Reflection masks the cause as `Actually Additions booklet page could not be read`; `GuideDocumentProvider` stops without a continuation for that non-budget extraction failure. The installed crystals entry has 18 pages; observed fragments stop after page 11, before textless crafting/decompression pages. Runtime visibility of every remaining page was not independently established.

## Open findings

- [ ] Fix AA textless-page traversal so a supported page without textual content does not terminate later discovery; preserve actionable extraction causes.
- [ ] Improve ordinary document query selection and recovery from empty literal matches; do not infer item behavior from ability names after failed retrieval.
- [ ] Correct recipe-slot aggregation reliability. Source slot counts were correct; two diagnostic answers miscounted them.
- [ ] Keep search cursors distinct from document handles and continue partial searches with unchanged arguments.
- [ ] Discover carried storage through item targets when a player asks about bundle/shulker contents, without requiring them to hold each container first.
- [ ] Make equipment coverage limitations clear when accessory slots were not inspected.
- [ ] Recover recipe queries on follow-up before presenting historical recipe requirements as currently verified.

No fixes were applied during this test run. These are observed follow-up items, not reopened claims that the individual tools never work. The existing automated suite was not rerun for this documentation-only change.

## Evidence and final world state

Saved replays are under the ATM10 instance's `packsage/OoO_-_PackSage_Trial-814a1737fe55fba5/` directory:

| Scenario | JSONL file |
|---|---|
| A and its diagnostic retries | `67ae0372-23a9-49d4-9f5f-f9c65af210ee-13fa694cd5671765.jsonl` |
| B | `04e8342f-2701-41e4-bda2-76fd7aa7e16e-f37cb8c2840c9aa0.jsonl` |
| C and both natural follow-ups | `2e2bce9e-437e-4d21-b323-116e7165969f-8efcd4e78160cfe5.jsonl` |

Independent reviewer audited the saved calls/results for A, both A diagnostics, B and C. The report distinguishes source failures, model mistakes, correct explicit rejections and untested behavior.

The trial world is paused, original hotbar selection restored to slot 9. Test additions remain in previously empty hotbar slots: three iron in slot 5 and the new bundle in slot 6, containing four redstone and one iron casing. Existing bundle/shulker contents and original world were not modified. Model/search settings remain unchanged.
