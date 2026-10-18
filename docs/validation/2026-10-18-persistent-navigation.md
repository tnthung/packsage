# Persistent navigation validation

Date: 2026-10-18.

Scope: [specification](../specs/2026-10-18-persistent-navigation.md), [implementation plan](../plans/2026-10-18-persistent-navigation.md), [JEI identity research](../research/2026-10-18-persistent-jei-navigation.md) and [guide identity research](../research/2026-10-18-persistent-guide-navigation.md).

## Automated evidence

Final source through `e3a91a1` passed portable Java 21.0.12.1 `./gradlew.bat test build` with Gradle 8.11.1, Minecraft 1.21.1 and NeoForge 21.1.202. The full XML reports contain 577 tests across 50 suites: 573 passed, four existing skips, zero failures and zero errors. Existing JEI deprecated-API and unchecked-operation warnings remain. The initial source-only build through `b7f47a0` had 576 tests; the installed-path regression below adds the final test.

- The focused baseline passed before implementation. New regressions first failed on missing guide resolver APIs, missing durable recipe IDs, old Patchouli lifecycle binding and old-scope web refusal.
- `KnowledgeServiceTest` (14 tests) includes a conversation JSONL round trip loaded by a new store, then resolves the saved destination through a new provider with a different source handle and current scope. The historical target remains unauthorized for direct opening, and navigation does not create model evidence references. Foreign-provider/scope results and late completion cannot authorize opening.
- `PonderGuideReaderTest` (17) and `GuideDocumentProviderTest` (13) cover registration replacement/reordering, canonical component IDs, changed scope, current client-executor checks, unavailable/malformed destinations and zero storyboard compilation during destination resolution. The viewer still opens a component, not an exact scene.
- `JeiProjectionTest` (24) recreates plugin runtime state and recipe objects, changes play-session/resource/runtime scope, resolves the same category/canonical recipe ID and opens the current exact object. It covers legacy canonical handles, duplicate/missing/hidden/malformed targets, category-only restoration, temporary no-ID handles and ordinary request progression. A real custom-category discovery fixture first reproduced lost canonical IDs, then passed after opaque projections retained those IDs; current visibility is still revalidated when resolving/opening.
- The JEI category restoration regression reproduced a null recipe being passed to recipe-handle construction. Category rehydration now creates a category handle and opens `showTypes`. Navigation traversal shares the provider callback work budget separately from semantic extraction. Test fixtures advance client ticks between lookups, matching the existing one-window-per-tick throttle.
- `PatchouliProviderTest` (19) covers lifecycle-free page destinations and canonical legacy lifecycle migration after reload, current entry/page availability, removal and malformed page/lifecycle values. Existing hidden/locked page and scope checks still pass. `FtbProviderTest` (18) includes saved quest navigation with a recreated provider and rejects removed quests.
- `AssistantRuntimeTest` (34) verifies saved same-world URLs after conversation reload and retains foreign-world read-only refusal, historical source membership, session-selection and late-navigation-result checks.
- `ClientAssistantTest.optionalProviderForwardsSavedDestinationResolutionToTheCurrentDelegate` first failed through the actual optional-provider wrapper used by the installed client. The wrapper now forwards navigation resolution to its current delegate and fails closed when the delegate disappears. Direct provider tests alone had missed this host path.
- The large-category JEI regression first failed with a unique target at ordinal 4,095. It now refreshes and opens the exact current recipe with a populated ingredient projection, rejects a duplicate beyond the old 2,048 window, accepts a unique final recipe at exactly 65,536 entries, and rejects 65,537 entries even with the target first. Canonical callbacks stop at 65,536; the stream iterator can prefetch one additional entry in `hasNext`. Semantic extraction remains limited to 2,048 values. Both canonical resolution and retained-object membership share the bounded navigation callback allowance.

Manual style inspection followed the existing code conventions. No formatter was used. Relative documentation links and `git diff --check` passed. The two pre-existing uncommitted validation documents were preserved.

Independent review covered shared resolution, Ponder/Patchouli, saved URLs and the final JEI implementation through `b7f47a0`. Its custom-category identity finding was reproduced and corrected. The final review found no remaining actionable correctness issue, including the category restoration, shared-budget, temporary-link request progression, current registration/visibility and late-result paths.

Follow-up review covered the optional-provider forwarding fix and the JEI scan-bound correction through `b787266`; no actionable defect remained. Architecture review accepted the measured occasional viewer-opening delay and retained the synchronous bounded implementation. Final probe removal was inspected directly; no diagnostic navigation logging remains.

## Installed Minecraft evidence

The authorized live check used the existing CurseForge `Packsage` fixture profile and its `New World`, with Minecraft 1.21.1, NeoForge 21.1.259, JEI 19.64.0.455, 24 active mod JARs and 34 mods shown by Minecraft. The existing fixture resource pack remained enabled. Every JAR replacement followed a normal save/quit and a process check confirming Minecraft had exited. PackSage settings retained their original SHA-256 throughout installation and testing.

The first installed run exposed the missing optional-provider forwarding: saved Ponder resolution fell back to the old source handle. The wrapper regression reproduced it, and forwarding corrected it. The subsequent JEI probe parsed the legacy torch identity and found it, then rejected the remaining crafting-category scan at 2,048 entries. The corrected scan completed all 2,963 crafting recipes and preserved uniqueness checks. Temporary diagnostic logs are retained locally under ignored `.tools/live-persistent-navigation/`; the final artifact contains no probes.

