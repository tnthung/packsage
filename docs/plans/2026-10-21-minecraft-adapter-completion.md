# Minecraft Adapter Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish every open Minecraft (`native`) adapter roadmap item except commands: enchantment level costs, effect payloads, decoded mechanics and item eligibility; status-effect attribute modifiers and active player effects; potion-item contents; component-distinct variants and recipe-preview slots in inventory results; and brewing name matching, scan progress and batch presentation.

**Architecture:** New logic goes into small focused classes in `dev.packsage.knowledge.nativegame`, which existing readers call through one-line hooks. Each lane owns its own new class and touches different methods of shared files, so lanes run in parallel git worktrees and merge back with `--no-ff`. No tool, argument or filter is added; the only new selector is `active_effect` in `search_registry`.

**Tech Stack:** Java 21, NeoForge 21.1 / Minecraft 1.21.1 (ModDevGradle), JUnit 5 with `Bootstrap.bootStrap()` and `VanillaRegistries.createLookup()`.

**Spec:** [Minecraft adapter completion](../specs/2026-10-21-minecraft-adapter-completion.md). Contracts it extends: [behavior-driven tools](../specs/2026-10-14-behavior-driven-tools.md), [brewing](../specs/2026-10-19-brewing.md).

## Global Constraints

- Scope excludes Minecraft commands and every third-party adapter.
- Every new field is read fresh on each search, continuation and `read_evidence`; nothing is cached between calls.
- New fields never replace existing payload keys (`minCost`, `maxCost`, `components`, `mechanicsStatus` stay as they are).
- A bound that cuts output makes the record `PARTIAL` and names the cut in the field's `status`. Unknown types are reported with their ID and `status: "opaque"`, never dropped.
- Bounds: 32 levels, 64 effect components, 32 effects per component, 16,384 bytes of encoded effects, 512 enchantment holders, amplifiers 0–3 for declared modifiers, 32 potion effects, 64 reagent names.
- Follow the existing code style. Never run formatting tools.
- Commit each written step immediately. Generate every message with the `commit-message` skill from the staged index. Never add `Co-Authored-By` or other AI attribution.
- Merge lanes with `git merge --no-ff` only.
- Test command, from the worktree root in PowerShell: `$env:JAVA_HOME='D:\Project\packsage\.tools\jdk\jdk-21.0.12.1'; .\gradlew.bat test --tests <fully.qualified.TestClass>`. Worktrees do not contain `.tools`; always use this absolute path.
- Never assign Java, Gradle or test commands to the player. Live checks go in the testing document.

## Review Focus

1. **An enchantment whose `maxLevel` is above 32 (modded, up to 255)** must keep 32 level rows, report `levelsStatus: "bounded"` and stay readable. Test in Task A1.
2. **A modded effect component or value-effect type** must appear as `opaque` with its ID and make the record `PARTIAL`, not throw or vanish. Test in Task A2.
3. **An item that already carries an enchantment** must report `compatibleWithCurrent: false` for an exclusive enchantment and `currentLevel` for the one it has. Test in Task B1.
4. **An effect that expires between search and `read_evidence`** must return the unknown-reference result, never the old instance. Test in Task C2.
5. **Two potions with the same item ID but different contents whose components are truncated** must keep different `componentFingerprint` values. Test in Task D1.

## Parallel execution map

Wave 1 runs all nine lanes at once, each in its own worktree branched from `feat/packsage-v1` after the spec and plan commits. Tasks inside a lane run in order. Wave 2 starts after every lane has merged.

| Lane | Tasks | Owns (new) | Touches in shared files |
| --- | --- | --- | --- |
| A — enchantment definitions | A1 → A2 | `NativeEnchantmentReader` | `NativeRegistryReader.enchantment` |
| B — enchantment eligibility | B1 | `NativeEnchantmentEligibility` | `NativeRegistryReader.read` (`item` case), `NativeInventoryReader.readEvidence` |
| C — status effects | C1 → C2 | `NativeEffectReader` | `NativeRegistryReader.effect`, `.search`, `.read`; `NativeProvider.metadata`, `.readEvidence`; `PackSageTools.NATIVE_REGISTRIES`; `GroundingInstructions` |
| D — item facts | D1 | `NativeItemFacts` | `NativeSnapshot.inventoryRecord`; payload sites in `NativeStorageReader` and `NativeServerStorageReader` |
| E — menu projections | E1 | — | `NativeStorageReader.openedEntries`, `OpenedEntries`, opened `Observation` details |
| F — brewing names | F1 | — | `NativeBrewingReader.record` |
| G — recipe scan progress | G1 | — | `NativeRecipeReader.search`, `.Page`; `NativeProvider.searchRecipe`; new `NativeBrewingReader.candidateCount` |
| H — planner brewing batches | H1 | — | `RecipePlanner.planned`, `.visit` |
| T — player testing document | T1 | `docs/testing/2026-10-21-minecraft-adapter-completion.md` | — |
| Wave 2 | Z1 → Z2 | validation record | roadmaps, `CHANGELOG.md` |

Shared files are edited in different methods by different lanes. Merge in lane order A, B, C, D, E, F, G, H, T; resolve any textual conflict by keeping both sides' additions.

---

### Task A1: Enchantment per-level costs and loaded effect payloads

**Files:**
- Create: `src/main/java/dev/packsage/knowledge/nativegame/NativeEnchantmentReader.java`
- Modify: `src/main/java/dev/packsage/knowledge/nativegame/NativeRegistryReader.java` (`enchantment` method, currently private at ~line 331)
- Test: `src/test/java/dev/packsage/knowledge/nativegame/NativeEnchantmentReaderTest.java`

**Interfaces:**
- Produces: `final class NativeEnchantmentReader` (package-private) with
  - `static final int MAX_LEVELS = 32;` `static final int MAX_EFFECT_BYTES = 16384;`
  - `record Levels(Evidence.JsonArray values, boolean bounded)`
  - `static Levels levels(Enchantment value)`: objects `{level, minCost, maxCost}` for levels 1..min(maxLevel, 32).
  - `static Evidence.JsonObject effects(Enchantment value, HolderLookup.Provider access)`: `EnchantmentEffectComponents.CODEC` encoded through `access.createSerializationContext(JsonOps.INSTANCE)`; `{status: "unknown"}` on failure, `{status: "unknown_truncated"}` above 16,384 UTF-8 bytes.
