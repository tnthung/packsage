# PackSage v2 final rereview 5

**Verdict: NOT READY.** One Important finding blocks this range: W12 looks up pending loot tables through the level's non-reloadable registries and consequently describes valid tables as undefined. Two Minor findings remain. No Critical finding was identified.

Reviewed on 2026-10-26: **0e894ac8c86eff0b370d617abc3d3b36ac077899..c48608d81acd56a7035bcc3851634c1d6f076370**, in the context of the previously READY v2 branch. Source inspection and the build used a detached checkout at `c48608d`. Inputs included rereview 4, `docs/plans/2026-10-26-v2-native-readiness.md`, `docs/specs/2026-10-22-v2-world-model.md`, `docs/specs/2026-10-19-coherent-search-policy.md`, the whole changed source/test range, and `GroundingInstructions.java`. This is a source/build verdict; it does not certify the independently running installed acceptance round.

## Findings

### Critical

None identified.

### Important 1 - Pending-container inspection uses the wrong loot registry

**Location:** `src/main/java/dev/packsage/world/minecraft/MinecraftInventories.java:171` (false classification at `:181`).

`unopenedLoot` passes `level.registryAccess()` to `MinecraftLoot.lookup`. In this Minecraft version, that registry access does not contain the reloadable loot-table registry. Thus an ordinary unopened chest assigned `minecraft:chests/simple_dungeon`, with a valid loaded table and a nonzero seed, is described as having "a table the loaded data packs do not define". Both inspected inventory metadata and the pending-loot explanation from `list_items` inherit this false fact. The special fallback for `minecraft:empty` masks the problem for that one table.

The authoritative registry is `server.reloadableRegistries().get()`, already used by `WorldKnowledgeTools.lootFetch` at `:225-227`. This was verified against the build's NeoForge 21.1.202 Minecraft sources: `WorldLoader.java:46-65` passes the pre-reloadable layered access into the world stem; `ReloadableServerResources.java:107-112` builds a separate full registry holder from the reload result; `MinecraftServer.java:1947-1956` exposes the two separately; `ServerLevel.java:222-225` initializes the level with `server.registryAccess()`.

Use the current server reloadable registry for this classification, preserving unknown status when it cannot be reached. Add coverage of the actual level/server registry distinction: a known nonempty table, an undefined table and `minecraft:empty`. The new tests currently either use a chest without a level or pass an already-chosen `Optional<LootTable>` directly to the description helper, so they cannot detect this integration error. No loot needs to be rolled or container slots accessed to correct it.

### Minor 1 - An oversized record after a valid prefix still drops the remaining suffix

**Locations:** `src/main/java/dev/packsage/model/PackSageTools.java:430,465-469,484-494`.

The new skip path works when an unshrinkable record is encountered before any record has been accepted. If one normal record has already been accepted, the encoder breaks at `:430` and starts retaining the tail. The tail loop then breaks at the oversized record instead of skipping it, dropping every later record from the current provider page as well.

A source-free JShell probe against this build supplied `search_recipe` with `[first, oversized, later]`, where `oversized` had the same 70,000-character canonical handle used by the added regression, and a provider cursor of `provider-next`. The first response delivered only `first` with an `output:` cursor. Replaying it delivered no rows, reported **2 records omitted**, and advanced to `provider-next`; the small valid `later` record was never delivered. The new first-record regression does not cover this ordering.

Skip an unencodable tail entry with a counted omission and retain subsequent encodable rows, subject to the existing retained-byte limit. The provider continuation and byte bound survive this case, and ordinary native identities do not approach this size, so this remains a nonblocking edge case rather than an Important finding.

### Minor 2 - Statistics key acquisition is still an unsliced full-map copy

**Locations:** `src/main/java/dev/packsage/world/minecraft/MinecraftStatistics.java:192-194,201-210`.

The initial step and each growth restart run `counted.keySet().toArray()` under the counter lock. This correctly avoids retaining an engine iterator, but the whole key set/backing-map traversal occurs before the step returns. A sufficiently large statistics map can therefore hold the server thread beyond its approximately 5 ms tick budget; `ServerWork` can check time only between steps. The 1,024-entry limit applies to subsequent processing, not to acquisition.

The batch regression counts `getInt` calls and does not bound or measure the full key copy. Treat this as a remaining performance limitation, comparable to the previously accepted owned advancement-reference copy, rather than claiming a strict per-step time guarantee. No installed tick-latency measurement was performed in this review. Both supporting reviews independently identified this same Minor finding.

