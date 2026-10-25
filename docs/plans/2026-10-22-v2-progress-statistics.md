# v2 Progress and Statistics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Answer "what advancements am I missing?" and "how many diamonds have I mined?" from the integrated server's own advancement progress and player statistics. The reads go through the mergeable `ProgressReader` and `StatisticReader` capabilities, so quest books and mod counters can join later.

**Architecture:**
- **Core.** `dev.packsage.world` gains plain-data progress and statistic types, two capability interfaces registered in `WorldRegistry`, and two pure listing classes. The listing classes filter, order, merge and page adapter results off the server thread.
- **Minecraft adapters.** `dev.packsage.world.minecraft` adds `MinecraftProgress` (advancements) and `MinecraftStatistics` (the player's `ServerStatsCounter`). On the server thread, inside `ServerWork` slices, they copy only what a call needs into those records.
- **Provider.** A new `KnowledgeProvider`, `ProgressTools`, with provider id `progress`, exposes `list_progress`, `inspect_progress` and `list_statistics`. It also owns `advancement|…` links, which open the advancements screen at the right tab.
- **Isolation from Stage 1.** `WorldTools` is not touched, so this plan stays clear of Stage 1's edits there.

**Tech Stack:** Java 21, NeoForge 21.1.202 / Minecraft 1.21.1, JUnit 5.
- Run one class: `JAVA_HOME=D:/Project/packsage/.tools/jdk/jdk-21.0.12.1 ./gradlew test --tests '<class>' -q` (bash).
- Full build: `JAVA_HOME=D:/Project/packsage/.tools/jdk/jdk-21.0.12.1 ./gradlew build -q`.
- Results: `build/test-results/test/*.xml`.

**Spec:** [v2 world model](../specs/2026-10-22-v2-world-model.md), sections Progress, Statistics, Model-facing tools, The server boundary and Shared types. The [Minecraft completion spec](../specs/2026-10-25-v2-minecraft-completion.md) is Stage 1 (item reading) and lands first. The prompt rewrite is the later plan `2026-10-22-v2-prompt-acceptance.md`.

**Vanilla API verification:** names below were checked against `build/moddev/artifacts/neoforge-21.1.202-sources.jar` (the version in `gradle.properties`):
- `PlayerAdvancements` lines 85–130, 222–233 and 293–306;
- `AdvancementProgress`, `CriterionProgress`, `AdvancementNode`, `AdvancementTree`;
- `Advancement` (record: `parent`, `display`, `rewards`, `criteria`, `requirements`, …), `DisplayInfo`, `AdvancementType`;
- `AdvancementRewards` (record: `experience`, `loot`, `recipes`, `function`), `CacheableFunction.getId()`, `AdvancementRequirements` (record `requirements: List<List<String>>`);
- `ServerAdvancementManager.tree()`, `MinecraftServer.getAdvancements()`, `ServerPlayer.getAdvancements()` and `getStats()`;
- `StatsCounter`, `StatType` (`Iterable<Stat<T>>`, `contains`, `get`, `getRegistry`, `getDisplayName`), `Stat.format(int)`, `StatFormatter.{DEFAULT, DIVIDE_BY_TEN, DISTANCE, TIME}`, `Stats.*`;
- `StatsScreen.getTranslationKey` (`"stat." + id.replace(':', '.')`);
- `ClientAdvancements.setSelectedTab(holder, boolean)`, `get(ResourceLocation)` and `getTree()`, and `AdvancementsScreen(ClientAdvancements, Screen)`.

The implementer re-checks any name that fails to compile against the same jar.

## Global Constraints

- **Singleplayer only.** Every new tool fails `UNAVAILABLE` when `Minecraft.getSingleplayerServer()` is null (multiplayer), like the other world tools. The player read is the host player, `server.getSingleplayerProfile()`, even when the world is open to LAN.
- **Pacing.** Reads run on the integrated server thread through the shared `ServerWork` (5 ms per tick budget) and copy into plain records before returning. Record building, filtering and paging run on the worker executor, never on the server thread.
- **Reading never changes anything**, specifically:
  - no advancement progress entry is created;
  - no `Stat` object is created in a `StatType`'s map;
  - no criterion is granted or revoked;
  - no packet is sent, except the `ServerboundSeenAdvancementsPacket.openedTab` that vanilla's own screen sends when a link opens a tab.
- **Hidden advancements.** A hidden advancement that is not earned is listed with status `hidden` and no title, description, criteria, requirements, unlocks or rewards. Text filters never match it.
- **Statistic units.** Distances are reported in blocks (raw centimetres / 100) and times in seconds (raw ticks / 20). Statistics count since the world began.
- **Paging.** Pages hold at most 64 records, the tool output limit (`AreaScan.LIST_PAGE`), including tree records. Larger listings continue with a cursor.
- **Coverage.** Every result reports coverage. A bounded or interrupted read is `PARTIAL` with a note, never a silent truncation.
- **Persistent links.** Links follow the [persistent navigation contract](../specs/2026-10-18-persistent-navigation.md): the target id is the stable advancement identity.
- **Commits.** Commit each step as soon as it is written. Use the `[Add]`/`[Chg]`/`[Fix]`/`[Rmv]` message format from `$commit-message`, with no AI attribution. No formatting tools.
- **Start point.** Start from the branch after Stage 1 has merged, and re-read `PackSageTools`, `GroundingInstructions`, `ScreenText` and `AssistantRuntime` before editing them. Every edit to those files is an addition to a list or switch.

## Review Focus

1. **A `/reload` lands between two slices of a progress snapshot.** Expect a `PARTIAL` page whose note says the advancements were reloaded, not a crash. `getOrStartProgress` must never be called with a holder from the old manager, because that would insert a stale entry into the player's map. Test in Task 3.
2. **A hidden, partly progressed advancement** is reached through a text filter, a status filter, a visible entry's `unlocks`, or `inspect_progress`. Expect no title, description or criteria anywhere. Tests in Tasks 2 and 3.
3. **"How much ancient debris have I mined?" when the player never mined any.** Expect an explicit zero row, and `Stats.BLOCK_MINED.contains(Blocks.ANCIENT_DEBRIS)` still false afterwards. Test in Task 4.
4. **A mod stat type or a stat with a custom formatter.** Expect the category to be the full stat type id (`modid:foo`), unit `COUNT` with the raw value, and no failure for the other rows. Test in Task 4.
5. **A saved advancement link opened after a restart, when the advancement is not in the client's visible tree** (locked further than vanilla's visibility shows) or no longer exists. Expect the root's tab to open in the first case, and `UNAVAILABLE` with nothing opened in the second. Test in Task 5.

