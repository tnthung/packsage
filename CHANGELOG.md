# Changelog

## Unreleased

### 2026-10-15

- Verified natural-player crafting across both carried bundles, a shulker and JEI, including a changed-bundle follow-up with fresh reads. Guide citations render correctly. The broader Powah/AA/quest workflow still exposes model continuation errors; [the correction report](docs/validation/2026-10-15-cross-mod-workflow-fixes.md) separates passing source checks from incomplete answers.
- Native inventory searches now report unsupported-only sections as unavailable and mixed supported/unsupported sections as partial, explicitly stating which sections were not inspected instead of returning a misleading complete empty result.
- Added a regression for unsupported vanilla inventory sections: unknown accessory slots must not appear as an inspected, completely empty inventory.
- Added deterministic totals for returned recipe inputs only when every slot is one known consumed item, preserving exact component identity, raw slots and coverage. Ambiguous or unsupported inputs omit the tally; inventory guidance now explicitly includes multiple matching carried containers.
- Added a serialization regression for exact repeated input totals after a natural answer miscounted the correctly returned crafting slots.
- Fixed later-page booklet seeks that mistook exhausted shared budgets for stale cursors; resumable extraction failures retain the current fragment, while genuinely out-of-range pages still reject the cursor.
- Added a regression distinguishing budget exhaustion while seeking a later booklet page from a genuinely stale page position.
- Replaced generic descriptions on document, recipe, inventory and evidence/progress tools with concise operation-specific guidance, keeping public schemas unchanged and reinforcing live reads, valid handles and continuation rules at tool selection time.
- Rendered citations from all six new guide/manual providers as compact source tags, including grouped markers; unknown providers stay literal and non-navigable records do not gain invented links.
- Added a regression for raw grouped citation markup from the six guide/manual adapters, including unknown-provider fallback and non-navigable evidence.
- Tightened equipment and behavior wording after a natural-player retest: unobserved accessory slots remain unknown, and comparisons must label inferred outcomes rather than treating one documented effect as proof of another.
- Made Relics and verified Reliquified Artifacts ability documents searchable by their current localized item names, retaining bounded titles and stable document references on search and reread.
- Clarified prompt recovery order: continue partial searches before changing their query, simplify complete misses, and inspect each relevant carried container requested by the player.
- Fixed Patchouli page-reserve exhaustion signaling and preserved AA reader failures across reflection, allowing bounded booklet searches to resume rather than terminate when the shared byte budget runs low.
- Added booklet regressions for textless-page continuation and a depleted shared byte budget; the latter checks that exhaustion is resumable and the underlying read failure survives reflection.
- Refined the system prompt for literal-phrase search recovery, unchanged-query pagination, cursor/reference separation, carried-container inspection and fresh recipe/inventory follow-ups. Added explicit per-slot ingredient counting and limits on behavior/accessory claims; ordinary-player live revalidation remains required.
- Added live guide/manual text discovery and evidence reads for Modonomicon, GuideME, Immersive Engineering, Actually Additions, Relics and Artifacts, with provider-owned version checks, localized text, bounded fragments and metadata-only continuations.
- Supported the verified Reliquified Artifacts replacement path while retaining Artifacts source identity. Ability documentation remains distinct from equipped item rolls and active effects.
- Fixed guide continuation at extraction budgets, live membership/reload invalidation, Modonomicon text-holder handling and GuideME no-progress boundaries. IE requires normal manual initialization and reports bounded extraction explicitly.
- Verified dimension discovery after a real dimension transition, including stale-handle rejection and fresh dimension-type reads. Reconciled inventory roadmap completion against recorded live checks.
- Recorded [cross-mod player workflows](docs/validation/2026-10-15-cross-mod-workflows.md): held-bundle ingredients and current inventory can be combined with JEI recipes, but ordinary questions exposed guide retrieval, recipe-counting and AA traversal failures. A regression isolated the latter to byte-reserve exhaustion, not textless pages. Individual source acceptance does not establish complete player-facing workflow acceptance.

