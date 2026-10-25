# Local debug bridge

The bridge is off by default. Before starting Minecraft, add these lines to the instance's `config/packsage.properties`:

```properties
debugBridge=true
debugBridgePort=25599
```

Alternatively, enable it with the JVM argument `-Dpacksage.debugBridge=true`. The port setting remains in the properties file. Restart Minecraft after changing either setting. The bridge binds only to `127.0.0.1`; an occupied port leaves it off and produces a refusal line in the log.

After the first client tick, read `<instance>/packsage/debug-bridge.json`. It contains `port`, `token` and the Minecraft process `pid`. The token changes each launch, is never logged and is removed on normal shutdown. Keep it private: the bridge can run commands with permission 4 and act as your player. While it runs, pause on lost focus is disabled during gameplay and on the PackSage screen. Other screens use the original preference, which is restored before entering menus so their options saves preserve it. Normal shutdown restores the preference and saves Minecraft's options. Opening a pausing screen can still pause singleplayer; enablement does not change normal players' first-tick assistant startup.

These examples use Git Bash and `curl`. Copy the port and token from the token file, join a disposable singleplayer world, and replace fixture coordinates and ids as needed:

```bash
PORT=25599
TOKEN='copy the token from debug-bridge.json'
BASE="http://127.0.0.1:$PORT"

# State, fixture commands and looking (either rotation or world coordinates).
curl -H "Authorization: Bearer $TOKEN" "$BASE/state"
curl -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"command":"time set day"}' "$BASE/command"
curl -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"yaw":90,"pitch":15}' "$BASE/player/look"
curl -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"x":10,"y":65,"z":10}' "$BASE/player/look"

# Actual player breaks and container interactions. /open uses the held item,
# so use an empty hand when you only want to open a chest.
curl -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"x":10,"y":64,"z":10}' "$BASE/player/break"
curl -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"x":11,"y":64,"z":10}' "$BASE/player/open"
curl -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"entity":"replace-with-entity-uuid"}' "$BASE/player/open"
curl -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"slot":0,"shift":true}' "$BASE/player/menu/move"
curl -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"slot":1}' "$BASE/player/menu/move"
curl -H "Authorization: Bearer $TOKEN" -X POST "$BASE/player/menu/close"

# Chat uses your current PackSage account, selected model and settings.
# Optional screenshot/webSearch booleans override the current PackSage screen's
# toggles for this request only. Before the first screen, defaults are true/false;
# webSearch=false still allows automatic search when enabled in settings.
# Submission returns conversationId and requestId without awaiting the reply.
curl -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"question":"What is in the chest I am targeting?"}' "$BASE/chat"
REQUEST_ID='copy requestId from the submission'
CONVERSATION_ID='copy conversationId from the submission'
curl -H "Authorization: Bearer $TOKEN" "$BASE/chat/$REQUEST_ID"
curl -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "{\"question\":\"What changed?\",\"conversation\":\"$CONVERSATION_ID\"}" "$BASE/chat"
curl -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"question":"Find a source online","screenshot":false,"webSearch":true}' "$BASE/chat"

# Developer prompt: read, replace from a JSON file containing {"text":"..."}, clear.
curl -H "Authorization: Bearer $TOKEN" "$BASE/prompt"
curl -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' --data-binary @prompt.json "$BASE/prompt"
curl -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"text":null}' "$BASE/prompt"

# Use an exact targetId returned by GET /chat/{requestId}; never invent ids.
TARGET_ID='copy a navigationTargets targetId from the reply'
curl -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "{\"requestId\":\"$REQUEST_ID\",\"targetId\":\"$TARGET_ID\"}" "$BASE/navigate"
curl -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"open":true}' "$BASE/chat-screen"
curl -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"hideGui":false}' "$BASE/screenshot"
curl -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"open":false}' "$BASE/chat-screen"
```

`GET /chat/{requestId}` returns `active`, `completed`, `failed` or `cancelled`, reply text, activities, tool arguments/results and their coverage/detail/record counts, navigation targets, citations and timing log fields. Finished turns are read from saved history after pending writes settle. `saved` is false for active requests and failures during preparation that the normal runtime does not save; up to 256 such failures are retained per conversation. Active activities expose arguments and progress; complete tool results become available with the saved turn. Requests remain discoverable across chat selection during the current world session; the bridge does not index every historical request after reconnecting. Timing capture keeps the newest 256 requests, at most 2,048 timing lines per request, and starts with the bridge.

Navigation targets belong to the requested reply, including when several turns share an evidence scope or target id. Inspection retains that turn's historical targets; `/navigate` also requires the target to be present in the live view and uses the normal click-time availability checks. For a two-turn check, ask for two different linked destinations in the same conversation, inspect both request ids, and navigate each reply's target. The second response must not list the first reply's unrelated targets or make its own target ambiguous.

`/screenshot` returns the saved PNG's absolute path. `hideGui` temporarily changes the HUD setting for the captured frame; omitting it preserves the current setting. Menu slots are the real menu's zero-based slot indices, including player inventory slots; shift defaults to false and the click button is left. Spectators and invalid menus cannot move items. Entity opens try the entity's position-specific interaction at its center before the general interaction. Commands, breaks and menu operations require singleplayer; interaction restrictions and event cancellation still apply.

For the pause-preference check, start with pause on lost focus enabled, enable the bridge, then open Minecraft's options, change another option and leave the menu. Confirm `options.txt` still has the original `pauseOnLostFocus` value. After normal shutdown, confirm it again, and restart with the bridge disabled to check normal focus-loss behavior. These game checks remain pending; automated tests do not establish options-screen or shutdown behavior in a running client.

Missing or wrong tokens return 401, unknown routes 404, wrong methods 405, invalid arguments/JSON 400, unavailable game state 409, oversized bodies 413 and game timeouts 504. Bodies are limited to 1 MiB, headers to 64 and 16 KiB total, and reading a request has a 10-second deadline. Client/server handoffs and asynchronous game operations have 10-second waits. Screenshots and actual navigation, journal effects and background play still need live acceptance; hovering and other mouse-driven UI behavior are outside this bridge.
