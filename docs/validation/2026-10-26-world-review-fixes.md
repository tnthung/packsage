# World final-review fixes

Date: 2026-10-26. Worktree: `D:/Project/packsage-fix-world`, branch `fix/v2-world`, base `4ca3176`.

All four findings have automated regression coverage and fixes. No installed JAR was copied and no Minecraft process or live world was accessed. Installed acceptance and actual tick/render timing remain unverified.

## Findings and regression evidence

1. **No-load POI facts: fixed.** `MinecraftWorld.poi` uses the cached `SectionStorage.get` exposed by an access transformer. An uncached section returns an explicit unavailable POI status; a cached empty section supplies no POI. Type and free tickets come directly from an existing `PoiSection`. The regression first failed through vanilla's loading path. Its strengthened storage sentinel observes zero reads for PackSage and rejects an explicit vanilla `getType` call, proving that the sentinel catches storage access.
2. **Bounded enumeration: fixed.** Entity listings retain the loaded-entity iterator and visit at most 64 entities per step, including nonmatches. Holder gathering also retains traversal state and performs at most 64 block/entity inventory checks per step. Continuations keep the entity ordinal, stop between block chunks, and do not repeat gathering for search-only resumes. Statistics retain a counter iterator under its lock, visit at most 1,024 entries per step, and bound named-zero traversal. A structural counter-size change fails that adapter read rather than presenting incomplete enumeration as complete. Server-side sorts were removed; `StatisticListing` already sorts plain copied rows on the worker.
   - Dense single-chunk tests reproduced 4,000 entity visits in one old step for both listing and holder gathering. Both now span more than 60 bounded steps.
   - A tracked counter map reproduced copying the entire map in the old first step. Every new step stays within 1,024 visits.
   - A 130-entity listing completes across three pages without missing or repeating a match. A follow-up search-only continuation regression reproduced and fixed a null-level access introduced during this work.
3. **Entity coverage and output size: fixed.** `EntityInspector` returns `Section`; registry merging preserves its coverage and note. `MinecraftEntities` reports PARTIAL for capped trade offers or attribute modifiers, with explicit notes. End-to-end `inspect_entity` regressions with 65 offers and 65 modifiers each first returned COMPLETE, then passed with PARTIAL record and result coverage. A 64-offer villager with 3,000-character item names reproduced an oversized COMPLETE record. Worker-side inspection serialization now replaces details exceeding the 64 KiB budget with a compact PARTIAL identity/location record and an omission note, preserving the same source and destination.
4. **Asynchronous navigation: fixed.** Server lookups return futures completed by queued server tasks, with an asynchronous one-second deadline. Navigation refresh retains resolved plain data; client opening uses `getNow`, never waits for a server tick, and only draws or shows coordinates. The regression first failed with `the server did not answer in time`; it now returns a still-pending future within 100 ms with exactly one server task queued. Missing destinations remain unavailable, including a missing block in another dimension. Saved-target serialization/restart tests and the runtime's existing late-result/selection/scope tests pass. `AssistantRuntime` retains its guards before opening on the client executor; no runtime guard was removed.

## Engine verification

All references below were read directly from `D:/Project/packsage/build/moddev/artifacts/neoforge-21.1.202-sources.jar`:

- `net/minecraft/world/entity/ai/village/poi/PoiManager.java:187-194`: both manager type and ticket reads call `getOrLoad`.
- `net/minecraft/world/level/chunk/storage/SectionStorage.java:74-75`: `get` reads only the in-memory storage map; `:78-94` loads an uncached column; `:117-124` joins the read and delegates to region storage.
- `net/minecraft/world/level/chunk/storage/SimpleRegionStorage.java:27-28`: the storage read calls `IOWorker.loadAsync`.
- `net/minecraft/world/entity/ai/village/poi/PoiSection.java:104-105` and `:123-128`: section ticket/type reads use the existing POI record map.
- `net/minecraft/server/level/ServerLevel.java:1581-1582`, `net/minecraft/world/level/entity/LevelEntityGetterAdapter.java:30-32`, and `net/minecraft/world/level/entity/EntityLookup.java:16,28-29`: the loaded entity enumeration is an unmodifiable iterable over the existing linked entity lookup, without materializing a chunk list.
- `net/minecraft/stats/StatsCounter.java:9`: the counter owns a synchronized fastutil map; each batch uses that map's monitor. Reads never enumerate or extend the client-shared `StatType` maps.
- `net/minecraft/world/entity/ai/attributes/AttributeInstance.java:60-61`: modifiers are exposed as a copied set; truncating their reported facts must affect section coverage.
- `net/minecraft/world/entity/npc/AbstractVillager.java:101-108`: ordinary `getOffers` can generate trades. PackSage continues to read only the stored offers field.
- `net/minecraft/util/thread/BlockableEventLoop.java:88-90`: `tell` queues a task and unparks the server thread without waiting for completion.

