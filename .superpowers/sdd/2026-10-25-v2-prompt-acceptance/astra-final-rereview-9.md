# Astra final rereview 9: NOT READY

Reviewed on 2026-10-26: **d378ba606b20e1cb2324ae43fa1683c843fb85c2..89c53a86ffd007a8714b062012fe1d066716b372**, on `feat/packsage-v2`. The review and detached build are pinned to `89c53a8`, before this verdict commit.

Inputs: rereview 8, every changed source/test hunk and relevant callers, the native-readiness plan, world-model and Minecraft-completion specifications, coherent-search policy, grounding instructions and tool descriptions, and round 5 controller rulings at `docs/validation/2026-10-25-v2-acceptance.md:37727`. Engine behavior below was checked in the build-generated NeoForge 21.1.202 source archive. This is a source/build verdict; no live gameplay or installed-default acceptance was performed.

## Findings

### Critical

None identified.

### Important 1 - Membership changes still cause an unbounded gameplay scan

**Location:** `src/main/java/dev/packsage/journal/JournalHooks.java:514-520`.

Every join or leave sets `trackedChanged`. The next poll traverses the entire map and allocates a full `Villager[]` before applying the 16-observation limit. The number of tracked villagers has no work bound.

**Failure scenario:** a world has many loaded villagers and recurring breeding, deaths or chunk loads/unloads. Each affected tick performs O(all tracked villagers) traversal/allocation, even when only one member changed. The 16-entry loop does not bound this work or its allocation pressure. This violates the bounded server-work requirement in `docs/plans/2026-10-26-v2-native-readiness.md:9` and the anti-stutter requirement at `docs/specs/2026-10-22-v2-world-model.md:82`.

Maintain the polling structure incrementally or pace rebuilding itself. The one-time startup seed is an initialization cost; it does not justify this repeated gameplay copy. No measured stall duration is inferred from source inspection.

### Important 2 - A temporarily untracked villager can disappear from polling permanently

**Location:** `src/main/java/dev/packsage/journal/JournalHooks.java:239-243`.

The pinned engine's join and leave events are not a symmetric membership pair. `PersistentEntitySectionManager.java:149-179` calls `stopTracking` when a chunk becomes HIDDEN and `startTracking` if accessibility returns. `ServerLevel.java:1796` emits `EntityLeaveLevelEvent` from `onTrackingEnd`; its `onTrackingStart` at `:1742-1767` emits no join event.

**Failure scenario:** a villager's chunk loses and regains accessibility before its entities are actually unloaded. This is a supported path: unloading defers while entity loading is PENDING (`PersistentEntitySectionManager.java:189-192`), and renewed accessibility removes the pending unload (`:155-157`). For example, a villager spawned or moved into a chunk during its pending entity read can survive this transition. PackSage removes it on Leave, but the surviving instance resumes tracking without Join. Its later profession changes are never observed until an actual unload/reload or a new world session.

Use lifecycle signals or bounded reconciliation that cover tracking resumption as well as physical additions/removals. This is a source-established failure path, not a live reproduction.

### Important 3 - Join events are admitted before the engine accepts the entity

**Locations:** `src/main/java/dev/packsage/journal/JournalHooks.java:233-237,521`.

`PersistentEntitySectionManager.java:78-85` posts the cancellable join event before adding the entity or checking UUID uniqueness. PackSage immediately inserts/replaces the entry at NORMAL priority.

**Failure scenarios:** a lower-priority listener cancels a villager addition, or a mod calls `ServerLevel.addFreshEntity` with a duplicate UUID and the engine rejects it. Neither produces a balancing Leave. The rejected duplicate can replace the real villager in PackSage's map, so subsequent changes to the real villager are missed; a canceled instance can remain referenced and polled indefinitely. Moving the listener to a later priority alone does not solve UUID rejection.

There is also a removed-entity retention path: a pending entity load completes after its chunk becomes HIDDEN. The manager can add those entities and then unload them in the same tick (`:243-256`). Because they never became accessible, removal does not call `stopTracking` (`:458-465`) and emits no Leave. PackSage retains the removed villager; `isRemoved()` only skips it and never evicts it. Repeated delayed loads can grow this stale set during the session.

Confirm actual admitted membership before replacing a live entry, and remove stale/removed entries within the polling work bound. The journal's profession baseline must continue to describe the real entity.

### Minor 1 - The new tracker retains entity references after world close

**Locations:** `src/main/java/dev/packsage/journal/JournalHooks.java:72-75,178-184`.

**Failure scenario:** leave a world after at least one villager has been included in `trackedSnapshot`. Stopping disables polling; Stopped closes the journal and clears only `session.server`. Even if shutdown leave events empty the map, no later poll rebuilds the snapshot. The event-bus listeners retain `JournalHooks`, its session and that array until another world starts. The map can also retain entries for which no leave was received.

Clear both newly retained collections/references at the stop boundary, including failure paths. Normal leave during active recording releases the old snapshot on the next poll, and `newSession()` releases it at the next start; neither provides close-time cleanup. This is a narrow new lifecycle defect. It is not claimed to be the sole cause of whole-world retention: the pre-existing `Journal.Session.gameTime` also retains a world reference, and these tracker sessions do not accumulate across successive starts.

### Minor 2 - Recipe completeness still omits dynamic and brewing source gaps

