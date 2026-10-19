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

The focused RED run completed 63 tests with three expected assertion failures: generated labels retained the brewing fingerprint/extra potion namespace; the provider omitted component-matching potion projections; and the real plugin capture returned zero records for the item-ID potion query. Fixture construction/compilation errors were corrected before counting this run as behavioral RED. Implementation and final acceptance remain in progress. No prompt change is justified by the failed lookup alone.
