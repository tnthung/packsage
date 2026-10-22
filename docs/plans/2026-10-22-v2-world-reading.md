# v2 World Reading Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Read blocks, mobs and every inventory, including nested ones, from the integrated server through mergeable capability interfaces, and let answers highlight what they found.

**Architecture:** A new `dev.packsage.world` package holds plain-data types and capability interfaces (`WorldAccessor`, `BlockInspector`, `EntityInspector`, `InventoryReader`) plus the core that pages, slices work across server ticks, merges adapter sections and resolves item paths. `dev.packsage.world.minecraft` implements the capabilities for vanilla. `WorldTools`, a `KnowledgeProvider` with provider id `world`, exposes the new model tools through the existing tool plumbing and evidence format, and owns `world-block` / `world-entity` navigation, which the client draws as highlights.

**Tech Stack:** Java 21, NeoForge 21.1 / Minecraft 1.21.1, JUnit 5. Run tests with `export JAVA_HOME="D:/Project/packsage/.tools/jdk/jdk-21.0.12.1"; ./gradlew test --tests '<class>' -q` and the full build with `./gradlew build -q`; results are in `build/test-results/test/*.xml`.

**Spec:** [v2 world model](../specs/2026-10-22-v2-world-model.md), sections Architecture, Reading the world, Inventories and Highlights. Journal, notes, progress, statistics and the prompt rewrite follow in later plans: `2026-10-22-v2-journal.md`, `2026-10-22-v2-progress-statistics.md`, `2026-10-22-v2-prompt-acceptance.md`.

## Global Constraints

- Singleplayer only: every new tool reports `UNAVAILABLE` when `Minecraft.getSingleplayerServer()` is null.
- No policy limits: no reach, lock, LAN or other-player refusals. Bounds only page work, and a bounded result is `PARTIAL` with a cursor that continues it.
- Server work runs in slices of at most 5 ms per server tick. The game thread never waits on PackSage.
- Reading never changes the world. Opening a container item must not add or change any component.
- Radius defaults to 16 and may reach as far as chunks are loaded. Pages hold up to 4,096 notable blocks for summaries and 64 items for lists. A `find_items` page inspects up to 512 containers. Item paths are at most 6 steps. Item entries carry up to 8 sample locations.
- Double chest: either half reads as one 54-slot `minecraft/container` and names the other half. Ender chest block: reads the player's `minecraft/ender_chest`.
- Highlights last 30 seconds; coordinates always appear in the answer text too.
- Commit each step as soon as it is written, using the `[Add]`/`[Chg]`/`[Fix]`/`[Rmv]` message format with no AI attribution. No formatting tools.

## Review Focus

1. **A chunk unloads between two slices of one read.** Expect that chunk to count as unloaded, with the result PARTIAL, not a crash or stale data. Test in Task 3.
2. **A path step whose slot now holds a different item** (the player moved the shulker box). Expect `Stale` at that step, with earlier steps still reported. Test in Task 4.
3. **A shulker box or bundle inspected while empty or never filled.** Expect an empty inventory, and the stack's components byte-for-byte unchanged afterwards. Test in Task 6.
4. **The world closes while a sliced read is in flight.** Expect the tool call to fail as `UNAVAILABLE` and no further slices scheduled. Test in Task 3.
5. **A saved highlight link opened after the block was broken or the mob died.** Expect `UNAVAILABLE` with no highlight drawn anywhere. Test in Task 10.

---

## File Structure

