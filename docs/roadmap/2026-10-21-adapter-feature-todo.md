# PackSage adapter feature todo

Updated: 2026-10-21.

Feature-grouped summary of the [tool roadmap](2026-10-13-todo.md). A mod can appear under multiple features. Unchecked items include missing implementation and implemented features still awaiting the stated live acceptance. Detailed contracts, evidence and acceptance limits remain in the tool roadmap and its linked reports.

Features whose implementation and acceptance criteria are both met leave this list; the Minecraft status-effect and potion-item inspection features are accepted and appear checked in the tool roadmap.

Generic JEI displays or GuideME documentation do not establish dedicated mechanics or storage adapter coverage. Reliquified Artifacts shares the existing Artifacts logical integration.

## 1. Has adapter, not fully implemented

### Inventory and equipment inspection

- [ ] Minecraft — opened-path entity-menu checks and stored quantities that exclude recipe previews (implemented; automated checks pass; installed acceptance pending or failed: the opened target was not exercised, and the assistant chose `targeted` instead of `opened` while the crafting and stonecutter menus were open). Targeted, ridden and mount-switch fixtures passed; see the [validation record](../validation/2026-10-21-minecraft-adapter-completion.md).

### Enchantment mechanics

- [ ] Minecraft — applicable enchantments for an item (implemented; automated checks pass; installed acceptance failed: the assistant made no `read_evidence` call, so `enchantmentEligibility` was never read; see the [validation record](../validation/2026-10-21-minecraft-adapter-completion.md)). Per-level costs, effect payloads and built-in effect decoding passed installed acceptance.

### Ability definitions and attributes

- [ ] Artifacts — decode ability components and item-specific attribute modifiers. Inventory reads already return raw item components.
- [ ] Reliquified Artifacts — effective definitions and bridge-supplied ability overrides through the Artifacts integration.

### Ability cooldowns

- [ ] Relics.
- [ ] Artifacts.

### Item rank and upgrade points

- [ ] Relics — rank, relic points and per-ability upgrade levels are implemented; nonzero values await live acceptance.

### Item upgrade state

- [ ] Relics — upgrade costs and availability beyond rank, points and per-ability levels.

### Guide item relationships

- [ ] Modonomicon — entry-to-item links; acceptance remains open.
- [ ] GuideME — page-to-item mappings; acceptance remains open.

### Guide recipe references

- [ ] Modonomicon — embedded recipe references; acceptance remains open.
- [ ] GuideME — recipe tags; unsupported nodes remain partial.
- [ ] Immersive Engineering — manual recipe references; remaining selector coverage and acceptance.

### Guide prerequisites and progress

- [ ] Modonomicon — prerequisite acceptance and dedicated visibility/unlock progress reads.

### Guide navigation

Entry and page opening, including after a full restart, is accepted for Modonomicon, GuideME, Immersive Engineering and Actually Additions.

- [ ] GuideME — installed scrolling to a heading below the top of a page; the installed Powah pages have only a top title heading.

### Guide reading before first use

- [ ] Immersive Engineering — model reads find no manual entries until the manual has been opened in the session. Navigation clicks already load it.

### Brewing discovery, recipe reads and planning

- [ ] Minecraft — localized potion-name matching and empty-page progress (implemented; automated checks pass; installed acceptance failed: the reader returned localized titles and progress text, but the model searched `strength` rather than the localized phrase), and planner brewing batch presentation (installed acceptance failed: no `plan_recipe` call, and the reply stated a three-bottle batch without plan evidence); see the [validation record](../validation/2026-10-21-minecraft-adapter-completion.md).
- [ ] Actually Additions — investigate the native laser recipe read that returned no inputs.
- [ ] PackSage planner — investigate the empty Powah partial plans, then add other verified transformation semantics to bounded route planning.

### Guide search reliability

- [ ] Modonomicon — investigate the title-only and budget failures in guide search.
- [ ] Artifacts — investigate an empty partial tooltip result returned without a cursor.

### Construction geometry

- [ ] Immersive Engineering — native template geometry.
- [ ] GuideME — embedded structure definitions.

### Construction material requirements

- [ ] Immersive Engineering — native template materials.

### Command discovery, help and execution

- [ ] Minecraft — define the contract and permission boundaries, then implement command support.

### Shared reliability and chat acceptance

