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
