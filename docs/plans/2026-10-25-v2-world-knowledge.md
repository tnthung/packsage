# v2 World Knowledge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the assistant read four more kinds of world knowledge from the integrated server, without changing anything:
- villager and wandering-trader offers;
- loot tables;
- the nearest biome or structure;
- the command tree.

**Architecture:**
- **New provider-side class.** The four new tools live in `dev.packsage.world.WorldKnowledgeTools`, so `WorldTools.java` (about 1,300 lines) does not grow further. `WorldTools` keeps provider id `world` and delegates the four tool names, plus the `loot|` and `command|` reread handles, to it. To make that possible, a few existing `WorldTools` helpers become package-private.
- **Vanilla readers.** The Minecraft-specific reading lives in `dev.packsage.world.minecraft`:
  - `MinecraftTrades`;
  - `MinecraftLoot`;
  - `LocateBiome`, `StructureCandidates`, `SavedStarts` and `LocateStructure`;
  - `CommandReader`.
- **Testing.** Each reader keeps its decisions in pure or bootstrapped-testable functions. The parts that need a live level are covered by the live checks.
- **Pacing.** Every server read runs through `ServerWork`. The one disk read (saved structure starts) runs on the game's own chunk I/O worker and is awaited on the PackSage worker, never on the server thread.

**Tech Stack:** Java 21, NeoForge 21.1.202 / Minecraft 1.21.1, Brigadier 1.3.10, JUnit 5.

Build with `JAVA_HOME=D:/Project/packsage/.tools/jdk/jdk-21.0.12.1 ./gradlew build -q` (bash). Run one class with `JAVA_HOME=D:/Project/packsage/.tools/jdk/jdk-21.0.12.1 ./gradlew test --tests '<class>' -q`. Results are in `build/test-results/test/*.xml`.

**Spec:** [v2 Minecraft completion](../specs/2026-10-25-v2-minecraft-completion.md), section 3 and acceptance checks 5–8. The rules in the [v2 world model](../specs/2026-10-22-v2-world-model.md) apply throughout: integrated-server reads, `ServerWork` pacing, no mutation, no policy limits, coverage, persistent navigation and singleplayer only.

**Order:** this is stage 3. Stage 1 (`2026-10-25-v2-item-reading-consolidation.md`) and stage 2 (`2026-10-22-v2-progress-statistics.md`) land first. Both also edit `PackSageTools`, `WorldTools` and `GroundingInstructions`. Keep every edit here additive:
- append tool names after whatever list exists;
- add `case` branches rather than restructuring;
- refer to methods by name, not line numbers.

The full prompt rewrite is stage 5 (`2026-10-22-v2-prompt-acceptance.md`). This plan adds only one short paragraph.

## Global Constraints

- **Singleplayer only.** Every new tool fails `UNAVAILABLE` when `Minecraft.getSingleplayerServer()` is null, through `WorldTools.server()`.
- **Nothing changes because PackSage read it:**
  - no trade generation;
  - no loot roll or unpack;
  - no chunk load or generation;
  - no region-file creation;
  - no command execution.
- **No policy limits.** Bounds only page work: a bounded result is `PARTIAL` with a cursor that continues it, never a refusal. A command the player may not run is still described.
- **Pacing.** Server work runs in `ServerWork` slices: 5 ms per tick, and a 500 ms call budget that triggers `stopEarly`. The game thread never waits on disk or on PackSage.
- **Coverage.** Every result reports coverage and says why it stopped early.
- **Locate bounds.** Radius is in chunks, defaults to 100 and is capped at 512 by the schema. The call budget makes larger searches `PARTIAL` with a cursor. Ids accept tags, such as `#minecraft:village`.
- **Locate links.** Results carry a persistent `world-place|<dimension>|x,y,z` link. Coordinates always appear in the answer text too.
- **Trades.** An entity whose offers were never generated reports `offers: "not generated yet"`.
- **Loot.** Tables come from `server.reloadableRegistries()`, so data-pack overrides apply. Unknown and empty tables each say so.
- **Commands.** Read for the player's own `CommandSourceStack`. The assistant never runs commands.
- **Commits.** Commit each step as soon as it is written. Generate the message with `$commit-message`, in the `[Add]`/`[Chg]`/`[Fix]`/`[Rmv]` format, with no AI attribution. Do not use formatting tools.

## Verified engine facts

