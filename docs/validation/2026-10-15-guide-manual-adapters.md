# Guide and manual adapter validation

Scope: the six guide/manual sources in the [roadmap](../roadmap/2026-10-13-todo.md), following the [implementation plan](../plans/2026-10-15-guide-manual-adapters.md) and [live checklist](../testing/2026-10-15-guide-manual-adapters.md).

## Automated checks

The first full implementation run passed 475 tests across 47 suites: 0 failures, 0 errors, 4 skipped. The focused guide and existing Patchouli suites passed. The portable Java 21 runtime was used for Gradle. `build` produced the full JAR, not the bootstrap subset.

Observed regressions included wrong-provider evidence dispatch, booklet fragments spending their text allowance on discarded structured fields, and source-specific compilation/fixture errors. These were corrected in separate commits. Tests cover fresh evidence reads, scope/query/source identity rejection, hidden candidate progression, overlapped text fragments and bounded source extraction.

Independent review checked shared registration, client-thread execution, reference ownership, scope checks and source continuations. It subsequently found two budget-bound continuation defects in GuideME and Actually Additions; their fixes and final rerun are pending below. Fixture coverage does not establish installed mod compatibility.

## Installed checks

Initial test artifact SHA-256: `CC0D52A95097DF097D0908035E4DF84710C64D188C5300D8C9AC9A8ACB1EC79A`. Build output, `dist/packsage.jar` and ATM10's installed `mods/packsage.jar` matched after copying with Minecraft closed.

Live checks are pending. Only `OoO - PackSage Trial` is authorized for acceptance testing. Record actual search/read call-result pairs, provider versions and the final installed artifact here before marking the six roadmap entries complete.