---

## File Structure

```
src/main/java/dev/packsage/world/
  ProgressId.java          record (adapter, local); text "minecraft/minecraft:story/mine_stone"
  ProgressStatus.java      enum DONE, IN_PROGRESS, AVAILABLE, LOCKED, HIDDEN
  ProgressTree.java        record: one advancement tab or quest chapter with done/total
  ProgressHandle.java      record: id, tree, title (null when hidden), status
  ProgressEntry.java       record: handle plus description, for listing and text filters
  ProgressCriterion.java   record: id, label, done, doneAt
  Progress.java            record: one inspected entry
  ProgressSnapshot.java    record: one adapter's trees and entries
  ProgressReader.java      capability
  ProgressListing.java     pure: filter, merge across adapters, page
  Statistic.java           record
  StatisticUnit.java       enum COUNT, DISTANCE, TIME, HEALTH
  StatisticReader.java     capability
  StatisticListing.java    pure: order, merge, page
  ServerReads.java         runs a slice on ServerWork with timeout and failure mapping (for ProgressTools)
  ProgressNavigation.java  advancement|… targets: refresh and open
  ProgressTools.java       KnowledgeProvider "progress": three tools, records, cursors, rereads
  WorldRegistry.java       (modify) registers and merges the two new capabilities
src/main/java/dev/packsage/world/minecraft/
  MinecraftProgress.java   ProgressReader for advancements
  MinecraftStatistics.java StatisticReader for ServerStatsCounter
```

---

### Task 1: Progress and statistic types, capabilities and registry

**Files:**
- Create: the `dev.packsage.world` type files above, from `ProgressId` to `ProgressReader`, plus `Statistic`, `StatisticUnit` and `StatisticReader`
- Modify: `src/main/java/dev/packsage/world/WorldRegistry.java` (two `instanceof` branches, two lists and three methods)
- Test: `src/test/java/dev/packsage/world/ProgressTypesTest.java` (new); `src/test/java/dev/packsage/world/WorldRegistryTest.java`

**Interfaces:**
- Produces:
  - **`record ProgressId(String adapter, String local)`.**
    - `String text()` gives `"minecraft/minecraft:adventure/adventuring_time"`.
    - `static ProgressId parse(String)` splits at the first `/`. If the part before the first `/` contains `:`, there is no adapter prefix and it returns `adapter == null` (any adapter). It throws `IllegalArgumentException` on blank input.
  - **`enum ProgressStatus { DONE, IN_PROGRESS, AVAILABLE, LOCKED, HIDDEN }`.** It has `String wire()` (lower snake case, such as `in_progress`) and `static ProgressStatus fromWire(String)`.
  - **`record ProgressTree(ProgressId id, String title, int done, int total)`.**
  - **`record ProgressHandle(ProgressId id, ProgressId tree, String title, ProgressStatus status)`.** `title` is null exactly when `status == HIDDEN`.
  - **`record ProgressEntry(ProgressHandle handle, String description)`.** `description` is null when hidden.
  - **`record ProgressCriterion(String id, String label, boolean done, Instant doneAt)`.**
  - **`record Progress(...)`**, with components `(ProgressHandle handle, String description, List<ProgressCriterion> criteria, List<ProgressHandle> requires, List<ProgressHandle> unlocks, List<String> rewards, Instant doneAt, Map<String, Section> facts)`. It has a `static Progress hidden(ProgressHandle handle)`, where every list is empty and the strings are null.
  - **`record ProgressSnapshot(List<ProgressTree> trees, List<ProgressEntry> entries, String note)`.** Entries are in the adapter's display order. `note` is non-null when the snapshot is partial.
  - **`interface ProgressReader`**, server thread only:
    - `String adapterId()`;
    - `ServerWork.Slice<ProgressSnapshot> snapshot(ServerPlayer player)`;
    - `Progress inspect(ServerPlayer player, String local)`, returning null when the id is not this adapter's;
    - `default Evidence.JsonObject progressFacts(ServerPlayer player, ProgressId id) { return null; }`, so other adapters can add facts to an entry they don't own.
  - **`enum StatisticUnit { COUNT, DISTANCE, TIME, HEALTH }`.** `HEALTH` is in health points (2 per heart).
  - **`record Statistic(String adapter, String category, String subject, String label, double value, StatisticUnit unit, long raw)`.**
    - `subject` is the block, item or entity id; for custom counters, the counter id, such as `minecraft:walk_one_cm` (see Open Question 2).
    - `value` is in `unit`; `raw` is the game's stored integer.
  - **`interface StatisticReader`**, server thread only:
    - `String adapterId()`;
    - `List<String> categories()`;
    - `ServerWork.Slice<List<Statistic>> statistics(ServerPlayer player, List<String> categories, KindFilter subjects)`. An empty `categories` means all of them.
  - **`WorldRegistry` additions:**
    - `register` also records `ProgressReader` and `StatisticReader` instances;
    - `List<ProgressReader> progressReaders()`;
    - `List<StatisticReader> statisticReaders()`;
    - `Map<String, Section> progressFacts(ServerPlayer player, ProgressId id, String ownerAdapter)`: it asks every reader except the owner through the existing `collect`, so a throwing adapter yields an `ERROR` section.

