# Astra final rereview 10: NOT READY

Reviewed on 2026-10-26: **08098f3adf8f5e9289f1e4a360fa1fbca8f78f6c..f0f0767f8ae1849f1a72d4b85022add994ca67e7**, on `feat/packsage-v2`. The review and detached build are pinned to `f0f0767`, before this verdict commit.

Inputs: rereview 9, every changed source/test hunk and relevant callers, the native-readiness plan, world-model and Minecraft-completion specifications, grounding instructions and tool descriptions, and round 6 controller rulings. Engine behavior was checked against the build-generated NeoForge **21.1.202 / Minecraft 1.21.1** sources and fastutil **8.5.12** sources. This is a source/build verdict; no live gameplay or installed-default acceptance was performed.

## Findings

### Critical

None identified.

### Important

None identified. All three Important villager findings from rereview 9 are addressed.

### Minor 1 - Map extension bypasses its special-output predicate and remains indexed as an empty-map recipe

**Locations:** `src/main/java/dev/packsage/knowledge/nativegame/NativeRecipeReader.java:42,153-166`. Related output copying: `src/main/java/dev/packsage/knowledge/nativegame/NativeSnapshot.java:94-101,190-192`.

The new table correctly names `FILLED_MAP` for `MapExtendingRecipe`, but the table is consulted only when the result is empty. Unlike the `CustomRecipe` subclasses, this class extends `ShapedRecipe`: its constructor supplies a nonempty `Items.MAP` display result (`MapExtendingRecipe.java:13-20`), while `assemble` copies the input `FILLED_MAP` and adds the scale-processing component (`:42-46`). Only smithing trim is forced into the computed-output path.

**Failure scenarios:** with the normal vanilla map-extension recipe loaded, `plan_recipe(minecraft:map)` includes the extension recipe as an empty-map output made from paper and a filled map. That operation actually produces a filled map. Its UNKNOWN decoding/consumption warnings do not correct the explicit wrong output identity. In a pack that removes map cloning but keeps map extension, with no other relevant incomplete source, `recipesComplete("minecraft:filled_map")` can also return true and classify a missing filled-map leaf as obtained even though extension still produces it. Map cloning masks this second symptom in the stock recipe set, not the bogus empty-map candidate.

Classify this known computed-output recipe before indexing its illustrative result, as is now done for trim. This is a remaining part of rereview 9 Minor 2 and conflicts with the output-keyed planning requirement and `GroundingInstructions.java:37,42`.

### Minor 2 - Trim and repair predicates exclude outputs their exact vanilla classes can produce

**Locations:** `src/main/java/dev/packsage/knowledge/nativegame/NativeRecipeReader.java:50-51,159-161,269-273`; `src/main/java/dev/packsage/knowledge/RecipePlanner.java:71-74`.

The predicates assume the shipped trim base tag and an item's default damage components are exhaustive. The actual recipe implementations accept a wider set of inputs:

- **Data-pack trim base:** an exact `SmithingTrimRecipe` can specify `minecraft:nether_star` as its base ingredient, with a valid vanilla template and trim material. `matches` tests that recipe's ingredients; `assemble` tests `this.base`, copies the base stack and sets its TRIM component. It does not require `ItemTags.TRIMMABLE_ARMOR` (`SmithingTrimRecipe.java:28-51`). If the star is outside that tag, PackSage skips the recipe's illustrative output but its new predicate also denies the real star output. With no other applicable gap, a star leaf is again labeled obtained rather than crafted. The 18 shipped vanilla trim recipes use the tag; that data convention does not constrain data-pack definitions using the same recipe class.
- **Stack-level repair components:** two single saddle stacks with MAX_DAMAGE and DAMAGE components satisfy the vanilla repair class's component checks and the item's repairability check. `RepairItemRecipe` creates a new stack of the same item and transfers the durability information (`RepairItemRecipe.java:43-52,64-72`). NeoForge's `Item.isRepairable` uses `canRepair && isDamageable(stack)`, and the default `IItemExtension.isDamageable` checks the stack's MAX_DAMAGE component. PackSage checks only `item.components()`, which has no MAX_DAMAGE for a saddle. Saddles already have a default maximum stack size of one; this example does not depend on incompatible stacking/durability components. A pack consuming such a repaired variant therefore gets the same false complete/no-recipe classification. The public tool contract explicitly covers component variants (`PackSageTools.java:255`).