The [six-source guide validation](docs/validation/2026-10-15-guide-manual-adapters.md) records 482 automated tests with four skips and installed source checks. Cross-mod failures remain tracked separately; physical power transfer, crafting and fall damage were not tested by those assistant workflows.

### 2026-10-14

- Migrated the public tools to the shared `Toolbox` adapter contract: adapters own matching, only `NotHandled` advances dispatch, and every tool query resolves current sources. Added behavior-based document, recipe, registry, inventory, evidence, progress, planning, config and history operations.
- Added bounded live registry discovery for effects, enchantments, potions, runtime dimensions and dimension types, including private integrated-server authorization and explicit client-only coverage limits.
- Added live targeted/ridden block and entity inventory access where authorized, horse-menu support and owner/scope revalidation. Verified chest, double-chest, bundle, shulker, minecart and donkey cases; documented modded boat access limits.
- Corrected opened-menu completeness and GUI slot indices, bounded quest/page extraction, fresh JEI recipe membership checks and opaque recipe coverage. Cursor continuations retain identity/position metadata rather than saved result bodies.
- Reworked inventory access behind a shared reader contract and added bounded `search_inventory` for item names, IDs, synchronized tags and tooltip text in vanilla player inventory/equipment.
- Replaced hand-specific storage targeting with scoped item-slot references. Added empty mainhand/offhand location metadata, fresh stack/component revalidation and direct vanilla container/bundle reads at arbitrary observed player slots. General inventory metadata omits nested storage contents.
- Standardized storage continuation on opaque `nextCursor` values and removed the misleading internal offset from model output. Added serializer-budget and stale-slot regressions; corrected compilation and tool-count expectations in separate follow-up commits.
- Fixed JEI Recipe citations by retaining bounded exact native registrations and freshly revalidating their current category, recipe, visibility and scope before opening. Links remain usable after later chat requests within the active runtime; cross-restart recipe recovery remains unsupported.
- Added on-demand inspection of occupied vanilla hotbar, main inventory, armor and offhand slots without requiring item IDs, preserving slot identity and copied item components.
- Added bounded storage inspection with paging and original observation timestamps: visible non-player slots from opened menus, direct synchronized contents of held vanilla containers and bundles, and targeted block identity with explicit unavailable contents. Added an internal reader boundary for future mod-specific storage support.
- Added content-free request timing traces from Send through gameplay/screenshot capture, request preparation, HTTP response headers, streamed text, tool rounds, retries, completion and persistence.
- Fixed navigation from older and restored local references by revalidating the exact source and destination against the current provider before opening, while preserving historical answers and scoped model evidence.
- Fixed clipped navigation status text by including the transcript's initial inset in scroll-height measurements.
- Added a structured [adapter roadmap](docs/roadmap/2026-10-13-todo.md) with one feature per checklist item, including shared enchantment, potion, effect and brewing readers. Documented the [shared registries and mod-owned mechanics](docs/research/2026-10-14-enchantment-potion-registries.md); later registry discovery is delivered, while specialized mechanics retain their roadmap status.
- Updated the implementation plan's parallel ownership and commit workflow to reflect the agreed execution rules.

Recorded [ATM10 inventory/storage checks](docs/validation/2026-10-14-inventory-storage-inspection.md) and [historical quest navigation/status checks](docs/validation/2026-10-14-historical-navigation.md). Specialized mod storage remains unsupported, and opened-menu observations remain partial. The latest recorded navigation build passed 400 tests with four existing skips. [Greeting timing measurements](docs/validation/2026-10-14-request-timing.md) separate local preparation from HTTP/stream waiting without promising a latency bound.

The [capability reader alignment checks](docs/validation/2026-10-14-capability-reader-alignment.md) record 411 passing automated tests and four skips, plus live inventory/search/slot-storage observations in the minimal fixture, FTB Evolution and ATM10. The [JEI follow-up](docs/validation/2026-10-14-jei-navigation-refresh.md) also passed actual recipe-page opening in all three profiles, including an older link after a subsequent fixture request. Mod storage/network support remains planned; existing quest/config extraction limits remain explicit.

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