| Saved destination | Observed native result |
| --- | --- |
| Legacy JEI `jei/1/1/minecraft:crafting/recipe-minecraft:torch-1` from the October 17 answer | Opened the exact torch crafting view after a full process restart: coal or charcoal plus a stick produces the fixture's torch output. |
| Fresh JEI `recipe-v1\|minecraft:crafting\|minecraft:torch` | Created through the ordinary in-game model workflow, saved to JSONL, then opened after two subsequent full Minecraft restarts, including the final probe-free artifact. |
| Legacy Ponder component `create:mechanical_press` from the earlier saved answer | Opened the native viewer titled "Processing Items with the Mechanical Press" after restarting Minecraft. |
| Fresh Ponder component `create:mechanical_press` | Created with the fresh torch answer, restored from disk and opened the same animated native component viewer after both subsequent restarts, including the final artifact. |
| Legacy Patchouli `naturesaura:book#naturesaura:intro/aura#1@lifecycle=0` | Opened the loaded "Aura, conceptually" entry, showing the fixture resource-precedence text in the native book. |
| FTB quest `1000000000000001` from the older saved config/quest answer | Opened the native "Gather the fixture input" quest detail, with the coal task complete. |

The fresh UI trial was request `6c2b7d27-6323-4d06-ad69-63cf6b50a20e`, persisted in `eef677b1-7015-46e0-a354-c84b8ff1e246-f311626224b89e79.jsonl`. Its JEI source handle remained runtime-local while its saved destination was the stable category/recipe pair. Ponder retained the stable component ID with a runtime-local registration handle. No conversation files were edited to manufacture targets, and no autonomous scenario runner was added.

The instrumented first JEI click completed canonical resolution of 2,963 crafting recipes in 77.3436 ms; a later legacy-link click completed it in 13.1021 ms. Log timestamps place first-click resolution plus projection/open revalidation at about 103 ms. These individual measurements do not establish a latency guarantee for larger categories or arbitrary category callbacks.

## Initial artifact (2026-10-18)

Built JAR: `build/libs/packsage-0.1.0.jar`. Distribution: `dist/packsage.jar`; matching metadata is in `dist/build-metadata.json`.

The built JAR, distribution JAR and installed `Packsage/mods/packsage.jar` all have SHA-256 `DB1211BFF7CDA5EA4A81A1303CF24A80D887223D7A7B4035C70FCEB1247BDE0B`. Distribution metadata matches this hash. The original installed JAR is backed up locally under ignored `.tools/live-persistent-navigation/previous-packsage.jar`.

## Acceptance and limits

Automated source, persistence, provider-recreation and build acceptance passed. Installed restart and native-viewer acceptance passed for the destinations listed above. The final probe-free artifact was reinstalled with matching hashes and its saved stable JEI/Ponder links were reopened after a full process restart. Patchouli, FTB and legacy JEI/Ponder observations occurred earlier in this same live run with the same final navigation logic.

Live coverage is the existing fixture profile, not the full ATM10 profile or a remote multiplayer server. Resource-reload, hidden/removed/duplicate destinations, foreign-world refusal and navigation races retain automated coverage; they were not newly exercised as live negative scenarios. External web navigation was not live-tested in this pass.

Durable destinations re-resolve current content and do not update historical answers or their evidence. Recipes without a reliable canonical ID remain temporary. Removed, hidden, ambiguous or budget-inconclusive destinations remain unavailable; no alternative recipe, page or component is silently substituted. Existing Ponder component destinations and parseable canonical JEI/Patchouli destinations can migrate without changing the conversation format.

## Native identity recovery follow-up (2026-10-19)

The [normal-player prompt trials](2026-10-18-natural-prompt-stress-test.md) generated an Actually Additions Diamatine laser recipe link that opened before restart but expired afterward. Its saved destination was the explicitly temporary class/object-hash handle. [Installed binary research](../research/2026-10-19-native-recipe-identities.md) established that this mod registers the exact raw native recipe objects with JEI, losing JEI's default holder-name lookup while Minecraft still retains their real holder IDs.

Source through `f154242` recovers a native ID only for an exact object match to one current native holder ID, after a null JEI registry name. The lazy index is rebuilt for each capture, bounded at 65,536 holders and discarded on every incomplete exit. Explicit JEI names, duplicate rejection, current registration and visibility checks remain in force. No semantic or output matching was added; old hash-only destinations cannot be safely migrated.

The new runtime fixture first failed because discovery emitted a temporary target instead of the expected native ID. It now exercises recreated native objects, exact opening, explicit-name precedence without initializing the fallback, ambiguous aliases including a repeated first alias, equal-but-distinct objects, missing membership and exact/over-cap enumeration. Independent review found that an iterator `Error` could leave a partial index visible to later recipes in the same capture. A second regression reproduced that leak, and an atomic-completion/finally cleanup fixed it. Follow-up review confirmed the finding resolved.

Java 21 `gradlew test build` passed through `f154242` in 25 seconds: 50 suites, 577 tests, 573 passed, four existing skips, zero failures or errors. The extended `JeiProjectionTest` still contains 24 test methods. Existing deprecated-API and unchecked-operation warnings remain. Built JAR SHA-256 is `58EE4E7B14C6F1A2B83741145FC46B6A074EA96EE766A54FA84417496D409864`.

At this checkpoint the new build is not installed: the live prompt trials still run the preceding `7BF5D161E044CD21899A023AF4CA0D5A053F0612F1EDF0EB5CE5454EF37AD526` JAR, and `dist` retains the initial artifact above. Acceptance for the native fallback remains pending a newly generated laser citation opened after a full Minecraft restart. The old temporary Diamatine link is not that acceptance target.
