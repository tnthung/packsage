# Persistent navigation implementation plan

**Goal:** Reopen saved recipe, Ponder, guide, quest and web destinations when their current sources still exist and are accessible.

**Architecture:** Preserve serialized historical source binding and use `targetId` as the durable destination. Add `KnowledgeProvider.refreshNavigation(NavigationTarget, Scope)` to resolve current targets independently of the old evidence locator; `KnowledgeService` authorizes only the returned current target. Provider-specific code owns identity migration and fresh registration/access checks.

**Tech stack:** Java 21, Minecraft 1.21.1, NeoForge 21.1.202, JEI 19.27.0.340, optional Ponder/Patchouli/FTB integrations, Gradle 8.11.1 and JUnit 5.

**Spec:** [Persistent source navigation](../specs/2026-10-18-persistent-navigation.md).

## Constraints

- Preserve the existing serialized turn format and the public model tool signatures.
- Keep source evidence historical, cross-world conversations read-only, and current scope/access checks mandatory.
- No formatting tools, unrelated edits or installation into an active Minecraft instance. Preserve existing uncommitted validation documents.
- Parent owns shared navigation, runtime, Patchouli and integration tests. Separate workers own JEI and Ponder/guide files. Only the parent mutates Git or runs Gradle; commit each written work step using the staged-index commit-message skill before waiting for passing tests.

## Work steps

- [x] Research JEI and guide destination identities in parallel, documenting primary sources under `docs/research/`.
- [x] Add failing `KnowledgeServiceTest` coverage for a provider returning a new source handle, invalid scope/provider results and late completion. Add the default provider resolver and route service refresh through it while preserving bounded opening authorization.
- [x] Add failing Ponder/guide tests for re-created registrations and legacy component destinations; implement reader/provider resolution on the client executor without storyboard compilation.
- [ ] Add failing JEI tests for a recreated runtime, stable category/recipe destinations, legacy migration, duplicate IDs and visibility/removal; implement bounded current-registry resolution and retain temporary-only behavior where identity is unavailable.
- [x] Add failing Patchouli tests for lifecycle-independent destinations and legacy migration; resolve current page targets and keep existing viewer access checks. Verify FTB's stable-ID path with a recreated provider.
- [x] Add saved-conversation integration regressions and allow validated historical web URLs in the active world's conversation. Keep foreign-world refusal and navigation race coverage.
- [ ] Run focused suites, full `gradlew.bat test build`, independent review and targeted corrections. Record exact automated evidence and remaining live limits under `docs/validation/`.

## Review focus

- A refreshed target may have a different source handle, but must belong to the requested provider and exact current scope.
- Canonical recipe IDs may be absent or duplicated; neither case permits selecting an arbitrary recipe.
- Legacy locators must be parsed canonically, without weakening current existence/access checks.
- Ponder component destinations must survive new storyboard objects without becoming scene deep links.
- An old same-world answer may navigate, but a foreign-world answer or late result may not open a destination.