Checked against the NeoForge 21.1.202 sources jar at `build/moddev/artifacts/neoforge-21.1.202-sources.jar` (the project's `neo_version`) and the Brigadier 1.3.10 sources in the Gradle cache.

| Fact | Source | Consequence |
|---|---|---|
| `AbstractVillager.getOffers()` sets `offers = new MerchantOffers()` and calls `updateTrades()` when `offers == null` | `AbstractVillager.java:100-111` | Never call it. Read the `protected MerchantOffers offers` field by reflection, as `MinecraftEntities` already reads `Villager.numberOfRestocksToday`. NeoForge 1.21.1 runs Mojang names at runtime. |
| `AbstractVillager.addAdditionalSaveData` calls `getOffers()` | `AbstractVillager.java:161-171` | The game itself generates offers whenever it saves the entity, such as on autosave or chunk unload. So "not generated yet" is mostly seen on villagers that changed profession recently (`Villager.setVillagerData` nulls `offers`) or spawned since the last save. This matters for live check 5. |
| `MerchantOffer.getCostA()` computes the demand-adjusted count from `baseCostA`, `demand`, `priceMultiplier` and `specialPriceDiff`, clamped to `[1, maxStackSize]`. `getBaseCostA`, `getItemCostB`, `getResult`, `getUses`, `getMaxUses`, `getDemand`, `getSpecialPriceDiff`, `getPriceMultiplier`, `getXp`, `shouldRewardExp` and `isOutOfStock` are plain getters | `MerchantOffer.java:99-195` | Safe to read on the server thread. |
| Player discounts are applied only when trading starts (`Villager.updateSpecialPrices`, which mutates `specialPriceDiff`): `-floor(reputation * priceMultiplier)`, then Hero of the Village `-max(floor((0.3 + 0.0625 * amplifier) * baseCount), 1)`. `Villager.getPlayerReputation(Player)` is a pure read | `Villager.java:480-498, 716-718` | Compute `costForPlayer` with the same formula without calling it. While `isTrading()`, `specialPriceDiff` already contains the discount, so use `getCostA()`. |
| `ReloadableServerRegistries.Holder.getLootTable(key)` returns `LootTable.EMPTY` for a missing key | `ReloadableServerRegistries.java:145-151` | To tell "unknown" from "empty", use `reloadableRegistries().get().lookup(Registries.LOOT_TABLE).flatMap(l -> l.get(key))`. |
| `BlockBehaviour.getLootTable()` is public and final. `EntityType.getDefaultLootTable()` gives `entities/<path>`. `Mob.getLootTable()` is public and final, and covers per-instance tables such as sheep colours | `BlockBehaviour.java:370`, `EntityType.java:1073`, `Mob.java:519` | Block and entity inputs are pure getters. |
| `RandomizableContainerBlockEntity.getItem`, `isEmpty`, `removeItem` and `setItem` call `unpackLootTable(null)`. `getLootTable()` and `getLootTableSeed()` do not | `RandomizableContainerBlockEntity.java:29-74` | Read pending loot only through `RandomizableContainer.getLootTable()` and `getLootTableSeed()`, as `MinecraftInventories.unopenedLoot` already does. |
| `LootTable.DIRECT_CODEC` encodes via NeoForge `CommonHooks.lootPoolsCodec`, whose name setter runs only in the decoder, and `ConditionalOps.decodeListWithElementConditions`, whose encoder is the plain list codec | `LootTable.java:37-46`, `CommonHooks.java:841-859`, `ConditionalOps.java:83-87` | `DIRECT_CODEC.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), table)` has no side effects. `HolderLookup.Provider.createSerializationContext` makes the `RegistryOps`. |
| `ServerLevel.findClosestBiome3d` → `BiomeSource.findClosestBiome3d` spirals with `BlockPos.spiralAround` and calls `getNoiseBiome(quartX, quartY, quartZ, sampler)`. The level is used only for build height. There is no chunk access, but it is one unbounded loop | `ServerLevel.java:1327`, `BiomeSource.java:78-105` | Re-implement the same spiral as a `ServerWork` slice: one column per step, with a horizontal step of 32 and a vertical step of 64, the `/locate biome` defaults. |
| `ChunkGenerator.getStructureGeneratingAt` calls `StructureManager.checkStructurePresence` → `StructureCheck.checkStart`. That calls `tryLoadFromStorage`, which does `storageAccess.scanChunk(...).join()`, a disk wait on the server thread. On `CHUNK_LOAD_NEEDED` it then calls `getChunk(x, z, ChunkStatus.STRUCTURE_STARTS)`, which generates chunks | `ChunkGenerator.java:137-310`, `StructureCheck.java:77-150` | Never call `findNearestMapStructure`, `checkStructurePresence` or `getChunk`. |
| `RandomSpreadStructurePlacement.getPotentialStructureChunk(seed, x, z)`, `StructurePlacement.isStructureChunk(state, x, z)` (placement, frequency and exclusion zone), `getLocatePos(chunk)`, `ChunkGeneratorStructureState.getPlacementsForStructure`, `getLevelSeed` and `getRingPositionsFor` are pure. The ring positions are computed when `ServerLevel` is constructed (`ensureStructuresGenerated`), so `join()` returns at once during play | `StructurePlacement.java:82-100`, `RandomSpreadStructurePlacement.java:70`, `ChunkGeneratorStructureState.java:170-187`, `ServerLevel.java:265` | These are safe for candidate generation. |
| `ServerChunkCache.getChunkNow(x, z)` returns only loaded full chunks. `ChunkAccess.getStartForStructure(structure)` is a map read | `ServerChunkCache`, `ChunkAccess.java:204` | A loaded chunk gives *found*, or rules the candidate out. |
| `IOWorker.scanChunk` runs on the chunk I/O mailbox and returns a future. It reads pending writes first, then `RegionFileStorage.scanChunk` → `getRegionFile`. That opens `r.X.Z.mca` with `StandardOpenOption.CREATE`, so it creates an empty region file when none exists | `IOWorker.java:173-190`, `RegionFileStorage.java:31-73`, `RegionFile.java:52-70` | Scan a saved chunk only when its region file already exists (`Files.isRegularFile`), and never `join` on the server thread. Without a region file, the chunk was never saved, so it stays *predicted*. |
| Saved chunk NBT holds `DataVersion`, `Status` (`ChunkStatus.byName`, `isOrAfter`) and `structures.starts.<structure id>.id` (`"INVALID"` when the start failed) | `ChunkSerializer.java:207, 295, 468`, `StructureCheck.java:111-150` | Classify without datafixing. A different `DataVersion` stays *predicted*. |
| `server.getCommands().getDispatcher()`. `Entity.createCommandSourceStack()` builds a record with the player's `getPermissionLevel()` and has no side effects. Brigadier provides `getSmartUsage(node, source)`, `getAllUsage(node, source, restricted)`, `findNode(path)`, `CommandNode.canUse(source)`, `getRedirect()` and `ArgumentCommandNode.getType()`. `ArgumentTypeInfos.byClass(type)` throws for unregistered types | `MinecraftServer.java:1652`, `Entity.java:3092`, `CommandDispatcher.java:394-616`, `ArgumentTypeInfos.java:171` | Pure tree reads, with no `execute` or `parse`. |

## Decisions

- **Locate links.** Structures and biomes are usually far away, in unloaded chunks. `world-block` requires an existing, loaded, non-air block, so it would report most results as unavailable. A new `world-place|<dimension>|x,y,z` target always links:
  - **Same dimension:** opening it draws a 30-second vertical marker column at x, z. When the place is beyond 128 blocks horizontally, the column is drawn 128 blocks from the camera in the place's direction.
  - **Different dimension:** opening it reports the coordinates and dimension, like `world-block` does.

  The place identity is stable, so saved links survive restarts.
- **Structure kinds:**
  - *found*: a loaded chunk holds a valid start, or a saved chunk of the current data version at or after `structure_starts` lists the start.
  - *ruled out*: the same reads show no start. The candidate is dropped.
  - *predicted*: placement and biome checks pass, and nothing confirms it otherwise. That includes no region file, an older data version, a scan timeout or a save in progress.
- **Unsupported placements.** Mod placements other than random spread and concentric rings are not predicted. They are named in the note, and the result is `PARTIAL`.
- **Up to 5 results.** `locate` returns up to 5 results, nearest first, so "the nearest village I have already visited" is answerable from the same call.
- **Loot paging.** Loot output is paged by whole pools within 24,576 UTF-8 bytes per page, with a cursor. A single pool larger than that has its entries cut and is `PARTIAL`.
- **Loot by entity.** `read_loot(entity)` also accepts a loaded entity UUID, which reads `Mob.getLootTable()` for per-instance tables such as a sheep's colour. An entity type id reads the type's default table. This is a small superset of the spec; see the open questions in the report.

## Review Focus

1. **A villager read twice before it ever traded.** Expect `offers: "not generated yet"` both times, with the `offers` field still null afterwards. A reader that generated the offers would show them on the second read. Test in Task 2.
2. **A predicted structure whose region file does not exist.** Expect no scan to be submitted, no `.mca` file created and the kind left as *predicted*. Test in Task 6.
3. **A modded structure set with an unknown placement type.** Expect the structure named in the note and `PARTIAL` coverage, with no crash and no silent "none found". Test in Task 6.
4. **`read_loot` for `minecraft:empty`, an unknown id and a block that drops nothing.** Expect three distinct, explicit results, never an error and never a silent empty page. Test in Task 3.
5. **`read_command("fill")` with permission level 0** (singleplayer without cheats). Expect the usage forms, the argument types and `canUse: false`, never a refusal. Test in Task 8.

---

## File Structure

```
src/main/java/dev/packsage/world/
  WorldKnowledgeTools.java   NEW  read_loot, locate, search_commands, read_command; loot| and command| rereads; records
  WorldTools.java            MOD  package-private seams; delegation in handle and reread
  WorldNavigation.java       MOD  world-place| target
src/main/java/dev/packsage/world/minecraft/
  MinecraftTrades.java       NEW  offers field read without generating; offer facts; costForPlayer
  MinecraftEntities.java     MOD  adds offers for AbstractVillager
  MinecraftLoot.java         NEW  table resolution, encoding, pool paging, item and tag summary
  LocateBiome.java           NEW  ServerWork.Slice spiral biome sampler
  StructureCandidates.java   NEW  candidate chunks per placement, in vanilla ring order
  SavedStarts.java           NEW  optional region pre-check, selective NBT visitor and classifier for PackSage's read-only region reader; no vanilla storage scan
  LocateStructure.java       NEW  ServerWork.Slice over candidates: loaded check, placement and biome prediction
  CommandReader.java         NEW  generic Brigadier tree reader
src/main/java/dev/packsage/client/highlight/WorldHighlighter.java   MOD  highlightPlace column
src/main/java/dev/packsage/model/PackSageTools.java                 MOD  four schemas and descriptions
src/main/java/dev/packsage/model/GroundingInstructions.java          MOD  one short paragraph
```

---

### Task 1: Tool surface and delegation

**Files:**
- Create: `src/main/java/dev/packsage/world/WorldKnowledgeTools.java`
- Modify:
  - `src/main/java/dev/packsage/world/WorldTools.java`: visibility of the helpers below; the `handle` switch; `reread`.
  - `src/main/java/dev/packsage/model/PackSageTools.java`: the name list, schema switch and description switch.
- Test: `src/test/java/dev/packsage/world/WorldKnowledgeToolsTest.java`, `src/test/java/dev/packsage/model/PackSageToolsTest.java`

**Interfaces:**
- Produces:
  - These `WorldTools` members move from private to package-private, with unchanged signatures:
    - `MinecraftServer server()`;
    - `<T> CompletionStage<T> run(MinecraftServer, ServerWork.Slice<T>)`;
    - `static <T> ServerWork.Slice<T> once(Supplier<T>)`;
    - `String remember(PackSageTools.ReadRequest, Object)`;
    - `static <T> T state(Object, Class<T>)`;
    - `static Evidence.JsonValue argument(ReadRequest, String)`, `static String requiredText(ReadRequest, String)` and `static Failure invalid(String)`;
    - `record Center` and `Center center(MinecraftServer, Anchor)`;
    - `static ServerLevel level(MinecraftServer, String)`, `static boolean loaded(ServerLevel, BlockPos)` and `static ServerPlayer host(MinecraftServer)`;
    - `static Evidence.Record evidence(...)`, `static Evidence.Result result(...)` and `static Evidence.Result single(Record)`;
    - `static Evidence.JsonString text(String)` and `static Evidence.JsonNumber number(long)`.

    It also gains `Executor worker()`.
  - `final class WorldKnowledgeTools`:
    - constructor `WorldKnowledgeTools(WorldTools calls)`;
    - `static final Set<String> TOOLS = Set.of("read_loot", "locate", "search_commands", "read_command")`;
    - `CompletionStage<Evidence.Result> handle(PackSageTools.ReadRequest request)`;
    - `CompletionStage<Evidence.Record> reread(String handle, Evidence.Scope scope)`, which handles `loot|` and `command|`.

    Each tool body here only validates its arguments, calls `calls.server()`, then fails `UNAVAILABLE` with `"not implemented yet"`. Tasks 3, 5, 7 and 8 fill them in.
  - `WorldTools.handle` adds `case "read_loot", "locate", "search_commands", "read_command" -> knowledge.handle(request);`. `WorldTools.reread` routes `loot|` and `command|` to `knowledge.reread`.
  - Argument validation, all `INVALID_ARGUMENTS`:
    - `read_loot` needs exactly one of `table`, `block`, `entity` and `place`;
    - `locate` needs exactly one of `biome` and `structure`;
    - `read_command` needs a non-blank `name`.
  - Schemas, appended after the existing names:

    | Tool | Properties | Required |
    |---|---|---|
    | `read_loot` | `table`, `block`: `{"type":["string","null"],"maxLength":256,"pattern":"^[a-z0-9_.-]+:[a-z0-9/._-]+$"}`; `entity`: `{"type":["string","null"],"maxLength":256}` (an id or a UUID); `place`: `{"type":["string","null"],"maxLength":300}`; `cursor`: `WORLD_CURSOR` | none |
    | `locate` | `biome`, `structure`: `{"type":["string","null"],"maxLength":257,"pattern":"^#?[a-z0-9_.-]+:[a-z0-9/._-]+$"}`; `center`: `WORLD_ANCHOR`; `radius`: `{"type":["integer","null"],"minimum":1,"maximum":512}`; `cursor` | none |
    | `search_commands` | `text`: `{"type":["string","null"],"maxLength":256}`; `cursor` | none |
    | `read_command` | `name`: `{"type":"string","maxLength":512}`; `cursor` | `name` |
  - Descriptions. These are exact copy:
    - `read_loot`: "Read a loot table as the game defines it, from a table id, a block id (its drops), an entity type id or loaded entity id (its death drops) or a container place (its pending loot and seed, before it is opened). Returns pools, rolls, entries, conditions and functions with item and tag ids, plus the item ids it can produce. It never rolls the table; explain chances from rolls, weights and conditions."
    - `locate`: "Find the nearest biome or structure (ids or tags such as #minecraft:village) around center within radius chunks (default 100), without loading or generating chunks. Biomes are sampled from the biome source; structures come back as found (present in a generated chunk) or predicted (placement and biome checks pass for an ungenerated chunk), up to 5 nearest first, each with a link that marks the place. Say which kind each result is."
    - `search_commands`: "List the server's commands matching text in their name or usage, with whether the player can run them and short usage. Read one with read_command. PackSage never runs commands; explain what to type."
    - `read_command`: "Read one command or subcommand path such as fill or execute if block: its usage forms for the player's permission level, every argument with its type and examples, redirects and whether the player can run it."

- [ ] **Step 1: Write the failing tests.**
  - `PackSageToolsTest.listsWorldKnowledgeTools`: the four names, properties and required arrays from the table above; locate radius `maximum` 512; and descriptions that do not start with `"Read current evidence for"`.
  - Update `exposesExactlyEighteenToolsAndKeepsOptionalArgumentsOptional`. Append the four names to `NAMES` after whatever stages 1 and 2 left there, and rename the method to match the new count.
  - `WorldKnowledgeToolsTest.toolsAreUnavailableWithoutAnIntegratedServer`. Use the `WorldToolsTest.tools()` pattern, with server supplier `() -> null`. Each of these fails `UNAVAILABLE`:
    - `read_loot {"table":"minecraft:entities/zombie"}`;
    - `locate {"structure":"#minecraft:village"}`;
    - `search_commands {}`;
    - `read_command {"name":"fill"}`.
  - `WorldKnowledgeToolsTest.argumentsNeedExactlyOneSource`. Each of these fails `INVALID_ARGUMENTS`:
    - `read_loot {}`;
    - `read_loot {"table":"a:b","block":"minecraft:stone"}`;
    - `locate {}`;
    - `locate {"biome":"minecraft:plains","structure":"minecraft:village_plains"}`;
    - `read_command {"name":"  "}`.
- [ ] **Step 2: Run** `PackSageToolsTest` and `WorldKnowledgeToolsTest`. Expect a compile failure.
- [ ] **Step 3: Implement** the seams, `WorldKnowledgeTools` and the definitions.
- [ ] **Step 4: Run both classes, then the full build.** Expect a pass.
- [ ] **Step 5: Commit.**

### Task 2: Villager and trader offers

**Files:**
- Create: `src/main/java/dev/packsage/world/minecraft/MinecraftTrades.java`
- Modify: `src/main/java/dev/packsage/world/minecraft/MinecraftEntities.java` (`inspectEntity`)
- Test: `src/test/java/dev/packsage/world/minecraft/MinecraftTradesTest.java`

**Interfaces:**
- Produces: `final class MinecraftTrades` with these members:
  - `static final Field OFFERS`: `AbstractVillager.class.getDeclaredField("offers")`, checked as type `MerchantOffers` and made accessible with `trySetAccessible`. It is null when unavailable, following `MinecraftEntities.restocksField`.
  - `static final int MAX_OFFERS = 64`.
  - `static MerchantOffers storedOffers(AbstractVillager merchant)`: the field value, or null. It never calls `getOffers()`. When `OFFERS` is null it throws `IllegalStateException("villager offers cannot be read without generating them")`, so the section reports `ERROR` instead of generating.
  - `static Evidence.JsonObject offer(MerchantOffer offer, int index, Integer costForPlayer)`, with these fields:
    - `index`;
    - `costA {item, count, baseCount}`, where `count` is `getCostA().getCount()` and `baseCount` is `getBaseCostA().getCount()`;
    - `costB {item, count}` when `getItemCostB()` is present;
    - `result {item, count, name}`;
    - `uses`, `maxUses` and `outOfStock`;
    - `specialPriceDiff`, `demand` and `priceMultiplier`;
    - `xp` and `rewardsXp`;
    - `costForPlayer`, when not null.
  - `static int costForPlayer(MerchantOffer offer, int reputation, int heroAmplifier)`, where `heroAmplifier` is -1 when the player has no Hero of the Village. It applies the vanilla formula from the verified facts to `baseCostA`, without mutating the offer, and clamps to `[1, maxStackSize]`.
  - `static void facts(AbstractVillager merchant, ServerPlayer player, Map<String, Evidence.JsonValue> values)` adds:
    - **`offers`:** `"not generated yet"` when the field is null; otherwise an array of up to `MAX_OFFERS` offers, with `offersStatus: "bounded"` beyond that.
    - **`trading`:** `merchant.isTrading()`.
    - **`playerReputation`:** for a `Villager` with a non-null player.
    - **`costForPlayer`:** set on each offer only when the merchant is a `Villager`, is not trading and the player is non-null.
    - **`pricesNote`:** "costA.count already includes demand; costForPlayer adds this player's reputation and Hero of the Village discounts, which the game applies when trading starts".
- Consumes: `MinecraftEntities.inspectEntity` calls `MinecraftTrades.facts(merchant, host, values)` for every `AbstractVillager`. The host is found as `MinecraftInventories.owner` finds it: the singleplayer profile's player. That logic is extracted to a package-private static `MinecraftInventories.host(MinecraftServer)` and reused.

- [ ] **Step 1: Write the failing tests.** Bootstrap with `SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();`.
  - `offersFieldResolvesToTheStoredOffers`: `OFFERS` is non-null and its type is `MerchantOffers`.
  - `readingAnUngeneratedVillagerLeavesOffersNull`: allocate a `Villager` with `sun.misc.Unsafe.allocateInstance`, so no constructor runs and `offers` stays null. `storedOffers` returns null twice, and `OFFERS.get(villager)` is still null afterwards.
  - `offerFactsReportCostsUsesAndPriceAdjustments`: `new MerchantOffer(new ItemCost(Items.EMERALD, 10), Optional.of(new ItemCost(Items.BOOK, 1)), new ItemStack(Items.ENCHANTED_BOOK), 3, 12, 2, 0.05f, 4)`. Expect:
    - `costA.count` 12 and `baseCount` 10;
    - `costB.item` `"minecraft:book"`;
    - `uses` 3, `maxUses` 12 and `outOfStock` false;
    - `demand` 4, `xp` 2 and `priceMultiplier` 0.05.
  - `costForPlayerAppliesReputationAndHeroDiscountsWithoutMutating`, on the same offer:
    - `costForPlayer(offer, 20, 0)` is 8;
    - `costForPlayer(offer, 0, -1)` is 12;
    - `costForPlayer(offer, 1000, 0)` is 1;
    - `offer.getSpecialPriceDiff()` is still 0 afterwards.
- [ ] **Step 2: Run** `MinecraftTradesTest`. Expect a compile failure.
- [ ] **Step 3: Implement** `MinecraftTrades` and the `MinecraftEntities` call.
- [ ] **Step 4: Run** `MinecraftTradesTest` and `MinecraftEntitiesTest`. Expect a pass.
- [ ] **Step 5: Commit.**

### Task 3: `read_loot`

**Files:**
- Create: `src/main/java/dev/packsage/world/minecraft/MinecraftLoot.java`
- Modify: `src/main/java/dev/packsage/world/WorldKnowledgeTools.java` (`readLoot`, the `loot|` reread)
- Test: `src/test/java/dev/packsage/world/minecraft/MinecraftLootTest.java`, `src/test/java/dev/packsage/world/WorldKnowledgeToolsTest.java`

**Interfaces:**
- Produces: `final class MinecraftLoot` with these members:
  - `static final int PAGE_BYTES = 24_576`, `static final int MAX_IDS = 256`.
  - `static ResourceKey<LootTable> table(String id)`. A malformed id throws `IllegalArgumentException`.
  - `static ResourceKey<LootTable> blockTable(String blockId)`. It uses `BuiltInRegistries.BLOCK.getOptional`, and an unknown block throws `IllegalArgumentException("unknown block …")`.
  - `static ResourceKey<LootTable> entityTypeTable(String typeId)`, using `EntityType.getDefaultLootTable()`.
  - `static ResourceKey<LootTable> entityTable(Entity entity)`: `Mob.getLootTable()` for a `Mob`, `LivingEntity.getLootTable()` otherwise, or null for a non-living entity.
  - `record Pending(ResourceKey<LootTable> table, long seed)` and `static Pending pending(BlockEntity entity)`. It returns null unless the entity is a `RandomizableContainer` with a non-null `getLootTable()`, and reads only `getLootTable()` and `getLootTableSeed()`.
  - `static Optional<LootTable> lookup(HolderLookup.Provider registries, ResourceKey<LootTable> key)`, using `registries.lookup(Registries.LOOT_TABLE)`.
  - `record Encoded(Evidence.JsonObject header, List<Evidence.JsonObject> pools, List<String> itemIds, List<String> tagIds, boolean idsBounded)` and `static Encoded encode(LootTable table, HolderLookup.Provider registries)`.
    - Encoding uses `DIRECT_CODEC.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), table).getOrThrow()` and `Evidence.deserializeObject`.
    - `header` is everything except `pools`.
    - `itemIds` and `tagIds` come from a walk of the encoded JSON: `"type":"minecraft:item"` entries give their `name`, and `"type":"minecraft:tag"` entries give `#name`. They are distinct, sorted and capped at `MAX_IDS`.
  - `record LootPage(List<Evidence.JsonObject> pools, int nextPool, boolean poolTrimmed)` and `static LootPage page(Encoded encoded, int fromPool)`.
    - It packs whole pools until `PAGE_BYTES` would be exceeded, and always at least one.
    - `nextPool` is -1 when no pools remain.
    - A first pool larger than `PAGE_BYTES` keeps leading `entries` that fit, and adds `entriesShown`, `entryCount` and `poolTrimmed = true`.
- `WorldKnowledgeTools.readLoot`. One `once` slice on the server thread resolves the key, looks up the table and encodes it. The rest is paged on the worker from an `Encoded` kept in the cursor state.
  - **Inputs:**
    - `table` reads that id;
    - `block` reads `blockTable`;
    - `entity` reads `entityTypeTable` when it is an id. When it is a UUID, it reads `entityTable` for that loaded entity, and a missing entity is `UNAVAILABLE`.
    - `place` needs a loaded chunk, else `UNAVAILABLE`. A block entity whose `pending` is null gives a COMPLETE record with `pending: false`, the block id and the note "this container has no pending loot: it was opened already or never had a loot table; its contents are read with list_items".
  - **Record:** handle `loot|<table id>`, kind `OTHER`, title `"Loot table <id>"`. Payload:
    - `table`;
    - `resolvedFrom` (`table`, `block:<id>`, `entity:<id or uuid>` or `place:<place>`);
    - `exists` and `empty`;
    - `header`;
    - `pools`, `poolCount` and `poolsShown`;
    - `itemIds` and `tagIds`;
    - `seed` and `pending: true` for a place input;
    - `readAt`: game time.

    A place input carries a `world-block` target to the container. Other inputs carry no target, because loot has no viewer.
  - **Unknown or empty tables:**
    - an unknown id gives `exists: false` with the note "no loot table has this id in the loaded data packs";
    - `minecraft:empty`, or a table without pools, gives `empty: true` with the note "this table drops nothing".

    Both are COMPLETE.
  - **Coverage:** `PARTIAL` when `nextPool != -1`, `poolTrimmed` or `idsBounded`. The cursor holds `(Encoded, nextPool)`.

- [ ] **Step 1: Write the failing tests.** Bootstrap, with `registries = VanillaRegistries.createLookup()`.
  - `blockAndEntityInputsResolveToTheirTables`: `blockTable("minecraft:stone")` is `minecraft:blocks/stone`, `entityTypeTable("minecraft:zombie")` is `minecraft:entities/zombie`, and `blockTable("minecraft:nope")` throws.
  - `encodingKeepsPoolsEntriesFunctionsAndReadableIds`. Use a table built with `LootTable.lootTable()`: one pool of `LootItem.lootTableItem(Items.ROTTEN_FLESH)` with `SetItemCountFunction.setCount(UniformGenerator.between(0, 2))`, plus `TagEntry.expandTag(ItemTags.LOGS)`. Expect `itemIds == ["minecraft:rotten_flesh"]`, `tagIds == ["#minecraft:logs"]`, and a pool containing `"minecraft:set_count"`.
  - `encodingTwiceIsIdenticalAndLeavesTheTableUnchanged`: equal `Encoded` values, and the same pool count before and after.
  - `largeTablesPageByWholePoolsWithinTheByteBound`: 300 pools each holding 10 item entries. Every page's pools serialize to at most `PAGE_BYTES`, the pages chained by `nextPool` cover all 300 exactly once, and the last page has `nextPool == -1`.
  - `anOversizedPoolIsTrimmedAndMarked`: one pool of 2,000 entries gives `poolTrimmed`, with `entriesShown < entryCount == 2000`.
  - `unknownEmptyAndNoDropTablesAreDistinct`: `lookup` of an unregistered key on a provider with an empty loot registry is empty. `encode(LootTable.EMPTY, …)` has no pools. `blockTable("minecraft:air")` is `minecraft:empty`. A `WorldKnowledgeTools` helper, `static Evidence.JsonObject lootPayload(String id, Optional<Encoded> table, LootPage page)`, gives `exists:false` for the first and `empty:true` for the other two.
- [ ] **Step 2: Run** `MinecraftLootTest` and `WorldKnowledgeToolsTest`. Expect a failure.
- [ ] **Step 3: Implement** `MinecraftLoot`, `readLoot`, `lootPayload` and the `loot|` reread, which repeats `read_loot` by table id.
- [ ] **Step 4: Run them.** Expect a pass.
- [ ] **Step 5: Commit.**

### Task 4: Place highlights

**Files:**
- Modify: `src/main/java/dev/packsage/world/WorldNavigation.java`, `src/main/java/dev/packsage/client/highlight/WorldHighlighter.java`
- Test: `src/test/java/dev/packsage/world/WorldNavigationTest.java`

**Interfaces:**
- Produces:
  - In `WorldNavigation`:
    - `record Destination.Place(BlockPlace place)`;
    - `static String placeId(BlockPlace place)`, giving `"world-place|" + place.text()`;
    - `parse` accepts the prefix;
    - `Client` gains `void highlightPlace(BlockPlace place, long untilMillis)`. Update the test fakes.
  - Opening a place:
    - with no player dimension, it is `UNAVAILABLE`;
    - in another dimension, it is `OPENED` with detail `"<x>, <y>, <z> in <dimension>"`;
    - in the same dimension, it calls `client.highlightPlace` with `now + HIGHLIGHT_MILLIS` and is `OPENED`, with no existence or loaded check;
    - `refresh` keeps it when well formed.
  - `WorldHighlighter.highlightPlace(BlockPlace, long)`: a vertical see-through column, one block wide, from the level's minimum to its maximum build height at x, z.
    - If the horizontal distance from the camera exceeds `MARKER_RANGE = 128`, the column is drawn at 128 blocks along the horizontal direction to the place.
    - It is drawn only in the matching dimension and expires like block highlights. Its look is the builder's choice.

- [ ] **Step 1: Write the failing tests:**
  - `placeIdsRoundTrip`;
  - `placesOpenWithoutAnExistenceCheckInTheSameDimension`: the fake `blockExists` returns false, and the fake client records one `highlightPlace` call;
  - `placesInAnotherDimensionOpenWithCoordinates`;
  - `savedPlaceTargetsRefreshIntoANewScope`, following the persistent navigation contract.
- [ ] **Step 2: Run** `WorldNavigationTest`. Expect a failure.
- [ ] **Step 3: Implement** the navigation and the column.
- [ ] **Step 4: Run them.** Expect a pass.
- [ ] **Step 5: Commit.**

### Task 5: Locating biomes

**Files:**
- Create: `src/main/java/dev/packsage/world/minecraft/LocateBiome.java`
- Modify: `src/main/java/dev/packsage/world/WorldKnowledgeTools.java` (`locate`, biome branch)
- Test: `src/test/java/dev/packsage/world/minecraft/LocateBiomeTest.java`

**Interfaces:**
- Produces: `final class LocateBiome implements ServerWork.Slice<LocateBiome.Result>` with these members:
  - `static final int HORIZONTAL_STEP = 32`, `static final int VERTICAL_STEP = 64`.
  - `interface Sampler { String biomeAt(int x, int y, int z); }`, taking block coordinates.
  - `record Hit(String biome, int x, int y, int z)`.
  - `record Result(Hit hit, int nextIndex, int columns)`, where `nextIndex` is -1 when the search finished.
  - Constructor: `LocateBiome(Predicate<String> matches, Sampler sampler, int x, int y, int z, int radiusChunks, int minY, int maxY, int startIndex)`.
  - Each `step` samples one spiral column. Columns follow `BlockPos.spiralAround(BlockPos.ZERO, radiusChunks * 16 / HORIZONTAL_STEP, Direction.EAST, Direction.SOUTH)`, skipping `startIndex` columns. Heights follow `Mth.outFromOrigin(y, minY + 1, maxY, VERTICAL_STEP)`. This is the same order as `BiomeSource.findClosestBiome3d`.
  - The slice stops at the first hit. `stopEarly` records `nextIndex` and returns true.
- Production sampler, built in one `then` step on the server thread: `level.getChunkSource().getGenerator().getBiomeSource().getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), level.getChunkSource().randomState().sampler())`, unwrapped to its key id. The predicate is the id, or the members of `level.registryAccess().registryOrThrow(Registries.BIOME).getTag(TagKey)`.
  - An unknown id or tag fails `INVALID_ARGUMENTS`, listing up to 16 registry ids that contain the query's path.
  - A biome or tag that the biome source cannot produce (`possibleBiomes()`) returns COMPLETE with the note "this dimension's biome source never produces it".
- `locate(biome)` results:
  - **Hit:** one record. Handle `locate|<dim>|<biome>|<x>,<z>`, kind `OTHER`, title `"<biome> at <x>, <y>, <z>"`. Payload: `kind: "sampled"`, `biome`, `query`, `dimension`, `place`, `distanceBlocks` (horizontal, rounded) and `resolution {horizontalBlocks: 32, verticalBlocks: 64}`. Target: `world-place`.
  - **No hit:** COMPLETE with no records and the note "no matching biome within <r> chunks".
  - **Stopped early:** PARTIAL with a cursor holding `(center, predicate inputs, nextIndex)`.

- [x] **Step 1: Write the failing tests:**
  - `sampledOrderMatchesTheVanillaSpiral`: with a fake sampler that records calls, radius 4 chunks visits the same (x, z) sequence as iterating `BlockPos.spiralAround` directly.
  - `stopsAtTheFirstMatchingColumn`: a fake sampler returns `minecraft:desert` only at x = 64, z = 0. The hit is at (64, y, 0), with `nextIndex == -1`.
  - `earlyStopResumesFromTheNextColumn`: after `stopEarly()` at column 10, a new slice with `startIndex = 10` visits column 10 first.
  - `noMatchWithinRadiusFinishesEmpty`.
- [x] **Step 2: Run** `LocateBiomeTest`. Expect a compile failure.
- [x] **Step 3: Implement** `LocateBiome` and the biome branch of `locate`.
- [x] **Step 4: Run them.** Expect a pass.
- [x] **Step 5: Commit.**

### Task 6: Structure candidates and saved-start verdicts

**Files:**
- Create: `src/main/java/dev/packsage/world/minecraft/StructureCandidates.java`, `src/main/java/dev/packsage/world/minecraft/SavedStarts.java`
- Test: `src/test/java/dev/packsage/world/minecraft/StructureCandidatesTest.java`, `src/test/java/dev/packsage/world/minecraft/SavedStartsTest.java`

**Interfaces:**
- Produces: `final class StructureCandidates` with these members:
  - `static boolean supported(StructurePlacement placement)`: true for `RandomSpreadStructurePlacement` and `ConcentricRingsStructurePlacement`.
  - `static List<ChunkPos> ring(RandomSpreadStructurePlacement placement, long seed, int centerChunkX, int centerChunkZ, int ring)`. This is exactly vanilla `ChunkGenerator.getNearestGeneratedStructure`'s cell order for ring `ring`: cells `centerChunk + spacing * (j, k)` on the ring border, mapped through `getPotentialStructureChunk(seed, …)`.
  - `static int rings(RandomSpreadStructurePlacement placement, int radiusChunks)`, giving `ceil(radiusChunks / spacing)`.
  - `static List<ChunkPos> concentric(List<ChunkPos> ringPositions, int centerChunkX, int centerChunkZ, int radiusChunks)`: positions within the radius, sorted by distance.
- Produces: `final class SavedStarts` with these members:
  - `enum Verdict { FOUND, ABSENT, UNKNOWN }`.
  - `static Path regionFile(Path dimensionFolder, ChunkPos chunk)`, giving `dimensionFolder/region/r.<rx>.<rz>.mca`.
  - `static boolean saved(Path dimensionFolder, ChunkPos chunk)`, which is `Files.isRegularFile(regionFile(...))`.
  - `static CollectFields selector()`: `DataVersion` (int), `Status` (string) and `structures`.`starts` (compound).
  - `static Verdict classify(Tag collected, String structureId, int currentDataVersion)`:
    - no tag, a different `DataVersion`, or a `Status` before `structure_starts` gives `UNKNOWN`;
    - a start present with `id != "INVALID"` gives `FOUND`;
    - otherwise `ABSENT`.

- [ ] **Step 1: Write the failing tests.** Bootstrap for the `StructurePlacement` registries.
  - `ringOrderMatchesVanilla`: `new RandomSpreadStructurePlacement(34, 8, RandomSpreadType.LINEAR, 10387312)`, seed `42L` and center (3, -7). For rings 0–2, the cell order equals an inline transcription of vanilla's loop (`j` outer, `k` inner, border cells only), mapped through `getPotentialStructureChunk`.
  - `ringCountCoversTheRadius`: spacing 34 with radius 100 gives 3 rings.
  - `concentricKeepsPositionsInRangeNearestFirst`.
  - `unknownPlacementTypesAreUnsupported`: an anonymous `StructurePlacement` subclass (Review Focus 3).
  - `SavedStartsTest.classifiesSavedStarts`, using hand-built `CompoundTag`s:
    - current version, `minecraft:full`, `starts.minecraft:village_plains.id = "minecraft:village_plains"` gives `FOUND`;
    - the same with `id = "INVALID"` gives `ABSENT`;
    - an empty `starts` gives `ABSENT`;
    - `Status = minecraft:empty` gives `UNKNOWN`;
    - `DataVersion` one lower gives `UNKNOWN`;
    - a null tag gives `UNKNOWN`.
  - `SavedStartsTest.noRegionFileMeansNotSavedAndCreatesNothing`: in a JUnit `@TempDir`, `saved(dir, new ChunkPos(40, 40))` is false, and the directory is still empty afterwards (Review Focus 2).
- [ ] **Step 2: Run both classes.** Expect a compile failure.
- [ ] **Step 3: Implement both classes.**
- [ ] **Step 4: Run them.** Expect a pass.
- [ ] **Step 5: Commit.**

### Task 7: Locating structures

**Files:**
- Create: `src/main/java/dev/packsage/world/minecraft/LocateStructure.java`
- Modify: `src/main/java/dev/packsage/world/WorldKnowledgeTools.java` (`locate`, structure branch)
- Test: `src/test/java/dev/packsage/world/minecraft/LocateStructureTest.java`, `src/test/java/dev/packsage/world/WorldKnowledgeToolsTest.java`

**Interfaces:**
- Consumes: `StructureCandidates`, `SavedStarts`, `WorldTools.worker()`.
- Produces: `final class LocateStructure implements ServerWork.Slice<LocateStructure.Result>` with these members:
  - `static final int RESULTS = 5`, `static final int SCANS = 8`.
  - `enum Kind { FOUND, PREDICTED }`.
  - `record Hit(String structure, int chunkX, int chunkZ, int x, int y, int z, Kind kind, String basis)`, where `basis` is one of `loaded chunk`, `saved chunk` and `placement and biome checks`.
  - `interface World`, so tests can fake it. All its methods run on the server thread and none of them may load chunks:
    - `boolean loaded(int chunkX, int chunkZ)`;
    - `StructureStart loadedStart(int chunkX, int chunkZ, Structure structure)`: called only when loaded; null when the chunk holds no valid start;
    - `boolean placementPasses(StructurePlacement placement, int chunkX, int chunkZ)`;
    - `boolean biomePasses(Structure structure, BlockPos locatePos)`;
    - `int surfaceY(int x, int z)`.
  - `record Target(String id, Structure structure, List<StructurePlacement> placements)`.
  - `record Result(List<Hit> hits, int nextRing, List<String> unsupported)`, with hits sorted by horizontal distance.
  - Constructor: `LocateStructure(World world, List<Target> targets, long seed, Function<ConcentricRingsStructurePlacement, List<ChunkPos>> rings, int centerChunkX, int centerChunkZ, int radiusChunks, int startRing)`.
  - **Each step** evaluates one (target, placement, candidate chunk):
    - loaded with a `loadedStart`: a `FOUND` hit, with y at the start bounding box's center;
    - loaded but no start: skipped;
    - not loaded, and both `placementPasses` and `biomePasses` hold: a `PREDICTED` hit at `placement.getLocatePos(chunk)`, with y from `surfaceY`.
  - **Order:** concentric candidates are evaluated first, all within radius. Random-spread rings follow, ring by ring. The slice finishes after the ring in which the hit count reaches `RESULTS`, or after the last ring.
  - **Unsupported placements** are listed by structure id.
  - **Early stop:** `stopEarly` finishes the current ring's bookkeeping, sets `nextRing` and returns true.
- Production `World`, built in a `then` step:
  - `loaded` is `level.getChunkSource().getChunkNow(x, z) != null`. `loadedStart` calls `getStartForStructure` on that chunk and keeps the start only when `isValid()`.
  - `placementPasses` is `placement.isStructureChunk(level.getChunkSource().getGeneratorState(), x, z)`.
  - `biomePasses` is `structure.biomes().contains(biomeSource.getNoiseBiome(quart(locatePos.x), quart(surfaceY), quart(locatePos.z), sampler))`.
  - `surfaceY` is `generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState)`.

  Targets resolve from `Registries.STRUCTURE` by id or tag, with the same unknown-id failure as biomes. Placements come from `getGeneratorState().getPlacementsForStructure(holder)`.
- **Confirmation in `WorldKnowledgeTools`**, on the worker after the slice:
  - For up to `SCANS` nearest `PREDICTED` hits, take `folder = DimensionType.getStorageFolder(level.dimension(), server.getWorldPath(LevelResource.ROOT))`. This is captured in the slice.
  - When `SavedStarts.saved(folder, chunk)`, submit `level.getChunkSource().chunkScanner().scanChunk(chunk, SavedStarts.selector())`. Await all of them with a 10-second timeout, never on the server thread.
  - Verdicts:
    - `FOUND`: kind `FOUND`, with basis `saved chunk`;
    - `ABSENT`: the hit is dropped;
    - `UNKNOWN`, a timeout or an exception: it stays `PREDICTED`.
  - Return the first `RESULTS` hits.
  - If no hits remain and `nextRing != -1`, return PARTIAL with a cursor from `nextRing` and the note "candidates in range were ruled out by saved chunks; continue to search further".
- **Records:** one per hit. Handle `locate|<dim>|<structure>|<x>,<z>`, kind `OTHER`, title `"<structure> (<found|predicted>) at <x>, <z>"`. Payload:
  - `kind` (`found` or `predicted`), `structure`, `query`;
  - `dimension`, `place`, `yEstimated` (true unless found in a loaded chunk);
  - `distanceBlocks`, horizontal;
  - `basis`;
  - for predicted hits, the note "should generate here from the world seed; terrain can still prevent it".

  Target: `world-place`. Coverage is PARTIAL when there are unsupported placements, a cursor or a scan timeout.

- [ ] **Step 1: Write the failing tests** with a fake `World` and a real `RandomSpreadStructurePlacement(34, 8, LINEAR, 10387312)`.
  - `loadedStartsAreFoundAndLoadedEmptyChunksAreSkipped`.
  - `unloadedCandidatesPassingPlacementAndBiomeArePredicted`, at `getLocatePos` with `yEstimated`.
  - `stopsAfterTheRingThatReachesFiveHits`: every candidate passes, so the result holds the hits of rings 0–1 only, sorted by distance, and `nextRing == 2`.
  - `unsupportedPlacementsAreNamed` (Review Focus 3).
  - `stopEarlyLeavesAResumableRing`.
  - `WorldKnowledgeToolsTest.savedVerdictsConfirmDropOrKeepPredictions`, through a package-private `static List<LocateStructure.Hit> confirm(List<Hit> hits, Function<ChunkPos, SavedStarts.Verdict> verdicts)`. The verdicts `FOUND`, `ABSENT` and `UNKNOWN` give one found hit, one dropped hit and one predicted hit.
- [ ] **Step 2: Run** `LocateStructureTest` and `WorldKnowledgeToolsTest`. Expect a failure.
- [ ] **Step 3: Implement** `LocateStructure`, the production `World`, `confirm` and the structure branch of `locate`.
- [ ] **Step 4: Run them, then the full build.** Expect a pass.
- [ ] **Step 5: Commit.**

### Task 8: Commands

**Files:**
- Create: `src/main/java/dev/packsage/world/minecraft/CommandReader.java`
- Modify: `src/main/java/dev/packsage/world/WorldKnowledgeTools.java` (`searchCommands`, `readCommand`, the `command|` reread)
- Test: `src/test/java/dev/packsage/world/minecraft/CommandReaderTest.java`

**Interfaces:**
- Produces: `final class CommandReader<S>` with these members:
  - `static final int PAGE = 64`, `static final int USAGE_PREVIEW = 4`, `static final int MAX_USAGE = 128`, `static final int MAX_ARGUMENTS = 256`.
  - Constructor: `CommandReader(CommandDispatcher<S> dispatcher, S source, Function<ArgumentType<?>, String> typeId)`.
  - `record Summary(String name, boolean canUse, List<String> usage, String redirect)`.
  - `record Argument(String path, String name, String type, List<String> examples, boolean canUse)`.
  - `record Detail(String path, boolean canUse, List<String> usage, List<String> playerUsage, List<Argument> arguments, boolean argumentsBounded, String redirect)`.
  - `List<Summary> search(String text)`: the root's children, sorted by name. `text`, when not null, matches the name or any full usage line case-insensitively. `usage` is the first `USAGE_PREVIEW` lines of `getAllUsage(node, source, false)`.
  - `Detail read(String path)`: a leading `/` is stripped and the path is split on spaces. It uses `findNode`, and returns null when the path is unknown.
    - `usage` is `getAllUsage(node, source, false)`, unrestricted, so a command the player cannot run is still described.
    - `playerUsage` is the values of `getSmartUsage(node, source)`.
    - `arguments` is a depth-first walk up to `MAX_ARGUMENTS` that does not follow redirects. `examples` is `getType().getExamples()`, up to 4.
    - `redirect` is `String.join(" ", dispatcher.getPath(node.getRedirect()))`.
  - It never calls `execute`, `parse` or `getCompletionSuggestions`.
- Production, in `WorldKnowledgeTools`. The reader is built in a `once` slice on the server thread: `new CommandReader<>(server.getCommands().getDispatcher(), host.createCommandSourceStack(), WorldKnowledgeTools::argumentType)`. `private static String argumentType(ArgumentType<?> type)` returns `BuiltInRegistries.COMMAND_ARGUMENT_TYPE.getKey(ArgumentTypeInfos.byClass(type)).toString()`, falling back to the class simple name on `IllegalArgumentException`.
  - **`search_commands`:** one record per summary, handle `command|<name>`, paged `PAGE` per page with an offset cursor. The page note gives `playerPermissionLevel`: the highest `n` from 4 down to 0 with `source.hasPermission(n)`.
  - **`read_command`:** one record, handle `command|<path>`. Payload: the `Detail` fields plus `playerPermissionLevel`. Usage lines beyond `MAX_USAGE` continue with the cursor (PARTIAL).
    - An unknown path is COMPLETE with no records and the note "no command <path>; search_commands lists them".
    - Every record carries the note "PackSage does not run commands".
  - Records carry no navigation target.

- [ ] **Step 1: Write the failing tests.** Use a plain `CommandDispatcher<Integer>` whose source is the permission level. Register:

  ```
  fill <from> <to> <block> [replace <filter>]   requires level >= 2
  say <message>                                  requires level >= 0
  gamerule keepInventory <value>                 requires level >= 2
  teleport <target>
  tp -> redirect to teleport
  ```

  Arguments are Brigadier `integer()` and `word()`, with `typeId` returning the class simple name.
  - `searchMatchesNamesAndUsage`: `search("fill")` gives one summary. `search(null)` gives every root command, sorted.
  - `readDescribesCommandsThePlayerCannotRun`: with source 0, `read("fill")` has `canUse == false`, non-empty `usage`, empty `playerUsage`, and arguments `from`, `to`, `block` and `filter` with types (Review Focus 5).
  - `readFollowsSubcommandPathsAndReportsRedirects`:
    - `read("/gamerule keepInventory")` has path `"gamerule keepInventory"` and one argument, `value`;
    - `read("fill")` lists `replace` within its usage;
    - `read("tp").redirect()` is `"teleport"`;
    - `read("nosuch")` is null.
  - `argumentWalkIsBounded`: a 300-argument chain gives `MAX_ARGUMENTS` arguments and `argumentsBounded`.
  - `readingNeverExecutes`: the commands' `executes` lambdas fail the test if they are called.
- [ ] **Step 2: Run** `CommandReaderTest`. Expect a compile failure.
- [ ] **Step 3: Implement** `CommandReader` and the two tools.
- [ ] **Step 4: Run them.** Expect a pass.
- [ ] **Step 5: Commit.**

### Task 9: Prompt mention and build

**Files:**
- Modify: `src/main/java/dev/packsage/model/GroundingInstructions.java`, adding a paragraph after the world-reading one under "Find evidence for the decision"
- Test: `src/test/java/dev/packsage/model/GroundingInstructionsTest.java`

**Interfaces:**
- The paragraph, exact copy: "Villager and wandering trader offers come with inspect_entity; say when offers are not generated yet. For drops and chest loot, read_loot reads the table definition; explain chances from it without rolling. To find a biome or structure, use locate, say whether each result was found or predicted, give coordinates and offer its link. For commands, read search_commands and read_command and explain what to type; you never run commands."

- [ ] **Step 1: Extend** `defaultInstructionsRouteCurrentStateEffectsAndPlans` with `assertTrue(text.contains("read_loot"))`, `assertTrue(text.contains("locate"))` and `assertTrue(text.contains("read_command"))`.
- [ ] **Step 2: Run it.** Expect a failure.
- [ ] **Step 3: Add the paragraph.**
- [ ] **Step 4: Run the full build.** Expect 0 failures in `build/test-results/test/*.xml`.
- [ ] **Step 5: Commit.**

### Task 10: Changelog and roadmaps

**Files:**
- Modify:
  - `CHANGELOG.md`: an entry under `### 2026-10-25`, or the current date when implemented;
  - `docs/roadmap/2026-10-22-v2-minecraft-only.md`: stage 3 sub-items;
  - the `## v2 branch` section of `docs/roadmap/2026-10-13-todo.md`;
  - the v2 section of `docs/roadmap/2026-10-21-adapter-feature-todo.md`.

- [ ] **Step 1: Write the changelog entry.** Say what the player can now ask: villager offers and prices, drops and chest loot, the nearest biome or structure (found or predicted) with a link that marks the place, and command usage. Say that nothing is generated, rolled, loaded or run. Give the automated test count. Mark live acceptance as pending.
- [ ] **Step 2: Update the v2 note.** Add four sub-items under stage 3: trades, `read_loot`, `locate` and commands. Mark each implemented with live acceptance pending, and leave them unchecked.
- [ ] **Step 3: Add the v2 lines to the other roadmaps.**
  - Tool roadmap: one unchecked line for `read_loot`, `locate`, `search_commands`, `read_command` and villager offers.
  - Adapter feature checklist: under the Minecraft adapter, add one unchecked entry for each feature: villager trades, loot tables, locate, commands. Group them by adapter status, then by feature.
- [ ] **Step 4: Check** the relative links in the edited files.
- [ ] **Step 5: Commit** each file's change as its own commit.

## Live acceptance

These checks run later, through the orchestrator, not the implementers. Use singleplayer, the installed build, the default prompt and a cleared developer override. Record the results in `docs/validation/2026-10-25-v2-world-knowledge.md`, then check the roadmap items that pass.

5. **Trades.**
   - **Librarian:** at a librarian that has been traded with, ask "what does this villager sell?" Expect its offers with prices from `inspect_entity`, cited.
   - **Never generated:** give a fresh villager a lectern, ask at once (before the next autosave), then ask again. Expect "not generated yet" both times; a read that generated trades would list offers the second time. Then right-click the villager to confirm that the game generates them normally.
6. **Loot.** Ask "what does a zombie drop?" Expect a reply from `read_loot entity minecraft:zombie`, with rotten flesh and the rare drops and their conditions, and no roll. Optional: an unopened dungeon chest by place reports its pending table and seed, and opening it afterwards still yields loot.
7. **Locating.** In a new world, ask "where is the nearest village?" Expect:
   - a position, found or predicted, and the link;
   - the link marks the place in the same dimension;
   - no new region files in `saves/<world>/region` after the call. Compare the directory listing before and after.

   Repeat near a visited village and expect `found`. Reopen the saved link after a full Minecraft restart.
8. **Commands.** Ask "how do I use /fill?" Expect usage from `read_command`, the argument types and, without cheats, a note that the player cannot run it yet.
