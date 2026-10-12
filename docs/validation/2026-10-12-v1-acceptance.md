# PackSage v1 acceptance status

Date: 2026-10-12

Release acceptance is open. The player launched the `Packsage` instance and completed real ChatGPT text, client-tool, image and hosted-search requests with `GPT-6-Luna`. This satisfies the G1 prerequisite for full-assistant activation. Installed-pack retrieval, gameplay behavior and recovery still require the [G2/G3 player checks](../testing/2026-10-12-player-checks.md).

## Automated build checkpoint

The latest installed candidate was built at 22:17 from committed source `629a95b401d4bc264405703097afd3757208ed55`, including replay/New chat fix `1c4490b`. The coordinator exported HEAD with `git archive`, linked the portable tooling and ran the exported `scripts/build.ps1` with Minecraft closed. This excluded the deliberately uncommitted implementation-plan edit. The full build passed: 251 tests, 0 failures/errors and one Windows-symlink skip. Bootstrap compilation also passed; independent scoped reviews approved both fixes.

Current artifact: `dist/packsage.jar`, version 0.1.0, Minecraft 1.21.1, NeoForge 21.1.202. SHA-256: `B8A7692C1409F22369B9C5135AB4EAC2D3A6FA8D88983D65E40D2378EF7CAA00`. The installed `C:\Users\hung1\curseforge\minecraft\Instances\Packsage\mods\packsage.jar` has the same hash. The JAR contains 198 PackSage classes, no bundled JEI/FTB/Patchouli classes, and `Implementation-Version: 0.1.0`. The following earlier checkpoints remain historical evidence.

The coordinator ran `scripts/build.ps1` using Oracle Java 21.0.12.1, Gradle 8.11.1 and ModDevGradle 2.0.147 against source through `de47f8b`, including activation `482d136` and registered-key fix `7b6d792`. The full build succeeded: 168 tests found, 167 passed, no failures or errors, and one skipped test. `ConversationStoreTest.latestMarkerLinkCannotRedirectConversationSelectionOutsideTheRoot` could not create a Windows symbolic link; its assertion remains unverified in this environment.

That initial G2 candidate was `dist/packsage.jar`, version 0.1.0, for Minecraft 1.21.1 and NeoForge 21.1.202. SHA-256: `6BF4A403C6288526BE4B799DA82BC5F39365C2C2A66E325DDB99F1B9BCBE915D`. It contained 193 PackSage classes and no bundled JEI, FTB or Patchouli classes. Its entrypoint opened the full assistant. The build installed it as `C:\Users\hung1\curseforge\minecraft\Instances\Packsage\mods\packsage.jar`; the installed hash matched at that checkpoint.

Pinned startup inspection established that mod constructors run on a parallel loading executor before Minecraft assigns its language manager. The production entrypoint therefore registers key/reload listeners early and creates the eager assistant on the first client-thread tick. Initial reloads before activation have no evidence to invalidate; subsequent reloads delegate to the active assistant. Compilation/tests verify the wiring's build contract; a full-assistant game launch remains a G2 observation.

The profile's `logs/latest.log` from the first full-assistant launch at 19:33 records PackSage 0.1.0 alongside the seven pinned mods. It shows `packsage_fixture` loading automatically in `New World`, FTB Quests loading one chapter with three quests, JEI starting its runtime and no error or exception. At world entry, PackSage created the world's `packsage/New_World-…` conversation record and `latest` pointer; the record contains only its header. This proves startup and first-tick activation without a crash. The assistant screen, sign-in state and evidence questions have not yet been reported.

The player's first screenshot exposed vanilla `EditBox`'s 32-character default, which cut questions off at `Use installed recipes: how many `. Fix `bc531a5` raises the question limit to 1000 characters. Restoring a saved sign-in at startup (`a82dce9`) and returning a rejected restored session to signed out (`9d216d2`) followed the player's request for persistent sign-in; scoped review approved them. Build `a82dce9` (177 tests, one Windows-symlink skip, SHA-256 `92B3A3CB4819D79C98AE644A29D88259EEC99EAF1339D005083928B138E35690`) was installed for the next session. The later `1b80c27` installation included `9d216d2`.

## G2 observations

The first evidence question was observed with `a82dce9` at 19:55, signed in and with search off. The full question was accepted. The answer reported **1 torch per craft** from **1 coal OR 1 charcoal** and **1 stick**, citing the active loaded `minecraft:torch` recipe with sources `native, jei`, outcome `COMPLETED`, and screenshot and gameplay context attached. Its recipe navigation button opened JEI's crafting view of the torch recipe: coal over stick producing one torch. The game log shows no PackSage error. Restored sign-in after a restart has not yet been observed separately.

Display defects observed in the same screenshot: Markdown emphasis appears as literal `**`, and the full context JSON appears both above the conversation and within the turn. The navigation button's label shows a truncated internal identifier (`…ng/recipe-minecraft:torch-331`) instead of a readable name.

