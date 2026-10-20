# Guide and manual relationship extraction validation

Date: 2026-10-20.

Scope: six structured relationship capabilities added to the existing guide/manual document sources. This report separates source inspection, automated checks, and installed acceptance. Implementation and automated checks are recorded below; all six installed acceptance results remain pending.

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

Reader access shapes are supported by the current source and the compatibility evidence below; they do not establish installed extraction acceptance:

| Source | Existing native access shape | Relationship work introduced here |
|---|---|---|
| Modonomicon | The reader uses current book/category/entry/page structures, localized text holders, and current unlock/visibility checks. It now extracts spotlight associations, both declared recipe slots, parent edges, native condition trees, and visible/unlocked state through the listed source members. Floor members are checked at tagged 1.98.1; 1.120.4 and 1.120.7 artifacts were checked for current forms. | Implemented with bounded source-positioned relationships; custom page types and unknown condition leaves remain opaque. Automated checks pass; installed acceptance is pending. |
| GuideME | The reader uses parsed frontmatter `item_ids`, element names from `MdxJsxElementFields.name()`, and literal attribute values. The concrete `MdxJsxAttribute` exposes its attribute name as a public `String name` field; `MdxJsxExpressionAttribute` is a nameless expression/spread node and is not executed. It preserves page associations and distinct `Recipe`, `RecipeFor` and `RecipesFor` declarations. `MdxJsxFlowElement` and `MdxJsxTextElement` implement the element interface at floor and current. | Implemented from currently selected parsed pages; unsupported AST forms remain bounded/opaque. Automated checks pass; installed acceptance is pending. |
| Immersive Engineering | The reader checks `ManualInstance.initialized`, then reads loaded entry special data and native `ManualRecipeRef` name/result/layout selectors from crafting and IE crafting elements, plus mixer fluid selectors. Native `ManualElementMixer` extends `ManualElementIECrafting`, so the reader dispatches mixers before the superclass branch to reach `fluids`; it preserves bounded `PositionedItemStack` alternatives and components. | Implemented from the already-loaded manual; it does not open or reload it. Automated provider/common-read checks pass; installed acceptance is pending. |

These shapes were inspected in the current PackSage readers and current installed artifacts, plus the exact GuideME and IE admitted-floor release artifacts described below. Source inspection supports the implementation description; it does not establish installed extraction or answer-quality acceptance.

## Compatibility evidence

Modonomicon requires three reader ranges within the existing `[1.98.1,2.0.0)` family because two APIs change at different tagged releases:

| Reader variant | Supported source range | Spotlight getter | Entry display state |
|---|---|---|---|
| Early | `[1.98.1,1.101.0)` | `BookSpotlightPage.getItem()` returns `Ingredient` | No `BookEntry.getEntryDisplayState(Player)`; reproduce the old UI decision using current unlock manager and entry parent flags. |
| Middle | `[1.101.0,1.102.0)` | Returns `Ingredient` | Public `getEntryDisplayState(Player)` is present. |
| Current | `[1.102.0,2.0.0)` | Returns `Either<ItemStack, Ingredient>` | Public `getEntryDisplayState(Player)` is present. |

The exact release-tag sources establish both boundaries: `getEntryDisplayState` is absent at 1.100.0 and present at 1.101.0; spotlight `getItem()` returns `Ingredient` at 1.101.0 and `Either<ItemStack, Ingredient>` at 1.102.0. Installed Modonomicon 1.120.4 and 1.120.7 confirm the current form. The floor sources also expose `BookEntry.getCondition()/getParents()`, parent `getEntryId()`, `BookContentEntry.getPages()`, public `children()` on AND/OR conditions, and the supported known leaf payloads (`advancementId`, `entryId`, `modId`, `categoryId`) as protected fields. The custom category-has-entries leaf was found in floor source but not in 1.120.7; decode only when that exact runtime class exists. One logical `modonomicon` provider dispatches to early, intermediate, or current reader forms at these tagged cutovers.

