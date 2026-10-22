# JEI Default Recipe Source Implementation Plan

**Goal:** Make an unqualified `search_recipe` read one source, JEI when it is available and Minecraft's recipes otherwise.

**Spec:** [JEI as the default recipe source](../specs/2026-10-22-jei-default-recipe-source.md).

**Tech Stack:** Java 21, NeoForge 21.1 / Minecraft 1.21.1, JUnit 5. Tests run with `JAVA_HOME=D:\Project\packsage\.tools\jdk\jdk-21.0.12.1`.

## Tasks

- [x] **D1. Default source selection.** In `KnowledgeService.Discovery`, choose the sources for `search_recipe` as the first recipe source, in registration order, whose provider is enabled. Adapters that are not `KnowledgeProvider`s count as enabled. Document discovery is unchanged. Test first in `KnowledgeServiceTest`: an enabled first recipe source is the only one queried; a disabled first source is skipped, with COMPLETE coverage and no note; a qualified search still reaches either source; a cursor issued for one default is rejected after the default changes.
- [x] **D2. Registration order.** In `ClientAssistant.knowledge`, register the JEI provider before the native provider. Check `ClientAssistantTest` for order-dependent expectations and update them only where order is the subject.
- [x] **D3. Contract text.** Update the `search_recipe` and combined-discovery paragraphs of the [behavior-driven tool design](../specs/2026-10-14-behavior-driven-tools.md) to link this spec.
- [x] **P1. Model-facing text.** Update the `search_recipe` description in `PackSageTools` and the recipe-source sentences in `GroundingInstructions`, reviewing the whole prompt as one policy. Update wording tests only where they assert the replaced text. Live iteration through the developer override is left to the player's in-game round.
- [x] **V1. Full build and tests.** Run the full test suite and build.
- [x] **R1. Records.** Update the [tool roadmap](../roadmap/2026-10-13-todo.md), the [adapter feature checklist](../roadmap/2026-10-21-adapter-feature-todo.md) and `CHANGELOG.md`. In-game acceptance stays unchecked until it is observed.
