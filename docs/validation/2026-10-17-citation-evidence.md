# Recorded citation labels and evidence previews

Date: 2026-10-17

Requirements: [modern chat presentation](../specs/2026-10-13-modern-chat-ui.md). Plan: [step 7](../plans/2026-10-13-modern-chat-ui.md). Player checks: [steps 9-11](../testing/2026-10-13-modern-chat-ui.md).

The player's screenshot showed three repeated, noninteractive `[game]` labels. The matching saved minimal-profile answer already contained descriptive Markdown labels for inventory, planks and sticks. The renderer discarded those labels, required a navigation target for native controls, and only allowed config citations to open recorded-source details. Citation hover handling was absent.

- The saved records supplied an inventory item/count/location and native recipe inputs, alternatives and outputs. Those recorded values are sufficient for inspection without new model requests or live game reads.
- Regression command: `gradlew.bat test --tests dev.packsage.client.ScreenTextTest.nativeMarkdownCitationsKeepTheirDescriptionsAndRecordedSources`. It failed before implementation: expected the three descriptive source labels, but received no bound source spans.
- The fix preserves descriptive link labels, binds recorded sources across direct/grouped/Markdown references, and shows a cached bounded hover preview. Non-navigable recorded sources open read-only details; validated navigation keeps priority for navigable sources.
- Source inspection retains exact provider, handle, scope, observation time and generation matching. Tests cover recorded inventory facts, actual recipe alternative groups and quantities, malformed payload rollback, missing or mismatched observations, source membership, existing config snapshots and navigation binding.
- Focused screen/text/details checks: 68 passed, zero failures. Full `gradlew.bat test build`: 517 tests in 49 suites, 513 passed, 4 skipped, zero failures or errors.
- Bounded read-only integration review found no actionable regression. The compiler variable-name collision and the old generic-Quest-label test expectation were corrected in separate commits before final validation. Manual style and `git diff --check` checks passed; no formatting tools were used.
- Tested source: `63d5d18`. JAR: `build/libs/packsage-0.1.0.jar`, 4,896,018 bytes. SHA-256: `E02A08B530EF6F98EDFF98F22E3D9DDF586BB5AB0824552F32120113BEC3023C`.
- With Minecraft closed, installed the matching JAR into `C:\Users\hung1\curseforge\minecraft\Instances\Packsage\mods\packsage.jar`; its hash matches the built JAR and the profile contains one PackSage JAR.
- Actual Minecraft hover rendering and click-through remain pending the linked player checks. The saved answer can be reopened to verify this correction; answer arithmetic is not established by inspecting citation presentation alone.

The existing developer-prompt validation edits and untracked prompt-tally retest document remain outside these commits.
