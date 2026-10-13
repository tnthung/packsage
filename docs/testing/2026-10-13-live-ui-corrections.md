# Live UI correction checks

Date: 2026-10-13

Reference: [accepted corrections](../specs/2026-10-13-live-ui-corrections.md). The assistant owns tests/build/installation; these checks require only Minecraft after restarting with the updated JAR.

1. Open chat and Settings. The world should remain visible through the dark glass backdrop, with sharp text and controls. Bold text should use a clean weight. Check label centering and hover on gear/pause, the globe on web search and the magnifier inside session search.
2. Settings should display account, model, effort, behavior and history controls. Drag its single scrollbar up and down, then use the wheel. Back stays in the current upper-left position. Confirm model/effort changes, valid numeric drafts and return to the same chat.
3. Click session search or the composer, then click blank space or Search/Screenshot. The old input should lose keyboard focus. Session filtering, multiline editing and wrap/caret behavior should still work.
4. The empty composer and send control should have matching height and vertical position. Send text: the input should clear, Send should immediately become Stop, and request activity should appear beside the pending model response. Cancelling/completing should restore Send.
5. Check longer responses containing bold words, lists, quotes and inline guide/quest controls. Words should wrap at boundaries except long tokens; message rows should have more space, and chips should fit within their own row. Newly generated guide/quest controls should work. Legacy navigation without saved targets may remain plain text.
6. Ask for `general.fieldCreatorRange` from Nature's Aura's config, then click its inline Config control. The read-only panel should show the recorded source and saved/loaded values/states, with the observation time. Back/Escape should return to the same draft and chat. For an older session without the value snapshot, the panel should state that it is unavailable.
7. In private singleplayer, click pause/resume twice immediately after opening chat. Each click should change the game pause state without first sending a model request. Open config details while paused and check the pause policy is preserved. Multiplayer/LAN should hide pause.
8. Change Minecraft GUI scale and resize the game window. Repeat bounds, wrapping, pointer/focus and scrollbar checks. The same logical layout should resize within the game screen without an independent UI scale.

Report the screen, GUI scale and visible behavior for any remaining failure. Automated layout/source checks do not establish live visual acceptance.
