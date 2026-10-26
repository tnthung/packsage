# Astra final rereview 7: NOT READY

Reviewed on 2026-10-26: **9561c4b6a9eb24bcd0400a803959aad1e67476dc..b402586ea92eb8415b3729f9e8a73b3eb980e30c**, on `feat/packsage-v2`. Source inspection and the build used a detached checkout at `b402586`. The shared branch continued advancing during review; later commits are outside this verdict.

Inputs included rereview 6, every changed source/test hunk, surrounding branch implementations, `docs/specs/2026-10-22-v2-world-model.md`, `docs/specs/2026-10-19-coherent-search-policy.md`, the native-readiness plan, the whole grounding prompt and relevant tool descriptions, and the controller rulings for native-readiness rounds 1-3. Candidate 10 was read from the shared checkout's unpromoted `build/prompt-candidates/candidate-10.txt`, SHA-256 `f3d62c12caec260ce88c5f1f8a6d403b0e16707ed88fbf5f1dfb1818bcc0da67`.

This is a source/build verdict. It does not certify the independently running round 4, promote candidate 10, or replace installed-default acceptance.

## Findings

### Critical

None identified.

### Important 1 - Mount-menu recording introduces an unbounded synchronous entity lookup

**Location:** `src/main/java/dev/packsage/journal/JournalHooks.java:391` (new lookup at `:385-401`; synchronous event dispatch at `:204-207`).

Every horse inventory whose slots do not directly expose an entity now calls `getEntitiesOfClass(AbstractHorse.class, player.getBoundingBox().inflate(16))` inside the server's container-open event. That API builds its entire result list before the loop can test inventory identity or execute `break`. The pinned engine implementation collects with an effectively unlimited result count; its section class lookup can also scan all stored entities to populate the class-specific collection. The recent-interaction fallback is reached only afterward, so even an ordinary known right-click takes this scan.

**Failure scenario:** opening a ridden horse/donkey inventory in a densely populated enclosure makes the journal synchronously enumerate and allocate the nearby-horse list on the server thread. A fixed radius does not cap entity density, traversal or allocation; there is no visit budget or tick continuation. This reintroduces the kind of engine traversal that the branch's bounded world-reader work removed. The hook merely measures elapsed time after the action; it cannot yield during it.

Resolve the menu's actual horse directly, or use an ownership lookup whose acquisition itself is bounded. Adding a limit or break to this loop after `getEntitiesOfClass` returns would not fix the traversal. The world-model anti-stutter requirement and native-readiness plan's bounded server-thread work apply to recording too. This is a source-established work-bound defect, not a claim that a live tick stall was measured in this review.

### Minor 1 - Smithing transform output components still claim complete decoding

**Locations:** `src/main/java/dev/packsage/knowledge/nativegame/NativeSnapshot.java:94-95,131-133,190-195,231-234`; exact variant filtering at `src/main/java/dev/packsage/knowledge/nativegame/NativeRecipeReader.java:258-260`.

The zero-input defect is fixed, and trims now have an explicitly unknown dynamic output. Transform recipes still serialize their static display result as a `VERIFIED`/`COMPLETE` output, however. The added prose constraint says the base item's components are retained, but neither qualifies the output-component coverage nor gives the exact-component matcher a dynamic relation it understands. In the pinned implementation, `assemble()` copies the base stack to the result item and then applies the recipe result's component patch; `getResultItem()` returns only that static result.

**Failure scenario:** upgrade a diamond sword with 7 damage. The resulting netherite sword has 7 damage, while the supposedly complete output record has empty component data. An exact lookup for that attainable output variant rejects the recipe. A source-free JShell probe against this checkout confirmed:

```text
PROBE transform=COMPLETE decoding=VERIFIED decodedComponents=JsonObject[values={}] actualComponents=JsonObject[values={minecraft:damage=JsonNumber[value=7]}]
PROBE matchingActualVariant=0
```