- [ ] **Step 1: Write the failing tests:**
  - `ProgressTypesTest.progressIdRoundTripsAndAcceptsABareLocalId`: `parse("minecraft/minecraft:story/root")` gives `("minecraft", "minecraft:story/root")`, and its `text()` round-trips. `parse("minecraft:story/root")` gives adapter null with local `"minecraft:story/root"`. `parse(" ")` throws.
  - `ProgressTypesTest.statusWireNamesRoundTrip`: `IN_PROGRESS.wire()` is `"in_progress"`, and `fromWire("hidden")` is `HIDDEN`.
  - `WorldRegistryTest.registersProgressAndStatisticReadersAndIsolatesAFailingFactsAdapter`: one object implementing both capabilities appears in both lists. `progressFacts` with owner `a` skips adapter `a`; adapter `b` returns facts and adapter `c` throws. Expect keys `b` (`COMPLETE`) and `c` (`ERROR`).
- [ ] **Step 2: Run both classes**, and expect a compile failure.
- [ ] **Step 3: Implement** the types and the registry additions.
- [ ] **Step 4: Run both classes**, and expect a pass.
- [ ] **Step 5: Commit.**

### Task 2: Pure listing and paging

**Files:**
- Create: `src/main/java/dev/packsage/world/ProgressListing.java`, `src/main/java/dev/packsage/world/StatisticListing.java`
- Test: `src/test/java/dev/packsage/world/ProgressListingTest.java`, `src/test/java/dev/packsage/world/StatisticListingTest.java`

**Interfaces:**
- Consumes: Task 1 types, `Page`, `KindFilter`.
- Produces:
  - **`record ProgressListing.Filter(ProgressId tree, Set<ProgressStatus> statuses, String text)`.**
    - A null tree means all trees, and an empty set means every status.
    - `text` is a case-insensitive substring of title, description or local id, and never matches `HIDDEN` entries.
    - A tree with `adapter == null` matches by `local` alone.
  - **`record ProgressListing.Item(ProgressTree tree, ProgressEntry entry)`.** Exactly one of the two is non-null.
  - **`static Page<ProgressListing.Item> ProgressListing.page(List<ProgressSnapshot> snapshots, Filter filter, int offset)`.**
    - Snapshots arrive in adapter registration order.
    - The page sequence is every matching tree (when `filter.tree` is null, or the matching tree only), then every matching entry, in order.
    - A page holds `LIST_PAGE` items starting at `offset`. `nextCursor` is the next offset as a decimal string, or null.
    - Coverage is `PARTIAL` when any snapshot has a note; notes are joined with `"; "`.
    - With no matches it is an empty `COMPLETE` page whose note is `"no progress entry matches"`.
  - **`static Page<Statistic> StatisticListing.page(List<List<Statistic>> perAdapter, int offset, List<String> notes)`.**
    - Order: category in the order `mined, crafted, used, broken, picked_up, dropped, killed, killed_by, custom`, then any other category alphabetically, then subject. Duplicates by (adapter, category, subject) are dropped.
    - Pages hold 64 items with a decimal offset cursor. Coverage is `PARTIAL` when `notes` is non-empty.
    - An empty listing is `COMPLETE` with note `"no counter matches; unlisted counters are zero"`.

- [ ] **Step 1: Write the failing tests:**
  - `ProgressListingTest.treesComeFirstThenEntriesInOrderAcross64RecordPages`: 2 adapters, 5 trees and 100 entries. Page 0 has 5 trees and 59 entries with cursor `"64"`. Page 64 has the remaining 41 entries with a null cursor.
  - `ProgressListingTest.textNeverMatchesHiddenEntries`: the hidden entry's local id contains `"levitate"`, and text `levitate` returns no entries. A status filter of `HIDDEN` returns it with a null title.
  - `ProgressListingTest.treeFilterAcceptsABareLocalIdAndListsOnlyThatTree`.
  - `ProgressListingTest.aPartialSnapshotMakesThePagePartialWithItsNote`.
  - `StatisticListingTest.ordersByCategoryThenSubjectAndPages`: 70 mined rows and 2 custom rows. Page 0 has 64 mined rows; page 64 has 6 mined rows, then the custom rows.
  - `StatisticListingTest.emptyListingSaysUnlistedCountersAreZero`.
- [ ] **Step 2: Run them**, and expect a compile failure.
- [ ] **Step 3: Implement** both classes.
- [ ] **Step 4: Run them**, and expect a pass.
- [ ] **Step 5: Commit.**

### Task 3: Minecraft advancements

**Files:**
- Create: `src/main/java/dev/packsage/world/minecraft/MinecraftProgress.java`
- Test: `src/test/java/dev/packsage/world/minecraft/MinecraftProgressTest.java`