- Produces: `NativeRegistryReader.enchantment(Enchantment, String, HolderLookup.Provider, Evidence.Scope)` becomes package-private `static` so tests can project a record.

- [ ] **Step 1: Write the failing tests**

```java
class NativeEnchantmentReaderTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "native", 1);
    private static HolderLookup.Provider lookup;

    @BeforeAll static void bootstrap() { Bootstrap.bootStrap(); lookup = VanillaRegistries.createLookup(); }

    private static Enchantment enchantment(ResourceKey<Enchantment> key) {
        return lookup.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key).value();
    }

    @Test void protectionReportsMinAndMaxCostForEveryLevel() {
        var levels = NativeEnchantmentReader.levels(enchantment(Enchantments.PROTECTION));
        assertFalse(levels.bounded());
        assertEquals(4, levels.values().values().size());
        var first = (Evidence.JsonObject) levels.values().values().getFirst();
        var last = (Evidence.JsonObject) levels.values().values().getLast();
        assertEquals("{\"level\":1,\"minCost\":1,\"maxCost\":12}", Evidence.serialize(first));
        assertEquals("{\"level\":4,\"minCost\":34,\"maxCost\":45}", Evidence.serialize(last));
    }

    @Test void levelsAboveThirtyTwoAreBounded() {
        var wide = Enchantment.enchantment(Enchantment.definition(HolderSet.direct(), 1, 255,
                Enchantment.constantCost(1), Enchantment.constantCost(2), 1, EquipmentSlotGroup.ANY)).build(
                ResourceLocation.parse("packsage:wide"));
        var levels = NativeEnchantmentReader.levels(wide);
        assertTrue(levels.bounded());
        assertEquals(32, levels.values().values().size());
    }

    @Test void effectsEncodeLoadedComponentsByComponentId() {
        var effects = NativeEnchantmentReader.effects(enchantment(Enchantments.SHARPNESS), lookup);
        assertTrue(effects.values().containsKey("minecraft:damage"));
        assertFalse(effects.values().containsKey("status"));
    }

    @Test void enchantmentRecordAddsLevelsAndEffectsWithoutReplacingExistingFields() {
        var record = NativeRegistryReader.enchantment(enchantment(Enchantments.PROTECTION), "minecraft:protection", lookup, SCOPE);
        var payload = record.payload().values();
        assertTrue(payload.containsKey("minCost"));
        assertTrue(payload.containsKey("maxCost"));
        assertTrue(payload.get("levels") instanceof Evidence.JsonArray);
        assertTrue(payload.get("effects") instanceof Evidence.JsonObject);
        assertFalse(payload.containsKey("levelsStatus"));
    }
}
```

Exact JSON key order in the two `serialize` assertions follows the insertion order `level`, `minCost`, `maxCost`; if `Evidence.serialize` sorts keys, compare the three numbers individually instead.

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `...; .\gradlew.bat test --tests dev.packsage.knowledge.nativegame.NativeEnchantmentReaderTest`
Expected: compilation failure, `NativeEnchantmentReader` not found.

- [ ] **Step 3: Implement `NativeEnchantmentReader.levels` and `.effects`, then hook them into `NativeRegistryReader.enchantment`**

In `enchantment(...)`, add after `exclusiveSet`: `levels` (plus `levelsStatus: "bounded"` when bounded) and `effects`. Add `levels.bounded()` and `effects.values().containsKey("status")` to the existing `partial` condition. Leave `effectsStatus` unchanged in this task.

- [ ] **Step 4: Run the tests and confirm they pass**

Run the Step 2 command, then `...; .\gradlew.bat test --tests dev.packsage.knowledge.nativegame.*`.
Expected: all PASS.

- [ ] **Step 5: Commit**

Stage `NativeEnchantmentReader.java`, `NativeRegistryReader.java` and the test; commit with a message from the `commit-message` skill.

---

### Task A2: Decoded enchantment effect mechanics

**Files:**
- Modify: `src/main/java/dev/packsage/knowledge/nativegame/NativeEnchantmentReader.java`
- Modify: `src/main/java/dev/packsage/knowledge/nativegame/NativeRegistryReader.java` (`enchantment` method)
- Test: `src/test/java/dev/packsage/knowledge/nativegame/NativeEnchantmentReaderTest.java`

**Interfaces:**
- Consumes: `NativeEnchantmentReader.levels`, `MAX_LEVELS` (Task A1).
- Produces: `static final int MAX_COMPONENTS = 64;` `static final int MAX_EFFECTS = 32;` `record Mechanics(Evidence.JsonArray values, boolean partial)` and `static Mechanics mechanics(Enchantment value)`.

Each array entry is `{component, kind, status, effects?}` following the spec table. Decoding rules, by the runtime class of each component value:

- `List` of `ConditionalEffect` or `TargetedConditionalEffect` whose `effect()` is an `EnchantmentValueEffect` → `kind: "value"`. Per effect: `type` from `BuiltInRegistries.ENCHANTMENT_VALUE_EFFECT_TYPE.getKey(effect.codec())`, `conditional` = `requirements().isPresent()`, `values` per level 1..min(maxLevel, 32):
  - `AddValue`, `SetValue`: `{level, add|set: effect.process(level, null, 0f)}`;
  - `MultiplyValue`: `{level, multiply: effect.process(level, null, 1f)}`;
  - `AllOf.ValueEffects`: `{type: "minecraft:all_of", effects: [...]}` with the nested effects decoded the same way;
  - `RemoveBinomial`: `{level, chance: effect.chance().calculate(level)}`;
  - any other class: `{type, status: "opaque"}`, which makes the entry and record partial.
- A bare `EnchantmentValueEffect` (`crossbow_charge_time`, `trident_spin_attack_strength`) → `kind: "value"`, one effect, `conditional: false`.
- `List<EnchantmentAttributeEffect>` → `kind: "attribute"`, per effect `{attribute, modifierId, operation, amounts: [{level, amount: amount().calculate(level)}]}`; `attribute` from `attribute().unwrapKey()`, `operation` from `operation().getSerializedName()`.
- `List` of `ConditionalEffect`/`TargetedConditionalEffect` with any other effect type (entity, location, damage immunity) → `kind: "behavior"`, `status: "not_evaluated"`, per effect `{type, conditional}`; `type` from the effect's codec registry when it is an `EnchantmentEntityEffect` or `EnchantmentLocationBasedEffect`, otherwise the class simple name.
- `Unit` → `kind: "flag"`, `status: "decoded"`.
- The component types `minecraft:trident_sound` and `minecraft:crossbow_charging_sounds` (selected by component ID, not element class) → `kind: "presentation"`, `status: "not_evaluated"`; not partial.
- An empty `List` → `kind: "empty"`, `status: "decoded"`.
- Anything else, including an unregistered component type (ID reported as `unknown`) → `kind: "opaque"`, `status: "opaque"`, `partial = true`.
- More than 32 effects in one component: keep 32 and set that entry's `status: "bounded"`; more than 64 components: stop and add `{status: "bounded"}` as the last entry. Both make `partial = true`.