Preserve the known output item/count while qualifying the unresolved component-dependent result, or represent the inheritance/override semantics so variant consumers can use them. The explanatory sentence alone does not make the static output an exact variant. This is the remaining component-specific part of rereview 6's smithing caveat; ordinary input accounting no longer has the zero-cost failure.

### Minor 2 - Candidate 10 makes a missing recipe sound like established non-crafting acquisition

**Location:** `build/prompt-candidates/candidate-10.txt:28` (unpromoted candidate only). Relevant contracts: `src/main/java/dev/packsage/model/PackSageTools.java:255`, `src/main/java/dev/packsage/knowledge/nativegame/NativeRecipeReader.java:244-254`, and `src/main/java/dev/packsage/knowledge/RecipePlanner.java:63-67`.

The new sentence says: "When the answer names a material the plan lists without a recipe, say it is obtained rather than crafted."

**Failure scenario:** a plan reaches the native recipe-copy cap while expanding a craftable intermediate. `PlanSource` returns no candidates for that intermediate and records the cap; `RecipePlanner` consequently lists it among `no-recipe-leaves`, and the provider qualifies the result as partial. The new unconditional sentence nevertheless directs the answer to classify that intermediate as obtained rather than crafted. The same missing-route problem can arise from unreadable source data. The tool description explicitly says a bounded scan's missing route is not absence.

The controller's A3 requirement to explain the obsidian acquisition gap is valid, but this generalization needs a coverage guard. Explain that a material still needs to be obtained separately when the plan leaves its acquisition unresolved; assert that it has no crafting route only when the evidence establishes that within the relevant scope. This prevents a conflict with the existing incomplete-results-cannot-prove-absence rule. It is a required candidate revision before promotion, not a defect already introduced into candidate 9's shipped prompt. No model-compliance result is inferred from this wording review.

## Previous findings and other reviewed behavior

- **Rereview 6 Important 1, smithing inputs: addressed, with Minor 1 above remaining for transform components.** Exact-class reflection reads `template`, `base` and `addition`, in that order, and reflection failure clears partial input extraction and reports a concrete unknown-input constraint. Actual vanilla transform/trim regressions cover all three consumed inputs. Trim output is now unknown/PARTIAL rather than the illustrative iron chestplate. The change is narrowly pinned to the two vanilla classes; unknown subclasses retain qualified coverage.
- **Rereview 6 Important 2, untyped menus: addressed.** `menuType` catches the accessor's `UnsupportedOperationException` and returns the menu class name. Registered menu types retain their names. The regression exercises both paths; it does not claim a live mount-open check.
- **Rereview 6 Minor 1, non-crafting remainders: addressed.** Only exact shaped/shapeless crafting classes invoke item crafting-remainder callbacks. The milk-bucket smelting regression now has no bucket remainder while preserving consumed input semantics.
- **Rereview 6 Minor 2, encoding failure: addressed for the reviewed native recipe path.** Failed or non-object stack encoding returns the explicit unknown marker. Input/output/remainder copies propagate a concrete component-encoding constraint, preventing a verified recipe from staying COMPLETE. Successful empty components remain distinct from a failure. The failing-registry regression and successful-empty regression pass.
- **Log4j:** `DebugRequestLog` now attaches to the existing `LoggerConfig` and removes only its named appender. It filters exact `PackSage` logger events, and synchronized append/read/close protect its bounded request map. An in-memory probe using the capture class's logger context verified the same config object, delivery to both the existing appender and capture, rejection of an unrelated logger's lookalike timing message, and continued delivery to the existing appender after capture closed. No new logging or locking defect was established. This demonstrates appender coexistence, not installed `latest.log`/`debug.log` acceptance.
- **Mount identity and ownership:** the first horse-menu slot uses the horse inventory, and pinned `AbstractHorse.hasInventoryChanged(container)` is an identity inequality. Its negation therefore identifies the correct horse's backing container. The lookup and session contents execute on the owning server thread. The problem is the lookup's work bound, not that comparison or cross-thread game access. The change has no dedicated mount-session regression in this range; live round 4 remains separate evidence.
- **Resource packs:** `repository.reload()` runs on the client owner before available-ID validation and selection. The pinned implementation rediscovers packs and rebuilds the selection from selected IDs. The reload future is awaited by the bridge worker. This addresses discovering a newly added pack; no live resource-pack action was performed here.
- **Local time:** request context derives `playerLocalTime` from the same captured instant in the machine's system zone, retaining an offset and truncating only subsecond precision. The regression verifies instant/offset consistency. It supplies evidence for interpreting day words without changing journal storage or game-time semantics.
- **Undefined loot:** the revised wording reports the loaded registry's undefined-table consequence while retaining the table ID and seed classification. The existing pending-loot path still avoids rolling or opening the container. This is consistent with the W12 ruling; it does not prove that the model will repeat every required fact.
- **Controller rulings:** reviewed the round 1 note-origin/current-biome rulings, round 2 coverage/navigation/fixture distinctions, and round 3 logging, mount, loot, raw-material and resource-pack diagnoses. The revised PASS rulings are not new failures here. Recorded NOT RUN fixture cases remain unverified, and round 3's installed `ed08bd0` evidence cannot validate these later fixes. The accepted statistics-key-copy limitation from rereview 6 is unchanged.

