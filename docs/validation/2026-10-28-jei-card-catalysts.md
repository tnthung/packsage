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

## Recheck after review fixes, 2026-10-28

The recheck ran on the build of `56906a22` plus `261d1678`, on the hidden desktop in the Packsage `New World (1)` world with JEI 19.64. The developer override was cleared.

- **Strip border and height PASS.** Request `ebcf6c56-b149-42f2-a3ac-2d2c422d8b81` drew the altar strip with a top, left and bottom border. Its height snaps to whole slots and leaves the remainder below uncovered ([zoomed image](assets/2026-10-28-jei-catalysts-border.png)). The strip's top edge sits about 3 pixels above the card's top border.
- **Machine uses FIXED.** U on the altar strip icon and right-click on the inline Natural Altar chip both left chat unchanged, while JEI's own U on the altar opened nine pages of Natural Altar Infusion recipes. The viewer sent only an INPUT focus, but JEI's U also sends CATALYST. `261d1678` sends both. Afterwards, U on the strip altar and right-click on the altar chip both opened the nine-page altar view. The earlier furnace U pass only matched the furnace's ingredient uses.
- **Strip hover PASS.** Hovering the strip altar showed its JEI tooltip.
- **Still open:** the expanded panel and the review's clipped-strip and over-card cases need a category with more than one column of machines. No Packsage category has one, so these move to ATM10.

## Strip corner correction, 2026-10-28

The annotated player screenshot and the earlier border image showed a notch at the card join and square exposed corners. The correction uses a named 4-pixel inset, calculates whole rows from the reduced content height, and applies the same placement to collapsed and expanded drawing, highlights and hit-testing. The background, border and slot sprite leave the outer corner pixels clear; the expanded panel rounds both outer sides.

Test-first evidence: the focused pair ran 24 tests, with four expected failures for the missing inset, reduced row count and transparent corners. Those checks passed after implementation. Geometry covers scales 1 and 0.75, left and over-card expansion, and the clear area above the strip. The existing fill-recording seam checks corner transparency and border continuity in both directions.

Visual acceptance remains pending with the controller. Minecraft was not launched for this correction.

Full verification: `JAVA_HOME=C:\Users\hung1\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2 ./gradlew test` passed. `test`: 1539 tests, 5 skipped, 0 failures and 0 errors; `testWithoutJei`: 1428 tests, 5 skipped, 0 failures and 0 errors. `git diff --check` passed. An earlier overlapping Gradle invocation caused a test-output file lock; the final full run completed successfully after it finished.
- **Corners and inset PASS (`4f916d7b`).** The strip starts 4 px below the card's top, clear of the card's rounded corner. Its exposed top-left and bottom-left corners are rounded, and the side that meets the card is square. U on the moved altar icon still opened JEI ([zoomed image](assets/2026-10-28-jei-catalysts-rounded.png)).
- **Card-matching border PASS (`90de313d`).** The strip has the card's 2 px border (outer #999999, inner #D8D8D8 top-left and #B3B3B3 bottom) on its exposed sides, with rounded outer corners and a square, unbordered joint. U on the altar icon still opened JEI ([zoomed image](assets/2026-10-28-jei-catalysts-card-border.png)).
