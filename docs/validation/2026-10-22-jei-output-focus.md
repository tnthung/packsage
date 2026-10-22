# JEI output focus live validation

Date: 2026-10-22.

Validates the in-game acceptance checks in the [JEI output focus specification](../specs/2026-10-22-jei-output-focus.md) and [implementation plan](../plans/2026-10-22-jei-output-focus.md), task F4. Procedures follow the [Minecraft adapter reachability checks](../testing/2026-10-21-minecraft-adapter-reachability.md) and compare against the [JEI default recipe source validation](2026-10-22-jei-default-recipe-source.md).

## Automated and install evidence

The plan's F3 evidence reports the full build passing with 854 tests. The saved Gradle test reports contain 854 test cases across 59 suites; JeIProjectionTest.outputSearchUsesTheJeiFocusOnlyWhenListedVariantsShareOneRecipeIdentity is present and passed (0.031 seconds). The requested scripts/build.ps1 build-and-install run completed with BUILD SUCCESSFUL; Gradle reported all 11 tasks UP-TO-DATE.

- JDK: D:\Project\packsage\.tools\jdk\jdk-21.0.12.1.
- Built and installed JAR: packsage.jar, SHA-256 96E12FD70C1AA5BA12E46778D91C75A98846648361D46E4255F25BABDE69363A. The dist and profile copies match. This differs from the prior round's B9093D4E932AF1AF7D01BB3896E1C9FAADBDC27EB8F0AF9047627AFB737BDF6B.
- The profile's active mods folder matched the seven JARs in instances/packsage-minimal/mods plus this packsage.jar. It contained JEI 19.27.0.340 and Nature's Aura 41.10; the Minecraft title screen reported 10 loaded mods. The mods-pre-minimal-2026-10-22 backup was left untouched.
- Profile: C:\Users\hung1\curseforge\minecraft\Instances\Packsage; world: PackSage Adapter Filter Live Test.
- The installed default prompt was used. config/packsage.properties had no developerPrompt override.
- Live game: Minecraft 1.21.1, NeoForge 21.1.259. The build metadata reports NeoForge 21.1.202; the launched CurseForge instance itself displayed 21.1.259.
- Each question used a new PackSage chat. The visible model setting was GPT-6-Sol / medium. Request duration is approximated from each JSONL sentAt to that JSONL file's last-write time, following the previous validation record.

Saved chat JSONL files are in C:\Users\hung1\curseforge\minecraft\Instances\Packsage\packsage\PackSage_Adapter_Filter_Live_Test-e341466b58d81012.

## Passing checks

### F-A1 — JEI torch recipe — PASS

- [x] PASS

Question: How do I craft a torch?

Request 6e366447-af2d-496d-a3ad-08adb8a18c1e, file 66d0033f-7fb8-494f-8e05-53a5e10fb7f0-53078ccb9082ea91.jsonl; approximately 21.9 seconds.

Tool calls from the saved JSONL, in order:

1. search_recipe — {"filter":{"text":null,"outputItem":"minecraft:torch","provider":null},"limit":16,"cursor":null}. Result provider jei, coverage/outcome PARTIAL, no detail and no next cursor. It returned one navigable RECIPE record, title Crafting, category minecraft:crafting, reference jei:jei/1/1/minecraft:crafting/recipe-minecraft:torch-1. This was the first page; no empty continuation page was used.
2. search_recipe — {"filter":{"text":null,"outputItem":"minecraft:torch","provider":"native"},"limit":16,"cursor":null}. Result provider native, coverage/outcome PARTIAL, detail scanned=0-1820 of 1820 (ordinary recipes 1539, brewing candidates 281); phase=brewing; matches=1. It returned one non-navigable native minecraft:torch record; nextCursor was null.

The exact reply was: “Put **one coal or charcoal above one stick** in a crafting grid to make a torch. You can use the 2×2 grid in your inventory. [View the torch recipe](jei:jei/1/1/minecraft:crafting/recipe-minecraft:torch-1).”

The JEI recipe link opened the JEI Crafting viewer, page 1/1, showing coal above a stick and a torch output. Pass: the first JEI output search found the torch on its first page and the reply's link opened it. There were two search_recipe calls total, one of them the successful JEI first page; the previous A1 made three JEI searches and took about 40 seconds. The JEI paging count dropped from three to one, and total response time was about 22 seconds.

## Inconclusive check

### F-A2 — Potion of Strength — JEI path not exercised

- [ ] INCONCLUSIVE — JEI path not exercised

Question: How do I brew a Potion of Strength?

Request 2e0842f5-3ccf-4372-b39b-b25e629a7869, file 159f4d76-a1ac-423e-b332-9fe842858b20-d43a718a42370d8f.jsonl; approximately 32.1 seconds.

Tool calls from the saved JSONL, in order:

