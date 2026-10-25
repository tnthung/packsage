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
- Advancement progress and player statistics through `list_progress`, `inspect_progress` and `list_statistics` in singleplayer; implemented, not yet checked in game.

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
- [ ] Villager time fields: facts were cited, but the reply did not reason from `gameTime`, and `golemDetectedUntil` was not exercised (Round 2, partial). Belongs with the prompt plan.
- [x] Saved highlight links across a full Minecraft restart: installed check passed (Round 2).
- [x] N1: fixed; installed recheck passed in Round 3 with 64 records per page (52 items and 12 holders) and a cursor for the rest. Reproduced in game in Round 2: 72 item types returned 55, with no holder records and a null cursor. A full page of found items with holder records exceeds the 64-record tool-output limit. That drops holder links and the continuation, and replaces the page notes. See the final re-review in `.superpowers/sdd/2026-10-22-v2-world-reading/final-rereview.md`.
- [x] Minor review backlog, fixed with automated tests in the [item reading plan](../plans/2026-10-25-v2-item-reading-consolidation.md); these are internal and need no live check:
  - N2: the paused-work flag can stay set. Fixed: the in-flight flag is keyed to the server and cleared when it stops.
  - N3: a tick can run on the client thread when the server has stopped. Fixed: the queued task steps only on a running, paused server's own thread.
  - N4: an early stop can make no progress. Fixed: a stop waits for at least one new container or chunk.
  - N5: `inspect_area` can drop the entity page's note. Fixed: the note is kept alongside the `list_entities` pointer.

### Journal and notes

Stage 4's [plan](../plans/2026-10-22-v2-journal.md) is implemented, including automatic recording, run merging, `search_journal`, silent `remember` / `update_note` / `forget_note`, note anchors and persistent note/place highlights. The stage's build reported 1,014 automated tests: 1,010 passed, 4 skipped, no failures or errors. Minimal prompt rules are included; the whole-prompt rewrite remains Stage 5. Full installed acceptance remains pending. Keep every item below unchecked until it passes.

The 999ac78 smoke run recorded and wrote 26/26 events, but Candidate 2 exposed a closed journal after late hook registration. Startup recovery, duplicate-destination bridge navigation and inspected-air target advertising now have failing-then-passing regressions. The full build reports 1,051 passed and 4 skipped; the new JAR is not installed, so full acceptance and installed rechecks remain pending ([fix report](../../.superpowers/sdd/2026-10-25-v2-prompt-acceptance/fix-journal-closed-report.md)).

Live checks for that final run, on the installed JAR in singleplayer with the default prompt and the developer override cleared (except the multiplayer check):

- [ ] SQLite driver loads under FML on Windows: joining a world creates `saves/<world>/packsage/journal.db` and its `-wal` file, with no native-library error. If module-layer loading fails, retry with `-Dorg.sqlite.tmpdir=<gameDir>/packsage/native` and record the result.
- [ ] The access transformer exposes `Raids.raidMap` without linkage/access errors; polling sees the live raid registry in each dimension.
- [ ] Block placement records the block, player, time and position.
- [ ] Block breaking records the block, player, time and position.
- [ ] Explosions record their source, location and affected block runs; truncated snapshots report missing detail as partial coverage.
- [ ] Container deposits and withdrawals: put stone into one chest and take iron out of another, then ask "what did I put in chests today?" Expect item counts, direction and working chest links. Also check a menu closed by disconnect, cancelled-click mod GUIs and keyed horse inventories; unsupported ownership must leave no misleading holder hint.
- [ ] Deaths: record a player, named mob, villager and tamed animal death with causes. On Easy, have a zombie kill a villager; an iron-farm diagnosis must combine that journal record naming the zombie with a live read of another villager missing its bed.
- [ ] Player kills record the player and killed mob type.
- [ ] Dimension travel records portals with source-side positions: enter the Nether and ask "where did I enter the Nether?" Expect the departure portal, including a last source position when no portal is known.
- [ ] Earned advancements record their identity and time; ask "when did I get it?"
- [ ] Villager profession changes record the old and new profession.
- [ ] Curing a zombie villager records the cure.
- [ ] A villager converting to a zombie villager records the conversion.
- [ ] Trading with a villager records the trade and player.
- [ ] Raid starts record the raid and its location.
- [ ] Raid finishes record the outcome without inventing a start for a raid first observed finished.
- [ ] First structure entries record the structure and place without duplicate records on re-entry.
- [ ] Invisible notes: say "I built an iron farm here" and later "I moved my iron farm" elsewhere. No reply, toast, chat line or activity list announces note saving; the correction updates one note rather than duplicating it. A request to forget removes it. Questions and requests are not saved as reports, and recalled notes are described as the player's report rather than game-confirmed facts.
- [ ] Note anchors and saved links: after a full Minecraft quit/relaunch/rejoin, ask "where was my iron farm?" Expect coordinates and a working `Show` link; "check around my iron farm" reads around the note's place. A saved note link follows a move under the same id and reports unavailable after forgetting; a saved journal place link still highlights the same place. Other-dimension links give coordinates.
- [ ] Honest search coverage: results expose `recordingSince`; a time before recording, a recording gap or missing event detail is PARTIAL with a reason, never proof that nothing happened.
- [ ] Multiplayer: every journal and note tool reports unavailable on a multiplayer server.
- [ ] Bulk performance: mine 2,000 sand quickly, then set off TNT. Expect no visible stutter in the F3 frame-time graph or change from idle `/tick query` mspt; search shows a few sand runs totaling about 2,000, the explosion and its break runs. At world close, the log summary reports `maxHookNanosPerTick` under 1 ms and `overflowed` 0.
- [ ] The first message of a brand-new chat with a tool call carries its persisted conversation id; notes retain that chat/message origin.
- [ ] Journal flushes when quitting the game: exit Minecraft immediately after a burst, fully restart and rejoin, then verify the final queued events and open runs survived. Also verify Save and Quit to Title followed by rapid rejoining preserves the records.