Component IDs come from `BuiltInRegistries.ENCHANTMENT_EFFECT_COMPONENT_TYPE.getKey(type)`. Wrap each component's decoding in `try`/`catch (RuntimeException)` and report a failure as `opaque`.

- [ ] **Step 1: Write the failing tests**

```java
@Test void sharpnessDamageIsDecodedAsPerLevelAddition() {
    var entry = component(NativeEnchantmentReader.mechanics(enchantment(Enchantments.SHARPNESS)), "minecraft:damage");
    assertEquals("value", string(entry, "kind"));
    assertEquals("decoded", string(entry, "status"));
    var effect = first(entry, "effects");
    assertEquals("minecraft:add", string(effect, "type"));
    assertEquals(0, BigDecimal.ONE.compareTo(number(at(effect, "values", 0), "add")));
    assertEquals(0, new BigDecimal("3").compareTo(number(at(effect, "values", 4), "add")));
}

@Test void protectionReportsItsConditionWithoutEvaluatingIt() {
    var entry = component(NativeEnchantmentReader.mechanics(enchantment(Enchantments.PROTECTION)), "minecraft:damage_protection");
    var effect = first(entry, "effects");
    assertEquals(new Evidence.JsonBoolean(true), effect.values().get("conditional"));
    assertEquals(0, new BigDecimal("4").compareTo(number(at(effect, "values", 3), "add")));
}

@Test void efficiencyAttributeAmountsFollowLevelsSquared() {
    var entry = component(NativeEnchantmentReader.mechanics(enchantment(Enchantments.EFFICIENCY)), "minecraft:attributes");
    assertEquals("attribute", string(entry, "kind"));
    var effect = first(entry, "effects");
    assertEquals(Attributes.MINING_EFFICIENCY.unwrapKey().orElseThrow().location().toString(), string(effect, "attribute"));
    assertEquals(0, new BigDecimal("5").compareTo(number(at(effect, "amounts", 1), "amount")));
}

@Test void unknownComponentTypeIsOpaqueAndPartial() {
    DataComponentType<String> modded = DataComponentType.<String>builder().persistent(Codec.STRING).build();
    var custom = Enchantment.enchantment(Enchantment.definition(HolderSet.direct(), 1, 1,
            Enchantment.constantCost(1), Enchantment.constantCost(2), 1, EquipmentSlotGroup.ANY))
            .withSpecialEffect(modded, "x")
            .withSpecialEffect(EnchantmentEffectComponents.TRIDENT_SOUND, List.of(SoundEvents.TRIDENT_RIPTIDE_1))
            .build(ResourceLocation.parse("packsage:custom"));
    var mechanics = NativeEnchantmentReader.mechanics(custom);
    assertTrue(mechanics.partial());
    assertEquals("opaque", string(component(mechanics, "unknown"), "kind"));
    assertEquals("presentation", string(component(mechanics, "minecraft:trident_sound"), "kind"));
}

@Test void recordReportsDecodedStatus() {
    var record = NativeRegistryReader.enchantment(enchantment(Enchantments.SHARPNESS), "minecraft:sharpness", lookup, SCOPE);
    assertEquals(new Evidence.JsonString("built_in_components_decoded"), record.payload().values().get("effectsStatus"));
    assertTrue(record.payload().values().get("effectMechanics") instanceof Evidence.JsonArray);
}
```

Add the small private helpers `component`, `first`, `at`, `string` and `number` to the test class; they cast through `Evidence.JsonObject`/`JsonArray` and fail with `assertInstanceOf` when a shape is wrong. `Enchantment.Builder.withSpecialEffect(DataComponentType<E>, E)` exists in 1.21.1.

- [ ] **Step 2: Run the tests and confirm the new ones fail**

Run: `...; .\gradlew.bat test --tests dev.packsage.knowledge.nativegame.NativeEnchantmentReaderTest`
Expected: compilation failure, `mechanics` not found.

- [ ] **Step 3: Implement `mechanics(Enchantment)` and hook it into `NativeRegistryReader.enchantment`**

Add `effectMechanics`, change `effectsStatus` to `built_in_components_decoded`, and add `mechanics.partial()` to the `partial` condition.

- [ ] **Step 4: Run `dev.packsage.knowledge.nativegame.*` and confirm all pass**

- [ ] **Step 5: Commit** (same procedure as Task A1)

---

### Task B1: Enchantment eligibility for an item

**Files:**
- Create: `src/main/java/dev/packsage/knowledge/nativegame/NativeEnchantmentEligibility.java`
- Modify: `src/main/java/dev/packsage/knowledge/nativegame/NativeRegistryReader.java` (`read`, `case "item"` only)
- Modify: `src/main/java/dev/packsage/knowledge/nativegame/NativeInventoryReader.java` (`readEvidence`, slot branch)
- Test: `src/test/java/dev/packsage/knowledge/nativegame/NativeEnchantmentEligibilityTest.java`

**Interfaces:**
- Produces: `final class NativeEnchantmentEligibility` (package-private) with `static final int MAX_ENCHANTMENTS = 512;` and `static Evidence.JsonObject read(ItemStack stack, Registry<Enchantment> enchantments)` returning `{enchantable, supported: [{id, primary, compatibleWithCurrent, currentLevel}], inspected, status}`. `status` is `bounded` when the registry has more than 512 entries, otherwise `complete`.

Iterate `enchantments.holders()` in registry order. For each `Holder.Reference<Enchantment> holder` with `stack.supportsEnchantment(holder)`: `primary = stack.isPrimaryItemFor(holder)`, `currentLevel = stack.getTagEnchantments().getLevel(holder)`, `compatibleWithCurrent` = for every holder `other` in `stack.getTagEnchantments().keySet()` other than `holder` itself, `Enchantment.areCompatible(holder, other)`. Wrap each holder's checks in `try`/`catch (RuntimeException)` and set `status: "partial"` on a failure.

