# Chat controls validation

Date: 2026-10-13

Source: `1a90b3206e5cd32f26abcab27b62685abae6772f`. Requirements: [chat controls](../specs/2026-10-13-chat-controls.md). Plan: [implementation](../plans/2026-10-13-chat-controls.md). Player checks: [Minecraft checks](../testing/2026-10-13-chat-controls.md).

## Behavior

Settings persist Enter/Ctrl+Enter and the per-world collapsed session count. Chat applies these preferences, centers the plus icon, provides Copy on both sides and latest-user Edit, and keeps a pending submitted question visible before the first controller refresh. Editing/resending also supports a latest failed or cancelled exchange; other-world sessions remain read-only.

The six composer controls share a compact width and wrap above the growing input. The 320×240 long-draft check establishes non-overlap. Model/effort dropdowns use the connected catalog/settings and save quick selections. Extra gameplay/image context moves to this toolbar. Token labels, the context bar and avatar inclusion indicators use the historical replay selection; the request and preview share the same injected counter and exclude the exchange being replaced.

The historical allowance is the smaller of the configured quota and a known published model context capacity. Missing metadata remains unknown. Role counts use the existing conservative serialized UTF-8 replay estimator, including message envelopes. They are not provider billing counts; fresh gameplay, screenshots, instructions, tools and output use additional context outside this historical meter.

Latest replacement verifies stored world/session/latest identity and evidence scope, then atomically rewrites the conversation. The original stays authoritative until saving settles. A barrier prevents another request from racing that save; same-session reopen waits/reloads, renamed labels match by stable world/server ID, and invalidation or another session suppresses late publication. Successful hidden saves reconcile history without replacing current context. Failed saves retain the original and their error remains visible on deferred or already-settled stale reloads, permitting retry.

HTTP 503 retries the failing HTTP input once within the same exchange. Recovery, exhaustion, genuine cancellation and continuation after a completed tool result are covered. The retry does not repeat that tool call/result, and provider-retry activity preserves successful earlier text.

## Evidence and installation

- Focused checks passed, including the final 30 request-controller tests. Independent bounded UI and high-risk persistence reviews approved the corrected paths.
- A fresh `git archive` export passed `scripts/build.ps1` (`test build`): 332 tests, 330 passed, no failures/errors, two skipped Windows symlink checks.
- JAR inspection confirmed the new context/toolbar/message-action classes and all 18 UI icons.
- `git diff --check` and staged checks passed. No formatting tools were used.
- Minecraft was closed before installation. The exported artifact, repository `dist/packsage.jar` and installed `C:\Users\hung1\curseforge\minecraft\Instances\Packsage\mods\packsage.jar` match SHA-256 `CFB9F0312D03E96D2848FEA670F1EEFB7F267876B26A485219D185DE0ECA3F98`. Repository metadata was copied from the verified build.

Automated behavior, packaging and installation are complete. The live checks below establish rendering and interaction in the installed Minecraft client. A persistent provider outage can still fail after the single retry.

## Live Minecraft checks

Computer Use exercised the installed NeoForge 1.21.1 singleplayer client on 2026-10-13, in a separate New World session titled `PackSage UI check edited A`. The window was 2560 by 1040 pixels including its title bar. Test requests used GPT-6-Luna with low effort, web search off and screenshots off.

- Enter sent and Shift+Enter inserted a newline. After choosing Ctrl+Enter, plain Enter inserted a newline and Ctrl+Enter sent. Sending cleared the composer, changed Send to Stop, and placed model activity at the pending response.
- User Copy pasted the complete question, including its trailing newline. Model Copy pasted `UI_CHECK_A`. Editing and resending changed the answer to `UI_CHECK_EDITED_A` and replaced the first exchange. Reopening chat retained the replacement. The stored test conversation contained the edited question and the subsequent B question, with no original A exchange.
- Quick model/effort selections updated the toolbar and were saved to settings. Choosing four sessions displayed four before Show more. Settings content, centered account card, clean radio circles and the scrollbar gutter rendered; dragging the single scrollbar reached the lower controls.
- With history turns set to one, the first completed pair became red and the newest pair remained green. Its role estimates, 89 and 156, summed to the displayed 245. The tooltip reported two included messages, one of one turns, configured allowance 12000 and model capacity 272000. Editing excluded the exchange being replaced from both the meter and its indicators.
- GUI scales two, three and four changed the UI size. At scale four, all six equal-width composer controls wrapped into four items on the first row and two on the second. A long unsent draft grew upward, wrapped at word boundaries and kept the toolbar above it without overlap. The constrained model popup scrolled and Escape dismissed it while leaving chat open.
- A fresh combined config/quest request returned loaded and saved `general.fieldCreatorRange = 24` and the current three-quest progress. Inline Config opened read-only recorded details showing the file/key, saved/loaded states and values, authority and provenance. Inline Quest opened `Craft the fixture lantern`; closing it returned to chat. Inline references fit within their message lines.
- The session search magnifier rendered and filtering selected the test session. Clicking Screenshot released the search focus: Backspace no longer changed the query. The pause/resume button changed its icon on the first click in both directions and showed a hover state; this check did not independently measure server tick suspension.
- The New chat plus, button labels and message spacing rendered correctly at the tested scales. The glass background remained transparent, bold text was intact at the original scale, and extra context stayed beside the composer controls.

Original preferences were restored through the UI and confirmed in the saved settings: provider-default model (GPT-6.1-Sol), medium effort, Enter, two sessions, ten history turns, 12000 history tokens, open paused and automatic web search off. GUI scale two and pause-on-lost-focus were confirmed in Minecraft options. Search is off, Screenshot is on, the composer/filter are empty and the original Nature's Aura conversation is selected. The separate test session remains available; the unsent layout draft was discarded.

No failure was observed in these live checks. Full application restart persistence, other-world read-only behavior, a quota larger than model capacity, failed-exchange editing and HTTP 503 recovery were not exercised live in this pass; their automated evidence above remains the verification for those paths. Login state was inspected without signing out, and no quest progress or config value was changed.