1. search_registry — {"registry":"potion","filter":{"text":"strength","ids":null,"tags":null,"provider":null},"limit":32,"cursor":null}. Provider native, COMPLETE, no detail and no next cursor; three potion records: minecraft:strength, minecraft:long_strength, and minecraft:strong_strength.
2. search_registry — {"registry":"item","filter":{"text":"potion","ids":null,"tags":null,"provider":null},"limit":32,"cursor":null}. Provider native, COMPLETE, no detail and no next cursor; four item records: minecraft:potion, minecraft:splash_potion, minecraft:lingering_potion, and naturesaura:potion_generator (Lingering Absorber).
3. plan_recipe — {"identity":"minecraft:potion","quantity":1,"depth":4}. Provider native, coverage/outcome PARTIAL, detail tool output limit reached; narrow the lookup; 43 native plan records with no JEI categories, grouped by title: Awkward Potion brewing transformation (1), Thick Potion brewing transformation (1), Mundane Potion brewing transformation (9), Potion of Wind Charging (1), Potion of Oozing (1), Potion of Infestation (1), Potion of Weaving (1), Potion of Night Vision (2), Potion of Invisibility (3), Potion of Fire Resistance (2), Potion of Leaping (3), Potion of Slowness (6), Potion of the Turtle Master (3), Potion of Swiftness (3), Potion of Water Breathing (2), Potion of Healing (2), Potion of Harming (1), and naturesaura:altar/water (1). They did not provide JEI records; nextCursor was null.
4. search_recipe — {"filter":{"text":"strength","outputItem":"minecraft:potion","provider":"native"},"limit":16,"cursor":null}. Provider native, coverage/outcome PARTIAL, detail scanned=0-1820 of 1820 (ordinary recipes 1539, brewing candidates 281); phase=brewing; matches=3; nextCursor null. It returned three non-navigable native RECIPE records, each titled Potion of Strength brewing transformation and without a JEI category:
   - Awkward Potion plus Blaze Powder → Potion of Strength.
   - Potion of Strength plus Redstone → Long Potion of Strength.
   - Potion of Strength plus Glowstone Dust → Strong Potion of Strength.

The exact reply was:

> In a brewing stand, brew a **water bottle with Nether Wart** to make an Awkward Potion. Then brew that **Awkward Potion with Blaze Powder** to make a Potion of Strength. [Awkward Potion recipe](native:plan|minecraft:potion|1|4|3|brewing:potion:2:0:ec6269b0a1f546cf576ed126463facb77f16d333a5cc2b1e43086e7499bc20c8) [Potion of Strength recipe](native:brewing:potion:56:0:af522daec41ebeaa1a3a82dbcaf722b3b6d847bab094a1f258849e0215d6ecd6)
>
> For a longer-lasting version, brew the Strength potion with **Redstone Dust**; for Strength II, use **Glowstone Dust** instead. [Long Strength recipe](native:brewing:potion:57:0:27275fa71612aef4394dd9e15f1df24dc63cfc15bc9f73bfd0dbdd051743b070) [Strength II recipe](native:brewing:potion:58:0:7c740016db05ee7a8e468f6fb6e6f97de84c6ce3d63eee392f232b7d47c794e5)

The reply included native recipe references, but the JSONL had no JEI navigation targets. Clicking the Potion of Strength source citation opened a Minecraft source detail page, not the JEI viewer. It showed output Potion ×1, inputs Potion ×? and Blaze Powder ×?, authority LOADED and PARTIAL coverage, with client-potion-brewing and potion-brewing-rule provenance. No JEI brewing record or JEI link appeared.

The assistant explicitly chose provider native, as in the previous A2. The native path returned the expected brewing data, but this means the JEI potion variant scan was not exercised. This is an inconclusive result for the JEI-specific regression check; the native-provider choice is recorded separately as a prompt-compliance observation, not as a failure of the output-focus code change.

## Failed check

### F-A3 — Nature's Aura Gold Powder — FAIL

- [ ] FAIL

Question: How do I make Gold Powder?

Request a4423de6-cc27-443d-a2d9-e39e05064eaa, file b05ba2d6-0e95-4c9b-b33f-6fd41b350f6a-2f05b6dacabe45e0.jsonl; approximately 38.7 seconds.

Tool calls from the saved JSONL, in order:

1. search_registry — {"registry":"item","filter":{"text":"Gold Powder","ids":null,"tags":null,"provider":null},"limit":32,"cursor":null}. Provider native, COMPLETE, no detail and no next cursor; one item record, naturesaura:gold_powder.
2. plan_recipe — {"identity":"naturesaura:gold_powder","quantity":1,"depth":3}. Provider native, coverage/outcome PARTIAL, detail bounded-live-recipe-scan, no next cursor; two native plan records: naturesaura:altar/gold_powder (four Gold Powder output) and naturesaura:gold_powder (the crafting route). These were not JEI viewer records.
3. search_recipe — {"filter":{"text":null,"outputItem":"naturesaura:gold_powder","provider":null},"limit":32,"cursor":null}. Result provider jei, coverage/outcome PARTIAL, no detail and no next cursor; one navigable recipe record, title Crafting, category minecraft:crafting, reference jei:jei/1/4/minecraft:crafting/recipe-naturesaura:gold_powder-1. The expected Natural Altar Infusion category and OPAQUE JEI record were absent.
4. search_document — {"query":"gold leaf","filter":{"types":null,"provider":null},"limit":16,"cursor":null}. Provider patchouli, coverage/outcome PARTIAL, zero records; nextCursor e3b38d2c-4580-4d83-90f3-72caeffcbe2c. Detail: patchouli: bounded current guide evidence; narrow the query or continue discovery (repeated seven times), then discovery work bound reached; continue with nextCursor. The assistant did not continue this cursor.
5. plan_recipe — {"identity":"naturesaura:gold_leaf","quantity":1,"depth":2}. Provider native, coverage/outcome PARTIAL, detail bounded-live-recipe-scan, zero records and no next cursor.

The exact reply was: “Craft **1 Gold Leaf** to get **2 Gold Powder**. Put the leaf in a crafting grid; the [Gold Powder recipe](jei:jei/1/4/minecraft:crafting/recipe-naturesaura:gold_powder-1) shows no other ingredients.”

The offered link opened the JEI Crafting viewer, page 1/1, showing Gold Leaf → 2 Gold Powder. No altar link was offered. The reply neither mentioned the Natural Altar route nor justified omitting it, so the check fails despite the native plan call containing a partial altar record.

The previous A3 took about 38 seconds and returned only the crafting route. This run also took about 39 seconds and the JEI output search again returned only Crafting, not Natural Altar Infusion.
