# Journal review fixes: round 1

Review range: `3c72912..d4920e3`. Worktree: `D:/Project/packsage-journal`, branch `feat/packsage-v2-journal`.

All four accepted findings are fixed:

- The prompt scopes action refusals and the tool prohibition to gameplay actions, explicitly permitting saving, updating and forgetting notes. Existing silent-saving, correction and journal-coverage rules remain intact.
- The stalled-store correctness test no longer asserts 250 ms total or 5 ms per call. The producer must finish before the store latch is released, and all overflow, dropped-event and persisted-count assertions remain.
- The burst test uses the known total of two transactions: one session initialization and one event batch. It no longer samples a baseline after the first drain can run.
- The server-thread test gates both background executors, proves recording returns before persistence and search/remember return incomplete futures, then separately gates the writer while close returns its incomplete future. After release, futures complete and the real database still contains the event, note and ended session. JDBC thread observations remain in place.

## Verification

Commands ran in Git Bash from the worktree with `export JAVA_HOME="D:/Project/packsage/.tools/jdk/jdk-21.0.12.1"`.

- RED: `./gradlew test --tests dev.packsage.model.GroundingInstructionsTest -q` exited 1: 13 tests, one failure in `defaultInstructionsLimitGameplayActionsButAllowNoteMaintenance`, at its first assertion (line 56). The gameplay-specific refusal was absent.
- GREEN: the same command exited 0 after the prompt change: 13 tests, no failures/errors/skips.
- `./gradlew test --tests dev.packsage.journal.JournalPerformanceTest -q` exited 0: 3 tests, no failures/errors/skips. These amend correctness checks over existing asynchronous behavior; no production journal change was required.
- `./gradlew build -q` exited 0: 92 suites, 1,014 tests, no failures or errors, 4 skipped. Both amended classes also pass in this full run.
- Manual review of every changed file and `git diff --check` passed. No formatter, push or merge was used.

## Acceptance limits

Automated prompt checks establish wording and tool-name consistency, not model compliance. Latches establish independence from blocked background work, not a latency budget or installed-game frame/tick timing. Live acceptance remains pending in Stage 5; no feature-acceptance checkbox changed.

The task reports retain their original contents with appended `Fix round 1` sections in worktree-local copies at `.superpowers/sdd/2026-10-25-v2-journal/task-13-report.md` and `task-14-report.md`. The main checkout's report files were only read, following the instruction to keep all writes in this worktree.
