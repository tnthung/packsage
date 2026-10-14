# Behavior-driven Toolbox validation

Date: 2026-10-14. Status: implementation, independent source review, automated build and installed live tool checks completed. The follow-up below verifies positive opened/targeted chest reads and records an unresolved opened-menu coverage-label issue. Mount fixtures remain outside observed live coverage.

The [specification](../specs/2026-10-14-behavior-driven-tools.md) defines the contract. The [implementation plan](../plans/2026-10-14-behavior-driven-toolbox.md) and [live checklist](../testing/2026-10-14-behavior-driven-toolbox.md) track the requested work. Earlier validation documents describe older artifacts and do not establish acceptance of this migration.

## Automated checks

Portable Java 21.0.12.1 and Gradle 8.11.1 are used locally. No formatter is used.

| Check | Observed result | Status |
| --- | --- | --- |
| Bootstrap `-PpacksageBootstrapOnly=true test build --console=plain` at `d5b6fa6` | 72 tests, zero failures/errors/skips; build successful. Missing settings and grounding dependency includes were corrected before the successful run. | Passed |
| Full mod `test build --console=plain` at `ecd13b9` | 438 tests, zero failures/errors, four skips; build successful in 22 seconds. Opened-horse follow-up still needs its own check. | Passed checkpoint |
| Final full mod `test build --console=plain` at `3869ee3` | 439 tests, zero failures/errors, four skips; build successful in 29 seconds, including the opened-horse regression. | Passed |
| Final artifact identity and installation | Local full JAR SHA-256: `659226FD0C92647E016C9C554674DA86631A46F28938E639CE9710A90F6F7ABC`. Installed in ATM10 after the player resumed access; build, dist and installed hashes matched. | Passed |

Four ConversationStore filesystem-link cases were skipped in this environment; the other 435 tests passed.

Focused regressions exercise ordered adapter fallback, current reference reads, UTF-8 output bounds, inventory location/owner changes, all six registry selectors, guide-page fragments, and large quest continuations. Intermediate failing runs are development evidence; they are not reported as final acceptance.

## Independent review

The independent reviewer inspected source and runtime paths from `c8a8a8e` onward. It did not run Gradle, install an artifact or operate Minecraft.

Resolved findings include discovery provider/kind ownership, missing-field absence claims, short guide cursors, current item-location contracts, guide text spanning fragment boundaries, retained quest fragments, unknown task/reward mechanics callbacks, bounded quest titles, advancing nested positions, JEI nullable filters and bounded current recipe membership checks. The reviewer cleared the optional-adapter changes through `aeef3d3`, subject to the native API cost boundary below. The late opened-horse finding was fixed in `0ebd365`: exact native handling avoids the null menu type and client-incompatible validity check, preserves equipment/cargo sections and binds references to the current menu instance. The reviewer closed source review with no unresolved material findings. The final automated regression also verifies fresh slot reads and rejection of a replacement menu with the same container ID.

The reviewed native server policy explicitly authorizes private integrated-world owner reads. It does not reproduce protection-mod GUI hooks. Same-ID item swaps at an unchanged location and container contract remain location-based; an ID or container-contract change invalidates continuation. A pre-existing recursive planner cap concern was declined as a migration blocker because current native recipes report unknown consumption and do not enter that recursion.

JEI's public native lookup API owns focus search, visibility filtering, category sorting and plugin lookup work. PackSage bounds its own inspection of returned categories/recipes and projection extraction, including retained-reference membership checks; these caps do not bound JEI's internal work or wall-clock time. The pinned public API has no raw paged membership endpoint. Retain native focus and visibility semantics rather than clone the recipe system or claim an end-to-end work bound.

## Installed live checks

The player resumed computer access on 2026-10-14. Installed the previously tested full JAR directly from `build/libs/packsage-0.1.0.jar` into ATM10 while Minecraft was closed, copied it to `dist/packsage.jar`, updated distribution metadata and verified identical SHA-256 values. No source changed after the final automated build, so this session did not rerun the installer script or reinstall the separate minimal Packsage profile.

Used `computer-use:computer-use` to launch ATM10 8.2, select only `OoO - PackSage Trial`, operate PackSage through F8, and inspect the actual inventory and answers. Runtime: Minecraft 1.21.1, NeoForge 21.1.251, FML 4.0.44, JEI 19.57.0.446, FTB Quests 2101.1.36, Patchouli 1.21.1-93. Model/settings remained GPT-6-Luna, medium, screenshots enabled, web search explicit-only; test requests requested no web.

All seven requests completed. Saved replay `toolCall` and `toolResult` entries establish 52 actual calls covering all twelve public tools. Replay file, relative to the ATM10 instance:

`packsage/OoO_-_PackSage_Trial-814a1737fe55fba5/7bdaf0a0-3a65-4a6a-afe2-a31ae6bf17b4-242bcf23504c4ac8.jsonl`