Hooks:
- `NativeRegistryReader.read`, `case "item"`: after building the record, add `enchantmentEligibility` = `read(new ItemStack(value), registryAccess.registryOrThrow(Registries.ENCHANTMENT))` to a copy of the payload; set the record `PARTIAL` unless `status` is `complete`. Search results are unchanged.
- `NativeInventoryReader.readEvidence`, slot branch: add `payload.put("enchantmentEligibility", NativeEnchantmentEligibility.read(stack, client.level.registryAccess().registryOrThrow(Registries.ENCHANTMENT)))` next to `relicInstance`, with the same coverage rule.

- [ ] **Step 1: Write the failing tests**

Build a `MappedRegistry<Enchantment>(Registries.ENCHANTMENT, Lifecycle.stable())` in `@BeforeAll` and register, in order:
- `packsage:sword_only`: supported and primary `HolderSet.direct(Items.DIAMOND_SWORD.builtInRegistryHolder())`, max level 5;
- `packsage:bow_only`: supported `HolderSet.direct(Items.BOW.builtInRegistryHolder())`;
- `packsage:sword_exclusive`: supported diamond sword, `exclusiveWith(HolderSet.direct(swordOnlyHolder))`.

```java
@Test void reportsSupportedPrimaryAndInspectedCounts() {
    var result = NativeEnchantmentEligibility.read(new ItemStack(Items.DIAMOND_SWORD), registry);
    assertEquals(new Evidence.JsonBoolean(true), result.values().get("enchantable"));
    assertEquals(List.of("packsage:sword_only", "packsage:sword_exclusive"), ids(result));
    assertEquals(new Evidence.JsonBoolean(true), entry(result, "packsage:sword_only").values().get("primary"));
    assertEquals(0, new BigDecimal(3).compareTo(((Evidence.JsonNumber) result.values().get("inspected")).value()));
    assertEquals(new Evidence.JsonString("complete"), result.values().get("status"));
}

@Test void existingEnchantmentMakesExclusiveIncompatibleAndReportsCurrentLevel() {
    var stack = new ItemStack(Items.DIAMOND_SWORD);
    stack.enchant(swordOnlyHolder, 2);
    var result = NativeEnchantmentEligibility.read(stack, registry);
    assertEquals(new Evidence.JsonBoolean(false), entry(result, "packsage:sword_exclusive").values().get("compatibleWithCurrent"));
    assertEquals(new Evidence.JsonBoolean(true), entry(result, "packsage:sword_only").values().get("compatibleWithCurrent"));
    assertEquals(0, new BigDecimal(2).compareTo(((Evidence.JsonNumber) entry(result, "packsage:sword_only").values().get("currentLevel")).value()));
    assertEquals(new Evidence.JsonBoolean(false), result.values().get("enchantable"));
}

@Test void unsupportedItemHasNoEntries() {
    assertEquals(List.of(), ids(NativeEnchantmentEligibility.read(new ItemStack(Items.STICK), registry)));
}
```

`ids` and `entry` are private test helpers over `supported`. `compatibleWithCurrent` for the enchantment already on the stack compares only against the other enchantments, so it is `true`.

- [ ] **Step 2: Run the tests and confirm they fail** (`...NativeEnchantmentEligibilityTest`; expected: compilation failure)

- [ ] **Step 3: Implement `read` and add both hooks**

- [ ] **Step 4: Run `dev.packsage.knowledge.nativegame.*` and confirm all pass**

- [ ] **Step 5: Commit**

---

### Task C1: Declared status-effect attribute modifiers

**Files:**
- Create: `src/main/java/dev/packsage/knowledge/nativegame/NativeEffectReader.java`
- Modify: `src/main/java/dev/packsage/knowledge/nativegame/NativeRegistryReader.java` (`effect` method)
- Test: `src/test/java/dev/packsage/knowledge/nativegame/NativeEffectReaderTest.java`

**Interfaces:**
- Produces: `final class NativeEffectReader` (package-private) with `static Evidence.JsonArray attributeModifiers(MobEffect effect, int fromAmplifier, int toAmplifier)`. Entries are grouped by attribute and modifier ID, in first-seen order: `{attribute, modifierId, operation, amounts: [{amplifier, amount}]}`. Collect them with `effect.createModifiers(amplifier, (attribute, modifier) -> ...)`; `operation` is `modifier.operation().getSerializedName()`.
- Produces: `NativeRegistryReader.effect(MobEffect, String, Evidence.Scope)` becomes package-private `static`.

- [ ] **Step 1: Write the failing tests**

```java
@Test void speedModifierScalesWithAmplifier() {
    var modifiers = NativeEffectReader.attributeModifiers(MobEffects.MOVEMENT_SPEED.value(), 0, 3);
    assertEquals(1, modifiers.values().size());
    var modifier = (Evidence.JsonObject) modifiers.values().getFirst();
    assertEquals(new Evidence.JsonString(Attributes.MOVEMENT_SPEED.unwrapKey().orElseThrow().location().toString()),
            modifier.values().get("attribute"));
    assertEquals(new Evidence.JsonString("minecraft:effect.speed"), modifier.values().get("modifierId"));
    assertEquals(new Evidence.JsonString("add_multiplied_total"), modifier.values().get("operation"));
    var amounts = ((Evidence.JsonArray) modifier.values().get("amounts")).values();
    assertEquals(4, amounts.size());
    assertEquals(0, new BigDecimal("0.4").compareTo(amount(amounts.get(1))), "amplifier 1 doubles 0.2");
}

@Test void effectWithoutModifiersReturnsEmptyArray() {
    assertEquals(List.of(), NativeEffectReader.attributeModifiers(MobEffects.NIGHT_VISION.value(), 0, 3).values());
}

@Test void registryRecordAddsModifiersAndStaysPartial() {
    var record = NativeRegistryReader.effect(MobEffects.MOVEMENT_SPEED.value(), "minecraft:speed", SCOPE);
    assertTrue(record.payload().values().get("attributeModifiers") instanceof Evidence.JsonArray);
    assertEquals(Evidence.Coverage.PARTIAL, record.source().coverage());
}
```

`amount(...)` reads `amount` and rounds it to 6 decimal places, since 0.2f widened to double is not exactly 0.2.

- [ ] **Step 2: Run and confirm failure** (`...NativeEffectReaderTest`)
- [ ] **Step 3: Implement `attributeModifiers` and add `attributeModifiers` (amplifiers 0–3) to the `effect` record**
- [ ] **Step 4: Run `dev.packsage.knowledge.nativegame.*` and confirm all pass**
- [ ] **Step 5: Commit**

