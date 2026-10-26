# PackSage v2: player feedback features

Date: 2026-10-26. These requests come from the player's own play testing of the native adapter. They extend the [v2 world model](2026-10-22-v2-world-model.md). Everything below that the specification does not change stays as it is.

## Running commands

- A `run_command` tool runs a command as the player, with the player's own permission level. It never elevates, so a command the player cannot run fails the way it would in chat.
- **Confirmation** is a setting, `commandConfirmation`, with three values:
  - `always` is the default. The chat shows the exact command with Run and Cancel buttons. Nothing runs until the player clicks Run.
  - `risky` lets read-only commands run directly and asks before the others. Read-only means the command cannot change blocks, entities, inventories, players, world settings, time, weather or data. The read-only set is a fixed allowlist of command roots and subcommands: `help`, `list`, `seed`, `locate`, `data get`, `scoreboard players get`, `time query`, `worldborder get`, `difficulty` without arguments, `gamerule` with only a rule name, and `attribute … get`. Any other command asks.
  - `direct` runs every command without asking.
- The tool result gives the command, whether it ran, the success value and the feedback lines the game produced, bounded. A cancelled or declined command reports that the player did not run it.
- Commands run on the integrated server's thread. On a multiplayer server, the client sends the command the way the chat box does, and only the server's own feedback comes back.
- The assistant offers or runs a command only when the player asks for an action. It still never performs gameplay actions with other tools. A command's result is evidence the assistant can cite.

## Reply and tool timing in the chat

- Each completed answer shows its total time, from submission to the end of the answer.
- Each tool activity row can expand to show:
  - the call's tool, its key arguments and its result summary: coverage, the record count and the first part of the detail;
  - its duration.
- Timing is saved with the turn, so earlier chats show it too.

## Chat opening speed

- Opening the chat must not wait to load every saved conversation in full. The sidebar lists conversations from their summaries. The selected conversation loads its turns, and the newest turns render first.
- Target: the chat screen appears within one frame. History and the sidebar fill in after it opens, without blocking the client thread on disk I/O.

## Answer verbosity

- A `verbosity` setting with the values `concise`, `normal` (the default, today's behavior) and `detailed`.
- The value adds one short instruction to the request, not part of the fixed system prompt. That instruction says how much explanation, how many options and how much intermediate detail to include. It never changes the evidence, citation, coverage or link rules.

## Chats from other sessions

- The sidebar's "show chats from other sessions" becomes a toggle. It is off by default, so the sidebar shows only the current world's chats. The toggle's state is saved.

## Location marker

- A place marked from a link draws as a beacon-style beam, a translucent vertical beam that is brighter at the center, rising from the marked block. It replaces the tall 1x1 pylon.
- It keeps the existing lifetime, the 128-block distance clamp and the depth behavior that keeps it visible.

## F3 game state

- A `read_game_state` tool returns what the F3 debug screen shows, read on the client thread with bounded work:
  - **Performance:** FPS, frame time, server TPS and mspt for the integrated server, render and simulation distance, loaded and rendered chunk counts, and memory use.
  - **Entities and chunks:** the rendered and total entity counts the debug screen reports, and the player's chunk, chunk section and region.
  - **Light and environment:** the light levels at the player (block, sky and combined), the biome, local difficulty, game time, day and the moon phase.
  - **Position:** coordinates, facing as a direction and yaw/pitch, and the dimension.
  - **Looking at:** the targeted block or fluid with its id, full block state, tags and position, and the targeted entity, as on F3's right side.
- Values the client cannot read are reported as unavailable, never guessed. On a multiplayer server, the server performance fields are unavailable.
