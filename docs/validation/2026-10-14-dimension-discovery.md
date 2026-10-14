# Dimension discovery validation

The `dimension` and `dimension_type` selectors are implemented and passed automated validation plus bounded live checks in ATM10 / `OoO - PackSage Trial`. Both selectors resolve current sources on every call; references retain identity rather than dimension definitions. This report does not establish installed RFTools Dimensions compatibility or remote-server behavior.

Contract: [specification](../specs/2026-10-14-behavior-driven-tools.md). Execution: [plan](../plans/2026-10-14-dimension-discovery.md). Repeatable checks: [player test instructions](../testing/2026-10-14-dimension-discovery.md).

## Artifact and automated checks

- Code through `960db1d`; Minecraft 1.21.1, compile target NeoForge 21.1.202, installed ATM10 8.2 with NeoForge 21.1.251.
- Portable JDK 21.0.12.1: `gradlew.bat test build --console=plain` succeeded. XML totals: 446 tests, 442 passed, 4 skipped, zero failures/errors. The four existing filesystem-link test skips remain.
- Focused suite: 6 dimension-reader, 3 registry-reader, 10 native-provider and 1 selector-schema regression passed.
- Tests cover changing client-known dimension membership, exact-ID disappearance, empty partial client knowledge, filtering/paging, bounded candidate work, the continuation seek cap, type environmental fields/tags and partial server records with unknown types. These fixtures do not instantiate a dynamic mod server or exercise a real disconnect.
- The selector-schema test was observed failing before schema implementation. Initial native tests encountered fixture compilation errors; those errors were corrected, but are not evidence of an assertion-red phase.
- Independent Luna review found an unusable continuation beyond the 2,048-entry seek bound. Regression `621298f` and fix `960db1d` cap traversal, retain partial coverage and omit that cursor with a narrower-query explanation. Final review found no actionable issues. Scope invalidation and the private integrated-owner policy were source-reviewed; production disconnect uses a different non-null runtime scope.
- Build, `dist/packsage.jar` and the ATM10 installed `mods/packsage.jar` matched SHA-256 `7208C07650A9019688CDAAF5576C8D6276A11F855D85475F2D7F2214AD906ADB`. Installation occurred while Minecraft was closed. No other profile was updated.

## Live evidence

Computer-use checks used the existing GPT-6-Luna / medium settings. The trial world loaded from the launcher with the new JAR. No worlds were created or entered by search, and no inventory items were moved. The game was returned to the normal scene afterward.

Two completed requests made eight tool calls. Results were paired by `callId` in the saved replay, independently of the generated answer. Replay file under the ATM10 instance:

`packsage/OoO_-_PackSage_Trial-814a1737fe55fba5/122084a3-d2dd-4f5f-b244-82ec6a7486b0-5fade726211bb80c.jsonl`

### DIMENSION_LIVE_A

Request `ef8080cb-1b33-4e30-aff9-ca28fa3b1bfd`, 2026-10-14 22:50 Asia/Taipei.

| Call | Arguments | Observed result |
|---|---|---|
| `call_qsJVz9BVe51vrx867ktHnWx9` | `search_registry`, dimension, no filter, limit 2 | Overworld and Bumblezone; PARTIAL result with continuation; each record COMPLETE / SERVER_READ. |
| `call_DtEQG390bIA4y5RxSSRiNelA` | Same query with returned cursor | `ad_astra:venus` and Nether; PARTIAL with another continuation; no duplicate from page one. |
| `call_PJ06lQcgt96NBAJkxZ4u0iP6` | dimension, native provider, IDs Overworld / Nether / `compactmachines:compact_world`, limit 16 | Exactly three requested IDs, COMPLETE / SERVER_READ, each linked to its corresponding type ID. |
| `call_s8qySQsSn9iSZmdf0s5oDuYo` | `read_evidence`, `native:dimension:compactmachines:compact_world` | COMPLETE / SERVER_READ; observedAt advanced from `14:50:23.200334200Z` to `14:50:27.297162800Z`. |

The generated answer accurately described the results, but included one unsupported citation marker rendered as stray text. The raw tool evidence was correct; this existing answer-presentation path was not changed by this extension.

### DIMENSION_LIVE_B

Request `ff67b1a0-28f4-47cf-b486-43628ff287d4`, 2026-10-14 22:51 Asia/Taipei.

| Call | Arguments | Observed result |
|---|---|---|
| `call_8JU7Fp93uRP0K2Vv1f2pulid` | dimension_type, native provider, same three IDs, limit 16 | Exactly three definitions, COMPLETE / LOADED, with current environmental fields and exposed tags. |
| `call_GxdkbItUEqcnfJaJTvWvQCJY` | `read_evidence`, `native:dimension_type:compactmachines:compact_world` | Same current definition, COMPLETE / LOADED; observedAt advanced from `14:51:17.369249800Z` to `14:51:21.313638Z`. |
| `call_uH5DNvyyJ0EvYI4qRlqVUniC` | dimension, same IDs AND text `COMPACT`, native provider, limit 16 | Only `compactmachines:compact_world`, COMPLETE / SERVER_READ; case-insensitive text and ID intersection worked. |
| `call_V6SJfkxWvc9oAC6BU07txIVl` | dimension, tags `[minecraft:overworld]`, native provider, limit 16 | ERROR with `UNSUPPORTED_FILTER: dimension does not support tag membership filters`; no records or silent filter removal. |

Observed type fields:

| Type | minY | height | logicalHeight | coordinateScale | bedWorks | respawnAnchorWorks |
|---|---:|---:|---:|---:|---|---|
| `minecraft:overworld` | -64 | 384 | 384 | 1 | true | false |
| `minecraft:the_nether` | 0 | 256 | 128 | 8 | false | true |
| `compactmachines:compact_world` | 0 | 48 | 48 | 1 | false | false |

## Acceptance limits

- The runtime selectors, native and modded IDs, page continuation, combined filters, fresh references, authority labels and explicit dimension-tag rejection passed live.
- Client-only empty/partial behavior and changing membership passed automated fixtures. Remote/published-session fallback and real dynamic world creation/removal remain untested live.
- ATM10 has RFTools Base, Builder, Power, Storage and Utility, but no RFTools Dimensions JAR. Discovering RFTools-created runtime worlds follows the generic current server-level path; installed compatibility is not claimed. Uncreated dimension designs and saved-but-uninstantiated worlds are outside this selector.
- A changing collection can change positional pagination between calls; no retained snapshot guarantees a stable multi-page listing. Exact-ID searches and reference reads resolve current membership.
