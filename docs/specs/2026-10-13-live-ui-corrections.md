# Live UI corrections

Date: 2026-10-13

This extends the [modern UI specification](2026-10-13-modern-chat-ui.md) with the player's first live feedback. Keep the current Back placement, working multiline editing and session filtering. Legacy sessions without persisted navigation can remain non-clickable before release.

- Restore the settings body, retain conditional account actions and direct model/effort choices, and provide one draggable page scrollbar.
- Center labels vertically in controls. Add hover to gear and pause/resume. Use a globe for web search and a magnifier before session-search text. Clicking outside an editor, including toggles, removes its focus.
- Use glassy transparent dark surfaces, with any background blur applied before UI content. Respect Minecraft's logical GUI coordinates and GUI-scale changes.
- Use a real weight for emphasis instead of fractured synthetic bold. Increase message line height by three logical pixels and fit inline chips inside each row. Wrap messages at word boundaries, with character fallback for long tokens. Render Markdown quotes deliberately while retaining lists and exact references.
- Align the initial composer and send control, clear submitted text, and keep immediate Send-to-Stop behavior. Show request activity beside the pending assistant response, rather than at the conversation header.
- Make pause/resume reliable from the first click while retaining private-singleplayer restrictions. Preserve exact source/scope checks for newly generated inline navigation. Config citations open a read-only panel with the source metadata and saved/loaded values recorded with that answer; do not invent gameplay navigation, read current files or change config values.

Automated checks establish source and interaction behavior. Updated rendering, font weight, transparency, GUI scales and actual pause/navigation still require Minecraft observation.
