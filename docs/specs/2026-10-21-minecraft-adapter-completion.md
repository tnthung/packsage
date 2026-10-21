# Minecraft adapter completion

Date: 2026-10-21.

Scope: every open Minecraft (`native`) adapter item in the [tool roadmap](../roadmap/2026-10-13-todo.md) and [adapter feature checklist](../roadmap/2026-10-21-adapter-feature-todo.md), except command discovery, help and execution. Third-party adapters (Apothic Enchanting, Tombstone, JER and others) and the optional server companion stay out of scope.

The existing [behavior-driven tool design](2026-10-14-behavior-driven-tools.md) still governs signatures, freshness, coverage and continuation. This specification adds fields and one registry selector; it adds no tools, no tool arguments and no filter fields.

## Shared rules

- Every new field is computed from current client data on each search, continuation and `read_evidence` call. Nothing is cached between calls.
- New fields never replace existing payload keys. Existing `minCost`, `maxCost`, `effectsStatus`, `mechanicsStatus` and `components` keep their names and values.
- A bound that cuts output marks the record `PARTIAL` and states the cut in the field's own `status` string. An unknown type is reported with its registry ID and `status: "opaque"`; it is never dropped silently.
- No reader invokes gameplay callbacks that need a server level, loot context, entity target or random source beyond the deterministic evaluations named below.

## Enchantments

### Per-level costs

The `enchantment` registry record adds `levels`: one object per level from 1 to `maxLevel`, capped at 32 levels. Each object has `level`, `minCost` and `maxCost`, from `definition.minCost().calculate(level)` and `definition.maxCost().calculate(level)`. A `maxLevel` above 32 sets `levelsStatus: "bounded"` and `PARTIAL` coverage.

### Loaded effect payloads

The record adds `effects`: the enchantment's `effects()` map encoded with `EnchantmentEffectComponents.CODEC` through the current registry serialization context. Keys are effect-component IDs. When the encoded object exceeds 16,384 UTF-8 bytes or fails to encode, `effects` becomes `{ "status": "unknown_truncated" }` or `{ "status": "unknown" }` and coverage is `PARTIAL`.

### Decoded effect mechanics

The record adds `effectMechanics`, an array with one entry per effect component, capped at 64 components. Each entry has `component` (ID), `kind` and `status`:

| Component value | `kind` | Decoded fields | `status` |
| --- | --- | --- | --- |
| `List<ConditionalEffect<EnchantmentValueEffect>>` or `List<TargetedConditionalEffect<EnchantmentValueEffect>>` | `value` | per effect: `type`, `conditional` (requirements present), `values`: `[{level, add?, multiply?, set?}]` per level | `decoded` |
| A single `EnchantmentValueEffect` (`crossbow_charge_time`, `trident_spin_attack_strength`) | `value` | as above, with `conditional: false` | `decoded` |
| `List<EnchantmentAttributeEffect>` | `attribute` | per effect: `attribute`, `modifierId`, `operation`, `amounts`: `[{level, amount}]` | `decoded` |
| Entity, location or damage-immunity effects | `behavior` | per effect: `type` (codec registry ID), `conditional` | `not_evaluated` |
| `Unit` (`prevent_equipment_drop`, `prevent_armor_change`) | `flag` | none | `decoded` |
| Sound components (`trident_sound`, `crossbow_charging_sounds`) | `presentation` | none | `not_evaluated` |
| An empty list | `empty` | none | `decoded` |
| Anything else, including modded or unregistered component types | `opaque` | none | `opaque` |

Value effects are evaluated per level (1 to `min(maxLevel, 32)`) with the built-in types only: `add` and `set` report the result of `process(level, null, 0)`, `multiply` reports `process(level, null, 1)`, and `all_of` reports its nested effects in order. `remove_binomial` is random; it reports `chance` from its `LevelBasedValue` and no deterministic result. Any other value-effect type is `opaque`. Conditions are reported as present, never evaluated. More than 32 effects in one component cuts the list and marks the entry `bounded`. Any `opaque` or `bounded` entry makes the record `PARTIAL`. `effectsStatus` changes from `registered_components_not_interpreted` to `built_in_components_decoded`.

### Item eligibility

`read_evidence` on an observed player-inventory slot handle (`inventory:`) and on an `item:` registry handle adds `enchantmentEligibility`. Search results do not carry it.

For each enchantment in the current enchantment registry (capped at 512 holders), it reports the enchantment ID when `stack.supportsEnchantment(holder)` is true, with `primary` from `stack.isPrimaryItemFor(holder)` and `compatibleWithCurrent` from `Enchantment.areCompatible` against every enchantment already on the stack (`stack.getTagEnchantments()`). Output fields: `enchantable` (`stack.isEnchantable()`), `supported` (array of `{id, primary, compatibleWithCurrent, currentLevel}`), `inspected` (holder count) and `status` (`complete` or `bounded`). These calls include NeoForge item hooks. An `item:` handle evaluates the item's default stack.

## Status effects

### Declared attribute modifiers

