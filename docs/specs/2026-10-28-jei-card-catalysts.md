# JEI card catalysts

Date: 2026-10-28. Extends the [JEI adapter](2026-10-27-jei-adapter.md).

## Problem

JEI shows the machines for a recipe category in a column at the left of its recipe screen: the Natural Altar and its catalyst, a Create mixer and basin, or a furnace. A JEI-drawn chat card uses only the recipe layout drawable, which does not include that column. A player reading a mod machine recipe in chat therefore cannot see which machine or ritual block it needs. The record already carries this data as the category-catalyst group, and the model can name the machines, but the card leaves them out.

## Behavior

- **Catalyst strip.** A JEI-drawn card draws the category's catalysts in a vertical strip on its left edge, styled like JEI's own side panel. The catalysts come from `createRecipeCatalystLookup(recipeType)`, in JEI's order. Each icon is drawn by JEI's ingredient renderer, so non-item catalysts render too. The strip is always one 18-pixel column, so the card width never depends on how many machines a category has. It shows as many catalysts as fit in the card's height. When more exist, the last visible spot becomes a "+N" overflow tile. Hovering it lists the hidden machines' names, and clicking it opens the recipe in JEI, where JEI's own side panel shows every machine. A card shorter than two slots still shows one catalyst plus the overflow tile, and the strip may then extend below the card.
- **Vanilla crafting is skipped.** Cards in the `minecraft:crafting` category draw no strip, because a crafting table on nearly every card adds nothing. Every other category with catalysts draws one, including furnace, smoking, blasting, campfire, stonecutting, smithing and brewing. A category with no catalysts draws no strip and keeps its current width.
- **Interaction.** Catalyst icons behave like card slots:
  - Hover shows JEI's tooltip for the ingredient, drawn above chat the way slot tooltips are.
  - Left-click and R open the item's recipes.
  - Right-click and U open its uses.
  - A toggles its bookmark where bookmarks are supported.
  - Hovering highlights the icon the way a slot is highlighted.
  - A non-item catalyst gets its hover tooltip only.
- **No relation claim.** The strip shows the machines exactly as JEI lists them. It never says whether they are alternatives or used together, which matches the evidence: "alternatives or used together; not determined".
- **Width and fallback.**
  - The strip counts toward the card's width for the full-size check and the 0.75 minimum scale. The whole card, strip included, scales together.
  - When the card falls back, the catalysts become small machine chips after the result chip: at most three, then a "+N" chip with the same hover and click as the overflow tile. Machine chips use a distinct style and are skipped for `minecraft:crafting`, like the strip. A machine chip behaves like an item chip for clicks, R/U/A and tooltip.
  - The full-size retry on hover shows the strip.
- **Native cards.** Native cards, drawn without JEI, are unchanged.
- **Read-only chats.** In a read-only chat from another world, catalyst clicks and R/U/A do nothing, as card slots already do.

## Constraints

- `mezz.jei` stays inside `dev.packsage.jei`. Outside `dev.packsage.jei.bookmark`, only JEI 19.27 API is used: the catalyst lookup, `IIngredientManager`, `IIngredientRenderer` and `ITypedIngredient`. `JeiApiBoundaryTest` enforces this.
- Catalyst lookups are cached with the card layout and invalidated with the JEI runtime generation, like the layout.
- Neither the model, the prompt nor the evidence changes.

## Acceptance

Automated:
- Strip geometry: one column, the visible count by card height, the overflow tile and its count, and the short-card minimum.
- No strip for `minecraft:crafting` or for categories without catalysts.
- Scale and fallback decisions that include the strip width.
- Hit-testing of strip icons, including when the card is scaled.
- Machine chips in the fallback: at most three plus "+N", skipped for crafting.

Live, Packsage instance (JEI 19.64):
- The Nature's Aura altar card shows the altar and its catalyst. Hover, clicks and R/U/A work on them.
- A vanilla crafting card shows no strip, and a furnace card shows the furnace.
- A narrow window gives the scaled card with its strip, then the fallback with machine chips.

Live, ATM10:
- The Create mixing card shows the mixer and basin.
- One Mekanism or Immersive Engineering card shows its machine.
- A furnace (smelting) card, which has many furnaces in ATM10, shows the overflow tile. Hovering it lists the rest, and clicking it opens JEI.
