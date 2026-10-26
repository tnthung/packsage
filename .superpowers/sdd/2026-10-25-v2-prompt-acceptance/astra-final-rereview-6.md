# PackSage v2 final rereview 6

**Verdict: NOT READY.** Two Important findings block this range: smithing recipes are falsely complete with no ingredients, and the new mount-inventory bridge operation throws while describing the menu it just opened. Two Minor recipe-evidence findings remain. No Critical finding was identified. The previous pending-loot and retained-tail findings are addressed; the statistics key-copy limitation is accepted as described below.

Reviewed on 2026-10-26: **870d511991c68b916c5345258c6ed1ac96fda237..ed08bd0f3b4eb99abb85d7568074f65d88e60cd0**, on `feat/packsage-v2`. Source inspection and the build used a detached checkout at `ed08bd0`. Inputs included rereview 5, the whole changed source/test range in branch context, `docs/specs/2026-10-22-v2-world-model.md`, `docs/specs/2026-10-19-coherent-search-policy.md`, `docs/plans/2026-10-26-v2-native-readiness.md`, the complete grounding policy and tool descriptions, and the recorded candidate-9 acceptance evidence. This is a source/build verdict, not certification of the independently running installed round 3.

## Findings

### Critical

None identified.

### Important 1 - Smithing recipes become COMPLETE despite having no decoded inputs

**Locations:** `src/main/java/dev/packsage/knowledge/nativegame/NativeSnapshot.java:109-115,167-175,207-217`; consequence in `src/main/java/dev/packsage/knowledge/RecipePlanner.java:87-88,115,182-190`.

The new exact-class allowlist includes `SmithingTransformRecipe` and `SmithingTrimRecipe`, but extraction still reads ingredients only through `recipe.getIngredients()`. In the pinned Minecraft/NeoForge sources, neither smithing class nor `SmithingRecipe` overrides that method: `Recipe.getIngredients()` returns an empty list. The new classification removes the old unknown constraints and promotes these records to `VERIFIED`/`COMPLETE`.

For the ordinary netherite-sword upgrade, `search_recipe` therefore exposes an apparently complete recipe with zero ingredients instead of the upgrade template, diamond sword and netherite ingot. Planning accepts that empty input list as fully resolved. A source-free JShell probe against this build constructed that vanilla recipe and returned:

```text
SMITHING COMPLETE VERIFIED inputs=0 constraints=[recipeType=smithing, serializer=...]
PLAN COMPLETE COMPLETE detail=null
```

There is a related output caveat within the same unsupported smithing classification: `SmithingTrimRecipe.getResultItem()` is an illustrative iron chestplate with an example trim, whereas `assemble()` derives the actual item and trim from the supplied base, template and material. `SmithingTransformRecipe.assemble()` also preserves base components. Treating the display result as an exact complete output loses those semantics.

Decode the smithing fields and dynamic output semantics explicitly, or retain concrete incomplete/unknown coverage for these classes. Add regressions using the actual vanilla smithing implementations. This violates the specifications' honest record coverage and the prompt's complete-requirements/material-accounting contract; the model cannot repair falsely complete source evidence by following the current policy.

### Important 2 - Mount inventory opening returns HTTP 500 after opening the menu

**Location:** `src/main/java/dev/packsage/debug/MinecraftDebugAdapter.java:180` (new mount path at `:161-165`).

Every successful `openInventory=true` call reaches `player.containerMenu.getType()`. Vanilla `HorseInventoryMenu` passes `null` to its superclass constructor, and `AbstractContainerMenu.getType()` throws `UnsupportedOperationException("Unable to construct this menu by type")` for that menu. Consequently, opening a tamed donkey or mule through the newly documented endpoint performs the action and then returns HTTP 500 instead of its `menuId`, `opened` and menu description. The same added line regresses ordinary `/player/open` interactions that leave `InventoryMenu` active, since that menu also has no type.

The pinned generated game sources establish both constructors and the throwing accessor. A focused in-memory accessor probe confirmed the exception. No live player interaction was performed. The bridge tests only echo validated bodies through a fake game adapter and do not exercise the actual horse menu.

Represent these special menus without requiring a registered `MenuType`, and preserve a successful response after a successful open. Track G specifically exists to unblock the mount-menu acceptance checks, so this is a readiness blocker even though normal bridge-disabled users are unaffected.

### Minor 1 - Crafting remainders are applied to non-crafting consumers

**Locations:** `src/main/java/dev/packsage/knowledge/nativegame/NativeSnapshot.java:104-110,135,154-163`.

The same allowlist controls consumption and calls to `hasCraftingRemainingItem`/`getCraftingRemainingItem`. Those item callbacks describe crafting remainders; they do not establish that the furnace or stonecutter returns that remainder from its input slot. For example, a valid datapack smelting recipe using a milk bucket is reported as returning a bucket, although `AbstractFurnaceBlockEntity.burn()` consumes one input with `shrink(1)` and does not return its crafting remainder. Its separate fuel-remainder handling does not apply to the recipe input. `StonecutterMenu` likewise removes its input directly.

A probe with an exact vanilla `SmeltingRecipe` from milk bucket to stone returned `COMPLETE` with `remainders=[minecraft:bucket x1]`. The generated furnace source confirms that this is not its input-consumption behavior. Restrict remainder inference to the actual consumer semantics, with a non-crafting regression. This needs a datapack/custom input to demonstrate the false returned bucket and is nonblocking on the vanilla recipe set, but the returned fact and any leftovers based on it are wrong.

### Minor 2 - Component encoding failure is still promoted to complete evidence