The `mob_effect` registry record adds `attributeModifiers` from `MobEffect.createModifiers(amplifier, consumer)` for amplifiers 0 to 3. Each entry has `attribute` (ID), `modifierId`, `operation` and `amounts`: `[{amplifier, amount}]`. This includes NeoForge curve-based templates. Callback mechanics remain `callbacks_not_interpreted`, so the record stays `PARTIAL`.

### Active player effects

`search_registry` gains the `active_effect` selector. It enumerates the current client player's `getActiveEffects()`, following the existing `dimension` precedent: a runtime-instance selector beside definition selectors. `text`, `ids` (effect IDs) and `tags` (mob-effect holder tags) filter as for `mob_effect`. Each record has handle `active_effect:<effect id>` and payload `effect`, `durationTicks`, `infinite`, `amplifier`, `ambient`, `visible`, `showIcon` and `attributeModifiers` at the instance's amplifier. A record is `COMPLETE` when its instance was read; callbacks remain interpreted only through the `mob_effect` definition.

The registry enum in the tool schema gains `active_effect`, and the assistant instructions' `search_registry` sentence adds one clause saying that `active_effect` lists the player's current effect instances rather than definitions. That prompt change follows the repository's prompt-validation rules.

`read_evidence` on `active_effect:<id>` reads the current instance again. An effect that has expired or been removed returns the existing unknown-reference result; it never replays the earlier record. No player or level returns unavailable. Results follow the ordinary 16/64 limit and native cursor rules.

## Potion-item contents

Every native item payload that already carries encoded `components` (player inventory, opened menu entries, item/targeted storage entries and their owners, and server-read targeted storage) adds `potionContents` when the stack has `minecraft:potion_contents`:

- `potion`: the named potion ID, or null.
- `customColor`: the integer, or null.
- `effects`: base potion effects then custom effects, each `{id, durationTicks, amplifier, ambient, visible, showIcon, origin}` with `origin` `potion` or `custom`, capped at 32.
- `durationScale`: 1.0 for `minecraft:potion` and `minecraft:splash_potion`, 0.25 for `minecraft:lingering_potion`, 0.125 for `minecraft:tipped_arrow`; null otherwise, with `durationScaleStatus: "unknown_item"`.

Instant effects report `durationTicks` as declared; the scale is not applied to the values themselves.

## Inventory result semantics

### Component-distinct variants

Native item payloads add `componentFingerprint`: a lowercase hex SHA-256 of the item ID plus the canonical encoded component JSON, computed before any truncation. Two entries with different components keep different fingerprints even when `components` is reported as `unknown_truncated`, so a reader can tell variants apart without the full detail. A stack whose components cannot be encoded reports `componentFingerprint: null` and `variantDetail: "unavailable"`.

### Recipe previews and result slots in opened menus

Opened-menu reads classify known vanilla computed-result slots instead of treating them as unread. A slot is a projection when its class is exactly `ResultSlot` or `NonInteractiveResultSlot` (`recipe_preview`), or exactly `MerchantResultSlot` (`trade_preview`), or when the exact menu class and menu slot index match: `GrindstoneMenu` 2, `StonecutterMenu` 1, `LoomMenu` 3, `CartographyTableMenu` 2, and `AnvilMenu` or `SmithingMenu` at `getResultSlot()` (all `recipe_preview`). Enchanting-table and beacon input slots hold real items and are not previews; they keep their current handling. Projection slots are excluded from stored contents and listed in the opened observation's details as `excludedProjections`, an array of `<menuSlot>=<kind>` strings. Coverage stays `COMPLETE` when every other slot was read. Unknown slot classes still make coverage `PARTIAL`, as today.

## Brewing

### Localized potion names

Brewing record titles use the output stack's hover name: `<output name> brewing transformation`, for example `Potion of Strength brewing transformation`. The payload adds `names`: `{input, reagents, output}` with hover names of the input stack, each reagent alternative (capped at 64) and the output stack. The existing literal text filter already searches title and payload, so `Potion of Strength` matches without further changes. Opaque records keep the title `Opaque brewing transformation`.

### Empty-page progress

Native `search_recipe` results always set `detail` to the scan position: `scanned=<a>-<b> of <total> (ordinary recipes <n>, brewing candidates <m>); phase=<ordinary|brewing>; matches=<k>`. When a page has no matches and a continuation exists, the detail adds `no matches in this range; continue with the cursor to scan the rest`. The model-facing tool description already explains that an empty page with a cursor is unfinished.

### Brewing batch presentation

`plan_recipe` withholds a numeric `batches` value for records whose constraints contain `brewing-batch=`. Those routes report `batches: null`, `producedQuantity: null` and `operationSemantics: "brewing: up to 3 bottles per operation, one reagent per operation; operation count depends on bottle grouping and is not computed"`. Their inputs stay partial as they are now.

## Live acceptance carried forward

These Minecraft items need the player's world, not new code:

- positive donkey/mule cargo and equipment, mule coverage and corrected horse-slot metadata through opened, targeted and ridden access;
- native chest-boat targeted and ridden contents;
- switching mounts resolves the new entity and invalidates continuations for the previous mount.

The [testing instructions](../testing/2026-10-21-minecraft-adapter-completion.md) list them with the new feature checks.