```
src/main/java/dev/packsage/world/
  BlockPlace.java        record (dimension, x, y, z), text form "dim|x,y,z"
  Anchor.java            sealed: Player, OfEntity(UUID), OfBlock(BlockPlace)   (OfNote added by the journal plan)
  KindFilter.java        ids, tags, text; matches(...)
  Page.java              record Page<T>(items, nextCursor, coverage, note)
  Section.java           record Section(facts, coverage, note)
  BlockHandle.java, EntityHandle.java, NotableBlock.java, BlockSummary.java
  InventoryId.java, InventoryInfo.java, SlotRole.java
  PathRoot.java, PathStep.java, ItemPath.java, ItemEntry.java
  LiveHolder.java        sealed, server-thread only
  LiveInventory.java, InventoryReader.java, BlockInspector.java, EntityInspector.java, WorldAccessor.java
  WorldRegistry.java     holds adapters, merges sections, isolates failures
  ServerWork.java        slices work across server ticks under a time budget
  PathResolver.java      walks an ItemPath across adapters
  ItemGrouper.java       groups stacks into ItemEntry rows
  ItemSearch.java        find_items orchestration
  AreaScan.java          sliced block and entity scans around an anchor
  WorldTools.java        KnowledgeProvider "world": tools, cursors, evidence, navigation
src/main/java/dev/packsage/world/minecraft/
  MinecraftWorld.java        WorldAccessor + BlockInspector
  MinecraftEntities.java     EntityInspector (villager and mob facts)
  MinecraftInventories.java  InventoryReader for the section table
src/main/java/dev/packsage/client/highlight/
  WorldHighlighter.java
```

---

### Task 1: Generic tool dispatch

**Files:**
- Modify: `src/main/java/dev/packsage/knowledge/Toolbox.java`, `src/main/java/dev/packsage/knowledge/AdapterRegistry.java:39-53`
- Test: `src/test/java/dev/packsage/knowledge/ToolboxTest.java`

**Interfaces:**
- Produces: `default CompletionStage<Evidence.Result> handle(PackSageTools.ReadRequest request, Context context)` on `Toolbox`, returning `NOT_HANDLED` by default. `AdapterRegistry.dispatch` routes any tool name it doesn't list to `handle`, with the same fall-through on `NOT_HANDLED`.

- [ ] **Step 1: Write the failing test** `unlistedToolsRouteThroughHandleWithFallThrough`: register a declining adapter and an adapter whose `handle` answers tool `inspect_block`. Dispatching `inspect_block` returns the second adapter's result; dispatching `no_such_tool` with no handler fails `NOT_HANDLED`.
- [ ] **Step 2: Run it**, and expect a failure with `INVALID_ARGUMENTS: unknown tool`.
- [ ] **Step 3: Implement** the default method and replace the `default ->` branch of the switch with `adapters.get(index).handle(request, context)`.
- [ ] **Step 4: Run `ToolboxTest`**, and expect a pass.
- [ ] **Step 5: Commit.**

### Task 2: Core types and merged inspection

**Files:**
- Create: the `dev.packsage.world` type files listed above (not `ServerWork`, `PathResolver`, `ItemGrouper`, `ItemSearch` or `WorldTools`), plus `WorldRegistry.java`
- Test: `src/test/java/dev/packsage/world/WorldTypesTest.java`, `src/test/java/dev/packsage/world/WorldRegistryTest.java`

