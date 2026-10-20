# Guide and Manual Relationships Implementation Plan

> **For agentic workers:** Use `superpowers:subagent-driven-development` or `superpowers:executing-plans` to execute this plan task by task. Follow repository model routing, file ownership and Git instructions. This document is a plan for review; writing it does not start implementation.

**Goal:** Complete all six unchecked capabilities in the roadmap's guide/manual relationships section by extending the existing Modonomicon, GuideME and Immersive Engineering adapters.

**Architecture:** Add bounded structured relationships to the existing guide fragments and evidence payloads. Each reader extracts its own native declarations, while the shared provider transports them, matches their identifiers and preserves current search, continuation and fresh-read contracts. Keep the three existing logical adapter entries and callable tools.

**Tech Stack:** Java 21, Minecraft 1.21.1, NeoForge, the existing optional guide integrations, JUnit 5 and Gradle.

**Spec:** The [behavior-driven tool contract](../specs/2026-10-14-behavior-driven-tools.md), the [six roadmap requirements](../roadmap/2026-10-13-todo.md#guides-and-manuals-1) and the additive design below. The [existing guide plan](2026-10-15-guide-manual-adapters.md) covers the already delivered text readers.

## Required coverage

These are extensions to working adapters, not new adapter registrations. Every item must receive implementation, automated regression coverage and a recorded installed acceptance result.

- [ ] Modonomicon entry-to-item relationships: spotlight items or ingredients associated with their containing entry, with the relationship's source page identified. Recipe-derived outputs remain separately labeled.
- [ ] GuideME page-to-item mappings: the selected parsed page's explicit `item_ids` associations.
- [ ] Modonomicon embedded recipes: both declared recipe slots on native recipe pages, including recipe identity and type.
- [ ] GuideME recipe tags: `Recipe`, `RecipeFor` and `RecipesFor`, retaining their different meanings.
- [ ] Immersive Engineering manual recipes: native recipe-ID, output-selector and fixed-layout declarations, plus built-in fluid selectors where applicable.
- [ ] Modonomicon prerequisites: graph-parent relationships, category/entry/page condition definitions and current display/unlock state, without treating graph edges as the condition evaluator.

Custom page implementations and custom condition types remain explicitly opaque when their semantics cannot be decoded. A built-in case cannot be skipped under that allowance. Arbitrary custom-code interpretation, new guide navigation, structure readers, recipe execution and planner changes are outside this plan.

## Source contracts established during planning

Installed signatures were inspected from `C:/Users/hung1/curseforge/minecraft/Instances/Packsage/mods/`: Modonomicon `1.120.7`, GuideME `21.1.19` and Immersive Engineering `12.4.2-194`. Retained Modonomicon `1.120.4` was also compared for the new page, parent and condition members. These checks establish source shape, not installed feature acceptance or compatibility with every admitted release.

### Modonomicon

- `BookContentEntry.getPages()` supplies the live pages. `BookSpotlightPage.getItem()` is `Either<ItemStack, Ingredient>`. The inspected entry/book model has no canonical entry-to-item index; do not substitute decorative entry icons for associations. Preserve spotlight stack, tag or ingredient alternatives with their actual role.
- `BookRecipePage` exposes `getRecipeType()`, `getRecipeId1()` and `getRecipeId2()`. Handle its native subclasses through the common recipe-page contract, including both slots. Recipe IDs are documentation references even if their currently loaded recipe is absent.
- `BookEntry.getParents()` exposes graph edges through `BookEntryParent.getEntryId()`. `getCondition()` supplies actual unlock conditions. AND/OR nodes expose `children()`; known leaf payloads require narrow, reader-owned access to protected fields: `advancementId`, `entryId`, `modId` or `categoryId`. The inspected built-ins also include true, false and none. Preserve each native type; no built-in NOT node was found.
- `BookEntry.getEntryDisplayState(player)` distinguishes `UNLOCKED`, `LOCKED` and `HIDDEN`. `showWhenAnyParentUnlocked()` controls graph visibility, not an OR interpretation of the entry condition. Retain category access and page unlock checks.

There are two confirmed compatibility breaks inside the current `[1.98.1,2.0.0)` range: the earliest source's spotlight getter returns `Ingredient`, while inspected `1.120.4`/`1.120.7` return `Either<ItemStack, Ingredient>`; the earliest source also lacks `getEntryDisplayState`. The transition releases and older native UI visibility path must be established in Task 1 before choosing implementation ranges. Do not call newer methods across the entire current range, silently hide all older locked entries, or narrow support to the two inspected patches.

Primary API references: [spotlight page](https://github.com/klikli-dev/modonomicon/blob/release/v1.21.1-1.98.1/common/src/main/java/com/klikli_dev/modonomicon/book/page/BookSpotlightPage.java), [recipe page](https://github.com/klikli-dev/modonomicon/blob/release/v1.21.1-1.98.1/common/src/main/java/com/klikli_dev/modonomicon/book/page/BookRecipePage.java), [entry](https://github.com/klikli-dev/modonomicon/blob/release/v1.21.1-1.98.1/common/src/main/java/com/klikli_dev/modonomicon/book/entries/BookEntry.java), [condition](https://github.com/klikli-dev/modonomicon/blob/release/v1.21.1-1.98.1/common/src/main/java/com/klikli_dev/modonomicon/book/conditions/BookCondition.java). Installed bytecode is the authority where these older sources differ.

### GuideME

- `ParsedGuidePage.getFrontmatter().additionalProperties().get("item_ids")` contains the page associations. Resolve bare IDs relative to the page namespace and validate current item membership, following the native `ItemIndex` semantics. Read each current page's declarations rather than the reverse index, which selects only one primary page per item.
- MDX flow/text elements implement `MdxJsxElementFields`; inspect `name()`, `attributes()` and their literal values while continuing through `children()`. `Recipe` names a recipe ID. `RecipeFor` and `RecipesFor` name an output item and select one or multiple matching recipes respectively. `fallbackText` is display text, not an identity. Do not execute expression-valued attributes.
- Retain the current base/development-page override selection and active localized parsed page. Existing test stubs need frontmatter and the narrow MDX surface added.

Exact-version sources: [ItemIndex](https://github.com/AppliedEnergistics/GuideME/blob/v21.1.19/src/main/java/guideme/indices/ItemIndex.java), [ParsedGuidePage](https://github.com/AppliedEnergistics/GuideME/blob/v21.1.19/src/main/java/guideme/compiler/ParsedGuidePage.java), [RecipeCompiler](https://github.com/AppliedEnergistics/GuideME/blob/v21.1.19/src/main/java/guideme/compiler/tags/RecipeCompiler.java), [attribute decoding](https://github.com/AppliedEnergistics/GuideME/blob/v21.1.19/src/main/java/guideme/compiler/tags/MdxAttrs.java).

### Immersive Engineering

- After normal manual initialization, `ManualEntry.getSpecialData()` exposes anchor/offset declarations. Each `SpecialElementData.getElement()` returns its memoized element. Native initialization indexes these elements before setting `ManualInstance.initialized`; verify that flag before accessing them so a tool does not initiate recipe scans through a lazy constructor. Incompatible or uninitialized access is reported, not repaired by opening/reloading the manual.
- Read `ManualElementCrafting.recipeRows` and `ManualElementIECrafting.stacks` through narrowly scoped reflection. The latter covers blueprint and bottling elements. `ManualElementMixer.fluids` supplies fluid selectors. Use declaring-class fields rather than assuming fields are declared on the concrete subclass.
- `ManualRecipeRef` distinguishes `isRecipeName()/getRecipeName()`, `isResult()/getResult()` and `isLayout()/getLayout()`. Fixed-layout cells expose `displayList()`, `x()`, `y()` and `amount()`. Keep these forms separate; an output selector or display layout does not establish a unique recipe ID or executable ingredient list.

Primary references: [manual initialization](https://github.com/BluSunrize/ImmersiveEngineering/blob/1.21.1/src/manual/java/blusunrize/lib/manual/ManualInstance.java), [entry special data](https://github.com/BluSunrize/ImmersiveEngineering/blob/1.21.1/src/manual/java/blusunrize/lib/manual/ManualEntry.java), [recipe reference forms](https://github.com/BluSunrize/ImmersiveEngineering/blob/1.21.1/src/manual/java/blusunrize/lib/manual/utils/ManualRecipeRef.java), [crafting elements](https://github.com/BluSunrize/ImmersiveEngineering/blob/1.21.1/src/manual/java/blusunrize/lib/manual/ManualElementCrafting.java). Installed signatures and initialization bytecode were checked; the branch URLs are supporting source explanations.

## Additive evidence and search design

1. Extend `GuideDocumentReader.Fragment` with a final `Evidence.JsonObject relationships` component. Retain the existing six-argument constructor, delegating to an empty object, so other guide readers keep their current behavior. Keep `Candidate` and the existing `readFragment`/`resolve` method signatures.
2. `GuideDocumentProvider.documentRecord` includes nonempty relationships under payload key `relationships`, alongside the existing `id`, `title`, `text`, `fragment`, `complete` and `provenance`. Reader data cannot overwrite those fields. Preserve document handles, source authority and actual loaded versions.
3. Relationship groups are `items`, `recipes` and `prerequisites`. Each present group contains `coverage` using existing `Evidence.Coverage` names and a `values` array. A complete empty group means the whole applicable group was inspected and empty. An absent group means it was not supplied in this fragment; it cannot establish absence. A continued group remains partial until its terminal range. Partial/opaque groups retain useful decoded values and a bounded explanation.
4. Item values carry `kind` (`associated`, `spotlight` or `recipe_output`), `sourcePosition` and the native `itemId`, `tagId` or bounded alternatives. Recipe values carry `kind` (`recipe`, `output_item`, `fluid` or `layout`), `sourcePosition` and the applicable `recipeId`, `recipeType`, `itemId`, `fluidId`, `components` or layout cells. GuideME also preserves its exact `tag` name. Missing identities remain missing rather than synthesized.
5. Prerequisite values distinguish `parent`, `condition` and `state`. Conditions retain native `type`, `scope` (`category`, `entry` or `page`), a stable tree `path`, child count for logical nodes and the applicable target ID for known leaves. Tree paths allow fragments to preserve AND/OR topology without flattening an unfinished subtree into a complete rule. State values report native display/unlock state; an individual condition's satisfaction is supplied only where authoritative current state is safely available. Unknown/custom leaves retain their type and opaque coverage.
6. Reuse `search_document(query, filter?, cursor?, limit?)` and `read_evidence(handles)`. Literal matching covers existing title/body text plus decoded identity fields (`itemId`, `tagId`, `recipeId`, `fluidId`, `entryId`, `advancementId`, `categoryId`, `modId`), including bounded alternatives. Do not search serialized JSON keys, coverage labels or invented item associations. Do not add new filters or tools.
7. Readers interleave bounded relationship fragments with their existing text traversal. The initial fragment contains the first relationship chunk and first available text fragment when both fit; otherwise prioritize the relationship chunk and continue text next. A relationship-only page is a valid result. Continue remaining declarations using source positions, not retained source objects or prior results. `read_evidence` rereads the first bounded current fragment, marks omitted remaining ranges partial and directs continuation through document search as today. A terminal text fragment must not erase an earlier opaque or unread relationship range; continuation may carry conservative coverage flags as metadata.
8. Preserve text limits and overlap behavior. Limit relationship output to 16 records, counting nested alternatives and condition nodes, and 8 KiB per fragment; limit nested paths to depth 64. All extraction also shares the existing `DocumentBudget` of 256 documents, 2,048 values and 96 KiB. Charge traversal and copying before performing them. Continue at the next unread relationship/alternative/node when possible; report an indivisible unsupported value as partial/opaque rather than repeatedly returning the same cursor.
9. Components that distinguish a native selector must survive, notably named potion contents in IE bottling examples. Project bounded supported components using the existing native/brewing conventions; undecoded components remain explicit partial data. Do not serialize arbitrary custom components or expand entire ingredient/tag/recipe collections before checking budgets.

## Global constraints

- No formatting tools. Follow existing style, inline trivial conversions and place substantial walkers/projectors at their smallest usable scope. No general adapter framework refactor.
- Use current loaded sources on the client thread. No document cache, compiled guide snapshot, retained condition/page object, automatic screen opening or manual reload.
- Keep current invalid-reference, stale-scope and cursor validation. Revalidate visibility, current page membership, locale and provider identity on every search page and evidence read.
- Preserve the declared API families from the [compatibility report](../validation/2026-10-19-guide-adapter-compatibility.md). Compare newly used members at the admitted floor and current representative artifacts. Actual API differences require separate nonoverlapping implementations under the same logical adapter entry; lack of testing alone does not justify exact-version pins.
- Implementation commits follow repository instructions and `$commit-message`, with unrelated Git state preserved. Commit each written execution step before awaiting its test result; a correction is a separate step. This planning pass does not stage or commit files.
- The coordinator runs automated checks. The player may perform manual JAR copying and live Minecraft actions; do not assign Gradle or Java commands to the player.
- Run the commands below from the repository root with Java 21 selected. The inspected portable JDK is `.tools/jdk/jdk-21.0.12.1`; system Java must not be assumed compatible.

## Review focus

- A visible locked Modonomicon entry must expose supported prerequisite metadata while hidden entries and locked page bodies remain inaccessible. Test in Task 3.
- Parent edges, actual AND/OR conditions and current unlock state must remain separate, including custom opaque leaves. Test in Task 3.
- Recipe IDs, output selectors, fluid selectors and fixed layouts must retain distinct meaning and component variants. Test in Tasks 3-5.
- Work/output limits must stop extraction itself, including large alternatives and condition trees, while cursors progress across text and relationships. Test in Tasks 2-5.
- Resource/locale changes, GuideME development overrides and evidence rereads must use current declarations. Test in Tasks 2-6.

## Task 1: Record the additive contract and compatibility evidence

**Files:** Modify `docs/specs/2026-10-14-behavior-driven-tools.md`; create `docs/validation/2026-10-20-guide-manual-relationships.md`.

**Consumes:** The source contracts and payload design above. **Produces:** One authoritative description of structured guide evidence, search matching, visibility and completeness; a capability-by-capability evidence record.

- [ ] Add the structured-guide result contract to the existing document-search section, preserving callable signatures and source ownership. Link this plan rather than duplicating execution steps in the spec.
- [ ] Record installed artifact versions/hashes and the exact new members checked. Check a representative admitted floor for each new native surface; record actual differences and select a compatible implementation split only where necessary.
- [ ] Resolve the confirmed Modonomicon spotlight-return and display-state transitions from release/tag evidence and inspect the older native visibility behavior. Record exact nonoverlapping version ranges for the necessary reader variants. If the two transitions differ, retain an intermediate variant. This is a required prerequisite to Task 3, not an optional compatibility follow-up.
- [ ] Record all six features as pending. Separate source inspection, automated results and installed acceptance throughout the report.

## Task 2: Carry and discover structured guide fragments

**Files:** Modify `src/main/java/dev/packsage/knowledge/guide/GuideDocumentReader.java`, `GuideDocumentProvider.java` and `src/test/java/dev/packsage/knowledge/guide/GuideDocumentProviderTest.java`.

**Consumes:** Task 1's contract. **Produces:** `Fragment(String text, String sourcePosition, int overlapChars, String nextPosition, Evidence.Coverage coverage, String detail, Evidence.JsonObject relationships)` plus its compatible six-argument constructor; structured payload transport and literal identity matching.

- [ ] Add failing provider regressions `relationshipOnlyFragmentMatchesDeclaredIdentity`, `freshReadPreservesCurrentRelationships` and `relationshipCoverageIsNotSearchText`: querying `minecraft:diamond` returns the fixture's association despite empty text; querying `COMPLETE` does not match that association; a changed item ID appears on reread. Assert the exact group payload survives and text-only fixtures retain their current payloads.
- [ ] Add failing bounded-continuation regressions: a large relationship collection produces advancing metadata-only cursors with the same document handle; a later current value changes before continuation/read; absent, complete-empty, partial and opaque groups remain distinguishable; oversized or malformed reader output is rejected.
- [ ] Run `.\gradlew.bat test --tests dev.packsage.knowledge.guide.GuideDocumentProviderTest --console=plain` with Java 21 and confirm the new regressions fail for the missing behavior.
- [ ] Implement the additive fragment component, shared payload validation and matching. Keep native decoding and source positions inside each reader. Keep other readers on the compatible constructor.
- [ ] Rerun that focused test class; require zero failures and unchanged text/overlap, cursor and fresh-read behavior.

## Task 3: Complete all three Modonomicon capabilities

**Files:** Modify `src/main/java/dev/packsage/knowledge/guide/ModonomiconGuideReader.java`, `src/main/java/dev/packsage/client/ClientAssistant.java` and `src/test/java/dev/packsage/knowledge/guide/ModonomiconGuideReaderTest.java`. Create `src/main/java/dev/packsage/knowledge/guide/ModonomiconLegacyGuideReader.java`; add `ModonomiconIntermediateGuideReader.java` in that directory only if Task 1 establishes different transition boundaries. Extend `src/test/java/dev/packsage/client/ClientAssistantTest.java` for variant selection.

**Consumes:** Task 1's proven API ranges and Task 2's fragment payload. **Produces:** All three Modonomicon groups through the existing `candidates`, `readFragment` and `resolve` methods, using version-specific native access under one logical provider.

- [ ] Add failing native-page fixtures: spotlight stack and ingredient/tag alternatives, both recipe slots, recipe subclasses, missing referenced recipes and text alongside relationships. Assert exact IDs, source pages and relationship kinds; entry icons must not create associations.
- [ ] Add failing prerequisite fixtures: multiple parent edges, nested AND/OR, known advancement/entry-read/entry-unlocked/mod/category leaves, true/false/none and a custom opaque condition. Assert topology, scoped conditions and parent edges remain distinct. Include depth/output bounds and resumption without changing logical meaning.
- [ ] Add failing access fixtures: unlocked entry, visible locked entry, hidden entry, locked category/page and a parent that must not reveal its title/body. Visible locked entries yield prerequisite metadata and permitted identifying labels only; hidden entries and locked content are not read. Preserve the prior unlocked text behavior.
- [ ] Add API-family regressions for the legacy Ingredient getter, modern Either getter and each native visibility path. Test representative versions and both sides of every researched split boundary; no version selects two variants. Preserve one `modonomicon` entry in status and the adapter list, with its actual loaded version.
- [ ] Run `.\gradlew.bat test --tests dev.packsage.knowledge.guide.ModonomiconGuideReaderTest --console=plain` and confirm meaningful failures.
- [ ] Extend the current traversal with native spotlight/recipe handling and a scoped condition walker. Select exactly one reader variant using Task 1's ranges; share unchanged traversal and isolate changed native API calls, including any newer-only return types. Use `getEntryDisplayState` where available and the verified older native UI visibility policy in legacy variants. Retain category access/page unlock gates and use a whitelist of inspected built-in leaf fields. Custom types must not trigger generic reflective dumping or arbitrary evaluation callbacks.
- [ ] Rerun the reader and shared provider tests. Verify a changed parent/condition/page or unlock state is observed on fresh read, and API-access failures retain text or decoded relationships with explicit partial/opaque coverage.

## Task 4: Complete both GuideME capabilities

**Files:** Modify `src/main/java/dev/packsage/knowledge/guide/GuideMeGuideReader.java`, `src/test/java/dev/packsage/knowledge/guide/GuideMeGuideReaderTest.java` and the relevant existing stubs under `src/test/java/guideme/`. Add only the frontmatter and MDX test types needed to mirror the inspected API.

**Consumes:** Task 2's fragment payload. **Produces:** Page associations and recipe-tag declarations from the currently selected parsed page.

- [ ] Add failing mapping fixtures for multiple `item_ids`, namespace-relative IDs, invalid/non-string/unknown items, an empty complete list, locale changes and a development page overriding base metadata.
- [ ] Add failing AST fixtures for `Recipe`, `RecipeFor` and `RecipesFor`, including inline/flow elements, nested/multiple tags, relative IDs, absent/malformed IDs, expression attributes and `fallbackText`. Assert recipe identities remain distinct from output-item selectors; no implicit scan resolves every output selector.
- [ ] Add a work-bound continuation case across metadata, MDX and text; change frontmatter/AST before fresh read and assert the current values appear. Unsupported nodes/attributes remain partial rather than silently empty.
- [ ] Run `.\gradlew.bat test --tests dev.packsage.knowledge.guide.GuideMeGuideReaderTest --console=plain` and confirm meaningful failures.
- [ ] Extend the current page and AST walkers. Preserve base/development selection, active localization, native namespace rules and optional classloading isolation. Do not compile/render a whole guide or execute MDX expressions.
- [ ] Rerun the reader and shared provider tests; verify a query for an associated item ID finds the page even when that ID is absent from its visible prose.

## Task 5: Complete IE manual recipe references

**Files:** Modify `src/main/java/dev/packsage/knowledge/guide/ImmersiveEngineeringGuideReader.java` and `src/test/java/dev/packsage/knowledge/guide/ImmersiveEngineeringGuideReaderTest.java`; add narrow native-API fixture types under `src/test/java/blusunrize/` as needed for the existing reflective reader.

**Consumes:** Task 2's fragment payload. **Produces:** Bounded recipe declarations with entry/anchor/offset provenance, retaining existing localized manual text.

- [ ] Add failing initialized-manual fixtures for crafting rows, multiple alternatives, blueprint/bottling refs, mixer fluids and all three `ManualRecipeRef` forms. Assert recipe IDs, output selectors and fixed layouts remain distinct; layout alternatives retain explicit coordinates/amounts without inventing input/output roles.
- [ ] Include two same-item potion selectors with different potion components. Assert those remain distinct and undecoded components are partial.
- [ ] Add failing no-side-effect fixtures: initialization is false, an element accessor would trigger lazy work, a field/API is absent and the source reloads before continuation. Require no call to manual open/reload, recipe recalculation, full recipe matching or rendering. Test bounded rows/layout alternatives and an opaque custom element.
- [ ] Run `.\gradlew.bat test --tests dev.packsage.knowledge.guide.ImmersiveEngineeringGuideReaderTest --console=plain` and confirm meaningful failures.
- [ ] Gate live-special reads on the inspected initialized state, then read cached declarations with exact-class field access and public reference accessors. Continue by special/row/alternative/cell position. Preserve text fallback and report unavailable structural access explicitly.
- [ ] Rerun the reader and shared provider tests. Require fresh declaration reads, advancing continuations and no eager native recipe scan.

## Task 6: Verify the complete section and record acceptance

**Files:** Modify `src/main/java/dev/packsage/model/PackSageTools.java`, `src/test/java/dev/packsage/knowledge/ToolboxTest.java`, `src/test/java/dev/packsage/model/PackSageToolsTest.java`, the roadmap and `CHANGELOG.md`; create `docs/testing/2026-10-20-guide-manual-relationships.md`; update Task 1's validation report. Read `src/main/java/dev/packsage/model/GroundingInstructions.java` and the [coherent search policy](../specs/2026-10-19-coherent-search-policy.md) together with the tool descriptions.

**Consumes:** All three completed reader extensions. **Produces:** Six individually evidenced capabilities through the unchanged public tools, with remaining limits recorded separately.

- [ ] Add focused integration regressions through provider-qualified and combined `search_document`, returned handles and fresh `read_evidence`. Assert structured-only matches, same-source provenance, mixed partial results and existing stale/invalid error behavior. Keep the callable schemas unchanged.
- [ ] Review the system prompt and tool descriptions together. Extend the existing document-search description to say that exact item/recipe IDs can find declared guide relationships, retaining compact literal queries, Ponder's existing item query and continuation tied to a remaining evidence gap. Do not append scenario rules or change the broader search policy. Description/schema assertions establish the contract only; live calls establish model behavior.
- [ ] Run the seven focused test classes from Tasks 2-6, including `ClientAssistantTest`, then one `.\gradlew.bat test build --console=plain` with Java 21. Record actual totals and failures; do not describe prior build results as verification of this work.
- [ ] Write six focused installed checks with source/result expectations. Use real content first, with a small isolated authored fixture only for built-in forms missing from the installed pack. Include a second read after an ordinary source/progress change and an existing text-search regression check.
- [ ] For Modonomicon, use NeoVitae's `dungeons/demon_crafting` spotlight pages, `utility/incense_altar` pages with both recipe slots and `alchemy_arrays/crafting_array` with parent/read prerequisite `alchemy_arrays/arcane_ash`, where current native visibility permits. Include a hidden-content negative check; custom opaque conditions cannot become a complete explanation. A mod-specific page subclassing `BookRecipePage` must retain its native recipe references.
- [ ] For GuideME, use AE2's patterns page (`ae2:processing_pattern` is one associated item) and the certus-growth page's `RecipeFor` target `ae2:damaged_budding_quartz`; separately exercise direct `Recipe` and plural `RecipesFor`. Confirm the parsed live pages, not just bundled Markdown resources, supplied the returned facts.
- [ ] For IE, open the manual normally once if necessary. Verify the hammer/wirecutter crafting references and a blueprint/bottling selector, including distinct named-potion variants; use a focused fixture for fixed layouts/fluid selectors if the installed content does not exercise them.
- [ ] Inspect actual tool calls, payloads and assistant replies. A passing reader call does not by itself establish a correct ordinary-language answer; record extraction acceptance and answer-quality failures separately. Builds, source inspection and bundled resources are not live proof.
- [ ] Check off each of the six roadmap boxes only after its implementation, automated checks and installed extraction acceptance are recorded. If live checks cannot run, record implemented/automated-pass status with installed acceptance pending and leave that box open. Update the changelog with delivered behavior and explicit limits.
- [ ] Perform a manual style pass and finish with `git diff --check`. Do not run a formatter or broaden the work into unrelated guide, prompt, planner or navigation fixes.

## Execution order and ownership

Complete Tasks 1-2 before reader work so all readers consume one established contract. Tasks 3-5 can then be implemented independently, with disjoint reader/test ownership; keep at most two children and let the coordinator own shared files, documentation and Gradle. Never overlap writes to shared fixtures or the Git index. Use the repository's Luna-first routing; escalate only unresolved native visibility/condition or compatibility invariants. Finish with Task 6 after all reader changes are integrated.

## Acceptance boundary

The section is complete when all six required capabilities are available through existing document search and fresh evidence reads, each has its own passing automated and installed extraction evidence, and none depends on inferred icon associations, guessed recipe identities or flattened prerequisite logic. Explicit limits for arbitrary custom source implementations remain documented. Planning, adapter presence and existing text-read acceptance do not satisfy these six boxes.
