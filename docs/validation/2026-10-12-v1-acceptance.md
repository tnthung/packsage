# PackSage v1 acceptance status

Date: 2026-10-12

Release acceptance is open. No live Minecraft or authenticated ChatGPT result has been recorded. The [player checklist](../testing/2026-10-12-player-checks.md) starts with G1; production entrypoint activation and G2/G3 follow that result.

## Automated build checkpoint

The coordinator ran `scripts/build.ps1` using Oracle Java 21.0.12.1, Gradle 8.11.1 and ModDevGradle 2.0.147 against the implementation through `5db0fa2`. The full build succeeded: 157 tests found, 156 passed, no failures or errors, and one skipped test. `ConversationStoreTest.latestMarkerLinkCannotRedirectConversationSelectionOutsideTheRoot` could not create a Windows symbolic link; its assertion remains unverified in this environment.

The resulting development JAR is `dist/packsage.jar`, version 0.1.0, for Minecraft 1.21.1 and NeoForge 21.1.202. SHA-256: `65341868D5461FAC8C2F012DD4C28DF3726AF120EAE7BB8E5B1D3B9963C076B3`. It contains 193 PackSage classes and no bundled JEI, FTB or Patchouli classes. Its entrypoint still opens the G1 development probe; the authored complete assistant binding is not activated.

Later integration and review fixes require a new build before their behavior can be included in this checkpoint.

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
| Optional integrations | Compile-only dependencies, no bundled optional classes, automated unavailable/error paths. | Valid provider-absent fixture variants and live loading/isolation. |
| Reusability | Production collectors consume scoped data; fixture data is separate from mod code. | Minimal fixture and representative pack observations, plus client-only remote/LAN behavior and measured performance. |

Build and mocked tests establish only their recorded automated behavior. Live route, provider lifecycle, screenshots, pause and navigation remain pending; no universal compatibility or performance result is claimed.