After the final run, record observed results in `docs/validation/2026-10-22-v2-journal.md` and check only passing entries here and in both roadmaps. That validation record has not been created yet.

### Progress and statistics

Stage 2's [plan](../plans/2026-10-22-v2-progress-statistics.md) is implemented. The full build reports 736 automated tests: 732 passed, 4 skipped, no failures or errors. These features are not yet checked in game; keep each item unchecked until it passes the single acceptance run at the end of all stages.

- [ ] `list_progress`, `inspect_progress` and `list_statistics`: advancement status and per-criterion state, plus player counters in blocks, seconds and health points. Interrupted or failed reads report PARTIAL with notes; multiplayer reports unavailable.
- [ ] Hidden advancements redacted until earned: no names, descriptions, criteria, requirements, unlocks or rewards, including through filters and inspection.
- [ ] Advancement links labeled `Open advancements`, supplied by provider `progress`, open the current tab and survive a full Minecraft restart. Missing advancement destinations report unavailable.
- [ ] The old `read_progress` plumbing removed, with earlier chats still displaying their saved quest sources and links.

Live checks for the final acceptance run, on the installed JAR with the developer prompt override cleared (singleplayer except the multiplayer check):

- [ ] Adventuring Time: with a few biomes visited, ask "what advancements am I missing for Adventuring Time?" Expect missing biomes by name from `inspect_progress` and an `Open advancements` link that opens the Adventure tab.
- [ ] Diamonds: after mining diamond ore, ask "how many diamonds have I mined?" Expect the exact `mined` counts for `minecraft:diamond_ore` and `minecraft:deepslate_diamond_ore`; picked-up diamonds may also be counted if clearly distinguished.
- [ ] Walking: ask "how far have I walked?" Expect `walk_one_cm` in blocks, or kilometres derived from blocks, rather than centimetres.
- [ ] Never mined: in a fresh world, ask "how much ancient debris have I mined?" Expect an explicit zero counter value rather than unknown.
- [ ] Hidden advancement: with a hidden advancement partly progressed, ask "what hidden advancements do I have left?" Expect a count with no names, real identifiers or criteria. Live P5 failed by exposing identifiers; the opaque world-session id fix passes automated regression checks, with live reacceptance pending.
- [ ] Play time: ask "how long have I played this world?" Expect `play_time` in seconds, converted sensibly for the reply.
- [ ] Next goal: ask "what should I do next for advancements?" Expect `available` entries from `list_progress`, each with a working link.
- [ ] Saved link: open a saved advancement link after a full Minecraft restart and expect its tab to open.
- [ ] Multiplayer: join a multiplayer server and expect all three progress and statistics tools to report unavailable.
- [ ] Performance: run `list_progress` with a large advancement datapack loaded and expect no visible stall.
- [ ] Retired tool and saved chats: verify the model-facing tool list has no `read_progress`, and earlier chats still display their saved quest sources and links.

After the final run, record results in `docs/validation/2026-10-22-v2-progress-statistics.md` and check only passing items here and in both roadmaps. That validation record has not been created yet.

### Next plans

The Minecraft adapter is finished before this branch merges back. The [completion specification](../specs/2026-10-25-v2-minecraft-completion.md) adds stages 1 and 3. The work lands in this order:

