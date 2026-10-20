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

Admitted-floor artifacts inspected from official publisher sources:

| Source | Floor artifact | SHA-256 | Comparison |
|---|---|---|---|
| GuideME | 21.1.0 author release JAR | `FB9B814CBE07C7CB1E8D893E37C848517C4D9B6968EB569E1734D9294D69B6E4` | JDK 21 `javap -p` checked against installed 21.1.19. |
| Immersive Engineering | 12.0.0-182 author release JAR | `867B49C0969B0DC47B4CE50CED07D4F53171F459D9165C339271B88755A221DB` | JDK 21 `javap -p` checked against installed 12.4.2-194. |
| Immersive Engineering | Matching 12.0.0-182.77 Maven source JAR | `B2F1958F2BD1CAE690AFFC5B03D528F31C0CF911A7D717548DC596B81FF84A7D` | Initial 1.21.1 source build; Maven binary SHA-256 `C6A2806DD35C28EF257F6B418DB3550AC53D1B27A6A29D527BF4502C8AB34772`. |

Current reader source inspection establishes these access shapes, not native relationships or range-wide compatibility:

| Source | Existing native access shape | Relationship work introduced here |
|---|---|---|
| Modonomicon | Existing reader uses current book/category/entry/page structures, localized text holders, and current unlock/visibility checks. Relationship source members: `BookContentEntry.getPages()`, `BookSpotlightPage.getItem()`, `BookRecipePage.getRecipeType()/getRecipeId1()/getRecipeId2()`, `BookEntry.getParents()/getCondition()`, parent `getEntryId()`, logical condition `children()`, built-in condition leaf payloads, and current display state. Floor members are checked at tagged 1.98.1; both 1.120.4 and 1.120.7 artifacts were checked for later forms. | Entry-to-item links, embedded recipe references, and prerequisite relationships remain pending implementation and automated/installed acceptance. |
| GuideME | Current reader calls `guideme.Guides.getAll()`, accesses each guide's private `pages` and `developmentPages` fields, and invokes `getLanguage()` on parsed-page values. Relationship source members: `ParsedGuidePage.getFrontmatter().additionalProperties().get("item_ids")`; `MdxJsxElementFields.name()/attributes()/children()`; and `MdxJsxAttribute.hasStringValue()/getStringValue()` versus expression-value accessors. `MdxJsxFlowElement` and `MdxJsxTextElement` implement the interface at floor and current. | Page-to-item associations and `Recipe`/`RecipeFor`/`RecipesFor` relationships remain pending implementation and automated/installed acceptance. |
| Immersive Engineering | Current reader calls `ManualHelper.getManual()`, reads `contentsByName`, invokes `showNodeInList` and `getTreeNode()`/`getTitle()`/`getLocation()`, then reads localized manual text resources. Floor and current relationship members match: private `ManualInstance.initialized`, `ManualEntry.getSpecialData()`, `SpecialElementData.getElement()/getAnchor()/getOffset()`, `ManualElementCrafting.recipeRows`, `ManualElementIECrafting.stacks`, `ManualElementMixer.fluids`, `ManualRecipeRef` name/result/layout selectors, and `PositionedItemStack.displayList()/x()/y()/amount()`. | Manual recipe references remain pending implementation and automated/installed acceptance. |

These shapes were inspected in the current PackSage readers and current installed artifacts, plus the exact GuideME and IE admitted-floor release artifacts described below. They establish the listed source members, not extraction implementation or runtime acceptance.

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