## Verification

Commands ran in Git Bash from the assigned worktree, with `JAVA_HOME=D:/Project/packsage/.tools/jdk/jdk-21.0.12.1`.

- Focused suites: `MinecraftWorldTest`, `MinecraftStatisticsTest`, `AreaScanTest`, `WorldToolsTest`, `WorldInspectionTest`, `WorldRegistryTest`, `MinecraftEntitiesTest`, `MinecraftTradesTest`, `ProgressToolsTest`, `WorldNavigationTest`, and `AssistantRuntimeTest` pass.
- Full `./gradlew build -q`: 97 suites, 1,079 tests, **1,074 passed, 5 skipped, 0 failures/errors**.
- Four skips are the existing symlink-dependent `ConversationStoreTest` cases. `GroundingInstructionsTest.defaultLengthMatchesLiveTestedCandidateWhenPresent` also skips because its local candidate artifact is absent from this isolated worktree.
- `git diff --check` passes. No formatting tools, push or merge were used.

The tests establish bounded operation counts, no-load POI access, coverage/output behavior and nonblocking lookup scheduling. They do not establish an installed-world latency measurement or live acceptance of these new cases.

## Fix round 2

The second review found that the original entity and block-entity iterators were unsafe across ticks. The following supersedes the traversal, statistics-growth and oversized-record behavior described above.

- Entity listing and holder gathering query the engine's spatial index per chunk in range, copy plain UUIDs/positions, and revalidate at most 64 holders per step. No entity or block-entity map iterator survives a step. UUID sets suppress repeated entity visits. Snapshots copy at most 4,096 keys per chunk; reaching that bound reports PARTIAL, and multi-step snapshots explicitly warn that additions or movement may be absent. Holder reads use existing block-entity map entries because even `getBlockEntity(..., CHECK)` can promote pending NBT.
- Entity pages resume after a sorted UUID anchor in the cursor's chunk. Removal of an earlier entity cannot shift an ordinal; a missing anchor resumes at its sorted insertion point with an explicit PARTIAL note. Holder gathering resumes only between chunks, with no entity ordinal in its continuation.
- Block inspectors now return `Section`, preserving unavailable POI facts as PARTIAL through the registry. Oversized entity inspections trim the largest facts first, retain smaller sections/facts and navigation identity, and mark omitted facts PARTIAL.
- Statistics share one 1,024-operation step budget across counter entries and named-zero work. Counter growth restarts the walk at most three times; continued growth returns partial copied rows with a retry note that reaches `list_statistics`. `StatisticListing` ordering is tested directly on the copied large-counter rows.
- Regression test declarations/imports follow the surrounding layout. Tests mutate entities and a fastutil block-entity map between steps, remove entities before and at a page anchor, reject global enumeration for a small-radius query, and verify retained health facts beside oversized offers.

Engine evidence was checked in the requested NeoForge 21.1.202 source JAR and fastutil 8.5.12 source/binary JARs in the Gradle cache. `LevelEntityGetterAdapter.get(EntityTypeTest, AABB, consumer)` delegates to `EntitySectionStorage`, which visits accessible spatial sections and propagates aborts; the global iterable instead exposes `EntityLookup.byId`. Fastutil iterators retain slot indices that removal, `shiftKeys` and rehash can invalidate. `StatsCounter.setValue` adds keys or updates values; size changes are checked before a retained statistics iterator advances. Tests execute the real Minecraft entity-section index.

Verification at `6988c67`: Git Bash `JAVA_HOME=D:/Project/packsage/.tools/jdk/jdk-21.0.12.1 ./gradlew build -q` passed. XML results: **97 suites, 1,088 tests, 1,083 passed, 5 skipped, 0 failures/errors**. The five skips are unchanged from the first round. The first targeted run reproduced six failures; the statistics-only budget run reproduced two; the POI coverage regression failed COMPLETE before its fix. A restart-test assertion was corrected to select the mined row rather than the crafted row with the same subject.

