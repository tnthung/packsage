# JEI Adapter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** First complete iteration of the JEI adapter spec. With JEI installed and ready, the model reads and plans through recipes, mod machine recipes included, with JEI as the default recipe source. It can ask "what uses this item" from either source, shows JEI-drawn cards with JEI's R/U/A and click behavior, opens persistent JEI links, and adds, removes and checks bookmarks.

**Architecture:** All code that links against JEI lives in `dev.packsage.jei`. Core code reaches it only through `Class.forName` after a `ModList` version check (`client/JeiSupport`). JEI layouts are copied on the client thread into plain-Java views, and a pure projection turns them into the existing `Evidence.Record`/`RecipePayload`. Search runs over a small index interface that the runtime adapter implements with JEI's focus lookups. The JEI provider registers before `native`, so the existing first-enabled-recipe-source rule in Discovery, and first-handler dispatch for `plan_recipe`, make JEI the default. Planner changes stay inside `RecipePlanner` and are driven by record constraints, so native plans are unchanged.

**Tech Stack:** Java 21, NeoForge 21.1.202 (ModDevGradle), JEI `jei-1.21.1-neoforge` (compile only), JUnit 5.

**Spec:** [docs/specs/2026-10-27-jei-adapter.md](../specs/2026-10-27-jei-adapter.md)

## Global Constraints

- Supported JEI range: `[19.27, 20)`, checked with `ModList` and `VersionRange`, the way v1's `ClientAssistant` did (`origin/feat/packsage-v1:src/main/java/dev/packsage/client/ClientAssistant.java`, `JEI_VERSION`). Bookmarks additionally require `[19.53, 20)`.
- Compile against `mezz.jei:jei-1.21.1-neoforge:19.64.0.454`, which is needed for `IBookmarkManager`. Every class except `dev.packsage.jei.bookmark.*` must use only API present in 19.27. In particular, never call `IRecipeSlotView.getDisplayedIngredients()`, `IRecipeSlotView.getTagKey()`, `IRecipeCategory.getSourceRecipeIds`, `IJeiRuntime.getBookmarkManager()` or the recipe-transfer additions outside that package. A test lists these names and fails if any appears in `src/main/java/dev/packsage/jei` outside `bookmark/`.
- No class outside `dev.packsage.jei` imports `mezz.jei.*`, and PackSage must load and work fully without JEI installed. A test fails if any other `src/main/java` file contains `mezz.jei`.
- JEI provider id is `jei`. Model-facing text never says "provider" to the player. Use the glossary terms: recipe source, default recipe source, mod machine recipe, assumed consumption, recipe card, bookmark.
- Item searches use JEI's focus index, never a scan of every category. v1's scan took about 40 s for a torch.
- Mod machine records: INPUT alternatives are `CONSUMED` *by assumption*, CATALYST alternatives are `REUSED`, and RENDER_ONLY slots are omitted. An item input with a crafting remainder uses only its contents, and the container returns. Every record that relies on these rules carries the constraint `consumption=assumed`.
- Coverage: a vanilla category decoded as native would be is COMPLETE when native would be COMPLETE. Mod categories with read slots are PARTIAL. Unreadable slots are OPAQUE.
- Plans: in a mod machine step only the planned item counts, at its shown quantity, marked `output=assumed-guaranteed`. Other outputs never count. A chance output never counts. Fluids and container contents are listed as unexpanded needs. REUSED catalysts are listed once as equipment. The planner builds no refill routes.
- Persistent links: the JEI `sourceHandle` is `<categoryUid>|<recipeId>`. A recipe without a stable ID uses `<categoryUid>|#<ordinal>@<jeiGeneration>` and a temporary link. Follow the [persistent navigation contract](../specs/2026-10-18-persistent-navigation.md).
- Repo rules: follow the existing style and never run formatters. Run `./gradlew test` yourself. Make one commit per task with its tests and docs, with a subject of `[Add]`, `[Chg]`, `[Fix]` or `[Rmv]` plus a short phrase, and no AI attribution trailers. Add a `CHANGELOG.md` line under the current date in every task that changes behavior. Prompt changes follow the in-game editor loop in `CLAUDE.md`.

## Review Focus

1. **An item JEI has never heard of.** `outputItem` or `inputItem` for a registry item that has no JEI recipes returns an empty COMPLETE page, not an error and not a fallback scan. Test: `JeiSearchTest.unknownFocusReturnsEmptyComplete`.
2. **JEI reloads during a conversation.** A cursor or reference from an earlier JEI runtime is rejected because the scope epoch changes. A saved *stable* link still reopens. A temporary link reports unavailable. Tests: `JeiProviderTest.cursorFromEarlierRuntimeIsStale`, `JeiNavigationTest.stableLinkSurvivesNewRuntime` and `JeiNavigationTest.temporaryLinkExpiresWithRuntime`.
3. **A recipe JEI cannot lay out.** When the drawable is missing or the category throws, that record is OPAQUE and the page continues. Tests: `JeiProjectionTest.unreadableLayoutIsOpaque` and `JeiSearchTest.failingRecipeDoesNotAbortPage`.
4. **Potions and other subtype items.** `outputItem=minecraft:potion` finds Strength potion brewing without a category scan. Test: `JeiSearchTest.subtypeOutputExpandsKnownVariants`.
5. **JEI installed but not ready**, on the title screen or while a world loads. An unqualified search or plan falls to native with no JEI note. `provider: "jei"` returns UNAVAILABLE. `bookmark` is not offered. Tests: `KnowledgeServiceTest.notReadyJeiFallsToNativeSilently` and `PackSageToolsTest.bookmarkOfferedOnlyWhenReady`.

