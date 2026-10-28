# JEI card catalyst validation

Date: 2026-10-28. These checks follow the [test procedure](../testing/2026-10-28-jei-card-catalysts.md) and [specification](../specs/2026-10-28-jei-card-catalysts.md). Packsage checks are partial; ATM10 acceptance remains pending.

## Setup

- Packsage instance, `New World (1)`, Minecraft 1.21.1, NeoForge 21.1.259, JEI 19.64.0.454 and Nature's Aura 41.10. The client ran on a hidden desktop with bridge screenshots and posted window input.
- Built commit `806222f285e7c254b44e82cfcc8a84d419875224` with Adoptium Java 21. `./gradlew build` passed; `test` and `testWithoutJei` were up to date. Installed and build JAR SHA-256: `e9e722c40329dc5457e12b135b391cf0e135cd1a6ea485728b9543468e7bfcb6`.
- Installed default prompt, override cleared; GPT-6.1-Sol medium. The client area was restored to 1024×768 and the two tested bookmarks were toggled back.

## Passing observations

- **Altar category machine:** request `05fd00da-3991-4ce5-9d0e-770c3d9c0f75`, conversation `74087349-5694-4e0d-8f79-1467bf59a719`. The JEI altar card showed the Natural Altar in its strip, matching [JEI's own category side panel](assets/2026-10-28-jei-catalysts-jei-altar.png). Hover showed its tooltip and highlight. Left-click and R opened the altar's Ritual of the Forest recipe; A added and then removed the altar bookmark, observed in JEI. See the [altar card](assets/2026-10-28-jei-catalysts-altar.png).
- **Vanilla cards:** request `1f281474-a887-45ca-8820-55738b5caace`, conversation `c30311e6-cf16-4428-bd4f-e0959bc248d8`. Two `search_recipe` calls returned crafting and smelting cards. The [torch card has no strip and the glass card shows a furnace](assets/2026-10-28-jei-catalysts-vanilla.png). Furnace strip right-click and U opened crafting uses; A added and then removed its JEI bookmark.
- **Narrow card:** at a 264×768 client area the [altar card scaled with its strip](assets/2026-10-28-jei-catalysts-scaled.png). At 244×768 it became [a Gold Powder result chip followed by a differently styled Natural Altar machine chip](assets/2026-10-28-jei-catalysts-fallback.png). Hover showed the altar tooltip; left-click and R opened its recipe. Only one category machine was present, so this did not exercise fallback `+N`.
- **Recipe-slot regression:** hovering the altar's Gold Leaf slot and the torch card's coal slot showed their tooltips and highlights. On the torch card, R opened coal recipes and U opened coal uses.

## Open or unmatched checks

- **Brief's two-icon altar expectation not met:** the altar strip contains only Natural Altar. The Crushing Catalyst is drawn inside the recipe, not in JEI's category side panel. The returned altar record identifies the Crushing Catalyst as `slot-2` and the altar as `category`; the installed Nature's Aura 41.10 JEI registration adds only the altar to this category. The strip therefore matches the specification's category lookup, but does not meet the brief's stated expectation of both icons in the strip. No code fix was made for this conflicting expectation.
- Right-click and U on the altar strip left chat visible; the test did not establish an available JEI use for that item. The same actions opened uses from the furnace strip. Machine-chip right-click/U were not verified.
- No overflowing category was found in the inspected Packsage examples. The altar and furnace each have one category machine; JEI's Ritual of the Forest side panel has two. This is not an exhaustive survey of every category. Collapsed `+N`, expanded multi-column interaction and the capped fallback tooltip remain unverified here.
- ATM10 Create mixer/basin, Mekanism or Immersive Engineering and many-furnace overflow checks remain pending. No expanded-panel screenshot was captured.