**Interfaces:**
- Produces:
  - `record BlockPlace(String dimension, int x, int y, int z)` with `String text()` giving `"minecraft:overworld|10,64,-3"` and `static BlockPlace parse(String)`, which throws `IllegalArgumentException`.
  - `sealed interface Anchor` with records `Player()`, `OfEntity(UUID id)` and `OfBlock(BlockPlace place)`.
  - `record KindFilter(List<String> ids, List<String> tags, String text)` with `static final KindFilter ANY` and `boolean matches(String id, Collection<String> tags, String name)`. Within a field the alternatives are OR; populated fields are AND; text is a case-insensitive substring of id or name.
  - `record Page<T>(List<T> items, String nextCursor, Evidence.Coverage coverage, String note)`.
  - `record Section(Evidence.JsonObject facts, Evidence.Coverage coverage, String note)`.
  - `record BlockHandle(String kind, BlockPlace place)`.
  - `record EntityHandle(String kind, UUID id, String dimension, double x, double y, double z, String name)`.
  - `record NotableBlock(BlockHandle handle, String role)`, where role is one of `container`, `bed`, `workstation`, `fluid`, `light`, `redstone`, `portal`, `spawner`.
  - `record BlockSummary(Map<String, Integer> counts, List<NotableBlock> notable, int unloadedChunks)`.
  - `record InventoryId(String adapter, String section)` with `String text()` giving `"minecraft/fuel"` and `static InventoryId parse(String)`.
  - `enum SlotRole { STORAGE, INPUT, FUEL, OUTPUT, EQUIPMENT, DISPLAY }` and `record InventoryInfo(InventoryId id, String label, int slots, SlotRole role)`, where `slots == -1` means keyed.
  - `sealed interface PathRoot` with `Player(UUID)`, `Block(BlockPlace)` and `Entity(UUID)`.
  - `record PathStep(InventoryId inventory, String entry, String kind)`.
  - `record ItemPath(PathRoot root, List<PathStep> steps)`, where `MAX_STEPS = 6`. Its `String text()` gives `"entity:<uuid>/minecraft/chest#5@sophisticatedbackpacks:backpack/..."`. It also has `static ItemPath parse(String)` and `ItemPath child(PathStep)`, which throws when it would exceed `MAX_STEPS`.
  - `record ItemEntry(String item, String name, long count, int variants, List<ItemPath> locations, boolean holdsItems)`.
  - `sealed interface LiveHolder` with `OfPlayer(ServerPlayer)`, `OfBlock(ServerLevel, BlockPos)`, `OfEntity(Entity)` and `OfItem(ItemStack)`.
  - `interface LiveInventory { ItemStack get(String entry); List<Map.Entry<String, ItemStack>> entries(); }`.
  - `interface InventoryReader { String adapterId(); List<InventoryInfo> inventories(LiveHolder holder); LiveInventory open(LiveHolder holder, String section); Evidence.JsonObject inspectItem(ItemStack stack); }`. `open` returns null when the section is not its own; `inspectItem` returns null when it has nothing to add.
  - `interface BlockInspector { String adapterId(); Evidence.JsonObject inspectBlock(ServerLevel level, BlockPos pos); }`, returning null when it has nothing to add.
  - `interface EntityInspector { String adapterId(); Evidence.JsonObject inspectEntity(Entity entity); }`.
  - `WorldAccessor` (see Task 7).
  - `final class WorldRegistry`:
    - `register(Object adapter)` registers every capability the object implements;
    - `Map<String, Section> inspectBlock(ServerLevel, BlockPos)`, `Map<String, Section> inspectEntity(Entity)` and `Map<String, Section> inspectItem(ItemStack)`;
    - `List<InventoryInfo> inventories(LiveHolder)`;
    - `InventoryReader inventoryReader(String adapterId)`;
    - `List<WorldAccessor> accessors()`.

    Maps keep registration order. An adapter that throws contributes `Section(empty, ERROR, message)`.

- [ ] **Step 1: Write the failing tests:**
  - `blockPlaceAndInventoryIdRoundTripTheirTextForms`;
  - `itemPathRoundTripsAndRejectsASeventhStep`;
  - `kindFilterCombinesFieldsWithAndAlternativesWithOr`;
  - `mergedInspectionKeepsEachAdaptersSectionAndIsolatesAFailingAdapter`. This uses two fake `InventoryReader`s on an `OfItem` holder over a bootstrapped `ItemStack`: one returns facts, one throws. Expect both keys, the failing one `ERROR`.
- [ ] **Step 2: Run them**, and expect them to fail to compile.
- [ ] **Step 3: Implement** the types and `WorldRegistry`.
- [ ] **Step 4: Run both test classes**, and expect a pass.
- [ ] **Step 5: Commit.**

### Task 3: Sliced server work

