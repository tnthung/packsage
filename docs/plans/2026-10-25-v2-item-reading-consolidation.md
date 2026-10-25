# v2 Item Reading Consolidation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the world tools the only item-reading path. They gain everything `search_inventory` could read: `held`, `offhand`, `targeted`, `ridden` and `opened` holders, slot markers, full item facts, tooltips and a tooltip filter. Then `search_inventory` and its readers are removed. The four minor world-reading review findings N2–N5 are fixed on the way.

**Architecture:** Holder words are parsed and resolved by a pure `HolderWords` resolver. It works from a `Situation` captured on the server thread and a `ClientFocus` captured on the client thread (crosshair and screen title). It always returns concrete roots, except for menus, which get a new `PathRoot.Opened(containerId)` root and `LiveHolder.OfMenu`. `MinecraftMenus` maps a server-side menu into sections and excluded preview slots. Item facts move into `MinecraftInventories.inspectItem`, which now returns a `Section` so it can report PARTIAL, using the kept `NativeItemFacts` and `NativeEnchantmentEligibility` helpers. Tooltips are core and adapter-independent: `ItemTooltips` always reads a copy of the stack. `list_items` pages lead with one holder summary record (section sizes, empty slots, menu facts). Removal comes last, in two tasks: first the model-facing tool and prompt, then the readers and their handle kinds.

**Tech Stack:** Java 21, NeoForge 21.1 / Minecraft 1.21.1, JUnit 5. Full build: `JAVA_HOME=D:/Project/packsage/.tools/jdk/jdk-21.0.12.1 ./gradlew build -q` (bash). One class: `JAVA_HOME=D:/Project/packsage/.tools/jdk/jdk-21.0.12.1 ./gradlew test --tests '<fully.qualified.Class>' -q`. Results are in `build/test-results/test/*.xml`.

**Spec:** [v2 Minecraft completion](../specs/2026-10-25-v2-minecraft-completion.md), sections 1 (One item-reading path) and 2 (Review findings), under the rules of the [v2 world model](../specs/2026-10-22-v2-world-model.md). N2–N5 locations and descriptions: `.superpowers/sdd/2026-10-22-v2-world-reading/final-rereview.md`. Stage 3 (trades, loot, locate, commands) is a separate plan, `2026-10-25-v2-world-knowledge.md`. The full prompt rewrite is the later prompt plan, `2026-10-22-v2-prompt-acceptance.md`.

## Global Constraints

- Singleplayer only: every world tool reports `UNAVAILABLE` without an integrated server. Reads run on the integrated server thread in `ServerWork` slices, at most 5 ms per server tick.
- Nothing in the world changes because PackSage read it:
  - Menu reads never call `Slot.set`, `setChanged`, `broadcastChanges`, `slotsChanged`, `clicked` or `quickMoveStack`.
  - Tooltips are computed on `stack.copy()`.
  - Component encodings read without writing.
  - An exception in any read becomes a status (`unknown`) and PARTIAL coverage, never a failed call.
- No policy limits; bounds only page work. A bounded result is PARTIAL with a note.
- Words resolve once, at call time. Returned paths use the concrete root (`player:`, `block:`, `entity:`), except menus (`opened:<containerId>`). Opened paths are valid only while that menu is open, and they produce no link.
- When a word cannot resolve, the result is not an error. It has no records, `COMPLETE` coverage and outcome, and a detail that says why. The detail strings are fixed:
  - `Your main hand is empty`
  - `Your offhand is empty`
  - `Nothing is under the crosshair`
  - `You are not riding anything`
  - `No menu is open`
- Tooltip lines are the ones shown with advanced tooltips off (`TooltipFlag.NORMAL`). They are bounded to 32 lines of at most 256 characters each.
- Raw item components are bounded to 8,192 encoded bytes.
- A tool page is at most 64 records (`AreaScan.LIST_PAGE`, the `PackSageTools` output cap), and holder summary and holder records count toward it.
- Saved chats keep their recorded sources and links. Only fresh rereads of removed handle kinds (`inventory:`, `inventory-summary:`, `storage:`, `storage-entry:`) report UNAVAILABLE.
- The build stays green after every task. `search_inventory` is removed only after the world tools cover all of its capabilities (Tasks 4–10). The prompt and tool descriptions change in the same task that removes the tool (Task 11).
- Follow the existing style. Never use formatting tools.
- Commit each work step as soon as it is written, using `[Add]`/`[Chg]`/`[Fix]`/`[Rmv]` messages generated with `$commit-message` from the staged index. No AI attribution.
- Implementers run all automated tests themselves. Live checks are run later by the orchestrator, not by implementers.

## Review Focus

1. **The player swaps the held item, or closes or reopens a menu, between a list and an inspect.**
   - A `held` word resolves to the slot selected at call time.
   - An old concrete `player:…>minecraft/main#3@…` path whose slot changed reports the existing STALE failure.
   - An `opened:<id>` path after the menu closed, or after a different menu opened, reports UNAVAILABLE "the menu is no longer open" and never reads the new menu.
   - Tests in Task 7 (`heldResolvesToTheSelectedSlotAtCallTime`) and Task 9 (`openedPathsFailOnceTheMenuChanges`).
2. **A modded item's tooltip throws or never ends.**
   - `inspect_item` still returns, with `tooltipStatus: "unknown"` (or `"bounded"`) and PARTIAL coverage.
   - A tooltip-filtered search skips that item and says so, PARTIAL.
   - Tests in Task 5 (`aThrowingTooltipMarksStatusUnknownInsteadOfFailing`) and Task 6 (`itemsWhoseTooltipCannotBeReadAreNotMatchedAndMakeThePagePartial`).
3. **Inspecting an item nothing can enchant, such as stone or a stick.** `enchantmentEligibility` is present, with `supported: []` and an explicit `none` note, and is never left out. Test in Task 4 (`everyItemReportsEnchantmentEligibilityEvenWhenNothingApplies`).
4. **A crafting table or stonecutter with an output preview showing.** The preview is not in contents and is listed under `excludedSlots`. A donkey's inactive armor slot is skipped. A modded menu still lists its slots under `minecraft/menu`, PARTIAL with a note. Tests in Task 9.
5. **A saved chat cites an old `inventory:` or `storage:` record.** Opening the chat still shows the source and its link. A fresh `read_evidence` of that handle reports UNAVAILABLE with a pointer to the world tools, not an exception or `INVALID_REFERENCE`. Test in Task 12 (`removedInventoryHandlesRereadAsUnavailable`).

