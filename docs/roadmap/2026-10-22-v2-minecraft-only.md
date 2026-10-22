# PackSage v2: Minecraft-only baseline

Updated: 2026-10-22.

The `feat/packsage-v2` branch removes every non-Minecraft adapter so mod adapters can be rebuilt with a new strategy. The existing [tool roadmap](2026-10-13-todo.md) and [adapter feature checklist](2026-10-21-adapter-feature-todo.md) still describe the previous branch and were left unchanged.

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
- Minecraft adapter: recipes and brewing, recipe planning, registries, effects, attributes, dimensions, inventory and equipment, and native storage reads.

## Open

- The assistant prompt edits that remove JEI, quest, guide and Ponder guidance are untested in game.
