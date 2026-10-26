# Astra final rereview 11: NOT READY

Reviewed on 2026-10-26, on `feat/packsage-v2`. Source review and detached build: **21d50d22e2e7137e18b9a29fd1f9b378022c3beb..fa0d545f4da99be7f4f20e603e43d4820883b027**. The branch advanced to `69ac6661927d4c0db6879d443ed1dd5439f8d783` during review; that commit adds only round 8 acceptance documentation. Its source is identical to the built commit, and its new acceptance evidence is considered separately below.

**Final source verdict: NOT READY. No Critical or Important finding remains; two Minor findings remain.** Map extension's planning-index defect is fixed. The trim/repair completeness predicates still have false-negative cases, and the new unchecked-material omission count can overstate the number of materials omitted. Consequently, not every earlier finding is closed or accepted.

Inputs: rereviews 4-10, every changed source/test hunk, relevant callers, the native-readiness plan, world-model and Minecraft-completion specifications, grounding instructions and tool descriptions, and controller rulings through round 7. Engine behavior was checked against the detached build's NeoForge **21.1.202 / Minecraft 1.21.1** sources. This review performed no live Minecraft interaction or installation.

## Findings

### Critical

None identified.

### Important

None identified. The Important findings carried through rereviews 4-10 are addressed, as detailed below.

### Minor 1 - Trim and repair predicates still mistake missing computed variants for complete recipe coverage

**Locations:** `src/main/java/dev/packsage/knowledge/nativegame/NativeRecipeReader.java:57-60,282-286,320`; `src/main/java/dev/packsage/knowledge/RecipePlanner.java:38,71-75,92,263`.

The new per-recipe predicate and component argument fix the specific plain-item trim and explicitly requested durability examples from rereview 10. Two valid cases still fail the eligibility check:

- **Component-sensitive trim base.** The trim predicate calls `isBaseIngredient(new ItemStack(item))`, ignoring the requested components. NeoForge's built-in `DataComponentIngredient` can require, for example, a nether star with `minecraft:custom_name` equal to `Special`. An exact vanilla `SmithingTrimRecipe` can use that ingredient as its base, with the sentry template and quartz. The default star fails the predicate, but the named star passes. The recipe's assembly copies that matching base and adds the trim. With no other source gap, the planner therefore calls this craftable output identity "obtained rather than crafted." An exact component request also receives the default-stack check. This does not require a custom recipe subclass: the component-sensitive ingredient is supported inside the exact class in the allowlist.
- **Wildcard repair request.** An empty requested component object means any variant in both `PlanSource.recipeOutputs` and the planner's output matcher. The public identity-only `plan_recipe` root always uses that empty object, and its description says routes cover component variants (`PackSageTools.java:255`). The repair predicate instead treats the empty object as though only default components could occur. Two saddles with `MAX_DAMAGE=10` and `DAMAGE=7` satisfy the exact repair recipe, yet `recipesComplete("minecraft:saddle", {})` returns true and the root plan calls the saddle obtained rather than crafted. Passing an explicit `minecraft:max_damage` component is fixed; the wildcard query remains wrong.

Correctly initialized, isolated JShell probes against the compiled checkout returned:

```text
PROBETRIM default=false named=true
PROBETRIM complete=true
PROBETRIM detail=materials with no recipe here, obtained rather than crafted=[minecraft:nether_star]
PROBEREPAIR matches=true
PROBEREPAIR complete=true detail=materials with no recipe here, obtained rather than crafted=[minecraft:saddle]
```

The trim assembly path is established by `SmithingTrimRecipe.java:34-51,76-77` in the generated source archive; `DataComponentIngredient.java:48-66` shows the component-sensitive test. These are source/in-memory failure cases, not installed reproductions. The root dynamic-output warning appended by the native provider does not retract the planner's explicit acquisition assertion.

Keep the completeness predicate conservative for wildcard requests and component-sensitive ingredients. An actual known matching variant, or uncertainty about matching one, must not become proof that no recipe exists. This is the remaining part of **rereview 10 Minor 2**, and hence of the incomplete-source finding carried through rereviews 7-9. It still conflicts with the no-absence-from-incomplete-evidence rule at `GroundingInstructions.java:37` and the recipe contract at `PackSageTools.java:255`.

### Minor 2 - Repeated omitted leaves inflate the unchecked-material count

**Locations:** `src/main/java/dev/packsage/knowledge/RecipePlanner.java:72-73,253,257,264-266`.

`uncheckedLeaves` remembers only the first 16 distinct identities. After it fills, every visit to an identity outside that set increments `omittedUncheckedLeaves`; an omitted identity is never remembered. The detail then describes this occurrence count as a lower bound on additional materials.

**Failure scenario:** a root needs five craftable intermediates. The first two expand to eight distinct raw materials each, filling the list. Each of the other three needs the same seventeenth raw material. All these recipes fit an ordinary crafting grid, and the traversal is well below the planner's node, branch and depth bounds. With an incomplete source for the raw leaves, there is one omitted material, but the detail says:

