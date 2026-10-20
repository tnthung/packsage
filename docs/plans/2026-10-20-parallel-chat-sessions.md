# Parallel chat sessions implementation plan

> **For agentic workers:** Use `superpowers:executing-plans` or `superpowers:subagent-driven-development` to implement this plan task by task. Keep one write owner for the shared runtime and UI files. Steps use checkboxes for tracking.

**Status:** Proposed for review. This document does not authorize implementation.

**Goal:** Let the player ask questions in several independent conversations, switch between them while replies continue, and return to completed answers with a primary-color dot before each unread conversation title.

**Architecture:** Keep one `RequestController` per loaded conversation under `AssistantRuntime`, with an independently owned gameplay capture, evidence scope and knowledge service. Selection controls which conversation is displayed; it does not control which requests run. Share account authentication, model catalog, settings, executors and the existing serialized conversation store.

**Tech stack:** Existing Java 21, Minecraft 1.21.1, NeoForge, Gradle and JUnit infrastructure; no new dependencies.

**Requirements:** The player's 2026-10-20 request, with proposed behavior below. Preserve the applicable [chat controls](../specs/2026-10-13-chat-controls.md), [activity turns](../specs/2026-10-17-chat-activity-turns.md), [steering](../specs/2026-10-17-chat-steering.md), [completion notice](../specs/2026-10-17-chat-completion-notice.md) and [persistent navigation](../specs/2026-10-18-persistent-navigation.md) contracts except for the explicitly changed session-switch behavior.

## Proposed behavior

- New chat and the existing session list remain available while another conversation is generating. A request in A keeps running when the player creates or opens B, and B can start its own request immediately.
- Each conversation has at most one active response. Sending in the selected active conversation continues to submit steering; Stop affects only that conversation. Edit/resend remains unavailable while that conversation is active.
- Reopening an active conversation restores its live answer and activity. Reopening a completed same-world conversation allows follow-up questions using that conversation's own history. Foreign-world conversations remain read-only.
- Preserve each conversation's unsent draft, edit target, scroll position, follow-bottom choice and activity expansion state when switching. These UI details need only survive within the current play session; saved answers retain their existing durable storage.
- A successful final response becomes unread until its completed conversation is displayed in the actual chat screen. Opening a loading session, showing its sidebar row, viewing another conversation, or receiving a toast does not count as reading. Opening the completed conversation counts as reading without requiring a scroll-to-bottom gesture.
- Draw a small solid dot immediately before an unread session title, using `ModernUi.ACCENT` (`0xffb8d6c4`). Keep the title text unchanged. Running, failed and cancelled responses do not create this completion dot. Existing history-inclusion dots retain their separate meaning.
- Persist unread state across closing chat, disconnecting and restarting Minecraft. Existing saved conversations initially count as read. Each later successful response can make its conversation unread again.
- Closing chat keeps all current-world requests running. Disconnect, account sign-out/replacement, and existing evidence-invalidating world/resource/provider changes cancel all affected requests. They preserve recorded partial turns and reject stale callbacks. Requests do not continue while Minecraft is closed or automatically restart on reconnect.
- Keep the current ding/toast policy: one notice per successful eligible request outside PackSage chat, suppressed while any PackSage chat is open. Background completions still update their sidebar dots while another conversation is displayed.
- Keep deletion simple: an active conversation cannot be deleted; inactive conversations can be deleted while other conversations run. Deletion must wait for that conversation's pending persistence and cannot be undone by a late callback.

## Current constraints in the source

- `AssistantRuntime` owns one controller, capture, scope, knowledge service and completion-request identity. Its `send()` advances the shared scope; `openSession()` and `newChat()` reuse the controller.
- `RequestController` already implements a single-conversation request lifecycle, persistence, steering and cancellation. Its `showConversation()`/`newChat()` paths cancel active work. Reuse this unit rather than adding multiple active requests inside it.
- `ChatGptBackend.start()` replaces `activeExchange` and cancels the previous exchange. Multiple controllers alone would therefore still cancel one another.
- `GameplayContextCapture` supersedes its previous request and pending frame. Sharing one capture instance would also break overlapping sends.
- `KnowledgeService` and several provider readers bind to a current-scope supplier. A send in B must not change the scope supplier used by A's tools, screenshots or navigation.
- `ConversationStore` already serializes reads/writes through its queue. `SessionSummary` has no completion/read metadata, and `AssistantScreen` currently has one composer and scroll state for all sessions.
- Production JEI bindings share `PackSageJeiPlugin.activeProvider`. Its recipe cursors already carry exact scopes, but its status/cache/throttle state is shared. Exercise the real binding with interleaved requests before deciding whether any provider change is needed.

