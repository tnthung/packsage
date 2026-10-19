# Complete failed-outcome display

Date: 2026-10-19.

Requirements: [chat presentation](../specs/2026-10-13-modern-chat-ui.md). Plan: [failed-outcome follow-up](../plans/2026-10-13-live-ui-corrections.md#failed-outcome-follow-up-2026-10-19). Implementation: `8c5c943`.

## Cause and correction

The saved turn retained the complete provider error. `AssistantScreen.drawConversation` rendered that outcome with one `drawString` call, so the conversation scissor clipped its right edge. The renderer now uses the existing plain-text `font.split` error pattern at the actual message-column width and advances the conversation height for every line. Error text keeps its font, color and literal interpretation; explicit line breaks are handled by Minecraft's splitter.

## Automated and artifact checks

- Java 21 `gradlew.bat test build` passed: 640 tests across 54 suites, zero failures/errors and four existing skips. The existing `AssistantScreenStateTest` suite passed all 47 checks.
- A proposed headless render regression was removed before commit: the actual conversation path invokes static OpenGL rendering, while an isolated layout helper would not reproduce this defect. No new test-only production seam or graphics mocking infrastructure was added. The existing automated checks establish compatibility; installed rendering below establishes the requested visual behavior.
- No formatting tools were used. `git diff --check` passed.
- Minecraft saved and closed normally before `scripts/build.ps1` installed into the minimal CurseForge `Packsage` profile. Build, distribution and installed JAR hashes matched SHA-256 `ED45618BA94710F16CA55B35A9825F19B9CB97FCC3D39046F8B4C73D771963C0`.

## Installed acceptance

Relaunched Minecraft 1.21.1 / NeoForge 21.1.259 with all 34 mods, entered the original New World and opened the existing investigation conversation at GUI scale 2 in the 1024 by 768 client window. Scrolling up from its later answer revealed failed request `91ce5ceb-c90c-4514-bb91-0cd5d8fcbe4a`.

The complete outcome was visible on two lines: `Outcome: FAILED (Response error: server_is_overloaded: Our servers are currently overloaded. Please try again later.)`. The ending and closing parenthesis were readable inside the message column. The following user bubble started below both lines without overlap, and scrolling reached the failed turn normally. No new model request or persisted-error modification was needed. The game remains paused in PackSage with that error visible and Sol Medium selected.

The observed clipping defect is accepted as fixed at this installed size/scale. Other GUI scales and arbitrary provider messages were not separately exercised.