Installed/live acceptance, arbitrary concurrent-world completeness and measured 5 ms tick timing remain unverified. No Minecraft launch, JAR installation, merge or push was performed.

## Fix round 3

The re-review of round 2 identified five remaining findings. All are addressed on `fix/v2-world`, starting from `7121411`.

1. **Unconditional PARTIAL coverage.** `AreaScan.java:527,596` and `WorldTools.java:789,944` compare the game time at the start of traversal with the time when the result is read. A read spanning ticks names the concrete risk of additions or movement into already-read chunks. Dense snapshots and entity continuation pages no longer add blanket retry notes. Stable single-tick reads with no other gaps or bounds are COMPLETE. Tests: `AreaScanTest.aDenseStableSingleTickReadCanProveNoMatches`, `denseEntityPagesContinueWithoutDroppingOrRepeatingMatches`, and the stable/next-tick cases in `WorldToolsTest.holderCoverageReflectsTickSpansAndCountsEachUnloadedChunkOnce`.
2. **Chunk-border duplicates across pages.** `AreaScan.java:71` includes an entity only in the snapshot of its position chunk. `AreaScanTest.aBorderEntityIsReturnedOnlyOnceAcrossPages` uses two loaded chunks and a border-straddling entity returned before a page break inside the earlier chunk. The old path repeated that entity; the fixed path returns all 66 entities once across two pages.
3. **Movement behind the traversal.** The tick-span rule also covers an entity moving from an unread chunk into an already-read chunk. `AreaScanTest.anEntityMovingIntoAnAlreadyReadChunkMakesTheReadPartial` demonstrates the missed entity and requires PARTIAL plus a tick-span reason, even with a one-entity snapshot.
4. **Unavailable entity chunks counted twice or lost.** `WorldTools.java:895` counts unavailable entities at the snapshot decision and finishes that chunk immediately. `:925` still covers unloading after a successful snapshot. The parameterized holder test checks initially unloaded entities, unloading at the snapshot followed by reloading, and unloading after the snapshot; every case reports exactly one unavailable chunk.
5. **Mutation assertion did not require a mutation.** `AreaScanTest.java:52` now chooses a UUID that has not been revalidated yet, removes it between steps, requires the specific disappearance note, and compares the exact 63 remaining selected UUIDs with the returned unique set. A blanket snapshot note cannot satisfy the test, and omitting the removal or retaining the disappeared entity fails its assertions.

Engine behavior was verified directly in `D:/Project/packsage/build/moddev/artifacts/neoforge-21.1.202-sources.jar`: `LevelEntityGetterAdapter.java:46-47` routes typed AABB queries to section storage; `EntitySectionStorage.java:35-58,118-119` visits accessible spatial sections; `EntitySection.java:40-50` selects by bounding-box intersection. `Entity.java:3296-3297,3377-3378` exposes the cached position chunk and updates it when the entity's block position crosses a chunk boundary. `Level.java:955-956` reads game time from level data, `ServerLevel.java:441-445` advances it with ticks, and `:1681-1682` delegates the entity-loaded status read. These reads do not load chunks or mutate world state. The existing ServerWork 5 ms scheduling budget and bounded per-step traversal are unchanged; the dense 4,000-entity listing and holder tests still enforce at most 64 revalidations/inventory checks per step.

Initial focused RED: 50 tests, 10 failures reproducing coverage and unload-count issues; the refined border fixture separately failed with a repeated UUID. After the tick-span correction, only the border and two unload-count cases remained failing. After all fixes, both focused suites passed all 50 tests. The required Git Bash command `JAVA_HOME=D:/Project/packsage/.tools/jdk/jdk-21.0.12.1 ./gradlew build -q` passed at `7f19548`: **97 XML suites, 1,096 tests, 1,091 passed, 5 skipped, 0 failures/errors**. Skips remain the four symlink-dependent ConversationStoreTest cases and the absent local prompt-candidate check. A manual style pass and `git diff 7121411 --check` passed; no formatter was used.

Installed/live acceptance and measured tick latency remain unverified. No Minecraft launch, installation, merge or push was performed. Roadmap acceptance entries remain unchecked.
