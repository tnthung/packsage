# Guide and manual adapter validation

Scope: the six guide/manual sources in the [roadmap](../roadmap/2026-10-13-todo.md), following the [implementation plan](../plans/2026-10-15-guide-manual-adapters.md) and [live checklist](../testing/2026-10-15-guide-manual-adapters.md).

## Automated checks

The final full implementation run completed 482 tests: 0 failures, 0 errors, 4 skipped. The focused guide and existing Patchouli suites passed. The portable Java 21 runtime was used for Gradle. `build` produced the full JAR, not the bootstrap subset.

Observed regressions included wrong-provider evidence dispatch, booklet fragments spending their text allowance on discarded structured fields, and source-specific compilation/fixture errors. These were corrected in separate commits. Tests cover fresh evidence reads, scope/query/source identity rejection, hidden candidate progression, overlapped text fragments and bounded source extraction.

Independent review checked shared registration, client-thread execution, reference ownership, scope checks and source continuations. GuideME and Actually Additions budget-bound continuation findings were fixed and included in the full rerun. Live testing exposed additional text-holder and exhausted-fragment issues; their fixes are also included. The shared fragment retry regression failed before the fix and passed afterward, checking changed live text on both initial and later fragment retries. Relics discovery now uses its native live item tag and validates tag generation/membership when continuing or rereading. Fixture coverage does not establish installed mod compatibility.

## Installed checks

Initial test artifact SHA-256: `CC0D52A95097DF097D0908035E4DF84710C64D188C5300D8C9AC9A8ACB1EC79A`. Build output, `dist/packsage.jar` and ATM10's installed `mods/packsage.jar` matched after copying with Minecraft closed.

Second test artifact SHA-256: `F59F10669A895669165BCBFAB0F19458936E0DA5DC6538EB0FEEFC593146585D`. The full suite/build passed before installation; all three JAR hashes matched with Minecraft closed.

Final artifact SHA-256: `30BE0F097A213A92266289BBCE4671C1EFFCA17FEEC0458C0BD4D12CBCDAE05C`. Build output, distribution and installed ATM10 JARs matched with Minecraft closed. The final changes only select/configure the Artifacts replacement reader; the final live request below retested that path and ordinary Relics.

Only `OoO - PackSage Trial` was used for acceptance testing. Initial requests below record failures and their subsequent fixes; the final acceptance summary supersedes those initial statuses.

| Request | Observed call/result evidence |
|---|---|
| `6b5b23e9-cf86-421c-a3a3-444591851e33` | GuideME discovered `powah:book#powah:items/index.md` and reread `Items` with a newer observation timestamp. Modonomicon hit a null text-holder exception, subsequently fixed with literal/translated holder tests. |
| `8732c05e-518b-42b8-a4cf-b024e7445180` | Actually Additions returned the Crystals and Reconstructor passage. IE explicitly reported its entries were not loaded. The model supplied an incorrect AA handle; that read was rejected. |
| `d7fd21fa-a278-4c1f-a2c8-c95dedc03fac` | A prior-turn AA reference was rejected as outside the current request scope. Relics returned empty bounded pages over repeated continuations; Artifacts incorrectly reported complete empty results. Both triggered adapter investigation. |
| `463fd3d1-cbf3-4f31-93ac-b5546c393472` | Fresh AA discovery followed by the exact returned reference succeeded; timestamps changed from `22:58:54.139944800Z` to `22:58:57.410915200Z`. GuideME continuation advanced from literal position `l:1.0:0` to terminal position `a:1.0`; rereading resolved the current first fragment again. |
| `5edf0fa2-e64d-4d46-ba66-00292e70cd47` | After opening IE's manual normally, a search for `Greetings and welcome` exhausted its extraction budget without a continuation; subsequently fixed. AA with type `quest` returned `NOT_HANDLED`. An invented GuideME cursor returned `INVALID_CURSOR`. |

The native Engineer's Manual Introduction visibly starts with `Greetings and welcome to Immersive Engineering.` A temporary manual was created in the trial world for comparison, then removed; hotbar slot 9 was restored. Obtaining it triggered the trial world's IE advancement. Opening it initializes IE's entry metadata, while tool calls themselves do not open or reload it.

### Updated artifact observations

| Request | Observed call/result evidence |
|---|---|
| `00dc3945-f6bd-41fa-9377-31c77d1e3217` | Modonomicon returned the Neovitae Spiritus fragment; Relics returned Leafy Mantle's camouflage description. Both exact references reread successfully. Artifacts visited 49 registered items and 1,176 ordered component pairs but found no ability components; it now explicitly reported partial coverage. |
| `403ee420-4334-4b3c-a9af-6bbd5844a6e8` | GuideME's `energy` body search reached Auroral's introduction after two continuations; fresh evidence reread returned its current first fragment. AA's `Atomic Reconstructor` search and reread passed. IE's introduction search returned continuation cursors across five bounded calls, without reaching that passage; this does not establish absence. |
| `4c44c926-d241-45ba-af93-2cced0a17ba6` | IE empty-query search returned Fluid Pipes; fresh read returned the same passage. Continuation advanced to Catwalks and its reread passed. Searching `versatile and efficient method of transporting fluids` matched Fluid Pipes. The native manual's Storage & Transport > Fluid Pipes page visibly matched that passage. |

The installed Reliquified Artifacts 1.0.8 replaces Artifacts registrations with Relics-backed item classes. Its mixins explain why the base Artifacts component reader found no abilities in this pack. The final factory reuses the live Relics definition reader only for the verified combination of that add-on and Relics 0.12.8, retaining the `artifacts` provider identity and using the native `artifacts:artifacts` tag. Independent review found no further ownership, freshness or bounds issue in that change.

### Final acceptance

This section records individual source-read acceptance. Subsequent [cross-mod player workflow checks](2026-10-15-cross-mod-workflows.md) exposed an AA budget-exhaustion signaling failure and model retrieval/grounding errors; complete player-facing workflows are tracked separately.

Request `9a038626-cbbc-4bd2-a2e8-41fbd0317160` on the final artifact discovered `artifacts:artifacts:umbrella#glider`, reread it, continued to `artifacts:artifacts:umbrella#shield`, and successfully searched the first passage's distinctive phrase. Glider observation timestamps advanced from `23:32:39.527716300Z` to `23:32:43.940393Z`; the later phrase search and reread produced new observations again. The same request rediscovered and reread Relics' Leafy Mantle camouflage description. Evidence rereads completed successfully; search pages remained partial because more documents existed.

All six requested text-search contributions passed installed search and fresh evidence-read checks: Modonomicon 1.120.4, GuideME 21.1.19, IE 12.4.2-194, Actually Additions 1.3.26, Relics 0.12.8, and Artifacts 13.2.3 with Reliquified Artifacts 1.0.8. The unmodified base Artifacts component path has source/automated coverage, but was not separately live-tested in a pack without the replacement add-on.

Coverage limits remain explicit: opaque Modonomicon components and custom GuideME AST nodes may be partial; IE must be opened normally once per resource load and preserves formatting tokens with a 16 KiB per-entry seek bound; AA preserves booklet formatting. Relics-based descriptions retain native substitution placeholders such as `%1$s` rather than inventing the player's rolled numbers. These are documentation reads, not item-instance stats. Cursors contain position/identity metadata only; each search, continuation and evidence read resolves current source objects. Runtime source reloads were not induced during these checks; automated mutation and scope tests cover those invalidation/freshness paths.