**Files:**
- Create: `src/main/java/dev/packsage/world/ServerWork.java`
- Test: `src/test/java/dev/packsage/world/ServerWorkTest.java`

**Interfaces:**
- Produces: `final class ServerWork`:
  - constructor `ServerWork(Executor serverThread, LongSupplier nanoClock, BooleanSupplier worldOpen)`;
  - `<T> CompletionStage<T> run(Slice<T> job)`;
  - `interface Slice<T> { boolean step(); T result(); }`. `step` does one bounded unit and returns `false` when finished.
  - `static final long BUDGET_NANOS = 5_000_000`.

  Each server task calls `step` until it returns false or the budget is spent, then re-submits itself. If `worldOpen` turns false, it fails with `Toolbox.Failure(UNAVAILABLE, "world closed while reading")` and stops scheduling.

- [ ] **Step 1: Write the failing tests:**
  - `workSpansTicksWhenTheBudgetIsSpent`: a fake clock advances 2 ms per step and the job takes 7 steps. Expect 3 executor submissions and the right result.
  - `closingTheWorldFailsTheReadAndStopsScheduling`.
  - `chunkUnloadedBetweenSlicesIsCountedNotThrown`: a fake slice reads a list whose second chunk becomes "unloaded" before its slice. The slice records it as unloaded, and the result reports 1 unloaded chunk and PARTIAL. This pins the contract for Task 7's scanning slices.
- [ ] **Step 2: Run them**, and expect them to fail to compile.
- [ ] **Step 3: Implement** `ServerWork`.
- [ ] **Step 4: Run them**, and expect a pass.
- [ ] **Step 5: Commit.**

### Task 4: Path resolution

**Files:**
- Create: `src/main/java/dev/packsage/world/PathResolver.java`
- Test: `src/test/java/dev/packsage/world/PathResolverTest.java`

**Interfaces:**
- Consumes: `WorldRegistry.inventoryReader(String)`, `ItemPath`, `LiveHolder`.
- Produces:
  - `final class PathResolver` with constructor `PathResolver(WorldRegistry registry, Function<PathRoot, LiveHolder> roots)`, where `roots` returns null when the root is gone;
  - `Resolution resolve(ItemPath path)`;
  - `record Resolution(LiveHolder holder, ItemStack stack, int failedStep, Failure failure)`, with `enum Failure { NONE, ROOT_GONE, UNAVAILABLE, STALE }`.

  On failure, `holder` and `stack` are the last resolved ones, and `failedStep` is the zero-based step that failed. `kind` is compared with `BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()`.

- [ ] **Step 1: Write the failing tests:**
  - `resolvesAcrossTwoAdapters`: fake reader `a` opens section `box` on an `OfItem` holder and returns a shulker box stack; fake reader `b` opens `bag` on that stack and returns an iron ingot. Expect `NONE` and the iron ingot.
  - `missingAdapterFailsUnavailableAtItsStep`.
  - `swappedItemFailsStaleAtItsStepAndKeepsEarlierSteps`: step 1's slot now holds dirt. Expect `STALE` with `failedStep == 1` and `stack` being step 0's item.
  - `goneRootFailsRootGone`.
- [ ] **Step 2: Run them**, and expect them to fail to compile.
- [ ] **Step 3: Implement** the resolver.
- [ ] **Step 4: Run them**, and expect a pass.
- [ ] **Step 5: Commit.**

### Task 5: Grouping items

**Files:**
- Create: `src/main/java/dev/packsage/world/ItemGrouper.java`
- Test: `src/test/java/dev/packsage/world/ItemGrouperTest.java`

**Interfaces:**
- Produces: `final class ItemGrouper` with `void add(ItemPath location, ItemStack stack, boolean holdsItems)` and `List<ItemEntry> entries()`. It groups by item ID and sums counts. `variants` counts distinct `stack.getComponentsPatch()` values. It keeps the first 8 locations in insertion order. `holdsItems` is true if any grouped stack holds items. Entries are sorted by count, descending, then by ID.

