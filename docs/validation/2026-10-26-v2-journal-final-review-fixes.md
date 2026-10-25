# Journal final-review fixes

Date: 2026-10-26. Worktree: `D:\Project\packsage-fix-journal`, branch `fix/v2-journal`, base `4ca3176`.

## Status

- Finding 1 (CRITICAL): fixed, with automated restart and outside-interval coverage regressions.
- Finding 2 (IMPORTANT): fixed, with an automated provider-to-tool-response pagination regression.
- No Minecraft interaction, installation, push or merge was performed. The only authorized write outside the assigned worktree is the requested controller report.

## Recording-loss persistence

`JournalStore.open` adds `recording_losses` and its time index using `CREATE TABLE/INDEX IF NOT EXISTS`. The schema version remains **1**: this is an additive extension; existing events, sessions, notes and metadata are retained, and unsupported schema versions are still refused before the extension runs.

Each marker stores its session id, loss kind, count, first/last wall milliseconds and first/last game ticks. The writer samples deltas for failed events, count-only overflow, hook failures, dropped/unread explosion positions, tracker evictions and oversized container snapshots. It commits markers on the writer thread during periodic drains and the final close drain, including when there were no queued events. Failed marker transactions retain their counter checkpoint and bounds for retry rather than acknowledging an unpersisted loss.

Historical searches aggregate markers whose intervals overlap the requested time range and return `PARTIAL` with a recording-loss reason. Current counters cover deltas awaiting persistence. Persisted counters no longer make unrelated time ranges globally partial. Event/overflow timestamps extend the marker bounds when needed; hook-only intervals conservatively span writer samples, so they can include some quiet time. Already forgotten losses from older builds cannot be reconstructed. If storage remains unwritable through shutdown, a durable marker cannot be guaranteed.

## Bounded journal pages

`JournalTools` measures UTF-8 bytes of records in the same JSON shape used by `PackSageTools`, including references, source metadata, title, payload and null recipe. The record budget is **96 KiB**, leaving **32 KiB** below the 128 KiB shared cap for the envelope, coverage reasons and the repeated filter in `nextCall`. The existing 64-record maximum remains.

When the next record would exceed that budget, the provider keeps the delivered prefix and creates continuation from the last delivered entry's creation time and stable key, rather than from the last entry fetched by the database page. Coverage and the delivered sources become partial. Long note titles use a surrogate-safe 96-character preview; full note text remains in the payload. Identical original wording is omitted, and distinct original wording is previewed to 1,000 UTF-16 units with `playerWordsTruncated` when necessary. Full original wording remains stored in SQLite. An individually oversized record returns an explicit extraction error rather than an invalid cursor or a silently dropped record.

## Test evidence

Both reproductions were run before production changes and committed in the RED state:

- `JournalLossTest`: **8 failures**. Six loss kinds changed to complete after clean restart; overflow was already incorrectly complete before restart. The existing-v1 fixture failed because the loss table did not exist.
- `JournalToolsTest.maximumLengthNotesKeepContinuationThroughTheToolResponsePipeline`: **1 failure**. The shared tool response hit its output limit and lost continuation.

After the fixes:

- `JournalLossTest`: **8 passed**. All seven loss kinds survive clean close/reopen; the same overlapping query stays partial and a query outside the lossy interval stays complete. An existing v1 database gains loss storage while retaining its version, note and session.
- `JournalWriterTest`: **13 passed**.
- `JournalSearchTest`: **26 passed**.
- `JournalToolsTest`: **17 passed**. The pipeline regression follows returned `nextCall` arguments through all **150** maximum-length notes at identical timestamps, preserves note/report text, rejects duplicate delivery, and verifies every serialized response stays below 128 KiB without shared-cap truncation.
- Full build: **1,077 tests, 1,072 passed, 5 skipped, 0 failures/errors**, across **97 suites**. Produced `build/libs/packsage-0.1.0.jar`; it was not installed.
- `git diff 4ca3176..HEAD --check`: passed. Existing coding style was reviewed manually; no formatting tools were used.

The supplied baseline was 1,064 passes. This work adds nine test cases. This isolated worktree also skips `GroundingInstructionsTest.defaultLengthMatchesLiveTestedCandidateWhenPresent` because its optional `build/prompt-candidates/candidate-6.txt` fixture is absent; the test uses an explicit assumption. Four `ConversationStoreTest` cases skip when symbolic links are unavailable: `sessionCatalogIgnoresExternalJsonlLinks`, `latestMarkerLinkCannotRedirectConversationSelectionOutsideTheRoot`, `historySearchRejectsForeignHeadersAndSymbolicLinkSessions` and `deletingNonLatestLeavesLatestAndRejectsCrossWorldOrUnsafeTargets`.

Commands ran in Git Bash from the assigned worktree root, each prefixed with `export JAVA_HOME="D:/Project/packsage/.tools/jdk/jdk-21.0.12.1"`:

```bash
./gradlew test --tests dev.packsage.journal.JournalLossTest -q
./gradlew test --tests dev.packsage.journal.JournalToolsTest.maximumLengthNotesKeepContinuationThroughTheToolResponsePipeline -q
./gradlew test --tests dev.packsage.journal.JournalLossTest --tests dev.packsage.journal.JournalWriterTest --tests dev.packsage.journal.JournalSearchTest -q
./gradlew test --tests dev.packsage.journal.JournalToolsTest -q
./gradlew build -q
```

## Commits

- `8d09adb` — `[Add] reproduce journal loss persistence and oversized page failures`
- `bb462aa` — `[Fix] persist journal recording loss intervals across restarts`
- `3148485` — `[Fix] keep journal pages within the tool output budget with continuation`

This validation record and the changelog/roadmap references are committed separately. Live acceptance remains unverified for these fixes; the broader journal checklist stays open.