---

## File Structure

```
src/main/java/dev/packsage/world/
  PausedWorkPump.java      new: paused-server ServerWork driver (N2, N3)
  HolderWords.java         new: parse and resolve held/offhand/targeted/ridden/opened
  ClientFocus.java         new: crosshair root, open container id and screen title, captured on the client thread
  ItemTooltips.java        new: bounded tooltip lines from a copy, with status
  PathRoot.java            + Opened(int containerId)
  ItemPath.java            + "opened:<id>" root text; parseSteps(String) shared with HolderWords
  LiveHolder.java          + OfMenu(AbstractContainerMenu menu, Container playerInventory)
  KindFilter.java          + tooltip fragments
  InventoryReader.java     inspectItem returns Section; + default entryFacts(...)
  WorldRegistry.java       inspectItem collects Sections
  ItemSearch.java          tooltip matching; N4 progress guard
  WorldTools.java          words, holder summary record, slot facts, tooltips, N4, N5
src/main/java/dev/packsage/world/minecraft/
  MinecraftInventories.java  full item facts, always-present eligibility, player slot facts, OfMenu sections
  MinecraftMenus.java        new: menu layout (sections, excluded preview slots, menu type)
src/main/java/dev/packsage/knowledge/nativegame/NativeItemFacts.java   public helpers (kept)
src/main/java/dev/packsage/client/ClientAssistant.java                 uses PausedWorkPump; passes ClientFocus supplier
src/main/java/dev/packsage/client/GameplayContextCapture.java          targetedHolder, ridden; ClientFocus capture
src/main/java/dev/packsage/model/PackSageTools.java                    item filter schema; descriptions; then search_inventory removal
src/main/java/dev/packsage/model/GroundingInstructions.java            minimal prompt swap (Task 11)
Removed in Tasks 11–12: knowledge/nativegame/NativeInventoryReader, knowledge/storage/*, knowledge/inventory/InventoryReader,
  inventory plumbing in Toolbox, AdapterRegistry, NativeProvider and ReferenceAliasBackend, and their tests.
```

---

### Task 1: Paused-work pump (N2, N3)

**Files:**
- Create: `src/main/java/dev/packsage/world/PausedWorkPump.java`
- Modify: `src/main/java/dev/packsage/client/ClientAssistant.java:88` (drop `pausedWorkQueued`), `:158` (also call `pump.reset()` on `ServerStoppingEvent`) and `:161-172` (`tickPausedServerWork` delegates to the pump)
- Test: `src/test/java/dev/packsage/world/PausedWorkPumpTest.java`

**Interfaces:**
- Produces:
  - `public final class PausedWorkPump`, constructed as `PausedWorkPump(ServerWork work)`.
  - `public interface Server { Object identity(); boolean isPaused(); boolean isStopped(); boolean isSameThread(); void execute(Runnable task); }`. `ClientAssistant` adapts `MinecraftServer` with `identity()` returning the server instance itself.
  - `public void poll(Server server)` is called from the client tick. It queues one task when the server is paused, `work.hasWork()` holds, and no task is in flight *for this identity*. A flag left over from another identity is replaced, which fixes N2.
  - The queued task clears its own flag (`compareAndSet(identity, null)`). It then calls `work.tick()` only if `!server.isStopped() && server.isSameThread() && server.isPaused()`, which fixes N3.
  - `public void reset()` clears the in-flight flag. `ClientAssistant` calls it on `ServerStoppingEvent`.

- [ ] **Step 1: Write the failing tests:**
  - `aTaskThatNeverRanDoesNotBlockTheNextServer`: fake server A's `execute` drops the task. A second `poll(A)` queues nothing, and `poll(B)` queues one task.
  - `resetClearsATaskThatNeverRan`: after `reset()`, `poll(A)` queues again.
  - `aTaskRunInlineOffTheServerThreadDoesNotTick`: the fake runs the task inline with `isSameThread() == false`, or `isStopped() == true`. A queued `ServerWork` job's slice records zero steps.
  - `aPausedServerTicksQueuedWorkOnItsThread`: with the fake on its thread, the slice steps and completes.
- [ ] **Step 2: Run `PausedWorkPumpTest`.** Expect a compile failure.
- [ ] **Step 3: Implement** `PausedWorkPump` and wire it into `ClientAssistant`.
- [ ] **Step 4: Run `PausedWorkPumpTest`, then the full build.** Expect a pass.
- [ ] **Step 5: Commit.**

### Task 2: Early stops always make progress (N4)

**Files:**
- Modify: `src/main/java/dev/packsage/world/ItemSearch.java:118-122` (`stopEarly`), `src/main/java/dev/packsage/world/WorldTools.java:579-586` (`FindSlice.stopEarly`)
- Test: `src/test/java/dev/packsage/world/ItemSearchTest.java`, `src/test/java/dev/packsage/world/WorldToolsTest.java`

**Interfaces:**
- Produces:
  - `ItemSearch.stopEarly()` returns false until this page has searched at least one non-replayed container (`containers > 0`). After that it stops as today.
  - `static boolean WorldTools.gatherCanStop(int chunkCount, int next, int startChunk)` is true only when `next > startChunk && next < chunkCount`. `FindSlice` records `startChunk` when it first sets `chunks`: 0 for a fresh search, and `gather.nextChunk()` on resume. Its `stopEarly` uses this rule.

- [ ] **Step 1: Write the failing tests:**
  - `ItemSearchTest.aStopDuringReplayWaitsForOneNewContainer`: use the fake reader with one holder that has three nested containers, and cursor `"0.2"`. After the first step (a replay), `stopEarly()` is false. Step until one new container is searched; then `stopEarly()` is true, and the result's cursor is `"0.3"` (not `"0.2"`).
  - `ItemSearchTest.aStopBeforeAnyContainerKeepsStepping`: on a fresh search, `stopEarly()` before any step is false.
  - `WorldToolsTest.gatheringStopsOnlyAfterAtLeastOneNewChunk`:
    - `gatherCanStop(10, 4, 4)` is false;
    - `gatherCanStop(10, 5, 4)` is true;
    - `gatherCanStop(10, 10, 0)` is false.