Both are source-established failure paths; no live reproduction is claimed. Use recipe/stack-aware eligibility where it can be established, or conservatively report the coverage gap. Item defaults and the stock recipe JSON alone cannot prove absence across the active pack. This is another remaining part of rereview 9 Minor 2.

### Minor 3 - The new unchecked-material list silently drops leaves after 16

**Locations:** `src/main/java/dev/packsage/knowledge/RecipePlanner.java:71-74,259-264`.

Previously each incomplete lookup contributed a reason, and detail reported the reason cap and omitted count. Now the seventeenth distinct unchecked identity is discarded without a counter or a cap reason. `omittedNoRecipeLeaves` covers only the other branch, so it cannot disclose these omissions.

**Failure scenario:** a root recipe uses three craftable intermediates, each requiring six distinct raw ingredients, and an unknown computed-output recipe makes those 18 raw lookups incomplete. This small tree fits the node, branch and depth budgets. The detail lists only the first 16 unchecked materials and gives no indication that two more unresolved dependencies were omitted. Their input references can still occur in route payloads; the failure is the silently truncated dependency explanation, not deletion of those inputs or a falsely COMPLETE result.

Keep the list bounded, but report its cap/omission count just as the ordinary no-recipe and general-reason lists do. The change should retain the planner's stated unresolved-dependency and coverage contract (`PackSageTools.java:255`, `GroundingInstructions.java:42`).

## Previous findings and villager review

- **Rereview 9 Important 1: addressed.** No membership map, snapshot rebuild or startup all-entity scan remains. `VillagerPoll.java:31-50` spends at most 64 iterations per level per tick, each on an entity visit or section progression/end check. `ClassInstanceMultiMap.allInstances` is an array-backed list. `EntitySectionStorage.sectionIds` is a `LongAVLTreeSet`; its `tailSet`, subset emptiness check and `firstLong` perform tree lookups, not a traversal/copy of all remaining sections. The bound is on logical visits, with logarithmic section lookup overhead; it is not a measured guarantee of a sub-millisecond tick.
- **Rereview 9 Important 2: addressed.** The current section status is checked on every visit. TRACKED and TICKING are accessible; HIDDEN is skipped. A section becoming accessible again remains discoverable on a later cycle, without needing a new join event. The pinned manager updates section visibility when chunk tracking changes.
- **Rereview 9 Important 3: addressed.** Canceled joins and duplicate UUID rejections happen before insertion into the engine section storage. The new poll reads admitted storage directly. Engine removal removes the entity from its section and removes empty sections, including the hidden/pending-load path. `isRemoved()` is an additional guard, not stale-entry retention.
- **Rereview 9 Minor 1: addressed.** The new poller retains dimension strings and primitive cursor state only. It holds no villagers, levels, section objects, callbacks or engine iterators across ticks. Removing the old map and array eliminates the reported tracker retention after close. The separate pre-existing journal world-clock reference is not claimed to be fixed.
- **Rereview 9 Minor 2: partially addressed.** Unknown empty-output classes, ordinary caps/unreadable copies, and brewing indexing gaps now qualify missing leaves. Smithing trim's illustrative chestplate is no longer indexed. Minor 1 and Minor 2 above prevent closing the computed-output portion completely.

Section deletion between ticks advances from the stored key using the current tree. Recreation at the same key can inherit a nonzero index for that cycle, but the next cycle resets it; shifted or earlier entries remain discoverable in a stable subsequent cycle. The Long.MAX_VALUE guard prevents key overflow. Exhaustion clears `started` and `index` and restarts from the current first section on the next tick, including after an empty store. There is no persistent iterator to invalidate.

Cursors are separate per dimension ID. The observation stamp comes from the iterated level; dimension transfer removes the old engine member and inserts the new one in the destination. The UUID profession baseline remains shared across dimensions. New world sessions create a fresh poller and baseline. `hook` gates polling to the integrated server thread, and section reads plus the observation callback remain synchronous there. No additional lifecycle, dimension or concurrency defect was established. As with polling generally, transient changes between observations and arbitrary perpetual entity churn are not guaranteed to be captured.

## Special-recipe and timing checks