- [ ] **Step 1: Write the failing test** `groupsByIdSumsCountsCountsVariantsAndCapsLocations`: 10 stacks of 64 stone at 10 paths, 2 plain iron swords and 1 named iron sword. Expect stone with count 640, 1 variant and 8 locations, and iron sword with count 3 and 2 variants.
- [ ] **Step 2: Run it**, and expect it to fail to compile.
- [ ] **Step 3: Implement** the grouper.
- [ ] **Step 4: Run it**, and expect a pass.
- [ ] **Step 5: Commit.**

### Task 6: Minecraft inventories

**Files:**
- Create: `src/main/java/dev/packsage/world/minecraft/MinecraftInventories.java`
- Test: `src/test/java/dev/packsage/world/minecraft/MinecraftInventoriesTest.java`

**Interfaces:**
- Produces: `final class MinecraftInventories implements InventoryReader`, with `adapterId()` returning `"minecraft"`. Sections follow the spec's table exactly. Entries are slot numbers as decimal strings, except `minecraft/bundle`, which uses content indexes. `inspectItem` returns these when present:
  - `name`, `customName`, `count`, `maxStackSize`;
  - `damage`, `maxDamage`;
  - `enchantments` and `storedEnchantments` (id to level);
  - `potion` (potion id and effects);
  - `attributeModifiers`;
  - `enchantmentEligibility`, reusing `NativeEnchantmentEligibility`;
  - `containerSlots` and `bundleCount`.

  Item holders use `DataComponents.CONTAINER` (shulker box) and `DataComponents.BUNDLE_CONTENTS`. Block holders use the block entity, as `Container`. The double chest uses `ChestBlock.getContainer` and names `otherHalf`. The ender chest block maps to the player's `getEnderChestInventory()`. Entity holders use `ContainerEntity`, `AbstractChestedHorse` (`minecraft/saddle`, `minecraft/chest`), `AbstractHorse` (`minecraft/body_armor`), `ArmorStand` and `Mob` equipment, `ItemFrame`, `Allay`, `Fox` (`minecraft/held`) and `Villager.getInventory()`.

- [ ] **Step 1: Write the failing tests:**
  - `shulkerAndBundleItemsExposeTheirSections`: a shulker box stack with stone in slot 3 lists `minecraft/container` with 27 slots, and `open(...).get("3")` is stone. A bundle lists `minecraft/bundle` with `slots == -1`.
  - `openingEmptyContainerItemsLeavesComponentsUnchanged`: for a fresh shulker box and a fresh bundle, `stack.getComponents()` is equal before and after `inventories`, `open`, `entries` and `inspectItem`.
  - `inspectItemReportsEnchantmentsDamageAndEligibility`: an iron sword with Sharpness 2 and 10 damage.
- [ ] **Step 2: Run them**, and expect them to fail.
- [ ] **Step 3: Implement** item holders, then block and entity holders. Block and entity holders need a live level and are covered by the live checks in Task 12.
- [ ] **Step 4: Run them**, and expect a pass.
- [ ] **Step 5: Commit.**

### Task 7: Minecraft blocks and entities

**Files:**
- Create: `src/main/java/dev/packsage/world/WorldAccessor.java`, `src/main/java/dev/packsage/world/minecraft/MinecraftWorld.java`, `src/main/java/dev/packsage/world/minecraft/MinecraftEntities.java`
- Test: `src/test/java/dev/packsage/world/minecraft/MinecraftWorldTest.java`

