# Natural player conversation tests

Use a fresh player-role subagent for each individual scenario. The agent receives a private gameplay intent and only the assistant's actual answers, not implementation details or tool traces. It chooses informal initial wording and adaptive follow-ups, up to three player turns. The UI operator sends those messages unchanged and audits saved evidence separately. Never prescribe tools, sources, query syntax or search strategy in player messages.

Run fresh chats on the installed build in `OoO - PackSage Trial`, retaining GPT-6-Luna medium, screenshots and explicit-only search. Serialize UI interactions; player agents can prepare independently. Do not fabricate possession, completed crafting, machine state or movement. Commands may establish needed fixtures in the trial copy, with changes recorded.

| Scenario | Private player need | What to observe |
| --- | --- | --- |
| Machine speed | Cheap faster glass smelting | Clarifies machine/input, compares practical options and avoids unsupported upgrades |
| Basic crafting | Cheap backpack because inventory fills | Identifies usable recipes/materials and relevant existing storage without assuming resources |
| Stalled machine | Intermittent power/fuel problem | Distinguishes symptoms from a proven cause and asks for missing state |
| Finding materials | Locate carried resources for a build | Resolves vague names, checks relevant containers, respects unsupported storage |
| Fall protection | Stop dying from falls | Clarifies circumstances and distinguishes documented effects from current equipment |
| Progression | Unsure what to do next | Clarifies desired direction and uses current progression without inventing completed quests |

Record exact player/assistant turns, independent agent identity, request IDs, supporting tool results, clarifying behavior, unsupported claims and practical outcome in `docs/validation/2026-10-15-natural-player-conversations.md`. A plausible answer alone is not a pass; report source correctness and conversational usefulness separately. This batch expands testing, not implementation scope.
