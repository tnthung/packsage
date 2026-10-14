# Capability reader alignment validation

Date: 2026-10-14. Status: shared-reader live checks passed; JEI navigation follow-up in progress.

## Build and automated checks

- Implemented the [alignment specification](../specs/2026-10-14-capability-reader-alignment.md) through the [implementation plan](../plans/2026-10-14-capability-reader-alignment.md).
- Written changes were committed separately: inventory reader `7da808e`, storage/model wiring `feec82b`, compile correction `5ab67c9`, storage regressions `1d1e6f1`, grounding expectation correction `bc80257`, serializer regression `e3cc024`.
- Java 21.0.12.1 with Gradle 8.11.1: final full `test build` passed after the opaque-cursor correction: 411 passed, four existing skips, zero failures/errors. The updated inventory serializer regression also passed in a focused run.
- Scope includes vanilla player inventory/equipment, selected empty hand locations, scoped item references, name/ID/tag/tooltip search, direct vanilla container/bundle contents and bounded paging. Curios, mod-specific storage, wireless-network readers and closed-container server reads remain unsupported.
- Independent code review found no demonstrated runtime defect. It identified missing live empty-hand/extraction evidence and a serializer-budget regression gap; the fixture probe and the serializer regression below address the covered cases. Arbitrary mod tooltip failure behavior remains bounded by explicit partial coverage, not universal mod compatibility.

## Installed build

Initial live candidate SHA-256: `B256227722ED71543C29B1220C29C71D861279FFA62508A429D8C2541F8D4320`.

Corrected candidate SHA-256: `BC4CAD800D1305BEE6135BE6ED3146C8800A68561811E09130FCC64B74007B52`. After saving/exiting the fixture, copied this JAR into all three closed profiles and verified matching hashes.

Copied `build/libs/packsage-0.1.0.jar` to `mods/packsage.jar` in all three closed CurseForge profiles and verified matching hashes. ATM10 was observed at its title screen and exited normally. Its original `OoO` save and existing verified backup were left untouched; subsequent ATM10 tests use `OoO - PackSage Trial`.

| Profile | Minecraft / NeoForge | Live status |
| --- | --- | --- |
| Packsage minimal fixture | 1.21.1 / 21.1.202 | Inventory/search/container and existing adapter probes passed; corrected paging probe passed. |
| FTB Evolution 1.45.0 | 1.21.1 / 21.1.250 | Shared inventory/search/item-target checks passed; existing retrieval limits recorded below. |
| ATM10 8.2 | 1.21.1 / 21.1.251 | Shared inventory/search/arbitrary-slot bundle and existing retrieval checks passed; JEI navigation failure recorded below. |

## Minimal fixture observations

World: existing creative `New World`. Installed JEI 19.27.0.340, FTB Quests 2101.1.36 and Patchouli 92. The deterministic recipe and guide overrides remain active.

Replay: profile-local `packsage/New_World-8a315c270917e038/5962c299-64c5-4e38-94a7-bf26f667b874-2fe0b96ff7cf55a8.jsonl`.

- `ALIGN_MIN_A` completed at the live client. `read_inventory` returned complete coverage, one occupied coal stack (64, hotbar 0), and explicit empty mainhand (hotbar 1) and offhand (offhand 0) locations with usable references.
- Four separate `search_inventory` calls using name, ID, tag and tooltip fields each found the same coal stack with complete coverage. The tag result included `#minecraft:coals`; tooltip text included `Coal`. The five initial tool calls were issued before their results, verifying a parallel model-call batch.
- `read_storage(target=item)` on the selected empty-mainhand reference returned unavailable/unknown item storage, rather than claiming an empty supported container.
- For the positive container fixture, used the creative world's setup command to place a shulker box in `inventory.0` with seven diamonds at component slot 3. Both hands remained empty. This only changed the fixture world.
- `ALIGN_MIN_B` read that main slot 0 through a fresh inventory reference. Storage reported complete contents: seven diamonds, owner count one, main slot 0, component slot 3, with no additional page.
- The same probe retrieved the loaded Nature's Aura fixture marker through Patchouli, refreshed all three fixture quests through `read_progress`, and retrieved the native torch override plus a complete navigable JEI reference. Native recipe semantics remained explicitly partial.
- One unnecessary model call used storage payload `nextOffset=1` as cursor `1` despite `nextCursor=null`; the reader correctly rejected it as unknown. Committed the regression as `d9ff416`, then removed that misleading internal offset and clarified continuation instructions in `542430d`.
- `ALIGN_MIN_C`, sent at 02:32:34 UTC on the corrected JAR, completed successfully. Two `read_inventory(limit=1)` calls enumerated coal and the shulker with the same capture timestamp, following the returned opaque cursor exactly once. Selected mainhand/offhand locations remained explicitly empty. A fresh main-slot reference returned complete shulker storage with seven diamonds, no `nextOffset` and `nextCursor=null`; no invalid continuation call followed. A name search independently found the shulker with complete coverage.

## FTB Evolution observations