- [ ] 1. One item-reading path ([plan](../plans/2026-10-25-v2-item-reading-consolidation.md)). Implemented; the build passes 679 automated tests. Not yet checked in game; the in-game acceptance runs once at the end of all stages, and each item below stays unchecked until it passes:
  - [ ] `held` and `offhand` words for `list_items` and `inspect_item`, resolved at call time; unresolvable words answer with a fixed detail instead of an error.
  - [ ] `targeted` and `ridden` holders, and gameplay context `targetedHolder` and `ridden`.
  - [ ] `opened` menus: menu sections, output previews listed under `excludedSlots`, `menuType` and title, modded menus PARTIAL under `minecraft/menu`, and paths valid only while the menu stays open.
  - [ ] A holder summary record with section sizes and empty slots, plus the selected-slot and armor-slot markers.
  - [ ] Full item facts in `inspect_item`: components, a component fingerprint, full potion contents and always-present `enchantmentEligibility`.
  - [ ] Tooltip lines in `inspect_item` and the `filter.tooltip` search in `list_items` and `find_items`.
  - [ ] `search_inventory` and the legacy inventory and storage readers removed; old `inventory:` and `storage:` handles reread as unavailable.
  - [x] N2–N5 fixed (see the backlog above).
- [ ] 2. Progress and statistics ([plan](../plans/2026-10-22-v2-progress-statistics.md), [acceptance checks](#progress-and-statistics)). Implemented; not yet checked in game. Acceptance runs once at the end of all stages.
- [ ] 3. More world knowledge ([plan](../plans/2026-10-25-v2-world-knowledge.md)). Implemented with the short prompt mention and review fixes; the build reports 781 automated tests: 777 passed and 4 skipped. Not yet checked in game; awaiting the final in-game acceptance run below.
  - [ ] Villager and wandering-trader offers and prices through `inspect_entity`, without generating trades; implemented, not yet checked in game.
  - [ ] `read_loot` for table, block, entity and pending container loot, without rolling or unpacking it; implemented, not yet checked in game.
  - [ ] `locate` for biome ids/tags and found or predicted structures, with persistent place links, without loading or generating chunks; implemented, not yet checked in game.
  - [ ] `search_commands` and `read_command` from a paced detached snapshot, including usage, argument types and player permissions, without running commands; implemented, not yet checked in game.
- [ ] 4. Journal and notes ([plan](../plans/2026-10-22-v2-journal.md), [acceptance checks](#journal-and-notes)). Implemented; not yet checked in game, awaiting the final in-game acceptance run.
- [ ] 5. Prompt and acceptance (below).

Stage 3 live checks for the final run, using the installed build in singleplayer with the default prompt and the developer override cleared. All are not yet checked in game:

- [ ] Trades: at a librarian already traded with, ask "what does this villager sell?" Check cited offers, distinguishing item facts such as enchantments, and prices for the player. Also check a wandering trader.
- [ ] Trades without generation: give a fresh villager a lectern and ask twice before the next autosave. Both reads must say "not generated yet"; then right-click it to confirm the game generates its offers normally.
- [ ] Loot: ask "what does a zombie drop?" Check that `read_loot` supplies the table's drops and conditions without rolling it. For an unopened chest, check its pending table and seed, then open it normally and confirm it still yields loot. Unknown and empty tables must be explicit.
- [ ] Biomes: locate a biome by id and tag. Check coordinates, sampling resolution and a working place link. Continue a partial result by its cursor when one is returned.
- [ ] Structures: in a new world, ask "where is the nearest village?" Check coordinates, a found or predicted label, and a working place link. Repeat near a visited village and expect found. Compare region-directory listings before and after the call: it must create no region files or load or generate chunks. Unknown saved-start confirmation must not be presented as proof of absence.
- [ ] Place links: check nearby and distant marker columns in the same dimension, a different-dimension link's coordinates, and a saved link after a full Minecraft restart. Check that columns are visible in the Nether at default render distance and in the Overworld at 8 chunks.
- [ ] Commands: ask "how do I use /fill?" without cheats. Check usage forms, argument names/types and `canUse: false`; `search_commands` must still discover the command. No command is run.

Plan details:

- [ ] Progress and statistics ([plan](../plans/2026-10-22-v2-progress-statistics.md)): the `ProgressReader` and `StatisticReader` capabilities, `list_progress`, `inspect_progress` and `list_statistics` are implemented; not yet checked in game (see the [acceptance checks](#progress-and-statistics)).
- [ ] Prompt and acceptance ([plan](../plans/2026-10-22-v2-prompt-acceptance.md)): live-tested candidate 6 is promoted to the default with compiled byte equality verified. The controller accepts its identical-note no-write behavior under the round-4 ruling; installed-default acceptance remains pending. Includes the Round 1 failures:
  - the armor reply omitted eligible enchantments that the evidence returned;
  - the mule reply gave no coordinates;
  - prefer `find_items` before per-holder listing;
  - consider an `includeNested` option for `list_items`;
  - continue a broad `find_items` cursor when the question asks what is stored (Round 3 stopped after the first page and said so);
  - a reply ended with a malformed source token, `[ref:e36be868.1.1f)`, shown raw instead of a source chip (Round 3).
- [ ] Log when a tool call is received and when its result is sent, so model time and tool time can be separated in response timing.

## Open

- The assistant prompt edits that remove JEI, quest, guide and Ponder guidance are untested in game.
- The paused JEI recipe-planning work (tasks J3–J7) is on `feat/packsage-v1` and does not apply to v2 until a JEI adapter returns.