**Locations:** `src/main/java/dev/packsage/knowledge/nativegame/NativeSnapshot.java:207-210,321-338`.

`codecJson` handles a codec failure by returning an empty object or an `encoding=unknown` object. `componentPayload` then converts either to empty components, without propagating failure to `decoding`. Now that ordinary native recipes can be `VERIFIED`/`COMPLETE`, an unreadable component-bearing output can appear to be a completely decoded default variant.

A focused failure-path probe supplied a registry provider whose serialization-context lookup throws, and an exact vanilla `ShapelessRecipe` output carrying damage. The result was `ENCODING_FAILURE COMPLETE VERIFIED outputComponents={}`. This is a synthetic encoding-failure probe, not an observed installed vanilla failure. Preserve the distinction between successfully encoded empty components and failed encoding, and downgrade the affected record with a concrete reason. The uncommon failure path makes this Minor; it nevertheless undermines component matching and the complete-record stopping rule. The specification supporting reviewer independently identified this finding.

## Prior findings and other reviewed behavior

- **Rereview 5 Important 1, pending loot: addressed.** `MinecraftInventories` now resolves the owning `ServerLevel`'s server and uses `server.reloadableRegistries().get()`, matching the pinned loot-registry contract. Pending-container inspection still returns no slots and never rolls the table. This source verdict does not substitute for the W12r installed check.
- **Rereview 5 Minor 1, retained oversized suffix record: addressed.** The retained-tail loop shrinks each oversized record, skips only an identity that still cannot fit, counts that omission and continues retaining later rows. The exact `[first, oversized, later]` regression delivers `later` on replay and preserves `provider-next`. The single-use cursor binding, failed-delivery cleanup and final 131,072-byte UTF-8 check remain intact; no new cursor or shared-output byte-bound defect was found.
- **Rereview 5 Minor 2, statistics keys: accepted limitation.** I agree with the controller's comparison to the owned advancement-reference copy. `MinecraftStatistics.java:192-194,201-210` still copies the entire key set in one synchronized step, so this is not a strict approximately 5 ms acquisition guarantee. No new regression or tick-latency measurement changes that assessment, and it is not a blocker for this verdict.
- **Track F, bounded work and accounting:** tag expansion is confined to planning, cached by tag identity, and copies member consumption from each recipe rather than the cache. Registry-order selection, the 4,096 registry scan cap, 16-member cap, 512 recipe-copy cap and planner node/branch bounds remain explicit. Equal alternative groups aggregate their batch demand before expansion; the piston regression establishes 30 planks, 8 four-plank batches and 32 produced planks. PARTIAL plans now carry concrete missing-leaf, depth, branch, quantity, source or constraint reasons. These bounded counts are not evidence of a hard per-frame time ceiling. The native-completeness exceptions above still require correction.
- **`search_recipe` and policy:** allowing an exhausted search of fully decoded ordinary recipes to become COMPLETE is consistent with the policy's distinction between discovery and record completeness. Unread pages still qualify coverage and preserve continuation. Tags with unestablished remainders, custom ingredients and unknown recipe subclasses remain qualified. The failures above concern incorrect record classification, not a need to weaken the policy.
- **Candidate 9:** the withdrawal sentence is integrated into note lifecycle handling and fits the owning `search_journal notesOnly` read, historical-evidence rules, silent writes and corrections. History does not regain authority to restore a withdrawn player note. The recorded J12r/N1 calls and replies support the temporary candidate's tested behavior; promotion alone does not prove the installed default with its override cleared, which remains round 3's check.
- **Track G, safety and disabled behavior:** construction stays under the explicit bridge setting/JVM flag. Endpoints remain loopback-only and bearer-authenticated before dispatch; the random token is not returned by these new operations or logged. Game-object operations are dispatched to client/server owners, while workers wait for their futures. Normal quit is scheduled after response flush. The new operations are not model tools, and the adapter-popup request flag remains false when unused. No additional token, ownership or bridge-disabled regression was identified. Endpoint routing tests are not live transition/reload/menu proof.
- **Logging:** `request_built` now writes offered names as one comma-joined field. No behavior or secrecy regression was identified in that change.

## Verification and limits

- Created `D:/Project/packsage-review-astra6` with detached HEAD at `ed08bd0`.
- Ran `$env:JAVA_HOME='D:/Project/packsage/.tools/jdk/jdk-21.0.12.1'` and `./gradlew build -q` there: **exit 0**. No Gradle command ran in `D:/Project/packsage`.
- Ran `node D:/Project/packsage/build/tools/count.cjs D:/Project/packsage-review-astra6`: **99 XML suites; 1,201 tests; 1,196 passed; 5 skipped; 0 failures; 0 errors**. Skips remain four symlink-dependent ConversationStore cases and the local-candidate-dependent GroundingInstructions case.
- `git diff --check 870d511 ed08bd0 -- src` passed.
- Inspected the pinned generated Minecraft/NeoForge 21.1.202 sources for smithing, menus and furnace/stonecutter consumption. Ran focused in-memory JShell probes against the compiled checkout; no source or test file was authored. Initial standalone bootstrap attempts needed an empty loading-mod list and game-version initialization before the successful probes. The menu probe exercises its untyped accessor, not a running game. The codec probe deliberately injects serialization failure.
- Two read-only Luna High supporting reviews covered standards/debug operations and native/specification behavior. The standards review reported no concrete finding; the specification review reported the encoding-coverage Minor. Astra independently established the smithing, menu and remainder findings and verified the encoding failure path.
- Removed the detached worktree and confirmed that its registration and directory were absent. No formatting, installation, Minecraft launch or live interaction occurred. This verdict is the sole authored repository file and the only file authorized for the review commit.
