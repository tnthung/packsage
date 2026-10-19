# Curios equipment and qualified citation validation

Scope: the approved [equipment contract](../specs/2026-10-19-curios-equipment.md), [implementation plan](../plans/2026-10-19-curios-equipment.md) and [API research](../research/2026-10-19-curios-equipment.md).

The supplied ATM10 screenshot exposes two separate problems. The stored answer contains an untranslated provider-qualified temporary citation. The inventory call is `search_inventory` with `target.type=player` and `limit=32`; its complete coverage is explicitly limited to direct vanilla player slots and equipment, so its inability to list equipped Curios is accurate for that build.

The citation regression first reproduced an unexpanded `[native:ref:...]`, then passed all 15 focused decorator tests. Independent review found that an alias-shaped Markdown label could consume the opening bracket before the parser recognized the destination. Both forms require exact current-request alias/provider validation rather than fuzzy source selection.

The native inventory RED run completed ten tests with one expected failure: `curiosSectionFiltersKeepSameIndexTypesDistinct` expected Ring and Necklace but received no records. The separate partial-coverage fixture retained its vanilla item.

## Implementation and automated acceptance

Commit `61cbf74` adds the optional reflected Curios adapter and contributes its functional stacks to the existing native player inventory reader. Sections retain the Curios slot type and local index; records, filters, pagination and live references use the existing contracts. The actual loaded Curios version and per-capture coverage are reported, while cosmetic slots remain excluded. The adapter list contains one Curios entry. API absence, unsupported versions, failed access and traversal bounds retain honest coverage; later reads retry transient failures.

The first focused run caught a return-type compilation error in the unavailable path; `6ed94b8` corrected it. The following 58-test run exposed the expected Markdown parsing regression and a changed vanilla scope-description phrase. `a52f359` preserves the vanilla description while reporting included Curios equipment, and `1ce638f` defers bracket interpretation until it distinguishes a standalone citation from a Markdown label. All 58 focused tests then passed, including every affected stream split and canonical completed replay.

Independent review identified two additional defects: mixed Curios/unknown section filters discarded readable Curios results, and another request's latest adapter failure could override an already complete capture. Test commit `848b346` reproduced both deterministically: expected partial or complete coverage, but received unavailable. `3c10b39` fixes the mixed-section guard and uses each capture's own coverage metadata in both initial and continuation callbacks. All 60 focused tests then passed. The review's bounded recheck found no remaining actionable defect in these corrections or the citation parser.

The first full run reached 616 tests and found one outdated runtime-availability fixture that omitted the new Curios entry. Test-only commit `4b679b9` updates that expected map. Java 21 `gradlew test build` then completed successfully in 25 seconds: 52 suites, 616 tests, 612 passed, four existing skips, zero failures and zero errors. Existing JEI deprecation and unchecked-operation warnings were observed during recompilation; no formatting tools were used.

The built artifact is `build/libs/packsage-0.1.0.jar`, SHA-256 `47EDA8CF4E70E9DDBEAC47528048C2BCCEBB0644C8660AF6CC9178DF23B3F92E`. Documentation, implementation, regressions and corrections are separate commits under the repository working rules. The citation correction applies to newly translated responses; the old saved malformed alias is not resolved by guessing a source.

## Installed acceptance

The automated acceptance checkpoint left installed Minecraft acceptance pending and did not modify the installed JAR, game settings, inventory or saved conversations. The requested live checks follow the [player checks](../testing/2026-10-19-curios-equipment.md) and are recorded below. API inspection and automated fixtures are not live-world synchronization proof.

### Distribution correction before live testing

The requested minimal-instance test initially found installed SHA-256 `3CFFEAFDFF9C803D8DD4F2575658DA8CA364FEB45811374E446C9E82FB8803BD`, matching the stale distribution rather than the verified build. Both old JARs lacked `CuriosInventoryAdapter.class`; their native inventory and citation parser classes also differed from the new build. No acceptance test of the new capability was claimed from that process.

The distribution now matches verified SHA-256 `47EDA8CF4E70E9DDBEAC47528048C2BCCEBB0644C8660AF6CC9178DF23B3F92E`; only the checksum field changed in its ignored build metadata. After the player exited Minecraft, minimal-process absence was verified, the corrected JAR was copied into the minimal instance, and its installed hash was verified equal. The minimal profile was then relaunched for the requested live checks. Settings remain unchanged; their baseline SHA-256 is `50EFFFD1284FBD4C99B0606F8120880542E3EE9EA6CBA3BCD80D0B47DF411D0B`.