```text
more materials with no recipe found, at least 3 beyond the 16 listed
```

A source-free JShell probe using the compiled planner reproduced this exact result. The list is no longer silently truncated, but its new count is inaccurate. Count distinct omitted identities, or label an occurrence count as occurrences instead of claiming at least that many additional materials. The related older `omittedNoRecipeLeaves` behavior is not expanded into a separate finding in this range.

This is the remaining accuracy issue in the fix for **rereview 10 Minor 3**. The new test proves the 18-distinct-material case, but does not cover a shared omitted material reached through multiple branches.

## Disposition of every finding from rereviews 4-10

No rereview in this requested sequence introduced a Critical finding. The earlier Critical closures inherited by rereview 4 are not changed by this range. All Important findings in the sequence are closed; the accepted statistics-copy limitation remains explicitly accepted.

- **Rereview 4 Minor 1, composite progress/statistics continuation: CLOSED.** The retained `ListingRead` resumes the source acquisition position and then pages the copied result; the fix accepted in rereview 5 remains present (`ProgressTools.java:41-80`).
- **Rereview 4 Minor 2, unshrinkable first record: CLOSED, including its rereview 5 suffix follow-up.** The encoder skips an unencodable identity with a count, retains later encodable rows and preserves the provider continuation. The retained-tail correction accepted in rereview 6 remains present (`PackSageTools.java:465-505`).
- **Rereview 4 Minor 3, failed replay tail retention: CLOSED.** Failed delivery discards its newly created tail and accounts for its bytes (`PackSageTools.java:546-550`).
- **Rereview 4 Minor 4, rejected server job retained in queue: CLOSED.** Rejection removes and accounts for the job if it still owns the queue entry, preserving the close/submission ordering (`ServerWork.java:95-103`).
- **Rereview 5 Important 1, pending-loot registry: CLOSED.** Pending loot resolves through the owning server's reloadable registries (`MinecraftInventories.java:169-172`); the rereview 6 closure is unchanged.
- **Rereview 5 Minor 1, oversized record after an accepted prefix: CLOSED.** Covered by the retained-tail correction above, including the `[first, oversized, later]` ordering.
- **Rereview 5 Minor 2, statistics key copy: EXPLICITLY ACCEPTED LIMITATION.** The synchronized full `keySet().toArray()` remains at `MinecraftStatistics.java:193,209`. Rereview 6 accepted this alongside the owned advancement-reference copy. It is still not a strict approximately 5 ms acquisition guarantee.
- **Rereview 6 Important 1, falsely complete smithing with no inputs: CLOSED.** Exact smithing field extraction retains template, base and addition, with failure qualification. Trim output is unknown; transform component inheritance is PARTIAL. The input fix accepted in rereview 7 and remaining output qualification accepted in rereview 8 remain present (`NativeSnapshot.java:114-133,190-199`). Minor 1 above concerns a different remaining absence inference in planning.
- **Rereview 6 Important 2, untyped mount/menu bridge response: CLOSED.** The fallback for `getType()` throwing remains (`MinecraftDebugAdapter.java:180,519`).
- **Rereview 6 Minor 1, crafting remainders on non-crafting consumers: CLOSED.** Remainder inference stays restricted to the exact shaped/shapeless crafting consumers; the rereview 7 closure is unchanged.
- **Rereview 6 Minor 2, failed component encoding promoted to complete: CLOSED for the reviewed recipe path.** The explicit failure marker propagates a component-encoding constraint and downgrades decoding (`NativeSnapshot.java:199,358-359`).
- **Rereview 7 Important 1 and rereview 8 Important 1, mount ownership traversal: CLOSED.** The menu's actual horse is read directly; no nearby-entity search remains in that path (`JournalHooks.java:402-409`). The rereview 9 closure is unchanged.
- **Rereview 7 Minor 1, transform component completeness: CLOSED.** The component inheritance qualification accepted in rereview 8 remains; this does not claim full dynamic component modeling.
- **Rereview 7 Minor 2 and rereview 8 Minor 1, unsupported non-crafting acquisition assertion: PARTIALLY CLOSED.** Ordinary unreadable/capped source cases are qualified, and the obsolete unpromoted candidate-10 sentence was superseded. The remaining computed-producer false negatives are Minor 1 above. The general finding cannot yet be marked closed.
- **Rereview 9 Important 1-3, repeated membership rebuild, tracking-resumption loss and pre-admission/stale membership: CLOSED.** The engine-section walk accepted in rereview 10 remains unchanged: bounded visits, current accessibility checks and admitted engine storage, with no separate join/leave membership map (`VillagerPoll.java:31-50`).
- **Rereview 9 Minor 1, retained tracker entity references: CLOSED.** The poller retains only dimension keys and primitive cursor positions, not the old entity map/snapshot. The separate pre-existing journal world-clock reference was not part of that closure.
- **Rereview 9 Minor 2, dynamic/brewing completeness gaps: PARTIALLY CLOSED.** Unknown dynamic classes, brewing omissions, ordinary index/copy failures and caps remain qualified. The remaining known-producer predicate issue is Minor 1 above.
- **Rereview 10 Minor 1, map-extension planning index: CLOSED.** `MapExtendingRecipe` enters the computed-output path before its illustrative `MAP` can be indexed; its producer names `FILLED_MAP` (`NativeRecipeReader.java:48,161-174`). The map/trim regression passes, and round 8 separately records W21 PASS.
- **Rereview 10 Minor 2, trim/repair predicate breadth: PARTIALLY CLOSED.** The plain nether-star base and explicitly component-bearing repair requests are fixed. Component-sensitive trim bases and wildcard repair queries remain as Minor 1 above.
- **Rereview 10 Minor 3, unchecked-leaf truncation: PARTIALLY CLOSED.** Omission disclosure exists and the distinct-leaf test passes; repeated omitted leaves still overstate the material count, as Minor 2 above.

