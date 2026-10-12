# PackSage v1 acceptance status

Date: 2026-10-12

Release acceptance is open. The player launched the `Packsage` instance and completed real ChatGPT text, client-tool, image and hosted-search requests with `GPT-6-Luna`. This satisfies the G1 prerequisite for full-assistant activation. Installed-pack retrieval, gameplay behavior and recovery still require the [G2/G3 player checks](../testing/2026-10-12-player-checks.md).

## Automated build checkpoint

The coordinator ran `scripts/build.ps1` using Oracle Java 21.0.12.1, Gradle 8.11.1 and ModDevGradle 2.0.147 against source through `de47f8b`, including activation `482d136` and registered-key fix `7b6d792`. The full build succeeded: 168 tests found, 167 passed, no failures or errors, and one skipped test. `ConversationStoreTest.latestMarkerLinkCannotRedirectConversationSelectionOutsideTheRoot` could not create a Windows symbolic link; its assertion remains unverified in this environment.

The resulting G2 candidate is `dist/packsage.jar`, version 0.1.0, for Minecraft 1.21.1 and NeoForge 21.1.202. SHA-256: `6BF4A403C6288526BE4B799DA82BC5F39365C2C2A66E325DDB99F1B9BCBE915D`. It contains 193 PackSage classes and no bundled JEI, FTB or Patchouli classes. Its entrypoint opens the full assistant. The build installed it as `C:\Users\hung1\curseforge\minecraft\Instances\Packsage\mods\packsage.jar`; the installed hash matches.

Pinned startup inspection established that mod constructors run on a parallel loading executor before Minecraft assigns its language manager. The production entrypoint therefore registers key/reload listeners early and creates the eager assistant on the first client-thread tick. Initial reloads before activation have no evidence to invalidate; subsequent reloads delegate to the active assistant. Compilation/tests verify the wiring's build contract; a full-assistant game launch remains a G2 observation.

The profile's `logs/latest.log` from the first full-assistant launch at 19:33 records PackSage 0.1.0 alongside the seven pinned mods. It shows `packsage_fixture` loading automatically in `New World`, FTB Quests loading one chapter with three quests, JEI starting its runtime and no error or exception. At world entry, PackSage created the world's `packsage/New_World-…` conversation record and `latest` pointer; the record contains only its header. This proves startup and first-tick activation without a crash. The assistant screen, sign-in state and evidence questions have not yet been reported.

The player's first screenshot exposed vanilla `EditBox`'s 32-character default, which cut questions off at `Use installed recipes: how many `. Fix `bc531a5` raises the question limit to 1000 characters. Restoring a saved sign-in at startup (`a82dce9`) and returning a rejected restored session to signed out (`9d216d2`) followed the player's request for persistent sign-in; scoped review approved them. Build `a82dce9` (177 tests, one Windows-symlink skip, SHA-256 `92B3A3CB4819D79C98AE644A29D88259EEC99EAF1339D005083928B138E35690`) was installed for the next session. `9d216d2` is not yet installed.

## G2 observations

The first evidence question was observed with `a82dce9` at 19:55, signed in and with search off. The full question was accepted. The answer reported **1 torch per craft** from **1 coal OR 1 charcoal** and **1 stick**, citing the active loaded `minecraft:torch` recipe with sources `native, jei`, outcome `COMPLETED`, and screenshot and gameplay context attached. Its recipe navigation button opened JEI's crafting view of the torch recipe: coal over stick producing one torch. The game log shows no PackSage error. Restored sign-in after a restart has not yet been observed separately.

Display defects observed in the same screenshot: Markdown emphasis appears as literal `**`, and the full context JSON appears both above the conversation and within the turn. The navigation button's label shows a truncated internal identifier (`…ng/recipe-minecraft:torch-331`) instead of a readable name.

The existing `New World` now contains the four authored datapack files. The profile contains the fixture quest chapter/translations and a resource ZIP whose metadata and book entry use forward-slash paths. The complete fixture and three provider-absent import ZIPs were regenerated and checked for portable paths. All seven active profile mod hashes match the pinned manifest after retaining Patchouli 93 as a disabled backup and installing supported 92. These are filesystem checks, not observed in-game recipe, quest or book results.