---

### Task 1: JEI dependency and optional loading

**Files:**
- Modify: `build.gradle` (repository `maven { url = 'https://maven.blamejared.com' }`, `compileOnly "mezz.jei:jei-1.21.1-neoforge:${jei_version}"`), `gradle.properties` (`jei_version=19.64.0.454`)
- Create: `src/main/java/dev/packsage/client/JeiSupport.java` (core, no JEI imports)
- Create: `src/main/java/dev/packsage/jei/PackSageJeiPlugin.java` (`@JeiPlugin`), `src/main/java/dev/packsage/jei/JeiRuntimeHolder.java`
- Modify: `src/main/java/dev/packsage/client/ClientAssistant.java` (`knowledge(...)` at about `:470`, scope epoch)
- Test: `src/test/java/dev/packsage/client/JeiSupportTest.java`, `src/test/java/dev/packsage/jei/JeiApiBoundaryTest.java`

**Interfaces:**
- Produces `JeiSupport`:
  - `supported(String version) -> boolean` checks `[19.27,20)`; `bookmarksSupported(String version) -> boolean` checks `[19.53,20)`.
  - `installedVersion() -> Optional<String>`. A test overload takes `Function<String, Optional<String>> modVersion`.
  - `providers(Minecraft, Executor clientExecutor, Supplier<Evidence.Scope>) -> List<KnowledgeProvider>`: empty without supported JEI; otherwise reflectively builds `dev.packsage.jei.JeiProvider`, plus `dev.packsage.jei.bookmark.JeiBookmarkProvider` when bookmarks are supported.
  - `runtimeGeneration() -> long`: 0 when JEI is absent.
  - `cardSource() -> Optional<RecipeCardSource>` and `viewer() -> Optional<ItemViewer>`, both filled by later tasks; empty until then.
- Produces `JeiRuntimeHolder` (static): `current() -> Optional<IJeiRuntime>`, and `generation() -> long`, which increments on every `onRuntimeAvailable` and `onRuntimeUnavailable`.

- [ ] **Step 1: Write failing tests.**
  - `JeiSupportTest`: `supported("19.27.0.340")` and `supported("19.64.0.454")` are true; `supported("19.26.9")` and `supported("20.0.0")` are false. `bookmarksSupported("19.52.9")` is false and `bookmarksSupported("19.53.0")` is true. With the mod lookup returning empty, `providers(...)` is empty.
  - `JeiApiBoundaryTest`: walks `src/main/java`. It fails if any file outside `dev/packsage/jei/` contains `mezz.jei`, or any file under `dev/packsage/jei/` but outside `bookmark/` contains `getDisplayedIngredients`, `getTagKey`, `getSourceRecipeIds`, `getBookmarkManager` or `IBookmarkManager`.
- [ ] **Step 2: Run** `./gradlew test --tests dev.packsage.client.JeiSupportTest --tests dev.packsage.jei.JeiApiBoundaryTest`. Expected: FAIL.
- [ ] **Step 3: Implement.** Add the dependency, then `JeiSupport`, `JeiRuntimeHolder` and `PackSageJeiPlugin` (`getPluginUid` = `packsage:jei`). A failed `Class.forName` returns empty and logs once through `PackSageLog`.
- [ ] **Step 4: Wire `ClientAssistant`.** Add `JeiSupport.providers(...)` *before* `NativeProvider` in `knowledge(...)`. Bump `providerRuntimeEpoch` when `JeiSupport.runtimeGeneration()` changes while a world is active; v1 did this in its tick path with `activeJeiRuntimeGeneration`.
- [ ] **Step 5: Run** `./gradlew test`. Expected: PASS.
- [ ] **Step 6: Commit** `[Add] load JEI as an optional recipe source`.

### Task 2: Projection from JEI layouts to records

**Files:**
- Create: `src/main/java/dev/packsage/jei/JeiLayout.java` (plain-Java views, no JEI types), `src/main/java/dev/packsage/jei/JeiProjection.java`
- Test: `src/test/java/dev/packsage/jei/JeiProjectionTest.java`

**Interfaces:**
- Produces:
  ```java
  record JeiLayout(String categoryUid, String categoryTitle, String recipeId /*nullable*/, int ordinal, long generation,
                   boolean hidden, boolean readable, List<Slot> slots, List<String> recipeTooltip) {
      enum Role { INPUT, OUTPUT, CATALYST, RENDER_ONLY }
      record Slot(int index, Role role, List<Ingredient> alternatives, List<String> extraTooltip) { }
      record Ingredient(String type /*item|fluid|other*/, String identity, String componentsJson,
                        long amount, String remainderIdentity /*nullable*/) { }
  }
  ```
