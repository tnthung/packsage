# Parallel chat sessions: Minecraft checks

Date: 2026-10-20

Plan: [parallel chat sessions](../plans/2026-10-20-parallel-chat-sessions.md). Run these after the updated JAR has been installed through the normal manual copying workflow. The assistant runs all Java/Gradle checks; these player checks establish actual rendering, input and notice behavior.

1. In a connected world, ask a question in conversation A. While A is responding, click New chat and ask a different question in B. Both requests should continue independently. Return to A while it runs and confirm its text and activity are still updating.
2. Leave A selected while B finishes. B's title should have a small primary-color dot immediately before it. B's completion must not replace A's messages, move A's scroll position or clear A's draft. The title itself should remain unchanged.
3. Open completed B. Its answer should be available, and its dot should clear after the conversation is displayed. Send a follow-up and confirm the assistant has B's history. A's question and answer must not appear in B's automatic history.
4. Type different unsent drafts in A and B, scroll each to a different position, and toggle an activity group. Switch back and forth. Each conversation should restore its own draft, edit target, scroll/follow choice and activity expansion state.
5. While both conversations run, Stop A. Only A should stop. Send steering in B and confirm it appears once in B and the response continues. Edit/resend should stay unavailable for a running selected conversation.
6. Start another request and close chat. After successful completion, hear one ding and see one native top-right toast. Reopening chat must not repeat either notice. When A completes while B is open in PackSage, A should get its sidebar dot without a ding/toast.
7. Leave a completed background conversation unread, close chat, disconnect and restart Minecraft normally. Reconnect and find its dot still present. Open it, restart again, and confirm the dot remains cleared. Existing conversations from before this feature should begin without dots.
8. Fail or cancel a request and confirm it does not create a successful-completion dot or notice. Leave the world while two requests run; neither should continue or restart automatically when reconnecting.
9. While A runs, delete an inactive conversation. A should continue; A itself should not offer deletion while active. A deleted conversation must stay deleted after queued replies, sidebar refreshes and reconnecting. Sessions belonging to another world must remain read-only.
10. Repeat session selection and unread-dot checks at a narrow window size and a larger GUI scale. Confirm the dot is aligned with the title, truncation leaves room for it, and title/delete click targets still match their visible controls. Open settings while a response finishes: the hidden chat must not acknowledge it as read.

Record observed results in [the validation record](../validation/2026-10-20-parallel-chat-sessions.md). Automated state tests do not establish visible dot color/position, actual mouse/keyboard behavior or audible/native notices.