## Journal warm-up ruling and installed evidence

**I agree with the round 7 ruling as an explicitly amended steady-state M5 criterion.** `JournalStats.java:78-85` increments the tick number before testing `> 200`; ticks 1-200 remain in the total/max figures but are excluded from the steady-state count. An in-memory boundary probe with exactly 1,000,000 ns at ticks 200 and 201 returned respectively `1 of 200 (0 after the first 200)` and `2 of 201 (1 after the first 200)`. The threshold and boundary implement the ruling correctly. Gameplay hooks and `endTick` run on the server thread in the reviewed path.

Two qualifications remain part of that agreement: 200 ticks is ten seconds at nominal 20 TPS, not a wall-clock guarantee under lag or pause; and excluding warm-up changes the acceptance criterion rather than proving those early stalls were caused by class loading or GC. The benchmark workload must occur after warm-up, and the all-session outliers must remain visible.

The concurrent documentation-only commit `69ac666` records **round 8 on the same installed source `fa0d545`**. It reports a 15,016 ms delay before fixture work, 2,000 successful breaks plus the explosion, offered=written=2,002, overflowed=failed=hook failures=0, and both shutdown summaries show **2 slow ticks of 1,131, with 0 after the first 200**. Its maximum 9,679 us early tick remains recorded. I accept the recorded **M5r5 PASS under the amended criterion**, alongside A1r4 and W21 PASS. This is consideration of the tester's evidence, not a new live measurement by this reviewer or a general no-stall guarantee.

The earlier **J8 reply-compliance failure and A3 from-scratch reply-compliance failure remain recorded**, under the round 5/7 rulings. Round 8 explicitly does not reclassify older checks. They are not converted into successes by the source build or by the M5 pass, and the source findings above independently prevent final native-readiness approval.

## Supporting review axes

- **Standards:** no concrete documented-style breach or actionable smell in the requested source diff.
- **Specification/correctness:** a separate challenge review confirmed both Minor findings above. The initial supporting review also raised omission of computed routes when static candidates already exist. That is the existing bounded planner disposition recorded in rereview 5, not a newly established regression in this range's missing-candidate classification; it is retained as a limitation rather than reopened as a new finding. This verdict does not certify exhaustive decoding of every computed recipe. The initial warm-up concern is handled by the qualified ruling and subsequent round 8 evidence above.

## Verification and cleanup

- Created `D:/Project/packsage-review-astra11`, detached at `fa0d545f4da99be7f4f20e603e43d4820883b027`.
- Ran `$env:JAVA_HOME='D:/Project/packsage/.tools/jdk/jdk-21.0.12.1'` and `./gradlew build -q` there: **exit 0**. No Gradle command from this review ran in `D:/Project/packsage`.
- Ran `node D:/Project/packsage/build/tools/count.cjs D:/Project/packsage-review-astra11`: **100 XML suites; 1,214 tests; 1,209 passed; 5 skipped; 0 failures; 0 errors**. Skips remain four symlink-dependent ConversationStore cases and the local prompt-candidate artifact check absent in the fresh checkout.
- Ran focused, source-free JShell probes for the two predicate cases, repeated omitted material and tick-200/201 boundary. Initial standalone game-probe setup lacked the generated game classpath/loader/version initialization; only the corrected initialized run supports the game-probe conclusions above. No source or test file was authored.
- `git diff --check 21d50d2 fa0d545 -- src` passed. The worktree remained clean at the pinned HEAD; it was removed, and both its directory and Git registration were confirmed absent.
- The only repository file authored and authorized for this review commit is this verdict. No formatter, source edits, installation, live-game action, push or merge was performed. The concurrent acceptance documentation commit was preserved.
