# Astra final rereview 13: READY

Reviewed on 2026-10-26: **6b2e90333b14f0cc906b6436ea76719c70be9e5b**, on `feat/packsage-v2`; focused diff `6b2e903^..6b2e903`. The detached build, engine inspection and in-memory probes used exactly that commit.

**Final native adapter readiness verdict: READY under the recorded scope and acceptance rulings. No Critical, Important or Minor findings remain.** The controller's same-item check is confirmed. Rereview 12's shulker-colouring finding is closed, closing the general recipe-completeness finding carried since rereview 7. Every source finding from rereviews 4-12 is now closed or explicitly accepted. This verdict preserves the recorded J8/A3 reply-compliance failures and the limitations below; it does not turn them into live passes.

Inputs: rereviews 4-12, the native-readiness plan, the world-model and Minecraft-completion specifications, the two-file source/test diff, relevant planner/provider callers, and recorded acceptance dispositions. Compared with the source reviewed in rereview 12 at `1902a9e`, only `NativeRecipeReader.java` and its test changed; the other changes are review/roadmap/acceptance documentation. Earlier source closures are preserved.

## Findings

- **Critical:** none identified.
- **Important:** none identified.
- **Minor:** none identified.

There is no remaining required fix or reproducible failure scenario in this review's scope.

## Shulker-colouring closure

**Locations:** `src/main/java/dev/packsage/knowledge/nativegame/NativeRecipeReader.java:57-59,168-175,284-288`; `src/test/java/dev/packsage/knowledge/nativegame/NativeRecipeReaderTest.java:345-356`; downstream classification at `src/main/java/dev/packsage/knowledge/RecipePlanner.java:67-75,263-265`.

The new predicate recognizes block items whose block is a `ShulkerBoxBlock`. When the exact vanilla colouring recipe has an unindexed computed result, it therefore prevents `recipesComplete` from claiming completeness for a coloured box. It covers all sixteen vanilla coloured boxes, independent of requested components. Including the uncoloured box in this conservative predicate does not introduce a false proof of absence or a fabricated recipe. Unrelated identities such as a nether star remain unaffected.

The pinned Minecraft 1.21.1 / NeoForge 21.1.202 source still confirms why this recipe must count: `ShulkerBoxColoring.java:17-40` accepts a shulker box and dye, while `:43-61` selects the colour's block and returns `itemstack.transmuteCopy(block, 1)`. An uncoloured box can produce the player's first red box, a different registered item.

Source-free JShell probes against the compiled detached checkout initialized the game version, empty loading-mod list and registries. The assembly probe bound the dye tag used by this NeoForge recipe. Observed results:

```text
PROBE matches=true input=minecraft:shulker_box output=minecraft:red_shulker_box
PROBE complete=false candidates=0
PROBE root detail=materials with no recipe found, though a capped, unreadable or computed-result recipe could still make them=[minecraft:red_shulker_box]
PROBE colored outputs qualified=16/16; nether_star complete=true
```

A separate probe used a synthetic ordinary shapeless recipe requiring a red shulker box and called the exact static `NativeProvider.planRecipe` overload. It verified the formerly failing intermediate-material path:

```text
PROBE intermediate records=1 coverage=PARTIAL detail=materials with no recipe found, though a capped, unreadable or computed-result recipe could still make them=[minecraft:red_shulker_box]
PROBE intermediate PASS
```

Neither path now says the red box is "obtained rather than crafted." The committed regression checks the corrected red-box completeness result. These are source/in-memory checks, not installed observations, and they establish conservative uncertainty rather than full enumeration of colouring routes.

## Controller check: remaining excluded classes

**Confirmed for the exact classes in the table and their successful nonempty assembly results.** Each result keeps the registry identity of an input stack. Copies can increase the number of maps, books or banners, but still require an existing item of that output identity; they do not supply its first acquisition. The checked sources are under `net/minecraft/world/item/crafting/` in this build's generated `neoforge-21.1.202-sources.jar`:

