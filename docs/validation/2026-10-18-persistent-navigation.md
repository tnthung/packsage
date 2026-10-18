# Persistent navigation validation

Date: 2026-10-18.

Scope: [specification](../specs/2026-10-18-persistent-navigation.md), [implementation plan](../plans/2026-10-18-persistent-navigation.md), [JEI identity research](../research/2026-10-18-persistent-jei-navigation.md) and [guide identity research](../research/2026-10-18-persistent-guide-navigation.md).

## Automated evidence

Source through `b7f47a0` passed portable Java 21.0.12.1 `./gradlew.bat test build` with Gradle 8.11.1, Minecraft 1.21.1 and NeoForge 21.1.202. The full XML reports contain 576 tests across 50 suites: 572 passed, four existing skips, zero failures and zero errors. Existing JEI deprecated-API and unchecked-operation warnings remain.

- The focused baseline passed before implementation. New regressions first failed on missing guide resolver APIs, missing durable recipe IDs, old Patchouli lifecycle binding and old-scope web refusal.
- `KnowledgeServiceTest` (14 tests) includes a conversation JSONL round trip loaded by a new store, then resolves the saved destination through a new provider with a different source handle and current scope. The historical target remains unauthorized for direct opening, and navigation does not create model evidence references. Foreign-provider/scope results and late completion cannot authorize opening.
- `PonderGuideReaderTest` (17) and `GuideDocumentProviderTest` (13) cover registration replacement/reordering, canonical component IDs, changed scope, current client-executor checks, unavailable/malformed destinations and zero storyboard compilation during destination resolution. The viewer still opens a component, not an exact scene.
- `JeiProjectionTest` (24) recreates plugin runtime state and recipe objects, changes play-session/resource/runtime scope, resolves the same category/canonical recipe ID and opens the current exact object. It covers legacy canonical handles, duplicate/missing/hidden/malformed targets, category-only restoration, temporary no-ID handles and ordinary request progression. A real custom-category discovery fixture first reproduced lost canonical IDs, then passed after opaque projections retained those IDs; current visibility is still revalidated when resolving/opening.
- The JEI category restoration regression reproduced a null recipe being passed to recipe-handle construction. Category rehydration now creates a category handle and opens `showTypes`. Navigation traversal shares the existing work budget. Test fixtures advance client ticks between lookups, matching the existing one-window-per-tick throttle.
- `PatchouliProviderTest` (19) covers lifecycle-free page destinations and canonical legacy lifecycle migration after reload, current entry/page availability, removal and malformed page/lifecycle values. Existing hidden/locked page and scope checks still pass. `FtbProviderTest` (18) includes saved quest navigation with a recreated provider and rejects removed quests.
- `AssistantRuntimeTest` (34) verifies saved same-world URLs after conversation reload and retains foreign-world read-only refusal, historical source membership, session-selection and late-navigation-result checks.

Manual style inspection followed the existing code conventions. No formatter was used. Relative documentation links and `git diff --check` passed. The two pre-existing uncommitted validation documents were preserved.

Independent review covered shared resolution, Ponder/Patchouli, saved URLs and the final JEI implementation through `b7f47a0`. Its custom-category identity finding was reproduced and corrected. The final review found no remaining actionable correctness issue, including the category restoration, shared-budget, temporary-link request progression, current registration/visibility and late-result paths.

## Artifact

Built JAR: `build/libs/packsage-0.1.0.jar`. Distribution: `dist/packsage.jar`; matching metadata is in `dist/build-metadata.json`.

Both JARs have SHA-256 `9E5B228C7B61ED7A2BADD7ED283E36E49FE9EB4AA20847AE6C2680F7CF0971C1`.

## Acceptance and limits

Automated source, persistence, provider-recreation and build acceptance passed. This task did not install the JAR or run a new Minecraft UI session; real-process restart and rendered viewer acceptance remain unobserved. Simulated current registries and conversation-file reloads are the automated evidence, not live-game proof.

Durable destinations re-resolve current content and do not update historical answers or their evidence. Recipes without a reliable canonical ID remain temporary. Removed, hidden, ambiguous or budget-inconclusive destinations remain unavailable; no alternative recipe, page or component is silently substituted. Existing Ponder component destinations and parseable canonical JEI/Patchouli destinations can migrate without changing the conversation format.
