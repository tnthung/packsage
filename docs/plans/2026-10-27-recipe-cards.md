# Recipe Cards Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show cited recipes as JEI-style recipe cards in Normal and Detailed answers, and as item chips plus a hoverable, Shift-pinnable result chip in Concise answers.

**Architecture:**
- **Card model.** A pure model (`client/recipe`) turns a recorded recipe record into a `RecipeCardModel` with JEI 19 slot coordinates.
- **Text parsing.** `ScreenText` classifies citations into cards, result chips and item chips, using the verbosity stored on the turn.
- **Native renderer.** The native renderer draws the model with bundled JEI sprites and vanilla item rendering, behind a `RecipeCardSource` seam that a later JEI implementation fills.
- **Chat layout.** `AssistantScreen` gains block rows, 18 px chip lines, and a floating card panel driven by a pure `PinnedCardState`.

**Tech Stack:** Java 21, NeoForge 1.21.1, Gson, JUnit 5.

**Spec:** `docs/specs/2026-10-27-recipe-cards.md`, including its reference mockup. The JEI coordinates are in `docs/research/2026-10-27-jei-recipe-layouts.md`.

## Global Constraints

- Build and test with `JAVA_HOME=D:/Project/packsage/.tools/jdk/jdk-21.0.12.1 ./gradlew build -q`, and count results with `node build/tools/count.cjs` (run from the repo root).
- Commit each step as soon as it is written. Messages start with `[Add]`, `[Chg]`, `[Fix]` or `[Rmv]`, with no AI attribution trailers. Merge only with `git merge --no-ff`. Do not push, and do not merge into `feat/packsage-v1`.
- Never run formatting tools. Match the surrounding style.
- No core class links against JEI. This plan adds no JEI dependency.
- Icons are always the standard item render: `GuiGraphics.renderItem` plus `renderItemDecorations`. Never draw a raw texture in an item's place. Items are never scaled down.
- A line holding an item chip or result chip is 18 GUI px tall; other lines stay `ModernUi.MESSAGE_LINE_HEIGHT` (12).
- Turns with no stored verbosity render exactly as today: no cards, no item chips and no result chips.
- Concise answers never contain block cards.
- Chat opening must stay within the 2026-10-26 recheck: about 11–50 ms from the key press to the first frame.
- Add a `CHANGELOG.md` line under `### 2026-10-27` with each task that changes behavior.

## Review Focus

These are the inputs most likely to break in real use. The task named on each line pins it with a test.

1. **Truncated payloads.** A tool result whose record payload was truncated by the output budget (`PackSageTools` replaces the largest field) must produce no card and must not throw. Pinned in Task 3.
2. **Aliased citations.** A recipe cited through a `ref:` alias or inside a `\uE200cite…` group must resolve to the same card as the canonical `[native:<id>]`. Pinned in Task 5.
3. **Unloaded items.** A recorded item from a mod that is no longer loaded must show the barrier icon in the slot and render as a plain label in a chip, never a substitute item. Pinned in Task 6.
4. **Duplicate recipes.** The same recipe cited twice in one answer, or recorded in two tool results with different `observedAt`, must render the card that matches the cited `Source` exactly, as `sourceLabels` does. Pinned in Task 5.
5. **Narrow windows.** A bubble narrower than the card must show the source chip instead of a clipped card. Pinned in Task 7.

## Parallel waves

Tasks in the same wave touch disjoint files and can run at the same time, each in its own worktree. Merge each wave back with `git merge --no-ff` before the next wave starts.

| Wave | Tasks | Depends on |
|---|---|---|
| A | 1 Cooking data · 2 Stored verbosity · 3 Card model · 4 Pin state | The JEI layout research |
| B | 5 Citation classification · 6 Native renderer · 8a Prompt draft | Wave A |
| C | 7 Chat layout and drawing | Wave B |
| D | 8b Live prompt iteration · 9 Live acceptance and docs | Wave C installed |

---

### Task 1: Record cooking time and experience (Wave A)

**Files:**
- Modify: `src/main/java/dev/packsage/knowledge/nativegame/NativeSnapshot.java` (`copyRecipe`, around lines 202–247)
- Test: `src/test/java/dev/packsage/knowledge/nativegame/NativeRecipeReaderTest.java`

