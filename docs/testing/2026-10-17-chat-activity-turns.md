# Thinking and tool turns: Minecraft checks

Date: 2026-10-17

Spec: [thinking and tool turns](../specs/2026-10-17-chat-activity-turns.md). These checks establish live rendering and input behavior after loading the updated JAR.

1. Ask a question that needs installed evidence, such as how to craft an item. Thinking and tool headers should appear above the answer. Running entries open automatically; each collapses when its work finishes.
2. Click a running header to close it. Further streaming updates should keep it closed. Click a finished header to reopen it; it should stay open on later updates. When the provider supplies no thinking summary, expanded details should say so.
3. Stop a request during thinking or tool work. Unfinished entries should collapse and show cancellation. Reopen them and check that the partial summary or a readable status remains available.
4. Close and reopen chat, then select the saved conversation. Finished entries should start collapsed and remain clickable. Existing older conversations should still open normally.
5. At a narrow window or larger GUI scale, check long headers and summaries wrap within the chat. Scroll up during a response and check it does not force you back down. Headers clipped behind the composer or outside the message viewport must not react to clicks there.

Automated tests and builds are run by the assistant. These player checks require only Minecraft interaction.