---

### Task C2: `active_effect` selector for current player effects

**Files:**
- Modify: `src/main/java/dev/packsage/knowledge/nativegame/NativeEffectReader.java`
- Modify: `src/main/java/dev/packsage/knowledge/nativegame/NativeRegistryReader.java` (`search` and `read` switches)
- Modify: `src/main/java/dev/packsage/knowledge/nativegame/NativeProvider.java` (`metadata`, `readEvidence` prefix check)
- Modify: `src/main/java/dev/packsage/model/PackSageTools.java` (`NATIVE_REGISTRIES`)
- Modify: `src/main/java/dev/packsage/model/GroundingInstructions.java` (the paragraph that begins "References identify records or locations")
- Test: `src/test/java/dev/packsage/knowledge/nativegame/NativeEffectReaderTest.java`, `src/test/java/dev/packsage/model/PackSageToolsTest.java`

**Interfaces:**
- Consumes: `NativeEffectReader.attributeModifiers` (Task C1); `NativeRegistryReader.Page` (existing).
- Produces in `NativeEffectReader`:
  - `static Evidence.Record activeRecord(MobEffectInstance instance, Evidence.Scope scope)`: handle `active_effect:<effect id>`, kind `OTHER`, title = effect display name, payload `{registry: "active_effect", id, identity, effect, durationTicks, infinite, amplifier, ambient, visible, showIcon, attributeModifiers}` with modifiers at the instance amplifier only, coverage `COMPLETE`, authority `LOADED`, provenance `client-player:active-effects`.
  - `static NativeRegistryReader.Page searchActive(Collection<MobEffectInstance> active, String text, List<String> ids, List<String> tags, int offset, int limit, Evidence.Scope scope)`: sorts by effect ID, applies the same text/IDs/tags semantics as `mob_effect` (tags from `instance.getEffect().tags()`), then pages by offset.
  - `static Evidence.Record readActive(Collection<MobEffectInstance> active, String effectId, Evidence.Scope scope)`: the current record, or `null` when the effect is no longer active.
- `NativeRegistryReader.search`, `case "active_effect"`: throws `new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "client player unavailable")` when `client.player == null`, else `searchActive(client.player.getActiveEffects(), ...)`. `read`, `case "active_effect"`: `client.player == null ? null : readActive(...)`.
- `NativeProvider.metadata()` and `PackSageTools.NATIVE_REGISTRIES` add `"active_effect"` after `"potion"`. `readEvidence` accepts the `active_effect:` prefix through the existing registry branch.

- [ ] **Step 1: Write the failing tests**

```java
@Test void activeEffectsAreSortedFilteredAndPaged() {
    var active = List.of(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 600, 1),
            new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0));
    var page = NativeEffectReader.searchActive(active, null, List.of(), List.of(), 0, 16, SCOPE);
    assertEquals(List.of("active_effect:minecraft:night_vision", "active_effect:minecraft:speed"),
            page.records().stream().map(record -> record.source().sourceHandle()).toList());
    var speed = page.records().getLast().payload().values();
    assertEquals(0, BigDecimal.ONE.compareTo(((Evidence.JsonNumber) speed.get("amplifier")).value()));
    assertEquals(0, new BigDecimal(600).compareTo(((Evidence.JsonNumber) speed.get("durationTicks")).value()));
    assertEquals(new Evidence.JsonBoolean(false), speed.get("infinite"));
    assertEquals(new Evidence.JsonBoolean(true), page.records().getFirst().payload().values().get("infinite"));
    var first = NativeEffectReader.searchActive(active, null, List.of(), List.of(), 0, 1, SCOPE);
    assertEquals(1, first.nextOffset());
    assertFalse(first.exhausted());
    assertEquals(List.of("active_effect:minecraft:speed"), NativeEffectReader.searchActive(active, null,
            List.of("minecraft:speed"), List.of(), 0, 16, SCOPE).records().stream().map(record -> record.source().sourceHandle()).toList());
}

@Test void expiredEffectIsNotReplayed() {
    assertNull(NativeEffectReader.readActive(List.of(), "minecraft:speed", SCOPE));
    assertNotNull(NativeEffectReader.readActive(List.of(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 20, 0)), "minecraft:speed", SCOPE));
}
```

In `PackSageToolsTest`:

```java
@Test void registrySchemaOffersActiveEffect() {
    var registry = PackSageTools.definitions().stream().filter(tool -> tool.name().equals("search_registry")).findFirst().orElseThrow();
    assertTrue(registry.serializedSchema().contains("\"active_effect\""));
}
```

- [ ] **Step 2: Run both test classes and confirm failure**
- [ ] **Step 3: Implement the three `NativeEffectReader` methods and the switch, metadata, prefix and schema list changes**
- [ ] **Step 4: Edit the prompt sentence**

In `GroundingInstructions`, change `search_registry discovers identities in the selected catalog;` to `search_registry discovers identities in the selected catalog; active_effect lists the player's current effect instances, not definitions;`. Do not change other wording. If a `GroundingInstructionsTest` pins the paragraph, update its expected text in the same commit.

- [ ] **Step 5: Run `dev.packsage.knowledge.nativegame.*`, `dev.packsage.model.*` and `dev.packsage.knowledge.*` and confirm all pass**
- [ ] **Step 6: Commit** the code change; commit the prompt sentence as a separate commit.

---

### Task D1: Potion contents and component fingerprints on item payloads

**Files:**
- Create: `src/main/java/dev/packsage/knowledge/nativegame/NativeItemFacts.java`
- Modify: `src/main/java/dev/packsage/knowledge/nativegame/NativeSnapshot.java` (`inventoryRecord`, after `payload.put("components", ...)`)
- Modify: `src/main/java/dev/packsage/knowledge/storage/NativeStorageReader.java` (each item payload map that receives `components`: opened entries ~line 185, item/targeted entries ~line 540, owner map ~line 465)
- Modify: `src/main/java/dev/packsage/knowledge/storage/NativeServerStorageReader.java` (payload ~line 351)
- Test: `src/test/java/dev/packsage/knowledge/nativegame/NativeItemFactsTest.java`

