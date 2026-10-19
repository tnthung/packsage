# Clickable Markdown web links

Date: 2026-10-19

The player rejected the first formatting correction because the Minecraft Wiki homepage remained plain text. The earlier change removed Markdown syntax but still required provider citation metadata for a clickable link. A link supplied directly in an answer needs its own web-link action. The contract and implementation checks are recorded in the [chat UI specification](../specs/2026-10-13-modern-chat-ui.md) and [implementation plan](../plans/2026-10-13-modern-chat-ui.md).

## Implementation and automated checks

`ScreenText.InlineSpan` retains a separate optional external URL. Ordinary Markdown links accept only HTTP(S), a nonblank URI host and no embedded credentials. The UI renders an accent `Open ...` control, preserves the destination through wrapping, clipping and table rendering, shows the URL on hover, and opens Minecraft's native link confirmation on click. These links create neither evidence sources nor registered gameplay navigation targets. Existing registered targets retain priority. Annotated Markdown is parsed without adding a duplicate citation action.

The focused regression run executed 81 tests with four expected failures before implementation. They reproduced the saved answer, missing ordinary link actions, table metadata loss and missing Open-control layout. The final Java 21 `gradlew test build` passed in 25 seconds: 603 tests, 599 passed, four existing skips, no failures or errors. Independent review found no actionable remaining issue. No formatting tools were used.

The tested production source is committed in `817e84a`, with the settings clarification in `c33bac2`. Built and installed JAR SHA-256: `BEAAF324BFAB57E721FA8CA04D292ECAFFFF94BEED469729DDCE3C320F916A4C`. Minecraft exited normally after saving before the JAR was copied into the Packsage instance. The installed hash was checked against `build/libs/packsage-0.1.0.jar` before startup and again after the live checks.

## Installed acceptance

Computer Use inspected the actual Minecraft 1.21.1 / NeoForge 21.1.259 client, 34 mods, GUI scale 2, window 1026 by 800. The fixture was the existing Packsage instance's New World. The corrected process exposed window `159256810`.

| Scenario | Observed result |
| --- | --- |
| Player's 08:28 saved answer, session `87bab13e-028c-4f28-9d59-3621b8c105c4-a6d45d3d2e94f90d.jsonl` | Rendered one `Open Minecraft Wiki homepage` control without raw Markdown or a printed URL beside it. Hover showed `Click: open web link` and `https://minecraft.wiki/`. |
| Click that saved homepage control | Minecraft displayed its native link confirmation with the exact homepage URL and Open in Browser, Copy to Clipboard and Cancel choices. Clicking Open in Browser opened Edge; its address bar showed `https://minecraft.wiki` and the actual Minecraft Wiki homepage loaded. |
| Original 07:39 answer, session `5518d717-9127-4c2d-bb59-9c4b898febb2-0c8c266dd70559b8.jsonl` | Displayed the homepage control and one `Open en.wikipedia.org` citation control, with no duplicate citation action or raw Markdown. Citation hover worked. Clicking it displayed the exact recorded Wikipedia URL in Minecraft's confirmation; Cancel returned to the answer. |
| Fresh identical player question | Sent `can you link me to the minecraft wiki homepage?` unchanged in a new chat with GPT-6-Luna Extra high and the existing controls/settings. The completed answer was `Sure: [Minecraft Wiki](https://minecraft.wiki/)`, displayed as `Sure: Open Minecraft Wiki`. Clicking the control showed the correct homepage URL in the native confirmation; Cancel returned to the answer. |

The fresh request was `f1f151b6-83df-4965-9029-443a429767de`, saved in `e9bcc3ce-6358-4a0a-8567-2538eb4e4d77-c7be7bc59ca2ffdf.jsonl`, with outcome COMPLETED. Its recorded navigation-target list was empty: the ordinary URL action worked independently of registered evidence navigation. The saved answer was read back from the actual session file.

The properties hash remained `50EFFFD1284FBD4C99B0606F8120880542E3EE9EA6CBA3BCD80D0B47DF411D0B` across the live checks. No model, effort, automatic-search, screenshot or history setting was changed. New World was left paused with the successful fresh answer open and no active request. The browser homepage tab remains open. Existing unrelated dirty validation documents were preserved. The changes were committed after the live checks at the player's request.

Acceptance: the reported saved and fresh homepage failures passed installed rendering, hover, click, native-confirmation and actual browser-destination checks. Invalid destinations, literal code, table links and layout clipping are covered by automated tests; they are not claimed as separate live UI checks.
