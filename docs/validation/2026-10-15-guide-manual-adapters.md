# Guide and manual adapter validation

Scope: the six guide/manual sources in the [roadmap](../roadmap/2026-10-13-todo.md), following the [implementation plan](../plans/2026-10-15-guide-manual-adapters.md) and [live checklist](../testing/2026-10-15-guide-manual-adapters.md).

## Automated checks

The latest full implementation run completed 481 tests: 0 failures, 0 errors, 4 skipped. The focused guide and existing Patchouli suites passed. The portable Java 21 runtime was used for Gradle. `build` produced the full JAR, not the bootstrap subset.

Observed regressions included wrong-provider evidence dispatch, booklet fragments spending their text allowance on discarded structured fields, and source-specific compilation/fixture errors. These were corrected in separate commits. Tests cover fresh evidence reads, scope/query/source identity rejection, hidden candidate progression, overlapped text fragments and bounded source extraction.

Independent review checked shared registration, client-thread execution, reference ownership, scope checks and source continuations. GuideME and Actually Additions budget-bound continuation findings were fixed and included in the full rerun. Live testing exposed additional text-holder and exhausted-fragment issues; their fixes are also included. The shared fragment retry regression failed before the fix and passed afterward, checking changed live text on both initial and later fragment retries. Relics discovery now uses its native live item tag and validates tag generation/membership when continuing or rereading. Fixture coverage does not establish installed mod compatibility.

## Installed checks

Initial test artifact SHA-256: `CC0D52A95097DF097D0908035E4DF84710C64D188C5300D8C9AC9A8ACB1EC79A`. Build output, `dist/packsage.jar` and ATM10's installed `mods/packsage.jar` matched after copying with Minecraft closed.

Updated test artifact SHA-256: `F59F10669A895669165BCBFAB0F19458936E0DA5DC6538EB0FEEFC593146585D`. The full suite/build passed before installation; all three JAR hashes matched with Minecraft closed. Updated-artifact live checks are pending.

Only `OoO - PackSage Trial` is used for acceptance testing. Initial live requests below used the initial artifact, before the subsequent fixes; final-artifact acceptance remains pending.

| Request | Observed call/result evidence |
|---|---|
| `6b5b23e9-cf86-421c-a3a3-444591851e33` | GuideME discovered `powah:book#powah:items/index.md` and reread `Items` with a newer observation timestamp. Modonomicon hit a null text-holder exception, subsequently fixed with literal/translated holder tests. |
| `8732c05e-518b-42b8-a4cf-b024e7445180` | Actually Additions returned the Crystals and Reconstructor passage. IE explicitly reported its entries were not loaded. The model supplied an incorrect AA handle; that read was rejected. |
| `d7fd21fa-a278-4c1f-a2c8-c95dedc03fac` | A prior-turn AA reference was rejected as outside the current request scope. Relics returned empty bounded pages over repeated continuations; Artifacts incorrectly reported complete empty results. Both triggered adapter investigation. |
| `463fd3d1-cbf3-4f31-93ac-b5546c393472` | Fresh AA discovery followed by the exact returned reference succeeded; timestamps changed from `22:58:54.139944800Z` to `22:58:57.410915200Z`. GuideME continuation advanced from literal position `l:1.0:0` to terminal position `a:1.0`; rereading resolved the current first fragment again. |
| `5edf0fa2-e64d-4d46-ba66-00292e70cd47` | After opening IE's manual normally, a search for `Greetings and welcome` exhausted its extraction budget without a continuation; fix pending. AA with type `quest` returned `NOT_HANDLED`. An invented GuideME cursor returned `INVALID_CURSOR`. |

The native Engineer's Manual Introduction visibly starts with `Greetings and welcome to Immersive Engineering.` A temporary manual was created in the trial world for this comparison; cleanup is pending. Opening it initializes IE's entry metadata, while tool calls themselves do not open or reload it.
