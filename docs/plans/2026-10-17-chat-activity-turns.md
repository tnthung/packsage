# Chat activity turn implementation

Date: 2026-10-17

Spec: [thinking and tool turns](../specs/2026-10-17-chat-activity-turns.md).

1. Add the smallest immutable activity contract shared by active views and saved turns, with backward-compatible constructors and conversation reads. Propagate provider summaries and exact step completion through the existing event/controller path, including tool errors, failure and cancellation. Keep provider replay separate from presentation.
2. Draw ordered activity headers and expanded details before the answer. Keep expansion state local to the screen, keyed by stable step identity and terminal phase, so refreshes preserve manual toggles and completion collapses once. Reuse existing viewport, font and input patterns.
3. Add focused stream/controller/store/screen tests. Run targeted checks, then the full Gradle test/build without the installation script. Review the diff and record validation and Minecraft-only checks.

One Luna implementation owner handles production and test changes; the coordinator owns these documents, integration review, Gradle and Git. Preserve unrelated validation work. Never use formatting tools or broaden the UI redesign.
