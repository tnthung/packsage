# JEI Output Focus Implementation Plan

**Goal:** Use JEI's recipe index for output searches of items without JEI-distinguished variants.

**Spec:** [JEI output focus for recipe search](../specs/2026-10-22-jei-output-focus.md).

**Tech Stack:** Java 21, NeoForge 21.1 / Minecraft 1.21.1, JEI 19.27.0.340, JUnit 5. Tests run with `JAVA_HOME=D:\Project\packsage\.tools\jdk\jdk-21.0.12.1`.

## Tasks

- [x] **F1. Failing tests.** In `JeiProjectionTest`, add a focused-runtime fixture: an ingredient manager listing item stacks, a stack helper returning recipe identities, a focus factory, and recipe and category lookups that return only focused recipes when a focus is applied. Cover: a variant-free output receives one OUTPUT focus on both lookups and finds a recipe beyond the scan budget on the first page; an output with differing listed identities applies no focus; a runtime without an ingredient manager applies no focus.
- [x] **F2. Focus decision.** In `PackSageJeiPlugin`, build `focuses` from `outputItem` as the spec describes, falling back to an empty list on missing helpers or runtime failures. Run the focused JEI suite, including the existing potion regression.
- [x] **F3. Full build and tests.**
- [x] **F4. Live checks.** Rerun A1, A2 and A3 of the [default-source checks](../validation/2026-10-22-jei-default-recipe-source.md) on the minimal fixture with JEI, and record the results in a new validation record.
- [x] **F5. Records.** Correct the tool roadmap's JEI output-focus item, update the adapter feature checklist and `CHANGELOG.md`.
