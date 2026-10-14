# Behavior-driven Toolbox validation

Date: 2026-10-14. Status: report finalized for the tested scope. Implementation, independent review and automated build passed; installed checks cover all twelve signatures and prepared inventory fixtures. Opened-menu coverage and server slot-metadata defects are corrected; the correction build passed a live mixed-chest comparison. Model-summary accuracy is mitigated, not guaranteed. The remaining coverage matrix below is explicit: not every checklist case has live proof.

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

Finding at this checkpoint (corrected and retested below): `NativeStorageReader.validOpened` unconditionally constructs runtime opened-menu observations with `completeness=partial` and `Coverage.PARTIAL`, even for this fully read two-stack vanilla chest. The raw result provides no explanation, so the model warns that unseen contents may exist. This is a coverage-reporting issue, not observed stale contents.

Restored the original 3 dirt and 7 cobblestone in their original chest slots, with the temporary player slot empty. Left Minecraft running in the trial world with PackSage displaying the final unavailable-target result; the camera points at grass. Health remained full and level 57. No other world was opened.

## Double chest follow-up

The player added the adjacent chest and authorized the next live check. Used the same running trial world, installed artifact and replay file as the prepared-chest follow-up. The visible large chest initially contained 3 dirt in menu slot 27 and 7 cobblestone in slot 28. Temporarily moved the dirt to slot 0 to ensure both physical halves contained stock.

| Request / ID | Observed result |
| --- | --- |
| `DOUBLE_CHEST_A` / `614bbf9f-97fa-4add-aa43-edda911372ad` | Two opened searches with limit 1: dirt x3 at menu slot 0, then cobblestone x7 at slot 28. First page had a cursor/hasMore; second terminated. Each stack appeared once. Both pages remained VIEWER/PARTIAL because of the previously recorded opened-menu coverage issue. |
| `DOUBLE_CHEST_B` / `42fa3a0c-50eb-4f19-899d-e79a6a91e53a` | With the menu closed, two targeted pages with limit 1 returned dirt x3 from the left half at (12, 2, -1026), componentSlot 0, then cobblestone x7 from the right half at (13, 2, -1026), componentSlot 1. A separate limit-32 read returned both stacks together. All three reads reported SERVER_READ/COMPLETE; the first paged read still carried a continuation and hasMore=true. |

Both requests completed; all five actual calls/results were inspected in the saved replay. Counts, physical halves, page advancement, termination and absence of duplicates matched the visible inventory. The model issued the limit-32 comparison alongside the initial limit-1 request, before requesting the continuation; no contents changed during these reads. Only the half at (12, 2, -1026) was targeted, so this is not proof that aiming at either half produces identical ordering.

Additional metadata finding at this checkpoint (corrected and retested below): targeted cobblestone reports `menuSlot=1`, whereas the opened large-chest menu reports slot 28. Its right-half position and `componentSlot=1` are correct. `NativeServerStorageReader.readBlock` passes an occupied-entry ordinal (`outputSlot++`) into the menu-slot field instead of the combined chest slot. Contents are correct, but this artifact's field is misleading.

Restored dirt to its original menu slot 27; cobblestone remained at slot 28 throughout. Left the large chest menu open, with the original counts and player inventory unchanged. No code changes, build or automated test rerun were made for this validation-only step.

## Bundle, shulker and chest minecart follow-up

The player prepared a bundle, shulker box, chest boat and chest minecart. Continued through `computer-use:computer-use` in the same running trial world and replay file. Directly observed bundle tooltip contents of 2 sticks and 5 cobblestone (7/64), and shulker tooltip contents of 5 cobblestone and 2 sticks. The minecart menu was empty; temporarily moved the player's 3 dirt from main inventory slot 1 into cargo slot 0.

| Request / ID | Observed result |
| --- | --- |
| `FIXTURE_A` / `c7d6bda7-8e20-4ab7-968c-95d6f7280dd4` | Five calls: opened and targeted minecart searches both returned dirt x3 at slot 0; opened was VIEWER/PARTIAL and targeted SERVER_READ/COMPLETE. Player name discovery found the bundle in hotbar 4 and shulker in hotbar 5; item searches using each fresh slot reference returned their correct contents with LOADED/COMPLETE coverage. |
| `FIXTURE_B` / `462f3aab-57f1-4899-98ce-24cf73afb155` | After returning dirt to its original player slot, closing the minecart menu, moving the bundle to offhand and selecting the shulker: four calls returned correct mainhand shulker and offhand bundle contents, targeted minecart COMPLETE empty storage, and opened UNAVAILABLE. No removed dirt was replayed. |

