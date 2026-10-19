# Curios equipment implementation

Implement the approved [equipment contract](../specs/2026-10-19-curios-equipment.md) through the existing player target and [behavior-driven tool contract](../specs/2026-10-14-behavior-driven-tools.md).

1. Verify the optional Curios API against the installed 1.21.1 artifact and an earlier release in its compatible major family. Use only current functional stack handlers; record the evidence under `docs/research/`.
2. Add meaningful failing native inventory tests for Curios section selection, distinct type/index locations and unavailable accessory coverage. Observe the failure before implementation.
3. Add one optional Curios equipment adapter that reads live functional handlers and resolves a current type/index. Share it with the native inventory reader and register its status for the existing adapter list. Keep optional API access isolated so a missing mod remains supported.
4. Extend native capture, section filtering, source scope and reference lookup without changing the model tool or player target. Preserve vanilla records if Curios fails. Add focused adapter tests for current stacks, cosmetics exclusion, unsupported versions and failures.
5. Update the tool description and hardcoded adapter availability/list entries. Run focused tests, then `gradlew test build`; refresh `dist/packsage.jar` and its metadata hash from the verified artifact before the player's installation. Manually inspect style and finish with `git diff --check`.
6. Update the roadmap, changelog and validation evidence. Commit written steps under the repository's [working rules](../../AGENTS.md#working-rules). Leave installation and live-world acceptance explicitly pending unless requested.

One implementation worker owns production/test changes. The parent owns documentation, automated runs and final validation; no formatting tools are permitted.