- [ ] **Step 2: Run both classes.** Expect failures.
- [ ] **Step 3: Implement** both guards.
- [ ] **Step 4: Run both classes.** Expect a pass.
- [ ] **Step 5: Commit.**

### Task 3: `inspect_area` keeps the entity page's note (N5)

**Files:**
- Modify: `src/main/java/dev/packsage/world/WorldTools.java:1095-1109` (`area`)
- Test: `src/test/java/dev/packsage/world/WorldToolsTest.java`

**Interfaces:**
- Produces: `static List<String> areaNotes(Page<BlockSummary> blocks, Page<EntityHandle> entities)`. `area` uses it, and it returns the notes in this order:
  1. the block page's note;
  2. the notable cap note;
  3. the cut-entity-page note, when `nextCursor != null`;
  4. the entity page's own note, whenever it is non-null, even when the page was cut.

- [ ] **Step 1: Write the failing test** `inspectAreaKeepsTheEntityPageNoteWhenItsListIsCut`. The entity page has a `nextCursor` and the note `"2 chunks in range did not have their entities loaded yet"`. Both the list_entities pointer and that note are returned.
- [ ] **Step 2: Run it.** Expect a failure.
- [ ] **Step 3: Implement** `areaNotes`.
- [ ] **Step 4: Run `WorldToolsTest`.** Expect a pass.
- [ ] **Step 5: Commit.**

### Task 4: Full item facts in `inspect_item`

**Files:**
- Modify:
  - `src/main/java/dev/packsage/world/InventoryReader.java`: `inspectItem` returns `Section`;
  - `src/main/java/dev/packsage/world/WorldRegistry.java:55-61, 89-98`: collect `Section`s; a null return adds nothing; a throw becomes ERROR as today;
  - `src/main/java/dev/packsage/world/minecraft/MinecraftInventories.java:314-383`;
  - `src/main/java/dev/packsage/knowledge/nativegame/NativeItemFacts.java`: public helpers;
  - `src/main/java/dev/packsage/model/PackSageTools.java`: the `inspect_item` description;
  - the fake readers in `ItemSearchTest`, `PathResolverTest` and `WorldRegistryTest`.
- Test: `src/test/java/dev/packsage/world/minecraft/MinecraftInventoriesTest.java`, `src/test/java/dev/packsage/knowledge/nativegame/NativeItemFactsTest.java` (create if absent)

**Interfaces:**
- Produces:
  - `Section InventoryReader.inspectItem(ItemStack stack)`, returning null when the reader has nothing to add.
  - `public static String NativeItemFacts.componentFingerprint(ItemStack, HolderLookup.Provider)`, returning null when unavailable.
  - `public static Evidence.JsonObject NativeItemFacts.potionContents(ItemStack)`: the existing body, made public.
  - `public static Evidence.JsonObject NativeItemFacts.components(ItemStack stack, HolderLookup.Provider registries)`. It encodes `stack.getComponents()` without `CONTAINER` and `BUNDLE_CONTENTS` through `DataComponentMap.CODEC` (those two are read through inventories). It returns `{"status":"bounded"}` over `MAX_COMPONENT_BYTES = 8192` serialized bytes, and `{"status":"unknown"}` when encoding throws or fails.
  - The `MinecraftInventories.inspectItem` facts:
    - existing keys stay;
    - `components`, from `NativeItemFacts.components`;
    - `componentFingerprint`, or null plus `componentFingerprintStatus: "unknown"`;
    - `potion` is replaced by the full `potionContents` output: potion id, `customColor`, `durationScale`, each effect's `amplifier`, `durationTicks`, `ambient`, `visible`, `showIcon` and `origin`, and `effectsStatus` when bounded;
    - `enchantmentEligibility` is always present. When `supported` is empty, it adds `none: "No enchantment can be applied to this item"`.
  - Section coverage is PARTIAL when any of these holds, otherwise COMPLETE:
    - `components` has a `status`;
    - the fingerprint is unknown;
    - `effectsStatus` is present;
    - the eligibility `status` is not `complete` (use `NativeEnchantmentEligibility.complete`);
    - eligibility is unavailable.

- [ ] **Step 1: Write the failing tests:**
  - `inspectItemReportsComponentsFingerprintAndFullPotionFacts`: a splash potion with a custom effect has `potion.effects[*]` carrying `ambient`, `visible`, `showIcon` and `durationScale` 1. Two stacks of the same id with different custom names get different `componentFingerprint`s.
  - `everyItemReportsEnchantmentEligibilityEvenWhenNothingApplies`: stone has `enchantmentEligibility.supported == []`, the `none` note is present, and the section is COMPLETE.
  - `boundedEligibilityOrComponentsMakeTheSectionPartial`: an item with a component payload over 8,192 bytes, such as a long written-book page list, gives `components.status == "bounded"` and a PARTIAL section.
  - `NativeItemFactsTest.componentsLeaveOutNestedContents`: a filled shulker box encodes without `minecraft:container`.
  - Keep `openingEmptyContainerItemsLeavesComponentsUnchanged`, extended to call the new `inspectItem`.
- [ ] **Step 2: Run both classes.** Expect failures.
- [ ] **Step 3: Implement** the signature change, the helpers and the facts.
- [ ] **Step 4: Run the full build.** Expect a pass.
- [ ] **Step 5: Commit.**

### Task 5: Tooltip lines in `inspect_item`

**Files:**
- Create: `src/main/java/dev/packsage/world/ItemTooltips.java`
- Modify: `src/main/java/dev/packsage/world/WorldTools.java:434-450` (`inspectItem` payload), `src/main/java/dev/packsage/model/PackSageTools.java` (`inspect_item` description mentions `tooltip`)
- Test: `src/test/java/dev/packsage/world/ItemTooltipsTest.java`