The table contains all **14 registered vanilla special crafting classes**: the 13 `CustomRecipe` subclasses plus `MapExtendingRecipe`, and additionally `SmithingTrimRecipe`. There is no missing stock class in the list. The problem is the bypass and predicate assumptions described above.

The fixed-output predicates agree with the native constructors/assembly paths for rocket, star, star fade, tipped arrow, stew, map cloning, book cloning, decorated pot and shield decoration. Banner duplication copies a `BannerItem`. Shulker coloring produces a colored shulker box; its block-type predicate is a conservative superset. Armor dyeing checks the DYEABLE tag on its input and copies it. The trim and repair default cases match stock data, subject to Minor 2. Exact-class lookup conservatively treats unknown empty-output subclasses as unknown producers. Brewing reasons use distinct `native-brewing-*` keys, so the before/after reason-set size comparison detects their addition; later per-key copy failures still enter `incomplete`.

`JournalStats` counts totals at or over 1,000,000 ns, matching its printed wording and the round 7 criterion. The slowest hook and its global gameplay-hook index are protected by the same monitor used when formatting them. Timing producers and `endTick` run on the server thread; worker readers use the existing atomic counters/monitor. The warm-up constructs a discarded event without offering it to the journal, and its duration is assigned to world-start work. It does not prove that all first-use or GC costs are eliminated. No new timing-counter or warm-up defect was established.

## Supporting review axes and dispositions

- **Standards:** no required source-style finding is retained. The supporting reviewer suggested renaming `rest`; its meaning is clear from this short local scope, so this remains optional. It also requested changelog/roadmap edits, but the applicable native-readiness plan explicitly assigns those files to the orchestrator (`docs/plans/2026-10-26-v2-native-readiness.md:10`), and this review is authorized to write only its verdict.
- **Specification/correctness:** the supporting recipe review independently confirmed the map-extension issue and checked all stock special classes and trim JSON definitions. Its stock-data approval does not establish the data-pack and stack-component cases in Minor 2. Final retained findings: **0 Critical, 0 Important, 3 Minor**.
- The timing reviewer noted that the stop-event summary precedes its own elapsed-time update and that the writer's later summary can race with that update. This ordering exists unchanged at `08098f3`, so it is not a finding introduced by this range. Neither summary should be treated as an exact measurement of all shutdown work. It does not invalidate the new completed-gameplay-tick counters.

## Live acceptance status

Round 6's D4/J10r and the journal/plan payload successes remain recorded successes. J10r exercised the previous tracker; it does not accept the new section walk. M5r3 remains over the timing threshold. The new metrics and warm-up support round 7 investigation but do not themselves close M5. A single outlier is compatible with class loading or GC; the recorded maximum alone does not establish either cause. J8r3 and A3r3 reply-compliance failures remain recorded separately from their passing payloads. No build or source finding here promotes these cases to live PASS.

## Verification and limits

- Created `D:/Project/packsage-review-astra10`, detached at `f0f0767f8ae1849f1a72d4b85022add994ca67e7`.
- Ran `$env:JAVA_HOME='D:/Project/packsage/.tools/jdk/jdk-21.0.12.1'` and `./gradlew build -q` there: **exit 0**. No Gradle command ran in the main checkout.
- Ran `node D:/Project/packsage/build/tools/count.cjs D:/Project/packsage-review-astra10`: **100 XML suites; 1,212 tests; 1,207 passed; 5 skipped; 0 failures; 0 errors**. The skips are four symlink-dependent ConversationStore cases and the prompt-candidate artifact check, whose ignored candidate file is absent in this checkout.
- Engine sources were read from the detached build's `build/moddev/artifacts/neoforge-21.1.202-sources.jar` and its matching generated cache archive, `sourcesWithNeoForge_11f0d6ad409d086963d985f3ab70c58ad620c6b1_output.zip`. Fastutil source checks used `fastutil-8.5.12-sources.jar`.
- `git diff --check 08098f3 f0f0767 -- src` passed. No formatter or source/test edit was performed.
- Verified the detached worktree remained clean at its pinned HEAD, removed it, and confirmed the path and Git worktree entry were gone. Only this verdict is staged/committed by this review.
- The new poll test covers a static multi-section walk and per-call bound, not live visibility/churn/reload scenarios. The native recipe test checks an unknown empty output and default repair-item metadata, not the failure scenarios above. Those gaps are evidence limits; the findings themselves follow from the concrete code paths described here.