| Request marker / request ID | Observed result |
| --- | --- |
| `TOOLBOX_LIVE_A` / `5953c3d5-b184-44f1-b691-917aa77e5d39` | Twelve-tool catalog; two player pages and four Unobtainium armor pieces matched the visible inventory. Sword item storage, absent opened menu, unsupported targeted entity and absent ridden entity returned unavailable coverage. |
| `TOOLBOX_LIVE_B` / `99bbfac2-1a90-4502-94ae-4aa9f73ed064` | Swapped the empty bundle from main slot 2 with the selected sword in hotbar 0. A fresh mainhand read returned complete empty bundle storage, and exact-ID player search found both bundles. Prior-request evidence/slot references were rejected after scope changed; no old sword body was replayed. |
| `TOOLBOX_LIVE_C` / `0c872d18-6033-454a-90fb-252a39db256b` | Item, block, enchantment, mob-effect and potion exact-ID searches returned current definitions, including a modded item. Item pagination advanced Air to Stone; mixed registry evidence reread succeeded. Restored mainhand again reported the sword with unavailable storage. Model used `tags` instead of `ids` for item-tag lookup; explicit `UNSUPPORTED_FILTER` was correct. |
| `TOOLBOX_LIVE_D` / `4dc5b959-0f65-49d0-8ba0-e908cf98116e` | Corrected item-tag exact-ID lookup found `minecraft:logs`, with bounded partial membership. FTB `Basic Tools` and Patchouli `Pressure` searches each advanced one continuation but had no matches within those windows; remained partial with further cursors. |
| `TOOLBOX_LIVE_E` / `0bbf1280-2fc9-4660-afdb-8fba5f15abec` | Unfiltered FTB enumeration returned Welcome chapter and Welcome to All The Mods 10 quest; current evidence/progress rereads succeeded with partial chapter coverage. Patchouli returned Ancient Knowledge and Wither guides and refreshed their current text. JEI native output focus returned the cattail/oil torch recipe and its current evidence. Native torch search and depth-one torch planning returned bounded partial results without records. |
| `TOOLBOX_LIVE_F` / `7f76df94-0a37-4db7-a67c-932796dd2333` | Config listing continued; search/read found `patchouli.useShiftForQuickLookup=false` in both loaded and saved values. History search/read returned the earlier JEI_NAV_ATM_A exchange as historical evidence. A newly observed bundle slot reference resolved complete empty storage within this request, followed by a successful storage `read_evidence`. |
| `TOOLBOX_LIVE_G` / `62d89132-5491-48ab-ba37-a550e064d667` | Unqualified document discovery continued across FTB records. Unqualified torch recipe discovery returned partial work-bound coverage and a cursor. Native enumeration returned `modern_industrialization:cutting_machine/stripped_wood/warped`; evidence refresh and a depth-one plan for `minecraft:stripped_warped_hyphae` returned that current recipe with unknown mechanics preserved. |

Call counts: search_inventory 14; read_evidence 6; search_registry 9; search_document 10; search_recipe 4; plan_recipe 2; read_progress 1; list_configs 2; search_config 1; search_history 1; read_config 1; read_history 1.

### Acceptance limits and restoration

- Live freshness was observed across sword -> bundle -> sword reads. The bundle tooltip independently showed `0/64`. Both stacks and the selected hotbar slot were restored before leaving; health remained full and level 57. Saved and quit the trial normally, then exited Minecraft. Original `OoO` was never opened.
- References include request scope. The attempted old-reference refresh crossed a user-request boundary and correctly failed; it is not proof of same-scope mutation refresh. Automated tests cover source mutation behind an existing same-scope reference/cursor; live same-scope reads verified resolution and re-extraction without an intervening mutation.
- No suitable chest, chest boat/minecart or mount fixture was available in the compact-machine scene. Positive opened-container synchronization, targeted chest contents and ridden/opened mount cargo remain automated/source-reviewed coverage, not installed live proof. The live server-path targeted call completed with an unsupported entity result while PackSage was open.
- Large-pack work limits are visible: the two named document queries did not reach their matches in two pages; unqualified torch discovery did not reach JEI within its first work window; native torch planning found no route within its bound. These outcomes remained partial with no complete-absence claim. The native stripped-hyphae plan did return a useful partial route. No claim of exhaustive recipe/document coverage is made.
- No new implementation failure surfaced during these calls. Expected stale-reference and unsupported-filter errors were preserved. No code fixes or fresh artifact were needed after the live run.

## Prepared chest follow-up

The player prepared a vanilla chest in the trial world and authorized computer control for manual tests 1 and 2. Used `computer-use:computer-use` to observe the menu, change contents, close it, query the targeted chest and aim at a non-container. The installed JAR still matched SHA-256 `659226FD0C92647E016C9C554674DA86631A46F28938E639CE9710A90F6F7ABC`. No code or artifact changed; automated tests were not rerun for this validation-only step.

