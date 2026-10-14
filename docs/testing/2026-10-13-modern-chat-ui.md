# Modern chat UI player checks

Date: 2026-10-13

Reference: [chat/settings requirements](../specs/2026-10-13-modern-chat-ui.md).

The assistant runs all automated checks and installs the build. These checks are for the actual Minecraft screen after restarting the game with that build.

1. Open PackSage in a private singleplayer world. Check the dark neutral styling, readable font, grouped session sidebar, player skin head and `Ps` avatar. Chat text, sidebar labels, floating controls and Settings should be as sharp as the input fields, without a blur pass over them. Widen the window: the conversation should retain its maximum readable width and leave blank padding.
2. Enter several lines using Shift+Enter, then paste enough text to wrap. The composer should grow upward to its limit, then scroll internally. Search and Screenshot should stay immediately above it. Click between wrapped lines, select/copy/paste and move the caret with arrow keys. Resize and open/return from Settings: the draft should remain.
3. Send with Enter or the send icon. It should become Stop immediately, including while capturing the screenshot, waiting for the model and reading tool results. Click it to cancel; completion, cancellation or failure should restore Send. Check streaming text, activity and transcript scrolling. Scroll up during streaming: incoming text should leave your reading position alone until you return to the bottom.
4. Search the sidebar, expand a world group and select a previous session in the current world. Continue it with a follow-up: history should remain and context should be captured fresh. Select a session in another world/server: its history should open read-only. New chat should return to the active world.
5. Ask `Find the installed Nature's Aura entry Aura, conceptually. Quote its fixture marker and open that entry`. The guide control should appear at the reference inside the answer. Click it; check that it opens the installed entry. Reopen an older session and click an old reference: either it opens under a valid current scope or reports that the source is stale without losing the answer.
6. Open Settings with the gear. Check the actual provider icon/status and the appropriate Sign in, Cancel sign-in or Sign out action. Choose models and reasoning efforts directly using their radio controls. Back and Escape should save valid fields and return to the same draft/chat.
7. Toggle pause in private singleplayer and verify the world pauses/resumes. Join multiplayer or publish the world to LAN: the pause control should be hidden.
8. Repeat the layout and input checks with Minecraft GUI scales that produce both a small and a large logical screen. No controls should overlap, escape the screen, or capture clicks through clipped content.

Report the screen, GUI scale and visible behavior for any failure. Build/unit checks do not establish visual acceptance.