**Interfaces:**
- Produces:
  - `public final class ItemTooltips` with `public record Tooltip(List<String> lines, String status)`, where status is `complete`, `bounded` or `unknown`.
  - `public static ItemTooltips of(ServerPlayer viewer)`. Its source is `copy.getTooltipLines(Item.TooltipContext.of(viewer.level()), viewer, TooltipFlag.NORMAL)`.
  - A package-private `ItemTooltips(Function<ItemStack, List<Component>> source)`.
  - `public Tooltip lines(ItemStack stack)`:
    - it always passes `stack.copy()` to the source;
    - it maps each line with `Component::getString`;
    - it bounds output to `MAX_LINES = 32` lines and `MAX_LINE_LENGTH = 256` characters, which gives `bounded`;
    - any `Throwable` except `VirtualMachineError` gives `Tooltip(List.of(), "unknown")`.
  - `WorldTools.inspectItem` adds `tooltip` (array) and `tooltipStatus` to the record payload, using `ItemTooltips.of(host(server))`. The record is PARTIAL unless the status is `complete`.

- [ ] **Step 1: Write the failing tests:**
  - `tooltipLinesAreBoundedAndComputedOnACopy`: the source records the stack instance it receives. It is not the original, and the original's components are unchanged. 40 lines give 32 lines and `bounded`.
  - `aThrowingTooltipMarksStatusUnknownInsteadOfFailing`.
- [ ] **Step 2: Run `ItemTooltipsTest`.** Expect a compile failure.
- [ ] **Step 3: Implement** `ItemTooltips` and the payload fields.
- [ ] **Step 4: Run `ItemTooltipsTest` and the full build.** Expect a pass.
- [ ] **Step 5: Commit.**

### Task 6: Tooltip filter for `list_items` and `find_items`

**Files:**
- Modify:
  - `src/main/java/dev/packsage/world/KindFilter.java`;
  - `src/main/java/dev/packsage/world/ItemSearch.java`;
  - `src/main/java/dev/packsage/world/WorldTools.java`: `filter(...)` at `:1074-1080`, `items(...)` at `:478-486` and `FindSlice`;
  - `src/main/java/dev/packsage/model/PackSageTools.java`: a new `WORLD_ITEM_FILTER` for `list_items` and `find_items`. `WORLD_FILTER` stays for blocks and entities.
- Test: `src/test/java/dev/packsage/world/WorldTypesTest.java`, `src/test/java/dev/packsage/world/ItemSearchTest.java`, `src/test/java/dev/packsage/model/PackSageToolsTest.java`

**Interfaces:**
- Consumes: `ItemTooltips.Tooltip` (Task 5).
- Produces:
  - `record KindFilter(List<String> ids, List<String> tags, String text, List<String> tooltip)`, keeping the 3-argument constructor (tooltip empty).
  - `boolean matches(String id, Collection<String> tags, String name, Supplier<ItemTooltips.Tooltip> tooltip)`. Tooltip fragments are OR among themselves and AND with the other fields. Each is a case-insensitive substring of any line. The supplier is called only when `tooltip` is non-empty and the other fields already matched. A `unknown` tooltip never matches.
  - The existing 3-argument `matches` is unchanged.
  - The `ItemSearch` constructor gains `Function<ItemStack, ItemTooltips.Tooltip> tooltips`. The old constructor delegates with `stack -> new ItemTooltips.Tooltip(List.of(), "complete")`.
  - Items whose tooltip is `unknown` or `bounded` add the note `Tooltips of N items could not be read in full, so the tooltip filter may have missed them`, and the page is PARTIAL.
  - Schema `WORLD_ITEM_FILTER` is `WORLD_FILTER` plus `tooltip: {type: [array, null], maxItems: 32, items: {type: string, maxLength: 256}}`. `WorldTools.filter` rejects `tooltip` for `list_blocks` and `list_entities` with `INVALID_ARGUMENTS`.
  - The `list_items` and `find_items` descriptions say that `filter.tooltip` matches the tooltip lines shown to the player, for example `["Luck"]`.

- [ ] **Step 1: Write the failing tests:**
  - `WorldTypesTest.tooltipFragmentsMatchCaseInsensitivelyAndCombineWithOtherFields`.
  - `ItemSearchTest.aTooltipFilterFindsTheItemInOneSearch`: an injected tooltip function gives a nested item "+1 Luck"; filter `["luck"]` finds it by path.
  - `ItemSearchTest.itemsWhoseTooltipCannotBeReadAreNotMatchedAndMakeThePagePartial`.
  - `PackSageToolsTest.onlyItemToolsAcceptATooltipFilter`.
- [ ] **Step 2: Run the three classes.** Expect failures.
- [ ] **Step 3: Implement** the filter, the search wiring (`WorldTools` passes `ItemTooltips.of(host(server))::lines`) and the schemas.
- [ ] **Step 4: Run the full build.** Expect a pass.
- [ ] **Step 5: Commit.**

### Task 7: Holder summary, slot markers and `held`/`offhand`/`targeted`/`ridden`

**Files:**
- Create: `src/main/java/dev/packsage/world/HolderWords.java`, `src/main/java/dev/packsage/world/ClientFocus.java`
- Modify:
  - `src/main/java/dev/packsage/world/InventoryReader.java`: default `entryFacts`;
  - `src/main/java/dev/packsage/world/minecraft/MinecraftInventories.java`: player slot facts;
  - `src/main/java/dev/packsage/world/ItemPath.java`: `static List<PathStep> parseSteps(String)`, package-private;
  - `src/main/java/dev/packsage/world/WorldTools.java`: `listItems`, `items`, `inspect_item` dispatch, `entries`, `EntriesState`, `ItemsRead`, `entryRecord`, plus a new `Supplier<CompletionStage<ClientFocus>> focus` constructor parameter on both constructors;
  - `src/main/java/dev/packsage/client/ClientAssistant.java:376`: pass `() -> client.submit(() -> GameplayContextCapture.focus(client))`;
  - `src/main/java/dev/packsage/client/GameplayContextCapture.java`: `public static ClientFocus focus(Minecraft)`;
  - `src/main/java/dev/packsage/model/PackSageTools.java`: the `list_items` and `inspect_item` descriptions.
- Test: `src/test/java/dev/packsage/world/HolderWordsTest.java`, `src/test/java/dev/packsage/world/WorldToolsTest.java`, `src/test/java/dev/packsage/world/minecraft/MinecraftInventoriesTest.java`

