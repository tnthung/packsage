# Final-review fixes: installed acceptance

Date: 2026-10-26. These checks cover the fixes made after the final review of `feat/packsage-v2`: world, journal and client fixes, and the promoted prompt (candidate 8). Results go in [the acceptance record](../validation/2026-10-25-v2-acceptance.md) under a new "Final-review installed recheck" section.

## Setup

- Instance `C:/Users/hung1/curseforge/minecraft/Instances/Packsage`, save "PackSage Adapter Filter Live Test". `config/packsage.properties` has `debugBridge=true`.
- Install: close Minecraft cleanly (Save and Quit to Title, then Quit Game), confirm no `javaw` remains, run `powershell -ExecutionPolicy Bypass -File D:/Project/packsage/scripts/build.ps1`, record the installed JAR's SHA-256 and the branch HEAD, relaunch through CurseForge and enter the save.
- Drive every check through the [debug bridge](2026-10-25-debug-bridge.md). Read the token from `<instance>/packsage/debug-bridge.json` privately and never print it. Use computer use only to close and relaunch the game.
- Each question uses a new chat. Record each request id, the calls with arguments, coverage and detail, the verbatim reply, and every `/navigate` result. Keep raw JSON and screenshots under `build/final-review-live/` (ignored).

## Checks

| Id | Steps | Pass when |
|---|---|---|
| D1 | `GET /prompt` | `text` is null; `effectiveText` equals `build/prompt-candidates/candidate-8.txt` byte for byte |
| R1 | At a new spot, "I built a copper farm here". Teleport 30 blocks away, "I moved my copper farm". Then "where is my copper farm?" and `/navigate` its note link | remember with `at` player; the move uses update_note on the same note with `at` the player and no clearPlace; one note; the reply gives the new coordinates; the link marks the new place |
| R2 | Summon a tamed no-AI mule at a whole-block x/z, look at it, "how high can this mule jump?" | inspect_entity; the reply gives the mule's coordinates matching its F3 block (floor of its position) beside its link |
| W1 | Near a village or a few summoned villagers with job sites, "what job sites do these villagers have?" and ask about one villager's home and job site | Villager memory facts come back; any POI fact that is not cached says unavailable and its section is PARTIAL with a note, never COMPLETE |
| W2 | Summon 80 armor stands or chickens across a chunk border, then `list_entities` through a chat question such as "list the chickens within 24 blocks", continuing through every page | Every entity appears exactly once across pages; a stable single read reports COMPLETE; any PARTIAL note names a concrete reason |
| W3 | "find iron ingots near me" with a few chests holding iron within 16 blocks | find_items returns the chests; with a stable world the result is COMPLETE |
| W4 | A villager with many trades (a librarian at master level via `/data`) or an entity with many attribute modifiers: "what does this villager trade?" | Capped offers or modifiers come back PARTIAL with a note; a very large record keeps its small facts |
| W5 | Click a world link (block or entity) from any reply via `/navigate` while the game is running | The highlight appears without a visible client stall; a link to a missing target reports unavailable, never a substitute |
| S1 | "what are my top statistics?" and a statistic question that needs many counters | list_statistics completes; values are ordered; no error |
| J1 | Break and place some blocks, quit to title, re-enter the world, then "what did I break in the last hour?" | The journal answers; any recording gap is reported as partial, including gaps from before the restart |
| J2 | Write a long note (over 1,000 characters) through chat, then "what do you remember about <it>?" | The note returns with a short title and bounded original wording; pages continue without losing records |
| P1 | "which advancement trees have I made the most progress in?" then ask a follow-up that rereads one tree | Rereading a tree with more than 64 entries reports COMPLETE when the snapshot is complete |
| C1 | `POST /prompt` with a short test text, `GET /prompt`, then `POST /prompt {text:null}` | Both saves succeed and the game stays responsive; `config/packsage.properties` ends without a developer prompt |

Report each check as PASS, FAIL or NOT RUN with the evidence. A failure keeps its raw output; do not retry until it passes.