**Interfaces:**
- Produces:
  - `interface WorldAccessor { String adapterId(); String notableRole(BlockState state, BlockEntity entity); }`, returning null when the block is not notable. Scanning lives in the core (Task 8), so adapters only classify.
  - `MinecraftWorld implements WorldAccessor, BlockInspector`:
    - `notableRole`: containers (anything with an `InventoryReader` section or a `Container` block entity), beds, workstation blocks (the points of interest for villager professions: `PoiTypes`), fluids, light emitters over 0, redstone components, portals and spawners.
    - `inspectBlock` facts: `state` (property map), `blockLight`, `skyLight`, `poi` (point-of-interest type and free tickets), `bedOccupied` and `redstoneSignal`.
  - `MinecraftEntities implements EntityInspector` facts: `health`, `maxHealth`, `effects`, `customName`, `age`, `tamedOwner`, `leashed`, `passengers`. For a `Villager` also:
    - `profession`, `level`, `xp`, `restocksToday`;
    - `home`, `jobSite` and `meetingPoint` positions from `MemoryModuleType.HOME`, `JOB_SITE` and `MEETING_POINT`;
    - `lastSlept`, `lastWorkedAtPoi` and `golemDetectedRecently` (game times);
    - `gossip` summed by type.

    For an `IronGolem`: `playerCreated`.

- [ ] **Step 1: Write the failing test** `notableRolesClassifyBedsWorkstationsFluidsLightRedstoneAndContainers`: for default states of `red_bed`, `composter`, `water`, `torch`, `redstone_wire`, `chest` and `stone`, expect `bed`, `workstation`, `fluid`, `light`, `redstone`, `container` and null.
- [ ] **Step 2: Run it**, and expect it to fail to compile.
- [ ] **Step 3: Implement** both classes. Entity and block facts that need a live level are verified in Task 12.
- [ ] **Step 4: Run it**, and expect a pass.
- [ ] **Step 5: Commit.**

### Task 8: Area scans and item search

**Files:**
- Create: `src/main/java/dev/packsage/world/ItemSearch.java`, `src/main/java/dev/packsage/world/AreaScan.java`
- Test: `src/test/java/dev/packsage/world/ItemSearchTest.java`

**Interfaces:**
- Consumes: `ServerWork.Slice`, `WorldRegistry`, `ItemGrouper`, `PathResolver`.
- Produces:
  - `final class ItemSearch implements ServerWork.Slice<Page<ItemEntry>>`. Constructor: `ItemSearch(WorldRegistry registry, List<Map.Entry<PathRoot, LiveHolder>> holders, KindFilter filter, boolean includeNested, int startHolder)`.
  - Each `step` inspects one holder: every section, recursing into items that hold items while `includeNested` and the path depth stays at most 6. It adds matches to an `ItemGrouper`.
  - After 512 holders it stops with `nextCursor` = the next holder index and coverage `PARTIAL`.
  - Holders and nested containers count toward the 512.
  - Area scan slices (blocks per chunk, entities per AABB) live in `AreaScan`: `summary(level, center, radius)`, `listBlocks(level, center, radius, filter, cursor)` and `listEntities(level, center, radius, filter, cursor)`. Each loaded chunk is one step. Unloaded chunks are counted. Distance is spherical, `<= radius`.

- [ ] **Step 1: Write the failing tests** with fake holders made of `OfItem` shulker boxes and a fake reader:
  - `findsNestedMatchesAndReportsTheirPaths`;
  - `stopsAt512ContainersWithACursorThatContinues`: 600 holders. The first page is PARTIAL with cursor `"512"`, and the second page finishes the rest, COMPLETE.
  - `nestingStopsAtSixSteps`.
- [ ] **Step 2: Run them**, and expect them to fail.
- [ ] **Step 3: Implement** `ItemSearch` and `AreaScan`.
- [ ] **Step 4: Run them**, and expect a pass.
- [ ] **Step 5: Commit.**

### Task 9: `WorldTools` provider and tools

**Files:**
- Create: `src/main/java/dev/packsage/world/WorldTools.java`
- Modify:
  - `src/main/java/dev/packsage/model/PackSageTools.java`: the tool list, schemas and descriptions;
  - `src/main/java/dev/packsage/client/ClientAssistant.java:339-344`: register `WorldTools`;
  - `src/main/java/dev/packsage/client/ScreenText.java:31`: add `world` to `CITATION_MARKER`;
  - `src/main/java/dev/packsage/client/AssistantScreen.java:940`: add `("world", "World")`;
  - `src/main/java/dev/packsage/chat/AssistantRuntime.java:342-345`: add `world`.