- Produces: `JeiProjection.record(JeiLayout, Evidence.Scope, String providerVersion, Instant observedAt) -> Evidence.Record`. `providerId` is `jei`, `kind` is `RECIPE`, and `sourceHandle` is `JeiProjection.handle(layout)`. Targets hold one `NavigationTarget(scope, "jei", handle, "recipe")`, or `"temporary"` when `recipeId` is null.
- Produces: `JeiProjection.handle(JeiLayout) -> String`, following the Global Constraints format. `JeiProjection.parseHandle(String) -> Optional<Handle>`, where `record Handle(String categoryUid, String recipeId, Integer ordinal, Long generation)`.
- Produces: `JeiProjection.VANILLA_CATEGORIES`: `minecraft:crafting`, `minecraft:smelting`, `minecraft:blasting`, `minecraft:smoking`, `minecraft:campfire_cooking`, `minecraft:stonecutting`, `minecraft:smithing` and `minecraft:brewing`.
- Constraint vocabulary consumed by Task 6:
  - `recipeType=<categoryUid>`
  - `consumption=assumed`
  - `output=assumed-guaranteed`
  - `container-returned=<input>><container>`, for example `container-returned=minecraft:water_bucket>minecraft:bucket`
  - `chance:<groupId>=<verbatim line>`
  - `hidden`
  - `not-read=values drawn by the category`

- [ ] **Step 1: Write failing tests** in `JeiProjectionTest`, each building a `JeiLayout` by hand:
  - `rolesMapToPayloadLists`: INPUT maps to `inputs`, OUTPUT to `outputs` and CATALYST to `catalysts`. RENDER_ONLY is absent. `groupId` is `"slot-" + index`.
  - `fluidAlternativeKeepsMillibuckets`: `type=fluid`, and `quantity` is the amount.
  - `modCategoryIsPartialWithAssumedConsumption`: `naturesaura:altar` gives PARTIAL coverage, `CONSUMED` inputs and `REUSED` catalysts. Constraints include `consumption=assumed`, `output=assumed-guaranteed`, `recipeType=naturesaura:altar` and `not-read=values drawn by the category`.
  - `containerInputReturnsContainer`: a `minecraft:water_bucket` input with remainder `minecraft:bucket` puts `minecraft:bucket` in `remainders` and adds `container-returned=minecraft:water_bucket>minecraft:bucket`.
  - `vanillaCraftingIsComplete`: `minecraft:crafting` with item-only slots gives COMPLETE, with neither `consumption=assumed` nor `output=assumed-guaranteed`.
  - `hiddenRecipeIsMarked`: adds the constraint `hidden`.
  - `chanceTooltipIsKeptVerbatim`: an OUTPUT slot 2 with `extraTooltip` `["25% Chance"]` adds `chance:slot-2=25% Chance`. A slot with no line containing `%` gets nothing.
  - `unreadableLayoutIsOpaque`: `readable=false` gives OPAQUE coverage and decoding `OPAQUE`.
  - `missingRecipeIdGetsTemporaryHandle`: produces handle `naturesaura:altar|#3@7`, a null `canonicalRecipeId` and target id `temporary`. `parseHandle` round-trips both handle forms.
- [ ] **Step 2: Run** `./gradlew test --tests dev.packsage.jei.JeiProjectionTest`. Expected: FAIL.
- [ ] **Step 3: Implement.** The `payload` JSON keeps the shape `NativeSnapshot` builds: `inputs`, `outputs`, `remainders`, `catalysts`, `constraints` and `decodingStatus`. Add `category` (the title) and `possibleExtras`, which lists the outputs for display.
- [ ] **Step 4: Run** the test. Expected: PASS.
- [ ] **Step 5: Commit** `[Add] project JEI recipe layouts into recipe evidence`.

### Task 3: JEI search over the focus index

**Files:**
- Create: `src/main/java/dev/packsage/jei/JeiIndex.java` (an interface with no JEI types), `src/main/java/dev/packsage/jei/JeiSearch.java` and `src/main/java/dev/packsage/jei/JeiRuntimeIndex.java`
- Test: `src/test/java/dev/packsage/jei/JeiSearchTest.java`, using a fake `JeiIndex`

**Interfaces:**
- Consumes: `JeiLayout` and `JeiProjection` (Task 2).
- Produces:
  ```java
  interface JeiIndex {
      record RecipeRef(String categoryUid, int ordinal) { }
      long generation();
      List<RecipeRef> byFocus(JeiLayout.Role role, String itemId);   // every known subtype of itemId
      List<String> categoriesMatching(String text);                  // category uid by title
      List<String> itemsMatching(String text);                       // item ids via IIngredientFilter
      List<RecipeRef> inCategory(String categoryUid);
      Optional<RecipeRef> byRecipeId(String categoryUid, String recipeId);
      JeiLayout layout(RecipeRef ref);                               // readable=false on any failure
  }
  record Page(List<Evidence.Record> records, int nextOffset, boolean exhausted) { }
  static Page JeiSearch.search(JeiIndex index, String outputItem, String inputItem, String text,
                               int offset, int limit, Evidence.Scope scope, String version, Instant now)
  ```
- Candidate order, de-duplicated by `RecipeRef`:
  - Given `outputItem`, use `byFocus(OUTPUT, outputItem)`. Given `inputItem`, use `byFocus(INPUT, inputItem)`, intersected with the output list when both are given.
  - Given only `text`, take `inCategory` of each matching category, then `byFocus(OUTPUT, item)` for each matching item.
  - Text combined with an item filter keeps only candidates whose category title or output display name contains the text.
  - `offset` indexes the candidate list.

- [ ] **Step 1: Write failing tests:**
  - `outputFocusUsesIndexOnly`: the fake fails if `inCategory` is called for an `outputItem` search.
  - `inputFocusFindsUses`: `inputItem=naturesaura:gold_powder` returns the two INPUT refs.
  - `textMatchesCategoryAndItemNames`: "infusion" returns category refs, then item-output refs, with no duplicates.
  - `subtypeOutputExpandsKnownVariants`: `byFocus(OUTPUT, "minecraft:potion")` brewing refs come back.
  - `pagingAdvancesAndExhausts`: 5 refs with limit 2 give offsets 2 and 4, then exhausted.
  - `unknownFocusReturnsEmptyComplete`.
  - `failingRecipeDoesNotAbortPage`: one OPAQUE record, and the others are still returned.
