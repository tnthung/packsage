# Astra final rereview 12: NOT READY

Reviewed on 2026-10-26: **1902a9eb7683da09397779403f57fa11cb237261**, on `feat/packsage-v2`; focused diff `1902a9e^..1902a9e`. The detached build and probes used exactly that commit. The shared branch subsequently advanced to `62c646955ed820ed5ed23970583b0706ac740240` through roadmap/acceptance documentation changes only; those changes were preserved.

**Final native adapter readiness verdict: NOT READY. No Critical or Important findings; one Minor finding.** The omitted-material counts are fixed. I accept the controller's first-acquisition rationale for genuine same-item transforms, including the trim/repair examples from rereview 11, but shulker-box colouring does not satisfy its premise: it produces a different registered item. The general recipe-completeness finding therefore cannot yet be closed.

Inputs: rereviews 4-11, the target source/test diff and relevant callers, the native-readiness plan, world-model and Minecraft-completion specifications, grounding instructions and recipe tool description, and the recorded acceptance dispositions. Earlier closures were checked against the fact that the only source/test changes since the rereview-11 build at `fa0d545` are the four files in this commit. Engine behavior was checked in this build's Minecraft 1.21.1 / NeoForge 21.1.202 source and resource archives. No installed-game testing was performed.

## Findings

### Critical

None identified.

### Important

None identified.

### Minor 1 - Shulker-box colouring is incorrectly excluded as a first source of coloured boxes

**Locations:** `src/main/java/dev/packsage/knowledge/nativegame/NativeRecipeReader.java:57,166-174,282-286`; `src/main/java/dev/packsage/knowledge/RecipePlanner.java:67-75,264`. The affected answer instruction is `src/main/java/dev/packsage/model/GroundingInstructions.java:42`.

The new always-false predicate assumes the output item must already be present among the inputs. `ShulkerBoxColoring` accepts any shulker box plus a dye, selects the block for that colour, and calls `itemstack.transmuteCopy(block, 1)`. An uncoloured `minecraft:shulker_box` and red dye therefore produce the player's first `minecraft:red_shulker_box`. The input and output have different registry identities. This is ordinary vanilla behavior, not a component-sensitive custom recipe or speculative subclass.

**Failure scenario:** a player asks how to obtain a red shulker box, or a pack recipe requires one as a material. With colouring loaded and no unrelated index/brewing gap, the planner finds no indexed red-box recipe, reports its lookup complete, and calls the box "obtained rather than crafted." In fact, the player can craft it from an uncoloured or differently coloured box and red dye without already having a red box. The pinned vanilla resource archive contains `shulker_box.json` and `shulker_box_coloring.json`, with no separate static red-box recipe to mask this error.

A source-free JShell probe against the compiled checkout initialized the game version, empty loading-mod list and registries, and bound the dye tag needed by the recipe. It used the exact vanilla colouring class and an uncoloured box plus red dye:

```text
PROBE matches=true output=minecraft:red_shulker_box
PROBE complete=true candidates=0
PROBE detail=materials with no recipe here, obtained rather than crafted=[minecraft:red_shulker_box]
```

A second isolated probe passed a normal shapeless recipe requiring that red box through `NativeProvider.planRecipe`. Its one leading route was returned, but its material detail still made the false assertion, without even the missing-root dynamic-output caveat:

```text
PROBENATIVE records=1 coverage=PARTIAL detail=materials with no recipe here, obtained rather than crafted=[minecraft:red_shulker_box]
```

The downstream fixture recipe is synthetic; the colouring operation and item-identity change are native. The engine source establishes the match at `ShulkerBoxColoring.java:17-40` and the different output block at `:43-61`. These are source/in-memory reproductions, not installed observations.

Restore a conservative possible-producer predicate for coloured shulker-box identities. It is sufficient to preserve the computed-recipe uncertainty for those outputs; this finding does not require implementing full dynamic recipe enumeration. Add a regression using an uncoloured input and a coloured output identity, including a missing intermediate lookup. The current test changes cover repair, stew, map extension and trim, but not this excluded class.

## Rationale and the five retained producer predicates