This is PackSage core work, rather than a missing third-party mod adapter.

- [ ] PackSage — assistant tool selection for eligibility reads, opened versus targeted menus, targeted entities and brewing plans (see the [tool roadmap](2026-10-13-todo.md)), plus freshness/continuation audits, version dispatch, coverage diagnostics, latency, citations, source presentation, search behavior and remaining parallel-chat checks.
- [ ] PackSage — adapter query execution through each provider's own live search and paging, and target references that keep their registered type. The Minecraft part of component-variant preservation passed installed acceptance ([validation record](../validation/2026-10-21-minecraft-adapter-completion.md)); provider aggregates remain pending.
- [ ] PackSage — installed recheck of the merged adapter-popup width and scrollbar.

## 2. Does not have an adapter at all

### Opened storage inspection

- [ ] Pocket Storage.
- [ ] Sophisticated Backpacks.
- [ ] Sophisticated Storage.
- [ ] Applied Energistics 2.
- [ ] Refined Storage.
- [ ] Mekanism.

### Storage inspection through inventory items

- [ ] Pocket Storage.
- [ ] Sophisticated Backpacks.
- [ ] Sophisticated Storage.
- [ ] Functional Storage.
- [ ] Storage Drawers.
- [ ] Mekanism.

### Targeted storage inspection

- [ ] Sophisticated Backpacks.
- [ ] Sophisticated Storage.
- [ ] Functional Storage.
- [ ] Storage Drawers.
- [ ] Applied Energistics 2.
- [ ] Refined Storage.
- [ ] Mekanism.

### Network discovery, wireless access and complete item totals

- [ ] Applied Energistics 2.
- [ ] Refined Storage.

### Modded mount and vehicle inventories

- [ ] Select provider mods and add verified live cargo/equipment access.

### Quest discovery, dependencies, progress and navigation

- [ ] Heracles.

### Advancement discovery and parent relationships

- [ ] Just Enough Advancements.

### Script-source inspection

- [ ] KubeJS.
- [ ] CraftTweaker.
- [ ] LootJS.

### Recipe-override attribution

- [ ] KubeJS.
- [ ] CraftTweaker.

### Custom recipe and transformation semantics

- [ ] KubeJS — custom recipe fields.
- [ ] Applied Energistics 2 — transformation conditions, Matter Condenser requirements and P2P attunement.

### Enchantment caps and custom mechanics

- [ ] Apothic Enchanting — effective caps and custom effect components.
- [ ] Tombstone — custom enchantment effect types.

### Enchantment descriptions and supplementary information

- [ ] Enchantment Descriptions.
- [ ] Just Enough Resources.

### Custom status-effect mechanics

- [ ] Tombstone.
- [ ] Potions Master.

### Effect descriptions and item-to-effect relationships

- [ ] Just Enough Effect Descriptions.

### Affix definitions and mechanics

- [ ] Apotheosis.

### Mob drops, plant drops, dungeon loot and villager trades

- [ ] Just Enough Resources.

### Effective loot-modifier rules

- [ ] LootJS.

### Profession-to-workstation relationships

- [ ] Just Enough Professions.

### Breeding, taming and entity transformations

- [ ] Just Enough Breeding.

### Biome-to-mob and biome-to-ore relationships

- [ ] Just Enough Biomes.

### Structure contents, biome relationships and loot sources

- [ ] Just Enough Structures.

### Ore-distribution data

- [ ] Just Enough Resources.

### Targeted block/entity tooltip facts

- [ ] Jade.

### Construction geometry

- [ ] Just Enough Immersive Multiblocks.

### Construction material requirements

- [ ] Just Enough Immersive Multiblocks.
- [ ] Just Enough Mekanism Multiblocks.

### Construction calculator dimensions and numeric results

- [ ] Just Enough Mekanism Multiblocks.

### Maps and waypoints

- [ ] JourneyMap — proposed example provider.

### Biome locating

- [ ] Nature's Compass.

### Structure locating

- [ ] Explorer's Compass.

### Authorized server-only inventories, loot and configuration

- [ ] PackSage — optional server companion remains unimplemented.

### Version-aware external documentation

These tasks use backend web search; no local documentation adapter is required.

- [ ] Refined Storage — official guide pages.
- [ ] Mekanism — official wiki pages.