- [ ] **Step 2: Run** `./gradlew test --tests dev.packsage.jei.JeiSearchTest`. Expected: FAIL.
- [ ] **Step 3: Implement `JeiSearch`.**
- [ ] **Step 4: Implement `JeiRuntimeIndex`** against `IJeiRuntime`, on the client thread only:
  - **`byFocus`:** for items that `ISubtypeManager` reports as having subtypes, use every stack of that item in `IIngredientManager.getAllItemStacks()`; otherwise use the default stack. Look up each category with `createRecipeLookup(type).limitFocus(focuses).includeHidden()`, where focuses come from `IFocusFactory.createFocus(role, VanillaTypes.ITEM_STACK, stack)`.
  - **`layout`:**
    - Build the drawable with `createRecipeLayoutDrawable(category, recipe, emptyFocusGroup)`.
    - Copy each `IRecipeSlotView` (`getRole`, `getAllIngredients`). Items come through `VanillaTypes.ITEM_STACK` with `getCraftingRemainingItem`. Fluids come through `NeoForgeTypes.FLUID_STACK`, in mB. Anything else becomes `type=other`.
    - `extraTooltip` holds the `IRecipeSlotDrawable` tooltip lines beyond the ingredient's own tooltip.
    - `recipeId` is `category.getRegistryName(recipe)`. When that is null and the recipe is a `RecipeHolder`, use `holder.id()`.
    - `hidden` means the recipe is absent from the same lookup without `includeHidden()`.
  - **Ref map:** keep `RecipeRef` → recipe object per generation, bounded at 4,096 entries and cleared on a generation change.
  - Live JEI is required, so this is covered by Task 12.
- [ ] **Step 5: Run** `./gradlew test`. Expected: PASS.
- [ ] **Step 6: Commit** `[Add] search JEI recipes through its focus index`.

### Task 4: JEI provider as the default recipe source

**Files:**
- Create: `src/main/java/dev/packsage/jei/JeiProvider.java`
- Test: `src/test/java/dev/packsage/jei/JeiProviderTest.java`, `src/test/java/dev/packsage/knowledge/KnowledgeServiceTest.java`

**Interfaces:**
- Consumes: `JeiSearch`, `JeiIndex`, `JeiProjection.parseHandle` and `JeiRuntimeHolder`.
- Produces: `final class JeiProvider implements KnowledgeProvider`.
  - Identity: `id()` is `jei`, `metadata()` is `new Metadata("jei", List.of(), true, List.of())`, and `enabled()` is true when an index is available.
  - Reads: `searchRecipe`. `readEvidence` and `query(GET, id=<handle>)` reread one handle through `JeiIndex.byRecipeId` or the temporary ordinal.
  - Navigation: `refreshNavigation` and `open` are filled in by Task 8 and return UNAVAILABLE until then. `planRecipe` comes in Task 6.
  - Test constructor: `JeiProvider(Supplier<Optional<JeiIndex>> index, Executor clientExecutor, Supplier<Evidence.Scope> scope)`.

- [ ] **Step 1: Write failing tests:**
  - `JeiProviderTest.validatesLikeNative`: a non-object filter, a bad `outputItem`, and `limit` 0 or 65 give `INVALID_ARGUMENTS`. `provider: "native"` gives `NOT_HANDLED`.
  - `JeiProviderTest.notReadyIsUnavailable`: `provider: "jei"` with no index gives `UNAVAILABLE`.
  - `JeiProviderTest.cursorFromEarlierRuntimeIsStale`: a cursor from scope `runtime-1`, used under `runtime-2`, gives `STALE_SCOPE`.
  - `KnowledgeServiceTest.readyJeiIsTheDefaultSource`: an unqualified `search_recipe` returns only `jei` records.
  - `KnowledgeServiceTest.notReadyJeiFallsToNativeSilently`: only `native` records come back, and `detail` does not mention `jei`.
- [ ] **Step 2: Run** these tests. Expected: FAIL.
- [ ] **Step 3: Implement.** Mirror `NativeProvider.searchRecipe` validation and cursor storage (`NativeProvider.java:193-243`, `signature`). Run on the client executor. A page is PARTIAL when it is not exhausted or any record is not COMPLETE.
- [ ] **Step 4: Run** `./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit** `[Add] make JEI the default recipe source while ready`, with a CHANGELOG line.

### Task 5: `inputItem` for both sources

**Files:**
- Modify: `src/main/java/dev/packsage/model/PackSageTools.java:101`, which defines the filter schema
- Modify: `NativeProvider.searchRecipe` and `NativeRecipeReader.search` / `searchCandidates`
- Modify: `JeiProvider`, to validate and pass `inputItem`
- Test: the existing tool-schema test class and the native recipe reader tests under `src/test/java/dev/packsage/knowledge/nativegame/`

**Interfaces:**
- Produces: a filter property `inputItem` that uses the same schema as `outputItem` (maxLength 256, pattern `^[a-z0-9_.-]+:[a-z0-9/._-]+$`).
- Produces: `NativeRecipeReader.search(Minecraft, String outputItem, String inputItem, String text, int offset, int limit, Evidence.Scope)`.

- [ ] **Step 1: Write failing tests:**
  - The schema has `inputItem`, and it is identical to `outputItem`.
  - Native search with `inputItem=minecraft:stick` matches a recipe that has a stick input.
  - A `#minecraft:planks` input matches `inputItem=minecraft:oak_planks` through the item's tags.
  - A recipe without the item is skipped.
  - The `inputItem` scan uses the 4,096-candidate budget.
  - A non-canonical item ID gives `INVALID_ARGUMENTS`. An unknown item gives `INVALID_ARGUMENTS` with "recipe input item is unknown".