- **Repair:** `RepairItemRecipe.java:43-52,59-87` requires matching input items and creates `new ItemStack(itemstack.getItem())`.
- **Smithing trim:** `SmithingTrimRecipe.java:38-55` copies the actual base with count one and sets its trim component. Its illustrative result is not the assembled output.
- **Armour dye:** `ArmorDyeRecipe.java:43-66` copies the dyeable input; `net/minecraft/world/item/component/DyedItemColor.java:41-86` copies that same item and sets its colour component.
- **Banner duplication:** `BannerDuplicateRecipe.java:62-74` returns a count-one copy of the patterned input banner.
- **Shield decoration:** `ShieldDecorationRecipe.java:51-73` copies the input shield and sets pattern/base-colour components.
- **Map cloning:** `MapCloningRecipe.java:39-63` copies the filled-map input with increased count. Empty maps are additional inputs, not a source of the first filled-map identity.
- **Map extension:** `MapExtendingRecipe.java:43-47` copies the filled-map input and sets the scale post-processing component.
- **Book cloning:** `BookCloningRecipe.java:43-79` copies the written-book input with a new count and copied book content. Writable books are additional inputs.
- **Firework-star fade:** `FireworkStarFadeRecipe.java:47-67` copies the input star and updates its explosion component.

Shulker colouring was the only identity-changing operation among these previously excluded classes. The first-acquisition acceptance from rereview 12 therefore applies to all nine exclusions left at `NativeRecipeReader.java:51-56,60-62`. This is not a claim that arbitrary subclasses or every component transformation are fully modeled; unrecognized computed-result classes retain the conservative unknown-producer path.

## Disposition of rereviews 4-12

- **Rereview 4 Minor 1, composite continuation: CLOSED.** Retained acquisition/paging state remains at `ProgressTools.java:41-80`.
- **Rereview 4 Minor 2 and rereview 5 Minor 1, unencodable first/suffix records: CLOSED.** Counted skips preserve later records and provider continuation (`PackSageTools.java:465-505`).
- **Rereview 4 Minor 3, failed-replay tail: CLOSED.** Failed delivery discards/accounts for its new tail (`PackSageTools.java:546-550`).
- **Rereview 4 Minor 4, closed-world submission queue: CLOSED.** Rejection removes/accounts for the entry when it still owns it (`ServerWork.java:95-103`).
- **Rereview 5 Important 1, pending-loot registry: CLOSED.** Resolution uses the owning server's reloadable registries (`MinecraftInventories.java:169-172`).
- **Rereview 5 Minor 2, statistics key acquisition: EXPLICITLY ACCEPTED LIMITATION.** The synchronized full key copy remains (`MinecraftStatistics.java:193,209`), as accepted in rereview 6 alongside the owned advancement-reference copy. No strict 5 ms acquisition guarantee is claimed.
- **Rereview 6 Important 1 and rereview 7 Minor 1, smithing inputs/component coverage: CLOSED.** All three inputs remain extracted; trim output and transform inheritance remain qualified (`NativeSnapshot.java:114-133,190-199`).
- **Rereview 6 Important 2, untyped menu bridge response: CLOSED.** The accessor exception fallback remains (`MinecraftDebugAdapter.java:180,519`).
- **Rereview 6 Minor 1, non-crafting remainders: CLOSED.** Remainder inference remains restricted to exact shaped/shapeless crafting consumers.
- **Rereview 6 Minor 2, component encoding failure: CLOSED for the reviewed recipe path.** The failure marker still qualifies decoding/coverage (`NativeSnapshot.java:199,358-359`).
- **Rereview 7 Important 1 and rereview 8 Important 1, mount ownership traversal: CLOSED.** The menu's actual horse is read directly (`JournalHooks.java:402-409`).
- **Rereview 7 Minor 2, rereview 8 Minor 1 and rereview 9 Minor 2, unsupported acquisition inference: CLOSED within the accepted first-acquisition scope.** Ordinary caps, unreadable sources, unknown dynamic classes and brewing gaps retain their qualifications. The final known-producer false negative, shulker colouring, is fixed and verified above.
- **Rereview 9 Important 1-3 and Minor 1, villager polling work/lifecycle/retention: CLOSED.** The bounded engine-section walk and primitive cursor state remain (`VillagerPoll.java:31-50`), without the old membership map or retained entity snapshot.
- **Rereview 10 Minor 1, map-extension index: CLOSED.** Its illustrative empty-map result is still excluded from static indexing (`NativeRecipeReader.java:163-167`). Exclusion as a first source of a filled map remains accepted.
- **Rereview 10 Minor 2 and rereview 11 Minor 1, trim/repair eligibility: EXPLICITLY ACCEPTED under the first-acquisition scope.** The earlier component-sensitive examples require an input with the output's registry identity. They are not claims of exhaustive component-variant modeling.
- **Rereview 10 Minor 3 and rereview 11 Minor 2, repeated omitted leaves: CLOSED.** Both omission categories retain distinct identity sets and report their sizes (`RecipePlanner.java:73-75,256-268`).
- **Rereview 12 Minor 1, shulker colouring: CLOSED.** The new possible-producer predicate and direct/intermediate probes establish the correction.

