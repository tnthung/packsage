# Prompt rewrite live retests

Retest [the coherent instruction rewrite](2026-10-15-coherent-assistant-instructions.md), including previously passing and claimed-fixed scenarios in [the workflow report](2026-10-15-natural-player-workflow-fixes.md). Follow [the natural-player protocol](../testing/2026-10-15-natural-player-conversations.md): independent player agents, fresh chats, ordinary questions and adaptive follow-ups, with tool evidence audited separately.

The trial world was saved and Minecraft closed before installation. Build, distribution and ATM10 installed JAR match SHA256 `1FF6A36FEA2AB9EAAAB9F446F14107F01EF1935D6181431614353D9843E9C0D9`, containing source commit `4a66a2f`. The full Java 21 `test build` rerun succeeded; live acceptance is recorded separately below.

| Scenario | Regression or acceptance target | Status |
| --- | --- | --- |
| Hopper automation | Full recipe, carried iron, feasible cheap feeding layout | Pending |
| Faster glass | Practical bounded comparison, throughput versus smelt speed | Pending |
| Backpack | Complete recipe and carried-container shortage checks | Pending |
| Stalled furnace | Known-empty fuel/output versus unread state, no inference from flames | Pending |
| Carried redstone | Correct returned slot references and relevant containers | Pending |
| Fall protection | Actual equipment versus documented effects, practical acquisition | Pending |
| Progression | Clarification, fresh quest state, useful next project | Pending |
| Another furnace | Complete eight-input recipe, correct affordable count | Pending |

No new result is a pass until both answer usefulness and supporting evidence have been assessed. Physical crafting, machine operation and damage tests must be identified separately from assistant conversations.
