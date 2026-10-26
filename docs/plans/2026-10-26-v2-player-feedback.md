# PackSage v2: player feedback implementation plan

Implements the [player feedback specification](../specs/2026-10-26-v2-player-feedback.md). The global constraints of the [native readiness plan](2026-10-26-v2-native-readiness.md#global-constraints) apply. Live checks use the debug bridge only, with no computer use; the player relaunches the game when an install needs it.

## Tracks

Each track runs in its own worktree from `feat/packsage-v2` and is merged with `--no-ff` after review. Tracks do not edit `CHANGELOG.md`, the roadmaps or `GroundingInstructions`. The orchestrator integrates prompt changes as one candidate after the tracks merge.

| Track | Scope | Main files |
|---|---|---|
| C | `run_command` tool, the `commandConfirmation` setting with its settings-screen control, and Run/Cancel confirmation in the chat | `model/PackSageTools`, a new `world/CommandRunner`, `chat/PackSageSettings`, `client/AssistantSettingsScreen`, a new confirmation component used by `AssistantScreen` |
| U | Reply and tool timing in the chat, the sidebar toggle for chats from other sessions, and chat opening speed | `client/AssistantScreen`, `chat/ConversationStore`, `chat/AssistantRuntime`, `chat/ChatContracts`, `chat/PackSageSettings` (the toggle) |
| B | Beacon-style location marker | `client/highlight/*` |
| G | `read_game_state` tool | `model/PackSageTools`, a new `client/GameStateReader` |
| V | `verbosity` setting and its request instruction | `chat/PackSageSettings`, `client/AssistantSettingsScreen`, `chat/RequestController` |

Tracks C and U both touch `AssistantScreen`, and C, U and V touch the settings. Merge them in the order U, C, V, resolving conflicts at each merge.

## Review and acceptance

1. Claude reviews each track before its merge, and Astra reviews the combined change once.
2. The prompt candidate adds the command tool's policy (offer or run commands only when the player asks for an action, cite results), routes the game state questions to `read_game_state`, and keeps verbosity outside the fixed prompt.
3. Sol runs one installed round through the bridge for every feature, including confirmation in all three settings, timing in a fresh and a reopened chat, chat opening time, the marker, game state answers and each verbosity level.