## Global constraints

- Preserve the 1,000-character composer limit, current send shortcuts, per-request captured model/effort/search/screenshot settings, and exact steering/replay ordering.
- Preserve current source identity, navigation validation, history limits, world isolation and bounded provider work. Parallel model requests do not permit Minecraft reads off the client thread.
- A conversation key is the stored world/server identity plus conversation ID. Do not key conversations by title, row index, or display label.
- Keep one runtime authority for each loaded conversation; reopening A must never create a second controller writing to A.
- Do not hold runtime locks while calling controllers, providers, UI listeners or persistence callbacks. Selection generations reject stale UI loads; request/world generations reject stale work independently.
- No formatting tools, dependency upgrades, prompt changes, new notification settings, request scheduler, cross-world background execution or unrelated refactoring.
- Keep this planning change uncommitted. Do not mutate Git without explicit authorization; use the staged-index `$commit-message` workflow when commits are authorized.

## Review focus

1. B starts while A is waiting for capture, tools or final token counting: neither request is cancelled or assigned the other's evidence.
2. A finishes after the player selects B: A persists and becomes unread without replacing B's messages, draft or scroll state.
3. An acknowledgement for A's earlier reply arrives after a newer reply: it cannot clear the newer completion dot.
4. A world change, sign-out or deletion overlaps queued callbacks: no stale answer, dot, notice or write resurrects the old session.
5. Opening several conversations and using their saved references preserves exact per-conversation history, scope and navigation checks.

## Task 1: Allow independent backend exchanges

**Files:**

- Modify `src/main/java/dev/packsage/model/chatgpt/ChatGptBackend.java`.
- Test `src/test/java/dev/packsage/model/chatgpt/ChatGptBackendTest.java`.
- Retain coverage in `src/test/java/dev/packsage/model/ReferenceAliasBackendTest.java`.

**Interface:** Keep `ModelBackend.start(Request, Consumer<Event>) -> Exchange` and exchange-level `submit`, `steer` and `cancel` unchanged. Replace the backend's single `activeExchange` with a set of exchanges belonging to the current account epoch.

- [ ] Add a controlled-stream test that starts A and B before either finishes; interleave deltas and tool continuations and assert both complete with separate events/replay. The current implementation must fail because starting B cancels A.
- [ ] Add tests that cancelling A leaves B active, terminalizing A removes only A, and sign-out/account replacement cancels every exchange. Include an exchange racing registration with an account change.
- [ ] Register and remove exchanges under the existing backend lock. Cancel the collected exchanges outside that lock. Preserve account-epoch checks, capability caches, token refresh sharing, retry bounds and exchange-local steering state.
- [ ] Run `./gradlew.bat test --tests dev.packsage.model.chatgpt.ChatGptBackendTest --tests dev.packsage.model.ReferenceAliasBackendTest`. Both suites must pass, including existing retry and replay tests.

## Task 2: Give each conversation independent request ownership

**Files:**

- Modify `src/main/java/dev/packsage/chat/AssistantRuntime.java`.
- Modify `src/main/java/dev/packsage/chat/RequestController.java` only where conversation attachment/persistence settlement needs an explicit interface.
- Modify `src/main/java/dev/packsage/client/ClientAssistant.java` if production factory wiring needs adjustment.
- Test `src/test/java/dev/packsage/chat/AssistantRuntimeTest.java`, `src/test/java/dev/packsage/chat/RequestControllerTest.java`, `src/test/java/dev/packsage/chat/AssistantIntegrationTest.java` and `src/test/java/dev/packsage/client/ContextCaptureTest.java`.

**Ownership:** Add a private nested conversation-state holder in `AssistantRuntime`: stored identity, controller, independently created `GameplayContextCapture`, current scope, `KnowledgeService` with its `HistoryAdapter`, request-local errors and completion identity. Track the selected key separately from the map. Keep shared account/settings/world state on the runtime.