- [ ] **Step 2: Run** these tests. Expected: FAIL.
- [ ] **Step 3: Implement.** In native, after `copyRecipe`, keep a record only if an input alternative's `comparisonIdentity` equals the item, or is a tag that holds it (`Holder.is(TagKey)`).
- [ ] **Step 4: Run** `./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit** `[Add] find recipes that use an item`, with a CHANGELOG line.

### Task 6: Planning through mod machines

**Files:**
- Modify: `src/main/java/dev/packsage/knowledge/RecipePlanner.java` (`visit`, `planned`, plan-level summary)
- Modify: `src/main/java/dev/packsage/model/PackSageTools.java` (`plan_recipe` gets an optional `provider` with the same enum as `search_recipe`)
- Modify: `JeiProvider` (`planRecipe`), `NativeProvider.planRecipe(ReadRequest, Context)` (respects `provider`)
- Create: `src/main/java/dev/packsage/jei/JeiPlanSource.java` (`RecipePlanner.Source`)
- Test: `src/test/java/dev/packsage/knowledge/RecipePlannerTest.java`, `src/test/java/dev/packsage/jei/JeiPlanSourceTest.java`

**Interfaces:**
- Consumes: the Task 2 constraint vocabulary and `JeiIndex.byFocus(OUTPUT, id)`.
- Produces: `JeiPlanSource(JeiIndex, Evidence.Scope, String version, Instant now) implements RecipePlanner.Source`. `recipeOutputs` projects the OUTPUT-focus refs. `recipesComplete` returns false when the ref list hit its bound.
- Produces planner behavior. It applies only when a record carries the matching constraint, so native plans stay byte-identical:
  - **Container rule:** an input listed in `container-returned=<in>><container>` adds, per route, the unexpanded need `{type: "contents", identity: <in>, quantity: batches×qty}`. It adds `<container>` once to `equipment`. Neither is expanded.
  - **Fluids:** `type=fluid` alternatives add an unexpanded need `{type: "fluid", identity, quantity: batches×mB}` instead of marking the route `unknown-consumption-or-requirement`.
  - **Catalysts:** when every catalyst alternative is `REUSED` and the record has `consumption=assumed`, the catalysts go to `equipment` once and no longer add `catalysts-present`.
  - **Chance output:** when the planned output's group has a `chance:` constraint, it does not count, `batches` is null, and the reason is `chance-output:<handle>`.
  - **Assumed steps:** routes from `consumption=assumed` records get the payload `assumed: true`. The plan `detail` adds `assumed-consumption:<handle>`.
  - **Payload:** route payloads gain `unexpandedNeeds` (array) and `possibleExtras` (the other outputs). The plan result gains `equipment`, deduplicated across routes.
- `plan_recipe` routing: `JeiProvider.planRecipe` answers when JEI is enabled and `provider` is null or `jei`, and returns `NOT_HANDLED` otherwise. Native answers when `provider` is null or `native`. JEI is registered first.

- [ ] **Step 1: Write failing tests** in `RecipePlannerTest`, using hand-built records:
  - `oneBucketForEightCrafts`: a machine needs `minecraft:water_bucket` (`container-returned=minecraft:water_bucket>minecraft:bucket`) plus 1 `minecraft:dirt` and outputs 1 `mod:mud`. Planning 8 mud gives the need `contents minecraft:water_bucket ×8` and equipment `[minecraft:bucket]`. The dirt requirement is 8. There is no route for buckets or iron.
  - `fluidInputListedNotExpanded`: 1000 mB water × 3 batches gives the need `fluid minecraft:water 3000`.
  - `reusedCatalystCountedOnce`: an altar catalyst over 5 batches appears once in `equipment`, and the reasons do not contain `catalysts-present`.
  - `onlyPlannedItemCounts`: grinding "1 iron ore → 2 iron dust + 1 gold dust" for 16 iron dust gives 8 batches. Gold dust is listed in `possibleExtras`, and a plan that also needs gold dust still routes gold dust separately.
  - `chanceOutputNeverCounts`: planning gold dust whose group has `chance:slot-2=10% Chance` gives null batches and the reason `chance-output`.
  - `assumedStepsMarked`: `assumed: true` is set on the route, and `assumed-consumption:` appears in `detail`.
  - `nativePlansUnchanged`: the existing beacon and torch fixtures are equal before and after the change.
  - `JeiPlanSourceTest.outputsComeFromFocusIndex`, using a fake `JeiIndex`.
- [ ] **Step 2: Run** `./gradlew test --tests dev.packsage.knowledge.RecipePlannerTest --tests dev.packsage.jei.JeiPlanSourceTest`. Expected: FAIL.
- [ ] **Step 3: Implement** the planner rules, `JeiPlanSource`, `JeiProvider.planRecipe` (reusing `NativeProvider`'s argument validation and the `plan|…` handle wrapping), and the `provider` argument.
- [ ] **Step 4: Run** `./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit** `[Add] plan through mod machine recipes with assumed consumption`, with a CHANGELOG line.

