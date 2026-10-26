# Astra final rereview 8: NOT READY

Reviewed on 2026-10-26: **612dfb44f7888532e8262a657a4ee3563daa923a..91750c26cd10f75b2b8b4a3ca6d0bd7ef0873bd4**, on `feat/packsage-v2`. Source inspection and build verification used a detached checkout at `91750c2`.

Inputs included rereview 7, every changed source/test hunk, surrounding branch implementations, the v2 world-model and Minecraft-completion specifications, the coherent-search policy, the native-readiness plan, the whole grounding prompt and tool descriptions, and the native-readiness round 4 controller rulings in `docs/validation/2026-10-25-v2-acceptance.md:36255`.

This is a source/build verdict. It does not replace the pending round 5 installed-default acceptance or assert that a live gameplay stall was measured.

## Findings

### Critical

None identified.

### Important 1 - The mount lookup limits matches, not traversal work

**Location:** `src/main/java/dev/packsage/journal/JournalHooks.java:408-409` (synchronous container-open dispatch at `:218-221`).

The replacement correctly stops after finding the owning horse and retains at most one horse result. It does not bound the work needed to find that horse. In the pinned NeoForge 21.1.202 sources:

- `net/minecraft/world/level/Level.java:903-907` evaluates the ownership predicate before adding a match and checking the result limit. Rejected horses consume no part of the limit.
- `net/minecraft/world/level/entity/EntitySection.java:43-49` obtains the class collection and iterates its members until the callback aborts.
- `net/minecraft/util/ClassInstanceMultiMap.java:58-64` builds an uncached class collection by scanning and collecting from all entities in that section, before the ownership predicate can run.

**Failure scenario:** open a ridden horse or donkey inventory in a dense enclosure where the owning mount occurs late in the section traversal. The hook synchronously visits the preceding horses on the server thread; a cold class lookup additionally scans the section's entire entity collection. The 8-block box bounds geometry, not entity density or the work preceding a match. Even a warm class collection has no visit or time budget. The recent-interaction fallback still runs only after this lookup.

This is the unresolved work-bound part of rereview 7 Important 1. `5322be0` improves result allocation and early termination after success, but does not establish the bounded acquisition required by the native-readiness plan and world-model anti-stutter rule. The controller's round 4 functional PASS for mount transfers remains valid evidence for those tested transfers; its description of the lookup as bounded does not establish a traversal bound.

Resolve the actual menu owner directly, or use acquisition with a real work bound and explicit handling of an unresolved owner. The pinned `HorseInventoryMenu` already retains the actual horse; another result limit or a smaller radius does not close this finding.

### Minor 1 - Missing recipe evidence still becomes an unqualified acquisition instruction

**Locations:** `src/main/java/dev/packsage/knowledge/RecipePlanner.java:253-254`; `src/main/java/dev/packsage/model/GroundingInstructions.java:42`. Relevant paths: `src/main/java/dev/packsage/knowledge/nativegame/NativeRecipeReader.java:244-260`, `src/main/java/dev/packsage/knowledge/RecipePlanner.java:62-68`, and `src/main/java/dev/packsage/knowledge/nativegame/NativeProvider.java:97-119`.

The new plan detail calls every empty candidate result a material "obtained rather than crafted." An empty result also occurs when an indexed recipe cannot be copied: `PlanSource` catches the failure, records `native-recipe-copy-unreadable=<item>`, and returns no records if all candidates fail. The planner then puts the item in `noRecipeLeaves`; the provider appends the unreadable-source reason and preserves PARTIAL coverage, but leaves the acquisition assertion intact.

**Failure scenario:** a modded recipe for a craftable intermediate has a readable result item but throws while its ingredients or other recipe fields are copied. A plan for an output needing that intermediate now labels the intermediate "obtained rather than crafted" despite having failed to establish its recipe. The same unconditional tool detail is emitted when a copy cap hides the intermediate's known indexed recipes.

Candidate 11 explicitly exempts caps or limits, which addresses the previous capped-search prompt example. It does not explicitly exempt failed/unreadable recipe evidence, and the tool detail itself is unconditional even for caps. Together they still conflict with `PackSageTools.java:255` (a missing route is not absence) and the prompt's existing rule at `GroundingInstructions.java:37` that incomplete, unavailable, unsupported or failed results cannot prove absence.

Describe these leaves as acquisition unresolved or materials still needed separately, without asserting that crafting is unavailable. Make any non-crafting classification depend on adequate relevant recipe coverage, including readable source data. This preserves the controller's A3 requirement to explain the actual acquisition gap without turning a failed lookup into an acquisition fact. It is a source/policy inconsistency, not an inferred live model-compliance failure.

## Previous findings and other reviewed behavior

