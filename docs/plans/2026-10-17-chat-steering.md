# Chat steering implementation

Date: 2026-10-17

Spec: [mid-response steering](../specs/2026-10-17-chat-steering.md).

1. Add a compatible exchange steering method and a locked pending-message queue in the ChatGPT backend. Drain at actual request dispatch, preserve the all-tool-results boundary, and continue text-only responses when steering is pending. Keep callbacks outside the backend lock.
2. Add immutable steering text/answer-offset metadata to turns and views, with compatible constructors and empty defaults when loading old conversations. Accept only into an installed active exchange; validate exact authored USER replay. Carry metadata through success, failure, cancellation, retries and runtime view copies.
3. Route active composer submissions through steering with an acceptance result. Keep rejected drafts and a separate Stop control. Render assistant segments around player steering bubbles, and project the same visible sequence into historical context and token accounting.
4. Write focused regression checks before implementation. Cover ordered messages, tool and no-tool boundaries, dispatch races, preparation/terminal rejection, cancellation/scope guards, exact replay, reload, offsets, history and composer controls. The coordinator commits every written step before running checks, then runs focused tests and the full Gradle test/build and records results.

Use one Luna write owner after the failed-tool detail work is committed. The coordinator owns documentation, Git and Gradle. Preserve unrelated validation documents and existing style; never run formatting tools or expand into capture-time queuing or stream interruption.
