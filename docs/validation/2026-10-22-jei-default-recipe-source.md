# JEI default recipe source validation

Date: 2026-10-22.

Validates the [JEI default recipe source](../specs/2026-10-22-jei-default-recipe-source.md) specification, implemented by its [plan](../plans/2026-10-22-jei-default-recipe-source.md).

## Automated checks

`gradlew build` passed with 853 tests.

- `KnowledgeServiceTest.unqualifiedRecipeSearchReadsOnlyTheFirstAvailableRecipeSource`: with the first recipe source available, an unqualified search queries only that source and is COMPLETE. With it unavailable, only the next source is queried, the result is COMPLETE with no source note, and a qualified search of the unavailable source fails as unavailable. Document discovery still combines sources.
- `KnowledgeServiceTest.recipeContinuationIsRejectedAfterTheDefaultSourceChanges`: a continuation issued for one default source is rejected with `INVALID_CURSOR` after the default changes.
- `AssistantIntegrationTest`: a second recipe source is reached through a provider-qualified search, and a failing default source returns a PARTIAL result naming it while the other source stays reachable by provider.
- `PackSageToolsTest`: the `search_recipe` description names the default source.

## In-game acceptance

Runs below used the installed default prompt with the developer prompt override cleared in `config/packsage.properties`. The prompt was not iterated during this run.

- [x] With JEI installed, the torch question makes unqualified `search_recipe` calls, returns a JEI recipe, makes no native `search_recipe` call, and offers a JEI link that opens the torch recipe.
- [x] Without JEI, the torch question returns a native recipe with no JEI-unavailable note; its PARTIAL coverage is due to the bounded native scan, not JEI availability.
- [ ] The installed default prompt, with the developer override cleared, consistently selects the available default for recipe searches. The JEI brewing check A2 explicitly selected `provider: native`.

### Test environment

- Minecraft 1.21.1, NeoForge 21.1.259; Nature's Aura 41.10; JEI 19.27.0.340 when installed.
- The installed JAR was `packsage.jar`, SHA-256 `B9093D4E932AF1AF7D01BB3896E1C9FAADBDC27EB8F0AF9047627AFB737BDF6B`; it matched `dist/packsage.jar` before and after both runs.
- `scripts/build.ps1` built and installed it with `JAVA_HOME=D:\Project\packsage\.tools\jdk\jdk-21.0.12.1`; Gradle reported BUILD SUCCESSFUL. The build's automated check record reports 853 tests.
- The initial CurseForge profile contained additional mods and a different JEI JAR. Those files were preserved at `C:\Users\hung1\curseforge\minecraft\Instances\Packsage\mods-pre-minimal-2026-10-22`; the active `mods` folder was aligned to `instances/packsage-minimal/mods` plus the installed `packsage.jar` before A1. JEI was moved to `C:\Users\hung1\curseforge\minecraft\Instances\Packsage\jei-no-jei-test-2026-10-22.jar` for B1/B2, then restored. The final active `mods` file list matches the fixture plus `packsage.jar`.
- A1/A2/A3 ran with JEI (10 mods); B1/B2 ran without JEI (9 mods). Each question was sent in a separate new PackSage chat in the existing `PackSage Adapter Filter Live Test` world.
- Saved chats are under `C:\Users\hung1\curseforge\minecraft\Instances\Packsage\packsage\PackSage_Adapter_Filter_Live_Test-e341466b58d81012`. Call arguments and returned tool data below were read from those JSONL files. Response times are approximate: `sentAt` to the JSONL file's last-write time.

### Per-check results

#### A1 — JEI torch recipe — PASS

Question: `How do I craft a torch?` Request `0f1fb314-192f-4870-abf3-c402f651b68f`, JSONL `a1d808e8-4fbe-4f72-90a1-467fccc116ff-57754f77e5b3e355.jsonl`; response time approximately 40 seconds.

Calls and results, in order:

1. `search_recipe` `{"filter":{"text":null,"outputItem":"minecraft:torch","provider":null},"limit":16,"cursor":null}` — PARTIAL, zero records; JEI scan pending category 0, recipe 156; continuation cursor `35bb9540-a97e-46cc-81a5-55fefdda92a2`.
2. `search_recipe` `{"filter":{"text":null,"outputItem":"minecraft:torch","provider":null},"limit":16,"cursor":"35bb9540-a97e-46cc-81a5-55fefdda92a2"}` — PARTIAL, zero records; JEI scan pending recipe 312; continuation cursor `4ad25a74-852e-47f3-8a0a-ab088f921fff`.
3. `plan_recipe` `{"identity":"minecraft:torch","quantity":4,"depth":1}` — PARTIAL, one native plan record (`minecraft:torch`), detail `bounded-live-recipe-scan`.
4. `search_recipe` `{"filter":{"text":null,"outputItem":"minecraft:torch","provider":null},"limit":16,"cursor":"4ad25a74-852e-47f3-8a0a-ab088f921fff"}` — PARTIAL, one JEI `Crafting` record (`minecraft:torch`); JEI scan pending recipe 467; continuation cursor `25a379dc-b945-4464-997d-38cf9904f308`.