**Interfaces:**
- Consumes: Task 1 types and `ServerWork.Slice`.
- Produces: `final class MinecraftProgress implements ProgressReader`, with `adapterId()` returning `"minecraft"`. It has these package-private seams for tests:
  - `MinecraftProgress.Source`, a record with components `(Supplier<Object> generation, Supplier<AdvancementTree> tree, Function<AdvancementHolder, AdvancementProgress> progress, Function<String, String> criterionLabel)`. `generation` returns an identity token for the loaded advancement set; production passes the `ServerAdvancementManager` instance, which a reload replaces;
  - `ServerWork.Slice<ProgressSnapshot> snapshot(Source source)`;
  - `Progress inspect(Source source, String local)`;
  - `static ProgressStatus status(boolean done, boolean anyCriterionDone, boolean hidden, Boolean parentDone)`, where `parentDone` is null for a root.

  The public methods build the `Source` from the player:
  - `generation`: `player.getServer()::getAdvancements`;
  - `tree`: `() -> player.getServer().getAdvancements().tree()`;
  - `progress`: `player.getAdvancements()::getOrStartProgress`;
  - `criterionLabel`: `player.registryAccess()` lookups. A criterion name that parses as a `ResourceLocation` present in `Registries.BIOME` gives the translated `biome.<ns>.<path>`. Otherwise an entity type gives its description, an item gives its description, and anything else gives the name itself.

