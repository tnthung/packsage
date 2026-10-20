# Guide and manual relationship extraction validation

Date: 2026-10-20.

Scope: six structured relationship capabilities added to the existing guide/manual document sources. This report separates source inspection, automated checks, and installed acceptance. All six features remain pending implementation and installed acceptance.

## Installed artifacts and source inspection

Installed JARs in `C:/Users/hung1/curseforge/minecraft/Instances/Packsage/mods`:

| Source | Installed version | SHA-256 |
|---|---|---|
| GuideME | 21.1.19 | `2CE254EDB931C3C3CF81D69ABAF8F4CE5B871BF85E64984F366BF044173BD66A` |
| Immersive Engineering | 12.4.2-194 | `45942985A4A4AEBF265B8E22A0C54A96208637471F36F2532FF5D4911322DEBC` |
| Modonomicon | 1.120.7 | `1166EB113230BBC69A3E9464262195A336B29401EF03BFD47BFE86FD558578DA` |

The same GuideME and Immersive Engineering artifacts are present in `D:/Project/packsage/.tools/all-adapter-fixture`; their hashes match the installed copies. The fixture also contains Modonomicon 1.120.4 (SHA-256 `35D055E524C3491126EBE874984E8D77CB18460DE4479D03A36D9F00404CC726`); the current installed 1.120.7 artifact is not in that fixture.

Current reader source inspection establishes these access shapes, not native relationships or range-wide compatibility:

| Source | Existing native access shape | Relationship work introduced here |
|---|---|---|
| Modonomicon | Existing reader uses current book/category/entry/page structures, localized text holders, and current unlock/visibility checks. Relationship source members: `BookContentEntry.getPages()`, `BookSpotlightPage.getItem()`, `BookRecipePage.getRecipeType()/getRecipeId1()/getRecipeId2()`, `BookEntry.getParents()/getCondition()`, parent `getEntryId()`, logical condition `children()`, built-in condition leaf payloads, and current display state. Floor members are checked at tagged 1.98.1; both 1.120.4 and 1.120.7 artifacts were checked for later forms. | Entry-to-item links, embedded recipe references, and prerequisite relationships remain pending implementation and automated/installed acceptance. |
| GuideME | Current reader calls `guideme.Guides.getAll()`, accesses each guide's private `pages` and `developmentPages` fields, and invokes `getLanguage()` on parsed-page values. Relationship source members: `ParsedGuidePage.getFrontmatter().additionalProperties().get("item_ids")`; parsed MDX elements expose `name()`, `attributes()` and `children()`. | Page-to-item associations and `Recipe`/`RecipeFor`/`RecipesFor` relationships remain pending implementation and automated/installed acceptance. |
| Immersive Engineering | Current reader calls `ManualHelper.getManual()`, reads `contentsByName`, invokes `showNodeInList` and `getTreeNode()`/`getTitle()`/`getLocation()`, then reads localized manual text resources. Relationship source members: initialized manual `ManualEntry.getSpecialData()`, `SpecialElementData.getElement()`, crafting element `recipeRows`, IE crafting element `stacks`, mixer `fluids`, and `ManualRecipeRef` recipe-name/result/layout selectors with layout cell `displayList()`/`x()`/`y()`/`amount()`. Current `1.21.1` primary source and current `12.4.2-194` artifact were checked; the exact `12.0.0-182` artifact shape remains unverified. | Manual recipe references remain pending implementation and automated/installed acceptance. |

These shapes were inspected in the current PackSage readers and current installed artifacts. They do not establish that all requested relationship fields are present, complete, or exposed in the same way at the admitted version floors.

## Compatibility evidence

Modonomicon requires three reader ranges within the existing `[1.98.1,2.0.0)` family because two APIs change at different tagged releases:

| Reader variant | Supported source range | Spotlight getter | Entry display state |
|---|---|---|---|
| Early | `[1.98.1,1.101.0)` | `BookSpotlightPage.getItem()` returns `Ingredient` | No `BookEntry.getEntryDisplayState(Player)`; reproduce the old UI decision using current unlock manager and entry parent flags. |
| Middle | `[1.101.0,1.102.0)` | Returns `Ingredient` | Public `getEntryDisplayState(Player)` is present. |
| Current | `[1.102.0,2.0.0)` | Returns `Either<ItemStack, Ingredient>` | Public `getEntryDisplayState(Player)` is present. |

