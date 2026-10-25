# PackSage v2: Minecraft-only baseline

Updated: 2026-10-25.

The `feat/packsage-v2` branch removes every non-Minecraft adapter so mod adapters can be rebuilt with a new strategy. The [tool roadmap](2026-10-13-todo.md) and [adapter feature checklist](2026-10-21-adapter-feature-todo.md) still describe the previous branch for removed adapters. Each now has a v2 section pointing here, and this note tracks v2 work.

## Removed

- JEI recipe adapter and its recipe navigation.
- FTB Quests adapter.
- Patchouli adapter.
- Guide and manual readers: Modonomicon, GuideME, Immersive Engineering, Actually Additions, the Relics guide, Artifacts and Ponder, together with the shared guide document provider.
- Curios equipment sections and Relics instance reads in the Minecraft inventory reader.
- Optional mod detection, version range checks and the reflective provider wrappers in the client.
- Build dependencies, repositories and version properties used only by these adapters.
- The `search_document` and `read_progress` tools. Internal document discovery remains for a future document adapter; the old `read_progress` plumbing is removed and the v2 progress tools replace it.

## Remaining

- Chat core: screen, chat runtime, conversation store and model backend.
- Conversation history tools: `search_history` and `read_history`.
- Config reader: `list_configs`, `search_config` and `read_config`.
- Minecraft adapter: recipes and brewing, recipe planning, registries, effects, attributes, dimensions, and inventory, equipment and storage reading through the world tools.
- Advancement progress and player statistics through `list_progress`, `inspect_progress` and `list_statistics` in singleplayer; installed P1–P8/P10 acceptance passed. P9/P11 remain NOT RUN.

## World model

The [v2 world model specification](../specs/2026-10-22-v2-world-model.md) defines the plan. It is split into four implementation plans: world reading, journal and notes, progress and statistics, and prompt and acceptance.

### World reading

The [plan](../plans/2026-10-22-v2-world-reading.md) is implemented. The [validation record](../validation/2026-10-22-v2-world-reading.md) has the live results.