**Interfaces:**
- Produces:
  - `public record ClientFocus(PathRoot targeted, int openedContainerId, String openedTitle)`. `targeted` is null when nothing is targeted, and `openedContainerId` is -1 when no screen is open. It has `static final ClientFocus NONE`.
  - `public static PathRoot ClientFocus.targetedRoot(HitResult hit, String dimension)`:
    - a `BlockHitResult` of type BLOCK gives `Block(BlockPlace)`;
    - an `EntityHitResult` gives `Player(uuid)` for players and `Entity(uuid)` for other entities;
    - a miss or null gives null.
  - `GameplayContextCapture.focus(Minecraft)` is client thread only. It takes the targeted root from `minecraft.hitResult`, and the container id and title (`getTitle().getString()`, cut to 64 characters) from the `AbstractContainerScreen` behind `AssistantScreen.containerScreen()` whose menu is `player.containerMenu`.
  - `public final class HolderWords`:
    - `enum Word { HELD, OFFHAND, TARGETED, RIDDEN, OPENED }`;
    - `record Phrase(Word word, List<PathStep> steps)`;
    - `static Phrase parse(String text)`: null when the text does not start with a word followed by end-of-text or `>`; it throws `IllegalArgumentException` on malformed steps;
    - `record Situation(UUID player, int selectedSlot, String mainKind, String offhandKind, PathRoot targeted, UUID ridden, int openedContainerId)`, where kinds are null for empty slots and the id is -1 when no menu is open;
    - `sealed interface Resolved` with `Path(ItemPath path)` and `Unresolved(String reason)`;
    - `static Resolved resolve(Phrase phrase, Situation situation)`. The resolved paths are:

      | Word | Path |
      |---|---|
      | `held` | `player:<id>>minecraft/main#<selected>@<mainKind>` + steps |
      | `offhand` | `player:<id>>minecraft/offhand#0@<offhandKind>` + steps |
      | `targeted` | the targeted root + steps |
      | `ridden` | `entity:<ridden>` + steps |
      | `opened` | `opened:<id>` + steps (the root is added in Task 9; until then `OPENED` resolves to `Unresolved("No menu is open")`) |

      Unresolvable words give the fixed detail strings from Global Constraints. Paths over `ItemPath.MAX_STEPS` throw `IllegalArgumentException`.
  - `default Evidence.JsonObject InventoryReader.entryFacts(LiveHolder holder, String section, String entry)` returns null by default.
  - `MinecraftInventories` implements it for `OfPlayer` through `static Evidence.JsonObject playerSlotFacts(String section, int index, int selected)`:
    - `main` gives `{slot, selected: true}`, with `selected` present only for the selected index;
    - `armor` gives `{slot, equipmentSlot}`, with `equipmentSlot` one of `feet`, `legs`, `chest`, `head` for indexes 0–3;
    - `offhand` gives `{equipmentSlot: "offhand"}`.
  - `WorldTools` changes:
    - **Word resolution.** `list_items(holder)` and `inspect_item(path)` accept `HolderWords` phrases besides `player` and concrete paths. Resolution happens in the first server step from a `Situation` read off the host player: `getInventory().selected`, the main-hand and offhand stacks, `getVehicle()` and `containerMenu`. `ClientFocus` is awaited first only for `TARGETED` and `OPENED`.
    - **Unresolved words.** `static Evidence.Result unresolved(String reason)` gives the no-record COMPLETE result.
    - **Holders.** `inspect_item` on a word that resolves to a holder (no steps) gives the existing "names a holder" `INVALID_ARGUMENTS`.
  - Holder summary:
    - `record HolderSummary(PathRoot root, List<InventorySummary> inventories, Evidence.JsonObject facts)` and `record InventorySummary(InventoryInfo info, Integer emptySlots)`. `emptySlots` is `slots - occupied` when `slots >= 0`, and null for keyed sections.
    - Player facts are `{selectedSlot}`.
    - `ItemsRead` and `EntriesState` gain `HolderSummary summary` (shown on the first page only; later pages carry null) and `Map<String, Evidence.JsonObject> slotFacts`, keyed by location path text.
    - `entries(...)` leads with one summary record when `summary != null`. It counts toward `LIST_PAGE`, and its root counts as already linked, so it is not repeated as a holder record.
    - The summary record payload is `holder`, `inventories: [{inventory, label, slots, role, emptySlots}]` and the facts. Its handle and link are:
      - `block|<place>` with the block link for block roots;
      - `entity|<uuid>` with the entity link for entity roots;
      - `holder|<root text>` with no link for player roots.
  - `entryRecord` adds `slots: [{path, …entryFacts}]` for those of its locations that have facts.

- [ ] **Step 1: Write the failing tests:**
  - `HolderWordsTest.heldResolvesToTheSelectedSlotAtCallTime`: with selected 3 holding a sword, `held>minecraft/container#0@minecraft:stone` gives `player:<id>>minecraft/main#3@minecraft:iron_sword>minecraft/container#0@minecraft:stone`. With selected 4 it gives `#4`.
  - `HolderWordsTest.unresolvableWordsSaySoInsteadOfFailing`: an empty hand, an empty offhand, no target, no vehicle and no menu each give their fixed detail.
  - `HolderWordsTest.nonWordsAndMalformedStepsAreRejected`: `heldx` parses to null; `held>bad` throws.
  - `ClientFocusTest.targetedRootNamesTheBlockPlaceOrEntity`: a `BlockHitResult` at (1,64,2) in `minecraft:overworld` gives `block:minecraft:overworld|1,64,2`; a MISS gives null.
  - `MinecraftInventoriesTest.playerSlotFactsMarkTheSelectedSlotAndArmorPieces`.
  - `WorldToolsTest.unresolvedWordsAnswerWithACompleteResultAndADetail`.
  - `WorldToolsTest.theHolderSummaryLeadsTheFirstPageAndCountsTowardTheRecordLimit`: 70 player entries plus a summary give 64 records on the first page. The summary comes first and is absent on the second page. Entry `slots` carry `selected`.
- [ ] **Step 2: Run the four classes.** Expect failures.
- [ ] **Step 3: Implement** in this order: `ClientFocus` and `HolderWords`, then entry facts, then the summary, then `WorldTools` dispatch, then descriptions and wiring.
- [ ] **Step 4: Run the full build.** Expect a pass.
- [ ] **Step 5: Commit.**