- Test: `src/test/java/dev/packsage/world/WorldToolsTest.java`, `src/test/java/dev/packsage/model/PackSageToolsTest.java`

**Interfaces:**
- Consumes: everything above.
- Produces:
  - `final class WorldTools implements KnowledgeProvider`, with `id()` returning `"world"`, constructed as `WorldTools(Minecraft client, WorldRegistry registry, Supplier<Evidence.Scope> scope)`.
  - It overrides `handle` for these tools:

    | Tool | Arguments |
    |---|---|
    | `inspect_area` | `center?` (anchor object), `radius?` (integer 1–256) |
    | `list_blocks` | `center?`, `radius?`, `filter` (`{ids?, tags?, text?}`), `cursor?` |
    | `list_entities` | `center?`, `radius?`, `filter`, `cursor?` |
    | `inspect_block` | `place` (`"dim\|x,y,z"`) |
    | `inspect_entity` | `id` (UUID) |
    | `list_items` | `holder` (a root text or an item path text), `inventory?`, `filter?`, `cursor?` |
    | `inspect_item` | `path` |
    | `find_items` | `filter`, `center?`, `radius?`, `includeNested?` (default true), `cursor?` |

    The anchor JSON is `{"type":"player"}`, `{"type":"entity","id":...}` or `{"type":"block","place":...}`.
  - Each result becomes `Evidence.Record`s with provider `world`:
    - one record per handle, entry or inspection, with kind `BLOCK`, `ITEM` or `OTHER`;
    - `sourceHandle` is `block|<place>`, `entity|<uuid>`, `item|<path>` or `area|<anchor>|<radius>`;
    - merged sections go under payload `sections`.
  - Blocks and entities carry navigation targets `world-block|<place>` and `world-entity|<uuid>`.
  - `readEvidence` rereads `block|`, `entity|` and `item|` handles fresh.
  - Cursors are opaque UUID tokens bound to the tool and its arguments, kept in a 256-entry LRU like `KnowledgeService.Discovery`.
  - Without an integrated server, every tool fails `UNAVAILABLE`.
  - Each tool description states what it returns and the follow-up call ("list, then inspect what matters").

- [ ] **Step 1: Write the failing tests:**
  - `PackSageToolsTest.listsWorldTools`: the definitions include the eight names with the schemas above.
  - `WorldToolsTest.toolsAreUnavailableWithoutAnIntegratedServer`.
  - `WorldToolsTest.anchorAndPlaceArgumentsParseAndRejectMalformedValues`: `"minecraft:overworld|1,2"` fails `INVALID_ARGUMENTS`.
  - `WorldToolsTest.cursorsBindToTheirToolAndArguments`: a cursor from `find_items` reused with a different filter fails `INVALID_CURSOR`.
- [ ] **Step 2: Run them**, and expect them to fail.
- [ ] **Step 3: Implement** `WorldTools`, the definitions and the registrations.
- [ ] **Step 4: Run the two classes, then the full suite**, and expect a pass.
- [ ] **Step 5: Commit.**

### Task 10: Highlights

**Files:**
- Create: `src/main/java/dev/packsage/client/highlight/WorldHighlighter.java`
- Modify: `src/main/java/dev/packsage/world/WorldTools.java` (`refreshNavigation`, `open`); `src/main/java/dev/packsage/client/ScreenText.java` (label `Place` for `world` targets)
- Test: `src/test/java/dev/packsage/world/WorldNavigationTest.java`

