# JEI Recipe Planning Implementation Plan

**Goal:** Plan recipe routes from JEI when it is available, following inputs through JEI's indexed output lookups.

**Spec:** [JEI recipe planning](../specs/2026-10-22-jei-recipe-planning.md).

**Tech Stack:** Java 21, NeoForge 21.1 / Minecraft 1.21.1, JEI 19.27.0.340, JUnit 5. Tests run with `JAVA_HOME=D:\Project\packsage\.tools\jdk\jdk-21.0.12.1`.

## Tasks

- [ ] **J1. Planner source.** Give `RecipePlanner` a small source interface (`available`, `recipeOutputs`) that `KnowledgeIndex` implements. No behavior change; the existing planner tests pass unchanged.
- [ ] **J2. JEI planner.** Test first with a fake `SnapshotSource`: a two-level route through a consumed input, one lookup per item, the 64-lookup and 16-record bounds making the plan PARTIAL, brewing batches withheld, normalized identities, JEI references and targets kept. Then implement `JeiProvider.planRecipe` with argument validation matching native, provider selection, one client-thread task and record normalization.
- [ ] **J3. Dispatch and override.** Add the optional `provider` to the `plan_recipe` schema. Native declines another selected provider. `ReflectiveProvider` forwards `planRecipe`; `StatusProvider` returns its unavailable failure when selected and declines otherwise. Tests cover the default, both overrides and missing JEI.
- [ ] **J4. Model-facing text.** Update the `plan_recipe` description and any instruction sentence that says planning reads only bounded native recipes. Update wording tests only where they assert replaced text.
- [ ] **J5. Full build and tests.**
- [ ] **J6. Live checks.** On the minimal fixture, with and without JEI, run the acceptance questions and record the results in a validation record.
- [ ] **J7. Records.** Update the tool roadmap's `plan_recipe` section, the adapter feature checklist and `CHANGELOG.md`.
