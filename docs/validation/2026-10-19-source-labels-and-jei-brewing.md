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

## Initial automated checks

- The focused `ScreenTextTest` and `JeiProjectionTest` run passes all 63 tests. Cases cover exact recorded labels and vessel identity, custom Markdown labels, mismatched generations, distinct Strength outputs, text-only potion-ID matching, advancing cursors, fresh source reads and persistent target refresh/open.
- Java 21 `gradlew.bat test build` passes: 643 tests, zero failures/errors and four existing skips across 54 suites. The built `build/libs/packsage-0.1.0.jar` has SHA256 `9546736FCE890B963379CF4B76A6858CD7C3FE01AF88B79BB72EE1C16451D439`.
- The long-scan fixture still checks per-call recipe visits, source bounds and emitted cursor validity. Its test-only retry ceiling increased from 16 to 128: projecting nonmatching ingredient facts spends at least 2,048 bytes per candidate against a 96 KiB shared budget, so reaching ordinal 4,095 can require roughly 90 continuations. No production cap was raised. Search latency is not established by this test.
- Follow-up fixture corrections advance the simulated client tick between new captures, retain an output in the eight-input budget case, and exercise oversized custom-type/slot data without claiming an unknown output matches an exact item filter. A thread stack confirmed the initial new fixture stall was waiting for a simulated tick; it was interrupted and corrected before the passing run.
- A read-only review found no additional material issue in the source-label, cursor and navigation changes. The subsequently repaired item-kind/potion capture path is covered by the passing focused and full suites.

## Installed acceptance

The player subsequently installed the candidate. At the initial inspection, the installed and distribution JARs both hashed to `9546736FCE890B963379CF4B76A6858CD7C3FE01AF88B79BB72EE1C16451D439`. The supplied screenshot shows readable generated brewing/potion labels. Source-chip clicking and a real JEI brewing viewer link were still unverified. Desktop checks were deferred while the player was using the PC, then resumed after the player explicitly granted desktop control while AFK.

### Continued JEI failure

Conversation `f23519dd-81a0-4a35-bda4-f83fbc0c61d9-96a03c798ec52dee.jsonl`, request `3b384c7d-4c56-4d5d-8003-94b9e522810a`, records the player's `JEI recipe?` follow-up. The UI reports 49 steps; replay contains 25 actual tool calls. All 11 JEI searches returned no records with `PARTIAL` coverage and valid continuations: two pages for `strength`, four for output item `minecraft:potion`, two for `blaze_powder`, and three for `minecraft:strength` plus output item `minecraft:potion`. There was no JEI tool error and no navigation target to open. The other calls rediscovered native evidence; one mistyped native cursor produced `INVALID_CURSOR`. The reply correctly described the JEI search as incomplete but did not fulfill the viewer request.

Code inspection confirms two reader concerns rather than an established model-capability failure. Every candidate is fully projected before the output filter, spending the shared budget on unrelated inputs and tooltip data. If projection exhausts the budget before the relevant output/text is decoded, a nonmatching filter's `continue` bypasses the exhaustion handler; the next cursor can advance past the unresolved recipe. Follow-up corrections must avoid full extraction for conclusively unrelated outputs and must never treat unread fields as a confirmed nonmatch. A candidate that cannot fit a fresh budget must terminate explicitly partial rather than produce an endlessly repeated cursor. Native localized potion-name matching and empty-page progress presentation remain separate open work.

### Reader and recorded-detail corrections

Exact output-item searches now inspect bounded detached OUTPUT slots before fully projecting matching recipes, reusing the same layout. Definitively unrelated candidates avoid full input/tooltip extraction. An undecoded nonmatch retains its source position for a fresh-budget retry; a repeated failure at that position terminates explicitly partial. Matching observed facts can still return records with incomplete details. Neither production extraction caps nor tool schemas changed.

Focused regressions failed before these corrections: expensive unrelated recipes hid a later Strength output, and budget exhaustion skipped a late text match. Additional cases cover an intrinsically oversized recipe and category/catalyst setup that cannot fit a fresh budget. Review identified a setup retry loop, then a synthetic budget diagnostic becoming a recipe record. Both findings were repaired; terminal setup diagnostics use the existing result detail with no recipe or navigation target. Follow-up read-only review found those findings closed.

The first agent-installed follow-up JAR, SHA256 `868BF9FFBA8E2A01EE72FE486FBC09E22AB624748A500ECB1CC1331F09CE8175`, still failed the short `JEI recipe?` request (`96b4a055-77ff-47f2-af0b-6994dc95974b`). Its 18 actual calls included 15 empty partial JEI pages. A fresh read of a pre-restart native reference was correctly rejected. Opening the actual JEI inventory viewer directly confirmed that its Brewing category contains the Strength recipe, blaze powder and awkward potion inputs; that direct keyboard action does not prove PackSage navigation.

Clicking the saved `Brewing: Strength` chip selected its exact original source, but exposed a separate preview bug: unknown quantities are JSON null, and `getAsString()` discarded the entire saved preview. Two null guards now retain known inputs/outputs and show unknown quantities as `?`. A new regression failed before the fix and passed afterward.

### Installed diagnostic candidate

Java 21 `gradlew.bat test build` passed 644 tests across 54 suites, with zero failures/errors and four existing skips. Build, distribution and installed JAR SHA256 all matched `D8F31793568D6432C4A6DD9DE988AC19281B39D0558D2E40077441A587F80155`. This candidate also adds bounded pending category/recipe positions through the existing result detail, preserving opaque tool cursors and compatible snapshot constructors.

The recorded-source live check passes on this candidate. Clicking `Brewing: Strength` opens the original source `brewing:potion:56:0:dd2c10d90e851649e418c479d0d2695c3eb9d551280029151ec3ccab0e67f48f`, observed at `2026-10-19T13:58:56.385718200Z`. The screen retains Potion ×1, Potion ×? and Blaze Powder ×?, with partial coverage and its recorded provenance.

A fresh Luna Extra high conversation `6ffc905a-edc0-4900-88e3-8594d6314ff2-b695879f55965604.jsonl`, request `c3cd75c3-d771-49ce-a34e-e6136bebb3af`, asked: "Show me the JEI brewing recipe for a Potion of Strength in this pack." The agent stopped this latency trial after about five minutes and 34 actual tool calls (61 UI activity steps), before a final answer. This cancellation is not a completed model-response result. All 18 completed JEI calls returned empty partial pages with advancing positions: seven Strength pages reached category 0, recipe 87; nine exact potion-output pages reached category 0, recipe 1,404; two blaze-powder pages reached category 0, recipe 24. No source position stalled. Native fallback did find brewing facts, but it supplied no JEI viewer target.

These positions establish category ordering as a practical discovery bottleneck. The [bounded brewing-first plan](../plans/2026-10-19-jei-potion-discovery.md) addresses it without claiming incomplete discovery means no recipe exists. The actual PackSage viewer link remains pending.