**I accept the rationale as an explicit first-acquisition interpretation for the base item identity.** Repairing two saddles cannot supply the first saddle; trimming a named nether star still requires a nether star. The earlier component-sensitive trim and wildcard repair examples do not refute that narrower acquisition question. I therefore accept those specific rereview-10/11 objections under the revised scope, rather than requiring increasingly elaborate trim/repair predicates. This is an accepted semantic adjustment, not proof that computed component variants are fully modeled or that every transformation route is enumerated.

The same reasoning applies to the other genuine same-identity operations listed in the change: armour dyeing, banner duplication, shield decoration, map cloning/extending, book cloning and firework-star fading. It does **not** justify treating all members of an item family as one identity. Shulker colouring is the concrete counterexample above, so I do not accept the blanket exclusion as implemented.

**All five retained item predicates are correct conservative possible-output predicates:**

- `FireworkRocketRecipe` assembles `FIREWORK_ROCKET` from paper and gunpowder, with optional stars (`FireworkRocketRecipe.java:14-16,66-68`).
- `FireworkStarRecipe` assembles `FIREWORK_STAR` from gunpowder, dye and optional modifiers (`FireworkStarRecipe.java:45,79-95,119-121`).
- `TippedArrowRecipe` assembles `TIPPED_ARROW` from arrows and a lingering potion (`TippedArrowRecipe.java:23-28,39-46`).
- `SuspiciousStewRecipe` assembles `SUSPICIOUS_STEW` from mushrooms, a flower and a bowl (`SuspiciousStewRecipe.java:26-42,45-59`).
- `DecoratedPotRecipe` assembles `DECORATED_POT` from its four decoration ingredients (`DecoratedPotRecipe.java:16-41,45-49`).

Their fixed output identities do not depend on the selected component variant, so ignoring components is conservative for a "could make" warning. These five entries are not an exhaustive set of identity-producing special recipes while colouring is excluded. Also, this NeoForge build exposes nonempty display results for rocket and star, so those two normally enter the static index before the empty-output table is consulted; the table assessment does not claim exhaustive dynamic-variant planning.

## Disposition of rereviews 4-11

**Not every general finding is closed or accepted:** the incomplete-source acquisition issue carried through rereviews 7-11 remains open through Minor 1 above. The previously reported trim/repair examples themselves are accepted under the first-acquisition interpretation, and the repeated-omission defect is fixed. All unrelated prior source closures and explicit limitations remain unchanged.

