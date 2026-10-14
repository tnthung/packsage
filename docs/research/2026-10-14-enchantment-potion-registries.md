# Enchantment, potion, and effect registries in Minecraft 1.21.1

Research date: 2026-10-14

## Conclusion

Minecraft and NeoForge expose shared registries for standard enchantments,
potions, and mob effects. They identify entries and expose standard data, but
do not centralize all mod behavior: hooks, custom effect components, callbacks,
brewing additions, and affix systems can remain mod-owned. A generic reader can
build a standard catalog, but deeper behavior is partial without relevant mod
APIs or data formats.

Scope: Minecraft 1.21.1, NeoForge 21.1.251; source and shipped-artifact
evidence only, with no live registry dump or runtime test.

## Three standard registries

| Concept | 1.21.1 registry | Loading model | Standard data and limit |
|---|---|---|---|
| Enchantment | `Registries.ENCHANTMENT` | Datapack registry; accessed through current `RegistryAccess` | Holder and `Enchantment` record; effects may invoke mod-owned components, callbacks, or item hooks. |
| Mob effect | `BuiltInRegistries.MOB_EFFECT` | Built-in registry | `MobEffect` identity/object; behavior may live in subclasses or other handlers. |
| Potion | `BuiltInRegistries.POTION` | Built-in registry | `Potion` identity/object and base effects; item components can add effects/color. |

NeoForge's [1.21.1 registry documentation](https://docs.neoforged.net/docs/1.21.1/concepts/registries/)
describes registries as ID-to-object maps, explains lookup/iteration, and
distinguishes static from datapack registries. Exact cached source declares
`MOB_EFFECT` and `POTION` in `BuiltInRegistries.java`, not `ENCHANTMENT`;
`RegistryDataLoader.java` lists enchantments among world-generation and
synchronized registries. Source JSON is not the effective current catalog.

## Enchantments

In the inspected 1.21.1 Minecraft source, `RegistryDataLoader.java` places
`Registries.ENCHANTMENT` in its world-generation and synchronized registry
groups. Read the current registry from the active level or client connection's
`RegistryAccess` (for example,
`registryOrThrow(Registries.ENCHANTMENT)`). This is safer than scanning mod JAR
JSON files: the registry is the game-loaded view for the current context.

`Enchantment.java` is a record with a description, definition, exclusive set,
and effects. The definition exposes supported and primary item sets, weight,
maximum level, level costs, anvil cost, and slots. `Enchantment.areCompatible`
checks the entries' exclusive sets. These are valuable standard facts for a
catalog and common eligibility/compatibility questions.

Eligibility is not always tag-only. NeoForge's `IItemStackExtension` exposes
`supportsEnchantment` and `isPrimaryItemFor`; the patched `EnchantmentHelper`
uses these item hooks. Custom enchantment effect components can have
registered codecs while delegating execution to mod code. Decoding common
components does not interpret arbitrary callbacks.

## Mob effects, potion values, and potion items

NeoForge's [1.21.1 Mob Effects & Potions documentation](https://docs.neoforged.net/docs/1.21.1/items/mobeffects/)
defines the data layers:

- `MobEffect` is a registered singleton describing an effect type.
- `MobEffectInstance` is an application instance with duration, amplifier,
  ambient/visibility flags, and other instance state.
- `Potion` is a collection of `MobEffectInstance` values.
- Potion items store a `PotionContents` data component.

The exact cached 1.21.1 source shows `PotionContents` as a record containing an
optional potion holder, optional custom color, and a list of custom effect
instances. Its `getAllEffects()` method combines the base potion effects with
those custom effects. Thus an `ItemStack` may have no named potion, or may add
effects beyond the named potion's base definition. For stack-level answers,
inspect `DataComponents.POTION_CONTENTS`; do not infer all effects from the
potion registry entry alone.

Effect identity and standard fields do not reveal the full effect semantics.
The 1.21.1 documentation notes that many effects have logic in other places;
custom effects can implement tick methods or use handlers. A reader can report
the registered effect, category/color where exposed, and observed instance
parameters. It cannot promise a complete natural-language explanation of
arbitrary mod behavior from the registry alone.

## Brewing