No native `search_recipe` call was made. The separate native `plan_recipe` call was PARTIAL. The reply was: “Put **one coal or charcoal above one stick** in a crafting grid to make a torch. [View the recipe](jei:jei/1/3/minecraft:crafting/recipe-minecraft:torch-331)”. The visible link tooltip identified JEI `Crafting` with PARTIAL coverage. Clicking it opened the torch recipe in JEI with coal or charcoal and a stick as inputs and a torch as output. Pass.

#### A2 — JEI Potion of Strength brewing — FAIL

Question: `How do I brew a Potion of Strength?` Request `1df7495d-0dcd-4d19-aa44-eab16bc6e411`, JSONL `2863dfe5-f4a9-41d8-82a2-0289c22ea217-9fefad7425edb0f5.jsonl`; response time approximately 29 seconds.

Calls and results, in order:

1. `search_registry` `{"registry":"potion","filter":{"text":"strength","ids":null,"tags":null,"provider":null},"limit":20,"cursor":null}` — COMPLETE, three native potion records: Strength, Long Strength, and Strong Strength.
2. `plan_recipe` `{"identity":"minecraft:potion","quantity":1,"depth":2}` — PARTIAL, 43 native records; detail `tool output limit reached; narrow the lookup`.
3. `search_recipe` `{"filter":{"text":"Strength","outputItem":"minecraft:potion","provider":"native"},"limit":20,"cursor":null}` — PARTIAL, three native brewing records; detail `scanned=0-1820 of 1820 (ordinary recipes 1539, brewing candidates 281); phase=brewing; matches=3`. The records were Awkward-to-Strength with Blaze Powder, Strength-to-Long-Strength with Redstone, and Strength-to-Strong-Strength with Glowstone.

The source pills were `Awkward Potion brewing rule` and `Strength brewing rule`; there were no JEI records or navigation targets. The exact reply was:

```text
To brew a **Potion of Strength** in a brewing stand:

1. Brew a **Water Bottle** with **Nether Wart** to make an **Awkward Potion**. [Awkward Potion brewing rule](native:plan|minecraft:potion|1|2|3|brewing:potion:2:0:ad10f4132bd49fb0ab6a283906a2af643b3425203f0198af41fd487c1c56854a)
2. Brew the **Awkward Potion** with **Blaze Powder** to make a **Potion of Strength**. [Strength brewing rule](native:brewing:potion:56:0:a64c644e3270a011d75090087f73e25b9166d94fc033acdbb49a0831360048d5)

You can put up to three bottles in the stand at once; each brewing step uses one reagent for the batch. [Strength brewing rule](native:brewing:potion:56:0:a64c644e3270a011d75090087f73e25b9166d94fc033acdbb49a0831360048d5)
```

The reply was useful, but it explicitly queried `provider: native` while JEI was installed. No JEI brewing card was opened. Fail for the JEI-source check.

#### A3 — Nature's Aura Gold Powder — FAIL (useful answer; expected JEI category not returned)

Question: `How do I make Gold Powder?` Request `43804695-879d-4942-8b71-193b27aeee38`, JSONL `6de7e92c-5667-46e0-8ff6-ffd8338d6205-3a9b37ddbd8b4b18.jsonl`; response time approximately 38 seconds. In JEI, Gold Powder had a `Natural Altar Infusion` category.

Calls and results, in order:

1. `search_registry` `{"registry":"item","filter":{"text":"Gold Powder","ids":null,"tags":null,"provider":null},"limit":32,"cursor":null}` — COMPLETE, one native Gold Powder item record.
2. `plan_recipe` `{"identity":"naturesaura:gold_powder","quantity":1,"depth":2}` — PARTIAL, two native recipe records; detail `bounded-live-recipe-scan`. One was `naturesaura:altar/gold_powder` with four Gold Powder outputs but PARTIAL decoding and unknown inputs/catalysts; the other was the Gold Leaf crafting recipe.
3. `search_recipe` `{"filter":{"text":null,"outputItem":"naturesaura:gold_powder","provider":null},"limit":32,"cursor":null}` — PARTIAL, one JEI `Crafting` record, category `minecraft:crafting`; detail `jei: JEI scan pending category 0, recipe 156 (retry required); discovery work bound reached; continue with nextCursor`; continuation cursor `8dd624b3-df95-4c0a-82d8-52b862ab3fde`.