Both requests completed; all nine calls/results were inspected in the saved replay. Targeted minecart identity was `3310651a-c151-42b0-b8e8-40eca16c0780`. Item-reference resolution, hand selection, minecart cargo reads, changed-to-empty freshness and closed-menu invalidation passed. The known opened-menu partial-label issue also affects the minecart. Bundle/shulker internal contents were not mutated during this follow-up, so their content-mutation freshness is not established by these calls.

Restored dirt to main inventory slot 1, bundle to hotbar 4, shulker to hotbar 5, offhand empty and selected hotbar 7. Minecart was empty again. Camera control through mouse dragging proved unreliable while trying to aim at the boat, so stopped input and asked the player to open the boat inventory. Boat checks remain pending at this checkpoint. No code, build or automated test changes were made.

### Skyroot chest boat

The player then opened the boat inventory. Its visible title was `Skyroot Boat with Chest`, a modded fixture, and cargo was empty. Temporarily placed the player's 3 dirt into cargo slot 0. Request `BOAT_LIVE_A` / `2845e324-3023-43dc-a652-d275a45b4c06` completed with three calls in the same replay:

- Opened: correctly returned dirt x3 at menu slot 0, VIEWER/PARTIAL through the current `minecraft:generic_9x3` menu.
- Ridden: UNAVAILABLE, exact detail `target entity type is unsupported`. This is an observed compatibility limit for the ridden Skyroot boat. The current native server reader accepts exact `ChestBoat` and `MinecartChest` classes, not arbitrary subclasses.
- Targeted: UNAVAILABLE, exact detail `target block inventory is unsupported`. This call selected a block while seated, not the boat entity; it does not validate targeted Skyroot or vanilla chest-boat reads.

Restored the 3 dirt to the original player slot and left the empty boat inventory open. No fixture or internal bundle/shulker contents were altered. Positive native chest-boat targeted/ridden coverage remains pending; opened Skyroot cargo reading is now observed. No implementation changes were made.

## Chest-bearing donkey follow-up

The player aimed at a tame native donkey with a chest, identity `bece15dc-5466-41b3-bdc7-32475c393508` in compactmachines:compact_world. Used `computer-use:computer-use` and the same installed artifact/replay. Its inventory visibly had no saddle and no cargo. Mounted it, opened its inventory, temporarily put the player's 3 dirt into the first cargo slot, then restored the dirt to main inventory slot 1. The player assisted with dismounting after computer-use Shift input did not reliably trigger it.

| Request / ID | Observed result |
| --- | --- |
| `DONKEY_LIVE_A` / `f306f0b7-c238-44fe-a188-d64e5e78319a` | Targeted search and evidence reread returned the correct donkey with COMPLETE empty contents and SERVER_READ authority. Before mounting, ridden returned UNAVAILABLE. |
| `DONKEY_LIVE_B` / `4a9f9ee3-7272-44b1-ad3a-23ad61aeac27` | With dirt x3 in cargo, opened returned the cargo at menu slot 2, VIEWER/PARTIAL. Ridden search and evidence reread returned the same donkey and dirt x3 in section cargo, componentSlot/menuSlot 1, SERVER_READ/COMPLETE. Opening PackSage from the native horse menu preserved access without a crash. |
| `DONKEY_LIVE_C` / `aa9746dc-870c-42e8-9ae5-99a5f6844c9b` | After cargo restoration, menu closure and player-assisted dismount: ridden was UNAVAILABLE (`player is not riding a supported container entity`), opened was UNAVAILABLE, and targeted returned the same donkey with COMPLETE empty contents. |

All three requests completed; nine actual calls/results were inspected in the saved replay. Targeted access, opened/ridden positive cargo reads, evidence rereads, restored empty contents and dismount/menu-close invalidation passed. Positive saddle/equipment handling was not tested because none was equipped. The existing opened-menu partial-label issue persists. The opened/server `menuSlot` values also differ (2 versus 1 for the same cargo slot), extending the recorded slot-metadata ambiguity to mounts; counts and cargo classification were correct.