**Interfaces:**
- Produces: for `AbstractCookingRecipe` records, payload JSON gains `"cookingTime"` (an integer number of ticks) and `"experience"` (a number). Both are absent for other types.

- [ ] **Step 1: Write the failing test.** In `smeltingRecordCarriesCookingTimeAndExperience`, a smelting recipe with `cookingtime` 200 and `experience` 0.1 is read through the reader. Assert `payload.cookingTime == 200` and `payload.experience == 0.1`. Also assert that a shaped crafting record has neither key.
- [ ] **Step 2: Run the test.** It should fail. Run: `JAVA_HOME=D:/Project/packsage/.tools/jdk/jdk-21.0.12.1 ./gradlew test --tests "dev.packsage.knowledge.nativegame.NativeRecipeReaderTest" -q`
- [ ] **Step 3: Implement.** In `copyRecipe`, when `recipe instanceof AbstractCookingRecipe cooking`, add `cookingTime` (`cooking.getCookingTime()`) and `experience` (`cooking.getExperience()`) to the payload map. Follow the same pattern as `shape`.
- [ ] **Step 4: Run the test.** It should pass. Then run the full build. Existing evidence tests that compare whole payloads need the new keys.
- [ ] **Step 5: Commit.** `[Add] record cooking time and experience in native cooking recipe payloads`

### Task 2: Store the verbosity on each turn (Wave A)

**Files:**
- Modify: `src/main/java/dev/packsage/chat/ChatContracts.java` (`Submission` and `Turn`)
- Modify: `src/main/java/dev/packsage/chat/AssistantRuntime.java` (submission creation; the request builder around line 1851)
- Modify: `src/main/java/dev/packsage/chat/RequestController.java` (every `new ChatContracts.Turn(...)`, lines 1006–1140)
- Modify: `src/main/java/dev/packsage/chat/ConversationStore.java` (turn JSON write around line 1115 and read around line 1356)
- Test: `src/test/java/dev/packsage/chat/ConversationStoreTest.java`, `src/test/java/dev/packsage/chat/RequestControllerTest.java`

**Interfaces:**
- Produces:
  - `ChatContracts.Submission.verbosity()` returns a `PackSageSettings.Verbosity`. It is non-null; a missing value defaults to `NORMAL` in the compact constructor.
  - `ChatContracts.Turn.verbosity()` returns a `PackSageSettings.Verbosity`, which is null for legacy turns.
  - It is persisted as the JSON key `"verbosity"`, holding the enum name.
- Keep the existing `Turn` and `Submission` constructors as delegating overloads that pass `null` and `NORMAL`, the same way `assistantSentAt` was added.

- [ ] **Step 1: Write the failing tests.**
  - `ConversationStoreTest.verbosityRoundTrips`: save a turn with `CONCISE`, reload it, and assert `CONCISE`.
  - `ConversationStoreTest.legacyTurnHasNoVerbosity`: a turn JSON without the key reloads with `verbosity() == null`.
  - `RequestControllerTest.completedTurnKeepsSubmissionVerbosity`: a submission with `DETAILED` produces a turn with `DETAILED`.
- [ ] **Step 2: Run the tests.** They should fail.
- [ ] **Step 3: Implement.**
  - `AssistantRuntime` captures `settings.verbosity()` under `stateLock` when it creates the `Submission`.
  - The request builder applies `prepared.submission().verbosity()` instead of re-reading settings, so the instructions and the stored value always match.
  - `RequestController` copies the submission's verbosity onto every turn it builds.
- [ ] **Step 4: Run the tests.** They should pass. Then run the full build.
- [ ] **Step 5: Commit.** `[Add] store the answer verbosity on each turn`

### Task 3: Build the recipe card model (Wave A)

**Files:**
- Create: `src/main/java/dev/packsage/client/recipe/RecipeCardModel.java`
- Create: `src/main/java/dev/packsage/client/recipe/RecipeCardLayouts.java`
- Create: `src/main/java/dev/packsage/client/recipe/RecipeCardModels.java`
- Test: `src/test/java/dev/packsage/client/recipe/RecipeCardModelsTest.java`

