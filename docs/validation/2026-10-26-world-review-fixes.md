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
