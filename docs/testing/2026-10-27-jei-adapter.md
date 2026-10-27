# JEI adapter live checks

Use the [JEI adapter specification](../specs/2026-10-27-jei-adapter.md) and [implementation plan](../plans/2026-10-27-jei-adapter.md). Record versions, installed JAR hash, prompt/model state, request IDs, actual tool calls and replies in the [validation record](../validation/2026-10-27-jei-adapter.md). Use a disposable singleplayer world and the [local debug bridge](2026-10-25-debug-bridge.md) for chats and evidence; inspect the actual JEI and chat UI for visual and input checks.

## JEI 19.27

1. With the installed default prompt and no override, ask “How do I craft a torch?” Verify one `search_recipe` reads JEI and a JEI-drawn card appears.
2. Ask “How do I make a Potion of Strength?” Verify the first recipe call returns the JEI brewing variant and the answer gives the relevant transformations and cards.
3. Ask “How do I make gold powder?” Check the Natural Altar record, assumed consumption and output, equipment and altar card. If the first reply omits the altar route, ask one directed follow-up in the same chat and record that classification.
4. Ask “What uses gold powder?” Verify `inputItem` and JEI results.
5. Plan eight of a Nature's Aura altar output. Verify assumed steps, equipment needed once, and any `possibleExtras` are listed without being counted as guaranteed.
6. Over card slots and chips, check R, U, A, left and right clicks. R and left click open recipes, U and right click on card slots open uses, and A has no visible effect on this JEI version. Check JEI slot tooltips, category catalysts and cycling. Check card layout at full size, at the 0.75 scale, and below the minimum width where the caption, chips and Open in JEI link must remain; hover a scaled slot.
7. Inspect `request_built`: `bookmark` must not be offered. Inspect the JEI row in the adapter popup.
8. Save a recipe link and an advancing cursor. Reload resources with F3+T, verify the old cursor is rejected and a fresh search works. Restart Minecraft and reopen the saved recipe link to the same recipe.
9. Quit Minecraft, temporarily move the JEI JAR out of `mods`, and repeat the torch and gold-powder uses questions. Verify native results and native cards without a JEI-unavailable note. Quit and immediately restore JEI.

## JEI 19.64

Update the instance to JEI 19.64, retaining the old JAR outside `mods`. Ask to bookmark gold powder and verify it appears in JEI. Ask a question where bookmarking is useful without requesting an add: the assistant may offer it but must not add it. Ask whether gold powder is bookmarked, then ask to remove it in the same chat. Verify the tool's check and remove outcomes and the list. Press A over a card item and verify the JEI list toggles; restore the initial list.

## ATM10 spot check

With JEI 19.64 and the same PackSage build, inspect one Create, one Mekanism and one Immersive Engineering recipe. Compare returned slots, fluids and catalysts with the JEI-drawn card and tooltip. Plan one route through an assumed-consumption machine, checking equipment, unexpanded needs and any possible extras. Restore any temporary debug-bridge configuration after the run.

## Planner-fix and persistent-link recheck

With the installed default prompt and its developer override cleared, plan eight andesite alloy in ATM10. Check the Create mixing plan route, following its `nextCall` when necessary; one directed same-chat follow-up may supply a missing route. Record the planner-call and whole-reply times. Repeat a depth-1 iron-nugget plan and record its bounds and times. In Packsage, repeat the eight-Gold-Powder altar plan and check that genuinely blank vanilla crafting positions do not produce `empty-input-slot`.

Plan a real fluid route and a returned-container route. Check unexpanded mB and contents, alternative grouping, component identities and equipment needed once. If the chosen routes have no component-bearing fluid or contents needs, record the observed limits rather than inferring that none exist in the pack. Check a plan with multiple category machines, and accept a relationship only with evidence or an explicit uncertainty. Ask for the native source while JEI is present. Open a mod-machine plan link, restart Minecraft and reopen it, then try the foreign-world read-only conversation. Repeat torch, gold-powder uses and bookmark add/remove. Restore any temporary settings and close the test instances. The [validation record](../validation/2026-10-27-jei-adapter.md) separates passing cases from open checks.