The unqualified recipe search returned JEI, but did not return the `Natural Altar Infusion` category or an OPAQUE JEI record. The answer, `Craft **1 Gold Leaf** to get **2 Gold Powder**. Put the leaf by itself in a crafting grid. [View the recipe](jei:jei/1/6/minecraft:crafting/recipe-naturesaura:gold_powder-97)`, was useful for the crafting method. Its link opened that Crafting recipe (one Gold Leaf to two Gold Powder), not the Natural Altar Infusion card. Fail for the expected modded-category coverage and link.

#### B1 — No-JEI torch recipe — PASS

Question: `How do I craft a torch?` Request `f546ee07-d0a8-44a7-b4a7-f73e68926e94`, JSONL `1edeca84-d0b3-4a83-a04b-020b4d7567f7-fd3af234dceea06a.jsonl`; response time approximately 23 seconds.

Calls and results, in order:

1. `plan_recipe` `{"identity":"minecraft:torch","quantity":1,"depth":2}` — PARTIAL, one native plan record; detail `bounded-live-recipe-scan`.
2. `search_recipe` `{"filter":{"text":null,"outputItem":"minecraft:torch","provider":null},"limit":16,"cursor":null}` — PARTIAL, one native `minecraft:torch` record; detail `native: scanned=0-1820 of 1820 (ordinary recipes 1539, brewing candidates 281); phase=brewing; matches=1`.

The reply was: “Put **one coal or charcoal above one stick** in a crafting grid to make a torch. The loaded recipe lists one torch as its output. [Torch recipe](native:minecraft:torch)”. The visible source pill was `Torch recipe`; the tool result had no JEI-unavailable note, and its PARTIAL detail was from the bounded native scan. No JEI/navigation target was emitted. Pass.

#### B2 — No-JEI Potion of Strength brewing — PASS

Question: `How do I brew a Potion of Strength?` Request `ec037a08-bd75-4926-99ab-1c092ef02da1`, JSONL `2fe693d1-df83-4590-bcc5-118198aa64f6-9eee9a3b5393e47b.jsonl`; response time approximately 31 seconds.

Calls and results, in order:

1. `search_registry` `{"registry":"potion","filter":{"text":"Strength","ids":null,"tags":null,"provider":null},"limit":32,"cursor":null}` — COMPLETE, three native potion records: Strength, Long Strength, and Strong Strength.
2. `plan_recipe` `{"identity":"minecraft:potion","quantity":1,"depth":4}` — PARTIAL, 43 native records; detail `tool output limit reached; narrow the lookup`.
3. `search_recipe` `{"filter":{"text":"Strength","outputItem":"minecraft:potion","provider":"native"},"limit":16,"cursor":null}` — PARTIAL, three native brewing records; detail `scanned=0-1820 of 1820 (ordinary recipes 1539, brewing candidates 281); phase=brewing; matches=3`.

The source pills were `Awkward Potion recipe` and `Potion of Strength recipe`; no JEI navigation target or brewing link was emitted. The exact reply was:

```text
In a **brewing stand**, brew a Water Bottle with **Nether Wart** to make an Awkward Potion. Then brew the Awkward Potion with **Blaze Powder** to make a Potion of Strength. [Awkward Potion recipe](native:plan|minecraft:potion|1|4|3|brewing:potion:2:0:1fbaec2a05b224f954e2db87ff86d69559a5323519c654f5c93cc9b4848f9834) · [Potion of Strength recipe](native:brewing:potion:56:0:2bdac6a5238691c66bdee5a8cb1e2b31141be64ab77f1fe366af3248e91d0163)

You can put up to three bottles in the stand for each brewing step; one reagent is used per step. [Awkward Potion recipe](native:plan|minecraft:potion|1|4|3|brewing:potion:2:0:1fbaec2a05b224f954e2db87ff86d69559a5323519c654f5c93cc9b4848f9834) · [Potion of Strength recipe](native:brewing:potion:56:0:2bdac6a5238691c66bdee5a8cb1e2b31141be64ab77f1fe366af3248e91d0163)
```

No JEI records or JEI brewing link were present, as expected without JEI; the native answer was useful. Pass for the no-JEI fallback.
