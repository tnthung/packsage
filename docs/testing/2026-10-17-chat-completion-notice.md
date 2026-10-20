# Completion notice: Minecraft checks

Date: 2026-10-17

Spec: [chat completion notice](../specs/2026-10-17-chat-completion-notice.md). Use the existing world in the selected test profile after installing the updated JAR.

1. Send a question, then close PackSage chat before it finishes. When the answer completes, hear one short ding and see a PackSage toast at the top right saying the answer is ready and showing the configured chat key. Reopen chat and check the answer is available; reopening must not repeat the notice.
2. Send another question and keep PackSage chat open through completion. Neither the completion ding nor the toast should appear.
3. Close chat into an inventory/container or another screen while a question runs. Successful completion should still notify. If the chat key has been rebound, the toast should name that key.
4. Stop a question or leave the world before completion. Cancelled or stale work must not produce a notice. A failed request must also remain quiet. New chat and session switching keep other requests running; close chat before one finishes and confirm that it can still notify once. See the [parallel-session checks](2026-10-20-parallel-chat-sessions.md).
5. Steer a running request and close chat. Intermediate tool/model rounds must not notify; the final successful answer should notify once.

The assistant owns all automated checks and builds. These player checks establish the actual sound, toast position and screen gating.