### Task 8: Gameplay context names the targeted holder and the ridden entity

**Files:**
- Modify: `src/main/java/dev/packsage/client/GameplayContextCapture.java` (`Observation` and `context(...)`, `:51-74`, `:151-185` and `:425-440`)
- Test: `src/test/java/dev/packsage/client/ContextCaptureTest.java`

**Interfaces:**
- Consumes: `ClientFocus.targetedRoot` (Task 7).
- Produces:
  - `Observation` gains `String targetedHolder` and `String ridden`. The existing shorter constructors pass null.
  - The context JSON adds:
    - `targetedHolder`: the root text, such as `block:minecraft:overworld|1,64,2` or `entity:<uuid>`, or `unavailable`;
    - `ridden`: `entity:<uuid>` of `player.getVehicle()`, or `none`.
  - A `miss` focus clears `targetedHolder`, like `targeted`.

- [ ] **Step 1: Write the failing test** `gameplayContextNamesTheTargetedHolderAndTheRiddenEntity`, and extend `missedRaysHaveNoBlockTargetAndCameraPerspectiveIsCapturedFresh` to assert `targetedHolder == "unavailable"`.
- [ ] **Step 2: Run `ContextCaptureTest`.** Expect a failure.
- [ ] **Step 3: Implement** the fields.
- [ ] **Step 4: Run the full build.** Expect a pass.
- [ ] **Step 5: Commit.**

### Task 9: Opened menus

**Files:**
- Create: `src/main/java/dev/packsage/world/minecraft/MinecraftMenus.java`
- Modify:
  - `src/main/java/dev/packsage/world/PathRoot.java`: `Opened(int containerId)`;
  - `src/main/java/dev/packsage/world/ItemPath.java`: text and parse for `opened:<id>`, a non-negative decimal;
  - `src/main/java/dev/packsage/world/LiveHolder.java`: `OfMenu`;
  - `src/main/java/dev/packsage/world/minecraft/MinecraftInventories.java`: `parts`, `entryFacts`;
  - `src/main/java/dev/packsage/world/HolderWords.java`: `OPENED` resolves;
  - `src/main/java/dev/packsage/world/WorldTools.java`: `root(...)`, `rootTarget`, `holderRecord`, the summary facts, and every exhaustive `PathRoot` switch;
  - `src/main/java/dev/packsage/model/PackSageTools.java`: the `list_items` description.
- Test: `src/test/java/dev/packsage/world/minecraft/MinecraftMenusTest.java`, `src/test/java/dev/packsage/world/WorldTypesTest.java`, `src/test/java/dev/packsage/world/HolderWordsTest.java`

**Interfaces:**
- Produces:
  - `record MenuSection(String section, String label, SlotRole role, List<Integer> menuSlots)`, `record ExcludedSlot(int slot, String kind)` (kind `recipe_preview` or `trade_preview`) and `record MenuLayout(List<MenuSection> sections, List<ExcludedSlot> excluded, boolean vanilla)`.
  - `static MenuLayout MinecraftMenus.layout(Class<?> menuType, List<Slot> slots, Container playerInventory, int combinerResultSlot)`, plus the overload `layout(AbstractContainerMenu menu, Container playerInventory)`. The overload passes `ItemCombinerMenu.getResultSlot()` or -1.
  - `static String MinecraftMenus.menuType(AbstractContainerMenu)`: the `BuiltInRegistries.MENU` key, or `unknown` when `getType()` throws or is unregistered.
  - Layout rules, applied to active slots (`slot.isActive()`) whose `slot.container != playerInventory`:

    | Menu class | Menu slot → section |
    |---|---|
    | `ChestMenu`, `ShulkerBoxMenu`, `HopperMenu`, `DispenserMenu`, `LecternMenu` | all → `container` |
    | `CrafterMenu` | grid → `container` |
    | `AbstractFurnaceMenu` subclasses | 0 `input`, 1 `fuel`, 2 `output` |
    | `BrewingStandMenu` | 0–2 `bottles`, 3 `ingredient`, 4 `fuel` |
    | `HorseInventoryMenu` | 0 `saddle`; 1 `carpet` when its stack is in `minecraft:wool_carpets`, else `body_armor`; 2+ `chest` |
    | other `net.minecraft.world.inventory.*` menus (crafting, stonecutter, anvil, grindstone, loom, smithing, enchanting, cartography, beacon, merchant) | all → `menu` |
    | any other class | all → `menu`, with `vanilla == false` |

    Excluded, never in contents:
    - `ResultSlot` and `NonInteractiveResultSlot` as `recipe_preview`, and `MerchantResultSlot` as `trade_preview`;
    - by index: `GrindstoneMenu` 2, `StonecutterMenu` 1, `LoomMenu` 3, `CartographyTableMenu` 2, and the `combinerResultSlot` of `AnvilMenu` and `SmithingMenu`, all `recipe_preview`.

    The rules carry over from `NativeStorageReader.projectionKind` and `supportedStockSlot`.
  - `MinecraftInventories` parts for `OfMenu`:
    - one `Part` per `MenuSection`, with entries keyed by menu slot index (decimal);
    - `get` and `entries` read `menu.slots.get(i).getItem()` and nothing else;
    - `slots` is the section's slot count.

    `entryFacts` for `OfMenu` gives `{menuSlot}`.
  - `WorldTools` changes:
    - **Root.** `root(server, Opened(id))` gives `OfMenu(host.containerMenu, host.getInventory())` only when `containerMenu != inventoryMenu && containerMenu.containerId == id`. Otherwise it gives null, reported as `UNAVAILABLE: the menu is no longer open`.
    - **Links.** `rootTarget` and `holderRecord` give null for `Opened`: no link and no holder record.
    - **Summary.** The summary record is `holder|opened:<id>` with no link. Its facts are `menuType`, `title` (from `ClientFocus` when `openedContainerId == id`, omitted otherwise) and `excludedSlots: [{slot, kind}]`.
    - **Coverage.** A non-vanilla layout adds the note `<menuType> is not a vanilla menu; some listed slots may be previews` and makes the page PARTIAL.
  - `HolderWords.resolve(OPENED)` gives `opened:<openedContainerId>` + steps, or `No menu is open`.