**Interfaces:**
- Produces: `public final class NativeItemFacts` with
  - `public static void enrich(Map<String, Evidence.JsonValue> payload, ItemStack stack, HolderLookup.Provider registries)`: puts `componentFingerprint` (or `null` plus `variantDetail: "unavailable"`) and, when `stack.has(DataComponents.POTION_CONTENTS)`, `potionContents`.
  - `static String componentFingerprint(ItemStack stack, HolderLookup.Provider registries)`: lowercase hex SHA-256 of `<item id>\n<canonical JSON>`, where the JSON is `DataComponentPatch.CODEC` applied to `stack.getComponentsPatch()` with object keys sorted recursively; `null` when encoding fails.
  - `static Evidence.JsonObject potionContents(ItemStack stack)`: `{potion, customColor, effects, durationScale}` per the spec, plus `durationScaleStatus: "unknown_item"` when the scale is null; effects capped at 32 with `effectsStatus: "bounded"` beyond.

Call `enrich` at every listed site before any size check, so fingerprints are computed before `components` can be truncated.

- [ ] **Step 1: Write the failing tests**

```java
@Test void differentPotionContentsGiveDifferentFingerprints() {
    var strength = PotionContents.createItemStack(Items.POTION, Potions.STRENGTH);
    var swiftness = PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS);
    assertNotEquals(NativeItemFacts.componentFingerprint(strength, registries), NativeItemFacts.componentFingerprint(swiftness, registries));
    assertEquals(NativeItemFacts.componentFingerprint(strength, registries),
            NativeItemFacts.componentFingerprint(PotionContents.createItemStack(Items.POTION, Potions.STRENGTH), registries));
}

@Test void fingerprintIgnoresComponentInsertionOrder() {
    var first = new ItemStack(Items.DIAMOND_SWORD);
    first.set(DataComponents.CUSTOM_NAME, Component.literal("A"));
    first.set(DataComponents.DAMAGE, 3);
    var second = new ItemStack(Items.DIAMOND_SWORD);
    second.set(DataComponents.DAMAGE, 3);
    second.set(DataComponents.CUSTOM_NAME, Component.literal("A"));
    assertEquals(NativeItemFacts.componentFingerprint(first, registries), NativeItemFacts.componentFingerprint(second, registries));
}

@Test void fingerprintSurvivesComponentTruncation() {
    var payload = new LinkedHashMap<String, Evidence.JsonValue>();
    NativeItemFacts.enrich(payload, PotionContents.createItemStack(Items.POTION, Potions.STRENGTH), registries);
    payload.put("components", new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unknown_truncated"))));
    assertTrue(payload.get("componentFingerprint") instanceof Evidence.JsonString);
}

@Test void lingeringPotionDecodesBaseAndCustomEffectsWithScale() {
    var stack = PotionContents.createItemStack(Items.LINGERING_POTION, Potions.STRENGTH);
    stack.set(DataComponents.POTION_CONTENTS, stack.get(DataComponents.POTION_CONTENTS)
            .withEffectAdded(new MobEffectInstance(MobEffects.JUMP, 200, 1)));
    var contents = NativeItemFacts.potionContents(stack).values();
    assertEquals(new Evidence.JsonString("minecraft:strength"), contents.get("potion"));
    assertEquals(0, new BigDecimal("0.25").compareTo(((Evidence.JsonNumber) contents.get("durationScale")).value()));
    var effects = ((Evidence.JsonArray) contents.get("effects")).values();
    assertEquals(List.of("potion", "custom"), effects.stream()
            .map(effect -> ((Evidence.JsonString) ((Evidence.JsonObject) effect).values().get("origin")).value()).toList());
}

@Test void unknownPotionCarrierReportsUnknownScale() {
    var stack = new ItemStack(Items.STICK);
    stack.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.STRENGTH));
    var contents = NativeItemFacts.potionContents(stack).values();
    assertEquals(new Evidence.JsonNull(), contents.get("durationScale"));
    assertEquals(new Evidence.JsonString("unknown_item"), contents.get("durationScaleStatus"));
}
```

`registries` is `RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)`, as in `NativeBrewingReaderTest`.

- [ ] **Step 2: Run and confirm failure** (`...NativeItemFactsTest`)
- [ ] **Step 3: Implement `NativeItemFacts` and add the `enrich` calls at all listed sites**
- [ ] **Step 4: Run `dev.packsage.knowledge.nativegame.*`, `dev.packsage.knowledge.storage.*` and `dev.packsage.knowledge.inventory.*` and confirm all pass**

Existing storage tests that compare whole payloads may need the new keys added to their expectations; change only those expectations.

- [ ] **Step 5: Commit**

---

### Task E1: Recipe-preview and trade-preview slots in opened menus

**Files:**
- Modify: `src/main/java/dev/packsage/knowledge/storage/NativeStorageReader.java` (`openedEntries` loop, `OpenedEntries` record, opened `Observation` details at ~line 340)
- Test: `src/test/java/dev/packsage/knowledge/storage/NativeStorageReaderTest.java`

**Interfaces:**
- Produces: `static String projectionKind(Class<?> menuType, Class<?> slotType, int menuSlot, int combinerResultSlot)` returning `"recipe_preview"`, `"trade_preview"` or `null`, per the spec's exact class and index list. `combinerResultSlot` is `menu instanceof ItemCombinerMenu combiner ? combiner.getResultSlot() : -1`.
- Produces: `record OpenedEntries(List<Evidence.Record> entries, boolean truncated, Evidence.Coverage coverage, List<String> projections)`; keep a three-argument constructor that passes `List.of()` if other callers need it.

In the loop, after skipping player-inventory slots and before the `supportedStockSlot` check, call `projectionKind`; for a non-null kind add `index + "=" + kind` to `projections` and `continue` without changing coverage. The opened `Observation` details become a `LinkedHashMap` with the existing keys plus `excludedProjections` (a `JsonArray` of strings) when the list is non-empty.

- [ ] **Step 1: Write the failing tests**

