# PackSage adapter feature todo

Updated: 2026-10-27.

Feature-grouped summary of the [tool roadmap](2026-10-13-todo.md). A mod can appear under multiple features. Unchecked items include missing implementation and implemented features still awaiting the stated live acceptance. Detailed contracts, evidence and acceptance limits remain in the tool roadmap and its linked reports.

Generic JEI displays or GuideME documentation do not establish dedicated mechanics or storage adapter coverage. Reliquified Artifacts shares the existing Artifacts logical integration.

On `feat/packsage-v2`, only the Minecraft adapter remains. Entries for other mods describe `feat/packsage-v1`; the [v2 note](2026-10-22-v2-minecraft-only.md) tracks v2 work.

## 1. Has adapter, not fully implemented

### Recipe cards and inline chips (v2)

- [ ] Minecraft — all eleven native recipe layouts pass JEI comparison; prompt candidate 18 passes all nine questions before promotion and on the installed default. The follow-up installed build passes JEI loading/R, compact 12-pixel chips, hover, beacon/glass cards, verbosity source visibility, retained links/legacy sources, and Normal-result/Detailed-source narrow fallbacks. The follow-up fixes are installed, and the player confirmed the final build works as intended. The player also saw missing and custom ingredient slots in game and they looked correct. Still open: cold-opening timing exceptions. See the [acceptance record](../validation/2026-10-27-recipe-cards.md).

### Journal recording and search (v2)

