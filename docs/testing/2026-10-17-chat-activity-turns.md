# Thinking and tool turns: Minecraft checks

Date: 2026-10-17

Spec: [thinking and tool turns](../specs/2026-10-17-chat-activity-turns.md). These checks establish live rendering and input behavior after loading the updated JAR.

1. In the existing ATM10 trial world, ask a question that needs installed evidence, such as how to craft an item. One count header, such as `4 steps`, should contain all thinking/tool steps above the answer. This group opens while the response is running and collapses when the response finishes, leaving only its count header above the answer.
2. Click the running group header to close the whole list. Streaming updates and additional steps should keep it closed. Click a finished group to reopen every step; it should stay open on later updates. Its inner step headers still toggle their details. When the provider supplies no thinking summary, expanded details should say so.
3. Stop a request during thinking or tool work. The group should collapse. Reopen it and check cancelled steps retain the partial summary or a readable status.
4. Close and reopen chat, then select the saved conversation. Finished groups should start collapsed and remain clickable. Existing older conversations should still open normally.
5. At a narrow window or larger GUI scale, check long headers and summaries wrap within the chat. Scroll up during a response and check it does not force you back down. Headers clipped behind the composer or outside the message viewport must not react to clicks there.

Automated tests and builds are run by the assistant. These player checks require only Minecraft interaction.