- [ ] Add deterministic tests using manually completed futures: start A, switch/create B, start B, interleave capture and tools, then finish B before A. Assert both requests persist to their original conversation IDs, use their own histories and keep their original captured settings.
- [ ] Add regressions for switching during A's final token counting and for two pending screenshot captures. Assert B does not invalidate A and cancelling A's capture leaves B's pending frame intact.
- [ ] Construct each conversation's capture/service using a supplier for that holder's scope. Allocate unique request generations from the runtime, but update only the submitting conversation's scope. A session switch must not invalidate another holder's scope.
- [ ] Bind preparation, tool execution, request building, view publication and completion callbacks to their owning holder. Only a callback for the selected holder may update the message pane; every holder can persist and update list state. Call render-frame capture hooks for every holder with pending capture work.
- [ ] Route send/steer/stop/resend/history preview/navigation to the selected holder. Keep one controller for each stored key, including while an asynchronous open is pending. Coalesce repeated New chat while its creation is loading, and reuse an already empty idle selection instead of making repeated empty files.
- [ ] Make `openSession()` attach an existing holder or load/create one for the explicit stored ID. Use the selection generation only to decide which load may attach to the UI; never cancel background requests because selection changed. Do not call `openLatest()` for every new holder.
- [ ] Broadcast existing world invalidation, disconnect and account transitions to every affected controller/capture/service. Disable deletion by the target conversation's activity, wait for its queued persistence, and remove only that holder. Preserve existing partial-turn and failed replacement behavior.
- [ ] Add tests for stale open callbacks, reopening an already active ID, selected-only Stop/steering/resend, deletion while another chat runs, all-request disconnect/sign-out, and late world callbacks. Use barriers/futures rather than sleeps.
- [ ] Interleave query/read/continuation/navigation calls through the production JEI wrapper with A and B scopes. Verify that A's observed handles and cursors remain usable after B's query, and B cannot use A's references. Keep shared cache limits/throttling unless this test exposes a correctness failure; scope any required correction to that binding.
- [ ] Run `./gradlew.bat test --tests dev.packsage.chat.AssistantRuntimeTest --tests dev.packsage.chat.RequestControllerTest --tests dev.packsage.chat.AssistantIntegrationTest --tests dev.packsage.client.ContextCaptureTest --tests dev.packsage.knowledge.jei.JeiProjectionTest`.

## Task 3: Persist completion and read state

**Files:**

- Modify `src/main/java/dev/packsage/chat/ChatContracts.java`, `src/main/java/dev/packsage/chat/ChatActions.java`, `src/main/java/dev/packsage/chat/ConversationStore.java` and `src/main/java/dev/packsage/chat/AssistantRuntime.java`.
- Test `src/test/java/dev/packsage/chat/ConversationStoreTest.java`, `src/test/java/dev/packsage/chat/ChatContractsTest.java` and `src/test/java/dev/packsage/chat/AssistantRuntimeTest.java`.

**Interfaces:** Extend `SessionSummary` with `boolean active` and nullable `RequestId unreadRequestId`, retaining its existing constructor with inactive/read defaults. Stored summaries are inactive; `AssistantRuntime.sessions()` overlays live activity and unread state by conversation key. Add `ChatActions.markRead(SessionSummary session, RequestId expectedCompletion) -> void` and `ConversationStore.markRead(WorldIdentity world, String conversationId, RequestId expectedCompletion) -> CompletionStage<Void>`. Add a store operation `recordCompletion(WorldIdentity world, String conversationId, RequestId requestId) -> CompletionStage<Void>` for the authoritative successful completion path.

- [ ] Add tests for a new unread completion, read acknowledgement, reconstruction of the store/runtime, legacy conversations without metadata, failed/cancelled turns, resend replacement, deletion and malformed/missing metadata.
- [ ] Store the latest successfully completed request ID and acknowledged request ID in one small companion JSON file beside each conversation, using the same safe world/conversation path derivation, serialized I/O queue and existing atomic-file-replacement pattern. Missing metadata means read. Keep these fields out of model replay and leave existing turn JSONL unchanged.
- [ ] Queue `recordCompletion` after the owning controller has dispatched persistence for the validated successful final turn. Verify that the referenced completed turn exists when writing/loading the metadata; an older completion callback cannot replace metadata for a later retained completed turn. A persistence failure must remain visible and cannot manufacture a durable completed answer.
- [ ] Acknowledge only the expected latest completion ID. A delayed read of A1 cannot acknowledge A2, and duplicate acknowledgements perform no additional write. Preserve a marker until its acknowledgement saves; report failures through existing error handling. Metadata changes must not reorder sessions or change their answer timestamps.
- [ ] Delete the companion metadata with its conversation through the existing store queue. Validate metadata against the retained turns after resend/replacement; never display a dot for a removed or unsuccessful response.
- [ ] Update per-holder completion handling and session summaries without using the runtime's selected scope or one global completion-request ID. Keep notice eligibility independent from read acknowledgement and preserve the current chat-open toast suppression.
- [ ] Add race tests for completion versus selection/read, A1 acknowledgement versus A2 completion, persistence failure, deletion and stale world callbacks. Assert repeated view refreshes do not recreate cleared dots or notices.
- [ ] Run `./gradlew.bat test --tests dev.packsage.chat.ConversationStoreTest --tests dev.packsage.chat.ChatContractsTest --tests dev.packsage.chat.AssistantRuntimeTest`.