### Task 7: JEI-drawn recipe cards

**Files:**
- Modify: `src/main/java/dev/packsage/client/recipe/RecipeCardModel.java` (`Category.VIEWER`, plus a trailing `String viewerHandle`), `RecipeCardModels.java` (`fromRecord`), `RecipeCardSource.java` (a `card(model, maxWidth)` default that delegates to `card(model)`), and `RecipeCard.java` (`ItemStack itemAt(int x, int y, double mouseX, double mouseY)`, default `ItemStack.EMPTY`)
- Create: `src/main/java/dev/packsage/client/recipe/RecipeCardSources.java`, `src/main/java/dev/packsage/jei/JeiRecipeCardSource.java`
- Modify: `src/main/java/dev/packsage/client/ScreenText.java:394-430` (accept `jei`), `src/main/java/dev/packsage/client/AssistantScreen.java:901` (the composed source, with the column width)
- Test: `src/test/java/dev/packsage/client/recipe/RecipeCardModelsTest.java`, `src/test/java/dev/packsage/client/ScreenTextTest.java`, `src/test/java/dev/packsage/client/recipe/RecipeCardSourcesTest.java`

**Interfaces:**
- Produces: for a `jei` record, `RecipeCardModels.fromRecord` returns a `Category.VIEWER` model with `viewerHandle` set to its handle, the category title as the caption, and payload stacks for the chip fallback. Native models pass a null `viewerHandle`.
- Produces: `RecipeCardSources.first(List<RecipeCardSource>) -> RecipeCardSource`, which returns the first non-empty card.
- Produces `JeiRecipeCardSource`, reached through `JeiSupport.cardSource()`:
  - **Lookup:** for `VIEWER` models it resolves `viewerHandle` through `JeiIndex`. For native models it resolves `minecraft:<category>` plus the model's canonical recipe ID with `byRecipeId`, and returns empty when there's no match.
  - **Drawing:** it wraps `IRecipeLayoutDrawable`. `getRect` gives the size; `draw` uses `setPosition`, `drawRecipe` and `drawOverlays`; `hoverAt` and `itemAt` use `getItemStackUnderMouse`.
  - **Width:** when the card is wider than `maxWidth`, scale it to fit, down to 0.75. Below that, return empty so the existing narrow fallback (caption and chips) applies.
  - **Cache:** cache by `(viewerHandle, generation)`, bounded at 64.

- [ ] **Step 1: Write failing tests:** `jeiRecordBecomesViewerModel`, `nativeModelsKeepNullViewerHandle`, `ScreenTextTest.jeiSourcesBecomeCards`, `RecipeCardSourcesTest.firstNonEmptyWins` and `RecipeCardSourcesTest.nativeFallbackWhenViewerEmpty`.
- [ ] **Step 2: Run** these tests. Expected: FAIL.
- [ ] **Step 3: Implement** the model and source changes, and wire `AssistantScreen` to `RecipeCardSources.first(...)`, with JEI first when present and native returning empty for `VIEWER`.
- [ ] **Step 4: Run** `./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit** `[Add] draw recipe cards with JEI`, with a CHANGELOG line.

### Task 8: Persistent JEI links

**Files:**
- Modify: `JeiProvider` (`refreshNavigation`, `open`)
- Create: `src/main/java/dev/packsage/jei/JeiViewer.java`, which wraps `IRecipesGui`
- Modify: `RecipeCardModels` and `ScreenText`: below the minimum scale, the narrow fallback adds an "Open in JEI" link to the record's target
- Test: `src/test/java/dev/packsage/jei/JeiNavigationTest.java`; the conversation-store round trip goes in the existing `ConversationStoreTest`

**Interfaces:**
- Consumes: `JeiProjection.parseHandle` and `JeiIndex.byRecipeId`.
- Produces: `JeiViewer.showRecipe(JeiIndex.RecipeRef) -> boolean`, which uses `IRecipesGui.showRecipes(category, List.of(recipe), focuses)`.
- `refreshNavigation`: a stable handle resolves by `(categoryUid, recipeId)` in the *current* runtime and returns a fresh target in the current scope. A temporary handle resolves only while `generation` matches. Duplicate matches, a missing recipe, a hidden or removed category, or a malformed handle return null.

- [ ] **Step 1: Write failing tests** with a fake `JeiIndex`:
  - `stableLinkSurvivesNewRuntime`: a target saved under generation 1 still resolves under generation 2 after the index is rebuilt.
  - `temporaryLinkExpiresWithRuntime`.
  - `removedRecipeFailsExplicitly`.
  - `duplicateMatchesAreRejected`.
  - `malformedHandleIsNull`.
  - `savedTargetRoundTripsThroughConversationStore`: serialize and deserialize, then resolve.
- [ ] **Step 2: Run** these tests. Expected: FAIL.
- [ ] **Step 3: Implement** `refreshNavigation`, `open` and `JeiViewer`, and the "Open in JEI" link in the narrow fallback.
- [ ] **Step 4: Run** `./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit** `[Add] open saved JEI recipe links after restarts`, with a CHANGELOG line.

### Task 9: JEI interaction over cards and chips

**Files:**
- Create: `src/main/java/dev/packsage/client/recipe/ItemViewer.java` (core interface)
- Create: `src/main/java/dev/packsage/jei/JeiItemViewer.java`
- Modify: `src/main/java/dev/packsage/client/AssistantScreen.java`. That means `mouseClicked` (`:1651`; the card panel click is swallowed at `:1652-1653` and chip clicks run at `:1777-1782`) and `keyPressed` (`:1829`). Use the existing `cardHits` and chip hit records to find the item under the mouse.
- Test: `src/test/java/dev/packsage/client/AssistantScreenStateTest.java` (or a new `CardInteractionTest`, using a fake `ItemViewer`)

