# Chat completion notice implementation

Date: 2026-10-17

Spec: [chat completion notice](../specs/2026-10-17-chat-completion-notice.md).

1. Add a compatible completion callback at the end of `RequestController.finishModel`, after final replay/token validation and normal answer publication/persistence dispatch. Invoke it only for a completed outcome; keep callbacks outside controller locks.
2. Pass the event through `AssistantRuntime` onto the existing client executor with world/session/publication/scope guards. Keep existing constructors usable for tests and other callers. Avoid polling or a separate event framework.
3. In `ClientAssistant`, use the actual current Minecraft screen to suppress notices in PackSage chat, and require a live player/world. Otherwise play a short existing UI ding and add a native top-right system toast with the configured chat key.
4. Reproduce missing completion delivery before implementation. Add focused lifecycle checks, commit each written step before tests, then run the full Gradle test/build and record the Minecraft-only checks. Preserve the steering and failed-call work and unrelated validation documents; never use formatting tools.