## Prior findings and reviewed behavior

- **Rereview 4 Minor 1, composite continuation: addressed.** `ListingRead` retains the current reader and its exact acquisition position through work-budget stops, then pages the completed copied snapshot. Single-use tokens are consumed under the cursor lock and checked against the tool, normalized filters, scope and server instance. The forced-budget regression reaches all three readers and all nine records exactly once; the snapshot-change and world-change cases pass. The owned statistics keys replace the retained live iterator, subject to Minor 2 above.
- **Rereview 4 Minor 2, unshrinkable first record: addressed for the reported ordering.** The added first-record regression preserves later rows and the provider continuation, with counted omissions and tool-neutral advice. Minor 1 above records the remaining suffix ordering.
- **Rereview 4 Minor 3, failed output delivery: addressed.** Delivery observation failure removes the newly created output tail and accounts for its retained bytes. The failed-replay regression passes. Shared output still checks the final serialized UTF-8 response against 131,072 bytes; no new byte-bound regression was found.
- **Rereview 4 Minor 4, rejected server job: addressed.** Rejection removes and accounts for the submitted job when it still owns that queue entry. If the server has already taken it, the existing server-side completion/drain path owns accounting. The close/submission checks remain ordered after queue insertion.
- **Track A:** planning indexes output identities before projecting selected recipe records, including brewing. The 65,536-entry index bounds and 512-copy bound report concrete reasons when hit. Late-output, late intermediate-source lookup, brewing identity, copy-cache and cap regressions pass. Computed-output recipes qualify a requested output with no indexed recipe; they no longer add a blanket qualification to an unrelated indexed output. Existing native record limitations, including unknown consumption, catalysts and remainders, remain explicit. The intermediate traversal fixture supplies known consumption; it does not prove that native extraction resolves every dependency or produces a complete material bill.
- **Tracks D/E and the entity anchor change:** no additional behavioral regression was identified in the family split and shared paging moves. Trade order, per-offer counts/prices, total offer count and the 64-offer qualification remain separate from shared item facts. The description explains `offers.entries`, `offers.itemFacts` and `itemFactsIndex`. Removing the vanished-anchor note matches resumption via `higher(anchor)` in UUID order; other concrete disappearance/unloaded-read qualifications remain.
- **Model contract:** the changed progress/statistics descriptions permit empty acquisition pages with continuation and explain single-use world-bound cursors. These agree with the prompt's useful-empty-page, exact-nextCall, unchanged-query and incomplete-record rules. No prompt change is needed to remedy the incorrect W12 fact.
- **Auditability/resources:** `request_built` logs the actual offered tool names. The built JAR contains `assets/packsage/licenses/OFL.txt` (4,469 bytes), with no `font/OFL.txt` entry.

## Verification and limits

- Created `D:/Project/packsage-review-astra5` with detached HEAD at `c48608d`.
- Ran `$env:JAVA_HOME='D:/Project/packsage/.tools/jdk/jdk-21.0.12.1'` and `./gradlew build -q` there: **exit 0**. No Gradle command ran in `D:/Project/packsage`.
- Ran `node D:/Project/packsage/build/tools/count.cjs D:/Project/packsage-review-astra5`: **99 XML suites; 1,188 tests; 1,183 passed; 5 skipped; 0 failures; 0 errors**. The skips are four existing ConversationStore symlink-dependent tests and the local-candidate-dependent GroundingInstructions test.
- `git diff --check 0e894ac c48608d -- src` passed. The whole-range check reports trailing spaces in copied Markdown answer transcripts in the validation document; those were not reformatted or treated as code defects.
- One focused, in-memory JShell probe confirmed Minor 1 using the compiled shared encoder; no source or test file was authored for it. The W12 registry mismatch was established from the pinned application and generated game sources, without accessing Minecraft.
- Removed the detached worktree and confirmed that both its registration and directory were absent. The main checkout's concurrent documentation commits were preserved. This verdict is the sole authored repository file and the sole file authorized for the review commit.
- Two read-only Luna High supporting reviews covered standards/split/ownership and specs/native planning/composite cursors. Both reported only the statistics-copy Minor; Astra independently established the Important loot finding and the remaining encoder Minor. No source edits, formatting, installation, Minecraft launch or live interaction occurred.