**Interfaces:**
- Produces:
  - `enum RecipeCardModel.Category { CRAFTING_SHAPED, CRAFTING_SHAPELESS, SMELTING, BLASTING, SMOKING, CAMPFIRE, STONECUTTING, SMITHING, BREWING }`
  - `record RecipeCardModel(Category category, int width, int height, List<Slot> slots, String caption, List<Text> texts)`
  - `record RecipeCardModel.Slot(String role, int x, int y, boolean output, List<Stack> stacks)`. Here `x` and `y` are the item position in GUI px; the background is drawn at `x - 1` and `y - 1` for 18×18 slots, and at the research document's offset for the output slot.
  - `record RecipeCardModel.Stack(Kind kind, String identity, String componentsJson, int count, String description)`, with `enum Kind { ITEM, TAG, CUSTOM, UNKNOWN }`.
  - `record RecipeCardModel.Text(String text, int x, int y, boolean rightAligned)`
  - `static Optional<RecipeCardModel> RecipeCardModels.fromRecord(com.google.gson.JsonObject record)`. The input is one element of a tool result's `records` array.
  - `RecipeCardLayouts` holds every coordinate, size and text position, copied verbatim from `docs/research/2026-10-27-jei-recipe-layouts.md`.
- Captions are exact strings:
  - `Crafting table`
  - `Crafting table · shapeless`
  - `Furnace`
  - `Blast furnace`
  - `Smoker`
  - `Campfire`
  - `Stonecutter`
  - `Smithing table`
  - `Brewing stand`
- **Eligibility:**
  - The `recipeType=` constraint (or a `brewing:` reference) maps to a category, and `decodingStatus` is not `UNKNOWN`. Otherwise the result is empty.
  - Missing or malformed `payload` fields also give an empty result, never an exception. Truncated payloads are one such case.

- [ ] **Step 1: Write the failing tests.** Use record JSON fixtures shaped like `NativeSnapshot.recipePayloadJson` output.
  - `shapedBeaconPlacesSlotsByIndex`: a 3×3 shape with `slot-4` = nether star sits at the research document's grid position (1, 1); the output has a count; the caption is `Crafting table`.
  - `shapedTwoByTwoUsesJeiPlacement`: a 2×2 shape is placed the way the research document says JEI places shapes smaller than 3×3.
  - `shapelessFillsInOrderAndCaptions`: slots fill left to right and top to bottom; the caption is `Crafting table · shapeless`.
  - `smeltingShowsCookTimeAndExperience`: a `cookingTime` of 200 produces the research document's cook time text (for example `10s`) and experience text, at the documented positions.
  - `blastingAndSmokingDifferOnlyByCaption`
  - `smithingTransformHasThreeInputs` and `smithingTrimUnknownOutputIsUnknownStack`
  - `brewingUsesJeiLayout`: three potion slots, the ingredient slot and the output slot are at the research positions; the result has no fuel slot and no "Steps" text.
  - `tagAlternativeBecomesTagStack` and `customAlternativeBecomesCustomStack`
  - `unknownDecodingHasNoCard`, `unrecognizedRecipeTypeHasNoCard`, and `truncatedPayloadHasNoCard` (the payload replaced by a string, or `inputs` missing), all returning an empty result
- [ ] **Step 2: Run the tests.** They should fail.
- [ ] **Step 3: Implement** `RecipeCardModels.fromRecord` and `RecipeCardLayouts`.
- [ ] **Step 4: Run the tests.** They should pass.
- [ ] **Step 5: Commit.** `[Add] build JEI-shaped recipe card models from recorded recipes`

### Task 4: Pinned card state (Wave A)

**Files:**
- Create: `src/main/java/dev/packsage/client/recipe/PinnedCardState.java`
- Test: `src/test/java/dev/packsage/client/recipe/PinnedCardStateTest.java`

**Interfaces:**
- Produces:
  - `enum PinnedCardState.Visibility { HIDDEN, HOVER, PINNED }`
  - `record PinnedCardState.Rect(int left, int top, int right, int bottom)`
  - `Visibility update(double mouseX, double mouseY, boolean shiftDown, Rect chip, Rect panel)`. The `chip` value is the chip under consideration, and `panel` is the last drawn panel, which is null while hidden.
  - `Rect anchor()`, the chip the panel was pinned from, or null.

