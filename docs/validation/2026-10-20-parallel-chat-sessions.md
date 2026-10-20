# Parallel chat sessions validation

Date: 2026-10-20

Plan: [parallel chat sessions](../plans/2026-10-20-parallel-chat-sessions.md). Worktree branch: `feat/parallel-chat-sessions`, starting at local commit `221fe33`. Implementation is in progress; this record distinguishes completed checks from remaining acceptance.

## Automated evidence

- The clean baseline passed `./gradlew.bat test --console=plain`: 644 tests, zero failures/errors and four existing skips. The system Java 24 failed during Gradle configuration with `Type T not present`; the existing portable Java 21 at `D:/Project/packsage/.tools/jdk/jdk-21.0.12.1` ran the build without a project configuration change.
- Backend RED reproduced the singleton failure: starting B cancelled A before A's tool call. The initial post-change run reached both responses and exposed an incorrect new replay assertion; that assertion now follows the actual `AssistantOutput` message shape.
- At `fb603d4`, the focused backend, reference-alias, conversation-store and contract suites passed: 116 tests, zero failures/errors and four existing skips. The command was `./gradlew.bat test --tests dev.packsage.model.chatgpt.ChatGptBackendTest --tests dev.packsage.model.ReferenceAliasBackendTest --tests dev.packsage.chat.ConversationStoreTest --tests dev.packsage.chat.ChatContractsTest --console=plain`.
- These checks cover independent backend streams and tool continuation replay, selected exchange cancellation, terminal cleanup while another exchange remains active, sign-out and successful account replacement. Store checks cover legacy read defaults, completion/read reconstruction, stale completion/acknowledgement IDs, unchanged turn logs and session timestamps, failed/cancelled outcomes, replacement and deletion.
- Runtime and screen integration, the final full build and whole-branch review remain in progress. No automated result here establishes visible Minecraft behavior.

## Decisions during execution

- Backend work and store/contracts work run in parallel, followed by runtime and screen work with disjoint ownership. The parent serializes Git and Gradle. Incorrect interface assumptions would require integration rework.
- The user's SDD execution authorizes commits inside the feature worktree, superseding the earlier planning-only hold. The original checkout remains separate; merge and push are not authorized.
- Displaying a completed foreign-world conversation may acknowledge its unread UI metadata. It remains read-only for new model requests and retains navigation restrictions. A different interpretation would change acknowledgement policy without changing game authority.
- Backend cancellation retains its existing weak callback contract. A terminal event accepted before an account transition may still reach the consumer afterward; this window predates the exchange-set change. Every affected runtime holder must invalidate before the backend account transition, and generation guards must reject late effects. Incorrect ordering could allow stale answers or notices.
- The start-latch test did not force the private account-epoch snapshot/registration interval, so it was replaced with deterministic cleanup/account-replacement coverage. Retained per-request consumers provide the runtime's late-event test seam. The backend epoch-registration check remains; no callback-drain framework or test-only production hook is added. The narrow internal interval has no forced-interleaving test.

## Minecraft acceptance

Not run. This worktree has not been installed into a Minecraft profile. The [player checklist](../testing/2026-10-20-parallel-chat-sessions.md) covers concurrent A/B interaction, draft/scroll restoration, dot appearance and clearing, restart persistence, Stop/steering/deletion isolation, world changes, settings visibility and ding/toast behavior. These remain pending until observed in Minecraft.
