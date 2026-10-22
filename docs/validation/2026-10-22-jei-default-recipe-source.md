# JEI default recipe source validation

Date: 2026-10-22.

Validates the [JEI default recipe source](../specs/2026-10-22-jei-default-recipe-source.md) specification, implemented by its [plan](../plans/2026-10-22-jei-default-recipe-source.md).

## Automated checks

`gradlew build` passed with 853 tests.

- `KnowledgeServiceTest.unqualifiedRecipeSearchReadsOnlyTheFirstAvailableRecipeSource`: with the first recipe source available, an unqualified search queries only that source and is COMPLETE. With it unavailable, only the next source is queried, the result is COMPLETE with no source note, and a qualified search of the unavailable source fails as unavailable. Document discovery still combines sources.
- `KnowledgeServiceTest.recipeContinuationIsRejectedAfterTheDefaultSourceChanges`: a continuation issued for one default source is rejected with `INVALID_CURSOR` after the default changes.
- `AssistantIntegrationTest`: a second recipe source is reached through a provider-qualified search, and a failing default source returns a PARTIAL result naming it while the other source stays reachable by provider.
- `PackSageToolsTest`: the `search_recipe` description names the default source.

## In-game acceptance

Pending. The prompt text has not yet been iterated through the developer override.

- [ ] With JEI installed, an unqualified recipe question makes JEI recipe searches and no native ones, and offers a JEI link that opens the recipe.
- [ ] Without JEI, the same question is answered from Minecraft's recipes, with no JEI-unavailable note.
- [ ] The installed default prompt, with the developer override cleared, gives the same tool choices.
