# PackSage v2 world reading live validation

Date: 2026-10-22.

## Environment

- Repository: `D:\Project\packsage`, branch `feat/packsage-v2`.
- CurseForge profile: `C:\Users\hung1\curseforge\minecraft\Instances\Packsage`.
- World: `PackSage Adapter Filter Live Test`; the save directory exists.
- Build: `scripts/build.ps1` completed successfully with Java 21.0.12.1. Gradle reported `BUILD SUCCESSFUL`; all 11 actionable tasks were up to date.
- Mod: PackSage 0.1.0; Minecraft 1.21.1; NeoForge 21.1.202.
- Installed artifact: `mods/packsage.jar`.
- SHA-256 (build output and installed JAR): `2FE71303B378F7F8CC8DBBDCC5DE7E9343DB968EA0BF156E3674B4BEC4F4EAD1`.
- Prompt state: `default`. The installed profile's `config/packsage.properties` has no `developerPrompt` setting.
- The Minecraft instance was closed for build and installation. No game process was running at the live-check preflight.

## Live checks

No check reached Minecraft. The available computer-use surface returned an empty native-app list (`apps: []`) and reports that native computer APIs are disabled. It exposed browser tabs only. No Minecraft window could be selected, launched, or controlled through the requested computer-use path. I did not connect to a different remote device or use shell commands to control the game.

The required new PackSage chat was therefore not created for any check. No per-check conversation JSONL was produced or read, and no gameplay tool calls or replies exist for this run. Each check remains unchecked and unaccepted.

| Check | Question | Status and evidence | Time |
|---|---|---|---|
| Storage | `where is my stone?` | **Not run (blocked).** No room setup, `find_items` call, returned coverage, item paths, coordinates, links, click results, or reply. | N/A; no request sent. |
| Farm facts | `why isn't my iron farm working?` | **Not run (blocked).** No villagers or farm setup, `inspect_entity`/`inspect_area` calls, returned facts, links, or reply. | N/A; no request sent. |
| Armor | `how can I make my armor stronger?` | **Not run (blocked).** No armor setup, inventory/item inspection, enchantment results, exclusions, or reply. | N/A; no request sent. |
| Nested mob storage | `what's in the bundle in my mule's chest?` | **Not run (blocked).** No mule/container setup, inventory traversal calls, returned path or contents, links, or reply. | N/A; no request sent. |
| Performance | `find_items` question over about 300 chests | **Not run (blocked).** Keep-running was not enabled; no chests/search were created or run. Frame/tick behavior and response time were not observed. | N/A; no request sent. |

## Other observations

- Block highlight links: not exercised; no links were returned or opened.
- Mob highlight/glow: not exercised.
- Other-dimension highlight notice: not exercised.
- UNAVAILABLE or timeout results: none observed; no world-reading tool call ran.
- Stutter, crash, or game stability: not assessed because Minecraft was not opened.

No live acceptance is established by this run. Rerun these checks when a native Minecraft window is available through computer use.