**Interfaces:**
- Produces:
  ```java
  interface ItemViewer {
      boolean showRecipes(ItemStack stack);
      boolean showUses(ItemStack stack);
      boolean toggleBookmark(ItemStack stack);    // false when bookmarks are unsupported
      boolean matchesShowRecipes(int keyCode, int scanCode);
      boolean matchesShowUses(int keyCode, int scanCode);
      boolean matchesBookmark(int keyCode, int scanCode);
  }
  ```
- `JeiItemViewer` implements `ItemViewer`:
  - Showing: `IRecipesGui.show(focus)` with an OUTPUT focus for recipes and an INPUT focus for uses.
  - Keys: `matchesShowRecipes` and `matchesShowUses` use `runtime.getKeyMappings().getShowRecipe()` and `getShowUses()` with `isActiveAndMatches(InputConstants.getKey(keyCode, scanCode))`. JEI has no public bookmark key mapping, so `matchesBookmark` matches `GLFW_KEY_A`.
  - Bookmarks: `toggleBookmark` delegates to `JeiBookmarks` (Task 10) when it's supported and returns false otherwise.
- Screen behavior:
  - Keys: R, U and A over a card slot item or an item or result chip call the matching method.
  - Card slots: a left click on a card slot item calls `showRecipes`, and a right click calls `showUses`.
  - Chips: a left click on an item or result chip calls `showRecipes`. A source chip that has a navigation target keeps today's `navigate`.
  - Without a viewer, nothing changes.

- [ ] **Step 1: Write failing tests** with a fake viewer that records calls:
  - `rOverCardSlotShowsRecipes`, `uOverChipShowsUses` and `aOverCardSlotTogglesBookmark`.
  - `leftClickCardSlotShowsRecipes` and `rightClickCardSlotShowsUses`.
  - `resultChipClickShowsRecipes`.
  - `sourceChipClickStillNavigates`.
  - `noViewerKeepsClicksInert`.
- [ ] **Step 2: Run** these tests. Expected: FAIL.
- [ ] **Step 3: Implement** the interface, `JeiItemViewer` and the screen wiring. `JeiSupport.viewer()` returns it.
- [ ] **Step 4: Run** `./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit** `[Add] open JEI recipes, uses and bookmarks from chat cards and chips`, with a CHANGELOG line.

### Task 10: Bookmarks

**Files:**
- Create: `src/main/java/dev/packsage/jei/bookmark/JeiBookmarks.java`, which uses `IBookmarkManager`, and `src/main/java/dev/packsage/jei/bookmark/JeiBookmarkProvider.java`, a `KnowledgeProvider` with id `jei_bookmarks`
- Modify: `src/main/java/dev/packsage/model/PackSageTools.java` (the `bookmark` definition, plus `definitions(List<Metadata>, Set<String> optionalTools)`), `src/main/java/dev/packsage/knowledge/KnowledgeService.java` (`optionalTools()`), `src/main/java/dev/packsage/chat/AssistantRuntime.java:1858` (pass `optionalTools()`), `src/main/java/dev/packsage/chat/ActivityLabels.java` (a label for `bookmark`), and `src/main/java/dev/packsage/model/GroundingInstructions.java:84` (the default definitions)
- Test: `src/test/java/dev/packsage/model/PackSageToolsTest.java` (or the existing schema test), `src/test/java/dev/packsage/jei/bookmark/JeiBookmarkProviderTest.java`

**Interfaces:**
- **Tool schema:** `bookmark` takes `{action: "add"|"remove"|"check" (required), item: {id: <item pattern>, components: object|null}|null, recipe: <reference string from search_recipe>|null}`, and exactly one of `item` or `recipe`.
- **Result:** one record of kind `OTHER` with payload `{action, target, bookmarked: boolean, changed: boolean}`.
- **Offering:** `KnowledgeService.optionalTools()` returns `Set.of("bookmark")` when a provider with id `jei_bookmarks` is registered and `enabled()`, and `Set.of()` otherwise. `PackSageTools.definitions(metadata, optionalTools)` includes `bookmark` only when the set contains it. The existing single-argument `definitions` passes `Set.of()`.
- **`JeiBookmarkProvider.handle`:** it answers only `bookmark`.
  - A recipe target resolves the observed `jei` reference through `JeiIndex`.
  - Items go through `IBookmarkManager.add`, `remove` and `contains(ITypedIngredient)`. Recipes go through `add`, `remove` and `contains(RecipeType, recipe)`.
  - It returns `UNAVAILABLE` when JEI is not ready, and `INVALID_REFERENCE` when the recipe reference wasn't observed.

- [ ] **Step 1: Write failing tests:**
  - `bookmarkOfferedOnlyWhenReady`: there's no `bookmark` tool with an empty set, and the tool and its schema are present with `{"bookmark"}`.
  - `exactlyOneTarget`: both targets or neither gives `INVALID_ARGUMENTS`.
  - `addRemoveCheckItem`, using a fake `JeiBookmarks` seam: `changed` and `bookmarked` follow the manager's results.
  - `recipeTargetNeedsObservedReference`.
  - `notReadyIsUnavailable`.
- [ ] **Step 2: Run** these tests. Expected: FAIL.
- [ ] **Step 3: Implement.** `JeiSupport.providers` adds `JeiBookmarkProvider` only when `bookmarksSupported`. `JeiItemViewer.toggleBookmark` uses `JeiBookmarks`.
- [ ] **Step 4: Run** `./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit** `[Add] let the assistant add, remove and check JEI bookmarks`, with a CHANGELOG line.

