# JEI card catalysts plan

Spec: [JEI card catalysts](../specs/2026-10-28-jei-card-catalysts.md). Branch `t3/jei-card-catalysts` from `feat/packsage-v2`.

## Task 1: Catalyst strip and machine chips

**Files:**
- Modify `src/main/java/dev/packsage/jei/JeiRecipeCardSource.java`. The `Layout` gains the category's catalysts, and `JeiCard` draws the strip, hit-tests it and offsets the drawable.
- Modify the card fallback and chip code. Find where the screen builds the narrow fallback from `RecipeCardModel` slots (`AssistantScreen`, `RecipeCardLayouts`). To give the fallback the same machines as the strip, add a seam on `RecipeCardSource`, such as `machines(model)` returning the catalyst stacks, with an empty default for native.
- Tests: `JeiRecipeCardSourceTest`, plus the fallback/chip tests next to the code you change.

Steps:
1. Write failing tests for strip geometry: width, height and wrapping. Cover the `minecraft:crafting` and no-catalyst exclusions, scale and fallback with the strip width included, and strip hit-testing at scale 1 and 0.75. Then add a failing test for fallback machine chips, skipped for crafting.
2. Implement the strip.
   - Each catalyst is an 18-pixel slot with JEI's slot background or the native card's slot look, inside a panel drawn like JEI's catalyst tab.
   - The strip is the same height as the layout and wraps into another column when it overflows. The layout is drawn to its right.
   - Render items and other ingredient types with `IIngredientManager#getIngredientRenderer(type)`.
   - `itemAt` returns the item stack for item catalysts. `hoverAt` returns the JEI tooltip lines: `IIngredientRenderer#getTooltip` with the current tooltip flag, the same way slot tooltips work.
   - Highlight the hovered catalyst like a slot.
3. Implement the machine chips in the fallback after the result chip, with a distinct style. Clicks and R/U/A reuse the item-chip path.
4. Run `./gradlew test` (it also runs `testWithoutJei`). Expected: PASS.
5. Commit `[Add] show JEI category machines on chat recipe cards`, with a CHANGELOG line under `### 2026-10-28`.

## Task 2: Live acceptance and records

Delegated to Codex gpt-6.1-terra with computer use.
- Build and install into the Packsage and ATM10 instances, then run the spec's live checks.
- Record them in `docs/validation/2026-10-28-jei-card-catalysts.md` and `docs/testing/2026-10-28-jei-card-catalysts.md`. Include screenshots of the altar, mixing and fallback cards under `docs/validation/assets/`.
- Update the roadmap entries.
- Commit `[Chg] record JEI card catalyst acceptance`.
