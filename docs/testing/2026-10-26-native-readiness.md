# Native adapter readiness: installed acceptance

Date: 2026-10-26. These checks close the unchecked clauses of the [v2 roadmap](../roadmap/2026-10-22-v2-minecraft-only.md) for the native adapter after the [readiness plan](../plans/2026-10-26-v2-native-readiness.md) tracks merge. Results go in [the acceptance record](../validation/2026-10-25-v2-acceptance.md) under a new "Native readiness round" section.

## Setup

- Same instance, save and install procedure as the [final-review recheck](2026-10-26-final-review-fixes.md#setup): clean close, `scripts/build.ps1`, record the installed SHA-256 and HEAD, relaunch through CurseForge.
- Drive every check through the [debug bridge](2026-10-25-debug-bridge.md); never print its token. Computer use is only for closing, relaunching and joining a server.
- `GET /prompt` before and after: `text` is null and `effectiveText` equals `build/prompt-candidates/candidate-8.txt`.
- New chat per question. Record request ids, calls with arguments and call groups, coverage and detail, the verbatim reply and every `/navigate` result. Raw files go under `build/native-readiness-live/` (ignored).
- Build fixtures with commands under 256 characters, in a scratch area away from earlier fixtures. A check that cannot be set up is NOT RUN with the reason.

## Checks

### Code tracks

| Id | Steps | Pass when |
|---|---|---|
| A1 | "how do I make a beacon?", "how do I make 64 enchanting tables?", "how do I make oak planks?" | plan_recipe returns routes with complete leading inputs; any PARTIAL names a concrete limit; links open the recipes |
| A2 | "how many sticks and planks do I need for 10 pistons?" | the plan reaches intermediate recipes; totals follow the returned yields |
| B1 | With the 2,000-advancement datapack loaded: "what advancements am I missing?" and "what are all my statistics?" | a composite that stops at its work budget continues to later records; no repeat-from-start; coverage exactly once |
| D1 | A master librarian and a summoned wandering trader: "what does this villager sell?", "what does this trader sell?" | every offer with prices; no trimmed offers; enchanted book/potion facts correct |
| C1 | Any question, then read the latest `request_built` log line | it lists the offered tool names, without `read_progress` |

### Journal and notes

| Id | Steps | Pass when |
|---|---|---|
| M5 | Break 2,000 sand quickly with `/player/break`, set off TNT, ask "what did I just dig up and blow up?", then Save and Quit | runs sum to about 2,000 plus the explosion; no visible stall; the world-close `journal:` log line shows overflowed 0 and slowest tick under 1,000 us |
| J8 | Take iron out of a chest, and put items in and take them out of a chested donkey: "what did I take out of chests today?", "what did I put in my donkey?" | withdrawals and the donkey deposits with links and coordinates |
| J9 | Open a chest and close it by disconnecting (Save and Quit with it open); rejoin and ask about it | the session is recorded or reported as incomplete, never invented |
| J10 | Place blocks; kill a mob; die to a named zombie; let a tamed wolf die; change a villager's profession; cure a zombie villager; let a zombie convert a villager; trade; enter a structure for the first time and again | each family appears in search_journal with its actor, subject and place |
| J11 | Start a raid (Bad Omen in a village) and finish or lose it | raid start, finish and outcome are recorded |
| J12 | Note anchors and lifecycle: a note at a place, one in the Nether asked from the Overworld, "forget my copper farm", a note deleted, and a plain question ("what is a beacon?") | anchored links open; other-dimension links say the dimension; forgotten/deleted notes stop being recalled and their links report unavailable; questions are not saved |
| J13 | Break blocks then quit the game immediately; separately Save and Quit and rejoin within a few seconds | the final events and open runs are all present after relaunch/rejoin |
| J14 | Inspect the saved conversation file of the first tool-using chat in this round | the conversation and message origin fields are present and correct |
| P9/J7 | NeoForge 21.1.202 dedicated server on localhost (installer `--installServer` in a scratch folder outside the repo, `online-mode=false`, no mods). Join, then "what advancements am I missing?", "how many blocks have I mined?", "what did I do today?" | progress, statistics and journal tools report unavailable and the replies say so |

### World and items

| Id | Steps | Pass when |
|---|---|---|
| W9 | Potions, tipped arrows, a dyed and a named item, and a written book: inspect each; reread an earlier inventory/storage handle after moving items | component and potion facts are correct; rereads return current data or unavailable |
| W10 | Open a chest, ask about it, close it, ask "what's in this?" again | the closed menu is not reused as current |
| W11 | Look at and open a donkey and a mule screen: "what's in this?" | the opened path is used |
| W12 | A loot chest placed with a LootTable and never opened; an unknown table; an empty table: "what's in this chest?" | the table and seed are reported without unpacking; unknown and empty tables say so |
| W13 | "what biome am I in?" and "where is the nearest cherry grove?"; follow any continuation | biome id/tags come back; continuations resume |
| W14 | "what commands can I use to change the weather?" and a follow-up page | command discovery and continuation work |
| W15 | Villagers whose job sites are in unloaded/uncached areas: "where does this villager work?" | the POI fallback reports unavailable/PARTIAL with a note |
| W16 | Ask about a chest and an advancement tree, then "check that again" | holder and progress-tree rereads return current counts; a removed tree is refused |
| W17 | Open a saved v1 conversation with provider citations | the chips render; removed providers report unavailable without substitution |
| W18 | A resource pack adding `effect.minecraft.speed.description`: "what does Speed do?" | the description is returned; without it the status is not provided |

### Prompt observations and client

| Id | Steps | Pass when |
|---|---|---|
| E8 | In the Overworld: "where is the nearest nether fortress?" | no invented link; the reply says it is a Nether structure, with a supported link if one is returned |
| E9 | "where is the nearest village?" then "look further out" | a continuation keeps nextCall arguments unchanged; a new radius starts without the old cursor |
| U1 | Open the adapter popup with a long adapter list | width and scrollbar fit |
| U2 | `POST /prompt` while the screen saves settings; quit during a save | saves stay ordered; the game stays responsive; shutdown flushes the newest settings |

Report each check as PASS, FAIL or NOT RUN with the evidence. Keep a failure's raw output and do not retry it until it passes.

## Round 2

Run on the installed merge of `fix/v2-readiness`. Run every code-track check (A1, A2, B1, D1, C1), every round-1 NOT RUN check (M5, J8 donkey part, J9, J13, P9/J7, W11, W15, W17, W18, U1, U2), and the checks below.

| Id | Steps | Pass when |
|---|---|---|
| W12r | Same three sealed chests as round 1 (known, missing and empty tables): "what's in this chest?" for each | the reply names the table and seed and says when the table is undefined or empty; nothing is unpacked |
| W19 | "what tags does this biome have?" | the biome's tags come from a tool read |
| J12r | With `build/prompt-candidates/candidate-9.txt` set through `POST /prompt`: make a note, delete it ("delete my <thing> note"), then "where is my <thing>?" | the reply says no note is kept and does not restore the place from history |
| N1 | With candidate 9 still set, rerun the note rules it sits beside: report, move ("I moved my <thing>") and recall; a plain question; "what do you remember about me?" | one note follows the move with the new place; no reply mentions saving; questions are not saved |

After J12r and N1, `POST /prompt {"text":null}` and confirm that `GET /prompt` shows candidate 8 again.

## Round 3

Run on the installed current `feat/packsage-v2`. The installed default prompt is candidate 9 (`build/prompt-candidates/candidate-9.txt`), with no override. Use the new bridge operations in the [debug bridge guide](2026-10-25-debug-bridge.md) instead of desktop control: `/world/leave`, `/world/open`, `/server/connect`, `/player/open` with `openInventory`, `/screen/open`, `/resourcepacks` and `/game/quit`. Desktop control is needed only to relaunch the game after `/game/quit` or a crash.

| Id | Steps | Pass when |
|---|---|---|
| D2 | `GET /prompt` | `text` is null; `effectiveText` equals candidate 9 byte for byte |
| A1r | Repeat A1 | routes with complete leading inputs; PARTIAL detail names concrete reasons, for example raw materials or the depth bound; no recipe links (v2 has no recipe viewer) |
| A3 | "how many logs do I need for 10 pistons?" and "what do I need to make a beacon from scratch?" | the plan expands intermediates: 30 planks from 8 logs, and the beacon's glass and obsidian sources or their raw-material limits, with the arithmetic following the returned batches |
| C1r | Any question; read the bridge `request_built` capture | `toolNames` lists all 28 tools, without `read_progress` |
| W12r2 | The three round-1 sealed chests | the known table is not called undefined; the missing table is undefined; the empty table generates nothing; each reply names the table; nothing is unpacked |
| W19r | "where is the nearest forest?" | locate uses a biome tag such as `#minecraft:is_forest` or forest biome ids, and returns a sampled place with a link |
| D1 | Repeat D1 with a correctly built master librarian (`/data` on a villager standing on solid ground with the world unpaused) and a wandering trader after a player interaction has generated its offers | every offer with prices and correct item facts; not trimmed |
| M5, J8, J9, J13, P9/J7, W11, W17, W18, U1, U2 | As listed above, using the bridge operations | as listed above |

## Round 4

Install the current `feat/packsage-v2`. `GET /prompt` must show the candidate 9 default first. Then set `build/prompt-candidates/candidate-10.txt` with `POST /prompt` for every question in this round, and clear it at the end.

| Id | Steps | Pass when |
|---|---|---|
| A3r | "what do I need to make a beacon from scratch?" and "how many logs do I need for 10 pistons?" | the expansion follows the returned batches; obsidian and the other recipe-less materials are said to be obtained, not crafted |
| A1r2 | Repeat A1 | as in round 3 |
| W12r3 | The three sealed chests | the missing table's reply says opening it generates nothing, or that the table is undefined; the known and empty tables as in round 3 |
| J8r | Ride a chested donkey, open its inventory with `/player/open` and `openInventory`, deposit and then withdraw items with `/player/menu/move`, close it. Ask "what did I put in my donkey today?" and "what did I take out of chests today?" | the donkey deposit and withdrawal appear with the donkey's link and coordinates; "today" follows playerLocalTime |
| W11r | Open a donkey's inventory screen with the crosshair on something else: "what's in this?" | list_items opened, or another read that returns the open screen's contents |
| M5r | M5 again; read logs/latest.log after `/world/leave` | the `PackSage journal:` summary line is present, with overflowed 0 and slowest tick under 1,000 us; request_built lines are present too |
| W18r | W18 with `/resourcepacks` | the pack's Speed description is returned; disable it afterwards |
| P9/J7r | Copy every jar in the instance's mods folder except packsage into the server's mods folder, start it, join with `/server/connect` and ask the three P9/J7 questions | the progress, statistics and journal tools report unavailable, and the replies say so |
| N2 | With candidate 10 set: report a build, move it, recall it, and ask one plain question | as N1 |

## Round 5

Install the current `feat/packsage-v2`. No developer override is used in this round.

| Id | Steps | Pass when |
|---|---|---|
| D3 | `GET /prompt` | `text` is null; `effectiveText` equals `build/prompt-candidates/candidate-11.txt` byte for byte |
| A3r2 | "what do I need to make a beacon from scratch?" | obsidian and the other recipe-less materials are said to be obtained, not crafted; the expansion follows the returned batches |
| M5r2 | M5 again, then `/world/leave` and read the `PackSage journal:` summary | overflowed 0; record `slowest tick`, `slowest hook` and `world start and stop`. The gameplay slowest tick should be under 1,000 us; if not, the named hook is the evidence for a fix |
| J8r2 | J8r again | as in round 4 |
| N3 | Report, move and recall a build; a plain question; a "what did I do today?" journal question | as N1, and "today" follows playerLocalTime |
| A1r3 | One A1 question | as in round 3 |

## Round 6

Install the current `feat/packsage-v2`, with no override.

| Id | Steps | Pass when |
|---|---|---|
| D4 | `GET /prompt` | candidate 11 byte for byte, `text` null |
| M5r3 | M5 again, then `/world/leave` and read the `PackSage journal:` summary | overflowed 0; slowest tick under 1,000 us; record `slowest hook` |
| J8r3 | J8r with the mount opened through `/player/open` and `openInventory` while not riding | deposit and withdrawal journaled with the donkey's link and coordinates |
| A3r3 | "what do I need to make a beacon from scratch?" | recorded either way; the plan detail lists the recipe-less materials |
| J10r | Change a villager's profession: break its job site, place another workstation and wait about a minute | the profession change is journaled |

## Round 7

Install the current `feat/packsage-v2`, with no override.

| Id | Steps | Pass when |
|---|---|---|
| M5r4 | M5 again, then `/world/leave` and quote both `PackSage journal:` lines | overflowed 0; `ticks at or over 1 ms` is 0. If not, record the slowest hook and its position, for example "gameplay hook 1 of 2002" for a first-use cost |
| J10r2 | J10r again | the profession change is journaled |
| A3r4 | "what do I need to make a beacon from scratch?" | record the plan detail: either materials obtained rather than crafted, or "no recipe found, though …" when the pack has computed-result recipes; record the reply |
| W20 | "how do I make a netherite sword?" | the smithing record lists template, diamond sword and netherite ingot, and the reply names all three |

## Round 8

Install the current `feat/packsage-v2`, with no override.

| Id | Steps | Pass when |
|---|---|---|
| M5r5 | M5 again, starting at least 15 seconds after the world opens; then `/world/leave` and quote both `PackSage journal:` lines | overflowed 0 and failed 0; the count in `(N after the first 200)` is 0 |
| A1r4 | "how do I make a beacon?" | routes come back with a concrete PARTIAL detail, as before |
| W21 | "how do I make a map?" | no route for an empty map uses map extension or a filled map as input |
