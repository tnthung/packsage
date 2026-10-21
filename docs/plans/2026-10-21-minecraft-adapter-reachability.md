# Minecraft Adapter Reachability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every delivered Minecraft read reachable by the assistant, and add effect descriptions plus player attribute sources, so the failed live checks and the ATM10 effect questions pass.

**Architecture:** Four independent code lanes, each in its own worktree and merged with `--no-ff`. A prompt lane follows that changes tool descriptions and assistant instructions, iterated in game through the developer override. A live-acceptance round and documentation finish the plan.

**Tech Stack:** Java 21, NeoForge 21.1 / Minecraft 1.21.1, JUnit 5 with `Bootstrap.bootStrap()`.

**Spec:** [Minecraft adapter reachability and effect sources](../specs/2026-10-21-minecraft-adapter-reachability.md). It extends [Minecraft adapter completion](../specs/2026-10-21-minecraft-adapter-completion.md).

## Global Constraints

- The global constraints of the [completion plan](2026-10-21-minecraft-adapter-completion.md#global-constraints) apply unchanged: freshness, PARTIAL on bounds, no formatting tools, a commit per step with the `commit-message` format, no AI attribution, `--no-ff` merges, and the test command with `JAVA_HOME=D:\Project\packsage\.tools\jdk\jdk-21.0.12.1`.
- Bounds: 64 modifiers per attribute record, 1,024 characters per effect description, 64 characters for an opened-menu title.
- Prompt and tool-description text changes are made only in Task P1, under the AGENTS.md prompt rules.

## Review Focus

1. **A held item that is not a container** must return its own record with `enchantmentEligibility`, not unavailable. Test in Task R1.
2. **An attribute the client does not keep current** must come back PARTIAL with `syncable: false`. Test in Task R3.
3. **An effect with no `.description` translation** must report `descriptionStatus: "not_provided"` and no `description`. Test in Task R4.
4. **A modifier list over 64 entries** must be cut with `modifiersStatus: "bounded"`. Test in Task R3.
5. **A targeted unsupported block while no menu is open** must not gain the opened-menu hint. Test in Task R2.

## Parallel execution map

| Lane | Task | Owns (new) | Touches |
| --- | --- | --- | --- |
| R1 | R1 selected item facts | — | `NativeStorageReader.itemObservation`; `NativeEnchantmentEligibility` (made public) |
| R2 | R2 opened menu context | — | `GameplayContextCapture`; `NativeStorageReader` targeted-unsupported detail |
| R3 | R3 player attributes | `NativePlayerAttributeReader` | `NativeRegistryReader.search`/`read`; `NativeProvider.metadata`/`readEvidence`; `PackSageTools.NATIVE_REGISTRIES` |
| R4 | R4 effect descriptions | — | `NativeEffectReader`; `NativeRegistryReader.effect` |
| T | T1 testing document | `docs/testing/2026-10-21-minecraft-adapter-reachability.md` | — |
| after merge | P1, then L1, then Z1 | — | prompt text, live round, docs |

---

### Task R1: Selected item facts

**Files:** Modify `src/main/java/dev/packsage/knowledge/storage/NativeStorageReader.java` (`itemObservation`, ~line 418) and `src/main/java/dev/packsage/knowledge/nativegame/NativeEnchantmentEligibility.java` (class and `read` become `public`). Test: `src/test/java/dev/packsage/knowledge/storage/NativeStorageReaderTest.java`.

**Interfaces:** The item observation's `entries` always begin with one record with section `item`, kind `ITEM` and handle `storage-entry:item:<slot>`. Its payload is the `NativeSnapshot.inventoryRecord`-equivalent payload (`id`, `name`, `count`, `components`, plus `NativeItemFacts.enrich`) and `enchantmentEligibility` from `NativeEnchantmentEligibility.read(stack, registries.lookupOrThrow(Registries.ENCHANTMENT))`. Container or bundle entries follow unchanged. For a non-container item, coverage is that record's coverage, not UNAVAILABLE, and `owners[0].storageStatus` becomes `none`.

`NativeEnchantmentEligibility.read` currently takes a `Registry<Enchantment>`. Add a public overload that takes a `HolderLookup.RegistryLookup<Enchantment>` and iterates `listElements()`. Keep the 512 bound and the status rules.

- [ ] **Step 1: Write failing tests.**
  - `nonContainerSelectedItemReturnsItsOwnRecordWithEligibility`: build a diamond pickaxe `LocatedItem` and call the package-visible `itemObservation(location, ref, scope, registries, Instant.EPOCH, 0)` with `registries = VanillaRegistries.createLookup()`. Assert the first entry's `section` is `item`, its `id` is `minecraft:diamond_pickaxe`, its payload contains `enchantmentEligibility`, and the observation coverage is not `UNAVAILABLE`.
  - `containerItemKeepsContentsAfterItsOwnRecord`: build a shulker box with one item. Assert entry 0 has section `item` and entry 1 is the contained item.
- [ ] **Step 2: Run the tests and confirm they fail.**
- [ ] **Step 3: Implement the change.**
- [ ] **Step 4: Run `dev.packsage.knowledge.storage.*` and `dev.packsage.knowledge.nativegame.*` and confirm they pass.** Update only the existing item-storage expectations that the new first entry changes.
- [ ] **Step 5: Commit.**

### Task R2: Open menu in gameplay context

**Files:** Modify `src/main/java/dev/packsage/client/GameplayContextCapture.java` (the observation and JSON at ~lines 139–160 and 388–401) and `src/main/java/dev/packsage/knowledge/storage/NativeStorageReader.java` (where a targeted block read returns `target block inventory is unsupported`). Tests: the existing `GameplayContextCapture` test class (find it under `src/test/java/dev/packsage/client`) and `NativeStorageReaderTest`.

**Interfaces:**
- The JSON gains `openedMenu`: the `BuiltInRegistries.MENU` key of `client.player.containerMenu.getType()` when `containerMenu != client.player.inventoryMenu`, otherwise `none`. When that lookup throws, use `unknown`. It also gains `openedMenuTitle` when the screen title is plain text of at most 64 characters.
- The capture must not dereference `containerMenu.getType()` for the player inventory menu, because vanilla throws `UnsupportedOperationException` there.
- The targeted-unsupported result appends `; an opened menu is available; use target opened for its contents` to its detail only when the opened observation is live (`validOpened(...)` non-null or a live `containerMenu`).

- [ ] **Step 1: Write failing tests.** The JSON contains `"openedMenu":"none"` by default, and the type key when a menu is supplied. Use the class's existing test seam or the record constructor. The hint is present only when an opened observation exists.
- [ ] **Step 2: Run the tests and confirm they fail.**
- [ ] **Step 3: Implement the change.**
- [ ] **Step 4: Run `dev.packsage.client.*` and `dev.packsage.knowledge.storage.*` and confirm they pass.**
- [ ] **Step 5: Commit.**

### Task R3: `player_attribute` selector

**Files:** Create `src/main/java/dev/packsage/knowledge/nativegame/NativePlayerAttributeReader.java`. Modify the `NativeRegistryReader` `search`/`read` switches, `NativeProvider.metadata()` and the `readEvidence` prefix list, `PackSageTools.NATIVE_REGISTRIES` (add `player_attribute` after `active_effect`) and `NativeProviderTest.advertisesAllNativeRegistryKinds`. Test: `src/test/java/dev/packsage/knowledge/nativegame/NativePlayerAttributeReaderTest.java`.

**Interfaces:**
- `static Evidence.Record record(AttributeInstance instance, Evidence.Scope scope)` returns handle `player_attribute:<id>`, kind `OTHER`, title = localized name, provenance `client-player:attributes`, and payload `{registry:"player_attribute", id, identity, attribute, name, syncable, base, value, modifiers:[{id, amount, operation}], modifiersStatus?}`. Modifiers are sorted by ID and capped at 64. Coverage is `COMPLETE` when the attribute is syncable and not bounded, otherwise `PARTIAL`.
- `static NativeRegistryReader.Page search(Collection<AttributeInstance> instances, String text, List<String> ids, List<String> tags, int offset, int limit, Evidence.Scope scope)` sorts by ID. Text matches ID or localized name; tags use `instance.getAttribute().tags()`.
- `static Evidence.Record read(Collection<AttributeInstance> instances, String id, Evidence.Scope scope)` returns null when the attribute is absent.
- The client source is every `BuiltInRegistries.ATTRIBUTE` holder for which `player.getAttributes().hasAttribute(holder)`, using `getInstance(holder)`. A missing player is handled the same way as for `active_effect`.

- [ ] **Step 1: Write failing tests.** Build `AttributeInstance`s directly (`new AttributeInstance(Attributes.LUCK, ignored -> {})`) with base 0 and modifiers `minecraft:effect.luck` +12 ADD_VALUE and `packsage:test_charm` +1 ADD_VALUE.
  - The value is 13, the modifiers are sorted, and coverage is COMPLETE.
  - An attribute with 65 modifiers gives `modifiersStatus: "bounded"`, 64 modifiers and PARTIAL.
  - A non-syncable attribute is PARTIAL with `syncable: false`. Use an attribute whose `isClientSyncable()` is false, or construct a `RangedAttribute` that is not syncable.
  - Filters by ID and paging behave as in `active_effect`.
  - `read` returns null for an absent ID.
  - `registrySchemaOffersPlayerAttribute` is added in `PackSageToolsTest`.
- [ ] **Step 2: Run the tests and confirm they fail.**
- [ ] **Step 3: Implement the change.**
- [ ] **Step 4: Run `dev.packsage.knowledge.*` and `dev.packsage.model.*` and confirm they pass.**
- [ ] **Step 5: Commit.**

### Task R4: Effect descriptions

**Files:** Modify `src/main/java/dev/packsage/knowledge/nativegame/NativeEffectReader.java` and the `NativeRegistryReader.effect` record builder. Test: `NativeEffectReaderTest`.

**Interfaces:**
- `static void describe(Map<String, Evidence.JsonValue> payload, MobEffect effect, Language language)`. The key is `effect.getDescriptionId() + ".description"`. When `language.has(key)`, put `description` (from `language.getOrDefault(key)`, with formatting codes stripped by `ChatFormatting.stripFormatting`, capped at 1,024 characters) and `descriptionSource: "language:<key>"`. Above the cap, also put `descriptionStatus: "bounded"`. Otherwise put `descriptionStatus: "not_provided"`.
- `NativeRegistryReader.effect` and `NativeEffectReader.activeRecord` call it with `Language.getInstance()`. A `RuntimeException` from the language lookup gives `descriptionStatus: "unknown"`.

- [ ] **Step 1: Write failing tests.** Use a small `Language` stub (anonymous subclass) whose `has`/`getOrDefault` return a description for `effect.minecraft.luck.description`.
  - Luck gets `description` and `descriptionSource`.
  - Speed gets `descriptionStatus: "not_provided"` and no `description`.
  - A 2,000-character description is capped with `bounded`.
  - A `§`-coded description is stripped.
- [ ] **Step 2: Run the tests and confirm they fail.**
- [ ] **Step 3: Implement the change.**
- [ ] **Step 4: Run `dev.packsage.knowledge.nativegame.*` and confirm it passes.**
- [ ] **Step 5: Commit.**

### Task T1: Testing document

Create `docs/testing/2026-10-21-minecraft-adapter-reachability.md` in the style of the completion checks, with commands under 256 characters and named entities. Include:

- reruns of completion checks 2, 4 (regression), 7, 8, 8b and 10, written so they can be executed;
- a luck source check: `/effect give @s minecraft:luck 120 2` plus `/give @s minecraft:golden_helmet[attribute_modifiers={modifiers:[{type:"minecraft:generic.luck",amount:3,operation:"add_value",id:"packsage:test_helmet_luck",slot:"head"}]}]`, worn. Ask `Where does my Luck come from?` Expect `player_attribute` evidence listing `minecraft:effect.luck` and `packsage:test_helmet_luck`, and a reply that attributes +3 to the helmet and the rest to the Luck effect while saying Minecraft doesn't record who applied the effect;
- an effect description check: ask `What does Luck do?` and pass either on a `description` field when present, or on `descriptionStatus: "not_provided"` handled without inventing a game description. The minimal profile may lack description mods, so state which case was observed;
- a held-item check: hold a plain diamond sword and ask `What enchantments can go on this sword?` Expect a `search_inventory` item target returning the item record with `enchantmentEligibility`.

Commit.

### Task P1: Assistant instructions and tool descriptions (after R1–R4 and T1 are merged)

**Files:** `src/main/java/dev/packsage/model/GroundingInstructions.java` and the tool descriptions in `src/main/java/dev/packsage/model/PackSageTools.java`.

- [ ] **Step 1: Draft the candidate whole prompt.** Integrate the spec's "Assistant instructions and tool descriptions" bullets into the existing paragraphs, consolidating rather than appending scenario rules. Update the `search_inventory`, `search_registry`, `read_evidence` and `plan_recipe` descriptions to name `item`-target eligibility, `opened` when `openedMenu` is set, `player_attribute`, effect `description`, and `plan_recipe` for plans. Write the candidate to `<workspace>/prompt-candidate.txt`. Do not change code yet.
- [ ] **Step 2: Iterate in game.** The live agent pastes the candidate into the developer override (middle-click the settings gear) and runs the T1 checks. Revise the candidate on failure, keeping Minecraft open. Record each candidate's results.
- [ ] **Step 3: Promote.** Promote the passing candidate's instruction text to `GroundingInstructions`, and its description text to `PackSageTools`. Update pinned-text tests, then run `dev.packsage.model.*` and the full build. Commit.
- [ ] **Step 4: Verify the default.** Install the build. The live agent clears the override and reruns the T1 checks on the installed default.

### Task Z1: Validation, roadmaps and changelog

Write `docs/validation/2026-10-21-minecraft-adapter-reachability.md` with the test totals, artifact hash, per-candidate and final-default results, and passes and failures in separate sections. Check roadmap items only on their live pass. Add the new player-attribute and effect-description items to both roadmaps, and update `CHANGELOG.md` to state accepted and not-accepted items accurately. Verify links and commit.