- [x] World tools: `inspect_area`, `list_blocks`, `list_entities`, `inspect_block`, `inspect_entity`, `list_items`, `inspect_item` and `find_items`, on singleplayer integrated-server reads paced across ticks. Multiplayer reports them as unavailable.
- [x] Inventory identity, item paths through nested holders, and two-step reading: list ids first, then inspect metadata.
- [x] Block and mob highlights from reply links, labeled `Show …`: installed check passed (Round 3).
- [x] Storage search: installed check passed (Round 1).
- [x] Iron-farm facts: installed check passed (Round 1).
- [x] Nested mob storage, mule chest to shulker box to bundle: installed check passed (Round 1).
- [x] Performance over about 320 chests with the world running: installed check passed (Round 1).
- [x] Mob attributes, and horse jump height and speed in blocks: installed check passed (Round 2), with a mule jump of 1.62 blocks and a horse speed of 10.77 blocks per second.
- [x] Final-review fixes on the installed build: pacing with the world running and paused, and per-holder links for stone, passed (Round 2).
- [x] Villager time interpretation: C6/Q5 converts sleep/work clocks and W2 uses gameTime deltas with bed/death evidence ([acceptance record](../validation/2026-10-25-v2-acceptance.md#acceptance-summary)). The specific golem expiry field remains unexercised; this does not establish that separate branch.
- [x] Saved highlight links across a full Minecraft restart: installed check passed (Round 2).
- [x] N1: fixed; installed recheck passed in Round 3 with 64 records per page (52 items and 12 holders) and a cursor for the rest. Reproduced in game in Round 2: 72 item types returned 55, with no holder records and a null cursor. A full page of found items with holder records exceeds the 64-record tool-output limit. That drops holder links and the continuation, and replaces the page notes. See the final re-review in `.superpowers/sdd/2026-10-22-v2-world-reading/final-rereview.md`.
- [x] Minor review backlog, fixed with automated tests in the [item reading plan](../plans/2026-10-25-v2-item-reading-consolidation.md); these are internal and need no live check:
  - N2: the paused-work flag can stay set. Fixed: the in-flight flag is keyed to the server and cleared when it stops.
  - N3: a tick can run on the client thread when the server has stopped. Fixed: the queued task steps only on a running, paused server's own thread.
  - N4: an early stop can make no progress. Fixed: a stop waits for at least one new container or chunk.
  - N5: `inspect_area` can drop the entity page's note. Fixed: the note is kept alongside the `list_entities` pointer.

### Journal and notes

Stage 4's [plan](../plans/2026-10-22-v2-journal.md) is implemented. The [single acceptance record](../validation/2026-10-25-v2-acceptance.md#acceptance-summary) holds Candidates 1–6, installed-default verification, Task 7 parts A/B and the L1 recheck. Journal startup recovery passed live in C3 and on a fresh installed-default launch; duplicate navigation ids and inspected-air links also passed rechecks. The original broad checklist is retained below with only observed clauses checked.

- [x] SQLite driver loads under FML on Windows: journal.db/WAL and fresh readable events, no native-library error (J0).
- [x] Player block breaks record subject, actor, time and position; farm diagnosis combines the bed break and zombie death (W2, fresh-launch smoke).
- [x] Explosion and merged break runs: Part B M5 records the TNT explosion and 75-sand/26-sandstone runs without rolling them into a claimed overall M5 pass.
- [x] Deposit question: 10 stone deposited with coordinates and a working chest link (J4). Controller accepts deposits-only for “what did I put in chests”; the original withdrawal requirement overreached that question.
- [x] Dimension travel and earned-advancement times: named empty-hand questions return the Nether departure portal and Sweet Dreams time with working links (J5 corrected fixture).
- [x] Invisible notes and trust: no saving announcement in Part A's 42 replies; recalled notes are player reports (J6). C4–C6 fresh cactus reports also prove silent located writes. Identical existing reports need no write.
- [x] Restart recall: a persisted farm note is recalled with coordinates and an opening link (M1 persistence subcheck). The complete move sequence remains failed below.
- [x] Honest coverage: candidate journal reads expose recordingSince and the historical gap as PARTIAL, rather than claiming nothing happened.
- [x] Bulk behavioral subchecks: 2,000 actual sand breaks merge into one run, TNT and break runs are searchable, sampled frames/TPS show no visible stall (M5 subchecks only).
- [ ] Move one existing note to the new place, preserving its id/link and avoiding duplicates: **M1 FAIL**, move used clearPlace and the old link became unavailable; two prior farm notes remain.
- [ ] Bulk performance acceptance: **M5 NOT RUN overall**, world-close maxHookNanosPerTick <1 ms / overflowed=0 summary not captured. Completed mining/TNT subchecks do not prove these counters.
- [ ] Multiplayer journal/note unavailability: **J7 NOT RUN**, vanilla localhost connection rejected; needs a NeoForge dedicated server.
- [ ] Raids.raidMap access transformer and live raid polling; raid start/finish/outcome and first-observed-finished behavior were not established by Task 7.
- [ ] Player placement, player kills, all death families/causes (player, named mob, tamed animal), profession changes, curing, zombie conversion, trades and first structure entry/re-entry: implemented, full event-family acceptance not captured. W2 verifies the villager-death subset only.
- [ ] Withdrawal question and exceptional menu ownership: disconnected/cancelled/modded menus and keyed horse inventories remain unverified by J4's deposits-only question.
- [ ] Note anchors, forget/delete/unavailable links, explicit other-dimension note links and questions/requests not being saved: full clauses not exercised. Restart recall and silent wording alone do not cover them.
- [ ] First tool-using chat's persisted conversation/message origin was not audited in Task 7.
- [ ] Flush all final queued events/open runs on immediate exit and rapid Save-and-Quit/rejoin: full clause unverified; restart recall is narrower.

### Progress and statistics

Stage 2's [plan](../plans/2026-10-22-v2-progress-statistics.md) is implemented. Results live in the [single acceptance record](../validation/2026-10-25-v2-acceptance.md#acceptance-summary), replacing the former per-stage validation pointer. P5 passed on the installed hidden-id fix; tab visibility and restart checks passed. Earlier fixture failures remain historical, superseded by Part B.

- [x] Advancement status, per-criterion missing biome names and available next goals with usable visible-tab links (P1/P7).
- [x] Hidden advancements return only count/anonymous aliases, without real names, ids or criteria in the audited results/reply (P5 recheck). Real-id lookup/order restrictions also have automated integration checks.
- [x] Open advancements links work for visible tabs and after a full restart (P8); invisible tabs remain source-only (Q11).
- [x] Exact diamond/deepslate-diamond mined counts distinguished from drops (P2).
- [x] Walking reported in blocks (P3), never-mined ancient debris explicitly zero (P4), play time sensibly converted from seconds (P6).
- [x] A 2,000-advancement datapack read has no visible stall in sampled frame/tick evidence (P10).
- [ ] Multiplayer progress/statistics unavailability: **P9 NOT RUN**, compatible NeoForge dedicated server required.
- [ ] Retired read_progress offered-tool audit and old quest-source UI: **P11 NOT RUN**, logs contain a count rather than tool names and JSONL lacks definitions. Historical chest/advancement sources and links reopen (W7/P8), but this is not the complete P11 check.
- [ ] Broader missing-destination, interrupted/failed read and health-unit branches have automated coverage; not all were exercised by these live questions.

### Next plans

Stages 1–5 are implemented. Checked entries below describe the accepted scope, with incomplete acceptance split out explicitly. Plan links remain for implementation context; all current live results use the [single acceptance record](../validation/2026-10-25-v2-acceptance.md#acceptance-summary).

- [x] 1. One item-reading path ([plan](../plans/2026-10-25-v2-item-reading-consolidation.md)): installed completion checks C1–C4 pass.
  - [x] Held sword eligibility/exclusions and empty-hand handling (C1); offhand Luck facts used with a tooltip search (C4).
  - [x] Targeted mule/librarian and ridden donkey reads, with nested paths and actual cargo (W4/C3/C5).
  - [x] Open crafting menu inputs and excluded output preview (C2).
  - [x] Holder section/slot summaries and armor-slot facts appear in candidate equipment reads; all four pieces inspected (Q1/W3).
  - [x] Complete returned enchantment eligibility and tooltip-filter search (W3/C1/C4); source item facts support the answers.
  - [ ] Other no-holder word branches, modded-menu PARTIAL sections, menu-close staleness, every potion/component/fingerprint variant and old inventory/storage handle rereads: no complete installed acceptance captured.
  - [x] N2–N5 fixed with automated tests (internal; see world-reading backlog above).
- [x] 2. Progress and statistics ([plan](../plans/2026-10-22-v2-progress-statistics.md)): singleplayer P1–P8 and P10 pass, including P5's fixed-build recheck. P9/P11 and broader clauses remain unchecked above.
- [x] 3. More world knowledge ([plan](../plans/2026-10-25-v2-world-knowledge.md)): C5–C8 and L1/L2 pass within the following scope.
  - [x] Stationary librarian offers/player prices and never-opened librarian not-generated reads twice, followed by ordinary trade opening (C5).
  - [x] Zombie loot-table drops/conditions without a roll (C6).
  - [x] Fresh village prediction, visited found result, unchanged region listing and full-restart saved-place link (C7).
  - [x] Nether ground-view marker visibility/lifetime after depth-test fix at render distance 12, Overworld fixed-build visibility at 12 and earlier distance-8 visibility/expiry (L1/L2).
  - [x] Cheats-off /fill usage, argument types and permission restriction without execution (C8).
  - [ ] Wandering trader; pending-container loot/seed without unpacking and unknown/empty tables; biome id/tag sampling and continuation; wider command discovery/continuation: implemented but not fully exercised by these checks.
- [ ] 4. Journal and notes ([plan](../plans/2026-10-22-v2-journal.md)): J0/J4/J5/J6 and M1/M5 subchecks accepted; **M1 FAIL**, **M5/J7 NOT RUN** and the broader event/flush clauses above remain open.
- [x] 5. Whole-policy prompt rewrite and promotion ([plan](../plans/2026-10-22-v2-prompt-acceptance.md)): six live rounds, C6 effectively 14/14 under the controller note-action ruling, compiled equality, installed default/override-clear verification and Task 7 results recorded.
- [ ] Full acceptance: **E6 FAIL** for W8's missing mule coordinates; M1/M5/P9/J7/P11 remain as recorded. No seventh candidate was run.
- [x] Tool timing receipt/submission/summary logs and backend/tool-wall separation (E7), with independent active-effect/attribute overlap (E4).
- [x] includeNested for list_items decided against by the controller: list one holder, then step into returned nested paths; find_items owns nested discovery.
- [x] Removed-source prompt guidance tested: Q7/E2 brewing/piston answers use supported Minecraft recipes/plans without JEI, quest or guide wording; plan_recipe is acceptable for E2.

## Open

The [cross-stage follow-ups](../../.superpowers/sdd/final-followups.md) include both deferred work and already completed integration items. Keep the deferred items below open; this list does not reopen the completed WorldTools.run delegation, merged tool-count updates or whole-prompt rewrite.

- [ ] Fix E6 coordinates in located-entity answers and revalidate the changed prompt rule.
- [ ] Fix/retest M1 move semantics and deduplication with a clean, unambiguous fixture.
- [ ] Capture M5 shutdown hook/overflow counters; run P9/J7 on a NeoForge dedicated server; make offered tool names auditable for P11.
- [ ] Investigate Part B's unscored unsupported fortress link and locate cursor reuse with changed radius. Rendering acceptance does not resolve either prompt observation.
- [ ] Return Section from EntityInspector so bounded trades/attributes can report PARTIAL.
- [ ] Reduce duplicated offer components/enchantment/potion data and the approximately 192 KB worst case. offerCount was added; payload duplication remains deferred.
- [ ] Split WorldTools after the merges; run delegation is already complete.
- [ ] Add continuation for progress/statistics composites that currently stop at the call budget with a repeat note.
- [ ] Task 2c: quiet routine test timing logs; deferred because ModDev forces its own Log4j configuration (about 153 timing lines after the summary follow-ups).
- [ ] Consider caching detached command snapshots/usage if live timing warrants it; canUse/canRun duplication remains a review follow-up.
- [ ] Resolve the resource warning for packsage:font/OFL.txt by moving/renaming the license resource.
- [ ] Complete the broader unchecked item/menu, world-knowledge, journal and progress clauses above before claiming their full acceptance.
- The paused JEI recipe-planning tasks J3–J7 belong to feat/packsage-v1 and do not apply until a JEI adapter returns.