- [ ] **Step 1: Write the failing tests.**
  - `hoverChipShowsPanel`: the mouse is on the chip, without Shift, so the result is `HOVER`.
  - `leavingChipWithoutShiftHides`
  - `shiftWhileHoverPins`: then moving into the panel stays `PINNED`, and the panel position does not change.
  - `releasingShiftOutsideChipAndPanelHides`
  - `releasingShiftInsidePanelKeepsHoverUntilMouseLeaves`: release inside the panel gives `HOVER`; leaving it gives `HIDDEN`.
  - `shiftAloneWithoutHoverDoesNothing`
- [ ] **Step 2: Run the tests.** They should fail.
- [ ] **Step 3: Implement** the state machine.
- [ ] **Step 4: Run the tests.** They should pass.
- [ ] **Step 5: Commit.** `[Add] add the hover and Shift pin state for recipe card panels`

### Task 5: Classify citations into cards and chips (Wave B)

**Files:**
- Modify: `src/main/java/dev/packsage/client/ScreenText.java` (`InlineSpan`, `InlineLine`, `inlineLines`, `inlineSpans`, and a new `recipeCards`)
- Test: `src/test/java/dev/packsage/client/ScreenTextTest.java`

**Interfaces:**
- Consumes:
  - `RecipeCardModels.fromRecord` (Task 3)
  - `ChatContracts.Turn.verbosity()` (Task 2)
- Produces:
  - `InlineSpan` gains `RecipeCardModel resultCard` (non-null means a result chip) and `String itemId` (non-null means an item chip). Existing constructors pass null for both.
  - `InlineLine` gains `RecipeCardModel card` (non-null means a block card row, whose spans hold the fallback source chip). Existing constructors pass null.
  - `static Map<Evidence.Source, RecipeCardModel> recipeCards(ChatContracts.Turn turn)` recovers records exactly as `sourceLabels` does: the provider, handle, `observedAt`, generation and scope must all match. Plan handles (`plan|…`) use the plan record's own payload.
  - A new overload: `inlineLines(String text, List<NavigationTarget> targets, List<UrlCitation> webCitations, List<Source> sources, Map<Source,String> sourceLabels, PackSageSettings.Verbosity verbosity, Map<Source,RecipeCardModel> cards)`. The old overload passes `null` and `Map.of()`.
- **Rules**, applied only when `verbosity != null`:
  - **Normal and Detailed.** A line whose only non-whitespace content is one recipe citation with a card becomes an `InlineLine` with `card` set.
  - **Concise.** Every recipe citation with a card becomes a result-chip span, whether or not it stands alone.
  - **Item links.** `[Label](item:<id>)` becomes an item span with `itemId = <id>`. With `verbosity == null`, it renders exactly as today (raw Markdown text).
  - **Everything else.** Unchanged.

- [ ] **Step 1: Write the failing tests.**
  - `normalStandaloneRecipeCitationIsCardLine`
  - `normalInlineRecipeCitationStaysSourceChip`
  - `conciseStandaloneRecipeCitationIsResultChip`
  - `conciseInlineRecipeCitationIsResultChip`
  - `legacyTurnKeepsSourceChipsAndPlainItemLabels` (`verbosity == null`)
  - `itemLinkBecomesItemChip`
  - `aliasedAndGroupedCitationsResolveToSameCard` (Review Focus 2)
  - `duplicateRecordsMatchCitedObservation` (Review Focus 4): two records for the same recipe with different `observedAt` values; the card comes from the one whose source was cited.
  - `planCitationUsesPlanPayload`
- [ ] **Step 2: Run the tests.** They should fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the tests.** They should pass. Then run the full build.
- [ ] **Step 5: Commit.** `[Add] classify recipe and item citations into cards and chips by stored verbosity`

### Task 6: Native card renderer and slot stacks (Wave B)

**Files:**
- Create: `src/main/java/dev/packsage/client/recipe/RecipeCard.java` (the interface)
- Create: `src/main/java/dev/packsage/client/recipe/RecipeCardSource.java` (the interface)
- Create: `src/main/java/dev/packsage/client/recipe/NativeRecipeCardSource.java`
- Create: `src/main/java/dev/packsage/client/recipe/SlotStacks.java`
- Create: `src/main/resources/assets/packsage/textures/gui/sprites/jei/`. Copy each sprite listed in the research document from the JEI jar, keeping the file names. The nine-slice borders are set in code (16 px corners, tiled edges), as the research document describes.
- Create: `src/main/resources/assets/packsage/textures/gui/sprites/jei/LICENSE-JEI.txt`, JEI's MIT license text, copied from `LICENSE.txt` in the JEI GitHub repository at tag `v19.27.0` (the jar has no license file).
- Test: `src/test/java/dev/packsage/client/recipe/SlotStacksTest.java`

