# JEI 19 vanilla recipe category layouts

Date: 2026-10-27.

Scope: exact layouts of JEI's vanilla recipe categories, to reproduce them as recipe cards. Target build: `jei-1.21.1-neoforge-19.27.0.340.jar` from the Packsage CurseForge profile.

Sources:

- Source: `mezz/JustEnoughItems` tag `v19.27.0` (commit `003ae0114`, 2025-12-21), `1.21.1` branch. Build 340 is somewhere between `v19.27.0` and `v19.28.0`. Between those tags the only changes to the vanilla plugin, `GuiHelper` or `RecipeLayout` were generics casts in `SmithingRecipeCategory`, an ingredient-validation flag in `GuiHelper`, and side-button wrapping in `RecipeLayout.getSideButtonArea`. None of them changes a layout constant.
- Bytecode: the installed jar, read with `javap -c -p -constants`. Every number in the category tables below was confirmed against the jar: slot coordinates, arrow and flame positions, text area sizes and colors, cook times, category sizes, slot background offsets, sprite sizes and nine-slice borders, shapeless icon trim and scale, cycle interval, border padding, bubble lengths and the brewing tick timers.
- Sprites: PNG headers and pixels read from the jar.

All coordinates are GUI pixels relative to the top-left of the recipe area (the category's `width` x `height` rectangle), unless noted otherwise. "Item" is where the 16x16 item is rendered. "Bg" is the top-left of the slot background sprite.

## Shared rules

| Rule | Value | Source |
|---|---|---|
| Standard slot background | `slot` sprite, 18x18, drawn at item position `(-1, -1)` | `RecipeSlotBuilder.setStandardSlotBackground` (`OffsetDrawable(bg, -1, -1)`) |
| Output slot background | `output_slot` sprite, 26x26, drawn at item position `(-5, -5)` | `RecipeSlotBuilder.setOutputSlotBackground` (`OffsetDrawable(bg, -5, -5)`) |
| Recipe arrow | `recipe_arrow`, 22x16, static | `Textures`, `RecipeLayout.addRecipeArrow`, `GuiHelper.getRecipeArrow` |
| Animated arrow | `recipe_arrow` with `recipe_arrow_filled` drawn over it, revealed left to right | `RecipeLayout.addAnimatedRecipeArrow` |
| Animated flame | `icons/flame_empty` with `icons/flame` drawn over it; the filled flame loses rows from the top | `RecipeLayout.addAnimatedRecipeFlame` |
| Plus sign | `recipe_plus_sign`, 13x13. Not used by any category in this document | `RecipeLayout.addRecipePlusSign` |
| Text widget defaults | Minecraft font (`lineHeight` 9), line spacing 2, color `0xFF000000`, no shadow, left/top alignment | `mezz.jei.common.gui.elements.TextWidget` |
| Text placement | `setPosition(areaX, areaY, areaW, areaH, h, v)` places the widget box at `areaX + h.getXPos(areaW, boxW)`, `areaY + v.getYPos(areaH, boxH)`. Inside the box, RIGHT gives `x = boxW - textWidth` and BOTTOM gives `y = boxH - (lines*11 - 2 - 1)`. Text wider than the box is truncated, and the full text then appears in the tooltip | `IPlaceable`, `TextWidget`, `HorizontalAlignment`, `VerticalAlignment` |
| Draw order | Recipe border background, category background (none for these categories), `category.draw`, slots (background, then item), widgets, then drawables added through `addDrawable`/`addAnimated*`/`addRecipeArrow`, decorators, and the shapeless icon last | `RecipeLayout.drawRecipe` |

### Animation timing

`mezz.jei.common.gui.elements.DrawableAnimated` and `mezz.jei.common.util.TickTimer` drive all animations from wall-clock time:

- One tick is 50 ms. The timer starts when the drawable is created.
- `value = floorDiv(((now - start) % (ticks*50)) * (max+1), ticks*50)`. If `countDown` is true, the timer returns `max - value`.

| Animation | Constructor | Effect |
|---|---|---|
| Recipe arrow (cooking) | `DrawableAnimated(filled, cookTime, LEFT, inverted=false)` → `TickTimer(cookTime, 22, countDown=true)`, `maskRight = value` | Filled width grows from 0 to 22 px over `cookTime` ticks, then restarts |
| Flame | `DrawableAnimated(flame, 300, TOP, inverted=true)` → direction BOTTOM, `TickTimer(300, 14, countDown=false)`, `maskTop = value` | The filled flame loses rows from the top over 300 ticks (15 s), then restarts |
| Brewing arrow | `createAnimatedDrawable(brewing_stand_arrow, 400, TOP, false)` → `TickTimer(400, 27, true)`, `maskBottom = value` | Fills downward from 0 to 27 px over 20 s |
| Brewing bubbles | `BrewingBubblesTickTimer`: `createTickTimer(14, 6, false)` indexes `BUBBLE_LENGTHS = {29, 23, 18, 13, 9, 5, 0}`. Direction BOTTOM, so `maskTop = length` | 7 frames of 100 ms (700 ms cycle). Visible bottom part of the 28 px sprite: 0 (mask 29 > 28), 5, 10, 15, 19, 23, 28 rows |

The first bubble frame masks 29 rows of a 28-row sprite, which gives a quad of height -1. I assume it draws nothing (degenerate/culled quad). Not confirmed in game.

### Cycling multi-item and tag slots

`mezz.jei.library.gui.ingredients.CycleTicker`:

- `TICKS_PER_UPDATE = 20` client ticks (about 1000 ms). Ticks are counted through `RecipeLayout.tick()`; this is not wall-clock time.
- Each recipe layout gets one ticker with a random start index in `[0, 100000)`. Every slot in the layout shows `list[index % list.size()]` from that shared index, so slots advance together.
- Cycling pauses while Shift is held (`Screen.hasShiftDown()`).
- On each advance, display overrides are cleared and `onDisplayedIngredientsUpdate` runs. Smithing trim uses this to recompute its output.

## Crafting (shaped and shapeless)

Classes: `mezz.jei.library.plugins.vanilla.crafting.CraftingRecipeCategory`, `CraftingCategoryExtension`, `mezz.jei.library.gui.helpers.CraftingGridHelper`.

| Element | Item / position | Bg / size | Notes |
|---|---|---|---|
| Category | — | 116 x 54 | Title `gui.jei.category.craftingTable` ("Crafting"); icon is the crafting table |
| Input grid (9 slots, role INPUT) | `(col*18 + 1, row*18 + 1)`, col and row 0..2 | standard 18x18 at `(col*18, row*18)` | All 9 slots are always created with backgrounds, including empty ones. Slot index = `row*3 + col` |
| Output (OUTPUT) | `(95, 19)` | `output_slot` 26x26 at `(90, 14)` | — |
| Arrow | `(61, 19)` | `recipe_arrow` 22x16, static | Drawn in `draw()` at y = `(54 - 16) / 2` |
| Shapeless icon | `(107, 0)` | logical 9x9 | Only for shapeless recipes (`width <= 0 || height <= 0` → `builder.setShapeless()`). Default top-right placement: `x = categoryWidth - iconWidth`, `y = 0`. Tooltip `jei.tooltip.shapeless.recipe` ("Shapeless Recipe") |
| Text | none | — | — |

The shapeless icon is drawn from `icons/shapeless_icon.png` (36x36) as `HighResolutionDrawable(scale 4)`. The pose is scaled by 1/4, so the logical size is 9x9 GUI px. The sprite is trimmed by `trim(left 1, right 2, top 1, bottom 1)` in source pixels. The drawn quad therefore covers source `x 1..35`, `y 1..35` (33 x 34 px), which is GUI `(107.25, 0.25)` to `(115.5, 8.75)`.

### Placing ingredients in the 3x3 grid

`CraftingGridHelper.getCraftingIndex(i, width, height)` maps ingredient `i` (row-major within the recipe's own `width` x `height`) to a grid index. For shapeless recipes, the size is first replaced by `getShapelessSize(n)`: 1 if n ≤ 1, 2 if n ≤ 4, otherwise 3, used for both width and height.

| Recipe size (w x h) | Grid indices used | Placement |
|---|---|---|
| 1x1 | 4 | Center |
| 1x2 | 1, 4 | Middle column, top two rows |
| 1x3 | 1, 4, 7 | Middle column |
| 2x1, 3x1 (height 1) | `i + 3` → 3, 4 (, 5) | Middle row, left-aligned |
| 2x2 | 0, 1, 3, 4 | Top-left 2x2 |
| 2x3 | 0, 1, 3, 4, 6, 7 | Left two columns |
| 3x2 | `i + 3` → 3..8 | Bottom two rows |
| 3x3 | `i` → 0..8 | Full grid |

Shaped recipes are not centered generally. Only 1-wide recipes use the middle column, and only 1-high recipes use the middle row. Empty ingredients leave their slot empty, but the slot background is still drawn.

## Smelting, blasting and smoking

Classes: `mezz.jei.library.plugins.vanilla.cooking.AbstractCookingCategory` and its subclasses `FurnaceSmeltingCategory`, `BlastingCategory` and `SmokingCategory`.

| Category | Size | Title key | Default cook time (used when the recipe's time ≤ 0) |
|---|---|---|---|
| Smelting | 82 x 54 | `gui.jei.category.smelting` ("Smelting") | 200 |
| Blasting | 82 x 54 | `gui.jei.category.blasting` ("Blasting") | 100 |
| Smoking | 82 x 54 | `gui.jei.category.smoking` ("Smoking") | 100 |

| Element | Item / position | Bg / size | Notes |
|---|---|---|---|
| Input (INPUT) | `(1, 1)` | standard at `(0, 0)` | First ingredient |
| Fuel slot (RENDER_ONLY) | `(1, 37)` | standard at `(0, 36)` | Always empty; only the background shows |
| Flame | `(1, 20)` | 14x14, animated, 300 ticks | Between the input and fuel slots |
| Arrow | `(26, 17)` | 22x16, animated over the recipe's cook time | — |
| Output (OUTPUT) | `(61, 19)` | `output_slot` at `(56, 14)` | — |
| Experience text | box `(20, 0)` size 62x10 | right-aligned, top. The text is at `x = 82 - textWidth`, `y = 0` | Only if experience > 0. Key `gui.jei.category.smelting.experience` = `"%s XP"`; the argument is a Java `float`, so it renders like `0.1 XP`, `0.35 XP` or `1.0 XP`. Color `0xFF808080`, no shadow |
| Cook-time text | box `(20, 44)` size 62x10 | right-aligned, bottom. The text is at `x = 82 - textWidth`, `y = 46` | Only if the time is > 0. Key `gui.jei.category.smelting.time.seconds` = `"%ss"`, with argument `cookTime / 20` (integer division), for example `10s` or `5s`. There is no space and no decimals. Color `0xFF808080`, no shadow |

Box width = `getWidth() - 20` (bytecode `getWidth`, `bipush 20`, `isub`). Box height = 10. The cook-time y comes from `54 - 10 + (10 - (11 - 2 - 1))` = 46.

## Campfire cooking

Class: `mezz.jei.library.plugins.vanilla.cooking.CampfireCookingCategory`.

| Element | Item / position | Bg / size | Notes |
|---|---|---|---|
| Category | — | 82 x 44 | Title `gui.jei.category.campfire` ("Campfire Cooking"); default cook time 400 |
| Input (INPUT) | `(1, 1)` | standard at `(0, 0)` | — |
| Fuel slot | none | — | — |
| Flame | `(1, 20)` | 14x14, animated, 300 ticks | Still drawn, with no slot under it |
| Arrow | `(26, 7)` | 22x16, animated over the cook time | — |
| Output (OUTPUT) | `(61, 9)` | `output_slot` at `(56, 4)` | — |
| Experience text | none | — | `createRecipeExtras` is overridden and does not call `addExperience` |
| Cook-time text | box `(20, 34)` 62x10. The text is at `x = 82 - textWidth`, `y = 36` | — | Same key, format and color as smelting |

## Stonecutting

Class: `mezz.jei.library.plugins.vanilla.stonecutting.StoneCuttingRecipeCategory`.

| Element | Item / position | Bg / size | Notes |
|---|---|---|---|
| Category | — | 82 x 34 | Title `gui.jei.category.stoneCutter` ("Stonecutting") |
| Input (INPUT) | `(1, 9)` | standard at `(0, 8)` | — |
| Arrow | `(26, 9)` | 22x16, static | — |
| Output (OUTPUT) | `(61, 9)` | `output_slot` at `(56, 4)` | — |
| Text | none | — | — |

## Smithing (transform and trim)

Classes: `mezz.jei.library.plugins.vanilla.anvil.SmithingRecipeCategory`, `SmithingCategoryExtension`, `SmithingTransformCategoryExtension`, `SmithingTrimCategoryExtension`. Transform and trim share the same layout.

| Element | Item / position | Bg / size | Notes |
|---|---|---|---|
| Category | — | 108 x 28 | Title `Blocks.SMITHING_TABLE.getName()` (`block.minecraft.smithing_table`), not a JEI key |
| Template (INPUT) | `(1, 6)` | standard at `(0, 5)` | — |
| Base (INPUT) | `(19, 6)` | standard at `(18, 5)` | Adjacent to the template, no gap |
| Addition (INPUT) | `(37, 6)` | standard at `(36, 5)` | Adjacent to the base |
| Arrow | `(61, 6)` | 22x16, static | — |
| Output (OUTPUT) | `(91, 6)` | standard 18x18 at `(90, 5)` | Uses the standard slot, not `output_slot` |
| Plus sign / text | none | — | — |

Output contents:

- Transform: for each template stack × base stack, using the first addition stack, add `assemble(input)`.
- Trim: `onDisplayedIngredientsUpdate` overrides the output with `assemble(displayed template, displayed base, displayed addition)` on each cycle. With an output focus, it overrides the base with `new ItemStack(output.getItem())` and reassembles.

## Brewing

Classes: `mezz.jei.library.plugins.vanilla.brewing.BrewingRecipeCategory` and its inner class `BrewingBubblesTickTimer`.

| Element | Position | Size / sprite | Notes |
|---|---|---|---|
| Category | — | 114 x 61 | Title `gui.jei.category.brewing` ("Brewing") |
| Background | `(0, 1)` | `brewing_stand_background` 64x60 | Drawn in `draw()`. It contains the frames of the four input slots, the bubble and arrow tracks, and the blaze-heat frame |
| Blaze heat bar | `(5, 30)` | `brewing_stand_blaze_heat` 18x4, static, always full | — |
| Bubbles | `(9, 1)` | `brewing_stand_bubbles` 11x28, animated (see above) | — |
| Arrow | `(43, 3)` | `brewing_stand_arrow` 7x27, animated downward over 400 ticks | — |
| Left potion (INPUT) | item `(1, 37)` | frame in the background at `(0, 36)` | Has no `setStandardSlotBackground`; the frame comes from the background sprite |
| Middle potion (INPUT) | item `(24, 44)` | frame at `(23, 43)` | Same |
| Right potion (INPUT) | item `(47, 37)` | frame at `(46, 36)` | Same |
| Ingredient (INPUT) | item `(24, 3)` | frame at `(23, 2)` | Same |
| Output potion (OUTPUT) | item `(81, 3)` | standard 18x18 at `(80, 2)` | Uses the standard slot, not `output_slot` |
| Steps text | `(70, 28)`, box 42x12 | left/top, color `0xFF808080`, no shadow | Key `gui.jei.category.brewing.steps` = `"Steps: %s"`. The argument is `Integer.toString(steps)`, or `"?"` when steps = `Integer.MAX_VALUE` |
| Fuel (blaze powder) slot | none | — | JEI 19.27 has no fuel slot. Only the static blaze-heat bar is drawn. Unlike the furnace categories' fuel slot, nothing marks the slot frames in the brewing background as fuel |

The three potion slots all receive the same `potionInputs` list. The slot frames were read from the PNG pixels: each is an 18x18 frame with a dark top-left edge and a light bottom-right edge, at sprite coordinates `(0,35)`, `(23,42)`, `(46,35)` and `(23,1)`. Add 1 to y for recipe coordinates.

## Recipe area background and border

Classes: `mezz.jei.library.recipes.RecipeManager.createRecipeLayoutDrawable`, `mezz.jei.library.gui.recipes.RecipeLayout`, `mezz.jei.library.gui.recipes.layout.builder.RecipeLayoutBuilder`.

| Context | Sprite | Padding | Drawn rect |
|---|---|---|---|
| Recipe in the recipes GUI (`needsRecipeBorder()`, true by default) | `single_recipe_background` nine-slice, 64x64, borders 16/16/16/16 | 4 | `(-4, -4)` size `(W + 8, H + 8)` (`getRectWithBorder`) |
| Category with `needsRecipeBorder() == false` | none (`DrawableBlank.EMPTY`) | 0 | — |
| Bookmark recipe preview tooltip | `recipe_preview_background` nine-slice, 64x64, borders 16 | 4 | `(-4, -4)` size `(W + 8, H + 8)` |
| Whole recipes window (not one recipe) | `gui_background` nine-slice, 64x64, borders 16 | `RecipesGui.borderPadding = 6` | — |

`DrawableNineSliceTexture` details:

- Corners are drawn at their native size, and edges and the middle are tiled (not stretched).
- Tiling is anchored at the bottom: tiles go upward from `y + tiledHeight`, and the partial tile is at the top.
- `middleHeight` is computed from `textureWidth`, a source quirk with no effect on these square sprites.
- Corners overlap when the drawn size is below 32 px. The smallest vanilla card is smithing at 116 x 36, so this never happens here.

The side buttons (recipe transfer "+" and bookmark) sit outside this rect. Each is 13x13 with 2 px spacing. Button 0 is at `(W + 4 + 2, H + 4 - 13)`, and later buttons stack upward. I did not check which button gets which index.

## Sprite inventory

Sprites are registered through `assets/jei/atlases/gui.json` (directory source `jei/atlas/gui`, empty prefix) and live under `assets/jei/textures/jei/atlas/gui/`. Sizes below are the PNG sizes. JEI defines its nine-slice borders in code (`Textures.createNineSliceGuiSprite(name, w, h, left, right, top, bottom)`), not in `.mcmeta`.

| Path (under `.../atlas/gui/`) | PNG px | JEI drawable size | Nine-slice L/R/T/B (code) | Used by |
|---|---|---|---|---|
| `slot.png` | 18x18 | 18x18 | — | Standard slot background |
| `output_slot.png` | 26x26 | 26x26 | — | Large output background |
| `recipe_arrow.png` | 22x16 | 22x16 | — | Arrow, empty |
| `recipe_arrow_filled.png` | 22x16 | 22x16 | — | Arrow fill |
| `recipe_plus_sign.png` | 13x13 | 13x13 | — | Plus sign (unused here) |
| `icons/flame.png` | 14x14 | 14x14 | — | Flame fill |
| `icons/flame_empty.png` | 14x14 | 14x14 | — | Flame base |
| `icons/shapeless_icon.png` | 36x36 | 9x9 logical (scale 1/4), trim 1/2/1/1 | — | Shapeless marker |
| `brewing_stand_background.png` | 64x60 | 64x60 | — | Brewing |
| `brewing_stand_blaze_heat.png` | 18x4 | 18x4 | — | Brewing |
| `brewing_stand_bubbles.png` | 11x28 | 11x28 | — | Brewing |
| `brewing_stand_arrow.png` | 7x27 | 7x27 | — | Brewing |
| `single_recipe_background.png` | 64x64 | scalable | 16/16/16/16 | Recipe card border |
| `recipe_preview_background.png` | 64x64 | scalable | 16/16/16/16 | Bookmark preview |
| `gui_background.png` | 64x64 | scalable | 16/16/16/16 | Recipes window |
| `recipe_catalyst_slot_background.png` | 18x18 | scalable | 4/4/4/4 | Catalyst column |
| `catalyst_tab.png` | 28x28 | scalable | 8/9/8/8 | Catalyst column |
| `button_enabled.png` / `button_disabled.png` / `button_highlight.png` / `button_pressed.png` / `button_pressed_highlight.png` | 20x20 | scalable | 6/6/6/6 | Side and nav buttons |
| `icons/recipe_transfer.png` | 7x7 | 7x7 | — | "+" transfer button icon |
| `icons/recipe_bookmark.png` | 9x9 | 9x9 | — | Bookmark button icon |

Other files in the folder, not used by recipe cards:

- `ingredient_list_*`, `bookmark_list_*`, `search_background`, `scrollbar_*`, `tab_*` and `recipe_options_tab`.
- The other `icons/*` sprites: 16x16 buttons, and 9x9 `arrow_next`/`arrow_previous` with trim 0/0/1/1.
- `button_pressed_highlighted.png` (20x20), which `Textures` does not reference.

There are only two `.mcmeta` files: `button_pressed.png.mcmeta` and `button_pressed_highlighted.png.mcmeta`. Both are vanilla-style `gui.scaling` `nine_slice` entries, 20x20 with a 6 px border. JEI's own drawing does not read them.

## License

JEI is MIT-licensed. The jar has no license file; `META-INF/neoforge.mods.toml` declares `license="The MIT License (MIT)"`. The full text is `LICENSE.txt` at the repository root (`The MIT License (MIT)`, `Copyright (c) 2014-2015 mezz`). Redistributing JEI sprites requires carrying that notice.

## Unconfirmed

- The exact commit for build 340 is unknown. Layout code was compared between tags `v19.27.0` and `v19.28.0`, and every constant above matches the jar bytecode.
- The first brewing-bubble frame (mask 29 on a 28 px sprite) is assumed to draw nothing; not checked in game.
- Text pixel positions assume the vanilla font (`lineHeight` 9). Resource packs that change the font change `textWidth`.
- Nothing here was checked against a live JEI screenshot. The values come from source and bytecode only.
