# Chat controls and context visibility

Date: 2026-10-13

Extend the [modern chat UI](2026-10-13-modern-chat-ui.md) using the existing custom dark glass surfaces and logical Minecraft GUI coordinates. Preserve unrelated work and avoid formatting tools or broad redesigns.

- Center the New chat plus icon in its 28-pixel button.
- Persist a send shortcut choice: Enter (default, Shift+Enter newline) or Ctrl+Enter (plain Enter newline).
- Add Copy to both sides of each exchange. Latest-user Edit loads its question into the composer; Resend replaces that latest exchange, including its response. Earlier exchanges remain unchanged. Editing/resending is unavailable during active requests or in another world's read-only session. Preserve the old exchange until resend is accepted, and persist replacement safely.
- Persist the number of sessions shown before each world's Show more control; default two, positive integer up to 1,000.
- Show estimated token counts for user and assistant text and a context bar based on the actual historical replay estimator and selector. Its historical allowance is the smaller of the configured history-token ceiling and a known model context limit. An unknown provider limit must be identified as unknown. Hover explains the configured allowance, model limit, selected usage, included messages/turns and exclusions. Counts describe local replay estimates rather than API billing. Preserve whole completed turns and history-turn limits; failed/cancelled exchanges are excluded.
- Add green/red inclusion dots at the bottom-right of each message avatar. Green means selected for the next request's history; red means excluded. Both sides of a turn share the selection result.
- Add model and reasoning dropdowns above the composer, before Search/Screenshot, backed by the connected provider catalog and existing settings. Active requests retain their captured model/effort.
- Put extra gameplay/screenshot context near the composer after Screenshot, with details on hover, instead of the top of the conversation.
- All items above the input share a compact equal width and wrap into additional rows when the available width is too small. Their rows move upward with the growing composer and reduce the message viewport accordingly.
- Treat HTTP 503 as a temporary provider failure. Inspect the existing retry policy and add a bounded retry where appropriate without replaying completed tool effects or duplicating persisted exchanges.

The assistant owns all automated tests/builds/installation. Player Minecraft checks establish actual rendering, clicks, keyboard behavior and hover appearance.