The donkey's original empty cargo and player inventory were restored. Left the player dismounted, with PackSage displaying the final results and the donkey still targeted. No code, build or automated test changes were made.

## Mixed-stack chest filters and pagination

The player prepared an opened large chest with seven stacks, all count 64: dirt at menu slot 7, cobblestone at 9 and 29, chest at 25, stick at 31, birch log at 46 and oak log at 53. Verified these against the visible menu using computer-use; no items were moved. Continued with the same artifact and replay file.

Request `MIXED_CHEST_A` / `3a9d9b1c-a3a7-4f20-88b4-c52d15c94009` completed with nine calls. Unfiltered limit-2 enumeration produced four pages with slot sequences [7,9], [25,29], [31,46], [53], then no continuation. No stack was omitted, merged or duplicated. Five additional queries returned:

| Filter | Actual tool result |
| --- | --- |
| ids=[minecraft:cobblestone] | Both separate stacks, slots 9 and 29, count 64 each. |
| tags=[minecraft:logs] | Birch log at 46 and oak log at 53, count 64 each. |
| names=[CoBbLe] | Both cobblestone stacks, confirming case-insensitive literal matching. |
| ids=[minecraft:cobblestone], names=[dirt] | Empty entries, confirming AND across fields for this fixture. |
| ids=[minecraft:dirt,minecraft:stick] | Dirt at 7 and stick at 31, confirming OR within the ID field. |

All opened results remained PARTIAL due to the existing coverage-label issue; an empty result must not be presented as complete absence. The first assistant answer incorrectly stated that names=[CoBbLe] returned no matches, despite its call-ID-matched tool result containing both stacks. This is an observed answer-grounding/reporting failure, not a failed filter. Isolated repeat `MIXED_CHEST_B` / `76996653-9108-477b-807b-edc7c4aa8ffe` made one fresh name-filter call and correctly reported both stacks in the final answer. The repeat does not erase the earlier reporting failure.

Ten actual calls across two completed requests were inspected. Pagination and filter execution passed against the fixture; multi-query answer accuracy has the issue above. Left PackSage displaying the correct isolated result over the still-open chest. Inventory contents were untouched. No code changes or automated test rerun were made.

## Follow-up assessment

Across the original run and prepared-fixture follow-ups, 22 completed requests contain 96 inspected tool calls. The original run covers all twelve signatures; follow-ups provide deeper inventory evidence. These counts describe the original installed artifact, not a later correction build.

| Area | Observed acceptance | Remaining live coverage |
| --- | --- | --- |
| Player and held storage | Player slots/equipment, reference discovery, mainhand/offhand bundle and shulker direct contents, unsupported item rejection and hand replacement. | Mutating internal bundle/shulker contents; same-request source mutation behind an existing reference. |
| Opened containers | Single/double chest, minecart, Skyroot boat and donkey contents; player slots excluded; count changes and closure invalidate access. Corrected COMPLETE reporting passed the mixed-chest regression below. | The corrected build was not separately rerun against every previous menu fixture. |
| Targeted containers | Single/double chest and minecart current stock, empty transitions, evidence rereads and non-container rejection. | Both double-chest aiming directions; lock/block/pending-loot and remote-server denials are not claimed as live-tested here. |
| Mounts and boats | Donkey targeted/opened/ridden cargo and dismount invalidation. Skyroot boat opened cargo works. | Positive equipped saddle/body items and native chest-boat targeted/ridden reads. Skyroot ridden access is explicitly unsupported by the reviewed native contract. |
| Filters and pagination | Separate duplicate stacks, exact IDs, logs tag, mixed-case literal names, OR within IDs, AND across fields, four-page termination without omissions/duplicates. | Tooltip/section filter combinations and mutations between live pages were not exhaustively exercised. |
| Other tool families | All signatures exercised, six registry kinds, FTB/Patchouli discovery and rereads, JEI output focus, native recipe evidence/planning, configs and history. | Document/recipe scan bounds prevented some named matches; no exhaustive pack-content or progression-mutation claim. |

### Incorrect multi-query answer

Independent trace matched `call_UY7jK740kvvf0L8nXpHZ0k6Z` to names=[CoBbLe] and its two cobblestone records. `RequestController.toolReady` validates IDs, `ChatGptBackend.Exchange.submit` rejects unknown/duplicate IDs, and continuation construction looks each result up by its call ID. Parallel completion order does not swap these responses. No deterministic filter or delivery defect was established; exact outgoing HTTP bytes were not captured.