### Task 11: Prompt and tool descriptions

**Files:**
- Modify: `src/main/java/dev/packsage/model/PackSageTools.java` (the `search_recipe`, `plan_recipe` and `bookmark` descriptions at `:256` and nearby), and `src/main/java/dev/packsage/model/GroundingInstructions.java` (the recipe paragraphs at `:25-35`, plus a bookmark policy sentence)
- Test: the existing prompt and tool-description tests, updated only where they assert the replaced sentences

**Content to integrate** (decision policy, not literal copy; review the whole prompt as one policy per `CLAUDE.md`):
- **Default source:** `search_recipe` and `plan_recipe` read the default recipe source, which is JEI while ready and otherwise the game's own recipes. Name `native` only for a recipe type JEI does not list.
- **Uses questions:** `inputItem` answers "what is this used in".
- **Mod machine recipes:** these are PARTIAL. Their usage counts are assumed (`consumption=assumed`, `output=assumed-guaranteed`), and the reply says so ("assuming the altar consumes them"). Chance lines and `possibleExtras` are possible, not guaranteed. `not-read` values are reported as unread.
- **Plans:** `equipment` is needed once. `unexpandedNeeds` lists fluids and container contents the player must supply.
- **Bookmarks:** add directly when the player asks. When the idea is the model's own, offer without adding. Remove only on request. "What did I bookmark?" is unsupported, and `check` covers one item or recipe.
- **Links:** keep including JEI recipe links and cards by default, as the navigation rules require.

- [ ] **Step 1: Draft** the candidate in the in-game developer editor (middle-click the settings gear), with Minecraft kept open between candidates.
- [ ] **Step 2: Live-validate the candidate** with the Task 12 question set: inspect the actual calls, the evidence and the replies, including whether links and cards appear and open. Record failures separately from passes.
- [ ] **Step 3: Promote** the passing whole prompt to the code defaults, then run `./gradlew test`. Expected: PASS.
- [ ] **Step 4: Verify the installed default** with the developer override cleared.
- [ ] **Step 5: Commit** `[Chg] teach the assistant JEI recipes, plans and bookmarks`, with a CHANGELOG line.

### Task 12: Live acceptance and records

**Files:**
- Create: `docs/testing/2026-10-27-jei-adapter.md` (the check list), `docs/validation/2026-10-27-jei-adapter.md` (the results)
- Modify: `docs/roadmap/2026-10-13-todo.md` and `docs/roadmap/2026-10-21-adapter-feature-todo.md`, which get JEI v2 entries grouped by adapter status, then feature, with one checkbox per feature. Also update the JEI adapter section of `docs/roadmap/2026-10-22-v2-minecraft-only.md`.

- [ ] **Step 1: Build and install.** Run `./gradlew build` and install into `C:\Users\hung1\curseforge\minecraft\Instances\Packsage\mods`. Close Minecraft yourself first, through the debug bridge or by killing `javaw`. Never remove the instance's JEI jar except for check 9, and restore it afterwards.
- [ ] **Step 2: Live checks on JEI 19.27**, as installed, delegated to Codex gpt-6.1-terra at xhigh with computer use, developer override cleared:
  1. "How do I craft a torch?": one `search_recipe` call answers from `jei`, and a JEI-drawn card appears.
  2. "How do I make a Potion of Strength?": JEI brewing answers on the first call.
  3. "How do I make gold powder?": the Nature's Aura altar recipe is PARTIAL with assumed consumption, the reply says it's assumed, and a JEI-drawn altar card appears.
  4. "What uses gold powder?": `inputItem` answers from JEI.
  5. "Plan 8 of <a Nature's Aura altar output>": the plan expands through the altar, marks the step assumed, lists the altar as equipment once, and lists possible extras without counting them.
  6. R, U and A, and left and right clicks over card slots and chips, open recipes and uses. A does nothing visible, and no error appears.
  7. The `bookmark` tool is not offered, as `request_built` shows.
  8. Reload resources (F3+T) during a chat: an old cursor is rejected and a new search works. A saved recipe link reopens after a full Minecraft restart.
  9. With JEI moved out of `mods` while Minecraft is closed, questions 1 and 4 answer from native with no JEI note, and the cards are native. Then restore JEI.
- [ ] **Step 3: Live checks on JEI 19.6x.** Update the Packsage instance's JEI to a 19.6x build (approved by the player). Then:
  - (a) "Bookmark gold powder" adds it, and it appears in JEI's list.
  - (b) A question where a bookmark would help gets an offer, not an add.
  - (c) "Is gold powder bookmarked?" uses `check`.
  - (d) "Remove that bookmark" removes it.
  - (e) A over a card item toggles its bookmark.
- [ ] **Step 4: ATM10 spot check (JEI 19.64).** Read one Create, one Mekanism and one Immersive Engineering recipe for slots, fluids, catalysts and card drawing, and plan one assumed-consumption route.
- [ ] **Step 5: Record results** in the validation file, with failures kept separate from passes. Update the three roadmap files; items implemented but not accepted stay unchecked.
- [ ] **Step 6: Commit** `[Chg] record JEI adapter acceptance`.
