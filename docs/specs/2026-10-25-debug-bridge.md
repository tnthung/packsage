# PackSage debug bridge

Date: 2026-10-25.

Live acceptance until now has needed an agent to control the player's screen through computer use. That blocks the player from using the PC, and it fails whenever the launcher window cannot be activated. Almost every check only needs game state, saved conversations and tool results, not vision. The debug bridge lets a test agent drive a running game over a local HTTP port while the player keeps working.

## Safety

- **Off by default.** It starts only when `config/packsage.properties` has `debugBridge=true`, or the JVM has `-Dpacksage.debugBridge=true`. Normal players never get it.
- **Local only.** It binds to `127.0.0.1` on a configurable port, `debugBridgePort`, default 25599. If the port is taken, the bridge logs one line and stays off.
- **Token.** Each launch generates a random token. It is written with the port to `<instance>/packsage/debug-bridge.json` and removed on exit. Every request needs `Authorization: Bearer <token>`; a missing or wrong token gets 401.
- **Powerful on purpose.** The bridge runs commands with full permission and acts as the player. That is what fixture setup needs, which is why it is off by default and local only.
- **No new dependencies.** It is a minimal HTTP/1.1 server on a plain `ServerSocket`. The game's bundled runtime may not include the `jdk.httpserver` module.
- **Threads.** Request handling runs on the bridge's own thread. Game access is handed to the client or server thread and awaited with a timeout. A slow or failed request never blocks either thread.
- **Background play.** While the bridge is enabled, "pause on lost focus" is turned off during gameplay and on the PackSage screen, so the game keeps running in the background. Other screens use the original preference so options-screen saves preserve it; normal shutdown restores and saves the original preference.

## Endpoints

All bodies are JSON. Errors return `{"error": "..."}` with a 4xx or 5xx status.

| Method and path | Purpose |
|---|---|
| `GET /state` | World and save name, dimension, player position, rotation, health, gamemode, game time, day time, paused or running, the open screen, the crosshair target, the ridden entity, the selected hotbar slot |
| `POST /command` `{command}` | Runs a server command with permission 4, as the player. Returns success and the command's feedback lines. |
| `POST /player/look` `{x, y, z}` or `{yaw, pitch}` | Points the player, so `targeted` holder words work |
| `POST /player/break` `{x, y, z}` | Breaks a block as the player through the server game mode, so the journal records a real player break |
| `POST /player/open` `{x, y, z}` or `{entity}` | Opens a block or entity menu as the player |
| `POST /player/menu/move` `{slot, shift?}` | Clicks or shift-clicks a slot in the open menu as the player, so container transfers are recorded |
| `POST /player/menu/close` | Closes the open menu |
| `POST /chat` `{question, conversation?, screenshot?, webSearch?}` | Sends a message through the normal chat path. `conversation` is `"new"` (default) or an existing id. Optional booleans override the screen's current toggles for this request; initial defaults are screenshot true and explicit web search false. Returns the conversation and request ids. |
| `GET /chat/{requestId}` | Status (`active`, `completed`, `failed`, `cancelled`), final reply text, each tool call with its arguments, result coverage, detail and record count, navigation targets, citations, and the timing log values for the request |
| `GET /prompt`, `POST /prompt` `{text}` | Reads or replaces the developer prompt override, as the in-game editor does. `{text: null}` clears it. |
| `POST /navigate` `{requestId, targetId}` | Opens one of that reply's navigation targets, as a player click would, and returns the navigation result |
| `POST /screenshot` `{hideGui?}` | Saves a screenshot and returns its file path, so an agent can look at visual results without controlling the screen |
| `POST /chat-screen` `{open}` | Opens or closes the PackSage screen, for screenshots of rendered replies and link chips |

## Limits

Mouse-driven UI behaviour, such as hovering a tooltip or middle-clicking the gear, is not covered. Screenshots after `/navigate` or `/chat-screen` cover the visual checks that remain.
