# Changelog

## Unreleased

### 2026-10-14

- Added on-demand inspection of occupied vanilla hotbar, main inventory, armor and offhand slots without requiring item IDs, preserving slot identity and copied item components.
- Added bounded storage inspection with paging and original observation timestamps: visible non-player slots from opened menus, direct synchronized contents of held vanilla containers and bundles, and targeted block identity with explicit unavailable contents. Added an internal reader boundary for future mod-specific storage support.
- Added content-free request timing traces from Send through gameplay/screenshot capture, request preparation, HTTP response headers, streamed text, tool rounds, retries, completion and persistence.
- Fixed navigation from older and restored local references by revalidating the exact source and destination against the current provider before opening, while preserving historical answers and scoped model evidence.
- Fixed clipped navigation status text by including the transcript's initial inset in scroll-height measurements.
- Added a structured [adapter roadmap](docs/roadmap/2026-10-13-todo.md) with one feature per checklist item, including shared enchantment, potion, effect and brewing readers. Documented the [shared registries and mod-owned mechanics](docs/research/2026-10-14-enchantment-potion-registries.md); these readers remain planned work.
- Updated the implementation plan's parallel ownership and commit workflow to reflect the agreed execution rules.

Recorded [ATM10 inventory/storage checks](docs/validation/2026-10-14-inventory-storage-inspection.md) and [historical quest navigation/status checks](docs/validation/2026-10-14-historical-navigation.md). Specialized mod storage remains unsupported, and opened-menu observations remain partial. The latest recorded navigation build passed 400 tests with four existing skips. [Greeting timing measurements](docs/validation/2026-10-14-request-timing.md) separate local preparation from HTTP/stream waiting without promising a latency bound.

### 2026-10-13

- Enabled explicit parallel model tool calls and configurable reasoning effort. Fixed follow-up history selection and encoded historical answers as completed assistant output, correcting rejected second-turn requests.
- Replaced the native chat/settings presentation with a compact custom UI, bundled fonts/icons, transparent surfaces, world/server-grouped searchable sessions, and read-only browsing of sessions from other worlds.
- Added a growing multiline composer with equal-width wrapping controls, quick model/effort selectors, Search and Screenshot toggles, and Send changing to Stop during an active request.
- Added provider-aware account controls, a Settings Back button, working draggable scrollbars, configurable Enter/Ctrl+Enter sending and configurable visible session counts.
- Added Copy actions for user/model messages, editing and resending the latest question with persisted replacement of its exchange, and guarded session deletion.
- Added message send times, token estimates, history-inclusion markers, a model-aware history context meter and readable gameplay-context previews. Corrected history estimates to tokenize message text rather than serialized replay wrappers.
- Added bounded model search/read tools for completed saved conversations in the active world/server, with scoped handles, cursors and stale-record checks.
- Added a sidebar adapter-status indicator with one row per logical adapter family, reporting availability separately from extraction coverage.
- Added bounded transient HTTP 503 retries that preserve completed tool exchanges and honor cancellation, plus model context-limit metadata.
- Added inline validated source references and read-only config-source details. Fixed word-boundary wrapping, Markdown quotes, input clearing, focus release, pause controls, settings rendering and source-button line spacing.
- Fixed native background blur over the custom UI, fractured bold text, vertical/horizontal alignment, oversized icons/tooltips and clipped avatars. Replaced rectangular edge strips with rounded alpha-mask sprites and distinct enabled-hover states.
- Broadened NeoForge, JEI, FTB Quests, Patchouli and config-bridge compatibility within bounded version lines for real-pack testing, retaining observed runtime versions and unsupported-version fallbacks.
- Improved literal search, pagination and provider diagnostics. Retained individually observed loaded config values from partial catalogs while keeping missing or incomplete values unknown; added focused navigable JEI recipe references and additional local citation formats.
- Distinguished missed targets and camera perspective in gameplay context. Compactly encoded held-item components without expanding registry objects, and placed the exact current question after separately labeled gameplay background.
- Corrected Patchouli page navigation to use the guide entry's recorded source.

Recorded focused [FTB Evolution](docs/validation/2026-10-13-ftb-evolution.md) and [ATM10](docs/validation/2026-10-13-atm10.md) trials, including quest progress, config-source details, JEI/Patchouli navigation, chat controls and [saved-history retrieval](docs/validation/2026-10-13-history-adapter.md). These checks establish the tested behavior and versions; full provider-absence, reload and multiplayer/LAN release acceptance remains separate. Greeting-only behavior in an existing history-rich session remained an open acceptance item.

### 2026-10-12

- Added the Minecraft 1.21.1 / NeoForge 21.1.202 client bootstrap, portable Java 21 build script and deterministic test fixture.
- Added direct ChatGPT sign-in, model selection, streamed replies, read-only tool exchanges, hosted search and a development probe screen.
- Added native recipe, tag, resource and inventory evidence, plus optional JEI, FTB Quests, config and Patchouli adapters with scoped sources and explicit coverage states.
- Added local JSONL conversations and screenshot assets, whole-turn history limits, cancellation and recovery from interrupted writes.
- Added assistant and settings screens, fresh gameplay context, removable screenshots, pause controls and validated source navigation.
- Added bounded tool dispatch, grounding instructions and client lifecycle wiring. Activated the full assistant after successful live sign-in/inference/tool checks, with initialization deferred until the client thread is ready; retained a separate bootstrap probe build.
- Fixed stale account, world, history and tool callbacks; typed replay ordering; provider extraction, pagination and visibility handling; and the fixture's FTB chapter identity.
- Added valid provider-absent fixture archives and mocked multi-tool conversation coverage; refreshed quest progress at read time with isolated failures, exposed streamed text in the screen, and corrected vanilla pause-menu usage.
- Added bounded sign-out discovery/revocation retries, repeated sign-out outcome handling, nullable hosted-search replay and clickable probe citations.
- Fixed duplicate background rendering that blurred custom text in the probe, assistant and settings screens.
- Fixed sign-in browser launching in Minecraft's headless Java runtime by using the native Minecraft URL launcher.
- Fixed Windows fixture ZIP paths and bootstrap source filtering with Gradle's configuration cache. Installed the deterministic fixture data into the player's test profile.
- Raised the assistant question limit, restored saved ChatGPT sign-in at startup and returned rejected restored sessions to signed out.
- Added readable answer/context rendering, compact navigation labels, request activity and follow-bottom streaming; kept new navigation buttons stable until completion.
- Fixed search paging to advance to matching records, added provider/kind filtering and useful tool failure details, and spread JEI scan windows across client ticks.
- Fixed incomplete-response recovery, immediate New chat updates and whole-turn history budgets without retaining abandoned tool calls.
- Restored Patchouli entries when resource provenance is unavailable and retained recent JEI targets within their active runtime scope.

Live ChatGPT text, fixture-tool, image-input and hosted-search checks passed with GPT-6-Luna. Initial full-assistant fixture checks observed recipes, quest progress, saved/loaded config values and pause behavior; guide, navigation and lifecycle defects prompted the fixes above. Full release acceptance remained open at this checkpoint.
