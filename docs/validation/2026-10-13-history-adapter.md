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

At the build handoff, only the repository distribution artifact and metadata were updated; installation and live acceptance were pending. The user subsequently requested the ATM10 JAR copy, then explicitly authorized computer use for the following live checks.

## Live ATM10 acceptance

Installed `mods/packsage.jar` in `C:\Users\hung1\curseforge\minecraft\Instances\All the Mods 10 - ATM10` matches the distribution SHA-256 above. ATM10 8.2 reached its 535-mod title screen on Minecraft 1.21.1 / NeoForge 21.1.251 and loaded only `OoO - PackSage Trial`, the existing backed-up working copy described in [ATM10 validation](2026-10-13-atm10.md). The original OoO world was not opened. Checks followed the [history test instructions](../testing/2026-10-13-history-adapter.md), using GPT-6-Luna / medium, web Search off, Screenshot off and paused chat.

Each row below was the only exchange in a separate new chat. Saved files are under the instance's `packsage/OoO_-_PackSage_Trial-814a1737fe55fba5/` directory. Both retrieval requests omitted the code word and had no preceding automatic conversation history.

| Check | Prompt and observed result | Saved file |
|---|---|---|
| Seed | `PACKSAGE_HISTORY_LIVE_20261013_A: my code word is sapphire-lantern-5482. Reply only recorded.` Sent at `2026-10-13T12:04:38.177759600Z`; completed with `Recorded.` | `e7de3ad8-c420-47e1-a30c-da7ed8a4c64f-6b21aec700bc2322.jsonl` |
| Cross-session search/read | `Use search_history to find PACKSAGE_HISTORY_LIVE_20261013_A, then use read_history on the matching exchange. What was my code word? Cite the saved history.` Both tools completed with one record. Read returned the original seed session/request and send time. The visible answer gave `sapphire-lantern-5482` with a compact `[history]` citation. | `5377bd49-c46e-4c8e-a231-3d17a05a2969-a5978f2f56afaa2d.jsonl` |
| Rejoin and natural-language retrieval | After saving/quitting and rejoining the same trial world: `What code word did I tell you earlier under PACKSAGE_HISTORY_LIVE_20261013_A? Search saved chats and read the original user message. Include when I sent it and cite history.` The model selected both history tools without explicit tool names. Search returned two exchanges; read selected the original seed. The answer gave the correct code word and `October 13, 2026 at 12:04:38 UTC` with `[history]`. | `4f601783-0f08-4584-94ae-181c3f33ecaa-63ee1676e0992ea1.jsonl` |
| Missing marker | `Search saved history for the exact text PACKSAGE_HISTORY_NEVER_SAVED_6841952. Was any matching exchange found? If none was found, say no match. Do not invent a code word.` Search completed with no records and no continuation. The answer was `No match.` | `58238246-1c7f-455d-bfa1-ed326c668140-1166f4eb456cd424.jsonl` |

The saved tool results independently confirm the visible answers. Before rejoin, the evidence world-session suffix was `c7518d85-9003-4907-8b5b-2d2a7528dbe9`; afterward it was `13107313-8e95-43bd-9de7-f8f466d50fae`. Newly discovered handles returned the same persisted seed exchange under the new scope. Historical metadata remained `INACTIVE` / `UNKNOWN`, with saved-conversation provenance and no navigation target.

All four exchanges completed; no HTTP 503 outcome occurred. Send became Stop during requests and the composer cleared after sending. No movement, block interaction, item transfer, quest submission or reward claim was performed. The trial world was saved/quit and Minecraft was left at its title screen. No product code changed or additional automated suite was needed for this live acceptance pass.

These observations accept cross-session retrieval, original text/timestamps after world re-entry, compact citation rendering and honest empty results. Full process-restart persistence and cross-world isolation were not separately exercised through the live UI in this pass; disk persistence and scope/isolation edge cases retain their automated coverage above.
