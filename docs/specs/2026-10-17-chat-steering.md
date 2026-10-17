# Mid-response chat steering

Date: 2026-10-17

Extend the [modern chat](2026-10-13-modern-chat-ui.md) and [activity groups](2026-10-17-chat-activity-turns.md) so the player can send text while a response is running.

- Enter or Send accepts a nonblank steering message once the active backend exchange is installed. Show a separate Stop control while active. Keep the draft when steering cannot be accepted, including context preparation or a just-finished exchange; show a readable status. Preserve multiline editing and the 1,000-character composer limit.
- Apply accepted messages in order at the next model request boundary. Finish already published tool calls and include all their results first. If the current provider response finishes without client tool calls, pending steering causes a follow-up model round before the overall exchange completes. Do not interrupt an HTTP stream or restart unfinished tools.
- Keep the original question, captured context, model, effort, search and screenshot settings unchanged. Steering is text only and belongs to the current request and world. Stop, session changes and world invalidation must never restart pending work.
- Preserve streamed text and show each accepted steering message as a player bubble at its submission position. Persist text plus its offset in the accumulated answer, with an empty default for old logs. When retrying a response with void partial output, retain completed model-round text, discard the retried segment, and move affected steering offsets to that segment's start without losing or reordering accepted messages. Saved, failed and cancelled exchanges retain accepted text.
- Backend acceptance is atomic with terminalization and next-request dispatch. A rejected message remains unsent. Accepted messages appear exactly once in provider replay; validate exact authored USER text and order, alongside existing tool result checks. Drain pending messages at actual dispatch, including ones accepted after continuation reservation.
- Historical model context preserves the visible question, assistant segments and steering messages in order. Continue omitting raw historical tool results and reasoning. Include steering text once in history token counts and limits.

Automated checks cover provider rounds, acceptance races, controller/runtime guards, saved and legacy exchanges, retry offsets, composer behavior and history. Actual Minecraft rendering and input remain player checks.
