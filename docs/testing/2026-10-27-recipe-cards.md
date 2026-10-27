# Recipe cards: player checks

Date: 2026-10-27. Results: [live acceptance](../validation/2026-10-27-recipe-cards.md). Requirements: [recipe cards](../specs/2026-10-27-recipe-cards.md).

Use the Packsage CurseForge instance and the existing PackSage Adapter Filter Live Test save. For comparison, JEI 19.27.0.340 must be installed. Open PackSage with P. Change Settings > Answer detail before starting a new chat; an existing answer keeps its original detail setting.

## Recipe appearance

With Normal selected, request each recipe below. Compare its card with the same recipe in JEI: open the inventory, search for the output, hover it and press R. Keep screenshots of both views.

| Recipe | Check |
|---|---|
| Beacon | Full 3 by 3 grid, arrow and output |
| Crafting table | Top-left 2 by 2 ingredients within the full grid |
| Oak planks from an oak log | Shapeless marker, centered ingredient and output count |
| Glass in a furnace | Input, empty fuel slot, flame, arrow, output, `10s` and `0.1 XP` |
| Iron ingot in a blast furnace | Same layout, with the recipe's time and experience |
| Cooked beef in a smoker | Same layout, with the recipe's time and experience |
| Cooked beef on a campfire | No fuel slot or XP text; shorter panel |
| Stone bricks in a stonecutter | One input, arrow and output |
| Netherite sword | Template, diamond sword, netherite ingot and result |
| Sentry armor trim | Template, armor and trim material; unknown native result is `?` |
| Potion of Swiftness | Reagent, three input bottles, output, heat bar, bubbles and vertical arrow |

Slot positions, item sizes, panel art and text must match JEI. PackSage's caption and JEI's transfer/bookmark buttons differ by design. Native brewing omits JEI's Steps text because its evidence has no step count. Native trim evidence can have an unknown output; compare that limit separately from the slot layout.

## Hover and history

- Hover input and output slots. Their ordinary item tooltips must appear. Toggle F3+H and repeat: advanced item details must appear too.
- Use a recipe accepting any planks. Ingredients should cycle together about once per second. Hold Shift for three seconds: they must freeze, then resume without skipping on release.
- If a saved recipe refers to a removed item, its slot must show a barrier and `Missing: <identity>` on hover. A custom undecoded ingredient must show `?` and its recorded description. Do not edit real saved chats just to fabricate these cases.
- With Concise selected, request a beacon recipe. Ingredient names should be item chips and the recipe should be one inline result chip. Hover the result to show the floating card. Hold Shift, move into its slots and inspect their tooltips. Release outside both chip and panel: it must close. Scrolling must close it. Clicking the floating panel must not activate a control beneath it.
- Make the game window narrow enough that the native card cannot fit. Its citation must become a source chip without clipping or shrinking the items. Restore the original window size afterwards.
- Reopen an answer saved before this feature. It must retain its original source chips and text without adding recipe cards or item chips. Changing Answer detail must not reshape old answers.

## Assistant wording and opening speed

For each of Concise, Normal and Detailed, start a fresh chat for each exact question:

- `how do I make a beacon?`
- `how do I smelt glass?`
- `how do I brew a potion of swiftness?`

Normal and Detailed should show the recipes as cards without text grids. Concise should name each result and ingredient once, using item chips and an inline recipe result chip. Potion variants must retain their evidence citation. All item IDs must come from returned evidence. Any useful supported navigation must remain available.

With a completed recipe answer selected, close the chat and reopen it using P five times. The test agent records `PackSage timing` open-request and first-frame lines and compares them with the previous approximately 11–50 ms range. A bridge response alone does not measure the first frame.

Finish with the original Answer detail setting restored, the developer prompt override cleared, and Minecraft running in the test world with chat and settings closed.