Build `1b80c27` (190 tests, one Windows-symlink skip, SHA-256 `9A447A0CC44AC5C950A36ECCC53F89AC77A5CD5BE6375A05D370090A6FF1C19F`) fixed those display defects after scoped review. The player observed it at 20:04 while signed in, with search off:

| Check | Observed result | Status |
|---|---|---|
| Fixture lantern recipe | Found `packsage:fixture_lantern`: eight coal-or-charcoal slots around one torch produce one `minecraft:lantern`, with sources `native, jei`. The answer did not name tag `packsage:fixture_inputs` because the extraction did not expose it. A later search page failed. The navigation button read `Open recipe: Fixture Lantern`. | Partial: tag id missing |
| Quest dependencies and progress | **Craft the fixture lantern** and **Check the alternate input** depend on **Gather the fixture input**. Current team progress was read as 0/1 for each task, with source `ftb_quests`. | Passed |
| Nature's Aura entry | The first attempt ended `FAILED (response error)` with no detail and no PackSage log line. The retry made about 30 tool round trips. Guide searches returned empty pages and then an `ERROR` without a message, so the answer could not find `intro/aura` or quote its marker. | Failed |
| Config value | `general.fieldCreatorRange` was reported as 24 in both the saved file and loaded configuration, labelled separately and cited to `naturesaura-common.toml`. An earlier `read_config` call in the same turn returned `ERROR` without a message. | Passed |
| Pause | The pause/resume control worked. | Passed |
| Formatting | Bold text rendered. Markdown tables and code fences still show raw pipes and backticks, and inline citation markers such as `[ftb_quests:1000000000000002]` remain verbose. | Partial |

Lifecycle and usability defects in the 20:04 session: during a request, the conversation view repeatedly jumped to the top. Navigation buttons for the new answer appeared about ten seconds before its text. No indication of reasoning or tool activity appeared, and every request was very slow. The conversation log shows repeated empty `PARTIAL` search pages with continuation cursors, each costing a full model round trip (up to 60 replay items and about 140,000 tokens for one question). Fixes `665ad1d` and `5aae90a` added activity, follow-bottom scrolling and stable completion-time navigation buttons; their live retest remains separate from automated verification.

Search fixes `73d36a6` and `745562a` added bounded paging to matches, provider/kind filtering, the Patchouli version match, error details and JEI windows spread across client ticks. The recorded installations were:

| Build | Automated tests | Installed SHA-256 |
|---|---|---|
| `73d36a6` | 229 tests, no failures, one Windows-symlink skip | `3074E925822AE991315F241F32AC9E7714A0FCA286A3EBA92A761482DE7DAA30` |
| `745562a` | 231 tests, no failures, one Windows-symlink skip | `7D1397D48D54E975DBAFAF7137D5BC3A3A7C2506DE2A7687BEE7F60ECBB758A0` |

The player retested `745562a` at 21:23 with Search on. Sign-in restored as `SIGNED_IN` after launch. Nature's Aura used four tool calls instead of about thirty, but `search_pack` for `Aura` and `GUIDE` still returned empty `PARTIAL` without a detail or Patchouli status record. The answer fell back to hosted search and exposed a GitHub source button. The lantern request ended `FAILED (incomplete tool replay)`; its underlying game-log error was `response incomplete: max_messages`. Its recipe button reported `Navigation stale: JEI target is outside the active runtime snapshot`. New chat did not refresh the screen immediately.

The fixture resource pack was not enabled in that session (`resourcePacks:[]`), so the override-marker check remains pending. Enable it through **Options → Resource Packs → move PackSage deterministic fixture resource override to the selected side → Done**, then repeat the local checks with both Automatic search and the per-send Search control off. The following continuation fixes address the reported failures; a fresh live retest remains required.

Fix `1c4490b` preserves provider failure detail and completed partial tool pairs, logs content-free incomplete-response diagnostics, and retries `max_messages` once without hosted search. Client calls are released only after a completed response validates, so abandoned calls cannot leak into the retry. Stop, New chat and scope changes still cancel it. New chat publishes the current scope and reuses already-empty conversations. History counts replayed input rather than local-only turn metadata and selects the newest whole-turn suffix; token accounting remains the documented conservative UTF-8 estimate.

Fix `629a95b` addresses the deterministic Patchouli capture defect: the pinned listener loader supplies null resource provenance, and the old `isBlank()` call dropped every entry. The actual pinned `Book`/`BookEntry` regression now preserves a visible, complete entry with unavailable resource provenance. Partial/capture/scope diagnostics survive tool aggregation. JEI navigation retains up to 16 recent target-bearing snapshots within one scope/runtime generation; empty scan windows cannot evict targets, and runtime/scope changes still make them stale. Older targets beyond that bounded retention require a fresh lookup.

The automated capture and navigation tests passed in the installed build. They do not establish live Nature's Aura lookup, fixture override text, JEI/Patchouli navigation, retry or New chat behavior. Repeat the [focused player checks](../testing/2026-10-12-player-checks.md), including New chat after resource reload. Native snapshot caching, smaller recipe payloads, recovering the lantern's tag identity and the previously deferred minor issues remain later work from the handoff. G2/G3 and release acceptance remain open.

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