- [ ] **Step 1: Write the failing tests.** Use `TestMenu` (an `AbstractContainerMenu` subclass with `super(null, 0)`, as in `NativeStorageReaderTest`) and `SimpleContainer`s:
  - `craftingGridIsListedAndItsOutputPreviewExcluded`: `CraftingMenu.class` with a `ResultSlot` at 0 and grid slots 1–9 gives section `menu` with 1–9, and excluded `[0 recipe_preview]`.
  - `furnaceBrewingAndHorseMenusUseTheirBlockAndEntitySections`.
  - `playerInventorySlotsAndInactiveSlotsAreLeftOut`.
  - `aModdedMenuListsItsSlotsUnderMenuAndIsNotVanilla`.
  - `readingAnOpenedMenuLeavesItsSlotsUntouched`: a `Slot` subclass whose `set`, `setChanged`, `setByPlayer`, `onTake`, `remove` and `safeTake` fail the test if called. Run `inventories`, `open`, `entries`, `get` and `inspectItem` through `MinecraftInventories` on an `OfMenu(testMenu, playerContainer)`. Every stack's components are equal before and after.
  - `WorldTypesTest.openedRootTextRoundTrips`: `opened:7>minecraft/menu#3@minecraft:oak_planks`.
  - `HolderWordsTest.openedResolvesToTheOpenContainerId`.
  - `WorldToolsTest.openedPathsFailOnceTheMenuChanges`: through the package-private `root` lookup with a fake host, a changed `containerId` gives null.
- [ ] **Step 2: Run the classes.** Expect failures.
- [ ] **Step 3: Implement** in this order: `MinecraftMenus`, the root and holder types, the inventories, then `WorldTools` and the description. The `list_items` description names `opened`, `excludedSlots` and `menuType`, and says that opened paths last only while the menu stays open.
- [ ] **Step 4: Run the full build.** Expect a pass.
- [ ] **Step 5: Commit.**

### Task 10: Coverage check against `search_inventory`

**Files:**
- Test: `src/test/java/dev/packsage/world/WorldToolsTest.java`

This task proves the swap is safe before removal. It adds no production code unless a gap is found; a gap is fixed in this task, test first.

- [ ] **Step 1: Write the test** `worldToolsAcceptEveryHolderSearchInventoryCouldRead`. Through `PackSageTools.definitions()` validation (`execute` with a dispatch fake that records requests), these calls validate and dispatch to tool `list_items` or `inspect_item` unchanged:
  - `list_items` with `holder` set to each of `player`, `held`, `offhand`, `targeted`, `ridden` and `opened`;
  - `list_items` with `filter: {ids, tags, text, tooltip}`;
  - `inspect_item` with `path` set to `held` and to `offhand`.
- [ ] **Step 2: Run it.** Expect a pass. A failure here is a gap: fix it before Task 11.
- [ ] **Step 3: Commit.**

### Task 11: Remove `search_inventory` from the model, with the prompt swap

**Files:**
- Modify:
  - `src/main/java/dev/packsage/model/PackSageTools.java`:
    - remove `NATIVE_INVENTORY_TARGETS`, the `search_inventory` name, schema, limit and description, its validation at `:263-269` and the `targetSchema` construction;
    - `read_evidence` description: "An item record's reference rereads that item's current record, including enchantmentEligibility.";
  - `src/main/java/dev/packsage/knowledge/Toolbox.java`: remove `searchInventory`, and remove `inventoryTargets` from `Metadata`;
  - `src/main/java/dev/packsage/knowledge/AdapterRegistry.java`: remove the duplicate-target check and the `search_inventory` case;
  - `src/main/java/dev/packsage/knowledge/nativegame/NativeProvider.java`: remove `searchInventory` (`:138-197`) and the `Metadata` argument;
  - `src/main/java/dev/packsage/knowledge/KnowledgeProvider.java:20`, `src/main/java/dev/packsage/knowledge/KnowledgeService.java:129`, `src/main/java/dev/packsage/chat/HistoryAdapter.java:19`: the `Metadata` constructor;
  - `src/main/java/dev/packsage/model/ReferenceAliasBackend.java`: remove slot aliases (`slotAliases`, `slots`, `encodeSlots`, the `search_inventory` branch of `transformArguments`);
  - `src/main/java/dev/packsage/model/GroundingInstructions.java`: the minimal swap below;
  - tests constructing `Metadata` (`AssistantIntegrationTest`, `AssistantRuntimeTest`, `KnowledgeServiceTest`, `ToolboxTest`, `PackSageToolsTest`) and tests of the removed behavior (`ToolboxTest` inventory target cases, `ReferenceAliasBackendTest` slot cases, `PackSageToolsTest` `search_inventory` cases).
- Test: `src/test/java/dev/packsage/model/GroundingInstructionsTest.java`, `src/test/java/dev/packsage/model/PackSageToolsTest.java`

**Interfaces:**
- Produces: `record Metadata(String id, List<String> documentTypes, boolean recipes, List<String> registries)`. `search_inventory` is unknown to `PackSageTools.execute` (`unknown read tool`).

Prompt wording is a minimal correct swap: it only replaces references to the removed tool. The whole-prompt rewrite and its in-game iteration belong to the prompt plan. Replace these sentences in `INSTRUCTIONS`:

| Current | New |
|---|---|
| "For the held or selected item, read its own record with search_inventory target item; judge…" | "For the held or selected item, read it with inspect_item path held (offhand for the offhand item); judge…" (rest unchanged) |
| "…openedMenu is not none, read it with target opened; never ask…" | "…openedMenu is not none, read it with list_items holder opened; never ask…" |
| "targeted reads the looked-at block or entity and ridden the mount. When a target is unsupported, follow the alternative it names or state the limit." | "list_items holder targeted reads the looked-at block or entity, and ridden the mount. When a holder cannot be read, say so and state the limit." |
| "…and a tooltip search of equipped and carried items." | "…and a find_items or list_items player search with filter.tooltip." |
| The paragraph starting "search_inventory reads one registered target." | "list_items reads one holder. Omit filter to enumerate; alternatives within a filter field are OR, populated fields are AND. Use returned item paths exactly, not reconstructed slot numbers. Inventory covers its reported sections, not automatically nested contents. Unknown sections remain unknown without invalidating inspected ones. Only a complete unfiltered read can establish empty slots in its scope." followed by the paragraph's unchanged last sentence ("Keep materials separate…") |

