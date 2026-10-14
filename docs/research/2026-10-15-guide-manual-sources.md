# Installed guide and manual source contracts

Scope: six sources requested under `search_document`, using the current ATM10 installation and the [implementation plan](../plans/2026-10-15-guide-manual-adapters.md). Installed JAR signatures and resources were inspected with the portable JDK's `javap` and ZIP inspection. These findings establish API shape, not live acceptance. Source branches and public documentation may differ from the installed artifact; initial registration gates use exact inspected versions.

| Logical provider | Installed mod version | Current text source |
|---|---|---|
| `modonomicon` | 1.120.4 | Loaded books, entries and pages; client-localized text holders and player unlock predicates. |
| `guideme` | 21.1.19 | Registered guides and their current parsed page AST; per-page identity/language/frontmatter. |
| `immersiveengineering` | 12.4.2-194 | Loaded manual entry metadata and active localized per-entry resource text. |
| `actuallyadditions` | 1.3.26 | Loaded Patchouli book `actuallyadditions:booklet`. |
| `relics` | 0.12.8 | Registry-reachable relic definitions and localized ability documentation. |
| `artifacts` | 13.2.3 | Artifact default-item ability components and their localized documentation. |

## Manuals

Modonomicon `BookDataManager` exposes loaded/build readiness, book maps and ID lookup. Books expose entry maps; `BookUnlockStateManager` supplies player-specific category/entry/page visibility. `BookTextHolder.getString()` localizes string-backed keys or renders its component. Read text on the client thread and do not extract locked pages merely because their objects are present. [Book structure](https://klikli-dev.github.io/modonomicon/docs/1.21.1/basics/structure/), [entries](https://klikli-dev.github.io/modonomicon/docs/1.21.1/basics/structure/entries/), [localization](https://klikli-dev.github.io/modonomicon/docs/advanced/localization/).

GuideME `guideme.Guides.getAll()/getById(...)` exposes registered guides. A guide supplies current parsed pages and per-ID lookup; `getPages()` throws before pages are loaded. Parsed pages expose ID, language, frontmatter and AST; the source Markdown string has no public getter in 21.1.19. Avoid compiling/rendering entire guides for text discovery. Translation fallback follows GuideME's active loaded page selection. Public changelog coverage lags the installed version, so newer-version Javadocs are not exact-version evidence. [Authoring](https://guideme.appliedenergistics.org/authoring/), [translations](https://guideme.appliedenergistics.org/translation/), [changelog](https://guideme.appliedenergistics.org/changelog/).

Immersive Engineering `ManualHelper.getManual()` exposes `ManualInstance`; its entries have stable locations, title/subtext, required advancement and visibility through `showNodeInList(entry.getTreeNode())`. Public entry APIs do not expose page body text. Bundled text is under `assets/immersiveengineering/manual/<locale>/<entry>.txt`; use the active resource manager and bounded per-entry reads, preserving fallback/formatting limits. Do not open or reload the manual as a tool side effect. [ManualInstance source](https://github.com/BluSunrize/ImmersiveEngineering/blob/1.21.1/src/manual/java/blusunrize/lib/manual/ManualInstance.java), [IEManualInstance source](https://github.com/BluSunrize/ImmersiveEngineering/blob/1.21.1/src/main/java/blusunrize/immersiveengineering/client/manual/IEManualInstance.java).

Actually Additions 1.3.26 retains legacy booklet interfaces but ships its actual text as Patchouli resources under `assets/actuallyadditions/patchouli_books/booklet/en_us/` and its book definition under `data/actuallyadditions/patchouli_books/booklet/book.json`. The source-specific contribution must select that loaded book, not present the legacy interface as the current corpus. [Pinned release](https://github.com/Ellpeck/ActuallyAdditions/releases/tag/v1.3.26), [legacy API source](https://github.com/Ellpeck/ActuallyAdditions/blob/v1.3.26/src/main/java/de/ellpeck/actuallyadditions/api/ActuallyAdditionsAPI.java).

## Ability documentation

Relics exposes `IRelicItem.getRelicData(LivingEntity, ItemStack)`, abilities, templates, IDs and localization keys under `relics.description.*`. Templates and documentation describe supported ability behavior; an actual stack's level, mode, stats and progress are separate instance evidence. Verify exact implementation methods against 0.12.8 rather than assuming a repository branch matches it. [Official repository](https://github.com/Octo-Studios/relics).

Artifacts 13.2.3 uses registered ability data components and `EquipmentAbility.addToTooltip(TooltipWriter)`. Generic localized ability prose alone does not establish configured component numbers or the player's actual stack. Read definitions through known components/default items, label documentation accordingly, and distinguish custom `ability_lore`. The old generic `artifacts:abilities` marker is unused since 13.0.0. [Official component documentation](https://github.com/ochotonida/artifacts/wiki/Creating-Custom-Items).

All source readers must enforce work and text bounds before walking/copying; unavailable pages, opaque custom nodes and missing translations must remain visible as coverage limits. Cursors retain only positions and source identity, and evidence reads resolve current definitions again.

## Implementation checks against the pinned JARs

- Modonomicon `BookContentEntry.getPages()` returns its backing list. `getCategoriesSorted()` copies/sorts categories, so the reader traverses the current category map instead. String-backed holders use the current language lookup; component-backed fields remain explicitly partial.
- GuideME `getPages()` can copy the page map when development pages exist. The pinned reader traverses `pages` and `developmentPages` directly, respects overrides, and rereads parsed AST nodes through metadata-only child paths.
- IE exposes the public `contentsByName` map and `showNodeInList(Tree.AbstractNode)`. The reader preserves raw manual formatting tokens with partial coverage; text seeking is bounded at 16 KiB per entry.
- Actually Additions reuses Patchouli's loaded book and bounded extraction with source fields omitted from its text-only contribution. The ordinary Patchouli structured extraction path is preserved.
- Relics' interface is `it.hurts.sskirillss.relics.api.relics.IRelicItem`. The installed description widget constructs `relics.description.<itemPath>.ability.<abilityId>.description`, or `<mode>.description` for a template mode. The reader uses these current localized definitions without reading the player's mode, level or rolled stats.
- Artifacts uses `artifacts.registry.ModItems.ITEMS`, `artifacts.registry.ModDataComponents.TOOLTIP_ORDER` (component suppliers), and `artifacts.component.ability.EquipmentAbility`. Its `TooltipWriter` is a concrete class with a component type, component consumer, tooltip context and stack constructor. The reader invokes the native tooltip producer on default item definitions and bounds the emitted component traversal.

## Installed Artifacts replacement

ATM10 also installs `reliquified_artifacts-1.21.1-1.0.8.jar`. Its metadata pins Artifacts 13.2.3. `ModItemsMixin.redirectWearableItem` replaces 45 wearable item factories; related redirects replace Umbrella, Everlasting Beef and Eternal Steak. The replacement `RARelicItem` and `RAWearableRelicItem` classes extend Relics item classes and supply `constructDefaultRelicTemplate()`. Consequently, their current ability documentation must use the Relics source contract under the original `artifacts:` item IDs. The base Artifacts data components are absent by design. This was confirmed against installed bytecode after live discovery visited all 49 Artifacts items without finding a native ability component.
