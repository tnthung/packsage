# Chat completion notice

Date: 2026-10-17

When a model response successfully finishes while the player is outside PackSage chat, play one short ding and show one native Minecraft toast in the top-right corner. The toast identifies PackSage, says the answer is ready, and names the configured chat key. Use existing Minecraft sound and toast assets.

Signal completion from the request's final validated, token-counted outcome, once per request. Do not infer it from polling the latest conversation, and do not notify for failed or cancelled requests, historical loads, repeated view refreshes, or intermediate steering/tool rounds. A successful response with recovered tool errors may still notify.

Deliver the notification on the client thread. Suppress it when PackSage chat is currently open, when no player/world is active, or when the request's world/session/scope has become stale. Closing chat while a response runs must not cancel the request or lose its notice. Opening chat later must not repeat a notice already handled. Keep the response available through the existing conversation and persistence paths.

With [parallel conversations](../plans/2026-10-20-parallel-chat-sessions.md), switching sessions or starting New chat does not make another conversation's request stale. Track completion eligibility per request and conversation. A successful background answer also sets its own persisted unread marker; opening the completed conversation clears that marker after display. A toast or an open chat showing another conversation does not acknowledge the answer. The existing suppression of sound/toast while any PackSage chat is open remains in effect.

Automated checks cover the authoritative completion event, failure/cancellation suppression, exactly-once delivery and stale client callbacks. Minecraft-only checks cover the actual ding, native toast position and chat-open suppression. No additional settings or notification framework is required.