## Task 4: Make the sidebar and composer conversation-aware

**Files:**

- Modify `src/main/java/dev/packsage/client/AssistantScreen.java`.
- Modify `src/main/java/dev/packsage/client/ClientAssistant.java` only for actual-screen visibility checks if required.
- Test `src/test/java/dev/packsage/client/AssistantScreenStateTest.java` and `src/test/java/dev/packsage/client/ClientAssistantTest.java`.

**Interface:** Continue rendering the selected `ChatContracts.View`. Overlay live holder summaries on the stored session list by stored key so a new/running conversation appears before its first answer saves. Use `SessionSummary.active()` for row-specific controls, `unreadRequestId()` for the dot and `markRead` for acknowledgement. Add `AssistantScreen.refreshSessions(List<SessionSummary> sessions)` so list updates do not need to replace the selected message view.

- [ ] Add screen tests for A running while B is selected, selecting A again, and preserving separate drafts, latest-edit targets, scroll/follow state and activity toggles. Assert A's deltas/completion do not clear or reposition B.
- [ ] Save/restore the existing screen state fields by stored conversation key on accepted selection changes. Keep global sidebar search, grouping and Show more controls global. Reset a new conversation's editor state and discard only a deleted conversation's state.
- [ ] Make New chat and session switching available independently of another holder's activity. Base Send/steer/Stop/Edit/Resend on the selected view. Base delete availability on the row's own activity; apply the same rule to drawing and click handling.
- [ ] Merge background completion updates into sidebar summaries even when the selected view does not change. Reject older asynchronous list results so they cannot overwrite newer live unread state. Preserve current grouping, collapse counts, title/last-message search and row selection.
- [ ] Draw a solid 4-logical-pixel dot before an unread title in `ModernUi.ACCENT`, vertically centered on the title line with a 4-pixel gap. Reserve its width before truncating title text; keep row/delete hit regions and selected/hover surfaces consistent. Narrate unread completion in the row's accessible description without inserting a symbol into the stored title.
- [ ] After the selected completed conversation is actually rendered in the active `AssistantScreen`, acknowledge its expected unread request once. Do not acknowledge while loading, behind settings, from sidebar rendering or from queued refresh callbacks. If it is already selected when completion arrives, the next visible render clears the marker.
- [ ] Add tests for dot layout at narrow widths, read clearing after successful display, hidden/loading-screen suppression, a second completion after an earlier read, stale sidebar reloads, and target-specific deletion controls. Keep actual color/click/render acceptance for Minecraft.
- [ ] Run `./gradlew.bat test --tests dev.packsage.client.AssistantScreenStateTest --tests dev.packsage.client.ClientAssistantTest`.

## Task 5: Verify the complete player flow and document delivery

**Files:**

- Maintain `CHANGELOG.md` and `docs/roadmap/2026-10-13-todo.md` alongside implementation.
- Add `docs/testing/2026-10-20-parallel-chat-sessions.md` and `docs/validation/2026-10-20-parallel-chat-sessions.md`.
- Update the session-switch clause in `docs/specs/2026-10-17-chat-steering.md` and document the independent unread marker alongside `docs/specs/2026-10-17-chat-completion-notice.md` when the feature is implemented.

- [ ] Run `./gradlew.bat test build` once the focused suites pass. Check every changed file against existing style manually and finish with `git diff --check`; never run a formatter. Record actual results, not planned results.
- [ ] Write the player-facing live checklist: start A, create B while A runs, ask in B, return to A while it runs, leave A, and observe A's completed-unread dot before its title. Open A and confirm the dot clears and the answer remains available for a follow-up.
- [ ] Cover two responses completing while chat is closed; A completing while B remains visible; independent Stop and steering; draft/scroll restoration; inactive deletion during another request; and foreign-world read-only behavior.
- [ ] Close/reopen chat and restart Minecraft after a background answer has saved. Confirm its unread dot survives until the completed conversation is opened, then remains cleared after another restart. Confirm unfinished requests are not resumed on reconnect.
- [ ] Confirm existing off-chat ding/toast behavior, actual accent-dot position and narrow-window clicks in Minecraft. The assistant runs all automated commands; the player's manual JAR-copying workflow applies only to live checks.
- [ ] Record passing automated checks and remaining live acceptance separately. This feature is accepted only when independent A/B requests, saved return/read state and the actual unread-dot UI all pass their corresponding checks.

## Execution order

Implement Tasks 1–4 in order with one owner for `AssistantRuntime` and related contracts, then complete Task 5. Backend and storage tests can be investigated independently, but runtime/contracts/UI writes overlap and should not run concurrently. No product code, installation or Git mutation is part of writing this plan.