The exact release-tag sources establish both boundaries: `getEntryDisplayState` is absent at 1.100.0 and present at 1.101.0; spotlight `getItem()` returns `Ingredient` at 1.101.0 and `Either<ItemStack, Ingredient>` at 1.102.0. Installed Modonomicon 1.120.4 and 1.120.7 confirm the current form. The floor sources also expose `BookEntry.getCondition()/getParents()`, parent `getEntryId()`, `BookContentEntry.getPages()`, public `children()` on AND/OR conditions, and the supported known leaf payloads (`advancementId`, `entryId`, `modId`, `categoryId`) as protected fields. The custom category-has-entries leaf was found in floor source but not in 1.120.7; decode only when that exact runtime class exists.

For `[1.98.1,1.101.0)`, the tagged `BookCategoryScreen.getEntryDisplayState(BookEntry)` decision preserves this order:

1. Query `BookUnlockStateManager.isUnlockedFor(player, entry)`.
2. Iterate `entry.getParents()`, query each parent's current unlock state, and track whether any and all parents are unlocked.
3. If `showWhenAnyParentUnlocked()` is true, hide the entry when no parent is unlocked; otherwise hide it when any parent is locked.
4. If graph-visible but the entry itself is locked, return hidden when `hideWhileLocked()` is true and locked otherwise; if entry-unlocked, return unlocked.

This is not equivalent to showing every old entry or to interpreting parent edges as the entry's condition. Continue to enforce category access and page unlock visibility with the unlock manager and `BookContentEntry.getUnlockedPagesFor(Player)`. Use the public method beginning at 1.101.0, whose logic preserves these semantics. Hidden entries and locked page bodies remain inaccessible; a visible locked entry may expose supported prerequisite declarations. Exact tagged sources and the separate evidence record are in [Modonomicon API cutovers and legacy visibility evidence](../../.superpowers/sdd/2026-10-20-guide-manual-relationships/modonomicon-cutovers-report.md).

GuideME's `[21.1.0,21.2.0)` floor source was inspected at the official `v21.1.0` tag: `ParsedGuidePage.getFrontmatter()` exists and `Frontmatter.additionalProperties()` exposes the parsed properties map needed for `item_ids`. This verifies that association surface at the floor; it does not prove unchanged internal page maps or MDX element structure across every patch. The current 21.1.19 artifact/member shapes remain separately recorded above. The official [GuideME releases](https://github.com/AppliedEnergistics/GuideME/releases) identify 21.1.0 as the Minecraft 1.21.1 API line floor; no exact-version pin is warranted by this evidence.

Immersive Engineering's selected family is `[12.0.0-182,13.0.0)`; the official [1.21.1 changelog](https://github.com/BluSunrize/ImmersiveEngineering/blob/1.21.1/changelog.md) identifies 12.0.0-182 as the first release for this Minecraft line. Current 1.21.1 primary source and installed 12.4.2-194 signatures establish the manual declaration shapes above, but this checkpoint did not obtain or inspect a 12.0.0-182 artifact/source snapshot. Therefore the exact floor member compatibility remains an explicit evidence limit. This is not evidence of an API break and does not justify pinning to 12.4.2-194; retain the selected range pending focused floor verification and tests.

## Feature status

| Feature | Source inspection | Automated result | Installed acceptance |
|---|---|---|---|
| Modonomicon entry-to-item relationships | Pending | Pending | Pending |
| Modonomicon recipe references | Pending | Pending | Pending |
| Modonomicon prerequisites | Pending | Pending | Pending |
| GuideME page-to-item relationships | Pending | Pending | Pending |
| GuideME recipe-tag relationships | Pending | Pending | Pending |
| Immersive Engineering manual recipe references | Pending | Pending | Pending |

## Automated checks

Baseline only, before relationship implementation: Java 21 `gradlew.bat test --console=plain` succeeded with 644 tests, 0 failures, 0 errors, and 4 skipped. This does not validate any relationship feature.

## Installed acceptance

No relationship extraction acceptance has been performed for these six features. Existing text search and fresh evidence-read checks for the guide/manual adapters do not establish structured relationship extraction. New guide navigation is outside this change's scope. Record each feature's observed source, returned relationship and completeness here only after an installed check.
