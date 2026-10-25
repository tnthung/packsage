# PackSage adapter feature todo

Updated: 2026-10-25.

Feature-grouped summary of the [tool roadmap](2026-10-13-todo.md). A mod can appear under multiple features. Unchecked items include missing implementation and implemented features still awaiting the stated live acceptance. Detailed contracts, evidence and acceptance limits remain in the tool roadmap and its linked reports.

Generic JEI displays or GuideME documentation do not establish dedicated mechanics or storage adapter coverage. Reliquified Artifacts shares the existing Artifacts logical integration.

On `feat/packsage-v2`, only the Minecraft adapter remains. Entries for other mods describe `feat/packsage-v1`; the [v2 note](2026-10-22-v2-minecraft-only.md) tracks v2 work.

## 1. Has adapter, not fully implemented

### Journal and notes (v2)

Stage 4 is implemented with automated tests; full installed acceptance is pending. Candidate 2 exposed a closed journal after late hook registration. Startup recovery now has failing-then-passing regressions, with the installed recheck pending ([fix report](../../.superpowers/sdd/2026-10-25-v2-prompt-acceptance/fix-journal-closed-report.md)). Keep these entries unchecked until the final acceptance run passes the corresponding checks in the [v2 note](2026-10-22-v2-minecraft-only.md#journal-and-notes).

- [ ] Minecraft — automatic event journal with run merging; singleplayer recording and background flushing, without blocking the game on disk or the database.
- [ ] Minecraft — player notes with place links; silent `remember`, `update_note` and `forget_note`, player-reported trust, note anchors and saved links that survive restarts and follow moved notes.
- [ ] Minecraft — journal search over events and notes; `search_journal` with recording-since and honest partial coverage, plus persistent place highlights.

### World block, mob and storage reading (v2)

Candidate 2's duplicate-destination bridge rejection and inspected-air Show link are fixed with automated regressions; installed rechecks remain pending in the [fix report](../../.superpowers/sdd/2026-10-25-v2-prompt-acceptance/fix-journal-closed-report.md).

- [x] Minecraft — nearby storage search, including items nested inside other items, with block highlights; installed acceptance passed ([validation record](../validation/2026-10-22-v2-world-reading.md)).
- [x] Minecraft — villager home, job site, sleep and work times and golem-spawning state; installed iron-farm diagnosis passed.
- [x] Minecraft — mob storage paths, mule chest to shulker box to bundle; installed acceptance passed.
- [x] Minecraft — every mob attribute, and horse jump height and speed in blocks; installed acceptance passed for a mule and a horse.
- [x] Minecraft — saved highlight links across a full restart, and pacing with the world running and paused; installed acceptance passed.
- [x] PackSage — item pages keep holder links and their continuation within the 64-record tool-output limit (N1); installed recheck passed with 52 items, 12 holders and a cursor.
- [ ] PackSage — prompt: the armor reply omitted eligible enchantments from returned evidence, and the mule reply gave no coordinates, the farm reply did not reason from villager times, a broad storage reply did not continue its cursor, and one reply ended with a malformed source token.

### Villager trades (v2)

- [ ] Minecraft — villager and wandering-trader offers, distinguishing item facts and prices for the player through `inspect_entity`, without generating trades; implemented with automated tests, not yet checked in game ([world knowledge plan](../plans/2026-10-25-v2-world-knowledge.md)).

### Loot tables (v2)

- [ ] Minecraft — `read_loot` reads table definitions, block/entity drops and pending container loot without rolling or unpacking it; implemented with automated tests, not yet checked in game.

### Biome and structure locating (v2)

- [ ] Minecraft — `locate` resolves biome and structure ids/tags, returns coordinates and persistent place links, and continues paced searches without loading or generating chunks. Structures distinguish found starts from predictions; unknown saved-start confirmation never proves absence. Place columns await visibility checks in the Nether at default render distance and the Overworld at 8 chunks, and saved links await a full-restart check. Implemented with automated tests, not yet checked in game ([final stage 3 checklist](2026-10-22-v2-minecraft-only.md)).

### Command discovery and usage (v2)

- [ ] Minecraft — `search_commands` and `read_command` read a paced detached command snapshot, with usage, argument names/types and player permissions, without running commands; implemented with automated tests, not yet checked in game.

### Held and offhand item facts (v2)

- [ ] Minecraft — `inspect_item` and `list_items` read the held and offhand items through `held` and `offhand`, with full item facts, tooltip lines and always-present `enchantmentEligibility`; implemented with automated tests, not yet checked in game ([plan](../plans/2026-10-25-v2-item-reading-consolidation.md)).

### Open-menu reading (v2)

- [ ] Minecraft — `list_items` reads the open menu through `opened`, with menu sections, output previews listed under `excludedSlots` instead of contents, and modded menus listed under `minecraft/menu` as PARTIAL; implemented with automated tests, not yet checked in game.

### Targeted and ridden holders (v2)

- [ ] Minecraft — `list_items` reads the block or mob under the crosshair through `targeted` and the ridden mob through `ridden`, and gameplay context names `targetedHolder` and `ridden`; implemented with automated tests, not yet checked in game.

### Tooltip item search (v2)

- [ ] Minecraft — `filter.tooltip` in `list_items` and `find_items` matches the tooltip lines shown to the player, and unreadable tooltips make the page PARTIAL; implemented with automated tests, not yet checked in game.

### Progress and statistics (v2)

- [ ] Minecraft — advancement progress with per-criterion state and tab links; live [Q11](../validation/2026-10-25-v2-acceptance.md#q11--fail) found links to absent tabs. Links now require server-tracked tab visibility. Live P5 exposed hidden identifiers; opaque world-session ids now redact listings, dependencies, inspection, evidence handles and hidden roots. Review fixes reject unearned hidden real-id lookups and order hidden siblings after visible ones by alias. Automated regression checks pass; both live rechecks remain pending ([remaining acceptance checks](2026-10-22-v2-minecraft-only.md#progress-and-statistics)).
- [ ] Minecraft — player statistics in blocks and seconds; implemented with automated tests, not yet checked in game. Acceptance runs once at the end of all stages, as listed in the [v2 note](2026-10-22-v2-minecraft-only.md#progress-and-statistics).

### Inventory and equipment inspection

- [ ] Minecraft — opened-path entity-menu checks (implemented; automated checks pass; installed acceptance pending: the opened target was not exercised for donkey or mule screens, and the reachability re-test again used the targeted path; see the [completion record](../validation/2026-10-21-minecraft-adapter-completion.md) and the [validation record](../validation/2026-10-21-minecraft-adapter-reachability.md)). Targeted, ridden and mount-switch fixtures passed.
- [x] Minecraft — stored quantities that exclude recipe previews; installed acceptance passed: the assistant read `opened` for the crafting and stonecutter menus and the output preview was not counted ([validation record](../validation/2026-10-21-minecraft-adapter-reachability.md)).

### Held and selected item facts

- [x] Minecraft — the item target returns the selected item's own record, including `enchantmentEligibility`, before contents, and an empty selection is reported as such; installed acceptance passed ([validation record](../validation/2026-10-21-minecraft-adapter-reachability.md)).

### Open-menu context

- [x] Minecraft — gameplay context `openedMenu` with an optional plain title and a pointer from targeted-unsupported results to `target: opened`; installed acceptance passed for the crafting table and stonecutter ([validation record](../validation/2026-10-21-minecraft-adapter-reachability.md)). Modded menus are unsupported by `target: opened`.

### Enchantment mechanics

- [x] Minecraft — applicable enchantments for an item, including supported enchantments each one conflicts with (`exclusiveWith`); installed acceptance passed for a held pickaxe and sword ([validation record](../validation/2026-10-21-minecraft-adapter-reachability.md)). Per-level costs, effect payloads and built-in effect decoding passed installed acceptance earlier ([completion record](../validation/2026-10-21-minecraft-adapter-completion.md)).

### Status-effect inspection

- [x] Minecraft — attribute modifiers and active player effects; installed acceptance passed ([validation record](../validation/2026-10-21-minecraft-adapter-completion.md)).

### Effect descriptions from language keys

- [ ] Minecraft — effect `description` from `<descriptionId>.description` keys, otherwise `descriptionStatus` `not_provided`, `bounded` or `unknown` (implemented; automated checks pass; installed acceptance covers only the description-absent case, and the present case needs a pack with description text, for example ATM10; see the [validation record](../validation/2026-10-21-minecraft-adapter-reachability.md)).

### Player attribute values and modifier sources

- [x] Minecraft — `player_attribute` base, value and modifiers; installed acceptance passed for a Luck effect plus equipped helmet and for the helmet alone ([validation record](../validation/2026-10-21-minecraft-adapter-reachability.md)). Minecraft does not record what applied an effect.

### Potion-item inspection

- [x] Minecraft — decode actual potion contents and additional custom effects; installed acceptance passed ([validation record](../validation/2026-10-21-minecraft-adapter-completion.md)).

### Ability definitions and attributes

- [ ] Artifacts — decode ability components and item-specific attribute modifiers. Inventory reads already return raw item components.
- [ ] Reliquified Artifacts — effective definitions and bridge-supplied ability overrides through the Artifacts integration.

### Ability cooldowns

- [ ] Relics.
- [ ] Artifacts.

### Item rank and upgrade points

- [ ] Relics — rank, relic points and per-ability upgrade levels are implemented; nonzero values await live acceptance.

### Item upgrade state

- [ ] Relics — upgrade costs and availability beyond rank, points and per-ability levels.

### Guide item relationships

- [ ] Modonomicon — entry-to-item links; acceptance remains open.
- [ ] GuideME — page-to-item mappings; acceptance remains open.

### Guide recipe references

- [ ] Modonomicon — embedded recipe references; acceptance remains open.
- [ ] GuideME — recipe tags; unsupported nodes remain partial.
- [ ] Immersive Engineering — manual recipe references; remaining selector coverage and acceptance.

### Guide prerequisites and progress

- [ ] Modonomicon — prerequisite acceptance and dedicated visibility/unlock progress reads.

### Guide navigation

Entry and page opening, including after a full restart, is accepted for Modonomicon, GuideME, Immersive Engineering and Actually Additions.

- [ ] GuideME — installed scrolling to a heading below the top of a page; the installed Powah pages have only a top title heading.

### Guide reading before first use

- [ ] Immersive Engineering — model reads find no manual entries until the manual has been opened in the session. Navigation clicks already load it.

### Brewing discovery, recipe reads and planning

- [x] Minecraft — localized potion-name matching and empty-page progress; installed acceptance passed: the potion was searched by name, the empty page was continued and the localized transformation was found in 2 recipe searches ([validation record](../validation/2026-10-21-minecraft-adapter-reachability.md)).
- [x] Minecraft — planner brewing batch presentation; installed acceptance passed: `plan_recipe` was called and the reply stated the batch semantics and derived totals under a stated grouping ([validation record](../validation/2026-10-21-minecraft-adapter-reachability.md)).
- [ ] Actually Additions — investigate the native laser recipe read that returned no inputs.
- [ ] PackSage planner — investigate the empty Powah partial plans, then add other verified transformation semantics to bounded route planning.

### Default recipe source

- [x] JEI — default source of an unqualified recipe search whenever its runtime is available; installed acceptance passed for the torch question ([specification](../specs/2026-10-22-jei-default-recipe-source.md), [validation record](../validation/2026-10-22-jei-default-recipe-source.md)).
- [x] Minecraft — default recipe source when JEI is not installed or not ready, with no JEI-unavailable note; installed acceptance passed for the torch and Potion of Strength questions.
- [ ] PackSage — prompt: the assistant still selects `provider: native` for brewing while JEI is installed, and omits a returned modded route without saying so ([validation record](../validation/2026-10-22-jei-output-focus.md)).

### JEI output search

- [x] JEI — output-item searches use JEI's recipe index for items without JEI-distinguished variants; installed acceptance passed: the torch was found on the first JEI page in about 22 seconds, down from about 40 ([specification](../specs/2026-10-22-jei-output-focus.md), [validation record](../validation/2026-10-22-jei-output-focus.md)).
- [x] JEI — output-item searches match modded-category recipes with their captured slots and OPAQUE coverage; installed retrieval passed for Nature's Aura's Natural Altar Infusion recipe for Gold Powder. Using that route in the reply is tracked under the prompt item above.
- [ ] JEI — items with JEI-distinguished variants, such as potions, still use the bounded scan; the installed potion path was not exercised because the assistant selected native.

### Native crafting grid layout

- [x] Minecraft — shaped recipes report `shape=WxH` with slot-index constraints and shapeless recipes report `shape=shapeless`; installed acceptance passed for a piston's 3x3 placement ([validation record](../validation/2026-10-21-minecraft-adapter-reachability.md)).

### Guide search reliability

- [ ] Modonomicon — investigate the title-only and budget failures in guide search.
- [ ] Artifacts — investigate an empty partial tooltip result returned without a cursor.

### Construction geometry

- [ ] Immersive Engineering — native template geometry.
- [ ] GuideME — embedded structure definitions.

### Construction material requirements

- [ ] Immersive Engineering — native template materials.

### Command discovery, help and execution

- [ ] Minecraft — define the contract and permission boundaries, then implement command support.

### Shared reliability and chat acceptance

This is PackSage core work, rather than a missing third-party mod adapter.

- [ ] PackSage — remaining assistant behaviour: prompt wording for the refreshing short-duration effect-source cue, which needs two reads (see the [tool roadmap](2026-10-13-todo.md)); freshness/continuation audits, version dispatch, coverage diagnostics, latency, citations, source presentation, search behavior and remaining parallel-chat checks. The eligibility, opened-menu, targeted-entity and brewing-plan tool-selection items passed ([validation record](../validation/2026-10-21-minecraft-adapter-reachability.md)).
- [ ] PackSage — adapter query execution through each provider's own live search and paging, and target references that keep their registered type.
- [ ] PackSage — installed recheck of the merged adapter-popup width and scrollbar.

## 2. Does not have an adapter at all

### Opened storage inspection

- [ ] Pocket Storage.
- [ ] Sophisticated Backpacks.
- [ ] Sophisticated Storage.
- [ ] Applied Energistics 2.
- [ ] Refined Storage.
- [ ] Mekanism.

### Storage inspection through inventory items

- [ ] Pocket Storage.
- [ ] Sophisticated Backpacks.
- [ ] Sophisticated Storage.
- [ ] Functional Storage.
- [ ] Storage Drawers.
- [ ] Mekanism.

### Targeted storage inspection

- [ ] Sophisticated Backpacks.
- [ ] Sophisticated Storage.
- [ ] Functional Storage.
- [ ] Storage Drawers.
- [ ] Applied Energistics 2.
- [ ] Refined Storage.
- [ ] Mekanism.

### Network discovery, wireless access and complete item totals

- [ ] Applied Energistics 2.
- [ ] Refined Storage.

### Modded mount and vehicle inventories

- [ ] Select provider mods and add verified live cargo/equipment access.

### Quest discovery, dependencies, progress and navigation

- [ ] Heracles.

### Advancement discovery and parent relationships

- [ ] Just Enough Advancements.

### Script-source inspection

- [ ] KubeJS.
- [ ] CraftTweaker.
- [ ] LootJS.

### Recipe-override attribution

- [ ] KubeJS.
- [ ] CraftTweaker.

### Custom recipe and transformation semantics

- [ ] KubeJS — custom recipe fields.
- [ ] Applied Energistics 2 — transformation conditions, Matter Condenser requirements and P2P attunement.

### Enchantment caps and custom mechanics

- [ ] Apothic Enchanting — effective caps and custom effect components.
- [ ] Tombstone — custom enchantment effect types.

### Enchantment descriptions and supplementary information

- [ ] Enchantment Descriptions.
- [ ] Just Enough Resources.

### Custom status-effect mechanics

- [ ] Tombstone.
- [ ] Potions Master.

### Effect descriptions and item-to-effect relationships

- [ ] Just Enough Effect Descriptions.

### Affix definitions and mechanics

- [ ] Apotheosis.

### Mob drops, plant drops, dungeon loot and villager trades

- [ ] Just Enough Resources.

### Effective loot-modifier rules

- [ ] LootJS.

### Profession-to-workstation relationships

- [ ] Just Enough Professions.

### Breeding, taming and entity transformations

- [ ] Just Enough Breeding.

### Biome-to-mob and biome-to-ore relationships

- [ ] Just Enough Biomes.

### Structure contents, biome relationships and loot sources

- [ ] Just Enough Structures.

### Ore-distribution data

- [ ] Just Enough Resources.

### Targeted block/entity tooltip facts

- [ ] Jade.

### Construction geometry

- [ ] Just Enough Immersive Multiblocks.

### Construction material requirements

- [ ] Just Enough Immersive Multiblocks.
- [ ] Just Enough Mekanism Multiblocks.

### Construction calculator dimensions and numeric results

- [ ] Just Enough Mekanism Multiblocks.

### Maps and waypoints

- [ ] JourneyMap — proposed example provider.

### Biome locating

- [ ] Nature's Compass.

### Structure locating

- [ ] Explorer's Compass.

### Authorized server-only inventories, loot and configuration

- [ ] PackSage — optional server companion remains unimplemented.

### Version-aware external documentation

These tasks use backend web search; no local documentation adapter is required.

- [ ] Refined Storage — official guide pages.
- [ ] Mekanism — official wiki pages.