- [ ] **Step 1: Write the failing tests:**
  - `GroundingInstructionsTest`: replace the `:19` and `:21` assertions with `"read it with inspect_item path held"` and `"read it with list_items holder opened"`.
  - `GroundingInstructionsTest.promptNamesNoRemovedTool`: the text contains none of `search_inventory`, `slotReference` and `target opened`.
  - `PackSageToolsTest.searchInventoryIsNoLongerOffered`: it is absent from the definitions, and `execute` of it fails `unknown read tool`.
  - `PackSageToolsTest.toolDescriptionsNameOnlyDefinedTools`: no description mentions `search_inventory`.
- [ ] **Step 2: Run both classes.** Expect failures.
- [ ] **Step 3: Implement** the removals and the prompt swap. Fix the compile errors in the listed tests by dropping the fourth `Metadata` argument.
- [ ] **Step 4: Run the full build.** Expect a pass.
- [ ] **Step 5: Commit.**

### Task 12: Remove the legacy readers and their handle kinds

**Files:**
- Delete:
  - `src/main/java/dev/packsage/knowledge/nativegame/NativeInventoryReader.java`;
  - `src/main/java/dev/packsage/knowledge/storage/NativeStorageReader.java`, `NativeServerStorageReader.java` and `StorageReader.java`;
  - `src/main/java/dev/packsage/knowledge/inventory/InventoryReader.java`;
  - their tests: `NativeInventoryReaderTest`, `NativeStorageReaderTest`, `NativeServerStorageReaderTest`, `StorageReaderTest` and `InventoryReaderTest`.
- Modify:
  - `src/main/java/dev/packsage/knowledge/nativegame/NativeProvider.java`:
    - remove the `inventory` and `storage` fields and their construction (`:40-41`, `:55-56`, `:68-69`);
    - remove `readInventory` (`:126-136`, used only by tests) and the `selectedIdentities` capture path in `execute` (`:415-440`, `:572-580`);
    - `readEvidence` (`:356-358`): for prefixes `inventory:`, `inventory-summary:`, `storage:` and `storage-entry:`, return a failed `Toolbox.Failure(UNAVAILABLE, "this inventory record came from a reader that was removed; read the items again with list_items or inspect_item")`;
  - `src/main/java/dev/packsage/knowledge/nativegame/NativeSnapshot.java`: remove `captureSelectedInventory` and `inventoryRecord` (`:88-154`);
  - `src/test/java/dev/packsage/knowledge/NativeProviderTest.java`: remove the `readInventory` tests (`:91-125`).
- Kept: `NativeItemFacts` and `NativeEnchantmentEligibility`, which are still used by `MinecraftInventories` and `NativeRegistryReader`.
- Test: `src/test/java/dev/packsage/knowledge/NativeProviderTest.java`

- [ ] **Step 1: Write the failing test** `removedInventoryHandlesRereadAsUnavailable`. For each of the four prefixes, with a context reference owned by `native`, `readEvidence` fails `UNAVAILABLE` with the message above.
- [ ] **Step 2: Run `NativeProviderTest`.** Expect a failure: today the reread is a lookup.
- [ ] **Step 3: Delete the files and remove the code.** Then confirm no references remain: `grep -rn "knowledge.storage\|knowledge.inventory\|NativeInventoryReader\|captureSelectedInventory" src` returns nothing.
- [ ] **Step 4: Run the full build.** Expect a pass. Record the test count for the changelog.
- [ ] **Step 5: Commit.**

### Task 13: Changelog and roadmaps

**Files:**
- Modify:
  - `CHANGELOG.md`: one entry under `### 2026-10-25` or the current date, in player language. It covers the new holders, the menu reading with previews left out, the slot markers, the full item facts and tooltips, the tooltip search, the removal of `search_inventory`, the four fixes, and the automated test count. It states that live acceptance is pending.
  - `docs/roadmap/2026-10-22-v2-minecraft-only.md`:
    - under "Next plans", item 1 links this plan and gets sub-items for each capability, left unchecked until live acceptance;
    - the "Minor review backlog" N2–N5 items are marked fixed, with no live check needed, because they are internal;
    - "Remaining" no longer lists native storage reads.
  - `docs/roadmap/2026-10-13-todo.md`: the v2 section (`## v2 branch`) gets one line for the consolidated item reading, unchecked pending live acceptance, and notes that `search_inventory` is removed on v2. The v1 `search_inventory` sections stay as they are, because they describe `feat/packsage-v1`.
  - `docs/roadmap/2026-10-21-adapter-feature-todo.md`: the v2 subsection of section 1 gets unchecked Minecraft entries for:
    - held and offhand item facts through `held` and `offhand`;
    - open-menu reading through `opened`;
    - targeted and ridden holders;
    - the tooltip filter.

    Each feature has one checkbox per mod, grouped by adapter status, then feature.

- [ ] **Step 1: Write the entries.** Verify every relative link resolves: run `grep -o '](\.\./[^)]*)'` on each edited doc and check that each target file exists.
- [ ] **Step 2: Commit** each document separately.

---

## In-game acceptance (orchestrator, after Task 13)

The orchestrator runs these later, in singleplayer with the default prompt and the developer override cleared. They are not run by implementers. They are acceptance checks 1–4 of the [completion spec](../specs/2026-10-25-v2-minecraft-completion.md#acceptance):

1. **Held item.** Holding a partly enchanted sword, ask "what enchantments can I still add?" The reply comes from `inspect_item held` and respects exclusions.
2. **Opened menu.** With a crafting table open and items in its grid, ask "what's in this crafting grid?" The reply lists the grid and leaves out the output preview.
3. **Ridden mount.** Riding a donkey with a chest, ask "what's in my donkey?" The reply lists the cargo through `ridden`.
4. **Tooltip search.** Ask "which of my items gives luck?" One tooltip-filtered search finds the item.

Results go into `docs/validation/2026-10-25-v2-item-reading-consolidation.md`. Roadmap checkboxes are checked only after these pass.
