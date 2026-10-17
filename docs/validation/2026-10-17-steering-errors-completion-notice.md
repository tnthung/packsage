# Steering, failed-call details and completion notice validation

Date: 2026-10-17

Source and tests: `18678cdf5aaca7c9ea8612242301c99f2478f028`.

## Automated evidence

- `./gradlew.bat test build` passed using the repository's portable Java 21. The final XML reports contain 541 tests across 49 suites: 537 passed, 4 skipped, zero failures and zero errors.
- Steering checks cover text-only follow-up, completion of the full parallel tool-result batch, ordered messages, delayed authentication, cancellation without later dispatch, preparation/terminal rejection, exact authored replay, saved and legacy turns, retry offsets, historical projection and token counts, history-tool retrieval and bounded excerpts, draft retention and separate Send/Stop controls at narrow widths.
- Failed-tool checks reproduce the former generic detail, then verify structured detail/code, execution errors, bounded readable text, malformed fallback, persistence and recovery of generic old activity from recorded failed results.
- Completion checks reproduce the absent callback, then verify one event after final validated success, no event for backend/replay/token-count failure or cancellation, client-executor delivery without an open screen, and suppression after New chat invalidates a queued callback. A separate regression reproduced New chat during final token counting before the callback is queued; binding the notice to the original request ID and session epochs now suppresses that stale notice too. The native sound/toast calls compile against the pinned Minecraft 1.21.1 API.
- The reviewed retry-boundary, historical-tool omission, narrow Stop/input overlap and blank clipped-history issues were corrected with regression coverage. Manual source/style inspection and `git diff --check` passed. No formatting tools were used; the two unrelated validation documents remain outside these commits.

## Built and installed artifact

- Built JAR: `build/libs/packsage-0.1.0.jar`, 4,907,914 bytes.
- SHA-256: `854ADD0C8021A373520627D0501229A39C8A431A654476A05C5D1D655D69F976`.
- With Minecraft closed, copied to `C:\Users\hung1\curseforge\minecraft\Instances\Packsage\mods\packsage.jar` and `C:\Users\hung1\curseforge\minecraft\Instances\All the Mods 10 - ATM10\mods\packsage.jar`. Both installed hashes match the built artifact, and each directory contains one PackSage JAR.
- Prior minimal-profile JAR backed up to `.tools/test-backups/minimal-packsage-before-steering-notice-2026-10-17.jar`.
- Prior ATM10 JAR backed up to `.tools/test-backups/atm10-packsage-before-steering-notice-2026-10-17.jar`.

## Minecraft acceptance

Actual rendering, input, sound and toast placement were not observed in this run. Continue in the existing world in the minimal test profile or ATM10 using the [steering checks](../testing/2026-10-17-chat-steering.md), [failed-step checks](../testing/2026-10-17-chat-activity-turns.md) and [completion notice checks](../testing/2026-10-17-chat-completion-notice.md).