The scoped sign-in re-review closed all six prior P1 findings. Integration re-review closed JEI runtime-loss invalidation, the bounded current-progress read contract, streamed-answer projection and screen pause handling. JEI loss is observed on the next client tick. `read_progress` refreshes selected observed quest handles; `read_evidence` retains timestamped earlier observations. In-place quest changes do not automatically cancel a request. The full suite includes fresh-progress failure isolation across selected handles.

## G1 probe handoff

The coordinator reran `scripts/build.ps1 -Bootstrap` against the separate bootstrap entrypoint and Gradle-cache fix `de47f8b`: all 42 focused tests passed, none failed or skipped. Its probe code includes browser-launch fix `8de090e`. `dist/packsage-bootstrap.jar` remains available for isolated G1 diagnosis and contains 100 PackSage contract/probe classes with no optional adapter classes. Version and loader pins match the full candidate. The full build ran afterward and replaced the installed probe with the assistant.

Probe SHA-256: `3EFA55121085E3672DF4A1DAADFAEF46ADE257280C8495DB723AFA67FF341D90`.

The build copied that probe to `C:\Users\hung1\curseforge\minecraft\Instances\Packsage\mods\packsage.jar`; the installed hash matches. The player's first screenshot exposed blurred status/response text. Fix `30f90cb` renders the inherited background once before custom drawing; compiled ordering checks cover all three affected screens, and the next player screenshot confirms readable text.

The following screenshot reported `FAILED: system browser is unavailable`. Pinned Minecraft startup code sets `java.awt.headless=true`, making the old AWT `Desktop` check reject browser opening. Fix `8de090e` uses Minecraft's native platform URL launcher without changing the OAuth flow. Four subsequent player screenshots show the authenticated model catalog and completed text, read-only fixture tool, synthetic-image and hosted-search requests; the [route validation](2026-10-12-chatgpt-route.md#live-gate) records their exact limits.

This replaces the earlier probe build. G1 sign-in/inference/tool exchange passed through the live route; image input and hosted search were also accepted. Cancellation, refresh/revocation and clicking the citation remain unobserved. These results came from player screenshots, not automated tests.

## Specification release gates

| Gate | Recorded evidence | Remaining evidence |
|---|---|---|
| Reproducible environment | Pinned Java/build/loader versions, successful full build, prepared minimal fixture with verified mod hashes; player launch and world entry. | Representative pack compatibility and run. |
| ChatGPT route | Automated authentication, streaming and typed replay checks; real sign-in/catalog, text, fixture-tool exchange and image acceptance with GPT-6-Luna. | Installed-data tool exchange, visible streaming and recovery/refresh/revocation. |
| Hosted search | Automated policy/replay/failure checks; real completed hosted search with URL citation and source button. | Clicking sources; automatic OFF plus explicit request; failure/cancellation in the live route. |
| Native and JEI evidence | Automated normalization, alternatives, planning, opaque/error handling and concurrent scoped reads. | Fixture recipe replacement, actual viewer/runtime records and navigation. |
| FTB and Patchouli | Automated scoped quest/book extraction and navigation validation; fixture chapter identity corrected. | Current team progression, book resource override and live navigation. |
| Config bridge | Automated bounded catalog/file fallback, scope labels, malformed values and failure isolation. | Pinned catalog runtime, saved/loaded/restart behavior and remote synchronized/unknown settings. |
| Context and history | Automated capture cancellation, image omission, JSONL/media round trips, recovery and history limits. | Clean fresh screenshot, screenshot removal, restart/reopening and game context. |
| Lifecycle and UI | Automated active-request, cancellation, late callback and world-history guards. | Streamed screen rendering, close/reopen, Stop/New chat, world/reload changes and effective pause/run. |
| Optional integrations | Compile-only dependencies, no bundled optional classes, automated unavailable/error paths; prepared and hash-checked provider-absent fixture archives. | Live loading/isolation in those valid dependency variants. |
| Reusability | Production collectors consume scoped data; fixture data is separate from mod code. | Minimal fixture and representative pack observations, plus client-only remote/LAN behavior and measured performance. |

The [FTB Evolution preflight](2026-10-12-ftb-evolution.md) found no official release matching the current complete loader/adapter contract. Its real-pack gate needs a validated compatibility extension before instance preparation; the real pack and current tested pins remain unchanged.

Build and mocked tests establish only their recorded automated behavior. G1 establishes the observed live route results above. Full-assistant provider lifecycle, screenshots, pause and navigation remain pending; no universal compatibility or performance result is claimed.