```java
@Test void projectionKindRecognizesVanillaResultSlots() {
    assertEquals("recipe_preview", NativeStorageReader.projectionKind(CraftingMenu.class, ResultSlot.class, 0, -1));
    assertEquals("recipe_preview", NativeStorageReader.projectionKind(CrafterMenu.class, NonInteractiveResultSlot.class, 45, -1));
    assertEquals("trade_preview", NativeStorageReader.projectionKind(MerchantMenu.class, MerchantResultSlot.class, 2, -1));
    assertEquals("recipe_preview", NativeStorageReader.projectionKind(GrindstoneMenu.class, Slot.class, 2, -1));
    assertEquals("recipe_preview", NativeStorageReader.projectionKind(StonecutterMenu.class, Slot.class, 1, -1));
    assertEquals("recipe_preview", NativeStorageReader.projectionKind(LoomMenu.class, Slot.class, 3, -1));
    assertEquals("recipe_preview", NativeStorageReader.projectionKind(CartographyTableMenu.class, Slot.class, 2, -1));
    assertEquals("recipe_preview", NativeStorageReader.projectionKind(AnvilMenu.class, Slot.class, 2, 2));
    assertNull(NativeStorageReader.projectionKind(GrindstoneMenu.class, Slot.class, 0, -1));
    assertNull(NativeStorageReader.projectionKind(EnchantmentMenu.class, Slot.class, 0, -1));
    assertNull(NativeStorageReader.projectionKind(ChestMenu.class, Slot.class, 2, -1));
}

@Test void openedCaptureExcludesPreviewSlotsWithoutLosingCompleteCoverage() {
    var menu = new TestMenu();
    var stock = new SimpleContainer(1);
    stock.setItem(0, new ItemStack(Items.OAK_PLANKS, 4));
    var result = new SimpleContainer(1);
    result.setItem(0, new ItemStack(Items.CRAFTING_TABLE));
    menu.add(new Slot(stock, 0, 0, 0));
    menu.add(new NonInteractiveResultSlot(result, 0, 0, 0));
    var captured = NativeStorageReader.openedEntries(menu, new SimpleContainer(1), FIRST, Instant.EPOCH, registries);
    assertEquals(List.of("minecraft:oak_planks"), captured.entries().stream().map(Evidence.Record::title).toList());
    assertEquals(List.of("1=recipe_preview"), captured.projections());
    assertEquals(Evidence.Coverage.COMPLETE, captured.coverage());
}
```

Use the existing `TestMenu` helper; add a public `add(Slot)` wrapper around `addSlot` if it has none.

- [ ] **Step 2: Run and confirm failure** (`dev.packsage.knowledge.storage.NativeStorageReaderTest`)
- [ ] **Step 3: Implement `projectionKind`, the record field, the loop change and the details key**
- [ ] **Step 4: Run `dev.packsage.knowledge.storage.*` and confirm all pass**
- [ ] **Step 5: Commit**

---

### Task F1: Localized names on brewing records

**Files:**
- Modify: `src/main/java/dev/packsage/knowledge/nativegame/NativeBrewingReader.java` (`record` method and its three callers)
- Test: `src/test/java/dev/packsage/knowledge/nativegame/NativeBrewingReaderTest.java`

**Interfaces:**
- Changes the private `record(...)` method to also receive the input `ItemStack` (null for custom rules whose input is an ingredient) and the reagent `Ingredient`. It sets the title to `<output hover name> brewing transformation` and adds payload `names: {input, reagents, output}`: hover names of the input stack (or null), up to 64 reagent display names from `ingredient.getItems()` (`namesStatus: "bounded"` above that) and the output stack. Opaque projections keep `Opaque brewing transformation`.
- Add `names` to the payload map before `NativeSnapshot.recipePayloadJson` is wrapped, keeping the typed `RecipePayload` unchanged.

- [ ] **Step 1: Write the failing test**

```java
@Test void localizedPotionNameFindsTheBrewingRecord() {
    var brewing = new PotionBrewing.Builder(FeatureFlags.DEFAULT_FLAGS);
    PotionBrewing.addVanillaMixes(brewing);
    var name = PotionContents.createItemStack(Items.POTION, Potions.STRENGTH).getHoverName().getString();

    var page = NativeBrewingReader.searchCandidates(brewing.build(), registries, items, null, null, name, 0, 64, SCOPE);

    assertFalse(page.records().isEmpty());
    assertTrue(page.records().stream().anyMatch(record -> record.title().equals(name + " brewing transformation")));
    var names = (Evidence.JsonObject) page.records().getFirst().payload().values().get("names");
    assertTrue(names.values().containsKey("reagents"));
}
```

If no record appears in the first 64 because of the 512-candidate scan bound, follow `nextOffset` until `exhausted()` and assert over all pages.

- [ ] **Step 2: Run and confirm failure** (`...NativeBrewingReaderTest`)
- [ ] **Step 3: Implement the title and `names` payload**
- [ ] **Step 4: Run `...NativeBrewingReaderTest` and `dev.packsage.knowledge.RecipePlannerTest`; confirm all pass**
- [ ] **Step 5: Commit**

---

### Task G1: Native recipe scan progress detail

**Files:**
- Modify: `src/main/java/dev/packsage/knowledge/nativegame/NativeRecipeReader.java` (`search`, `Page`)
- Modify: `src/main/java/dev/packsage/knowledge/nativegame/NativeProvider.java` (`searchRecipe` result construction)
- Test: `src/test/java/dev/packsage/knowledge/nativegame/NativeRecipeReaderTest.java`

**Interfaces:**
- Produces: `record Page(List<Evidence.Record> records, int nextOffset, boolean exhausted, int scanned, boolean partial, String detail)` with the existing four- and five-argument constructors passing `null`.
- Produces: `static String progressDetail(int startOffset, int nextOffset, int ordinaryCount, int brewingCount, int matches, boolean hasContinuation)` returning exactly the spec format:
  `scanned=<startOffset>-<nextOffset> of <ordinaryCount + brewingCount> (ordinary recipes <ordinaryCount>, brewing candidates <brewingCount>); phase=<ordinary|brewing>; matches=<matches>`, plus `; no matches in this range; continue with the cursor to scan the rest` when `matches == 0 && hasContinuation`. `phase` is `ordinary` when `nextOffset <= ordinaryCount`, otherwise `brewing`.
- `NativeRecipeReader.search` fills `detail` using the brewing source's candidate count. Add a package-private `static int candidateCount(PotionBrewing, RegistryAccess)` to `NativeBrewingReader` that returns `source(...).count()`.
- `NativeProvider.searchRecipe` passes `page.detail()` as the `Evidence.Result` detail.

- [ ] **Step 1: Write the failing tests**

```java
@Test void progressDetailNamesTheRangePhaseAndEmptyContinuation() {
    assertEquals("scanned=0-512 of 1300 (ordinary recipes 1200, brewing candidates 100); phase=ordinary; matches=0; "
            + "no matches in this range; continue with the cursor to scan the rest",
            NativeRecipeReader.progressDetail(0, 512, 1200, 100, 0, true));
    assertEquals("scanned=1200-1300 of 1300 (ordinary recipes 1200, brewing candidates 100); phase=brewing; matches=3",
            NativeRecipeReader.progressDetail(1200, 1300, 1200, 100, 3, false));
}
```

