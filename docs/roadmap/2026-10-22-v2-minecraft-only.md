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
- The `search_document` and `read_progress` tools, which have no source left. They stay out of the model-facing tool list until a document or quest source returns. The internal document discovery and progress plumbing remains so a future adapter can restore them.

## Remaining

- Chat core: screen, chat runtime, conversation store and model backend.
- Conversation history tools: `search_history` and `read_history`.
- Config reader: `list_configs`, `search_config` and `read_config`.
- Minecraft adapter: recipes and brewing, recipe planning, registries, effects, attributes, dimensions, and inventory, equipment and storage reading through the world tools.

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
- [ ] 2. Progress and statistics (below).
- [ ] 3. More world knowledge (`docs/plans/2026-10-25-v2-world-knowledge.md`):
  - villager trades without generating them;
  - `read_loot`;
  - `locate` for biomes and structures without generating chunks;
  - read-only `search_commands` and `read_command`.
- [ ] 4. Journal and notes (below).
- [ ] 5. Prompt and acceptance (below).

Plan details:

- [ ] Journal and notes (`docs/plans/2026-10-22-v2-journal.md`): automatic event recording into SQLite with run-length merging, `search_journal`, and invisible model-chosen notes.
- [ ] Progress and statistics (`docs/plans/2026-10-22-v2-progress-statistics.md`): the `ProgressReader` and `StatisticReader` traits, `list_progress`, `inspect_progress` and `list_statistics`.
- [ ] Prompt and acceptance (`docs/plans/2026-10-22-v2-prompt-acceptance.md`, not written). Includes the Round 1 failures:
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
