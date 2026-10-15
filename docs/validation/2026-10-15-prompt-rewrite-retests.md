# Prompt rewrite live retests

Retest [the coherent instruction rewrite](2026-10-15-coherent-assistant-instructions.md), including previously passing and claimed-fixed scenarios in [the workflow report](2026-10-15-natural-player-workflow-fixes.md). Follow [the natural-player protocol](../testing/2026-10-15-natural-player-conversations.md): independent player agents, fresh chats, ordinary questions and adaptive follow-ups, with tool evidence audited separately.

The trial world was saved and Minecraft closed before installation. Build, distribution and ATM10 installed JAR match SHA256 `1FF6A36FEA2AB9EAAAB9F446F14107F01EF1935D6181431614353D9843E9C0D9`, containing source commit `4a66a2f`. The full Java 21 `test build` rerun succeeded; live acceptance is recorded separately below.

| Scenario | Regression or acceptance target | Status |
| --- | --- | --- |
| Hopper automation | Full recipe, carried iron, feasible cheap feeding layout | Failed after three turns |
| Faster glass | Practical bounded comparison, throughput versus smelt speed | Two turns completed; evidence acceptance incomplete |
| Backpack | Complete recipe and carried-container shortage checks | Failed ingredient counts and citations |
| Stalled furnace | Known-empty fuel/output versus unread state, no inference from flames | Passed conversational check |
| Carried redstone | Correct returned slot references and relevant containers | Passed requested lookup |
| Fall protection | Actual equipment versus documented effects, practical acquisition | Pending |
| Progression | Clarification, fresh quest state, useful next project | Pending |
| Another furnace | Complete eight-input recipe, correct affordable count | Qualified: one craft verified; requested maximum omitted |

No new result is a pass until both answer usefulness and supporting evidence have been assessed. Physical crafting, machine operation and damage tests must be identified separately from assistant conversations.

## Interrupted installed-build batch

The user interrupted this batch to explore faster iteration and then requested developer prompt settings. Fall-protection and progression questions were prepared but never submitted. No crafting, fuel insertion or fall-damage test was performed. Trying the default P binding before discovering this instance uses F8 toggled an unrelated "Farting enabled" feature; this batch cannot claim an entirely unchanged game/client state.

The completed scenarios used GPT-6-Luna medium, screenshots enabled and per-send search off. Separate player agents (`luna_high_player_hopper_v2`, `luna_high_player_furnace_v2`, `luna_high_player_stall_v2`, `luna_high_player_materials_v2`, `luna_high_player_backpack_v2`, `luna_high_player_speed_v2`) supplied questions and adaptive follow-ups. `luna_high_retest_evidence_audit` independently inspected saved tool results. Player satisfaction did not override factual failures.

- Hopper: requests `2341deae-f747-42a7-8102-f53863bc7b59`, `db0634e2-5893-490f-97df-4a364e41a1fd`, `95e8b648-5cf0-4761-99e2-42124fde6dd0`. The first two answers acknowledged incomplete ingredients and deferred the full recipe to the player. The last claimed one iron ingot as the total cost from a partial `utilitarian:utility/logs_to_hopper` prefix. No exact evidence reread or carried-container read occurred. The first answer called the observed furnace "hopper-fed" without establishing that. The player still lacked the remaining materials plan.
- Furnace crafting: `95b0b3b8-afd4-4cfa-8939-d077525e958b`. Correct eight-cobblestone ring recipe; 61 loose cobblestone plus five in a bundle and five in a distinct shulker were verified. The answer confirmed one furnace but did not answer the requested maximum: eight from these 71 verified cobblestone. The player accepted one; acceptance remains qualified.
- Stalled furnace: `ba8ff445-c28b-44ad-b778-3d76edaf6940`. Complete targeted evidence showed 64 sand in slot 0 and empty slots 1 and 2. The answer distinguished lit appearance from processing and suggested fuel. The unavailable opened-source read was not used to infer emptiness. Player satisfied; physical repair untested.
- Redstone: `f2cfeff1-da8d-4370-a07c-e98980efe2c8`. Located four dust in the sixth hotbar slot's bundle, alongside an iron ingot and iron casing. Separate complete reads established no redstone in the second bundle or shulker. Returned slot references were correct. This passes the requested find, not exhaustive storage coverage.
- Backpack: `0bcb848a-9ca7-4c56-ab1b-1d9ff9dc3f5a`, `5225d7f3-dcba-4efe-8fb4-df72a193e3ff`. Both answers gave three leather and five string; returned slots show four of each plus a chest. Shortened citations did not match returned evidence handles. Direct inventory was refreshed but relevant carried containers were not inspected. The player accepted the explanation; evidence acceptance fails.
- Faster glass: `86cc4b80-acb4-4e00-88ab-791a5281909e`, `ef9c7319-ed45-49b3-a91d-815def534578`. Suggested parallel regular furnaces, distinguished lit appearance from fuel sufficiency, and scoped absent logs/planks to inspected slots. No citations or documentation/config reads supported the comparison. The sticks-as-fuel follow-up completed after interruption and was read from history. Final player judgment and physical throughput verification remain incomplete.

The full Java 21 rerun passed 492 tests with four skips. The prompt rewrite did not resolve the hopper failure and did not preserve correct backpack ingredient accounting. These remain open behavioral failures.