**Interfaces:**
- Produces:
  - `final class WorldHighlighter` with `static void highlightBlock(BlockPlace place, long untilMillis)` and `static void highlightEntity(UUID id, long untilMillis)`. It is registered on `RenderLevelStageEvent` (stage `AFTER_TRANSLUCENT_BLOCKS`). Blocks get a see-through line box; entities get client-side `setGlowingTag(true)`, reverted at expiry. It only draws in the player's current dimension.
  - `WorldTools.refreshNavigation` and `open` take the target id `world-block|<place>` or `world-entity|<uuid>`.
    - **Block:** opening needs the same world and an existing non-air block at the place. A different dimension opens with the coordinates and the detail "in <dimension>".
    - **Entity:** opening needs the entity to be loaded and alive.
    - **Otherwise:** the result is `UNAVAILABLE`, and nothing is drawn.
  - Duration: 30,000 ms.

- [ ] **Step 1: Write the failing tests:**
  - `navigationIdsRoundTrip`.
  - `openingABrokenBlockOrDeadMobIsUnavailableAndDrawsNothing`, with a fake world lookup injected through a package-private `WorldTools` constructor taking `Function<BlockPlace, Boolean> blockExists` and `Function<UUID, Boolean> entityAlive`.
  - `savedTargetsResolveAfterAReloadWithANewScope`, following the persistent navigation contract.
- [ ] **Step 2: Run them**, and expect them to fail.
- [ ] **Step 3: Implement** the navigation and `WorldHighlighter`.
- [ ] **Step 4: Run them**, and expect a pass.
- [ ] **Step 5: Commit.**

### Task 11: Remove policy refusals from the storage reader

**Files:**
- Modify: `src/main/java/dev/packsage/knowledge/storage/NativeServerStorageReader.java` (the LAN, other-player, interaction-range and `LockCode` checks)
- Test: `src/test/java/dev/packsage/knowledge/storage/NativeServerStorageReaderTest.java`

- [ ] **Step 1: Change the tests** that assert those refusals so they assert a successful read instead. Add `lockedAndOutOfReachContainersAreRead`.
- [ ] **Step 2: Run them**, and expect a failure on the old refusals.
- [ ] **Step 3: Remove the four checks** and any detail text that names them.
- [ ] **Step 4: Run the storage tests**, and expect a pass.
- [ ] **Step 5: Commit.**

### Task 12: Prompt touch-up, build and live checks

**Files:**
- Modify: `src/main/java/dev/packsage/model/GroundingInstructions.java`: one paragraph under "Find evidence for the decision" naming `inspect_area`, `find_items` (before opening individual containers), list-then-inspect, and highlight links for found blocks and mobs. The full rewrite is the prompt plan.
- Create: `docs/validation/2026-10-22-v2-world-reading.md`
- Modify: `docs/roadmap/2026-10-22-v2-minecraft-only.md` (checklist), `CHANGELOG.md`

- [ ] **Step 1: Run the full build**, and expect it to pass with no failures.
- [ ] **Step 2: Live checks**, delegated to a computer-use test agent on the minimal fixture profile with the developer override cleared:
  1. **Storage.** Fill 20 chests. Put stone in one, and in a shulker box inside another. Ask "where is my stone?" Expect both found, the nested one by its path, and both links highlighting.
  2. **Farm facts.** Build a small iron farm with 3 villagers, one bed removed. Ask "why isn't my iron farm working?" Expect villager facts showing the unlinked villager.
  3. **Armor.** Wear partly enchanted armor. Ask "how can I make my armor stronger?" Expect remaining enchantments per piece with exclusions.
  4. **Nested mob storage.** A mule carrying a shulker box that holds a bundle. Ask "what's in the bundle in my mule's chest?" Expect an answer through the path.
  5. **Performance.** Open chat with "keep running" enabled during a `find_items` over 300 chests. Expect no visible stutter.
- [ ] **Step 3: Record** the results in the validation record, then update the v2 checklist and `CHANGELOG.md`.
- [ ] **Step 4: Commit** each document separately.
