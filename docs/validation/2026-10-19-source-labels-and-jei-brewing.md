# Source labels and JEI brewing lookup

Date: 2026-10-19.

Follow the [chat source contract](../specs/2026-10-13-modern-chat-ui.md), [brewing contract](../specs/2026-10-19-brewing.md) and [persistent navigation contract](../specs/2026-10-18-persistent-navigation.md). This correction addresses the player's unreadable source-chip screenshot and failed JEI brewing request without changing source identities or adding gameplay actions.

## Reproduced paths

Generated native source chips used `displayName(sourceHandle)`. Brewing handles include rule/variant positions and a fingerprint, so that fallback exposed the fingerprint as a label. Potion registry handles retained an extra namespace. Successful saved tool results already retain the exact source, title and component facts needed for readable labels; old answers need no storage migration. Explicit Markdown labels must remain unchanged, with the original recorded source and validated target retained.

The second screenshot is recorded in the original New World's conversation `e2f03fa1-d7ef-441d-bce1-9fd0c8119c09-64f6fbd0c28cd861.jsonl`. Request `1a0acd7e-2a49-4ec2-a214-9e4937f006d0` asked "you cant show the jei?". Its exact-output JEI query for `minecraft:potion` returned complete with no records or continuation. Several text queries returned unfinished discovery; only one `potion` continuation was followed, reaching an unrelated crafting recipe. No brewing navigation target was returned, so this trace does not test the Open action.

A read-only bytecode check compared the installed `jei-1.21.1-neoforge-19.64.0.455.jar` and compatible baseline 19.27.0.340. In both, JEI's vanilla plugin registers a potion-contents subtype interpreter. `StackHelper.getUidForStack` includes subtype data, and `RecipeMap` indexes focused recipe queries by that subtype-aware UID. PackSage constructed a bare potion stack for the component-agnostic `outputItem` filter; its UID therefore excluded Strength potion outputs. Public wildcard IDs are not a wildcard recipe-focus API, and ingredient-manager collections have no bounded filtered enumeration API.

## Intended correction and acceptance

- Derive generated brewing/potion labels from exact recorded facts, checking provider, handle, scope, observed time and generation. Keep missing or malformed snapshots generic and keep explicit Markdown labels intact. Memoize labels with the displayed view so rendering does not repeatedly parse full tool-result JSON.
- Apply the item-ID output filter to bounded projected JEI output facts across component variants, preserving existing pagination limits, fresh membership/visibility checks and exact navigation identities. Literal text can inspect returned ingredient/component facts as well as recipe metadata.
- Exercise meaningful failing regressions before fixes, followed by focused suites and the full Java 21 test/build. Record installed source-chip rendering and a real brewing viewer link separately from source/build checks.

The focused RED run completed 63 tests with three expected assertion failures: generated labels retained the brewing fingerprint/extra potion namespace; the provider omitted component-matching potion projections; and the real plugin capture returned zero records for the item-ID potion query. Fixture construction/compilation errors were corrected before counting this run as behavioral RED. No prompt change was made.

## Implemented behavior

Generated native citations now use exact saved facts for labels such as `Brewing: Strength`, `Brewing: Splash Potion (Strength)` and `Potion effects: Long Strength`. Missing or mismatched records use generic labels. Explicit Markdown labels, recorded source bindings and validated navigation targets remain intact. Labels are cached with each immutable displayed turn; existing saved answers need no migration.

JEI item-ID output searches now scan bounded projected outputs across component variants instead of focusing a bare stack. Known ItemStack ingredients are normalized to `item`; a resolvable potion holder contributes its actual registry ID as `components.potion`, within the existing shared extraction budget. Literal text searches inspect ingredient facts even without an output filter. Component-bearing stacks retain partial coverage for omitted details. Current source reads recheck output identity, and persistent `recipe-v1` targets retain the plugin's fresh resolution rather than being rejected for differing from an old source handle.

## Automated checks

- The focused `ScreenTextTest` and `JeiProjectionTest` run passes all 63 tests. Cases cover exact recorded labels and vessel identity, custom Markdown labels, mismatched generations, distinct Strength outputs, text-only potion-ID matching, advancing cursors, fresh source reads and persistent target refresh/open.
- Java 21 `gradlew.bat test build` passes: 643 tests, zero failures/errors and four existing skips across 54 suites. The built `build/libs/packsage-0.1.0.jar` has SHA256 `9546736FCE890B963379CF4B76A6858CD7C3FE01AF88B79BB72EE1C16451D439`.
- The long-scan fixture still checks per-call recipe visits, source bounds and emitted cursor validity. Its test-only retry ceiling increased from 16 to 128: projecting nonmatching ingredient facts spends at least 2,048 bytes per candidate against a 96 KiB shared budget, so reaching ordinal 4,095 can require roughly 90 continuations. No production cap was raised. Search latency is not established by this test.
- Follow-up fixture corrections advance the simulated client tick between new captures, retain an output in the eight-input budget case, and exercise oversized custom-type/slot data without claiming an unknown output matches an exact item filter. A thread stack confirmed the initial new fixture stall was waiting for a simulated tick; it was interrupted and corrected before the passing run.
- A read-only review found no additional material issue in the source-label, cursor and navigation changes. The subsequently repaired item-kind/potion capture path is covered by the passing focused and full suites.

## Installed acceptance

Pending: the running Minecraft instance still has the earlier JAR. The current desktop is in use by another game, so installation and live input are waiting for permission to take focus. The candidate has not yet demonstrated source-chip rendering or a real JEI brewing viewer link in the installed game. Native localized potion-name matching and empty-page progress presentation remain separate open work.