**Interfaces:**
- Consumes: `RecipeCardModel` (Task 3).
- Produces:
  - `interface RecipeCard { int width(); int height(); void draw(GuiGraphics graphics, int x, int y, int mouseX, int mouseY); Optional<ItemStack> stackAt(int x, int y, double mouseX, double mouseY); }`. The `height()` value includes the caption line, and `draw` draws the caption.
  - `interface RecipeCardSource { Optional<RecipeCard> card(RecipeCardModel model); }`
  - `NativeRecipeCardSource implements RecipeCardSource`. It is the only implementation in this plan.
  - `static int SlotStacks.cycleIndex(long millis, int size)` returns `(int) ((millis / CYCLE_MS) % size)`, with `CYCLE_MS = 1000` (JEI cycles every 20 client ticks). The card keeps its own cycle clock, starting at a random offset; all of a card's slots share it, and it stops advancing while Shift is held.
  - `static List<ItemStack> SlotStacks.resolve(RecipeCardModel.Stack stack, HolderLookup.Provider registries)`:
    - **Items:** the item ID plus a component patch decoded with `DataComponentPatch.CODEC` through `registries.createSerializationContext(JsonOps.INSTANCE)`.
    - **Tags:** the members of the item tag.
    - **Missing items:** a single `Items.BARRIER` stack marked by the custom name `Missing: <identity>`.
    - **Custom ingredients:** an empty list, which the card draws as "?".
- Draw order:
  1. the panel background, as the research document's nine-slice
  2. the slot backgrounds
  3. the arrows and icons
  4. `renderItem`, then `renderItemDecorations`
  5. the texts
  6. the hover highlight, which is the vanilla `0x80FFFFFF` fill over the hovered slot's 16×16 area
  7. the caption, in `ModernUi.MUTED` with `ModernUi.FONT`, 4 px under the panel

- [ ] **Step 1: Write the failing tests.**
  - `cycleIndexAdvancesOncePerInterval`
  - `cycleIndexHandlesSingleStack`
  - `missingItemResolvesToBarrierMarker` (Review Focus 3): uses an identity that is not in the registry. Use the test registry setup already used by `NativeRecipeReaderTest`.
- [ ] **Step 2: Run the tests.** They should fail.
- [ ] **Step 3: Implement** `SlotStacks`, the interfaces, `NativeRecipeCardSource`, and the bundled sprites.
- [ ] **Step 4: Run the tests.** They should pass. Then run the full build.
- [ ] **Step 5: Commit.** `[Add] draw native recipe cards with JEI sprites and vanilla item rendering`

### Task 7: Chat layout, chips, cards and the floating panel (Wave C)

**Files:**
- Modify: `src/main/java/dev/packsage/client/AssistantScreen.java`
  - `prepareTurn` (843): pass the turn's verbosity and `ScreenText.recipeCards(turn)`, and cache the resolved `RecipeCard`s with the prepared turn.
  - `messageLayout`: add block rows and the 18 px chip line height.
  - `MessageRun` (945): add `RecipeCard card`, `int height`.
  - `drawInlineLines` (918): draw cards and chips, and record hit boxes.
  - Hover handling (around 1345): add item tooltips, result chip panels and `PinnedCardState`.
- Test: `src/test/java/dev/packsage/client/AssistantScreenStateTest.java`

**Interfaces:**
- Consumes: Tasks 3–6.
- Produces:
  - `static int AssistantScreen.lineHeight(ScreenText.InlineLine line)`, which returns 18 when the line holds an item or result chip, the card height plus 8 for card lines, and `ModernUi.MESSAGE_LINE_HEIGHT` otherwise.
  - Item hover draws `graphics.renderTooltip(font, stack, mouseX, mouseY)`.
  - The result chip panel is a `ModernUi.surface(... ModernUi.SURFACE)` with 8 px padding around the card, placed below the chip and flipped above it when it would leave the screen.
  - Cards wider than the bubble's text width render the line's fallback source chip instead.