Five completed requests contain eight actual tool calls, verified from saved replay `packsage/OoO_-_PackSage_Trial-814a1737fe55fba5/122084a3-d2dd-4f5f-b244-82ec6a7486b0-5fade726211bb80c.jsonl`, relative to the ATM10 instance. Prompts requested tool evidence without screenshot inference or web search.

| Request / ID | Observed result |
| --- | --- |
| `CHEST_LIVE_A` / `b8337888-a7b1-4a6a-b259-679e6882bba8` | Opened chest search and `read_evidence` both returned 3 dirt in menu slot 0 and 7 cobblestone in menu slot 1, matching the visible chest. Both reported VIEWER/PARTIAL, no continuation and no reason for partial coverage. |
| `CHEST_LIVE_B` / `9bf6566f-abe4-4872-ac2e-fe6bb85d414a` | Moved 4 cobblestone into an empty player slot. Fresh opened search returned 3 dirt and 3 cobblestone; it excluded the player's removed stack. |
| `CHEST_LIVE_C` / `5841455a-d810-4cb0-831c-5865f35bfa9b` | Closed the chest normally. Opened search became UNAVAILABLE. Targeted search returned 3 dirt and 3 cobblestone with COMPLETE coverage, SERVER_READ authority and private_integrated_owner provenance, without opening a menu. Chest position was (13, 2, -1026) in compactmachines:compact_world. |
| `CHEST_LIVE_D` / `31426deb-3d01-40d0-8f67-bc80307576d5` | Restored the 4 cobblestone to the original chest slot and closed the chest. Targeted search and its evidence reread both returned the restored 3 dirt and 7 cobblestone with COMPLETE/SERVER_READ. |
| `CHEST_LIVE_E` / `9ef0378e-deff-4cba-b622-ad203e8d7509` | Aimed at a grass block. Targeted search returned UNAVAILABLE with empty entries and detail `target block inventory is unsupported`; no previous chest contents were replayed. |

Fresh contents, player/container separation, closed-menu invalidation, targeted access without a menu, evidence rereads and non-container target rejection passed. This supersedes the earlier absence of positive opened/targeted chest coverage. Switching between two distinct chests, replacing item identities, double chests, mounts and same-request mutation remain outside this follow-up's coverage.

Unresolved finding: `NativeStorageReader.validOpened` unconditionally constructs runtime opened-menu observations with `completeness=partial` and `Coverage.PARTIAL`, even for this fully read two-stack vanilla chest. The raw result provides no explanation, so the model warns that unseen contents may exist. This is a coverage-reporting issue, not observed stale contents. Do not mark opened-chest coverage reporting fully accepted until it is corrected and retested.

Restored the original 3 dirt and 7 cobblestone in their original chest slots, with the temporary player slot empty. Left Minecraft running in the trial world with PackSage displaying the final unavailable-target result; the camera points at grass. Health remained full and level 57. No other world was opened.

## Double chest follow-up

The player added the adjacent chest and authorized the next live check. Used the same running trial world, installed artifact and replay file as the prepared-chest follow-up. The visible large chest initially contained 3 dirt in menu slot 27 and 7 cobblestone in slot 28. Temporarily moved the dirt to slot 0 to ensure both physical halves contained stock.

| Request / ID | Observed result |
| --- | --- |
| `DOUBLE_CHEST_A` / `614bbf9f-97fa-4add-aa43-edda911372ad` | Two opened searches with limit 1: dirt x3 at menu slot 0, then cobblestone x7 at slot 28. First page had a cursor/hasMore; second terminated. Each stack appeared once. Both pages remained VIEWER/PARTIAL because of the previously recorded opened-menu coverage issue. |
| `DOUBLE_CHEST_B` / `42fa3a0c-50eb-4f19-899d-e79a6a91e53a` | With the menu closed, two targeted pages with limit 1 returned dirt x3 from the left half at (12, 2, -1026), componentSlot 0, then cobblestone x7 from the right half at (13, 2, -1026), componentSlot 1. A separate limit-32 read returned both stacks together. All three reads reported SERVER_READ/COMPLETE; the first paged read still carried a continuation and hasMore=true. |

Both requests completed; all five actual calls/results were inspected in the saved replay. Counts, physical halves, page advancement, termination and absence of duplicates matched the visible inventory. The model issued the limit-32 comparison alongside the initial limit-1 request, before requesting the continuation; no contents changed during these reads. Only the half at (12, 2, -1026) was targeted, so this is not proof that aiming at either half produces identical ordering.

Additional unresolved metadata finding: targeted cobblestone reports `menuSlot=1`, whereas the opened large-chest menu reports slot 28. Its right-half position and `componentSlot=1` are correct. `NativeServerStorageReader.readBlock` passes an occupied-entry ordinal (`outputSlot++`) into the menu-slot field instead of the combined chest slot. Contents are correct, but the field is misleading and should not be treated as a GUI slot index until corrected or explicitly renamed.

Restored dirt to its original menu slot 27; cobblestone remained at slot 28 throughout. Left the large chest menu open, with the original counts and player inventory unchanged. No code changes, build or automated test rerun were made for this validation-only step.