GuideME's `[21.1.0,21.2.0)` floor was inspected in the author's exact 21.1.0 release JAR with JDK 21 `javap -p`; official tag source confirms the same declarations. `MdxJsxElementFields` has `name(): String`, `attributes(): List<MdxJsxAttributeNode>` and `children(): List<? extends MdAstAnyContent>`. Both flow and text elements implement it. `MdxJsxAttribute` distinguishes literal string values with `hasStringValue()/getStringValue()` from expression values with `hasExpressionValue()/getExpressionValue()`. `ParsedGuidePage.getFrontmatter()` and `Frontmatter.additionalProperties()` supply `item_ids`. These members match installed 21.1.19. See the official tagged [MDX interface](https://github.com/AppliedEnergistics/GuideME/blob/v21.1.0/src/main/java/guideme/libs/mdast/mdx/model/MdxJsxElementFields.java), [literal/expression attributes](https://github.com/AppliedEnergistics/GuideME/blob/v21.1.0/src/main/java/guideme/libs/mdast/mdx/model/MdxJsxAttribute.java), and [GuideME floor release metadata](https://api.modrinth.com/v2/version/MfzKuNeT).

There is a built-in tag capability change, not an MDX API break. `RecipeCompiler.getTagNames()` returns `Recipe` and `RecipeFor` at 21.1.0 and 21.1.5; `RecipesFor` is first present at 21.1.6 and remains in 21.1.19. Its semantics differ: `Recipe` consumes a recipe ID, `RecipeFor` selects the first renderable recipe for an item, and `RecipesFor` selects every renderable recipe for an item. The plan/spec follows the native tag capability set instead of claiming `RecipesFor` built-in semantics for 21.1.0–21.1.5. The checked public zero-argument `RecipeCompiler` and pure immutable-set `getTagNames()` are present at floor and current; no API break was found in the observed 21.1.x range, though no separate binary-stability promise for that concrete class was located. The official [21.1.0](https://github.com/AppliedEnergistics/GuideME/blob/v21.1.0/src/main/java/guideme/compiler/tags/RecipeCompiler.java), [21.1.5](https://github.com/AppliedEnergistics/GuideME/blob/v21.1.5/src/main/java/guideme/compiler/tags/RecipeCompiler.java), [21.1.6](https://github.com/AppliedEnergistics/GuideME/blob/v21.1.6/src/main/java/guideme/compiler/tags/RecipeCompiler.java) and [21.1.19](https://github.com/AppliedEnergistics/GuideME/blob/v21.1.19/src/main/java/guideme/compiler/tags/RecipeCompiler.java) tag sources show the transition. The official [GuideME releases](https://github.com/AppliedEnergistics/GuideME/releases) identify the 1.21.1 line; no exact patch pin is justified by this evidence.

Immersive Engineering's selected family is `[12.0.0-182,13.0.0)`; the official [1.21.1 changelog](https://github.com/BluSunrize/ImmersiveEngineering/blob/1.21.1/changelog.md) identifies 12.0.0-182 as the first release for this Minecraft line. JDK 21 `javap -p` on the exact [author 12.0.0-182 release JAR](https://api.modrinth.com/v2/version/UoruPMQV) and matching [12.0.0-182.77 Maven binary](https://maven.blamejared.com/blusunrize/immersiveengineering/ImmersiveEngineering/1.21.1-12.0.0-182.77/ImmersiveEngineering-1.21.1-12.0.0-182.77.jar) confirms the contracted members: private `ManualInstance.initialized`; `ManualEntry.getSpecialData()`; `SpecialElementData.getElement()/getAnchor()/getOffset()`; crafting `recipeRows`; IE crafting `stacks`; mixer `fluids`; recipe-name/result/layout APIs; and positioned-cell accessors. Matching [Maven source](https://maven.blamejared.com/blusunrize/immersiveengineering/ImmersiveEngineering/1.21.1-12.0.0-182.77/ImmersiveEngineering-1.21.1-12.0.0-182.77-sources.jar) confirms initialization order: manual reload initializes entries, indexes recipes, then sets `initialized` on success; `getSpecialData()` itself does not initialize data and `getElement()` invokes its stored supplier. Installed 12.4.2-194 has the same listed member shapes. No member compatibility break is observed in this floor/current comparison, and no narrower exact-version range is indicated. These signatures do not establish runtime extraction acceptance.

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