Minecraft remains in this adapter-status group because full coverage and acceptance are incomplete. Checked rows describe accepted features only; remaining clauses have their own unchecked rows. The [single acceptance record](../validation/2026-10-25-v2-acceptance.md#acceptance-summary) applies the controller rulings and supersedes earlier fixture failures; the [v2 note](2026-10-22-v2-minecraft-only.md) retains the broader checklist and cross-stage follow-ups.

- [x] Minecraft — journal startup/SQLite, fresh break/death evidence, deposits with chest coordinates/links and portal/advancement times (J0/W2/J4/J5). Recording start/gaps are honestly qualified in candidate reads; journal startup recovery passed live. J4's deposits-only answer is correct under the controller ruling.
- [x] Minecraft — bulk run search: 2,000 player sand breaks merge into one run; TNT explosion and break runs appear with working links (M5 completed subchecks).
- [x] Minecraft — M5 bulk performance: native readiness round 8 recorded overflowed 0, failed 0 and 0 slow ticks after the first 200; the controller amended the criterion to steady state after a ten-second warm-up.
- [x] Minecraft — multiplayer journal/note unavailability: J7 passed on a NeoForge 21.1.202 dedicated server with the instance's mods (round 4).
- [x] Minecraft — event families: placement, kills, deaths, profession changes, curing, zombie conversion, trades and structure entry (round 1 J10, round 6/7 J10r), and raids start/finish/outcome (round 1 J11).
- [x] Minecraft — withdrawal journaling and mount (donkey) menu ownership (round 4 J8r, round 6 J8r3 journal pass) and the disconnected-menu session (round 3 J9).
- [ ] Minecraft — J8 withdrawal reply compliance: the reply omits the donkey amounts and coordinates although the journal is correct. Recorded failure.
- [x] Minecraft — flush on immediate exit and rapid Save-and-Quit/rejoin (round 3 J13).
- [x] Minecraft — note anchors, other-dimension links, forget/delete with links unavailable, questions not saved, and first tool-using chat origin on notes (round 1 J12/J14, round 2 J12r/N1, round 4 N2, round 5 N3).

### Silent notes and persistent recall (v2)

- [x] Minecraft — new located reports saved silently, identical existing reports need no write, recalled notes remain player reports, and restart recall has coordinates and an opening place link (C4–C6/J6/M1 persistence subcheck).
- [x] Minecraft — moving one note and following its saved link: M1 PASS with prompt candidate 7; the move updates the same note with the player's place, one note remains and the old link marks the new place (installed recheck R1; [acceptance record](../validation/2026-10-25-v2-acceptance.md)).

### World block, mob and storage reading (v2)

- [ ] Minecraft — installed checks for cached-only POI facts and partial coverage, mutation-safe chunk snapshots/UUID paging without chunk-border repeats, section-shift/recreation recovery and bounded churn with concrete PARTIAL coverage, stable single- and multi-tick COMPLETE coverage with informational game-time spans (including /tick freeze), concrete revalidation gaps retained in area summaries, single-count unavailable entity chunks, bounded neighboring-candidate traversal, reachable entity/holder tails beyond 4,096 entries, byte-bounded long-name entity/item pages, area summaries and block pages, capped inspection custom names, dense entity/holder pacing, capped attribute coverage and asynchronous highlights; [automated regressions pass](../validation/2026-10-26-world-review-fixes.md). Installed rechecks passed border and dense entity paging, stable multi-tick COMPLETE spans, capped trades, highlights, long-name capping and item/area pages ([acceptance record](../validation/2026-10-25-v2-acceptance.md)); uncached POI facts, /tick freeze and >4,096-entry tails were not run live.
- [x] Minecraft — nearby/nested storage search, both stone stores and per-holder highlights, continued complete 72-type catalogue, sampled responsive 320-chest search and exact saved chest link after full restart (W1/W4/W5/W6/W7).
- [x] Minecraft — villager home/job/sleep/work facts and gameTime interpretation combined with fresh bed-break/zombie-death evidence (C6/Q5, W2); specific golem expiry conversion remains unexercised.
- [x] Minecraft — mob attributes and horse-like jump/speed facts, including cited 1.62-block mule jump (W8) and earlier horse acceptance. Its omitted coordinates remain a separate E6 failure below.
- [x] PackSage — item pages retain holder links and continuation within the 64-record limit (N1/W5); repeated equivalent bridge navigation ids and inspected-air link suppression passed live rechecks (C3).

### Held, offhand and item facts (v2)

- [x] Minecraft — fresh holder-summary evidence rereads (native readiness W16; [acceptance record](../validation/2026-10-25-v2-acceptance.md)). Incomplete-inventory coverage is covered by automated regressions.
- [x] Minecraft — held sword eligibility/exclusions, empty-hand response, four-piece armor eligibility and offhand Luck facts found by tooltip search (C1/C4/W3).
- [x] Minecraft — item component and potion variants and stale handle rereads (native readiness round 1 W9; [acceptance record](../validation/2026-10-25-v2-acceptance.md)).

### Open-menu reading (v2)

- [x] Minecraft — opened crafting-grid inputs with output preview excluded (C2).
- [x] Minecraft — menu-close path staleness (W10) and the opened mount menu path (W11r) passed in native readiness rounds 1 and 4.
- [ ] Minecraft — modded-menu PARTIAL behavior: not exercised live.

### Targeted and ridden holders (v2)

- [x] Minecraft — targeted mule/librarian and ridden donkey reads return nested contents/offers and actual cargo; coordinates and links work (W4/C3/C5).

### Tooltip item search (v2)

- [x] Minecraft — one tooltip-filtered search finds the rabbit's foot and distinguishes its offhand bonus from current Luck (C4).
- [ ] Minecraft — unreadable-tooltip PARTIAL behavior and every listing/search variant: automated coverage, not fully exercised live.

### Villager trades (v2)

- [x] Minecraft — stationary librarian offers/prices/item distinctions and never-opened librarian not-generated reads twice, then ordinary trade opening (C5 corrected fixture). The natural-job fixture already had offers and is not the no-generation proof.
- [x] Minecraft — wandering trader and master librarian full offers payload with shared item facts, offers payload reduced from about 220 KB to 25 KB (native readiness round 3 D1 ruling; offer-cap, oversized-record and retained-small-fact automated regressions also pass in the [world review fixes](../validation/2026-10-26-world-review-fixes.md)).

### Loot tables (v2)

- [x] Minecraft — zombie common/conditional rare drops read from its loot definition, without rolling (C6).
- [x] Minecraft — pending-container loot table/seed without unpacking, and undefined/empty tables (native readiness round 4 W12r3 controller ruling).

### Structure locating and place markers (v2)

- [x] Minecraft — fresh village predicted/visited found results with coordinates, unchanged region listing and saved place link after full restart (C7). Nether column visibility/lifetime passes after the depth-test fix at distance 12 (L1); earlier Overworld distance-8 clamp/expiry passes (L2), and fixed-build Overworld visibility passes at 12.
- [x] Minecraft — biome id/tag sampling/continuation (round 1 W13) and tag locate (round 3 W19r); the Overworld fortress question has no invented link (E8).

### Command discovery and usage (v2)

- [x] Minecraft — cheats-off /fill usage/argument types/permissions from read_command, without execution (C8).
- [x] Minecraft — search_commands discovery/continuation (W14). Snapshot caching is not pursued: no live timing issue appeared in W14 or C8; revisit only if timing evidence appears.

### Progress and statistics (v2)

- [ ] Minecraft — installed byte-aware progress/statistics pages with safe long-text truncation and exact continuation after delivered records; automated wire and completed-snapshot regressions pass in the [world review fixes](../validation/2026-10-26-world-review-fixes.md).
- [x] Minecraft — installed public-node advancement reference snapshots with bounded root/sibling processing and hidden-alias ordering; [20,000-node automated regressions pass](../validation/2026-10-26-world-review-fixes.md), and installed rounds 3–4 listed all seven trees with correct counts and paged the 2,000-entry tree exactly once to COMPLETE.
- [x] Minecraft — fresh progress-tree evidence rereads with current counts and removed-tree refusal (native readiness W16; [acceptance record](../validation/2026-10-25-v2-acceptance.md)).
- [ ] Minecraft — installed large-counter pacing and changing-counter retry checks after the bounded traversal fix; [automated batch-count and restart regressions pass](../validation/2026-10-26-world-review-fixes.md).
- [x] Minecraft — per-criterion missing biome names, available goals, anonymous hidden entries after the P5 fix, visible-tab links and restart reopening (P1/P5/P7/P8). Q11 confirms invisible tabs stay source-only.
- [x] Minecraft — exact mined counts, walking in blocks, explicit never-mined zero, play time and sampled 2,000-advancement performance (P2/P3/P4/P6/P10).
- [x] Minecraft — multiplayer progress/statistics unavailability: P9 passed on a NeoForge 21.1.202 dedicated server with the instance's mods (native readiness round 4). The progress acquisition-budget continuation branch and some interrupted/missing-destination/unit branches are automated-only and cannot be set up live.
- [x] PackSage — offered-tool removal audit: P11 passed (request_built lists 28 tools, no read_progress); W7/P8 separately pass historical chest/advancement link reopening.

### Whole-policy prompt, citations and timing (v2)

- [x] PackSage — saved v1/legacy provider citations render with unavailable destinations (native readiness round 3 W17; [acceptance record](../validation/2026-10-25-v2-acceptance.md)). Automated store/render/navigation regressions also pass.
- [ ] PackSage — A3 reply compliance: the from-scratch beacon reply does not say obsidian is obtained, although the plan detail is correct. Recorded failure.
- [x] PackSage — offered tool names (P11): request_built lists 28 tools and no read_progress (native readiness round 3 C1r). Astra final review READY at 6b2e903 (rereview 13).
- [x] PackSage — six live prompt rounds, C6 promoted byte-for-byte and effectively 14/14 under Q8a/Q8b note-action ruling; installed default verified with override cleared. Whole-set armor, continued storage, grounded farm diagnosis, silent notes and supported Minecraft recipe/plan answers pass (W2/W3/W5/J6/E2). Provider citation fixes pass installed E5 scans and sampled UI checks.
- [x] PackSage — every located thing has text coordinates: E6 PASS with prompt candidate 8, including the targeted W8 mule (installed recheck R2).
- [x] PackSage — Part B prompt observations: the Overworld fortress question has no invented link (E8) and locate continuation keeps its arguments (E9); installed default prompt is candidate 11 (D3/D4).
- [x] PackSage — safe tool receipt/submission/summary logs distinguish backend and tool wall time (E7), and independent effect/attribute reads overlap (E4).

### Developer prompt persistence (v2)

- [x] PackSage — all settings saves use one asynchronous writer and bridge/developer prompts publish before persistence: stalled-writer client saves, generation ordering, stale copies, timeout consistency and shutdown flushing regressions pass; installed acceptance passed for the settings writer/screen saves and the shutdown flush (native readiness round 3 U2; [acceptance record](../validation/2026-10-25-v2-acceptance.md)).

### Inventory and equipment inspection

- [x] Minecraft — opened-path entity-menu checks: the opened mount (donkey) menu path passed in native readiness round 4 (W11r) on top of the targeted, ridden and mount-switch fixtures ([acceptance record](../validation/2026-10-25-v2-acceptance.md); earlier [completion record](../validation/2026-10-21-minecraft-adapter-completion.md) and [validation record](../validation/2026-10-21-minecraft-adapter-reachability.md)).
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

- [x] Minecraft — effect `description` from `<descriptionId>.description` keys, otherwise `descriptionStatus` `not_provided`, `bounded` or `unknown`; installed acceptance passed for the absent case and, via a resource pack with description text, the present case (native readiness round 4 W18r; [acceptance record](../validation/2026-10-25-v2-acceptance.md)).

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
- [x] Minecraft — plan_recipe routes reach every loaded recipe (beacon, enchanting table, oak planks) with concrete PARTIAL details, intermediate expansion and smithing inputs (A1r/A3 plan detail/W20/W21, native readiness rounds 3–8; [acceptance record](../validation/2026-10-25-v2-acceptance.md)).
- [ ] JEI — [focus-index planning source](../specs/2026-10-27-jei-adapter.md), default/explicit routing, shared argument validation and rereadable plan handles are implemented with automated coverage; installed acceptance remains pending.
- [ ] Actually Additions — investigate the native laser recipe read that returned no inputs.
- [ ] PackSage planner — investigate the empty Powah partial plans, then add other verified transformation semantics to bounded route planning.

### Default recipe source

- [x] JEI — default source of an unqualified recipe search whenever its runtime is available; installed acceptance passed for the torch question ([specification](../specs/2026-10-22-jei-default-recipe-source.md), [validation record](../validation/2026-10-22-jei-default-recipe-source.md)).
- [x] Minecraft — default recipe source when JEI is not installed or not ready, with no JEI-unavailable note; installed acceptance passed for the torch and Potion of Strength questions.
- [ ] JEI — [replacement provider](../specs/2026-10-27-jei-adapter.md) defaults to focus-index search while ready, falls back silently to native while unavailable, and binds continuation to scope/generation; automated coverage passes, installed acceptance remains pending.
- [ ] PackSage — prompt: the assistant still selects `provider: native` for brewing while JEI is installed, and omits a returned modded route without saying so ([validation record](../validation/2026-10-22-jei-output-focus.md)).

### JEI output search

- [x] JEI — output-item searches use JEI's recipe index for items without JEI-distinguished variants; installed acceptance passed: the torch was found on the first JEI page in about 22 seconds, down from about 40 ([specification](../specs/2026-10-22-jei-output-focus.md), [validation record](../validation/2026-10-22-jei-output-focus.md)).
- [x] JEI — output-item searches match modded-category recipes with their captured slots and OPAQUE coverage; installed retrieval passed for Nature's Aura's Natural Altar Infusion recipe for Gold Powder. Using that route in the reply is tracked under the prompt item above.
- [ ] JEI — items with JEI-distinguished variants, such as potions, still use the bounded scan; the installed potion path was not exercised because the assistant selected native.

### Recipe cards and navigation

- [ ] JEI — draw recipe cards with JEI; R, U and clicks on card slots open recipes or uses, and R, U and clicks on item or result chips open their JEI recipes or uses (chip clicks open recipes).

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
- [ ] PackSage — installed recheck of complete leading recipe routes with concrete omitted counts, bounded output tails, stale-world cursor errors and bounded settings shutdown; [automated validation](../validation/2026-10-26-world-review-fixes.md) passes, live acceptance pending.
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