The existing bounded-planner limitation concerning omitted computed routes when static candidates exist remains explicitly accepted as recorded in rereviews 5, 11 and 12. No exhaustive dynamic-recipe decoding claim is added.

## Acceptance boundaries and supporting reviews

The recorded round-8 A1/W21 passes and M5 pass remain accepted. M5 uses the amended criterion of zero slow ticks after the first 200; its early outliers remain recorded. This is the qualified ruling from rereview 11, not a new no-stall guarantee.

J8 journal data and A3 plan detail passed their recorded checks, but their reply-compliance failures remain recorded under the round-5/7 rulings. They are not reclassified here. The roadmap's uncached-POI and acquisition-budget branches remain automated-only, and modded-menu PARTIAL sections remain unexercised live. No new installed-default, navigation or model-compliance result is claimed.

- **Standards axis:** the read-only Luna High review approved the two-file diff, with no documented standard breach or actionable smell.
- **Specification axis:** the read-only Luna High review approved the shulker correction and prior closure ledger, with no additional unresolved source finding. It retained the J8/A3 reply failures and amended M5 boundary. Astra independently checked all ten relevant engine assembly paths and the former shulker failure scenarios.

## Verification and cleanup

- Created `D:/Project/packsage-review-astra13` with detached HEAD at `6b2e90333b14f0cc906b6436ea76719c70be9e5b`.
- Ran `$env:JAVA_HOME='D:/Project/packsage/.tools/jdk/jdk-21.0.12.1'` and `./gradlew build -q` there: **exit 0**. No Gradle command ran in `D:/Project/packsage`.
- Ran `node D:/Project/packsage/build/tools/count.cjs D:/Project/packsage-review-astra13`: **100 XML suites; 1,215 tests; 1,210 passed; 5 skipped; 0 failures; 0 errors**. Four skips are in `ConversationStoreTest`, and one is in `GroundingInstructionsTest`.
- Ran the source-free JShell probes above. The first combined probe did not complete its provider-overload step; the separate intermediate probe selected the explicit static signature and completed with `PROBE intermediate PASS`. Only its completed output supports the provider-path conclusion.
- `git diff --check 6b2e903^ 6b2e903` passed, and the detached checkout remained clean at its pinned HEAD.
- Verified the resolved removal path, HEAD and clean status; removed the worktree and confirmed both its directory and Git registration were absent.
- This verdict is the only repository file authored and authorized for the review commit. No formatter, source/test edit, installation, live-game action, push or merge was performed.