- [ ] **Step 2: Run and confirm failure** (`...NativeRecipeReaderTest`)
- [ ] **Step 3: Implement `progressDetail`, `candidateCount`, the `Page.detail` field and the provider pass-through**
- [ ] **Step 4: Run `dev.packsage.knowledge.nativegame.*` and `dev.packsage.knowledge.NativeProviderTest`; confirm all pass**
- [ ] **Step 5: Commit**

---

### Task H1: Withhold planner batch counts for brewing

**Files:**
- Modify: `src/main/java/dev/packsage/knowledge/RecipePlanner.java`
- Test: `src/test/java/dev/packsage/knowledge/RecipePlannerTest.java`

**Interfaces:**
- Produces: `private static boolean sharedBatch(Evidence.Record record)`, true when any constraint starts with `brewing-batch=`. In `visit`, a shared-batch candidate uses `batches = null`. `planned(...)` writes `batches: null`, `producedQuantity: null` and `operationSemantics` with the exact spec string for those records.

- [ ] **Step 1: Write the failing test**

Add a fixture brewing record to the existing planner fixture style: output `minecraft:potion` with components `{"minecraft:potion_contents":"minecraft:strength"}`, quantity 1, constraint `brewing-batch=up-to-3-bottles; reagent=one-per-operation; fuel=unknown-per-operation; remainders=unknown`, coverage `PARTIAL`.

```java
@Test void brewingRoutesWithholdBatchCounts() {
    var result = planner(brewingRecord()).plan("minecraft:potion", 3, SCOPE, 1);
    var payload = result.records().getFirst().payload().values();
    assertEquals(new Evidence.JsonNull(), payload.get("batches"));
    assertEquals(new Evidence.JsonNull(), payload.get("producedQuantity"));
    assertEquals(new Evidence.JsonString("brewing: up to 3 bottles per operation, one reagent per operation; "
            + "operation count depends on bottle grouping and is not computed"), payload.get("operationSemantics"));
    assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
}
```

Reuse the class's existing helpers for building records and the `KnowledgeIndex`; add `brewingRecord()` beside them.

- [ ] **Step 2: Run and confirm failure** (`dev.packsage.knowledge.RecipePlannerTest`)
- [ ] **Step 3: Implement `sharedBatch` and the `planned` change**
- [ ] **Step 4: Run `dev.packsage.knowledge.*` and confirm all pass**
- [ ] **Step 5: Commit**

---

### Task T1: Player testing instructions

**Files:**
- Create: `docs/testing/2026-10-21-minecraft-adapter-completion.md`

Write numbered player checks in the style of `docs/testing/2026-10-19-brewing.md`. Each check states the setup in the world, the exact question to type into PackSage and what to look for in the reply and its sources. Cover:

1. Enchantment costs and mechanics: "What does Sharpness V add to damage, and what enchanting levels can roll it?" Expect per-level additions and costs.
2. Eligibility: hold a diamond pickaxe with Efficiency, ask "Which enchantments can still go on the pickaxe I'm holding?" Expect supported entries without Fortune/Silk Touch conflicts misreported.
3. Declared modifiers: "How much faster does Speed II make me?" Expect 40% from the effect definition.
4. Active effects: drink a Speed potion, ask "What effects do I have and how long are they left?" Wait for it to expire, ask again. Expect a fresh read that no longer lists it.
5. Potion item: carry a lingering Strength potion and a tipped arrow; ask what each does. Expect the 0.25 and 0.125 duration scales.
6. Variants: carry two differently named swords; ask "Do I have two different swords?" Expect distinct items.
7. Previews: open a crafting table with a full recipe; ask "What's in this crafting table?" Expect the grid items and no crafted output counted as stored.
8. Brewing: in a new chat, "How do I brew a Potion of Strength?" with no hints. Expect native brewing records found by name and a plan without a bottle batch count.
9. Carried-forward entity checks from the spec: positive donkey and mule cargo/equipment through opened, targeted and ridden access; a native chest boat targeted and ridden; switching mounts while a continuation is pending.

Note that the `active_effect` prompt clause is a prompt change: test it through the in-game editor workflow in `AGENTS.md` before treating it as accepted.

- [ ] **Step 1: Write the document**
- [ ] **Step 2: Commit**

---

### Task Z1: Merge lanes and run the full build

**Files:** none new.

- [ ] **Step 1: Merge each lane into `feat/packsage-v1` with `git merge --no-ff`, in lane order A–H, then T**
- [ ] **Step 2: Run the full build**

Run: `$env:JAVA_HOME='D:\Project\packsage\.tools\jdk\jdk-21.0.12.1'; .\gradlew.bat test build`
Expected: BUILD SUCCESSFUL. Record the total, skipped and failed test counts and the SHA-256 of `build/libs/packsage-0.1.0.jar` (`Get-FileHash -Algorithm SHA256`).

- [ ] **Step 3: Fix any failure as its own commit, then rerun Step 2**

---

### Task Z2: Validation record, roadmaps and changelog

**Files:**
- Create: `docs/validation/2026-10-21-minecraft-adapter-completion.md`
- Modify: `docs/roadmap/2026-10-13-todo.md`
- Modify: `docs/roadmap/2026-10-21-adapter-feature-todo.md`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Write the validation record**

Record the Z1 command, test counts, JAR hash, the delivered bounds from the spec, and that installed acceptance is pending the [player checks](../testing/2026-10-21-minecraft-adapter-completion.md).

- [ ] **Step 2: Update both roadmaps**

Every item this plan implements stays unchecked, because the live checks have not run. Change each item's wording to "implemented; automated checks pass; installed acceptance pending", following existing entries such as Modonomicon's. Affected items in the tool roadmap: inventory result semantics (stored quantities/previews, component variants), enchantment per-level costs, loaded effect payloads, decoded effect mechanics, applicable enchantments, declared attribute modifiers, active player effects, potion-item contents, and the remaining native brewing discovery, presentation and batch items. Mirror the same status in the feature checklist's Minecraft entries without moving them, since the adapter already exists. Leave the command entries untouched.

- [ ] **Step 3: Add a `### 2026-10-21` changelog entry** in the existing voice: what the player gains, then the pending acceptance.
- [ ] **Step 4: Check relative links in all four files and run `git diff --check`**
- [ ] **Step 5: Commit**
