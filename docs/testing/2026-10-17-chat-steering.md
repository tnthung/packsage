# Chat steering: Minecraft checks

Date: 2026-10-17

Spec: [mid-response steering](../specs/2026-10-17-chat-steering.md). Use the existing world in the selected minimal test profile after installing the updated JAR.

1. Ask a question that takes multiple evidence lookups. While the answer is thinking, searching or writing, type a correction such as `Use only what is in my inventory` and press Enter or Send. The draft clears only when accepted, a player bubble appears at that point in the response, and the next model continuation receives the correction.
2. Send two different follow-ups during the same response. Both bubbles remain in order, the response keeps working, and earlier streamed text stays visible. A text-only response with pending steering also continues before finishing the exchange.
3. While active, check Send and Stop are separate controls. Stop still works with a draft present. A submission during initial context preparation or just after the backend finishes should retain its draft when steering cannot be accepted.
4. Reopen the conversation after completion, failure or Stop. Accepted steering bubbles and surrounding answer text should remain in order. Send a normal follow-up after completion and check it retains the corrections in conversation context.
5. After steering, use Stop, New chat or switch sessions/worlds. Pending work must not restart in the new conversation. At narrow widths or larger GUI scales, check the two controls, wrapped bubbles and scrolling remain usable.

The assistant runs automated checks and builds. These player checks require only Minecraft interaction.
