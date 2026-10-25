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

`JournalTools` measures UTF-8 bytes through the shared `PackSageTools.encodeRecord` wire encoder, including references, source metadata, title, payload and null recipe. The record budget is **96 KiB**, leaving **32 KiB** below the 128 KiB shared cap for the envelope, coverage reasons and the repeated filter in `nextCall`. The existing 64-record maximum remains.

When the next record would exceed that budget, the provider keeps the delivered prefix and creates continuation from the last delivered entry's creation time and stable key, rather than from the last entry fetched by the database page. Coverage and the delivered sources become partial. Long note titles use a surrogate-safe 96-character preview; full note text remains in the payload. Original wording is included whenever present, even when equal to note text, distinguishing it from absent wording. It is previewed to 1,000 UTF-16 units with `playerWordsTruncated` when necessary. Full original wording remains stored in SQLite. An individually oversized record returns an explicit extraction error rather than an invalid cursor or a silently dropped record.

## Initial final-review test evidence

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

## Fix round 1

Reviewed range: `4ca3176..3dbd6e9`. All nine follow-up findings are addressed:

1. Shared record encoding: `PackSageTools.encodeRecord` owns the entire wire record, including recipe handling. Both the tool response and journal byte budget call it. The 150-note pagination regression compares each actual wire record and its UTF-8 size to the encoder and checks each page's record array against the 96 KiB budget.
2. Marker-write failure: real SQLite triggers block loss-marker inserts. In-session searches remain `PARTIAL` with an awaiting-persistence reason. Removing the trigger and draining persists the marker once; another drain does not duplicate it, and a clean restart keeps overlapping queries partial.
3. Awaiting interval: the writer publishes its earliest pending wall/game bounds with the persisted counter checkpoint before attempting markers. A narrow query at 500-600 ms detects losses timestamped before the preceding 1,000 ms sample.
4. Post-commit cleanup: marker writes acknowledge the counter checkpoint immediately after `Connection.commit()` succeeds, before auto-commit restoration and statement cleanup. A real SQLite connection wrapped to fail `setAutoCommit(true)` proves that retries do not duplicate the committed count.
5. Sustained losses: touching or overlapping consecutive intervals for the same session/kind merge into the latest row. One hundred lossy drains produce one row with count 100 and preserved outer bounds. Separate intervals stay separate; a merged interval conservatively reports its aggregate count for overlapping queries.
6. Logging: marker persistence failures have a distinct message and independent rate limit, so an event-batch error cannot suppress the marker message or mislabel marker failure as newly lost events.
7. Note provenance: equal original wording remains in `playerWords`; absent original wording remains absent. Long wording still has its bounded preview and truncation flag.
8. Coverage detail: multiple session gaps collapse into one count with earliest/latest wall bounds. A 100-gap fixture stays below 1,024 characters; the existing single-gap explanation is preserved.
9. Timestamp bounds: failed-event and overflow fixtures both prove persisted first wall/game bounds are 500 ms/10 ticks, rather than the session start or the preceding sample. Queries ending at 499 ms remain complete after restart.

### Verification

- Tests were committed first in `377d710`. The first run failed compilation because the proposed shared encoder did not exist. After extracting it, the focused run reproduced **seven behavioral failures** across marker retry/awaiting bounds, post-commit acknowledgment, row growth, logging, gap detail and note provenance.
- Shared encoder: `20f4555`. Remaining runtime fixes: `99c74c4`.
- Focused run: **107 passed**, zero skips/failures/errors, covering `JournalLossTest`, `JournalSearchTest`, `JournalToolsTest`, `JournalWriterTest`, `JournalStoreTest` and `PackSageToolsTest`.
- Full `./gradlew build -q`, from this worktree in Git Bash with `JAVA_HOME=D:/Project/packsage/.tools/jdk/jdk-21.0.12.1`: **1,084 tests, 1,079 passed, 5 skipped, 0 failures/errors**, across **97 suites**. This adds seven passing cases to the supplied 1,072-pass baseline. The five skip identities match those documented above.
- `git diff 3dbd6e9..HEAD --check`: passed. Changed files were manually checked against existing style; no formatting tools were used.
- No live acceptance or installation was performed. Only the assigned worktree and the explicitly requested controller-report append were written; no push or merge was performed.