World: existing survival `PackSage Evolution Trial`. The client loaded 566 mods; installed JEI 19.57.0.450, FTB Quests 2101.1.36 and Patchouli 93. Used GPT-6-Luna / medium, Search off, Screenshot off, and paused gameplay. No fixture content or inventory items were added.

Replay: profile-local `packsage/PackSage_Evolution_Trial-2dc1b88ff37c5763/e62e8301-de8c-4c5e-8c43-974df56bbbd1-c30c303c364bc06f.jsonl`.

- `ALIGN_EVO_A`, sent at 02:45:53 UTC, completed. `read_inventory(limit=2)` followed two opaque cursors and returned five occupied hotbar slots with complete coverage: Tall White Allium, Wheat Seeds, Old Root, Hag Taper and two Flax Seeds. Mainhand identified hotbar 0; offhand was explicitly empty.
- Four `search_inventory` calls for `seed`, selecting name, ID, tag and tooltip independently, each returned Wheat Seeds (one) and Actually Additions Flax Seeds (two), with complete coverage. Tooltip capture included both display names and their mod names.
- Item storage at a fresh selected-slot reference identified the Tall White Allium owner and returned unavailable/unknown contents, not an empty container. This checks slot dispatch in the pack; positive native component storage was established in the minimal fixture.
- Existing reads retrieved the Getting Started chapter and current progress for Punch A Tree and Wooden Pickaxe (both zero/incomplete), a complete PneumaticCraft Getting Started entry, and a complete navigable JEI crafting record for four torches. Quest smart-filter/reward semantics and the wider recipe result remained partial; Create's parallel automatic-crafting record remained opaque. The unrelated Genetics Resequenced book reload failure still made guide search partial.
- Ars Nouveau `general.sylphManaCost` explicitly returned saved and loaded values of 250. Config catalog coverage remained partial with the bounded-extraction diagnostic; this value does not establish complete catalog capture.
- The combined question repeated some inventory/search reads and made two rejected recipe calls: `outputItem` with the native provider, then a JEI cursor reused with changed filters. These did not affect successful inventory/search/storage results. Efficient model call selection and full real-pack extraction are not established by this check. A Recipe click did not visibly change screens in this pass, so JEI page opening is not claimed.

## ATM10 observations

World: existing backed-up `OoO - PackSage Trial`, selected by its trial folder label in the world list. The original `OoO` was not opened. The client loaded 535 mods. Used GPT-6-Luna / medium, Search off, Screenshot off, and paused gameplay. No items were moved, added or removed.

Replay: profile-local `packsage/OoO_-_PackSage_Trial-814a1737fe55fba5/9623b8a6-ad4a-49ba-9544-6557c2bf905a-f05668e4ac0a28f8.jsonl`.

- `ALIGN_ATM_A`, sent at 03:02:01 UTC, completed. Four `read_inventory(limit=8)` pages followed the returned opaque cursors and retained the original 03:02:04 UTC observation. Complete coverage returned seven occupied hotbar slots, 20 main slots and four armor slots; the main stacks totalled 285 items. The four equipped pieces were Unobtainium boots, leggings, chestplate and helmet. Mainhand identified the sword at hotbar 0; offhand was explicitly empty.
- All four `pickaxe` field searches were complete: name, ID and tooltip each found the Unobtainium Pickaxe, while tag search additionally found the Inferium Paxel through `#minecraft:pickaxes`.
- `read_storage(target=item)` used the inventory observation's main-slot-2 reference to read the existing bundle without holding it. The selected item reported supported bundle storage, complete direct contents, zero entries and no continuation cursor. This distinguishes observed empty native storage from unsupported formats while an unrelated sword remains selected.
- Separate item reads correctly identified the sword at hotbar 0 and Tier 3 Pocket Storage at hotbar 8, returning unavailable/unknown contents for both. No internal `nextOffset` appeared. Pocket Storage integration remains pending; no empty-container claim was made.
- `ALIGN_ATM_B`, sent at 03:07:01 UTC, completed with six tool calls and no rejected calls. It discovered Basic Tools quests and refreshed Wooden Pickaxe (zero/incomplete) and Stone Pickaxe (one/complete), with partial quest metadata. The PneumaticCraft Getting Started entry was complete. The canonical JEI crafting record returned four torches with complete viewer coverage; the additional Create record was opaque. Patchouli `useShiftForQuickLookup` explicitly returned saved and loaded values of false under partial config catalog coverage.
- Clicking Config opened read-only source details with the same recorded values and provenance. Clicking Recipe failed with `Navigation target is no longer available`. Read-only source review identified the earlier navigation refresh's exact-GET request conflicting with JEI's snapshot-version-specific handles, rather than an inventory-reader regression. A [separate follow-up](../plans/2026-10-14-jei-navigation-refresh.md) preserves exact recipe binding while refreshing its current projection.

## Remaining acceptance

- Complete the separate JEI navigation correction and save/quit the trial world.
- No multiplayer/LAN, Curios, mod storage/network integration, or universal tooltip-provider compatibility is established by these checks.