Commit `2f1f569` adds a focused grounding instruction to reconcile each reported filter outcome, count and no-match claim with its returned records. This is a model-behavior mitigation, not a guarantee of correct future summaries. The earlier incorrect answer remains a recorded failure even if a subsequent repeat passes.

## Correction build

- `7cc9581` changes the public opened-horse regression to require complete coverage for its fully inspected native inventory. The test failed against the old unconditional PARTIAL result.
- `d2da14a` derives opened coverage from inspected slots and fields. Unknown non-player slot contracts, bounds and unavailable/truncated metadata or requested filter fields keep coverage partial; a fully read supported menu is complete.
- `9e63508` replaces occupied-entry numbering with real block GUI slots, adds the right double-chest half's 27-slot offset, and maps horse body/saddle/cargo to GUI indices 1/0/source+1. `componentSlot` retains its source meaning. These are metadata corrections; the live-source read and access policy are unchanged.

Independent review found no behavioral defect in these corrections. It identified two low-priority automated coverage gaps: server GUI-slot mapping has no dedicated assertions, and selected-field extraction failures/truncation lack focused opened-menu regressions. Existing opened tests cover complete native slots, unknown slots and the entry bound; the server tests cover owner and lock/loot guards. GUI mapping therefore also requires the explicit live comparison below, and positive horse equipment mapping is not claimed as live-proven.

At `9e63508`, full `test build --console=plain` passed in 29 seconds: 439 tests, 435 passed, zero failures/errors and the same four filesystem-link skips. Focused native storage tests had also passed (21 reader tests plus two server guard tests). `git diff --check` passed. No formatter was used.

Saved and exited the trial normally, then copied the tested JAR to ATM10 and `dist/packsage.jar`. Build, distribution and installed SHA-256 values all matched `B54C31D8A37B2DAA8F739A2A347849858D09ABBDDCA029163E552075296AE67C`. Distribution metadata was refreshed. The separate minimal profile was not reinstalled. The live correction checks use this artifact; earlier calls above use the previous hash.

### Installed correction regression

Relaunched ATM10 using computer-use and opened only `OoO - PackSage Trial`. The same seven mixed-chest stacks remained visible in their original slots. Request `CORRECTION_A` / `47201461-1b64-4fa7-b830-e4c27a17a308` completed in the same prepared-fixture replay file with seven call-ID-paired `search_inventory` results:

| Query | Observed result |
| --- | --- |
| Opened, unfiltered, limit 32 | COMPLETE, no cursor; all seven count-64 stacks at slots 7, 9, 25, 29, 31, 46 and 53. |
| Opened, ids=[minecraft:cobblestone] | COMPLETE; separate stacks at slots 9 and 29. |
| Opened, tags=[minecraft:logs] | COMPLETE; birch log at 46 and oak log at 53. |
| Opened, names=[CoBbLe] | COMPLETE; both cobblestone stacks at 9 and 29. |
| Opened, ids=[minecraft:cobblestone], names=[dirt] | COMPLETE, empty. No incorrect partial-absence warning. |
| Opened, ids=[minecraft:dirt,minecraft:stick] | COMPLETE; dirt at 7 and stick at 31. |
| Targeted, unfiltered, limit 32 | COMPLETE; all seven entries and GUI slots match the visible/opened chest. Left component slots are 7, 9, 25; right component slots are 2, 4, 19, 26 with GUI slots 29, 31, 46, 53. |

The raw tools and the answer's per-query contents agree. The answer's introduction incorrectly says "six searches" although it lists all seven correctly. Thus the original false CoBbLe no-match claim did not recur, but a minor summary-count error remains; the grounding change cannot establish general model-answer reliability.

Opened-menu completeness and sparse/double-chest GUI mapping are accepted on the corrected installed artifact. Horse GUI mapping is source-reviewed and compiled, with the opened-horse automated regression passing; the changed server horse indices and positive equipment were not rerun live after this rebuild. No generic modded-entity contract was added. No snapshots or retained content bodies were introduced.

Total observed live evidence is now 23 completed requests and 103 tool calls across the two artifacts. This is coverage of the stated scenarios, not a claim that all possible tests passed. No inventory items were moved during the correction run. Left Minecraft running with the unchanged large-chest inventory visible; original `OoO` was never opened.