**Locations:** `src/main/java/dev/packsage/knowledge/nativegame/NativeRecipeReader.java:237,245-247`; `src/main/java/dev/packsage/knowledge/RecipePlanner.java:71-73`. Related source paths: `NativeRecipeReader.java:135-149`, `NativeBrewingReader.java:149-192,234-243`.

The new signal correctly covers ordinary recipe-index caps, unreadable ordinary outputs, and per-key copy caps/failures. It does not cover dynamic outputs skipped during indexing or brewing rules omitted before an output key is established. Brewing adds reasons to the same source but never sets its `indexIncomplete` flag. The dynamic-output warning is restricted to the first missing root lookup.

**Failure scenarios:** a readable ordinary mod recipe consumes a firework star whose special recipe computes its output from the crafting grid; or it consumes an intermediate made only by an opaque custom brewing rule or a rule beyond the 8,192-rule brewing cap. The corresponding lookup is empty, `recipesComplete` returns true, and the material is still labeled "obtained rather than crafted." For brewing, the provider subsequently appends the partial-source reason without retracting that assertion. For a dynamic intermediate, even the root-only dynamic warning is absent.

This is the remaining source-completeness part of rereview 8 Minor 1, not a newly introduced wording regression. Propagate relevant indexing gaps from every contributing recipe source, or otherwise establish sufficient item-specific coverage before classifying a leaf. PARTIAL coverage alone does not correct the acquisition assertion. The current behavior still conflicts with `GroundingInstructions.java:37,42` and `PackSageTools.java:255`.

## Previous findings and other checked behavior

- **Rereview 8 Important 1: addressed.** `JournalHooks.java:427-434` reads `HorseInventoryMenu.horse` directly through `src/main/resources/META-INF/accesstransformer.cfg:17`. The generated menu source confirms that this is the actual constructor-supplied owner. No entity search remains in this ownership path.
- **Rereview 8 Minor 1: partially addressed.** The originally demonstrated ordinary-copy failure/cap cases, plus ordinary index caps and unreadable outputs, now become `recipe-lookup-incomplete`. Dynamic and brewing omissions remain as Minor 2 above.
- **Normal dimension transfers:** the old instance leaves and the new instance joins under the same UUID; identity-conditional removal protects a replacement. Polling derives the dimension from the current instance. No additional dimension-transfer defect was established.
- **Thread ownership and resets:** `hook` rejects work off the integrated server thread and listeners verify the owning server/level. Startup replaces the session and seeds once per server. No additional concurrent map access or cross-world baseline reuse was established. The missing close-time reference cleanup is separate.
- The changed journal test updates listener/failure counts; it does not exercise membership, tracking resumption or reference cleanup. The new planner regression exercises the completeness interface, not every native source that must supply it.

## Round 5 controller rulings

- **D3, N3 and A1r3 PASS:** accepted as the recorded installed-default results. This review does not rerun them.
- **M5r2:** agree that removing the periodic all-entity scan addresses a real cost, and that round 6 must measure the result. Disagree that `a750664` establishes a fully bounded/correct polling implementation: Important 1-3 remain. A named slowest hook identifies the handler, not a proof that its replacement meets the timing threshold.
- **J8r2 journal PASS / reply FAIL:** agree. The recorded transfers and local-day filtering worked; the reply omitted the donkey withdrawal amounts and coordinates required by the explicit acceptance criterion. Existing returned-set and coordinate rules already cover this. Recording the failure is preferable to adding another fixture-specific rule.
- **A3r2 prompt-compliance FAIL:** agree. The supplied obsidian material detail was not explained in the reply. Retaining this as an open compliance failure after the prior prompt changes is reasonable; it is not evidence of acceptance or a reason to add a scenario-specific rule. The source-completeness issue above is separate from this tested obsidian omission.
- Both prompt failures remain failures. Their classification and the decision not to add more fixture wording must not be read as closing the native-readiness plan's live-acceptance gate.

## Supporting review axes

- **Standards:** approved; no concrete required style violation or actionable smell identified.
- **Specification/correctness:** supporting review identified the full snapshot copy, close-time retention and dynamic-output gap. Focused challenge reviews confirmed the engine lifecycle asymmetry, rejected-addition problem and remaining brewing/dynamic completeness gaps. The stronger claim that the new tracker uniquely causes a whole-world leak was withdrawn. Final findings: **3 Important, 2 Minor**.

## Verification and limits

- Created `D:/Project/packsage-review-astra9`, detached at `89c53a86ffd007a8714b062012fe1d066716b372`.
- Ran `$env:JAVA_HOME='D:/Project/packsage/.tools/jdk/jdk-21.0.12.1'` and `./gradlew build -q` there: **exit 0**. No Gradle command ran in the main checkout.
- Ran `node D:/Project/packsage/build/tools/count.cjs D:/Project/packsage-review-astra9`: **99 XML suites; 1,210 tests; 1,205 passed; 5 skipped; 0 failures; 0 errors**. Skips are four symlink-dependent ConversationStore cases and the prompt-candidate artifact check, whose ignored candidate file is absent in the detached checkout.
- `git diff --check d378ba6 89c53a8 -- src` passed. The detached checkout had no source/test or untracked changes before removal.
- Inspected `build/moddev/artifacts/neoforge-21.1.202-sources.jar` from that build for actual menu ownership, entity addition, tracking visibility, removal and dimension transitions. No extra tests or fixtures were written.
- Removed the worktree and verified that both its directory and Git registration were absent. No formatting tools, installations or live game actions were used. This verdict is the sole authored repository file and the only file staged for the requested commit.