- **Rereview 7 Important 1: partially addressed; still open above.** The ownership predicate and server-thread execution remain correct. The remaining issue is traversal before a match, not container identity or cross-thread access.
- **Rereview 7 Minor 1, smithing transform components: addressed.** `NativeSnapshot.java:132,194-197,236-247` preserves the known output item and quantity, explicitly marks component inheritance unresolved, and propagates PARTIAL decoding and record coverage into both typed and serialized records. It no longer describes the static display stack as a complete crafted variant. The regression still checks all three consumed inputs and planned requirements. Exact component-dependent planning remains limited; the public root `plan_recipe` query accepts an item identity, and the reviewed change honestly qualifies that limitation.
- **Smithing supporting-review objection resolved.** One supporting reviewer initially flagged exact component matching still rejecting a damaged variant. After checking the public query and downstream coverage, the challenge review withdrew that as a separate required issue: the prior verdict allowed qualifying unresolved output components, and no new false complete result or unqualified absence was established from this fix. This does not claim dynamic smithing components are now fully modeled.
- **Rereview 7 Minor 2, candidate 10: the explicit cap example is addressed; the remaining incomplete-source case and unconditional plan detail are Minor 1 above.** The change is in the appropriate planning paragraph, beside requirements, unresolved dependencies and coverage. It needs the same qualification in the tool evidence and the answer instruction.
- **Journal timing and thread safety:** `JournalHooks.java:105-140,325-348` measures synchronous journal opening, excludes lifecycle events from gameplay tick totals, and subtracts opening time when startup occurs through the post-tick fallback. All gameplay registrations now pass their event to the common failure boundary. Session timing fields are used on the owning server thread. `JournalStats.java:40-72,103-117` keeps counters atomic and protects the slowest hook's name/duration pair with the same monitor on write and read. No additional thread-safety defect was established in this range. The passing regression verifies lifecycle exclusion, aggregate tick time and the named hook; it does not prove the live sub-millisecond target.

## Candidate 11 and controller rulings

The complete default text extracted from the Java text block exactly matches `build/prompt-candidates/candidate-11.txt` in the shared checkout: **22,624 characters**, SHA-256 **`0960af8d90bdad1450d1a18c0866aa87799f4dbc257f52ad5326f23c3e0ae7c8`** for both. This was a full-content comparison, not just the existing length assertion.

- The `playerLocalTime` day-word instruction fits the existing timestamp paragraph and the journal tool's wall-clock `from`/`to` semantics. It preserves the distinction between local calendar dates and recorded game-tick spans. It does not change the owning first reads, reference/cursor rules, claim-specific coverage, batching or navigation policy.
- The material clause fits the planning stage but requires the remaining coverage qualification described above. The complete policy and tool descriptions were reviewed together; no other new conflict was established.
- The round 4 **W12r3 PASS** ruling is accepted: the required answer is the player-relevant sealed/undefined/empty loot status, not an unnecessary raw table ID.
- The recorded **J8r, W11r, N2, W18r and P9/J7r PASS** rulings remain recorded functional acceptance. The later mount implementation still needs its round 5 recheck and the source work-bound issue remains independent of that functional evidence.
- **A3r** remains a candidate-10 compliance failure pending the candidate-11 installed-default check. **M5r** remains a threshold failure pending the new gameplay/lifecycle timing check. The instrumentation change is not a passing M5 measurement. No round 5 result is inferred from this build or prompt equality.

## Supporting review axes

- **Standards:** no concrete documented-style violation requiring a change was identified. The supporting review raised the incomplete-source acquisition issue; no speculative extraction or formatting change is requested.
- **Specification/correctness:** the initial supporting review raised that acquisition issue and a smithing matching objection. A separate challenge round, supplied with the original brief, prior findings and concrete engine call paths, confirmed the mount traversal issue and the acquisition issue and withdrew the separate smithing objection for the reasons recorded above. Final source findings: **1 Important, 1 Minor**.

## Verification and limits

- Created `D:/Project/packsage-review-astra8`, detached at `91750c26cd10f75b2b8b4a3ca6d0bd7ef0873bd4`.
- Ran `$env:JAVA_HOME='D:/Project/packsage/.tools/jdk/jdk-21.0.12.1'` and `./gradlew build -q` in that worktree: **exit 0**. No Gradle command ran in `D:/Project/packsage`.
- Ran `node D:/Project/packsage/build/tools/count.cjs D:/Project/packsage-review-astra8`: **99 XML suites; 1,209 tests; 1,204 passed; 5 skipped; 0 failures; 0 errors**. The skips are four symlink-dependent ConversationStore cases and the candidate artifact check, because the ignored candidate file is absent in a fresh worktree. The separate full-content comparison above covers default/candidate equality without claiming live compliance.
- `git diff --check 612dfb4 91750c2 -- src` passed. The detached worktree had no source/test changes.
- Inspected the pinned generated NeoForge/Minecraft source archive directly for entity traversal and menu ownership. No extra source/test files, test fixtures, live game operations or installations were created.
- Removed the detached worktree and verified its directory and Git registration were absent. No formatting tools were used. This verdict is the sole authored repository file and the only file staged for the requested commit.