## Candidate 10 as a whole policy

The comparison against `GroundingInstructions.java` at `b402586` found exactly two changed prompt paragraphs, each adding one clause/sentence; the rest matches candidate 9.

1. **Local-day interpretation fits.** The `playerLocalTime` clause belongs in the existing timestamp paragraph, keeps wall-clock dates separate from game ticks, and works with journal `from`/`to` and the owning first-read rules. It generalizes the J8 time-zone problem without introducing a fixture answer.
2. **Material acquisition needs the qualification in Minor 2.** Its location beside plan coverage/accounting is appropriate, but the unconditional inference conflicts with that paragraph's coverage rules, the search stopping policy, and the tool's explicit missing-route limitation.

No promotion or installed-default compliance claim is made. Candidate 10 remains under independent live testing, and the code default reviewed here remains candidate 9.

## Verification and limits

- Created `D:/Project/packsage-review-astra7`, detached at `b402586ea92eb8415b3729f9e8a73b3eb980e30c`.
- Used the verification-before-completion workflow: ran `$env:JAVA_HOME='D:/Project/packsage/.tools/jdk/jdk-21.0.12.1'` and `./gradlew build -q` there, with **exit 0**. No Gradle command ran in `D:/Project/packsage`.
- Ran `node D:/Project/packsage/build/tools/count.cjs D:/Project/packsage-review-astra7`: **99 XML suites; 1,208 tests; 1,203 passed; 5 skipped; 0 failures; 0 errors**. The skips are the four symlink-dependent ConversationStore cases and the locally absent candidate-9 artifact check.
- `git diff --check 9561c4b b402586 -- src` passed. The detached checkout had no source/test changes.
- Inspected the pinned generated NeoForge 21.1.202/Minecraft sources for horse menus/inventory identity, entity traversal, smithing assembly and resource-pack reload. The smithing and Log4j probes ran in isolated JShell processes, without authoring source/test files or launching Minecraft. The logging probe explicitly selected the capture class's logger context after an initial JShell caller-context mismatch; only the corrected-context result supports the conclusion above.
- Two parallel read-only Luna High supporting reviews covered standards/debug behavior and native specification behavior. The specification review independently raised the transform-component Minor. The initial standards review found no issue; a separate challenge round confirmed the mount traversal defect and candidate policy conflict after Astra supplied their concrete call paths. No documented-style violation or speculative refactor is raised.
- Removed the detached worktree and verified both its directory and Git registration were absent. No formatting, source edits, installation, live game interaction or prompt mutation occurred. This verdict is the sole authored repository file and the only file included in the requested review commit.
