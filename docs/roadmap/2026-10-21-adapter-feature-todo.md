# PackSage adapter feature todo

Updated: 2026-10-26.

Feature-grouped summary of the [tool roadmap](2026-10-13-todo.md). A mod can appear under multiple features. Unchecked items include missing implementation and implemented features still awaiting the stated live acceptance. Detailed contracts, evidence and acceptance limits remain in the tool roadmap and its linked reports.

Generic JEI displays or GuideME documentation do not establish dedicated mechanics or storage adapter coverage. Reliquified Artifacts shares the existing Artifacts logical integration.

On `feat/packsage-v2`, only the Minecraft adapter remains. Entries for other mods describe `feat/packsage-v1`; the [v2 note](2026-10-22-v2-minecraft-only.md) tracks v2 work.

## 1. Has adapter, not fully implemented

### Journal recording and search (v2)

Minecraft remains in this adapter-status group because full coverage and acceptance are incomplete. Checked rows describe accepted features only; remaining clauses have their own unchecked rows. The [single acceptance record](../validation/2026-10-25-v2-acceptance.md#acceptance-summary) applies the controller rulings and supersedes earlier fixture failures; the [v2 note](2026-10-22-v2-minecraft-only.md) retains the broader checklist and cross-stage follow-ups.

- [x] Minecraft — journal startup/SQLite, fresh break/death evidence, deposits with chest coordinates/links and portal/advancement times (J0/W2/J4/J5). Recording start/gaps are honestly qualified in candidate reads; journal startup recovery passed live. J4's deposits-only answer is correct under the controller ruling.
- [x] Minecraft — bulk run search: 2,000 player sand breaks merge into one run; TNT explosion and break runs appear with working links (M5 completed subchecks).
- [ ] Minecraft — complete journal performance: M5 NOT RUN overall because world-close hook <1 ms / overflow=0 summary was not captured; broader event families, exceptional menu ownership and final-burst/rapid-rejoin flush remain unverified.
- [ ] Minecraft — multiplayer journal/note unavailability: J7 NOT RUN; installed client rejects vanilla localhost, so a NeoForge dedicated server is needed.

### Silent notes and persistent recall (v2)

- [x] Minecraft — new located reports saved silently, identical existing reports need no write, recalled notes remain player reports, and restart recall has coordinates and an opening place link (C4–C6/J6/M1 persistence subcheck).
- [ ] Minecraft — moving one note and following its saved link: M1 FAIL; clearPlace loses the location and the old link becomes unavailable, with two pre-existing notes still present. Forget/anchor/other-dimension clauses remain unverified live.

### World block, mob and storage reading (v2)

- [ ] Minecraft — installed checks for cached-only POI facts and partial coverage, mutation-safe chunk snapshots/UUID paging without chunk-border repeats, stable single-tick COMPLETE coverage and tick-span gaps, single-count unavailable entity chunks, bounded neighboring-candidate traversal, reachable entity/holder tails beyond 4,096 entries, byte-bounded long-name entity pages, dense entity/holder pacing, capped attribute coverage and asynchronous highlights; [automated regressions pass](../validation/2026-10-26-world-review-fixes.md), live acceptance pending.
- [x] Minecraft — nearby/nested storage search, both stone stores and per-holder highlights, continued complete 72-type catalogue, sampled responsive 320-chest search and exact saved chest link after full restart (W1/W4/W5/W6/W7).
- [x] Minecraft — villager home/job/sleep/work facts and gameTime interpretation combined with fresh bed-break/zombie-death evidence (C6/Q5, W2); specific golem expiry conversion remains unexercised.
- [x] Minecraft — mob attributes and horse-like jump/speed facts, including cited 1.62-block mule jump (W8) and earlier horse acceptance. Its omitted coordinates remain a separate E6 failure below.
- [x] PackSage — item pages retain holder links and continuation within the 64-record limit (N1/W5); repeated equivalent bridge navigation ids and inspected-air link suppression passed live rechecks (C3).

### Held, offhand and item facts (v2)

- [ ] Minecraft — fresh holder-summary evidence rereads, including incomplete-inventory coverage: implemented with automated regressions; installed acceptance pending.
- [x] Minecraft — held sword eligibility/exclusions, empty-hand response, four-piece armor eligibility and offhand Luck facts found by tooltip search (C1/C4/W3).
- [ ] Minecraft — every component/fingerprint/potion variant, other missing-holder cases and legacy inventory/storage handle rereads: implemented, complete installed coverage not captured.

### Open-menu reading (v2)

- [x] Minecraft — opened crafting-grid inputs with output preview excluded (C2).
- [ ] Minecraft — other menu types, modded-menu PARTIAL behavior and menu-close path staleness: not exercised by C2.

### Targeted and ridden holders (v2)

- [x] Minecraft — targeted mule/librarian and ridden donkey reads return nested contents/offers and actual cargo; coordinates and links work (W4/C3/C5).

### Tooltip item search (v2)

- [x] Minecraft — one tooltip-filtered search finds the rabbit's foot and distinguishes its offhand bonus from current Luck (C4).
- [ ] Minecraft — unreadable-tooltip PARTIAL behavior and every listing/search variant: automated coverage, not fully exercised live.

### Villager trades (v2)

- [x] Minecraft — stationary librarian offers/prices/item distinctions and never-opened librarian not-generated reads twice, then ordinary trade opening (C5 corrected fixture). The natural-job fixture already had offers and is not the no-generation proof.
- [ ] Minecraft — wandering-trader live acceptance and installed bounded-inspector payload/PARTIAL checks remain open; offer-cap, oversized-record and retained-small-fact automated regressions pass in the [world review fixes](../validation/2026-10-26-world-review-fixes.md).

### Loot tables (v2)

- [x] Minecraft — zombie common/conditional rare drops read from its loot definition, without rolling (C6).
- [ ] Minecraft — pending-container table/seed without unpacking, unknown/empty tables and broader loot variants: installed acceptance not captured.

### Structure locating and place markers (v2)

- [x] Minecraft — fresh village predicted/visited found results with coordinates, unchanged region listing and saved place link after full restart (C7). Nether column visibility/lifetime passes after the depth-test fix at distance 12 (L1); earlier Overworld distance-8 clamp/expiry passes (L2), and fixed-build Overworld visibility passes at 12.
- [ ] Minecraft — biome id/tag sampling/continuation and different-dimension place handling: broader installed acceptance not captured.

### Command discovery and usage (v2)

- [x] Minecraft — cheats-off /fill usage/argument types/permissions from read_command, without execution (C8).
- [ ] Minecraft — broader search_commands discovery/continuation and snapshot caching/performance follow-ups remain open.

### Progress and statistics (v2)

- [ ] Minecraft — fresh progress-tree evidence rereads with current counts and removed-tree refusal: implemented with automated regressions; installed acceptance pending.
- [ ] Minecraft — installed large-counter pacing and changing-counter retry checks after the bounded traversal fix; [automated batch-count and restart regressions pass](../validation/2026-10-26-world-review-fixes.md).
- [x] Minecraft — per-criterion missing biome names, available goals, anonymous hidden entries after the P5 fix, visible-tab links and restart reopening (P1/P5/P7/P8). Q11 confirms invisible tabs stay source-only.
- [x] Minecraft — exact mined counts, walking in blocks, explicit never-mined zero, play time and sampled 2,000-advancement performance (P2/P3/P4/P6/P10).
- [ ] Minecraft — multiplayer progress/statistics unavailability: P9 NOT RUN, requires a NeoForge dedicated server. Broader interrupted/missing-destination/unit branches remain unexercised live.
- [ ] PackSage — offered-tool removal and historical quest-source UI audit: P11 NOT RUN, logs show tool count without names and JSONL has no definitions. W7/P8 separately pass historical chest/advancement link reopening.

### Whole-policy prompt, citations and timing (v2)

- [ ] PackSage — saved v1 provider citations bind recorded sources and targets; removed providers return unavailable without substitution: automated store/render/navigation regressions pass; installed acceptance pending.
- [x] PackSage — six live prompt rounds, C6 promoted byte-for-byte and effectively 14/14 under Q8a/Q8b note-action ruling; installed default verified with override cleared. Whole-set armor, continued storage, grounded farm diagnosis, silent notes and supported Minecraft recipe/plan answers pass (W2/W3/W5/J6/E2). Provider citation fixes pass installed E5 scans and sampled UI checks.
- [ ] PackSage — every located thing has text coordinates: E6 FAIL, W8 mule jump answer omits them. Part B's unscored unsupported fortress link and changed-radius cursor reuse remain prompt follow-ups; no seventh candidate was run.
- [x] PackSage — safe tool receipt/submission/summary logs distinguish backend and tool wall time (E7), and independent effect/attribute reads overlap (E4).

### Developer prompt persistence (v2)

- [ ] PackSage — debug prompt saves persist off the client thread and retain concurrent screen saves: blocking-writer, failure, validation and reset regressions pass; installed acceptance pending.

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
