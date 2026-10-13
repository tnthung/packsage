# History adapter validation

Spec: [model-searchable conversation history](../specs/2026-10-13-history-adapter.md). Plan: [implementation](../plans/2026-10-13-history-adapter.md).

## Implemented behavior

`search_history` searches completed saved question/answer text across sessions in the current world/server, beyond the automatic history window. `read_history` returns bounded original text for discovered handles. Results include session/request identity and the original send time when available; they are historical context with unknown gameplay authority. The model instructions require fresh evidence for current game facts. Automatic history selection and its settings are unchanged.

Retrieval excludes the exchange being replaced during edit/resend and returns no screenshots, raw gameplay snapshots, tool replay or navigation targets. Search/read limits are specified in the spec. Handles and cursors are scoped; reads revalidate saved identity and fingerprints. Malformed, oversized or incompletely scanned data reports partial coverage. World transitions clear the previous adapter before provider creation, including when that creation fails.

## Automated evidence

- Storage tests: 24 total, 20 passed and four Windows symlink skips. Cover current-world isolation, world label changes, completed-only text, pagination, oversized-record continuation, replaced/deleted sources and stale cursors.
- Five adapter tests cover historical metadata, text limits, unknown handles, changed-query cursors, replacement exclusion, deletion, late scope changes and overlapping searches.
- Runtime regression uses a fake model backend to search and read an earlier session excluded from automatic history, then dispatch an ordinary pack tool. It also verifies that a failed new-world provider transition cannot expose the previous world's history. This is an automated tool round trip, not a live model acceptance check.
- `./gradlew.bat test build --console=plain`: 373 tests, 369 passed, four skipped, no failures or errors; build successful. After the final compact citation regex change, `./gradlew.bat test --tests dev.packsage.client.ScreenTextTest build --console=plain`: all 29 ScreenText tests passed; build successful.
- Independent review of scope authorization, asynchronous publication, bounded physical reads and stale-record checks completed. The cross-world provider-failure regression failed before the adapter-clear fix and passed afterward. `git diff --check` passed; no formatting tools were used.

## Distribution and acceptance

Storage commit: `f4356cd`. Model integration commit: `74d35aa8c66c7c32aeacd2277f720848e2b72493`.

Built with Java 21.0.12.1, Gradle 8.11.1, NeoForge 21.1.202 and ModDevGradle 2.0.147 for Minecraft 1.21.1. Distribution: `dist/packsage.jar`; SHA-256: `938F3DDB5F0320F749F2586185918686B6988E1FF24B45B7A3A80B2D8CF54B60`.

Only the repository distribution artifact and metadata were updated. No computer use, running-game interaction or instance installation occurred. Live model selection/use of these tools and Minecraft acceptance remain pending at the user's direction.
