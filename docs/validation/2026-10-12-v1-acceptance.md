# PackSage v1 acceptance status

Date: 2026-10-12

Release acceptance is open. The player launched Minecraft, entered a world and opened the G1 probe in the `Packsage` instance; the screenshot and instance log establish that startup. Authenticated ChatGPT results remain unverified. The [player checklist](../testing/2026-10-12-player-checks.md) starts with G1; production entrypoint activation and G2/G3 follow that result.

## Automated build checkpoint

The coordinator ran `scripts/build.ps1` using Oracle Java 21.0.12.1, Gradle 8.11.1 and ModDevGradle 2.0.147 against committed rendering fix `30f90cb`. The full build succeeded: 168 tests found, 167 passed, no failures or errors, and one skipped test. `ConversationStoreTest.latestMarkerLinkCannotRedirectConversationSelectionOutsideTheRoot` could not create a Windows symbolic link; its assertion remains unverified in this environment.

The resulting development JAR is `dist/packsage.jar`, version 0.1.0, for Minecraft 1.21.1 and NeoForge 21.1.202. SHA-256: `791E8FDEA72CB3456106F856A32B2BD3157FF24599508BB0467CD245A4594603`. It contains 193 PackSage classes and no bundled JEI, FTB or Patchouli classes. Its entrypoint still opens the G1 development probe; the authored complete assistant binding is not activated.

The scoped sign-in re-review closed all six prior P1 findings. Integration re-review closed JEI runtime-loss invalidation, the bounded current-progress read contract, streamed-answer projection and screen pause handling. JEI loss is observed on the next client tick. `read_progress` refreshes selected observed quest handles; `read_evidence` retains timestamped earlier observations. In-place quest changes do not automatically cancel a request. The full suite includes fresh-progress failure isolation across selected handles.

## G1 probe handoff

The coordinator also ran `scripts/build.ps1 -Bootstrap` against the same committed implementation: 42 tests passed, none failed or skipped. Copy `dist/packsage-bootstrap.jar` for G1. It contains 100 PackSage contract/probe classes and no optional adapter classes. Version and loader pins match the full development JAR.

Probe SHA-256: `2DCA5C24DE59C1B3996DB37CC308410CC5244B385D6168583702DD53AAF43978`.

The build copied that probe to `C:\Users\hung1\curseforge\minecraft\Instances\Packsage\mods\packsage.jar`; the installed hash matches. The player's screenshot exposed blurred status/response text. Pinned `Screen.render()` bytecode confirmed that the superclass applies the background blur: the old override invoked it again after drawing text. Fix `30f90cb` renders the inherited background once before custom drawing; compiled ordering checks cover all three affected screens. Visual confirmation after restarting Minecraft remains pending.

This replaces the earlier probe build. The [G1 checklist](../testing/2026-10-12-player-checks.md#g1-packsage-load-and-chatgpt-route) covers browser sign-in, text, a read-only fixture tool, a synthetic image, hosted search and cancellation. No real account result has been inferred from the tests.

## Specification release gates

| Gate | Recorded evidence | Remaining evidence |
|---|---|---|
| Reproducible environment | Pinned Java/build/loader versions, successful full build, prepared minimal fixture with verified mod hashes. | Player launch; representative FTB Evolution version and run. |
| ChatGPT route | Automated authentication, streaming and typed tool/replay checks. | Real sign-in, account/client eligibility, inference, installed-data tool exchange, image acceptance and recovery. |
| Hosted search | Automated request policy, typed search/citation replay and failure checks. | Hosted search and clickable sources; automatic OFF plus explicit request; failure/cancellation in the live route. |
| Native and JEI evidence | Automated normalization, alternatives, planning, opaque/error handling and concurrent scoped reads. | Fixture recipe replacement, actual viewer/runtime records and navigation. |
| FTB and Patchouli | Automated scoped quest/book extraction and navigation validation; fixture chapter identity corrected. | Current team progression, book resource override and live navigation. |
| Config bridge | Automated bounded catalog/file fallback, scope labels, malformed values and failure isolation. | Pinned catalog runtime, saved/loaded/restart behavior and remote synchronized/unknown settings. |
| Context and history | Automated capture cancellation, image omission, JSONL/media round trips, recovery and history limits. | Clean fresh screenshot, screenshot removal, restart/reopening and game context. |
| Lifecycle and UI | Automated active-request, cancellation, late callback and world-history guards. | Streamed screen rendering, close/reopen, Stop/New chat, world/reload changes and effective pause/run. |
| Optional integrations | Compile-only dependencies, no bundled optional classes, automated unavailable/error paths; prepared and hash-checked provider-absent fixture archives. | Live loading/isolation in those valid dependency variants. |
| Reusability | Production collectors consume scoped data; fixture data is separate from mod code. | Minimal fixture and representative pack observations, plus client-only remote/LAN behavior and measured performance. |

The [FTB Evolution preflight](2026-10-12-ftb-evolution.md) found no official release matching the current complete loader/adapter contract. Its real-pack gate needs a validated compatibility extension before instance preparation; the real pack and current tested pins remain unchanged.

Build and mocked tests establish only their recorded automated behavior. Live route, provider lifecycle, screenshots, pause and navigation remain pending; no universal compatibility or performance result is claimed.