Vanilla brewing is handled by `PotionBrewing`, not by a general datapack recipe
registry. NeoForge's 1.21.1 documentation says Mojang provides no datapack
support for brewing recipes and exposes `RegisterBrewingRecipesEvent` for mods
to add recipes. The [NeoForge 1.21.1 event source](https://raw.githubusercontent.com/neoforged/NeoForge/1.21.1/src/main/java/net/neoforged/neoforge/event/brewing/RegisterBrewingRecipesEvent.java)
documents that the event fires on both client and server and supplies a
`PotionBrewing.Builder` and `RegistryAccess`.

The [NeoForge `PotionBrewing` patch for 1.21.1](https://raw.githubusercontent.com/neoforged/NeoForge/1.21.1/patches/net/minecraft/world/item/alchemy/PotionBrewing.java.patch)
shows vanilla mixes added before the event is posted. It also adds support for
simple input/ingredient/output recipes and custom `IBrewingRecipe` instances.
The [NeoForge `BrewingRecipeRegistry` source](https://raw.githubusercontent.com/neoforged/NeoForge/1.21.1/src/main/java/net/neoforged/neoforge/common/brewing/BrewingRecipeRegistry.java)
states that queries use vanilla `PotionBrewing`, while registration uses the
event.

There is no single public enumerable list that represents all brewing
behavior. Vanilla potion and container mixes live in private lists on
`PotionBrewing`; NeoForge's public `getRecipes()` returns only the custom
`IBrewingRecipe` list and explicitly excludes those vanilla mixes. `hasMix` and
`mix` can answer specific input queries, but arbitrary `IBrewingRecipe`
implementations run code and are not necessarily declarative rows that a
generic reader can enumerate or explain. The server constructs a
`PotionBrewing` instance during bootstrap with its registry access, as shown in
the [NeoForge 1.21.1 MinecraftServer patch](https://raw.githubusercontent.com/neoforged/NeoForge/1.21.1/patches/net/minecraft/server/MinecraftServer.java.patch).
The documentation's registration example is consistent with this source; it
does not promise that all vanilla and custom brewing behavior is enumerable.

## Current ATM10 artifact examples

The following are observations from shipped mod JAR contents and source/class
inspection, not a runtime enumeration of the active game's registries:

- `ApothicEnchanting-1.21.1-1.6.2.jar` contains 20
  `data/apothic_enchanting/enchantment/*.json` entries and overrides for
  vanilla Sharpness and Protection. Its `miners_fervor` definition declares maximum level
  5, mining-supported items, an exclusive set with Efficiency, and a custom
  `apothic_enchanting:miners_fervor` effect component. Its code computes a
  default maximum level using power 200 and has an IMC message path for changing
  hard caps; no verified user config field was found for that behavior.
- `tombstone-neoforge-1.21.1-9.5.6.jar` contains 12 enchantment JSON entries,
  referencing custom entity-effect types such as `apply_stackable_effect` and
  `apply_bone_shield_effect`. Its `ApplyRandomBadEffect` uses a vanilla
  `EnchantmentEntityEffect` codec, and Tombstone also defines a custom
  `MobEffect`.
- `potionsmaster-21.1.113-1.0.1.jar` registers `Potion` and `MobEffect`
  entries through `DeferredRegister` using the standard registry keys.
- `Apotheosis-1.21.1-8.8.0.jar` includes affix configuration beneath
  `data/apotheosis/affixes/.../enchantment/`. Those files describe affixes,
  not entries in the standard enchantment registry; the path and owning schema
  matter when classifying data.
- The installed `kubejs/server_scripts/Tweaks/tags.js:220-222` removes Miner's Fervor from
  `minecraft:non_treasure` through an enchantment tag event. This changes tag
  membership; it is not a separate semantic manager.

These examples show that standard registry entries, registry-backed JSON,
component codecs, tags, and mod-specific systems coexist. Do not assume every
mod mechanic or every object called an enchantment/potion uses one of the three
standard registries. Affixes, spells, custom carriers, and other addon models
may be separate.

## PackSage implications

`NativeSnapshot.java` currently captures item/block registries, item tags, and
recipes; it has no dedicated enchantment, potion, or mob-effect catalog. Its
inventory component serialization preserves components present on inspected
stacks, but that is not a substitute for discovering all registry entries.

Within the existing native adapter, the bounded path is:

1. Discover standard registry entries from the current game registry access.
2. Record registry IDs and standard fields separately from interpreted
   mechanics, with provenance and the active Minecraft/NeoForge version.
3. Decode common supported item eligibility, exclusivity, effect-instance,
   and potion-content data where the API exposes it.
4. Observe version-specific item hooks where useful; do not reduce every
   eligibility answer to tags or exclusive-set data.
5. Keep custom component/effect semantics, affixes, and mod-specific brewing
   descriptions behind relevant adapters. Mark unhandled behavior as partial.

Represent catalog coverage and semantic coverage separately. A complete list
of standard registry IDs does not mean PackSage can explain each mechanic,
derive every item-specific eligibility result, or enumerate every brewing
transformation.

## Evidence and limits

Installed artifacts were read from
`C:\Users\hung1\curseforge\minecraft\Instances\All the Mods 10 - ATM10\mods`.

The exact cached source and compiled archive inspected was
`C:\Users\hung1\.gradle\caches\neoformruntime\intermediate_results\sourcesAndCompiledWithNeoForge_308ee162acce69e2f6e98e68aa5241b31763ef15_output.jar`.
Its manifest identifies Minecraft 1.21.1 and NeoForge 21.1.251. Evidence was
obtained by inspecting its ZIP sources, plus the listed shipped mod JAR
contents and compiled classes (`javap`). No game was launched and no
runtime registry dump was collected; the ATM10 examples establish artifact
contents, not that every example is active after datapack, script, or runtime
conditions are applied.

Official versioned references:

- [NeoForge registries, 1.21.1](https://docs.neoforged.net/docs/1.21.1/concepts/registries/)
- [NeoForge mob effects, potions, and brewing, 1.21.1](https://docs.neoforged.net/docs/1.21.1/items/mobeffects/)
- [NeoForge `RegisterBrewingRecipesEvent` source, 1.21.1 branch](https://raw.githubusercontent.com/neoforged/NeoForge/1.21.1/src/main/java/net/neoforged/neoforge/event/brewing/RegisterBrewingRecipesEvent.java)
- [NeoForge `PotionBrewing` patch, 1.21.1 branch](https://raw.githubusercontent.com/neoforged/NeoForge/1.21.1/patches/net/minecraft/world/item/alchemy/PotionBrewing.java.patch)
- [NeoForge `BrewingRecipeRegistry` source, 1.21.1 branch](https://raw.githubusercontent.com/neoforged/NeoForge/1.21.1/src/main/java/net/neoforged/neoforge/common/brewing/BrewingRecipeRegistry.java)
- [NeoForge `MinecraftServer` patch, 1.21.1 branch](https://raw.githubusercontent.com/neoforged/NeoForge/1.21.1/patches/net/minecraft/server/MinecraftServer.java.patch)
- [NeoForge enchantment documentation, 1.21.1](https://docs.neoforged.net/docs/1.21.1/resources/server/enchantments/)
