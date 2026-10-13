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

Automated behavior, packaging and installation are complete. Actual Minecraft rendering, clipboard interaction, keyboard behavior and hover appearance remain pending the linked player checks. A persistent provider outage can still fail after the single retry.
