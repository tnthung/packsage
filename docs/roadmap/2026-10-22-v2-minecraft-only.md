# PackSage v2: Minecraft-only baseline

Updated: 2026-10-26.

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
- Advancement progress and player statistics through `list_progress`, `inspect_progress` and `list_statistics` in singleplayer; installed P1–P11 acceptance passed, with P9 on a NeoForge dedicated server.

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

World final-review follow-up: [automated validation](../validation/2026-10-26-world-review-fixes.md) covers no-load POI reads, dense entity/holder and statistics traversal, entity truncation/output bounds and asynchronous highlight resolution. The [2026-10-27 installed retest](../validation/2026-10-26-world-review-fixes.md#2026-10-27-installed-acceptance) passes the documented public reads, verified >4,096 tails, frozen-time coverage and output paging. Broad acceptance remains pending: W-R1 is a delivered-count metadata bug, and the listed deterministic internal branches retain automated-only coverage.

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
- [x] Move one existing note to the new place, preserving its id/link and avoiding duplicates: M1 PASS with prompt candidate 7 (installed recheck R1).
- [x] Bulk performance acceptance: M5 passed in round 8 (overflowed 0, failed 0, 0 slow ticks after the first 200); the controller amended the criterion to steady state after a ten-second warm-up.
- [x] Multiplayer journal/note unavailability: J7 passed on a NeoForge 21.1.202 dedicated server with the instance's mods (round 4).
- [x] Raids start, finish and outcome (round 1 J11).
- [x] Player placement, kills, deaths, profession changes, curing, zombie conversion, trades and first structure entry (round 1 J10; profession round 6/7 J10r).
- [x] Withdrawal journaling and mount (donkey) menu ownership (round 4 J8r, round 6 J8r3 journal pass) and the disconnected-menu session (round 3 J9).
- [x] J8 withdrawal reply compliance: the original withdrawal answer and same-chat follow-up both give 17 gold and (1605,250,1602) from journal event 329 ([2026-10-27 retest](../validation/2026-10-25-v2-acceptance.md#2026-10-27-retest)).
- [x] Note anchors, other-dimension links, forget/delete with links unavailable, and questions not saved (round 1 J12 partially, round 2 J12r/N1 with candidate 9 promoted, round 4 N2, round 5 N3).
- [x] First tool-using chat origin on notes (round 1 J14 controller ruling).
- [x] Flush on immediate exit and rapid Save-and-Quit/rejoin (round 3 J13).

### Progress and statistics

Stage 2's [plan](../plans/2026-10-22-v2-progress-statistics.md) is implemented. Results live in the [single acceptance record](../validation/2026-10-25-v2-acceptance.md#acceptance-summary), replacing the former per-stage validation pointer. P5 passed on the installed hidden-id fix; tab visibility and restart checks passed. Earlier fixture failures remain historical, superseded by Part B.

- [x] Advancement status, per-criterion missing biome names and available next goals with usable visible-tab links (P1/P7).
- [x] Hidden advancements return only count/anonymous aliases, without real names, ids or criteria in the audited results/reply (P5 recheck). Real-id lookup/order restrictions also have automated integration checks.
- [x] Open advancements links work for visible tabs and after a full restart (P8); invisible tabs remain source-only (Q11).
- [x] Exact diamond/deepslate-diamond mined counts distinguished from drops (P2).
- [x] Walking reported in blocks (P3), never-mined ancient debris explicitly zero (P4), play time sensibly converted from seconds (P6).
- [x] A 2,000-advancement datapack read has no visible stall in sampled frame/tick evidence (P10).
- [x] Multiplayer progress/statistics unavailability: P9 passed on a NeoForge 21.1.202 dedicated server with the instance's mods (round 4).
- [x] Retired read_progress offered-tool audit: P11 passed (round 3 C1r: request_built lists 28 tools, no read_progress) and saved v1/legacy citations render with unavailable destinations (round 3 W17).
- [x] Progress/statistics composite continuation passed by code and automated coverage (B1 ruling PASS; the acquisition-budget branch was not triggered live). The statistics one-step owned key copy is an accepted limitation (Astra accepted).
- [ ] Missing-destination, interrupted/failed read and health-unit branches have automated coverage only; the progress acquisition-budget continuation branch cannot be set up live.

### Next plans

Stages 1–5 are implemented. Checked entries below describe the accepted scope, with incomplete acceptance split out explicitly. Plan links remain for implementation context; all current live results use the [single acceptance record](../validation/2026-10-25-v2-acceptance.md#acceptance-summary).

- [x] 1. One item-reading path ([plan](../plans/2026-10-25-v2-item-reading-consolidation.md)): installed completion checks C1–C4 pass.
  - [x] Held sword eligibility/exclusions and empty-hand handling (C1); offhand Luck facts used with a tooltip search (C4).
  - [x] Targeted mule/librarian and ridden donkey reads, with nested paths and actual cargo (W4/C3/C5).
  - [x] Open crafting menu inputs and excluded output preview (C2).
  - [x] Holder section/slot summaries and armor-slot facts appear in candidate equipment reads; all four pieces inspected (Q1/W3).
  - [x] Complete returned enchantment eligibility and tooltip-filter search (W3/C1/C4); source item facts support the answers.
  - [x] Other missing-holder cases, menu-close staleness (W10), the opened mount menu path (W11r), every potion/component/fingerprint variant with stale handle rereads (W9) and holder/progress-tree rereads (W16) passed live.
  - Modded-menu PARTIAL sections: not exercised live; deferred by the player (2026-10-27) until the storage mod adapters (for example Sophisticated Storage or Iron Chests).
  - [x] N2–N5 fixed with automated tests (internal; see world-reading backlog above).
- [x] 2. Progress and statistics ([plan](../plans/2026-10-22-v2-progress-statistics.md)): singleplayer P1–P8 and P10 pass, including P5's fixed-build recheck. P9/P11 and broader clauses remain unchecked above.
- [x] 3. More world knowledge ([plan](../plans/2026-10-25-v2-world-knowledge.md)): C5–C8 and L1/L2 pass within the following scope.
  - [x] Stationary librarian offers/player prices and never-opened librarian not-generated reads twice, followed by ordinary trade opening (C5).
  - [x] Zombie loot-table drops/conditions without a roll (C6).
  - [x] Fresh village prediction, visited found result, unchanged region listing and full-restart saved-place link (C7).
  - [x] Nether ground-view marker visibility/lifetime after depth-test fix at render distance 12, Overworld fixed-build visibility at 12 and earlier distance-8 visibility/expiry (L1/L2).
  - [x] Cheats-off /fill usage, argument types and permission restriction without execution (C8).
  - [x] Wandering trader (round 3 D1 ruling: full offers payload with shared item facts); pending-container loot table/seed and undefined/empty tables (round 4 W12r3 ruling); biome sampling/continuation and tag locate (round 1 W13, round 3 W19r); command discovery/continuation (W14).
- [x] 4. Journal and notes ([plan](../plans/2026-10-22-v2-journal.md)): every journal clause above is accepted, including M1 and M5; the J8 withdrawal reply and same-chat amount/location follow-up pass the [2026-10-27 retest](../validation/2026-10-25-v2-acceptance.md#2026-10-27-retest).
- [x] 5. Whole-policy prompt rewrite and promotion ([plan](../plans/2026-10-22-v2-prompt-acceptance.md)): six live rounds, C6 effectively 14/14 under the controller note-action ruling, compiled equality, installed default/override-clear verification and Task 7 results recorded.
- [x] F4 identity/command routing: exact op me uses TNThung and the approved command confirmation; a separate name question confirms context identity. The game rejects op in this singleplayer fixture; no permission grant is claimed ([retest](../validation/2026-10-25-v2-acceptance.md#2026-10-27-retest)).
- [x] Sidebar selection stability and recipe-card/chip popup layering pass the [2026-10-27 UI retest](../validation/2026-10-27-recipe-cards.md#2026-10-27-sidebar-and-popup-retest).
- [ ] Full acceptance: E6, M1, M5, P9, J7 and P11 now pass. Remaining: A3 reply compliance and the automated-only branches. J8 passes the 2026-10-27 retest. Astra final review READY at 6b2e903 (rereview 13).
- [x] Tool timing receipt/submission/summary logs and backend/tool-wall separation (E7), with independent active-effect/attribute overlap (E4).
- [x] includeNested for list_items decided against by the controller: list one holder, then step into returned nested paths; find_items owns nested discovery.
- [x] Removed-source prompt guidance tested: Q7/E2 brewing/piston answers use supported Minecraft recipes/plans without JEI, quest or guide wording; plan_recipe is acceptable for E2.

## Open

- Journal final-review fixes preserve recording-loss intervals across restarts with an additive schema-v1 table and keep byte-bounded pages resumable after their last delivered record. The [validation record](../validation/2026-10-26-v2-journal-final-review-fixes.md) records the follow-up shared encoder, marker retry/checkpoint and bounded-detail fixes with 1,079 passing tests, five skips and unverified live acceptance; these fixes do not close the broader journal checklist.
Final review: Astra returned **READY** at 0e894ac after four re-review rounds (no Critical or Important findings); four installed recheck rounds are recorded in the [acceptance record](../validation/2026-10-25-v2-acceptance.md). The vanished-anchor note found in the fourth round is fixed in 744a96d. Later native-readiness rereviews 5–11 are in `.superpowers/sdd/2026-10-25-v2-prompt-acceptance/astra-final-rereview-*.md`; rereview 12 is in progress, so the final review is still open and readiness is not claimed.

Native readiness: Astra final review **READY** at 6b2e903 (rereview 13, no findings). The [native readiness checklist](../testing/2026-10-26-native-readiness.md) was run in eight live rounds ([acceptance record](../validation/2026-10-25-v2-acceptance.md), sections Native readiness round 1–8 and their controller rulings; [plan](../plans/2026-10-26-v2-native-readiness.md)). Everything except the items below passed live or by controller ruling; A3 reply compliance remains recorded, J8 passes the [2026-10-27 retest](../validation/2026-10-25-v2-acceptance.md#2026-10-27-retest), and two branches are automated-only.

Final client-review fixes have automated coverage for historical v1 citation chips and unavailable destinations, worker-side debug prompt saves with concurrent screen updates, and fresh holder/progress-tree evidence rereads. Their installed acceptance remains pending; the promoted prompt text is unchanged.

Fix round 1 adds regressions for large progress-tree reread coverage, unused holder-reread cursors, atomic settings replacement and bounded prompt-save conflicts. The fixes preserve the existing feature and installed-acceptance scope.

The [cross-stage follow-ups](../../.superpowers/sdd/final-followups.md) include both deferred work and already completed integration items. Keep the deferred items below open; this list does not reopen the completed WorldTools.run delegation, merged tool-count updates or whole-prompt rewrite.

- [x] Fix E6 coordinates in located-entity answers and revalidate the changed prompt rule (candidate 8; installed R2 passed).
- [x] Fix/retest M1 move semantics and deduplication with a clean, unambiguous fixture (candidate 7; installed R1 passed).
- [x] M5 steady-state bulk performance (round 8: overflowed 0, failed 0, 0 slow ticks after the first 200; the controller amended the criterion to steady state after a ten-second warm-up), P9/J7 multiplayer unavailability (round 4, NeoForge 21.1.202 dedicated server with the instance's mods) and P11 offered tool names (round 3 C1r: request_built lists 28 tools, no read_progress).
- [x] Part B prompt observations: the Overworld fortress question has no invented link (E8) and locate continuation keeps its arguments (E9).
- [x] Return Section from EntityInspector so bounded trades/attributes can report PARTIAL (installed W4 passed).
- [x] Reduce duplicated offer components/enchantment/potion data: offers share one item-facts table (worst case about 220 KB to 25 KB); installed D1 ruling passed with the full offers payload for the wandering trader and master librarian.
- [x] Split WorldTools after the merges; run delegation was already complete.
- [x] Add continuation for progress/statistics composites: single-use acquisition cursors replace the repeat note; code and automated coverage, and installed B1 ruled PASS (the acquisition-budget branch was not triggered live).
- [ ] Task 2c: quiet routine test timing logs; deferred because ModDev forces its own Log4j configuration (about 153 timing lines after the summary follow-ups).
- Not pursued: command snapshot caching. No live timing issue appeared in W14 or C8, so it stays unneeded unless timing evidence appears; the canUse/canRun duplication remains a review follow-up.
- [x] Resolve the resource warning for packsage:font/OFL.txt: the license moved to assets/packsage/licenses/OFL.txt.
- [x] Final-review Minors from the [READY verdict](../../.superpowers/sdd/2026-10-25-v2-prompt-acceptance/astra-final-rereview-4.md): an oversized first record keeps later records and the continuation, a failed output replay discards its new tail, and a rejected server submission leaves the queue (automated regressions).
- [x] plan_recipe routes: the output-keyed lookup reaches every loaded recipe, fixing the zero routes for beacon, enchanting table and oak planks; installed A1r/A3 plan detail/W20/W21 passed with concrete PARTIAL details, intermediate expansion and smithing inputs (rounds 3–8).
- [x] Holder and progress-tree evidence rereads (W16), saved v1 citations rendered with unavailable destinations (round 3 W17) and the wandering trader (round 3 D1 ruling) passed live.
- [ ] Uncached-POI section branch of inspect_block and the progress acquisition-budget continuation branch cannot be set up live; they have automated coverage only.
- [ ] Recorded reply-compliance failures: A3 (accepted by the player on 2026-10-27; the from-scratch beacon reply does not say obsidian is obtained, though the plan detail is correct). J8 passes the 2026-10-27 retest, including the same-chat follow-up. Accepted limitation: the statistics one-step owned key copy (Astra accepted).
- Modded-menu PARTIAL sections are deferred until the storage mod adapters (player decision, 2026-10-27); the rest of the item/menu, world-knowledge, journal and progress clauses are accepted above.
- The paused JEI recipe-planning tasks J3–J7 belong to feat/packsage-v1 and do not apply until a JEI adapter returns.