- **Status:** `DONE` if done. Otherwise `HIDDEN` if `display.isHidden()`, else `IN_PROGRESS` if any criterion is done, else `AVAILABLE` if it is a root or its parent is done, else `LOCKED`.
- **Snapshot:**
  - It covers advancements with a `display` only. Recipe and technical advancements have none.
  - Trees are display roots from `tree.get().roots()`. Entries go depth-first per root with roots and siblings ordered by advancement id (vanilla's `children()` set order is identity-hashed and changes on every reload).
  - Each step handles at most 64 nodes.
  - `ProgressTree.done` and `total` count the displayed nodes under that root, including hidden ones.
  - Title and description come from `DisplayInfo.getTitle()` and `getDescription()` via `getString()`.
- **No mutation:** `getOrStartProgress` is called only for holders of the generation captured at the first step. On login and on every reload, `PlayerAdvancements.load` calls `registerListeners`, which calls `getOrStartProgress` for every advancement of the current manager. So for those holders the call is a pure lookup. Even a fresh entry would not be saved, because `asData` writes only entries with progress. Each step first checks that `generation.get()` is still the captured token. If it is not, the slice stops and the snapshot is partial, with note `"advancements were reloaded during the read; repeat it"`.
- **Inspect:**
  - `local` is an advancement id. It returns null when the id is not a displayed advancement of the current tree.
  - A hidden, not-done entry returns `Progress.hidden(handle)`.
  - Criteria come from `AdvancementRequirements.names()` in requirement order: `(name, criterionLabel(name), done, getObtained())`.
  - `requires` is the displayed parent's handle; `unlocks` is the displayed children's handles. Hidden ones are redacted the same way as in the listing.
  - `rewards`, in order:
    - `"experience: <n>"` when n > 0;
    - `"loot: <id>"` per loot table;
    - `"recipe: <id>"` per recipe;
    - `"function: <id>"`.
  - `doneAt` is the latest criterion `getObtained()` when done.
  - `facts` contains `minecraft` with:
    - `frame` (`AdvancementType.getSerializedName()`);
    - `criteriaDone` and `criteriaTotal` (requirement groups met and total);
    - `requirements`, the list of lists, present only when some group has more than one alternative.

- [ ] **Step 1: Write the failing tests.** Call `Bootstrap.bootStrap()`. Build holders with `Advancement.Builder`, `DisplayInfo`, `AdvancementRewards.Builder` and criteria from `CriteriaTriggers.IMPOSSIBLE.createCriterion(new ImpossibleTrigger.TriggerInstance())`. Add them to a `new AdvancementTree()` with `addAll`, and use a mutable `Object` token as `generation`. Progress comes from a map of `AdvancementProgress` objects (`new AdvancementProgress()`, then `update(requirements)`, then `grantProgress`). The progress function fails the test for any holder outside the map.
  - `statusCoversDoneInProgressAvailableLockedAndHidden`: the five combinations of `status(...)`.
  - `snapshotListsDisplayedTreesAndEntriesAndSkipsDisplaylessAdvancements`: a root with 3 children, one recipe-like child without display, and 150 nodes spread over 2 roots. Expect the right `done`/`total`, depth-first order, and at least 3 steps for 150 nodes.
  - `hiddenUnearnedEntriesCarryNoTitleDescriptionOrCriteria`: a hidden child with 1 of 2 criteria done. In the listing its title and description are null and its status is `HIDDEN`. `inspect` returns `Progress.hidden`. The parent's `unlocks` handle for it has a null title. Once both criteria are done, it is `DONE` with its title.
  - `inspectReportsCriteriaWithDatesRequirementsUnlocksAndRewards`: an "adventuring"-style advancement with criteria `minecraft:plains` (done) and `minecraft:desert` (not done), labels from a fake label function, and rewards of experience 100 and recipe `minecraft:torch`.
  - `readingNeverStartsProgressOrChangesCriteria`: after `snapshot` and `inspect`, the progress map's key set and every criterion's `isDone` and `getObtained` are unchanged.
  - `reloadBetweenSlicesStopsThePartialSnapshotWithoutTouchingStaleHolders`: swap the identity token after the first step. Expect a partial note, and no progress lookups after the swap.
- [ ] **Step 2: Run it**, and expect a compile failure.
- [ ] **Step 3: Implement** `MinecraftProgress`.
- [ ] **Step 4: Run it**, and expect a pass.
- [ ] **Step 5: Commit.**

### Task 4: Minecraft statistics

**Files:**
- Create: `src/main/java/dev/packsage/world/minecraft/MinecraftStatistics.java`
- Test: `src/test/java/dev/packsage/world/minecraft/MinecraftStatisticsTest.java`

**Interfaces:**
- Consumes: Task 1 types and `KindFilter`.
- Produces: `final class MinecraftStatistics implements StatisticReader`, with `adapterId()` returning `"minecraft"`. Package-private seams:
  - `ServerWork.Slice<List<Statistic>> statistics(StatsCounter counter, List<String> categories, KindFilter subjects)`. The public method passes `player.getStats()`.
  - `static StatisticUnit unit(Stat<?> stat)`.
  - `static String category(ResourceLocation statTypeId)`: the path for the `minecraft` namespace, otherwise the full id.

- **`categories()`:** every `BuiltInRegistries.STAT_TYPE` key mapped through `category`. Unknown requested categories throw `IllegalArgumentException("unknown statistic category <c>; use one of <list>")`.
- **Walk:** for each selected stat type, walk its `getRegistry()` in slices of 1,024 registry entries per step. Do not iterate the `StatType` itself: its `IdentityHashMap` is shared with the client thread, which adds stats while the stats screen is open.
  - For each value, the stat exists only when `type.contains(value)`. Then `type.get(value)` returns the existing `Stat` without creating one, and the value is `counter.getValue(stat)`.
  - Rows with value 0 are skipped, except subjects named in `subjects.ids()`. Those always yield a row, with value 0 when the stat does not exist, and are never created.
- **Filter:** `subjects.matches(id, tags, name)`, where:
  - `id` is the value's registry key;
  - `tags` come from `registry.wrapAsHolder(value).tags()` as strings;
  - `name` is the block's `getName()`, an item's or entity type's `getDescription()`, a custom stat's `Component.translatable("stat." + id.toString().replace(':', '.')).getString()` (the `StatsScreen` key), and the id otherwise.
- **Label:** `type.getDisplayName().getString() + ": " + name` for registry stats, and the translated custom name for `custom`.
- **Unit:** compare `stat.format(123456)` with each `StatFormatter` constant's `format(123456)`:

  | Formatter | Unit | Value |
  |---|---|---|
  | `DISTANCE` | `DISTANCE` | raw / 100.0 |
  | `TIME` | `TIME` | raw / 20.0 |
  | `DIVIDE_BY_TEN` | `HEALTH` | raw / 10.0 |
  | `DEFAULT` or anything else | `COUNT` | raw |

- [ ] **Step 1: Write the failing tests.** Use `Bootstrap.bootStrap()` and `new StatsCounter()`, set with `setValue(null, stat, n)`.
  - `listsMinedCraftedKilledAndCustomCountersWithLabels`: diamond ore mined 12, torch crafted 64, zombie killed 3, deaths 2. Expect the categories `mined`, `crafted`, `killed` and `custom`, and subjects `minecraft:diamond_ore`, `minecraft:torch`, `minecraft:zombie` and `minecraft:deaths`.
  - `distancesAreBlocksAndTimesSeconds`: `walk_one_cm` 123456 gives 1234.56 `DISTANCE`, and `play_time` 72000 gives 3600.0 `TIME`.
  - `damageIsHealthPointsAndUnknownFormattersAreCounts`: `damage_taken` 55 gives 5.5 `HEALTH`.
  - `namedNeverIncrementedSubjectIsAZeroRowAndCreatesNoStat`: ids `[minecraft:ancient_debris]` with category `mined` give one row with value 0. `Stats.BLOCK_MINED.contains(Blocks.ANCIENT_DEBRIS)` is false before and after.
  - `unknownCategoryIsRejectedWithTheValidList`.
  - `walkSpansStepsOverLargeRegistries`: the `mined` walk over the block registry takes more than one step.
  - `modStatTypeCategoryIsItsFullId`: `category(ResourceLocation.fromNamespaceAndPath("examplemod", "smelted"))` is `"examplemod:smelted"`.
- [ ] **Step 2: Run it**, and expect a compile failure.
- [ ] **Step 3: Implement** `MinecraftStatistics`.
- [ ] **Step 4: Run it**, and expect a pass.
- [ ] **Step 5: Commit.**

### Task 5: Advancement links

**Files:**
- Create: `src/main/java/dev/packsage/world/ProgressNavigation.java`
- Modify: `src/main/java/dev/packsage/client/ScreenText.java` (`navigationLabel`: one `progress` branch)
- Test: `src/test/java/dev/packsage/world/ProgressNavigationTest.java`, `src/test/java/dev/packsage/client/ScreenTextTest.java`

**Interfaces:**
- Produces: `final class ProgressNavigation`:
  - **`static String targetId(ProgressId tree, ProgressId entry)`** gives `"advancement|<root id>|<advancement id>"`, using local ids. Only `minecraft` handles that are not `HIDDEN` get one.
  - **`interface Client`:**
    - `boolean inWorld()`;
    - `boolean visible(ResourceLocation id)`, true when `ClientAdvancements.get(id) != null`;
    - `void openTab(ResourceLocation root)`. Production code calls `advancements.setSelectedTab(advancements.get(root), true)`, then `minecraft.setScreen(new AdvancementsScreen(advancements, minecraft.screen))`. The screen's `init` selects the client's selected tab.
  - **`static Client client(Minecraft)`**, using `minecraft.getConnection().getAdvancements()`.
  - **`CompletionStage<Evidence.NavigationTarget> refresh(target, scope)`** rebinds any well-formed `progress` target to the current scope, without reading, as `WorldNavigation.refresh` does.
  - **`Evidence.NavigationResult open(target)`:**
    - It opens the tab of `root` when the advancement or its root is visible.
    - It is `UNAVAILABLE` when out of a world, or with detail `"advancement <id> is not in this world"` when neither is visible.
    - A malformed id is `ERROR`.
  - **`ScreenText.navigationLabel`** gives `"Open advancements: " + <advancement path tail>` for provider `progress`.

- [ ] **Step 1: Write the failing tests:**
  - `ProgressNavigationTest.targetIdsRoundTripAndHiddenHandlesGetNone`.
  - `ProgressNavigationTest.opensTheRootTabWhenTheAdvancementIsNotVisibleYet`: the fake client sees the root only, and `openTab(root)` is called.
  - `ProgressNavigationTest.missingAdvancementIsUnavailableAndOpensNothing`.
  - `ProgressNavigationTest.savedTargetsRebindToANewScope`.
  - `ScreenTextTest.progressTargetsReadOpenAdvancements`.
- [ ] **Step 2: Run them**, and expect them to fail.
- [ ] **Step 3: Implement** the navigation and the label branch.
- [ ] **Step 4: Run them**, and expect a pass.
- [ ] **Step 5: Commit.**

### Task 6: `ProgressTools` provider

**Files:**
- Create: `src/main/java/dev/packsage/world/ServerReads.java`, `src/main/java/dev/packsage/world/ProgressTools.java`
- Test: `src/test/java/dev/packsage/world/ProgressToolsTest.java`

**Interfaces:**
- Consumes: Tasks 1–5, `ServerWork`, `Toolbox.Failure`/`Code`, `Evidence`.
- Produces:
  - **`final class ServerReads`:**
    - `static <T> CompletionStage<T> run(ServerWork work, Executor worker, BooleanSupplier worldOpen, ServerWork.Slice<T> slice)`. It applies a 60-second timeout and continues on `worker`. It maps failures as `WorldTools.run` does: `Failure` passes through, a timeout becomes `UNAVAILABLE "the world did not answer in time"`, `IllegalArgumentException` becomes `INVALID_ARGUMENTS`, and anything else becomes `EXTRACTION_FAILED`.
    - `static <T> ServerWork.Slice<T> once(Supplier<T> read)`.
    - `WorldTools` keeps its own copy until a later cleanup.
  - **`final class ProgressTools implements KnowledgeProvider`:**
    - `ID = "progress"`, `VERSION = "1"`.
    - Public constructor `ProgressTools(Minecraft client, WorldRegistry registry, Supplier<Evidence.Scope> scope, Executor worker, ServerWork work)`. Package-private constructor `(Supplier<MinecraftServer> server, Function<MinecraftServer, ServerPlayer> host, WorldRegistry, Supplier<Evidence.Scope>, Executor, ServerWork, ProgressNavigation)`.
    - `enabled()` and `status()` follow `WorldTools` (`UNAVAILABLE "progress tools need an integrated singleplayer server"`).
    - `handle` takes:
      - `list_progress(tree?, status?, text?, cursor?)`;
      - `inspect_progress(id)`;
      - `list_statistics(categories?, subjects?, cursor?)`.

      Any other tool fails `NOT_HANDLED`.
  - **Reads:**
    - `list_progress` runs every `progressReaders()` snapshot sequentially in one slice chain, then `ProgressListing.page` on the worker.
    - `inspect_progress` uses `once`: the owner reader's `inspect` (by adapter, or the first non-null for a bare id), then `registry.progressFacts` merged into `facts`. An unknown id fails `INVALID_ARGUMENTS "no progress entry has id <id>; find ids with list_progress"`.
    - `list_statistics` chains every `statisticReaders()` slice. A reader that throws adds a note and a `PARTIAL` result, and the others still return.
  - **Cursors** are stateless: `"<offset>.<hex hash of the signature>"`. The signature is the arguments without `cursor` and with nulls dropped, as in `WorldTools.signature`. A mismatched or malformed cursor fails `INVALID_CURSOR "cursor does not match this query; restart without a cursor"`.
  - **Records** (provider `progress`, authority `SERVER_READ`; each source handle can be reread through `read_evidence`):

    | Record | Handle | Kind | Payload | Link |
    |---|---|---|---|---|
    | Tree | `tree\|<id text>` | `OTHER` | `tree`, `adapter`, `title`, `done`, `total` | the tab |
    | Entry | `progress\|<id text>` | `QUEST` | `id`, `adapter`, `tree`, `title` (JSON null when hidden), `status` (wire), and `description` when not hidden | the advancement |
    | Inspection | `progress\|<id text>` | `QUEST` | the entry's fields, then `criteria` (`id`, `label`, `done`, `doneAt` ISO-8601 or null), `requires` and `unlocks` (handle objects), `rewards`, `doneAt`, and `sections` (adapter to coverage, facts, note), as in `WorldTools.sections` | when not hidden |
    | Statistic | `stat\|<adapter>\|<category>\|<subject>` | `OTHER` | `adapter`, `category`, `subject`, `label`, `value`, `unit` (lower case), `raw` | none |

    Statistic titles look like `"Times Mined: Diamond Ore = 12"`.
  - **Rereads.** `readEvidence` and `query(GET)` reread `progress|` (as inspect) and `stat|` (as a one-subject, one-category listing that always returns its row). `tree|` fails `INVALID_REFERENCE "repeat list_progress"`.
  - **Navigation.** `refreshNavigation` and `open` delegate to `ProgressNavigation`.

- [ ] **Step 1: Write the failing tests.** Use fake readers registered in a `WorldRegistry`, a `ServerWork` ticked by the test, and `Runnable::run` as the worker:
  - `toolsAreUnavailableWithoutAnIntegratedServer`: all three tools fail `UNAVAILABLE`.
  - `listProgressCombinesAdaptersTreesFirstWithLinksExceptHidden`.
  - `listProgressCursorContinuesAndRejectsAChangedFilter`.
  - `inspectProgressMergesOtherAdaptersFactsAndRejectsUnknownIds`.
  - `listStatisticsKeepsOtherAdaptersWhenOneFails`: the result is `PARTIAL` with the failing adapter named in the detail.
  - `statisticAndProgressHandlesRereadThroughReadEvidence`.
- [ ] **Step 2: Run it**, and expect a compile failure.
- [ ] **Step 3: Implement** `ServerReads` and `ProgressTools`.
- [ ] **Step 4: Run it**, and expect a pass.
- [ ] **Step 5: Commit.**

### Task 7: Model-facing schemas, registration and prompt mention

**Files:**
- Modify:
  - `src/main/java/dev/packsage/model/PackSageTools.java`: the three names appended after `"find_items"` in the tool list, three schema cases and three descriptions;
  - `src/main/java/dev/packsage/client/ClientAssistant.java` (`knowledge(...)`): register `new MinecraftProgress()` and `new MinecraftStatistics()` on the same `WorldRegistry`, and add `new ProgressTools(client, world, scope, workerExecutor, serverWork)`;
  - `src/main/java/dev/packsage/client/ScreenText.java`: add `progress` to `CITATION_MARKER`'s provider group, and an inline label `"Advancements"` for `progress` targets in both `switch`es;
  - `src/main/java/dev/packsage/client/AssistantScreen.java` (`ADAPTERS`): add `Map.entry("progress", "Progress")`;
  - `src/main/java/dev/packsage/chat/AssistantRuntime.java` (adapter availability): add `progress` to both maps;
  - `src/main/java/dev/packsage/model/GroundingInstructions.java`: one new line after the surroundings paragraph that names `inspect_area` and `find_items`.
- Test: `src/test/java/dev/packsage/model/PackSageToolsTest.java` (`NAMES` and a new test), plus any screen or runtime test that enumerates adapters.

**Interfaces:**
- **Schemas.** Every schema has `additionalProperties: false` and reuses `WORLD_CURSOR` and `WORLD_FILTER` (made nullable as `list_items` does):

  | Tool | Property | Schema |
  |---|---|---|
  | `list_progress` | `tree` | `{"type":["string","null"],"maxLength":600}` |
  | | `status` | `{"type":["array","null"],"maxItems":5,"items":{"type":"string","enum":["done","in_progress","available","locked","hidden"]}}` |
  | | `text` | `{"type":["string","null"],"maxLength":256}` |
  | | `cursor` | `WORLD_CURSOR` |
  | `inspect_progress` | `id` (required) | `{"type":"string","maxLength":600}` |
  | `list_statistics` | `categories` | `{"type":["array","null"],"maxItems":16,"items":{"type":"string","maxLength":128,"pattern":"^[a-z0-9_.:-]+$"}}` |
  | | `subjects` | nullable `WORLD_FILTER` |
  | | `cursor` | `WORLD_CURSOR` |

- **Descriptions** (exact copy):
  - `list_progress`: "List progress entries: Minecraft advancements now, quest books later. The first page starts with one record per tree (an advancement tab) with done and total counts, then entries in tree order with status done, in_progress, available, locked or hidden, 64 records per page. Filter by tree id, statuses, or text in the title or description. Hidden entries have no title until earned; do not guess what they are. Read criteria, requirements and rewards with inspect_progress."
  - `inspect_progress`: "Read one progress entry by its returned id: description, each criterion with its done state and when it was met (for example the biomes still missing for Adventuring Time), what it requires and unlocks, and rewards. A hidden entry that is not earned returns only its status."
  - `list_statistics`: "Read the player's own counters since the world began: blocks mined; items crafted, used, broken, picked up and dropped; mobs killed and killed by; and custom counters such as deaths, jumps, play time and distance walked. categories narrows to mined, crafted, used, broken, picked_up, dropped, killed, killed_by or custom. subjects filters block, item, entity or counter ids, tags or name text. Distances are in blocks, times in seconds and damage in health points. Counters never incremented are zero and are listed only when named in subjects.ids. 64 per page."
- **Prompt line** (exact copy; the full prompt rewrite is the later prompt-acceptance plan): "For advancement progress, use list_progress, then inspect_progress for the criteria still missing. For how many things the player mined, crafted, used, picked up, dropped or killed, how often they died, how far they traveled or how long they played, use list_statistics; it counts since the world began, and the journal answers when."

  Drop "and the journal answers when" if the journal plan has not landed.

- [ ] **Step 1: Write the failing tests:**
  - `PackSageToolsTest.listsProgressAndStatisticTools`: names, properties and required lists as in the schema table, in the style of `listsWorldTools`. Also add the three names to `NAMES`.
  - `PackSageToolsTest.progressStatusRejectsUnknownValues`: `{"status":["finished"]}` is rejected before dispatch.
- [ ] **Step 2: Run `PackSageToolsTest`**, and expect a failure.
- [ ] **Step 3: Implement** the schemas, descriptions, registrations, provider-id additions and the prompt line.
- [ ] **Step 4: Run `PackSageToolsTest`, then the full build**, and expect a pass. Fix any test that enumerates the adapter list.
- [ ] **Step 5: Commit.**

### Task 8: Remove the v1 `read_progress` plumbing

**Decision:** delete it. It rereads observed `QUEST` references through `read_progress`, a tool that is already out of the tool list. `ProgressReader` and `inspect_progress(id)` replace it for quest adapters, so keeping it would leave two progress paths. `Evidence.RecordKind.QUEST` stays, because progress records use it.

**Files:**
- Modify:
  - `src/main/java/dev/packsage/knowledge/Toolbox.java:105`: remove `readProgress`;
  - `src/main/java/dev/packsage/knowledge/AdapterRegistry.java:45`: remove the `read_progress` case;
  - `src/main/java/dev/packsage/knowledge/KnowledgeService.java:50-84`: drop the `read_progress` branches. `read_evidence` keeps its multi-handle path unchanged.
  - `src/main/java/dev/packsage/chat/ActivityLabels.java:33`: remove the label;
  - `src/main/java/dev/packsage/model/ReferenceAliasBackend.java:326`: drop `read_progress` from the handle-alias list.
- Test: remove the following, and adjust `RequestControllerTest` lines 1419 and 1503 to use `read_evidence`:
  - `AssistantIntegrationTest.readProgressRequeriesObservedQuestAndPreservesProviderUnavailable`;
  - `AssistantIntegrationTest.readProgressKeepsFreshHandlesWhenAnotherSelectedProviderIsUnavailable`;
  - the fake `readProgress` override (line 1159);
  - the `read_progress` cases in `KnowledgeServiceTest` (around lines 340–370).

- [ ] **Step 1: Change the tests** listed above. Add `KnowledgeServiceTest.readProgressIsNotAToolAnyMore`: executing `read_progress` fails like any unknown tool, with `NOT_HANDLED` from the registry.
- [ ] **Step 2: Run** `KnowledgeServiceTest`, `AssistantIntegrationTest` and `RequestControllerTest`, and expect the new test to fail.
- [ ] **Step 3: Remove the plumbing.**
- [ ] **Step 4: Run the three classes, then the full build**, and expect a pass.
- [ ] **Step 5: Commit** as `[Rmv]`.

### Task 9: Build, tracking documents and live-check hand-off

**Files:**
- Modify:
  - `CHANGELOG.md`: an Unreleased entry for the v2 branch, with the automated test count from the build;
  - `docs/roadmap/2026-10-22-v2-minecraft-only.md`: under "World model", add a "Progress and statistics" subsection linking this plan, with these entries, unchecked until live acceptance:
    - `list_progress`, `inspect_progress` and `list_statistics`;
    - hidden advancements redacted;
    - advancement links;
    - the `read_progress` plumbing removed.

    Also change the "Next plans" entry from "not written" to a link.
  - `docs/roadmap/2026-10-13-todo.md` ("v2 branch"): one unchecked line for the progress and statistic tools, linking the v2 note;
  - `docs/roadmap/2026-10-21-adapter-feature-todo.md`: a new "Progress and statistics (v2)" group under the v2 section, with two unchecked entries:
    - "Minecraft — advancement progress with per-criterion state and tab links";
    - "Minecraft — player statistics in blocks and seconds".

- [ ] **Step 1: Run the full build**, and expect no failures in `build/test-results/test/*.xml`.
- [ ] **Step 2: Update the four documents**, then verify every relative link resolves.
- [ ] **Step 3: Commit** the changelog and the roadmaps as separate commits.

**Live acceptance (run later by the orchestrator, not implementers).** Run on the installed JAR, in singleplayer, with the developer override cleared. Afterwards, record the results in `docs/validation/2026-10-22-v2-progress-statistics.md` and check the roadmap entries that pass.

1. **Adventuring Time.** With a few biomes visited, ask "what advancements am I missing for Adventuring Time?" Expect the missing biomes by name from `inspect_progress`, and an `Open advancements` link that opens the Adventure tab.
2. **Diamonds.** After mining some diamond ore, ask "how many diamonds have I mined?" Expect the exact `mined` count for `minecraft:diamond_ore` and `minecraft:deepslate_diamond_ore`. Also count picked-up diamonds if the reply distinguishes them.
3. **Walking.** Ask "how far have I walked?" Expect `walk_one_cm` in blocks, or km derived from blocks, not centimetres.
4. **Never mined.** Ask "how much ancient debris have I mined?" in a fresh world. Expect zero, stated as a counter value, not "unknown".
5. **Hidden advancement.** With a hidden advancement partly progressed, ask "what hidden advancements do I have left?" Expect a count with no names or criteria.
6. **Play time.** Ask "how long have I played this world?" Expect seconds converted sensibly, from `play_time`.
7. **Next goal.** Ask "what should I do next for advancements?" Expect `available` entries from `list_progress`, each with a working link.
8. **Saved link.** Open a saved advancement link after a full Minecraft restart, and expect the tab to open.
9. **Multiplayer.** On a multiplayer server, expect the tools to report unavailable.
10. **Performance.** Run a `list_progress` with a large datapack of advancements loaded, and expect no visible stutter.

## Open questions

These are decided as written above. Each needs confirmation before or during review.

1. **Hidden advancement ids.** Hidden, unearned entries keep their real id (for example `minecraft:nether/all_effects`), because `inspect_progress`, `requires`/`unlocks` and cursors need a stable identity. The id can hint at the name. The alternative is an opaque per-snapshot id, which would break saved references. Recommendation: keep the real id, and rely on the tool description's "do not guess what they are".
2. **`subject` for custom counters.** The spec says `None`. This plan puts the counter id there (`minecraft:walk_one_cm`), so one `KindFilter` serves every category, and rows and handles stay uniform. Recommendation: accept, and amend the spec's `Statistic` comment.
3. **A fourth unit, `HEALTH`.** Damage counters are stored in tenths of a health point. The spec lists only Count, Distance and Time. Recommendation: add `HEALTH`, because reporting raw tenths as a count invites wrong answers.
4. **Separate provider id `progress`** instead of `world`. This keeps `WorldTools` free of Stage 1 conflicts, and gives advancement links their own `Open` label rather than `Show in world`. The cost is one-line additions in `ScreenText`, `AssistantScreen` and `AssistantRuntime`. Recommendation: accept.
5. **Stateless offset cursors.** Progress and statistic cursors encode an offset, so they survive restarts and need no LRU. A page boundary can shift if an advancement is earned, or a counter first increments, between pages. Recommendation: accept; both lists are small and stably ordered.