- [ ] **Step 1: Write the failing tests.**
  - `chipLinesAreEighteenPixels`
  - `plainLinesStayTwelvePixels`
  - `cardLineHeightIsCardPlusPadding`
  - `cardWiderThanBubbleFallsBackToSourceChip` (Review Focus 5)
  - `legacyTurnLayoutUnchanged`: the layout of a recorded legacy answer is identical before and after.
  - `copyTextUsesLabelsForCardsAndChips`: copying a message gives a card as its citation label and an item chip as its label.
- [ ] **Step 2: Run the tests.** They should fail.
- [ ] **Step 3: Implement** layout, drawing and hover, with culling identical to text runs.
- [ ] **Step 4: Run the tests.** They should pass. Then run the full build and install it with `powershell -ExecutionPolicy Bypass -File scripts/build.ps1` once `Get-Process javaw` shows that the game is closed. Verify that the installed JAR's hash matches `build/libs`.
- [ ] **Step 5: Commit.** `[Add] show recipe cards, item chips and pinnable result chips in the chat`

### Task 8: Prompt rules for cards and chips (8a in Wave B, 8b in Wave D)

**Files:**
- Modify: `src/main/java/dev/packsage/model/GroundingInstructions.java`
- Modify: `src/main/java/dev/packsage/chat/PackSageSettings.java` (the `Verbosity` instruction text)
- Create: `build/prompt-candidates/candidate-14.txt`
- Test: `src/test/java/dev/packsage/model/GroundingInstructionsTest.java` (point it at `candidate-14.txt`)

The rules to integrate follow the "Assistant prompt changes" rules in `AGENTS.md`. Integrate them into the existing citation and answer sections; do not append them as a new block.

- **Normal and Detailed.** To show a recipe, cite its record on a line of its own. Never write a crafting grid or slot layout in text.
- **Concise.** Cite the result recipe inline, and mark ingredient names as `[Name](item:<registry id>)`, using item IDs that appear in tool results only.
- **All verbosities.** Never invent item IDs.

- [ ] **8a Step 1:** Draft `candidate-14.txt` as the whole prompt, and update the test to compare against it. Commit with `[Chg] draft prompt candidate 14 for recipe cards and item chips`.
- [ ] **8b Step 2:** Iterate live by middle-clicking the settings gear, with the game kept open. For each verbosity, ask:
  - "how do I make a beacon?"
  - "how do I smelt glass?"
  - "how do I brew a potion of swiftness?"

  Inspect the calls, the replies and the rendered cards and chips. Record failures separately in `docs/validation/2026-10-27-recipe-cards.md`.
- [ ] **8b Step 3:** Promote the passing prompt to the code default. Clear the developer override and verify the installed default. Commit with `[Chg] promote prompt candidate 14 with recipe card rules`.

### Task 9: Live acceptance and docs (Wave D)

**Files:**
- Create: `docs/testing/2026-10-27-recipe-cards.md` (player-facing checks)
- Modify: `docs/validation/2026-10-27-recipe-cards.md`
- Modify: `docs/roadmap/2026-10-13-todo.md`, `docs/roadmap/2026-10-21-adapter-feature-todo.md`, `CHANGELOG.md`

Steps:

- [ ] **Step 1: Compare against JEI with computer use.** Install JEI 19.27.0.340 temporarily for the comparison only. For each category, screenshot a native card and JEI's screen for the same recipe. The comparison passes when the only differences are the caption and JEI's transfer and bookmark buttons. Then remove JEI again.
- [ ] **Step 2: Check the interactions.**
  - Slot tooltips, including advanced tooltips (F3+H)
  - Tag cycling
  - The barrier slot for a missing item
  - The "?" slot for a custom ingredient
  - The Concise result chip on hover, with Shift pinned and slot tooltips, and released
  - A narrow window falling back to source chips
- [ ] **Step 3: Recheck chat opening timings** with the real open key, against the 2026-10-26 recheck.
- [ ] **Step 4: Record and commit.** Record the results, and update the roadmaps and the changelog. Commit with `[Add] record recipe card live acceptance`.
