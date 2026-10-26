# Track U: chat timing, opening and session filtering

Date: 2026-10-26. Branch: `t3/chat-timing-opening-speed`, based on `feat/packsage-v2` at `ea64a69`.

Implements Track U of the [player feedback plan](../plans/2026-10-26-v2-player-feedback.md) and the timing, opening-speed and other-session clauses of the [specification](../specs/2026-10-26-v2-player-feedback.md).

## Behavior and decisions

- Completed answers display elapsed milliseconds from the controller's existing monotonic acceptance timestamp to model termination. Capture, history preparation and tool/model work are included; token counting and persistence after termination are excluded.
- Expanded tool rows retain the tool name, bounded arguments, coverage, returned record count, the first 240 characters of result detail and duration. Existing hidden note activity and structured error behavior remain intact.
- Turn and activity durations, tool names and result summaries are additive JSONL fields. Old turns without them load with absent timing. Existing constructors remain available.
- `showChatsFromOtherSessions` defaults to false, survives settings saves and is toggled directly in the sidebar. Filtering uses the active world's stable identity, including during sidebar searches. Existing other-world read-only behavior remains.
- Bounded, rebuildable `.summary.json` files retain sidebar titles, answer previews, file freshness stamps and the latest completed request identity. Unread state is still reconciled against `.state.json`. Creation, append and replacement update the cache; deletion removes it. The entire post-save summary refresh is best-effort, so a cache write or reread failure cannot fail a saved exchange.
- Missing, stale or malformed summaries initially produce header-only `Saved chat` entries. A subsequent callback supplies the actual titles, previews and unread state after backfill. Backfill schedules one conversation read at a time on the existing serial I/O executor, allowing selected-chat reads to run between those tasks.
- The selected conversation is published as soon as its asynchronous read and controller setup finish, independently of sidebar metadata. Existing world, holder and load-generation guards still reject late loads.
- Screen initialization builds widgets and schedules the sidebar without selecting/rendering history. The newest four turns render first; four older turns are added per client tick. Scrolled content is anchored during prepend, and conversation UI state retains the rendered window when switching away and back.

## Opening-path profile

Before this change, `ConversationStore.sessions()` parsed every JSONL file in full. `AssistantRuntime.enterWorld()` then waited for that entire catalog before attaching the selected conversation. Store reads were already on the I/O executor; the defect was eager work and a dependency on that work, rather than synchronous conversation-file reads in `AssistantScreen.init()`.

`AssistantScreen.init()` also selected history before opening, and conversation drawing formatted turns from oldest to newest. Initialization now defers history selection, and drawing starts with the recent tail. `AssistantRuntime` still performs controller/provider attachment on the client executor; this change does not move engine-owned work to an arbitrary thread. The existing history preview refresh during rendering remains.

The opening regression prepares 24 saved conversations, each containing a 128 KiB answer and a 128 KiB encrypted replay item. It uses a fresh store and a gated executor. JFR `jdk.FileRead` events record bytes and owning threads while listing sessions and loading the selected conversation. The baseline uses the pre-summary store from `8fd6d42` with the same fixture. JFR startup, shutdown and dump time are excluded from the measured read interval.

| Measurement | Before | After, cached summaries | After, legacy files without summaries |
|---|---:|---:|---:|
| Caller scheduling, rounded to ms | 0 | 0 | 0 |
| Sidebar listing | 664 ms | 343 ms | 56 ms |
| Selected conversation read | 12 ms | 20 ms | 12 ms |
| Combined wall time | 680 ms | 364 ms | 68 ms |
| Conversation-root bytes read | 6,972,824 | 477,969 | 664,301 |
| Files read beyond 64 KiB | 24/24 | 1/24 | 1/24 |
| Conversation reads on caller thread | 0 | 0 | 0 |

These are single fixture samples, not a frame-time SLA or percentile benchmark. Cached-summary and legacy cases run sequentially, so JVM/JFR warmup affects their relative timings. Legacy backfill is deliberately excluded from the initial-listing measurement and separately tested for interleaving with selected reads. The only full body read before initial listing and selected-load completion is the selected conversation. The cached-summary case reduces those bytes by about 93%.

The runtime regression separately proves that a selected answer becomes usable before an already-queued sidebar catalog completes. The screen initialization regression proves widgets exist without a history-preview selection. No game was launched, so an actual rendered one-frame opening and installed UI acceptance remain unmeasured.

## Validation

At code commit `823f7a2`:

```powershell
$env:JAVA_HOME='D:/Project/packsage/.tools/jdk/jdk-21.0.12.1'
./gradlew build -q
node D:/Project/packsage/build/tools/count.cjs .
```

Build exit code: 0. Totals: **1,234 tests; 1,229 passed; 5 skipped; 0 failures; 0 errors**.

The skipped tests are:

- `ConversationStoreTest.sessionCatalogIgnoresExternalJsonlLinks`
- `ConversationStoreTest.latestMarkerLinkCannotRedirectConversationSelectionOutsideTheRoot`
- `ConversationStoreTest.historySearchRejectsForeignHeadersAndSymbolicLinkSessions`
- `ConversationStoreTest.deletingNonLatestLeavesLatestAndRejectsCrossWorldOrUnsafeTargets`
- `GroundingInstructionsTest.defaultLengthMatchesLiveTestedCandidateWhenPresent`

Tests preceded implementation for timing persistence, setting defaults/persistence and opening I/O. Initial persistence/settings RED runs identified the missing APIs; later controller, sidebar, opening, cache-failure, selected-publication and scroll regressions failed on the corresponding missing behavior before fixes. The opening regression failed with all 24 bodies read before passing with only the selected body read, including the legacy case.

Claude reviewed the track and its follow-up fixes; the final bounded review approved the remaining cache-refresh guard. Review was static; automated evidence comes from the build above. A manual style pass and `git diff --check` were clean. No formatter, computer use, game launch, push or merge was performed. `CHANGELOG.md`, `docs/roadmap` and `model/GroundingInstructions.java` were unchanged.

## Implementation commits

- `d359818` [Add] persist the other-session chats toggle
- `a402d9c` [Add] save answer and tool timing with conversation turns
- `8fd6d42` [Add] show answer and tool timing and wire the sidebar toggle
- `119d5c0` [Fix] retain tool metadata on interrupted activity rows
- `29b11b9` [Chg] open chats from summaries and render recent history first
- `708b2f0` [Fix] publish selected chats before sidebar metadata is ready
- `4da7767` [Fix] preserve scroll position while older turns fill in
- `78e2673` [Fix] keep summary cache failures from failing saved chats
- `d6281bd` [Fix] restore the rendered history window when switching chats
- `69e0163` [Fix] backfill legacy sidebar summaries without blocking selected chats
- `823f7a2` [Fix] isolate the entire post-save summary refresh