For `[1.98.1,1.101.0)`, the tagged [`BookCategoryScreen.java` at 1.98.1](https://github.com/klikli-dev/modonomicon/blob/release/v1.21.1-1.98.1/common/src/main/java/com/klikli_dev/modonomicon/client/gui/book/BookCategoryScreen.java), method `getEntryDisplayState(BookEntry)`, decides visibility in this order:

1. Query `BookUnlockStateManager.isUnlockedFor(player, entry)`.
2. Iterate `entry.getParents()`, query each parent's current unlock state, and track whether any and all parents are unlocked.
3. If `showWhenAnyParentUnlocked()` is true, hide the entry when no parent is unlocked; otherwise hide it when any parent is locked.
4. If graph-visible but the entry itself is locked, return hidden when `hideWhileLocked()` is true and locked otherwise; if entry-unlocked, return unlocked.

This is not equivalent to showing every old entry or to interpreting parent edges as the entry's condition. Continue to enforce category access and page unlock visibility with the unlock manager and `BookContentEntry.getUnlockedPagesFor(Player)`. At 1.101.0, the public [`BookEntry.java` implementation](https://github.com/klikli-dev/modonomicon/blob/release/v1.21.1-1.101.0/common/src/main/java/com/klikli_dev/modonomicon/book/entries/BookEntry.java) first returns `UNLOCKED` when the entry's own condition is satisfied, before checking parent visibility. The legacy UI checks parent visibility before its final locked/unlocked result, so those rules are not identical for an already-unlocked entry with locked parents. The implementation keeps the native public helper for releases that provide it and reproduces the tagged legacy UI ordering below 1.101.0. Hidden entries and locked page bodies remain inaccessible; a visible locked entry may expose supported prerequisite declarations. The compatibility report links the exact release-tag sources directly.

GuideME's `[21.1.0,21.2.0)` floor was inspected in the author's exact 21.1.0 release JAR with JDK 21 `javap -p`; official tag source confirms the same declarations. `MdxJsxElementFields` has `name(): String`, `attributes(): List<MdxJsxAttributeNode>` and `children(): List<? extends MdAstAnyContent>`. Both flow and text elements implement it. Separately, the concrete `MdxJsxAttribute` declares a public `String name` field, not a `name()` method, and distinguishes literal string values with `hasStringValue()/getStringValue()` from expression values with `hasExpressionValue()/getExpressionValue()`. `MdxJsxExpressionAttribute` is an attribute node extending `MdAstLiteral` and has no name field or method; expression/spread values are therefore left opaque. `ParsedGuidePage.getFrontmatter()` and `Frontmatter.additionalProperties()` supply `item_ids`. These members match installed 21.1.19. See the official tagged [MDX interface](https://github.com/AppliedEnergistics/GuideME/blob/v21.1.0/src/main/java/guideme/libs/mdast/mdx/model/MdxJsxElementFields.java), [literal attributes](https://github.com/AppliedEnergistics/GuideME/blob/v21.1.0/src/main/java/guideme/libs/mdast/mdx/model/MdxJsxAttribute.java), [expression attribute](https://github.com/AppliedEnergistics/GuideME/blob/v21.1.0/src/main/java/guideme/libs/mdast/mdx/model/MdxJsxExpressionAttribute.java), and [GuideME floor release metadata](https://api.modrinth.com/v2/version/MfzKuNeT).

There is a built-in tag capability change, not an MDX API break. `RecipeCompiler.getTagNames()` returns `Recipe` and `RecipeFor` at 21.1.0 and 21.1.5; `RecipesFor` is first present at 21.1.6 and remains in 21.1.19. Its semantics differ: `Recipe` consumes a recipe ID, `RecipeFor` selects the first renderable recipe for an item, and `RecipesFor` selects every renderable recipe for an item. The plan/spec follows the native tag capability set instead of claiming `RecipesFor` built-in semantics for 21.1.0–21.1.5. The checked public zero-argument `RecipeCompiler` and pure immutable-set `getTagNames()` are present at floor and current; no API break was found in the observed 21.1.x range, though no separate binary-stability promise for that concrete class was located. The official [21.1.0](https://github.com/AppliedEnergistics/GuideME/blob/v21.1.0/src/main/java/guideme/compiler/tags/RecipeCompiler.java), [21.1.5](https://github.com/AppliedEnergistics/GuideME/blob/v21.1.5/src/main/java/guideme/compiler/tags/RecipeCompiler.java), [21.1.6](https://github.com/AppliedEnergistics/GuideME/blob/v21.1.6/src/main/java/guideme/compiler/tags/RecipeCompiler.java) and [21.1.19](https://github.com/AppliedEnergistics/GuideME/blob/v21.1.19/src/main/java/guideme/compiler/tags/RecipeCompiler.java) tag sources show the transition. The official [GuideME releases](https://github.com/AppliedEnergistics/GuideME/releases) identify the 1.21.1 line; no exact patch pin is justified by this evidence.

Immersive Engineering's selected family is `[12.0.0-182,13.0.0)`; the official [1.21.1 changelog](https://github.com/BluSunrize/ImmersiveEngineering/blob/1.21.1/changelog.md) identifies 12.0.0-182 as the first release for this Minecraft line. JDK 21 `javap -p` on the exact [author 12.0.0-182 release JAR](https://api.modrinth.com/v2/version/UoruPMQV) and matching [12.0.0-182.77 Maven binary](https://maven.blamejared.com/blusunrize/immersiveengineering/ImmersiveEngineering/1.21.1-12.0.0-182.77/ImmersiveEngineering-1.21.1-12.0.0-182.77.jar) confirms the contracted members: private `ManualInstance.initialized`; `ManualEntry.getSpecialData()`; `SpecialElementData.getElement()/getAnchor()/getOffset()`; crafting `recipeRows`; IE crafting `stacks`; mixer `fluids`; recipe-name/result/layout APIs; and positioned-cell accessors. `ManualElementMixer` extends `ManualElementIECrafting`; the mixer branch must precede the superclass branch to reach its own `fluids` selector rather than the inherited crafting data. Matching [Maven source](https://maven.blamejared.com/blusunrize/immersiveengineering/ImmersiveEngineering/1.21.1-12.0.0-182.77/ImmersiveEngineering-1.21.1-12.0.0-182.77-sources.jar) confirms initialization order: manual reload initializes entries, indexes recipes, then sets `initialized` on success; `getSpecialData()` itself does not initialize data and `getElement()` invokes its stored supplier. Installed 12.4.2-194 has the same listed member shapes and mixer inheritance. No member compatibility break is observed in this floor/current comparison, and no narrower exact-version range is indicated. These signatures do not establish runtime extraction acceptance.

## Feature status

| Feature | Source inspection | Automated result | Installed acceptance |
|---|---|---|---|
| Modonomicon entry-to-item relationships | Implemented; source declarations and visibility gates inspected | Pass: focused reader/provider coverage and full build | Pending |
| Modonomicon recipe references | Implemented; both native slots retain declared identity/type | Pass: focused reader coverage and full build | Pending |
| Modonomicon prerequisites | Implemented; graph parents, condition trees and current state remain distinct | Pass: focused reader coverage and full build | Pending |
| GuideME page-to-item relationships | Implemented from active parsed-page frontmatter | Pass: focused reader/provider coverage and full build | Pending |
| GuideME recipe-tag relationships | Implemented with separate tag semantics | Pass: focused reader coverage and full build | Pending |
| Immersive Engineering manual recipe references | Implemented from initialized native declarations | Pass: provider search and fresh common `read_evidence` regression, plus full build | Pending |

## Automated checks

Final-review regressions cover GuideME expression/spread preservation with literal relationships and text, IE mixer-fluid dispatch through the native subclass hierarchy, group-local `UNAVAILABLE` evidence retention, valid empty-layout continuation and stale-target rejection, bounded long-amount progress, and incomplete terminal coverage for unknown components. The byte-budget cases also verify that an exhausted budget stops before reading an unread native layout selector and that accepted candidates retain enough budget to emit a progressing fragment. The positive-insufficient-budget provider regression exercises both nonempty alternatives and empty-display cells, requires an external continuation when positive shared bytes cannot admit the next candidate, then follows fresh-budget cursors and checks exact-once retention of every native alternative/cell.

At revision `802ac85`, the focused seven-class Java 21 run used `.\gradlew.bat test --tests dev.packsage.knowledge.guide.GuideDocumentProviderTest --tests dev.packsage.knowledge.guide.ModonomiconGuideReaderTest --tests dev.packsage.knowledge.guide.GuideMeGuideReaderTest --tests dev.packsage.knowledge.guide.ImmersiveEngineeringGuideReaderTest --tests dev.packsage.client.ClientAssistantTest --tests dev.packsage.knowledge.ToolboxTest --tests dev.packsage.model.PackSageToolsTest --console=plain`: BUILD SUCCESSFUL in 16 seconds, 127 tests, 0 failures, 0 errors, 0 skipped. The suite counts are GuideME 17, IE 29, provider 30, Modonomicon 17, client 13, Toolbox 5, and tools 16. The complete `.\gradlew.bat test build --console=plain` run at the same revision was BUILD SUCCESSFUL in 24 seconds: 708 tests, 0 failures, 0 errors, 4 skipped. The candidate `build/libs/packsage-0.1.0.jar` is 5,070,584 bytes with SHA-256 `A2681767E20A98A5259C07184882AF3747BD0D05C867FC2A81AD06DB1AC0E47E`. These are automated test/build results, not installed acceptance; the historical 644-test baseline predates relationship implementation and is not final validation.

## Installed acceptance

No relationship extraction acceptance has been performed for these six features. Existing text search and fresh evidence-read checks for the guide/manual adapters do not establish structured relationship extraction. New guide navigation is outside this change's scope. All six installed checks remain pending; record each observed source, returned relationship and completeness here only after a live check. A passing extraction read also does not establish that the assistant gave a correct ordinary-language explanation: inspect actual tool calls, payloads, returned evidence and reply separately.

Compatibility limit: automated Modonomicon tests use the actual 1.120.4 fixture artifact; the currently installed 1.120.7 was inspected for source/member shape, but was not exercised by those tests. Older supported Modonomicon binaries were source/descriptor inspected rather than runtime-loaded, so that binary-loading gap remains. GuideME 21.1.19 and Immersive Engineering 12.4.2-194 were ABI-inspected, while their automated reader tests use native-shaped source fixtures rather than the actual mod runtime JARs. None of these checks is installed relationship acceptance; all six installed checks remain pending.
