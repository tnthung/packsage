# Thinking and tool turns in chat

Date: 2026-10-17

Extend the [modern chat](2026-10-13-modern-chat-ui.md) with visible thinking and tool steps above each answer. Use the existing custom surfaces, typography, wrapping and scroll viewport.

- Keep steps in occurrence order, with a stable identity, readable header, details and running/completed/failed/cancelled state. Show local tool calls and hosted web search, alongside provider thinking summaries.
- Put all activity for one response inside one clickable count header, such as `4 steps`. Collapsing this group hides every thinking/tool row while leaving the answer visible. Do not leave all individual step headers visible when the group is collapsed.
- Expand the group while the response is running and collapse it once when the response completes, fails or is cancelled. Clicking its header toggles the whole group. Streaming refreshes and step-count changes preserve a manual toggle; later terminal refreshes preserve manual reopening. Completing an individual step must not collapse a still-running response's group. Keep the existing per-step detail toggles inside the expanded group.
- Display only provider reasoning summary text, never encrypted reasoning or raw reasoning events. Request summaries using `reasoning.summary = auto`; retain the selected reasoning effort. When a provider sends no summary, show a readable thinking status.
- Tool details use the existing player-facing labels and allowlisted argument summaries, with readable outcome text. Expanded failed calls show the recorded error detail and error code when available, including execution exceptions. Keep the message bounded and readable, with a generic fallback for missing or malformed errors. Do not dump raw tool result JSON, credentials or provider replay data into chat.
- Persist activity with its exchange, including failures and cancellation. Old conversation files without activity remain readable. Activity presentation does not alter model replay/history selection, tool execution, world guards or request cancellation.
- Keep the existing answer, source/navigation controls, composer and activity status behavior. Use matching header draw/click geometry, reject clicks outside the message viewport, and preserve scrolling and narrow-window layout.

The summary request and delta fields follow the official [reasoning summaries](https://developers.openai.com/api/docs/guides/reasoning#reasoning-summaries) and [summary streaming event](https://developers.openai.com/api/reference/resources/responses/streaming-events#response.reasoning_summary_text.delta) documentation. The ChatGPT account route still requires live Minecraft confirmation.

Automated checks cover summary event handling, step lifecycle and tool outcomes, stored and legacy conversations, auto expansion/collapse and manual toggles, and clipped/narrow layouts. Actual Minecraft rendering remains a player check.