- **Rereview 4 Minor 1, composite continuation: CLOSED.** Retained `ListingRead` acquisition and paging remain unchanged (`ProgressTools.java:41-80`).
- **Rereview 4 Minor 2 and rereview 5 Minor 1, unencodable first/suffix rows: CLOSED.** Counted skips retain later rows and provider continuation (`PackSageTools.java:465-505`).
- **Rereview 4 Minor 3, failed-replay tail: CLOSED.** Failed delivery removes the newly retained tail and accounts for its bytes (`PackSageTools.java:546-550`).
- **Rereview 4 Minor 4, closed-world submission queue: CLOSED.** Rejection removes/accounts for the entry when it still owns it (`ServerWork.java:95-103`).
- **Rereview 5 Important 1, pending-loot registry: CLOSED.** Resolution uses the owning server's reloadable registries (`MinecraftInventories.java:169-172`).
- **Rereview 5 Minor 2, statistics key acquisition: EXPLICITLY ACCEPTED LIMITATION.** The synchronized full key copy remains (`MinecraftStatistics.java:193,209`), as accepted in rereview 6 alongside the owned advancement-reference copy. This is not a strict 5 ms acquisition guarantee.
- **Rereview 6 Important 1 and rereview 7 Minor 1, smithing inputs/component coverage: CLOSED.** All three inputs remain extracted; trim output and transform inheritance remain qualified (`NativeSnapshot.java:114-133,190-199`).
- **Rereview 6 Important 2, untyped menu bridge response: CLOSED.** The accessor exception fallback remains (`MinecraftDebugAdapter.java:180,519`).
- **Rereview 6 Minor 1, non-crafting remainders: CLOSED.** The exact shaped/shapeless-consumer restriction remains.
- **Rereview 6 Minor 2, component encoding failure: CLOSED for the reviewed recipe path.** Failure qualification still propagates into decoding/coverage (`NativeSnapshot.java:199,358-359`).
- **Rereview 7 Important 1 and rereview 8 Important 1, mount ownership traversal: CLOSED.** The menu's actual horse is read directly (`JournalHooks.java:402-409`).
- **Rereview 7 Minor 2, rereview 8 Minor 1 and rereview 9 Minor 2, unsupported acquisition inference: PARTIALLY CLOSED; still open above.** Ordinary caps, unreadable sources, unknown dynamic classes and brewing gaps remain qualified. This commit introduces a known first-acquisition producer false negative for coloured shulker boxes.
- **Rereview 9 Important 1-3 and Minor 1, villager polling work/lifecycle/retention: CLOSED.** The bounded engine-section walk and primitive cursor state remain unchanged (`VillagerPoll.java:31-50`). No separate join/leave membership map is reintroduced.
- **Rereview 10 Minor 1, map-extension index: CLOSED.** Map extension is still forced through the computed-output path before its illustrative empty-map result can be indexed (`NativeRecipeReader.java:163-165`). Its exclusion as a first source of a filled map is accepted under the revised rationale.
- **Rereview 10 Minor 2 and rereview 11 Minor 1, trim/repair eligibility: EXPLICITLY ACCEPTED under the first-acquisition scope**, as explained above. The old predicates are removed, not repaired into complete variant enumerators.
- **Rereview 10 Minor 3 and rereview 11 Minor 2, omitted unchecked leaves: CLOSED.** Both omitted categories use identity sets (`RecipePlanner.java:73-75,256-268`), exclude identities already listed, and report set sizes. Repeated visits cannot inflate either count; each insertion follows a visit admitted under the 256-node cap (`:48-53`). The new shared-leaf regression covers repeated no-recipe leaves, while the existing 18-distinct unchecked-leaf regression covers the updated unchecked count. The corresponding unchecked repeated-visit path uses the same set operation.

The inherited bounded-planner limitation concerning omitted computed routes when static candidates exist remains accepted as recorded in rereviews 5 and 11. This review does not reopen it or claim exhaustive dynamic output decoding.

## Installed acceptance and supporting reviews

Rereview 11's acceptance of round 8 M5 under the amended steady-state criterion remains: zero slow ticks after the first 200, with the earlier outliers still recorded. The recorded A1/W21 passes remain passes. The J8 reply-compliance and A3 from-scratch reply-compliance failures remain recorded under the round 5/7 rulings; neither this build nor the controller's acquisition rationale converts them into passing live checks. No new installed-default or model-compliance result is claimed.

- **Standards axis:** the read-only Luna High review reported no concrete style breach or actionable smell in the four changed files. Parent review used the repository instructions supplied for this task.
- **Specification axis:** the read-only Luna High review accepted the prior closure ledger and found no additional issue. Astra's independent pinned-engine inspection and the two probes above establish the shulker exception, so that supporting approval does not establish final readiness.

## Verification and cleanup

- Created `D:/Project/packsage-review-astra12` with detached HEAD at `1902a9eb7683da09397779403f57fa11cb237261`.
- Ran `$env:JAVA_HOME='D:/Project/packsage/.tools/jdk/jdk-21.0.12.1'` and `./gradlew build -q` there: **exit 0**. No Gradle command ran in `D:/Project/packsage`.
- Ran `node D:/Project/packsage/build/tools/count.cjs D:/Project/packsage-review-astra12`: **100 XML suites; 1,215 tests; 1,210 passed; 5 skipped; 0 failures; 0 errors**. Skips occur in `ConversationStoreTest` and `GroundingInstructionsTest`, consistent with the fresh-checkout symlink/local-candidate limitations recorded previously.
- Ran the two source-free, isolated JShell probes described above. No source or test file was authored, and no game process was launched.
- `git diff --check 1902a9e^ 1902a9e` passed. The detached checkout remained clean at its pinned HEAD.
- Verified the resolved removal path and pinned HEAD, removed the worktree, and confirmed both its directory and Git registration were absent.
- This verdict is the only repository file authored and authorized for the review commit. No formatter, source edit, installation, live-game action, push or merge was performed; concurrent documentation commits were preserved.
